package com.waph1.markithub.util

import com.waph1.markithub.model.CalendarEvent
import org.junit.Assert.*
import org.junit.Test
import java.time.LocalDateTime

class YamlConverterTest {

    @Test
    fun testParseMarkdownWithYaml() {
        val content = """
            ---
            title: "Test Event"
            start: "2023-10-27T10:00:00"
            end: "2023-10-27T11:00:00"
            all_day: false
            location: "Office"
            ---
            
            This is the body of the event.
        """.trimIndent()

        val event = YamlConverter.parseMarkdown(content, "test.md", "Work")

        assertEquals("Test Event", event.title)
        assertEquals(LocalDateTime.of(2023, 10, 27, 10, 0), event.start)
        assertEquals(LocalDateTime.of(2023, 10, 27, 11, 0), event.end)
        assertFalse(event.isAllDay)
        assertEquals("Office", event.location)
        assertEquals("This is the body of the event.", event.body)
    }

    @Test
    fun testParseMarkdownWithCustomDate() {
        val content = """
            ---
            title: "Custom Date Event"
            start: "2023-10-27 10:30"
            ---
        """.trimIndent()

        val event = YamlConverter.parseMarkdown(content, "test.md", "Work")

        assertEquals("Custom Date Event", event.title)
        assertEquals(LocalDateTime.of(2023, 10, 27, 10, 30), event.start)
    }

    @Test
    fun testParseMarkdownWithReminderKey() {
        val content = """
            ---
            reminder: "2023-10-27 12:00"
            ---
        """.trimIndent()

        val event = YamlConverter.parseMarkdown(content, "Test File.md", "Tasks")

        assertEquals("Test File", event.title)
        assertEquals(LocalDateTime.of(2023, 10, 27, 12, 0), event.start)
    }

    @Test
    fun testParseMarkdownNoDate() {
        val content = """
            ---
            title: "No Date Event"
            ---
        """.trimIndent()

        val event = YamlConverter.parseMarkdown(content, "test.md", "Work")

        assertEquals("No Date Event", event.title)
        assertNull(event.start)
        assertNull(event.end)
    }

    @Test
    fun testToMarkdown() {
        val event = CalendarEvent(
            title = "Another Event",
            start = LocalDateTime.of(2023, 10, 28, 14, 0),
            end = LocalDateTime.of(2023, 10, 28, 15, 0),
            isAllDay = true,
            location = "Home",
            body = "Event description here.",
            calendarName = "Personal"
        )

        val markdown = YamlConverter.toMarkdown(event)

        assertTrue(markdown.contains("title: \"Another Event\""))
        assertTrue(markdown.contains("all_day: true"))
        assertTrue(markdown.contains("location: \"Home\""))
        assertTrue(markdown.contains("Event description here."))
        assertTrue(markdown.contains("2023-10-28 14:00"))
        assertTrue(markdown.contains("reminder: \"2023-10-28 14:00\"")) // MarkIt compatibility
        assertTrue(markdown.contains("start: \"2023-10-28 14:00\""))
    }

    @Test
    fun testToMarkdownNoDate() {
        val event = CalendarEvent(
            title = "Simple Note",
            body = "Just a note.",
            calendarName = "Notes"
        )

        val markdown = YamlConverter.toMarkdown(event)

        assertTrue(markdown.contains("title: \"Simple Note\""))
        assertFalse(markdown.contains("start:"))
        assertFalse(markdown.contains("reminder:"))
        assertTrue(markdown.contains("Just a note."))
    }}
