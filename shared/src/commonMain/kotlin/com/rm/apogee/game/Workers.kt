package com.rm.apogee.game

import com.rm.apogee.server.GameServer
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import com.rm.apogee.net.Transport

/**
 * A pool of [threads] named [name] for background building, just below normal priority. In a
 * browser there's one thread, so it's the page's own event loop.
 */
expect fun workerPool(name: String, threads: Int): CoroutineDispatcher

/**
 * The thread a hosted game's server ticks on: one of its own, ahead of the drawing's helpers, so a
 * tick isn't kept waiting behind a sea or a patch of ground being built.
 */
expect fun serverThread(): CoroutineDispatcher

/** One background thread named [name], for jobs run one after another. */
expect class Worker(name: String) {
    fun execute(task: () -> Unit)
}

/** A game opened to the network: the port it's reached on, and how to close it again. */
class NetworkHost(val port: Int, val stop: () -> Unit)

/**
 * Opens [server] to the network and announces it as [serverName], or null where there's no network
 * to open it to (a browser).
 */
expect fun openToNetwork(server: GameServer, scope: CoroutineScope, serverName: String, catalogHash: String): NetworkHost?

/** A connection to a game hosted at [host]:[port], or a failure (always, in a browser). */
expect suspend fun connectTo(host: String, port: Int): Result<Transport>
