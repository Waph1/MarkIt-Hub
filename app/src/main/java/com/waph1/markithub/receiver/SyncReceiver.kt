package com.waph1.markithub.receiver

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.accounts.Account
import android.os.Bundle
import android.content.ContentResolver
import android.provider.ContactsContract
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import com.waph1.markithub.util.SyncLogger
import com.waph1.markithub.util.SyncWorker
import com.waph1.markithub.util.SyncScheduler
import com.waph1.markithub.util.SyncEngine

class SyncReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        when (intent.action) {
            "com.waph1.markithub.SYNC" -> {
                SyncLogger.log(context, "calendar", "ADB Broadcast received: Starting Calendar Sync...")
                val workManager = WorkManager.getInstance(context)
                val syncRequest = OneTimeWorkRequestBuilder<SyncWorker>().build()
                workManager.enqueue(syncRequest)
            }
            "com.waph1.markithub.SYNC_CONTACTS" -> {
                SyncLogger.log(context, "contacts", "ADB Broadcast received: Starting Contacts Sync...")
                val account = Account("MarkItHub Contacts", "com.waph1.markithub.contacts")
                val extras = Bundle().apply {
                    putBoolean(ContentResolver.SYNC_EXTRAS_MANUAL, true)
                    putBoolean(ContentResolver.SYNC_EXTRAS_EXPEDITED, true)
                }
                ContentResolver.requestSync(account, ContactsContract.AUTHORITY, extras)
            }
            "com.waph1.markithub.EXPORT_LOGS" -> {
                SyncLogger.log(context, "calendar", "ADB Broadcast received: Exporting Calendar Logs...")
                val prefs = context.getSharedPreferences("MarkItHubPrefs", Context.MODE_PRIVATE)
                val rootUriString = prefs.getString("rootUri", null)
                if (rootUriString != null) {
                    SyncLogger.export(context, "calendar", android.net.Uri.parse(rootUriString))
                }
            }
            "com.waph1.markithub.CHECK_CONFIG" -> {
                val prefs = context.getSharedPreferences("MarkItHubPrefs", Context.MODE_PRIVATE)
                val root = prefs.getString("rootUri", "Not Set")
                val task = prefs.getString("taskUri", "Not Set")
                val contacts = prefs.getString("contactsUri", "Not Set")
                SyncLogger.log(context, "calendar", "Current Configuration - Calendar: $root, Tasks: $task, Contacts: $contacts")
            }
            "com.waph1.markithub.STOP_SYNC" -> {
                SyncLogger.log(context, "calendar", "ADB Broadcast received: Stopping All Syncs...")
                SyncScheduler.stopAll(context)
            }
            "com.waph1.markithub.SET_INTERVAL" -> {
                val minutes = intent.getLongExtra("minutes", -1L)
                if (minutes != -1L) {
                    SyncLogger.log(context, "calendar", "ADB Broadcast received: Setting Calendar Interval to $minutes mins...")
                    val prefs = context.getSharedPreferences("MarkItHubPrefs", Context.MODE_PRIVATE)
                    prefs.edit().putLong("syncInterval", minutes).apply()
                    SyncScheduler.schedule(context, minutes)
                }
            }
            "com.waph1.markithub.NUKE" -> {
                SyncLogger.log(context, "calendar", "ADB Broadcast received: NUKING EVERYTHING...")
                val engine = SyncEngine(context)
                kotlinx.coroutines.runBlocking {
                    engine.wipeAllAppData()
                }
            }
        }
    }
}
