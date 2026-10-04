package com.rm.apogee.game

import com.rm.apogee.net.Transport
import com.rm.apogee.server.GameServer
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers

actual fun workerPool(name: String, threads: Int): CoroutineDispatcher = Dispatchers.Default

actual fun serverThread(): CoroutineDispatcher = Dispatchers.Default

actual fun frameThread(): CoroutineDispatcher = Dispatchers.Default

actual class Worker actual constructor(name: String) {
    private val background = com.rm.apogee.core.Background(name)
    actual fun execute(task: () -> Unit) = background.execute(task)
}

actual fun openToNetwork(server: GameServer, scope: CoroutineScope, serverName: String, catalogHash: String): NetworkHost? = null

actual suspend fun connectTo(host: String, port: Int): Result<Transport> =
    Result.failure(UnsupportedOperationException("Joining games isn't possible from a browser"))
