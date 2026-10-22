@file:OptIn(androidx.compose.ui.ExperimentalComposeUiApi::class)

package com.manishlad.glasslauncher.ui

import android.provider.Settings
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
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
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.unit.dp
import com.manishlad.glasslauncher.HomeTab
import com.manishlad.glasslauncher.LaunchableApp
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.requiredWidth
import androidx.compose.ui.graphics.graphicsLayer
import com.manishlad.glasslauncher.data.CalendarState
import com.manishlad.glasslauncher.data.WatchItem
import com.manishlad.glasslauncher.data.WeatherCodes
import com.manishlad.glasslauncher.data.WeatherPlace
import com.manishlad.glasslauncher.data.WeatherState
import java.time.Instant
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlin.math.abs

class HomeData(
    val dock: List<LaunchableApp>,
    val apps: List<LaunchableApp>,
    val lastDockApp: String?,
    val weather: WeatherState,
    val calendar: CalendarState,
    val watching: List<WatchItem>,
    val watchingIndex: Int,
    val widgetsHidden: Boolean,
    val tab: HomeTab,
)

class HomeActions(
    val onLaunch: (LaunchableApp) -> Unit,
    val onTab: (HomeTab) -> Unit,
    val onAddToDock: (LaunchableApp) -> String,
    val onOpenSettings: (String) -> Unit,
    val onOpenWeather: () -> Unit,
    val onOpenCalendar: () -> Unit,
    val onOpenWatching: () -> Unit,
    val onOpenPairing: () -> Unit,
    val onOpenQuickSettings: () -> Unit,
    val onEditDock: (String) -> Unit,
    val onResume: (WatchItem) -> Unit,
    val onWatchingIndex: (Int) -> Unit,
    val onToggleWidgets: () -> Unit,
)

@Composable
fun HomeScreen(data: HomeData, actions: HomeActions, focusRequest: Int, returnToWidget: Int?, profileFocusRequest: Int) {
    val profile = remember { FocusRequester() }
    LaunchedEffect(profileFocusRequest) { if (profileFocusRequest > 0) runCatching { profile.requestFocus() } }
    val widgets = remember { WidgetSlots() }
    widgets.hidden = data.widgetsHidden
    LaunchedEffect(focusRequest) {
        if (returnToWidget != null && !data.widgetsHidden) runCatching { widgets.requesters[returnToWidget].requestFocus() }
    }
    val appsTab = remember { FocusRequester() }
    // The dock leaves composition on the Apps tab; remember which focus request it already handled
    // so switching back to Home (by focusing the tab) doesn't pull focus down to the dock.
    val dockFocus = remember { HandledRequest() }
    Column(
        Modifier.fillMaxSize().padding(horizontal = 120.px, vertical = 72.px),
        verticalArrangement = Arrangement.SpaceBetween,
    ) {
        TopBar(data.weather, data.widgetsHidden, data.tab, actions, profile, appsTab)
        when (data.tab) {
            HomeTab.Home -> {
                // Hidden widgets keep their space, so the dock doesn't move and the wallpaper shows through.
                Box(Modifier.fillMaxWidth().height(304.px)) {
                    androidx.compose.animation.AnimatedVisibility(visible = !data.widgetsHidden, enter = fadeIn(tween(300)), exit = fadeOut(tween(300))) {
                        WidgetRow(widgets, data, actions)
                    }
                }
                Dock(
                    data.dock, data.lastDockApp, focusRequest, dockFocus,
                    focusDock = returnToWidget == null || data.widgetsHidden,
                    widgets, actions.onLaunch, actions.onEditDock,
                )
            }
            HomeTab.Apps -> AppsPage(data.apps, actions, appsTab, Modifier.weight(1f).padding(top = 36.px))
        }
    }
}

private class HandledRequest { var value = -1 }

/** Home's "Apps" tab: every launchable app, below the top bar. Hold OK adds one to the dock. */
@Composable
private fun AppsPage(apps: List<LaunchableApp>, actions: HomeActions, appsTab: FocusRequester, modifier: Modifier) {
    var message by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(message) { if (message != null) { kotlinx.coroutines.delay(2500); message = null } }
    Column(modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(16.px)) {
        Row(Modifier.fillMaxWidth().height(36.px), verticalAlignment = Alignment.CenterVertically) {
            T("${apps.size} apps", 24, alpha = .8f)
            T("   ·   " + (message ?: "Hold OK to add to the dock"), 22, alpha = if (message != null) 1f else .65f)
        }
        // The grid adds 20 px each side so focused tiles can grow; widen it by that much to keep
        // the tiles aligned with the clock and the M button.
        Box(Modifier.fillMaxWidth().weight(1f)) {
            AppsGrid(
                apps = apps,
                onSelect = actions.onLaunch,
                onHold = { message = actions.onAddToDock(it) },
                upTarget = appsTab,
                modifier = Modifier.align(Alignment.TopCenter).requiredWidth(1720.px),
            )
        }
    }
}

/**
 * Netflix-style tab: plain text, the selected tab white and underlined, the focused one in a glass
 * pill. Focusing a tab switches the page; ◀ ▶ move between tabs.
 */
@Composable
private fun TabItem(label: String, selected: Boolean, onFocused: () -> Unit, modifier: Modifier = Modifier) {
    var focused by remember { mutableStateOf(false) }
    val focus by animateFocus(focused)
    Box(
        modifier
            .tvSelectable(onFocus = { focused = it; if (it) onFocused() }, onClick = onFocused)
            .graphicsLayer { val s = 1f + .06f * focus; scaleX = s; scaleY = s }
            .clip(RoundedCornerShape(999.px))
            .background(Color.White.copy(alpha = .18f * focus))
            .border(1.px, Color.White.copy(alpha = .6f * focus), RoundedCornerShape(999.px))
            .padding(horizontal = 30.px, vertical = 12.px),
        contentAlignment = Alignment.Center,
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(6.px)) {
            T(label, 28, weight = if (selected) 600 else 500, alpha = if (selected || focused) 1f else .7f)
            Box(Modifier.size(28.px, 4.px).clip(CircleShape).background(Color.White.copy(alpha = if (selected) 1f else 0f)))
        }
    }
}

/** Where each widget sits horizontally, so ▲ from the dock goes to the widget directly above. */
private class WidgetSlots {
    /** While the widgets are hidden, ▲ from the dock falls through to the top bar. */
    var hidden = false
    val requesters = List(3) { FocusRequester() }
    private val spans = Array(3) { 0f to 0f }

    fun track(i: Int) = Modifier
        .focusRequester(requesters[i])
        .onGloballyPositioned { val x = it.positionInRoot().x; spans[i] = x to x + it.size.width }

    fun above(x: Float): FocusRequester {
        if (hidden) return FocusRequester.Default
        val i = spans.indices.minBy { val (l, r) = spans[it]; if (x in l..r) 0f else minOf(abs(x - l), abs(x - r)) }
        return requesters[i]
    }
}

fun weatherGlyph(code: Int, isDay: Boolean): ImageVector = when (WeatherCodes.kind(code)) {
    WeatherCodes.Kind.Clear -> if (isDay) Glyphs.Sun else Glyphs.Moon
    WeatherCodes.Kind.PartlyCloudy -> if (isDay) Glyphs.PartlyCloudy else Glyphs.Cloud
    WeatherCodes.Kind.Cloudy -> Glyphs.Cloud
    WeatherCodes.Kind.Fog -> Glyphs.Fog
    WeatherCodes.Kind.Drizzle, WeatherCodes.Kind.Rain -> Glyphs.Rain
    WeatherCodes.Kind.Thunder -> Glyphs.Thunder
    WeatherCodes.Kind.Snow -> Glyphs.Snow
}

/** "3 min ago", or "Offline · 2 h" after a failed refresh ([long] keeps the "ago"). */
fun weatherAge(state: WeatherState, now: Instant = Instant.now(), long: Boolean = false): String {
    val data = state.data ?: return if (state.lastAttemptFailed) "Offline" else "Loading"
    val age = ageLabel(data.fetchedAt, now)
    return if (!state.lastAttemptFailed) age
        else if (long || age == "just now") "Offline · $age"
        else "Offline · ${age.removeSuffix(" ago")}"
}

// ---- Top bar: clock + date, weather chip, Wi-Fi, profile -------------------------------

@Composable
private fun TopBar(
    weather: WeatherState,
    widgetsHidden: Boolean,
    tab: HomeTab,
    actions: HomeActions,
    profile: FocusRequester,
    appsTab: FocusRequester,
) {
    val now by rememberNow()
    val is24 = is24Hour()
    Box(Modifier.fillMaxWidth()) {
    // Tabs, centred between the clock and the chips, level with the chips.
    Row(Modifier.align(Alignment.TopCenter), horizontalArrangement = Arrangement.spacedBy(12.px)) {
        TabItem("Home", tab == HomeTab.Home, { actions.onTab(HomeTab.Home) },
            Modifier.focusProperties { left = FocusRequester.Cancel })
        TabItem("Apps", tab == HomeTab.Apps, { actions.onTab(HomeTab.Apps) }, Modifier.focusRequester(appsTab))
    }
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.Top) {
        Column(verticalArrangement = Arrangement.spacedBy(10.px)) {
            Row {
                T(
                    now.format(DateTimeFormatter.ofPattern(if (is24) "HH:mm" else "h:mm")),
                    132, Modifier.alignByBaseline(), weight = 200, tracking = -5, lineHeight = 132,
                )
                if (!is24) {
                    Spacer(Modifier.width(14.px))
                    T(now.format(DateTimeFormatter.ofPattern("a", Locale.ENGLISH)).uppercase(), 32, Modifier.alignByBaseline(), weight = 300, alpha = .8f)
                }
            }
            T(now.format(DateTimeFormatter.ofPattern("EEEE, d MMMM", Locale.ENGLISH)), 30, alpha = .82f)
        }
        Row(horizontalArrangement = Arrangement.spacedBy(16.px), verticalAlignment = Alignment.CenterVertically) {
            // Show / hide the widgets (to see the wallpaper).
            // Show / hide the widgets. Only on the Home tab, which has widgets; ◀ from here goes to the tabs.
            if (tab == HomeTab.Home) {
                GlassButton(onClick = actions.onToggleWidgets, radius = 999.dp) {
                    Glyph(if (widgetsHidden) Glyphs.EyeOff else Glyphs.Eye, 28, Modifier.padding(horizontal = 18.px, vertical = 14.px))
                }
            }
            val current = weather.data?.current
            GlassButton(onClick = actions.onOpenWeather, radius = 999.dp, focusScale = 1.08f) {
                Row(
                    Modifier.padding(horizontal = 26.px, vertical = 14.px),
                    horizontalArrangement = Arrangement.spacedBy(14.px),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Glyph(current?.let { weatherGlyph(it.code, it.isDay) } ?: Glyphs.Cloud, 30)
                    T(current?.let { deg(it.temp) } ?: "—°", 28, weight = 500)
                    T(WeatherPlace.NAME, 24, alpha = .78f)
                }
            }
            GlassButton(onClick = { actions.onOpenSettings(Settings.ACTION_WIFI_SETTINGS) }, radius = 999.dp) {
                Glyph(Glyphs.Wifi, 28, Modifier.padding(horizontal = 18.px, vertical = 14.px))
            }
            // Profile: opens Quick settings in phase 6; Android Settings for now.
            // Profile: OK opens Quick settings; hold OK goes straight to phone pairing.
            GlassButton(
                onClick = actions.onOpenQuickSettings,
                focusRequester = profile,
                modifier = Modifier.size(64.px).focusProperties { right = FocusRequester.Cancel },
                onLongClick = actions.onOpenPairing,
                radius = 999.dp,
            ) {
                T("M", 26, weight = 600)
            }
        }
    }
    }
}

// ---- Widgets ------------------------------------------------------------------------------

@Composable
private fun WidgetRow(widgets: WidgetSlots, data: HomeData, actions: HomeActions) {
    Row(Modifier.fillMaxWidth().height(304.px), horizontalArrangement = Arrangement.spacedBy(32.px)) {
        WidgetCard(Modifier.width(400.px).then(widgets.track(0)), actions.onOpenWeather) { WeatherWidget(data.weather) }
        WidgetCard(Modifier.width(460.px).then(widgets.track(1)), actions.onOpenCalendar) { UpNextWidget(data.calendar) }
        NowWatchingStack(
            Modifier.weight(1f).fillMaxHeight().then(widgets.track(2)),
            items = data.watching,
            apps = data.apps,
            index = data.watchingIndex,
            onIndex = actions.onWatchingIndex,
            onResume = actions.onResume,
            onDetails = actions.onOpenWatching,
        )
    }
}

@Composable
private fun WidgetCard(modifier: Modifier, onClick: () -> Unit, content: @Composable () -> Unit) {
    GlassButton(
        onClick = onClick,
        modifier = modifier.fillMaxHeight(),
        radius = 36.px,
        focusScale = 1.05f,
        contentAlignment = Alignment.TopStart,
    ) {
        Box(Modifier.padding(horizontal = 34.px, vertical = 30.px)) { content() }
    }
}

@Composable
private fun WidgetHeader(label: String, right: String?) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
        SectionLabel(label, Modifier.alignByBaseline().weight(1f, fill = false))
        if (right != null) T(right, 18, Modifier.alignByBaseline().padding(start = 12.px), alpha = .72f)
    }
}

@Composable
private fun WeatherWidget(state: WeatherState) {
    val now by rememberNow()
    val is24 = is24Hour()
    val data = state.data
    Column(verticalArrangement = Arrangement.spacedBy(18.px)) {
        WidgetHeader("Weather · ${WeatherPlace.NAME}", weatherAge(state))
        if (data == null) {
            T(if (state.lastAttemptFailed) "Can't reach the weather service." else "Getting the forecast…", 26, weight = 500, maxLines = 2)
            T("It will appear here once the TV is online.", 22, alpha = .75f, maxLines = 2)
            return@Column
        }
        val c = data.current
        val today = data.today(now)
        Row(horizontalArrangement = Arrangement.spacedBy(20.px), verticalAlignment = Alignment.CenterVertically) {
            T(deg(c.temp), 84, weight = 200, tracking = -3, lineHeight = 84)
            Column(verticalArrangement = Arrangement.spacedBy(4.px)) {
                T(WeatherCodes.label(c.code, c.isDay), 26, weight = 500)
                if (today != null) T("H ${deg(today.max)} · L ${deg(today.min)}", 22, alpha = .75f)
            }
        }
        Box(Modifier.fillMaxWidth().height(1.px).background(Color.White.copy(alpha = .16f)))
        Row(Modifier.fillMaxWidth()) {
            for (h in data.next12(now).drop(1).take(5)) {
                Column(Modifier.weight(1f), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(6.px)) {
                    T(hourLabel(h.time, is24), 18, alpha = .75f)
                    T(deg(h.temp), 22, weight = 500)
                }
            }
        }
    }
}

@Composable
private fun UpNextWidget(state: CalendarState) {
    val now by rememberNow()
    val is24 = is24Hour()
    val today = now.toLocalDate()
    val upcoming = state.upcoming(now).take(3)
    val headerDay = upcoming.firstOrNull()?.start?.toLocalDate()
    Column(verticalArrangement = Arrangement.spacedBy(16.px)) {
        WidgetHeader("Up next", headerDay?.let { dayLabel(it, today) })
        when {
            !state.loaded -> Unit
            state.sources.isEmpty() -> {
                T("No calendars yet.", 26, weight = 500)
                T("Add a calendar link from your phone.", 22, alpha = .75f, maxLines = 2)
            }
            upcoming.isEmpty() -> {
                T("Nothing coming up.", 26, weight = 500)
                T("Your calendars are clear for the next few weeks.", 22, alpha = .75f, maxLines = 2)
            }
            // Long titles wrap to two lines; only events that fit whole are shown.
            else -> FitColumn(14.px, Modifier.weight(1f)) {
                for (e in upcoming) {
                    val day = e.start.toLocalDate()
                    val time = eventTimeRange(e, is24)
                    Row(horizontalArrangement = Arrangement.spacedBy(18.px), verticalAlignment = Alignment.CenterVertically) {
                        Box(Modifier.size(12.px).clip(CircleShape).background(Color(state.source(e.calendarId)?.color ?: 0xFFFFFFFF)))
                        Column(verticalArrangement = Arrangement.spacedBy(2.px)) {
                            MiddleEllipsisText(e.title, 26, weight = 500, maxLines = 2, lineHeight = 30)
                            T(if (day == headerDay) time else "${dayLabel(day, today)} · $time", 20, alpha = .75f, lineHeight = 24)
                        }
                    }
                }
            }
        }
    }
}

// ---- Dock -----------------------------------------------------------------------------

@Composable
private fun Dock(
    apps: List<LaunchableApp>,
    lastDockApp: String?,
    focusRequest: Int,
    handled: HandledRequest,
    focusDock: Boolean,
    widgets: WidgetSlots,
    onLaunch: (LaunchableApp) -> Unit,
    onEditDock: (String) -> Unit,
) {
    var focusedName by remember { mutableStateOf<String?>(null) }
    val requesters = remember(apps.map { it.packageName }) { apps.associate { it.packageName to FocusRequester() } }

    // Launch focus = last-used dock app; also after Back, Home, or returning from an app.
    LaunchedEffect(focusRequest, requesters) {
        if (!focusDock || handled.value == focusRequest) return@LaunchedEffect
        handled.value = focusRequest
        val target = requesters[lastDockApp] ?: requesters[apps.firstOrNull()?.packageName] ?: return@LaunchedEffect
        runCatching { target.requestFocus() }
    }

    Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.fillMaxWidth()) {
        // The dock spans the full content width (120–1800 px), like the top bar and the widgets.
        GlassSurface(Modifier.fillMaxWidth(), radius = 48.px) {
            Row(
                Modifier.fillMaxWidth().padding(horizontal = 24.px, vertical = 22.px),
                horizontalArrangement = Arrangement.spacedBy(DOCK_GAP.px, Alignment.CenterHorizontally),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                // Tiles share the dock's width after its padding (1680 − 48); All apps is the Apps tab now.
                val tileW = minOf(260, (1632 - (apps.size - 1) * DOCK_GAP) / apps.size.coerceAtLeast(1))
                for ((i, app) in apps.withIndex()) {
                    GlassButton(
                        onClick = { onLaunch(app) },
                        onLongClick = { onEditDock(app.packageName) },
                        modifier = Modifier.size(tileW.px, DOCK_TILE_H.px).upToWidget(widgets).stopAtEnds(i == 0, i == apps.lastIndex),
                        radius = 28.px,
                        focusRequester = requesters[app.packageName],
                        onFocus = { if (it) focusedName = app.label else if (focusedName == app.label) focusedName = null },
                    ) { AppArt(app, 84) }
                }
            }
        }
        Spacer(Modifier.height(22.px))
        Box(Modifier.height(32.px), contentAlignment = Alignment.Center) {
            focusedName?.let { T(it, 24, weight = 500, tracking = .3) }
        }
    }
}

@Composable
private fun Modifier.upToWidget(widgets: WidgetSlots): Modifier {
    var centerX by remember { mutableStateOf(0f) }
    return onGloballyPositioned { centerX = it.positionInRoot().x + it.size.width / 2f }
        .focusProperties { up = widgets.above(centerX) }
}

/** Dock tiles: taller than the design's 104 px so app banners show at a decent size. */
const val DOCK_TILE_H = 124
const val DOCK_GAP = 18

/** ◀ ▶ stop at the ends of the dock (no wrap, no escaping to the top bar). */
private fun Modifier.stopAtEnds(first: Boolean, last: Boolean) = focusProperties {
    if (first) left = FocusRequester.Cancel
    if (last) right = FocusRequester.Cancel
}

@Composable
fun AppIcon(app: LaunchableApp, sizePx: Int) {
    Image(
        bitmap = app.icon,
        contentDescription = app.label,
        modifier = Modifier.size(sizePx.px).clip(RoundedCornerShape((sizePx / 5).px)),
    )
}

/**
 * Fills a whole tile: the app's TV banner, or (no banner) its icon on the icon's own colour.
 * The tile's glass clip rounds the corners.
 */
@Composable
fun AppArt(app: LaunchableApp, iconPx: Int) {
    val banner = app.banner
    if (banner != null) {
        Image(banner, contentDescription = app.label, modifier = Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
    } else {
        Box(Modifier.fillMaxSize().background(Color(app.iconColor)), contentAlignment = Alignment.Center) {
            AppIcon(app, iconPx)
        }
    }
}
