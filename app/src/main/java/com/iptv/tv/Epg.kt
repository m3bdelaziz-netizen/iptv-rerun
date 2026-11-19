package com.iptv.tv

import android.util.Xml
import java.io.BufferedInputStream
import java.net.HttpURLConnection
import java.net.URL
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.TimeZone
import java.util.zip.GZIPInputStream

/** Optional XMLTV guide sources. Any number of URLs (.xml or .xml.gz); all are merged. With none set, the provider's own guide is used. */
object Epg {
    private const val KEY = "epg_urls"
    private class Data(val progs: Map<String, List<Prog>>, val names: Map<String, String>, val at: Long)
    @Volatile private var data: Data? = null
    @Volatile private var sig = ""
    private val lock = Any()

    fun urls(): List<String> = Store.str(KEY, "").lines().map { it.trim() }.filter { it.isNotEmpty() }
    private fun save(l: List<String>) { Store.put(KEY, l.joinToString("\n")); data = null }
    fun add(text: String): Int {
        val new = text.split('\n', ',', ' ', '\t', '\r').map { it.trim() }.filter { it.startsWith("http", true) }
        val cur = urls(); val add = new.filter { it !in cur }.distinct()
        if (add.isNotEmpty()) save(cur + add)
        return add.size
    }
    fun remove(u: String) = save(urls().filter { it != u })

    private fun norm(s: String) = s.lowercase().replace(Regex("[^\\p{L}\\p{N}]"), "")

    private fun open(u: String): java.io.InputStream {
        val cn = URL(u).openConnection() as HttpURLConnection
        cn.connectTimeout = 15000; cn.readTimeout = 120000; cn.instanceFollowRedirects = true
        cn.setRequestProperty("User-Agent", "Mozilla/5.0 (Linux; Android TV)")
        val s = BufferedInputStream(cn.inputStream, 65536)
        s.mark(2); val a = s.read(); val b = s.read(); s.reset()
        return if (a == 0x1f && b == 0x8b) GZIPInputStream(s, 65536) else s
    }

    private fun parseTime(s: String): Long {
        val t = s.trim(); if (t.length < 14) return 0
        val f = SimpleDateFormat(if (t.length > 14) "yyyyMMddHHmmss Z" else "yyyyMMddHHmmss", Locale.US)
        if (t.length <= 14) f.timeZone = TimeZone.getTimeZone("UTC")
        return runCatching { f.parse(t.replace(Regex("\\s+"), " "))!!.time }.getOrDefault(0)
    }

    private fun load(list: List<String>): Data {
        val now = System.currentTimeMillis()
        val from = now - 3 * 3600_000L; val to = now + 36 * 3600_000L
        val progs = HashMap<String, MutableList<Prog>>(); val names = HashMap<String, String>()
        for (u in list) runCatching {
            open(u).use { ins ->
                val p = Xml.newPullParser(); p.setInput(ins, null)
                var ev = p.eventType
                var chId = ""; var cStart = 0L; var cStop = 0L; var cTitle = ""
                var inProg = false; var inTitle = false; var inName = false; var inChan = false
                while (ev != org.xmlpull.v1.XmlPullParser.END_DOCUMENT) {
                    when (ev) {
                        org.xmlpull.v1.XmlPullParser.START_TAG -> when (p.name) {
                            "channel" -> { inChan = true; chId = p.getAttributeValue(null, "id") ?: "" }
                            "display-name" -> inName = inChan
                            "programme" -> {
                                chId = p.getAttributeValue(null, "channel") ?: ""
                                cStart = parseTime(p.getAttributeValue(null, "start") ?: "")
                                cStop = parseTime(p.getAttributeValue(null, "stop") ?: "")
                                cTitle = ""; inProg = cStop > from && cStart < to
                            }
                            "title" -> inTitle = inProg && cTitle.isEmpty()
                        }
                        org.xmlpull.v1.XmlPullParser.TEXT -> {
                            if (inTitle) cTitle = p.text.trim()
                            else if (inName && chId.isNotEmpty()) { val n = norm(p.text); if (n.isNotEmpty()) names.putIfAbsent(n, chId.lowercase()) }
                        }
                        org.xmlpull.v1.XmlPullParser.END_TAG -> when (p.name) {
                            "channel" -> inChan = false
                            "display-name" -> inName = false
                            "title" -> inTitle = false
                            "programme" -> {
                                if (inProg && cTitle.isNotEmpty() && chId.isNotEmpty()) progs.getOrPut(chId.lowercase()) { ArrayList() }.add(Prog(cStart, cStop, cTitle))
                                inProg = false
                            }
                        }
                    }
                    ev = p.next()
                }
            }
        }
        progs.values.forEach { l -> l.sortBy { it.start } }
        return Data(progs, names, now)
    }

    private fun ensure(): Data? {
        val l = urls(); if (l.isEmpty()) return null
        val key = l.joinToString("\n")
        val d = data
        if (d != null && sig == key && System.currentTimeMillis() - d.at < 3 * 3600_000L) return d
        synchronized(lock) {
            val d2 = data
            if (d2 != null && sig == key && System.currentTimeMillis() - d2.at < 3 * 3600_000L) return d2
            val n = load(l); data = n; sig = key; return n
        }
    }

    /** Next programmes of a channel from the added guide sources, as "HH:mm  Title" lines. Empty when none match. */
    fun lines(epgId: String, name: String): List<String> {
        val d = ensure() ?: return emptyList()
        val id = (if (epgId.isNotBlank() && d.progs.containsKey(epgId.lowercase())) epgId.lowercase() else null)
            ?: d.names[norm(name)]?.takeIf { d.progs.containsKey(it) } ?: return emptyList()
        val now = System.currentTimeMillis(); val f = SimpleDateFormat("HH:mm", Locale.getDefault())
        return d.progs[id].orEmpty().filter { it.stop > now }.take(3).map { f.format(java.util.Date(it.start)) + "  " + it.title }
    }
}
