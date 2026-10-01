package com.rm.apogee.net

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.withContext
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
 * A [Transport] over a TCP socket. Each frame is one byte of channel, four bytes of big-endian
 * length, then the payload, since TCP is a stream with no message boundaries.
 *
 * Blocking IO on [Dispatchers.IO], a thread per connection, which is fine for tens of players.
 * TCP's head-of-line blocking isn't ideal for a 20 Hz stream; the [Channel] split marks where an
 * unreliable path could go.
 */
class TcpTransport private constructor(
    private val socket: Socket,
    private val input: DataInputStream,
    private val output: DataOutputStream,
) : Transport {

    private val writeLock = Mutex()

    @Volatile
    private var closed = false

    /** The remote address, for logging and the player list. */
    val remoteAddress: String get() = socket.inetAddress?.hostAddress ?: "?"

    override val incoming: Flow<Packet> = flow {
        try {
            while (!closed) {
                val channelOrdinal = input.readByte().toInt()
                val length = input.readInt()
                if (length < 0 || length > MAX_FRAME_BYTES) {
                    // Corrupt or hostile. We've lost the frame boundary, so drop the stream.
                    throw IOException("Frame length $length out of range")
                }
                val channel = CHANNELS.getOrNull(channelOrdinal)
                    ?: throw IOException("Unknown channel $channelOrdinal")

                val bytes = ByteArray(length)
                input.readFully(bytes)
                emit(Packet(channel, bytes))
            }
        } catch (_: EOFException) {
            // The other end hung up.
        } catch (_: IOException) {
            // A reset, timeout or bad frame. Treated as a disconnect.
        } finally {
            close()
        }
    }.flowOn(Dispatchers.IO)

    override suspend fun send(packet: Packet) {
        if (closed) return
        // Blocking writes go on IO. The client sends commands from the UI scope, which on Android
        // is the main thread, where a socket write throws NetworkOnMainThreadException.
        withContext(Dispatchers.IO) {
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
    }

    override fun close() {
        if (closed) return
        closed = true
        runCatching { socket.close() }
    }

    companion object {
        /**
         * Larger frames are refused, so a bad length can't make us allocate any size. A craft
         * design is a few kilobytes.
         */
        const val MAX_FRAME_BYTES = 1 shl 20

        private val CHANNELS = Channel.entries.toTypedArray()

        fun wrap(socket: Socket): TcpTransport {
            // Nagle off, so each 20 Hz update goes out at once.
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
 * Accepts TCP connections and hands each one to [onConnected] as a [Transport], so
 * [com.rm.apogee.server.GameServer] never sees sockets.
 */
class TcpListener(
    private val port: Int,
    private val onConnected: (TcpTransport) -> Unit,
) {
    private var serverSocket: java.net.ServerSocket? = null

    /** The port actually bound, which may be different if 0 was asked for. */
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
                // Closed while blocked in accept(); that's how stop() works.
            }
        }
    }

    fun stop() {
        running = false
        runCatching { serverSocket?.close() }
        serverSocket = null
    }
}
