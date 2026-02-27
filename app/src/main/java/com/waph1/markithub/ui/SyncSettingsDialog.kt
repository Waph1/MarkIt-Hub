package com.waph1.markithub.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp

@Composable
fun SyncSettingsDialog(
    currentInterval: Long,
    onDismiss: () -> Unit,
    onConfirm: (Long) -> Unit
) {
    var customInterval by remember { mutableStateOf(if (currentInterval !in listOf(0L, 15L, 30L, 60L, 1440L)) currentInterval.toString() else "") }
    var selectedOption by remember { mutableStateOf(if (currentInterval in listOf(0L, 15L, 30L, 60L, 1440L)) currentInterval else -1L) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Sync Settings") },
        text = {
            Column {
                Text("Sync Frequency:")
                Spacer(modifier = Modifier.height(8.dp))
                
                val options = listOf(
                    0L to "Manual", 
                    15L to "15 Minutes", 
                    30L to "30 Minutes", 
                    60L to "1 Hour",
                    1440L to "1 Day"
                )
                
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
                    customInterval.toLongOrNull() ?: 15L
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
