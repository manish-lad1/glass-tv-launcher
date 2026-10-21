package com.manishlad.glasslauncher.ui

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.State
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.RoundRect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.geometry.toRect
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEvent
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp

/** The pre-blurred image glass draws from, and the root size it is stretched to. */
class GlassSource(val image: ImageBitmap, val rootSize: State<IntSize>)

val LocalGlassSource = staticCompositionLocalOf<GlassSource?> { null }

private val ShadowColor = Color(0xFF080618)

/**
 * Glass card (§4): a crop of the blurred wallpaper at this element's screen position,
 * white tint, diagonal sheen, 1 px border with a brighter top edge, soft shadow.
 * [focus] goes 0→1 for the focused look: brighter fill/border, outer ring, scale.
 */
@Composable
fun GlassSurface(
    modifier: Modifier = Modifier,
    radius: Dp,
    focus: Float = 0f,
    focusScale: Float = 1.1f,
    contentAlignment: Alignment = Alignment.TopStart,
    content: @Composable BoxScope.() -> Unit = {},
) {
    val source = LocalGlassSource.current
    var origin by remember { mutableStateOf(Offset.Zero) }
    val shape = RoundedCornerShape(radius)
    Box(
        modifier = modifier
            .onGloballyPositioned { val p = it.positionInRoot(); if (p != origin) origin = p }
            .graphicsLayer {
                val s = 1f + (focusScale - 1f) * focus
                scaleX = s
                scaleY = s
                shadowElevation = (12.dp + 10.dp * focus).toPx()
                this.shape = shape
                clip = false
                ambientShadowColor = ShadowColor
                spotShadowColor = ShadowColor
            }
            .drawWithCache {
                val r = radius.toPx()
                val path = Path().apply { addRoundRect(RoundRect(size.toRect(), CornerRadius(r))) }
                val sheen = Brush.linearGradient(
                    0f to Color.White.copy(alpha = .22f), .42f to Color.Transparent,
                    start = Offset.Zero, end = Offset(size.width, size.height),
                )
                val highlight = Brush.verticalGradient(
                    listOf(Color.White.copy(alpha = .45f), Color.Transparent), startY = 0f, endY = r * .7f,
                )
                val inset = Offset(.5f, .5f)
                val insetSize = Size(size.width - 1f, size.height - 1f)
                onDrawWithContent {
                    if (source != null) clipPath(path) {
                        translate(-origin.x, -origin.y) {
                            drawImage(source.image, dstSize = source.rootSize.value, filterQuality = FilterQuality.Low)
                        }
                    }
                    drawPath(path, Color.White.copy(alpha = .12f + .18f * focus))
                    drawPath(path, sheen)
                    // Content (e.g. an app banner) sits between the glass and its edge, so the
                    // border and focus ring stay visible over full-bleed artwork.
                    drawContent()
                    drawRoundRect(Color.White.copy(alpha = .24f + .46f * focus), inset, insetSize, CornerRadius(r - .5f), Stroke(1f))
                    drawRoundRect(highlight, inset, insetSize, CornerRadius(r - .5f), style = Stroke(1f))
                    if (focus > 0f) drawRoundRect(
                        Color.White.copy(alpha = .18f * focus), Offset(-1f, -1f),
                        Size(size.width + 2f, size.height + 2f), CornerRadius(r + 1f), Stroke(2f),
                    )
                }
            }
            .clip(shape),
        contentAlignment = contentAlignment,
        content = content,
    )
}

fun KeyEvent.isSelect() = key == Key.DirectionCenter || key == Key.Enter || key == Key.NumPadEnter

/** Focus progress 0→1, animated (transform/alpha only). */
@Composable
fun animateFocus(focused: Boolean) =
    animateFloatAsState(if (focused) 1f else 0f, tween(160), label = "focus")

/**
 * OK/Enter handling for remote keys: [onClick] on release, or [onLongClick] once the key
 * auto-repeats (held ~0.5 s), in which case the release is swallowed.
 */
fun Modifier.onSelect(onClick: () -> Unit, onLongClick: (() -> Unit)? = null): Modifier = composed {
    var longFired by remember { mutableStateOf(false) }
    onKeyEvent { e ->
        if (!e.isSelect()) return@onKeyEvent false
        when (e.type) {
            KeyEventType.KeyDown -> if (e.nativeKeyEvent.repeatCount > 0 && onLongClick != null && !longFired) {
                longFired = true
                onLongClick()
            }
            KeyEventType.KeyUp -> if (longFired) longFired = false else onClick()
        }
        true
    }
}

/** Focusable element with remote OK handling, for custom-drawn items (not glass). */
fun Modifier.tvSelectable(onFocus: (Boolean) -> Unit, onClick: () -> Unit, onLongClick: (() -> Unit)? = null): Modifier =
    onFocusChanged { onFocus(it.isFocused) }.onSelect(onClick, onLongClick).focusable()

/** A focusable glass button. OK/Enter (on release) triggers [onClick]; holding OK triggers [onLongClick]. */
@Composable
fun GlassButton(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    onLongClick: (() -> Unit)? = null,
    radius: Dp,
    focusScale: Float = 1.1f,
    focusRequester: FocusRequester? = null,
    onFocus: (Boolean) -> Unit = {},
    contentAlignment: Alignment = Alignment.Center,
    content: @Composable BoxScope.(focused: Boolean) -> Unit,
) {
    var focused by remember { mutableStateOf(false) }
    val focus by animateFocus(focused)
    GlassSurface(
        modifier = modifier
            .then(if (focusRequester != null) Modifier.focusRequester(focusRequester) else Modifier)
            .onFocusChanged {
                if (it.isFocused != focused) {
                    focused = it.isFocused
                    onFocus(it.isFocused)
                }
            }
            .onSelect(onClick, onLongClick)
            .focusable(),
        radius = radius,
        focus = focus,
        focusScale = focusScale,
        contentAlignment = contentAlignment,
    ) { content(focused) }
}
