package com.manishlad.glasslauncher.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate
import java.time.LocalDateTime
import java.util.TimeZone

class IndianAqiTest {
    @Test fun bandsInterpolate() {
        assertEquals(0, IndianAqi.pm25SubIndex(0.0))
        assertEquals(50, IndianAqi.pm25SubIndex(30.0))
        assertEquals(51, IndianAqi.pm25SubIndex(31.0))
        assertEquals(100, IndianAqi.pm25SubIndex(60.0))
        assertEquals(152, IndianAqi.pm25SubIndex(76.0)) // 101 + 99*(15/29) = 152.2
    }

    @Test fun pm10Bands() {
        assertEquals(100, IndianAqi.pm10SubIndex(100.0))
        assertEquals(200, IndianAqi.pm10SubIndex(250.0))
        assertEquals(500, IndianAqi.pm10SubIndex(900.0))
    }

    @Test fun maxOfSubIndicesOverRollingMean() {
        val now = LocalDateTime.of(2026, 10, 21, 21, 30)
        val samples = (0 until 30).map { h ->
            AirSample(now.withMinute(0).minusHours(h.toLong()), pm25 = 40.0, pm10 = 120.0)
        }
        val r = IndianAqi.fromHourly(samples, now)!!
        // PM2.5 40 → 51 + 49*(9/29) ≈ 66; PM10 120 → 101 + 99*(19/149) ≈ 114 → AQI 114, Moderate.
        assertEquals(114, r.value)
        assertEquals("Moderate", r.category)
    }

    @Test fun needsSixteenHours() {
        val now = LocalDateTime.of(2026, 10, 21, 21, 0)
        val samples = (0 until 10).map { AirSample(now.minusHours(it.toLong()), 40.0, 120.0) }
        assertNull(IndianAqi.fromHourly(samples, now))
    }
}

class IcsExpanderTest {
    private val ics = """
        BEGIN:VCALENDAR
        VERSION:2.0
        PRODID:test
        BEGIN:VTIMEZONE
        TZID:Asia/Kolkata
        BEGIN:STANDARD
        DTSTART:19700101T000000
        TZOFFSETFROM:+0530
        TZOFFSETTO:+0530
        END:STANDARD
        END:VTIMEZONE
        BEGIN:VEVENT
        UID:badminton
        DTSTART;TZID=Asia/Kolkata:20261012T070000
        DTEND;TZID=Asia/Kolkata:20261012T080000
        RRULE:FREQ=WEEKLY;BYDAY=MO,TH
        EXDATE;TZID=Asia/Kolkata:20261026T070000
        SUMMARY:Badminton
        END:VEVENT
        BEGIN:VEVENT
        UID:badminton
        RECURRENCE-ID;TZID=Asia/Kolkata:20261022T070000
        DTSTART;TZID=Asia/Kolkata:20261022T073000
        DTEND;TZID=Asia/Kolkata:20261022T083000
        SUMMARY:Badminton (late)
        END:VEVENT
        BEGIN:VEVENT
        UID:diwali
        DTSTART;VALUE=DATE:20261108
        DTEND;VALUE=DATE:20261109
        SUMMARY:Diwali
        END:VEVENT
        BEGIN:VEVENT
        UID:gone
        DTSTART:20261021T120000Z
        DTEND:20261021T130000Z
        STATUS:CANCELLED
        SUMMARY:Cancelled thing
        END:VEVENT
        END:VCALENDAR
    """.trimIndent().replace("\n", "\r\n")

    @Test fun expandsRecurrenceWithExdateAndOverride() {
        TimeZone.setDefault(TimeZone.getTimeZone("Asia/Kolkata"))
        val events = IcsExpander.expand("c", ics, LocalDate.of(2026, 10, 19), LocalDate.of(2026, 11, 10))
            .sortedBy { it.start }
        val badminton = events.filter { it.title.startsWith("Badminton") }.map { it.start to it.title }
        assertEquals(
            listOf(
                LocalDateTime.of(2026, 10, 19, 7, 0) to "Badminton",
                LocalDateTime.of(2026, 10, 22, 7, 30) to "Badminton (late)", // override replaces 7:00
                // 26 Oct excluded by EXDATE
                LocalDateTime.of(2026, 10, 29, 7, 0) to "Badminton",
                LocalDateTime.of(2026, 11, 2, 7, 0) to "Badminton",
                LocalDateTime.of(2026, 11, 5, 7, 0) to "Badminton",
                LocalDateTime.of(2026, 11, 9, 7, 0) to "Badminton",
            ),
            badminton,
        )
        val diwali = events.single { it.title == "Diwali" }
        assertTrue(diwali.allDay)
        assertEquals(LocalDate.of(2026, 11, 8), diwali.start.toLocalDate())
        assertTrue(events.none { it.title == "Cancelled thing" })
    }
}
