package com.manishlad.glasslauncher

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.ColorMatrix
import android.graphics.ColorMatrixColorFilter
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RadialGradient
import android.graphics.Shader
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asAndroidBitmap
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import androidx.compose.ui.graphics.asImageBitmap

/**
 * The screen background plus its pre-blurred copies. There is no live blur on this TV,
 * so glass surfaces draw a crop of [blurred] (or [heavy], for the All apps overlay)
 * at their own screen position.
 */
class Backdrop(
    val sharp: ImageBitmap,
    val blurred: ImageBitmap,
    val heavy: ImageBitmap?,
) {
    /**
     * Free the bitmaps (and their GPU textures) once nothing draws them any more. Waits
     * [afterMs] so a crossfade or the frame after a screen change can finish first.
     */
    fun recycleLater(scope: CoroutineScope, afterMs: Long = 5_000) {
        scope.launch {
            delay(afterMs)
            listOfNotNull(sharp, blurred, heavy).forEach { it.asAndroidBitmap().recycle() }
        }
    }
}

/** Colours for a procedural mountain scene. */
class Palette(
    val sky: IntArray,
    val glow: IntArray,
    val glowAt: Pair<Float, Float>,
    val ridges: IntArray,
)

object Scenes {
    val DUSK_RIDGE = Palette(
        sky = intArrayOf(0xFF0B1026.toInt(), 0xFF1C2856.toInt(), 0xFF5A447A.toInt(), 0xFFD48A6C.toInt(), 0xFFF0B07A.toInt()),
        glow = intArrayOf(0xE6FFD9A0.toInt(), 0x73F3A77A, 0x00F3A77A),
        glowAt = 1260f to 690f,
        ridges = intArrayOf(0xD94A3F6E.toInt(), 0xFF2A2350.toInt(), 0xFF14102B.toInt()),
    )
    val BLUE_HOUR = Palette(
        sky = intArrayOf(0xFF050B1C.toInt(), 0xFF0E2A4F.toInt(), 0xFF1F5A7A.toInt(), 0xFF5FA3B5.toInt(), 0xFFA8D5D0.toInt()),
        glow = intArrayOf(0xB3D8F3FF.toInt(), 0x4D7FD1FF, 0x007FD1FF),
        glowAt = 560f to 640f,
        ridges = intArrayOf(0xD9254A66.toInt(), 0xFF153049.toInt(), 0xFF081A2B.toInt()),
    )
    val EMBER = Palette(
        sky = intArrayOf(0xFF140A1C.toInt(), 0xFF3A1430.toInt(), 0xFF7A2E3E.toInt(), 0xFFE0704A.toInt(), 0xFFFFC27A.toInt()),
        glow = intArrayOf(0xE6FFE0A8.toInt(), 0x80FF9A5A.toInt(), 0x00FF9A5A),
        glowAt = 980f to 720f,
        ridges = intArrayOf(0xD95A2440.toInt(), 0xFF35152C.toInt(), 0xFF1A0B17.toInt()),
    )
}

object Wallpapers {

    /** A procedural layered-mountain scene (the built-in wallpapers). No scrim. */
    fun scene(palette: Palette, width: Int, height: Int): Bitmap {
        val bmp = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        val c = Canvas(bmp)
        c.scale(width / 1920f, height / 1080f)
        val p = Paint(Paint.ANTI_ALIAS_FLAG)

        p.shader = LinearGradient(
            0f, 0f, 0f, 1080f,
            palette.sky,
            floatArrayOf(0f, .42f, .66f, .84f, 1f), Shader.TileMode.CLAMP,
        )
        c.drawRect(0f, 0f, 1920f, 1080f, p)
        p.shader = RadialGradient(
            palette.glowAt.first, palette.glowAt.second, 420f,
            palette.glow,
            floatArrayOf(0f, .25f, 1f), Shader.TileMode.CLAMP,
        )
        c.drawRect(0f, 0f, 1920f, 1080f, p)
        p.shader = null

        val stars = listOf(
            floatArrayOf(210f, 120f, 1.6f, .8f), floatArrayOf(480f, 210f, 1.2f, .6f), floatArrayOf(760f, 90f, 1.8f, .7f),
            floatArrayOf(1010f, 170f, 1.1f, .5f), floatArrayOf(1380f, 70f, 1.5f, .8f), floatArrayOf(1620f, 190f, 1.3f, .6f),
            floatArrayOf(1830f, 110f, 1.7f, .7f), floatArrayOf(340f, 300f, 1f, .4f), floatArrayOf(1500f, 300f, 1f, .4f),
        )
        for ((x, y, r, a) in stars) {
            p.color = android.graphics.Color.argb((a * 255).toInt(), 255, 255, 255)
            c.drawCircle(x, y, r, p)
        }

        fun ridge(color: Int, bottom: Float, vararg pts: Float) {
            val path = Path().apply {
                moveTo(0f, pts[0])
                var i = 1
                while (i < pts.size) { lineTo(pts[i], pts[i + 1]); i += 2 }
                lineTo(1920f, bottom); lineTo(0f, bottom); close()
            }
            p.color = color
            c.drawPath(path, p)
        }
        ridge(palette.ridges[0], 860f, 700f, 180f, 612f, 320f, 662f, 520f, 540f, 700f, 640f, 880f, 580f, 1060f, 650f, 1240f, 560f, 1420f, 640f, 1600f, 590f, 1780f, 650f, 1920f, 610f)
        ridge(palette.ridges[1], 900f, 770f, 240f, 668f, 420f, 742f, 640f, 642f, 860f, 762f, 1080f, 692f, 1300f, 772f, 1520f, 682f, 1740f, 762f, 1920f, 702f)
        ridge(palette.ridges[2], 1080f, 890f, 300f, 802f, 560f, 862f, 820f, 782f, 1100f, 872f, 1400f, 802f, 1700f, 882f, 1920f, 832f)

        return bmp
    }

    /**
     * Turns a 1080p wallpaper (mutable, consumed) into a backdrop: legibility scrim drawn in
     * (top ~30% and bottom ~45% darkened), then the pre-blurred copies glass draws from.
     */
    fun fromBitmap(bmp: Bitmap, withHeavy: Boolean = true): Backdrop {
        val c = Canvas(bmp)
        c.scale(bmp.width / 1920f, bmp.height / 1080f)
        c.drawRect(0f, 0f, 1920f, 1080f, Paint().apply {
            shader = LinearGradient(
                0f, 0f, 0f, 1080f,
                intArrayOf(0x73060614, 0x00060614, 0x00060614, 0x99060614.toInt()),
                floatArrayOf(0f, .28f, .5f, 1f), Shader.TileMode.CLAMP,
            )
        })
        // CSS blur(36px) ≈ sigma 4.5 at 1/8 scale; blur(60px) ≈ sigma 7.5.
        val small = downscale8(bmp, saturation = 1.7f)
        val blurred = small.copy(Bitmap.Config.ARGB_8888, true).also { boxBlur(it, radius = 4) }
        val heavy = if (withHeavy) small.also { boxBlur(it, radius = 7) }.also {
            // All apps overlay tint rgba(10,10,32,.42), baked into the heavy copy.
            Canvas(it).drawColor(0x6B0A0A20)
        } else null
        return Backdrop(bmp.asImageBitmap(), blurred.asImageBitmap(), heavy?.asImageBitmap())
    }

    /** Three halving steps so small bright details average out instead of aliasing. */
    private fun downscale8(src: Bitmap, saturation: Float): Bitmap {
        var b = src
        repeat(3) { step ->
            val next = Bitmap.createBitmap(maxOf(1, b.width / 2), maxOf(1, b.height / 2), Bitmap.Config.ARGB_8888)
            val paint = Paint(Paint.FILTER_BITMAP_FLAG)
            if (step == 2) paint.colorFilter = ColorMatrixColorFilter(ColorMatrix().apply { setSaturation(saturation) })
            Canvas(next).drawBitmap(b, null, android.graphics.Rect(0, 0, next.width, next.height), paint)
            if (b !== src) b.recycle()
            b = next
        }
        return b
    }

    /** Three box passes ≈ gaussian. Runs on a ~240×135 bitmap, so it's cheap. */
    private fun boxBlur(bmp: Bitmap, radius: Int) {
        val w = bmp.width
        val h = bmp.height
        val px = IntArray(w * h)
        val tmp = IntArray(w * h)
        bmp.getPixels(px, 0, w, 0, 0, w, h)
        repeat(3) {
            pass(px, tmp, w, h, radius, horizontal = true)
            pass(tmp, px, w, h, radius, horizontal = false)
        }
        bmp.setPixels(px, 0, w, 0, 0, w, h)
    }

    private fun pass(src: IntArray, dst: IntArray, w: Int, h: Int, r: Int, horizontal: Boolean) {
        val lines = if (horizontal) h else w
        val len = if (horizontal) w else h
        val div = 2 * r + 1
        for (line in 0 until lines) {
            fun idx(i: Int): Int {
                val k = i.coerceIn(0, len - 1)
                return if (horizontal) line * w + k else k * w + line
            }
            var a = 0; var rr = 0; var g = 0; var b = 0
            for (i in -r..r) {
                val c = src[idx(i)]
                a += c ushr 24; rr += (c shr 16) and 0xFF; g += (c shr 8) and 0xFF; b += c and 0xFF
            }
            for (i in 0 until len) {
                dst[idx(i)] = ((a / div) shl 24) or ((rr / div) shl 16) or ((g / div) shl 8) or (b / div)
                val out = src[idx(i - r)]
                val inn = src[idx(i + r + 1)]
                a += (inn ushr 24) - (out ushr 24)
                rr += ((inn shr 16) and 0xFF) - ((out shr 16) and 0xFF)
                g += ((inn shr 8) and 0xFF) - ((out shr 8) and 0xFF)
                b += (inn and 0xFF) - (out and 0xFF)
            }
        }
    }
}
