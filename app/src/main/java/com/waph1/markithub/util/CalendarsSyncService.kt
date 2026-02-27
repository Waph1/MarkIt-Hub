package com.waph1.markithub.util

import android.accounts.Account
import android.app.Service
import android.content.*
import android.os.Bundle
import android.os.IBinder
import com.waph1.markithub.PREFS_NAME
import kotlinx.coroutines.runBlocking

class CalendarsSyncService : Service() {
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
                runBlocking {
                    try {
                        val syncEngine = SyncEngine(ctx)
                        val prefs = ctx.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                        
                        val rootUriString = prefs.getString("rootUri", null)
                        val taskUriString = prefs.getString("taskUri", null)
                        
                        if (rootUriString != null || taskUriString != null) {
                            rootUriString?.let { syncEngine.setRootUri(android.net.Uri.parse(it)) }
                            taskUriString?.let { syncEngine.setTaskRootUri(android.net.Uri.parse(it)) }
                            
                            syncEngine.performSync()
                            
                            val now = System.currentTimeMillis()
                            prefs.edit().putLong("lastSyncTime", now).apply()
                        }
                    } catch (e: Exception) {
                        SyncLogger.log(ctx, "calendar", "SyncAdapter error: ${e.message}")
                    }
                }
            } catch (e: InterruptedException) {
                SyncLogger.log(ctx, "calendar", "Sync cancelled by Android OS.")
                Thread.currentThread().interrupt() // Restore interrupt status
            } catch (e: Exception) {
                SyncLogger.log(ctx, "calendar", "Fatal SyncAdapter error: ${e.message}")
            } finally {
                pingThread.interrupt()
            }
        }
    }

    companion object {
        private val lock = Any()
    }
}
