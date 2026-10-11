package com.rm.apogee.game

import kotlinx.coroutines.asCoroutineDispatcher

/** A pool of [threads] named [name], just below normal priority. */
actual fun workerPool(name: String, threads: Int): kotlinx.coroutines.CoroutineDispatcher =
    java.util.concurrent.Executors.newFixedThreadPool(threads) { r ->
        Thread(r, name).apply { isDaemon = true; priority = Thread.NORM_PRIORITY - 1 }
    }.asCoroutineDispatcher()

actual fun serverThread(): kotlinx.coroutines.CoroutineDispatcher = displayThread("game-server")

actual fun frameThread(): kotlinx.coroutines.CoroutineDispatcher = displayThread("frame-build")

/** A thread named [name], above the builders. */
private fun displayThread(name: String): kotlinx.coroutines.CoroutineDispatcher =
    java.util.concurrent.Executors.newSingleThreadExecutor { r ->
        Thread(r, name).apply { isDaemon = true; priority = Thread.NORM_PRIORITY + 1 }
    }.asCoroutineDispatcher()

actual class Worker actual constructor(name: String) {
    private val executor = java.util.concurrent.Executors.newSingleThreadExecutor { r ->
        Thread(r, name).apply { isDaemon = true; priority = Thread.NORM_PRIORITY - 1 }
    }

    actual fun execute(task: () -> Unit) = executor.execute(task)
}

actual fun openToNetwork(
    server: com.rm.apogee.server.GameServer,
    scope: kotlinx.coroutines.CoroutineScope,
    serverName: String,
    catalogHash: String,
    locked: Boolean,
): NetworkHost? {
    val tcp = com.rm.apogee.net.TcpListener(GameSession.DEFAULT_PORT) { transport -> server.accept(transport, scope) }
    tcp.start(scope)
    val beacon = com.rm.apogee.net.LanDiscovery.announce(scope) {
        com.rm.apogee.net.ServerBeacon(
            serverName = serverName,
            port = tcp.boundPort,
            players = server.playerCount,
            protocolVersion = com.rm.apogee.core.world.Protocol.VERSION,
            catalogHash = catalogHash,
            locked = locked,
        )
    }
    return NetworkHost(tcp.boundPort) { beacon.cancel(); tcp.stop() }
}

actual suspend fun connectTo(host: String, port: Int): Result<com.rm.apogee.net.Transport> =
    com.rm.apogee.net.TcpTransport.connect(host, port)
