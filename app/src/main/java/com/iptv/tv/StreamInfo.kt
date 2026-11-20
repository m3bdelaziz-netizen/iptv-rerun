package com.iptv.tv

import android.media.MediaFormat
import android.os.Handler
import android.os.Looper
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.media3.common.Format
import androidx.media3.exoplayer.video.VideoFrameMetadataListener

/** Helpers for the quality + frame rate badge (for example "FHD  50 fps"). */
object StreamInfo {
    private val STD = intArrayOf(24, 25, 30, 50, 60, 100, 120)
    private val NAME_FPS = Regex("(\\d{2,3})\\s?FPS", RegexOption.IGNORE_CASE)

    /** 23.976 -> 24, 29.97 -> 30, 59.94 -> 60 ... Returns 0 when the value is unknown. */
    fun stdFps(f: Float): Int {
        if (f.isNaN() || f < 10f || f > 130f) return 0
        var best = 0
        var d = Float.MAX_VALUE
        for (s in STD) {
            val x = kotlin.math.abs(f - s.toFloat())
            if (x < d) { d = x; best = s }
        }
        return if (d <= 1.6f) best else Math.round(f)
    }

    /** Frame rate written in a channel name ("... 50FPS"), or 0. */
    fun nameFps(name: String): Int =
        NAME_FPS.find(name)?.groupValues?.get(1)?.toIntOrNull()?.takeIf { it in 15..120 } ?: 0

    /** Quality label from the real decoded video height. */
    fun qualityOfHeight(h: Int): String? = when {
        h <= 0 -> null
        h >= 2000 -> "4K"
        h >= 1300 -> "2K"
        h >= 1000 -> "FHD"
        h >= 700 -> "HD"
        else -> "SD"
    }
}

/**
 * Finds the real frame rate of the video that is playing. Uses the rate declared by the stream when there is one,
 * otherwise measures the gap between the first frames (TS streams usually declare nothing).
 */
class FpsProbe(private val onResult: (Int) -> Unit) : VideoFrameMetadataListener {
    private val ts = LongArray(48)
    private var n = 0
    @Volatile private var done = false
    private val main = Handler(Looper.getMainLooper())

    override fun onVideoFrameAboutToBeRendered(presentationTimeUs: Long, releaseTimeNs: Long, format: Format, mediaFormat: MediaFormat?) {
        if (done) return
        val declared = StreamInfo.stdFps(format.frameRate)
        if (declared > 0) { done = true; main.post { onResult(declared) }; return }
        if (n > 0 && presentationTimeUs <= ts[n - 1]) return
        ts[n++] = presentationTimeUs
        if (n == ts.size) {
            done = true
            val d = LongArray(n - 1) { ts[it + 1] - ts[it] }
            d.sort()
            val med = d[d.size / 2]
            val fps = if (med > 0) StreamInfo.stdFps(1_000_000f / med.toFloat()) else 0
            main.post { onResult(fps) }
        }
    }
}

/** Remembers the measured frame rate of every channel that was watched, so the channel list can show it. Kept per playlist. */
object FpsMemory {
    private var pid = ""
    private val map = LinkedHashMap<String, Int>()

    /** Call once per screen (it reads the playlist id). */
    fun sync() {
        val p = Store.pid()
        if (p == pid) return
        pid = p
        map.clear()
        Store.str("fps_$p", "").split(',').forEach {
            val i = it.indexOf('=')
            if (i > 0) it.substring(i + 1).toIntOrNull()?.let { v -> map[it.substring(0, i)] = v }
        }
    }

    fun get(key: String): Int = map[key] ?: 0

    fun put(key: String, fps: Int) {
        if (fps <= 0 || map[key] == fps) return
        map.remove(key); map[key] = fps
        while (map.size > 1500) map.remove(map.keys.first())
        Store.put("fps_$pid", map.entries.joinToString(",") { "${it.key}=${it.value}" })
    }
}

/** Small badges: quality (gold) and frame rate (cyan), on a dark chip so they stay readable on any background. */
@Composable
fun StreamBadge(q: String?, fps: Int, big: Boolean = false) {
    if (q == null && fps <= 0) return
    val fs = if (big) 13.sp else 10.sp
    val pad = if (big) 7.dp else 5.dp
    Row(horizontalArrangement = Arrangement.spacedBy(4.dp), verticalAlignment = Alignment.CenterVertically) {
        if (q != null) Box(Modifier.background(Color(0x99000000), RoundedCornerShape(4.dp)).padding(horizontal = pad, vertical = 1.dp)) {
            Text(q, fontSize = fs, fontWeight = FontWeight.Bold, color = Color(0xFFFFC107), maxLines = 1)
        }
        if (fps > 0) Box(Modifier.background(Color(0x99000000), RoundedCornerShape(4.dp)).padding(horizontal = pad, vertical = 1.dp)) {
            Text("$fps fps", fontSize = fs, fontWeight = FontWeight.Bold, color = Color(0xFF7CE7FF), maxLines = 1)
        }
    }
}
