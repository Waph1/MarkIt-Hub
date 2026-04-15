package com.waph1.markithub.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Palette
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.waph1.markithub.viewmodel.SyncViewModel
import com.waph1.markithub.viewmodel.SyncStatus
import java.time.format.DateTimeFormatter

@Composable
fun SyncDashboardScreen(
    viewModel: SyncViewModel = viewModel(),
    onSelectRootFolder: () -> Unit,
    onSelectTaskFolder: () -> Unit
) {
    val syncStatus by viewModel.syncStatus.collectAsState()
    val lastSyncTime by viewModel.lastSyncTime.collectAsState()
    val syncLogs by viewModel.syncLogs.collectAsState()
    val rootFolder by viewModel.rootUri.collectAsState()
    val taskFolder by viewModel.taskRootUri.collectAsState()
    val syncInterval by viewModel.syncInterval.collectAsState()
    val isSyncthingEnabled by viewModel.isSyncthingEnabled.collectAsState()
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
                text = "Calendar Hub",
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
                        Text(text = "Calendar Folder:", style = MaterialTheme.typography.labelMedium)
                        Text(
                            text = rootFolder?.path ?: "Not Selected", 
                            style = MaterialTheme.typography.bodyMedium
                        )
                    }
                    TextButton(onClick = onSelectRootFolder, colors = ButtonDefaults.textButtonColors(contentColor = themeColor)) {
                        Text(if (rootFolder == null) "Select" else "Change")
                    }
                }
                
                Spacer(modifier = Modifier.height(12.dp))

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(text = "Tasks (MarkIt) Folder:", style = MaterialTheme.typography.labelMedium)
                        Text(
                            text = taskFolder?.path ?: "Not Selected", 
                            style = MaterialTheme.typography.bodyMedium
                        )
                    }
                    TextButton(onClick = onSelectTaskFolder, colors = ButtonDefaults.textButtonColors(contentColor = themeColor)) {
                        Text(if (taskFolder == null) "Select" else "Change")
                    }
                }
                
                Spacer(modifier = Modifier.height(12.dp))
                
                Text(text = "Last Sync:", style = MaterialTheme.typography.labelMedium)
                Text(
                    text = lastSyncTime?.format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")) ?: "Never", 
                    style = MaterialTheme.typography.bodyMedium
                )
                
                Spacer(modifier = Modifier.height(8.dp))
                
                Text(text = "Sync Frequency:", style = MaterialTheme.typography.labelMedium)
                Text(
                    text = if (syncInterval == 0L) "Manual" else "$syncInterval minutes", 
                    style = MaterialTheme.typography.bodyMedium
                )
            }
        }

        if (showSettings) {
            SyncSettingsDialog(
                currentInterval = syncInterval,
                isSyncthingEnabled = isSyncthingEnabled,
                onDismiss = { showSettings = false },
                onConfirm = { interval, syncthing -> 
                    viewModel.setSyncInterval(interval)
                    viewModel.setSyncthingEnabled(syncthing)
                }
            )
        }

        if (showColorPicker) {
            ColorPickerDialog(
                onDismiss = { showColorPicker = false },
                onColorSelected = { 
                    viewModel.setThemeColor(it)
                    showColorPicker = false
                }
            )
        }

        Spacer(modifier = Modifier.height(24.dp))

        if (syncStatus is SyncStatus.Syncing) {
            CircularProgressIndicator(color = themeColor)
            Text("Syncing...", modifier = Modifier.padding(top = 8.dp))
        } else {
            Button(
                onClick = { viewModel.triggerSync() },
                modifier = Modifier.fillMaxWidth(),
                colors = ButtonDefaults.buttonColors(containerColor = themeColor)
            ) {
                Text("Sync Now")
            }
            
            Spacer(modifier = Modifier.height(8.dp))
            
            var showNukeDialog by remember { mutableStateOf(false) }
            
            OutlinedButton(
                onClick = { showNukeDialog = true },
                modifier = Modifier.fillMaxWidth(),
                colors = ButtonDefaults.outlinedButtonColors(contentColor = MaterialTheme.colorScheme.error)
            ) {
                Text("Nuke & Reset Calendars")
            }
            
            if (showNukeDialog) {
                AlertDialog(
                    onDismissRequest = { showNukeDialog = false },
                    title = { Text("Danger Zone") },
                    text = { Text("Delete all calendars created by this app? Markdown files will NOT be touched.") },
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
        
        var showVerboseLogs by remember { mutableStateOf(false) }

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text("Calendar Logs", style = MaterialTheme.typography.titleMedium)
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("Verbose", style = MaterialTheme.typography.bodySmall)
                Switch(
                    checked = showVerboseLogs,
                    onCheckedChange = { showVerboseLogs = it },
                    modifier = Modifier.padding(horizontal = 4.dp).scale(0.8f)
                )
                TextButton(onClick = { viewModel.exportLogs() }, colors = ButtonDefaults.textButtonColors(contentColor = themeColor), contentPadding = PaddingValues(horizontal = 8.dp)) {
                    Text("Export")
                }
                TextButton(onClick = { viewModel.clearLogs() }, colors = ButtonDefaults.textButtonColors(contentColor = themeColor), contentPadding = PaddingValues(horizontal = 8.dp)) {
                    Text("Clear")
                }
            }
        }
        
        val filteredLogs = remember(syncLogs, showVerboseLogs) {
            if (showVerboseLogs) {
                syncLogs
            } else {
                val excludedKeywords = listOf("Evaluating", "Parsed", "Parsing", "Skipping", "skipped", "Merging", "Copying", "Cleaning", "Saving", "Updating", "Deleting", "Removing", "Provider event dirty", "Applying batch", "File missing", "Could not find file", "Could not read content")
                syncLogs.filter { logLine ->
                    excludedKeywords.none { keyword -> logLine.contains(keyword, ignoreCase = true) }
                }
            }
        }
        
        LazyColumn(
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f)
                .padding(top = 8.dp)
        ) {
            items(filteredLogs) { log ->
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
fun ColorPickerDialog(
    onDismiss: () -> Unit,
    onColorSelected: (Long) -> Unit
) {
    val colors = listOf(
        0xFF2196F3 to "Blue",
        0xFFF44336 to "Red",
        0xFFFF9800 to "Orange",
        0xFF9C27B0 to "Purple",
        0xFF00BCD4 to "Cyan",
        0xFF607D8B to "Grey"
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
