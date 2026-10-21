package com.manishlad.glasslauncher.ui

import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.remember
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.Constraints
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.layout.Placeable
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.manishlad.glasslauncher.R

/*
 * The designs are specified in px at 1920×1080. This TV runs at density 320 (2 px per dp),
 * so `132.px` / `132.pxSp` let the code use the design's numbers directly.
 */
val Number.px: Dp get() = (toFloat() / 2f).dp
val Number.pxSp: TextUnit get() = (toFloat() / 2f).sp

val Inter = FontFamily(
    Font(R.font.inter_extralight, FontWeight.ExtraLight),
    Font(R.font.inter_light, FontWeight.Light),
    Font(R.font.inter_regular, FontWeight.Normal),
    Font(R.font.inter_medium, FontWeight.Medium),
    Font(R.font.inter_semibold, FontWeight.SemiBold),
)

/** Text in Inter, white at [alpha]. Sizes and tracking in design px. */
@Composable
fun T(
    text: String,
    size: Number,
    modifier: Modifier = Modifier,
    weight: Int = 400,
    alpha: Float = 1f,
    tracking: Number = 0,
    lineHeight: Number? = null,
    align: TextAlign = TextAlign.Start,
    maxLines: Int = 1,
) {
    BasicText(
        text = text,
        modifier = modifier,
        style = TextStyle(
            color = Color.White.copy(alpha = alpha),
            fontFamily = Inter,
            fontWeight = FontWeight(weight),
            fontSize = size.pxSp,
            letterSpacing = tracking.pxSp,
            lineHeight = lineHeight?.pxSp ?: TextUnit.Unspecified,
            textAlign = align,
        ),
        maxLines = maxLines,
        overflow = TextOverflow.Ellipsis,
    )
}

/**
 * Like [T], but when the text overflows [maxLines] it cuts the middle instead of the end,
 * so both the start and the distinguishing end ("… - Practice 1") stay visible.
 */
@Composable
fun MiddleEllipsisText(
    text: String,
    size: Number,
    modifier: Modifier = Modifier,
    weight: Int = 400,
    alpha: Float = 1f,
    maxLines: Int = 2,
    lineHeight: Number? = null,
) {
    val style = TextStyle(
        color = Color.White.copy(alpha = alpha),
        fontFamily = Inter,
        fontWeight = FontWeight(weight),
        fontSize = size.pxSp,
        lineHeight = lineHeight?.pxSp ?: TextUnit.Unspecified,
    )
    val measurer = rememberTextMeasurer()
    BoxWithConstraints(modifier) {
        val width = constraints.maxWidth
        val shown = remember(text, width, style) { middleEllipsis(text, width, maxLines, style, measurer) }
        BasicText(shown, style = style, maxLines = maxLines, overflow = TextOverflow.Ellipsis)
    }
}

private fun middleEllipsis(text: String, width: Int, maxLines: Int, style: TextStyle, measurer: TextMeasurer): String {
    fun fits(s: String) = !measurer.measure(s, style, maxLines = maxLines, constraints = Constraints(maxWidth = width)).hasVisualOverflow
    if (width <= 0 || fits(text)) return text
    // Keep the part after the last " - " (a session or episode name) when it's short; otherwise the last third.
    val dash = text.lastIndexOf(" - ")
    val tail = if (dash >= 0 && text.length - dash <= 32) text.substring(dash + 1) else text.takeLast(text.length / 3)
    val head = text.dropLast(tail.length).trimEnd()
    var lo = 0
    var hi = head.length
    while (lo < hi) { // longest head prefix that still fits
        val mid = (lo + hi + 1) / 2
        if (fits(head.take(mid).trimEnd() + "… " + tail)) lo = mid else hi = mid - 1
    }
    return head.take(lo).trimEnd() + "… " + tail
}

/** Stacks children with [spacing], laying out only the ones that fit whole in the available height. */
@Composable
fun FitColumn(spacing: Dp, modifier: Modifier = Modifier, content: @Composable () -> Unit) {
    Layout(content, modifier) { measurables, constraints ->
        val gap = spacing.roundToPx()
        val loose = constraints.copy(minWidth = 0, minHeight = 0)
        val placed = ArrayList<Placeable>()
        var height = 0
        for (m in measurables) {
            val p = m.measure(loose)
            val top = if (placed.isEmpty()) 0 else height + gap
            if (constraints.hasBoundedHeight && top + p.height > constraints.maxHeight) break
            placed += p
            height = top + p.height
        }
        val width = placed.maxOfOrNull { it.width }?.coerceAtLeast(constraints.minWidth) ?: constraints.minWidth
        layout(width, height.coerceAtLeast(constraints.minHeight)) {
            var y = 0
            for (p in placed) {
                p.place(0, y)
                y += p.height + gap
            }
        }
    }
}

/** Section label: 20 px, weight 500, uppercase, tracking 1.5, white 72%. */
@Composable
fun SectionLabel(text: String, modifier: Modifier = Modifier, alpha: Float = .72f, size: Number = 20) =
    T(text.uppercase(), size, modifier, weight = 500, alpha = alpha, tracking = 1.5)
