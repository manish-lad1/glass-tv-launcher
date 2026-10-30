package com.manishlad.glasslauncher.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextAlign
import com.manishlad.glasslauncher.data.F1Session
import com.manishlad.glasslauncher.data.F1Standing
import com.manishlad.glasslauncher.data.F1State
import java.time.Duration
import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneId

private val F1Red = Color(0xFFFF5A4E)

/** "in 2d 4h", "in 5h 20m", "in 12 min", "Live now", "Done". */
private fun countdown(s: F1Session, now: Instant): String {
    if (!now.isBefore(s.start)) return if (now.isBefore(s.start.plus(Duration.ofHours(2)))) "Live now" else "Done"
    val m = Duration.between(now, s.start).toMinutes()
    return when {
        m < 60 -> "in ${m.coerceAtLeast(1)} min"
        m < 24 * 60 -> "in ${m / 60}h ${m % 60}m"
        else -> "in ${m / (24 * 60)}d ${(m % (24 * 60)) / 60}h"
    }
}

private fun local(i: Instant): LocalDateTime = LocalDateTime.ofInstant(i, ZoneId.systemDefault())

/** "Sat 02:30" (or "Today 21:00" / "Tomorrow …"). */
@Composable
private fun sessionTime(s: F1Session): String {
    val now by rememberNow()
    val t = local(s.start)
    return "${dayLabel(t.toLocalDate(), now.toLocalDate())} ${clockTime(t, is24Hour())}"
}

/** "Mexico City Grand Prix" → "Mexico City GP" for the small card. */
private fun shortName(name: String) = name.replace("Grand Prix", "GP")

// ---- Home card ------------------------------------------------------------------------------

@Composable
fun F1Widget(state: F1State) {
    val nowLocal by rememberNow()
    val now = nowLocal.atZone(ZoneId.systemDefault()).toInstant()
    val data = state.data
    val race = data?.next
    Column(verticalArrangement = Arrangement.spacedBy(14.px)) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            SectionLabel("Formula 1", Modifier.alignByBaseline())
            if (race != null) T("Round ${race.round}", 18, Modifier.alignByBaseline(), alpha = .72f)
        }
        when {
            data == null -> {
                T(if (state.lastAttemptFailed) "Can't reach the F1 data service." else "Getting the F1 schedule…", 24, weight = 500, maxLines = 2)
                T("It will appear here once the TV is online.", 20, alpha = .75f, maxLines = 2)
            }
            race == null -> {
                T("Season finished", 28, weight = 500)
                data.drivers.firstOrNull()?.let { T("Champion: ${it.name}", 22, alpha = .8f) }
            }
            else -> {
                T(shortName(race.name), 28, weight = 500)
                val next = race.nextSession(now)
                if (next != null) {
                    val cd = countdown(next, now)
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                        T("${next.name} · ${sessionTime(next)}", 20, Modifier.weight(1f), alpha = .8f)
                        T(cd, 20, Modifier.padding(start = 10.px), weight = 600, alpha = 1f)
                    }
                }
                Box(Modifier.fillMaxWidth().height(1.px).background(Color.White.copy(alpha = .16f)))
                Column(verticalArrangement = Arrangement.spacedBy(6.px)) {
                    for (s in data.drivers.take(3)) {
                        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                            T("${s.position}", 20, Modifier.width(28.px), alpha = .7f)
                            T(s.code, 21, Modifier.width(66.px), weight = 600)
                            T(s.team, 18, Modifier.weight(1f), alpha = .72f)
                            T(s.points, 21, weight = 500, align = TextAlign.End)
                        }
                    }
                }
            }
        }
    }
}

// ---- Full screen ----------------------------------------------------------------------------

@Composable
fun F1Screen(state: F1State) {
    val nowLocal by rememberNow()
    val now = nowLocal.atZone(ZoneId.systemDefault()).toInstant()
    val data = state.data
    val race = data?.next
    val focus = remember { FocusRequester() }
    LaunchedEffect(Unit) { runCatching { focus.requestFocus() } }
    val subtitle = when {
        race != null -> "Round ${race.round} · ${race.circuit}, ${race.locality} · updated ${ageLabel(data.fetchedAt)}"
        data != null -> "Season ${data.next?.season ?: ""} finished · updated ${ageLabel(data.fetchedAt)}"
        else -> "No data yet"
    }
    ExpandedPage(race?.name ?: "Formula 1", subtitle) {
        Row(Modifier.fillMaxWidth().weight(1f).focusRequester(focus).focusable(), horizontalArrangement = Arrangement.spacedBy(32.px)) {
            // Weekend schedule + last race
            Card(Modifier.width(560.px).fillMaxHeight()) {
                Column(Modifier.fillMaxSize().padding(horizontal = 40.px, vertical = 40.px)) {
                    SectionLabel("This weekend")
                    Spacer(Modifier.height(20.px))
                    if (race == null) T("No upcoming race.", 26, alpha = .8f)
                    val next = race?.nextSession(now)
                    race?.sessions?.forEach { s ->
                        val isNext = s == next
                        val done = !now.isBefore(s.start.plus(Duration.ofHours(2)))
                        Row(Modifier.fillMaxWidth().padding(vertical = 10.px), verticalAlignment = Alignment.CenterVertically) {
                            Box(
                                Modifier.size(12.px).clip(CircleShape)
                                    .background(if (isNext) F1Red else Color.White.copy(alpha = if (done) .25f else .6f)),
                            )
                            Spacer(Modifier.width(18.px))
                            Column(Modifier.weight(1f)) {
                                T(s.name, 26, weight = if (isNext || s.name == "Race") 600 else 400, alpha = if (done) .55f else 1f)
                                T(sessionTime(s), 20, alpha = if (done) .45f else .75f)
                            }
                            T(countdown(s, now), 21, weight = if (isNext) 600 else 400, alpha = if (done) .45f else .9f)
                        }
                    }
                    Spacer(Modifier.weight(1f))
                    data?.lastRace?.let { last ->
                        SectionLabel("Last race · ${last.raceName.replace("Grand Prix", "GP")}")
                        Spacer(Modifier.height(14.px))
                        last.top3.forEachIndexed { i, name -> T("${i + 1}   $name", 22, alpha = if (i == 0) 1f else .85f, weight = if (i == 0) 600 else 400) }
                    }
                    Spacer(Modifier.height(16.px))
                    T("Data: Jolpica F1 API (api.jolpi.ca)", 18, alpha = .6f)
                }
            }
            Card(Modifier.weight(1f).fillMaxHeight()) {
                StandingsTable("Drivers" + (data?.afterRound?.let { " · after round $it" } ?: ""), data?.drivers.orEmpty(), showTeam = true)
            }
            Card(Modifier.width(470.px).fillMaxHeight()) {
                StandingsTable("Constructors", data?.constructors.orEmpty(), showTeam = false)
            }
        }
    }
}

@Composable
private fun Card(modifier: Modifier, content: @Composable BoxScope.() -> Unit) =
    GlassSurface(modifier, radius = 40.px, content = content)

@Composable
private fun StandingsTable(title: String, rows: List<F1Standing>, showTeam: Boolean) {
    Column(Modifier.fillMaxSize().padding(horizontal = 36.px, vertical = 40.px)) {
        SectionLabel(title)
        Spacer(Modifier.height(18.px))
        if (rows.isEmpty()) T("Standings will appear after the first race.", 22, alpha = .75f, maxLines = 2)
        for (s in rows.take(10)) {
            Row(Modifier.fillMaxWidth().padding(vertical = 8.px), verticalAlignment = Alignment.CenterVertically) {
                T("${s.position}", 22, Modifier.width(44.px), alpha = .7f)
                Column(Modifier.weight(1f)) {
                    T(s.name, 23, weight = if (s.position == 1) 600 else 500)
                    if (showTeam) T(s.team, 18, alpha = .65f)
                }
                T(s.points, 23, weight = 500, align = TextAlign.End)
            }
        }
    }
}
