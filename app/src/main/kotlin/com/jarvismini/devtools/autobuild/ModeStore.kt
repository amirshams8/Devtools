package com.jarvismini.devtools.autobuild

import android.content.Context
import com.jarvismini.devtools.autobuild.models.ExtractionMode

/**
 * Persists the user-selected ExtractionMode to SharedPreferences.
 * Readable from both MainActivity and the background service.
 */
object ModeStore {
    private const val PREFS = "devtools_prefs"
    private const val KEY   = "extraction_mode"

    fun save(context: Context, mode: ExtractionMode) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit().putString(KEY, mode.name).apply()
    }

    fun load(context: Context): ExtractionMode {
        val raw = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getString(KEY, ExtractionMode.CODE_BLOCK.name)
        return runCatching { ExtractionMode.valueOf(raw!!) }
            .getOrDefault(ExtractionMode.CODE_BLOCK)
    }
}
