package com.jarvismini.devtools.autobuild

import android.content.Context
import android.util.Log
import com.jarvismini.devtools.autobuild.models.AutoBuildState
import com.jarvismini.devtools.autobuild.models.BuildResult
import com.jarvismini.devtools.autobuild.models.ErrorLogBundle
import com.jarvismini.devtools.autobuild.models.ExtractionMode
import com.jarvismini.devtools.autobuild.models.LoopState
import kotlinx.coroutines.delay

/**
 * State machine + main loop.
 *
 * EXTRACTING_CODE delegates to UIWatcherModule.extractAndWrite() which handles
 * all three modes. In every case the result is ai-output.txt on disk before
 * TRIGGERING_BUILD fires.
 */
class OrchestrationController(
    private val context: Context,
    private val uiWatcher: UIWatcherModule
) {
    companion object {
        private const val TAG = "DevTools:Orchestrator"

        const val LOOP_MAX_ITERATIONS   = 50
        const val MAX_RETRIES_PER_STATE = 3
        const val BETWEEN_ITER_DELAY_MS = 500L

        const val STALE_ERROR_PROMPT =
            "The build is still failing with the same errors as the previous attempt. " +
            "Please try a different approach or check for structural issues."

        const val FRESH_ERROR_PROMPT =
            "The build failed. The error logs are attached below. " +
            "Please analyze the errors and provide corrected code."
    }

    private val fm       = FileManagerModule()
    private val termux   = TermuxBridgeModule(context)
    private val notifier = BuildNotifier(context)

    private var retryCount  = 0
    private var errorBundle: ErrorLogBundle? = null

    @Volatile private var stopRequested = false

    fun requestStop() { stopRequested = true }

    suspend fun runLoop() {
        val checkpoint = fm.loadState()
        var state      = checkpoint?.state     ?: AutoBuildState.WAITING_FOR_RESPONSE
        var iteration  = checkpoint?.iteration ?: 0
        stopRequested  = false

        // Read mode once per loop start — persisted via ModeStore / spinner in MainActivity
        val mode = ModeStore.load(context)
        log("Loop started — state=$state iter=$iteration mode=$mode")
        notifier.update(iteration, state)

        while (iteration < LOOP_MAX_ITERATIONS && !stopRequested) {
            fm.saveState(LoopState(state, iteration))

            state = when (state) {

                AutoBuildState.IDLE ->
                    AutoBuildState.WAITING_FOR_RESPONSE

                AutoBuildState.WAITING_FOR_RESPONSE -> {
                    val done = uiWatcher.waitForResponseComplete()
                    if (done) AutoBuildState.EXTRACTING_CODE
                    else      timeout(state)
                }

                AutoBuildState.EXTRACTING_CODE -> {
                    val root = uiWatcher.service.rootInActiveWindow
                    // extractAndWrite handles all three modes internally.
                    // For DOWNLOADED_FILE it also coordinates the Termux snapshot
                    // and assemble steps before returning.
                    val ok = uiWatcher.extractAndWrite(root, mode, termux)
                    if (ok) {
                        log("Extraction OK (mode=$mode) — ai-output.txt ready")
                        AutoBuildState.TRIGGERING_BUILD
                    } else {
                        retry(state)
                    }
                }

                AutoBuildState.WRITING_OUTPUT ->
                    AutoBuildState.TRIGGERING_BUILD

                AutoBuildState.TRIGGERING_BUILD -> {
                    log("Triggering build (iteration=$iteration) via Termux…")
                    termux.triggerBuild()
                    retryCount = 0
                    AutoBuildState.WAITING_FOR_BUILD
                }

                AutoBuildState.WAITING_FOR_BUILD -> {
                    log("Polling for Apk.yml completion…")
                    when (termux.pollForCompletion()) {
                        BuildResult.SUCCESS -> AutoBuildState.BUILD_SUCCEEDED
                        BuildResult.FAILURE -> AutoBuildState.CHECKING_ERROR_FRESHNESS
                        BuildResult.TIMEOUT -> timeout(state)
                    }
                }

                AutoBuildState.BUILD_SUCCEEDED -> {
                    log("✅ Build succeeded after $iteration iteration(s)")
                    notifier.success(iteration)
                    return
                }

                AutoBuildState.CHECKING_ERROR_FRESHNESS -> {
                    if (fm.hasNewErrors(iteration)) {
                        log("New error logs detected — reading logs")
                        AutoBuildState.READING_ERROR_LOGS
                    } else {
                        log("Error logs unchanged — sending nudge prompt")
                        uiWatcher.fillPromptField(STALE_ERROR_PROMPT)
                        iteration++
                        AutoBuildState.SUBMITTING_PROMPT
                    }
                }

                AutoBuildState.READING_ERROR_LOGS -> {
                    val bundle = fm.readErrorLogs()
                    if (bundle != null) {
                        fm.saveErrorFingerprint(fm.computeErrorFingerprint(iteration))
                        errorBundle = bundle
                        log("Error logs read (${bundle.errorSummaryContent.length} chars)")
                        AutoBuildState.ATTACHING_FILES
                    } else {
                        log("Error logs missing after FAILURE — retrying", isError = true)
                        timeout(state)
                    }
                }

                AutoBuildState.ATTACHING_FILES -> {
                    val ok = attachErrorFiles()
                    if (ok) { iteration++; AutoBuildState.SUBMITTING_PROMPT }
                    else    retry(state)
                }

                AutoBuildState.SUBMITTING_PROMPT -> {
                    uiWatcher.tapSendButton()
                    delay(UIWatcherModule.POST_TAP_DELAY_MS)
                    AutoBuildState.WAITING_FOR_RESPONSE
                }

                AutoBuildState.TIMEOUT_ERROR -> {
                    val backoff = 1_000L * (1 shl minOf(retryCount, 3))
                    log("Timeout recovery — waiting ${backoff}ms (retry=$retryCount)", isError = true)
                    delay(backoff)
                    if (++retryCount > MAX_RETRIES_PER_STATE) {
                        notifier.error("Too many retries — stopped at $state")
                        return
                    }
                    AutoBuildState.WAITING_FOR_RESPONSE
                }
            }

            AutoBuildService.currentState     = state
            AutoBuildService.currentIteration = iteration
            notifier.update(iteration, state)
            AutoBuildService.onStatusUpdate?.invoke(iteration, state)
            delay(BETWEEN_ITER_DELAY_MS)
        }

        if (stopRequested) log("Loop stopped by user request")
        else log("Max iterations ($LOOP_MAX_ITERATIONS) reached — stopping", isError = true)
        notifier.error(if (stopRequested) "Stopped by user" else "Max iterations reached")
    }

    private suspend fun attachErrorFiles(): Boolean {
        if (!uiWatcher.tapAddFilesButton()) {
            log("tapAddFilesButton failed (1)", isError = true); return false
        }
        if (!uiWatcher.selectFileInPicker(FileManagerModule.ERROR_FILES_FILE.name)) {
            log("selectFileInPicker(error_files.txt) failed", isError = true); return false
        }
        delay(UIWatcherModule.POST_TAP_DELAY_MS)

        if (!uiWatcher.tapAddFilesButton()) {
            log("tapAddFilesButton failed (2)", isError = true); return false
        }
        if (!uiWatcher.selectFileInPicker(FileManagerModule.ERROR_SUMMARY_FILE.name)) {
            log("selectFileInPicker(error_summary.txt) failed", isError = true); return false
        }
        delay(UIWatcherModule.POST_TAP_DELAY_MS)

        uiWatcher.fillPromptField(FRESH_ERROR_PROMPT)
        log("Both error files attached, prompt filled")
        return true
    }

    private fun timeout(state: AutoBuildState): AutoBuildState {
        log("Timeout in $state", isError = true)
        return AutoBuildState.TIMEOUT_ERROR
    }

    private fun retry(state: AutoBuildState): AutoBuildState {
        return if (++retryCount <= MAX_RETRIES_PER_STATE) {
            log("Retrying $state (attempt $retryCount)")
            state
        } else {
            log("Max retries exceeded for $state", isError = true)
            AutoBuildState.TIMEOUT_ERROR
        }
    }

    private fun log(msg: String, isError: Boolean = false) {
        if (isError) Log.e(TAG, msg) else Log.d(TAG, msg)
        AutoBuildService.onLogLine?.invoke(msg, isError)
    }
}
