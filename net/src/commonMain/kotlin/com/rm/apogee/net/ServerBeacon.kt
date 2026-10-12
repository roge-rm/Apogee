package com.rm.apogee.net

import kotlinx.serialization.Serializable

/** What a host broadcasts about itself. */
@Serializable
data class ServerBeacon(
    val serverName: String,
    val port: Int,
    val players: Int,
    val protocolVersion: Int,
    val catalogHash: String,
    /** It asks for a password. */
    val locked: Boolean = false,
    /** Made up for each game announced, so one heard on two networks is listed once. */
    val hostId: String = "",
) {
    /** Filled in by the listener from where the packet came from. */
    @kotlinx.serialization.Transient
    var address: String = ""
}
