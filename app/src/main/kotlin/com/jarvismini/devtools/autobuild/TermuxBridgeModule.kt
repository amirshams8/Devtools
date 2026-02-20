package com.jarvismini.devtools.autobuild

import android.content.Context
import android.content.Intent
import android.util.Log
import com.jarvismini.devtools.autobuild.models.BuildResult
import kotlinx.coroutines.delay
import java.io.File

/**
 * Drives the GitHub Actions pipeline via Termux.
 *
 * Delivery flow:
 *   1. triggerBuild() sends a Termux RUN_COMMAND broadcast to run build_runner.sh.
 *   2. build_runner.sh (on-device in Termux):
 *        a. Copies /sdcard/ai-automation/ai-output.txt into the JarvisMini repo.
 *        b. git commit + git push → triggers Ai-codegen.yml on GitHub.
 *        c. Ai-codegen.yml completes → triggers main.yml ("Extract Repo Source to TXT").
 *        d. main.yml completes → triggers Apk.yml ("Build APK").
 *        e. Polls Apk.yml via `gh run watch`.
 *        f. On failure: Apk.yml commits build_error_logs/ to main, build_runner.sh
 *           does git pull to download error_summary.txt and error_files.txt into
 *           /sdcard/ai-automation/build_error_logs/.
 *        g. Writes /sdcard/ai-automation/build_complete.flag to signal done.
 *
 * pollForCompletion() watches for build_complete.flag from this side.
 * FileManagerModule.buildFailed() then checks if error_summary.txt exists.
 *
 * Requires:
 *   - Termux installed from F-Droid (Play Store build lacks RUN_COMMAND permission).
 *   - "Allow External Apps" enabled in Termux → Settings.
 *   - gh CLI authenticated: gh auth login
 *   - git remote configured with push access in ~/jarvis/ (or wherever the repo lives).
 */
class TermuxBridgeModule(private val context: Context) {

    companion object {
        private const val TAG = "DevTools:TermuxBridge"

        private const val TERMUX_PKG     = "com.termux"
        private const val TERMUX_ACTION  = "com.termux.RUN_COMMAND"
        private const val TERMUX_BASH    = "/data/data/com.termux/files/usr/bin/bash"
        private const val TERMUX_HOME    = "/data/data/com.termux/files/home"
        private const val RUNNER_SCRIPT  = "/sdcard/ai-automation/scripts/build_runner.sh"

        const val POLL_INTERVAL_MS  = 12_000L    // 12 s between polls
        const val BUILD_TIMEOUT_MS  = 720_000L   // 12 min max (GH Actions cold start)

        val COMPLETE_FLAG = File("/sdcard/ai-automation/build_complete.flag")
    }

    /**
     * Fires the Termux broadcast to start build_runner.sh in the background.
     * Also clears the previous completion flag so polling starts fresh.
     */
    fun triggerBuild() {
        COMPLETE_FLAG.delete()
        FileManagerModule().clearBuildFlags()

        val intent = Intent(TERMUX_ACTION).apply {
            setPackage(TERMUX_PKG)
            putExtra("com.termux.RUN_COMMAND_PATH",       TERMUX_BASH)
            putExtra("com.termux.RUN_COMMAND_ARGUMENTS",  arrayOf(RUNNER_SCRIPT))
            putExtra("com.termux.RUN_COMMAND_WORKDIR",    TERMUX_HOME)
            putExtra("com.termux.RUN_COMMAND_BACKGROUND", true)
        }
        runCatching { context.sendBroadcast(intent) }
            .onSuccess { Log.d(TAG, "build_runner.sh dispatched via Termux") }
            .onFailure { Log.e(TAG, "Termux broadcast failed — Allow External Apps enabled?", it) }
    }

    /**
     * Polls every POLL_INTERVAL_MS until build_complete.flag appears or timeout.
     * build_runner.sh writes this flag after git pull has completed (whether
     * the build succeeded or failed).
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
