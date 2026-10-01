package com.rm.apogee.net

import kotlinx.coroutines.channels.Channel as CoroutineChannel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.receiveAsFlow

/**
 * A [Transport] pair joined in memory. Single player is a one-player server over this, so the
 * client uses the same path with or without a socket.
 */
class LoopbackTransportPair(
    capacity: Int = 256,
    /** Pass messages across as they are (see [Transport.passesObjects]). Off, they go through the codec as over a socket. */
    objects: Boolean = false,
) {

    private val toServer = CoroutineChannel<Packet>(capacity)
    private val toClient = CoroutineChannel<Packet>(capacity)

    val clientSide: Transport = Endpoint(outgoing = toServer, incomingChannel = toClient, passesObjects = objects)
    val serverSide: Transport = Endpoint(outgoing = toClient, incomingChannel = toServer, passesObjects = objects)

    fun close() {
        toServer.close()
        toClient.close()
    }

    private class Endpoint(
        private val outgoing: CoroutineChannel<Packet>,
        private val incomingChannel: CoroutineChannel<Packet>,
        override val passesObjects: Boolean,
    ) : Transport {

        override val incoming: Flow<Packet> = incomingChannel.receiveAsFlow()

        override suspend fun send(packet: Packet) {
            // A closed channel means the other end has gone. That's a disconnect, so don't throw.
            runCatching { outgoing.send(packet) }
        }

        override fun close() {
            outgoing.close()
        }
    }
}
