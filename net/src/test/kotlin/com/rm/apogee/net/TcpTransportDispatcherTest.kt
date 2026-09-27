package com.rm.apogee.net

import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNotSame
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.InputStream
import java.io.OutputStream
import java.net.Socket
import java.util.concurrent.Executors

/**
 * Where a send actually runs.
 *
 * Writing to a socket blocks, and the client sends control commands from the UI's own coroutine
 * scope, which on Android is the main thread, where a blocking write is a fatal
 * NetworkOnMainThreadException. Single player never hits this, because its transport is
 * [LoopbackTransport], an in-memory queue with no socket to block on. So nothing caught it until a
 * real device joined a real server and touched the throttle.
 *
 * So the contract belongs to the transport, not the caller: send has to get itself off whatever
 * thread it was called on.
 */
class TcpTransportDispatcherTest {

    @Test
    fun `send doesn't write on the calling thread`() {
        val recorder = RecordingSocket()
        val transport = TcpTransport.wrap(recorder)

        val caller = Executors.newSingleThreadExecutor { runnable ->
            Thread(runnable, "test-caller")
        }
        val callingThread: Thread
        try {
            callingThread = runBlocking(caller.asCoroutineDispatcher()) {
                // The thread itself, not its name. kotlinx decorates thread names with "
                // @coroutine#n" while debugging is on, so a name comparison quietly never matches
                // and the test passes whether or not the dispatcher is right. This one failed that
                // way the first time.
                val here = Thread.currentThread()
                transport.send(Packet(Channel.CONTROL, byteArrayOf(1, 2, 3)))
                here
            }
        } finally {
            caller.shutdown()
        }

        val wrote = recorder.stream.writingThread
        assertNotNull("nothing was written", wrote)
        assertNotSame(
            "the socket write ran on the calling thread",
            callingThread,
            wrote,
        )
    }

    private class RecordingSocket : Socket() {
        val stream = RecordingStream()
        override fun getOutputStream(): OutputStream = stream

        // The transport starts a reader over this. An empty stream reads as an immediate
        // disconnect, which is all this test needs from it.
        override fun getInputStream(): InputStream = ByteArrayInputStream(ByteArray(0))
        override fun close() = Unit
    }

    private class RecordingStream : OutputStream() {
        @Volatile
        var writingThread: Thread? = null

        override fun write(b: Int) {
            writingThread = Thread.currentThread()
        }

        override fun write(b: ByteArray, off: Int, len: Int) {
            writingThread = Thread.currentThread()
        }
    }
}
