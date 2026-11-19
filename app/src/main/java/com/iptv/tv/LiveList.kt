package com.iptv.tv

import org.json.JSONArray
import org.json.JSONObject

/** Manual channel order, saved per playlist and per list (favorites, a category, all channels). Stored as a comma list of item keys. */
object LiveOrder {
    private fun key(cat: String) = "ord_${Store.pid()}_$cat"
    fun saved(cat: String): List<String> = Store.str(key(cat), "").takeIf { it.isNotEmpty() }?.split(",") ?: emptyList()
    fun save(cat: String, keys: List<String>) { Store.put(key(cat), keys.joinToString(",")) }
    fun clear(cat: String) { Store.sp.edit().remove(key(cat)).apply() }
    fun clearAll() {
        val p = "ord_${Store.pid()}_"
        val e = Store.sp.edit()
        Store.sp.all.keys.filter { it.startsWith(p) }.forEach { e.remove(it) }
        e.apply()
    }
}

/** 0 = provider order, 1 = A-Z, 2 = manual (my order). */
fun sortLiveMode() = Store.int("sortLive", 0).coerceIn(0, 2)

fun applyLiveSort(cat: String, base: List<Item>): List<Item> = when (sortLiveMode()) {
    1 -> base.sortedBy { it.nn }
    2 -> {
        val ord = LiveOrder.saved(cat)
        if (ord.isEmpty()) base
        else {
            val pos = HashMap<String, Int>(ord.size * 2)
            ord.forEachIndexed { i, k -> pos[k] = i }
            base.sortedBy { pos[it.fkey] ?: Int.MAX_VALUE }   // stable: channels without a saved place keep provider order, after the others
        }
    }
    else -> base
}

/** Channels of a live list exactly as they are shown on screen (with the chosen sort / manual order applied). */
fun liveChannels(cat: String): List<Item> {
    val base: List<Item> = when (cat) {
        "fav" -> (Repo.live + Repo.radio).filter { Store.favs.contains(it.fkey) }
        "radio" -> Repo.radio
        "*" -> Repo.live
        else -> Repo.live.filter { it.cat == cat }
    }
    return applyLiveSort(cat, base.distinctBy { it.fkey })
}

/** Backup of every setting, playlist, favorite, history entry, VPN profile and channel order to one JSON file. The cached library is not included (it reloads). */
object Backup {
    fun export(): String {
        val arr = JSONArray()
        Store.sp.all.forEach { (k, v) ->
            val o = JSONObject().put("k", k)
            when (v) {
                is Boolean -> o.put("t", "b").put("v", v)
                is Int -> o.put("t", "i").put("v", v)
                is Long -> o.put("t", "l").put("v", v)
                is Float -> o.put("t", "f").put("v", v.toDouble())
                is String -> o.put("t", "s").put("v", v)
                is Set<*> -> o.put("t", "set").put("v", JSONArray(v.map { it.toString() }))
                else -> return@forEach
            }
            arr.put(o)
        }
        return JSONObject().put("app", "iptv-tv").put("v", 1).put("ts", System.currentTimeMillis()).put("prefs", arr).toString()
    }

    /** Returns null on success, otherwise a readable error. Replaces all current settings. */
    fun restore(text: String): String? {
        val root = try { JSONObject(text) } catch (e: Exception) { return "Not a valid backup file" }
        if (root.s("app") != "iptv-tv") return "Not an IPTV TV backup"
        val arr = root.optJSONArray("prefs") ?: return "Backup is empty"
        if (arr.length() == 0) return "Backup is empty"
        val e = Store.sp.edit().clear()
        try {
            for (i in 0 until arr.length()) {
                val o = arr.getJSONObject(i); val k = o.getString("k")
                when (o.s("t")) {
                    "b" -> e.putBoolean(k, o.getBoolean("v"))
                    "i" -> e.putInt(k, o.getInt("v"))
                    "l" -> e.putLong(k, o.getLong("v"))
                    "f" -> e.putFloat(k, o.getDouble("v").toFloat())
                    "s" -> e.putString(k, o.getString("v"))
                    "set" -> { val a = o.getJSONArray("v"); val s = HashSet<String>(); for (j in 0 until a.length()) s.add(a.getString(j)); e.putStringSet(k, s) }
                }
            }
        } catch (ex: Exception) { return "Backup file is damaged" }
        e.commit()
        runCatching { Store.savePls(Store.pls()) }   // refresh the playlists safety copy too
        return null
    }

    /** Used when the device has no file manager for the picker: a copy in the app's own storage folder. */
    fun fallbackFile(ctx: android.content.Context): java.io.File =
        java.io.File(ctx.getExternalFilesDir(null) ?: ctx.filesDir, "iptv-tv-backup.json")
}
