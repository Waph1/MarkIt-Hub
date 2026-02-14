package com.waph1.markithub.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Settings
import androidx.compose.foundation.clickable
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.waph1.markithub.viewmodel.SyncViewModel
import com.waph1.markithub.viewmodel.SyncStatus
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter

@Composable
fun SyncDashboardScreen(
    viewModel: SyncViewModel = viewModel(),
    onSelectTaskFolder: () -> Unit
) {
    val syncStatus by viewModel.syncStatus.collectAsState()
    val lastSyncTime by viewModel.lastSyncTime.collectAsState()
    val syncLogs by viewModel.syncLogs.collectAsState()
    val rootFolder by viewModel.rootUri.collectAsState()
    val taskFolder by viewModel.taskRootUri.collectAsState()
    val syncInterval by viewModel.syncInterval.collectAsState()
    
    var showSettings by remember { mutableStateOf(false) }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Box(modifier = Modifier.fillMaxWidth()) {
            Text(
                text = "MarkIt Hub",
                style = MaterialTheme.typography.headlineMedium,
                modifier = Modifier.align(Alignment.Center)
            )
            IconButton(
                onClick = { showSettings = true },
                modifier = Modifier.align(Alignment.CenterEnd)
            ) {
                Icon(
                    imageVector = androidx.compose.material.icons.Icons.Default.Settings,
                    contentDescription = "Settings"
                )
            }
        }
        
        Spacer(modifier = Modifier.height(16.dp))

        Card(
            modifier = Modifier.fillMaxWidth()
        ) {
            Column(modifier = Modifier.padding(16.dp)) {
                Text(text = "Root Folder (Calendars):", style = MaterialTheme.typography.labelMedium)
                Text(text = rootFolder?.path ?: "Not Selected", style = MaterialTheme.typography.bodyMedium)
                
                Spacer(modifier = Modifier.height(12.dp))

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(text = "Task Folder (MarkIt):", style = MaterialTheme.typography.labelMedium)
                        Text(
                            text = taskFolder?.path ?: "Not Selected", 
                            style = MaterialTheme.typography.bodyMedium
                        )
                    }
                    TextButton(onClick = onSelectTaskFolder) {
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
                onDismiss = { showSettings = false },
                onConfirm = { viewModel.setSyncInterval(it) }
            )
        }

        Spacer(modifier = Modifier.height(24.dp))

        if (syncStatus is SyncStatus.Syncing) {
            CircularProgressIndicator()
            Text("Syncing...", modifier = Modifier.padding(top = 8.dp))
        } else {
            Button(
                onClick = { viewModel.triggerSync() },
                modifier = Modifier.fillMaxWidth()
            ) {
                Text("Sync Now")
            }
        }

        Spacer(modifier = Modifier.height(16.dp))
        
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text("Sync Logs", style = MaterialTheme.typography.titleMedium)
            TextButton(onClick = { viewModel.clearLogs() }) {
                Text("Clear")
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
fun SyncSettingsDialog(
    currentInterval: Long,
    onDismiss: () -> Unit,
    onConfirm: (Long) -> Unit
) {
    var customInterval by remember { mutableStateOf(if (currentInterval !in listOf(0L, 15L, 30L, 60L)) currentInterval.toString() else "") }
    var selectedOption by remember { mutableStateOf(if (currentInterval in listOf(0L, 15L, 30L, 60L)) currentInterval else -1L) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Sync Settings") },
        text = {
            Column {
                Text("Sync Frequency:")
                Spacer(modifier = Modifier.height(8.dp))
                
                val options = listOf(0L to "Manual", 15L to "15 Minutes", 30L to "30 Minutes", 60L to "1 Hour")
                
                options.forEach { (value, label) ->
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 4.dp)
                            .clickable { 
                                selectedOption = value
                                customInterval = ""
                            }
                    ) {
                        RadioButton(
                            selected = selectedOption == value,
                            onClick = { 
                                selectedOption = value
                                customInterval = ""
                            }
                        )
                        Text(text = label, modifier = Modifier.padding(start = 8.dp))
                    }
                }

                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 4.dp)
                        .clickable { selectedOption = -1L }
                ) {
                    RadioButton(
                        selected = selectedOption == -1L,
                        onClick = { selectedOption = -1L }
                    )
                    Text(text = "Custom (min):", modifier = Modifier.padding(start = 8.dp))
                    Spacer(modifier = Modifier.width(8.dp))
                    OutlinedTextField(
                        value = customInterval,
                        onValueChange = { 
                            if (it.all { char -> char.isDigit() }) {
                                customInterval = it
                                selectedOption = -1L
                            }
                        },
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                        enabled = true,
                        modifier = Modifier.weight(1f)
                    )
                }
            }
        },
        confirmButton = {
            TextButton(onClick = {
                val interval = if (selectedOption != -1L) {
                    selectedOption
                } else {
                    customInterval.toLongOrNull() ?: 15L // Default to 15 if invalid
                }
                onConfirm(interval)
                onDismiss()
            }) {
                Text("Save")
            }
        },
        dismissButton = {
             TextButton(onClick = onDismiss) {
                Text("Cancel")
            }
        }
    )
}