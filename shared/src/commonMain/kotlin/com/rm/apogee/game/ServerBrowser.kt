package com.rm.apogee.game

import com.rm.apogee.core.world.Protocol
import com.rm.apogee.net.ServerBeacon
import kotlinx.coroutines.CoroutineScope

/** A game found on the network, as the join list shows it. */
class DiscoveredServer(
    val beacon: ServerBeacon,
    val lastSeenMillis: Long,
) {
    /** One per game: its host's id, or for an older host its address. */
    val key: String get() = if (beacon.hostId.isNotEmpty()) "id:${beacon.hostId}" else "${beacon.address}:${beacon.port}"

    /** Whether this client could actually play on it. */
    val compatible: Boolean
        get() = beacon.protocolVersion == Protocol.VERSION

    companion object {
        /**
         * The list's entry for [beacon] heard now, given what it had for that game. A game heard
         * on two networks keeps an address others can reach, not this machine's own 127.0.0.1.
         */
        fun heard(old: DiscoveredServer?, beacon: ServerBeacon, now: Long): DiscoveredServer {
            if (old != null && isLoopback(beacon.address) && !isLoopback(old.beacon.address)) {
                beacon.address = old.beacon.address
            }
            return DiscoveredServer(beacon, now)
        }

        private fun isLoopback(address: String) = address.startsWith("127.") || address == "::1"
    }

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
