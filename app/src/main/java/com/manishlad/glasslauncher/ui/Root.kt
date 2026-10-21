package com.manishlad.glasslauncher.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.unit.IntSize
import com.manishlad.glasslauncher.LaunchableApp
import com.manishlad.glasslauncher.LauncherViewModel
import com.manishlad.glasslauncher.Screen
import com.manishlad.glasslauncher.data.WatchItem

@Composable
fun LauncherRoot(
    vm: LauncherViewModel,
    onLaunch: (LaunchableApp) -> Unit,
    onResume: (WatchItem) -> Unit,
    onOpenSettings: (String) -> Unit,
    onSwitchInput: (com.manishlad.glasslauncher.data.TvSource?) -> Unit,
) {
    val rootSize = remember { mutableStateOf(IntSize.Zero) }
    val weather by vm.weather.collectAsState()
    val calendar by vm.calendar.collectAsState()
    val watching by vm.watching.collectAsState()
    val watchingLoaded by vm.watchingLoaded.collectAsState()
    Box(
        Modifier
            .fillMaxSize()
            .background(Color(0xFF0B1026))
            .onSizeChanged { rootSize.value = it },
    ) {
        val backdrop = vm.backdrop ?: return@Box
        // Ambient covers everything; the screen underneath stays composed (invisible) so focus
        // is exactly where it was when a key wakes it.
        Box(Modifier.fillMaxSize().graphicsLayer { alpha = if (vm.ambient) 0f else 1f }) {
        when (vm.screen) {
            Screen.Home -> {
                FullScreenImage(backdrop.sharp)
                // Cold start (boot, crash): wallpaper only until the apps and stored data are in.
                if (!vm.appsLoaded || !calendar.loaded || !watchingLoaded) return@Box
                CompositionLocalProvider(LocalGlassSource provides GlassSource(backdrop.blurred, rootSize)) {
                    HomeScreen(
                        data = HomeData(vm.dock, vm.apps, vm.lastDockApp, weather, calendar, watching, vm.watchingIndex, vm.widgetsHidden),
                        actions = HomeActions(
                            onLaunch = onLaunch,
                            onAllApps = vm::openAllApps,
                            onOpenSettings = onOpenSettings,
                            onOpenWeather = { vm.openWidget(Screen.Weather) },
                            onOpenCalendar = { vm.openWidget(Screen.Calendar) },
                            onOpenWatching = { vm.openWidget(Screen.Watching) },
                            onOpenPairing = vm::openPairing,
                            onOpenQuickSettings = vm::openQuickSettings,
                            onEditDock = vm::startDockEdit,
                            onResume = onResume,
                            onWatchingIndex = { vm.watchingIndex = it },
                            onToggleWidgets = vm::toggleWidgets,
                        ),
                        focusRequest = vm.homeFocusRequest,
                        returnToWidget = vm.returnToWidget,
                        profileFocusRequest = vm.profileFocusRequest,
                    )
                    if (vm.quickSettingsOpen) {
                        QuickSettingsPanel(
                            inputs = vm.inputs,
                            lastInputId = vm.lastInputId,
                            wifi = vm.wifi,
                            wallpapers = vm.wallpaperThumbs,
                            homeWallpaperId = vm.homeSlide?.info?.id,
                            wallpaperMode = vm.wallpaperMode,
                            actions = QuickSettingsActions(
                                onInput = onSwitchInput,
                                onWallpaper = vm::setHomeWallpaper,
                                onOpenSettings = { vm.closeQuickSettings(); onOpenSettings(it) },
                                onPairPhone = { vm.closeQuickSettings(); vm.openPairing() },
                            ),
                        )
                    }
                }
            }
            Screen.Watching -> WatchingScreen(
                items = watching,
                apps = vm.apps,
                index = vm.watchingIndex,
                onIndex = { vm.watchingIndex = it },
                onResume = onResume,
                onRemove = { vm.removeWatching(it.id) },
            )
            else -> {
                FullScreenImage(backdrop.heavy ?: backdrop.blurred)
                CompositionLocalProvider(LocalGlassSource provides GlassSource(backdrop.heavy ?: backdrop.blurred, rootSize)) {
                    when (vm.screen) {
                        Screen.AllApps -> AllAppsScreen(vm.apps, onLaunch, vm.allAppsAddMode) { vm.addToDock(it.packageName, it.label) }
                        Screen.DockEdit -> DockEditScreen(
                            packages = vm.editDock, apps = vm.apps, cursor = vm.editCursor, moving = vm.editMoving,
                            onLeft = vm::editLeft, onRight = vm::editRight, onOk = vm::editOk, onRemove = vm::editRemove,
                        )
                        Screen.Weather -> WeatherScreen(weather)
                        Screen.Calendar -> CalendarScreen(calendar)
                        Screen.Pairing -> {
                            val pairing by vm.pairing.collectAsState()
                            PairingScreen(pairing, onNewCode = vm::newPairingCode, onDone = vm::back)
                        }
                        else -> Unit
                    }
                }
            }
        }
        }
        if (vm.ambient) {
            val slide by vm.slideshow.slide.collectAsState()
            AmbientScreen(slide, weather, calendar, watching.getOrNull(vm.watchingIndex) ?: watching.firstOrNull())
        }
    }
}

@Composable
private fun FullScreenImage(image: ImageBitmap) {
    Canvas(Modifier.fillMaxSize()) {
        drawImage(image, dstSize = IntSize(size.width.toInt(), size.height.toInt()), filterQuality = FilterQuality.Low)
    }
}
