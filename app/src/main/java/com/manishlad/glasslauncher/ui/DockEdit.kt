package com.manishlad.glasslauncher.ui

import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import com.manishlad.glasslauncher.LaunchableApp
import com.manishlad.glasslauncher.data.DOCK_MAX

/**
 * Dock edit mode (design 06): widgets hidden, tiles wiggle with remove badges, the moving tile
 * lifts with ◀ ▶ chevrons, and a dashed "+" slot adds an app. One focus target handles the keys:
 * ◀ ▶ move (or step the cursor), OK pick up / drop / add, ▼ remove, Back done.
 */
@Composable
fun DockEditScreen(
    packages: List<String>,
    apps: List<LaunchableApp>,
    cursor: Int,
    moving: Boolean,
    onLeft: () -> Unit,
    onRight: () -> Unit,
    onOk: () -> Unit,
    onRemove: () -> Unit,
) {
    val byPkg = apps.associateBy { it.packageName }
    val dock = packages.mapNotNull { byPkg[it] }
    val focus = remember { FocusRequester() }
    LaunchedEffect(Unit) { runCatching { focus.requestFocus() } }

    Box(
        Modifier
            .fillMaxSize()
            .focusRequester(focus)
            .onKeyEvent { e ->
                when (e.key) {
                    Key.DirectionLeft -> { if (e.type == KeyEventType.KeyDown) onLeft(); true }
                    Key.DirectionRight -> { if (e.type == KeyEventType.KeyDown) onRight(); true }
                    Key.DirectionDown -> { if (e.type == KeyEventType.KeyDown) onRemove(); true }
                    Key.DirectionUp -> true
                    else -> if (e.isSelect()) { if (e.type == KeyEventType.KeyUp) onOk(); true } else false
                }
            }
            .focusable(),
    ) {
        Row(
            Modifier.fillMaxWidth().padding(start = 120.px, end = 120.px, top = 96.px),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(verticalArrangement = Arrangement.spacedBy(10.px)) {
                T("Editing dock", 64, weight = 300, tracking = -1.5)
                T("Widgets are hidden while you edit. ${dock.size} of $DOCK_MAX slots used.", 24, alpha = .8f)
            }
            HintChip("Back · Done")
        }

        Column(
            Modifier.align(Alignment.Center).fillMaxWidth().padding(horizontal = 120.px),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            val current = dock.getOrNull(cursor)
            Box(Modifier.height(40.px), contentAlignment = Alignment.Center) {
                when {
                    current != null && moving -> Row {
                        T("Moving ", 26)
                        T(current.label, 26, weight = 600)
                    }
                    current != null -> T(current.label, 26, weight = 500)
                    else -> T("Add an app", 26, weight = 500)
                }
            }
            Spacer(Modifier.height(30.px))
            GlassSurface(Modifier.fillMaxWidth(), radius = 48.px) {
                Row(
                    Modifier.fillMaxWidth().padding(horizontal = 24.px, vertical = 22.px),
                    horizontalArrangement = Arrangement.spacedBy(DOCK_GAP.px, Alignment.CenterHorizontally),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    val slots = dock.size + if (dock.size < DOCK_MAX) 1 else 0
                    val tileW = minOf(260, (1680 - 48 - (slots - 1) * DOCK_GAP) / slots.coerceAtLeast(1))
                    dock.forEachIndexed { i, app -> EditTile(app, i, tileW, i == cursor, moving && i == cursor) }
                    if (dock.size < DOCK_MAX) AddSlot(DOCK_TILE_H, cursor == dock.size)
                }
            }
        }

        // Hint bar
        GlassSurface(Modifier.align(Alignment.BottomCenter).padding(bottom = 80.px), radius = 999.px) {
            Row(
                Modifier.padding(horizontal = 34.px, vertical = 18.px),
                horizontalArrangement = Arrangement.spacedBy(30.px),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Hint("◀ ▶", if (moving) "Move" else "Choose")
                Hint("OK", if (moving) "Drop here" else if (cursor >= dock.size) "Add an app" else "Pick up")
                Hint("▼", "Remove from dock")
                Hint("Back", "Done")
            }
        }
    }
}

@Composable
private fun EditTile(app: LaunchableApp, index: Int, widthPx: Int, focused: Boolean, lifted: Boolean) {
    // Gentle wiggle (±1.5°), alternating per tile; the lifted tile holds still.
    val wiggle = rememberInfiniteTransition(label = "wiggle")
    val angle by wiggle.animateFloat(
        initialValue = -1.5f, targetValue = 1.5f,
        animationSpec = infiniteRepeatable(tween(260 + (index % 3) * 40, easing = FastOutSlowInEasing), RepeatMode.Reverse),
        label = "angle",
    )
    val lift by animateFloatAsState(if (lifted) 1f else 0f, tween(180), label = "lift")
    val focus by animateFocus(focused)
    Box {
        GlassSurface(
            Modifier
                .size(widthPx.px, DOCK_TILE_H.px)
                .graphicsLayer {
                    rotationZ = if (lifted) 0f else if (index % 2 == 0) angle else -angle
                    translationY = -16.px.toPx() * lift
                },
            radius = 28.px,
            focus = maxOf(focus, lift),
            focusScale = 1.1f,
        ) { AppArt(app, 84) }
        if (!lifted) {
            // Remove badge
            Box(
                Modifier.offset((-10).px, (-10).px).size(36.px).clip(CircleShape).background(Color(0xFF1D1A3D))
                    .border(1.px, Color.White.copy(alpha = .5f), CircleShape),
                contentAlignment = Alignment.Center,
            ) { T("−", 24, weight = 600) }
        } else {
            T("‹", 40, Modifier.align(Alignment.CenterStart).offset((-30).px, (-16).px), weight = 300)
            T("›", 40, Modifier.align(Alignment.CenterEnd).offset(30.px, (-16).px), weight = 300)
        }
    }
}

@Composable
private fun AddSlot(sizePx: Int, focused: Boolean) {
    val focus by animateFocus(focused)
    Box(
        Modifier
            .size(sizePx.px)
            .graphicsLayer { val s = 1f + .08f * focus; scaleX = s; scaleY = s }
            .drawBehind {
                drawRoundRect(
                    Color.White.copy(alpha = .45f + .5f * focus),
                    cornerRadius = CornerRadius(28.px.toPx()),
                    style = Stroke(width = 2f + 2f * focus, pathEffect = PathEffect.dashPathEffect(floatArrayOf(14f, 10f))),
                )
            },
        contentAlignment = Alignment.Center,
    ) { Glyph(Glyphs.Plus, 40) }
}

@Composable
private fun Hint(key: String, label: String) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.px)) {
        Box(Modifier.clip(RoundedCornerShape(10.px)).background(Color.White).padding(horizontal = 12.px, vertical = 4.px)) {
            androidx.compose.foundation.text.BasicText(
                key,
                style = TextStyle(color = Color(0xFF16132E), fontFamily = Inter, fontWeight = FontWeight.SemiBold, fontSize = 22.pxSp),
            )
        }
        T(label, 24)
    }
}
