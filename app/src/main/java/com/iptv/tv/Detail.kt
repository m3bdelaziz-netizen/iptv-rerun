@file:OptIn(ExperimentalTvMaterial3Api::class)
package com.iptv.tv

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
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.rememberScrollState
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
    val white = Brush.verticalGradient(listOf(Color.White, Color(0xFFD5DAE6)))
    Focusable(mod, RoundedCornerShape(50), onClick) { foc ->
        Box(Modifier.background(if (foc) Theme.gloss else white).padding(horizontal = 26.dp, vertical = 10.dp), contentAlignment = Alignment.Center) {
            Text(label, fontSize = 14.sp, fontWeight = FontWeight.Bold, maxLines = 1, color = if (foc) Theme.onAccent else Color.Black)
        }
    }
}

@Composable
private fun RoundBtn(glyph: String, label: String, onClick: () -> Unit) {
    Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(3.dp)) {
        Focusable(Modifier, CircleShape, onClick) { foc ->
            Box(Modifier.size(40.dp).background(if (foc) Theme.gloss else Brush.verticalGradient(listOf(Color(0x44FFFFFF), Color(0x22FFFFFF)))), contentAlignment = Alignment.Center) {
                Text(glyph, fontSize = 17.sp, color = if (foc) Theme.onAccent else Color.White)
            }
        }
        Text(label, fontSize = 10.sp, color = Theme.muted, maxLines = 1)
    }
}

@Composable
private fun SectionTitle(t: String, start: androidx.compose.ui.unit.Dp = 24.dp) {
    Text(t, Modifier.padding(start = start, top = 14.dp, bottom = 6.dp), fontSize = 16.sp, fontWeight = FontWeight.Bold)
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
private fun CrewLine(label: String, value: String, lines: Int = 2) {
    if (value.isBlank()) return
    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        Text(label, fontSize = 12.sp, fontWeight = FontWeight.Bold, color = Theme.muted)
        Text(value, Modifier.weight(1f, fill = false), fontSize = 12.sp, maxLines = lines, overflow = TextOverflow.Ellipsis)
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
private fun ScoreChip(label: String, value: String, bg: Color) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(5.dp)) {
        Box(Modifier.background(bg, RoundedCornerShape(3.dp)).padding(horizontal = 4.dp, vertical = 1.dp)) {
            Text(label, fontSize = 10.sp, fontWeight = FontWeight.Black, color = Color.White)
        }
        Text(value, fontSize = 14.sp, fontWeight = FontWeight.Bold)
    }
}

private fun runLabel(min: Int): String = if (min <= 0) "" else if (min >= 60) (if (min % 60 == 0) "${min / 60}h" else "${min / 60}h ${min % 60}m") else "${min}m"

@Composable
private fun EpisodeRow(e: Ep, info: EpInfo?, seen: Boolean, onClick: () -> Unit) {
    val img = info?.still?.takeIf { it.isNotBlank() } ?: e.img
    val title = info?.title?.takeIf { it.isNotBlank() } ?: e.title
    val plot = info?.overview?.takeIf { it.isNotBlank() } ?: e.plot
    val line = listOf(
        info?.runtime?.let { runLabel(it) }?.takeIf { it.isNotBlank() } ?: e.dur,
        info?.air.orEmpty().takeIf { it.isNotBlank() }?.let { prettyDate(it) }.orEmpty(),
        info?.vote.orEmpty().takeIf { it.isNotBlank() }?.let { "★ $it" }.orEmpty()
    ).filter { it.isNotBlank() }.joinToString("  •  ")
    Focusable(Modifier.fillMaxWidth(), RoundedCornerShape(10.dp), onClick) { foc ->
        Row(Modifier.fillMaxWidth().background(if (foc) Color(0x33FFFFFF) else Theme.card).padding(8.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Box(Modifier.width(150.dp).height(84.dp).clip(RoundedCornerShape(6.dp)).background(Color.Black), contentAlignment = Alignment.Center) {
                if (img.isNotBlank()) Img(img, Modifier.fillMaxSize(), ContentScale.Crop, 300, 170)
                else Text("E${e.num}", fontSize = 18.sp, fontWeight = FontWeight.Bold, color = Theme.muted)
                if (seen) Text("●", Modifier.align(Alignment.TopEnd).padding(5.dp), fontSize = 12.sp, color = Theme.accent)
            }
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                Text("${e.num}.  $title", fontSize = 13.sp, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                if (line.isNotBlank()) Text(line, fontSize = 10.sp, color = Theme.muted, maxLines = 1)
                if (plot.isNotBlank()) Text(plot, fontSize = 11.sp, color = Color(0xCCFFFFFF), maxLines = 3, overflow = TextOverflow.Ellipsis)
            }
        }
    }
}

@Composable
private fun SimilarCard(s: Item, onClick: () -> Unit) {
    Focusable(Modifier, RoundedCornerShape(8.dp), onClick) { _ ->
        Column(Modifier.width(110.dp).background(Theme.card)) {
            Img(s.img, Modifier.fillMaxWidth().height(160.dp), ContentScale.Crop, 300, 450)
            Text(Api.cleanTitle(s.name), Modifier.padding(6.dp), fontSize = 10.sp, maxLines = 2, overflow = TextOverflow.Ellipsis)
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
fun DetailScreen(item: Item, play: (PlayArgs) -> Unit, open: (Item) -> Unit = {}) {
    // keyed so that opening a "Similar" title starts from a clean state
    key(item.fkey) { DetailBody(item, play, open) }
}

@Composable
private fun DetailBody(item: Item, play: (PlayArgs) -> Unit, open: (Item) -> Unit) {
    val isSeries = item.kind == "series"
    var meta by remember { mutableStateOf<Meta?>(null) }
    var eps by remember { mutableStateOf<List<Ep>>(emptyList()) }
    var loaded by remember { mutableStateOf(false) }
    var epErr by remember { mutableStateOf("") }
    var retry by remember { mutableStateOf(0) }
    var season by remember { mutableStateOf(-1) }
    var more by remember { mutableStateOf(false) }
    var epInfo by remember { mutableStateOf<Map<Int, EpInfo>>(emptyMap()) }
    var similar by remember { mutableStateOf<List<Item>>(emptyList()) }
    val playFR = remember { FocusRequester() }
    val hv = History.version
    val lastKey = "lastEp_${Store.pid()}_${item.id}"

    LaunchedEffect(Unit) { delay(120); runCatching { playFR.requestFocus() } }

    // Progressive loading: provider data first (fast), then TMDB, then OMDb. Each step updates the page as soon as it arrives.
    LaunchedEffect(item) {
        val p = withContext(Dispatchers.IO) { Api.providerMeta(isSeries, item.id) }
        meta = p
        val t = withContext(Dispatchers.IO) { Api.tmdbExtra(p, item.name, isSeries, item.id) }
        meta = t
        meta = withContext(Dispatchers.IO) { Api.omdbExtra(t) }
    }
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
    LaunchedEffect(meta?.id, season) {
        epInfo = emptyMap()
        val tid = meta?.id ?: 0
        if (isSeries && tid > 0 && season >= 0) epInfo = withContext(Dispatchers.IO) { Api.tmdbSeason(tid, season) }
    }
    LaunchedEffect(meta?.recs, Repo.version) {
        val rs = meta?.recs ?: emptyList()
        similar = if (rs.isEmpty()) emptyList() else withContext(Dispatchers.Default) { runCatching { LibIndex.match(rs, isSeries, item) }.getOrDefault(emptyList()) }
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

    val runtime = (m?.runtime ?: "").ifBlank { eps.firstOrNull { it.dur.isNotBlank() }?.dur.orEmpty() }
    val title = Api.cleanTitle(item.name).ifBlank { item.name }

    Box(Modifier.fillMaxSize()) {
        // fixed backdrop (does not scroll, so nothing repaints while moving around)
        val bd = m?.backdrop?.takeIf { it.isNotBlank() }
        Img(bd ?: item.img, Modifier.fillMaxSize().alpha(if (bd != null) 0.40f else 0.16f), ContentScale.Crop, 960, 540)
        Box(Modifier.fillMaxSize().background(Brush.horizontalGradient(listOf(Theme.bg.copy(alpha = 0.97f), Theme.bg.copy(alpha = 0.80f), Theme.bg.copy(alpha = 0.55f)))))

        Row(Modifier.fillMaxSize().padding(start = 24.dp, end = 24.dp, top = 16.dp), horizontalArrangement = Arrangement.spacedBy(22.dp)) {
            // ---------------- LEFT: always-visible info panel with Play
            Column(Modifier.weight(0.46f).fillMaxHeight().verticalScroll(rememberScrollState()).padding(bottom = 24.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    Box(Modifier.width(96.dp).height(144.dp).clip(RoundedCornerShape(8.dp)).background(Theme.card)) {
                        Img(item.img, Modifier.fillMaxSize(), ContentScale.Crop, 300, 450)
                    }
                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(5.dp)) {
                        if (m != null && m.logo.isNotBlank()) Img(m.logo, Modifier.height(54.dp).fillMaxWidth(), ContentScale.Fit, 600, 200, Alignment.CenterStart)
                        else Text(title, fontSize = 22.sp, fontWeight = FontWeight.Black, maxLines = 3, overflow = TextOverflow.Ellipsis)
                        if (m != null && m.genres.isNotBlank()) Text(m.genres, fontSize = 11.sp, color = Theme.muted, maxLines = 2)
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                            val yr = m?.year.orEmpty()
                            if (yr.isNotBlank()) Text(yr, fontSize = 14.sp, fontWeight = FontWeight.Bold)
                            if (runtime.isNotBlank()) Box(Modifier.background(Theme.gloss, RoundedCornerShape(50)).padding(horizontal = 10.dp, vertical = 2.dp)) {
                                Text(runtime, fontSize = 13.sp, fontWeight = FontWeight.Bold, color = Theme.onAccent)
                            }
                            val cert = m?.cert.orEmpty()
                            if (cert.isNotBlank()) Box(Modifier.border(1.dp, Color.White.copy(alpha = 0.8f), RoundedCornerShape(4.dp)).padding(horizontal = 5.dp, vertical = 1.dp)) {
                                Text(cert, fontSize = 10.sp, fontWeight = FontWeight.Bold)
                            }
                        }
                        if (isSeries && loaded && eps.isNotEmpty()) {
                            val ns = eps.map { it.season }.distinct().size
                            Text("$ns " + tr(if (ns == 1) "Season" else "Seasons") + "  •  ${eps.size} " + tr("Episodes"), fontSize = 11.sp, color = Theme.muted)
                        }
                    }
                }
                if (m != null) {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        RatingBadge(m)
                        if (m.rt.isNotBlank()) ScoreChip("RT", m.rt, Color(0xFFFA320A))
                        if (m.mc.isNotBlank()) {
                            val n = m.mc.toIntOrNull() ?: 0
                            ScoreChip("MC", m.mc, if (n >= 61) Color(0xFF3FA535) else if (n >= 40) Color(0xFFC98F00) else Color(0xFFD32F2F))
                        }
                    }
                }
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.Top) {
                    PlayPill(playLabel, Modifier.width(190.dp).focusRequester(playFR), onPlay)
                    val fav = Store.favs.contains(item.fkey)
                    RoundBtn(if (fav) "★" else "☆", if (fav) "Favorited" else "Favorite") { Store.fav(item) }
                    if (!isSeries && hist != null && hist.pos > 5000) RoundBtn("↺", "Restart") { History.remove(movie.key); play(PlayArgs(vod = listOf(movie))) }
                }
                if (m == null) Text("Loading details...", fontSize = 12.sp, color = Theme.muted)
                else {
                    CrewLine(if (isSeries) "Creator:" else "Director:", m.director)
                    CrewLine("Writer:", m.writer)
                    CrewLine("Cast:", m.cast.split(",").map { it.trim() }.filter { it.isNotBlank() }.take(10).joinToString(", "), 3)
                    CrewLine("Awards:", m.awards)
                    if (m.overview.isNotBlank()) {
                        Text(m.overview, fontSize = 12.sp, maxLines = if (more) Int.MAX_VALUE else 4, overflow = TextOverflow.Ellipsis)
                        if (m.overview.length > 200) {
                            Focusable(Modifier, RoundedCornerShape(6.dp), { more = !more }) { foc ->
                                Text(if (more) "Show Less ▴" else "Show More ▾", Modifier.padding(horizontal = 6.dp, vertical = 3.dp), fontSize = 12.sp, color = if (foc) Theme.accent else Theme.muted)
                            }
                        }
                    }
                }
            }

            // ---------------- RIGHT: episodes (series) or details + similar (movies)
            Box(Modifier.weight(0.54f).fillMaxHeight()) {
                if (isSeries) {
                    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = 40.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        item {
                            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
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
                                EpisodeRow(e, epInfo[e.num], "e${e.id}" in watched) {
                                    val idx = queue.indexOfFirst { it.key == "e${e.id}" }
                                    if (idx >= 0) {
                                        Store.put(lastKey, e.id)
                                        play(PlayArgs(vod = queue, idx = idx))
                                    }
                                }
                            }
                        }
                        if (m != null) item { DetailsTable(m, true) }
                        if (similar.isNotEmpty()) item { SimilarRow(similar, open) }
                    }
                } else {
                    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(bottom = 40.dp)) {
                        if (m != null) DetailsTable(m, false)
                        if (similar.isNotEmpty()) SimilarRow(similar, open)
                    }
                }
            }
        }
    }
}

@Composable
private fun DetailsTable(m: Meta, isSeries: Boolean) {
    Column {
        SectionTitle(if (isSeries) "Series Details" else "Movie Details", 0.dp)
        Column(Modifier.widthIn(max = 520.dp)) {
            DetailRow("Status", m.status)
            DetailRow("Release Info", if (m.release.isNotBlank()) prettyDate(m.release) else m.year)
            DetailRow("Runtime", m.runtime)
            DetailRow("Certification", m.cert)
            DetailRow("Origin Country", m.country)
        }
    }
}

@Composable
private fun SimilarRow(similar: List<Item>, open: (Item) -> Unit) {
    Column {
        SectionTitle("Similar", 0.dp)
        LazyRow(horizontalArrangement = Arrangement.spacedBy(10.dp), contentPadding = PaddingValues(vertical = 4.dp)) {
            items(similar, key = { it.id }) { s -> SimilarCard(s) { open(s) } }
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
