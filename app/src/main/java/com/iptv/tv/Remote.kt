package com.iptv.tv

import android.view.KeyEvent

/**
 * Remote control mapping (Settings > Remote control), like the TV's own "Remote control" screen.
 * Each D-pad key (short press or hold) can be given one action. Short presses only apply in the Live full-screen player,
 * everywhere else the arrows keep moving the focus. Holds work in the live list, the movie / series grid and the live player.
 */
object Remote {
    const val NONE = 0; const val TOP = 1; const val LAST = 2; const val RECENT = 3; const val CH_UP = 4
    const val CH_DOWN = 5; const val TOGGLE = 6; const val FAV = 7; const val SEARCH = 8; const val SETTINGS = 9

    val labels = listOf(
        "Not set", "Top icons", "Last channel", "Recent channels", "Channel up",
        "Channel down", "List / full screen", "Favorite on/off", "Search", "Settings"
    )

    // Slots, same order as the TV's own screen.
    const val OK_HOLD = 0; const val LEFT = 1; const val LEFT_HOLD = 2; const val RIGHT = 3; const val RIGHT_HOLD = 4
    const val UP = 5; const val UP_HOLD = 6; const val DOWN = 7; const val DOWN_HOLD = 8

    val slotNames = listOf(
        "OK (hold down)", "Left", "Left (hold down)", "Right", "Right (hold down)",
        "Up", "Up (hold down)", "Down", "Down (hold down)"
    )
    fun isHold(slot: Int) = slot == OK_HOLD || slot == LEFT_HOLD || slot == RIGHT_HOLD || slot == UP_HOLD || slot == DOWN_HOLD

    // Up hold = top icons and Left hold = last channel as requested; the short presses are what the app always did.
    private val defaults = intArrayOf(NONE, TOGGLE, LAST, NONE, NONE, CH_UP, TOP, CH_DOWN, NONE)

    fun get(slot: Int): Int = Store.int("rc_$slot", defaults[slot]).coerceIn(0, labels.size - 1)
    fun set(slot: Int, a: Int) { Store.put("rc_$slot", a) }
    fun reset() { Store.sp.edit().apply { for (i in defaults.indices) remove("rc_$i") }.apply() }
    fun label(slot: Int) = labels[get(slot)]

    /** Actions that make sense for a slot (a short press can not be "top icons"). */
    fun choices(slot: Int): List<Int> = labels.indices.filter { isHold(slot) || it != TOP }

    /** Slot of a D-pad key, or -1. */
    fun slotOf(keyCode: Int, hold: Boolean): Int = when (keyCode) {
        KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER, KeyEvent.KEYCODE_NUMPAD_ENTER -> if (hold) OK_HOLD else -1
        KeyEvent.KEYCODE_DPAD_LEFT -> if (hold) LEFT_HOLD else LEFT
        KeyEvent.KEYCODE_DPAD_RIGHT -> if (hold) RIGHT_HOLD else RIGHT
        KeyEvent.KEYCODE_DPAD_UP -> if (hold) UP_HOLD else UP
        KeyEvent.KEYCODE_DPAD_DOWN -> if (hold) DOWN_HOLD else DOWN
        else -> -1
    }

    fun isPageUp(k: Int) = k == KeyEvent.KEYCODE_CHANNEL_UP || k == KeyEvent.KEYCODE_PAGE_UP
    fun isPageDown(k: Int) = k == KeyEvent.KEYCODE_CHANNEL_DOWN || k == KeyEvent.KEYCODE_PAGE_DOWN
}

/** Remembers which key is being held and whether its hold action has already fired. */
class HoldState { var key = -1; var fired = false }

/**
 * Hold handling for screens where the arrows move the focus (live list, movie / series grid).
 * A normal press still moves the focus at once. When the key stays down, the mapped action runs once and the
 * auto-repeat is swallowed so the list does not keep scrolling. [act] returns false when the action does not apply here.
 * Use from onPreviewKeyEvent.
 */
fun holdPreview(e: KeyEvent, st: HoldState, act: (Int) -> Boolean): Boolean {
    val k = e.keyCode
    val slot = Remote.slotOf(k, true)
    if (slot < 0 || slot == Remote.OK_HOLD) return false   // OK hold is the item menu in lists
    val a = Remote.get(slot)
    if (a == Remote.NONE) return false
    if (e.action == KeyEvent.ACTION_UP) {
        val was = st.fired && st.key == k
        if (st.key == k) { st.key = -1; st.fired = false }
        return was
    }
    if (e.action != KeyEvent.ACTION_DOWN) return false
    if (e.repeatCount == 0) { st.key = k; st.fired = false; return false }
    if (st.key == k && st.fired) return true
    if (e.repeatCount >= 1 && st.key == k && !st.fired) {
        if (act(a)) { st.fired = true; return true }
    }
    return false
}
