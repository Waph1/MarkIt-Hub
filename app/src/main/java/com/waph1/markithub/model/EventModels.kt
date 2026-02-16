package com.waph1.markithub.model

import java.time.LocalDateTime

data class CalendarEvent(
    val title: String,
    val start: LocalDateTime? = null,
    val end: LocalDateTime? = null,
    val isAllDay: Boolean = false,
    val location: String? = null,
    val attendees: List<String> = emptyList(),
    val reminders: List<Int> = emptyList(), // Minutes before
    val timezone: String? = null,
    val color: Int? = null,
    val tags: List<String> = emptyList(),
    val recurrenceRule: String? = null,
    val overrideId: String? = null,
    val body: String = "",
    val fileName: String? = null,
    val calendarName: String = "Default",
    val systemEventId: Long? = null,
    val needsUpdate: Boolean = false,
    val metadata: Map<String, Any> = emptyMap()
)