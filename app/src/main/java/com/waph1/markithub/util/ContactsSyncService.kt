package com.waph1.markithub.util

import android.accounts.Account
import android.app.Service
import android.content.*
import android.os.Bundle
import android.os.IBinder
import com.waph1.markithub.KEY_CONTACTS_URI
import com.waph1.markithub.PREFS_NAME

class ContactsSyncService : Service() {
    private var syncAdapter: SyncAdapter? = null

    override fun onCreate() {
        synchronized(lock) {
            if (syncAdapter == null) {
                syncAdapter = SyncAdapter(applicationContext, true)
            }
        }
    }

    override fun onBind(intent: Intent?): IBinder? {
        return syncAdapter?.syncAdapterBinder
    }

    class SyncAdapter(context: Context?, autoInitialize: Boolean) :
        AbstractThreadedSyncAdapter(context, autoInitialize) {
        
        override fun onPerformSync(
            account: Account,
            extras: Bundle,
            authority: String,
            provider: ContentProviderClient,
            syncResult: android.content.SyncResult
        ) {
            val ctx = getContext() ?: return
            val pingThread = Thread {
                while (!Thread.currentThread().isInterrupted) {
                    try {
                        val conn = java.net.URL("https://clients3.google.com/generate_204").openConnection() as java.net.HttpURLConnection
                        conn.connectTimeout = 3000; conn.readTimeout = 3000; conn.responseCode; conn.disconnect()
                        Thread.sleep(10000)
                    } catch (e: InterruptedException) { break } catch (e: Exception) {}
                }
            }
            pingThread.start()
            try {
                val prefs = ctx.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                val contactsUriString = prefs.getString(KEY_CONTACTS_URI, null)
                if (contactsUriString != null) {
                    ContactSyncEngine.sync(ctx, android.net.Uri.parse(contactsUriString))
                    
                    val now = java.time.LocalDateTime.now()
                    prefs.edit().putLong("lastContactSyncTime", now.toEpochSecond(java.time.ZoneOffset.UTC)).apply()
                }
            } catch (e: Exception) {
                SyncLogger.log(ctx, "contacts", "SyncAdapter error: ${e.message}")
            } finally {
                pingThread.interrupt()
            }
        }
    }

    companion object {
        private val lock = Any()
    }
}
