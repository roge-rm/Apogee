package com.rm.apogee.desktop

import java.io.File
import java.net.StandardProtocolFamily
import java.net.UnixDomainSocketAddress
import java.nio.ByteBuffer
import java.nio.channels.FileChannel
import java.nio.channels.FileLock
import java.nio.channels.ServerSocketChannel
import java.nio.channels.SocketChannel
import java.nio.file.StandardOpenOption

/**
 * One copy of the app at a time. The first takes a lock in [dir] and listens
 * on a socket beside it; a later launch finds the lock taken, asks the first
 * to come to the front, and quits. Two copies would fight over the saved
 * worlds and the LAN port.
 */
internal object SingleInstance {
    /** Called on the socket's thread when another launch asks for the window. */
    @Volatile var onRaise: (() -> Unit)? = null

    // Held for as long as the app runs; the OS lets it go when the process ends, crash or not.
    private var lock: FileLock? = null

    /** True if this is the only copy; false after asking the running one to show itself. */
    fun claim(dir: File): Boolean {
        val socket = File(dir, "running.sock").toPath()
        val channel = runCatching {
            FileChannel.open(File(dir, "running.lock").toPath(), StandardOpenOption.CREATE, StandardOpenOption.WRITE)
        }.getOrNull() ?: return true
        lock = runCatching { channel.tryLock() }.getOrNull()
        if (lock == null) {
            // The running copy may not be listening yet if both started at once.
            repeat(RAISE_TRIES) {
                val sent = runCatching {
                    SocketChannel.open(UnixDomainSocketAddress.of(socket)).use { it.write(ByteBuffer.wrap(RAISE)) }
                }.isSuccess
                if (sent) return false
                Thread.sleep(RAISE_WAIT_MS)
            }
            return false
        }
        runCatching {
            java.nio.file.Files.deleteIfExists(socket)
            val server = ServerSocketChannel.open(StandardProtocolFamily.UNIX).bind(UnixDomainSocketAddress.of(socket))
            val listener = Thread({
                while (true) {
                    val client = runCatching { server.accept() }.getOrNull() ?: break
                    client.use { onRaise?.invoke() }
                }
            }, "single instance")
            listener.isDaemon = true
            listener.start()
        }
        return true
    }

    private val RAISE = "raise\n".toByteArray()
    private const val RAISE_TRIES = 20
    private const val RAISE_WAIT_MS = 250L
}
