@file:OptIn(ExperimentalTvMaterial3Api::class)
package com.iptv.tv

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.widget.Toast
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.grid.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.tv.material3.ExperimentalTvMaterial3Api
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Locale

// ---------------------------------------------------------------- details page building blocks

/** Anything focusable on the details page: scales up and gets a white ring on focus. [content] receives the focus state. */
@Composable
private fun Focusable(mod: Modifier = Modifier, shape: Shape = RoundedCornerShape(8.dp), onClick: () -> Unit = {}, content: @Composable BoxScope.(Boolean) -> Unit) {
    var foc by remember { mutableStateOf(false) }
    val sc by animateFloatAsState(if (foc) 1.06f else 1f, label = "dfocus")
    Box(
        mod.scale(sc).clip(shape)
            .then(if (foc) Modifier.border(2.dp, Color.White, shape) else Modifier)
            .onFocusChanged { foc = it.isFocused || it.hasFocus }
            .clickable(onClick = onClick)
    ) { content(foc) }
}

@Composable
private fun PlayPill(label: String, mod: Modifier = Modifier, onClick: () -> Unit) {
    Focusable(mod, RoundedCornerShape(50), onClick) { foc ->
        Box(Modifier.background(if (foc) Theme.accent else Color.White).padding(horizontal = 26.dp, vertical = 9.dp), contentAlignment = Alignment.Center) {
            Text(label, fontSize = 14.sp, fontWeight = FontWeight.Bold, maxLines = 1, color = if (foc) Theme.onAccent else Color.Black)
        }
    }
}

@Composable
private fun RoundBtn(glyph: String, label: String, onClick: () -> Unit) {
    Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(3.dp)) {
        Focusable(Modifier, CircleShape, onClick) { foc ->
            Box(Modifier.size(42.dp).background(if (foc) Theme.accent else Color(0x33FFFFFF)), contentAlignment = Alignment.Center) {
                Text(glyph, fontSize = 17.sp, color = if (foc) Theme.onAccent else Color.White)
            }
        }
        Text(label, fontSize = 10.sp, color = Theme.muted, maxLines = 1)
    }
}

@Composable
private fun SectionTitle(t: String) {
    Text(t, Modifier.padding(start = 24.dp, top = 20.dp, bottom = 8.dp), fontSize = 18.sp, fontWeight = FontWeight.Bold)
}

@Composable
private fun RatingBadge(m: Meta) {
    if (m.rating.isBlank()) return
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(5.dp)) {
        when (m.ratingSrc) {
            "IMDb" -> {
                Box(Modifier.background(Color(0xFFF5C518), RoundedCornerShape(3.dp)).padding(horizontal = 4.dp, vertical = 1.dp)) {
                    Text("IMDb", fontSize = 10.sp, fontWeight = FontWeight.Black, color = Color.Black)
                }
                Text(m.rating, fontSize = 14.sp, fontWeight = FontWeight.Bold, color = Color(0xFFF5C518))
            }
            "TMDB" -> {
                Box(Modifier.background(Color(0xFF01B4E4), RoundedCornerShape(3.dp)).padding(horizontal = 4.dp, vertical = 1.dp)) {
                    Text("TMDB", fontSize = 10.sp, fontWeight = FontWeight.Black, color = Color.White)
                }
                Text(m.rating, fontSize = 14.sp, fontWeight = FontWeight.Bold, color = Color(0xFF01B4E4))
            }
            else -> Text("★ ${m.rating}", fontSize = 14.sp, fontWeight = FontWeight.Bold, color = Color(0xFFFFC107))
        }
    }
}

@Composable
private fun CrewLine(label: String, value: String) {
    if (value.isBlank()) return
    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        Text(label, fontSize = 12.sp, fontWeight = FontWeight.Bold, color = Theme.muted)
        Text(value, fontSize = 12.sp, maxLines = 2, overflow = TextOverflow.Ellipsis)
    }
}

@Composable
private fun DetailRow(label: String, value: String) {
    if (value.isBlank()) return
    Row(Modifier.fillMaxWidth().padding(vertical = 9.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(label, fontSize = 13.sp, fontWeight = FontWeight.Bold, color = Theme.muted)
        Spacer(Modifier.weight(1f))
        Text(value, Modifier.widthIn(max = 320.dp), fontSize = 13.sp, maxLines = 2, overflow = TextOverflow.Ellipsis)
    }
    Box(Modifier.fillMaxWidth().height(1.dp).background(Color(0x22FFFFFF)))
}

@Composable
private fun CastCard(p: Person) {
    Focusable(Modifier, RoundedCornerShape(12.dp)) { _ ->
        Column(Modifier.width(92.dp).padding(6.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(3.dp)) {
            Box(Modifier.size(66.dp).clip(CircleShape).background(Theme.card), contentAlignment = Alignment.Center) {
                if (p.img.isBlank()) Text(p.name.take(1).uppercase(), fontSize = 22.sp, fontWeight = FontWeight.Bold, color = Theme.muted)
                else Img(p.img, Modifier.fillMaxSize(), ContentScale.Crop, 185, 185)
            }
            Text(p.name, Modifier.widthIn(max = 80.dp), fontSize = 11.sp, fontWeight = FontWeight.Medium, maxLines = 1, overflow = TextOverflow.Ellipsis)
            if (p.role.isNotBlank()) Text(p.role, Modifier.widthIn(max = 80.dp), fontSize = 10.sp, color = Theme.muted, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
    }
}

@Composable
private fun TrailerCard(t: Trailer, onClick: () -> Unit) {
    Focusable(Modifier, RoundedCornerShape(10.dp), onClick) { _ ->
        Column(Modifier.width(200.dp).background(Theme.card)) {
            Box(Modifier.fillMaxWidth().height(112.dp).background(Color.Black), contentAlignment = Alignment.Center) {
                Img("https://img.youtube.com/vi/${t.key}/hqdefault.jpg", Modifier.fillMaxSize(), ContentScale.Crop, 480, 270)
                Box(Modifier.size(34.dp).background(Color(0x99000000), CircleShape), contentAlignment = Alignment.Center) { Text("▶", fontSize = 15.sp) }
            }
            Text(t.title, Modifier.padding(8.dp), fontSize = 11.sp, fontWeight = FontWeight.Medium, maxLines = 2, overflow = TextOverflow.Ellipsis)
        }
    }
}

/** Opens a YouTube trailer in the YouTube app, or in a browser when the app is missing. */
private fun openTrailer(ctx: Context, key: String) {
    fun go(u: String) = ctx.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(u)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    try { go("vnd.youtube:$key") } catch (e: Exception) {
        try { go("https://www.youtube.com/watch?v=$key") } catch (e2: Exception) {
            Toast.makeText(ctx, tr("No app found to play the trailer"), Toast.LENGTH_SHORT).show()
        }
    }
}

private fun prettyDate(s: String): String {
    if (s.length < 10) return s
    return runCatching {
        SimpleDateFormat("d MMM yyyy", Locale.getDefault()).format(SimpleDateFormat("yyyy-MM-dd", Locale.US).parse(s.take(10))!!)
    }.getOrDefault(s.take(4))
}

// ---------------------------------------------------------------- the details page

@Composable
fun DetailScreen(item: Item, play: (PlayArgs) -> Unit) {
    val ctx = LocalContext.current
    val isSeries = item.kind == "series"
    var meta by remember { mutableStateOf<Meta?>(null) }
    var eps by remember { mutableStateOf<List<Ep>>(emptyList()) }
    var loaded by remember { mutableStateOf(false) }
    var epErr by remember { mutableStateOf("") }
    var retry by remember { mutableStateOf(0) }
    var season by remember { mutableStateOf(-1) }
    var more by remember { mutableStateOf(false) }
    val playFR = remember { FocusRequester() }
    val hv = History.version
    val lastKey = "lastEp_${Store.pid()}_${item.id}"

    LaunchedEffect(Unit) { delay(120); runCatching { playFR.requestFocus() } }
    LaunchedEffect(item) { meta = withContext(Dispatchers.IO) { runCatching { Api.tmdb(item.name, isSeries, item.id) }.getOrNull() } }
    LaunchedEffect(item, retry) {
        if (isSeries) {
            loaded = false
            val r = withContext(Dispatchers.IO) { runCatching { Api.episodes(item.id) } }
            eps = r.getOrDefault(emptyList())
            epErr = r.exceptionOrNull()?.let { it.message ?: it.javaClass.simpleName } ?: (if (eps.isEmpty()) "The provider returned no episodes" else "")
            val last = Store.str(lastKey, "")
            season = eps.firstOrNull { it.id == last }?.season ?: eps.firstOrNull()?.season ?: 0
            loaded = true
        }
    }

    val queue = remember(eps) {
        eps.map { Media("e${it.id}", Api.epUrl(it.id, it.ext), "${item.name} S${it.season}E${it.num}", item.img, "series", item.id) }
    }
    val watched = remember(hv) { History.all().filter { it.pos > 0 }.map { it.key }.toSet() }
    val movie = remember(item) { Media("m${item.id}", Api.vodUrl(item.id, item.ext), item.name, item.img, "movie") }
    val m = meta
    val hist = remember(hv) { History.get(movie.key) }
    val playLabel: String
    val onPlay: () -> Unit
    if (!isSeries) {
        playLabel = if (hist != null && hist.pos > 5000) "▶  Resume ${fmtTime(hist.pos)}" else "▶  Play"
        onPlay = { play(PlayArgs(vod = listOf(movie))) }
    } else {
        val last = Store.str(lastKey, "")
        val li = queue.indexOfFirst { it.key == "e$last" }
        playLabel = if (li >= 0) "▶  Continue ${queue[li].title.substringAfterLast(' ')}" else "▶  Play"
        onPlay = {
            if (queue.isNotEmpty()) {
                val idx = if (li >= 0) li else 0
                Store.put(lastKey, queue[idx].key.removePrefix("e"))
                play(PlayArgs(vod = queue, idx = idx))
            }
        }
    }

    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = 48.dp)) {
        // ---- hero: backdrop, title / logo, genres, Play + round buttons
        item {
            Box(Modifier.fillMaxWidth().height(300.dp)) {
                val bd = m?.backdrop?.takeIf { it.isNotBlank() }
                Img(bd ?: item.img, Modifier.fillMaxSize().alpha(if (bd != null) 1f else 0.22f), ContentScale.Crop, 1280, 720)
                Box(Modifier.fillMaxSize().background(Brush.horizontalGradient(listOf(Theme.bg.copy(alpha = 0.92f), Theme.bg.copy(alpha = 0.35f), Color.Transparent))))
                Box(Modifier.fillMaxSize().background(Brush.verticalGradient(0.45f to Color.Transparent, 1f to Theme.bg)))
                Column(Modifier.align(Alignment.BottomStart).padding(start = 24.dp, end = 24.dp, bottom = 6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    if (m != null && m.logo.isNotBlank()) Img(m.logo, Modifier.height(62.dp).width(260.dp), ContentScale.Fit, 600, 200, Alignment.CenterStart)
                    else Text(item.name, Modifier.widthIn(max = 520.dp), fontSize = 26.sp, fontWeight = FontWeight.Black, maxLines = 2, overflow = TextOverflow.Ellipsis)
                    if (m != null && m.genres.isNotBlank()) Text(m.genres, fontSize = 12.sp, color = Theme.muted, maxLines = 1)
                    Spacer(Modifier.height(2.dp))
                    PlayPill(playLabel, Modifier.width(260.dp).focusRequester(playFR), onPlay)
                    Row(horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                        val fav = Store.favs.contains(item.fkey)
                        RoundBtn(if (fav) "★" else "☆", if (fav) "Favorited" else "Favorite") { Store.fav(item) }
                        val tr0 = m?.trailers?.firstOrNull()
                        if (tr0 != null) RoundBtn("▶", "Trailer") { openTrailer(ctx, tr0.key) }
                        if (!isSeries && hist != null && hist.pos > 5000) RoundBtn("↺", "Restart") { History.remove(movie.key); play(PlayArgs(vod = listOf(movie))) }
                    }
                }
            }
        }

        // ---- year, runtime, age rating, score, crew, overview
        item {
            Column(Modifier.padding(horizontal = 24.dp).widthIn(max = 680.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                if (m == null) Text("Loading details...", fontSize = 12.sp, color = Theme.muted)
                else {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        if (m.year.isNotBlank()) Text(m.year, fontSize = 15.sp, fontWeight = FontWeight.Bold)
                        if (m.runtime.isNotBlank()) Text(m.runtime, fontSize = 15.sp, fontWeight = FontWeight.Bold)
                        if (m.cert.isNotBlank()) Box(Modifier.border(1.dp, Color.White.copy(alpha = 0.8f), RoundedCornerShape(4.dp)).padding(horizontal = 6.dp, vertical = 1.dp)) {
                            Text(m.cert, fontSize = 11.sp, fontWeight = FontWeight.Bold)
                        }
                        RatingBadge(m)
                    }
                    CrewLine(if (isSeries) "Creator:" else "Director:", m.director)
                    CrewLine("Writer:", m.writer)
                    Text(m.overview, fontSize = 13.sp, maxLines = if (more) Int.MAX_VALUE else 3, overflow = TextOverflow.Ellipsis)
                    if (m.overview.length > 170) {
                        Focusable(Modifier, RoundedCornerShape(6.dp), { more = !more }) { foc ->
                            Text(if (more) "Show Less ▴" else "Show More ▾", Modifier.padding(horizontal = 6.dp, vertical = 3.dp), fontSize = 12.sp, color = if (foc) Theme.accent else Theme.muted)
                        }
                    }
                    if (m.note.isNotBlank() && m.rating.isBlank()) Text(m.note, fontSize = 10.sp, color = Theme.muted)
                }
            }
        }

        // ---- series: seasons and episodes
        if (isSeries) {
            item {
                Column(Modifier.padding(horizontal = 24.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Spacer(Modifier.height(10.dp))
                    if (!loaded) Text("Loading...", fontSize = 12.sp)
                    else if (eps.isEmpty()) {
                        Text("Could not load episodes: $epErr", fontSize = 12.sp, color = Color(0xFFFF6B6B))
                        Chip("Retry", true) { retry++ }
                    } else LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        items(eps.map { it.season }.distinct()) { s -> Chip("Season $s", s == season) { season = s } }
                    }
                }
            }
            if (loaded && eps.isNotEmpty()) {
                items(eps.filter { it.season == season }, key = { it.id }) { e ->
                    val mark = if ("e${e.id}" in watched) "  ●" else ""
                    Box(Modifier.padding(horizontal = 24.dp, vertical = 2.dp)) {
                        Chip("E${e.num}  ${e.title}$mark", false, Modifier.fillMaxWidth()) {
                            val idx = queue.indexOfFirst { it.key == "e${e.id}" }
                            if (idx >= 0) {
                                Store.put(lastKey, e.id)
                                play(PlayArgs(vod = queue, idx = idx))
                            }
                        }
                    }
                }
            }
        }

        // ---- cast
        if (m != null && m.people.isNotEmpty()) {
            item {
                Column {
                    SectionTitle("Cast")
                    LazyRow(contentPadding = PaddingValues(horizontal = 18.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        items(m.people) { p -> CastCard(p) }
                    }
                }
            }
        }

        // ---- trailers
        if (m != null && m.trailers.isNotEmpty()) {
            item {
                Column {
                    SectionTitle("Trailers")
                    LazyRow(contentPadding = PaddingValues(horizontal = 24.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        items(m.trailers) { t -> TrailerCard(t) { openTrailer(ctx, t.key) } }
                    }
                }
            }
        }

        // ---- details table
        if (m != null) {
            item {
                Column {
                    SectionTitle(if (isSeries) "Series Details" else "Movie Details")
                    Column(Modifier.padding(horizontal = 24.dp).widthIn(max = 680.dp)) {
                        DetailRow("Status", m.status)
                        DetailRow("Release Info", if (m.release.isNotBlank()) prettyDate(m.release) else m.year)
                        DetailRow("Runtime", m.runtime)
                        DetailRow("Certification", m.cert)
                        DetailRow("Origin Country", m.country)
                    }
                }
            }
        }
    }
}

@Composable
fun SearchScreen(open: (Item) -> Unit, play: (List<Item>, Int) -> Unit) {
    var q by remember { mutableStateOf("") }
    var films by remember { mutableStateOf(listOf<Item>()) }
    var sers by remember { mutableStateOf(listOf<Item>()) }
    var chans by remember { mutableStateOf(listOf<Item>()) }
    var menu by remember { mutableStateOf<MenuState?>(null) }
    val fr = remember { FocusRequester() }
    LaunchedEffect(Unit) { delay(150); runCatching { fr.requestFocus() } }
    LaunchedEffect(q) {
        val k = nz(q.trim())
        if (k.length < 2) { films = emptyList(); sers = emptyList(); chans = emptyList(); return@LaunchedEffect }
        delay(250)
        val r = withContext(Dispatchers.Default) {
            Triple(
                Repo.movies.filter { it.nn.contains(k) }.take(60),
                Repo.series.filter { it.nn.contains(k) }.take(60),
                (Repo.live + Repo.radio).filter { it.nn.contains(k) }.take(60)
            )
        }
        films = r.first; sers = r.second; chans = r.third
    }
    Column(Modifier.fillMaxSize()) {
        Box(Modifier.padding(horizontal = 24.dp, vertical = 8.dp)) {
            Field("Search films, series, channels", q, false, Modifier.focusRequester(fr)) { q = it }
        }
        LazyVerticalGrid(
            GridCells.Adaptive(120.dp), Modifier.weight(1f), contentPadding = PaddingValues(16.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp), verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            if (films.isNotEmpty()) {
                item(span = { GridItemSpan(maxLineSpan) }) { Text("Movies", fontSize = 14.sp, fontWeight = FontWeight.Bold) }
                items(films) { m -> PosterCard(m.name, m.year, m.img, Store.favs.contains(m.fkey), 0f, true, "", Modifier, { open(m) }, { menu = MenuState(m.name, listOf(MenuAction("ⓘ Details") { open(m) }, favAction(m))) }) }
            }
            if (sers.isNotEmpty()) {
                item(span = { GridItemSpan(maxLineSpan) }) { Text("Series", fontSize = 14.sp, fontWeight = FontWeight.Bold) }
                items(sers) { m -> PosterCard(m.name, m.year, m.img, Store.favs.contains(m.fkey), 0f, true, "", Modifier, { open(m) }, { menu = MenuState(m.name, listOf(MenuAction("ⓘ Details") { open(m) }, favAction(m))) }) }
            }
            if (chans.isNotEmpty()) {
                item(span = { GridItemSpan(maxLineSpan) }) { Text("Channels", fontSize = 14.sp, fontWeight = FontWeight.Bold) }
                itemsIndexed(chans, span = { _, _ -> GridItemSpan(2) }) { i, c ->
                    LiveCard(ChNum.num(c, i), c.name, c.img, Store.favs.contains(c.fkey), Modifier, { play(chans, i) }, { menu = MenuState(c.name, listOf(MenuAction("▶  Play") { play(chans, i) }, favAction(c))) })
                }
            }
        }
        menu?.let { mm -> OptionsDialog(mm.title, mm.actions) { menu = null } }
    }
}
