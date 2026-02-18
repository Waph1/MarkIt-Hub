package com.waph1.markithub.util

import com.waph1.markithub.model.CalendarEvent
import android.content.Context
import android.net.Uri
import org.snakeyaml.engine.v2.api.Dump
import org.snakeyaml.engine.v2.api.DumpSettings
import org.snakeyaml.engine.v2.api.Load
import org.snakeyaml.engine.v2.api.LoadSettings
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter

object YamlConverter {
    private val isoFormatter = DateTimeFormatter.ISO_LOCAL_DATE_TIME
    private val customFormatter = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm")

    private fun parseDateTime(dateTimeStr: String?): LocalDateTime? {
        if (dateTimeStr == null) return null
        val cleaned = dateTimeStr.trim().removeSurrounding("\"").removeSurrounding("'")
        return try {
            LocalDateTime.parse(cleaned, isoFormatter)
        } catch (e: Exception) {
            try {
                LocalDateTime.parse(cleaned, customFormatter)
            } catch (e2: Exception) {
                null
            }
        }
    }

    fun hasRequiredMetadata(uri: Uri, context: Context): Boolean {
        return try {
            val content = context.contentResolver.openInputStream(uri)?.use { reader -> reader.bufferedReader().readText() } ?: return false
            val regex = Regex("""^---\s*\n(.*?)\n---\s*""", RegexOption.DOT_MATCHES_ALL)
            val matchResult = regex.find(content.trim()) ?: return false
            val yamlContent = matchResult.groups[1]?.value ?: return false
            
            // Minimal check: should have reminder/start and title
            (yamlContent.contains("reminder:") || yamlContent.contains("start:")) && yamlContent.contains("title:")
        } catch (e: Exception) {
            false
        }
    }

    fun parseMarkdown(content: String, fileName: String, calendarName: String): CalendarEvent {
        val regex = Regex("""^---\s*\n(.*?)\n---\s*(?:\n(.*))?$""", RegexOption.DOT_MATCHES_ALL)
        val matchResult = regex.find(content.trim())

        var yamlContent = matchResult?.groups?.get(1)?.value
        var body = if (matchResult != null) {
            matchResult.groups[2]?.value?.trim() ?: ""
        } else {
            content.trim()
        }

        // Handle case where YAML delimiters are missing but metadata lines exist in body
        if (yamlContent == null) {
            val lines = body.lines()
            val metaLines = mutableListOf<String>()
            val remainingBody = mutableListOf<String>()
            val knownKeys = setOf("reminder:", "title:", "all_day:", "location:", "timezone:", "color:", "attendees:", "reminders:", "tags:", "recurrence:", "system_id:", "start:", "end:")
            
            var parsingMeta = true
            for (line in lines) {
                val trimmed = line.trim()
                if (parsingMeta && knownKeys.any { trimmed.startsWith(it, ignoreCase = true) }) {
                    metaLines.add(line)
                } else {
                    if (trimmed.isNotEmpty()) parsingMeta = false
                    remainingBody.add(line)
                }
            }
            
            if (metaLines.isNotEmpty()) {
                yamlContent = metaLines.joinToString("\n")
                body = remainingBody.joinToString("\n").trim()
            }
        }

        val yamlMap = if (yamlContent != null) {
            try {
                val loadSettings = LoadSettings.builder().build()
                val load = Load(loadSettings)
                (load.loadFromString(yamlContent) as? Map<*, *>)?.mapKeys { it.key.toString() } ?: emptyMap()
            } catch (e: Exception) {
                emptyMap()
            }
        } else {
            emptyMap()
        }

        val title = yamlMap["title"]?.toString() ?: fileName.substringBeforeLast(".md").replace("_", " ")
        
        // Universal 'reminder' key takes precedence
        val dateStr = yamlMap["reminder"]?.toString() ?: yamlMap["start"]?.toString()
        val endStr = yamlMap["end"]?.toString()
        
        var allDay = yamlMap["all_day"]?.toString()?.toBoolean() ?: false
        
        val start = if (dateStr != null && dateStr.length <= 10) {
            allDay = true
            try { java.time.LocalDate.parse(dateStr).atStartOfDay() } catch (e: Exception) { null }
        } else parseDateTime(dateStr)
        
        val end = parseDateTime(endStr)
        
        val location = yamlMap["location"]?.toString()
                val timezone = yamlMap["timezone"]?.toString()
                val colorStr = yamlMap["color"]?.toString()
                
                val attendees = (yamlMap["attendees"] as? List<*>)?.filterIsInstance<String>() ?: emptyList()
                val reminders = (yamlMap["reminders"] as? List<*>)?.filterIsInstance<Int>() ?: emptyList()
        
                val color = colorStr?.let { 
                    try { android.graphics.Color.parseColor(it) } catch (e: Exception) { null } 
                }
        val knownKeys = setOf(
            "title", "start", "end", "all_day", "location", "timezone", 
            "color", "attendees", "reminders", "tags", "recurrence", 
            "override_id", "system_id", "reminder"
        )
        val metadata = yamlMap.filterKeys { it !in knownKeys }.mapValues { it.value ?: "" }

        return CalendarEvent(
            title = title,
            start = start,
            end = end,
            isAllDay = allDay,
            location = location,
            attendees = attendees,
            reminders = reminders,
            timezone = timezone,
            color = color,
            tags = (yamlMap["tags"] as? List<*>)?.filterIsInstance<String>() ?: emptyList(),
            recurrenceRule = yamlMap["recurrence"]?.toString(),
            overrideId = yamlMap["override_id"]?.toString(),
            body = body,
            fileName = fileName,
            calendarName = calendarName,
            systemEventId = (yamlMap["system_id"] as? Number)?.toLong() ?: (yamlMap["system_id"] as? String)?.toLongOrNull(),
            metadata = metadata
        )
    }

    fun toMarkdown(event: CalendarEvent): String {
        return buildString {
            append("---\n")
            
            // color format: "#AARRGGBB"
            val colorInt = event.color ?: 0xFFFFFFFF.toInt()
            append("color: \"#${String.format("%08X", colorInt).takeLast(8)}\"\n")
            
            // Universal Date: Use 'reminder' as primary key
            event.start?.let {
                val formattedDate = it.format(customFormatter)
                append("reminder: \"$formattedDate\"\n")
            }
            event.end?.let { append("end: \"${it.format(customFormatter)}\"\n") }
            
            append("title: ${quoteValue(event.title)}\n")
            append("all_day: ${event.isAllDay}\n")
            
            event.location?.let { append("location: ${quoteValue(it)}\n") }
            event.timezone?.let { append("timezone: \"$it\"\n") }
            
            if (event.tags.isNotEmpty()) {
                append("tags: [${event.tags.joinToString(", ") { quoteValue(it) }}]\n")
            }
            if (event.attendees.isNotEmpty()) {
                append("attendees: [${event.attendees.joinToString(", ") { quoteValue(it) }}]\n")
            }
            
            event.recurrenceRule?.let { append("recurrence: \"$it\"\n") }
            event.systemEventId?.let { append("system_id: $it\n") }
            
            // Flatten extra metadata
            event.metadata.forEach { (k, v) ->
                if (v !is Map<*, *> && v !is List<*>) {
                    append("$k: ${quoteValue(v.toString())}\n")
                }
            }
            
            append("---\n")
            if (event.body.isNotBlank()) {
                append("\n${event.body}")
            }
        }
    }

    private fun quoteValue(value: String): String {
        val escaped = value.replace("\"", "\\\"")
        return "\"$escaped\""
    }

    fun removeCalendarData(content: String): String {
        val regex = Regex("""^---\s*\n(.*?)\n---\s*(?:\n(.*))?$""", RegexOption.DOT_MATCHES_ALL)
        val matchResult = regex.find(content.trim()) ?: return content

        val yamlContent = matchResult.groups[1]?.value ?: return content
        val body = matchResult.groups[2]?.value ?: ""

        val lines = yamlContent.lines()
        val keysToRemove = setOf(
            "start", "end", "reminder", "system_id", "recurrence", "override_id"
        )
        
        val newYamlLines = lines.filter { line ->
            val key = line.substringBefore(":").trim()
            key !in keysToRemove
        }

        if (newYamlLines.all { it.isBlank() }) {
            return body.trim()
        }

        return "---\n${newYamlLines.joinToString("\n")}\n---\n\n${body.trim()}"
    }
}
