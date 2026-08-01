package com.sidephone.atvremote.remote

/** A discovered (or manually entered) Apple TV Companion endpoint. */
data class AppleTvDevice(
    val name: String,
    val host: String,
    val port: Int,
) {
    /** Stable-ish key for de-duplicating discovery results and storing pairings. */
    val id: String get() = "$host:$port"
}
