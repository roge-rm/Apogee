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
 * Where a send runs. The client sends from the UI's scope, which on Android is the main thread,
 * where a blocking socket write throws NetworkOnMainThreadException. So send must get itself off
 * the calling thread. [LoopbackTransport] has no socket, so single player never shows this.
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
                // Compare the thread itself: kotlinx adds " @coroutine#n" to names while
                // debugging, so a name comparison never matches.
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

        // An empty stream reads as an immediate disconnect, which is all this needs.
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
