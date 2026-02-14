package com.waph1.markithub.viewmodel

import android.app.Application
import android.content.Context
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import com.waph1.markithub.KEY_LAST_SYNC_TIME
import com.waph1.markithub.util.SyncEngine
import com.waph1.markithub.util.SyncWorker
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneId
import java.util.concurrent.TimeUnit

sealed class SyncStatus {
    object Idle : SyncStatus()
    object Syncing : SyncStatus()
    data class Error(val message: String) : SyncStatus()
}

class SyncViewModel(application: Application) : AndroidViewModel(application) {

    private val _syncStatus = MutableStateFlow<SyncStatus>(SyncStatus.Idle)
    val syncStatus: StateFlow<SyncStatus> = _syncStatus

    private val _rootUri = MutableStateFlow<Uri?>(null)
    val rootUri: StateFlow<Uri?> = _rootUri

    private val _taskRootUri = MutableStateFlow<Uri?>(null)
    val taskRootUri: StateFlow<Uri?> = _taskRootUri

    private val _lastSyncTime = MutableStateFlow<LocalDateTime?>(null)
    val lastSyncTime: StateFlow<LocalDateTime?> = _lastSyncTime

    private val _syncLogs = MutableStateFlow<List<String>>(emptyList())
    val syncLogs: StateFlow<List<String>> = _syncLogs
    
    // Sync Frequency (minutes). 0 = Manual
    private val _syncInterval = MutableStateFlow<Long>(15)
    val syncInterval: StateFlow<Long> = _syncInterval

    private val syncEngine = SyncEngine(application)
    private val prefs = application.getSharedPreferences("CalendarAppPrefs", Context.MODE_PRIVATE)

    init {
        val savedInterval = prefs.getLong("syncInterval", 15)
        _syncInterval.value = savedInterval
        _syncLogs.value = com.waph1.markithub.util.SyncLogger.getLogs(getApplication())
        
        val lastSyncMillis = prefs.getLong(KEY_LAST_SYNC_TIME, 0)
        if (lastSyncMillis > 0) {
            _lastSyncTime.value = Instant.ofEpochMilli(lastSyncMillis)
                .atZone(ZoneId.systemDefault())
                .toLocalDateTime()
        }
        
        scheduleSync(savedInterval)
    }

    fun setRootUri(uri: Uri) {
        _rootUri.value = uri
        syncEngine.setRootUri(uri)
    }

    fun setTaskRootUri(uri: Uri) {
        _taskRootUri.value = uri
        syncEngine.setTaskRootUri(uri)
    }
    
    fun setSyncInterval(minutes: Long) {
        _syncInterval.value = minutes
        prefs.edit().putLong("syncInterval", minutes).apply()
        scheduleSync(minutes)
        addLog("Sync interval set to ${if (minutes == 0L) "Manual" else "$minutes min"}")
    }

    private fun scheduleSync(minutes: Long) {
        val workManager = WorkManager.getInstance(getApplication())
        if (minutes > 0) {
            val syncRequest = PeriodicWorkRequestBuilder<SyncWorker>(minutes, TimeUnit.MINUTES)
                .build()
            workManager.enqueueUniquePeriodicWork(
                "CalendarSyncWork",
                ExistingPeriodicWorkPolicy.UPDATE,
                syncRequest
            )
        } else {
            workManager.cancelUniqueWork("CalendarSyncWork")
        }
    }

    fun triggerSync() {
        if (_rootUri.value == null && _taskRootUri.value == null) {
            _syncStatus.value = SyncStatus.Error("No folders selected")
            return
        }

        viewModelScope.launch {
            _syncStatus.value = SyncStatus.Syncing
            addLog("Starting manual sync...")
            try {
                val result = syncEngine.performSync()
                val now = LocalDateTime.now()
                _lastSyncTime.value = now
                
                prefs.edit().putLong(KEY_LAST_SYNC_TIME, System.currentTimeMillis()).apply()
                
                addLog("Sync success. Processed: ${result.eventsProcessed}, Deleted: ${result.deletions}")
                _syncStatus.value = SyncStatus.Idle
            } catch (e: Exception) {
                addLog("Sync failed: ${e.message}")
                _syncStatus.value = SyncStatus.Error(e.message ?: "Unknown error")
            }
        }
    }
    
    fun refreshLogs() {
        _syncLogs.value = com.waph1.markithub.util.SyncLogger.getLogs(getApplication())
    }

    fun clearLogs() {
        com.waph1.markithub.util.SyncLogger.clearLogs(getApplication())
        refreshLogs()
    }

    private fun addLog(message: String) {
        com.waph1.markithub.util.SyncLogger.log(getApplication(), message)
        refreshLogs()
    }
}
