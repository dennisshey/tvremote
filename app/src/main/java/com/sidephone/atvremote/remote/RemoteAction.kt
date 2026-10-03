package com.sidephone.atvremote.remote

/**
 * A device-agnostic remote intent. Physical keys map to these (see [KeyMapper]),
 * and [RemoteController] translates each one into Companion commands. Keeping this
 * layer free of any Apple/Companion detail makes both the keymap and the on-screen
 * buttons trivial to reason about and unit-test.
 *
 * [holdable] actions support press-and-hold. tvOS does not auto-repeat a held
 * Companion HID arrow, so [autoRepeat] actions (the D-pad) are repeated by us while
 * held, giving continuous scrolling; other holdable actions (Select) keep the button
 * down on the TV from key-down to key-up, which opens context menus like the Siri
 * Remote.
 */
enum class RemoteAction(
    val label: String,
    val holdable: Boolean = false,
    val autoRepeat: Boolean = false,
) {
    UP("Up", holdable = true, autoRepeat = true),
    DOWN("Down", holdable = true, autoRepeat = true),
    LEFT("Left", holdable = true, autoRepeat = true),
    RIGHT("Right", holdable = true, autoRepeat = true),
    SELECT("Select", holdable = true),
    BACK("Back"),
    HOME("Home"),
    HOME_SCREEN("Home Screen"),
    PLAY_PAUSE("Play / Pause"),
    VOLUME_UP("Volume +"),
    VOLUME_DOWN("Volume −"),
    SKIP_FORWARD("Skip forward"),
    SKIP_BACKWARD("Skip backward"),
    SIRI("Siri"),
    POWER("Power"),
}
