package com.jarvismini.devtools.autobuild.models

import org.json.JSONObject

/**
 * Fingerprint of the last-processed error_summary.txt.
 * Used by FileManagerModule.hasNewErrors() to avoid reprocessing
 * identical logs when the build fails with the same errors twice in a row.
 *
 * Stored at: /sdcard/ai-automation/last_error_fingerprint.json
 */
data class ErrorFingerprint(
    val lastModifiedMs: Long,
    val contentHash: String,      // SHA-256 hex of error_summary.txt bytes
    val buildIteration: Int
) {
    fun toJson(): String = JSONObject()
        .put("lastModifiedMs", lastModifiedMs)
        .put("contentHash", contentHash)
        .put("buildIteration", buildIteration)
        .toString()

    companion object {
        fun fromJson(json: String): ErrorFingerprint? = runCatching {
            JSONObject(json).let {
                ErrorFingerprint(
                    lastModifiedMs  = it.getLong("lastModifiedMs"),
                    contentHash     = it.getString("contentHash"),
                    buildIteration  = it.getInt("buildIteration")
                )
            }
        }.getOrNull()
    }
}
