@file:OptIn(ExperimentalTvMaterial3Api::class)
package com.iptv.tv

import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.grid.*
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusDirection
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.tv.material3.Card
import androidx.tv.material3.CardDefaults
import androidx.tv.material3.ExperimentalTvMaterial3Api
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Selection state that must survive opening a detail page / the player and coming back. */
class MainUi {
    var tab by mutableStateOf(0)          // 0 movies, 1 series, 2 live
    var cat by mutableStateOf("*")        // "*" all, "cw" continue watching, "fav", "radio" or a category id
    var lastIdx = 0                       // last focused grid index, to restore focus on return

    // Live TV home (the vertical list + preview screen). Kept here so Search / Settings can be opened and closed without losing the place.
    var liveBrowse = "fav"                // category shown in the list (always starts on favorites)
    var livePlayCat = "fav"               // list the playing channel belongs to (Up / Down zapping)
    var liveId: String? = null            // key of the playing channel
    var liveStartFull = false             // open the channel full screen (start option "Last channel")
    fun openLive() { liveBrowse = "fav"; livePlayCat = "fav"; liveId = null; liveStartFull = false }
}

/** What the live home should play when it opens. */
class LiveStart(val args: PlayArgs, val playCat: String, val full: Boolean)

fun liveStart(ui: MainUi): LiveStart {
    var pc = ui.livePlayCat
    var chans = liveChannels(pc)
    val want = ui.liveId
    var idx = if (want != null) chans.indexOfFirst { it.fkey == want } else -1
    if (want != null && idx < 0) { pc = "*"; chans = liveChannels("*"); idx = chans.indexOfFirst { it.fkey == want } }
    var full = ui.liveStartFull && idx >= 0
    if (want == null) {   // fresh open: show the channel watched last if it is in the list, else the first one
        val last = Store.str("lastCh_${Store.pid()}", "")
        idx = chans.indexOfFirst { it.fkey == last }
    }
    if (idx < 0) idx = 0
    if (chans.isEmpty()) full = false
    return LiveStart(PlayArgs(chans = chans, idx = idx), pc, full)
}


private val Q_4K = Regex("\\b(4K|2160P|UHD)\\b")
private val Q_FHD = Regex("\\b(FHD|1080P|1080I)\\b")
private val Q_HD = Regex("\\b(HD|720P)\\b")
private val Q_SD = Regex("\\b(SD|480P|576P)\\b")

fun streamQuality(name: String): String? {
    val u = name.uppercase()
    return when {
        Q_4K.containsMatchIn(u) -> "4K"
        Q_FHD.containsMatchIn(u) -> "FHD"
        Q_HD.containsMatchIn(u) -> "HD"
        Q_SD.containsMatchIn(u) -> "SD"
        else -> null
    }
}

/**
 * Sorted copies of the big movie / series lists, built once and reused (sorting 50k+ items on every category switch froze the UI).
 * A cached copy is valid while the source list is the same object and the sort mode is unchanged.
 */
object SortCache {
    private class E(val src: List<Item>, val sort: Int, val out: List<Item>)
    private val m = HashMap<String, E>()

    @Synchronized
    fun get(tag: String, src: List<Item>, sort: Int): List<Item> {
        if (sort !in 1..3 || src.size < 2) return src
        val e = m[tag]
        if (e != null && e.src === src && e.sort == sort) return e.out
        val out: List<Item> = when (sort) {
            1 -> src.sortedByDescending { it.added }
            2 -> src.sortedBy { it.nn }
            else -> src.map { it to (it.rating.toDoubleOrNull() ?: 0.0) }.sortedByDescending { it.second }.map { it.first }
        }
        m[tag] = E(src, sort, out)
        return out
    }

    /** Called from the background right after the library is parsed, so the first open of a sorted list is instant. */
    fun warm() {
        val sort = Store.int("sortVod", Store.int("sort", 0))
        if (sort !in 1..3) return
        runCatching { get("m", Repo.movies, sort); get("s", Repo.series, sort) }
    }
}

class Cell(val item: Item?, val hist: Hist? = null)
private class Entry(val id: String, val label: String)

private fun subOf(i: Item, showRating: Boolean): String {
    val r = if (showRating && i.rating.isNotBlank() && i.rating != "0") "★ ${i.rating}" else ""
    val y = i.year
    return if (y.isEmpty()) r else if (r.isEmpty()) y else "$y  $r"
}

@Composable
fun MainScreen(
    ui: MainUi, grid: LazyGridState,
    onOpen: (Item) -> Unit, onLive: (List<Item>, Int) -> Unit, onResume: (Hist) -> Unit, onPlay: (Media) -> Unit,
    onSearch: () -> Unit, onSettings: () -> Unit, onPlaylists: () -> Unit
) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val side = rememberLazyListState()
    val selFR = remember { FocusRequester() }
    val restoreFR = remember { FocusRequester() }
    var contentFocused by remember { mutableStateOf(false) }
    var lastBack by remember { mutableStateOf(0L) }
    var menu by remember { mutableStateOf<MenuState?>(null) }
    val focusManager = LocalFocusManager.current
    val topFR = remember { FocusRequester() }          // the selected Movies / Series / Live tile
    val hold = remember { HoldState() }
    var sideFocused by remember { mutableStateOf(false) }

    val tab = ui.tab
    val plName = remember(Repo.version) { Store.cur()?.name?.ifBlank { null } ?: "Playlists" }
    val plDot = remember(Repo.version) { Store.cur()?.let { PlColor.of(it.id) } ?: Color.Gray }
    val cat = ui.cat
    val ver = Repo.version
    val hv = History.version
    val fv = Store.favs.size
    val sort = remember { Store.int("sortVod", Store.int("sort", 0)) }
    val showLabel = remember { !Store.bool("hideLabels", false) }
    val showRating = remember { Store.bool("rating", true) }
    val cardMin = remember { when (Store.int("grid", 1)) { 0 -> 100; 2 -> 140; else -> 120 } }

    val entries = remember(tab, ver) {
        val l = mutableListOf<Entry>()
        l += Entry("cw", "Continue Watching"); l += Entry("fav", "★ Favorites"); l += Entry("", "Categories"); l += Entry("*", "All")
        (if (tab == 1) Repo.serCats else Repo.movieCats).forEach { l += Entry(it.id, it.name) }
        l
    }
    val selIdx = entries.indexOfFirst { it.id == cat }.coerceAtLeast(0)

    val cells = remember(tab, cat, ver, hv, fv, sort) {
        val base = if (tab == 1) Repo.series else Repo.movies
        when (cat) {
            "cw" -> History.all().filter { it.kind == (if (tab == 0) "movie" else "series") }
                .distinctBy { if (it.sid.isNotBlank()) it.sid else it.key }.map { Cell(null, it) }
            "fav" -> {
                val fs = HashSet<String>(Store.favs)   // one hash lookup per item instead of scanning the favourites list
                SortCache.get(if (tab == 1) "s" else "m", base, sort).filter { it.fkey in fs }.map { Cell(it) }
            }
            "*" -> SortCache.get(if (tab == 1) "s" else "m", base, sort).map { Cell(it) }
            else -> SortCache.get(if (tab == 1) "s" else "m", base, sort).filter { it.cat == cat }.map { Cell(it) }
        }
    }
    val chans = remember(cells) { cells.mapNotNull { it.item } }
    val restoreIdx = ui.lastIdx.coerceIn(0, maxOf(0, cells.size - 1))

    val title = when (cat) {
        "cw" -> "Continue Watching"; "fav" -> "★ Favorites"; "*" -> "All"
        else -> entries.firstOrNull { it.id == cat }?.label ?: ""
    }

    fun switchTab(i: Int) {
        if (i == 2) ui.openLive()   // Live TV always opens on Favorites
        ui.tab = i; ui.cat = "*"; ui.lastIdx = 0
        scope.launch { grid.scrollToItem(0); side.scrollToItem(0) }
    }
    fun switchCat(id: String) {
        ui.cat = id; ui.lastIdx = 0
        scope.launch { grid.scrollToItem(0) }
    }
    fun exitApp() {
        val now = System.currentTimeMillis()
        if (now - lastBack < 2500) ctx.activity()?.finish()
        else { lastBack = now; Toast.makeText(ctx, tr("Press back again to exit"), Toast.LENGTH_SHORT).show() }
    }

    // ---- Long-press (hold OK) menus: more than just favorites
    fun itemMenu(m: Item, index: Int): MenuState {
        val a = mutableListOf<MenuAction>()
        if (m.kind == "movie") a += MenuAction("▶  Play") { onPlay(Media("m${m.id}", Api.vodUrl(m.id, m.ext), m.name, m.img, "movie")) }
        a += MenuAction("ⓘ Details") { onOpen(m) }
        a += favAction(m)
        val inHistory = History.all().any { if (m.kind == "movie") it.key == "m${m.id}" else m.kind == "series" && it.sid == m.id }
        if (inHistory) a += MenuAction("✕ Remove from Continue Watching") {
            if (m.kind == "movie") History.remove("m${m.id}") else History.removeWhere { it.sid == m.id }
        }
        if (m.cat.isNotBlank() && m.cat != cat && entries.any { it.id == m.cat }) a += MenuAction("Go to category") { switchCat(m.cat) }
        return MenuState(m.name, a)
    }
    fun cwMenu(h: Hist): MenuState {
        val a = mutableListOf<MenuAction>()
        a += MenuAction(if (h.pos > 5000) "▶  Resume ${fmtTime(h.pos)}" else "▶  Play") { onResume(h) }
        val m = if (h.kind == "movie") Repo.movies.firstOrNull { "m${it.id}" == h.key } else Repo.series.firstOrNull { it.id == h.sid }
        if (m != null) { a += MenuAction("ⓘ Details") { onOpen(m) }; a += favAction(m) }
        a += MenuAction("✕ Remove from Continue Watching") { if (h.sid.isNotBlank()) History.removeWhere { it.sid == h.sid } else History.remove(h.key) }
        a += MenuAction("Clear all Continue Watching") { History.clear() }
        return MenuState(h.title, a)
    }

    // P+ / P- (channel keys) move the whole list one page up / down. The four arrows keep moving one item.
    fun pageKey(e: android.view.KeyEvent): Boolean {
        val up = Remote.isPageUp(e.keyCode); val dn = Remote.isPageDown(e.keyCode)
        if (!up && !dn) return false
        if (e.action != android.view.KeyEvent.ACTION_DOWN) return true
        val inGrid = contentFocused
        if (!inGrid && !sideFocused) return true
        val dir = if (up) FocusDirection.Up else FocusDirection.Down
        val back = if (up) FocusDirection.Down else FocusDirection.Up
        val n = if (inGrid) {
            val rows = grid.layoutInfo.visibleItemsInfo.map { it.offset.y }.distinct().size
            maxOf(1, rows - 1)
        } else maxOf(1, side.layoutInfo.visibleItemsInfo.size - 1)
        for (i in 0 until n) {
            if (!focusManager.moveFocus(dir)) break
            if ((inGrid && !contentFocused) || (!inGrid && !sideFocused)) { focusManager.moveFocus(back); break }   // never leave the list
        }
        return true
    }
    // Long press (hold) on an arrow: Up = top icons by default (Settings > Remote control).
    fun holdAct(a: Int): Boolean = when (a) {
        Remote.TOP -> runCatching { topFR.requestFocus() }.isSuccess
        Remote.SEARCH -> { onSearch(); true }
        Remote.SETTINGS -> { onSettings(); true }
        else -> false
    }

    // Back: content -> sidebar, sidebar -> (double back) exit.
    BackHandler {
        if (contentFocused) {
            scope.launch {
                side.scrollToItem(selIdx); delay(30)
                if (runCatching { selFR.requestFocus() }.isFailure) exitApp()
            }
        } else exitApp()
    }

    // Initial / restored focus once the library is available.
    LaunchedEffect(Repo.ready) {
        if (!Repo.ready) return@LaunchedEffect
        if (restoreIdx > 0) grid.scrollToItem(restoreIdx)
        repeat(12) {
            val ok = runCatching { if (cells.isNotEmpty()) restoreFR.requestFocus() else selFR.requestFocus() }.isSuccess
            if (ok) return@LaunchedEffect
            delay(60)
        }
    }

    if (!Repo.ready) {
        val loading = Repo.error.isBlank() && Api.c.server.isNotBlank()
        var slow by remember { mutableStateOf(false) }
        LaunchedEffect(loading) { slow = false; if (loading) { delay(20000); slow = true } }
        if (loading) { LoadingScreen(slow, onPlaylists) { scope.launch { Repo.load(true) } }; return }
        Box(Modifier.fillMaxSize(), Alignment.Center) {
            Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text(when {
                    Repo.error.isNotBlank() -> "Error: ${Repo.error}"
                    Api.c.server.isBlank() -> "No playlist yet"
                    else -> "Loading your library..."
                })
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Chip("Playlists", true) { onPlaylists() }
                    Chip("Retry", false) { scope.launch { Repo.load(true) } }
                    Chip("Settings", false) { onSettings() }
                }
            }
        }
        return
    }

    Column(Modifier.fillMaxSize().onPreviewKeyEvent { pageKey(it.nativeKeyEvent) || holdPreview(it.nativeKeyEvent, hold) { a -> holdAct(a) } }) {
        // ---------- Top bar: Movies | Series | Live ----------
        Row(
            Modifier.fillMaxWidth().background(Theme.sidebar).padding(horizontal = 16.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            MainNav(tab, topFR) { switchTab(it) }
            Spacer(Modifier.weight(1f))
            if (Repo.refreshing) Text("↻", fontSize = 16.sp)
            Chip("Search", false, Modifier, 14.dp) { onSearch() }
            Chip(if (Vpn.up != null) "VPN ●" else "Settings", false, Modifier, 14.dp) { onSettings() }
        }
        Row(Modifier.weight(1f).fillMaxWidth()) {
            // ---------- Sidebar ----------
            Column(Modifier.width(210.dp).fillMaxHeight().background(Theme.sidebar).padding(10.dp).onFocusChanged { sideFocused = it.hasFocus }) {
                LazyColumn(Modifier.weight(1f), state = side, verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    itemsIndexed(entries) { _, e ->
                        if (e.id.isEmpty()) Text(e.label, Modifier.padding(start = 6.dp, top = 8.dp, bottom = 2.dp), fontSize = 11.sp)
                        else if (e.id == "fav") FavItem("Favorites", null, e.id == cat, if (e.id == cat) Modifier.focusRequester(selFR) else Modifier) { switchCat(e.id) }
                        else SideItem(e.label, e.id == cat, if (e.id == cat) Modifier.focusRequester(selFR) else Modifier) { switchCat(e.id) }
                    }
                }
                Spacer(Modifier.height(6.dp))
                PlChip(plName, plDot, Modifier.fillMaxWidth()) { onPlaylists() }
            }

            // ---------- Content ----------
            Column(Modifier.weight(1f).fillMaxHeight().onFocusChanged { contentFocused = it.hasFocus }) {
                Row(Modifier.fillMaxWidth().padding(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 2.dp),
                    verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(title, Modifier.weight(1f), fontSize = 16.sp, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
                if (cells.isEmpty()) {
                    Box(Modifier.weight(1f).fillMaxWidth(), Alignment.Center) {
                        Text(if (cat == "cw") "Nothing watched yet" else if (cat == "fav") "No favorites yet (hold OK on an item)" else "Nothing here")
                    }
                } else {
                    LazyVerticalGrid(
                        GridCells.Adaptive(cardMin.dp), Modifier.weight(1f), state = grid,
                        contentPadding = PaddingValues(16.dp),
                        horizontalArrangement = Arrangement.spacedBy(12.dp), verticalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        itemsIndexed(cells) { i, c ->
                            val mod = Modifier
                                .onFocusChanged { if (it.hasFocus) ui.lastIdx = i }
                                .then(if (i == restoreIdx) Modifier.focusRequester(restoreFR) else Modifier)
                            val h = c.hist
                            val m = c.item
                            if (h != null) {
                                PosterCard(h.title, if (h.dur > 0L) "${fmtTime(h.pos)} / ${fmtTime(h.dur)}" else "Next episode", h.img, false, h.progress, showLabel, "", mod, { onResume(h) }, { menu = cwMenu(h) })
                            } else if (m != null) {
                                val fav = Store.favs.contains(m.fkey)
                                val rTag = if (showRating && m.rating.isNotBlank() && m.rating != "0") "★ ${m.rating}" else ""
                                PosterCard(m.name, subOf(m, showRating), m.img, fav, 0f, showLabel, rTag, mod, { onOpen(m) }, { menu = itemMenu(m, i) })
                            }
                        }
                    }
                }
            }
        }
    }
    menu?.let { mm -> OptionsDialog(mm.title, mm.actions) { menu = null } }
}

/** Cards keep their dark background and white text when focused (the default turns them white with black text). */
@Composable
fun cardColors() = CardDefaults.colors(
    containerColor = Theme.card, contentColor = Color.White,
    focusedContainerColor = Theme.card, focusedContentColor = Color.White,
    pressedContainerColor = Theme.card, pressedContentColor = Color.White
)

@Composable
fun PosterCard(
    title: String, sub: String, img: String, fav: Boolean, progress: Float, showLabel: Boolean,
    ratingText: String = "", mod: Modifier, onClick: () -> Unit, onLong: () -> Unit
) {
    Card(onClick = onClick, onLongClick = onLong, modifier = mod.fillMaxWidth(), colors = cardColors()) {
        Box(Modifier.fillMaxWidth().aspectRatio(2f / 3f).background(Theme.card)) {
            if (img.isNotBlank()) Img(img, Modifier.fillMaxSize())
            else Text(title, Modifier.align(Alignment.Center).padding(6.dp), fontSize = 11.sp, maxLines = 4)
            if (ratingText.isNotBlank()) {
                Box(
                    Modifier.align(Alignment.TopStart).padding(4.dp)
                        .background(Color(0xCC000000), RoundedCornerShape(4.dp))
                        .padding(horizontal = 5.dp, vertical = 1.dp)
                ) {
                    Text(ratingText, color = Color(0xFFFFC107), fontSize = 10.sp, fontWeight = FontWeight.Bold, maxLines = 1)
                }
            }
            if (fav) Text("★", Modifier.align(Alignment.TopEnd).padding(4.dp), color = Color(0xFFFFC107), fontSize = 14.sp)
            if (progress > 0f) Box(Modifier.align(Alignment.BottomStart).fillMaxWidth(progress).height(3.dp).background(Theme.accent))
        }
        if (showLabel) Column(Modifier.padding(horizontal = 6.dp, vertical = 4.dp).height(38.dp)) {
            Text(title, fontSize = 11.sp, fontWeight = FontWeight.Bold, maxLines = 2, overflow = TextOverflow.Ellipsis)
            if (sub.isNotBlank()) Text(sub, fontSize = 10.sp, maxLines = 1)
        }
    }
}

@Composable
fun LiveCard(num: Int, name: String, img: String, fav: Boolean, mod: Modifier, onClick: () -> Unit, onLong: () -> Unit) {
    val q = remember(name) { streamQuality(name) }  // cached regexes: cheap
    Card(onClick = onClick, onLongClick = onLong, modifier = mod.fillMaxWidth(), colors = cardColors()) {
        Row(Modifier.padding(8.dp).height(40.dp), verticalAlignment = Alignment.CenterVertically) {
            if (num > 0) Text("$num", Modifier.width(30.dp), fontSize = 11.sp, maxLines = 1)
            if (img.isNotBlank()) Img(img, Modifier.size(34.dp), ContentScale.Fit, 100, 100) else Spacer(Modifier.size(34.dp))
            Spacer(Modifier.width(8.dp))
            Text(name, Modifier.weight(1f), fontSize = 13.sp, maxLines = 2, overflow = TextOverflow.Ellipsis)
            if (q != null) {
                Spacer(Modifier.width(4.dp))
                Box(
                    Modifier.background(Theme.accent.copy(alpha = 0.25f), RoundedCornerShape(4.dp))
                        .padding(horizontal = 5.dp, vertical = 1.dp)
                ) {
                    Text(q, fontSize = 10.sp, fontWeight = FontWeight.Bold, color = Theme.accent, maxLines = 1)
                }
            }
            if (fav) Text("★", Modifier.padding(start = 4.dp), color = Color(0xFFFFC107))
        }
    }
}
