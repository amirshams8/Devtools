package com.jarvismini.devtools.autobuild

import android.app.Notification
import android.app.NotificationManager
import android.content.Context
import android.util.Log
import com.jarvismini.devtools.DevToolsApp
import com.jarvismini.devtools.autobuild.models.AutoBuildState

/**
 * Posts and updates the persistent foreground notification.
 * Uses Android default icon — no custom drawables required.
 */
class BuildNotifier(private val context: Context) {

    companion object {
        const val NOTIFICATION_ID = 9_001
        private const val TAG     = "DevTools:Notifier"
    }

    private val nm = context.getSystemService(NotificationManager::class.java)

    fun buildNotification(iteration: Int, state: AutoBuildState): Notification =
        Notification.Builder(context, DevToolsApp.CHANNEL_ID)
            .setSmallIcon(android.R.mipmap.sym_def_app_icon)
            .setContentTitle("AutoBuild — Iter $iteration")
            .setContentText(stateText(state))
            .setOngoing(true)
            .build()

    fun update(iteration: Int, state: AutoBuildState) {
        runCatching { nm.notify(NOTIFICATION_ID, buildNotification(iteration, state)) }
            .onFailure { Log.w(TAG, "Notification update failed", it) }
    }

    fun success(iteration: Int) {
        runCatching {
            nm.notify(NOTIFICATION_ID,
                Notification.Builder(context, DevToolsApp.CHANNEL_ID)
                    .setSmallIcon(android.R.mipmap.sym_def_app_icon)
                    .setContentTitle("✅ Build Succeeded")
                    .setContentText("Done after $iteration iteration(s)")
                    .setAutoCancel(true)
                    .build()
            )
        }
    }

    fun error(msg: String) {
        runCatching {
            nm.notify(NOTIFICATION_ID,
                Notification.Builder(context, DevToolsApp.CHANNEL_ID)
                    .setSmallIcon(android.R.mipmap.sym_def_app_icon)
                    .setContentTitle("❌ AutoBuild Stopped")
                    .setContentText(msg)
                    .setAutoCancel(true)
                    .build()
            )
        }
    }

    private fun stateText(state: AutoBuildState) = when (state) {
        AutoBuildState.WAITING_FOR_RESPONSE     -> "Waiting for Claude response…"
        AutoBuildState.EXTRACTING_CODE          -> "Extracting code…"
        AutoBuildState.WRITING_OUTPUT           -> "Writing ai-output.txt…"
        AutoBuildState.TRIGGERING_BUILD         -> "Pushing via Termux…"
        AutoBuildState.WAITING_FOR_BUILD        -> "Waiting for Apk.yml…"
        AutoBuildState.CHECKING_ERROR_FRESHNESS -> "Checking error freshness…"
        AutoBuildState.READING_ERROR_LOGS       -> "Pulling error logs…"
        AutoBuildState.ATTACHING_FILES          -> "Attaching error logs to Claude…"
        AutoBuildState.SUBMITTING_PROMPT        -> "Submitting prompt…"
        AutoBuildState.BUILD_SUCCEEDED          -> "Build succeeded!"
        AutoBuildState.TIMEOUT_ERROR            -> "Timeout — retrying…"
        AutoBuildState.IDLE                     -> "Idle"
    }
}
