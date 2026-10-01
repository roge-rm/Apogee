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
 * The standalone Apogee server. It runs the same [GameServer] a phone hosts with, but has no client
 * of its own, keeps its world on disk and has an admin control socket.
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

    // Load a world if there is one. A fresh directory is a normal first run.
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
    // A port in use is the usual failure, so say so plainly instead of a stack trace.
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
    // The world is taken between ticks, never during one, since mid-tick the craft could be half
    // moved. Once ticking has stopped it's taken straight away.
    fun worldNow(): com.rm.apogee.core.world.WorldSave {
        val taken = java.util.concurrent.atomic.AtomicReference<com.rm.apogee.core.world.WorldSave?>(null)
        val done = CountDownLatch(1)
        server.runBetweenTicks { taken.set(runCatching { world.save() }.getOrNull()); done.countDown() }
        if (!done.await(SAVE_WAIT_SECONDS, java.util.concurrent.TimeUnit.SECONDS)) return world.save()
        return taken.get() ?: world.save()
    }

    fun saveWorld(reason: String): Result<Unit> =
        store.save(worldNow())
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
    // A container stop is a SIGTERM; save on the way out so nothing since the last autosave is lost.
    Runtime.getRuntime().addShutdownHook(
        Thread {
            log.info("Shutting down")
            saveWorld("shutdown")
            stopped.countDown()
        }
    )

    log.info("Ready")
    // Park the main thread until something asks it to stop.
    runCatching { stopped.await() }

    control?.stop()
    listener.stop()
    saveWorld("stop")
    scope.cancel()
    log.info("Stopped")
}

/** How long a save waits for a gap between ticks, in seconds, before taking the world as it is. */
private const val SAVE_WAIT_SECONDS = 2L
