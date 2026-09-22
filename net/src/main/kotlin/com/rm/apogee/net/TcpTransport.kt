package com.rm.apogee.net

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.EOFException
import java.io.IOException
import java.net.InetSocketAddress
import java.net.Socket

/**
 * A [Transport] over a TCP socket.
 *
 * Framing is one byte of channel, four bytes of big-endian length, then the
 * payload. TCP is a stream and has no notion of where one message ends - a
 * protocol that forgets that works perfectly on localhost, where writes tend to
 * arrive whole, and then fragments the moment it meets a real network.
 *
 * Blocking IO on [Dispatchers.IO] rather than NIO selectors. A game hosted from
 * a phone has a handful of players and a dedicated server has tens; at that
 * scale a thread per connection is simpler, easier to reason about, and behaves
 * identically on Android and on a server JVM. Selectors become worth their
 * complexity in the hundreds.
 *
 * TCP, not UDP, for now. Head-of-line blocking means a lost packet delays the
 * positions behind it, which is exactly the wrong trade for a 20 Hz state
 * stream - but it is the right trade for getting two devices flying together,
 * and the [Channel] split already marks where an unreliable path will go.
 */
class TcpTransport private constructor(
    private val socket: Socket,
    private val input: DataInputStream,
    private val output: DataOutputStream,
) : Transport {

    private val writeLock = Mutex()

    @Volatile
    private var closed = false

    /** Remote address, for logging and the player list. */
    val remoteAddress: String get() = socket.inetAddress?.hostAddress ?: "?"

    override val incoming: Flow<Packet> = flow {
        try {
            while (!closed) {
                val channelOrdinal = input.readByte().toInt()
                val length = input.readInt()
                if (length < 0 || length > MAX_FRAME_BYTES) {
                    // A bad length is either corruption or something hostile.
                    // Either way the stream can no longer be trusted, because
                    // we no longer know where the next frame starts.
                    throw IOException("Frame length $length out of range")
                }
                val channel = CHANNELS.getOrNull(channelOrdinal)
                    ?: throw IOException("Unknown channel $channelOrdinal")

                val bytes = ByteArray(length)
                input.readFully(bytes)
                emit(Packet(channel, bytes))
            }
        } catch (_: EOFException) {
            // The peer hung up. An ordinary disconnect, not an error.
        } catch (_: IOException) {
            // Reset, timeout, or a malformed frame. Also just a disconnect from
            // the game's point of view.
        } finally {
            close()
        }
    }.flowOn(Dispatchers.IO)

    override suspend fun send(packet: Packet) {
        if (closed) return
        writeLock.withLock {
            try {
                output.writeByte(packet.channel.ordinal)
                output.writeInt(packet.bytes.size)
                output.write(packet.bytes)
                output.flush()
            } catch (_: IOException) {
                close()
            }
        }
    }

    override fun close() {
        if (closed) return
        closed = true
        runCatching { socket.close() }
    }

    companion object {
        /**
         * Frames larger than this are refused.
         *
         * A craft design is a few kilobytes; a megabyte is already implausible.
         * The cap exists so a corrupt or hostile length cannot make the server
         * allocate an arbitrary buffer on demand.
         */
        const val MAX_FRAME_BYTES = 1 shl 20

        private val CHANNELS = Channel.entries.toTypedArray()

        fun wrap(socket: Socket): TcpTransport {
            // Disable Nagle: the whole point of a 20 Hz state stream is that
            // each update goes out now, not when the buffer happens to fill.
            runCatching { socket.tcpNoDelay = true }
            return TcpTransport(
                socket,
                DataInputStream(socket.getInputStream().buffered()),
                DataOutputStream(socket.getOutputStream().buffered()),
            )
        }

        /** Connects to a host, or fails with the reason. */
        suspend fun connect(
            host: String,
            port: Int,
            timeoutMillis: Int = 5_000,
        ): Result<TcpTransport> = runCatching {
            kotlinx.coroutines.withContext(Dispatchers.IO) {
                val socket = Socket()
                socket.connect(InetSocketAddress(host, port), timeoutMillis)
                wrap(socket)
            }
        }
    }
}

/**
 * Accepts TCP connections and hands each one to [onConnected] as a [Transport].
 *
 * Knows nothing about the game: it is the seam between sockets and sessions, so
 * that [com.rm.apogee.server.GameServer] never has to.
 */
class TcpListener(
    private val port: Int,
    private val onConnected: (TcpTransport) -> Unit,
) {
    private var serverSocket: java.net.ServerSocket? = null

    /** The port actually bound, which may differ if 0 was requested. */
    val boundPort: Int get() = serverSocket?.localPort ?: port

    @Volatile
    private var running = false

    fun start(scope: CoroutineScope): kotlinx.coroutines.Job {
        val socket = java.net.ServerSocket(port)
        serverSocket = socket
        running = true

        return scope.launch(Dispatchers.IO) {
            try {
                while (running && isActive) {
                    val client = socket.accept()
                    onConnected(TcpTransport.wrap(client))
                }
            } catch (_: IOException) {
                // Closed while blocked in accept(); that is how stop() works.
            }
        }
    }

    fun stop() {
        running = false
        runCatching { serverSocket?.close() }
        serverSocket = null
    }
}
