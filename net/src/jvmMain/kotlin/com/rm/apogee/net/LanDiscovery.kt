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

/**
 * Finding games on the local network, without a lobby server.
 *
 * A host broadcasts a small JSON beacon once a second, and clients listen. I chose this over
 * mDNS/NSD because it behaves the same on Android and on a desktop JVM (the dedicated server can
 * announce itself with exactly this code), and because the payload can carry the protocol version
 * and catalogue hash. That way a client can grey out a game it can't join in the list, instead of
 * finding out only after trying to join.
 *
 * It's JSON instead of the protobuf the game uses. A beacon is a handful of fields sent once a
 * second, and being able to read one with tcpdump while working out why two devices can't see each
 * other is worth more than the bytes.
 */
object LanDiscovery {

    /** Fixed, so hosts and clients agree without any setup. */
    const val PORT = 45_677

    private val format = Json { ignoreUnknownKeys = true }

    private const val MAGIC = "APOGEE1"

    /**
     * Broadcasts [beacon] until the scope is cancelled.
     *
     * It sends to every broadcast address the device has, not just 255.255.255.255. On Android that
     * global address often gets dropped, while the per-interface broadcast address gets through.
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
     * Listens for beacons, calling [onFound] for each one.
     *
     * The caller has to remove the duplicates. A host announces once a second forever, and on a
     * device with several interfaces the same beacon can arrive more than once per round.
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

                    // A malformed beacon isn't worth taking the browser down for. An older build on
                    // the network will send one.
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
