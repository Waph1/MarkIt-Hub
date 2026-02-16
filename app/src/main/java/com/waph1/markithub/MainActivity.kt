package com.waph1.markithub

import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.net.Uri
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.core.app.ActivityCompat
import androidx.lifecycle.viewmodel.compose.viewModel
import com.waph1.markithub.ui.OnboardingScreen
import com.waph1.markithub.ui.SyncDashboardScreen
import com.waph1.markithub.ui.theme.CalendarAppTheme
import com.waph1.markithub.viewmodel.SyncViewModel

const val PREFS_NAME = "CalendarAppPrefs"
const val KEY_ROOT_URI = "rootUri"
const val KEY_TASK_URI = "taskUri"
const val KEY_LAST_SYNC_TIME = "lastSyncTime"
const val KEY_SETUP_COMPLETED = "setupCompleted"

class MainActivity : ComponentActivity() {
    private lateinit var prefs: SharedPreferences

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        prefs = getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

        setContent {
            CalendarAppTheme {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.background
                ) {
                    val viewModel: SyncViewModel = viewModel()
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

                    when {
                        !isSetupCompleted -> {
                            OnboardingScreen(
                                viewModel = viewModel,
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
                            SyncDashboardScreen(
                                viewModel = viewModel,
                                onSelectRootFolder = { launcher.launch(null) },
                                onSelectTaskFolder = { taskLauncher.launch(null) }
                            )
                        }
                    }
                }
            }
        }
    }
}