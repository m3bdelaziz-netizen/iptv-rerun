package com.iptv.tv

import android.content.ContentUris
import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Backup files WITHOUT the system file picker (most TVs have no file manager, which is why the old button failed).
 * Saved to Downloads/Waleen (survives uninstall) and to the app's own folder. Restore lists every backup found.
 */
object Backups {
    private const val DIR = "Waleen"
    private const val KEEP = 10

    class Found(val name: String, val size: Long, val time: Long, val place: String, val read: () -> String?)

    private fun appDir(ctx: Context) = File(ctx.getExternalFilesDir(null) ?: ctx.filesDir, "backups")
    private fun publicDir() = File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS), DIR)

    /** Returns a message for the screen. */
    fun save(ctx: Context): String {
        val text = try { Backup.export() } catch (e: Exception) { return "Backup failed: ${e.message}" }
        val name = "waleen-backup-" + SimpleDateFormat("yyyyMMdd-HHmm", Locale.US).format(Date()) + ".json"
        var where = ""

        // 1) Public Downloads/Waleen
        runCatching {
            if (Build.VERSION.SDK_INT >= 29) {
                val cv = ContentValues().apply {
                    put(MediaStore.MediaColumns.DISPLAY_NAME, name)
                    put(MediaStore.MediaColumns.MIME_TYPE, "application/json")
                    put(MediaStore.MediaColumns.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS + "/" + DIR)
                }
                val uri = ctx.contentResolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, cv)!!
                ctx.contentResolver.openOutputStream(uri)!!.use { it.write(text.toByteArray()) }
                where = "Downloads/$DIR/$name"
                pruneMediaStore(ctx)
            } else {
                val d = publicDir()
                if (d.isDirectory || d.mkdirs()) {
                    File(d, name).writeText(text)
                    where = "Downloads/$DIR/$name"
                    prune(d)
                }
            }
        }
        // 2) The app's own folder (always works)
        val own = runCatching {
            val d = appDir(ctx); d.mkdirs()
            val f = File(d, name); f.writeText(text); prune(d); f
        }.getOrNull()
        if (where.isEmpty() && own != null) where = own.absolutePath
        return if (where.isNotEmpty()) "Backup saved: $where" else "Backup failed: no writable storage"
    }

    /** Every backup that can be found, newest first. */
    fun list(ctx: Context): List<Found> {
        val out = ArrayList<Found>()
        if (Build.VERSION.SDK_INT >= 29) runCatching {
            val base = MediaStore.Downloads.EXTERNAL_CONTENT_URI
            val proj = arrayOf(MediaStore.MediaColumns._ID, MediaStore.MediaColumns.DISPLAY_NAME, MediaStore.MediaColumns.SIZE, MediaStore.MediaColumns.DATE_MODIFIED)
            ctx.contentResolver.query(base, proj, "${MediaStore.MediaColumns.DISPLAY_NAME} LIKE ?", arrayOf("%backup%.json"),
                "${MediaStore.MediaColumns.DATE_MODIFIED} DESC")?.use { c ->
                while (c.moveToNext()) {
                    val uri = ContentUris.withAppendedId(base, c.getLong(0))
                    out += Found(c.getString(1) ?: "backup.json", c.getLong(2), c.getLong(3) * 1000L, "Downloads") { readUri(ctx, uri) }
                }
            }
        }
        val dirs = listOf("Downloads/$DIR" to publicDir(), "App folder" to appDir(ctx), "App folder" to (ctx.getExternalFilesDir(null) ?: ctx.filesDir))
        for ((label, d) in dirs) runCatching {
            d.listFiles { f -> f.isFile && f.name.endsWith(".json", true) && f.name.contains("backup", true) }?.forEach { f ->
                out += Found(f.name, f.length(), f.lastModified(), label) { runCatching { f.readText() }.getOrNull() }
            }
        }
        return out.distinctBy { it.name + "|" + it.size }.sortedByDescending { it.time }
    }

    fun readUri(ctx: Context, uri: Uri): String? =
        runCatching { ctx.contentResolver.openInputStream(uri)!!.bufferedReader().use { it.readText() } }.getOrNull()

    private fun prune(d: File) {
        runCatching {
            d.listFiles { f -> f.isFile && f.name.startsWith("waleen-backup-") }?.sortedByDescending { it.name }?.drop(KEEP)?.forEach { it.delete() }
        }
    }

    private fun pruneMediaStore(ctx: Context) {
        if (Build.VERSION.SDK_INT < 29) return
        runCatching {
            val base = MediaStore.Downloads.EXTERNAL_CONTENT_URI
            val ids = ArrayList<Long>()
            ctx.contentResolver.query(base, arrayOf(MediaStore.MediaColumns._ID),
                "${MediaStore.MediaColumns.DISPLAY_NAME} LIKE ?", arrayOf("waleen-backup-%.json"),
                "${MediaStore.MediaColumns.DISPLAY_NAME} DESC")?.use { c -> while (c.moveToNext()) ids += c.getLong(0) }
            ids.drop(KEEP).forEach { ctx.contentResolver.delete(ContentUris.withAppendedId(base, it), null, null) }
        }
    }
}

/**
 * What "Clear cache" removes: downloaded posters and other temporary files, the in-memory guide and ratings.
 * The channel / movie / series lists are not deleted here: Settings re-downloads them right after (so they can never end up empty).
 * Playlists, favorites, history and settings are never touched.
 */
object CacheTools {
    private fun size(f: File): Long = if (f.isFile) f.length() else (f.listFiles()?.sumOf { size(it) } ?: 0L)

    fun bytes(ctx: Context): Long = runCatching { size(ctx.cacheDir) }.getOrDefault(0L)

    fun clear(ctx: Context) {
        runCatching { coil.Coil.imageLoader(ctx).memoryCache?.clear() }
        runCatching { coil.Coil.imageLoader(ctx).diskCache?.clear() }
        runCatching { ctx.cacheDir.listFiles()?.filter { it.name != "image_cache" }?.forEach { it.deleteRecursively() } }
        Epg.clearCache()
        Api.clearMeta()
    }

    fun human(b: Long): String = when {
        b >= 1024L * 1024L * 1024L -> "%.1f GB".format(Locale.US, b / 1073741824.0)
        b >= 1024L * 1024L -> "%.1f MB".format(Locale.US, b / 1048576.0)
        b >= 1024L -> "${b / 1024} KB"
        else -> "0 KB"
    }
}
