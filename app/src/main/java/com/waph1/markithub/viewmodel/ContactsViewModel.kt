package com.waph1.markithub.viewmodel

import android.app.Application
import android.content.Context
import android.content.SharedPreferences
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.waph1.markithub.KEY_CONTACTS_SYNC_INTERVAL
import com.waph1.markithub.KEY_LAST_CONTACT_SYNC_TIME
import com.waph1.markithub.PREFS_NAME
import com.waph1.markithub.util.ContactSyncEngine
import com.waph1.markithub.util.SyncLogger
import com.waph1.markithub.util.SyncScheduler
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.time.LocalDateTime
import java.time.ZoneOffset

class ContactsViewModel(application: Application) : AndroidViewModel(application) {
    private val LOG_NAME = "contacts"
    private val prefs = application.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    private val _isSyncing = MutableStateFlow(false)
    val isSyncing: StateFlow<Boolean> = _isSyncing

    private val _contactsFolderUri = MutableStateFlow<Uri?>(null)
    val contactsFolderUri: StateFlow<Uri?> = _contactsFolderUri

    private val _syncInterval = MutableStateFlow(prefs.getLong(KEY_CONTACTS_SYNC_INTERVAL, 1440L))
    val syncInterval: StateFlow<Long> = _syncInterval

    private val _themeColor = MutableStateFlow(0xFF4CAF50) // Default Green
    val themeColor: StateFlow<Long> = _themeColor

    private val _lastSyncTime = MutableStateFlow<LocalDateTime?>(
        prefs.getLong(KEY_LAST_CONTACT_SYNC_TIME, 0L).let { 
            if (it == 0L) null else LocalDateTime.ofEpochSecond(it, 0, ZoneOffset.UTC)
        }
    )
    val lastSyncTime: StateFlow<LocalDateTime?> = _lastSyncTime

    private val _syncLogs = MutableStateFlow<List<String>>(emptyList())
    val syncLogs: StateFlow<List<String>> = _syncLogs

    private val preferenceChangeListener = SharedPreferences.OnSharedPreferenceChangeListener { sharedPrefs, key ->
        if (key == KEY_LAST_CONTACT_SYNC_TIME) {
            val timestamp = sharedPrefs.getLong(KEY_LAST_CONTACT_SYNC_TIME, 0L)
            if (timestamp > 0) {
                _lastSyncTime.value = LocalDateTime.ofEpochSecond(timestamp, 0, ZoneOffset.UTC)
            }
        }
    }

    init {
        _themeColor.value = prefs.getLong("contactsThemeColor", 0xFF4CAF50)
        prefs.registerOnSharedPreferenceChangeListener(preferenceChangeListener)
        refreshLogs()
    }

    override fun onCleared() {
        super.onCleared()
        prefs.unregisterOnSharedPreferenceChangeListener(preferenceChangeListener)
    }

    fun setContactsFolderUri(uri: Uri) {
        _contactsFolderUri.value = uri
        // CRITICAL: Ensure account exists as soon as folder is selected
        viewModelScope.launch(Dispatchers.IO) {
            ContactSyncEngine.ensureAccountExists(getApplication())
        }
    }

    fun setThemeColor(color: Long) {
        _themeColor.value = color
        prefs.edit().putLong("contactsThemeColor", color).apply()
    }

    fun setSyncInterval(minutes: Long) {
        _syncInterval.value = minutes
        prefs.edit().putLong(KEY_CONTACTS_SYNC_INTERVAL, minutes).apply()
        SyncScheduler.scheduleContactsSync(getApplication(), minutes)
        addLog("Contacts sync interval set to $minutes min")
    }

    fun triggerSync() {
        viewModelScope.launch {
            _isSyncing.value = true
            addLog("Requesting system contacts sync...")
            try {
                withContext(Dispatchers.IO) {
                    ContactSyncEngine.ensureAccountExists(getApplication())
                    val account = android.accounts.Account("MarkItHub Contacts", "com.waph1.markithub.contacts")
                    val extras = android.os.Bundle().apply {
                        putBoolean(android.content.ContentResolver.SYNC_EXTRAS_MANUAL, true)
                        putBoolean(android.content.ContentResolver.SYNC_EXTRAS_EXPEDITED, true)
                        putBoolean(android.content.ContentResolver.SYNC_EXTRAS_FORCE, true)
                    }
                    android.content.ContentResolver.requestSync(account, android.provider.ContactsContract.AUTHORITY, extras)
                    
                    kotlinx.coroutines.delay(500)
                    var attempts = 0
                    while ((android.content.ContentResolver.isSyncActive(account, android.provider.ContactsContract.AUTHORITY) || android.content.ContentResolver.isSyncPending(account, android.provider.ContactsContract.AUTHORITY)) && attempts < 45) {
                        refreshLogs()
                        kotlinx.coroutines.delay(1000)
                        attempts++
                    }
                }
            } catch (e: Exception) {
                addLog("Sync launch error: ${e.message}")
            } finally {
                _isSyncing.value = false
                refreshLogs()
            }
        }
    }

    fun nukeAndReset() {
        viewModelScope.launch(Dispatchers.IO) {
            _isSyncing.value = true
            try {
                ContactSyncEngine.deleteAllSystemContacts(getApplication())
                addLog("NUKED all system contacts managed by MarkItHub")
            } catch (e: Exception) {
                addLog("Nuke failed: ${e.message}")
            } finally {
                _isSyncing.value = false
                refreshLogs()
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
        val uri = _contactsFolderUri.value ?: return
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
