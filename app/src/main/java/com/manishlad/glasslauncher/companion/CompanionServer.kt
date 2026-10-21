package com.manishlad.glasslauncher.companion

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.Log
import androidx.compose.ui.graphics.asAndroidBitmap
import com.manishlad.glasslauncher.AppRepository
import com.manishlad.glasslauncher.CALENDAR_COLORS
import com.manishlad.glasslauncher.GlassApp
import com.manishlad.glasslauncher.LaunchableApp
import com.manishlad.glasslauncher.data.DOCK_MAX
import com.manishlad.glasslauncher.data.DayPart
import com.manishlad.glasslauncher.data.WallpaperMode
import com.manishlad.glasslauncher.data.WatchItem
import fi.iki.elonen.NanoHTTPD
import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.net.Inet4Address
import java.net.InetAddress
import java.net.NetworkInterface

private const val TAG = "GlassLauncher"
const val COMPANION_PORT = 8080
private const val COOKIE = "glass_session"
private const val MAX_UPLOAD = 30L * 1024 * 1024

/** The TV's IPv4 addresses on the home network (for the pairing screen and the Host check). */
fun localIpv4(): List<String> = runCatching {
    NetworkInterface.getNetworkInterfaces().toList()
        .filter { it.isUp && !it.isLoopback }
        .flatMap { it.inetAddresses.toList() }
        .filterIsInstance<Inet4Address>()
        .filter { it.isSiteLocalAddress }
        .map { it.hostAddress!! }
}.getOrDefault(emptyList())

/**
 * The phone companion's web server (port 8080, home network only). Serves the single-page UI
 * from assets and a small JSON API. Calendar links arrive here in request bodies and are never logged.
 */
class CompanionServer(private val app: GlassApp) : NanoHTTPD(COMPANION_PORT) {
    private val apps = AppRepository(app)
    private var appsCache: Pair<Long, List<LaunchableApp>>? = null
    private val artCache = HashMap<String, ByteArray>()

    override fun serve(session: IHTTPSession): Response = try {
        guard(session) ?: route(session)
    } catch (e: Exception) {
        Log.w(TAG, "companion: ${session.method} ${session.uri.substringBefore('?')} failed: ${e.javaClass.simpleName}")
        json(Response.Status.INTERNAL_ERROR, JSONObject().put("error", "Something went wrong on the TV."))
    }

    /** Home network only, addressed to this TV by IP (blocks DNS rebinding), CSRF header on writes. */
    private fun guard(s: IHTTPSession): Response? {
        val remote = runCatching { InetAddress.getByName(s.remoteIpAddress) }.getOrNull()
        if (remote == null || !(remote.isSiteLocalAddress || remote.isLoopbackAddress || remote.isLinkLocalAddress)) {
            return text(Response.Status.FORBIDDEN, "Home network only.")
        }
        val host = s.headers["host"]?.substringBeforeLast(':')?.trim('[', ']')
        if (host != null && host !in localIpv4() && host != "localhost" && host != "127.0.0.1") {
            return text(Response.Status.FORBIDDEN, "Open this page using the TV's address.")
        }
        if (s.method != Method.GET && s.headers["x-glass"] != "1") {
            return text(Response.Status.FORBIDDEN, "Missing header.")
        }
        return null
    }

    private fun route(s: IHTTPSession): Response {
        val path = s.uri.trimEnd('/').ifEmpty { "/" }
        val seg = path.trim('/').split('/').filter { it.isNotEmpty() }

        // Public: the page itself and pairing.
        if (s.method == Method.GET && (path == "/" || path == "/index.html")) return page()
        if (s.method == Method.POST && path == "/api/pair") return pair(s)

        val token = s.cookies.read(COOKIE)
        if (!app.pairing.isValid(token)) return json(Response.Status.UNAUTHORIZED, JSONObject().put("error", "not paired"))

        return when {
            s.method == Method.GET && path == "/api/state" -> json(Response.Status.OK, state())
            s.method == Method.POST && path == "/api/logout" -> {
                app.pairing.revoke(token)
                json(Response.Status.OK, JSONObject()).also { it.addHeader("Set-Cookie", "$COOKIE=; Max-Age=0; Path=/; HttpOnly; SameSite=Strict") }
            }

            // Apps (for pickers)
            s.method == Method.GET && seg.size == 4 && seg[0] == "api" && seg[1] == "apps" && seg[3] == "art" -> appArt(seg[2])

            // Now watching
            s.method == Method.POST && path == "/api/watching" -> {
                val b = body(s)
                runBlocking {
                    app.watching.add(
                        WatchItem(
                            id = "", title = b.getString("title").trim(), packageName = b.getString("package"),
                            upTo = b.optString("upTo").trim().ifBlank { null }, timeLeft = b.optString("timeLeft").trim().ifBlank { null },
                            progress = if (b.has("progress") && !b.isNull("progress")) b.getDouble("progress").toFloat().coerceIn(0f, 1f) else null,
                            deepLink = b.optString("link").trim().ifBlank { null },
                        ),
                    )
                }
                ok()
            }
            s.method == Method.POST && path == "/api/watching/order" -> {
                val ids = body(s).getJSONArray("ids").let { a -> (0 until a.length()).map(a::getString) }
                runBlocking { app.watching.reorder(ids) }
                ok()
            }
            seg.size == 3 && seg[0] == "api" && seg[1] == "watching" && s.method == Method.PATCH -> {
                val b = body(s)
                runBlocking {
                    app.watching.edit(seg[2]) { w ->
                        w.copy(
                            title = if (b.has("title")) b.getString("title").trim().ifBlank { w.title } else w.title,
                            packageName = if (b.has("package")) b.getString("package") else w.packageName,
                            upTo = if (b.has("upTo")) b.optString("upTo").trim().ifBlank { null } else w.upTo,
                            timeLeft = if (b.has("timeLeft")) b.optString("timeLeft").trim().ifBlank { null } else w.timeLeft,
                            progress = if (b.has("progress")) (if (b.isNull("progress")) null else b.getDouble("progress").toFloat().coerceIn(0f, 1f)) else w.progress,
                            deepLink = if (b.has("link")) b.optString("link").trim().ifBlank { null } else w.deepLink,
                            // Once edited by hand it's no longer auto-managed.
                            auto = false,
                        )
                    }
                }
                ok()
            }
            seg.size == 3 && seg[0] == "api" && seg[1] == "watching" && s.method == Method.DELETE -> {
                runBlocking { app.watching.remove(seg[2]) }
                ok()
            }
            seg.size == 4 && seg[0] == "api" && seg[1] == "watching" && seg[3] == "artwork" && s.method == Method.PUT -> {
                val tmp = upload(s)
                try {
                    val jpeg = File.createTempFile("art", ".jpg", app.cacheDir)
                    toJpeg(tmp, jpeg, maxWidth = 1280)
                    runBlocking { app.watching.setArtwork(seg[2], jpeg) }
                    jpeg.delete()
                } finally {
                    tmp.delete()
                }
                ok()
            }
            seg.size == 4 && seg[0] == "api" && seg[1] == "watching" && seg[3] == "artwork" && s.method == Method.GET -> {
                val f = app.watching.artworkFile(seg[2]) ?: return text(Response.Status.NOT_FOUND, "")
                bytes("image/jpeg", f.readBytes())
            }

            // Dock
            s.method == Method.PUT && path == "/api/dock" -> {
                val a = body(s).getJSONArray("packages")
                runBlocking { app.dock.set((0 until a.length()).map(a::getString)) }
                ok()
            }

            // Wallpapers
            s.method == Method.POST && path == "/api/wallpapers" -> {
                val name = s.parameters["name"]?.firstOrNull()?.trim()?.ifBlank { null } ?: "Photo"
                val use = s.parameters["use"]?.firstOrNull() == "true"
                val tmp = upload(s)
                try {
                    runBlocking {
                        val info = app.wallpapers.import(tmp, name, s.parameters["credit"]?.firstOrNull()?.trim()?.ifBlank { null })
                        if (use) app.wallpapers.setMode(WallpaperMode.Fixed(info.id))
                    }
                } finally {
                    tmp.delete()
                }
                app.wallpaperChanged()
                ok()
            }
            s.method == Method.PUT && path == "/api/wallpapers/mode" -> {
                val b = body(s)
                val lib = app.wallpapers
                val mode = when (b.getString("mode")) {
                    "daily" -> WallpaperMode.Daily
                    "hours" -> WallpaperMode.EveryHours(b.optInt("hours", 6).coerceIn(1, 24))
                    "timeofday" -> {
                        val sets = b.optJSONObject("sets") ?: JSONObject()
                        WallpaperMode.TimeOfDay(DayPart.entries.associateWith { part ->
                            val a = sets.optJSONArray(part.name) ?: JSONArray()
                            (0 until a.length()).map(a::getString)
                        })
                    }
                    else -> WallpaperMode.Fixed(b.getString("id"))
                }
                runBlocking { lib.setMode(mode) }
                app.wallpaperChanged()
                ok()
            }
            seg.size == 4 && seg[0] == "api" && seg[1] == "wallpapers" && seg[3] == "thumb" && s.method == Method.GET -> {
                val info = app.wallpapers.library.value.firstOrNull { it.id == seg[2] } ?: return text(Response.Status.NOT_FOUND, "")
                bytes("image/jpeg", runBlocking { app.wallpapers.thumbnail(info) })
            }
            seg.size == 3 && seg[0] == "api" && seg[1] == "wallpapers" && s.method == Method.DELETE -> {
                runBlocking { app.wallpapers.removeById(seg[2]) }
                app.wallpaperChanged()
                ok()
            }

            // Calendars
            s.method == Method.POST && path == "/api/calendars" -> {
                val b = body(s)
                val url = b.getString("url").trim()
                if (!Regex("^(webcals?|https?)://", RegexOption.IGNORE_CASE).containsMatchIn(url)) {
                    return json(Response.Status.BAD_REQUEST, JSONObject().put("error", "That doesn't look like a calendar link."))
                }
                val used = app.calendar.state.value.sources.map { it.color }.toSet()
                val color = b.optString("color").removePrefix("#").toLongOrNull(16)?.let { 0xFF000000 or it }
                    ?: CALENDAR_COLORS.firstOrNull { it !in used } ?: CALENDAR_COLORS.first()
                runBlocking { app.calendar.add(b.getString("name").trim().ifBlank { "Calendar" }, url, color) }
                ok()
            }
            s.method == Method.POST && path == "/api/calendars/refresh" -> {
                runBlocking { app.calendar.refresh() }
                ok()
            }
            seg.size == 3 && seg[0] == "api" && seg[1] == "calendars" && s.method == Method.DELETE -> {
                runBlocking { app.calendar.removeById(seg[2]) }
                ok()
            }

            else -> text(Response.Status.NOT_FOUND, "Not found")
        }.also { it.addHeader("Cache-Control", "no-store") }
    }

    private fun pair(s: IHTTPSession): Response {
        val code = body(s).optString("code")
        val label = s.headers["user-agent"].orEmpty()
        return when (val r = app.pairing.pair(code, label)) {
            is Pairing.Result.Ok -> json(Response.Status.OK, JSONObject()).also {
                it.addHeader("Set-Cookie", "$COOKIE=${r.token}; Max-Age=31536000; Path=/; HttpOnly; SameSite=Strict")
                Log.i(TAG, "companion: phone paired")
            }
            Pairing.Result.NotOpen -> json(Response.Status.FORBIDDEN, JSONObject().put("error", "Open pairing on the TV first: hold OK on the M button."))
            Pairing.Result.Expired -> json(Response.Status.FORBIDDEN, JSONObject().put("error", "That code has expired. Press OK on the TV for a new one."))
            Pairing.Result.Wrong -> json(Response.Status.FORBIDDEN, JSONObject().put("error", "That code doesn't match the one on the TV."))
        }
    }

    private fun state(): JSONObject {
        val all = appsList()
        val labels = all.associate { it.packageName to it.label }
        val w = app.watching.items.value
        val cal = app.calendar.state.value
        val lib = app.wallpapers.library.value
        return JSONObject()
            .put("tvName", "Living room TV")
            .put("dockMax", DOCK_MAX)
            .put("apps", JSONArray(all.map { JSONObject().put("package", it.packageName).put("label", it.label) }))
            .put("dock", JSONArray(app.dock.packages.value))
            .put("watching", JSONArray(w.map { item ->
                JSONObject().put("id", item.id).put("title", item.title).put("package", item.packageName)
                    .put("app", labels[item.packageName] ?: item.packageName)
                    .put("upTo", item.upTo ?: "").put("timeLeft", item.timeLeft ?: "")
                    .put("progress", item.progress?.toDouble() ?: JSONObject.NULL)
                    .put("link", item.deepLink ?: "")
                    .put("hasArtwork", item.artworkPath != null).put("auto", item.auto)
                    .put("updatedAt", item.updatedAt)
            }))
            .put("wallpapers", JSONArray(lib.map { JSONObject().put("id", it.id).put("name", it.name).put("builtIn", it.builtIn) }))
            .put("wallpaperMode", when (val m = app.wallpapers.mode.value) {
                is WallpaperMode.Fixed -> JSONObject().put("mode", "fixed").put("id", m.id)
                WallpaperMode.Daily -> JSONObject().put("mode", "daily")
                is WallpaperMode.EveryHours -> JSONObject().put("mode", "hours").put("hours", m.hours)
                is WallpaperMode.TimeOfDay -> JSONObject().put("mode", "timeofday").put("sets", JSONObject().apply {
                    for ((part, ids) in m.sets) put(part.name, JSONArray(ids))
                })
            })
            .put("homeWallpaper", app.wallpapers.pickForHome().id)
            // Calendar links are deliberately not sent back to the phone.
            .put("calendars", JSONArray(cal.sources.map { src ->
                val st = cal.status[src.id]
                JSONObject().put("id", src.id).put("name", src.name).put("color", "#%06x".format(src.color and 0xFFFFFF))
                    .put("lastSync", st?.lastSync?.toEpochMilli() ?: JSONObject.NULL)
                    .put("error", st?.error ?: JSONObject.NULL)
            }))
            .put("colors", JSONArray(CALENDAR_COLORS.map { "#%06x".format(it and 0xFFFFFF) }))
            .put("autoTracking", app.mediaTrackingEnabled())
            // Which apps have shared titles while playing (built up as you watch), newest first.
            .put("mediaApps", JSONArray(app.mediaApps.apps.value.entries.sortedByDescending { it.value.lastSeen }.map { (pkg, i) ->
                JSONObject().put("package", pkg).put("app", labels[pkg] ?: pkg).put("shares", i.sharesTitles).put("lastSeen", i.lastSeen)
            }))
    }

    private fun appsList(): List<LaunchableApp> {
        val now = System.currentTimeMillis()
        appsCache?.let { (t, list) -> if (now - t < 60_000) return list }
        return apps.load().also { appsCache = now to it }
    }

    private fun appArt(pkg: String): Response {
        val bytes = synchronized(artCache) {
            artCache.getOrPut(pkg) {
                val a = appsList().firstOrNull { it.packageName == pkg } ?: return text(Response.Status.NOT_FOUND, "")
                val bmp = (a.banner ?: a.icon).asAndroidBitmap()
                ByteArrayOutputStream().also { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }.toByteArray()
            }
        }
        return bytes("image/png", bytes).also { it.addHeader("Cache-Control", "max-age=3600") }
    }

    private fun page(): Response {
        val html = app.assets.open("companion/index.html").use { it.readBytes() }
        return bytes("text/html; charset=utf-8", html).also {
            it.addHeader("Cache-Control", "no-store")
            it.addHeader("X-Frame-Options", "DENY")
            it.addHeader("Content-Security-Policy", "default-src 'self'; img-src 'self' blob: data:; style-src 'self' 'unsafe-inline'; script-src 'self' 'unsafe-inline'")
        }
    }

    // ---- helpers ----

    private fun contentLength(s: IHTTPSession) = s.headers["content-length"]?.toLongOrNull() ?: 0L

    private fun body(s: IHTTPSession): JSONObject {
        val len = contentLength(s)
        require(len in 0..1_000_000) { "body too large" }
        val buf = ByteArray(len.toInt())
        var read = 0
        while (read < len) {
            val n = s.inputStream.read(buf, read, buf.size - read)
            if (n < 0) break
            read += n
        }
        return if (read == 0) JSONObject() else JSONObject(String(buf, 0, read))
    }

    /** Streams a raw image body to a temp file. */
    private fun upload(s: IHTTPSession): File {
        val len = contentLength(s)
        require(len in 1..MAX_UPLOAD) { "upload size" }
        val tmp = File.createTempFile("upload", ".img", app.cacheDir)
        tmp.outputStream().use { out ->
            val buf = ByteArray(64 * 1024)
            var left = len
            while (left > 0) {
                val n = s.inputStream.read(buf, 0, minOf(buf.size.toLong(), left).toInt())
                if (n < 0) break
                out.write(buf, 0, n)
                left -= n
            }
        }
        return tmp
    }

    private fun toJpeg(src: File, dest: File, maxWidth: Int) {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(src.path, bounds)
        require(bounds.outWidth > 0) { "not an image" }
        var sample = 1
        while (bounds.outWidth / (sample * 2) >= maxWidth) sample *= 2
        var bmp = BitmapFactory.decodeFile(src.path, BitmapFactory.Options().apply { inSampleSize = sample })!!
        if (bmp.width > maxWidth) {
            val scaled = Bitmap.createScaledBitmap(bmp, maxWidth, bmp.height * maxWidth / bmp.width, true)
            bmp.recycle()
            bmp = scaled
        }
        dest.outputStream().use { bmp.compress(Bitmap.CompressFormat.JPEG, 85, it) }
        bmp.recycle()
    }

    private fun ok() = json(Response.Status.OK, JSONObject().put("ok", true))

    private fun json(status: Response.Status, o: JSONObject) =
        newFixedLengthResponse(status, "application/json", o.toString())

    private fun text(status: Response.Status, t: String) = newFixedLengthResponse(status, "text/plain", t)

    private fun bytes(mime: String, b: ByteArray) =
        newFixedLengthResponse(Response.Status.OK, mime, ByteArrayInputStream(b), b.size.toLong())
}
