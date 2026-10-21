package com.manishlad.glasslauncher.ui

import android.text.format.DateFormat
import androidx.compose.runtime.Composable
import androidx.compose.runtime.produceState
import androidx.compose.ui.platform.LocalContext
import com.manishlad.glasslauncher.data.CalEvent
import kotlinx.coroutines.delay
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlin.math.roundToInt

/** Current local time, ticking on the minute. */
@Composable
fun rememberNow() = produceState(LocalDateTime.now()) {
    while (true) {
        value = LocalDateTime.now()
        delay(60_000L - System.currentTimeMillis() % 60_000L)
    }
}

@Composable
fun is24Hour() = DateFormat.is24HourFormat(LocalContext.current)

private fun fmt(pattern: String) = DateTimeFormatter.ofPattern(pattern, Locale.ENGLISH)

fun clockTime(t: LocalDateTime, is24: Boolean): String = t.format(fmt(if (is24) "HH:mm" else "h:mm a"))

/** "10 PM" / "22:00". */
fun hourLabel(t: LocalDateTime, is24: Boolean): String = t.format(fmt(if (is24) "HH:mm" else "h a"))

/** "7:00 – 8:00 AM", "11:00 AM – 1:00 PM", "All day". */
fun eventTimeRange(e: CalEvent, is24: Boolean): String {
    if (e.allDay) return "All day"
    val start = clockTime(e.start, is24)
    if (!e.end.isAfter(e.start)) return start
    val end = clockTime(e.end, is24)
    if (!is24 && e.start.toLocalDate() == e.end.toLocalDate() && start.takeLast(2) == end.takeLast(2)) {
        return "${start.dropLast(3)} – $end"
    }
    return "$start – $end"
}

/** "Today", "Tomorrow", "Thu" within the coming week, "Sun 8 Nov" further out. */
fun dayLabel(date: LocalDate, today: LocalDate): String = when {
    date == today -> "Today"
    date == today.plusDays(1) -> "Tomorrow"
    date.isBefore(today.plusDays(7)) -> date.format(fmt("EEE"))
    else -> date.format(fmt("EEE d MMM"))
}

/** "just now", "3 min ago", "2 h ago", "3 d ago". */
fun ageLabel(then: Instant, now: Instant = Instant.now()): String {
    val mins = Duration.between(then, now).toMinutes().coerceAtLeast(0)
    return when {
        mins < 1 -> "just now"
        mins < 60 -> "$mins min ago"
        mins < 48 * 60 -> "${mins / 60} h ago"
        else -> "${mins / (24 * 60)} d ago"
    }
}

fun deg(t: Double) = "${t.roundToInt()}°"

/** "1 hr", "2 hr", "45 min", "1 hr 30 min". */
fun durationLabel(e: CalEvent): String? {
    if (e.allDay) return null
    val mins = Duration.between(e.start, e.end).toMinutes()
    if (mins <= 0) return null
    val h = mins / 60
    val m = mins % 60
    return when {
        h == 0L -> "$m min"
        m == 0L -> "$h hr"
        else -> "$h hr $m min"
    }
}

fun formatDate(date: LocalDate, pattern: String): String = date.format(fmt(pattern))
