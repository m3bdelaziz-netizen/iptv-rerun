@file:OptIn(ExperimentalTvMaterial3Api::class)
package com.iptv.tv

import android.view.KeyEvent
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.TrackSelectionOverride
import androidx.media3.common.Tracks
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.exoplayer.DefaultLoadControl
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.ui.AspectRatioFrameLayout
import androidx.media3.ui.PlayerView
import androidx.tv.material3.Button
import androidx.tv.material3.Card
import androidx.tv.material3.CardDefaults
import androidx.compose.ui.input.key.onPreviewKeyEvent
import android.widget.Toast
import androidx.tv.material3.ButtonDefaults
import androidx.tv.material3.ExperimentalTvMaterial3Api
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

object Hub {
    @Volatile var p: ExoPlayer? = null
    var was = false
}

/** Set when the player is used as the Live TV home screen (categories | vertical channel list | preview). */
class LiveHome(
    val browseCat: String, val playCat: String, val startFull: Boolean,
    val onTab: (Int) -> Unit, val onSettings: () -> Unit, val onPlaylists: () -> Unit,
    val onState: (browseCat: String, playCat: String, channelKey: String?) -> Unit,
    val exitApp: () -> Unit
)

fun fmtTime(ms: Long): String {
    val s = ms / 1000
    val h = s / 3600; val m = (s % 3600) / 60; val x = s % 60
    return if (h > 0) "%d:%02d:%02d".format(h, m, x) else "%02d:%02d".format(m, x)
}

private fun trackName(g: Tracks.Group, n: Int, total: Int): String {
    val f = g.getTrackFormat(0)
    return (f.label ?: f.language ?: "Track") + " (${n + 1}/$total)"
}

fun cycleTrack(p: ExoPlayer, type: Int, allowOff: Boolean): String {
    val gs = p.currentTracks.groups.filter { it.type == type }
    if (gs.isEmpty()) return "—"
    val cur = gs.indexOfFirst { it.isSelected }
    val b = p.trackSelectionParameters.buildUpon()
    if (allowOff && cur == gs.size - 1) {
        b.setTrackTypeDisabled(type, true)
        p.trackSelectionParameters = b.build()
        return "Off"
    }
    val n = (cur + 1) % gs.size
    b.setTrackTypeDisabled(type, false)
    b.setOverrideForType(TrackSelectionOverride(gs[n].mediaTrackGroup, 0))
    p.trackSelectionParameters = b.build()
    return trackName(gs[n], n, gs.size)
}

@Composable
fun PlayerScreen(args: PlayArgs, onExit: () -> Unit, onSearch: (() -> Unit)? = null, home: LiveHome? = null) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val isLive = home != null || args.chans.isNotEmpty()

    // Live channel list & category state (allows switching categories while watching in split mode)
    var activeChans by remember { mutableStateOf(args.chans) }
    var ci by remember { mutableStateOf(args.idx.coerceAtLeast(0)) }
    var fullScreen by remember { mutableStateOf(home?.startFull ?: true) }
    var selectedCatId by remember {
        mutableStateOf(home?.browseCat ?: args.chans.getOrNull(args.idx)?.cat?.takeIf { it.isNotBlank() } ?: "*")
    }
    // The list the playing channel came from (used to remember the last channel and its zapping list).
    var playCat by remember { mutableStateOf(home?.playCat ?: args.chans.getOrNull(args.idx)?.cat?.takeIf { it.isNotBlank() } ?: "*") }
    // Manual rearranging of the channel list (long press a channel -> Rearrange).
    var moveId by remember { mutableStateOf<String?>(null) }
    var work by remember { mutableStateOf<List<Item>>(emptyList()) }
    var sortTick by remember { mutableStateOf(0) }
    var menuCh by remember { mutableStateOf<MenuState?>(null) }
    val catFR = remember { FocusRequester() }
    val moveFR = remember { FocusRequester() }
    var catFocused by remember { mutableStateOf(false) }

    var u by remember(ci, activeChans) { mutableStateOf(0) }
    var err by remember(ci, activeChans, u) { mutableStateOf("") }
    var perr by remember { mutableStateOf<PlaybackException?>(null) }
    var ended by remember { mutableStateOf(0) }
    var buffering by remember { mutableStateOf(true) }
    var menu by remember { mutableStateOf(false) }
    var bar by remember { mutableStateOf(false) }
    var stamp by remember { mutableStateOf(0) }
    var fit by remember { mutableStateOf(Store.int("aspect", 0).coerceIn(0, 2)) }
    var note by remember { mutableStateOf("") }
    var digits by remember { mutableStateOf("") }
    var pos by remember { mutableStateOf(0L) }
    var dur by remember { mutableStateOf(0L) }
    var clock by remember { mutableStateOf(System.currentTimeMillis()) }
    var epg by remember(ci, activeChans) { mutableStateOf(listOf<String>()) }
    var banner by remember(ci, activeChans) { mutableStateOf(true) }
    var speedIdx by remember { mutableStateOf(1) }
    var vh by remember { mutableStateOf(0) }       // real video height (for the quality badge)
    var vfps by remember { mutableStateOf(0) }     // real frame rate (25 / 50 ...)
    val probeGen = remember { IntArray(1) }
    var controls by remember { mutableStateOf(false) }   // VOD button bar has the focus
    var playing by remember { mutableStateOf(true) }     // play/pause icon

    val rootFR = remember { FocusRequester() }
    val menuFR = remember { FocusRequester() }
    val ctlFR = remember { FocusRequester() }
    val activeChanFR = remember { FocusRequester() }
    val loaded = remember { arrayOfNulls<Media>(1) }

    val cur: Item? = if (isLive) activeChans.getOrNull(ci) else null
    val media: Media? = if (!isLive) args.vod.getOrNull(ci) else null
    val title = cur?.name ?: media?.title ?: ""
    val badgeQ: String? = StreamInfo.qualityOfHeight(vh) ?: cur?.name?.let { streamQuality(it) }
    val seekStep = remember { if (Store.int("seek", 0) == 0) 10_000L else 30_000L }
    val subMult = remember { when (Store.int("ssize", 1)) { 0 -> 0.8f; 2 -> 1.3f; 3 -> 1.6f; else -> 1f } }
    val showClock = remember { Store.bool("clock", true) }
    val clockFmt = remember { SimpleDateFormat("HH:mm", Locale.getDefault()) }
    val speeds = remember { listOf(0.75f, 1f, 1.25f, 1.5f, 2f) }

    val player = remember {
        val (mn, mx, st, rb) = when (Store.int("buf", 1)) {
            0 -> listOf(5000, 20000, 500, 1500)
            2 -> listOf(30000, 120000, 2500, 5000)
            else -> listOf(15000, 50000, 1500, 3000)
        }
        val lc = DefaultLoadControl.Builder().setBufferDurationsMs(mn, mx, st, rb).build()
        val dsf = DefaultHttpDataSource.Factory().setUserAgent("IPTVSmartersPro").setAllowCrossProtocolRedirects(true)
            .setConnectTimeoutMs(10000).setReadTimeoutMs(15000)
        val rf = DefaultRenderersFactory(ctx).setEnableDecoderFallback(true)
        ExoPlayer.Builder(ctx, rf)
            .setMediaSourceFactory(DefaultMediaSourceFactory(ctx).setDataSourceFactory(dsf))
            .setLoadControl(lc)
            .setAudioAttributes(AudioAttributes.DEFAULT, true)
            .setHandleAudioBecomingNoisy(true)
            .build().apply {
                val al = Store.str("alang", ""); val sl = Store.str("slang", "")
                var b = trackSelectionParameters.buildUpon()
                if (al.isNotEmpty()) b = b.setPreferredAudioLanguage(al)
                if (sl.isNotEmpty()) b = b.setPreferredTextLanguage(sl)
                trackSelectionParameters = b.build()
            }
    }

    fun finishMove(save: Boolean) {
        val mv = moveId
        if (save && mv != null) {
            LiveOrder.save(selectedCatId, work.map { it.fkey })
            if (Store.int("sortLive", 0) != 2) { Store.put("sortLive", 2); Toast.makeText(ctx, tr("Live sort set to Manual"), Toast.LENGTH_SHORT).show() }
            sortTick++
            if (playCat == selectedCatId) {   // keep Up / Down zapping in the new order
                val curKey = cur?.fkey
                val nl = liveChannels(selectedCatId)
                activeChans = nl
                ci = nl.indexOfFirst { it.fkey == curKey }.coerceAtLeast(0)
            }
        }
        moveId = null; work = emptyList()
    }

    fun poke() { bar = true; stamp++ }
    fun markDone(m: Media) {
        History.remove(m.key)
        if (m.kind != "series") return
        val i = args.vod.indexOfFirst { it.key == m.key }
        val n = args.vod.getOrNull(i + 1) ?: return
        if (i < 0) return
        History.put(Hist(n.key, n.title, n.img, n.url, "series", 0L, 0L, System.currentTimeMillis(), n.sid))
        if (n.sid.isNotBlank()) Store.put("lastEp_${Store.pid()}_${n.sid}", n.key.removePrefix("e"))
    }
    fun saveNow() {
        val m = loaded[0] ?: return
        val d = player.duration; val p = player.currentPosition
        if (d <= 0 || d == C.TIME_UNSET) return
        if (p > d * 0.95) markDone(m)
        else if (p > 5000) History.put(Hist(m.key, m.title, m.img, m.url, m.kind, p, d, System.currentTimeMillis(), m.sid))
    }
    fun zap(d: Int) { val n = activeChans.size; if (n > 0) ci = (ci + d + n) % n }
    fun seek(delta: Long) {
        val d = player.duration
        var t = (player.currentPosition + delta).coerceAtLeast(0L)
        if (d != C.TIME_UNSET && d > 0) t = t.coerceAtMost(maxOf(0L, d - 1000))
        player.seekTo(t); poke()
    }
    fun toggle() { if (player.playWhenReady) player.pause() else player.play(); poke() }

    DisposableEffect(player) {
        Hub.p = player
        val l = object : Player.Listener {
            override fun onPlayerError(error: PlaybackException) { perr = error }
            override fun onPlayWhenReadyChanged(playWhenReady: Boolean, reason: Int) { playing = playWhenReady }
            override fun onVideoSizeChanged(videoSize: androidx.media3.common.VideoSize) { vh = maxOf(videoSize.height, videoSize.width * 9 / 16) }   // wide films are letterboxed: judge by width too
            override fun onPlaybackStateChanged(state: Int) {
                buffering = state == Player.STATE_BUFFERING
                if (state == Player.STATE_ENDED) ended++
            }
        }
        player.addListener(l)
        onDispose { saveNow(); Hub.p = null; player.removeListener(l); player.release() }
    }

    // Play or switch channel seamlessly without destroying the PlayerView
    LaunchedEffect(cur?.id, media?.key, u) {
        saveNow(); loaded[0] = null
        val url: String
        if (isLive) {
            val c = cur ?: return@LaunchedEffect
            url = Api.liveUrls(c.id)[u.coerceIn(0, 1)]
        } else {
            val m = media ?: return@LaunchedEffect
            loaded[0] = m; url = m.url
        }
        player.setMediaItem(MediaItem.fromUri(url)); player.prepare(); player.playWhenReady = true
        // Real quality + frame rate of what is playing (shown as badges). Starts from what was measured last time for this channel.
        FpsMemory.sync()
        val pk = cur?.fkey
        val gen = ++probeGen[0]
        vh = 0; vfps = if (pk != null) FpsMemory.get(pk) else 0
        player.setVideoFrameMetadataListener(FpsProbe { f ->
            if (gen == probeGen[0] && f > 0) { vfps = f; if (pk != null) FpsMemory.put(pk, f) }
        })
        val m = media
        if (!isLive && m != null && Store.bool("resume", true)) {
            val h = History.get(m.key)
            if (h != null && h.pos > 5000 && h.progress < 0.95f) player.seekTo(h.pos)
        }
    }

    var guide by remember(ci) { mutableStateOf<Guide?>(null) }
    var guideShow by remember(ci) { mutableStateOf(false) }
    LaunchedEffect(ci) {
        if (isLive || Api.c.tmdb.isBlank()) return@LaunchedEffect
        val m = media ?: return@LaunchedEffect
        val t = m.title.replace(Regex("\\s+S\\d+E\\d+$"), "")
        val g = withContext(Dispatchers.IO) { runCatching { Api.guide(t, m.kind == "series") }.getOrNull() } ?: return@LaunchedEffect
        guide = g
        var w = 0
        while (!player.isPlaying && w < 60) { delay(300); w++ }
        guideShow = true; delay(8000); guideShow = false
    }
    LaunchedEffect(perr) {
        val e = perr ?: return@LaunchedEffect
        perr = null
        if (isLive && u < 1) { u++ } else { err = e.errorCodeName }
    }
    LaunchedEffect(ended) {
        if (ended == 0 || isLive) return@LaunchedEffect
        media?.let { markDone(it) }
        loaded[0] = null
        if (Store.bool("autonext", true) && ci < args.vod.size - 1) ci++ else onExit()
    }
    LaunchedEffect(cur?.id) { banner = true; delay(3500); banner = false }
    // Remember the last live channel (start option "Last channel") and where the live home was.
    LaunchedEffect(cur?.fkey, selectedCatId, playCat) {
        val c = cur
        if (isLive && c != null) { Store.put("lastCh_${Store.pid()}", c.fkey); Store.put("lastChCat_${Store.pid()}", playCat) }
        home?.onState?.invoke(selectedCatId, playCat, c?.fkey)
    }
    // Keep the channel that is being moved under the D-pad cursor.
    LaunchedEffect(moveId, work) { if (moveId != null) { delay(30); runCatching { moveFR.requestFocus() } } }
    LaunchedEffect(menu, fullScreen, controls) {
        delay(80)
        runCatching {
            when {
                menu -> menuFR.requestFocus()
                fullScreen -> if (controls) ctlFR.requestFocus() else rootFR.requestFocus()
                else -> activeChanFR.requestFocus()
            }
        }
    }
    LaunchedEffect(bar, menu) {
        while (bar || menu) {
            pos = player.currentPosition
            dur = player.duration.let { if (it == C.TIME_UNSET || it < 0) 0L else it }
            clock = System.currentTimeMillis()
            delay(500)
        }
    }
    LaunchedEffect(stamp, playing) {
        if (stamp == 0) return@LaunchedEffect
        delay(if (controls) 6000 else 4000)
        if (!isLive && !playing) return@LaunchedEffect   // keep the bar on screen while paused
        bar = false; controls = false
    }
    LaunchedEffect(note) { if (note.isNotEmpty()) { delay(2000); note = "" } }
    LaunchedEffect(digits) {
        if (digits.isEmpty()) return@LaunchedEffect
        delay(1500)
        val n = digits.toIntOrNull(); digits = ""
        if (n != null) { val j = ChNum.indexOf(activeChans, n); if (j >= 0) ci = j }
    }
    // Fetch EPG whenever OSD menu is open OR split-screen preview is visible
    LaunchedEffect(menu, fullScreen, cur?.id) {
        val c = cur ?: return@LaunchedEffect
        if (menu || !fullScreen) {
            epg = withContext(Dispatchers.IO) { runCatching { Epg.lines(c.epgId, c.name).ifEmpty { Api.epg(c.id) } }.getOrDefault(emptyList()) }
        }
    }

    fun handleKey(e: KeyEvent): Boolean {
        if (!fullScreen || menu || e.action != KeyEvent.ACTION_DOWN) return false
        val k = e.keyCode
        val ok = k == KeyEvent.KEYCODE_DPAD_CENTER || k == KeyEvent.KEYCODE_ENTER || k == KeyEvent.KEYCODE_NUMPAD_ENTER
        val up = k == KeyEvent.KEYCODE_DPAD_UP || k == KeyEvent.KEYCODE_CHANNEL_UP
        val down = k == KeyEvent.KEYCODE_DPAD_DOWN || k == KeyEvent.KEYCODE_CHANNEL_DOWN
        val info = k == KeyEvent.KEYCODE_MENU || k == KeyEvent.KEYCODE_INFO
        if (k == KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE) { toggle(); return true }
        if (k == KeyEvent.KEYCODE_MEDIA_PLAY) { player.play(); poke(); return true }
        if (k == KeyEvent.KEYCODE_MEDIA_PAUSE) { player.pause(); poke(); return true }
        if (isLive) {
            val digit = when (k) {
                in KeyEvent.KEYCODE_0..KeyEvent.KEYCODE_9 -> k - KeyEvent.KEYCODE_0
                in KeyEvent.KEYCODE_NUMPAD_0..KeyEvent.KEYCODE_NUMPAD_9 -> k - KeyEvent.KEYCODE_NUMPAD_0
                else -> -1
            }
            return when {
                digit >= 0 -> { if (digits.length < 4) digits += digit.toString(); true }
                up -> { zap(1); true }
                down -> { zap(-1); true }
                k == KeyEvent.KEYCODE_MEDIA_NEXT -> { zap(1); true }
                k == KeyEvent.KEYCODE_MEDIA_PREVIOUS -> { zap(-1); true }
                ok || info -> { menu = true; true }
                k == KeyEvent.KEYCODE_DPAD_LEFT -> { fullScreen = false; true }
                k == KeyEvent.KEYCODE_DPAD_RIGHT -> true
                else -> false
            }
        }
        if (controls) {
            poke()
            return when (k) {
                KeyEvent.KEYCODE_DPAD_UP -> { controls = false; true }
                KeyEvent.KEYCODE_MEDIA_REWIND -> { seek(-30_000L); true }
                KeyEvent.KEYCODE_MEDIA_FAST_FORWARD -> { seek(30_000L); true }
                KeyEvent.KEYCODE_MEDIA_NEXT -> { if (ci < args.vod.size - 1) ci++; true }
                KeyEvent.KEYCODE_MEDIA_PREVIOUS -> { if (ci > 0) ci--; true }
                else -> false   // Left / Right / OK are handled by the focused button
            }
        }
        val mult = if (e.repeatCount > 8) 6 else if (e.repeatCount > 3) 3 else 1
        return when {
            k == KeyEvent.KEYCODE_DPAD_LEFT -> { seek(-seekStep * mult); true }
            k == KeyEvent.KEYCODE_DPAD_RIGHT -> { seek(seekStep * mult); true }
            k == KeyEvent.KEYCODE_MEDIA_REWIND -> { seek(-30_000L); true }
            k == KeyEvent.KEYCODE_MEDIA_FAST_FORWARD -> { seek(30_000L); true }
            k == KeyEvent.KEYCODE_MEDIA_NEXT -> { if (ci < args.vod.size - 1) ci++; true }
            k == KeyEvent.KEYCODE_MEDIA_PREVIOUS -> { if (ci > 0) ci--; true }
            ok -> { toggle(); true }
            up || info -> { menu = true; true }
            down -> { poke(); controls = true; true }
            else -> false
        }
    }

    // Back handling:
    // - Menu open -> close menu
    // - Live full-screen -> open split-screen channel browser
    // - VOD full-screen -> first Back opens controls; second Back (when controls/bar already open) exits
    BackHandler(enabled = menu || isLive || !isLive) {
        when {
            menu -> menu = false
            moveId != null -> finishMove(false)
            isLive && fullScreen -> fullScreen = false
            isLive && !fullScreen -> {
                if (home == null) onExit()
                else if (catFocused || runCatching { catFR.requestFocus() }.isFailure) home.exitApp()   // 1st Back: to the categories, 2nd Back: exit
            }
            !isLive && (!controls && !bar) -> { bar = true; controls = true; stamp++ }
            else -> onExit()
        }
    }

    val split = isLive && !fullScreen
    var slot by remember { mutableStateOf<Rect?>(null) }
    val density = LocalDensity.current
    LaunchedEffect(split) {
        if (!split) slot = null
    }
    // One root box and ONE video surface for both layouts: switching between the channel browser and full screen
    // never re-creates the surface (that is what froze weaker boxes).
    Box(
        Modifier.fillMaxSize().background(if (split) Theme.bg else Color.Black)
            .then(if (!split) Modifier.focusRequester(rootFR).focusable().onKeyEvent { handleKey(it.nativeKeyEvent) } else Modifier)
    ) {
    if (split) {
        // ==================== LIVE TV: Categories | vertical channel list | Preview + EPG ====================
        val categories = remember(Repo.version, Store.favs.size) {
            val counts = Repo.live.groupingBy { it.cat }.eachCount()
            buildList {
                add(Triple("fav", "Favorites", (Repo.live + Repo.radio).count { Store.favs.contains(it.fkey) }))
                add(Triple("*", "All channels", Repo.live.size))
                Repo.liveCats.forEach { c -> add(Triple(c.id, c.name, counts[c.id] ?: 0)) }
                if (Repo.radio.isNotEmpty()) add(Triple("radio", "Radio", Repo.radio.size))
            }
        }
        val displayedChans = remember(selectedCatId, Repo.version, Store.favs.size, sortTick) { liveChannels(selectedCatId) }
        val shown = if (moveId != null) work else displayedChans
        val plName = remember(Repo.version) { Store.cur()?.name?.ifBlank { null } ?: "Playlists" }
        val catListState = rememberLazyListState()
        val chanListState = rememberLazyListState()

        fun playFrom(idx: Int, ch: Item) {
            if (ch.fkey == cur?.fkey) { if (cur != null) fullScreen = true }
            else { activeChans = shown; playCat = selectedCatId; ci = idx }
        }
        fun chanMenu(ch: Item, idx: Int): MenuState {
            val a = mutableListOf<MenuAction>()
            a += MenuAction("▶  Play") { playFrom(idx, ch) }
            a += favAction(ch)
            a += MenuAction("⇅  Rearrange") { work = displayedChans; moveId = ch.fkey }
            if (LiveOrder.saved(selectedCatId).isNotEmpty()) a += MenuAction("Reset order of this list") { LiveOrder.clear(selectedCatId); sortTick++ }
            return MenuState(ch.name, a)
        }

        LaunchedEffect(selectedCatId) {
            val i = displayedChans.indexOfFirst { it.fkey == cur?.fkey }
            if (i >= 0) chanListState.scrollToItem(i)
        }
        // Make sure the D-pad always has somewhere to land: the playing channel if it is on screen, else the category list.
        LaunchedEffect(Unit) {
            delay(250)
            if (runCatching { activeChanFR.requestFocus() }.isFailure) runCatching { catFR.requestFocus() }
        }

        Column(Modifier.fillMaxSize().background(Theme.bg)) {
            // Top bar: Movies | Series | Live pills, playing channel, settings, clock
            Row(
                Modifier.fillMaxWidth().background(Theme.sidebar).padding(horizontal = 16.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                if (home != null) {
                    MainNav(2) { i -> if (i != 2) home.onTab(i) }
                } else Text("Live TV", fontSize = 15.sp, fontWeight = FontWeight.Bold, color = Theme.accent)
                Text("•", fontSize = 14.sp, color = Color.Gray)
                Text(cur?.name ?: "", Modifier.weight(1f), fontSize = 14.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                if (home != null) Chip(if (Vpn.up != null) "VPN ●" else "Settings", false, Modifier, 10.dp) { home.onSettings() }
                if (showClock) Text(clockFmt.format(Date(clock)), fontSize = 14.sp, fontWeight = FontWeight.Bold)
            }

            Row(Modifier.weight(1f).fillMaxWidth()) {
                // 1. Categories
                Column(Modifier.width(210.dp).fillMaxHeight().background(Theme.sidebar).padding(8.dp)) {
                    LazyColumn(
                        state = catListState,
                        modifier = Modifier.weight(1f).fillMaxWidth().onFocusChanged { catFocused = it.hasFocus },
                        verticalArrangement = Arrangement.spacedBy(4.dp)
                    ) {
                        itemsIndexed(categories) { _, (id, label, count) ->
                            val sel = id == selectedCatId
                            val m = if (sel) Modifier.focusRequester(catFR) else Modifier
                            if (id == "fav") FavItem(label, count, sel, m) { finishMove(false); selectedCatId = id }
                            else Button(
                                onClick = { finishMove(false); selectedCatId = id },
                                modifier = Modifier.fillMaxWidth().then(m),
                                colors = focusColors(if (sel) Theme.accent.copy(alpha = 0.35f) else Color.Transparent),
                                contentPadding = PaddingValues(horizontal = 10.dp, vertical = 7.dp)
                            ) {
                                Text(label, Modifier.weight(1f), fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            }
                        }
                    }
                    if (home != null) {
                        Spacer(Modifier.height(6.dp))
                        Chip("☰  $plName", false, Modifier.fillMaxWidth(), 8.dp) { home.onPlaylists() }
                    }
                }

                // 2. Channels, one per row
                LazyColumn(
                    state = chanListState,
                    modifier = Modifier.weight(1f).fillMaxHeight().padding(8.dp)
                        .onPreviewKeyEvent { e ->
                            val mv = moveId
                            if (mv == null) false
                            else {
                                val k = e.nativeKeyEvent.keyCode
                                val isDown = e.nativeKeyEvent.action == KeyEvent.ACTION_DOWN
                                val i = work.indexOfFirst { it.fkey == mv }
                                when (k) {
                                    KeyEvent.KEYCODE_DPAD_UP, KeyEvent.KEYCODE_DPAD_DOWN -> {
                                        val to = if (k == KeyEvent.KEYCODE_DPAD_UP) i - 1 else i + 1
                                        if (isDown && i >= 0 && to in work.indices) {
                                            val l = work.toMutableList(); java.util.Collections.swap(l, i, to); work = l
                                            val first = chanListState.firstVisibleItemIndex
                                            val n = chanListState.layoutInfo.visibleItemsInfo.size
                                            if (to <= first) scope.launch { chanListState.scrollToItem((to - 1).coerceAtLeast(0)) }
                                            else if (to >= first + n - 1) scope.launch { chanListState.scrollToItem((to - n + 2).coerceAtLeast(0)) }
                                        }
                                        true
                                    }
                                    KeyEvent.KEYCODE_DPAD_LEFT, KeyEvent.KEYCODE_DPAD_RIGHT -> true   // stay in the list while moving
                                    else -> false
                                }
                            }
                        },
                    verticalArrangement = Arrangement.spacedBy(5.dp)
                ) {
                    if (shown.isEmpty()) item {
                        Text(if (selectedCatId == "fav") "No favorites yet (hold OK on a channel)" else "Nothing here",
                            Modifier.padding(16.dp), fontSize = 13.sp, color = Theme.muted)
                    }
                    itemsIndexed(shown, key = { _, ch -> ch.fkey }) { idx, ch ->
                        val playingThis = ch.fkey == cur?.fkey
                        val isFav = Store.favs.contains(ch.fkey)
                        val grabbed = moveId == ch.fkey
                        val fr = when { grabbed -> Modifier.focusRequester(moveFR); playingThis -> Modifier.focusRequester(activeChanFR); else -> Modifier }
                        Card(
                            onClick = { if (moveId != null) finishMove(true) else playFrom(idx, ch) },
                            onLongClick = { if (moveId == null) menuCh = chanMenu(ch, idx) },
                            modifier = Modifier.fillMaxWidth().then(fr),
                            colors = CardDefaults.colors(
                                containerColor = if (grabbed) Theme.accent.copy(alpha = 0.55f) else if (playingThis) Theme.accent.copy(alpha = 0.28f) else Theme.card,
                                contentColor = Color.White,
                                focusedContainerColor = Theme.accent, focusedContentColor = Color.White,
                                pressedContainerColor = Theme.accent, pressedContentColor = Color.White
                            ),
                            scale = CardDefaults.scale(focusedScale = 1f)
                        ) {
                            Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 7.dp), verticalAlignment = Alignment.CenterVertically) {
                                val num = ChNum.num(ch, idx)
                                if (grabbed) Text("⇅", Modifier.width(34.dp), fontSize = 15.sp, fontWeight = FontWeight.Bold)
                                else if (num > 0) Text("$num", Modifier.width(34.dp), fontSize = 11.sp, color = Color.LightGray, maxLines = 1)
                                if (ch.img.isNotBlank()) Img(ch.img, Modifier.size(30.dp), ContentScale.Fit, 80, 80) else Spacer(Modifier.size(30.dp))
                                Spacer(Modifier.width(10.dp))
                                Text(ch.name, Modifier.weight(1f), fontSize = 13.sp,
                                    fontWeight = if (playingThis || grabbed) FontWeight.Bold else FontWeight.Normal,
                                    maxLines = 1, overflow = TextOverflow.Ellipsis)
                                run {
                                    val rq = if (playingThis) badgeQ else streamQuality(ch.name)
                                    val rf = if (playingThis) vfps else FpsMemory.get(ch.fkey).takeIf { it > 0 } ?: StreamInfo.nameFps(ch.name)
                                    if (rq != null || rf > 0) { Spacer(Modifier.width(6.dp)); StreamBadge(rq, rf) }
                                }
                                if (playingThis) Text("  ▶", fontSize = 11.sp)
                                if (isFav) Text("  ★", fontSize = 13.sp, color = Color(0xFFFFC107))
                            }
                        }
                    }
                }

                // 3. Preview + guide + buttons
                Column(
                    modifier = Modifier.width(360.dp).fillMaxHeight().background(Theme.panel).padding(12.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .aspectRatio(16f / 9f)
                            .clip(RoundedCornerShape(8.dp))
                            .background(Color.Black)
                            .border(1.dp, Theme.accent.copy(alpha = 0.5f), RoundedCornerShape(8.dp))
                            .onGloballyPositioned { slot = it.boundsInRoot() }
                    ) {
                        if (cur != null && buffering && err.isBlank()) Text("Loading...", Modifier.align(Alignment.Center), fontSize = 11.sp)
                        if (err.isNotBlank()) Text("Error: $err", Modifier.align(Alignment.Center), fontSize = 11.sp)
                    }

                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text(cur?.name ?: "", fontSize = 15.sp, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        StreamBadge(badgeQ, vfps, big = true)
                        if (moveId != null) {
                            Text("Moving: press Up / Down, then OK to drop. Back cancels.", fontSize = 12.sp, color = Theme.accent)
                        } else if (epg.isEmpty()) {
                            Text("No guide data", fontSize = 11.sp, color = Color.Gray)
                        } else {
                            epg.take(3).forEach { line -> Text(line, fontSize = 11.sp, maxLines = 1, overflow = TextOverflow.Ellipsis) }
                        }
                    }

                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        Chip("Full Screen", true, Modifier.weight(1f), 8.dp) { if (cur != null) fullScreen = true }
                        if (cur != null) {
                            Chip(if (Store.favs.contains(cur.fkey)) "★ Fav" else "☆ Fav", false, Modifier.weight(1f), 8.dp) { Store.fav(cur) }
                        }
                        if (onSearch != null) Chip("Search", false, Modifier.weight(1f), 8.dp) { onSearch() }
                    }
                }
            }
        }
        menuCh?.let { mm -> OptionsDialog(mm.title, mm.actions) { menuCh = null } }
    }

    // Single persistent PlayerView instance so switching between full screen and preview never recreates the surface
    val sl = slot
    val surfaceModifier = when {
        !split -> Modifier.fillMaxSize()
        sl != null -> Modifier
            .offset { IntOffset(sl.left.toInt(), sl.top.toInt()) }
            .size(with(density) { sl.width.toDp() }, with(density) { sl.height.toDp() })
            .clip(RoundedCornerShape(8.dp))
        else -> Modifier.size(1.dp).alpha(0f)
    }
    AndroidView(
        factory = { context ->
            PlayerView(context).apply {
                useController = false
                keepScreenOn = true
                isFocusable = false
                isFocusableInTouchMode = false
                setKeepContentOnPlayerReset(true)
                setShutterBackgroundColor(android.graphics.Color.TRANSPARENT)
            }
        },
        update = { view ->
            if (view.player !== player) {
                view.player = player
            }
            view.resizeMode = when (fit) {
                1 -> AspectRatioFrameLayout.RESIZE_MODE_FILL
                2 -> AspectRatioFrameLayout.RESIZE_MODE_ZOOM
                else -> AspectRatioFrameLayout.RESIZE_MODE_FIT
            }
            view.subtitleView?.setFractionalTextSize(0.0533f * subMult)
            view.requestLayout()
        },
        modifier = surfaceModifier
    )

    // ==================== FULL-SCREEN OVERLAYS ====================
    if (!split) {

        if (buffering && err.isBlank()) Text("Loading...", Modifier.align(Alignment.Center), fontSize = 14.sp)
        if (err.isNotBlank()) Text("Playback error: $err", Modifier.align(Alignment.Center), fontSize = 16.sp)
        if (isLive && banner && !menu) Row(Modifier.align(Alignment.TopStart).padding(24.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Text(chLabel(cur, ci, title), fontSize = 20.sp)
            StreamBadge(badgeQ, vfps, big = true)
        }
        val gd = guide
        if (guideShow && gd != null && !menu) Column(
            Modifier.align(Alignment.TopStart).padding(start = 24.dp, top = 20.dp)
                .alpha(0.6f).background(Color.Black, RoundedCornerShape(8.dp))
                .padding(horizontal = 10.dp, vertical = 6.dp)
        ) {
            Text("Parental guidance", fontSize = 9.sp)
            if (gd.cert.isNotBlank()) Text(gd.cert, fontSize = 15.sp, fontWeight = FontWeight.Bold)
            if (gd.tags.isNotEmpty()) Text(gd.tags.joinToString(" · ") { tr(it) }, fontSize = 10.sp, maxLines = 1)
        }
        if (digits.isNotEmpty()) Text(digits, Modifier.align(Alignment.TopEnd).padding(24.dp), fontSize = 34.sp, fontWeight = FontWeight.Bold)
        if (note.isNotEmpty()) Text(note, Modifier.align(Alignment.TopCenter).padding(top = 28.dp)
            .padding(horizontal = 16.dp, vertical = 6.dp), fontSize = 15.sp, fontWeight = FontWeight.Bold)
        if (showClock && (bar || menu)) Text(clockFmt.format(Date(clock)), Modifier.align(Alignment.TopEnd).padding(24.dp), fontSize = 16.sp)

        if (!isLive && (bar || menu)) {
            val multi = args.vod.size > 1
            Column(
                Modifier.align(Alignment.BottomStart).fillMaxWidth()
                    .background(Brush.verticalGradient(listOf(Color.Transparent, Color(0xE6000000))))
                    .padding(horizontal = 32.dp, vertical = 20.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text(title, Modifier.weight(1f, false), fontSize = 16.sp, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    StreamBadge(badgeQ, vfps, big = true)
                }
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text(fmtTime(pos), fontSize = 12.sp)
                    Box(Modifier.weight(1f).height(6.dp).clip(RoundedCornerShape(3.dp)).background(Color(0x55FFFFFF))) {
                        val frac = if (dur > 0) (pos.toFloat() / dur).coerceIn(0f, 1f) else 0f
                        Box(Modifier.fillMaxWidth(frac).fillMaxHeight().background(Theme.accent))
                    }
                    Text(fmtTime(dur), fontSize = 12.sp)
                }
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    if (multi) CtlBtn(Ico.PREV) { if (ci > 0) ci--; poke() }
                    CtlBtn(Ico.BACK10) { seek(-seekStep) }
                    CtlBtn(if (playing) Ico.PAUSE else Ico.PLAY, Modifier.focusRequester(ctlFR), big = true) { toggle() }
                    CtlBtn(Ico.STOP) { onExit() }
                    CtlBtn(Ico.FWD10) { seek(seekStep) }
                    if (multi) CtlBtn(Ico.NEXT) { if (ci < args.vod.size - 1) ci++; poke() }
                    Spacer(Modifier.weight(1f))
                    Chip("Aspect: " + listOf("Fit", "Fill", "Zoom")[fit], false, Modifier, 12.dp) { fit = (fit + 1) % 3; Store.put("aspect", fit); poke() }
                    Chip("Audio", false, Modifier, 12.dp) { note = "Audio: " + cycleTrack(player, C.TRACK_TYPE_AUDIO, false); poke() }
                    Chip("Subtitles", false, Modifier, 12.dp) { note = "Subtitles: " + cycleTrack(player, C.TRACK_TYPE_TEXT, true); poke() }
                    Chip("Speed: ${speeds[speedIdx]}x", false, Modifier, 12.dp) {
                        speedIdx = (speedIdx + 1) % speeds.size; player.setPlaybackSpeed(speeds[speedIdx]); poke()
                    }
                }
            }
        }

        if (menu) Column(
            Modifier.align(Alignment.TopStart).fillMaxWidth()
                .background(Brush.verticalGradient(listOf(Color(0xDD000000), Color.Transparent))).padding(24.dp)
        ) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Text(if (isLive) chLabel(cur, ci, title) else title, Modifier.weight(1f, false), fontSize = 18.sp, fontWeight = FontWeight.Bold, maxLines = 1)
                StreamBadge(badgeQ, vfps, big = true)
            }
            epg.forEach { Text(it, fontSize = 12.sp, maxLines = 1) }
            Row(Modifier.padding(top = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                if (!isLive) Chip(if (playing) "⏸  Pause" else "▶  Play", true, Modifier.focusRequester(menuFR)) { toggle(); menu = false }
                if (isLive) Chip("Channels", true, Modifier.focusRequester(menuFR)) { menu = false; fullScreen = false }
                Chip("Aspect: " + listOf("Fit", "Fill", "Zoom")[fit], false, if (isLive) Modifier else Modifier) {
                    fit = (fit + 1) % 3; Store.put("aspect", fit)
                }
                Chip("Audio", false) { note = "Audio: " + cycleTrack(player, C.TRACK_TYPE_AUDIO, false) }
                Chip("Subtitles", false) { note = "Subtitles: " + cycleTrack(player, C.TRACK_TYPE_TEXT, true) }
                if (!isLive) Chip("Speed: ${speeds[speedIdx]}x", false) {
                    speedIdx = (speedIdx + 1) % speeds.size; player.setPlaybackSpeed(speeds[speedIdx])
                }
                if (isLive && cur != null) Chip(if (Store.favs.contains(cur.fkey)) "★ Unfavorite" else "☆ Favorite", false) { Store.fav(cur) }
            }
        }
    }
    }
}


// ---------------------------------------------------------------- round icon buttons for the VOD control bar

enum class Ico { PLAY, PAUSE, STOP, PREV, NEXT, BACK10, FWD10 }

private fun DrawScope.drawIco(ico: Ico, c: Color) {
    val w = size.width; val h = size.height
    fun tri(x1: Float, y1: Float, x2: Float, y2: Float, x3: Float, y3: Float) {
        drawPath(Path().apply { moveTo(x1, y1); lineTo(x2, y2); lineTo(x3, y3); close() }, c)
    }
    when (ico) {
        Ico.PLAY -> tri(w * 0.15f, 0f, w * 0.15f, h, w, h / 2)
        Ico.PAUSE -> { drawRect(c, Offset(w * 0.1f, 0f), Size(w * 0.30f, h)); drawRect(c, Offset(w * 0.6f, 0f), Size(w * 0.30f, h)) }
        Ico.STOP -> drawRect(c, Offset(w * 0.08f, h * 0.08f), Size(w * 0.84f, h * 0.84f))
        Ico.PREV -> { drawRect(c, Offset(0f, 0f), Size(w * 0.16f, h)); tri(w, 0f, w, h, w * 0.22f, h / 2) }
        Ico.NEXT -> { drawRect(c, Offset(w * 0.84f, 0f), Size(w * 0.16f, h)); tri(0f, 0f, 0f, h, w * 0.78f, h / 2) }
        Ico.BACK10 -> { tri(w * 0.5f, 0f, w * 0.5f, h, 0f, h / 2); tri(w, 0f, w, h, w * 0.5f, h / 2) }
        Ico.FWD10 -> { tri(0f, 0f, 0f, h, w * 0.5f, h / 2); tri(w * 0.5f, 0f, w * 0.5f, h, w, h / 2) }
    }
}

/** Round, D-pad friendly icon button: grows and takes the accent colour when focused. */
@Composable
fun CtlBtn(ico: Ico, mod: Modifier = Modifier, big: Boolean = false, onClick: () -> Unit) {
    var foc by remember { mutableStateOf(false) }
    val sc by animateFloatAsState(if (foc) 1.15f else 1f, label = "ctl")
    Box(
        mod.size(if (big) 56.dp else 44.dp).scale(sc).clip(CircleShape)
            .background(if (foc) Theme.accent else Color(0x55FFFFFF))
            .onFocusChanged { foc = it.isFocused }
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center
    ) {
        val fg = if (foc) Theme.onAccent else Color.White
        Canvas(Modifier.size(if (big) 24.dp else 18.dp)) { drawIco(ico, fg) }
    }
}
