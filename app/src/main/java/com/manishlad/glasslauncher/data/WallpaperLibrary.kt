package com.manishlad.glasslauncher.data

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Matrix
import android.graphics.Paint
import android.media.ExifInterface
import android.util.Log
import com.manishlad.glasslauncher.Palette
import com.manishlad.glasslauncher.Scenes
import com.manishlad.glasslauncher.Wallpapers
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.time.LocalDateTime
import java.time.ZoneId
import java.util.UUID

private const val TAG = "GlassLauncher"
const val WALL_W = 1920
const val WALL_H = 1080

data class WallpaperInfo(val id: String, val name: String, val credit: String?, val builtIn: Boolean)

/** How Home picks its wallpaper. Home only changes quietly, when the TV wakes. */
sealed class WallpaperMode {
    data class Fixed(val id: String) : WallpaperMode()
    data object Daily : WallpaperMode()
    data class EveryHours(val hours: Int) : WallpaperMode()
    /** Wallpaper ids per part of the day; an empty set uses the whole library. */
    data class TimeOfDay(val sets: Map<DayPart, List<String>>) : WallpaperMode()
}

enum class DayPart { Morning, Afternoon, Evening, Night;
    companion object {
        fun of(hour: Int) = when (hour) {
            in 5..11 -> Morning
            in 12..16 -> Afternoon
            in 17..20 -> Evening
            else -> Night
        }
    }
}

/**
 * Wallpapers: built-in procedural scenes plus Manish's own photos, stored at 1920×1080 JPEG
 * (~85%) in app storage. Decoding at 1080p keeps each one ~8 MB in memory instead of ~33 MB at 4K.
 */
class WallpaperLibrary(context: Context) {
    private val dir = File(context.filesDir, "wallpapers").apply { mkdirs() }
    private val indexFile = File(dir, "index.json")
    private val modeFile = File(dir, "mode.json")
    private val mutex = Mutex()

    private val builtIns = listOf(
        WallpaperInfo("builtin:dusk_ridge", "Dusk Ridge", null, true),
        WallpaperInfo("builtin:blue_hour", "Blue Hour", null, true),
        WallpaperInfo("builtin:ember", "Ember Peaks", null, true),
    )
    private val palettes: Map<String, Palette> = mapOf(
        "builtin:dusk_ridge" to Scenes.DUSK_RIDGE,
        "builtin:blue_hour" to Scenes.BLUE_HOUR,
        "builtin:ember" to Scenes.EMBER,
    )

    private val _library = MutableStateFlow(builtIns)
    /** Your photos first (newest first), then the built-in scenes. */
    val library: StateFlow<List<WallpaperInfo>> = _library
    private val _mode = MutableStateFlow<WallpaperMode>(WallpaperMode.Fixed(builtIns.first().id))
    val mode: StateFlow<WallpaperMode> = _mode

    suspend fun load() = withContext(Dispatchers.IO) {
        mutex.withLock {
            _library.value = readIndex() + builtIns
            _mode.value = readMode()
        }
    }

    /** The wallpaper Home should show at [now], per the current mode. */
    fun pickForHome(now: LocalDateTime = LocalDateTime.now()): WallpaperInfo {
        val all = _library.value
        fun rotate(list: List<WallpaperInfo>, slot: Long) = list[Math.floorMod(slot, list.size.toLong()).toInt()]
        val epochHour = now.atZone(ZoneId.systemDefault()).toEpochSecond() / 3600
        return when (val m = _mode.value) {
            is WallpaperMode.Fixed -> all.firstOrNull { it.id == m.id } ?: all.first()
            WallpaperMode.Daily -> rotate(all, now.toLocalDate().toEpochDay())
            is WallpaperMode.EveryHours -> rotate(all, epochHour / m.hours.coerceAtLeast(1))
            is WallpaperMode.TimeOfDay -> {
                val ids = m.sets[DayPart.of(now.hour)].orEmpty()
                val set = all.filter { it.id in ids }.ifEmpty { all }
                rotate(set, now.toLocalDate().toEpochDay())
            }
        }
    }

    /** Decodes a wallpaper at exactly 1920×1080 (mutable, ready for [Wallpapers.fromBitmap]). */
    fun decode(info: WallpaperInfo): Bitmap {
        palettes[info.id]?.let { return Wallpapers.scene(it, WALL_W, WALL_H) }
        val file = File(dir, "${info.id}.jpg")
        val opts = BitmapFactory.Options().apply { inMutable = true }
        return BitmapFactory.decodeFile(file.path, opts)?.let { coverTo1080(it) }
            ?: Wallpapers.scene(Scenes.DUSK_RIDGE, WALL_W, WALL_H).also { Log.w(TAG, "wallpaper '${info.name}' unreadable") }
    }

    /** Import a photo: orient (EXIF), scale/crop to 1920×1080, save as JPEG 85%. */
    suspend fun import(source: File, name: String, credit: String?): WallpaperInfo = withContext(Dispatchers.IO) {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(source.path, bounds)
        require(bounds.outWidth > 0) { "not an image" }
        var sample = 1
        while (bounds.outWidth / (sample * 2) >= WALL_W && bounds.outHeight / (sample * 2) >= WALL_H) sample *= 2
        var bmp = BitmapFactory.decodeFile(source.path, BitmapFactory.Options().apply { inSampleSize = sample })
            ?: error("decode failed")
        val rotation = when (runCatching { ExifInterface(source.path).getAttributeInt(ExifInterface.TAG_ORIENTATION, 1) }.getOrDefault(1)) {
            ExifInterface.ORIENTATION_ROTATE_90 -> 90f
            ExifInterface.ORIENTATION_ROTATE_180 -> 180f
            ExifInterface.ORIENTATION_ROTATE_270 -> 270f
            else -> 0f
        }
        if (rotation != 0f) {
            val rotated = Bitmap.createBitmap(bmp, 0, 0, bmp.width, bmp.height, Matrix().apply { postRotate(rotation) }, true)
            if (rotated !== bmp) bmp.recycle()
            bmp = rotated
        }
        val out = coverTo1080(bmp)
        val info = WallpaperInfo(UUID.randomUUID().toString(), name, credit, false)
        File(dir, "${info.id}.jpg").outputStream().use { out.compress(Bitmap.CompressFormat.JPEG, 85, it) }
        out.recycle()
        mutex.withLock {
            val photos = listOf(info) + readIndex()
            writeIndex(photos)
            _library.value = photos + builtIns
        }
        Log.i(TAG, "wallpaper imported: '$name'")
        info
    }

    suspend fun remove(name: String) = removeWhere { it.name.equals(name, ignoreCase = true) }

    suspend fun removeById(id: String) = removeWhere { it.id == id }

    /** Small JPEG preview for the phone (cached for photos, rendered for built-ins). */
    suspend fun thumbnail(info: WallpaperInfo): ByteArray = withContext(Dispatchers.IO) {
        val cached = File(dir, "${info.id}.thumb.jpg")
        if (!info.builtIn && cached.exists()) return@withContext cached.readBytes()
        val bmp = palettes[info.id]?.let { Wallpapers.scene(it, 384, 216) }
            ?: BitmapFactory.decodeFile(File(dir, "${info.id}.jpg").path, BitmapFactory.Options().apply { inSampleSize = 4 })
            ?: return@withContext ByteArray(0)
        val out = java.io.ByteArrayOutputStream()
        bmp.compress(Bitmap.CompressFormat.JPEG, 80, out)
        bmp.recycle()
        out.toByteArray().also { if (!info.builtIn) cached.writeBytes(it) }
    }

    private suspend fun removeWhere(match: (WallpaperInfo) -> Boolean) = withContext(Dispatchers.IO) {
        mutex.withLock {
            val (gone, kept) = readIndex().partition(match)
            gone.forEach { File(dir, "${it.id}.jpg").delete(); File(dir, "${it.id}.thumb.jpg").delete() }
            writeIndex(kept)
            _library.value = kept + builtIns
        }
    }

    suspend fun setMode(mode: WallpaperMode) = withContext(Dispatchers.IO) {
        mutex.withLock {
            writeMode(mode)
            _mode.value = mode
        }
    }

    fun findByName(name: String) = _library.value.firstOrNull { it.name.equals(name, ignoreCase = true) }

    /** Scale to cover 1920×1080 and centre-crop. Recycles [src] if it made a new bitmap. */
    private fun coverTo1080(src: Bitmap): Bitmap {
        if (src.width == WALL_W && src.height == WALL_H && src.isMutable) return src
        val out = Bitmap.createBitmap(WALL_W, WALL_H, Bitmap.Config.ARGB_8888)
        val scale = maxOf(WALL_W / src.width.toFloat(), WALL_H / src.height.toFloat())
        val m = Matrix().apply {
            postScale(scale, scale)
            postTranslate((WALL_W - src.width * scale) / 2f, (WALL_H - src.height * scale) / 2f)
        }
        Canvas(out).drawBitmap(src, m, Paint(Paint.FILTER_BITMAP_FLAG))
        src.recycle()
        return out
    }

    private fun readIndex(): List<WallpaperInfo> {
        if (!indexFile.exists()) return emptyList()
        val a = JSONArray(indexFile.readText())
        return (0 until a.length()).map {
            val o = a.getJSONObject(it)
            WallpaperInfo(o.getString("id"), o.getString("name"), o.optString("credit").ifBlank { null }, false)
        }
    }

    private fun writeIndex(list: List<WallpaperInfo>) {
        indexFile.writeText(JSONArray(list.map {
            JSONObject().put("id", it.id).put("name", it.name).put("credit", it.credit ?: "")
        }).toString())
    }

    private fun readMode(): WallpaperMode {
        if (!modeFile.exists()) return WallpaperMode.Fixed(builtIns.first().id)
        val o = JSONObject(modeFile.readText())
        return when (o.getString("mode")) {
            "daily" -> WallpaperMode.Daily
            "hours" -> WallpaperMode.EveryHours(o.optInt("hours", 6))
            "timeofday" -> {
                val sets = o.optJSONObject("sets")
                WallpaperMode.TimeOfDay(DayPart.entries.associateWith { part ->
                    val a = sets?.optJSONArray(part.name) ?: JSONArray()
                    (0 until a.length()).map { a.getString(it) }
                })
            }
            else -> WallpaperMode.Fixed(o.optString("id", builtIns.first().id))
        }
    }

    private fun writeMode(mode: WallpaperMode) {
        val o = when (mode) {
            is WallpaperMode.Fixed -> JSONObject().put("mode", "fixed").put("id", mode.id)
            WallpaperMode.Daily -> JSONObject().put("mode", "daily")
            is WallpaperMode.EveryHours -> JSONObject().put("mode", "hours").put("hours", mode.hours)
            is WallpaperMode.TimeOfDay -> JSONObject().put("mode", "timeofday").put("sets", JSONObject().apply {
                for ((part, ids) in mode.sets) put(part.name, JSONArray(ids))
            })
        }
        modeFile.writeText(o.toString())
    }
}
