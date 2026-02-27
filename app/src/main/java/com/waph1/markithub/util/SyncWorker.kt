package com.waph1.markithub.util

import android.content.Context
import android.net.Uri
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.waph1.markithub.PREFS_NAME
import com.waph1.markithub.KEY_ROOT_URI
import com.waph1.markithub.KEY_TASK_URI

class SyncWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        val prefs = applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val rootUriString = prefs.getString(KEY_ROOT_URI, null)
        val taskUriString = prefs.getString(KEY_TASK_URI, null)

        SyncLogger.log(applicationContext, "calendar", "Background sync triggered by WorkManager.")

        if (rootUriString == null && taskUriString == null) {
            return Result.success()
        }

        return try {
            val syncEngine = SyncEngine(applicationContext)
            rootUriString?.let { syncEngine.setRootUri(Uri.parse(it)) }
            taskUriString?.let { syncEngine.setTaskRootUri(Uri.parse(it)) }
            
            val result = syncEngine.performSync()
            SyncLogger.log(
                applicationContext, 
                "calendar",
                "Background sync success. Processed: ${result.eventsProcessed}, Deleted: ${result.deletions}"
            )
            Result.success()
        } catch (e: Exception) {
            SyncLogger.log(applicationContext, "calendar", "Background sync failed: ${e.message}")
            Result.retry()
        }
    }
}
