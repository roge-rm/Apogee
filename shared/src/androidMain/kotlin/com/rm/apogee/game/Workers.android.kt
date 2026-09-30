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
actual fun workerPool(name: String, threads: Int): kotlinx.coroutines.CoroutineDispatcher =
    java.util.concurrent.Executors.newFixedThreadPool(threads) { r ->
        Thread({
            // Stubbed out off the device, in unit tests.
            runCatching { android.os.Process.setThreadPriority(android.os.Process.THREAD_PRIORITY_LESS_FAVORABLE) }
            r.run()
        }, name).apply { isDaemon = true }
    }.asCoroutineDispatcher()

actual fun serverThread(): kotlinx.coroutines.CoroutineDispatcher =
    java.util.concurrent.Executors.newSingleThreadExecutor { r ->
        Thread({
            // Up with the display's own threads. Stubbed out off the device, in unit tests.
            runCatching { android.os.Process.setThreadPriority(android.os.Process.THREAD_PRIORITY_DISPLAY) }
            r.run()
        }, "game-server").apply { isDaemon = true }
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
