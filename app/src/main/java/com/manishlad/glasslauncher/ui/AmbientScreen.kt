package com.manishlad.glasslauncher.ui

import androidx.compose.animation.Crossfade
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.unit.IntSize
import com.manishlad.glasslauncher.Slide
import com.manishlad.glasslauncher.data.CalendarState
import com.manishlad.glasslauncher.data.WatchItem
import com.manishlad.glasslauncher.data.WeatherCodes
import com.manishlad.glasslauncher.data.WeatherState
import java.time.format.DateTimeFormatter
import java.util.Locale

/**
 * Ambient: wallpaper slideshow with a slow drift and crossfade, big clock, date + weather,
 * glass chips for tomorrow's first event and "Resume <show>". Used on Home after 3 min idle
 * and as the system screensaver (DreamService).
 */
@Composable
fun AmbientScreen(
    slide: Slide?,
    weather: WeatherState,
    calendar: CalendarState,
    resume: WatchItem?,
) {
    val rootSize = remember { mutableStateOf(IntSize.Zero) }
    Box(Modifier.fillMaxSize().background(Color(0xFF0B1026)).onSizeChanged { rootSize.value = it }) {
        Crossfade(slide, animationSpec = tween(2500), label = "slides") { s ->
            if (s != null) DriftingImage(s)
        }
        val current = slide ?: return@Box
        CompositionLocalProvider(LocalGlassSource provides GlassSource(current.backdrop.blurred, rootSize)) {
            AmbientOverlay(current, weather, calendar, resume)
        }
    }
}

/** Slow Ken Burns pan/zoom (transform only), alternating direction per slide. */
@Composable
private fun DriftingImage(slide: Slide) {
    val t = remember(slide) { Animatable(0f) }
    LaunchedEffect(slide) { t.animateTo(1f, tween(45_000, easing = LinearEasing)) }
    val dir = if (slide.index % 2 == 0) 1f else -1f
    Canvas(
        Modifier.fillMaxSize().graphicsLayer {
            val p = t.value
            val s = 1.08f - 0.06f * p
            scaleX = s
            scaleY = s
            translationX = dir * (p - .5f) * 0.03f * size.width
            translationY = (p - .5f) * -0.015f * size.height
        },
    ) {
        drawImage(slide.backdrop.sharp, dstSize = IntSize(size.width.toInt(), size.height.toInt()), filterQuality = FilterQuality.Low)
    }
}

@Composable
private fun AmbientOverlay(slide: Slide, weather: WeatherState, calendar: CalendarState, resume: WatchItem?) {
    val now by rememberNow()
    val is24 = is24Hour()
    Box(Modifier.fillMaxSize()) {
        val credit = slide.info.credit?.let { " · $it" }.orEmpty()
        T(
            "${slide.info.name}$credit · Wallpaper ${slide.index + 1} of ${slide.total}", 20,
            Modifier.align(Alignment.TopEnd).padding(top = 72.px, end = 120.px), alpha = .75f, tracking = .3,
        )
        Row(
            Modifier.align(Alignment.BottomStart).fillMaxWidth().padding(start = 120.px, end = 120.px, bottom = 96.px),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.Bottom,
        ) {
            Column {
                T(now.format(DateTimeFormatter.ofPattern(if (is24) "HH:mm" else "h:mm")), 220, weight = 200, tracking = -9, lineHeight = 220)
                val w = weather.data?.current
                val date = now.format(DateTimeFormatter.ofPattern("EEEE, d MMMM", Locale.ENGLISH))
                T(if (w != null) "$date · ${deg(w.temp)} ${WeatherCodes.label(w.code, w.isDay)}" else date, 36, weight = 300, alpha = .88f)
            }
            Column(horizontalAlignment = Alignment.End, verticalArrangement = Arrangement.spacedBy(16.px)) {
                // Tomorrow's first event (or the next one after that).
                val today = now.toLocalDate()
                val next = calendar.upcoming(now).firstOrNull { it.start.toLocalDate().isAfter(today) }
                if (next != null) {
                    val day = next.start.toLocalDate()
                    GlassSurface(Modifier.widthIn(min = 360.px), radius = 28.px) {
                        Column(Modifier.padding(horizontal = 28.px, vertical = 20.px), verticalArrangement = Arrangement.spacedBy(4.px)) {
                            SectionLabel(dayLabel(day, today), size = 18, alpha = .75f)
                            val time = if (next.allDay) "All day" else clockTime(next.start, is24)
                            MiddleEllipsisText("${next.title} · $time", 26, Modifier.widthIn(max = 560.px), weight = 500, maxLines = 2, lineHeight = 32)
                        }
                    }
                }
                if (resume != null) {
                    GlassSurface(Modifier.widthIn(min = 360.px), radius = 28.px) {
                        Row(
                            Modifier.padding(horizontal = 28.px, vertical = 20.px),
                            horizontalArrangement = Arrangement.spacedBy(16.px),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Glyph(Glyphs.PlayOutline, 28)
                            Column(verticalArrangement = Arrangement.spacedBy(4.px)) {
                                T("Resume ${resume.title}", 26, Modifier.widthIn(max = 520.px), weight = 500)
                                T(listOfNotNull("Press OK", resume.upTo?.replace(" · ", " ")).joinToString(" · "), 20, alpha = .75f)
                            }
                        }
                    }
                }
            }
        }
    }
}
