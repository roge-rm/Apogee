package com.rm.apogee.net

import kotlinx.coroutines.flow.Flow

/**
 * Message channels. Structure and kinematics are split because they're shaped the opposite way. A
 * [STRUCTURE] message is large and rare (a craft appears, a stage separates), and a [KINEMATICS]
 * message is small and constant (20 Hz of positions). Keeping them apart stops a craft spawn from
 * stalling the position stream, and later it lets kinematics move to unreliable delivery while
 * structure stays reliable.
 */
enum class Channel {
    /** Handshake, chat and commands. Reliable and ordered. */
    CONTROL,

    /** Craft designs, spawns, decouples and destruction. Reliable and ordered. */
    STRUCTURE,

    /** Per-tick vessel positions. The latest one wins, so dropping one does no harm. */
    KINEMATICS,
}

data class Packet(val channel: Channel, val bytes: ByteArray) {
    override fun equals(other: Any?): Boolean =
        this === other || (other is Packet && channel == other.channel && bytes.contentEquals(other.bytes))

    override fun hashCode(): Int = 31 * channel.hashCode() + bytes.contentHashCode()
}

/**
 * The one seam between the game and the network.
 *
 * Single player is a one-player server talking over [LoopbackTransport], so the client code path is
 * the same whether or not there's a socket involved. Swapping in the TCP version for LAN play
 * changes nothing above this interface. That's the whole reason I built multiplayer in from the
 * start instead of bolting it on later.
 */
interface Transport {
    val incoming: Flow<Packet>
    suspend fun send(packet: Packet)
    fun close()
}
