package com.manishlad.glasslauncher

import android.content.ComponentName
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.Request
import android.media.MediaMetadata
import android.media.session.MediaController
import android.media.session.MediaSessionManager
import android.media.session.PlaybackState
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.service.notification.NotificationListenerService
import android.util.Log
import kotlinx.coroutines.launch
import java.io.File

private const val TAG = "GlassLauncher"

/** Not shows: music, live TV, ourselves. */
private val IGNORED = setOf(
    "com.manishlad.glasslauncher",
    "com.google.android.youtube.tvmusic",
    "com.mediatek.wwtv.tvcenter",
    "com.google.android.katniss",
)

/** Shorter than this (when the app reports a length) isn't a show or film: trailers, previews, Shorts, ads. */
private const val MIN_DURATION_MS = 5 * 60_000L

/**
 * Automatic Now watching. Android lets a notification listener read other apps' media sessions:
 * the title, how far in, and the length, for apps that publish them (JioHotstar does; Netflix
 * sends no title). Playing titles are added to the front of the list and kept up to date;
 * finishing one (95%+) removes it. Notifications themselves are ignored.
 *
 * Needs a one-time grant (see README): cmd notification allow_listener <this component>.
 */
class MediaWatcher : NotificationListenerService() {
    private val handler = Handler(Looper.getMainLooper())
    private val controllers = HashMap<MediaSession2Key, MediaController>()
    private val callbacks = HashMap<MediaSession2Key, MediaController.Callback>()
    /** Titles already moved to the front during their current playback. */
    private val fronted = HashSet<String>()
    private val lastWrite = HashMap<String, Pair<Long, Float?>>()
    private var sessions: MediaSessionManager? = null

    private data class MediaSession2Key(val pkg: String, val token: Any)

    private val sessionsChanged = MediaSessionManager.OnActiveSessionsChangedListener { list -> track(list.orEmpty()) }

    private val tick = object : Runnable {
        override fun run() {
            controllers.values.forEach { noteSharing(it); consider(it) }
            handler.postDelayed(this, 30_000)
        }
    }

    override fun onListenerConnected() {
        super.onListenerConnected()
        val msm = getSystemService(MediaSessionManager::class.java)
        sessions = msm
        val me = ComponentName(this, MediaWatcher::class.java)
        msm.addOnActiveSessionsChangedListener(sessionsChanged, me, handler)
        track(msm.getActiveSessions(me))
        handler.post(tick)
        Log.i(TAG, "media watcher connected")
    }

    override fun onListenerDisconnected() {
        sessions?.removeOnActiveSessionsChangedListener(sessionsChanged)
        handler.removeCallbacks(tick)
        track(emptyList())
        super.onListenerDisconnected()
    }

    private fun track(list: List<MediaController>) {
        Log.d(TAG, "watch: active sessions = ${list.map { it.packageName }}")
        val current = list.filter { it.packageName !in IGNORED }.associateBy { MediaSession2Key(it.packageName, it.sessionToken) }
        for ((k, c) in controllers.toMap()) if (k !in current) {
            callbacks.remove(k)?.let(c::unregisterCallback)
            controllers.remove(k)
        }
        for ((k, c) in current) if (k !in controllers) {
            val cb = object : MediaController.Callback() {
                override fun onPlaybackStateChanged(state: PlaybackState?) = consider(c)
                override fun onMetadataChanged(metadata: MediaMetadata?) = consider(c)
            }
            c.registerCallback(cb, handler)
            controllers[k] = c
            callbacks[k] = cb
            consider(c)
        }
    }

    private val lastSkip = HashMap<String, String>()

    /** When each app's current playback started (elapsed realtime), for titles with no length. */
    private val playingSince = HashMap<String, Long>()

    /** Logs why a session was ignored (package and reason only, never titles), once per change. */
    private fun skip(c: MediaController, why: String) {
        if (lastSkip.put(c.packageName, why) != why) Log.d(TAG, "watch: ${c.packageName} skipped: $why")
    }

    /** First non-blank title-like field: some apps (JioHotstar) send TITLE as "" and put the name elsewhere. */
    private fun titleOf(meta: MediaMetadata?): String? = meta?.let {
        sequenceOf(
            it.getString(MediaMetadata.METADATA_KEY_DISPLAY_TITLE),
            it.getString(MediaMetadata.METADATA_KEY_TITLE),
            it.description?.title?.toString(),
            it.getString(MediaMetadata.METADATA_KEY_ALBUM),
            it.getString(MediaMetadata.METADATA_KEY_ARTIST),
        ).map { t -> t?.trim() }.firstOrNull { t -> !t.isNullOrEmpty() }
    }

    /**
     * Per-app record for the phone page. Runs on the 30 s tick, so an app that sends its title a
     * moment after playback starts isn't wrongly marked as not sharing.
     */
    private fun noteSharing(c: MediaController) {
        if (c.playbackState?.state != PlaybackState.STATE_PLAYING) return
        val record = glassApp.mediaApps
        if (titleOf(c.metadata) != null) record.markShares(c.packageName) else record.markNoTitle(c.packageName)
    }

    private fun consider(c: MediaController) {
        val meta = c.metadata ?: return skip(c, "no metadata yet")
        val title = titleOf(meta) ?: return skip(c, "no title (keys=${meta.keySet().joinToString()})")
        glassApp.mediaApps.markShares(c.packageName)
        val state = c.playbackState ?: return skip(c, "no playback state")
        val playing = state.state == PlaybackState.STATE_PLAYING
        if (!playing && state.state != PlaybackState.STATE_PAUSED) {
            playingSince.remove(c.packageName)
            return skip(c, "state ${state.state}")
        }

        val duration = meta.getLong(MediaMetadata.METADATA_KEY_DURATION).takeIf { it > 0 }
        if (duration != null && duration < MIN_DURATION_MS) return skip(c, "short (${duration / 1000}s)")
        var position = state.position
        if (playing && state.lastPositionUpdateTime > 0) {
            position += ((SystemClock.elapsedRealtime() - state.lastPositionUpdateTime) * state.playbackSpeed).toLong()
        }
        val progress = duration?.let { (position.toFloat() / it).coerceIn(0f, 1f) }
        val key = "${c.packageName}|${title.lowercase()}"
        val app = glassApp

        if (progress != null && progress >= 0.95f) {
            if (fronted.contains(key) || lastWrite.containsKey(key)) Log.i(TAG, "media watcher: finished in ${c.packageName}, removed from Now watching")
            app.scope.launch { app.watching.removeAuto(c.packageName, title) }
            fronted.remove(key)
            return
        }

        // Without a known length (often a live stream, whose "position" is just a spot in a short
        // rewind buffer), count real time spent playing instead: 5 min skips trailers and previews.
        if (playing) playingSince.putIfAbsent(c.packageName, SystemClock.elapsedRealtime())
        val playedFor = playingSince[c.packageName]?.let { SystemClock.elapsedRealtime() - it } ?: 0L
        if (duration == null && position < 5 * 60_000 && playedFor < 5 * 60_000) return skip(c, "no length, played under 5 min")

        val toFront = playing && fronted.add(key)
        if (!playing) fronted.remove(key)
        val now = System.currentTimeMillis()
        val (lastAt, lastProgress) = lastWrite[key] ?: (0L to null)
        val changed = toFront || lastProgress == null || progress == null || kotlin.math.abs(progress - lastProgress) >= 0.01f
        if (!changed && now - lastAt < 60_000) return
        lastWrite[key] = now to progress

        val timeLeft = duration?.let { leftLabel(it - position) }
        // Artwork comes either as a bitmap in the metadata or as a link (Media3 apps usually send a link).
        val art = meta.getBitmap(MediaMetadata.METADATA_KEY_ART)
            ?: meta.getBitmap(MediaMetadata.METADATA_KEY_ALBUM_ART)
            ?: meta.getBitmap(MediaMetadata.METADATA_KEY_DISPLAY_ICON)
        val artUri = sequenceOf(
            meta.getString(MediaMetadata.METADATA_KEY_ART_URI),
            meta.getString(MediaMetadata.METADATA_KEY_ALBUM_ART_URI),
            meta.getString(MediaMetadata.METADATA_KEY_DISPLAY_ICON_URI),
            meta.description?.iconUri?.toString(),
        ).firstOrNull { !it.isNullOrBlank() }
        if (loggedKeys.add(c.packageName)) {
            Log.d(TAG, "watch: ${c.packageName} metadata keys=${meta.keySet().joinToString()} " +
                "artBitmap=${art?.let { "${it.width}x${it.height}" } ?: "none"} artLink=${artUri?.substringBefore(':') ?: "none"}")
        }
        app.scope.launch {
            val item = app.watching.upsertAuto(c.packageName, title, progress, timeLeft, toFront) ?: return@launch
            if (item.artworkPath == null) {
                // Prefer the link when the bitmap is just a small icon.
                val picture = if (artUri != null && (art == null || art.width < 640)) fetchArt(artUri) ?: art else art
                if (picture != null) saveArt(item.id, picture)
            }
            if (toFront) Log.i(TAG, "media watcher: now watching in ${c.packageName}")
        }
    }

    /** Package names whose metadata layout has been logged this session. */
    private val loggedKeys = HashSet<String>()

    /** Downloads (https) or opens (content/file/android.resource) an artwork link, scaled to ≤1280 px wide. */
    private suspend fun fetchArt(uri: String): Bitmap? = withContext(Dispatchers.IO) {
        runCatching {
            val bytes = when (Uri.parse(uri).scheme?.lowercase()) {
                "https", "http" -> glassApp.http.newCall(Request.Builder().url(uri).build()).execute().use { r ->
                    if (!r.isSuccessful) null else r.body?.bytes()
                }
                "content", "file", "android.resource" -> contentResolver.openInputStream(Uri.parse(uri))?.use { it.readBytes() }
                else -> null
            } ?: return@runCatching null
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
            var sample = 1
            while (bounds.outWidth / (sample * 2) >= 1280) sample *= 2
            BitmapFactory.decodeByteArray(bytes, 0, bytes.size, BitmapFactory.Options().apply { inSampleSize = sample })
        }.onFailure { Log.d(TAG, "watch: artwork link failed: ${it.javaClass.simpleName}") }.getOrNull()
    }

    private suspend fun saveArt(id: String, art: Bitmap) {
        val tmp = File.createTempFile("art", ".jpg", cacheDir)
        val scaled = if (art.width > 1280) Bitmap.createScaledBitmap(art, 1280, art.height * 1280 / art.width, true) else art
        tmp.outputStream().use { scaled.compress(Bitmap.CompressFormat.JPEG, 85, it) }
        glassApp.watching.setArtwork(id, tmp)
        tmp.delete()
    }

    private fun leftLabel(ms: Long): String {
        val mins = (ms / 60_000).coerceAtLeast(1)
        return if (mins < 60) "$mins min left" else "${mins / 60}h ${mins % 60}m left"
    }
}
