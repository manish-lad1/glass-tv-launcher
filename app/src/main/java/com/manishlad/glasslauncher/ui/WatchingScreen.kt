package com.manishlad.glasslauncher.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.foundation.focusable
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.unit.dp
import com.manishlad.glasslauncher.LaunchableApp
import com.manishlad.glasslauncher.data.WatchItem

/** "S2 · E5" → "Season 2 · Episode 5"; anything else is shown as typed. */
private fun longUpTo(upTo: String?): String? {
    val m = upTo?.let { Regex("""^\s*S(\d+)\s*[·.\-]?\s*E(\d+)\s*$""", RegexOption.IGNORE_CASE).find(it) } ?: return upTo
    return "Season ${m.groupValues[1]} · Episode ${m.groupValues[2]}"
}

@Composable
fun WatchingScreen(
    items: List<WatchItem>,
    apps: List<LaunchableApp>,
    index: Int,
    onIndex: (Int) -> Unit,
    onResume: (WatchItem) -> Unit,
    onRemove: (WatchItem) -> Unit,
) {
    val item = items.getOrNull(index.coerceIn(0, (items.size - 1).coerceAtLeast(0)))
    val resumeFocus = remember { FocusRequester() }
    LaunchedEffect(item?.id) { runCatching { resumeFocus.requestFocus() } }

    Box(Modifier.fillMaxSize()) {
        // Artwork full-bleed, darkened towards the left for the text.
        if (item != null) Artwork(item, Modifier.fillMaxSize())
        Box(
            Modifier.fillMaxSize().background(
                Brush.horizontalGradient(0f to Color(0xF20B0E24), .45f to Color(0xB30B0E24), .75f to Color(0x330B0E24), 1f to Color(0x000B0E24)),
            ),
        )
        Box(Modifier.fillMaxSize().background(Brush.verticalGradient(.55f to Color.Transparent, 1f to Color(0xE60B0E24))))
        Box(Modifier.align(Alignment.TopEnd).padding(top = 72.px, end = 120.px)) { HintChip("Back to Home") }

        Column(Modifier.fillMaxSize().padding(start = 120.px, end = 120.px, top = 150.px, bottom = 72.px)) {
            if (item == null) {
                SectionLabel("Now watching")
                Spacer(Modifier.height(20.px))
                T("Nothing in your list yet.", 72, weight = 300, tracking = -1.5)
                Spacer(Modifier.height(20.px))
                T("Add what you're watching from your phone: the title, the app, and where you're up to.", 26, alpha = .85f, maxLines = 2)
                Spacer(Modifier.weight(1f))
                AddFromPhoneTile(Modifier.width(400.px).height(190.px), focusRequester = resumeFocus)
                return@Column
            }
            val app = appLabel(item, apps)
            SectionLabel("Now watching · $app", size = 22)
            Spacer(Modifier.height(16.px))
            T(item.title, 104, weight = 400, tracking = -2.5, lineHeight = 120)
            Spacer(Modifier.height(12.px))
            val sub = listOfNotNull(longUpTo(item.upTo), item.timeLeft).joinToString(" · ")
            if (sub.isNotEmpty()) T(sub, 30, alpha = .9f)
            item.progress?.let {
                Spacer(Modifier.height(22.px))
                ProgressBar(it, Modifier.width(560.px), 6)
            }
            Spacer(Modifier.height(36.px))
            Row(horizontalArrangement = Arrangement.spacedBy(20.px), verticalAlignment = Alignment.CenterVertically) {
                PillButton("Resume", primary = true, focusRequester = resumeFocus) { onResume(item) }
                PillButton("Mark watched") { onRemove(item) }
                PillButton("Remove from list") { onRemove(item) }
            }
            Spacer(Modifier.height(26.px))
            T(
                "${if (item.deepLink != null) "Resume opens the show in $app." else "Resume opens $app."} Where you are is the episode you set — the TV can't read the app's own progress.",
                21, alpha = .78f, maxLines = 2, lineHeight = 28, modifier = Modifier.width(900.px),
            )
            Spacer(Modifier.weight(1f))
            val others = items.filter { it.id != item.id }
            SectionLabel("Up next in your list", alpha = .85f)
            Spacer(Modifier.height(20.px))
            Row(horizontalArrangement = Arrangement.spacedBy(28.px)) {
                for (other in others.take(3)) {
                    Column(verticalArrangement = Arrangement.spacedBy(14.px)) {
                        GlassButton(
                            onClick = { onIndex(items.indexOf(other)) },
                            modifier = Modifier.width(400.px).height(190.px),
                            radius = 24.px,
                            focusScale = 1.05f,
                        ) { Artwork(other, Modifier.fillMaxSize()) }
                        T(other.title, 26, weight = 500, modifier = Modifier.width(400.px))
                        T(listOfNotNull(appLabel(other, apps), other.timeLeft).joinToString(" · "), 21, alpha = .78f, modifier = Modifier.width(400.px))
                    }
                }
                if (others.size < 3) AddFromPhoneTile(Modifier.width(400.px).height(190.px))
            }
        }
    }
}

@Composable
private fun PillButton(text: String, primary: Boolean = false, focusRequester: FocusRequester? = null, onClick: () -> Unit) {
    var focused by remember { mutableStateOf(false) }
    GlassButton(
        onClick = onClick,
        radius = 999.dp,
        focusScale = 1.06f,
        focusRequester = focusRequester,
        onFocus = { focused = it },
    ) {
        Box(Modifier.then(if (primary) Modifier.background(Color.White) else Modifier)) {
            Row(
                Modifier.padding(horizontal = 40.px, vertical = 18.px),
                horizontalArrangement = Arrangement.spacedBy(14.px),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                if (primary) {
                    Canvas(Modifier.size(18.px)) {
                        val p = androidx.compose.ui.graphics.Path().apply {
                            moveTo(size.width * .15f, 0f); lineTo(size.width, size.height / 2); lineTo(size.width * .15f, size.height); close()
                        }
                        drawPath(p, Color(0xFF14102B))
                    }
                    androidx.compose.foundation.text.BasicText(
                        text,
                        style = androidx.compose.ui.text.TextStyle(
                            color = Color(0xFF14102B), fontFamily = Inter,
                            fontWeight = androidx.compose.ui.text.font.FontWeight.Medium, fontSize = 26.pxSp,
                        ),
                    )
                } else {
                    T(text, 26, weight = 500)
                }
            }
        }
    }
}

/** Dashed slot: adding happens on the phone (phase 5). Focusable so the row can be reached. */
@Composable
private fun AddFromPhoneTile(modifier: Modifier, focusRequester: FocusRequester? = null) {
    var focused by remember { mutableStateOf(false) }
    val focus by animateFocus(focused)
    Box(
        modifier
            .then(if (focusRequester != null) Modifier.focusRequester(focusRequester) else Modifier)
            .onFocusChanged { focused = it.isFocused }
            .focusable()
            .graphicsLayer { val s = 1f + .05f * focus; scaleX = s; scaleY = s }
            .drawBehind {
                drawRoundRect(
                    Color.White.copy(alpha = .35f + .45f * focus),
                    cornerRadius = CornerRadius(24.px.toPx()),
                    style = Stroke(width = 2f, pathEffect = PathEffect.dashPathEffect(floatArrayOf(14f, 10f))),
                )
            },
        contentAlignment = Alignment.Center,
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(12.px)) {
            Glyph(Glyphs.Plus, 36)
            T("Add from your phone", 22, alpha = .9f)
        }
    }
}
