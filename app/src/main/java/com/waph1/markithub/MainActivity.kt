package com.waph1.markithub

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ContactPage
import androidx.compose.material.icons.filled.Sync
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.lifecycle.viewmodel.compose.viewModel
import com.waph1.markithub.ui.ContactsScreen
import com.waph1.markithub.ui.OnboardingScreen
import com.waph1.markithub.ui.SyncDashboardScreen
import com.waph1.markithub.ui.theme.CalendarAppTheme
import com.waph1.markithub.util.SyncScheduler
import com.waph1.markithub.viewmodel.ContactsViewModel
import com.waph1.markithub.viewmodel.SyncViewModel

const val PREFS_NAME = "MarkItHubPrefs"
const val KEY_ROOT_URI = "rootUri"
const val KEY_TASK_URI = "taskUri"
const val KEY_CONTACTS_URI = "contactsUri"
const val KEY_LAST_SYNC_TIME = "lastSyncTime"
const val KEY_LAST_CONTACT_SYNC_TIME = "lastContactSyncTime"
const val KEY_SETUP_COMPLETED = "setupCompleted"
const val KEY_SYNC_INTERVAL = "syncInterval"
const val KEY_CONTACTS_SYNC_INTERVAL = "contactsSyncInterval"

class MainActivity : ComponentActivity() {
    private lateinit var prefs: SharedPreferences

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        prefs = getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

        setContent {
            CalendarAppTheme {
                val context = androidx.compose.ui.platform.LocalContext.current
                var selectedTab by remember { mutableStateOf(0) }
                val viewModel: SyncViewModel = viewModel()
                val contactsViewModel: ContactsViewModel = viewModel()

                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.background
                ) {
                    var isLoading by remember { mutableStateOf(true) }
                    var isSetupCompleted by remember {
                        mutableStateOf(prefs.getBoolean(KEY_SETUP_COMPLETED, false))
                    }

                    LaunchedEffect(Unit) {
                        val savedUriString = prefs.getString(KEY_ROOT_URI, null)
                        if (savedUriString != null) {
                            val savedUri = Uri.parse(savedUriString)
                            val hasPermission = contentResolver.persistedUriPermissions.any {
                                it.uri == savedUri && it.isReadPermission && it.isWritePermission
                            }
                            if (hasPermission) {
                                viewModel.setRootUri(savedUri)
                            }
                        }
                        
                        val savedTaskUriString = prefs.getString(KEY_TASK_URI, null)
                        if (savedTaskUriString != null) {
                            val savedUri = Uri.parse(savedTaskUriString)
                            val hasPermission = contentResolver.persistedUriPermissions.any {
                                it.uri == savedUri && it.isReadPermission && it.isWritePermission
                            }
                            if (hasPermission) {
                                viewModel.setTaskRootUri(savedUri)
                            }
                        }

                        val savedContactsUriString = prefs.getString(KEY_CONTACTS_URI, null)
                        if (savedContactsUriString != null) {
                            val savedUri = Uri.parse(savedContactsUriString)
                            val hasPermission = contentResolver.persistedUriPermissions.any {
                                it.uri == savedUri && it.isReadPermission && it.isWritePermission
                            }
                            if (hasPermission) {
                                contactsViewModel.setContactsFolderUri(savedUri)
                                val interval = prefs.getLong(KEY_CONTACTS_SYNC_INTERVAL, 1440L)
                                SyncScheduler.scheduleContactsSync(this@MainActivity, interval)
                            }
                        }
                        
                        isLoading = false
                    }

                    val launcher = rememberLauncherForActivityResult(
                        contract = ActivityResultContracts.OpenDocumentTree()
                    ) { uri ->
                        uri?.let {
                            contentResolver.takePersistableUriPermission(
                                it,
                                Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
                            )
                            prefs.edit().putString(KEY_ROOT_URI, it.toString()).apply()
                            viewModel.setRootUri(it)
                        }
                    }

                    val taskLauncher = rememberLauncherForActivityResult(
                        contract = ActivityResultContracts.OpenDocumentTree()
                    ) { uri ->
                        uri?.let {
                            contentResolver.takePersistableUriPermission(
                                it,
                                Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
                            )
                            prefs.edit().putString(KEY_TASK_URI, it.toString()).apply()
                            viewModel.setTaskRootUri(it)
                        }
                    }

                    val contactsLauncher = rememberLauncherForActivityResult(
                        contract = ActivityResultContracts.OpenDocumentTree()
                    ) { uri ->
                        uri?.let {
                            contentResolver.takePersistableUriPermission(
                                it,
                                Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
                            )
                            prefs.edit().putString(KEY_CONTACTS_URI, it.toString()).apply()
                            contactsViewModel.setContactsFolderUri(it)
                        }
                    }

                    when {
                        !isSetupCompleted -> {
                            OnboardingScreen(
                                onOnboardingComplete = {
                                    prefs.edit().putBoolean(KEY_SETUP_COMPLETED, true).apply()
                                    isSetupCompleted = true
                                }
                            )
                        }
                        isLoading -> {
                            Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                                CircularProgressIndicator()
                            }
                        }
                        else -> {
                            Scaffold(
                                bottomBar = {
                                    NavigationBar {
                                        NavigationBarItem(
                                            icon = { Icon(Icons.Default.Sync, contentDescription = "Calendar") },
                                            label = { Text("Calendar") },
                                            selected = selectedTab == 0,
                                            onClick = { selectedTab = 0 }
                                        )
                                        NavigationBarItem(
                                            icon = { Icon(Icons.Default.ContactPage, contentDescription = "Contacts") },
                                            label = { Text("Contacts") },
                                            selected = selectedTab == 1,
                                            onClick = { 
                                                selectedTab = 1 
                                                checkAndRequestContactsPermissions()
                                            }
                                        )
                                    }
                                }
                            ) { paddingValues ->
                                Box(modifier = Modifier.padding(paddingValues)) {
                                    if (selectedTab == 0) {
                                        SyncDashboardScreen(
                                            viewModel = viewModel,
                                            onSelectRootFolder = { launcher.launch(null) },
                                            onSelectTaskFolder = { taskLauncher.launch(null) }
                                        )
                                    } else {
                                        ContactsScreen(
                                            viewModel = contactsViewModel,
                                            onSelectFolder = { contactsLauncher.launch(null) }
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    private fun checkAndRequestContactsPermissions() {
        val permissions = arrayOf(
            Manifest.permission.READ_CONTACTS,
            Manifest.permission.WRITE_CONTACTS
        )
        val missingPermissions = permissions.filter {
            ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED
        }
        if (missingPermissions.isNotEmpty()) {
            ActivityCompat.requestPermissions(this, missingPermissions.toTypedArray(), 101)
        }
    }
}