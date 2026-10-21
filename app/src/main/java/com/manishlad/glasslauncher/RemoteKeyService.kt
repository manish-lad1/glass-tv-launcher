package com.manishlad.glasslauncher

import android.accessibilityservice.AccessibilityService
import android.content.ActivityNotFoundException
import android.content.Intent
import android.util.Log
import android.view.KeyEvent
import android.view.accessibility.AccessibilityEvent

private const val TAG = "GlassLauncher"

/**
 * Remote app buttons. Hisense's key handler sends these to the stock launcher, which is
 * disabled, so this service catches the keys first (system-wide) and opens the apps itself.
 * Key codes found with getevent/logcat on 21 Oct 2026. Netflix (KEY_FN_F5) works without help.
 */
private val APP_BUTTONS = mapOf(
    293 to "com.google.android.youtube.tv",      // YouTube (scancode 469)
    295 to "com.amazon.amazonvideo.livingroom",  // Prime Video (scancode 468)
    337 to "in.startv.hotstar",                  // Hotstar (scancode 484)
)

class RemoteKeyService : AccessibilityService() {

    override fun onKeyEvent(event: KeyEvent): Boolean {
        val pkg = APP_BUTTONS[event.keyCode]
        val isSettings = event.keyCode == KeyEvent.KEYCODE_NOTIFICATION // Settings button (scancode 598)
        if (pkg == null && !isSettings) return false
        if (event.action == KeyEvent.ACTION_UP && !event.isCanceled) {
            val intent = if (pkg != null) {
                AppRepository(this).launchIntent(pkg)
            } else {
                Intent(ACTION_QUICK_SETTINGS).setClass(this, MainActivity::class.java)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
            }
            Log.i(TAG, "remote button ${event.keyCode} -> ${pkg ?: "settings"}")
            try {
                if (intent != null) startActivity(intent)
            } catch (e: ActivityNotFoundException) {
                Log.w(TAG, "remote button ${event.keyCode}: launch failed", e)
            }
        }
        return true
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) = Unit

    override fun onInterrupt() = Unit
}
