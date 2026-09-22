package com.rm.apogee.net

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.NetworkInterface
import java.net.SocketTimeoutException

/** What a host broadcasts about itself. */
@Serializable
data class ServerBeacon(
    val serverName: String,
    val port: Int,
    val players: Int,
    val protocolVersion: Int,
    val catalogHash: String,
) {
    /** Filled in by the listener from the packet's source. */
    @kotlinx.serialization.Transient
    var address: String = ""
}

/**
 * Finding games on the local network, without a lobby server.
 *
 * A host broadcasts a small JSON beacon once a second; clients listen. Chosen
 * over mDNS/NSD because it behaves identically on Android and on a desktop JVM
 * - the dedicated server can announce itself with exactly this code - and
 * because the payload can carry the protocol version and catalogue hash, so a
 * client can grey out an incompatible game in the list instead of discovering
 * the mismatch only after trying to join.
 *
 * JSON rather than the protobuf the game uses: a beacon is a handful of fields
 * sent once a second, and being able to read one with tcpdump while debugging
 * why two devices cannot see each other is worth more than the bytes.
 */
object LanDiscovery {

    /** Fixed so hosts and clients agree without configuration. */
    const val PORT = 45_677

    private val format = Json { ignoreUnknownKeys = true }

    private const val MAGIC = "APOGEE1"

    /**
     * Broadcasts [beacon] until the scope is cancelled.
     *
     * Sends to every broadcast address the device has, not just
     * 255.255.255.255: on Android that global address is frequently dropped,
     * while the per-interface broadcast address gets through.
     */
    fun announce(beacon: ServerBeacon, scope: CoroutineScope): Job =
        scope.launch(Dispatchers.IO) {
            val socket = DatagramSocket().apply { broadcast = true }
            val payload = (MAGIC + format.encodeToString(beacon)).toByteArray()
            try {
                while (isActive) {
                    for (address in broadcastAddresses()) {
                        runCatching {
                            socket.send(
                                DatagramPacket(payload, payload.size, address, PORT)
                            )
                        }
                    }
                    delay(ANNOUNCE_INTERVAL_MILLIS)
                }
            } finally {
                runCatching { socket.close() }
            }
        }

    /**
     * Listens for beacons, calling [onFound] for each.
     *
     * The caller is responsible for de-duplicating: a host announces once a
     * second forever, and on a device with several interfaces the same beacon
     * can arrive more than once per round.
     */
    fun listen(scope: CoroutineScope, onFound: (ServerBeacon) -> Unit): Job =
        scope.launch(Dispatchers.IO) {
            val socket = DatagramSocket(null).apply {
                reuseAddress = true
                soTimeout = LISTEN_TIMEOUT_MILLIS
                bind(InetSocketAddress(PORT))
            }
            val buffer = ByteArray(2_048)
            try {
                while (isActive) {
                    val packet = DatagramPacket(buffer, buffer.size)
                    try {
                        socket.receive(packet)
                    } catch (_: SocketTimeoutException) {
                        continue
                    }
                    val text = String(packet.data, packet.offset, packet.length)
                    if (!text.startsWith(MAGIC)) continue

                    // A malformed beacon is not worth taking the browser down
                    // for - an older build on the network will send one.
                    val beacon = runCatching {
                        format.decodeFromString<ServerBeacon>(text.removePrefix(MAGIC))
                    }.getOrNull() ?: continue

                    beacon.address = packet.address.hostAddress ?: continue
                    onFound(beacon)
                }
            } finally {
                runCatching { socket.close() }
            }
        }

    /** Every interface's broadcast address, plus the global fallback. */
    private fun broadcastAddresses(): List<InetAddress> {
        val addresses = ArrayList<InetAddress>(4)
        runCatching {
            for (nic in NetworkInterface.getNetworkInterfaces()) {
                if (!nic.isUp || nic.isLoopback) continue
                for (binding in nic.interfaceAddresses) {
                    binding.broadcast?.let { addresses.add(it) }
                }
            }
        }
        runCatching { addresses.add(InetAddress.getByName("255.255.255.255")) }
        return addresses
    }

    private const val ANNOUNCE_INTERVAL_MILLIS = 1_000L
    private const val LISTEN_TIMEOUT_MILLIS = 500
}
