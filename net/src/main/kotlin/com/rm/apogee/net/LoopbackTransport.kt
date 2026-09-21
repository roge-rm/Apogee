package com.rm.apogee.net

import kotlinx.coroutines.channels.Channel as CoroutineChannel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.receiveAsFlow

/**
 * A [Transport] pair joined in memory, with no socket involved.
 *
 * This is what makes "single-player is a one-player server" true rather than
 * aspirational. The client talks to a server through exactly the same interface
 * whether that server is in this process or on someone else's phone, so the
 * networked build is not a separate code path that only gets exercised when
 * two devices are in the room.
 */
class LoopbackTransportPair(capacity: Int = 256) {

    private val toServer = CoroutineChannel<Packet>(capacity)
    private val toClient = CoroutineChannel<Packet>(capacity)

    val clientSide: Transport = Endpoint(outgoing = toServer, incomingChannel = toClient)
    val serverSide: Transport = Endpoint(outgoing = toClient, incomingChannel = toServer)

    fun close() {
        toServer.close()
        toClient.close()
    }

    private class Endpoint(
        private val outgoing: CoroutineChannel<Packet>,
        private val incomingChannel: CoroutineChannel<Packet>,
    ) : Transport {

        override val incoming: Flow<Packet> = incomingChannel.receiveAsFlow()

        override suspend fun send(packet: Packet) {
            // A closed channel means the peer has gone. Over a real socket that
            // is an ordinary disconnect, not an error, so it must not propagate
            // as one here either.
            runCatching { outgoing.send(packet) }
        }

        override fun close() {
            outgoing.close()
        }
    }
}
