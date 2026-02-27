package com.waph1.markithub.viewmodel

import android.app.Application
import android.content.Context
import android.content.SharedPreferences
import android.net.Uri
import android.provider.CalendarContract
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.waph1.markithub.KEY_LAST_SYNC_TIME
import com.waph1.markithub.util.SyncEngine
import com.waph1.markithub.util.SyncLogger
import com.waph1.markithub.util.SyncScheduler
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneId

sealed class SyncStatus {
    object Idle : SyncStatus()
    object Syncing : SyncStatus()
    data class Error(val message: String) : SyncStatus()
}

class SyncViewModel(application: Application) : AndroidViewModel(application) {
    private val LOG_NAME = "calendar"
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
    
    private val _syncInterval = MutableStateFlow<Long>(15)
    val syncInterval: StateFlow<Long> = _syncInterval

    private val _themeColor = MutableStateFlow(0xFF2196F3) // Default Blue
    val themeColor: StateFlow<Long> = _themeColor

    private val syncEngine = SyncEngine(application)
    private val prefs = application.getSharedPreferences(com.waph1.markithub.PREFS_NAME, Context.MODE_PRIVATE)

    private val preferenceChangeListener = SharedPreferences.OnSharedPreferenceChangeListener { sharedPrefs, key ->
        if (key == KEY_LAST_SYNC_TIME) {
            val lastSyncMillis = sharedPrefs.getLong(KEY_LAST_SYNC_TIME, 0)
            if (lastSyncMillis > 0) {
                _lastSyncTime.value = Instant.ofEpochMilli(lastSyncMillis)
                    .atZone(ZoneId.systemDefault())
                    .toLocalDateTime()
            }
        }
    }

    init {
        val savedInterval = prefs.getLong("syncInterval", 15)
        _syncInterval.value = savedInterval
        _themeColor.value = prefs.getLong("calendarThemeColor", 0xFF2196F3)
        prefs.registerOnSharedPreferenceChangeListener(preferenceChangeListener)
        refreshLogs()
        
        val lastSyncMillis = prefs.getLong(KEY_LAST_SYNC_TIME, 0)
        if (lastSyncMillis > 0) {
            _lastSyncTime.value = Instant.ofEpochMilli(lastSyncMillis)
                .atZone(ZoneId.systemDefault())
                .toLocalDateTime()
        }
    }

    override fun onCleared() {
        super.onCleared()
        prefs.unregisterOnSharedPreferenceChangeListener(preferenceChangeListener)
    }

    fun setRootUri(uri: Uri) {
        _rootUri.value = uri
        syncEngine.setRootUri(uri)
        ensureAccount()
    }

    fun setTaskRootUri(uri: Uri) {
        _taskRootUri.value = uri
        syncEngine.setTaskRootUri(uri)
        ensureAccount()
    }

    private fun ensureAccount() {
        viewModelScope.launch(Dispatchers.IO) {
            syncEngine.ensureAccountExists()
        }
    }
    
    fun setSyncInterval(minutes: Long) {
        _syncInterval.value = minutes
        prefs.edit().putLong("syncInterval", minutes).apply()
        SyncScheduler.schedule(getApplication(), minutes)
        addLog("Calendar sync interval set to ${if (minutes == 0L) "Manual" else "$minutes min"}")
    }

    fun setThemeColor(color: Long) {
        _themeColor.value = color
        prefs.edit().putLong("calendarThemeColor", color).apply()
    }

    fun triggerSync() {
        if (_rootUri.value == null && _taskRootUri.value == null) {
            _syncStatus.value = SyncStatus.Error("No folders selected")
            return
        }

        viewModelScope.launch {
            _syncStatus.value = SyncStatus.Syncing
            addLog("Requesting system calendar sync...")
            try {
                withContext(Dispatchers.IO) {
                    syncEngine.ensureAccountExists()
                    val account = android.accounts.Account("MarkItHub Calendars", "com.waph1.markithub.calendars")
                    val extras = android.os.Bundle().apply {
                        putBoolean(android.content.ContentResolver.SYNC_EXTRAS_MANUAL, true)
                        putBoolean(android.content.ContentResolver.SYNC_EXTRAS_EXPEDITED, true)
                        putBoolean(android.content.ContentResolver.SYNC_EXTRAS_FORCE, true)
                    }
                    android.content.ContentResolver.requestSync(account, CalendarContract.AUTHORITY, extras)
                    
                    kotlinx.coroutines.delay(500)
                    var attempts = 0
                    while ((android.content.ContentResolver.isSyncActive(account, CalendarContract.AUTHORITY) || android.content.ContentResolver.isSyncPending(account, CalendarContract.AUTHORITY)) && attempts < 45) {
                        refreshLogs()
                        kotlinx.coroutines.delay(1000)
                        attempts++
                    }
                }
            } catch (e: Exception) {
                addLog("Sync launch error: ${e.message}")
            } finally {
                _syncStatus.value = SyncStatus.Idle
                refreshLogs()
            }
        }
    }

    fun nukeAndReset() {
        viewModelScope.launch {
            _syncStatus.value = SyncStatus.Syncing
            try {
                syncEngine.wipeAllAppData()
                addLog("Calendar app data and system entries wiped.")
                _lastSyncTime.value = null
                _syncStatus.value = SyncStatus.Idle
            } catch (e: Exception) {
                addLog("Calendar nuke failed: ${e.message}")
                _syncStatus.value = SyncStatus.Idle
            }
        }
    }
    
    fun refreshLogs() {
        viewModelScope.launch(Dispatchers.IO) {
            _syncLogs.value = SyncLogger.getLogs(getApplication(), LOG_NAME)
        }
    }

    fun clearLogs() {
        SyncLogger.clearLogs(getApplication(), LOG_NAME)
        refreshLogs()
    }

    fun exportLogs() {
        val uri = _rootUri.value ?: _taskRootUri.value ?: return
        viewModelScope.launch(Dispatchers.IO) {
            SyncLogger.export(getApplication(), LOG_NAME, uri)
            refreshLogs()
        }
    }

    private fun addLog(message: String) {
        SyncLogger.log(getApplication(), LOG_NAME, message)
        refreshLogs()
    }
}
