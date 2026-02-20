package com.jarvismini.devtools.autobuild.models

/**
 * Snapshot of error_summary.txt and error_files.txt read from
 * /sdcard/ai-automation/build_error_logs/ after Apk.yml commits them to main
 * and Termux pulls them down via git pull.
 */
data class ErrorLogBundle(
    val errorFilesContent: String,
    val errorSummaryContent: String,
    val capturedAtMs: Long = System.currentTimeMillis()
)
