package com.iptv.tv

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext
import java.io.File

/** Library in memory + on-disk JSON cache so the app opens instantly and refreshes in the background. */
object Repo {
    var liveCats = listOf<Cat>(); var live = listOf<Item>(); var radioCats = listOf<Cat>(); var radio = listOf<Item>()
    var movieCats = listOf<Cat>(); var movies = listOf<Item>(); var serCats = listOf<Cat>(); var series = listOf<Item>()
    /** Every category the provider offers (before the "choose categories" filter), used by the picker screen. */
    var allLiveCats = listOf<Cat>(); var allMovieCats = listOf<Cat>(); var allSerCats = listOf<Cat>()
    var ready by mutableStateOf(false)
    var error by mutableStateOf("")
    var refreshing by mutableStateOf(false)
    var version by mutableStateOf(0)
    /** When the library currently on screen was fetched (shown in Settings). */
    var cacheTs by mutableStateOf(0L)
        private set

    /** Auto refresh setting: 0 off, 1 every 4 hours, 2 every 12 hours (default), 3 daily. Returns the interval in ms, 0 = off. */
    fun autoIntervalMs(): Long = when (Store.int("autoref", 2)) {
        1 -> 4 * 3600_000L
        2 -> 12 * 3600_000L
        3 -> 24 * 3600_000L
        else -> 0L
    }
    /** True when auto refresh is on and the library is older than the chosen interval. */
    fun stale(): Boolean { val iv = autoIntervalMs(); return iv > 0L && System.currentTimeMillis() - cacheTs >= iv }

    private val names = listOf("get_live_categories", "get_live_streams", "get_vod_categories", "get_vod_streams",
        "get_series_categories", "get_series")

    private fun selKey(kind: String) = "sel_${Store.pid()}_$kind"
    /** Saved category ids for live / movie / series, or null = keep everything. */
    fun selection(kind: String): Set<String>? =
        Store.str(selKey(kind), "").takeIf { it.isNotEmpty() }?.split(",")?.toHashSet()
    fun saveSelection(kind: String, ids: Set<String>?) {
        if (ids == null) Store.sp.edit().remove(selKey(kind)).apply() else Store.put(selKey(kind), ids.joinToString(","))
    }
    private fun validSel(kind: String, cats: List<Cat>): Set<String>? =
        selection(kind)?.takeIf { s -> cats.any { it.id in s } }   // ignore a stale selection that matches nothing

    private fun isRadio(n: String) = n.contains("radio", true) || n.contains("راديو") || n.contains("إذاعة") || n.contains("اذاعة")
    private fun file(pid: String, n: String) = File(Store.dir, "c_${pid}_$n.json")

    fun reset() {
        ready = false; error = ""; refreshing = false; cacheTs = 0L
        allLiveCats = emptyList(); allMovieCats = emptyList(); allSerCats = emptyList()
        liveCats = emptyList(); live = emptyList(); radioCats = emptyList(); radio = emptyList()
        movieCats = emptyList(); movies = emptyList(); serCats = emptyList(); series = emptyList()
        version++
    }

    /** Live categories + channels only: this is all that is needed to open the app on Live TV. */
    private fun applyLive(raw: Map<String, String?>) {
        val cats0 = Api.parseCats(raw["get_live_categories"] ?: "[]")
        allLiveCats = cats0
        val selL = validSel("live", cats0)
        val cats = if (selL == null) cats0 else cats0.filter { it.id in selL }
        val all0 = Api.parseItems(raw["get_live_streams"] ?: "[]", "live", "stream_id", "stream_icon")
        val all = if (selL == null) all0 else all0.filter { it.cat in selL }
        val rid = cats.filter { isRadio(it.name) }.map { it.id }.toSet()
        liveCats = cats.filter { it.id !in rid }; radioCats = cats.filter { it.id in rid }
        live = all.filter { it.cat !in rid }
        radio = all.filter { it.cat in rid }.map { it.copy(kind = "radio") }
        version++
    }

    /** Movies + series (the big lists). Parsed after Live TV is already on screen. */
    private fun applyVod(raw: Map<String, String?>) {
        allMovieCats = runCatching { Api.parseCats(raw["get_vod_categories"] ?: "[]") }.getOrDefault(emptyList())
        val selM = validSel("movie", allMovieCats)
        movieCats = if (selM == null) allMovieCats else allMovieCats.filter { it.id in selM }
        val mv = runCatching { Api.parseItems(raw["get_vod_streams"] ?: "[]", "movie", "stream_id", "stream_icon") }.getOrDefault(emptyList())
        movies = if (selM == null) mv else mv.filter { it.cat in selM }
        allSerCats = runCatching { Api.parseCats(raw["get_series_categories"] ?: "[]") }.getOrDefault(emptyList())
        val selS = validSel("series", allSerCats)
        serCats = if (selS == null) allSerCats else allSerCats.filter { it.id in selS }
        val sr = runCatching { Api.parseItems(raw["get_series"] ?: "[]", "series", "series_id", "cover") }.getOrDefault(emptyList())
        series = if (selS == null) sr else sr.filter { it.cat in selS }
        version++
    }

    private fun applyRaw(raw: Map<String, String?>) { applyLive(raw); applyVod(raw) }

    /** Re-applies the category selection from the on-disk cache (no network, no reload of the provider). */
    suspend fun reapply() {
        val pid = Store.pid()
        val raw = withContext(Dispatchers.IO) {
            val m = HashMap<String, String?>()
            names.forEach { n -> val f = file(pid, n); m[n] = if (f.exists()) runCatching { f.readText() }.getOrNull() else null }
            m
        }
        if (raw["get_live_streams"] == null) { load(true); return }
        withContext(Dispatchers.Default) { runCatching { applyRaw(raw) } }
    }

    /**
     * Reads the on-disk library and applies it. Live TV comes first (a few thousand channels): as soon as it is parsed the app is
     * ready and opens on Live. The much larger movie and series lists are read and parsed afterwards, in the background.
     * Returns the cache's timestamp, or null when there is no usable cache.
     */
    private suspend fun loadCache(): Long? {
        val pid = Store.pid()
        val liveNames = names.take(2)
        val cached = withContext(Dispatchers.IO) {
            val m = HashMap<String, String?>()
            liveNames.forEach { n -> val f = file(pid, n); m[n] = if (f.exists()) runCatching { f.readText() }.getOrNull() else null }
            if (m["get_live_streams"] == null) null else Pair(m, file(pid, "get_live_streams").lastModified())
        } ?: return null
        val ok = withContext(Dispatchers.Default) { runCatching { applyLive(cached.first) }.isSuccess }
        if (!ok) return null
        ready = true
        cacheTs = cached.second
        val vod = withContext(Dispatchers.IO) {
            val m = HashMap<String, String?>()
            names.drop(2).forEach { n -> val f = file(pid, n); m[n] = if (f.exists()) runCatching { f.readText() }.getOrNull() else null }
            m
        }
        withContext(Dispatchers.Default) { runCatching { applyVod(vod) } }
        return cached.second
    }

    /** Shows the saved library immediately (no network) so the app opens straight into it. */
    suspend fun showCached() { if (!ready) loadCache() }

    /** force = true always hits the network. Otherwise cached data is used and refreshed only when older than the auto refresh interval (Settings). */
    suspend fun load(force: Boolean = false) {
        val pid = Store.pid()
        error = ""
        if (!ready) loadCache()
        val shown = ready   // the saved library is already on screen: apply the refreshed data once, at the end
        val iv = autoIntervalMs()   // 0 = auto refresh off: a saved library is kept until the user refreshes by hand
        val fresh = shown && (iv == 0L || System.currentTimeMillis() - cacheTs < iv)
        if (fresh && !force) return
        refreshing = true
        try {
            // 1) Live data first (needed to open the app). Some providers reject many parallel requests, so the rest is sequential.
            val raw = HashMap<String, String?>()
            withContext(Dispatchers.IO) {
                names.forEach { n -> val f = file(pid, n); if (f.exists()) raw[n] = runCatching { f.readText() }.getOrNull() }
                coroutineScope {
                    listOf("get_live_categories", "get_live_streams").map { n -> n to async { Api.act(n) } }
                        .forEach { (n, j) -> val txt = j.await(); raw[n] = txt; runCatching { file(pid, n).writeText(txt) } }
                }
            }
            if (!shown) { withContext(Dispatchers.Default) { applyRaw(raw) }; ready = true }
            // 2) Movies and series, one request at a time, each retried once.
            var changed = shown
            for (n in names.drop(2)) {
                val txt = withContext(Dispatchers.IO) { fetchList(n) }
                if (txt != null) { raw[n] = txt; changed = true; withContext(Dispatchers.IO) { runCatching { file(pid, n).writeText(txt) } } }
            }
            if (changed) withContext(Dispatchers.Default) { applyRaw(raw) }
            cacheTs = System.currentTimeMillis()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            if (!ready) error = e.message ?: "Load failed"
        } finally {
            refreshing = false
        }
    }

    /** One list from the provider; retried once after a pause. Returns null when it keeps failing or is not a JSON list. */
    private suspend fun fetchList(n: String): String? {
        repeat(2) { attempt ->
            val t = runCatching { Api.act(n) }.getOrNull()
            if (t != null && t.trimStart().startsWith("[")) return t
            if (attempt == 0) kotlinx.coroutines.delay(1500)
        }
        return null
    }
}
