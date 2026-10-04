package com.rm.apogee.game

import kotlinx.coroutines.asCoroutineDispatcher

/**
 * A pool of [threads] named [name], just below normal priority.
 *
 * Not [Thread.NORM_PRIORITY] - 1: Android runs that as a background thread with a tenth of the CPU
 * share, and the sea and sky starved behind busy terrain workers. Just below normal still gives
 * way to the game server and the frame.
 */
actual fun workerPool(name: String, threads: Int): kotlinx.coroutines.CoroutineDispatcher =
    java.util.concurrent.Executors.newFixedThreadPool(threads) { r ->
        Thread({
            // Stubbed out off the device, in unit tests.
            runCatching { android.os.Process.setThreadPriority(android.os.Process.THREAD_PRIORITY_LESS_FAVORABLE) }
            r.run()
        }, name).apply { isDaemon = true }
    }.asCoroutineDispatcher()

actual fun serverThread(): kotlinx.coroutines.CoroutineDispatcher = displayThread("game-server")

actual fun frameThread(): kotlinx.coroutines.CoroutineDispatcher = displayThread("frame-build")

/**
 * A thread named [name] up with the display's own, and in the performance hint session (see
 * [com.rm.apogee.platform.HintedThreads]).
 */
private fun displayThread(name: String): kotlinx.coroutines.CoroutineDispatcher =
    java.util.concurrent.Executors.newSingleThreadExecutor { r ->
        Thread({
            // Stubbed out off the device, in unit tests.
            runCatching {
                android.os.Process.setThreadPriority(android.os.Process.THREAD_PRIORITY_DISPLAY)
                com.rm.apogee.platform.HintedThreads.set(name, android.os.Process.myTid())
            }
            r.run()
        }, name).apply { isDaemon = true }
    }.asCoroutineDispatcher()

actual class Worker actual constructor(name: String) {
    private val executor = java.util.concurrent.Executors.newSingleThreadExecutor { r ->
        Thread({
            runCatching { android.os.Process.setThreadPriority(android.os.Process.THREAD_PRIORITY_LESS_FAVORABLE) }
            r.run()
        }, name).apply { isDaemon = true }
    }

    actual fun execute(task: () -> Unit) = executor.execute(task)
}

actual fun openToNetwork(
    server: com.rm.apogee.server.GameServer,
    scope: kotlinx.coroutines.CoroutineScope,
    serverName: String,
    catalogHash: String,
): NetworkHost? {
    val tcp = com.rm.apogee.net.TcpListener(GameSession.DEFAULT_PORT) { transport -> server.accept(transport, scope) }
    tcp.start(scope)
    val beacon = com.rm.apogee.net.LanDiscovery.announce(
        com.rm.apogee.net.ServerBeacon(
            serverName = serverName,
            port = tcp.boundPort,
            players = server.playerCount,
            protocolVersion = com.rm.apogee.core.world.Protocol.VERSION,
            catalogHash = catalogHash,
        ),
        scope,
    )
    return NetworkHost(tcp.boundPort) { beacon.cancel(); tcp.stop() }
}

actual suspend fun connectTo(host: String, port: Int): Result<com.rm.apogee.net.Transport> =
    com.rm.apogee.net.TcpTransport.connect(host, port)
