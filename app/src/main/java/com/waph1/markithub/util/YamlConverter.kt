package com.waph1.markithub.util

import com.waph1.markithub.model.CalendarEvent
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

    fun parseMarkdown(content: String, fileName: String, calendarName: String): CalendarEvent {
        val regex = Regex("""^---\s*\n(.*?)\n---\s*(?:\n(.*))?$""", RegexOption.DOT_MATCHES_ALL)
        val matchResult = regex.find(content.trim())

        val yamlContent = matchResult?.groups?.get(1)?.value
        val body = matchResult?.groups?.get(2)?.value?.trim() ?: (if (yamlContent == null) content.trim() else "")

        val yamlMap = if (yamlContent != null) {
            try {
                val loadSettings = LoadSettings.builder().build()
                val load = Load(loadSettings)
                (load.loadFromString(yamlContent) as? Map<String, Any>) ?: emptyMap()
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
        val allDay = yamlMap["all_day"]?.toString()?.toBoolean() ?: false
        val location = yamlMap["location"]?.toString()
        val timezone = yamlMap["timezone"]?.toString()
        val colorStr = yamlMap["color"]?.toString()
        
        val attendees = (yamlMap["attendees"] as? List<String>) ?: emptyList()
        val reminders = (yamlMap["reminders"] as? List<Int>) ?: emptyList()

        val start = parseDateTime(dateStr)
        val end = parseDateTime(endStr)

        val color = colorStr?.let { 
            try { android.graphics.Color.parseColor(it) } catch (e: Exception) { null } 
        }

        val knownKeys = setOf(
            "title", "start", "end", "all_day", "location", "timezone", 
            "color", "attendees", "reminders", "tags", "recurrence", 
            "override_id", "system_id", "reminder"
        )
        val metadata = yamlMap.filterKeys { it !in knownKeys }

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
            tags = (yamlMap["tags"] as? List<String>) ?: emptyList(),
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
            
            append("---\n\n")
            append(event.body)
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
