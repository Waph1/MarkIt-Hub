package com.waph1.markithub.util

import android.content.Context
import android.net.Uri
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.waph1.markithub.KEY_LAST_SYNC_TIME
import com.waph1.markithub.KEY_ROOT_URI
import com.waph1.markithub.PREFS_NAME

class SyncWorker(appContext: Context, workerParams: WorkerParameters) :
    CoroutineWorker(appContext, workerParams) {

    override suspend fun doWork(): Result {
        return try {
            SyncLogger.log(applicationContext, "Starting background sync...")
            val prefs = applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            val rootUriString = prefs.getString(KEY_ROOT_URI, null)
            val taskUriString = prefs.getString("taskUri", null)

            if (rootUriString != null || taskUriString != null) {
                val engine = SyncEngine(applicationContext)
                
                if (rootUriString != null) {
                    engine.setRootUri(Uri.parse(rootUriString))
                }
                
                if (taskUriString != null) {
                    engine.setTaskRootUri(Uri.parse(taskUriString))
                }
                
                // We don't have access to ViewModel logging here, just perform sync
                val result = engine.performSync()
                
                prefs.edit().putLong(KEY_LAST_SYNC_TIME, System.currentTimeMillis()).apply()
                
                SyncLogger.log(applicationContext, "Sync success. Processed: ${result.eventsProcessed}, Deleted: ${result.deletions}")
                Result.success()
            } else {
                SyncLogger.log(applicationContext, "Sync failed: No folders selected")
                Result.failure()
            }
        } catch (e: Exception) {
            e.printStackTrace()
            SyncLogger.log(applicationContext, "Sync failed with exception: ${e.message}")
            Result.retry()
        }
    }
}
