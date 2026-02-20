package com.jarvismini.devtools.autobuild.models

import org.json.JSONObject

/**
 * Persisted to /sdcard/ai-automation/loop_state.json after every state
 * transition so the loop can resume from the last checkpoint if the service
 * is killed and restarted by Android.
 */
data class LoopState(
    val state: AutoBuildState,
    val iteration: Int,
    val lastUpdatedMs: Long = System.currentTimeMillis()
) {
    fun toJson(): String = JSONObject()
        .put("state", state.name)
        .put("iteration", iteration)
        .put("lastUpdatedMs", lastUpdatedMs)
        .toString()

    companion object {
        fun fromJson(json: String): LoopState? = runCatching {
            JSONObject(json).let {
                LoopState(
                    state         = AutoBuildState.valueOf(it.getString("state")),
                    iteration     = it.getInt("iteration"),
                    lastUpdatedMs = it.getLong("lastUpdatedMs")
                )
            }
        }.getOrNull()
    }
}
