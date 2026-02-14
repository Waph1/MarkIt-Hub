package com.waph1.markithub.util

import com.waph1.markithub.model.CalendarEvent
import org.snakeyaml.engine.v2.api.Dump
import org.snakeyaml.engine.v2.api.DumpSettings
import org.snakeyaml.engine.v2.api.Load
import org.snakeyaml.engine.v2.api.LoadSettings
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter

object YamlConverter {
    private val dateTimeFormatter = DateTimeFormatter.ISO_LOCAL_DATE_TIME

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

        // Fallback or parsing logic
        val title = yamlMap["title"] as? String ?: fileName.substringBeforeLast(".md").replace("_", " ")
        
        val startStr = yamlMap["start"] as? String
        val endStr = yamlMap["end"] as? String
        val allDay = yamlMap["all_day"] as? Boolean ?: false
        val location = yamlMap["location"] as? String
        val timezone = yamlMap["timezone"] as? String
        val colorStr = yamlMap["color"] as? String
        val colorInt = (yamlMap["color"] as? Number)?.toInt()
        val attendees = (yamlMap["attendees"] as? List<String>) ?: emptyList()
        val reminders = (yamlMap["reminders"] as? List<Int>) ?: emptyList()

        val start = try {
            if (startStr != null) LocalDateTime.parse(startStr, dateTimeFormatter) else LocalDateTime.now()
        } catch (e: Exception) { LocalDateTime.now() }

        val end = try {
            if (endStr != null) LocalDateTime.parse(endStr, dateTimeFormatter) else start.plusHours(1)
        } catch (e: Exception) { start.plusHours(1) }

        val color = colorInt ?: colorStr?.let { 
            try { android.graphics.Color.parseColor(it) } catch (e: Exception) { null } 
        }

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
            recurrenceRule = yamlMap["recurrence"] as? String,
            overrideId = yamlMap["override_id"] as? String,
            body = body,
            fileName = fileName,
            calendarName = calendarName,
            systemEventId = (yamlMap["system_id"] as? Number)?.toLong() ?: (yamlMap["system_id"] as? String)?.toLongOrNull()
        )
    }

    fun toMarkdown(event: CalendarEvent): String {
        val yamlMap = mutableMapOf<String, Any>()
        yamlMap["title"] = event.title
        yamlMap["start"] = event.start.format(dateTimeFormatter)
        yamlMap["end"] = event.end.format(dateTimeFormatter)
        yamlMap["all_day"] = event.isAllDay
        event.location?.let { yamlMap["location"] = it }
        event.timezone?.let { yamlMap["timezone"] = it }
        event.color?.let { yamlMap["color"] = String.format("#%06X", (0xFFFFFF and it)) }
        if (event.attendees.isNotEmpty()) yamlMap["attendees"] = event.attendees
        if (event.reminders.isNotEmpty()) yamlMap["reminders"] = event.reminders
        
        if (event.tags.isNotEmpty()) yamlMap["tags"] = event.tags
        event.recurrenceRule?.let { yamlMap["recurrence"] = it }
        event.overrideId?.let { yamlMap["override_id"] = it }
        event.systemEventId?.let { yamlMap["system_id"] = it }

        val dumpSettings = DumpSettings.builder().build()
        val dump = Dump(dumpSettings)
        val yamlString = dump.dumpToString(yamlMap)

        return "---\n${yamlString}---\n\n${event.body}"
    }
}