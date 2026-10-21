package com.manishlad.glasslauncher.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import com.manishlad.glasslauncher.data.CalEvent
import com.manishlad.glasslauncher.data.CalendarState
import com.manishlad.glasslauncher.data.WeatherCodes
import com.manishlad.glasslauncher.data.WeatherPlace
import com.manishlad.glasslauncher.data.WeatherState
import java.time.DayOfWeek
import java.time.Instant
import java.time.LocalDate
import java.time.temporal.TemporalAdjusters

private val RainBlue = Color(0xFF7FD1FF)
private val Warm = Color(0xFFFFC27A)

/** Page frame for expanded screens: title, subtitle, "Back to Home" hint, content. */
@Composable
fun ExpandedPage(title: String, subtitle: String, content: @Composable ColumnScope.() -> Unit) {
    Column(Modifier.fillMaxSize().padding(horizontal = 120.px, vertical = 72.px), verticalArrangement = Arrangement.spacedBy(40.px)) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
            Column(verticalArrangement = Arrangement.spacedBy(8.px)) {
                T(title, 64, weight = 300, tracking = -1.5)
                T(subtitle, 24, alpha = .8f)
            }
            HintChip("Back to Home")
        }
        content()
    }
}

@Composable
fun HintChip(text: String) {
    GlassSurface(radius = 999.dp) { T(text, 22, Modifier.padding(horizontal = 24.px, vertical = 12.px)) }
}

@Composable
private fun Card(modifier: Modifier, content: @Composable BoxScope.() -> Unit) =
    GlassSurface(modifier, radius = 40.px, content = content)

/** Expanded screens have nothing to select, but something must hold focus for keys to arrive. */
@Composable
private fun Modifier.holdFocus(): Modifier {
    val r = remember { FocusRequester() }
    LaunchedEffect(Unit) { runCatching { r.requestFocus() } }
    return focusRequester(r).focusable()
}

// ---- Weather ---------------------------------------------------------------------------------

@Composable
fun WeatherScreen(state: WeatherState) {
    val now by rememberNow()
    val is24 = is24Hour()
    val data = state.data
    ExpandedPage("Weather", "${WeatherPlace.NAME} · updated ${weatherAge(state, Instant.now(), long = true)}") {
        if (data == null) {
            Card(Modifier.fillMaxWidth().weight(1f).holdFocus()) {
                Column(Modifier.padding(44.px), verticalArrangement = Arrangement.spacedBy(16.px)) {
                    T("No forecast yet.", 38, weight = 300)
                    T("The TV hasn't been able to reach Open-Meteo. It retries every few minutes.", 24, alpha = .8f, maxLines = 2)
                }
            }
            return@ExpandedPage
        }
        val c = data.current
        val today = data.today(now)
        Row(Modifier.fillMaxWidth().weight(1f).holdFocus(), horizontalArrangement = Arrangement.spacedBy(32.px)) {
            // Right now
            Card(Modifier.width(620.px).fillMaxHeight()) {
                Column(Modifier.fillMaxSize().padding(horizontal = 44.px, vertical = 44.px)) {
                    SectionLabel("Right now")
                    Spacer(Modifier.height(16.px))
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(36.px)) {
                        Glyph(weatherGlyph(c.code, c.isDay), 96)
                        T(deg(c.temp), 168, weight = 200, tracking = -6, lineHeight = 168)
                    }
                    Spacer(Modifier.height(12.px))
                    T(WeatherCodes.label(c.code, c.isDay), 34, weight = 500)
                    Spacer(Modifier.height(8.px))
                    T(
                        listOfNotNull("Feels like ${deg(c.feelsLike)}", today?.let { "H ${deg(it.max)}" }, today?.let { "L ${deg(it.min)}" }).joinToString(" · "),
                        24, alpha = .8f,
                    )
                    Spacer(Modifier.height(24.px))
                    Box(Modifier.fillMaxWidth().height(1.px).background(Color.White.copy(alpha = .16f)))
                    Spacer(Modifier.height(24.px))
                    val aqi = data.indianAqi(now)
                    StatGrid(
                        listOf(
                            Stat("Humidity", "${c.humidity}%"),
                            Stat("Wind", "${c.windKmh.toInt()} km/h ${WeatherCodes.compass(c.windDegrees)}"),
                            Stat("Air quality\n(Indian AQI)", aqi?.value?.toString() ?: "—", aqi?.category),
                            Stat("Rain", data.rainChanceNow(now)?.let { "$it%" } ?: "—"),
                            Stat("Sunrise", today?.let { clockTime(it.sunrise, is24) } ?: "—"),
                            Stat("Sunset", today?.let { clockTime(it.sunset, is24) } ?: "—"),
                        ),
                    )
                    Spacer(Modifier.weight(1f))
                    T(
                        "Weather data by Open-Meteo.com (CC BY 4.0). Air quality is a model forecast, converted to the Indian AQI scale.",
                        18, alpha = .72f, maxLines = 3, lineHeight = 26,
                    )
                }
            }
            Column(Modifier.weight(1f).fillMaxHeight(), verticalArrangement = Arrangement.spacedBy(32.px)) {
                // Next 12 hours
                Card(Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(horizontal = 40.px, vertical = 36.px), verticalArrangement = Arrangement.spacedBy(20.px)) {
                        SectionLabel("Next 12 hours")
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                            data.next12(now).forEachIndexed { i, h ->
                                Column(
                                    Modifier
                                        .clip(RoundedCornerShape(16.px))
                                        .background(if (i == 0) Color.White.copy(alpha = .14f) else Color.Transparent)
                                        .padding(horizontal = 14.px, vertical = 14.px),
                                    horizontalAlignment = Alignment.CenterHorizontally,
                                    verticalArrangement = Arrangement.spacedBy(10.px),
                                ) {
                                    T(if (i == 0) "Now" else hourLabel(h.time, is24), 19, weight = if (i == 0) 600 else 400, alpha = if (i == 0) 1f else .75f)
                                    T(deg(h.temp), 26, weight = 500)
                                }
                            }
                        }
                    }
                }
                // This week
                Card(Modifier.fillMaxWidth().weight(1f)) { WeekForecast(state, now.toLocalDate()) }
            }
        }
    }
}

private class Stat(val label: String, val value: String, val sub: String? = null)

@Composable
private fun StatGrid(stats: List<Stat>) {
    Column(verticalArrangement = Arrangement.spacedBy(28.px)) {
        for (row in stats.chunked(3)) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(24.px)) {
                for (stat in row) {
                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(6.px)) {
                        T(stat.label, 20, alpha = .75f, maxLines = 2)
                        T(stat.value, 30, weight = 500)
                        stat.sub?.let { T(it, 22, weight = 500, alpha = .85f, maxLines = 2) }
                    }
                }
            }
        }
    }
}

@Composable
private fun WeekForecast(state: WeatherState, today: LocalDate) {
    val days = state.data?.daily.orEmpty().filter { !it.date.isBefore(today) }.take(6)
    if (days.isEmpty()) return
    val lo = days.minOf { it.min }
    val hi = days.maxOf { it.max }
    Column(Modifier.padding(horizontal = 40.px, vertical = 36.px), verticalArrangement = Arrangement.spacedBy(22.px)) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.Bottom) {
            SectionLabel("This week", Modifier.width(170.px))
            SectionLabel("Rain", Modifier.width(90.px))
            Spacer(Modifier.weight(1f))
            SectionLabel("Low · High")
        }
        for (d in days) {
            val isToday = d.date == today
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                T(if (isToday) "Today" else formatDate(d.date, "EEE"), 24, Modifier.width(170.px), weight = if (isToday) 600 else 400)
                val rain = d.rainChance
                T(rain?.let { "$it%" } ?: "—", 24, Modifier.width(90.px), alpha = if ((rain ?: 0) >= 30) 1f else .75f,
                    weight = if ((rain ?: 0) >= 30) 500 else 400)
                RangeBar(Modifier.weight(1f).padding(horizontal = 24.px), d.min, d.max, lo, hi)
                T("${deg(d.min)} · ${deg(d.max)}", 24, Modifier.width(120.px), align = androidx.compose.ui.text.style.TextAlign.End)
            }
        }
    }
}

/** Track spans the week's lowest → highest; the bar marks this day's range, cool → warm. */
@Composable
private fun RangeBar(modifier: Modifier, min: Double, max: Double, lo: Double, hi: Double) {
    var width by remember { mutableStateOf(0) }
    val span = (hi - lo).coerceAtLeast(1.0)
    val start = ((min - lo) / span).toFloat()
    val end = ((max - lo) / span).toFloat()
    Box(modifier.height(6.px).clip(CircleShape).background(Color.White.copy(alpha = .14f)).onSizeChanged { width = it.width }) {
        val density = LocalDensity.current
        Box(
            Modifier
                .offset(x = with(density) { (width * start).toDp() })
                .width(with(density) { (width * (end - start)).toDp() })
                .fillMaxHeight()
                .clip(CircleShape)
                .background(Brush.horizontalGradient(listOf(RainBlue, Warm))),
        )
    }
}

// ---- Calendar ---------------------------------------------------------------------------------

@Composable
fun CalendarScreen(state: CalendarState) {
    val now by rememberNow()
    val is24 = is24Hour()
    val today = now.toLocalDate()
    var selected by remember { mutableStateOf(today.plusDays(1)) }
    val weekStart = selected.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY))
    val lastDay = today.plusDays(34)

    ExpandedPage("Calendar", "${formatDate(selected, "MMMM yyyy")} · from your calendars · view only") {
        // Week strip: ◀ ▶ move the selected day (and page weeks at the ends).
        var stripFocused by remember { mutableStateOf(false) }
        val r = remember { FocusRequester() }
        LaunchedEffect(Unit) { runCatching { r.requestFocus() } }
        Card(
            Modifier.fillMaxWidth().height(162.px)
                .focusRequester(r)
                .onFocusChanged { stripFocused = it.isFocused }
                .onKeyEvent { e ->
                    if (e.type != KeyEventType.KeyDown) return@onKeyEvent false
                    when (e.key) {
                        Key.DirectionLeft -> { if (selected > today.minusDays(13)) selected = selected.minusDays(1); true }
                        Key.DirectionRight -> { if (selected < lastDay) selected = selected.plusDays(1); true }
                        Key.DirectionUp, Key.DirectionDown -> true
                        else -> false
                    }
                }
                .focusable(),
        ) {
            Row(Modifier.fillMaxSize().padding(14.px), horizontalArrangement = Arrangement.spacedBy(10.px)) {
                for (offset in 0L..6L) {
                    val day = weekStart.plusDays(offset)
                    DayCell(Modifier.weight(1f), day, today, day == selected, stripFocused, state.on(day).map { state.source(it.calendarId)?.color ?: 0xFFFFFFFF }.distinct())
                }
            }
        }
        Row(Modifier.fillMaxWidth().weight(1f), horizontalArrangement = Arrangement.spacedBy(32.px)) {
            Card(Modifier.width(520.px).fillMaxHeight()) { RestOfToday(state, now, is24) }
            Card(Modifier.weight(1f).fillMaxHeight()) { DayAgenda(state, selected, is24) }
        }
    }
}

@Composable
private fun DayCell(modifier: Modifier, day: LocalDate, today: LocalDate, selected: Boolean, stripFocused: Boolean, colors: List<Long>) {
    val isToday = day == today
    val focus by animateFocus(selected && stripFocused)
    val base = Modifier.fillMaxHeight()
    val inner: @Composable BoxScope.() -> Unit = {
        Column(Modifier.fillMaxSize(), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
            T(if (isToday) "Today" else formatDate(day, "EEE"), 20, weight = if (isToday) 600 else 400, alpha = if (isToday) 1f else .8f)
            T(day.dayOfMonth.toString(), 40, weight = if (isToday) 500 else 300)
            Row(Modifier.height(14.px).padding(top = 6.px), horizontalArrangement = Arrangement.spacedBy(6.px)) {
                for (c in colors.take(3)) Box(Modifier.size(8.px).clip(CircleShape).background(Color(c)))
            }
        }
    }
    if (selected) {
        GlassSurface(modifier.then(base), radius = 26.px, focus = if (stripFocused) focus else .5f, focusScale = 1.02f, content = inner)
    } else {
        Box(
            modifier.then(base).clip(RoundedCornerShape(26.px)).background(if (isToday) Color.White.copy(alpha = .10f) else Color.Transparent),
            content = inner,
        )
    }
}

@Composable
private fun RestOfToday(state: CalendarState, now: java.time.LocalDateTime, is24: Boolean) {
    val today = now.toLocalDate()
    val rest = state.on(today).filter { it.end.isAfter(now) }
    Column(Modifier.fillMaxSize().padding(horizontal = 40.px, vertical = 44.px)) {
        SectionLabel("Rest of today")
        Spacer(Modifier.height(20.px))
        when {
            state.sources.isEmpty() -> {
                T("No calendars yet.", 38, weight = 300)
                Spacer(Modifier.height(16.px))
                T("Add a calendar link from your phone to see it here.", 24, alpha = .8f, maxLines = 3)
            }
            rest.isEmpty() -> {
                T("Nothing else today.", 38, weight = 300)
                Spacer(Modifier.height(16.px))
                val next = state.upcoming(now).firstOrNull { it.start.toLocalDate().isAfter(today) }
                val freeUntil = next?.let {
                    val day = it.start.toLocalDate()
                    val whenText = when (day) {
                        today.plusDays(1) -> "tomorrow"
                        else -> "on ${formatDate(day, "EEEE")}"
                    }
                    if (it.allDay) "You're free until ${it.title}, $whenText." else "You're free until ${it.title}, $whenText at ${clockTime(it.start, is24)}."
                } ?: "Nothing on the calendar for the next few weeks."
                T(freeUntil, 24, alpha = .8f, maxLines = 4, lineHeight = 30)
            }
            else -> Column(verticalArrangement = Arrangement.spacedBy(18.px)) {
                for (e in rest.take(4)) EventLine(state, e, is24)
            }
        }
        Spacer(Modifier.weight(1f))
        if (state.sources.isNotEmpty()) {
            SectionLabel("Calendars shown")
            Spacer(Modifier.height(16.px))
            Column(verticalArrangement = Arrangement.spacedBy(10.px)) {
                for (src in state.sources) {
                    val err = state.status[src.id]?.error
                    Column {
                        Row(horizontalArrangement = Arrangement.spacedBy(14.px), verticalAlignment = Alignment.CenterVertically) {
                            Box(Modifier.size(12.px).clip(CircleShape).background(Color(src.color)))
                            T(src.name, 24)
                        }
                        if (err != null) T(err, 19, Modifier.padding(start = 26.px, top = 2.px), alpha = .9f)
                    }
                }
            }
            Spacer(Modifier.height(18.px))
            T(state.syncedAt?.let { "Synced ${ageLabel(it)} · calendar links" } ?: "Not synced yet · calendar links", 19, alpha = .72f)
        }
    }
}

@Composable
private fun EventLine(state: CalendarState, e: CalEvent, is24: Boolean) {
    Row(horizontalArrangement = Arrangement.spacedBy(16.px), verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(12.px).clip(CircleShape).background(Color(state.source(e.calendarId)?.color ?: 0xFFFFFFFF)))
        Column {
            T(e.title, 26, weight = 500, maxLines = 2, lineHeight = 30)
            T(eventTimeRange(e, is24), 20, alpha = .75f)
        }
    }
}

@Composable
private fun DayAgenda(state: CalendarState, day: LocalDate, is24: Boolean) {
    val events = state.on(day)
    Column(Modifier.fillMaxSize().padding(horizontal = 48.px, vertical = 44.px)) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            SectionLabel(formatDate(day, "EEEE, d MMMM"), Modifier.alignByBaseline())
            T(if (events.size == 1) "1 event" else "${events.size} events", 22, Modifier.alignByBaseline(), alpha = .8f)
        }
        Spacer(Modifier.height(18.px))
        if (events.isEmpty()) {
            Box(Modifier.fillMaxWidth().height(1.px).background(Color.White.copy(alpha = .14f)))
            Spacer(Modifier.height(28.px))
            T("Nothing scheduled.", 30, weight = 300, alpha = .9f)
        }
        for (e in events.take(5)) {
            Box(Modifier.fillMaxWidth().height(1.px).background(Color.White.copy(alpha = .14f)))
            Row(Modifier.fillMaxWidth().padding(vertical = 26.px), verticalAlignment = Alignment.Top) {
                T(if (e.allDay) "All day" else clockTime(e.start, is24), 26, Modifier.width(230.px), weight = 500)
                Box(Modifier.padding(top = 10.px, end = 24.px).size(14.px).clip(CircleShape).background(Color(state.source(e.calendarId)?.color ?: 0xFFFFFFFF)))
                Column(verticalArrangement = Arrangement.spacedBy(6.px)) {
                    T(e.title, 28, weight = 500, maxLines = 2, lineHeight = 34)
                    T(listOfNotNull(durationLabel(e), state.source(e.calendarId)?.name, e.location).joinToString(" · "), 21, alpha = .75f)
                }
            }
        }
        if (events.size > 5) T("+ ${events.size - 5} more", 21, alpha = .75f)
    }
}
