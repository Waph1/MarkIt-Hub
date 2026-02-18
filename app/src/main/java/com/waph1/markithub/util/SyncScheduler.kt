package com.waph1.markithub.util

import android.content.Context
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import java.util.concurrent.TimeUnit

object SyncScheduler {
    const val SYNC_WORK_NAME = "CalendarSyncWork"

    fun schedule(context: Context, minutes: Long) {
        val workManager = WorkManager.getInstance(context)
        if (minutes > 0) {
            val syncRequest = PeriodicWorkRequestBuilder<SyncWorker>(minutes, TimeUnit.MINUTES)
                .addTag(SYNC_WORK_NAME)
                .build()
            workManager.enqueueUniquePeriodicWork(
                SYNC_WORK_NAME,
                ExistingPeriodicWorkPolicy.UPDATE,
                syncRequest
            )
            SyncLogger.log(context, "Periodic sync scheduled every $minutes minutes.")
        } else {
            workManager.cancelUniqueWork(SYNC_WORK_NAME)
            SyncLogger.log(context, "Periodic sync disabled.")
        }
    }

    fun stopAll(context: Context) {
        val workManager = WorkManager.getInstance(context)
        workManager.cancelUniqueWork(SYNC_WORK_NAME)
        workManager.cancelAllWorkByTag(SYNC_WORK_NAME)
        // Also cancel any one-time workers
        workManager.cancelAllWork() 
        SyncLogger.log(context, "All synchronization tasks stopped/cancelled.")
    }
}
