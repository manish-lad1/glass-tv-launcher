package com.manishlad.glasslauncher.ui

import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.addPathNodes
import androidx.compose.ui.graphics.vector.rememberVectorPainter
import androidx.compose.ui.unit.dp

/** 24×24 stroked line icons from the design (stroke 1.6, white). */
object Glyphs {
    val Moon = stroke("M20 14.5A8 8 0 1 1 9.5 4a6.5 6.5 0 0 0 10.5 10.5z")
    val Wifi = stroke(
        "M2 8.5a15 15 0 0 1 20 0", "M5 12a10 10 0 0 1 14 0", "M8.5 15.5a5 5 0 0 1 7 0",
        fill = listOf("M11 19a1 1 0 1 0 2 0a1 1 0 1 0 -2 0z"),
    )
    val Grid = stroke(rr(4f, 4f), rr(14f, 4f), rr(4f, 14f), rr(14f, 14f))

    val Sun = stroke(
        "M12 8a4 4 0 1 0 0 8a4 4 0 1 0 0-8z",
        "M12 2.5v2M12 19.5v2M2.5 12h2M19.5 12h2M5.3 5.3l1.4 1.4M17.3 17.3l1.4 1.4M5.3 18.7l1.4-1.4M17.3 6.7l1.4-1.4",
    )
    private const val CLOUD = "M7 19h10.5a4.5 4.5 0 0 0 .4-8.98A6.5 6.5 0 0 0 5.5 11.5 3.75 3.75 0 0 0 7 19z"
    private const val CLOUD_HIGH = "M7 15h10.5a4.5 4.5 0 0 0 .4-8.98A6.5 6.5 0 0 0 5.5 7.5 3.75 3.75 0 0 0 7 15z"
    val Cloud = stroke(CLOUD)
    val PartlyCloudy = stroke(
        "M8 2.5v1.5M2.5 8H4M4.1 4.1l1 1M11.9 4.1l-1 1M5.3 10.3A3 3 0 0 1 10.4 6.6",
        "M9.5 20.5h8a3.5 3.5 0 0 0 .3-6.99A5 5 0 0 0 8.7 14.6 3 3 0 0 0 9.5 20.5z",
    )
    val Rain = stroke(CLOUD_HIGH, "M8.5 18l-1 3M12.5 18l-1 3M16.5 18l-1 3")
    val Thunder = stroke(CLOUD_HIGH, "M12.5 16.5l-2 3h3l-2 3")
    val Fog = stroke("M4 8h16M6 12h12M4 16h16M7 20h10")
    val Snow = stroke(CLOUD_HIGH, "M8 18.5h.01M12 18.5h.01M16 18.5h.01M10 21.5h.01M14 21.5h.01")
    val Play = stroke(fill = listOf("M8 5.5v13l10.5-6.5z"))
    val Plus = stroke("M12 5v14M5 12h14")
    val Eye = stroke("M2.5 12s3.5-6.5 9.5-6.5 9.5 6.5 9.5 6.5-3.5 6.5-9.5 6.5S2.5 12 2.5 12z", "M12 9a3 3 0 1 0 0 6a3 3 0 1 0 0-6z")
    val EyeOff = stroke(
        "M2.5 12s3.5-6.5 9.5-6.5 9.5 6.5 9.5 6.5-3.5 6.5-9.5 6.5S2.5 12 2.5 12z",
        "M12 9a3 3 0 1 0 0 6a3 3 0 1 0 0-6z",
        "M4 4l16 16",
    )
    val Headphones = stroke("M4 15v-3a8 8 0 0 1 16 0v3", "M4 14.5h3v6H5.2A1.2 1.2 0 0 1 4 19.3z", "M20 14.5h-3v6h1.8a1.2 1.2 0 0 0 1.2-1.2z")
    val Phone = stroke("M8 2.5h8a1.5 1.5 0 0 1 1.5 1.5v16a1.5 1.5 0 0 1-1.5 1.5H8A1.5 1.5 0 0 1 6.5 20V4A1.5 1.5 0 0 1 8 2.5z", "M11 18.5h2")
    val Lock = stroke("M8 11V8a4 4 0 0 1 8 0v3", "M6 11h12v9.5H6z")
    val Check = stroke("M5 12.5l4.5 4.5L19 7.5")
    val Chevron = stroke("M9.5 6l6 6-6 6")
    val Input = stroke("M3 6.5h12v11H3z", "M15 12h6M18 9l3 3-3 3")
    val PlayOutline = stroke("M8 5.5v13l10.5-6.5z")

    private fun rr(x: Float, y: Float, s: Float = 6f, r: Float = 1.5f) =
        "M${x + r} ${y}h${s - 2 * r}a$r $r 0 0 1 $r ${r}v${s - 2 * r}a$r $r 0 0 1 -$r ${r}h-${s - 2 * r}a$r $r 0 0 1 -$r -${r}v-${s - 2 * r}a$r $r 0 0 1 $r -${r}z"

    private fun stroke(vararg paths: String, fill: List<String> = emptyList()): ImageVector =
        ImageVector.Builder(defaultWidth = 24.dp, defaultHeight = 24.dp, viewportWidth = 24f, viewportHeight = 24f)
            .apply {
                for (p in paths) addPath(
                    pathData = addPathNodes(p),
                    stroke = SolidColor(Color.White),
                    strokeLineWidth = 1.6f,
                    strokeLineCap = StrokeCap.Round,
                    strokeLineJoin = StrokeJoin.Round,
                )
                for (p in fill) addPath(pathData = addPathNodes(p), fill = SolidColor(Color.White))
            }
            .build()
}

@Composable
fun Glyph(icon: ImageVector, sizePx: Number, modifier: Modifier = Modifier) =
    Image(rememberVectorPainter(icon), contentDescription = null, modifier = modifier.size(sizePx.px))
