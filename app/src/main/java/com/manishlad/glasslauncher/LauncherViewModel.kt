package com.manishlad.glasslauncher

import android.app.Application
import android.content.Context
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import android.graphics.BitmapFactory
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import com.manishlad.glasslauncher.data.DOCK_MAX
import com.manishlad.glasslauncher.data.TvSource
import com.manishlad.glasslauncher.data.TvSources
import com.manishlad.glasslauncher.data.WallpaperInfo
import com.manishlad.glasslauncher.data.WallpaperMode
import com.manishlad.glasslauncher.data.WifiNetworks
import com.manishlad.glasslauncher.data.WifiSnapshot
import kotlinx.coroutines.delay
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private val Context.dataStore by preferencesDataStore("launcher")
private val LAST_DOCK_APP = stringPreferencesKey("last_dock_app")
private val WIDGETS_HIDDEN = booleanPreferencesKey("widgets_hidden")
private val LAST_INPUT = stringPreferencesKey("last_input")

enum class Screen { Home, AllApps, Weather, Calendar, Watching, Pairing, DockEdit }

/** Home widget slots, left to right. */
object Widget {
    const val WEATHER = 0
    const val CALENDAR = 1
    const val WATCHING = 2
}

class LauncherViewModel(app: Application) : AndroidViewModel(app) {
    val repo = AppRepository(app)
    private val glass = app as GlassApp
    val weather = glass.weather.state
    val calendar = glass.calendar.state
    val watching = glass.watching.items
    val watchingLoaded = glass.watching.loaded

    /** Apps have been listed at least once; until then Home shows only the wallpaper. */
    var appsLoaded by mutableStateOf(false)
        private set

    var apps by mutableStateOf<List<LaunchableApp>>(emptyList())
        private set
    var backdrop by mutableStateOf<Backdrop?>(null)
        private set
    var lastDockApp by mutableStateOf<String?>(null)
        private set
    var screen by mutableStateOf(Screen.Home)
        private set

    /** Front of the Now watching stack. */
    var watchingIndex by mutableIntStateOf(0)

    /** Bumped whenever Home should re-apply focus: to [returnToWidget], or the last-used dock app. */
    var homeFocusRequest by mutableIntStateOf(0)
        private set
    var returnToWidget by mutableStateOf<Int?>(null)
        private set

    var dockPackages by mutableStateOf(DEFAULT_DOCK)
        private set

    val dock: List<LaunchableApp>
        get() = dockPackages.mapNotNull { pkg -> apps.firstOrNull { it.packageName == pkg } }

    init {
        viewModelScope.launch {
            val prefs = app.dataStore.data.first()
            lastDockApp = prefs[LAST_DOCK_APP]
            widgetsHidden = prefs[WIDGETS_HIDDEN] ?: false
            lastInputId = prefs[LAST_INPUT]
        }
    }

    /** The wallpaper on Home, kept as a slide so Ambient can start from it without re-decoding. */
    var homeSlide: Slide? = null
        private set
    private var wallpaperJob: Job? = null

    /** Re-pick Home's wallpaper (on start, library/mode change, and on wake). No-op if unchanged. */
    fun refreshHomeWallpaper() {
        // Ambient may be showing Home's slide; the swap happens on wake (exitAmbient calls this).
        if (ambient) return
        val lib = glass.wallpapers
        val pick = lib.pickForHome()
        if (pick.id == homeSlide?.info?.id) return
        wallpaperJob?.cancel()
        wallpaperJob = viewModelScope.launch {
            val b = withContext(Dispatchers.Default) { Wallpapers.fromBitmap(lib.decode(pick)) }
            val all = lib.library.value
            val old = homeSlide
            homeSlide = Slide(pick, all.indexOfFirst { it.id == pick.id }.coerceAtLeast(0), all.size, b)
            backdrop = b
            old?.backdrop?.recycleLater(viewModelScope)
        }
    }

    // ---- Ambient ----

    val slideshow = AmbientSlideshow(glass, viewModelScope)
    var ambient by mutableStateOf(false)
        private set

    fun enterAmbient() {
        if (ambient) return
        ambient = true
        slideshow.start(homeSlide)
    }

    /** Any key wakes Ambient; Home underneath kept its focus. The wallpaper may change quietly now. */
    fun exitAmbient() {
        if (!ambient) return
        ambient = false
        slideshow.stop()
        refreshHomeWallpaper()
    }

    fun refreshApps() {
        viewModelScope.launch {
            apps = withContext(Dispatchers.IO) { repo.load() }
            appsLoaded = true
        }
    }

    fun openAllApps() {
        screen = Screen.AllApps
    }

    fun openWidget(target: Screen) {
        screen = target
    }

    val pairing = glass.pairing.state

    /** Phone pairing screen (from holding OK on "M"; moves to Quick settings in phase 6). */
    fun openPairing() {
        glass.pairing.start()
        screen = Screen.Pairing
    }

    fun newPairingCode() = glass.pairing.start()

    /** Home, or returning from an app: close everything and focus the dock. */
    fun returnHome() {
        if (screen == Screen.Pairing) glass.pairing.cancel()
        quickSettingsOpen = false
        allAppsAddMode = false
        editMoving = false
        screen = Screen.Home
        returnToWidget = null
        homeFocusRequest++
    }

    /** Back: from an expanded widget return to that widget; otherwise to the dock. */
    fun back() {
        if (quickSettingsOpen) { closeQuickSettings(); return }
        if (screen == Screen.AllApps && allAppsAddMode) { allAppsAddMode = false; screen = Screen.DockEdit; return }
        if (screen == Screen.Pairing) glass.pairing.cancel()
        editMoving = false
        returnToWidget = when (screen) {
            Screen.Weather -> Widget.WEATHER
            Screen.Calendar -> Widget.CALENDAR
            Screen.Watching -> Widget.WATCHING
            else -> null
        }
        screen = Screen.Home
        homeFocusRequest++
    }

    fun removeWatching(id: String) {
        viewModelScope.launch {
            glass.watching.remove(id)
            val size = glass.watching.items.value.size
            if (watchingIndex >= size) watchingIndex = (size - 1).coerceAtLeast(0)
            if (size == 0 && screen == Screen.Watching) back()
        }
    }

    // ---- Quick settings ----

    var quickSettingsOpen by mutableStateOf(false)
        private set
    var inputs by mutableStateOf<List<TvSource>>(emptyList())
        private set
    /** Android won't say which input is on screen; this is the last one switched to from here. */
    var lastInputId by mutableStateOf<String?>(null)
        private set
    var wifi by mutableStateOf<WifiSnapshot?>(null)
        private set
    var wallpaperThumbs by mutableStateOf<List<Pair<WallpaperInfo, ImageBitmap>>>(emptyList())
        private set
    /** Bumped when focus should return to the profile ("M") button. */
    var profileFocusRequest by mutableIntStateOf(0)
        private set
    private val thumbCache = HashMap<String, ImageBitmap>()

    fun openQuickSettings() {
        screen = Screen.Home
        quickSettingsOpen = true
        val app = getApplication<Application>()
        viewModelScope.launch {
            inputs = withContext(Dispatchers.IO) { TvSources.load(app) }
            WifiNetworks.requestScan(app)
            wifi = withContext(Dispatchers.IO) { WifiNetworks.snapshot(app) }
            wallpaperThumbs = withContext(Dispatchers.IO) {
                glass.wallpapers.library.value.map { info ->
                    info to thumbCache.getOrPut(info.id) {
                        val bytes = glass.wallpapers.thumbnail(info)
                        BitmapFactory.decodeByteArray(bytes, 0, bytes.size).asImageBitmap()
                    }
                }
            }
            // A fresh scan takes a few seconds; show what's known, then update once.
            delay(4000)
            if (quickSettingsOpen) wifi = withContext(Dispatchers.IO) { WifiNetworks.snapshot(app) }
        }
    }

    fun closeQuickSettings() {
        if (!quickSettingsOpen) return
        quickSettingsOpen = false
        profileFocusRequest++
    }

    fun onInputSwitched(source: TvSource) {
        lastInputId = source.id
        quickSettingsOpen = false
        viewModelScope.launch { getApplication<Application>().dataStore.edit { it[LAST_INPUT] = source.id } }
    }

    val wallpaperMode get() = glass.wallpapers.mode.value

    fun setHomeWallpaper(info: WallpaperInfo) {
        viewModelScope.launch {
            glass.wallpapers.setMode(WallpaperMode.Fixed(info.id))
            glass.wallpaperChanged()
        }
    }

    // ---- Dock edit mode ----

    /** The dock being edited (saved as it changes, so the phone sees it too). */
    var editDock by mutableStateOf<List<String>>(emptyList())
        private set
    /** Cursor position: 0 until editDock.size; editDock.size is the "+" slot. */
    var editCursor by mutableIntStateOf(0)
        private set
    var editMoving by mutableStateOf(false)
        private set
    /** All apps opened from the "+" slot: OK adds the app and returns to the editor. */
    var allAppsAddMode by mutableStateOf(false)
        private set

    fun startDockEdit(packageName: String) {
        editDock = dockPackages
        editCursor = editDock.indexOf(packageName).coerceAtLeast(0)
        editMoving = editDock.contains(packageName)
        screen = Screen.DockEdit
    }

    fun editLeft() = editStep(-1)
    fun editRight() = editStep(1)

    private fun editStep(d: Int) {
        val n = editDock.size
        if (editMoving) {
            val j = editCursor + d
            if (j < 0 || j >= n) return
            editDock = editDock.toMutableList().also { java.util.Collections.swap(it, editCursor, j) }
            editCursor = j
            saveDock()
        } else {
            val max = if (n < DOCK_MAX) n else n - 1 // the "+" slot only while there's room
            editCursor = (editCursor + d).coerceIn(0, max)
        }
    }

    fun editOk() {
        when {
            editMoving -> editMoving = false
            editCursor >= editDock.size -> { allAppsAddMode = true; screen = Screen.AllApps }
            else -> editMoving = true
        }
    }

    fun editRemove() {
        if (editCursor >= editDock.size) return
        editDock = editDock.toMutableList().also { it.removeAt(editCursor) }
        editMoving = false
        editCursor = editCursor.coerceAtMost(editDock.size)
        saveDock()
    }

    /** Hold OK in All apps (or OK in add mode): add to the dock. Returns a message to show. */
    fun addToDock(packageName: String, label: String): String {
        val dock = if (screen == Screen.DockEdit || allAppsAddMode) editDock else dockPackages
        val msg = when {
            packageName in dock -> "$label is already in the dock"
            dock.size >= DOCK_MAX -> "The dock is full ($DOCK_MAX apps). Remove one first."
            else -> {
                val next = dock + packageName
                editDock = next
                editCursor = next.size - 1
                viewModelScope.launch { glass.dock.set(next) }
                "Added $label to the dock"
            }
        }
        if (allAppsAddMode) {
            allAppsAddMode = false
            editMoving = false
            screen = Screen.DockEdit
        }
        return msg
    }

    private fun saveDock() {
        val next = editDock
        viewModelScope.launch { glass.dock.set(next) }
    }

    /** Widgets hidden so the wallpaper shows; remembered across restarts. */
    var widgetsHidden by mutableStateOf(false)
        private set

    fun toggleWidgets() {
        widgetsHidden = !widgetsHidden
        val hidden = widgetsHidden
        viewModelScope.launch { getApplication<Application>().dataStore.edit { it[WIDGETS_HIDDEN] = hidden } }
    }

    fun onLaunched(packageName: String) {
        if (packageName !in dockPackages) return
        lastDockApp = packageName
        viewModelScope.launch {
            getApplication<Application>().dataStore.edit { it[LAST_DOCK_APP] = packageName }
        }
    }

    // Last in the class: the collector runs immediately (Main.immediate) and needs every property above.
    init {
        // Home's wallpaper: picked by the library's mode, reloaded when the library/mode changes.
        viewModelScope.launch { glass.wallpaperVersion.collect { refreshHomeWallpaper() } }
        viewModelScope.launch { glass.dock.packages.collect { dockPackages = it } }
    }
}
