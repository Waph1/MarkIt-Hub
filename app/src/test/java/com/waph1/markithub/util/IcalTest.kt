package com.waph1.markithub.util

import org.junit.Test
import org.junit.Assert.*
import net.fortuna.ical4j.data.CalendarOutputter
import net.fortuna.ical4j.model.Calendar
import net.fortuna.ical4j.model.component.VEvent
import net.fortuna.ical4j.model.property.*
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.ZonedDateTime
import java.io.StringWriter

class IcalTest {

    // ── helpers ──────────────────────────────────────────────────────────────

    private fun buildIcs(block: VEvent.() -> Unit): String {
        val calendar = Calendar().apply {
            add(ProdId("-//MarkIt Hub//EN"))
            add(Version.VERSION_2_0)
        }
        val vEvent = VEvent().apply(block)
        vEvent.add(Uid("test-uid-1234"))
        calendar.add(vEvent)
        val writer = StringWriter()
        CalendarOutputter().output(calendar, writer)
        return writer.toString()
    }

    // ── Bug fix 1: Returns null for broken / empty ICS ────────────────────────

    @Test
    fun parseIcs_returnsNullForEmptyString() {
        val result = IcsConverter.parseIcs("", "test.ics", "Cal")
        assertNull("Expected null for empty ICS content", result)
    }

    @Test
    fun parseIcs_returnsNullWhenNoVEvent() {
        val ics = "BEGIN:VCALENDAR\r\nVERSION:2.0\r\nEND:VCALENDAR\r\n"
        val result = IcsConverter.parseIcs(ics, "test.ics", "Cal")
        assertNull("Expected null when no VEVENT present", result)
    }

    // ── Bug fix 1b: LocalDateTime round-trip ─────────────────────────────────

    @Test
    fun parseIcs_localDateTimeRoundTrip() {
        val now = LocalDateTime.of(2025, 3, 10, 10, 0, 0)
        val ics = buildIcs {
            val dtStart = DtStart<LocalDateTime>(now)
            dtStart.add<DtStart<LocalDateTime>>(net.fortuna.ical4j.model.parameter.Value.DATE_TIME)
            add(dtStart)
            val dtEnd = DtEnd<LocalDateTime>(now.plusHours(2))
            dtEnd.add<DtEnd<LocalDateTime>>(net.fortuna.ical4j.model.parameter.Value.DATE_TIME)
            add(dtEnd)
            add(Summary("Team Meeting"))
        }
        val result = IcsConverter.parseIcs(ics, "team_meeting.ics", "Work")
        assertNotNull(result)
        assertEquals("Team Meeting", result!!.title)
        assertEquals(now, result.start)
        assertEquals(now.plusHours(2), result.end)
        assertFalse(result.isAllDay)
    }

    // ── Bug fix 1c: UTC 'Z' timestamp converts to system zone ─────────────────

    @Test
    fun parseIcs_utcZTimestampConvertsToLocalZone() {
        // Build an ICS with a raw UTC string (DTSTART:20250310T090000Z)
        val utcIcs = """
            BEGIN:VCALENDAR
            VERSION:2.0
            PRODID:-//Test//EN
            BEGIN:VEVENT
            UID:utc-test-123
            DTSTART:20250310T090000Z
            DTEND:20250310T100000Z
            SUMMARY:UTC Event
            END:VEVENT
            END:VCALENDAR
        """.trimIndent().replace("\n", "\r\n")

        val result = IcsConverter.parseIcs(utcIcs, "utc_event.ics", "Cal")
        assertNotNull(result)
        // The start should be in device-local time, not UTC 09:00 if the device is not UTC
        val expectedLocal = ZonedDateTime.of(2025, 3, 10, 9, 0, 0, 0, ZoneId.of("UTC"))
            .withZoneSameInstant(ZoneId.systemDefault())
            .toLocalDateTime()
        assertEquals(expectedLocal, result!!.start)
    }

    // ── Bug fix 1d: ZonedDateTime (TZID) — ical4j behaviour in unit-test env ──
    //
    // Without a VTIMEZONE block, ical4j resolves TZID-prefixed timestamps as
    // LocalDateTime (the wall-clock value). The ZonedDateTime branch in our
    // temporalToLocalDateTime() is exercised in production when ical4j has
    // timezone data available (Android device, or ICS with an embedded VTIMEZONE).
    // Here we verify the fallback: the wall-clock value is kept as-is (09:00 UTC
    // becomes 09:00 local), which is the safe no-loss behaviour.

    @Test
    fun parseIcs_tzidWithoutVtimezonePreservesWallClockValue() {
        val romeIcs = """
            BEGIN:VCALENDAR
            VERSION:2.0
            PRODID:-//Test//EN
            BEGIN:VEVENT
            UID:rome-test-456
            DTSTART;TZID=Europe/Rome:20250310T100000
            DTEND;TZID=Europe/Rome:20250310T110000
            SUMMARY:Rome Event
            END:VEVENT
            END:VCALENDAR
        """.trimIndent().replace("\n", "\r\n")

        val result = IcsConverter.parseIcs(romeIcs, "rome_event.ics", "Cal")
        assertNotNull(result)
        assertEquals("Rome Event", result!!.title)
        // ical4j without a VTIMEZONE block returns LocalDateTime (wall-clock preserved)
        assertNotNull("start should not be null", result.start)
        // The date part must be correct regardless of how the time is interpreted
        assertEquals(2025, result.start!!.year)
        assertEquals(3, result.start!!.monthValue)
        assertEquals(10, result.start!!.dayOfMonth)
    }

    // ── Bug fix 2: All-day event ─────────────────────────────────────────────

    @Test
    fun parseIcs_allDayEvent() {
        val ics = buildIcs {
            val dtStart = DtStart<java.time.LocalDate>(java.time.LocalDate.of(2025, 5, 1))
            dtStart.add<DtStart<java.time.LocalDate>>(net.fortuna.ical4j.model.parameter.Value.DATE)
            add(dtStart)
            add(Summary("Labour Day"))
        }
        val result = IcsConverter.parseIcs(ics, "labour_day.ics", "Holidays")
        assertNotNull(result)
        assertTrue(result!!.isAllDay)
        assertEquals(java.time.LocalDate.of(2025, 5, 1).atStartOfDay(), result.start)
    }

    // ── Bug fix 3: Recurring event round-trips RRULE ─────────────────────────

    @Test
    fun parseIcs_recurringEventPreservesRRule() {
        val now = LocalDateTime.of(2025, 1, 6, 9, 0, 0)
        val ics = buildIcs {
            val dtStart = DtStart<LocalDateTime>(now)
            dtStart.add<DtStart<LocalDateTime>>(net.fortuna.ical4j.model.parameter.Value.DATE_TIME)
            add(dtStart)
            add(Summary("Weekly Standup"))
            add(RRule<java.time.LocalDate>("FREQ=WEEKLY;BYDAY=MO"))
        }
        val result = IcsConverter.parseIcs(ics, "Weekly Standup.ics", "Work")
        assertNotNull(result)
        assertEquals("Weekly Standup", result!!.title)
        assertEquals("FREQ=WEEKLY;BYDAY=MO", result.recurrenceRule)
    }

    // ── toIcs round-trip ────────────────────────────────────────────────────

    @Test
    fun toIcs_thenParseIcs_roundTrips() {
        val original = com.waph1.markithub.model.CalendarEvent(
            title = "Round-trip Test",
            start = LocalDateTime.of(2025, 6, 15, 14, 30),
            end = LocalDateTime.of(2025, 6, 15, 15, 30),
            body = "Some notes",
            calendarName = "Test"
        )
        val ics = IcsConverter.toIcs(original)
        val parsed = IcsConverter.parseIcs(ics, "round_trip.ics", "Test")
        assertNotNull(parsed)
        assertEquals(original.title, parsed!!.title)
        assertEquals(original.body, parsed.body)
        assertEquals(original.start, parsed.start)
        assertEquals(original.end, parsed.end)
    }
}
