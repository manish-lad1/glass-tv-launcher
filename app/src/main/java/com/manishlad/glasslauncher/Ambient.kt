package com.manishlad.glasslauncher

import android.util.Log
import com.manishlad.glasslauncher.data.WallpaperInfo
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private const val TAG = "GlassLauncher"

/** One slideshow frame: the wallpaper (scrim + blur applied) and its place in the library. */
class Slide(val info: WallpaperInfo, val index: Int, val total: Int, val backdrop: Backdrop)

/**
 * Ambient slideshow: walks the wallpaper library, preparing the next wallpaper before
 * switching so the crossfade never waits on decoding. Holds at most two decoded wallpapers.
 */
class AmbientSlideshow(private val app: GlassApp, private val scope: CoroutineScope) {
    private val _slide = MutableStateFlow<Slide?>(null)
    val slide: StateFlow<Slide?> = _slide
    private var job: Job? = null

    /** Start at library position [index] (decoding it first). */
    fun startAt(index: Int, intervalMs: Long = 40_000) {
        start(null, intervalMs, index)
    }

    /** Home's slide is owned by Home; never freed here. */
    private var keep: Slide? = null

    /** Start from [first] (the wallpaper on screen, if already decoded) so entering Ambient is seamless. */
    fun start(first: Slide? = null, intervalMs: Long = 40_000, startIndex: Int = 0) {
        stop()
        keep = first
        job = scope.launch {
            val lib = app.wallpapers.library.value
            if (lib.isEmpty()) return@launch
            var index = first?.let { s -> lib.indexOfFirst { it.id == s.info.id } }?.takeIf { it >= 0 } ?: startIndex.coerceIn(0, lib.size - 1)
            _slide.value = first ?: prepare(lib, index)
            if (lib.size < 2) return@launch
            while (isActive) {
                val nextIndex = (index + 1) % lib.size
                val next = prepare(lib, nextIndex)
                try {
                    delay(intervalMs)
                } catch (e: kotlinx.coroutines.CancellationException) {
                    retire(next) // prepared but never shown
                    throw e
                }
                retire(_slide.value) // stays on screen through the crossfade, freed after
                _slide.value = next
                index = nextIndex
            }
        }
    }

    fun stop() {
        job?.cancel()
        job = null
        retire(_slide.value)
        _slide.value = null
    }

    private fun retire(s: Slide?) {
        if (s == null || s === keep) return
        s.backdrop.recycleLater(scope)
    }

    private suspend fun prepare(lib: List<WallpaperInfo>, index: Int): Slide = withContext(Dispatchers.Default) {
        val info = lib[index]
        val t = System.currentTimeMillis()
        val backdrop = Wallpapers.fromBitmap(app.wallpapers.decode(info), withHeavy = false)
        Log.d(TAG, "ambient: prepared '${info.name}' in ${System.currentTimeMillis() - t} ms")
        Slide(info, index, lib.size, backdrop)
    }
}
