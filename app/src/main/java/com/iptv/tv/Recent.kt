@file:OptIn(ExperimentalTvMaterial3Api::class)
package com.iptv.tv

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.compositeOver
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.compose.ui.window.DialogWindowProvider
import androidx.tv.material3.Button
import androidx.tv.material3.ButtonDefaults
import androidx.tv.material3.ExperimentalTvMaterial3Api
import kotlinx.coroutines.delay

/** The last channels watched on the active playlist (newest first, at most 10). */
object Recent {
    private const val MAX = 10
    private fun key() = "recent_${Store.pid()}"
    fun keys(): List<String> = Store.str(key(), "").split(",").filter { it.isNotBlank() }
    fun push(fkey: String) {
        val l = (listOf(fkey) + keys().filter { it != fkey }).take(MAX)
        Store.put(key(), l.joinToString(","))
    }
    /** The channels behind [keys], skipping any that are no longer in the library. */
    fun items(): List<Item> {
        val ks = keys()
        if (ks.isEmpty()) return emptyList()
        val all = HashMap<String, Item>()
        (Repo.live + Repo.radio).forEach { all.putIfAbsent(it.fkey, it) }
        return ks.mapNotNull { all[it] }
    }
    fun find(fkey: String): Item? = items().firstOrNull { it.fkey == fkey }
}

/** One colour per playlist, shown as a glossy dot so you always know which playlist a channel comes from. */
object PlColor {
    val palette = listOf(
        0xFFE53935, 0xFF43A047, 0xFF1E88E5, 0xFFFFB300, 0xFF8E24AA, 0xFF00ACC1, 0xFFF4511E, 0xFFEC407A
    ).map { Color(it) }

    fun idx(id: String): Int {
        val k = "plc_$id"
        if (Store.sp.contains(k)) return Store.int(k, 0).coerceIn(0, palette.size - 1)
        // First time: the first colour no other playlist uses yet.
        val used = Store.pls().filter { it.id != id }.mapNotNull { p -> Store.sp.getInt("plc_${p.id}", -1).takeIf { it >= 0 } }.toSet()
        val n = palette.indices.firstOrNull { it !in used } ?: (Store.pls().indexOfFirst { it.id == id }.coerceAtLeast(0) % palette.size)
        Store.put(k, n)
        return n
    }
    fun of(id: String): Color = palette[idx(id)]
    fun next(id: String) { Store.put("plc_$id", (idx(id) + 1) % palette.size) }
}

/** Round, glossy colour dot (bright spot on top, darker rim, small shine). */
@Composable
fun PlDot(color: Color, size: androidx.compose.ui.unit.Dp = 12.dp, modifier: Modifier = Modifier) {
    Canvas(modifier.size(size)) {
        val w = this.size.width
        val r = w / 2f
        drawCircle(
            Brush.radialGradient(
                0f to Color.White, 0.30f to color, 1f to Color(0xFF000000).copy(alpha = 0.45f).compositeOver(color),
                center = androidx.compose.ui.geometry.Offset(w * 0.36f, w * 0.30f), radius = w * 0.85f
            ), r
        )
        drawCircle(Color.White.copy(alpha = 0.75f), r * 0.20f, androidx.compose.ui.geometry.Offset(w * 0.34f, w * 0.28f))
    }
}

/** Playlist button with its colour dot (used under the category lists). */
@Composable
fun PlChip(name: String, dot: Color, modifier: Modifier = Modifier, onClick: () -> Unit) {
    Button(
        onClick = onClick, modifier = modifier,
        colors = focusColors(Theme.chip),
        contentPadding = PaddingValues(horizontal = 8.dp, vertical = 6.dp)
    ) {
        PlDot(dot, 11.dp)
        Spacer(Modifier.width(7.dp))
        Text(name, Modifier.weight(1f), fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

/**
 * The last 10 channels as a see-through list on the left of the picture. Pick one to watch it again.
 * It is a dialog window so the D-pad stays inside it; Back closes it.
 */
@Composable
fun RecentDialog(items: List<Item>, curKey: String?, onPick: (Item) -> Unit, onAll: () -> Unit, onDismiss: () -> Unit) {
    val fr = remember { FocusRequester() }
    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        // No dimming, pinned to the left edge: the picture stays fully visible behind the list.
        val view = LocalView.current
        SideEffect {
            (view.parent as? DialogWindowProvider)?.window?.apply {
                setDimAmount(0f)
                setBackgroundDrawableResource(android.R.color.transparent)
            }
        }
        LaunchedEffect(Unit) { delay(60); runCatching { fr.requestFocus() } }
        val first = items.indexOfFirst { it.fkey != curKey }.coerceAtLeast(0)
        Box(Modifier.fillMaxSize(), Alignment.CenterStart) {
        Column(
            Modifier.width(330.dp).padding(start = 20.dp)
                .background(Color(0x99000000), RoundedCornerShape(16.dp)).padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(5.dp)
        ) {
            Text("Recent channels", Modifier.padding(start = 6.dp, bottom = 4.dp), fontSize = 14.sp, fontWeight = FontWeight.Bold)
            if (items.isEmpty()) Text("No recent channels yet", Modifier.padding(6.dp), fontSize = 12.sp, color = Color.LightGray)
            LazyColumn(Modifier.heightIn(max = 400.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                itemsIndexed(items, key = { _, c -> c.fkey }) { i, c ->
                    val now = c.fkey == curKey
                    Button(
                        onClick = { onDismiss(); onPick(c) },
                        modifier = Modifier.fillMaxWidth().then(if (i == first) Modifier.focusRequester(fr) else Modifier),
                        colors = ButtonDefaults.colors(
                            containerColor = if (now) Theme.accent.copy(alpha = 0.35f) else Color(0x66000000), contentColor = Color.White,
                            focusedContainerColor = Theme.accent, focusedContentColor = Color.White,
                            pressedContainerColor = Theme.accent, pressedContentColor = Color.White
                        ),
                        contentPadding = PaddingValues(horizontal = 10.dp, vertical = 6.dp)
                    ) {
                        Text("${i + 1}", Modifier.width(22.dp), fontSize = 11.sp, color = Color.LightGray)
                        if (c.img.isNotBlank()) Img(c.img, Modifier.size(26.dp), ContentScale.Fit, 80, 80) else Spacer(Modifier.size(26.dp))
                        Spacer(Modifier.width(8.dp))
                        Text(c.name, Modifier.weight(1f), fontSize = 13.sp, maxLines = 1, overflow = TextOverflow.Ellipsis,
                            fontWeight = if (now) FontWeight.Bold else FontWeight.Normal)
                        if (now) Text("▶", fontSize = 11.sp)
                    }
                }
            }
            Button(
                onClick = { onDismiss(); onAll() },
                modifier = Modifier.fillMaxWidth().then(if (items.isEmpty()) Modifier.focusRequester(fr) else Modifier),
                colors = ButtonDefaults.colors(
                    containerColor = Color(0x66000000), contentColor = Color.White,
                    focusedContainerColor = Theme.accent, focusedContentColor = Color.White,
                    pressedContainerColor = Theme.accent, pressedContentColor = Color.White
                ),
                contentPadding = PaddingValues(horizontal = 10.dp, vertical = 6.dp)
            ) { Text("☰  All channels", fontSize = 13.sp) }
        }
        }
    }
}
