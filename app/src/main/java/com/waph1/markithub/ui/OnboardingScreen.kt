package com.waph1.markithub.ui

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.PowerManager
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.BatteryAlert
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.Notifications
import androidx.compose.material.icons.filled.PermMedia
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.waph1.markithub.PREFS_NAME
import com.waph1.markithub.KEY_ROOT_URI
import com.waph1.markithub.viewmodel.SyncViewModel

@Composable
fun OnboardingScreen(
    viewModel: SyncViewModel,
    onOnboardingComplete: () -> Unit
) {
    var currentStep by remember { mutableStateOf(0) }
    val context = LocalContext.current
    val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    val folderLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocumentTree()
    ) { uri ->
        uri?.let {
            context.contentResolver.takePersistableUriPermission(
                it,
                Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
            )
            prefs.edit().putString(KEY_ROOT_URI, it.toString()).apply()
            viewModel.setRootUri(it)
            // Automatically move to next step after folder selection
            currentStep++
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        when (currentStep) {
            0 -> WelcomeStep(onNext = { currentStep++ })
            1 -> PermissionsStep(context, onNext = { currentStep++ })
            2 -> BatteryOptimizationStep(context, onNext = { currentStep++ })
            3 -> FolderSelectionStep(
                onSelectFolder = { folderLauncher.launch(null) },
                isFolderSelected = viewModel.rootUri.collectAsState().value != null,
                onFinish = onOnboardingComplete
            )
        }
    }
}

@Composable
fun WelcomeStep(onNext: () -> Unit) {
    Text(
        text = "Welcome to MarkIt Hub",
        style = MaterialTheme.typography.headlineLarge,
        fontWeight = FontWeight.Bold,
        textAlign = TextAlign.Center
    )
    Spacer(modifier = Modifier.height(16.dp))
    Text(
        text = "Your central hub for syncing Markdown calendars and notes privately.",
        style = MaterialTheme.typography.bodyLarge,
        textAlign = TextAlign.Center
    )
    Spacer(modifier = Modifier.height(32.dp))
    Button(onClick = onNext, modifier = Modifier.fillMaxWidth()) {
        Text("Get Started")
    }
}

@Composable
fun PermissionsStep(context: Context, onNext: () -> Unit) {
    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { }

    Text(
        text = "Permissions",
        style = MaterialTheme.typography.headlineMedium
    )
    Spacer(modifier = Modifier.height(16.dp))
    Text(
        text = "To sync your calendars and send notifications, MarkIt Hub needs access to:",
        textAlign = TextAlign.Center
    )
    Spacer(modifier = Modifier.height(16.dp))
    
    PermissionItem(icon = Icons.Default.Notifications, text = "Notifications")
    PermissionItem(icon = Icons.Default.PermMedia, text = "Calendar Read/Write")
    
    Spacer(modifier = Modifier.height(32.dp))
    
    Button(
        onClick = {
            val permissions = mutableListOf(
                Manifest.permission.READ_CALENDAR,
                Manifest.permission.WRITE_CALENDAR
            )
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                permissions.add(Manifest.permission.POST_NOTIFICATIONS)
            }
            permissionLauncher.launch(permissions.toTypedArray())
        },
        modifier = Modifier.fillMaxWidth()
    ) {
        Text("Grant Permissions")
    }
    
    Spacer(modifier = Modifier.height(8.dp))
    
    OutlinedButton(onClick = onNext, modifier = Modifier.fillMaxWidth()) {
        Text("Next")
    }
}

@Composable
fun PermissionItem(icon: ImageVector, text: String) {
    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(8.dp)) {
        Icon(imageVector = icon, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
        Spacer(modifier = Modifier.width(12.dp))
        Text(text, style = MaterialTheme.typography.bodyLarge)
    }
}

@SuppressLint("BatteryLife")
@Composable
fun BatteryOptimizationStep(context: Context, onNext: () -> Unit) {
    Text(
        text = "Background Sync",
        style = MaterialTheme.typography.headlineMedium
    )
    Spacer(modifier = Modifier.height(16.dp))
    Text(
        text = "To ensure your calendar stays in sync, please disable battery optimization for MarkIt Hub.",
        textAlign = TextAlign.Center
    )
    Spacer(modifier = Modifier.height(32.dp))
    
    Button(
        onClick = {
            val intent = Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS).apply {
                data = Uri.parse("package:${context.packageName}")
            }
            context.startActivity(intent)
        },
        modifier = Modifier.fillMaxWidth()
    ) {
        Icon(Icons.Default.BatteryAlert, contentDescription = null)
        Spacer(modifier = Modifier.width(8.dp))
        Text("Disable Battery Opt.")
    }
    
    Spacer(modifier = Modifier.height(8.dp))
    
    OutlinedButton(onClick = onNext, modifier = Modifier.fillMaxWidth()) {
        Text("Skip / Next")
    }
}

@Composable
fun FolderSelectionStep(
    onSelectFolder: () -> Unit,
    isFolderSelected: Boolean,
    onFinish: () -> Unit
) {
    Text(
        text = "Select Storage",
        style = MaterialTheme.typography.headlineMedium
    )
    Spacer(modifier = Modifier.height(16.dp))
    Text(
        text = "Choose the folder where your Markdown calendar files will be stored.",
        textAlign = TextAlign.Center
    )
    Spacer(modifier = Modifier.height(32.dp))
    
    if (isFolderSelected) {
        Icon(
            imageVector = Icons.Default.Check,
            contentDescription = "Selected",
            tint = MaterialTheme.colorScheme.primary,
            modifier = Modifier.size(48.dp)
        )
        Text("Folder Selected!", color = MaterialTheme.colorScheme.primary)
        Spacer(modifier = Modifier.height(32.dp))
        Button(onClick = onFinish, modifier = Modifier.fillMaxWidth()) {
            Text("Finish Setup")
        }
    } else {
        Button(onClick = onSelectFolder, modifier = Modifier.fillMaxWidth()) {
            Icon(Icons.Default.Folder, contentDescription = null)
            Spacer(modifier = Modifier.width(8.dp))
            Text("Select Folder")
        }
    }
}
