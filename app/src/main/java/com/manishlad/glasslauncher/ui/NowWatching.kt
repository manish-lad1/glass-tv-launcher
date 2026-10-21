package com.manishlad.glasslauncher.ui

import android.graphics.BitmapFactory
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.dp
import com.manishlad.glasslauncher.LaunchableApp
import com.manishlad.glasslauncher.data.WatchItem
import kotlin.math.abs

/**
 * Now watching: a card stack that is the widget. Front tile fills the slot; the next items
 * peek out to the right, smaller and fainter. ◀ ▶ flip, OK resumes, hold OK opens details.
 */
@Composable
fun NowWatchingStack(
    modifier: Modifier,
    items: List<WatchItem>,
    apps: List<LaunchableApp>,
    index: Int,
    onIndex: (Int) -> Unit,
    onResume: (WatchItem) -> Unit,
    onDetails: () -> Unit,
) {
    var focused by remember { mutableStateOf(false) }
    val focus by animateFocus(focused)
    val front = index.coerceIn(0, (items.size - 1).coerceAtLeast(0))
    Box(
        modifier
            .onFocusChanged { focused = it.isFocused }
            .onKeyEvent { e ->
                if (e.type != KeyEventType.KeyDown) return@onKeyEvent false
                when (e.key) {
                    // ◀ at the first item lets focus move on to the Calendar widget.
                    Key.DirectionLeft -> if (front > 0) { onIndex(front - 1); true } else false
                    Key.DirectionRight -> { if (front < items.size - 1) onIndex(front + 1); true }
                    else -> false
                }
            }
            .onSelect(
                onClick = { items.getOrNull(front)?.let(onResume) ?: onDetails() },
                onLongClick = onDetails,
            )
            .focusable()
            .graphicsLayer { val s = 1f + .04f * focus; scaleX = s; scaleY = s },
    ) {
        if (items.isEmpty()) {
            EmptyWatching(focus)
            return@Box
        }
        AnimatedContent(
            targetState = front,
            transitionSpec = {
                val forward = targetState > initialState
                (slideInHorizontally { if (forward) it / 3 else -it / 3 } + fadeIn()) togetherWith
                    (slideOutHorizontally { if (forward) -it / 3 else it / 3 } + fadeOut())
            },
            label = "stack",
        ) { i ->
            // The stack's right edge lines up with the rest of Home (1800 px): the next items peek
            // out as slim slivers inside the slot, and a lone item fills it.
            val peeks = (items.size - 1 - i).coerceIn(0, 2)
            Box(Modifier.fillMaxSize()) {
                items.getOrNull(i + 2)?.let { Tile(Modifier.padding(start = 140.px, top = 40.px, bottom = 40.px), 30, .3f, .5f, it) }
                items.getOrNull(i + 1)?.let { Tile(Modifier.padding(start = 70.px, end = (PEEK * (peeks - 1)).px, top = 20.px, bottom = 20.px), 33, .35f, .78f, it) }
                val item = items[i]
                Tile(Modifier.padding(end = (PEEK * peeks).px), 36, .45f + .4f * focus, 1f, item, scrim = true)
                FrontText(item, appLabel(item, apps), i, items.size, endPad = PEEK * peeks)
            }
        }
    }
}

/** How far each item behind the front one peeks out on the right, in design px. */
private const val PEEK = 14

fun appLabel(item: WatchItem, apps: List<LaunchableApp>) =
    apps.firstOrNull { it.packageName == item.packageName }?.label ?: item.packageName

@Composable
private fun FrontText(item: WatchItem, app: String, index: Int, count: Int, endPad: Int) {
    Box(Modifier.fillMaxSize().padding(end = endPad.px)) {
        SectionLabel("Now watching · $app", Modifier.padding(start = 30.px, top = 24.px), alpha = .9f, size = 18)
        Row(
            Modifier.align(Alignment.BottomStart).fillMaxWidth().padding(start = 30.px, end = 30.px, bottom = 26.px),
            horizontalArrangement = Arrangement.spacedBy(20.px),
            verticalAlignment = Alignment.Bottom,
        ) {
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(6.px)) {
                T(item.title, 34, weight = 500, tracking = -.4)
                val sub = listOfNotNull(item.upTo, item.timeLeft).joinToString(" · ")
                if (sub.isNotEmpty()) T(sub, 19, alpha = .88f)
                item.progress?.let { ProgressBar(it, Modifier.padding(top = 6.px).fillMaxWidth(.7f), 5) }
            }
            if (count > 1) Dots(index, count)
        }
    }
}

@Composable
fun ProgressBar(progress: Float, modifier: Modifier, heightPx: Int) {
    Box(modifier.height(heightPx.px).clip(CircleShape).background(Color.White.copy(alpha = .25f))) {
        Box(Modifier.fillMaxWidth(progress.coerceIn(0f, 1f)).fillMaxHeight().clip(CircleShape).background(Color.White))
    }
}

/** Current = 24 px pill, others 8 px dots. */
@Composable
private fun Dots(index: Int, count: Int) {
    Row(horizontalArrangement = Arrangement.spacedBy(8.px), modifier = Modifier.padding(bottom = 2.px)) {
        repeat(count.coerceAtMost(6)) { i ->
            val current = i == index.coerceAtMost(5)
            Box(Modifier.size(if (current) 24.px else 8.px, 8.px).clip(CircleShape).background(Color.White.copy(alpha = if (current) 1f else .5f)))
        }
    }
}

@Composable
private fun EmptyWatching(focus: Float) {
    GlassSurface(Modifier.fillMaxSize(), radius = 36.px, focus = focus, focusScale = 1f) {
        Column(Modifier.padding(horizontal = 34.px, vertical = 30.px), verticalArrangement = Arrangement.spacedBy(16.px)) {
            SectionLabel("Now watching")
            T("Nothing in your list yet.", 26, weight = 500)
            T("Add what you're watching from your phone, and it'll wait for you here.", 22, alpha = .75f, maxLines = 2)
        }
    }
}

@Composable
private fun Tile(modifier: Modifier, radiusPx: Int, borderAlpha: Float, alpha: Float, item: WatchItem, scrim: Boolean = false) {
    val shape = RoundedCornerShape(radiusPx.px)
    Box(
        modifier
            .fillMaxSize()
            .alpha(alpha)
            .shadow(14.dp, shape, ambientColor = Color.Black, spotColor = Color.Black)
            .clip(shape)
            .border(1.px, Color.White.copy(alpha = borderAlpha), shape),
    ) {
        Artwork(item, Modifier.fillMaxSize())
        if (scrim) Box(Modifier.fillMaxSize().background(Brush.verticalGradient(0f to Color(0x59060614), .3f to Color(0x00060614), 1f to Color(0xBF060614))))
    }
}

/** Uploaded artwork if there is one, otherwise a generated placeholder picked from the title. */
@Composable
fun Artwork(item: WatchItem, modifier: Modifier) {
    val bitmap = remember(item.artworkPath) {
        item.artworkPath?.substringBefore('?')?.let { path ->
            runCatching {
                val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                BitmapFactory.decodeFile(path, bounds)
                var sample = 1
                while (bounds.outWidth / (sample * 2) >= 1280) sample *= 2
                BitmapFactory.decodeFile(path, BitmapFactory.Options().apply { inSampleSize = sample })?.asImageBitmap()
            }.getOrNull()
        }
    }
    if (bitmap != null) {
        Image(bitmap, contentDescription = null, modifier = modifier, contentScale = ContentScale.Crop)
    } else {
        val art = Art.entries[abs(item.title.hashCode()) % Art.entries.size]
        Canvas(modifier) { drawArt(art) }
    }
}

enum class Art { Dusk, City, Forest }

/** Placeholder artwork drawn in a 320×180 viewBox with "slice" (cover) scaling. */
fun DrawScope.drawArt(art: Art) {
    val s = maxOf(size.width / 320f, size.height / 180f)
    val dx = (size.width - 320f * s) / 2f
    val dy = (size.height - 180f * s) / 2f
    fun o(x: Float, y: Float) = Offset(dx + x * s, dy + y * s)
    fun poly(color: Color, vararg pts: Float) {
        val p = Path().apply {
            moveTo(o(pts[0], pts[1]).x, o(pts[0], pts[1]).y)
            var i = 2
            while (i < pts.size) { val q = o(pts[i], pts[i + 1]); lineTo(q.x, q.y); i += 2 }
            close()
        }
        drawPath(p, color)
    }
    when (art) {
        Art.Dusk -> {
            drawRect(Color(0xFF24345E))
            drawCircle(Color(0x1FF2C27E), 56 * s, o(236f, 62f))
            drawCircle(Color(0xFFF2C27E), 34 * s, o(236f, 62f))
            poly(Color(0xFF141D3A), 0f, 140f, 90f, 84f, 170f, 128f, 250f, 94f, 320f, 122f, 320f, 180f, 0f, 180f)
        }
        Art.City -> {
            drawRect(Color(0xFF3F2A3A))
            poly(Color(0xFF6B4058), 40f, 50f, 110f, 50f, 110f, 180f, 40f, 180f)
            poly(Color(0xFF8A5470), 122f, 26f, 178f, 26f, 178f, 180f, 122f, 180f)
            poly(Color(0xFF5A3550), 190f, 66f, 280f, 66f, 280f, 180f, 190f, 180f)
        }
        Art.Forest -> {
            drawRect(Color(0xFF2F5A4C))
            drawCircle(Color(0xD9CFE8D6), 44 * s, o(96f, 74f))
            poly(Color(0xFF163029), 0f, 180f, 80f, 124f, 160f, 156f, 240f, 110f, 320f, 142f, 320f, 180f)
        }
    }
}
