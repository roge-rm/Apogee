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
 * Finds games on the local network with no lobby server. A host broadcasts a small JSON beacon once
 * a second and clients listen. It works the same on Android and the dedicated server, and the
 * beacon carries the protocol version and catalogue hash so the list can grey out games you can't
 * join. JSON so a beacon can be read in tcpdump.
 */
object LanDiscovery {

    /** Fixed, so hosts and clients agree without any setup. */
    const val PORT = 45_677

    private val format = Json { ignoreUnknownKeys = true }

    private const val MAGIC = "APOGEE1"

    /**
     * Broadcasts [beacon] until the scope is cancelled, to every interface's broadcast address as
     * well as 255.255.255.255, which Android often drops.
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
     * Listens for beacons, calling [onFound] for each one. The caller removes duplicates: hosts
     * repeat every second, and a beacon can arrive once per interface.
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

                    // Skip malformed beacons; an older build may send them.
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
