@file:OptIn(androidx.compose.ui.ExperimentalComposeUiApi::class)

package com.manishlad.glasslauncher.ui

import android.provider.Settings
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.focusGroup
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
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
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.manishlad.glasslauncher.data.TvSource
import com.manishlad.glasslauncher.data.WallpaperInfo
import com.manishlad.glasslauncher.data.WallpaperMode
import com.manishlad.glasslauncher.data.WifiSnapshot
import java.time.format.DateTimeFormatter

class QuickSettingsActions(
    val onInput: (TvSource?) -> Unit, // null = Home
    val onWallpaper: (WallpaperInfo) -> Unit,
    val onOpenSettings: (String) -> Unit,
    val onPairPhone: () -> Unit,
)

private val Ink = Color(0xFF16132E)

/**
 * Quick settings (design 04): a glass panel on the right over Home. Focus stays inside the panel
 * until Back closes it. Inputs switch for real; picture/sound/timer open the TV's own menus.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun QuickSettingsPanel(
    inputs: List<TvSource>,
    lastInputId: String?,
    wifi: WifiSnapshot?,
    wallpapers: List<Pair<WallpaperInfo, androidx.compose.ui.graphics.ImageBitmap>>,
    homeWallpaperId: String?,
    wallpaperMode: WallpaperMode,
    actions: QuickSettingsActions,
) {
    val first = remember { FocusRequester() }
    LaunchedEffect(Unit) { runCatching { first.requestFocus() } }
    val now by rememberNow()
    val is24 = is24Hour()

    Box(Modifier.fillMaxSize().background(Color(0x59060614))) {
        GlassSurface(
            Modifier
                .align(Alignment.TopEnd)
                .padding(top = 48.px, end = 48.px, bottom = 48.px)
                .width(620.px)
                .fillMaxSize(),
            radius = 48.px,
        ) {
            Column(
                Modifier
                    .fillMaxSize()
                    .focusGroup()
                    // Keep focus inside the panel; Back closes it.
                    .focusProperties { exit = { FocusRequester.Cancel } }
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = 44.px, vertical = 44.px),
            ) {
                // Profile header
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(18.px)) {
                    Box(Modifier.size(64.px).clip(CircleShape).background(Color.White), contentAlignment = Alignment.Center) {
                        InkText("M", 26, FontWeight.SemiBold)
                    }
                    Column {
                        T("Manish", 32, weight = 500)
                        val net = when {
                            wifi == null -> ""
                            wifi.connectedSsid != null -> " · Wi-Fi connected"
                            wifi.onEthernet -> " · Ethernet"
                            wifi.online -> " · Online"
                            else -> " · Offline"
                        }
                        T(now.format(DateTimeFormatter.ofPattern(if (is24) "HH:mm" else "h:mm a")) + net, 22, alpha = .78f)
                    }
                }

                Section("Input", null)
                FlowRow(horizontalArrangement = Arrangement.spacedBy(12.px), verticalArrangement = Arrangement.spacedBy(12.px)) {
                    Pill("Home", selected = true, modifier = Modifier.focusRequester(first)) { actions.onInput(null) }
                    for (src in inputs) {
                        Pill(src.label, selected = false, dot = src.id == lastInputId) { actions.onInput(src) }
                    }
                }
                if (lastInputId != null && inputs.any { it.id == lastInputId }) {
                    Spacer(Modifier.height(10.px))
                    T("• last used from here", 18, alpha = .6f)
                }

                Section("Wi-Fi", wifi?.connectedSsid?.let { "Connected to $it" } ?: if (wifi?.onEthernet == true) "Using Ethernet" else null)
                WifiList(wifi) { actions.onOpenSettings(Settings.ACTION_WIFI_SETTINGS) }

                Section("Wallpaper", modeLabel(wallpaperMode))
                Row(horizontalArrangement = Arrangement.spacedBy(14.px), modifier = Modifier.fillMaxWidth()) {
                    for ((info, thumb) in wallpapers.take(3)) {
                        WallThumb(thumb, info.name, info.id == homeWallpaperId) { actions.onWallpaper(info) }
                    }
                }
                if (wallpapers.size > 3) {
                    Spacer(Modifier.height(14.px))
                    Row(horizontalArrangement = Arrangement.spacedBy(14.px), modifier = Modifier.fillMaxWidth()) {
                        for ((info, thumb) in wallpapers.drop(3).take(3)) {
                            WallThumb(thumb, info.name, info.id == homeWallpaperId) { actions.onWallpaper(info) }
                        }
                    }
                }

                Section("TV settings", "Opens the TV's own menus")
                Column(verticalArrangement = Arrangement.spacedBy(14.px)) {
                    SettingRow(Glyphs.Sun, "Picture & brightness", "Picture mode, backlight, colour") { actions.onOpenSettings("android.settings.DISPLAY_SETTINGS") }
                    SettingRow(Glyphs.Headphones, "Sound & Bluetooth", "Speakers, headphones, sound mode") { actions.onOpenSettings(Settings.ACTION_SOUND_SETTINGS) }
                    SettingRow(Glyphs.Moon, "Sleep timer", "Turn the TV off after a set time") { actions.onOpenSettings("android.settings.TIMER_SETTINGS") }
                    SettingRow(Glyphs.Sun, "All settings", "Network, apps, system") { actions.onOpenSettings(Settings.ACTION_SETTINGS) }
                    SettingRow(Glyphs.Phone, "Pair a phone", "Manage the TV from your phone's browser") { actions.onPairPhone() }
                }
            }
        }
    }
}

private fun modeLabel(m: WallpaperMode) = when (m) {
    is WallpaperMode.Fixed -> "Fixed"
    WallpaperMode.Daily -> "Rotates daily"
    is WallpaperMode.EveryHours -> "Every ${m.hours} h"
    is WallpaperMode.TimeOfDay -> "By time of day"
}

@Composable
private fun Section(title: String, note: String?) {
    Spacer(Modifier.height(40.px))
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
        SectionLabel(title, Modifier.alignByBaseline())
        if (note != null) T(note, 20, Modifier.alignByBaseline().padding(start = 16.px), alpha = .75f)
    }
    Spacer(Modifier.height(16.px))
}

@Composable
private fun InkText(text: String, size: Int, weight: FontWeight = FontWeight.Medium) =
    androidx.compose.foundation.text.BasicText(text, style = TextStyle(color = Ink, fontFamily = Inter, fontWeight = weight, fontSize = size.pxSp))

/** Input chip: selected = white with dark text (as in the design); focus = glass ring + scale. */
@Composable
private fun Pill(text: String, selected: Boolean, modifier: Modifier = Modifier, dot: Boolean = false, onClick: () -> Unit) {
    GlassButton(onClick = onClick, modifier = modifier, radius = 999.dp, focusScale = 1.08f) {
        Box(Modifier.then(if (selected) Modifier.background(Color.White) else Modifier)) {
            Row(Modifier.padding(horizontal = 24.px, vertical = 12.px), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.px)) {
                if (selected) InkText(text, 24) else T(text, 24, weight = 500)
                if (dot) Box(Modifier.size(8.px).clip(CircleShape).background(Color(0xFF7FD1FF)))
            }
        }
    }
}

@Composable
private fun WallThumb(thumb: androidx.compose.ui.graphics.ImageBitmap, name: String, selected: Boolean, onClick: () -> Unit) {
    var focused by remember { mutableStateOf(false) }
    val scale by animateFloatAsState(if (focused) 1.08f else 1f, tween(160), label = "thumb")
    Box(
        Modifier
            .size(166.px, 92.px)
            .graphicsLayer { scaleX = scale; scaleY = scale }
            .clip(RoundedCornerShape(14.px))
            .border(if (selected || focused) 3.px else 1.px, Color.White.copy(alpha = if (selected || focused) 1f else .3f), RoundedCornerShape(14.px))
            .tvSelectable(onFocus = { focused = it }, onClick = onClick),
    ) {
        Image(thumb, contentDescription = name, modifier = Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
    }
}

@Composable
private fun SettingRow(icon: ImageVector, title: String, sub: String, onClick: () -> Unit) {
    GlassButton(onClick = onClick, modifier = Modifier.fillMaxWidth(), radius = 22.px, focusScale = 1.03f, contentAlignment = Alignment.CenterStart) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 24.px, vertical = 18.px), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(20.px)) {
            Glyph(icon, 30)
            Column(Modifier.weight(1f)) {
                T(title, 26, weight = 500)
                T(sub, 20, alpha = .78f)
            }
            Glyph(Glyphs.Chevron, 26)
        }
    }
}

/** Connected network first (✓), then others by signal; choosing one opens the TV's Wi-Fi settings. */
@Composable
private fun WifiList(wifi: WifiSnapshot?, onOpen: () -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(10.px)) {
        when {
            wifi == null -> T("Looking for networks…", 22, alpha = .75f)
            !wifi.hasPermission -> T("Wi-Fi list needs the location permission (see README).", 22, alpha = .75f, maxLines = 2)
            wifi.networks.isEmpty() -> T("No networks found yet.", 22, alpha = .75f)
            else -> for (n in wifi.networks.take(5)) {
                GlassButton(onClick = onOpen, modifier = Modifier.fillMaxWidth(), radius = 18.px, focusScale = 1.03f, contentAlignment = Alignment.CenterStart) {
                    Row(Modifier.fillMaxWidth().padding(horizontal = 22.px, vertical = 14.px), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(16.px)) {
                        SignalBars(n.level)
                        Column(Modifier.weight(1f)) {
                            T(n.ssid, 24, weight = if (n.connected) 600 else 400)
                            if (n.connected) T("Connected", 18, alpha = .78f)
                        }
                        if (n.secured) Glyph(Glyphs.Lock, 22, Modifier.graphicsLayer { alpha = .7f })
                        if (n.connected) Glyph(Glyphs.Check, 26)
                    }
                }
            }
        }
        SettingRow(Glyphs.Wifi, "Wi-Fi settings", "Join or switch networks") { onOpen() }
    }
}

@Composable
private fun SignalBars(level: Int) {
    Canvas(Modifier.size(26.px, 22.px)) {
        val w = size.width / 4.6f
        for (i in 0..3) {
            val h = size.height * (i + 1) / 4f
            drawRoundRect(
                Color.White.copy(alpha = if (i <= level) 1f else .25f),
                topLeft = Offset(i * w * 1.2f, size.height - h),
                size = Size(w, h),
                cornerRadius = androidx.compose.ui.geometry.CornerRadius(w / 3),
            )
        }
    }
}
