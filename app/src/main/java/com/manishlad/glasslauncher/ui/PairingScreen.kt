package com.manishlad.glasslauncher.ui

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.text.style.TextAlign
import com.google.zxing.BarcodeFormat
import com.google.zxing.EncodeHintType
import com.google.zxing.qrcode.QRCodeWriter
import com.manishlad.glasslauncher.companion.COMPANION_PORT
import com.manishlad.glasslauncher.companion.PairingState
import com.manishlad.glasslauncher.companion.localIpv4
import kotlinx.coroutines.delay

/** QR code as a bitmap: one pixel per module (scaled up without smoothing when drawn). */
private fun qr(text: String): ImageBitmap {
    val m = QRCodeWriter().encode(text, BarcodeFormat.QR_CODE, 0, 0, mapOf(EncodeHintType.MARGIN to 0))
    val px = IntArray(m.width * m.height) { i -> if (m[i % m.width, i / m.width]) 0xFF16132E.toInt() else -1 }
    return android.graphics.Bitmap.createBitmap(px, m.width, m.height, android.graphics.Bitmap.Config.ARGB_8888).asImageBitmap()
}

/**
 * "Set up from your phone": QR + 4-digit code (5-min expiry, home network only).
 * The QR opens the companion page with the code filled in; the phone still confirms it.
 */
@Composable
fun PairingScreen(state: PairingState, onNewCode: () -> Unit, onDone: () -> Unit) {
    val ip = remember { localIpv4().firstOrNull() ?: "this TV" }
    val now by produceState(System.currentTimeMillis()) { while (true) { value = System.currentTimeMillis(); delay(1000) } }
    val focus = remember { FocusRequester() }
    LaunchedEffect(Unit) { runCatching { focus.requestFocus() } }
    LaunchedEffect(state) { if (state is PairingState.Paired) { delay(2500); onDone() } }

    val waiting = state as? PairingState.Waiting
    val expired = waiting != null && now > waiting.expiresAt
    Box(
        Modifier.fillMaxSize().focusRequester(focus).onSelect(onClick = { if (expired || state is PairingState.Idle) onNewCode() }).focusable(),
        contentAlignment = Alignment.Center,
    ) {
        GlassSurface(Modifier.width(1320.px).height(680.px), radius = 40.px) {
            Row(Modifier.fillMaxSize().padding(horizontal = 64.px, vertical = 56.px), horizontalArrangement = Arrangement.spacedBy(64.px), verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.size(380.px).clip(RoundedCornerShape(36.px)).background(Color.White).padding(30.px), contentAlignment = Alignment.Center) {
                    if (waiting != null && !expired) {
                        val bmp = remember(waiting.code, ip) { qr("http://$ip:$COMPANION_PORT/#code=${waiting.code}") }
                        Image(bmp, contentDescription = "Pairing QR code", modifier = Modifier.fillMaxSize(), filterQuality = FilterQuality.None)
                    }
                }
                Column(verticalArrangement = Arrangement.spacedBy(0.px)) {
                    when {
                        state is PairingState.Paired -> {
                            T("Paired", 64, weight = 300, tracking = -1.5)
                            Spacer(Modifier.height(16.px))
                            T("Your phone can now manage the TV. You won't need to pair it again.", 26, alpha = .8f, maxLines = 2, lineHeight = 34)
                        }
                        else -> {
                            T("Set up from your phone", 64, weight = 300, tracking = -1.5)
                            Spacer(Modifier.height(16.px))
                            T("Reorder the dock, add shows and upload wallpapers without typing on the TV.", 26, alpha = .8f, maxLines = 2, lineHeight = 34)
                            Spacer(Modifier.height(40.px))
                            Step(1, "Scan the code with your phone camera")
                            Spacer(Modifier.height(30.px))
                            Step(2, "Enter this code on the page that opens")
                            Spacer(Modifier.height(30.px))
                            Row(Modifier.padding(start = 58.px), horizontalArrangement = Arrangement.spacedBy(14.px)) {
                                for (ch in (waiting?.code ?: "----")) {
                                    GlassSurface(Modifier.size(86.px, 106.px), radius = 18.px, focus = .5f, focusScale = 1f, contentAlignment = Alignment.Center) {
                                        T(if (expired) "·" else ch.toString(), 64, weight = 300, alpha = if (expired) .4f else 1f)
                                    }
                                }
                            }
                            Spacer(Modifier.height(36.px))
                            val left = ((waiting?.expiresAt ?: now) - now).coerceAtLeast(0) / 1000
                            val first = if (expired) "Code expired · press OK for a new one" else "Expires in ${left / 60}:%02d".format(left % 60)
                            T("$first   ·   Works only on your home Wi-Fi", 22, Modifier.padding(start = 58.px), alpha = .75f)
                            Spacer(Modifier.height(10.px))
                            T("Or open $ip:$COMPANION_PORT", 22, Modifier.padding(start = 58.px), alpha = .75f)
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun Step(n: Int, text: String) {
    Row(horizontalArrangement = Arrangement.spacedBy(18.px), verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(40.px).clip(CircleShape).background(Color.White.copy(alpha = .9f)), contentAlignment = Alignment.Center) {
            androidx.compose.foundation.text.BasicText(
                n.toString(),
                style = androidx.compose.ui.text.TextStyle(
                    color = Color(0xFF16132E), fontFamily = Inter, fontSize = 20.pxSp,
                    fontWeight = androidx.compose.ui.text.font.FontWeight.SemiBold, textAlign = TextAlign.Center,
                ),
            )
        }
        T(text, 28)
    }
}
