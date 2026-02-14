package com.waph1.markithub.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.DatePicker
import androidx.compose.material3.DatePickerDialog
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TextField
import androidx.compose.material3.rememberDatePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter

enum class RecurrenceFrequency { DAILY, WEEKLY, MONTHLY, YEARLY }

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun RecurrenceEditorDialog(
    onDismiss: () -> Unit,
    onConfirm: (String) -> Unit
) {
    var frequency by remember { mutableStateOf(RecurrenceFrequency.DAILY) }
    var interval by remember { mutableStateOf("1") }
    val weekDays = remember { mutableStateMapOf<DayOfWeek, Boolean>() }
    var untilDate by remember { mutableStateOf<LocalDate?>(null) }

    var showDatePicker by remember { mutableStateOf(false) }
    val datePickerState = rememberDatePickerState()

    fun buildRRuleString(): String {
        val sb = StringBuilder()
        sb.append("FREQ=${frequency.name}")
        
        val intVal = interval.toIntOrNull() ?: 1
        if (intVal > 1) {
            sb.append(";INTERVAL=$intVal")
        }
        
        if (frequency == RecurrenceFrequency.WEEKLY && weekDays.any { it.value }) {
            val days = weekDays.filter { it.value }.keys.sorted()
            val dayString = days.joinToString(",") { 
                it.name.take(2) 
            }
            sb.append(";BYDAY=$dayString")
        }
        
        untilDate?.let {
             val formatter = DateTimeFormatter.ofPattern("yyyyMMdd")
             sb.append(";UNTIL=${it.format(formatter)}")
        }
        
        return sb.toString()
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Edit Recurrence") },
        text = {
            Column {
                FrequencySelector(frequency) { frequency = it }
                Spacer(Modifier.height(8.dp))
                
                if (frequency == RecurrenceFrequency.WEEKLY) {
                    WeekDaySelector(weekDays)
                    Spacer(Modifier.height(8.dp))
                }

                TextField(
                    value = interval,
                    onValueChange = { if (it.all { char -> char.isDigit() }) interval = it },
                    label = { Text("Repeat every") },
                    suffix = { Text(when(frequency) {
                        RecurrenceFrequency.DAILY -> " day(s)"
                        RecurrenceFrequency.WEEKLY -> " week(s)"
                        RecurrenceFrequency.MONTHLY -> " month(s)"
                        RecurrenceFrequency.YEARLY -> " year(s)"
                    }) }
                )
                Spacer(Modifier.height(16.dp))
                
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("Ends")
                    Spacer(Modifier.weight(1f))
                    Button(onClick = { showDatePicker = true }) {
                        Text(untilDate?.toString() ?: "Never")
                    }
                }
            }
        },
        confirmButton = { Button(onClick = { onConfirm(buildRRuleString()) }) { Text("Done") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } }
    )

    if (showDatePicker) {
        DatePickerDialog(
            onDismissRequest = { showDatePicker = false },
            confirmButton = {
                TextButton(onClick = {
                    datePickerState.selectedDateMillis?.let {
                        untilDate = java.time.Instant.ofEpochMilli(it).atZone(ZoneId.systemDefault()).toLocalDate()
                    }
                    showDatePicker = false
                }) { Text("OK") }
            },
            dismissButton = { TextButton(onClick = { showDatePicker = false }) { Text("Cancel") } }
        ) {
            DatePicker(state = datePickerState)
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun FrequencySelector(
    selected: RecurrenceFrequency,
    onSelect: (RecurrenceFrequency) -> Unit
) {
    var expanded by remember { mutableStateOf(false) }
    ExposedDropdownMenuBox(expanded = expanded, onExpandedChange = { expanded = !expanded }) {
        TextField(
            value = selected.name,
            onValueChange = {},
            readOnly = true,
            label = { Text("Frequency") },
            trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = expanded) },
            modifier = Modifier.menuAnchor().fillMaxWidth()
        )
        ExposedDropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            RecurrenceFrequency.values().forEach { freq ->
                DropdownMenuItem(text = { Text(freq.name) }, onClick = {
                    onSelect(freq)
                    expanded = false
                })
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun WeekDaySelector(
    selectedDays: MutableMap<DayOfWeek, Boolean>
) {
    val days = listOf(
        DayOfWeek.SUNDAY, DayOfWeek.MONDAY, DayOfWeek.TUESDAY, 
        DayOfWeek.WEDNESDAY, DayOfWeek.THURSDAY, DayOfWeek.FRIDAY, DayOfWeek.SATURDAY
    )
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) {
        days.forEach { day ->
            val isSelected = selectedDays[day] ?: false
            FilterChip(
                selected = isSelected,
                onClick = { selectedDays[day] = !isSelected },
                label = { Text(day.name.take(1)) }
            )
        }
    }
}
