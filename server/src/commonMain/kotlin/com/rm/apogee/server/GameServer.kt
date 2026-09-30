package com.rm.apogee.server

import com.rm.apogee.core.craft.CraftDesign
import com.rm.apogee.core.craft.StockCraft
import com.rm.apogee.core.world.WorldSave
import com.rm.apogee.core.craft.VesselId
import com.rm.apogee.core.part.PartCatalog
import com.rm.apogee.core.part.StockParts
import com.rm.apogee.core.terrain.TerrainField
import com.rm.apogee.core.world.ClientMessage
import com.rm.apogee.core.world.Command
import com.rm.apogee.core.world.PartEventKind
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
import com.rm.apogee.core.ConcurrentQueue
import com.rm.apogee.core.putIfAbsentShared
import kotlin.concurrent.Volatile

class ServerConfig(
    val name: String = "Apogee Server",
    /** The simulation rate. It has to match the client's prediction rate. */
    val tickHz: Int = 60,
    /**
     * How often vessel motion goes out.
     *
     * It's lower than the tick rate on purpose, and the same over loopback as over a socket on
     * purpose. Running loopback at the full tick rate would hide every interpolation and prediction
     * problem until the first time two real devices tried to play.
     */
    val snapshotHz: Int = 20,
    /** The craft handed to a player who joins with nothing. */
    val starterCraft: (PartCatalog) -> CraftDesign = { StockCraft.starterRocket(it) },
    /**
     * Whether a player who owns nothing gets [starterCraft] when they join.
     *
     * It's false when the client is about to launch a craft of its own. Handing it a stock rocket
     * first would leave one standing on the pad in a persistent world every time somebody launched
     * something they'd built, which is debris made just by starting properly.
     */
    val assignCraftOnJoin: Boolean = true,
    /**
     * Start every joining player on a fresh [starterCraft], clearing away the craft they flew last
     * time instead of handing it back. This is Free Flight in single player: a new flight, not a
     * continuation, while the bases a player left elsewhere stay where they are.
     */
    val freshFlight: Boolean = false,
    /**
     * Put a joining player straight into this craft of theirs. That's Resume Flight, with the craft
     * chosen from a list. It's ignored if the craft is gone or isn't theirs.
     */
    val resumeVessel: Long? = null,
    /** How many players at once, or 0 for no limit. */
    val maxPlayers: Int = 0,
    /**
     * How lively the weather is, overriding what the world was saved with. Null keeps the world's
     * own. The world's seed stays, so it's the same world, just calmer or wilder.
     */
    val weatherIntensity: com.rm.apogee.core.weather.WeatherIntensity? = null,
    /** How cloudy it is, also overriding the world's own. Null keeps it. */
    val cloudCover: com.rm.apogee.core.weather.CloudCover? = null,
    /**
     * Whether a player can pause the world or run it faster. That's only the phone's own game,
     * never a dedicated server, and even then only while that player is the only one on it.
     */
    val allowWarp: Boolean = false,
)

/**
 * One connected player.
 *
 * It holds only what the server needs to talk to them and decide what they're allowed to do. Note
 * [controlledVessel]: authority is per vessel, so a command naming a vessel the sender doesn't own
 * gets thrown away instead of obeyed. Without that check any client could fly every craft in a
 * persistent world.
 */
class PlayerSession internal constructor(
    val id: Int,
    val transport: Transport,
) {
    @Volatile var playerName: String = "Pilot"
        internal set

    /**
     * Who this player is, as far as the world is concerned.
     *
     * It's opaque, made by the client once per install, and never typed. Craft ownership hangs off
     * this instead of off [playerName], which is a label two people can share without noticing.
     * They did, the first time two clients joined the same server as the default "Pilot" and found
     * themselves flying one rocket.
     */
    @Volatile var clientId: String = ""
        internal set

    @Volatile var handshakeComplete: Boolean = false
        internal set

    @Volatile var controlledVessel: VesselId? = null

    /** The world's crew revision this player's roster was last sent at, or -1 for never. */
    @Volatile var rosterRevision: Long = -1L
        internal set

    /** The career revision this player's career was last sent at, or -1 for never. */
    @Volatile var careerRevision: Long = -1L

    /**
     * The world's finds as this player was last told them. See
     * [com.rm.apogee.core.world.World.wondersRevision].
     */
    @Volatile var wondersRevision: Int = -1
        internal set

    @Volatile var connected: Boolean = true
        internal set

    suspend fun send(message: ServerMessage, channel: Channel) {
        if (!connected) return
        transport.send(Packet(channel, Codec.encode(message)))
    }
}

/**
 * The simulation that's in charge.
 *
 * The same class runs a phone hosting a game for friends and a headless dedicated server. The only
 * difference is which [Transport] the sessions arrive on. Commands get queued as they arrive and
 * applied between ticks, never mid-step, so the result never depends on where in the vessel loop a
 * packet happened to land.
 */
class GameServer(
    val world: World,
    val config: ServerConfig = ServerConfig(),
) {
    private val sessions = com.rm.apogee.core.copyOnWriteListOf<PlayerSession>()

    /**
     * Feats earned by players who weren't connected at the time, by owner, to tell them when they
     * are.
     */
    private val unsentFeats = com.rm.apogee.core.concurrentMapOf<String, ArrayDeque<ServerMessage.Feat>>()

    init {
        // Every game has weather. It's set here, before anyone joins, so the welcome can tell each
        // client what to work out.
        val base = world.weatherConfig ?: com.rm.apogee.core.weather.WeatherConfig()
        world.weatherConfig = base.copy(
            intensity = config.weatherIntensity ?: base.intensity,
            clouds = config.cloudCover ?: base.clouds,
        )
        // The Cape's buildings, in a new world or one saved before they existed.
        world.ensureStructures()
        world.repairStructures(now = true)
    }

    /** Names of everyone connected right now, for the admin view. */
    val playerNames: List<String>
        get() = sessions.filter { it.connected && it.handshakeComplete }.map { it.playerName }

    /** Ticks done since this process started. */
    val tick: Long get() = world.tick
    private val inbox = ConcurrentQueue<Pair<PlayerSession, ClientMessage>>()
    private var nextSessionId = 1

    val dt: Double = 1.0 / config.tickHz
    private val ticksPerSnapshot: Int = (config.tickHz / config.snapshotHz).coerceAtLeast(1)

    /** Fuel goes to each pilot every quarter of a second. It's a gauge, not motion. */
    private val ticksPerFuel: Int = (config.tickHz / FUEL_HZ).coerceAtLeast(1)

    val playerCount: Int get() = sessions.count { it.connected && it.handshakeComplete }

    /**
     * Registers a transport and starts pumping its packets into the inbox.
     *
     * Reading happens on the caller's scope instead of on the simulation loop, so a slow or hostile
     * client can never stall the world.
     */
    fun accept(transport: Transport, scope: CoroutineScope): PlayerSession {
        val session = PlayerSession(nextSessionId++, transport)
        sessions.add(session)

        scope.launch(Dispatchers.Default) {
            try {
                transport.incoming.collect { packet ->
                    val message = runCatching { Codec.decodeClientMessage(packet.bytes) }.getOrNull()
                    // A packet that won't decode is a protocol error, not a reason to take the
                    // server down.
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

    /**
     * Runs the simulation until the scope is cancelled.
     *
     * It's paced against a fixed schedule, with each tick due at a fixed offset from the last,
     * instead of adding up elapsed time and sleeping for what's left. There are two reasons.
     *
     * Adding up drifts. Every loop rounds its sleep down to whole milliseconds, and the lost
     * fractions never get paid back. Worse, when what was left came to less than a millisecond the
     * sleep truncated to zero and the loop spun flat out until the next tick was due. That cost
     * about 6% of a core on a completely empty world, and the same loop runs on the phone when it
     * hosts, where it's battery.
     *
     * With a fixed schedule there's exactly one wake-up per tick, no drift, and an idle server
     * costs almost nothing.
     *
     * [dispatcher] is where the ticks run. A phone hosting its own game gives it a thread of its
     * own, ahead of the ones building the sea and the ground: on the shared pool it waited its turn
     * behind them, a tick starting tens of milliseconds late.
     */
    fun start(scope: CoroutineScope, dispatcher: kotlinx.coroutines.CoroutineDispatcher = Dispatchers.Default): Job = scope.launch(dispatcher) {
        val tickNanos = (dt * 1e9).toLong()
        var nextTickAt = com.rm.apogee.core.nanoTime()
        var tickCount = 0L

        while (isActive) {
            val now = com.rm.apogee.core.nanoTime()
            val remainingMillis = (nextTickAt - now) / 1_000_000

            if (remainingMillis >= 1) {
                delay(remainingMillis)
                continue
            }
            // Under a millisecond to go, so take the tick now instead of spinning for it. At 60Hz
            // that's well under a frame of jitter, and the fixed schedule means it doesn't add up.

            drainInbox()
            runQueuedTasks()
            advanceWorld()
            tickCount++

            publishEvents()
            if (tickCount % ticksPerSnapshot == 0L) broadcastSnapshot()
            if (tickCount % ticksPerFuel == 0L) sendFuel()

            nextTickAt += tickNanos

            // Far enough behind that catching up would mean a burst of ticks each taking longer
            // than real time: a stalled thread, a paused container, or a laptop lid. Give up the
            // backlog and get back in step.
            if (com.rm.apogee.core.nanoTime() - nextTickAt > MAX_CATCHUP_NANOS) {
                nextTickAt = com.rm.apogee.core.nanoTime() + tickNanos
            }
            // Behind, it goes straight on to the next tick, and in a browser, where the server
            // shares the page's one thread, that never let the page draw or hear a touch again.
            // Giving way once a tick costs a thread of its own nothing.
            kotlinx.coroutines.yield()
        }
    }

    /**
     * One tick of the world at the rate asked for, as far as the world allows: nothing while
     * paused, extra steps up to physics warp, and past that the craft on rails. It falls back to
     * physics warp for whatever part of the tick the rails won't take.
     */
    private fun advanceWorld() {
        // It got to where a warp was asked to stop, so back to real time.
        if (!world.warpUntil.isNaN() && world.time >= world.warpUntil - 1e-6) {
            world.warpUntil = Double.NaN
            requestedWarp = 1.0
        }
        world.hurried = warpAllowed && requestedWarp > World.PHYSICS_WARP
        val rate = effectiveWarp()
        when {
            rate <= 0.0 -> Unit
            rate <= World.PHYSICS_WARP -> repeat(rate.toInt().coerceAtLeast(1)) { world.step(dt) }
            else -> {
                val done = world.advanceOnRails(dt * rate)
                if (done < dt * rate) repeat(World.PHYSICS_WARP.toInt()) { world.step(dt) }
            }
        }
    }

    /** What the player asked for: 1 is real time, and 0 is paused. */
    @Volatile
    var requestedWarp: Double = 1.0
        private set

    /** Warp is only for a server that allows it, with one player on it. */
    val warpAllowed: Boolean get() = config.allowWarp && playerCount <= 1

    /** The rate the world actually runs at: what was asked for, as far as the world allows. */
    fun effectiveWarp(): Double {
        if (!warpAllowed) return 1.0
        if (requestedWarp <= 0.0) return 0.0
        return minOf(requestedWarp, world.maxWarp())
    }

    /** Steps once without any wall-clock pacing. For tests. */
    suspend fun stepOnce() {
        drainInbox()
        runQueuedTasks()
        advanceWorld()
        publishEvents()
        broadcastSnapshot()
        sendFuel()
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
                } else if (command is Command.SetWarp) {
                    requestedWarp = command.rate.coerceIn(0.0, World.WARP_RATES.last())
                    world.warpUntil = Double.NaN
                } else if (command is Command.WarpTo) {
                    if (command.time > world.time) {
                        world.warpUntil = command.time
                        requestedWarp = World.WARP_RATES.last()
                    }
                } else if (command is Command.RemoveVessel) {
                    val flying = session.controlledVessel?.raw == command.vessel
                    world.apply(command)
                    if (flying) {
                        session.controlledVessel = null
                        world.lastFlown.remove(session.clientId)
                        session.send(ServerMessage.ControlChanged(-1L), Channel.CONTROL)
                    }
                } else if (command is Command.SetDockPilot) {
                    dockPilots[command.vessel] = command.pilot
                    announceDock(command.vessel)
                } else if (command is Command.Eva) {
                    // Out into a suit of their own, which they fly now.
                    world.eva(command.vessel, command.crew)?.let { suit ->
                        suit.ownerName = session.playerName
                        takeControl(session, suit.id)
                    }
                } else if (command is Command.Board) {
                    world.boardCraft(command.vessel, command.target)?.let { takeControl(session, it.id) }
                } else if (command is Command.SwitchVessel) {
                    world.apply(command)
                    takeControl(session, VesselId(command.vessel))
                } else if (command is Command.Unlock) {
                    world.unlock(session.clientId, command.node)?.let { session.send(ServerMessage.CareerRefused(it), Channel.CONTROL) }
                } else if (command is Command.SpawnCraft) {
                    // In a career, only what the player has unlocked, and no more than the pad they
                    // launch from can take.
                    val refused = world.program?.refusal(session.clientId, command.design, command.siteId, world.catalog)
                    if (refused != null) {
                        session.send(ServerMessage.CareerRefused(refused), Channel.CONTROL)
                    } else {
                        // Launching is how a player gets a *new* craft in a world they already have
                        // one in. Without this the spawn would land on the pad and they'd still be
                        // flying whatever they arrived in, which made building a base out of
                        // several launches impossible.
                        val vessel = world.spawnFor(command, session.clientId)
                        vessel.ownerName = session.playerName
                        takeControl(session, vessel.id)
                    }
                } else {
                    if (command is Command.Refuel) refuelStops.remove(command.vessel)
                    world.apply(command)
                }
            }
        }
    }

    /**
     * Refuses a mismatched client before it can do any damage.
     *
     * Both checks matter, and they fail in different ways. A protocol mismatch means the messages
     * themselves get misread. A catalogue mismatch means they get read perfectly and mean something
     * else. The client's "tank-cask2" weighs something the server disagrees with, and the two
     * simulations drift apart with no error anywhere. The second is far harder to work out from the
     * symptoms, which is exactly why it gets checked here.
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
        if (config.maxPlayers > 0 && playerCount >= config.maxPlayers) {
            // Refused with a reason instead of dropped. A player who can't tell "server full" from
            // "server broken" will keep retrying.
            session.send(
                ServerMessage.Rejected(
                    "Server is full (${config.maxPlayers} players)"
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
        if (hello.terrainGeneration != TerrainField.GENERATION) {
            session.send(
                ServerMessage.Rejected(
                    "Terrain mismatch: the server's world is terrain generation " +
                        "${TerrainField.GENERATION}, this game has ${hello.terrainGeneration}. " +
                        "Both need the same version of Apogee."
                ),
                Channel.CONTROL,
            )
            disconnect(session)
            return
        }

        if (hello.systemHash != world.system.contentHash) {
            session.send(
                ServerMessage.Rejected(
                    "Solar system mismatch: the server's worlds are ${world.system.contentHash}, " +
                        "this game's are ${hello.systemHash}. Both need the same version of Apogee."
                ),
                Channel.CONTROL,
            )
            disconnect(session)
            return
        }

        session.playerName = hello.playerName.take(32).ifBlank { "Pilot" }
        session.clientId = hello.clientId.take(64)
        if (session.clientId.isBlank()) {
            // Every build that speaks this protocol version sends one, so a blank id is a client
            // that shouldn't have got this far. It's refused instead of given an anonymous craft,
            // which would be shared by every other client in the same state.
            session.send(
                ServerMessage.Rejected("Client sent no identity"),
                Channel.CONTROL,
            )
            disconnect(session)
            return
        }
        // The stripe their crew wear, kept with the world for when they're away. A change shows
        // on their crew already out in suits too.
        val stripe = hello.stripe.takeIf { it in 0 until com.rm.apogee.core.crew.Crew.STRIPES }
        if (world.stripes[session.clientId] != stripe) {
            if (stripe != null) world.stripes[session.clientId] = stripe else world.stripes.remove(session.clientId)
            for (suit in world.vessels.filter { it.owner == session.clientId && it.design.parts.singleOrNull()?.partId == World.SUIT_PART }) {
                broadcast(ServerMessage.StructureMessage(world.structureUpdateFor(suit)), Channel.STRUCTURE)
            }
        }
        session.handshakeComplete = true

        // A returning player gets their craft back, wherever they left it. That's what "persistent
        // world" means from the seat: log off in orbit, come back, and still be in orbit.
        if (config.freshFlight) {
            world.lastFlown[session.clientId]?.let { last ->
                val previous = world.vessel(VesselId(last))
                if (previous != null && previous.owner == session.clientId) {
                    world.destroy(previous.id, "replaced by a new flight")
                }
            }
        }
        // Resuming one of theirs, or an unowned craft, which becomes theirs. That's a base from
        // before craft were owned by install, left there to be claimed.
        val chosen = config.resumeVessel?.let { world.vessel(VesselId(it)) }
            ?.takeIf { it.owner == session.clientId || it.owner.isBlank() }
            ?.also { if (it.owner.isBlank()) { world.claim(it, session.clientId); it.ownerName = session.playerName } }
        val existing = chosen ?: if (config.freshFlight) null else world.vesselOwnedBy(session.clientId)
        // The label follows the player, so renaming yourself renames your craft's owner instead of
        // orphaning it.
        existing?.ownerName = session.playerName
        // A career gets nothing. It starts from scratch, with whatever its player builds from the
        // starting kit.
        val vessel = existing ?: if (config.assignCraftOnJoin && world.program == null) {
            // The nearest clear pad, so joining never drops a craft inside one already standing
            // there, not even one left from before a restart.
            world.spawnAtSite(config.starterCraft(world.catalog), World.launchSites.first(), legsOut = true).also {
                world.assignOwner(it, session.clientId)
                it.ownerName = session.playerName
            }
        } else {
            null
        }
        session.controlledVessel = vessel?.id
        vessel?.let { world.lastFlown[session.clientId] = it.id.raw }

        session.send(
            ServerMessage.Welcome(
                protocolVersion = Protocol.VERSION,
                catalogHash = world.catalog.contentHash,
                serverName = config.name,
                controlledVessel = vessel?.id?.raw ?: -1L,
                weather = world.weatherConfig,
                mode = if (world.program != null) WorldSave.MODE_CAREER else WorldSave.MODE_SANDBOX,
            ),
            Channel.CONTROL,
        )

        // And what's been knocked down, so their forest matches everyone else's.
        if (world.felledScatter.isNotEmpty()) {
            session.send(ServerMessage.ScatterFelled(world.felledScatter.toList()), Channel.STRUCTURE)
            session.send(ServerMessage.Surveyed(world.surveyed.sorted()), Channel.STRUCTURE)
            session.rosterRevision = world.crewRevision
            session.send(ServerMessage.Roster(world.crewOf(session.clientId)), Channel.STRUCTURE)
        }

        // A joining client needs the structure of everything already out there, not just its own
        // craft, or every other player is invisible until something about them happens to change.
        for (existing in world.vessels) {
            session.send(
                ServerMessage.StructureMessage(world.structureUpdateFor(existing)),
                Channel.STRUCTURE,
            )
        }
        session.send(ServerMessage.SnapshotMessage(world.snapshot()), Channel.KINEMATICS)
    }

    private val tasks = ConcurrentQueue<() -> Unit>()

    /**
     * Runs [task] on the tick thread, between two steps, where the world is whole, instead of
     * whenever the calling thread happens to catch it. A save taken from another thread mid-step
     * could record half a tick, or trip over the vessel map changing under it.
     */
    fun runBetweenTicks(task: () -> Unit) {
        tasks.add(task)
    }

    private fun runQueuedTasks() {
        while (true) {
            val task = tasks.poll() ?: return
            task()
        }
    }

    /** Moves a session's control to [id] and tells the client about it. */
    private suspend fun takeControl(session: PlayerSession, id: VesselId) {
        session.controlledVessel = id
        world.lastFlown[session.clientId] = id.raw
        session.send(ServerMessage.ControlChanged(id.raw), Channel.CONTROL)
    }

    /**
     * Whether [session] can work the controls of [vessel]. It has to be the craft they're in, and
     * if two players' craft are docked, it's theirs to fly by what the two of them chose (anyone,
     * when nobody has said).
     */
    private fun flies(session: PlayerSession, vessel: Long): Boolean {
        if (session.controlledVessel?.raw != vessel) return false
        // Aboard someone else's craft, they're a passenger, not its pilot.
        val craft = world.vessel(VesselId(vessel)) ?: return false
        if (session.clientId !in world.ownersOf(craft)) return false
        val pilot = dockPilots[vessel] ?: return true
        return pilot.isEmpty() || pilot == session.clientId
    }

    private fun isAuthorised(session: PlayerSession, command: Command): Boolean = when (command) {
        is Command.SetThrottle -> flies(session, command.vessel)
        is Command.SetAttitude -> flies(session, command.vessel)
        is Command.SetSas -> flies(session, command.vessel)
        is Command.SetSasMode -> flies(session, command.vessel)
        is Command.SetNavFrame -> flies(session, command.vessel)
        is Command.SetTarget -> flies(session, command.vessel)
        is Command.SetBrakes -> flies(session, command.vessel)
        is Command.SetReverse -> flies(session, command.vessel)
        is Command.SetFlaps -> flies(session, command.vessel)
        is Command.ToggleGroup -> flies(session, command.vessel)
        is Command.SetCruise -> flies(session, command.vessel)
        is Command.SetStationKeep -> flies(session, command.vessel)
        is Command.Hook -> flies(session, command.vessel)
        is Command.Reel -> flies(session, command.vessel)
        is Command.ReleaseLine -> flies(session, command.vessel)
        is Command.Deploy -> flies(session, command.vessel)
        // Only for your own crew, from the craft you're in.
        is Command.Eva -> session.controlledVessel?.raw == command.vessel && world.crew[command.crew]?.owner == session.clientId
        is Command.TransferCrew -> session.controlledVessel?.raw == command.vessel && world.crew[command.crew]?.owner == session.clientId
        is Command.Board -> flies(session, command.vessel)
        is Command.Jump -> flies(session, command.vessel)
        is Command.Grab -> flies(session, command.vessel)
        is Command.PlantFlag -> flies(session, command.vessel)
        is Command.RightCraft -> flies(session, command.vessel)
        is Command.SetIndustry -> flies(session, command.vessel) || world.vessel(VesselId(command.vessel))?.let { it.anchored && it.owner == session.clientId } == true
        is Command.Unload -> flies(session, command.vessel)
        is Command.SetBallast -> flies(session, command.vessel)
        is Command.HoldDepth -> flies(session, command.vessel)
        is Command.SetTranslation -> flies(session, command.vessel)
        is Command.SetRcs -> flies(session, command.vessel)
        is Command.Stage -> flies(session, command.vessel)
        is Command.Undock -> flies(session, command.vessel)
        // Either of the two it's shared between can say who flies it.
        is Command.SetDockPilot -> session.controlledVessel?.raw == command.vessel &&
            world.vessel(VesselId(command.vessel))?.let { session.clientId in world.ownersOf(it) } == true
        // Welding uses up the *other* craft, which might belong to someone else. Only the craft
        // being flown can start it, and the world still refuses unless the two are touching and at
        // rest. This is the line to look at again when bases get owners worth defending.
        is Command.Join -> session.controlledVessel?.raw == command.vessel
        is Command.Anchor -> world.vessel(VesselId(command.vessel))?.owner == session.clientId
        is Command.Refuel -> flies(session, command.vessel)
        // Only your own craft. Otherwise a player could take the controls of somebody else's base
        // on a shared server. Or one docked with yours, because you have a seat in it.
        is Command.SwitchVessel ->
            world.vessel(VesselId(command.vessel))
                ?.let { session.clientId in world.ownersOf(it) } == true
        is Command.SpawnCraft -> true
        is Command.Chat -> true
        is Command.SetWarp -> warpAllowed
        is Command.WarpTo -> warpAllowed
        is Command.PlanBurns -> flies(session, command.vessel)
        is Command.SetAutopilot -> flies(session, command.vessel)
        // Only your own, never another player's base.
        is Command.RemoveVessel -> world.vessel(VesselId(command.vessel))?.owner == session.clientId
        // Their own career, whatever they're flying.
        is Command.Unlock -> true
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

                // Staging changes which parts are live and which stage is next, and both live in
                // the structure message. Without this, the client's stage counter and engine-lit
                // state go stale the moment anything gets staged that doesn't also separate.
                is WorldEvent.Staged ->
                    world.vessel(event.id)?.let {
                        broadcast(
                            ServerMessage.StructureMessage(world.structureUpdateFor(it)),
                            Channel.STRUCTURE,
                        )
                    }

                is WorldEvent.Touchdown -> Unit
                is WorldEvent.BodyChanged -> Unit
                // Told as the roster changes. See sendFuel.
                is WorldEvent.CrewLost -> Unit
                // Told to the player whose career it is, wherever they are.
                is WorldEvent.FeatEarned -> {
                    val feat = ServerMessage.Feat(event.title, event.grade, event.insight)
                    val present = sessions.filter { it.clientId == event.owner && it.connected }
                    for (s in present) s.send(feat, Channel.CONTROL)
                    // Earned while they were away (a craft of theirs coming to rest before they'd
                    // finished joining), and told when they're back.
                    if (present.isEmpty()) unsentFeats.getOrPut(event.owner) { ArrayDeque() }.let { waiting ->
                        if (waiting.size >= MAX_UNSENT_FEATS) waiting.removeFirst()
                        waiting.addLast(feat)
                    }
                }
                is WorldEvent.Surveyed -> broadcast(ServerMessage.Surveyed(world.surveyed.sorted()), Channel.STRUCTURE)
                // Told to the pilot with the refuel state, not as an event of its own.
                is WorldEvent.RefuelStopped -> refuelStops[event.id.raw] = event.reason

                is WorldEvent.ScatterFelled ->
                    broadcast(ServerMessage.ScatterFelled(listOf(event.scatterId)), Channel.STRUCTURE)

                // A failed part changes what the craft can still do, and that lives in the
                // structure message next to staging.
                is WorldEvent.PartFailed ->
                    world.vessel(event.id)?.let {
                        broadcast(
                            ServerMessage.StructureMessage(world.structureUpdateFor(it)),
                            Channel.STRUCTURE,
                        )
                    }

                is WorldEvent.LightningHit ->
                    broadcast(ServerMessage.Lightning(event.strikeId, event.id.raw, event.partIndex), Channel.STRUCTURE)

                // A break-up reaches clients as the structure changes it brings. These are for the
                // effects and the sound.
                is WorldEvent.Impact -> partEvent(
                    ServerMessage.PartEvent(
                        PartEventKind.IMPACT, event.id.raw, event.partId, event.bodyId, event.position.copy(), event.speed,
                        cause = if (event.water) "water" else "", time = world.time,
                    ),
                )
                is WorldEvent.PartDestroyed -> partEvent(
                    ServerMessage.PartEvent(PartEventKind.DESTROYED, event.id.raw, event.partId, event.bodyId, event.position.copy(), cause = event.cause, time = world.time),
                )
                is WorldEvent.PartDetached -> partEvent(
                    ServerMessage.PartEvent(PartEventKind.DETACHED, event.id.raw, event.partId, event.bodyId, event.position.copy(), cause = event.cause, time = world.time),
                )
                is WorldEvent.Docked -> {
                    // Whoever was in the craft that docked on is in the whole thing now.
                    for (session in sessions) {
                        if (session.controlledVessel == event.absorbed) takeControl(session, event.keeper)
                    }
                    world.vessel(event.keeper)?.let { whole ->
                        if (world.ownersOf(whole).size > 1) {
                            dockPilots.putIfAbsentShared(event.keeper.raw, "")
                            announceDock(event.keeper.raw)
                        }
                    }
                    partEvent(ServerMessage.PartEvent(PartEventKind.DOCKED, event.keeper.raw, "", event.bodyId, event.position.copy(), time = world.time))
                }
                is WorldEvent.Undocked -> {
                    // A player whose craft that was goes with it.
                    val spawned = world.vessel(event.spawned)
                    for (session in sessions) {
                        if (spawned != null && session.controlledVessel == event.from && spawned.owner == session.clientId &&
                            world.vessel(event.from)?.owner != session.clientId
                        ) takeControl(session, event.spawned)
                    }
                    if (world.vessel(event.from)?.let { world.ownersOf(it).size <= 1 } != false) dockPilots.remove(event.from.raw)
                    partEvent(ServerMessage.PartEvent(PartEventKind.UNDOCKED, event.from.raw, "", event.bodyId, event.position.copy(), time = world.time))
                }
                is WorldEvent.Hitched -> partEvent(
                    ServerMessage.PartEvent(
                        if (event.coupled) PartEventKind.HITCHED else PartEventKind.UNHITCHED,
                        event.a.raw, "", event.bodyId, event.position.copy(), time = world.time,
                    ),
                )
                is WorldEvent.Winched -> world.vessel(event.a)?.let { vessel ->
                    partEvent(
                        ServerMessage.PartEvent(
                            when { event.snapped -> PartEventKind.SNAPPED; event.hooked -> PartEventKind.HOOKED; else -> PartEventKind.UNHOOKED },
                            event.a.raw, "", vessel.referenceBodyId, vessel.body.position.copy(), time = world.time,
                        ),
                    )
                }
                is WorldEvent.Explosion -> partEvent(
                    ServerMessage.PartEvent(PartEventKind.EXPLOSION, -1L, "", event.bodyId, event.position.copy(), event.energy, time = world.time),
                )
            }
        }
    }

    /**
     * Sends a chat line from the server itself.
     *
     * It's public because the dedicated server's admin channel needs to talk to the people playing.
     * An operator announcing a restart is the most useful thing an admin panel does.
     */
    suspend fun broadcastChat(from: String, text: String) =
        broadcast(ServerMessage.ChatMessage(from, text), Channel.CONTROL)

    /** Finds a connected player by name, for admin actions. */
    fun sessionNamed(playerName: String): PlayerSession? =
        sessions.firstOrNull {
            it.connected && it.handshakeComplete && it.playerName.equals(playerName, ignoreCase = true)
        }

    private suspend fun broadcastSnapshot() {
        if (sessions.isEmpty()) return
        // A second player arriving ends any pause or warp, because it's their world too.
        if (!warpAllowed) requestedWarp = 1.0
        val snapshot = world.snapshot().copy(
            warp = effectiveWarp(),
            warpRequested = requestedWarp,
            warpAllowed = warpAllowed,
        )
        broadcast(ServerMessage.SnapshotMessage(snapshot), Channel.KINEMATICS)
    }

    /** Each pilot's own tanks, what their craft can do with a base, and the base nearest them. */
    private suspend fun sendFuel() {
        for (session in sessions) {
            if (!session.connected || !session.handshakeComplete) continue
            // Whether or not they still have a craft, because one just lost is when it matters
            // most.
            if (session.rosterRevision != world.crewRevision) {
                session.rosterRevision = world.crewRevision
                session.send(ServerMessage.Roster(world.crewOf(session.clientId)), Channel.STRUCTURE)
            }
            world.program?.let { program ->
                if (session.careerRevision != program.revision) {
                    session.careerRevision = program.revision
                    session.send(ServerMessage.Career(program.careerOf(session.clientId), program.firsts.toList()), Channel.STRUCTURE)
                }
            }
            if (session.wondersRevision != world.wondersRevision) {
                session.wondersRevision = world.wondersRevision
                session.send(ServerMessage.WondersFound(world.wondersFoundBy(session.clientId).sorted()), Channel.STRUCTURE)
            }
            // Feats in a career, and finds in free play too.
            unsentFeats.remove(session.clientId)?.forEach { session.send(it, Channel.CONTROL) }
            val vessel = session.controlledVessel?.let { world.vessel(it) } ?: continue
            session.send(ServerMessage.FuelLevels(vessel.id.raw, vessel.flatResources()), Channel.KINEMATICS)
            session.send(world.systemsOf(vessel).copy(passenger = session.clientId !in world.ownersOf(vessel)), Channel.KINEMATICS)
            session.send(
                ServerMessage.Service(
                    vessel.id.raw,
                    canFound = world.canAnchor(vessel),
                    founded = vessel.anchored,
                    canRefuel = world.canRefuel(vessel),
                    refuelling = world.isRefuelling(vessel.id),
                    stopped = refuelStops[vessel.id.raw].orEmpty(),
                    canUnload = world.canUnload(vessel),
                    unloading = world.isUnloading(vessel.id),
                ),
                Channel.KINEMATICS,
            )
            nearestBase(vessel)?.let { (base, distance) -> session.send(baseStatus(base, distance), Channel.KINEMATICS) }
        }
    }

    /** Why each craft's refuelling last stopped, by id, to tell its pilot. */
    private val refuelStops = com.rm.apogee.core.concurrentMapOf<Long, String>()

    /**
     * The founded base nearest [vessel], within [BASE_CARD_REACH] (itself, if it is one), and how
     * far away it is.
     */
    private fun nearestBase(vessel: com.rm.apogee.core.craft.Vessel): Pair<com.rm.apogee.core.craft.Vessel, Double>? {
        if (vessel.anchored) return vessel to 0.0
        var best: com.rm.apogee.core.craft.Vessel? = null
        var bestDistance = BASE_CARD_REACH
        for (other in world.vessels) {
            if (!other.anchored || other.owner == World.WORLD_OWNER || other.referenceBodyId != vessel.referenceBodyId) continue
            // A planted flag is founded, but it isn't a base.
            if (other.design.parts.singleOrNull()?.partId == World.FLAG_PART) continue
            val d = other.body.position.distanceTo(vessel.body.position) - other.contactRadius
            if (d < bestDistance) { bestDistance = d; best = other }
        }
        return best?.let { it to bestDistance.coerceAtLeast(0.0) }
    }

    private fun baseStatus(base: com.rm.apogee.core.craft.Vessel, distance: Double): ServerMessage.BaseStatus {
        world.settlePower(base)
        val charge = com.rm.apogee.core.part.ResourceType.ELECTRIC_CHARGE
        val propellant = com.rm.apogee.core.part.ResourceType.PROPELLANT
        val mono = com.rm.apogee.core.part.ResourceType.MONOPROPELLANT
        return ServerMessage.BaseStatus(
            vessel = base.id.raw,
            name = base.name,
            powered = base.powered,
            charge = base.amountOf(charge).toFloat(),
            chargeCapacity = base.capacityOf(charge).toFloat(),
            net = base.powerNet.toFloat(),
            propellant = base.amountOf(propellant).toFloat(),
            propellantCapacity = base.capacityOf(propellant).toFloat(),
            monopropellant = base.amountOf(mono).toFloat(),
            monopropellantCapacity = base.capacityOf(mono).toFloat(),
            pads = base.defs.count { it.hasModule<com.rm.apogee.core.part.LaunchPad>() },
            distance = distance.toFloat(),
            ore = base.amountOf(com.rm.apogee.core.part.ResourceType.ORE).toFloat(),
            oreCapacity = base.capacityOf(com.rm.apogee.core.part.ResourceType.ORE).toFloat(),
            water = base.amountOf(com.rm.apogee.core.part.ResourceType.WATER).toFloat(),
            waterCapacity = base.capacityOf(com.rm.apogee.core.part.ResourceType.WATER).toFloat(),
            hasRefinery = base.defs.indices.any { !base.isBroken(it) && base.defs[it].hasModule<com.rm.apogee.core.part.Converter>() },
            refining = base.control.refining,
            drilling = base.control.drilling,
        )
    }

    private suspend fun partEvent(event: ServerMessage.PartEvent) = broadcast(event, Channel.STRUCTURE)

    /**
     * Two players' craft docked into one: who flies each, by vessel id, as a client id, or empty
     * for either of them. If it's missing, the question doesn't come up.
     */
    private val dockPilots = com.rm.apogee.core.concurrentMapOf<Long, String>()

    /** Tells each player with a seat in [vessel] who else has one and who flies it. */
    private suspend fun announceDock(vessel: Long) {
        val whole = world.vessel(VesselId(vessel)) ?: return
        val owners = world.ownersOf(whole)
        val pilot = dockPilots[vessel] ?: ""
        for (session in sessions) {
            if (!session.connected || !session.handshakeComplete || session.clientId !in owners) continue
            val other = sessions.firstOrNull { it.clientId in owners && it.clientId != session.clientId }
            val otherName = other?.playerName
                ?: whole.design.parts.firstNotNullOfOrNull { it.dockedFrom?.takeIf { d -> d.owner != session.clientId }?.ownerName }
                ?: whole.ownerName
            session.send(ServerMessage.DockedWith(vessel, otherName, other?.clientId ?: "", pilot), Channel.CONTROL)
        }
    }

    private suspend fun broadcast(message: ServerMessage, channel: Channel) {
        for (session in sessions) {
            if (session.connected && session.handshakeComplete) session.send(message, channel)
        }
    }

    companion object {
        /** The most feats kept for a player who's away: a few to tell them, not a backlog. */
        const val MAX_UNSENT_FEATS = 5

        /** How near a founded base has to be for its card to show, in metres beyond its edge. */
        const val BASE_CARD_REACH = 300.0

        private const val MAX_CATCHUP_NANOS = 250_000_000L
        private const val FUEL_HZ = 4

        fun default(catalog: PartCatalog = StockParts.catalog, config: ServerConfig = ServerConfig()) =
            GameServer(World.default(catalog), config)
    }
}
