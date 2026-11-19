package com.iptv.tv

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/** The only thing shown while the library loads: a smooth accent spinner and one line of text. */
@Composable
fun LoadingScreen(slow: Boolean, onPlaylists: () -> Unit, onRetry: () -> Unit) {
    val t = rememberInfiniteTransition(label = "loading")
    val angle by t.animateFloat(0f, 360f, infiniteRepeatable(tween(1100, easing = LinearEasing)), label = "angle")
    val pulse by t.animateFloat(0.45f, 1f, infiniteRepeatable(tween(900), RepeatMode.Reverse), label = "pulse")
    Box(
        Modifier.fillMaxSize().background(Brush.radialGradient(listOf(Theme.panel, Theme.bg))),
        Alignment.Center
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(18.dp)) {
            Canvas(Modifier.size(76.dp)) {
                val sw = 7.dp.toPx()
                val box = Size(size.width - sw, size.height - sw)
                val tl = Offset(sw / 2, sw / 2)
                drawArc(Color(0x22FFFFFF), 0f, 360f, false, tl, box, style = Stroke(sw))
                drawArc(Theme.accent, angle, 110f, false, tl, box, style = Stroke(sw, cap = StrokeCap.Round))
            }
            Text("Loading your library", fontSize = 20.sp, fontWeight = FontWeight.Bold)
            Text(if (slow) "Taking longer than usual..." else "Just a moment", Modifier.alpha(pulse), fontSize = 12.sp, color = Theme.muted)
            if (slow) Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Chip("Retry", true) { onRetry() }
                Chip("Playlists", false) { onPlaylists() }
            }
        }
    }
}
