package com.manishlad.glasslauncher.data

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.UUID

/**
 * One title Manish is watching. The TV can't read other apps' playback, so everything here
 * is what he set from the phone: where he's up to, time left, progress.
 */
data class WatchItem(
    val id: String,
    val title: String,
    val packageName: String,
    /** "S2 · E5", or null for a film. */
    val upTo: String? = null,
    /** "24 min left", "not started". */
    val timeLeft: String? = null,
    /** 0..1, or null to hide the bar. */
    val progress: Float? = null,
    /** Optional deep link to the title; otherwise Resume just opens the app. */
    val deepLink: String? = null,
    /** Uploaded artwork in app storage; null = generated placeholder. */
    val artworkPath: String? = null,
    /** Added automatically from the app's media session (vs. set by hand on the phone). */
    val auto: Boolean = false,
    /** Last time this item was changed or seen playing (epoch ms). */
    val updatedAt: Long = 0,
)

class NowWatchingRepository(context: Context) {
    private val file = File(context.filesDir, "now_watching.json")
    private val _items = MutableStateFlow<List<WatchItem>>(emptyList())
    val items: StateFlow<List<WatchItem>> = _items
    private val _loaded = MutableStateFlow(false)
    val loaded: StateFlow<Boolean> = _loaded

    suspend fun load() = withContext(Dispatchers.IO) {
        _items.value = read()
        _loaded.value = true
    }

    private val mutex = Mutex()
    private val artDir = File(context.filesDir, "artwork").apply { mkdirs() }

    suspend fun add(item: WatchItem): WatchItem {
        val added = item.copy(id = item.id.ifBlank { UUID.randomUUID().toString() }, updatedAt = System.currentTimeMillis())
        update { it + added }
        return added
    }

    suspend fun edit(id: String, change: (WatchItem) -> WatchItem) =
        update { list -> list.map { if (it.id == id) change(it).copy(updatedAt = System.currentTimeMillis()) else it } }

    suspend fun remove(id: String) {
        update { list -> list.filterNot { it.id == id } }
        File(artDir, "$id.jpg").delete()
    }

    /** New order by id; ids not mentioned keep their relative order at the end. */
    suspend fun reorder(ids: List<String>) = update { list ->
        val byId = list.associateBy { it.id }
        ids.mapNotNull { byId[it] } + list.filter { it.id !in ids }
    }

    suspend fun clear() = update { emptyList() }

    /** Saves artwork (already a JPEG file) for an item, replacing any previous one. */
    suspend fun setArtwork(id: String, jpeg: File) {
        val dest = File(artDir, "$id.jpg")
        withContext(Dispatchers.IO) { jpeg.copyTo(dest, overwrite = true) }
        // A new path each time so the TV's image cache picks up the change.
        edit(id) { it.copy(artworkPath = dest.path + "?v=" + System.currentTimeMillis()) }
    }

    fun artworkFile(id: String) = File(artDir, "$id.jpg").takeIf { it.exists() }

    /**
     * Upsert from a media session. Matches by app + title (any item, so a hand-set entry isn't
     * duplicated, but only auto items get their progress overwritten). [toFront] when playback starts.
     */
    suspend fun upsertAuto(packageName: String, title: String, progress: Float?, timeLeft: String?, toFront: Boolean): WatchItem? {
        var result: WatchItem? = null
        update { list ->
            val i = list.indexOfFirst { it.packageName == packageName && it.title.equals(title, ignoreCase = true) }
            val now = System.currentTimeMillis()
            val item = if (i >= 0) {
                val old = list[i]
                if (old.auto) old.copy(progress = progress ?: old.progress, timeLeft = timeLeft ?: old.timeLeft, updatedAt = now) else old
            } else {
                WatchItem(UUID.randomUUID().toString(), title, packageName, progress = progress, timeLeft = timeLeft, auto = true, updatedAt = now)
            }
            result = item
            val rest = list.filterIndexed { j, _ -> j != i }
            val merged = if (toFront || i < 0) listOf(item) + rest else list.toMutableList().also { it[i] = item }
            // Keep at most 4 auto-added titles: drop the least recently seen.
            val autos = merged.filter { it.auto }.sortedByDescending { it.updatedAt }
            val drop = autos.drop(4).map { it.id }.toSet()
            merged.filterNot { it.id in drop }
        }
        return result
    }

    /** Removes an auto-added title (e.g. finished watching). Hand-set items are left alone. */
    suspend fun removeAuto(packageName: String, title: String) = update { list ->
        list.filterNot { it.auto && it.packageName == packageName && it.title.equals(title, ignoreCase = true) }
    }

    private suspend fun update(change: (List<WatchItem>) -> List<WatchItem>) = withContext(Dispatchers.IO) {
        mutex.withLock {
            val next = change(read())
            write(next)
            _items.value = next
        }
    }

    private fun read(): List<WatchItem> {
        if (!file.exists()) return emptyList()
        val a = JSONArray(file.readText())
        return (0 until a.length()).map { i ->
            val o = a.getJSONObject(i)
            WatchItem(
                id = o.getString("id"),
                title = o.getString("title"),
                packageName = o.getString("package"),
                upTo = o.optStringOrNull("upTo"),
                timeLeft = o.optStringOrNull("timeLeft"),
                progress = if (o.has("progress")) o.getDouble("progress").toFloat() else null,
                deepLink = o.optStringOrNull("deepLink"),
                artworkPath = o.optStringOrNull("artwork"),
                auto = o.optBoolean("auto", false),
                updatedAt = o.optLong("updatedAt", 0),
            )
        }
    }

    private fun write(items: List<WatchItem>) {
        file.writeText(JSONArray(items.map { w ->
            JSONObject().put("id", w.id).put("title", w.title).put("package", w.packageName).apply {
                w.upTo?.let { put("upTo", it) }
                w.timeLeft?.let { put("timeLeft", it) }
                w.progress?.let { put("progress", it.toDouble()) }
                w.deepLink?.let { put("deepLink", it) }
                w.artworkPath?.let { put("artwork", it) }
                put("auto", w.auto)
                put("updatedAt", w.updatedAt)
            }
        }).toString())
    }
}

private fun JSONObject.optStringOrNull(key: String) = if (has(key) && !isNull(key)) getString(key) else null
