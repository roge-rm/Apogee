package com.rm.apogee.server

import com.rm.apogee.core.craft.CraftDesign
import com.rm.apogee.core.craft.StockCraft
import com.rm.apogee.core.craft.VesselId
import com.rm.apogee.core.part.PartCatalog
import com.rm.apogee.core.part.StockParts
import com.rm.apogee.core.world.ClientMessage
import com.rm.apogee.core.world.Command
import com.rm.apogee.core.world.Protocol
import com.rm.apogee.core.world.ServerMessage
import com.rm.apogee.core.world.World
import com.rm.apogee.core.world.WorldEvent
import com.rm.apogee.net.Channel
import com.rm.apogee.net.Codec
import com.rm.apogee.net.Packet
import com.rm.apogee.net.Transport
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.CopyOnWriteArrayList

class ServerConfig(
    val name: String = "Apogee Server",
    /** Simulation rate. Must match the client's prediction rate. */
    val tickHz: Int = 60,
    /**
     * How often vessel motion goes out.
     *
     * Deliberately lower than the tick rate, and deliberately the same over
     * loopback as over a socket. Running loopback at full tick rate would hide
     * every interpolation and prediction problem until the first time two real
     * devices tried to play.
     */
    val snapshotHz: Int = 20,
    /** Craft handed to a player who joins with nothing. */
    val starterCraft: (PartCatalog) -> CraftDesign = { StockCraft.starterRocket(it) },
)

/**
 * One connected player.
 *
 * Holds only what the server needs to talk to them and decide what they may
 * do. Note [controlledVessel]: authority is per-vessel, so a command naming a
 * vessel the sender does not own is discarded rather than obeyed. Without that
 * check any client could fly every craft in a persistent world.
 */
class PlayerSession internal constructor(
    val id: Int,
    val transport: Transport,
) {
    @Volatile var playerName: String = "Pilot"
        internal set

    @Volatile var handshakeComplete: Boolean = false
        internal set

    @Volatile var controlledVessel: VesselId? = null
        internal set

    @Volatile var connected: Boolean = true
        internal set

    suspend fun send(message: ServerMessage, channel: Channel) {
        if (!connected) return
        transport.send(Packet(channel, Codec.encode(message)))
    }
}

/**
 * The authoritative simulation.
 *
 * The same class backs a phone hosting a game for friends and a headless
 * dedicated server; the only difference is which [Transport] the sessions
 * arrive on. Commands are queued as they arrive and applied at tick boundaries,
 * never mid-step, so the result never depends on where in the vessel iteration
 * a packet happened to land.
 */
class GameServer(
    val world: World,
    val config: ServerConfig = ServerConfig(),
) {
    private val sessions = CopyOnWriteArrayList<PlayerSession>()
    private val inbox = ConcurrentLinkedQueue<Pair<PlayerSession, ClientMessage>>()
    private var nextSessionId = 1

    val dt: Double = 1.0 / config.tickHz
    private val ticksPerSnapshot: Int = (config.tickHz / config.snapshotHz).coerceAtLeast(1)

    val playerCount: Int get() = sessions.count { it.connected && it.handshakeComplete }

    /**
     * Registers a transport and starts pumping its packets into the inbox.
     *
     * Reading happens on the caller's scope rather than on the simulation loop,
     * so a slow or hostile client can never stall the world.
     */
    fun accept(transport: Transport, scope: CoroutineScope): PlayerSession {
        val session = PlayerSession(nextSessionId++, transport)
        sessions.add(session)

        scope.launch(Dispatchers.Default) {
            try {
                transport.incoming.collect { packet ->
                    val message = runCatching { Codec.decodeClientMessage(packet.bytes) }.getOrNull()
                    // A packet that will not decode is a protocol error, not a
                    // reason to take the server down.
                    if (message != null) inbox.add(session to message)
                }
            } finally {
                session.connected = false
            }
        }
        return session
    }

    fun disconnect(session: PlayerSession) {
        session.connected = false
        session.transport.close()
        sessions.remove(session)
    }

    /** Runs the simulation until the scope is cancelled. */
    fun start(scope: CoroutineScope): Job = scope.launch(Dispatchers.Default) {
        var accumulator = 0.0
        var lastNanos = System.nanoTime()
        var tickInFrame = 0L

        while (isActive) {
            val now = System.nanoTime()
            var elapsed = (now - lastNanos) / 1e9
            lastNanos = now
            if (elapsed > MAX_CATCHUP_SECONDS) elapsed = MAX_CATCHUP_SECONDS
            accumulator += elapsed

            while (accumulator >= dt && isActive) {
                drainInbox()
                world.step(dt)
                accumulator -= dt
                tickInFrame++

                publishEvents()
                if (tickInFrame % ticksPerSnapshot == 0L) broadcastSnapshot()
            }

            val sleepMillis = ((dt - accumulator) * 1000.0).toLong()
            if (sleepMillis > 0) delay(sleepMillis)
        }
    }

    /** Steps once without any wall-clock pacing. For tests. */
    suspend fun stepOnce() {
        drainInbox()
        world.step(dt)
        publishEvents()
        broadcastSnapshot()
    }

    private suspend fun drainInbox() {
        while (true) {
            val (session, message) = inbox.poll() ?: return
            handle(session, message)
        }
    }

    private suspend fun handle(session: PlayerSession, message: ClientMessage) {
        when (message) {
            is ClientMessage.Hello -> completeHandshake(session, message)

            is ClientMessage.CommandMessage -> {
                if (!session.handshakeComplete) return
                val command = message.command
                if (!isAuthorised(session, command)) return
                if (command is Command.Chat) {
                    broadcast(
                        ServerMessage.ChatMessage(session.playerName, command.text),
                        Channel.CONTROL,
                    )
                } else {
                    world.apply(command)
                }
            }
        }
    }

    /**
     * Refuses a mismatched client before it can do any damage.
     *
     * Both checks matter, and they fail differently. A protocol mismatch means
     * the messages themselves will be misread; a catalogue mismatch means they
     * will be read perfectly and mean something else - the client's "tank-cask2"
     * weighs something the server disagrees with, and the two simulations drift
     * apart with no error anywhere. The second is far harder to diagnose from
     * the symptoms, which is exactly why it is checked here.
     */
    private suspend fun completeHandshake(session: PlayerSession, hello: ClientMessage.Hello) {
        if (hello.protocolVersion != Protocol.VERSION) {
            session.send(
                ServerMessage.Rejected(
                    "Protocol mismatch: server speaks ${Protocol.VERSION}, " +
                        "client speaks ${hello.protocolVersion}"
                ),
                Channel.CONTROL,
            )
            disconnect(session)
            return
        }
        if (hello.catalogHash != world.catalog.contentHash) {
            session.send(
                ServerMessage.Rejected(
                    "Part catalogue mismatch: server has ${world.catalog.contentHash}, " +
                        "client has ${hello.catalogHash}"
                ),
                Channel.CONTROL,
            )
            disconnect(session)
            return
        }

        session.playerName = hello.playerName.take(32).ifBlank { "Pilot" }
        session.handshakeComplete = true

        val vessel = world.spawnOnSurface(
            config.starterCraft(world.catalog),
            World.launchSites.first(),
        )
        session.controlledVessel = vessel.id

        session.send(
            ServerMessage.Welcome(
                protocolVersion = Protocol.VERSION,
                catalogHash = world.catalog.contentHash,
                serverName = config.name,
                controlledVessel = vessel.id.raw,
            ),
            Channel.CONTROL,
        )

        // A joining client needs the structure of everything already out there,
        // not just its own craft, or every other player is invisible until
        // something about them happens to change.
        for (existing in world.vessels) {
            session.send(
                ServerMessage.StructureMessage(world.structureUpdateFor(existing)),
                Channel.STRUCTURE,
            )
        }
        session.send(ServerMessage.SnapshotMessage(world.snapshot()), Channel.KINEMATICS)
    }

    private fun isAuthorised(session: PlayerSession, command: Command): Boolean = when (command) {
        is Command.SetThrottle -> session.controlledVessel?.raw == command.vessel
        is Command.SetAttitude -> session.controlledVessel?.raw == command.vessel
        is Command.SetSas -> session.controlledVessel?.raw == command.vessel
        is Command.Stage -> session.controlledVessel?.raw == command.vessel
        is Command.SpawnCraft -> true
        is Command.Chat -> true
    }

    private suspend fun publishEvents() {
        for (event in world.drainEvents()) {
            when (event) {
                is WorldEvent.VesselSpawned ->
                    world.vessel(event.id)?.let {
                        broadcast(
                            ServerMessage.StructureMessage(world.structureUpdateFor(it)),
                            Channel.STRUCTURE,
                        )
                    }

                is WorldEvent.VesselStructureChanged ->
                    world.vessel(event.id)?.let {
                        broadcast(
                            ServerMessage.StructureMessage(world.structureUpdateFor(it)),
                            Channel.STRUCTURE,
                        )
                    }

                is WorldEvent.VesselDestroyed ->
                    broadcast(
                        ServerMessage.StructureMessage(
                            com.rm.apogee.core.world.StructureUpdate(event.id.raw, design = null)
                        ),
                        Channel.STRUCTURE,
                    )

                // Staging changes which parts are live and which stage is
                // next, and both live in the structure message. Without this
                // the client's stage counter and engine-lit state go stale the
                // moment anything is staged that does not also separate.
                is WorldEvent.Staged ->
                    world.vessel(event.id)?.let {
                        broadcast(
                            ServerMessage.StructureMessage(world.structureUpdateFor(it)),
                            Channel.STRUCTURE,
                        )
                    }

                is WorldEvent.Touchdown -> Unit
            }
        }
    }

    private suspend fun broadcastSnapshot() {
        if (sessions.isEmpty()) return
        val snapshot = world.snapshot()
        broadcast(ServerMessage.SnapshotMessage(snapshot), Channel.KINEMATICS)
    }

    private suspend fun broadcast(message: ServerMessage, channel: Channel) {
        for (session in sessions) {
            if (session.connected && session.handshakeComplete) session.send(message, channel)
        }
    }

    companion object {
        private const val MAX_CATCHUP_SECONDS = 0.25

        fun default(catalog: PartCatalog = StockParts.catalog, config: ServerConfig = ServerConfig()) =
            GameServer(World.default(catalog), config)
    }
}
