package com.rm.apogee.dedicated

import com.rm.apogee.core.part.StockParts
import com.rm.apogee.core.world.Protocol
import com.rm.apogee.core.world.World
import com.rm.apogee.core.world.WorldStore
import com.rm.apogee.net.LanDiscovery
import com.rm.apogee.net.ServerBeacon
import com.rm.apogee.net.TcpListener
import com.rm.apogee.server.GameServer
import com.rm.apogee.server.ServerConfig
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonPrimitive
import java.util.concurrent.CountDownLatch

/**
 * The standalone Apogee server.
 *
 * Runs exactly the same [GameServer] a phone hosts a game with - the only
 * differences are that this one has no client of its own, keeps its world on
 * disk, and exposes an admin control socket. There is one implementation of
 * the simulation, and this is not a privileged copy of it.
 */
fun main(): Unit = runBlocking {
    val settings = ServerSettings.fromEnvironment()
    val log = LogRing()
    val catalog = StockParts.catalog

    log.info("Apogee dedicated server starting")
    log.info("Protocol ${Protocol.VERSION}, parts ${catalog.size} (${catalog.contentHash})")
    settings.describe().lines().forEach { log.info(it) }

    val store = WorldStore(settings.worldFile)
    val world = World.default(catalog)

    // Load a world if there is one. A fresh directory is the normal first run,
    // not an error.
    val loaded = store.loadWithFallback()
    if (loaded == null) {
        log.info("No saved world at ${store.path}; starting a new one")
    } else {
        val (save, warning) = loaded
        warning?.let { log.warn(it) }
        val problems = world.restore(save)
        problems.forEach { log.warn(it) }
        log.info(
            "Loaded world: ${world.vessels.size} craft, " +
                "universe time ${"%.0f".format(world.time)}s"
        )
    }

    val server = GameServer(
        world = world,
        config = ServerConfig(
            name = settings.serverName,
            tickHz = settings.tickHz,
            snapshotHz = settings.snapshotHz,
            maxPlayers = settings.maxPlayers,
        ),
    )

    val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    val stopped = CountDownLatch(1)
    val startedAt = System.currentTimeMillis()

    val listener = TcpListener(settings.port) { transport ->
        log.info("Connection from ${transport.remoteAddress}")
        server.accept(transport, scope)
    }
    // A port already in use is the single most common way starting a server
    // fails, and a Java stack trace is a poor way to be told so.
    val bound = runCatching { listener.start(scope) }
    if (bound.isFailure) {
        val cause = bound.exceptionOrNull()
        log.error(
            "Could not listen on port ${settings.port}: " +
                (cause?.message ?: "unknown error")
        )
        if (cause is java.net.BindException) {
            log.error(
                "Something else is already using it. Stop that, or set " +
                    "APOGEE_PORT to a free port."
            )
        }
        scope.cancel()
        return@runBlocking
    }
    log.info("Listening on port ${listener.boundPort}")

    if (settings.lanDiscovery) {
        LanDiscovery.announce(
            ServerBeacon(
                serverName = settings.serverName,
                port = listener.boundPort,
                players = server.playerCount,
                protocolVersion = Protocol.VERSION,
                catalogHash = catalog.contentHash,
            ),
            scope,
        )
        log.info("Announcing on the local network")
    }

    server.start(scope)

    // --- autosave ----------------------------------------------------------
    //
    // A world nobody saved is a world nobody keeps. The interval is a
    // trade: too long and a crash costs real play, too short and a large
    // world spends its time serialising.
    fun saveWorld(reason: String): Result<Unit> =
        store.save(world.save())
            .onSuccess {
                log.info(
                    "Saved ${world.vessels.size} craft to ${store.path} " +
                        "(${store.sizeBytes / 1024}KB, $reason)"
                )
            }
            .onFailure { log.error("Save failed ($reason): ${it.message}") }

    if (settings.autosaveSeconds > 0) {
        scope.launch {
            while (isActive) {
                delay(settings.autosaveSeconds * 1_000L)
                saveWorld("autosave")
            }
        }
    }

    // --- admin control channel ---------------------------------------------
    val control = settings.controlSocket?.let { socketFile ->
        ControlServer(
            socketFile = socketFile,
            log = log,
            handler = object : ControlServer.ControlHandler {
                override fun status(): Map<String, JsonElement> = mapOf(
                    "running" to JsonPrimitive(true),
                    "serverName" to JsonPrimitive(settings.serverName),
                    "port" to JsonPrimitive(listener.boundPort),
                    "protocolVersion" to JsonPrimitive(Protocol.VERSION),
                    "catalogHash" to JsonPrimitive(catalog.contentHash),
                    "players" to JsonPrimitive(server.playerCount),
                    "vessels" to JsonPrimitive(world.vessels.size),
                    "tick" to JsonPrimitive(server.tick),
                    "universeTime" to JsonPrimitive(world.time),
                    "uptimeSeconds" to JsonPrimitive((System.currentTimeMillis() - startedAt) / 1000),
                    "lastSaveEpochMillis" to JsonPrimitive(store.lastSavedEpochMillis),
                    "worldPath" to JsonPrimitive(store.path),
                )

                override fun players(): List<Map<String, JsonElement>> =
                    server.playerNames.map { name ->
                        val vessel = world.vesselOwnedBy(name)
                        val attractor = vessel?.let { world.attractorFor(it) }
                        mapOf(
                            "name" to JsonPrimitive(name),
                            "vessel" to JsonPrimitive(vessel?.name ?: ""),
                            "altitude" to JsonPrimitive(
                                if (vessel != null && attractor != null) {
                                    attractor.altitudeOf(vessel.body.position)
                                } else 0.0
                            ),
                        )
                    }

                override fun logLines(count: Int): List<String> =
                    log.recent(count).map { it.toString() }

                override fun saveNow(): Map<String, JsonElement> {
                    val result = saveWorld("requested")
                    return mapOf(
                        "saved" to JsonPrimitive(result.isSuccess),
                        "path" to JsonPrimitive(store.path),
                        "bytes" to JsonPrimitive(store.sizeBytes),
                    )
                }

                override fun broadcast(text: String) {
                    log.info("[admin] $text")
                    scope.launch { server.broadcastChat("Server", text) }
                }

                override fun kick(playerName: String): Boolean {
                    val session = server.sessionNamed(playerName) ?: return false
                    log.info("Kicking $playerName")
                    server.disconnect(session)
                    return true
                }

                override fun requestStop() {
                    log.info("Stop requested over the control channel")
                    stopped.countDown()
                }
            },
        ).also { it.start(scope) }
    }

    // --- shutdown ------------------------------------------------------------
    //
    // A container stop is a SIGTERM, and a server that does not save on the way
    // out loses everything since the last autosave. This is the difference
    // between a restart being routine and being expensive.
    Runtime.getRuntime().addShutdownHook(
        Thread {
            log.info("Shutting down")
            saveWorld("shutdown")
            stopped.countDown()
        }
    )

    log.info("Ready")
    // Park the main thread until something asks to stop.
    runCatching { stopped.await() }

    control?.stop()
    listener.stop()
    saveWorld("stop")
    scope.cancel()
    log.info("Stopped")
}
