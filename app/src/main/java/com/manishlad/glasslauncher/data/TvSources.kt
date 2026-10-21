package com.manishlad.glasslauncher.data

import android.content.Context
import android.content.Intent
import android.media.tv.TvContract
import android.media.tv.TvInputInfo
import android.media.tv.TvInputManager

/** A TV input (HDMI port, AV, antenna) as shown in Quick settings. */
data class TvSource(val id: String, val label: String, val kind: Kind, val hdmiPort: Int = 0) {
    enum class Kind { Hdmi, Av, Tuner }
}

/** Live TV app: plays the antenna tuner and handles passthrough (HDMI/AV) channel URIs. */
const val LIVE_TV_PACKAGE = "com.mediatek.wwtv.tvcenter"

/**
 * Inputs from the TV framework (TvInputManager), read at runtime rather than hard-coded.
 * Android doesn't tell other apps which input is on screen, so "last used" is what we switched to.
 */
object TvSources {
    fun load(context: Context): List<TvSource> {
        val tim = context.getSystemService(TvInputManager::class.java) ?: return emptyList()
        val list = tim.tvInputList.filter { !it.isHidden(context) }.mapNotNull { info ->
            val kind = when (info.type) {
                TvInputInfo.TYPE_HDMI -> TvSource.Kind.Hdmi
                TvInputInfo.TYPE_COMPOSITE, TvInputInfo.TYPE_COMPONENT, TvInputInfo.TYPE_SVIDEO, TvInputInfo.TYPE_SCART -> TvSource.Kind.Av
                TvInputInfo.TYPE_TUNER -> TvSource.Kind.Tuner
                else -> return@mapNotNull null
            }
            // HDMI port from the input id ("…/HW4") isn't reliable; the label usually carries it.
            val label = (info.loadCustomLabel(context) ?: info.loadLabel(context))?.toString()?.trim().orEmpty()
            val port = Regex("""(\d+)""").find(label)?.value?.toIntOrNull() ?: 0
            TvSource(info.id, friendlyLabel(kind, label, port), kind, port)
        }
        // The tuner is often listed twice (e.g. HW0 and HW8); keep one of each label.
        return list.distinctBy { it.label }.sortedWith(compareBy({ it.kind.ordinal }, { it.hdmiPort }, { it.label }))
    }

    private fun friendlyLabel(kind: TvSource.Kind, label: String, port: Int) = when (kind) {
        TvSource.Kind.Hdmi -> if (port > 0) "HDMI $port" else label.ifBlank { "HDMI" }
        TvSource.Kind.Av -> "AV"
        TvSource.Kind.Tuner -> "Antenna"
    }

    /** Intent that switches the TV to [source]. */
    fun switchIntent(context: Context, source: TvSource): Intent? = when (source.kind) {
        TvSource.Kind.Tuner -> context.packageManager.getLeanbackLaunchIntentForPackage(LIVE_TV_PACKAGE)
            ?: context.packageManager.getLaunchIntentForPackage(LIVE_TV_PACKAGE)
        else -> Intent(Intent.ACTION_VIEW, TvContract.buildChannelUriForPassthroughInput(source.id))
    }?.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
}
