package com.iptv.tv

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.text.font.FontFamily

/** App colours. Every value reads Compose state, so changing the theme/accent in Settings recolours the UI live. */
val SfProFontFamily = FontFamily.SansSerif

class Palette(val name: String, val bg: Long, val sidebar: Long, val panel: Long, val card: Long, val chip: Long)

object Theme {
    /** Dark palettes only (the UI uses white text everywhere). The first three keep their old positions so saved choices still match. */
    val palettes = listOf(
        Palette("Midnight",     0xFF0B0E1A, 0xFF10142A, 0xFF131833, 0xFF1A2040, 0xFF242C52),
        Palette("Graphite",     0xFF121212, 0xFF1A1A1A, 0xFF1E1E1E, 0xFF262626, 0xFF303030),
        Palette("Super AMOLED", 0xFF000000, 0xFF050505, 0xFF0A0A0A, 0xFF141414, 0xFF1E1E1E),
        Palette("Ocean",        0xFF061A24, 0xFF0A2533, 0xFF0D2E40, 0xFF123A50, 0xFF1A4A63),
        Palette("Forest",       0xFF0A1A12, 0xFF0F2519, 0xFF12301F, 0xFF184029, 0xFF205236),
        Palette("Crimson",      0xFF1A0A0E, 0xFF260E14, 0xFF2E1219, 0xFF3B1822, 0xFF4D202D),
        Palette("Royal",        0xFF120B24, 0xFF1A1033, 0xFF211540, 0xFF2C1C55, 0xFF3A2670),
        Palette("Mocha",        0xFF17110D, 0xFF211812, 0xFF2A1F17, 0xFF372A20, 0xFF4A392B),
        Palette("Slate",        0xFF0F1419, 0xFF161D25, 0xFF1C2530, 0xFF263341, 0xFF32445A),
        Palette("Sunset",       0xFF1B0F1F, 0xFF27142B, 0xFF301935, 0xFF3F2147, 0xFF52295C),
        Palette("Teal Noir",    0xFF031514, 0xFF062120, 0xFF082B2A, 0xFF0D3A38, 0xFF144D4B)
    )
    val modes: List<String> = palettes.map { it.name }
    val accents: List<Pair<String, Long>> = listOf(
        "Red" to 0xFFE50914, "Blue" to 0xFF2979FF, "Green" to 0xFF00C853, "Purple" to 0xFF8E44FF,
        "Orange" to 0xFFFF6D00, "Teal" to 0xFF00BFA5, "Pink" to 0xFFFF4081, "Gold" to 0xFFFFC107
    )

    private var modeState by mutableStateOf(0)
    private var accentState by mutableStateOf(0)
    val mode: Int get() = modeState
    val accentIdx: Int get() = accentState

    fun load() {
        modeState = Store.int("theme", 0).coerceIn(0, palettes.size - 1)
        accentState = Store.int("accent", 0).coerceIn(0, accents.size - 1)
    }
    fun setMode(i: Int) { modeState = i.coerceIn(0, palettes.size - 1); Store.put("theme", modeState) }
    fun setAccent(i: Int) { accentState = i.coerceIn(0, accents.size - 1); Store.put("accent", accentState) }

    val accent: Color get() = Color(accents[accentIdx].second)
    val onAccent: Color get() = if (accent.luminance() > 0.5f) Color.Black else Color.White

    val bg: Color get() = Color(palettes[mode].bg)
    val sidebar: Color get() = Color(palettes[mode].sidebar)
    val panel: Color get() = Color(palettes[mode].panel)
    val card: Color get() = Color(palettes[mode].card)
    val chip: Color get() = Color(palettes[mode].chip)
    val muted: Color get() = Color(0xFF9AA3C0)
}
