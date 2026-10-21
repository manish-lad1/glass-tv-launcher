package com.manishlad.glasslauncher

import android.service.dreams.DreamService
import android.view.KeyEvent
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.platform.ComposeView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.setViewTreeLifecycleOwner
import androidx.savedstate.SavedStateRegistry
import androidx.savedstate.SavedStateRegistryController
import androidx.savedstate.SavedStateRegistryOwner
import androidx.savedstate.setViewTreeSavedStateRegistryOwner
import com.manishlad.glasslauncher.data.WatchItem
import com.manishlad.glasslauncher.ui.AmbientScreen

/**
 * System screensaver: the same Ambient screen as Home's idle mode, shown by Android TV when the
 * TV sits idle anywhere (e.g. a paused video). Interactive so OK can resume; any other key wakes.
 */
class GlassDream : DreamService(), LifecycleOwner, SavedStateRegistryOwner {
    private val lifecycleRegistry = LifecycleRegistry(this)
    private val savedState = SavedStateRegistryController.create(this)
    override val lifecycle: Lifecycle get() = lifecycleRegistry
    override val savedStateRegistry: SavedStateRegistry get() = savedState.savedStateRegistry

    private lateinit var slideshow: AmbientSlideshow
    private var front: WatchItem? = null

    override fun onCreate() {
        super.onCreate()
        savedState.performRestore(null)
        lifecycleRegistry.currentState = Lifecycle.State.CREATED
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        isInteractive = true
        isFullscreen = true
        isScreenBright = true
        val app = glassApp
        slideshow = AmbientSlideshow(app, lifecycleScope)
        window.decorView.setViewTreeLifecycleOwner(this)
        window.decorView.setViewTreeSavedStateRegistryOwner(this)
        setContentView(ComposeView(this).apply {
            setContent {
                val slide by slideshow.slide.collectAsState()
                val weather by app.weather.state.collectAsState()
                val calendar by app.calendar.state.collectAsState()
                val watching by app.watching.items.collectAsState()
                front = watching.firstOrNull()
                AmbientScreen(slide, weather, calendar, front)
            }
        })
    }

    override fun onDreamingStarted() {
        super.onDreamingStarted()
        lifecycleRegistry.currentState = Lifecycle.State.RESUMED
        // Start from what Home would show, then walk the library.
        val app = glassApp
        val lib = app.wallpapers.library.value
        val home = app.wallpapers.pickForHome()
        slideshow.startAt(lib.indexOfFirst { it.id == home.id }.coerceAtLeast(0))
    }

    override fun onDreamingStopped() {
        slideshow.stop()
        lifecycleRegistry.currentState = Lifecycle.State.CREATED
        super.onDreamingStopped()
    }

    override fun onDetachedFromWindow() {
        lifecycleRegistry.currentState = Lifecycle.State.DESTROYED
        super.onDetachedFromWindow()
    }

    /** OK on the Resume chip resumes the show; every key ends the screensaver. */
    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        if (event.action == KeyEvent.ACTION_UP) {
            val ok = event.keyCode == KeyEvent.KEYCODE_DPAD_CENTER || event.keyCode == KeyEvent.KEYCODE_ENTER
            val item = front
            if (ok && item != null) {
                val intent = AppRepository(this).launchIntent(item.packageName)
                if (intent != null) startActivity(intent)
            }
            finish()
        }
        return true
    }
}
