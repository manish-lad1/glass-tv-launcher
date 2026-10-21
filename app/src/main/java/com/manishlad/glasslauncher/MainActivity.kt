package com.manishlad.glasslauncher

import android.content.ActivityNotFoundException
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.util.Log
import android.view.KeyEvent
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.viewModels
import com.manishlad.glasslauncher.data.TvSource
import com.manishlad.glasslauncher.data.TvSources
import com.manishlad.glasslauncher.data.WatchItem
import com.manishlad.glasslauncher.ui.LauncherRoot

private const val TAG = "GlassLauncher"
private const val RECOVERY_HOLD_MS = 5_000L
private const val IDLE_MS = 3 * 60_000L

/** Opens the launcher with Quick settings showing (used by the remote's Settings button). */
const val ACTION_QUICK_SETTINGS = "com.manishlad.glasslauncher.QUICK_SETTINGS"

class MainActivity : ComponentActivity() {
    private val vm: LauncherViewModel by viewModels()
    private val handler = Handler(Looper.getMainLooper())

    private var recoveryFired = false
    private val recovery = Runnable {
        recoveryFired = true
        Log.i(TAG, "Back held ${RECOVERY_HOLD_MS}ms: opening Android Settings")
        openSettings(Settings.ACTION_SETTINGS)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        openQuickSettings = intent?.action == ACTION_QUICK_SETTINGS
        setContent {
            LauncherRoot(
                vm = vm,
                onLaunch = ::launch,
                onResume = ::resume,
                onOpenSettings = ::openSettings,
                onSwitchInput = ::switchInput,
            )
        }
    }

    /** Idle 3 min on Home → Ambient. Any key resets it. */
    private val idle = Runnable { if (vm.screen == Screen.Home) vm.enterAmbient() }

    private fun restartIdleTimer() {
        handler.removeCallbacks(idle)
        handler.postDelayed(idle, glassApp.idleMsOverride ?: IDLE_MS)
    }

    /** Set when launched by the remote's Settings button: open Quick settings once resumed. */
    private var openQuickSettings = false

    override fun onResume() {
        super.onResume()
        vm.exitAmbient()
        vm.refreshApps()
        vm.returnHome()
        if (openQuickSettings) {
            openQuickSettings = false
            vm.openQuickSettings()
        }
        restartIdleTimer()
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        openQuickSettings = intent.action == ACTION_QUICK_SETTINGS
        vm.returnHome()
    }

    /** null = Home (just close the panel). Otherwise switch the TV to that input. */
    private fun switchInput(source: TvSource?) {
        if (source == null) {
            vm.closeQuickSettings()
            return
        }
        val intent = TvSources.switchIntent(this, source)
        try {
            if (intent == null) throw ActivityNotFoundException()
            startActivity(intent)
            vm.onInputSwitched(source)
        } catch (e: ActivityNotFoundException) {
            Log.w(TAG, "No app handles input ${source.label}")
        }
    }

    /**
     * Back never leaves the launcher. A short press returns to the dock; holding it
     * for 5 s opens Android Settings (the recovery hatch).
     */
    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        restartIdleTimer()
        // In Ambient every key just wakes (and is swallowed); OK on the Resume chip resumes.
        if (vm.ambient) {
            if (event.action == KeyEvent.ACTION_UP) {
                val front = vm.watching.value.let { it.getOrNull(vm.watchingIndex) ?: it.firstOrNull() }
                val ok = event.keyCode == KeyEvent.KEYCODE_DPAD_CENTER || event.keyCode == KeyEvent.KEYCODE_ENTER
                vm.exitAmbient()
                if (ok && front != null) resume(front)
            }
            return true
        }
        if (event.keyCode == KeyEvent.KEYCODE_BACK) {
            when (event.action) {
                KeyEvent.ACTION_DOWN -> if (event.repeatCount == 0) {
                    recoveryFired = false
                    handler.removeCallbacks(recovery)
                    handler.postDelayed(recovery, RECOVERY_HOLD_MS)
                }
                KeyEvent.ACTION_UP -> {
                    handler.removeCallbacks(recovery)
                    if (!recoveryFired && !event.isCanceled) vm.back()
                }
            }
            return true
        }
        return super.dispatchKeyEvent(event)
    }

    override fun onPause() {
        handler.removeCallbacks(recovery)
        handler.removeCallbacks(idle)
        super.onPause()
    }

    private fun launch(app: LaunchableApp) {
        val intent = vm.repo.launchIntent(app.packageName)
        if (intent == null) {
            Log.w(TAG, "No launch intent for ${app.packageName}")
            return
        }
        try {
            startActivity(intent)
            vm.onLaunched(app.packageName)
        } catch (e: ActivityNotFoundException) {
            Log.w(TAG, "Launch failed for ${app.packageName}", e)
        }
    }

    /** Resume a Now watching title: its deep link if it has one, otherwise just open the app. */
    private fun resume(item: WatchItem) {
        item.deepLink?.let { link ->
            val view = Intent(Intent.ACTION_VIEW, Uri.parse(link))
                .setPackage(item.packageName)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            try {
                startActivity(view)
                return
            } catch (e: ActivityNotFoundException) {
                Log.w(TAG, "Deep link not handled by ${item.packageName}; opening the app")
            }
        }
        val app = vm.apps.firstOrNull { it.packageName == item.packageName }
        if (app != null) launch(app) else vm.repo.launchIntent(item.packageName)?.let { startActivity(it) }
    }

    private fun openSettings(action: String) {
        try {
            startActivity(Intent(action).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        } catch (e: ActivityNotFoundException) {
            Log.w(TAG, "No activity for $action", e)
            if (action != Settings.ACTION_SETTINGS) openSettings(Settings.ACTION_SETTINGS)
        }
    }
}
