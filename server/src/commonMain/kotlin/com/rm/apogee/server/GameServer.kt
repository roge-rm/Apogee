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
     * How often vessel motion goes out. It's below the tick rate, and the same over loopback as
     * over a socket, so loopback doesn't hide interpolation and prediction problems.
     */
    val snapshotHz: Int = 20,
    /** The craft handed to a player who joins with nothing. */
    val starterCraft: (PartCatalog) -> CraftDesign = { StockCraft.starterRocket(it) },
    /**
     * Whether a player who owns nothing gets [starterCraft] when they join. False when the client
     * is about to launch its own craft, or a spare rocket gets left on the pad.
     */
    val assignCraftOnJoin: Boolean = true,
    /**
     * Start every joining player on a fresh [starterCraft], clearing away the craft they flew last
     * time. Free Flight in single player. Their bases elsewhere stay.
     */
    val freshFlight: Boolean = false,
    /** The one player, by client id, who starts on a fresh [starterCraft] (the host picking a site). */
    val freshFlightFor: String? = null,
    /** Where [starterCraft] goes, by launch site id. Null for the first site, the Cape. */
    val starterSite: String? = null,
    /**
     * The craft a joining player who's choosing ([ClientMessage.Hello.choosing]) can pick from,
     * besides their own. Read on the server's own thread as each player joins.
     */
    val offeredCraft: (PartCatalog) -> List<CraftDesign> = { listOf(starterCraft(it)) },
    /** Joining asks for this, or blank for anyone. */
    val password: String = "",
    /** Put a joining player straight into this craft (Resume Flight). Ignored if it isn't theirs. */
    val resumeVessel: Long? = null,
    /** How many players at once, or 0 for no limit. */
    val maxPlayers: Int = 0,
    /** How lively the weather is, overriding the world's own. Null keeps it. The seed stays. */
    val weatherIntensity: com.rm.apogee.core.weather.WeatherIntensity? = null,
    /** How cloudy it is, also overriding the world's own. Null keeps it. */
    val cloudCover: com.rm.apogee.core.weather.CloudCover? = null,
    /** Whether a player can pause or warp at all. See [warp] for how, with others in. */
    val allowWarp: Boolean = false,
    /** How players share warp, the host's choice. Without [allowWarp], none. */
    val warp: WarpPolicy = if (allowWarp) WarpPolicy.TOGETHER else WarpPolicy.OFF,
)

/** How a game with more than one player in it warps. Alone, a player always can. */
enum class WarpPolicy {
    /** Anyone asks, and warps once everyone else agrees. */
    TOGETHER,
    /** Only the host warps. */
    HOST,
    /** Nobody warps or pauses. */
    OFF,
    /**
     * Each player warps in a bubble of their own time, alone in their part of space, and rejoins
     * everyone's time when they stop. Near someone else, it's asked of everyone as [TOGETHER].
     */
    BUBBLES,
}

/**
 * One connected player: what the server needs to talk to them and decide what they may do.
 * Authority is per vessel, so a command for a vessel the sender doesn't control is dropped.
 */
class PlayerSession internal constructor(
    val id: Int,
    val transport: Transport,
) {
    @Volatile var playerName: String = "Pilot"
        internal set

    /**
     * Who this player is to the world: opaque, made once per install. Craft ownership hangs off
     * this, since two people can share a [playerName].
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

    /** The finds revision last sent. See [com.rm.apogee.core.world.World.wondersRevision]. */
    @Volatile var wondersRevision: Int = -1
        internal set

    @Volatile var connected: Boolean = true
        internal set

    /** The host's own player, who can pause everyone. */
    var isHost: Boolean = false
        internal set

    /** The server tick of this player's last command for their craft, for telling who's away. */
    internal var lastInputTick: Long = 0L

    /**
     * Queues [message] for the writer. The tick never waits on a socket, so one player on a slow
     * link can't hold up the world. Motion goes latest only, so a link that can't keep up skips
     * stale snapshots instead of falling behind; everything else goes in order. Messages are
     * encoded on the writer too. A loopback in the same process takes them straight.
     */
    suspend fun send(message: ServerMessage, channel: Channel) {
        if (!connected) return
        if (transport.passesObjects) {
            transport.send(Packet(channel, NO_BYTES, message))
            return
        }
        if (transport.inProcess) {
            transport.send(Packet(channel, Codec.encode(message)))
            return
        }
        if (channel == Channel.KINEMATICS) latestMotion = message else inOrder.trySend(channel to message)
        wake.trySend(Unit)
    }

    private val inOrder = kotlinx.coroutines.channels.Channel<Pair<Channel, ServerMessage>>(kotlinx.coroutines.channels.Channel.UNLIMITED)
    @Volatile private var latestMotion: ServerMessage? = null
    private val wake = kotlinx.coroutines.channels.Channel<Unit>(kotlinx.coroutines.channels.Channel.CONFLATED)

    /** Writes what [send] queued until the player goes. One per player, off the tick. */
    internal fun startWriter(scope: CoroutineScope) {
        if (transport.inProcess) return
        writing = true
        // On the scope's own dispatcher: the transport does its writing off it.
        scope.launch {
            try {
                while (true) {
                    wake.receive()
                    while (true) {
                        val next = inOrder.tryReceive()
                        // Closed and empty: everything queued before the end has gone.
                        if (next.isClosed) return@launch
                        val (channel, message) = next.getOrNull() ?: break
                        transport.send(Packet(channel, Codec.encode(message)))
                    }
                    latestMotion?.let { motion ->
                        latestMotion = null
                        if (connected) transport.send(Packet(Channel.KINEMATICS, Codec.encode(motion)))
                    }
                }
            } finally {
                if (closeWhenWritten) transport.close()
            }
        }
    }

    @Volatile private var writing = false
    @Volatile private var closeWhenWritten = false

    /**
     * Ends the writer once what's queued has gone, so a rejection still reaches the player, and
     * with [close], shuts the transport after it.
     */
    internal fun stopWriter(close: Boolean) {
        closeWhenWritten = close
        inOrder.close()
        wake.trySend(Unit)
        if (close && !writing) transport.close()
    }
}

/**
 * The simulation that's in charge, on a hosting phone or a dedicated server; only the [Transport]
 * differs. Commands are queued and applied between ticks, never mid-step.
 */
class GameServer(
    val world: World,
    val config: ServerConfig = ServerConfig(),
) {
    private val sessions = com.rm.apogee.core.copyOnWriteListOf<PlayerSession>()

    /** Feats earned while their owner was away, to tell them when they're back. */
    private val unsentFeats = com.rm.apogee.core.concurrentMapOf<String, ArrayDeque<ServerMessage.Feat>>()

    init {
        // Weather is set before anyone joins, so the welcome can carry it.
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

    /** Fuel goes to each pilot four times a second. */
    private val ticksPerFuel: Int = (config.tickHz / FUEL_HZ).coerceAtLeast(1)

    val playerCount: Int get() = sessions.count { it.connected && it.handshakeComplete }

    /**
     * Registers a transport and pumps its packets into the inbox. Reading runs on the caller's
     * scope, so a slow client can't stall the world.
     */
    fun accept(transport: Transport, scope: CoroutineScope, host: Boolean = false): PlayerSession {
        val session = PlayerSession(nextSessionId++, transport)
        session.isHost = host
        sessions.add(session)
        session.startWriter(scope)

        scope.launch(Dispatchers.Default) {
            try {
                transport.incoming.collect { packet ->
                    val message = packet.message as? ClientMessage ?: runCatching { Codec.decodeClientMessage(packet.bytes) }.getOrNull()
                    // A packet that won't decode is dropped.
                    if (message != null) inbox.add(session to message)
                }
            } finally {
                session.connected = false
                session.stopWriter(close = false)
            }
        }
        return session
    }

    fun disconnect(session: PlayerSession) {
        session.connected = false
        session.stopWriter(close = true)
        sessions.remove(session)
    }

    /**
     * Runs the simulation until the scope is cancelled. Ticks are due on a fixed schedule, so
     * millisecond rounding doesn't drift and the loop never spins waiting for a tick.
     *
     * [dispatcher] is where the ticks run. A hosting phone gives it a thread of its own, so it
     * doesn't wait behind the sea and ground builders.
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
            // Under a millisecond to go, so take the tick now. The schedule stops it adding up.

            drainInbox()
            runQueuedTasks()
            advanceWorld()
            tickCount++

            publishEvents()
            if (tickCount % ticksPerSnapshot == 0L) broadcastSnapshot()
            if (tickCount % ticksPerFuel == 0L) sendFuel()

            nextTickAt += tickNanos

            // Too far behind (a stalled thread or a sleeping laptop): drop the backlog.
            if (com.rm.apogee.core.nanoTime() - nextTickAt > MAX_CATCHUP_NANOS) {
                nextTickAt = com.rm.apogee.core.nanoTime() + tickNanos
            }
            // In a browser the server shares the page's one thread, so give way once a tick or a
            // server running behind starves the page.
            kotlinx.coroutines.yield()
        }
    }

    /**
     * One tick at the warp rate: nothing while paused, extra steps up to physics warp, and past
     * that on rails, falling back to physics warp for what the rails won't take.
     */
    private fun advanceWorld() {
        serverTick++
        resolveVote()
        // Reached the warp's stop time, so back to real time.
        if (!world.warpUntil.isNaN() && world.time >= world.warpUntil - 1e-6) {
            world.warpUntil = Double.NaN
            if (requestedWarp > 1.0 && playerCount > 1) notices += "Warp done"
            requestedWarp = 1.0
        }
        world.hurried = warpAllowed && !paused && requestedWarp > World.PHYSICS_WARP
        val rate = effectiveWarp()
        when {
            rate <= 0.0 -> Unit
            rate <= World.PHYSICS_WARP -> repeat(rate.toInt().coerceAtLeast(1)) { world.step(dt) }
            else -> {
                val done = world.advanceOnRails(dt * rate)
                if (done < dt * rate) repeat(World.PHYSICS_WARP.toInt()) { world.step(dt) }
            }
        }
        if (!paused && !held) advanceBubbles(dt)
    }

    /** The warp asked for and agreed: 1 is real time. See [paused]. */
    @Volatile
    var requestedWarp: Double = 1.0
        private set

    /** Paused, by the one player alone or by the host. */
    @Volatile
    var paused: Boolean = false
        private set

    /** Held still while the phone's own game is out of sight, apart from any pause of the player's. */
    @Volatile
    var held: Boolean = false

    /** Whether this server warps at all. Who may is [mayWarp]. */
    val warpAllowed: Boolean get() = config.warp != WarpPolicy.OFF

    /** Whether [session] may speed the world up, alone or as the host chose. */
    private fun mayWarp(session: PlayerSession): Boolean = when (config.warp) {
        WarpPolicy.OFF -> false
        WarpPolicy.HOST -> session.isHost || playerCount <= 1
        WarpPolicy.TOGETHER, WarpPolicy.BUBBLES -> true
    }

    /** Whether [session] may pause everyone: the one player alone, or the host. */
    private fun mayPause(session: PlayerSession): Boolean = warpAllowed && (playerCount <= 1 || session.isHost)

    /** The rate the world actually runs at. */
    fun effectiveWarp(): Double {
        if (held) return 0.0
        if (!warpAllowed) return 1.0
        if (paused) return 0.0
        if (requestedWarp <= 1.0) return requestedWarp.coerceAtLeast(1.0)
        val limit = world.maxWarp()
        // With others in, anyone flying (thrusting, landing, in the air) holds an agreed warp to
        // real time, not physics warp, so it's never sped up on them. Standing on the ground is
        // no danger.
        if (playerCount > 1 && limit <= World.PHYSICS_WARP && world.movingWarpLimiter() != null) return 1.0
        return minOf(requestedWarp, limit)
    }

    /** Server ticks, paused or not, for the vote's timing. */
    private var serverTick = 0L

    /** How long a player away has before they count as allowing, how long it takes to be away, and how long a vote lasts. */
    private val AUTO_ALLOW_TICKS get() = 5L * config.tickHz
    private val IDLE_TICKS get() = 60L * config.tickHz
    private val VOTE_TICKS get() = 30L * config.tickHz

    /** Lines to tell everyone, from the tick: who asked, who stopped it. Sent with the snapshot. */
    private val notices = ArrayList<String>()

    /** A warp asked for, waiting on everyone else. */
    private class Pending(
        val id: Int, val from: PlayerSession, val rate: Double, val until: Double, val label: String,
        val at: Long, val allowed: MutableSet<Int> = HashSet(),
    )
    private var pending: Pending? = null
    private var nextVoteId = 1

    /** A warp to [rate] (until [until], or NaN), from [session]: at once if it may be, else asked of everyone. */
    private fun askWarp(session: PlayerSession, rate: Double, until: Double, label: String) {
        val wanted = rate.coerceIn(1.0, World.WARP_RATES.last())
        // Slower is always safe, and alone or as the host given it, nobody's asked.
        val now = wanted <= requestedWarp || playerCount <= 1 || (config.warp == WarpPolicy.HOST && session.isHost)
        if (now) {
            if (playerCount > 1 && wanted > requestedWarp) notices += "${session.playerName} is warping${aboutWarp(wanted, until, label)}"
            setWarp(wanted, until)
            pending = null
            return
        }
        if (config.warp != WarpPolicy.TOGETHER && config.warp != WarpPolicy.BUBBLES) return
        pending = Pending(nextVoteId++, session, wanted, until, label, serverTick).also { it.allowed += session.id }
        notices += "${session.playerName} asks to warp${aboutWarp(wanted, until, label)}"
    }

    private fun setWarp(rate: Double, until: Double) {
        requestedWarp = rate
        world.warpUntil = if (until > world.time) until else Double.NaN
    }

    /** " to Luna arrival (2 d)" or " 1000x", for the notices. */
    private fun aboutWarp(rate: Double, until: Double, label: String): String =
        if (!until.isNaN()) " to ${label.ifBlank { "later" }} (${com.rm.apogee.core.world.WarpWords.span(until - world.time)})"
        else " ${rate.toLong()}x"

    /** The players still to answer [vote]: everyone in but the asker who hasn't allowed, and isn't away. */
    private fun waitingOn(vote: Pending): List<PlayerSession> = sessions.filter { s ->
        s.connected && s.handshakeComplete && s.id !in vote.allowed &&
            !(serverTick - vote.at >= AUTO_ALLOW_TICKS && away(s))
    }

    /** Whether [session] is away: nothing to fly, parked, or not touched the controls in a while. */
    private fun away(session: PlayerSession): Boolean {
        val craft = session.controlledVessel?.let { world.vessel(it) } ?: return true
        return craft.dormant || craft.anchored || serverTick - session.lastInputTick >= IDLE_TICKS
    }

    /** Starts the warp once everyone's agreed, or lets it lapse. */
    private fun resolveVote() {
        val vote = pending ?: return
        if (!vote.from.connected) { pending = null; return }
        val waiting = waitingOn(vote)
        if (waiting.isEmpty()) {
            pending = null
            setWarp(vote.rate, vote.until)
            notices += "Warping${aboutWarp(vote.rate, vote.until, vote.label)}"
        } else if (serverTick - vote.at >= VOTE_TICKS) {
            pending = null
            notices += "No answer from ${waiting.joinToString { it.playerName }}, so no warp"
        }
    }

    private fun answerWarp(session: PlayerSession, command: Command.AnswerWarp) {
        val vote = pending ?: return
        if (vote.id != command.request) return
        if (command.allow) vote.allowed += session.id
        else {
            pending = null
            notices += "${session.playerName} said no to warp"
        }
    }

    /**
     * The bubble [session]'s warp goes in: theirs already, or a new one if this game warps in
     * bubbles, others are in, and nobody else's craft is near. Null to warp the whole world.
     */
    private fun bubbleFor(session: PlayerSession): World.Bubble? {
        val craft = session.controlledVessel?.let { world.vessel(it) } ?: return null
        world.bubbles[craft.id.raw]?.let { return it }
        if (config.warp != WarpPolicy.BUBBLES || playerCount <= 1 || paused) return null
        if (othersNear(session, craft)) return null
        return world.openBubble(world.hitchedGroup(craft), session.clientId)
    }

    /**
     * Whether everything near [vessel] is in the same time as it, so it can board, hook, join or
     * refuel from them. Out of one bubble, or near a craft in one, the two aren't really there
     * together. Away from every bubble it's always true.
     */
    private fun sameTimeAround(vessel: Long): Boolean {
        if (world.bubbles.isEmpty()) return true
        val craft = world.vessel(VesselId(vessel)) ?: return true
        val mine = world.bubbles[vessel]
        return world.vessels.none { other ->
            other !== craft && world.bubbles[other.id.raw] !== mine &&
                other.referenceBodyId == craft.referenceBodyId &&
                other.body.position.distanceTo(craft.body.position) < SAME_TIME_REACH
        }
    }

    /** Whether another player's craft is in [craft]'s part of space: round the same world, and near. */
    private fun othersNear(session: PlayerSession, craft: com.rm.apogee.core.craft.Vessel): Boolean =
        world.vessels.any { other ->
            other.owner.isNotBlank() && other.owner != session.clientId && other.owner != World.WORLD_OWNER &&
                other.referenceBodyId == craft.referenceBodyId &&
                other.body.position.distanceTo(craft.body.position) < BUBBLE_NEAR
        }

    /** [session] warping their own bubble: at [rate], to [until] in its own time, or with rate 0, waiting. */
    private fun warpBubble(session: PlayerSession, rate: Double, until: Double, label: String) {
        val craft = session.controlledVessel?.let { world.vessel(it) } ?: return
        val bubble = world.bubbles[craft.id.raw] ?: return
        if (rate <= 0.0 && mayPause(session) && playerCount <= 1) { paused = true; return }
        if (paused && mayPause(session)) paused = false
        bubble.rate = rate.coerceIn(0.0, World.WARP_RATES.last())
        bubble.until = if (until > bubble.time) until else Double.NaN
        if (rate > 1.0 && !until.isNaN()) notices += "${session.playerName} is warping ahead${aboutWarp(rate, until - (bubble.time - world.time), label)}"
    }

    /**
     * Each bubble on by a tick: stopping at its warp-to time or near someone else, waiting while
     * its owner's away, and back on everyone's time once it's stopped where it can be.
     */
    private fun advanceBubbles(dt: Double) {
        if (world.bubbles.isEmpty()) return
        for (bubble in world.bubbles.values.toSet()) {
            val owner = sessions.firstOrNull { it.clientId == bubble.owner && it.connected && it.handshakeComplete }
            val members = world.membersOf(bubble)
            if (members.isEmpty()) continue
            when {
                // Gone, or alone in the game: it waits, and the world catches it up.
                owner == null || playerCount <= 1 -> bubble.rate = 0.0
                !bubble.until.isNaN() && bubble.time >= bubble.until - 1e-6 -> { bubble.rate = 1.0; bubble.until = Double.NaN }
                bubble.rate > 1.0 && members.any { othersNear(owner, it) } -> {
                    bubble.rate = 1.0; bubble.until = Double.NaN
                    notices += "${owner.playerName} came near someone, so their warp stopped"
                }
            }
            world.advanceBubble(bubble, dt)
            val lead = bubble.time - world.time
            // Caught up, or stopped where it can rejoin.
            if (lead <= 1e-6) { bubble.time = world.time; world.closeBubble(bubble) }
            else if (bubble.rate == 1.0 && world.canClose(bubble)) {
                world.closeBubble(bubble)
                if (owner != null) notices += "${owner.playerName} is back in everyone's time"
            }
        }
    }

    /** Asked of everyone, as the snapshot carries it. */
    private fun voteState(): com.rm.apogee.core.world.WarpVote? {
        val vote = pending ?: return null
        val waiting = waitingOn(vote)
        return com.rm.apogee.core.world.WarpVote(
            vote.id, vote.from.clientId, vote.from.playerName, vote.rate, vote.until, vote.label,
            waiting.map { it.clientId }, waiting.map { it.playerName },
            ((VOTE_TICKS - (serverTick - vote.at)) / config.tickHz.toDouble()).coerceAtLeast(0.0),
        )
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
                // Anything but talk and warp is them at the controls.
                if (command !is Command.Chat && command !is Command.SetWarp && command !is Command.WarpTo && command !is Command.AnswerWarp) {
                    session.lastInputTick = serverTick
                }
                if (command is Command.Chat) {
                    val text = command.text.trim().take(MAX_CHAT_CHARS)
                    if (text.isNotEmpty()) broadcast(
                        ServerMessage.ChatMessage(
                            session.playerName, text,
                            com.rm.apogee.core.crew.Crew.stripeFor(session.clientId, world.stripes[session.clientId]),
                        ),
                        Channel.CONTROL,
                    )
                } else if (command is Command.SetWarp && bubbleFor(session) != null) {
                    warpBubble(session, command.rate, Double.NaN, "")
                } else if (command is Command.WarpTo && bubbleFor(session) != null) {
                    if (command.time > world.time) warpBubble(session, World.WARP_RATES.last(), command.time, command.label)
                } else if (command is Command.SetWarp) {
                    if (command.rate <= 0.0) {
                        if (mayPause(session) && !paused) {
                            paused = true
                            pending = null
                            if (playerCount > 1) notices += "${session.playerName} paused the game"
                        }
                    } else {
                        if (paused && mayPause(session)) {
                            paused = false
                            if (playerCount > 1) notices += "${session.playerName} carried on"
                        }
                        if (command.rate < requestedWarp) {
                            // Anyone can slow it, back to real time at the least.
                            if (playerCount > 1 && command.rate <= 1.0) notices += "${session.playerName} stopped warp"
                            setWarp(command.rate.coerceAtLeast(1.0), Double.NaN)
                            pending = null
                        } else if (command.rate > requestedWarp && mayWarp(session)) {
                            askWarp(session, command.rate, Double.NaN, "")
                        }
                    }
                } else if (command is Command.WarpTo) {
                    if (command.time > world.time && mayWarp(session)) askWarp(session, World.WARP_RATES.last(), command.time, command.label)
                } else if (command is Command.AnswerWarp) {
                    answerWarp(session, command)
                } else if (command is Command.Kick) {
                    kick(command.clientId)
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
                    // They fly the suit now.
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
                    // In a career, only unlocked parts, and no more than the pad can take.
                    val refused = world.program?.refusal(session.clientId, command.design, command.siteId, world.catalog)
                    if (refused != null) {
                        session.send(ServerMessage.CareerRefused(refused), Channel.CONTROL)
                    } else {
                        // The player flies the new craft, not whatever they arrived in.
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
     * Refuses a mismatched client. A protocol mismatch misreads messages; a catalogue, terrain or
     * system mismatch reads them fine but the two simulations quietly drift apart.
     */
    private suspend fun completeHandshake(session: PlayerSession, hello: ClientMessage.Hello) {
        if (!session.isHost && hello.clientId in kicked) {
            session.send(ServerMessage.Rejected("The host has sent you out of this game"), Channel.CONTROL)
            disconnect(session)
            return
        }
        if (!session.isHost && config.password.isNotEmpty() && hello.password != config.password) {
            session.send(
                ServerMessage.Rejected(if (hello.password.isEmpty()) "This game needs a password" else "Wrong password", needsPassword = true),
                Channel.CONTROL,
            )
            disconnect(session)
            return
        }
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
            // Refused with a reason, so it doesn't look like a broken server.
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

        // The same player again: their old link is dead but not noticed yet (a dropped Wi-Fi link
        // can take minutes to time out), so it goes, and they come back to their craft at once.
        // Never the host's own.
        val returning = hello.clientId.take(64)
        for (old in sessions.filter { it !== session && it.clientId == returning && it.handshakeComplete }) {
            if (old.isHost) {
                session.send(ServerMessage.Rejected("You're already in this game"), Channel.CONTROL)
                disconnect(session)
                return
            }
            disconnect(old)
        }

        session.playerName = uniqueName(session, hello.playerName.trim().take(32).ifBlank { "Pilot" })
        session.clientId = hello.clientId.take(64)
        if (session.clientId.isBlank()) {
            // Every build on this protocol sends one. A blank id would share an anonymous craft
            // with every other such client, so it's refused.
            session.send(
                ServerMessage.Rejected("Client sent no identity"),
                Channel.CONTROL,
            )
            disconnect(session)
            return
        }
        // Their crew's stripe, kept with the world. A change shows on suits already out too.
        val stripe = hello.stripe.takeIf { it in 0 until com.rm.apogee.core.crew.Crew.STRIPES }
        if (world.stripes[session.clientId] != stripe) {
            if (stripe != null) world.stripes[session.clientId] = stripe else world.stripes.remove(session.clientId)
            for (suit in world.vessels.filter { it.owner == session.clientId && it.design.parts.singleOrNull()?.partId == World.SUIT_PART }) {
                broadcast(ServerMessage.StructureMessage(world.structureUpdateFor(suit)), Channel.STRUCTURE)
            }
        }
        session.handshakeComplete = true
        // Everything in the next snapshot, sleeping craft included.
        world.sendEverythingNext()

        // A returning player gets their craft back where they left it, unless it's a fresh flight.
        val fresh = config.freshFlight || (config.freshFlightFor != null && config.freshFlightFor == session.clientId)
        if (fresh) {
            world.lastFlown[session.clientId]?.let { last ->
                val previous = world.vessel(VesselId(last))
                if (previous != null && previous.owner == session.clientId) {
                    world.destroy(previous.id, "replaced by a new flight")
                }
            }
        }
        // Resuming one of theirs, or an unowned craft, which they then claim.
        val chosen = config.resumeVessel?.let { world.vessel(VesselId(it)) }
            ?.takeIf { it.owner == session.clientId || it.owner.isBlank() }
            ?.also { if (it.owner.isBlank()) { world.claim(it, session.clientId); it.ownerName = session.playerName } }
        val existing = chosen ?: if (fresh) null else world.vesselOwnedBy(session.clientId)
        // The owner label follows the player's name.
        existing?.ownerName = session.playerName
        // A career gets no starter craft.
        // One choosing their own craft gets none handed to them.
        val vessel = existing ?: if (config.assignCraftOnJoin && !hello.choosing && world.program == null) {
            // The nearest clear pad, so it never lands inside a craft already there.
            val site = World.launchSites.firstOrNull { it.id == config.starterSite } ?: World.launchSites.first()
            world.spawnAtSite(config.starterCraft(world.catalog), site, legsOut = true).also {
                world.assignOwner(it, session.clientId)
                it.ownerName = session.playerName
            }
        } else {
            null
        }
        session.controlledVessel = vessel?.id
        vessel?.let { world.lastFlown[session.clientId] = it.id.raw }

        // At the controls from the start, and someone arriving ends any warp or vote.
        session.lastInputTick = serverTick
        if (playerCount > 1 && (requestedWarp > 1.0 || pending != null)) {
            if (requestedWarp > 1.0) notices += "${session.playerName} joined, so warp stopped"
            setWarp(1.0, Double.NaN)
            pending = null
        }
        session.send(
            ServerMessage.Welcome(
                protocolVersion = Protocol.VERSION,
                catalogHash = world.catalog.contentHash,
                serverName = config.name,
                controlledVessel = vessel?.id?.raw ?: -1L,
                weather = world.weatherConfig,
                mode = if (world.program != null) WorldSave.MODE_CAREER else WorldSave.MODE_SANDBOX,
                host = session.isHost,
            ),
            Channel.CONTROL,
        )

        // What they can pick from, unless it's a career, where each player flies their own.
        if (hello.choosing && world.program == null) {
            val offer = runCatching { config.offeredCraft(world.catalog) }.getOrDefault(emptyList())
            for (design in offer) {
                session.send(ServerMessage.CraftOffered(design, offer.size, config.starterSite), Channel.STRUCTURE)
            }
        }

        // What's been knocked down, so their forest matches everyone else's.
        if (world.felledScatter.isNotEmpty()) {
            session.send(ServerMessage.ScatterFelled(world.felledScatter.toList()), Channel.STRUCTURE)
        }
        // What's been surveyed, and their crew.
        session.send(ServerMessage.Surveyed(world.surveyed.sorted()), Channel.STRUCTURE)
        session.rosterRevision = world.crewRevision
        session.send(ServerMessage.Roster(world.crewOf(session.clientId)), Channel.STRUCTURE)

        // Every craft's structure, or other players stay invisible until they change.
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
     * Runs [task] on the tick thread between steps, where the world is whole. A save from another
     * thread mid-step could record half a tick.
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
     * Whether [session] can fly [vessel]: the craft they're in, and when two players' craft are
     * docked, whoever they chose (anyone, if unset).
     */
    private fun flies(session: PlayerSession, vessel: Long): Boolean {
        if (session.controlledVessel?.raw != vessel) return false
        // Aboard someone else's craft, they're a passenger.
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
        is Command.SetLights -> flies(session, command.vessel)
        is Command.SetCruise -> flies(session, command.vessel)
        is Command.SetStationKeep -> flies(session, command.vessel)
        is Command.Hook -> flies(session, command.vessel) && sameTimeAround(command.vessel)
        is Command.Reel -> flies(session, command.vessel)
        is Command.ReleaseLine -> flies(session, command.vessel)
        is Command.Deploy -> flies(session, command.vessel)
        is Command.SetGear -> flies(session, command.vessel)
        // Only for your own crew, from the craft you're in.
        is Command.Eva -> session.controlledVessel?.raw == command.vessel && world.crew[command.crew]?.owner == session.clientId && sameTimeAround(command.vessel)
        is Command.TransferCrew -> session.controlledVessel?.raw == command.vessel && world.crew[command.crew]?.owner == session.clientId
        is Command.Board -> flies(session, command.vessel) && sameTimeAround(command.vessel)
        is Command.Jump -> flies(session, command.vessel)
        is Command.Grab -> flies(session, command.vessel) && sameTimeAround(command.vessel)
        is Command.PlantFlag -> flies(session, command.vessel)
        is Command.ClimbOut -> flies(session, command.vessel) && sameTimeAround(command.vessel)
        is Command.RightCraft -> flies(session, command.vessel)
        is Command.SetIndustry -> flies(session, command.vessel) || world.vessel(VesselId(command.vessel))?.let { it.anchored && it.owner == session.clientId } == true
        is Command.Unload -> flies(session, command.vessel) && sameTimeAround(command.vessel)
        is Command.SetBallast -> flies(session, command.vessel)
        is Command.HoldDepth -> flies(session, command.vessel)
        is Command.SetTranslation -> flies(session, command.vessel)
        is Command.SetRcs -> flies(session, command.vessel)
        is Command.Stage -> flies(session, command.vessel)
        is Command.Undock -> flies(session, command.vessel)
        // Either of the two it's shared between can say who flies it.
        is Command.SetDockPilot -> session.controlledVessel?.raw == command.vessel &&
            world.vessel(VesselId(command.vessel))?.let { session.clientId in world.ownersOf(it) } == true
        // Welding uses up the other craft, which may be someone else's. Only the flown craft can
        // start it, and the world also needs them touching and at rest.
        is Command.Join -> session.controlledVessel?.raw == command.vessel && sameTimeAround(command.vessel)
        is Command.Anchor -> world.vessel(VesselId(command.vessel))?.owner == session.clientId
        is Command.Refuel -> flies(session, command.vessel) && sameTimeAround(command.vessel)
        // Only your own craft, or one docked with yours.
        is Command.SwitchVessel ->
            world.vessel(VesselId(command.vessel))
                ?.let { session.clientId in world.ownersOf(it) } == true
        is Command.SpawnCraft -> true
        is Command.Chat -> true
        is Command.Kick -> session.isHost
        // Who may do what is worked out where they're handled; anyone may slow it.
        is Command.SetWarp -> warpAllowed
        is Command.WarpTo -> warpAllowed
        is Command.AnswerWarp -> warpAllowed
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

                // Staging changes live parts and the next stage, both in the structure message.
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
                    // Earned while away, told when they're back.
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

                // A failed part changes what the craft can do, which is in the structure message.
                is WorldEvent.PartFailed ->
                    world.vessel(event.id)?.let {
                        broadcast(
                            ServerMessage.StructureMessage(world.structureUpdateFor(it)),
                            Channel.STRUCTURE,
                        )
                    }

                is WorldEvent.LightningHit ->
                    broadcast(ServerMessage.Lightning(event.strikeId, event.id.raw, event.partIndex), Channel.STRUCTURE)

                // Break-ups reach clients as structure changes. These are for effects and sound.
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
                    // Whoever was in the absorbed craft is in the whole now.
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

    /** Sends a chat line from the server itself, for the dedicated server's admin channel. */
    suspend fun broadcastChat(from: String, text: String) =
        broadcast(ServerMessage.ChatMessage(from, text), Channel.CONTROL)

    /** Players the host sent out, by client id, kept out until the game ends. */
    private val kicked = HashSet<String>()

    /** Sends [clientId] out of the game, telling them and everyone. Never the host. */
    private suspend fun kick(clientId: String) {
        val target = sessions.firstOrNull { it.clientId == clientId && it.connected && !it.isHost } ?: return
        kicked += clientId
        target.send(ServerMessage.Rejected("The host has sent you out of this game"), Channel.CONTROL)
        disconnect(target)
        notices += "${target.playerName} was sent out of the game"
    }

    /** Everyone in the game, as the snapshot carries it. */
    private fun playerList(): List<com.rm.apogee.core.world.PlayerInfo> =
        sessions.filter { it.connected && it.handshakeComplete }.map { s ->
            com.rm.apogee.core.world.PlayerInfo(
                s.clientId, s.playerName,
                com.rm.apogee.core.crew.Crew.stripeFor(s.clientId, world.stripes[s.clientId]),
                s.isHost,
                s.controlledVessel?.let { id -> world.vessel(id) }?.let { world.leadOf(it) } ?: 0.0,
            )
        }

    /** [wanted], or with a number after it if someone else in the game already has it, so chat and tags tell them apart. */
    private fun uniqueName(session: PlayerSession, wanted: String): String {
        val taken = sessions.filter { it !== session && it.connected && it.handshakeComplete }.map { it.playerName.lowercase() }.toSet()
        if (wanted.lowercase() !in taken) return wanted
        var n = 2
        while ("$wanted $n".lowercase() in taken) n++
        return "$wanted $n"
    }

    /** Finds a connected player by name, for admin actions. */
    fun sessionNamed(playerName: String): PlayerSession? =
        sessions.firstOrNull {
            it.connected && it.handshakeComplete && it.playerName.equals(playerName, ignoreCase = true)
        }

    private suspend fun broadcastSnapshot() {
        if (sessions.isEmpty()) return
        // What the tick had to say about warp, as lines of chat from nobody.
        if (notices.isNotEmpty()) {
            val said = notices.toList()
            notices.clear()
            for (line in said) broadcast(ServerMessage.ChatMessage("", line), Channel.CONTROL)
        }
        val flown = sessions.mapNotNullTo(HashSet()) { it.controlledVessel?.raw }
        val snapshot = world.snapshot(quietEvery = QUIET_EVERY_TICKS, always = flown).copy(
            warp = effectiveWarp(),
            warpRequested = if (paused) 0.0 else requestedWarp,
            warpAllowed = warpAllowed,
            shared = playerCount > 1,
            warpHostOnly = config.warp == WarpPolicy.HOST,
            vote = voteState(),
            heldBy = if (requestedWarp > 1.0 && playerCount > 1) world.movingWarpLimiter()?.ownerName ?: "" else "",
            players = playerList(),
        )
        broadcast(ServerMessage.SnapshotMessage(snapshot), Channel.KINEMATICS)
    }

    /** Each pilot's own tanks, what their craft can do with a base, and the base nearest them. */
    private suspend fun sendFuel() {
        for (session in sessions) {
            if (!session.connected || !session.handshakeComplete) continue
            // Even with no craft, since one just lost is when it matters most.
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

    /** Who flies each two-player docked craft, by vessel id: a client id, or empty for either. */
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
        /** The longest chat line passed on, in characters. */
        const val MAX_CHAT_CHARS = 200

        /** How near another player's craft ends someone's bubble or keeps them out of one, in metres. */
        const val BUBBLE_NEAR = 100_000.0

        /** How near a craft in another time has to be to stop boarding, hooking and the like, in metres. */
        const val SAME_TIME_REACH = 2_000.0

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

/** The bytes of a packet that carries its message as it is. */
private val NO_BYTES = ByteArray(0)

/** Ticks between snapshots of a sleeping craft (two seconds), so a client that missed one sees it. */
private const val QUIET_EVERY_TICKS = 120L
