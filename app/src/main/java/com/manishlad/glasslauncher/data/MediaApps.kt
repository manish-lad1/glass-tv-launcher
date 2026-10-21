package com.manishlad.glasslauncher.data

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import org.json.JSONObject
import java.io.File

/** Whether an app has been seen sharing the title of what it plays. */
data class MediaAppInfo(val sharesTitles: Boolean, val lastSeen: Long)

/**
 * A small record, built up during normal viewing, of which apps share titles through their
 * media session (so automatic Now watching works) and which don't (add those by hand).
 */
class MediaAppsRecord(context: Context) {
    private val file = File(context.filesDir, "media_apps.json")
    private val _apps = MutableStateFlow(read())
    val apps: StateFlow<Map<String, MediaAppInfo>> = _apps

    /** Seen with a title: shares. Never downgraded afterwards. */
    @Synchronized
    fun markShares(pkg: String) = put(pkg, true)

    /** Seen playing for a while without a title. Ignored if the app has ever shared one. */
    @Synchronized
    fun markNoTitle(pkg: String) {
        if (_apps.value[pkg]?.sharesTitles == true) return
        put(pkg, false)
    }

    private fun put(pkg: String, shares: Boolean) {
        val old = _apps.value[pkg]
        val now = System.currentTimeMillis()
        // Avoid rewriting the file every tick: only on a change, or hourly to refresh lastSeen.
        if (old != null && old.sharesTitles == shares && now - old.lastSeen < 3_600_000) return
        val next = _apps.value + (pkg to MediaAppInfo(shares, now))
        _apps.value = next
        file.writeText(JSONObject().apply {
            for ((p, i) in next) put(p, JSONObject().put("shares", i.sharesTitles).put("lastSeen", i.lastSeen))
        }.toString())
    }

    private fun read(): Map<String, MediaAppInfo> {
        if (!file.exists()) return emptyMap()
        val o = runCatching { JSONObject(file.readText()) }.getOrNull() ?: return emptyMap()
        return o.keys().asSequence().associateWith { p ->
            val i = o.getJSONObject(p)
            MediaAppInfo(i.getBoolean("shares"), i.getLong("lastSeen"))
        }
    }
}
