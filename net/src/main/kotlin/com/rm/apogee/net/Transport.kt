package com.rm.apogee.net

import kotlinx.coroutines.flow.Flow

/**
 * Message channels. Structure and kinematics are split because they have
 * opposite shapes: a [STRUCTURE] message is large and rare (a craft appears, a
 * stage separates), a [KINEMATICS] message is small and constant (20 Hz of
 * positions). Keeping them apart is what stops a craft spawn from stalling the
 * position stream, and later lets kinematics move to unreliable delivery while
 * structure stays reliable.
 */
enum class Channel {
    /** Handshake, chat, commands. Reliable, ordered. */
    CONTROL,

    /** Craft designs, spawns, decouples, destruction. Reliable, ordered. */
    STRUCTURE,

    /** Per-tick vessel positions. Latest-wins; dropping one is harmless. */
    KINEMATICS,
}

data class Packet(val channel: Channel, val bytes: ByteArray) {
    override fun equals(other: Any?): Boolean =
        this === other || (other is Packet && channel == other.channel && bytes.contentEquals(other.bytes))

    override fun hashCode(): Int = 31 * channel.hashCode() + bytes.contentHashCode()
}

/**
 * The single seam between the game and the network.
 *
 * Single-player is a one-player server talking over [LoopbackTransport], so the
 * client code path is identical whether or not a socket is involved. Swapping
 * in the TCP implementation for LAN play changes nothing above this interface -
 * that is the whole reason multiplayer is being built in from the start rather
 * than retrofitted.
 */
interface Transport {
    val incoming: Flow<Packet>
    suspend fun send(packet: Packet)
    fun close()
}
