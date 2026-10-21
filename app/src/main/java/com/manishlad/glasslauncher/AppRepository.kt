package com.manishlad.glasslauncher

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ResolveInfo
import android.graphics.Bitmap
import android.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.core.graphics.drawable.toBitmap

data class LaunchableApp(
    val packageName: String,
    val label: String,
    val icon: ImageBitmap,
    /** The app's 16:9 TV banner, used to fill tiles; null if the app has none. */
    val banner: ImageBitmap?,
    /** Average colour of the icon (ARGB), the tile background when there's no banner. */
    val iconColor: Int,
)

/** Dock contents until dock editing (phase 6) makes this user-editable. Max 8 slots. */
val DEFAULT_DOCK = listOf(
    "com.netflix.ninja",
    "com.amazon.amazonvideo.livingroom",
    "com.google.android.youtube.tv",
    "in.startv.hotstar",
    "com.sonyliv",
    "com.apple.atve.androidtv.appletv",
    "com.mediatek.wwtv.tvcenter", // Live TV & inputs
)

/** All apps grid order from the design; anything else follows alphabetically. */
private val PREFERRED_ORDER = listOf(
    "com.netflix.ninja",
    "com.amazon.amazonvideo.livingroom",
    "in.startv.hotstar",
    "com.google.android.youtube.tv",
    "com.sonyliv",
    "com.apple.atve.androidtv.appletv",
    "com.graymatrix.did",
    "com.fancode.tv",
    "com.formulaone.production",
    "com.google.android.youtube.tvmusic",
    "com.google.android.videos",
    "com.mediatek.wwtv.tvcenter",
    "com.hisense.hibrowser",
    "com.android.vending",
    "com.android.tv.settings",
)

private val LABEL_OVERRIDES = mapOf(
    "com.mediatek.wwtv.tvcenter" to "Live TV & inputs",
    "com.android.tv.settings" to "TV settings",
    "com.android.vending" to "Play Store",
)

private const val ICON_PX = 128
private const val BANNER_W = 320
private const val BANNER_H = 180

class AppRepository(private val context: Context) {
    private val pm: PackageManager = context.packageManager
    private val cache = HashMap<String, Art>()

    private class Art(val icon: ImageBitmap, val banner: ImageBitmap?, val iconColor: Int)

    /** Every launchable app: LEANBACK_LAUNCHER first, LAUNCHER-only apps added after. */
    fun load(): List<LaunchableApp> {
        val byPackage = LinkedHashMap<String, ResolveInfo>()
        for (category in listOf(Intent.CATEGORY_LEANBACK_LAUNCHER, Intent.CATEGORY_LAUNCHER)) {
            val intent = Intent(Intent.ACTION_MAIN).addCategory(category)
            for (info in pm.queryIntentActivities(intent, 0)) {
                val pkg = info.activityInfo.packageName
                if (pkg != context.packageName) byPackage.putIfAbsent(pkg, info)
            }
        }
        val apps = byPackage.map { (pkg, info) ->
            val art = cache.getOrPut(pkg) {
                val icon = info.loadIcon(pm).toBitmap(ICON_PX, ICON_PX)
                Art(
                    icon = icon.asImageBitmap(),
                    // Activity banner, falling back to the application banner.
                    banner = info.activityInfo.loadBanner(pm)?.toBitmap(BANNER_W, BANNER_H)?.asImageBitmap(),
                    iconColor = averageColor(icon),
                )
            }
            LaunchableApp(
                packageName = pkg,
                label = LABEL_OVERRIDES[pkg] ?: info.loadLabel(pm).toString(),
                icon = art.icon,
                banner = art.banner,
                iconColor = art.iconColor,
            )
        }
        return apps.sortedWith(
            compareBy<LaunchableApp> { PREFERRED_ORDER.indexOf(it.packageName).let { i -> if (i < 0) Int.MAX_VALUE else i } }
                .thenBy { it.label.lowercase() }
        )
    }

    /** Mean colour of the icon's opaque pixels. */
    private fun averageColor(icon: Bitmap): Int {
        val small = Bitmap.createScaledBitmap(icon, 16, 16, true)
        var r = 0L; var g = 0L; var b = 0L; var n = 0L
        for (y in 0 until 16) for (x in 0 until 16) {
            val c = small.getPixel(x, y)
            if (Color.alpha(c) < 200) continue
            r += Color.red(c); g += Color.green(c); b += Color.blue(c); n++
        }
        if (small !== icon) small.recycle()
        if (n == 0L) return Color.rgb(40, 44, 80)
        return Color.rgb((r / n).toInt(), (g / n).toInt(), (b / n).toInt())
    }

    fun launchIntent(packageName: String): Intent? =
        (pm.getLeanbackLaunchIntentForPackage(packageName) ?: pm.getLaunchIntentForPackage(packageName))
            ?.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
}
