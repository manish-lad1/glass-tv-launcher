package com.manishlad.glasslauncher

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import com.manishlad.glasslauncher.data.DayPart
import com.manishlad.glasslauncher.data.WallpaperMode
import com.manishlad.glasslauncher.data.WatchItem
import kotlinx.coroutines.launch

private const val TAG = "GlassLauncher"

/** Calendar accent colours; new calendars take the next unused one. */
val CALENDAR_COLORS = listOf(0xFF7FD1FF, 0xFFFFC27A, 0xFFB9A6FF, 0xFF8FE3B0, 0xFFFF9EC7, 0xFF7FE0E0)

/**
 * Stand-in for the phone companion until phase 5. Only adb shell / system can send it (DUMP).
 * Usage: see README ("Configure from adb"). Calendar links are never logged.
 */
class DevConfigReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val app = context.glassApp
        val pending = goAsync()
        val cmd = intent.getStringExtra("cmd")
        app.scope.launch {
            try {
                when (cmd) {
                    "add_calendar" -> {
                        val name = intent.getStringExtra("name") ?: return@launch
                        val url = intent.getStringExtra("url") ?: return@launch
                        val used = app.calendar.state.value.sources.map { it.color }.toSet()
                        val color = intent.getStringExtra("color")?.let { 0xFF000000 or it.removePrefix("#").toLong(16) }
                            ?: CALENDAR_COLORS.firstOrNull { it !in used } ?: CALENDAR_COLORS.first()
                        app.calendar.add(name, url, color)
                        Log.i(TAG, "dev: added calendar '$name'")
                    }
                    "remove_calendar" -> app.calendar.remove(intent.getStringExtra("name") ?: return@launch)
                    "add_watching" -> {
                        app.watching.add(
                            WatchItem(
                                id = "",
                                title = intent.getStringExtra("title") ?: return@launch,
                                packageName = intent.getStringExtra("package") ?: return@launch,
                                upTo = intent.getStringExtra("upto"),
                                timeLeft = intent.getStringExtra("left"),
                                progress = intent.getStringExtra("progress")?.toFloatOrNull(),
                                deepLink = intent.getStringExtra("link"),
                            )
                        )
                        Log.i(TAG, "dev: added watching item")
                    }
                    "clear_watching" -> app.watching.clear()
                    "remove_wallpaper" -> {
                        app.wallpapers.remove(intent.getStringExtra("name") ?: return@launch)
                        app.wallpaperChanged()
                    }
                    // mode: fixed (+ name), daily, hours (+ hours), timeofday (+ morning/afternoon/evening/night = "Name|Name")
                    "wallpaper_mode" -> {
                        val lib = app.wallpapers
                        val mode = when (intent.getStringExtra("mode")) {
                            "daily" -> WallpaperMode.Daily
                            "hours" -> WallpaperMode.EveryHours(intent.getStringExtra("hours")?.toIntOrNull() ?: 6)
                            "timeofday" -> WallpaperMode.TimeOfDay(DayPart.entries.associateWith { part ->
                                intent.getStringExtra(part.name.lowercase()).orEmpty().split('|')
                                    .mapNotNull { n -> lib.findByName(n.trim())?.id }
                            })
                            else -> WallpaperMode.Fixed(lib.findByName(intent.getStringExtra("name") ?: "")?.id ?: return@launch)
                        }
                        lib.setMode(mode)
                        app.wallpaperChanged()
                        Log.i(TAG, "dev: wallpaper mode $mode")
                    }
                    "idle" -> app.idleMsOverride = intent.getStringExtra("seconds")?.toLongOrNull()?.times(1000)
                    "unpair_all" -> app.pairing.revokeAll()
                    "offline" -> app.simulateOffline = intent.getStringExtra("on") != "false"
                    "refresh" -> {
                        app.weather.refresh()
                        app.calendar.refresh()
                    }
                    else -> Log.w(TAG, "dev: unknown cmd $cmd")
                }
            } finally {
                pending.finish()
            }
        }
    }
}
