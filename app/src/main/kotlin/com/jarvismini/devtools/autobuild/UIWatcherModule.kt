package com.jarvismini.devtools.autobuild

import android.accessibilityservice.AccessibilityService
import android.os.Bundle
import android.util.Log
import android.view.accessibility.AccessibilityNodeInfo
import kotlinx.coroutines.delay

/**
 * All UI interaction with the Claude Android app.
 *
 * Response completion detection:
 *   - Listens for TYPE_WINDOW_CONTENT_CHANGED events from com.anthropic.claude.
 *   - Stamps lastClaudeEventMs on each event.
 *   - Response is complete when no events have arrived for STABILITY_WINDOW_MS
 *     AND the streaming indicator node is absent.
 *
 * Code extraction strategy:
 *   1. Find TextViews whose text matches LANGUAGE_LABEL_REGEX (e.g. "kotlin").
 *   2. The next sibling TextView contains the code block content.
 *   3. Fallback: largest TextView in the window.
 *
 * File attachment:
 *   - Taps Claude's "Add Files" button, waits for picker, selects file by name.
 *   - Picker navigation assumes files are visible at the default location
 *     (/sdcard/ai-automation/build_error_logs/). If Claude uses a different
 *     picker the node text search will still find items by filename.
 */
class UIWatcherModule(val service: AccessibilityService) {

    companion object {
        private const val TAG = "DevTools:UIWatcher"

        const val CLAUDE_PKG            = "com.anthropic.claude"
        const val STABILITY_WINDOW_MS   = 2_500L
        const val RESPONSE_TIMEOUT_MS   = 120_000L
        const val POST_TAP_DELAY_MS     = 900L
        const val FILE_ATTACH_DELAY_MS  = 1_600L

        val STREAMING_INDICATORS = setOf("●", "Thinking…", "Thinking...", "▌")

        val LANGUAGE_LABEL_REGEX = Regex(
            "^(kotlin|java|python|bash|shell|javascript|typescript|xml|json|gradle|swift|cpp|c|html|css|yaml|toml)$",
            RegexOption.IGNORE_CASE
        )

        val ADD_FILES_KEYWORDS  = listOf("add files", "attach", "attachment", "paperclip")
        val SEND_BUTTON_KEYWORDS = listOf("send message", "send", "submit")
    }

    @Volatile var lastClaudeEventMs: Long = 0L

    /** Called by AutoBuildService.onAccessibilityEvent for every Claude event. */
    fun onAccessibilityEvent(packageName: String?) {
        if (packageName == CLAUDE_PKG) lastClaudeEventMs = System.currentTimeMillis()
    }

    // ── Response detection ────────────────────────────────────────────────────

    suspend fun waitForResponseComplete(timeoutMs: Long = RESPONSE_TIMEOUT_MS): Boolean {
        val deadline = System.currentTimeMillis() + timeoutMs
        delay(1_500L)  // give Claude a moment to start generating

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

    // ── Code extraction ───────────────────────────────────────────────────────

    fun extractCodeBlocks(root: AccessibilityNodeInfo?): List<String> {
        root ?: return emptyList()
        val blocks = mutableListOf<String>()
        collectCodeBlocks(root, blocks)

        if (blocks.isEmpty()) {
            Log.w(TAG, "Primary extraction empty, trying fallback")
            largestTextViewText(root)?.takeIf { it.isNotBlank() }?.let { blocks.add(it) }
        }

        Log.d(TAG, "Extracted ${blocks.size} code block(s)")
        return blocks
    }

    private fun collectCodeBlocks(node: AccessibilityNodeInfo, out: MutableList<String>) {
        val text = node.text?.toString() ?: ""
        if (node.className?.toString()?.endsWith("TextView") == true &&
            LANGUAGE_LABEL_REGEX.matches(text.trim())) {
            val parent = node.parent
            if (parent != null) {
                var seenLabel = false
                for (i in 0 until parent.childCount) {
                    val child = parent.getChild(i) ?: continue
                    if (seenLabel) {
                        child.text?.toString()
                            ?.takeIf { it.isNotBlank() }
                            ?.let { out.add(it) }
                        break
                    }
                    if (child == node) seenLabel = true
                }
            }
        }
        for (i in 0 until node.childCount) collectCodeBlocks(node.getChild(i) ?: continue, out)
    }

    private fun largestTextViewText(root: AccessibilityNodeInfo): String? {
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

    // ── Tap helpers ───────────────────────────────────────────────────────────

    suspend fun tapAddFilesButton(): Boolean {
        val root = service.rootInActiveWindow ?: return false
        val node = findNodeByContentDesc(root, ADD_FILES_KEYWORDS)
            ?: findNodeByText(root, ADD_FILES_KEYWORDS.toSet())
            ?: run {
                Log.w(TAG, "Add Files button not found")
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
        val node = findNodeByContentDesc(root, SEND_BUTTON_KEYWORDS)
            ?: findNodeByText(root, SEND_BUTTON_KEYWORDS.toSet())
            ?: run {
                Log.w(TAG, "Send button not found")
                return false
            }
        return node.performAction(AccessibilityNodeInfo.ACTION_CLICK)
            .also { if (it) delay(POST_TAP_DELAY_MS) }
    }

    // ── Node search ───────────────────────────────────────────────────────────

    private fun findNodeByContentDesc(
        root: AccessibilityNodeInfo,
        keywords: List<String>
    ): AccessibilityNodeInfo? {
        val desc = root.contentDescription?.toString()?.lowercase() ?: ""
        if (keywords.any { desc.contains(it) }) return root
        for (i in 0 until root.childCount) {
            findNodeByContentDesc(root.getChild(i) ?: continue, keywords)?.let { return it }
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
