package com.jarvismini.devtools.autobuild

import android.accessibilityservice.AccessibilityService
import android.os.Bundle
import android.util.Log
import android.view.accessibility.AccessibilityNodeInfo
import com.jarvismini.devtools.autobuild.models.ExtractionMode
import kotlinx.coroutines.delay
import java.io.File

/**
 * All UI interaction with the Claude Android app.
 *
 * Three extraction modes:
 *
 *   CODE_BLOCK
 *     Walks node tree for HorizontalScrollView → TextView children.
 *     Joins all blocks and writes /sdcard/ai-automation/ai-output.txt directly.
 *
 *   DOWNLOADED_FILE
 *     1. Fires Termux: build_runner.sh --snapshot
 *        (records what files already exist in /sdcard/Download/)
 *     2. Finds and taps ALL visible download buttons in Claude's UI sequentially.
 *     3. Fires Termux: build_runner.sh --assemble-downloads
 *        (Termux detects new files, assembles ai-output.txt in file-header format)
 *     4. Polls for /sdcard/ai-automation/downloads_assembled.flag.
 *     Returns true once flag exists — OrchestrationController then moves to TRIGGERING_BUILD.
 *     NOTE: actual file reading / ai-output.txt assembly is entirely done by Termux,
 *     not by this app. No clipboard. No size limits.
 *
 *   PLAIN_TEXT
 *     Grabs the largest TextView in the window, writes ai-output.txt directly.
 *
 * Node content-desc values below are PLACEHOLDERS pending uiautomator dump verification.
 * After dumping the real tree, update DOWNLOAD_BUTTON_DESCS with confirmed values.
 */
class UIWatcherModule(val service: AccessibilityService) {

    companion object {
        private const val TAG = "DevTools:UIWatcher"

        const val CLAUDE_PKG           = "com.anthropic.claude"
        const val STABILITY_WINDOW_MS  = 2_500L
        const val RESPONSE_TIMEOUT_MS  = 120_000L
        const val POST_TAP_DELAY_MS    = 900L
        const val FILE_ATTACH_DELAY_MS = 1_600L

        // How long to wait for Termux to finish assembling ai-output.txt
        const val ASSEMBLY_TIMEOUT_MS  = 60_000L
        const val ASSEMBLY_POLL_MS     = 1_000L

        val STREAMING_INDICATORS = setOf("●", "Thinking…", "Thinking...", "▌")

        const val ADD_TO_CHAT_DESC = "Add to chat"
        const val SEND_DESC        = "Send"

        // ── PLACEHOLDERS — update after uiautomator dump ──────────────────────
        // These are the content-desc values tried IN ORDER when looking for
        // download buttons. The first match per node wins.
        val DOWNLOAD_BUTTON_DESCS = listOf(
            "Download file",
            "Download",
            "download"
        )
        // File extensions that count as code output from Claude.
        // Any new file in /sdcard/Download/ matching these is picked up by Termux.
        val CODE_EXTENSIONS = setOf(
            "kt", "java", "py", "txt", "xml", "json", "gradle",
            "kts", "sh", "cpp", "c", "h", "ts", "js", "md"
        )
        // ─────────────────────────────────────────────────────────────────────

        val ASSEMBLED_FLAG = File("/sdcard/ai-automation/downloads_assembled.flag")
    }

    @Volatile var lastClaudeEventMs: Long = 0L

    fun onAccessibilityEvent(packageName: String?) {
        if (packageName == CLAUDE_PKG) lastClaudeEventMs = System.currentTimeMillis()
    }

    // ── Response completion ───────────────────────────────────────────────────

    suspend fun waitForResponseComplete(timeoutMs: Long = RESPONSE_TIMEOUT_MS): Boolean {
        val deadline = System.currentTimeMillis() + timeoutMs
        delay(1_500L)
        while (System.currentTimeMillis() < deadline) {
            val elapsed = System.currentTimeMillis() - lastClaudeEventMs
            if (elapsed >= STABILITY_WINDOW_MS && !isStreamingIndicatorVisible()) {
                Log.d(TAG, "Response stable for ${elapsed}ms — complete")
                return true
            }
            delay(500L)
        }
        Log.w(TAG, "waitForResponseComplete timed out")
        return false
    }

    private fun isStreamingIndicatorVisible(): Boolean {
        val root = service.rootInActiveWindow ?: return false
        return findNodeByText(root, STREAMING_INDICATORS) != null
    }

    // ── Unified extraction ────────────────────────────────────────────────────

    /**
     * Extracts code according to [mode] and ensures ai-output.txt is ready on disk.
     * Returns true on success, false if extraction failed (caller will retry).
     */
    suspend fun extractAndWrite(
        root: AccessibilityNodeInfo?,
        mode: ExtractionMode,
        termux: TermuxBridgeModule
    ): Boolean = when (mode) {

        ExtractionMode.CODE_BLOCK -> {
            val blocks = extractCodeBlocks(root)
            if (blocks.isEmpty()) {
                Log.w(TAG, "CODE_BLOCK: no blocks found")
                false
            } else {
                writeAiOutput(blocks.joinToString("\n\n// ===== next block =====\n\n"))
                true
            }
        }

        ExtractionMode.DOWNLOADED_FILE -> {
            downloadViaTermux(termux)
        }

        ExtractionMode.PLAIN_TEXT -> {
            val text = largestTextViewText(root)
            if (text.isNullOrBlank()) {
                Log.w(TAG, "PLAIN_TEXT: no text found")
                false
            } else {
                writeAiOutput(text)
                true
            }
        }
    }

    // ── CODE_BLOCK extraction ─────────────────────────────────────────────────

    private fun extractCodeBlocks(root: AccessibilityNodeInfo?): List<String> {
        root ?: return emptyList()
        val blocks = mutableListOf<String>()
        collectCodeBlocks(root, blocks)
        if (blocks.isEmpty()) {
            Log.w(TAG, "No HorizontalScrollView blocks found, trying largest TextView")
            largestTextViewText(root)?.takeIf { it.isNotBlank() }?.let { blocks.add(it) }
        }
        Log.d(TAG, "Extracted ${blocks.size} code block(s)")
        return blocks
    }

    private fun collectCodeBlocks(node: AccessibilityNodeInfo, out: MutableList<String>) {
        if (node.className?.toString() == "android.widget.HorizontalScrollView") {
            for (i in 0 until node.childCount) {
                val child = node.getChild(i) ?: continue
                val text = child.text?.toString()
                if (!text.isNullOrBlank()) {
                    out.add(text)
                    Log.d(TAG, "Code block (${text.length} chars): ${text.take(60)}…")
                }
            }
            return
        }
        for (i in 0 until node.childCount) {
            collectCodeBlocks(node.getChild(i) ?: continue, out)
        }
    }

    private fun largestTextViewText(root: AccessibilityNodeInfo?): String? {
        var best: String? = null
        fun traverse(n: AccessibilityNodeInfo?) {
            n ?: return
            if (n.className?.toString()?.endsWith("TextView") == true) {
                val t = n.text?.toString() ?: ""
                if (t.length > (best?.length ?: 0)) best = t
            }
            for (i in 0 until n.childCount) traverse(n.getChild(i))
        }
        traverse(root)
        return best
    }

    // ── DOWNLOADED_FILE extraction ────────────────────────────────────────────

    /**
     * Full sequence for DOWNLOADED_FILE mode:
     *   1. Termux snapshots /sdcard/Download/ (so it knows what was there before)
     *   2. App taps every visible download button in Claude's UI
     *   3. Termux assembles ai-output.txt from the new files
     *   4. App waits for downloads_assembled.flag
     */
    private suspend fun downloadViaTermux(termux: TermuxBridgeModule): Boolean {
        // Step 1 — snapshot before tapping anything
        ASSEMBLED_FLAG.delete()
        termux.runScript("--snapshot")
        delay(1_500L)   // give Termux a moment to write the snapshot

        // Step 2 — tap all visible download buttons
        val count = tapAllDownloadButtons()
        if (count == 0) {
            Log.w(TAG, "DOWNLOADED_FILE: no download buttons found in current window")
            return false
        }
        Log.d(TAG, "Tapped $count download button(s)")

        // Step 3 — tell Termux to assemble ai-output.txt
        delay(2_000L)   // brief wait so downloads have a moment to start
        termux.runScript("--assemble-downloads")

        // Step 4 — poll for assembled flag
        val deadline = System.currentTimeMillis() + ASSEMBLY_TIMEOUT_MS
        while (System.currentTimeMillis() < deadline) {
            delay(ASSEMBLY_POLL_MS)
            if (ASSEMBLED_FLAG.exists()) {
                Log.d(TAG, "downloads_assembled.flag detected — ai-output.txt ready")
                return true
            }
        }

        Log.w(TAG, "DOWNLOADED_FILE: assembly timed out after ${ASSEMBLY_TIMEOUT_MS / 1000}s")
        return false
    }

    /**
     * Walks the entire node tree and taps every download button found.
     * Collects all matching nodes first, then taps them sequentially with
     * a delay between each so Claude has time to process each download.
     * Returns the number of buttons tapped.
     */
    private suspend fun tapAllDownloadButtons(): Int {
        val root = service.rootInActiveWindow ?: return 0
        val nodes = mutableListOf<AccessibilityNodeInfo>()
        collectDownloadNodes(root, nodes)

        if (nodes.isEmpty()) return 0

        Log.d(TAG, "Found ${nodes.size} download node(s)")
        var tapped = 0
        for (node in nodes) {
            val clickable = resolveClickable(node)
            if (clickable != null) {
                val ok = clickable.performAction(AccessibilityNodeInfo.ACTION_CLICK)
                if (ok) {
                    tapped++
                    Log.d(TAG, "Tapped download button #$tapped")
                    delay(POST_TAP_DELAY_MS)
                }
            }
        }
        return tapped
    }

    /**
     * Recursively collects nodes that look like download buttons.
     * Tries content-desc match against DOWNLOAD_BUTTON_DESCS.
     * Also catches nodes whose content-desc ends with a known code extension
     * (e.g. Claude sometimes uses the filename "MainActivity.kt" as the desc).
     */
    private fun collectDownloadNodes(
        node: AccessibilityNodeInfo,
        out: MutableList<AccessibilityNodeInfo>
    ) {
        val desc = node.contentDescription?.toString() ?: ""
        val isDownloadByDesc = DOWNLOAD_BUTTON_DESCS.any { d ->
            desc.equals(d, ignoreCase = true)
        }
        val isDownloadByExt = CODE_EXTENSIONS.any { ext ->
            desc.endsWith(".$ext", ignoreCase = true)
        }
        if (isDownloadByDesc || isDownloadByExt) {
            out.add(node)
            return  // don't recurse into this node's children
        }
        for (i in 0 until node.childCount) {
            collectDownloadNodes(node.getChild(i) ?: continue, out)
        }
    }

    /**
     * Given a node (which may be the icon/label rather than the hit-area),
     * walks UP the tree to find the nearest clickable ancestor.
     * Falls back to the node itself if it is clickable.
     */
    private fun resolveClickable(node: AccessibilityNodeInfo): AccessibilityNodeInfo? {
        if (node.isClickable) return node
        var current: AccessibilityNodeInfo? = node.parent
        while (current != null) {
            if (current.isClickable) return current
            current = current.parent
        }
        return null
    }

    // ── ai-output.txt write ───────────────────────────────────────────────────

    private fun writeAiOutput(content: String) {
        val dir = File("/sdcard/ai-automation")
        dir.mkdirs()
        val tmp = File(dir, "ai-output.tmp")
        tmp.writeText(content)
        tmp.renameTo(File(dir, "ai-output.txt"))
        Log.d(TAG, "ai-output.txt written (${content.length} chars)")
    }

    // ── Prompt field ──────────────────────────────────────────────────────────

    fun fillPromptField(text: String): Boolean {
        val root  = service.rootInActiveWindow ?: return false
        val input = findEditText(root) ?: run {
            Log.w(TAG, "fillPromptField: EditText not found")
            return false
        }
        val args = Bundle().apply {
            putString(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, text)
        }
        return input.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args)
    }

    // ── Error log file attachment ─────────────────────────────────────────────

    suspend fun tapAddFilesButton(): Boolean {
        val root = service.rootInActiveWindow ?: return false
        val node = findClickableParentByDesc(root, ADD_TO_CHAT_DESC) ?: run {
            Log.w(TAG, "Add to chat button not found")
            return false
        }
        return node.performAction(AccessibilityNodeInfo.ACTION_CLICK)
            .also { if (it) delay(FILE_ATTACH_DELAY_MS) }
    }

    suspend fun selectFileInPicker(fileName: String): Boolean {
        delay(POST_TAP_DELAY_MS)
        val root = service.rootInActiveWindow ?: return false
        val node = findNodeByText(root, setOf(fileName)) ?: run {
            Log.w(TAG, "File '$fileName' not visible in picker")
            return false
        }
        val clicked = node.performAction(AccessibilityNodeInfo.ACTION_CLICK)
        if (clicked) {
            delay(POST_TAP_DELAY_MS)
            confirmPickerSelection()
        }
        return clicked
    }

    private suspend fun confirmPickerSelection() {
        val root = service.rootInActiveWindow ?: return
        findNodeByText(root, setOf("Select", "Open", "Done", "OK"))
            ?.performAction(AccessibilityNodeInfo.ACTION_CLICK)
        delay(POST_TAP_DELAY_MS)
    }

    suspend fun tapSendButton(): Boolean {
        val root = service.rootInActiveWindow ?: return false
        val node = findClickableParentByDesc(root, SEND_DESC) ?: run {
            Log.w(TAG, "Send button not found")
            return false
        }
        return node.performAction(AccessibilityNodeInfo.ACTION_CLICK)
            .also { if (it) delay(POST_TAP_DELAY_MS) }
    }

    // ── Node search helpers ───────────────────────────────────────────────────

    private fun findClickableParentByDesc(
        root: AccessibilityNodeInfo,
        desc: String
    ): AccessibilityNodeInfo? {
        val target = findNodeByDesc(root, desc) ?: return null
        return resolveClickable(target)
    }

    private fun findNodeByDesc(
        root: AccessibilityNodeInfo,
        desc: String
    ): AccessibilityNodeInfo? {
        if (root.contentDescription?.toString().equals(desc, ignoreCase = true)) return root
        for (i in 0 until root.childCount) {
            findNodeByDesc(root.getChild(i) ?: continue, desc)?.let { return it }
        }
        return null
    }

    private fun findNodeByText(
        root: AccessibilityNodeInfo,
        targets: Set<String>
    ): AccessibilityNodeInfo? {
        val text = root.text?.toString()?.trim() ?: ""
        if (targets.any { text.contains(it, ignoreCase = true) }) return root
        for (i in 0 until root.childCount) {
            findNodeByText(root.getChild(i) ?: continue, targets)?.let { return it }
        }
        return null
    }

    private fun findEditText(root: AccessibilityNodeInfo): AccessibilityNodeInfo? {
        if (root.className?.toString()?.endsWith("EditText") == true && root.isEditable) return root
        for (i in 0 until root.childCount) {
            findEditText(root.getChild(i) ?: continue)?.let { return it }
        }
        return null
    }
}
