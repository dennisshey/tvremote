package com.sidephone.atvremote.companion

/** HID button codes understood by tvOS (pyatv `companion/api.py`). */
enum class HidCommand(val value: Int) {
    Up(1),
    Down(2),
    Left(3),
    Right(4),
    Menu(5),
    Select(6),
    Home(7),
    VolumeUp(8),
    VolumeDown(9),
    Siri(10),
    Screensaver(11),
    Sleep(12),
    Wake(13),
    PlayPause(14),
    ChannelIncrement(15),
    ChannelDecrement(16),
    Guide(17),
    PageUp(18),
    PageDown(19),
}

/** Attention (power) states reported by "FetchAttentionState" (pyatv `companion/api.py`). */
enum class SystemStatus(val value: Int) {
    Asleep(1),
    Screensaver(2),
    Awake(3),
    Idle(4);

    companion object {
        fun from(value: Int?): SystemStatus? = entries.firstOrNull { it.value == value }
    }
}

/** Media-control codes understood by tvOS. */
enum class MediaControlCommand(val value: Int) {
    Play(1),
    Pause(2),
    NextTrack(3),
    PreviousTrack(4),
    GetVolume(5),
    SetVolume(6),
    SkipBy(7),
    FastForwardBegin(8),
    FastForwardEnd(9),
    RewindBegin(10),
    RewindEnd(11),
}
