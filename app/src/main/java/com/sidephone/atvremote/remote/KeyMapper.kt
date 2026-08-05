package com.sidephone.atvremote.remote

import android.view.KeyEvent

/**
 * Translates SidePhone SP-01 hardware keys into [RemoteAction]s.
 *
 * The SP-01 surfaces its physical keypad as ordinary Android [KeyEvent]s, so the
 * mapping is just a lookup table. Two intents are served at once:
 *
 *  - The **D-pad** is the primary navigation surface: up/down/left/right/centre
 *    drive the Apple TV cursor 1:1, and Back/backspace act as the Apple TV "Back".
 *  - The **T9 number pad** doubles as a "virtual D-pad" (2-4-6-8 = arrows, 5 =
 *    select) so keypad-only tiles without a D-pad remain fully usable, while the
 *    outer keys are media/transport shortcuts.
 *
 * The table is intentionally the single source of truth: the on-screen help
 * ([cheatSheet]) is generated from the same data, so docs never drift from code.
 */
object KeyMapper {

    /** Short-press mapping. */
    private val shortPress: Map<Int, RemoteAction> = buildMap {
        // Physical D-pad (primary).
        put(KeyEvent.KEYCODE_DPAD_UP, RemoteAction.UP)
        put(KeyEvent.KEYCODE_DPAD_DOWN, RemoteAction.DOWN)
        put(KeyEvent.KEYCODE_DPAD_LEFT, RemoteAction.LEFT)
        put(KeyEvent.KEYCODE_DPAD_RIGHT, RemoteAction.RIGHT)
        put(KeyEvent.KEYCODE_DPAD_CENTER, RemoteAction.SELECT)
        put(KeyEvent.KEYCODE_ENTER, RemoteAction.SELECT)
        put(KeyEvent.KEYCODE_CALL, RemoteAction.SELECT)

        // Apple TV Back (the Companion "Menu" HID code). The SP-01 keypad's
        // backspace key arrives as KEYCODE_DEL — same intent. Holding either is
        // Home (see longPress); the system back *gesture* is deliberately not
        // mapped so it keeps its Android meaning — leave for the device list
        // (see RemoteActivity.isGestureBack).
        put(KeyEvent.KEYCODE_BACK, RemoteAction.BACK)
        put(KeyEvent.KEYCODE_DEL, RemoteAction.BACK)
        put(KeyEvent.KEYCODE_MENU, RemoteAction.HOME)

        // T9 keypad as a virtual D-pad + transport controls.
        put(KeyEvent.KEYCODE_2, RemoteAction.UP)
        put(KeyEvent.KEYCODE_8, RemoteAction.DOWN)
        put(KeyEvent.KEYCODE_4, RemoteAction.LEFT)
        put(KeyEvent.KEYCODE_6, RemoteAction.RIGHT)
        put(KeyEvent.KEYCODE_5, RemoteAction.SELECT)
        put(KeyEvent.KEYCODE_1, RemoteAction.BACK)
        put(KeyEvent.KEYCODE_3, RemoteAction.HOME)
        put(KeyEvent.KEYCODE_7, RemoteAction.SKIP_BACKWARD)
        put(KeyEvent.KEYCODE_9, RemoteAction.SKIP_FORWARD)
        put(KeyEvent.KEYCODE_0, RemoteAction.PLAY_PAUSE)
        put(KeyEvent.KEYCODE_STAR, RemoteAction.VOLUME_DOWN)
        put(KeyEvent.KEYCODE_POUND, RemoteAction.VOLUME_UP)

        // Hardware volume rocker (if present) drives the Apple TV, not the phone.
        put(KeyEvent.KEYCODE_VOLUME_UP, RemoteAction.VOLUME_UP)
        put(KeyEvent.KEYCODE_VOLUME_DOWN, RemoteAction.VOLUME_DOWN)
    }

    /** Long-press overrides. */
    private val longPress: Map<Int, RemoteAction> = mapOf(
        KeyEvent.KEYCODE_POUND to RemoteAction.SIRI,
        KeyEvent.KEYCODE_5 to RemoteAction.HOME,
        KeyEvent.KEYCODE_0 to RemoteAction.POWER,
        KeyEvent.KEYCODE_BACK to RemoteAction.HOME,
        KeyEvent.KEYCODE_DEL to RemoteAction.HOME,
    )

    fun map(keyCode: Int): RemoteAction? = shortPress[keyCode]

    fun mapLongPress(keyCode: Int): RemoteAction? = longPress[keyCode]

    /** True for keys we consume so the OS doesn't also act on them (e.g. volume). */
    fun handles(keyCode: Int): Boolean = shortPress.containsKey(keyCode)

    /** Human-readable mapping for the on-screen help sheet. */
    val cheatSheet: List<Pair<String, String>> = listOf(
        "D-pad" to "Navigate",
        "D-pad centre / 5" to "Select",
        "Back / ⌫ / 1" to "Back",
        "Menu / 3" to "Home",
        "2 4 6 8" to "Up / Left / Right / Down",
        "0" to "Play / Pause",
        "7 / 9" to "Skip back / forward",
        "∗ / #" to "Volume − / +",
        "Hold #" to "Siri",
        "Hold 0" to "Power (sleep / wake)",
        "Hold Back / ⌫" to "Home",
        "Back swipe gesture" to "Device list",
    )
}
