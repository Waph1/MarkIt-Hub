package com.waph1.markithub.receiver

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import com.waph1.markithub.util.SyncLogger
import com.waph1.markithub.util.SyncWorker

class SyncReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        when (intent.action) {
            "com.waph1.markithub.SYNC" -> {
                SyncLogger.log(context, "ADB Broadcast received: Starting Sync...")
                val workManager = WorkManager.getInstance(context)
                val syncRequest = OneTimeWorkRequestBuilder<SyncWorker>().build()
                workManager.enqueue(syncRequest)
            }
            "com.waph1.markithub.EXPORT_LOGS" -> {
                SyncLogger.log(context, "ADB Broadcast received: Exporting Logs...")
                SyncLogger.export(context)
            }
            "com.waph1.markithub.CHECK_CONFIG" -> {
                val prefs = context.getSharedPreferences("MarkItHubPrefs", Context.MODE_PRIVATE)
                val root = prefs.getString("rootUri", "Not Set")
                val task = prefs.getString("taskUri", "Not Set")
                SyncLogger.log(context, "Current Configuration - Root: $root, Task: $task")
            }
            "com.waph1.markithub.STOP_SYNC" -> {
                SyncLogger.log(context, "ADB Broadcast received: Stopping Sync...")
                com.waph1.markithub.util.SyncScheduler.stopAll(context)
            }
            "com.waph1.markithub.SET_INTERVAL" -> {
                val minutes = intent.getLongExtra("minutes", -1L)
                if (minutes != -1L) {
                    SyncLogger.log(context, "ADB Broadcast received: Setting Interval to $minutes mins...")
                    val prefs = context.getSharedPreferences("MarkItHubPrefs", Context.MODE_PRIVATE)
                    prefs.edit().putLong("syncInterval", minutes).apply()
                    com.waph1.markithub.util.SyncScheduler.schedule(context, minutes)
                } else {
                    SyncLogger.log(context, "SET_INTERVAL failed: Missing 'minutes' extra.")
                }
            }
            "com.waph1.markithub.GET_INTERVAL" -> {
                val prefs = context.getSharedPreferences("MarkItHubPrefs", Context.MODE_PRIVATE)
                val minutes = prefs.getLong("syncInterval", 15)
                SyncLogger.log(context, "Current Sync Interval: $minutes minutes")
            }
            "com.waph1.markithub.NUKE" -> {
                SyncLogger.log(context, "ADB Broadcast received: NUKING EVERYTHING...")
                val engine = com.waph1.markithub.util.SyncEngine(context)
                kotlinx.coroutines.runBlocking {
                    engine.wipeAllAppData()
                }
            }
        }
    }
}
