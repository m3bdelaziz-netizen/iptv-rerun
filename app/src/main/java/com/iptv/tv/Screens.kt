@file:OptIn(ExperimentalTvMaterial3Api::class)
package com.iptv.tv

import android.Manifest
import android.app.Activity
import android.content.pm.PackageManager
import android.os.Build
import android.net.VpnService
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.tv.material3.Button
import androidx.tv.material3.ButtonDefaults
import androidx.tv.material3.ExperimentalTvMaterial3Api
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@Composable
fun Opt(label: String, value: String, f: () -> Unit) {
    Button(
        onClick = f, modifier = Modifier.fillMaxWidth(),
        colors = focusColors(Theme.chip),
        contentPadding = PaddingValues(horizontal = 12.dp, vertical = 5.dp)
    ) {
        Text(label, Modifier.weight(1f), fontSize = 13.sp, maxLines = 1, color = Color.White)
        Text(value, fontSize = 13.sp, fontWeight = FontWeight.Bold, maxLines = 1, color = Color.White)
    }
}

@Composable
private fun Swatch(c: Color, sel: Boolean, f: () -> Unit) {
    Button(
        onClick = f, modifier = Modifier.width(32.dp).height(30.dp),
        colors = ButtonDefaults.colors(containerColor = c, focusedContainerColor = c),
        contentPadding = PaddingValues(0.dp)
    ) { Text(if (sel) "✓" else "", fontSize = 12.sp, color = Color.White) }
}

@Composable
private fun Group(t: String) {
    Text(t, Modifier.padding(top = 8.dp, bottom = 0.dp), fontSize = 13.sp, fontWeight = FontWeight.Bold, color = Theme.accent)
}

@Composable
fun SettingsScreen(goVpn: () -> Unit, goPl: () -> Unit, goPhone: () -> Unit, goCats: () -> Unit, refresh: ((Boolean) -> Unit) -> Unit, onRestored: () -> Unit) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    var tick by remember { mutableStateOf(0) }
    var tmdb by remember { mutableStateOf(Store.str("tmdb", "")) }
    var omdb by remember { mutableStateOf(Store.str("omdb", "")) }
    var msg by remember { mutableStateOf("") }
    var epgList by remember { mutableStateOf(Epg.urls()) }
    var epgNew by remember { mutableStateOf("") }
    var remoteSlot by remember { mutableStateOf<Int?>(null) }
    var crash by remember { mutableStateOf(runCatching { java.io.File(Store.dir, "crash.log").takeIf { it.exists() }?.readText()?.take(900) }.getOrNull()) }
    @Suppress("UNUSED_VARIABLE") val t = tick

    // Backup: written straight to Downloads/Waleen (+ the app folder). No system file picker, so it works on TVs without a file manager.
    var restoreList by remember { mutableStateOf<List<Backups.Found>?>(null) }
    var cacheText by remember { mutableStateOf("") }
    LaunchedEffect(tick) { cacheText = withContext(Dispatchers.IO) { CacheTools.human(CacheTools.bytes(ctx)) } }

    fun backupNow() { scope.launch { msg = withContext(Dispatchers.IO) { Backups.save(ctx) } } }
    val storagePerm = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { backupNow() }
    fun doBackup() {
        if (Build.VERSION.SDK_INT in 23..28 && ctx.checkSelfPermission(Manifest.permission.WRITE_EXTERNAL_STORAGE) != PackageManager.PERMISSION_GRANTED)
            runCatching { storagePerm.launch(Manifest.permission.WRITE_EXTERNAL_STORAGE) }.onFailure { backupNow() }
        else backupNow()
    }
    fun applyRestore(txt: String?) {
        val err = if (txt == null) "Cannot read file" else Backup.restore(txt)
        if (err == null) { msg = "Restored"; onRestored() } else msg = err
    }
    val restorePicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) scope.launch { applyRestore(withContext(Dispatchers.IO) { Backups.readUri(ctx, uri) }) }
    }
    fun doRestore() { scope.launch { restoreList = withContext(Dispatchers.IO) { Backups.list(ctx) } } }

    val lastFmt = remember { java.text.SimpleDateFormat("dd/MM HH:mm", java.util.Locale.US) }
    fun doRefresh() {
        if (Repo.refreshing) { msg = "Already refreshing..."; return }
        msg = "Refreshing playlist..."
        refresh { ok -> msg = if (ok) "Playlist refreshed" else "Refresh failed. Check your connection." }
    }
    fun doClearCache() {
        scope.launch {
            withContext(Dispatchers.IO) { CacheTools.clear(ctx) }
            tick++
            if (Repo.refreshing) { msg = "Cache cleared"; return@launch }
            msg = "Cache cleared. Reloading library..."
            refresh { ok -> msg = if (ok) "Cache cleared and library reloaded" else "Cache cleared. Reload failed: use Refresh playlist now" }
        }
    }

    fun cyc(k: String, n: Int, d: Int = 0) { Store.put(k, (Store.int(k, d) + 1) % n); tick++ }
    fun tog(k: String, d: Boolean) { Store.put(k, !Store.bool(k, d)); tick++ }
    fun cycStr(k: String, vararg v: String) { val i = v.indexOf(Store.str(k, v[0])); Store.put(k, v[(i + 1) % v.size]); tick++ }
    fun pick(k: String, d: Int, vararg l: String) = l[Store.int(k, d).coerceIn(0, l.size - 1)]
    fun onoff(b: Boolean) = if (b) "On" else "Off"
    fun lang(v: String) = when (v) { "ar" -> "العربية"; "en" -> "English"; else -> "Auto / تلقائي" }
    fun track(v: String) = when (v) { "ar" -> "Arabic"; "en" -> "English"; else -> "Auto" }

    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 24.dp, vertical = 14.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(16.dp)) {
            Text("Settings", fontSize = 20.sp, fontWeight = FontWeight.Bold, color = Color.White)
            Chip("Setup from phone (QR)", true) { goPhone() }
        }
        // Two columns so more fits on one screen; the whole page scrolls together.
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(24.dp), verticalAlignment = Alignment.Top) {
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(5.dp)) {
        Group("General")
        Opt("Language / اللغة", lang(Store.str("lang", "auto"))) {
            cycStr("lang", "auto", "en", "ar"); Lang.apply()
        }
        Opt("Start screen", pick("start", 2, "Movies", "Series", "Live TV", "Last channel (full screen)")) { cyc("start", 4, 2) }
        Opt("Channel numbers", pick("chnum", 0, "List position", "Provider number", "Same in every category", "Off")) { cyc("chnum", 4) }
        Opt("Sort: Movies & Series", pick("sortVod", 0, "Provider order", "Recently added", "A-Z", "Top rated")) { cyc("sortVod", 4) }
        Opt("Sort: Live TV", pick("sortLive", 0, "Provider order", "A-Z", "Manual (my order)")) { cyc("sortLive", 3) }
        Text("Manual order: hold OK on a channel, then choose Rearrange.", fontSize = 11.sp, color = Theme.muted)
        Chip("Reset all manual channel orders", false) { LiveOrder.clearAll(); msg = "Saved" }
        Opt("Press Back twice to exit", onoff(Store.bool("confirmExit", true))) { tog("confirmExit", true) }

        Group("Appearance")
        Text(tr("Theme") + ": " + tr(Theme.modes[Theme.mode]), Modifier.padding(top = 4.dp), fontSize = 13.sp, color = Color.White)
        Row(horizontalArrangement = Arrangement.spacedBy(5.dp)) {
            Theme.palettes.forEachIndexed { i, pal -> Swatch(Color(pal.chip), i == Theme.mode) { Theme.setMode(i); tick++ } }
        }
        Text(tr("Accent color") + ": " + tr(Theme.accents[Theme.accentIdx].first), Modifier.padding(top = 4.dp), fontSize = 13.sp, color = Color.White)
        Row(horizontalArrangement = Arrangement.spacedBy(5.dp)) {
            Theme.accents.forEachIndexed { i, a -> Swatch(Color(a.second), i == Theme.accentIdx) { Theme.setAccent(i); tick++ } }
        }
        Opt("Poster size", pick("grid", 1, "Small", "Medium", "Large")) { cyc("grid", 3, 1) }
        Opt("Titles under posters", onoff(!Store.bool("hideLabels", false))) { tog("hideLabels", false) }
        Opt("Show ratings", onoff(Store.bool("rating", true))) { tog("rating", true) }

        Group("Playback")
        Opt("Stream format", if (Store.str("fmt", "m3u8") == "ts") "TS" else "HLS (m3u8)") { cycStr("fmt", "m3u8", "ts") }
        Opt("Buffer", pick("buf", 1, "Fast start", "Balanced", "Large")) { cyc("buf", 3, 1) }
        Opt("Default aspect", pick("aspect", 0, "Fit", "Fill", "Zoom")) { cyc("aspect", 3) }
        Opt("Resume where I stopped", onoff(Store.bool("resume", true))) { tog("resume", true) }
        Opt("Auto-play next episode", onoff(Store.bool("autonext", true))) { tog("autonext", true) }
        Opt("Seek step (Left / Right)", pick("seek", 0, "10 s", "30 s")) { cyc("seek", 2) }
        Opt("Show clock in player", onoff(Store.bool("clock", true))) { tog("clock", true) }
        Opt("Audio language", track(Store.str("alang", ""))) { cycStr("alang", "", "ar", "en") }
        Opt("Subtitle language", track(Store.str("slang", ""))) { cycStr("slang", "", "ar", "en") }
        Opt("Subtitle size", pick("ssize", 1, "Small", "Normal", "Large", "Huge")) { cyc("ssize", 4, 1) }

        Group("Backup")
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Chip("Backup to internal storage", true) { doBackup() }
            Chip("Restore from backup", false) { doRestore() }
        }
        Text("Saved to Downloads/Waleen (no file manager needed). Contains playlists, favorites, history, settings, VPN profiles and channel order. The file contains your logins: keep it private.", fontSize = 11.sp, color = Theme.muted)

            }
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(5.dp)) {
        Group("Remote control")
        Text("Press OK on a row, then choose what that key does. Short presses (Left / Right / Up / Down) work in Live full screen; holds work in the live list, movies / series and the live player. P+ / P- move a list one page.", fontSize = 11.sp, color = Theme.muted)
        for (slot in Remote.slotNames.indices) Opt(Remote.slotNames[slot], Remote.label(slot)) { remoteSlot = slot }
        Chip("Reset remote control", false) { Remote.reset(); tick++; msg = "Saved" }
        Group("Library")
        Opt("Refresh playlist now", if (Repo.refreshing) "Refreshing..." else if (Repo.cacheTs > 0L) "Last: " + lastFmt.format(java.util.Date(Repo.cacheTs)) else "") { doRefresh() }
        Opt("Auto refresh", pick("autoref", 2, "Off", "Every 4 hours", "Every 12 hours", "Every 24 hours")) { cyc("autoref", 4, 2) }
        Opt("Clear cache", cacheText) { doClearCache() }
        Text("Clear cache removes posters and temporary files, then reloads the lists. Playlists, favorites and settings are kept.", fontSize = 11.sp, color = Theme.muted)
        Chip("Choose categories", true) { goCats() }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Chip("Playlists", false) { goPl() }
            Chip("VPN profiles", false) { goVpn() }
        }

        Group("EPG (optional)")
        Text("Add as many XMLTV guide links as you like (.xml or .xml.gz). Leave empty to use the provider's guide.", fontSize = 11.sp, color = Theme.muted)
        epgList.forEach { u ->
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(u, Modifier.weight(1f), maxLines = 1, fontSize = 12.sp, color = Color.White)
                Chip("Remove", false) { Epg.remove(u); epgList = Epg.urls() }
            }
        }
        Field("EPG URL (XMLTV)", epgNew, frac = 1f) { epgNew = it }
        Chip("Add EPG URL", false) {
            val n = Epg.add(epgNew); epgList = Epg.urls()
            msg = if (n > 0) { epgNew = ""; "Added" } else "Enter a valid http(s) link"
        }
        Field("TMDB API key v3 (rating + age rating)", tmdb, frac = 1f) { tmdb = it }
        Field("OMDb API key (exact IMDb rating, optional)", omdb, frac = 1f) { omdb = it }
        Chip("Save keys", true) { Store.put("tmdb", tmdb.trim()); Store.put("omdb", omdb.trim()); Api.c = Api.c.copy(tmdb = tmdb.trim()); msg = "Saved" }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Chip("Clear favorites", false) { Store.clearFavs(); msg = "Saved" }
            Chip("Clear continue watching", false) { History.clear(); msg = "Saved" }
        }
            }
        }
        if (msg.isNotBlank()) Text(msg, color = Color.White)
        crash?.let { c ->
            Group("Last crash (send this text to get it fixed)")
            Text(c, fontSize = 10.sp, color = Color.White)
            Chip("Clear crash log", false) { runCatching { java.io.File(Store.dir, "crash.log").delete() }; crash = null }
        }
    }

    remoteSlot?.let { sl ->
        OptionsDialog(tr(Remote.slotNames[sl]), Remote.choices(sl).map { a ->
            MenuAction((if (Remote.get(sl) == a) "✓  " else "") + Remote.labels[a]) { Remote.set(sl, a); tick++ }
        }, 340.dp, "", false) { remoteSlot = null }
    }

    restoreList?.let { found ->
        val fmt = java.text.SimpleDateFormat("dd/MM/yyyy HH:mm", java.util.Locale.US)
        val acts = found.take(12).map { f ->
            MenuAction(fmt.format(java.util.Date(f.time)) + "  -  " + CacheTools.human(f.size) + "  -  " + f.place) {
                scope.launch { applyRestore(withContext(Dispatchers.IO) { f.read() }) }
            }
        } + MenuAction("Browse files...") {
            runCatching { restorePicker.launch(arrayOf("*/*")) }.onFailure { msg = "No file manager on this TV" }
        }
        OptionsDialog(if (found.isEmpty()) "No backup found. Make a backup first." else "Choose a backup to restore", acts, 480.dp, "", false) { restoreList = null }
    }
}

@Composable
fun VpnScreen() {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    var profiles by remember { mutableStateOf(Vpn.list()) }
    var auto by remember { mutableStateOf(Store.sp.getString("vpn_auto", "") ?: "") }
    var url by remember { mutableStateOf("") }
    var paste by remember { mutableStateOf("") }
    var pname by remember { mutableStateOf("") }
    var note by remember { mutableStateOf("") }
    var pending by remember { mutableStateOf<VpnP?>(null) }
    fun connect(p: VpnP) { scope.launch { withContext(Dispatchers.IO) { Vpn.connect(ctx, p) }; note = Vpn.msg } }
    val perm = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { r ->
        if (r.resultCode == Activity.RESULT_OK) pending?.let { connect(it) } else note = "VPN permission denied"
    }
    fun start(p: VpnP) { val i = VpnService.prepare(ctx); if (i == null) connect(p) else { pending = p; perm.launch(i) } }
    fun add(n: String, t: String) { note = Vpn.add(n, t) ?: "Added '$n'"; profiles = Vpn.list() }
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) runCatching {
            val t = ctx.contentResolver.openInputStream(uri)!!.bufferedReader().readText()
            add((uri.lastPathSegment ?: "profile").substringAfterLast('/').removeSuffix(".conf"), t)
        }.onFailure { note = "Cannot read file" }
    }
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(24.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text("VPN profiles (WireGuard .conf)", fontWeight = FontWeight.Bold, color = Color.White)
        Text(if (Vpn.up != null) "Connected: ${Vpn.up}" else "Not connected", fontSize = 13.sp, color = Color.White)
        profiles.forEach { p ->
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(p.name, Modifier.width(160.dp), maxLines = 1, color = Color.White)
                if (Vpn.up == p.name) Chip("Disconnect", true) { scope.launch { withContext(Dispatchers.IO) { Vpn.disconnect(ctx) } } }
                else Chip("Connect", false) { start(p) }
                Chip(if (auto == p.name) "Auto-connect: ON" else "Auto-connect: off", auto == p.name) {
                    auto = if (auto == p.name) "" else p.name; Store.sp.edit().putString("vpn_auto", auto).apply()
                }
                Chip("Delete", false) { Vpn.remove(p.name); profiles = Vpn.list() }
            }
        }
        Text("Add profile", fontWeight = FontWeight.Bold, color = Color.White)
        Chip("Import .conf from file", false) { picker.launch(arrayOf("*/*")) }
        Field("Direct link to .conf (optional)", url) { url = it }
        Chip("Import from link", false) {
            scope.launch {
                val r = runCatching { withContext(Dispatchers.IO) { Api.get(url) } }
                val txt = r.getOrNull()
                if (txt != null) { add(url.substringAfterLast('/').removeSuffix(".conf").ifBlank { "profile" }, txt) } else { note = "Download failed" }
            }
        }
        Field("Or paste: profile name", pname) { pname = it }
        Field("Paste full .conf text", paste) { paste = it }
        Chip("Add pasted profile", false) { add(pname.ifBlank { "profile" }, paste) }
        if (note.isNotBlank()) Text(note, fontSize = 13.sp, color = Color.White)
    }
}

