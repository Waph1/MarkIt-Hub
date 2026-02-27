package com.waph1.markithub.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.Sync
import androidx.compose.material.icons.filled.Palette
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.waph1.markithub.viewmodel.ContactsViewModel
import java.time.format.DateTimeFormatter

@Composable
fun ContactsScreen(
    viewModel: ContactsViewModel = viewModel(),
    onSelectFolder: () -> Unit
) {
    val isSyncing by viewModel.isSyncing.collectAsState()
    val folderUri by viewModel.contactsFolderUri.collectAsState()
    val syncInterval by viewModel.syncInterval.collectAsState()
    val lastSyncTime by viewModel.lastSyncTime.collectAsState()
    val syncLogs by viewModel.syncLogs.collectAsState()
    val themeColorLong by viewModel.themeColor.collectAsState()
    val themeColor = Color(themeColorLong)
    
    var showSettings by remember { mutableStateOf(false) }
    var showColorPicker by remember { mutableStateOf(false) }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Box(modifier = Modifier.fillMaxWidth()) {
            Text(
                text = "Contacts Hub",
                style = MaterialTheme.typography.headlineMedium,
                color = themeColor,
                modifier = Modifier.align(Alignment.Center)
            )
            Row(modifier = Modifier.align(Alignment.CenterEnd)) {
                IconButton(onClick = { showColorPicker = true }) {
                    Icon(Icons.Default.Palette, contentDescription = "Theme Color", tint = themeColor)
                }
                IconButton(onClick = { showSettings = true }) {
                    Icon(Icons.Default.Settings, contentDescription = "Settings")
                }
            }
        }

        Spacer(modifier = Modifier.height(16.dp))

        Card(
            modifier = Modifier.fillMaxWidth()
        ) {
            Column(modifier = Modifier.padding(16.dp)) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(text = "VCF Folder:", style = MaterialTheme.typography.labelMedium)
                        Text(
                            text = folderUri?.path ?: "Not Selected", 
                            style = MaterialTheme.typography.bodyMedium
                        )
                    }
                    TextButton(onClick = onSelectFolder, colors = ButtonDefaults.textButtonColors(contentColor = themeColor)) {
                        Text(if (folderUri == null) "Select" else "Change")
                    }
                }
                
                Spacer(modifier = Modifier.height(12.dp))
                
                Text(text = "Last Sync:", style = MaterialTheme.typography.labelMedium)
                Text(
                    text = lastSyncTime?.format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")) ?: "Never", 
                    style = MaterialTheme.typography.bodyMedium
                )
                
                Spacer(modifier = Modifier.height(12.dp))
                
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column {
                        Text(text = "Sync Frequency:", style = MaterialTheme.typography.labelMedium)
                        Text(
                            text = formatInterval(syncInterval), 
                            style = MaterialTheme.typography.bodyMedium
                        )
                    }
                    TextButton(onClick = { showSettings = true }, colors = ButtonDefaults.textButtonColors(contentColor = themeColor)) {
                        Text("Change")
                    }
                }
            }
        }

        if (showSettings) {
            SyncSettingsDialog(
                currentInterval = syncInterval,
                onDismiss = { showSettings = false },
                onConfirm = { viewModel.setSyncInterval(it) }
            )
        }

        if (showColorPicker) {
            ContactColorPickerDialog(
                onDismiss = { showColorPicker = false },
                onColorSelected = { 
                    viewModel.setThemeColor(it)
                    showColorPicker = false
                }
            )
        }

        Spacer(modifier = Modifier.height(24.dp))

        if (isSyncing) {
            CircularProgressIndicator(color = themeColor)
            Text("Syncing Contacts...", modifier = Modifier.padding(top = 8.dp))
        } else {
            Button(
                onClick = { viewModel.triggerSync() },
                modifier = Modifier.fillMaxWidth(),
                enabled = folderUri != null,
                colors = ButtonDefaults.buttonColors(containerColor = themeColor)
            ) {
                Icon(Icons.Default.Sync, contentDescription = null)
                Spacer(Modifier.width(8.dp))
                Text("Sync Now")
            }
            
            Spacer(modifier = Modifier.height(8.dp))
            
            var showNukeDialog by remember { mutableStateOf(false) }
            
            OutlinedButton(
                onClick = { showNukeDialog = true },
                modifier = Modifier.fillMaxWidth(),
                colors = ButtonDefaults.outlinedButtonColors(contentColor = MaterialTheme.colorScheme.error)
            ) {
                Text("Nuke MarkIt Contacts")
            }
            
            if (showNukeDialog) {
                AlertDialog(
                    onDismissRequest = { showNukeDialog = false },
                    title = { Text("Danger Zone") },
                    text = { Text("Delete all contacts created by this app? VCF files will NOT be touched.") },
                    confirmButton = {
                        TextButton(onClick = {
                            viewModel.nukeAndReset()
                            showNukeDialog = false
                        }) { Text("Confirm Nuke") }
                    },
                    dismissButton = {
                        TextButton(onClick = { showNukeDialog = false }) { Text("Cancel") }
                    }
                )
            }
        }

        Spacer(modifier = Modifier.height(16.dp))
        
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text("Contacts Logs", style = MaterialTheme.typography.titleMedium)
            Row {
                TextButton(onClick = { viewModel.exportLogs() }, colors = ButtonDefaults.textButtonColors(contentColor = themeColor)) {
                    Text("Export")
                }
                TextButton(onClick = { viewModel.clearLogs() }, colors = ButtonDefaults.textButtonColors(contentColor = themeColor)) {
                    Text("Clear")
                }
            }
        }
        
        LazyColumn(
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f)
                .padding(top = 8.dp)
        ) {
            items(syncLogs) { log ->
                Text(
                    text = log,
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.padding(vertical = 2.dp)
                )
                Divider()
            }
        }
    }
}

@Composable
fun ContactColorPickerDialog(
    onDismiss: () -> Unit,
    onColorSelected: (Long) -> Unit
) {
    val colors = listOf(
        0xFF4CAF50 to "Green",
        0xFF2E7D32 to "Dark Green",
        0xFF8BC34A to "Light Green",
        0xFFCDDC39 to "Lime",
        0xFF009688 to "Teal",
        0xFF388E3C to "Forest"
    )

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Pick Entity Theme") },
        text = {
            Column {
                colors.chunked(3).forEach { row ->
                    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) {
                        row.forEach { (colorValue, name) ->
                            Box(
                                modifier = Modifier
                                    .size(50.dp)
                                    .background(Color(colorValue), CircleShape)
                                    .clickable { onColorSelected(colorValue) }
                            )
                        }
                    }
                    Spacer(modifier = Modifier.height(8.dp))
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) { Text("Close") }
        }
    )
}

private fun formatInterval(minutes: Long): String {
    return when {
        minutes == 0L -> "Manual"
        minutes < 60L -> "$minutes Minutes"
        minutes == 60L -> "1 Hour"
        minutes == 1440L -> "1 Day"
        minutes % 1440L == 0L -> "${minutes / 1440L} Days"
        else -> "$minutes Minutes"
    }
}
