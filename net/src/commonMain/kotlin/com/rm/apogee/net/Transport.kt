package com.rm.apogee.net

import kotlinx.coroutines.flow.Flow

/**
 * Message channels. [STRUCTURE] is large and rare, [KINEMATICS] small and constant (20 Hz), so a
 * craft spawn can't stall positions, and kinematics could later go unreliable.
 */
enum class Channel {
    /** Handshake, chat and commands. Reliable and ordered. */
    CONTROL,

    /** Craft designs, spawns, decouples and destruction. Reliable and ordered. */
    STRUCTURE,

    /** Per-tick vessel positions. The latest one wins, so dropping one does no harm. */
    KINEMATICS,
}

data class Packet(
    val channel: Channel,
    val bytes: ByteArray,
    /** The message itself when the transport [Transport.passesObjects]; [bytes] is then empty. */
    val message: Any? = null,
) {
    override fun equals(other: Any?): Boolean =
        this === other || (other is Packet && channel == other.channel && bytes.contentEquals(other.bytes))

    override fun hashCode(): Int = 31 * channel.hashCode() + bytes.contentHashCode()
}

/**
 * The one seam between the game and the network. Single player runs over [LoopbackTransport] and
 * LAN play over TCP; nothing above this interface changes.
 */
interface Transport {
    val incoming: Flow<Packet>

    /** Both ends are in this process, so messages go across unencoded. */
    val passesObjects: Boolean get() = false

    suspend fun send(packet: Packet)
    fun close()
}
