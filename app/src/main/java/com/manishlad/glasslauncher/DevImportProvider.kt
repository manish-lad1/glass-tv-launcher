package com.manishlad.glasslauncher

import android.content.ContentProvider
import android.content.ContentValues
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.os.ParcelFileDescriptor
import android.util.Log
import com.manishlad.glasslauncher.data.WallpaperMode
import kotlinx.coroutines.launch
import java.io.File
import java.io.FileNotFoundException

private const val TAG = "GlassLauncher"

/**
 * Stand-in for phone uploads until phase 5: lets adb pipe a photo into the wallpaper library.
 * Write-only and guarded by DUMP (adb shell / system only):
 *   adb shell 'content write --uri "content://com.manishlad.glasslauncher.dev/wallpaper?name=Goa%20Beach&use=true"' < photo.jpg
 */
class DevImportProvider : ContentProvider() {
    override fun onCreate() = true

    override fun openFile(uri: Uri, mode: String): ParcelFileDescriptor {
        if (uri.pathSegments.firstOrNull() != "wallpaper" || !mode.contains('w')) throw FileNotFoundException("write-only: /wallpaper")
        val app = context!!.glassApp
        val name = uri.getQueryParameter("name")?.takeIf { it.isNotBlank() } ?: "Photo"
        val credit = uri.getQueryParameter("credit")
        val use = uri.getQueryParameter("use") == "true"
        val tmp = File.createTempFile("import", ".img", context!!.cacheDir)
        // Import once adb closes the stream.
        return ParcelFileDescriptor.open(
            tmp,
            ParcelFileDescriptor.MODE_WRITE_ONLY or ParcelFileDescriptor.MODE_TRUNCATE,
            Handler(Looper.getMainLooper()),
        ) {
            app.scope.launch {
                try {
                    val info = app.wallpapers.import(tmp, name, credit)
                    if (use) app.wallpapers.setMode(WallpaperMode.Fixed(info.id))
                    app.wallpaperChanged()
                } catch (e: Exception) {
                    Log.w(TAG, "wallpaper import failed: ${e.message}")
                } finally {
                    tmp.delete()
                }
            }
        }
    }

    override fun query(uri: Uri, p: Array<out String>?, s: String?, a: Array<out String>?, o: String?) = null
    override fun getType(uri: Uri): String? = null
    override fun insert(uri: Uri, values: ContentValues?): Uri? = null
    override fun delete(uri: Uri, s: String?, a: Array<out String>?) = 0
    override fun update(uri: Uri, v: ContentValues?, s: String?, a: Array<out String>?) = 0
}
