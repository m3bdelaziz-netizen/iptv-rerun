package com.iptv.tv

import android.content.Context
import android.content.SharedPreferences
import android.net.Uri
import android.util.Base64
import androidx.compose.runtime.mutableStateListOf
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

private val YEAR_RE = Regex("(19|20)\\d{2}")

data class Creds(val server: String, val user: String, val pass: String, val tmdb: String)
data class Cat(val id: String, val name: String)
data class Item(val id: String, val name: String, val img: String, val kind: String, val cat: String,
                val ext: String = "", val added: Long = 0, val rating: String = "", val epgId: String = "", val num: Int = 0) {
    val nn: String by lazy(LazyThreadSafetyMode.NONE) { nz(name) }
    val year: String by lazy(LazyThreadSafetyMode.NONE) { YEAR_RE.find(name)?.value ?: "" }
    /** Quality badge text (4K / FHD / HD / SD) and the fps written in the name: computed once per item, not on every scroll. */
    val quality: String? by lazy(LazyThreadSafetyMode.NONE) { streamQuality(name) }
    val nameFps: Int by lazy(LazyThreadSafetyMode.NONE) { StreamInfo.nameFps(name) }
    val fkey: String = "$kind:$id"
}
data class Ep(val id: String, val title: String, val season: Int, val num: Int, val ext: String,
              val img: String = "", val plot: String = "", val dur: String = "")
data class EpInfo(val title: String, val still: String, val overview: String, val runtime: Int, val air: String, val vote: String)
data class Rec(val title: String, val alt: String)
data class Person(val name: String, val role: String, val img: String)
data class Trailer(val title: String, val key: String)
data class Meta(val backdrop: String?, val overview: String, val rating: String, val year: String, val id: Int = 0, val cast: String = "", val director: String = "", val cert: String = "", val trailer: String = "",
                val ratingSrc: String = "", val note: String = "",
                val genres: String = "", val runtime: String = "", val writer: String = "", val status: String = "", val country: String = "",
                val logo: String = "", val release: String = "", val people: List<Person> = emptyList(), val trailers: List<Trailer> = emptyList(),
                val rt: String = "", val mc: String = "", val awards: String = "", val recs: List<Rec> = emptyList(), val imdb: String = "")
data class Guide(val cert: String, val tags: List<String>)

data class Prog(val start: Long, val stop: Long, val title: String)
data class Pl(val id: String, val name: String, val server: String, val user: String, val pass: String)

fun JSONObject.s(k: String): String = if (isNull(k)) "" else optString(k)
private fun enc(s: String) = URLEncoder.encode(s, "UTF-8")

object Store {
    lateinit var sp: SharedPreferences
    lateinit var dir: File
    val favs = mutableStateListOf<String>()

    /** Index of the playlist in use. Written with commit() so it survives the app being killed right after a change. */
    var active: Int
        get() = sp.getInt("active", 0)
        set(v) { sp.edit().putInt("active", v).commit() }

    private fun backup() = File(dir, "playlists.json")

    fun init(c: Context) {
        sp = c.getSharedPreferences("cfg", 0)
        dir = c.filesDir
        migrate()
        if (!sp.contains("pls")) {
            // Prefs were wiped (or first run after an update): restore from the backup file, else migrate the old single-server settings.
            val txt = runCatching { backup().readText() }.getOrNull()
            val n = if (txt == null) 0 else runCatching { JSONArray(txt).length() }.getOrDefault(0)
            if (txt != null && n > 0) sp.edit().putString("pls", txt).commit()
            else if (!sp.getString("server", "").isNullOrBlank())
                savePls(listOf(Pl("0", "Default", sp.getString("server", "")!!, sp.getString("user", "") ?: "", sp.getString("pass", "") ?: "")))
        }
    }
    fun bool(k: String, d: Boolean) = sp.getBoolean(k, d)
    /** Old versions had a single "sort" setting; it becomes the movies/series sort. */
    fun migrate() { if (!sp.contains("sortVod") && sp.contains("sort")) sp.edit().putInt("sortVod", sp.getInt("sort", 0)).apply() }
    fun int(k: String, d: Int) = sp.getInt(k, d)
    fun str(k: String, d: String) = sp.getString(k, d) ?: d
    fun put(k: String, v: Any) {
        val e = sp.edit()
        when (v) { is Boolean -> e.putBoolean(k, v); is Int -> e.putInt(k, v); is Long -> e.putLong(k, v); else -> e.putString(k, v.toString()) }
        e.apply()
    }
    fun pls(): List<Pl> {
        val a = runCatching { JSONArray(sp.getString("pls", "[]") ?: "[]") }.getOrDefault(JSONArray())
        return List(a.length()) { val o = a.getJSONObject(it); Pl(o.s("id"), o.s("n"), o.s("s"), o.s("u"), o.s("p")) }
    }
    fun savePls(l: List<Pl>) {
        val a = JSONArray()
        l.forEach { a.put(JSONObject().put("id", it.id).put("n", it.name).put("s", it.server).put("u", it.user).put("p", it.pass)) }
        sp.edit().putString("pls", a.toString()).commit()
        runCatching { backup().writeText(a.toString()) }
    }
    /** The playlist in use (falls back to the first one if the saved index is stale). */
    fun cur(): Pl? { val l = pls(); return l.getOrNull(active) ?: l.firstOrNull() }
    fun pid(): String = cur()?.id ?: "0"

    /** Deletes playlist [i] together with its cached library, favourites and history; keeps the active index pointing at the same playlist. */
    fun removePl(i: Int) {
        val l = pls()
        if (i !in l.indices) return
        val gone = l[i]
        val rest = l.filterIndexed { j, _ -> j != i }
        var a = active
        if (i < a) a--
        a = a.coerceIn(0, maxOf(0, rest.size - 1))
        savePls(rest)
        active = a
        sp.edit().remove("fav2_${gone.id}").remove("favs_${gone.id}").remove("hist_${gone.id}")
            .remove("sel_${gone.id}_live").remove("sel_${gone.id}_movie").remove("sel_${gone.id}_series").commit()
        runCatching { dir.listFiles { f -> f.name.startsWith("c_${gone.id}_") }?.forEach { it.delete() } }
    }

    fun loadFavs() {
        favs.clear()
        val id = pid()
        val cur = sp.getStringSet("fav2_$id", null)
        if (cur != null) favs.addAll(cur)
        else sp.getStringSet("favs_$id", emptySet())?.forEach { favs.add("live:$it") }
    }
    fun isFav(i: Item) = favs.contains(i.fkey)
    fun fav(i: Item) {
        if (!favs.remove(i.fkey)) favs.add(i.fkey)
        sp.edit().putStringSet("fav2_" + pid(), favs.toSet()).apply()
    }
    fun clearFavs() { favs.clear(); sp.edit().putStringSet("fav2_" + pid(), emptySet()).apply() }
}

object Api {
    var c = Creds("", "", "", "")
    private val cache: MutableMap<String, Meta> = java.util.Collections.synchronizedMap(HashMap<String, Meta>())

    fun clearMeta() { cache.clear(); pcache.clear(); tcache.clear(); rawCache.clear(); ocache.clear() }
    fun normUrl(server: String): String { val s = server.trim().trimEnd('/'); return if (s.startsWith("http", true)) s else "http://$s" }
    fun base(): String = normUrl(c.server)

    fun get(url: String, rt: Int = 90000): String {
        val cn = URL(url).openConnection() as HttpURLConnection
        cn.connectTimeout = minOf(15000, rt); cn.readTimeout = rt
        cn.setRequestProperty("User-Agent", "Mozilla/5.0 (Linux; Android TV)")
        try { return cn.inputStream.bufferedReader().use { it.readText() } } finally { cn.disconnect() }
    }
    private fun auth(u: String = c.user, p: String = c.pass) = "username=${enc(u)}&password=${enc(p)}"
    /** Xtream "player_api.php" call. [extra] is a raw query tail such as "vod_id=12". */
    fun act(a: String, extra: String = "", rt: Int = 90000) = get("${base()}/player_api.php?${auth()}&action=$a${if (extra.isNotEmpty()) "&$extra" else ""}", rt)

    // ------------------------------------------------------------------ login
    /** True when the server accepts the account. Throws with a readable message on network errors / expired accounts. */
    fun test(server: String, user: String, pass: String): Boolean {
        val j = JSONObject(get("${normUrl(server)}/player_api.php?${auth(user, pass)}", 20000))
        val ui = j.optJSONObject("user_info") ?: return false
        val ok = ui.optInt("auth", 0) == 1 || ui.optBoolean("auth", false)
        if (!ok) return false
        val st = ui.s("status")
        if (st.equals("Expired", true) || st.equals("Disabled", true) || st.equals("Banned", true)) throw IOException("Account is $st")
        return true
    }
    fun login(): Boolean = test(c.server, c.user, c.pass)

    // ------------------------------------------------------------------ stream urls
    private fun p(s: String) = Uri.encode(s)
    fun liveUrls(id: String): List<String> {
        val b = "${base()}/live/${p(c.user)}/${p(c.pass)}/$id"
        return if (Store.str("fmt", "m3u8") == "ts") listOf("$b.ts", "$b.m3u8") else listOf("$b.m3u8", "$b.ts")
    }
    fun vodUrl(id: String, ext: String) = "${base()}/movie/${p(c.user)}/${p(c.pass)}/$id.${ext.ifBlank { "mp4" }}"
    fun epUrl(id: String, ext: String) = "${base()}/series/${p(c.user)}/${p(c.pass)}/$id.${ext.ifBlank { "mp4" }}"

    // ------------------------------------------------------------------ library parsing
    /** Reads any JSON scalar as text (strings, numbers, booleans); null and nested values give "". */
    private fun android.util.JsonReader.str(): String = when (peek()) {
        android.util.JsonToken.STRING, android.util.JsonToken.NUMBER -> nextString()
        android.util.JsonToken.BOOLEAN -> nextBoolean().toString()
        android.util.JsonToken.NULL -> { nextNull(); "" }
        else -> { skipValue(); "" }
    }

    /** Streaming parse (no full JSON tree in memory): much faster and lighter than JSONArray for lists of tens of thousands. */
    fun parseCats(text: String): List<Cat> {
        val out = ArrayList<Cat>()
        val r = android.util.JsonReader(java.io.StringReader(text))
        r.isLenient = true
        r.beginArray()
        while (r.hasNext()) {
            if (r.peek() != android.util.JsonToken.BEGIN_OBJECT) { r.skipValue(); continue }
            var id = ""; var name = ""
            r.beginObject()
            while (r.hasNext()) when (r.nextName()) {
                "category_id" -> id = r.str()
                "category_name" -> name = r.str()
                else -> r.skipValue()
            }
            r.endObject()
            if (id.isNotBlank()) out.add(Cat(id, name))
        }
        r.endArray()
        return out
    }

    fun parseItems(text: String, kind: String, idKey: String, imgKey: String): List<Item> {
        val out = ArrayList<Item>(4096)
        val r = android.util.JsonReader(java.io.StringReader(text))
        r.isLenient = true
        r.beginArray()
        while (r.hasNext()) {
            if (r.peek() != android.util.JsonToken.BEGIN_OBJECT) { r.skipValue(); continue }
            var id = ""; var name = ""; var img = ""; var cat = ""; var ext = ""
            var added = ""; var lastMod = ""; var rating = ""; var rating5 = ""; var epg = ""; var num = ""
            r.beginObject()
            while (r.hasNext()) {
                val k = r.nextName()
                when {
                    k == idKey -> id = r.str()
                    k == imgKey -> img = r.str()
                    k == "name" -> name = r.str()
                    k == "category_id" -> cat = r.str()
                    k == "container_extension" -> ext = r.str()
                    k == "added" -> added = r.str()
                    k == "last_modified" -> lastMod = r.str()
                    k == "rating" -> rating = r.str()
                    k == "rating_5based" -> rating5 = r.str()
                    k == "epg_channel_id" -> epg = r.str()
                    k == "num" -> num = r.str()
                    else -> r.skipValue()
                }
            }
            r.endObject()
            if (id.isBlank()) continue
            out.add(Item(id, name, img, kind, cat, ext,
                added.ifBlank { lastMod }.toLongOrNull() ?: 0L,
                rating.ifBlank { rating5 }, epg, num.toIntOrNull() ?: 0))
        }
        r.endArray()
        out.trimToSize()
        return out
    }

    fun episodes(seriesId: String): List<Ep> {
        val j = JSONObject(rawInfo(true, seriesId))
        val e = j.opt("episodes")
        val out = mutableListOf<Ep>()
        fun addArr(a: JSONArray, fallbackSeason: Int) {
            for (i in 0 until a.length()) {
                val o = a.optJSONObject(i) ?: continue
                val n = o.optInt("episode_num", i + 1)
                val season = o.optInt("season", 0).takeIf { it > 0 } ?: fallbackSeason
                val inf = o.optJSONObject("info")
                val secs = inf?.optInt("duration_secs", 0) ?: 0
                val dm = if (secs > 0) secs / 60 else durMin(inf?.s("duration").orEmpty())
                out.add(Ep(o.s("id"), o.s("title").ifBlank { "Episode $n" }, season, n, o.s("container_extension").ifBlank { "mp4" },
                    inf?.s("movie_image").orEmpty(), inf?.s("plot").orEmpty(), fmtRun(dm)))
            }
        }
        when (e) {
            is JSONObject -> e.keys().asSequence().toList().forEach { k -> e.optJSONArray(k)?.let { addArr(it, k.toIntOrNull() ?: 1) } }
            is JSONArray -> if (e.length() > 0 && e.opt(0) is JSONArray) {
                for (i in 0 until e.length()) e.optJSONArray(i)?.let { addArr(it, i + 1) }
            } else addArr(e, 1)
        }
        return out.sortedWith(compareBy({ it.season }, { it.num }))
    }

    private fun b64(s: String) = runCatching { String(Base64.decode(s, Base64.DEFAULT), Charsets.UTF_8) }.getOrDefault(s)

    /** Next programmes of a live channel, as "HH:mm  Title" lines. */
    fun epg(streamId: String): List<String> {
        val j = JSONObject(act("get_short_epg", "stream_id=$streamId&limit=3"))
        val a = j.optJSONArray("epg_listings") ?: return emptyList()
        val f = SimpleDateFormat("HH:mm", Locale.getDefault())
        val out = ArrayList<String>()
        for (i in 0 until a.length()) {
            val o = a.optJSONObject(i) ?: continue
            val t = b64(o.s("title")).trim()
            if (t.isBlank()) continue
            val st = o.s("start_timestamp").toLongOrNull()
            out.add((if (st != null) f.format(Date(st * 1000)) + "  " else "") + t)
        }
        return out
    }

    // ------------------------------------------------------------------ TMDB / provider details
    private const val TMDB = "https://api.themoviedb.org/3"
    private const val IMG = "https://image.tmdb.org/t/p/w1280"

    /** "EN - Movie Name (2020) 4K" -> "Movie Name" so TMDB can find it. */
    fun cleanTitle(n: String): String {
        var s = n
        s = s.replace(Regex("\\[[^\\]]*\\]|\\([^)]*\\)|\\{[^}]*\\}"), " ")
        s = s.replace(Regex("^\\s*\\|[^|]*\\|\\s*"), "")
        s = s.replace(Regex("^\\s*[A-Z]{2,3}\\s*[:|\\-]\\s*"), "")
        s = s.replace(Regex("(?i)\\b(4k|uhd|fhd|hd|sd|hdr|1080p|720p|2160p|bluray|web-?dl|multi|dubbed|مدبلج|مترجم)\\b"), " ")
        s = s.replace(Regex("\\s+S\\d+E\\d+$"), "")
        return s.replace(Regex("\\s+"), " ").trim().trim('-', ':', '|').trim()
    }

    private val yearTail = Regex("\\s(19|20)\\d{2}$")
    private val nonAlnum = Regex("[^\\p{L}\\p{N}]+")
    /** Comparable form of a title (used to match TMDB suggestions against the provider library). */
    fun libKey(n: String): String = nz(cleanTitle(n)).replace(yearTail, "").replace(nonAlnum, "")

    private const val IMG_STILL = "https://image.tmdb.org/t/p/w300"
    private val seasonCache = java.util.Collections.synchronizedMap(HashMap<String, Map<Int, EpInfo>>())

    /** Episode thumbnails, descriptions, runtime, air date and score for one season (TMDB). Empty when unavailable. */
    fun tmdbSeason(tvId: Int, season: Int): Map<Int, EpInfo> {
        if (tvId <= 0 || c.tmdb.isBlank()) return emptyMap()
        val k = "$tvId:$season:${Lang.ar}"
        seasonCache[k]?.let { return it }
        return try {
            val lang = if (Lang.ar) "&language=ar-SA" else ""
            val arr = JSONObject(tget("/tv/$tvId/season/$season?api_key=${c.tmdb}$lang")).optJSONArray("episodes")
            val out = HashMap<Int, EpInfo>()
            if (arr != null) for (i in 0 until arr.length()) {
                val o = arr.optJSONObject(i) ?: continue
                out[o.optInt("episode_number")] = EpInfo(o.s("name"), o.s("still_path").takeIf { it.isNotBlank() }?.let { IMG_STILL + it } ?: "",
                    o.s("overview"), o.optInt("runtime", 0), o.s("air_date"), o.optDouble("vote_average", 0.0).takeIf { it > 0.0 }?.let { fmt1(it) } ?: "")
            }
            out.also { seasonCache[k] = it }
        } catch (e: Exception) { emptyMap() }
    }

    private fun fget(url: String, ct: Int = 4000, rt: Int = 8000): String {
        val cn = URL(url).openConnection() as HttpURLConnection
        cn.connectTimeout = ct; cn.readTimeout = rt
        cn.setRequestProperty("User-Agent", "Mozilla/5.0 (Linux; Android TV)")
        try { return cn.inputStream.bufferedReader().use { it.readText() } } finally { cn.disconnect() }
    }

    private val tHosts = arrayOf("https://api.themoviedb.org/3", "https://api.tmdb.org/3")
    @Volatile private var tHost = 0
    @Volatile private var tDownUntil = 0L

    /** TMDB request: short timeouts, remembers which host works, tries the other one, and backs off for a minute when TMDB is unreachable. */
    private fun tget(path: String): String {
        if (System.currentTimeMillis() < tDownUntil) throw java.io.IOException("TMDB unreachable")
        var err: Exception? = null
        for (n in 0..1) {
            val h = (tHost + n) % 2
            try { val r = fget(tHosts[h] + path); tHost = h; return r } catch (e: Exception) { err = e }
        }
        val x = err
        if (x is java.net.SocketTimeoutException || x is java.net.UnknownHostException || x is java.net.ConnectException) tDownUntil = System.currentTimeMillis() + 60_000
        throw x ?: java.io.IOException("TMDB failed")
    }

    /** Runs [block] on its own thread right away; the returned function waits for the result (null on failure). */
    private fun <T> par(block: () -> T): () -> T? {
        var res: T? = null
        val t = Thread { res = runCatching(block).getOrNull() }
        t.start()
        return { t.join(); res }
    }

    private fun search(q: String, type: String, year: String?): JSONObject? {
        val y = if (year != null) (if (type == "tv") "&first_air_date_year=$year" else "&year=$year") else ""
        val arr = JSONObject(tget("/search/$type?api_key=${c.tmdb}&include_adult=false&query=${enc(q)}$y")).optJSONArray("results") ?: return null
        return if (arr.length() > 0) arr.getJSONObject(0) else null
    }

    /** Best TMDB hit for an IPTV title: tries with the year, without it, then the part before ":" / " - ". */
    private fun tmdbFind(name: String, series: Boolean): JSONObject? {
        val type = if (series) "tv" else "movie"
        val q = cleanTitle(name)
        if (q.isBlank()) return null
        val year = Regex("(19|20)\\d{2}").find(name)?.value
        if (year != null) runCatching { search(q, type, year) }.getOrNull()?.let { return it }
        search(q, type, null)?.let { return it }
        val short = q.split(Regex("\\s[-:]\\s|:")).firstOrNull()?.trim()
        if (!short.isNullOrBlank() && short != q) return search(short, type, null)
        return null
    }

    private fun certOf(detail: JSONObject, series: Boolean): String {
        val results = (if (series) detail.optJSONObject("content_ratings") else detail.optJSONObject("release_dates"))
            ?.optJSONArray("results") ?: return ""
        var any = ""
        for (i in 0 until results.length()) {
            val o = results.optJSONObject(i) ?: continue
            val cert = if (series) o.s("rating") else {
                val rd = o.optJSONArray("release_dates")
                var r = ""
                if (rd != null) for (k in 0 until rd.length()) { val x = rd.optJSONObject(k)?.s("certification").orEmpty(); if (x.isNotBlank()) { r = x; break } }
                r
            }
            if (cert.isBlank()) continue
            if (o.s("iso_3166_1") == "US") return cert
            if (any.isEmpty()) any = cert
        }
        return any
    }

    private fun positive(s: String) = (s.toDoubleOrNull() ?: 0.0) > 0.0
    private fun fmt1(d: Double) = "%.1f".format(Locale.US, d)

    private const val IMG_S = "https://image.tmdb.org/t/p/w185"
    private const val IMG_L = "https://image.tmdb.org/t/p/w500"

    private fun fmtRun(min: Int): String = if (min <= 0) "" else if (min >= 60) (if (min % 60 == 0) "${min / 60}h" else "${min / 60}h ${min % 60}m") else "${min}m"

    /** "01:36:00" -> 96 (minutes). */
    private fun durMin(s: String): Int {
        val p = s.trim().split(":").mapNotNull { it.toIntOrNull() }
        return when (p.size) { 3 -> p[0] * 60 + p[1]; 2 -> p[0] * 60 + p[1]; 1 -> p[0]; else -> 0 }
    }

    private fun ytKey(s: String): String {
        val t = s.trim()
        if (t.isBlank()) return ""
        Regex("(?:v=|youtu\\.be/|embed/)([A-Za-z0-9_-]{11})").find(t)?.let { return it.groupValues[1] }
        return if (Regex("^[A-Za-z0-9_-]{11}$").matches(t)) t else ""
    }

    private fun names(a: JSONArray?, key: String = "name", max: Int = 3): List<String> {
        if (a == null) return emptyList()
        val out = linkedSetOf<String>()
        for (i in 0 until a.length()) { val n = a.optJSONObject(i)?.s(key).orEmpty(); if (n.isNotBlank()) out.add(n); if (out.size >= max) break }
        return out.toList()
    }

    private val pcache = java.util.Collections.synchronizedMap(HashMap<String, Meta>())
    private val tcache = java.util.Collections.synchronizedMap(HashMap<String, Meta>())
    private val rawCache = java.util.Collections.synchronizedMap(HashMap<String, String>())

    /** Provider info (movie or series), fetched once and shared by the details page and the episode list. */
    @Synchronized fun rawInfo(series: Boolean, id: String): String {
        val k = "${base()}:$series:$id"
        rawCache[k]?.let { return it }
        val s = if (series) act("get_series_info", "series_id=$id", 25000) else act("get_vod_info", "vod_id=$id", 25000)
        rawCache[k] = s
        return s
    }

    private fun hasArabic(s: String) = s.any { it in '\u0600'..'\u06FF' }

    /** PHASE 1 (fast): everything the IPTV provider already knows: plot, genres, duration, cast, director, year, rating, backdrop. Never throws. */
    fun providerMeta(series: Boolean, id: String): Meta {
        val ck = "${base()}:$series:$id"
        pcache[ck]?.let { return it }
        if (id.isBlank()) return Meta(null, "", "", "")
        return try {
            val j = JSONObject(rawInfo(series, id))
            val info = j.optJSONObject("info") ?: JSONObject()
            val overview = info.s("plot").ifEmpty { info.s("description") }
            val rating = info.s("rating").ifEmpty { info.s("kinopoisk_rating") }.takeIf { positive(it) } ?: ""
            val release = info.s("releasedate").ifEmpty { info.s("releaseDate") }
            val year = release.ifEmpty { info.s("year") }.take(4)
            val secs = info.optInt("duration_secs", 0)
            val rtm = if (secs > 0) secs / 60 else durMin(info.s("duration")).takeIf { it > 0 } ?: info.s("episode_run_time").toIntOrNull() ?: 0
            val m = Meta(
                backdrop = (info.optJSONArray("backdrop_path")?.optString(0) ?: "").ifBlank { null },
                overview = overview, rating = rating, year = year,
                cast = info.s("cast"), director = info.s("director"), trailer = info.s("youtube_trailer"),
                cert = info.s("age").ifEmpty { info.s("mpaa") },
                genres = info.s("genre").split(",", "/").map { it.trim() }.filter { it.isNotBlank() }.joinToString(" • "),
                runtime = fmtRun(rtm), country = info.s("country"), release = release)
            pcache[ck] = m
            m
        } catch (e: Exception) { Meta(null, "", "", "") }
    }

    /** PHASE 2: TMDB fills only what the provider lacks (logo, age rating, writer, status, country, IMDb id, suggestions). Never throws. */
    fun tmdbExtra(base: Meta, name: String, series: Boolean, id: String): Meta {
        if (c.tmdb.isBlank()) return base
        val ck = "${normUrl(c.server)}:$series:$id:$name:${Lang.ar}:${c.tmdb.takeLast(4)}"
        tcache[ck]?.let { return it }
        return try {
            val hit = tmdbFind(name, series) ?: return base
            val tid = hit.getInt("id")
            val type = if (series) "tv" else "movie"
            val extra = if (series) "credits,content_ratings,external_ids,images" else "credits,release_dates,external_ids,images"
            val lg = if (Lang.ar) "&language=ar-SA" else ""
            val recsJob = par {
                val l = ArrayList<Rec>()
                for (kind in listOf("recommendations", "similar")) {
                    val ra = runCatching { JSONObject(tget("/$type/$tid/$kind?api_key=${c.tmdb}")).optJSONArray("results") }.getOrNull() ?: continue
                    for (i in 0 until minOf(20, ra.length())) {
                        val o = ra.optJSONObject(i) ?: continue
                        val t = o.s("title").ifBlank { o.s("name") }
                        if (t.isNotBlank()) l.add(Rec(t, o.s("original_title").ifBlank { o.s("original_name") }))
                    }
                }
                l.toList()
            }
            val d = JSONObject(tget("/$type/$tid?api_key=${c.tmdb}&append_to_response=$extra&include_image_language=en,null$lg"))

            var overview = base.overview.ifBlank { hit.s("overview") }
            if (Lang.ar) d.s("overview").takeIf { it.isNotBlank() && !hasArabic(base.overview) }?.let { overview = it }
            var rating = base.rating; var src = base.ratingSrc
            if (rating.isBlank()) { val v = hit.optDouble("vote_average", 0.0); if (v > 0.0) { rating = fmt1(v); src = "TMDB" } }
            val year = base.year.ifBlank { hit.s("release_date").ifEmpty { hit.s("first_air_date") }.take(4) }
            val backdrop = base.backdrop ?: hit.s("backdrop_path").takeIf { it.isNotBlank() }?.let { IMG + it }

            val crew = d.optJSONObject("credits")?.optJSONArray("crew")
            var director = base.director
            if (director.isBlank()) director = if (series) names(d.optJSONArray("created_by")).joinToString(", ")
                else crew?.let { cr -> (0 until cr.length()).mapNotNull { cr.optJSONObject(it) }.filter { it.s("job") == "Director" }.map { it.s("name") }.distinct().take(2).joinToString(", ") } ?: ""
            val writer = if (crew == null) "" else (0 until crew.length()).mapNotNull { crew.optJSONObject(it) }
                .filter { it.s("job") in setOf("Writer", "Screenplay", "Story", "Author", "Teleplay") }
                .map { it.s("name") }.filter { it.isNotBlank() }.distinct().take(3).joinToString(", ")
            var cast = base.cast
            if (cast.isBlank()) cast = names(d.optJSONObject("credits")?.optJSONArray("cast"), "name", 8).joinToString(", ")

            val genres = base.genres.ifBlank { names(d.optJSONArray("genres"), "name", 3).joinToString(" • ") }
            val rtm = if (series) d.optJSONArray("episode_run_time")?.optInt(0, 0) ?: 0 else d.optInt("runtime", 0)
            val runtime = base.runtime.ifBlank { fmtRun(rtm) }
            val pc = names(d.optJSONArray("production_countries"), "name", 4)
            val country = base.country.ifBlank {
                if (pc.isNotEmpty()) pc.joinToString(", ") else {
                    val oc = d.optJSONArray("origin_country")
                    if (oc != null) (0 until oc.length()).joinToString(", ") { java.util.Locale("", oc.optString(it)).getDisplayCountry(java.util.Locale.ENGLISH) } else ""
                }
            }
            val release = base.release.ifBlank { d.s("release_date").ifEmpty { d.s("first_air_date") } }
            var logo = ""
            d.optJSONObject("images")?.optJSONArray("logos")?.let { lg2 ->
                val all = (0 until lg2.length()).mapNotNull { lg2.optJSONObject(it) }
                val best = all.firstOrNull { it.s("iso_639_1") == "en" } ?: all.firstOrNull { it.isNull("iso_639_1") || it.s("iso_639_1").isBlank() } ?: all.firstOrNull()
                best?.s("file_path")?.takeIf { it.isNotBlank() }?.let { logo = IMG_L + it }
            }
            val imdb = d.optJSONObject("external_ids")?.s("imdb_id").orEmpty().ifBlank { d.s("imdb_id") }
            val m = base.copy(backdrop = backdrop, overview = overview, rating = rating, ratingSrc = src, year = year, id = tid,
                cast = cast, director = director, cert = certOf(d, series).ifBlank { base.cert }, genres = genres, runtime = runtime,
                writer = writer, status = d.s("status"), country = country, logo = logo, release = release,
                recs = recsJob() ?: emptyList(), imdb = imdb)
            tcache[ck] = m
            m
        } catch (e: Exception) { base }
    }

    private val ocache = java.util.Collections.synchronizedMap(HashMap<String, Meta>())

    /** PHASE 3 (optional OMDb key): real IMDb score, Rotten Tomatoes, Metacritic, awards. Never throws. */
    fun omdbExtra(base: Meta): Meta {
        val key = Store.str("omdb", "")
        if (key.isBlank() || base.imdb.isBlank()) return base
        val ck = "${base.imdb}:$key"
        ocache[ck]?.let { return it }
        return try {
            val o = JSONObject(fget("https://www.omdbapi.com/?apikey=$key&i=${base.imdb}"))
            var rating = base.rating; var src = base.ratingSrc
            val r = o.s("imdbRating")
            if (positive(r)) { rating = r; src = "IMDb" }
            var rtS = ""; var mcS = ""
            o.optJSONArray("Ratings")?.let { ra ->
                for (i in 0 until ra.length()) {
                    val x = ra.optJSONObject(i) ?: continue
                    when (x.s("Source")) { "Rotten Tomatoes" -> rtS = x.s("Value"); "Metacritic" -> mcS = x.s("Value").substringBefore("/") }
                }
            }
            val m = base.copy(rating = rating, ratingSrc = src, rt = rtS, mc = mcS, awards = o.s("Awards").takeIf { it.isNotBlank() && it != "N/A" } ?: "")
            ocache[ck] = m
            m
        } catch (e: Exception) { base }
    }

    /** All phases in one blocking call (kept for other screens). */
    fun tmdb(name: String, series: Boolean, id: String = ""): Meta = omdbExtra(tmdbExtra(providerMeta(series, id), name, series, id))

    private val tagWords = listOf(
        "violence" to "Violence", "gore" to "Gore", "blood" to "Blood", "nudity" to "Nudity", "sex" to "Sexual content",
        "drug" to "Drugs", "alcohol" to "Alcohol", "profanity" to "Strong language", "swear" to "Strong language",
        "torture" to "Torture", "rape" to "Sexual violence", "suicide" to "Suicide", "smoking" to "Smoking"
    )

    /** Age rating + content tags from TMDB, or null when there is nothing to show. */
    fun guide(title: String, series: Boolean): Guide? {
        if (c.tmdb.isBlank()) return null
        val hit = tmdbFind(title, series) ?: return null
        val tid = hit.getInt("id")
        val type = if (series) "tv" else "movie"
        val extra = if (series) "content_ratings,keywords" else "release_dates,keywords"
        val d = JSONObject(get("$TMDB/$type/$tid?api_key=${c.tmdb}&append_to_response=$extra", 15000))
        val cert = certOf(d, series)
        val arr = d.optJSONObject("keywords")?.optJSONArray(if (series) "results" else "keywords")
        val tags = linkedSetOf<String>()
        if (arr != null) for (i in 0 until arr.length()) {
            val n = arr.getJSONObject(i).s("name").lowercase()
            tagWords.forEach { (w, t) -> if (n.contains(w)) tags.add(t) }
        }
        return if (cert.isBlank() && tags.isEmpty()) null else Guide(cert, tags.toList())
    }
}


/** Matches TMDB suggestions against the provider library (rebuilt when the library reloads). */
object LibIndex {
    private var ver = -1
    private var mov: Map<String, Item> = emptyMap()
    private var ser: Map<String, Item> = emptyMap()

    private fun idx(l: List<Item>): Map<String, Item> {
        val m = HashMap<String, Item>()
        for (x in l) { val k = Api.libKey(x.name); if (k.isNotBlank() && !m.containsKey(k)) m[k] = x }
        return m
    }

    @Synchronized private fun ensure() {
        val v = Repo.version
        if (v == ver && (mov.isNotEmpty() || ser.isNotEmpty())) return
        mov = idx(Repo.movies); ser = idx(Repo.series); ver = v
    }

    fun match(recs: List<Rec>, series: Boolean, self: Item): List<Item> {
        ensure()
        val m = if (series) ser else mov
        val out = ArrayList<Item>()
        for (r in recs) {
            val x = m[Api.libKey(r.title)] ?: (if (r.alt.isNotBlank()) m[Api.libKey(r.alt)] else null) ?: continue
            if (x.id != self.id && out.none { it.id == x.id }) out.add(x)
            if (out.size >= 15) break
        }
        return out
    }
}
