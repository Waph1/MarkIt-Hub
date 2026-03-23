package com.waph1.markithub.util

import com.waph1.markithub.model.CalendarEvent
import net.fortuna.ical4j.data.CalendarBuilder
import net.fortuna.ical4j.data.CalendarOutputter
import net.fortuna.ical4j.model.Calendar
import net.fortuna.ical4j.model.Property
import net.fortuna.ical4j.model.component.VEvent
import net.fortuna.ical4j.model.property.*
import net.fortuna.ical4j.util.RandomUidGenerator
import java.io.StringReader
import java.io.StringWriter
import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.ZoneOffset
import java.time.temporal.Temporal
import android.graphics.Color as AndroidColor

object IcsConverter {

    init {
        // ical4j uses JCache for timezone data caching, but Android doesn't ship a JCache provider.
        // This causes javax.cache.CacheException on every parse call. Use a simple map-based cache instead.
        System.setProperty("net.fortuna.ical4j.timezone.cache.impl", "net.fortuna.ical4j.util.MapTimeZoneCache")
    }

    /** Last error message from a failed parseIcs call (for diagnostic logging). */
    @Volatile
    var lastParseError: String? = null
        private set

    /** Returns null if the content cannot be parsed or contains no VEVENT. */
    fun parseIcs(content: String, fileName: String, calendarName: String): CalendarEvent? {
        return try {
            val sin = StringReader(content)
            val builder = CalendarBuilder()
            val calendar = builder.build(sin)

            val event = calendar.getComponents<VEvent>(net.fortuna.ical4j.model.Component.VEVENT).firstOrNull()
                ?: return null

            val summaryProp = event.getProperty<Summary>(Property.SUMMARY)
            val title = if (summaryProp.isPresent) summaryProp.get().value else fileName.substringBeforeLast(".ics")

            val descProp = event.getProperty<Description>(Property.DESCRIPTION)
            val description = if (descProp.isPresent) descProp.get().value else ""

            val uidProp = event.getProperty<Uid>(Property.UID)
            val uid = if (uidProp.isPresent) uidProp.get().value else null

            val startProp = event.getProperty<DtStart<*>>(Property.DTSTART)
            val endProp = event.getProperty<DtEnd<*>>(Property.DTEND)

            var isAllDay = false
            var start: LocalDateTime? = null
            var end: LocalDateTime? = null

            if (startProp.isPresent) {
                start = temporalToLocalDateTime(startProp.get().date, isStart = true).also {
                    // If the DTSTART value is a bare LocalDate, it's an all-day event
                    if (startProp.get().date is java.time.LocalDate) isAllDay = true
                }
            }

            if (endProp.isPresent) {
                end = temporalToLocalDateTime(endProp.get().date, isStart = false)
            }

            val rruleProp = event.getProperty<RRule<*>>(Property.RRULE)
            val rrule = if (rruleProp.isPresent) rruleProp.get().value else null

            val locProp = event.getProperty<Location>(Property.LOCATION)
            val location = if (locProp.isPresent) locProp.get().value else null

            // Standard RFC 7986 COLOR or Apple's property
            var colorStr: String? = null
            val customColor = event.getProperty<Property>("COLOR")
            if (customColor.isPresent) {
                colorStr = customColor.get().value
            } else {
                val appleColor = event.getProperty<Property>("X-APPLE-CALENDAR-COLOR")
                if (appleColor.isPresent) colorStr = appleColor.get().value
            }

            val color = colorStr?.let {
                try { AndroidColor.parseColor(it) } catch (e: Exception) { null }
            }

            CalendarEvent(
                title = title,
                body = description,
                start = start,
                end = end,
                isAllDay = isAllDay,
                recurrenceRule = rrule,
                location = location,
                color = color,
                overrideId = uid,
                fileName = fileName,
                calendarName = calendarName
            )
        } catch (e: Exception) {
            lastParseError = e.toString()
            e.printStackTrace()
            null
        }
    }

    /**
     * Converts any ical4j temporal type to a LocalDateTime in the device's system timezone.
     * Returns null only if the type is completely unrecognized.
     */
    private fun temporalToLocalDateTime(temporal: Temporal, isStart: Boolean): LocalDateTime? {
        return when (temporal) {
            is java.time.LocalDate -> temporal.atStartOfDay()
            is LocalDateTime -> temporal
            // ZonedDateTime: honour the timezone — convert to device-local time
            is java.time.ZonedDateTime -> temporal.withZoneSameInstant(ZoneId.systemDefault()).toLocalDateTime()
            // Instant (UTC 'Z' suffix): convert to device-local time
            is Instant -> temporal.atZone(ZoneId.systemDefault()).toLocalDateTime()
            else -> {
                // Last-resort: try to adapt to Instant via the standard accessor
                try {
                    Instant.from(temporal).atZone(ZoneId.systemDefault()).toLocalDateTime()
                } catch (e: Exception) {
                    null
                }
            }
        }
    }

    fun toIcs(event: CalendarEvent): String {
        val calendar = Calendar().apply {
            add(ProdId("-//MarkIt Hub//EN"))
            add(Version.VERSION_2_0)
        }

        val vEvent = VEvent()

        val start = event.start ?: LocalDateTime.now()
        
        if (event.isAllDay) {
            val dtStart = DtStart<java.time.LocalDate>(start.toLocalDate())
            dtStart.add<DtStart<java.time.LocalDate>>(net.fortuna.ical4j.model.parameter.Value.DATE)
            vEvent.add(dtStart)
            
            event.end?.let {
                val dtEnd = DtEnd<java.time.LocalDate>(it.toLocalDate())
                dtEnd.add<DtEnd<java.time.LocalDate>>(net.fortuna.ical4j.model.parameter.Value.DATE)
                vEvent.add(dtEnd)
            }
        } else {
            val dtStart = DtStart<java.time.LocalDateTime>(start)
            dtStart.add<DtStart<java.time.LocalDateTime>>(net.fortuna.ical4j.model.parameter.Value.DATE_TIME)
            vEvent.add(dtStart)
            
            event.end?.let { endLocalDateTime ->
                val dtEnd = DtEnd<java.time.LocalDateTime>(endLocalDateTime)
                dtEnd.add<DtEnd<java.time.LocalDateTime>>(net.fortuna.ical4j.model.parameter.Value.DATE_TIME)
                vEvent.add(dtEnd)
            }
        }

        vEvent.add(Summary(event.title))
        
        if (event.body.isNotBlank()) {
            vEvent.add(Description(event.body))
        }

        event.location?.let {
            if (it.isNotBlank()) vEvent.add(Location(it))
        }

        event.recurrenceRule?.let {
            vEvent.add(RRule<java.time.LocalDate>(it))
        }

        val uidStr = event.overrideId ?: RandomUidGenerator().generateUid().value
        vEvent.add(Uid(uidStr))

        calendar.add(vEvent)

        val outputter = CalendarOutputter()
        val writer = StringWriter()
        outputter.output(calendar, writer)
        
        // Ical4j makes adding custom raw properties a bit verbose, so we inject COLOR manually 
        // string replacement for standard RFC 7986 COLOR before the end if needed.
        var icsString = writer.toString()
        event.color?.let { colorInt ->
            val colorHex = String.format("#%06X", (0xFFFFFF and colorInt))
            icsString = icsString.replace("END:VEVENT", "COLOR:$colorHex\r\nEND:VEVENT")
        }
        
        return icsString
    }
}
