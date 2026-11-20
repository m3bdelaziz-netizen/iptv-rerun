@file:OptIn(ExperimentalTvMaterial3Api::class)
package com.iptv.tv

import android.graphics.Typeface
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.tv.material3.ExperimentalTvMaterial3Api

/*
 * The three main sections: Movies, Series, Live.
 * Movies and Series are rounded tiles with an icon and a heavy font and take the accent colour when selected.
 * Live is deliberately different: a full pill, always red, with a broadcast dot and an italic condensed LIVE label.
 */

private val NavFont = FontFamily(Typeface.create("sans-serif-black", Typeface.NORMAL))
private val LiveFont = FontFamily(Typeface.create("sans-serif-condensed", Typeface.BOLD_ITALIC))
private val LiveRed = Color(0xFFE53935)
private val LiveRedDark = Color(0xFFB71C1C)

/** [sel]: 0 Movies, 1 Series, 2 Live. */
@Composable
fun MainNav(sel: Int, onTab: (Int) -> Unit) {
    Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
        NavTile(0, "Movies", sel == 0) { onTab(0) }
        NavTile(1, "Series", sel == 1) { onTab(1) }
        LiveTile(sel == 2) { onTab(2) }
    }
}

private fun DrawScope.drawNavIcon(kind: Int, c: Color) {
    val w = size.width
    val h = size.height
    val st = Stroke(width = w * 0.11f)
    when (kind) {
        0 -> {   // Movies: screen with a play triangle
            drawRoundRect(c, Offset(0f, h * 0.10f), Size(w, h * 0.80f), CornerRadius(w * 0.18f), style = st)
            drawPath(Path().apply { moveTo(w * 0.40f, h * 0.30f); lineTo(w * 0.40f, h * 0.70f); lineTo(w * 0.72f, h * 0.50f); close() }, c)
        }
        else -> {   // Series: stacked screens
            drawRoundRect(c.copy(alpha = 0.55f), Offset(w * 0.20f, 0f), Size(w * 0.80f, h * 0.62f), CornerRadius(w * 0.12f), style = st)
            drawRoundRect(c, Offset(0f, h * 0.32f), Size(w * 0.80f, h * 0.62f), CornerRadius(w * 0.12f), style = st)
            drawPath(Path().apply { moveTo(w * 0.28f, h * 0.50f); lineTo(w * 0.28f, h * 0.76f); lineTo(w * 0.50f, h * 0.63f); close() }, c)
        }
    }
}

@Composable
private fun NavTile(kind: Int, label: String, sel: Boolean, onClick: () -> Unit) {
    var foc by remember { mutableStateOf(false) }
    val sc by animateFloatAsState(if (foc) 1.08f else 1f, label = "nav")
    val shape = RoundedCornerShape(14.dp)
    val bg = if (sel) Brush.horizontalGradient(listOf(Theme.accent, Theme.accent.copy(alpha = 0.72f)))
             else Brush.horizontalGradient(listOf(Color(0x40FFFFFF), Color(0x1AFFFFFF)))
    val fg = if (sel) Theme.onAccent else Color.White
    Row(
        Modifier.scale(sc).height(42.dp).clip(shape).background(bg, shape)
            .border(if (foc) 2.5.dp else 1.dp, if (foc) Color.White else Color(0x55FFFFFF), shape)
            .onFocusChanged { foc = it.isFocused || it.hasFocus }
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        Canvas(Modifier.size(20.dp)) { drawNavIcon(kind, fg) }
        androidx.tv.material3.Text(tr(label), fontFamily = NavFont, fontSize = 17.sp,
            letterSpacing = if (Lang.ar) 0.sp else 0.4.sp, maxLines = 1, color = fg)
    }
}

@Composable
private fun LiveTile(sel: Boolean, onClick: () -> Unit) {
    var foc by remember { mutableStateOf(false) }
    val sc by animateFloatAsState(if (foc) 1.08f else 1f, label = "live")
    val shape = RoundedCornerShape(50)
    val bg = if (sel) Brush.horizontalGradient(listOf(LiveRed, LiveRedDark))
             else Brush.horizontalGradient(listOf(Color(0xCC1E080B), Color(0xCC300C11)))
    val dot = if (sel) Color.White else LiveRed
    Row(
        Modifier.scale(sc).height(42.dp).clip(shape).background(bg, shape)
            .border(if (foc) 2.5.dp else 1.5.dp, if (foc) Color.White else LiveRed, shape)
            .onFocusChanged { foc = it.isFocused || it.hasFocus }
            .clickable(onClick = onClick)
            .padding(start = 14.dp, end = 20.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        Canvas(Modifier.size(18.dp)) {
            val r = size.minDimension / 2f
            drawCircle(dot.copy(alpha = 0.30f), r)
            drawCircle(dot, r * 0.50f)
        }
        androidx.tv.material3.Text(tr("Live").uppercase(), fontFamily = if (Lang.ar) NavFont else LiveFont,
            fontStyle = if (Lang.ar) FontStyle.Normal else FontStyle.Italic,
            fontSize = 19.sp, letterSpacing = if (Lang.ar) 0.sp else 2.sp, maxLines = 1, color = Color.White)
    }
}
