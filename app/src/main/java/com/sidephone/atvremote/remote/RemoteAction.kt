package com.sidephone.atvremote.remote

/**
 * A device-agnostic remote intent. Physical keys map to these (see [KeyMapper]),
 * and [RemoteController] translates each one into Companion commands. Keeping this
 * layer free of any Apple/Companion detail makes both the keymap and the on-screen
 * buttons trivial to reason about and unit-test.
 */
enum class RemoteAction(val label: String) {
    UP("Up"),
    DOWN("Down"),
    LEFT("Left"),
    RIGHT("Right"),
    SELECT("Select"),
    BACK("Back"),
    HOME("Home"),
    PLAY_PAUSE("Play / Pause"),
    VOLUME_UP("Volume +"),
    VOLUME_DOWN("Volume −"),
    SKIP_FORWARD("Skip forward"),
    SKIP_BACKWARD("Skip backward"),
    SIRI("Siri"),
    POWER("Power"),
}
