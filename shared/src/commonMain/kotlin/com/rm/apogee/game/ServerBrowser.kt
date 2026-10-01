package com.rm.apogee.game

import com.rm.apogee.core.world.Protocol
import com.rm.apogee.net.ServerBeacon
import kotlinx.coroutines.CoroutineScope

/** A game found on the network, as the join list shows it. */
class DiscoveredServer(
    val beacon: ServerBeacon,
    val lastSeenMillis: Long,
) {
    val key: String get() = "${beacon.address}:${beacon.port}"

    /** Whether this client could actually play on it. */
    val compatible: Boolean
        get() = beacon.protocolVersion == Protocol.VERSION

    fun incompatibilityReason(localCatalogHash: String): String? = when {
        beacon.protocolVersion != Protocol.VERSION ->
            "Different game version"
        beacon.catalogHash != localCatalogHash ->
            "Different parts"
        else -> null
    }
}

/**
 * Games found on the local network, for Join a Game. Android listens for beacons; a browser can't,
 * so its list stays empty.
 */
interface ServerBrowser {
    val servers: List<DiscoveredServer>
    val scanning: Boolean

    fun start(scope: CoroutineScope)
    fun stop()

    /** Why [server] can't be joined from here, or null when it can. */
    fun reasonFor(server: DiscoveredServer): String?
}
