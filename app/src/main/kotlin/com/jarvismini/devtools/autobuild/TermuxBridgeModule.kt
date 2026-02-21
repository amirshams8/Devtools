package com.jarvismini.devtools.autobuild

import android.content.Context
import android.content.Intent
import android.util.Log
import com.jarvismini.devtools.autobuild.models.BuildResult
import kotlinx.coroutines.delay
import java.io.File

/**
 * Drives the GitHub Actions pipeline via Termux RUN_COMMAND broadcasts.
 *
 * Three script modes (all run build_runner.sh with different args):
 *
 *   (no args)              — normal build: copy ai-output.txt → git push → watch pipeline
 *   --snapshot             — record files currently in /sdcard/Download/ before tapping
 *   --assemble-downloads   — detect new files in /sdcard/Download/, assemble ai-output.txt
 *                            in //===== FILE: filename ===== format, write assembled flag
 *
 * Requires:
 *   - Termux from F-Droid (Play Store build lacks RUN_COMMAND permission)
 *   - "Allow External Apps" enabled in Termux → Settings
 *   - gh CLI authenticated: gh auth login
 *   - git remote with push access configured in ~/jarvis/
 */
class TermuxBridgeModule(private val context: Context) {

    companion object {
        private const val TAG = "DevTools:TermuxBridge"

        private const val TERMUX_PKG    = "com.termux"
        private const val TERMUX_ACTION = "com.termux.RUN_COMMAND"
        private const val TERMUX_BASH   = "/data/data/com.termux/files/usr/bin/bash"
        private const val TERMUX_HOME   = "/data/data/com.termux/files/home"
        private const val RUNNER_SCRIPT = "/sdcard/ai-automation/scripts/build_runner.sh"

        const val POLL_INTERVAL_MS = 12_000L   // 12s between completion polls
        const val BUILD_TIMEOUT_MS = 720_000L  // 12 min max

        val COMPLETE_FLAG = File("/sdcard/ai-automation/build_complete.flag")
    }

    /**
     * Fires build_runner.sh with the given arguments via Termux broadcast.
     * Pass no args (empty) for the normal build push.
     * Pass "--snapshot" or "--assemble-downloads" for download mode helpers.
     */
    fun runScript(vararg args: String) {
        val scriptArgs = if (args.isEmpty()) {
            arrayOf(RUNNER_SCRIPT)
        } else {
            arrayOf(RUNNER_SCRIPT, *args)
        }

        val intent = Intent(TERMUX_ACTION).apply {
            setPackage(TERMUX_PKG)
            putExtra("com.termux.RUN_COMMAND_PATH",       TERMUX_BASH)
            putExtra("com.termux.RUN_COMMAND_ARGUMENTS",  scriptArgs)
            putExtra("com.termux.RUN_COMMAND_WORKDIR",    TERMUX_HOME)
            putExtra("com.termux.RUN_COMMAND_BACKGROUND", true)
        }
        runCatching { context.sendBroadcast(intent) }
            .onSuccess { Log.d(TAG, "Termux broadcast sent: build_runner.sh ${args.joinToString(" ")}") }
            .onFailure { Log.e(TAG, "Termux broadcast failed", it) }
    }

    /**
     * Normal build trigger: clears flags, fires build_runner.sh with no args.
     */
    fun triggerBuild() {
        COMPLETE_FLAG.delete()
        FileManagerModule().clearBuildFlags()
        runScript()  // no args = normal push mode
    }

    /**
     * Polls every POLL_INTERVAL_MS for build_complete.flag.
     * build_runner.sh writes this after git pull completes (success or failure).
     */
    suspend fun pollForCompletion(timeoutMs: Long = BUILD_TIMEOUT_MS): BuildResult {
        val deadline = System.currentTimeMillis() + timeoutMs
        val fm = FileManagerModule()

        while (System.currentTimeMillis() < deadline) {
            delay(POLL_INTERVAL_MS)
            if (COMPLETE_FLAG.exists()) {
                Log.d(TAG, "build_complete.flag detected")
                return if (fm.buildFailed()) {
                    Log.d(TAG, "error_summary.txt present → FAILURE")
                    BuildResult.FAILURE
                } else {
                    Log.d(TAG, "No error_summary.txt → SUCCESS")
                    BuildResult.SUCCESS
                }
            }
            val remaining = (deadline - System.currentTimeMillis()) / 1000
            Log.d(TAG, "Waiting for build… ${remaining}s remaining")
        }

        Log.w(TAG, "Build poll timed out after ${timeoutMs / 1000}s")
        return BuildResult.TIMEOUT
    }
}
