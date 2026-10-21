package com.manishlad.glasslauncher

import android.app.Application
import android.app.NotificationManager
import android.content.ComponentName
import android.util.Log
import com.manishlad.glasslauncher.companion.COMPANION_PORT
import com.manishlad.glasslauncher.companion.CompanionServer
import com.manishlad.glasslauncher.companion.Pairing
import fi.iki.elonen.NanoHTTPD
import com.manishlad.glasslauncher.data.CalendarRepository
import com.manishlad.glasslauncher.data.DockRepository
import com.manishlad.glasslauncher.data.MediaAppsRecord
import com.manishlad.glasslauncher.data.NowWatchingRepository
import com.manishlad.glasslauncher.data.WallpaperLibrary
import com.manishlad.glasslauncher.data.WeatherRepository
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import okhttp3.OkHttpClient
import java.util.concurrent.TimeUnit

/** Process-wide data. The launcher process stays alive as HOME, so the refresh loop lives here. */
class GlassApp : Application() {
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    /** Dev only (adb): shorter idle-to-Ambient time for testing. Not persisted. */
    @Volatile var idleMsOverride: Long? = null

    /** Dev only (adb): make all network calls fail, to check offline states. Not persisted. */
    @Volatile var simulateOffline = false

    val http by lazy {
        OkHttpClient.Builder()
            .addInterceptor { chain ->
                if (simulateOffline) throw java.io.IOException("simulated offline")
                chain.proceed(chain.request())
            }
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(30, TimeUnit.SECONDS)
            .build()
    }
    val weather by lazy { WeatherRepository(this, http) }
    val calendar by lazy { CalendarRepository(this, http) }
    val watching by lazy { NowWatchingRepository(this) }
    val wallpapers by lazy { WallpaperLibrary(this) }
    val dock by lazy { DockRepository(this) }
    val pairing by lazy { Pairing(this) }
    val mediaApps by lazy { MediaAppsRecord(this) }
    private var server: CompanionServer? = null

    /** Whether the media-session listener (automatic Now watching) has been granted. */
    fun mediaTrackingEnabled(): Boolean =
        getSystemService(NotificationManager::class.java)
            .isNotificationListenerAccessGranted(ComponentName(this, MediaWatcher::class.java))

    /** Bumped when the library or mode changes, so Home re-picks its wallpaper. */
    val wallpaperVersion = MutableStateFlow(0)
    fun wallpaperChanged() { wallpaperVersion.value++ }

    override fun onCreate() {
        super.onCreate()
        // Phone companion web server (home network only).
        server = CompanionServer(this).also {
            runCatching { it.start(NanoHTTPD.SOCKET_READ_TIMEOUT, true) }
                .onSuccess { Log.i("GlassLauncher", "companion server on :$COMPANION_PORT") }
                .onFailure { e -> Log.w("GlassLauncher", "companion server failed to start: ${e.message}") }
        }
        scope.launch {
            weather.loadCache()
            calendar.load()
            watching.load()
            dock.load()
            wallpapers.load()
            wallpaperChanged()
            // Weather and calendars refresh every 30 min (retry every 5 min after a failure).
            while (true) {
                launch { weather.refreshIfStale() }
                launch { calendar.refreshIfStale() }
                delay(60_000)
            }
        }
    }
}

val android.content.Context.glassApp get() = applicationContext as GlassApp
