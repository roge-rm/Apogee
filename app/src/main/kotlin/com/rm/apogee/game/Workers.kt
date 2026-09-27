package com.rm.apogee.game

import kotlinx.coroutines.asCoroutineDispatcher

/**
 * A pool of [threads] named [name], just below normal priority.
 *
 * Not [Thread.NORM_PRIORITY] - 1, because Android runs that as a background thread with a tenth of
 * a normal one's share of the CPU. While the terrain workers kept every core busy (flying low and
 * fast), the sea took twenty seconds to build and the sky nearly two to list. Just below normal
 * still gives way to the game server and the frame, without starving.
 */
fun workerPool(name: String, threads: Int) =
    java.util.concurrent.Executors.newFixedThreadPool(threads) { r ->
        Thread({
            // Stubbed out off the device, in unit tests.
            runCatching { android.os.Process.setThreadPriority(android.os.Process.THREAD_PRIORITY_LESS_FAVORABLE) }
            r.run()
        }, name).apply { isDaemon = true }
    }.asCoroutineDispatcher()
