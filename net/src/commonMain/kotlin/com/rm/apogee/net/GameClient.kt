package com.rm.apogee.net

import kotlin.concurrent.Volatile
import com.rm.apogee.core.craft.CraftDesign
import com.rm.apogee.core.world.ClientMessage
import com.rm.apogee.core.world.Command
import com.rm.apogee.core.world.Protocol
import com.rm.apogee.core.world.ServerMessage
import com.rm.apogee.core.world.Snapshot
import com.rm.apogee.core.world.VesselKinematics
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

/** A vessel as the client knows it: its structure, plus the last two motion states. */
class ClientVessel(
    val id: Long,
    @Volatile var design: CraftDesign,
    @Volatile var name: String,
    @Volatile var currentStage: Int = 0,
    @Volatile var activatedParts: List<Int> = emptyList(),
    /** Tanks from [ServerMessage.FuelLevels]. Null until sent; only the flown craft has them. */
    @Volatile var fuel: List<Float>? = null,

    /** The owner's name. Blank for debris. */
    @Volatile var owner: String = "",
    /** Founded: pinned to the ground and can't be moved. */
    @Volatile var anchored: Boolean = false,
    /** Burns planned for it, soonest first, as the server has them. */
    @Volatile var burns: List<com.rm.apogee.core.world.PlannedBurn> = emptyList(),
    /** Who sits in each part, by crew id. Empty with nobody aboard. */
    @Volatile var crew: List<List<Long>> = emptyList(),
    /** Someone in a suit: its stripe and visor, as [com.rm.apogee.core.crew.Crew] numbers them, or -1. */
    @Volatile var stripe: Int = -1,
    @Volatile var visor: Int = -1,
) {
    /** The two most recent snapshots, for the renderer to interpolate. */
    @Volatile var previous: VesselKinematics? = null
        private set

    @Volatile var latest: VesselKinematics? = null
        private set

    /** [latest] and the universe time it describes, read together. */
    class Observation(val kinematics: VesselKinematics, val time: Double)

    /**
     * The latest state with its own snapshot's time, in one reference so they can't be read half
     * updated. The client's snapshot time changes before each craft's state does.
     */
    @Volatile var observed: Observation? = null
        private set

    /** The observation before [observed], the other end to draw between under warp. */
    @Volatile var previousObserved: Observation? = null
        private set

    /**
     * The last few observations, oldest first, replaced as a whole so a frame reads one consistent
     * list. Under warp the frame is drawn about a snapshot and a half behind the newest, so two
     * aren't enough.
     */
    @Volatile var recent: List<Observation> = emptyList()
        private set

    fun observe(kinematics: VesselKinematics, time: Double = 0.0) {
        previous = latest
        latest = kinematics
        previousObserved = observed
        val next = Observation(kinematics, time)
        observed = next
        val kept = recent
        recent = if (kept.size < RECENT) kept + next else kept.subList(kept.size - RECENT + 1, kept.size) + next
    }

    /**
     * The two kept observations either side of [time], older first. The older one is null when
     * [time] is before everything kept, and the newer one is the newest when [time] is past it.
     * Null when nothing has been seen.
     */
    fun around(time: Double): Pair<Observation?, Observation>? {
        val kept = recent
        if (kept.isEmpty()) return observed?.let { null to it }
        var k = kept.size - 1
        while (k > 0 && kept[k - 1].time > time) k--
        return (if (k > 0) kept[k - 1] else null) to kept[k]
    }

    private companion object {
        /** Enough to cover the warp clock's lag and a late snapshot. */
        const val RECENT = 5
    }
}

/**
 * The client's view of a server's world. Structure and motion are held apart, as they arrive: parts
 * when they change, motion at 20 Hz. Recent motion is kept so a 60 Hz display can interpolate.
 */
class GameClient(
    private val transport: Transport,
    val playerName: String,
    private val catalogHash: String,
    /** This install's opaque identity. The server ties craft ownership to it, not [playerName]. */
    val clientId: String,
    /** The ground this build simulates. Only a test would pass anything else. */
    private val terrainGeneration: Int = com.rm.apogee.core.terrain.TerrainField.GENERATION,
    /** The worlds this build flies among. Only a test would pass anything else. */
    private val systemHash: String = com.rm.apogee.core.orbit.SolarSystem.DEFAULT_HASH,
    /** The stripe this player's crew wear, or -1 to leave it to the server. */
    private val stripe: Int = -1,
) {
    private val vesselsById = com.rm.apogee.core.concurrentMapOf<Long, ClientVessel>()

    @Volatile var connected: Boolean = false
        private set

    @Volatile var rejectionReason: String? = null
        private set

    @Volatile var serverName: String = ""
        private set

    /** The vessel this client is allowed to fly, or null before the welcome. */
    @Volatile var controlledVessel: Long? = null
        private set

    @Volatile var latestSnapshot: Snapshot? = null
        private set

    /** Newest snapshot's arrival, by [com.rm.apogee.core.nanoTime]. Prediction needs its age. */
    @Volatile var latestSnapshotNanos: Long = 0L
        private set

    /** Chat lines, newest last, capped. */
    private val chatLines = ArrayDeque<String>()

    val vessels: Collection<ClientVessel> get() = vesselsById.values

    /** What the flown craft can do with a base right now, as the server last said. */
    @Volatile var service: ServerMessage.Service? = null
        private set

    /** The flown craft's power and link home, as the server last said. */
    @Volatile var systems: ServerMessage.CraftSystems? = null
        private set

    /** This player's crew, as the server last said. */
    @Volatile var roster: List<com.rm.apogee.core.crew.CrewMember> = emptyList()
        private set

    /** This player's career, and the world's firsts. Null in a sandbox. */
    @Volatile var career: com.rm.apogee.core.career.CareerState? = null
        private set
    @Volatile var firsts: List<com.rm.apogee.core.career.WorldFirst> = emptyList()
        private set

    /** The sea's named places this player has found, in a career or in free play. */
    @Volatile var wondersFound: Set<String> = emptySet()
        private set

    /** Which kind of world this is: [com.rm.apogee.core.world.WorldSave.MODE_CAREER] or sandbox. */
    @Volatile var mode: String = com.rm.apogee.core.world.WorldSave.MODE_SANDBOX
        private set

    /** Feats just earned, and career refusals, for the HUD to show once each. */
    val feats: com.rm.apogee.core.ConcurrentQueue<ServerMessage.Feat> = com.rm.apogee.core.ConcurrentQueue()
    val refusals: com.rm.apogee.core.ConcurrentQueue<String> = com.rm.apogee.core.ConcurrentQueue()

    /** Bodies surveyed for ore and water, so their richness is on the map. */
    @Volatile var surveyed: Set<String> = emptySet()
        private set

    /** The founded base nearest the flown craft, as the server last said. Null once it stops saying. */
    val nearestBase: ServerMessage.BaseStatus?
        get() = nearBase?.takeIf { com.rm.apogee.core.nanoTime() - nearBaseNanos < BASE_STALE_NANOS }

    @Volatile private var nearBase: ServerMessage.BaseStatus? = null
    @Volatile private var nearBaseNanos = 0L

    fun vessel(id: Long): ClientVessel? = vesselsById[id]

    fun chatHistory(): List<String> = com.rm.apogee.core.guarded(chatLines) { chatLines.toList() }

    /** Starts reading from the transport and sends the handshake. */
    fun connect(scope: CoroutineScope): Job = scope.launch(Dispatchers.Default) {
        val reader = launch {
            transport.incoming.collect { packet ->
                val message = packet.message as? ServerMessage ?: runCatching { Codec.decodeServerMessage(packet.bytes) }.getOrNull()
                if (message != null) handle(message)
            }
        }
        transport.send(
            Packet(
                Channel.CONTROL,
                Codec.encode(
                    ClientMessage.Hello(
                        protocolVersion = Protocol.VERSION,
                        catalogHash = catalogHash,
                        playerName = playerName,
                        clientId = clientId,
                        terrainGeneration = terrainGeneration,
                        systemHash = systemHash,
                        stripe = stripe,
                    )
                ),
            )
        )
        reader.join()
    }

    suspend fun send(command: Command) {
        sendMessage(ClientMessage.CommandMessage(command))
    }

    /** [message] to the server, encoded unless it's in this process. */
    private suspend fun sendMessage(message: ClientMessage) {
        transport.send(if (transport.passesObjects) Packet(Channel.CONTROL, NO_BYTES, message) else Packet(Channel.CONTROL, Codec.encode(message)))
    }

    fun close() {
        connected = false
        transport.close()
    }

    /**
     * Scatter the server says is knocked down. Shared with the prediction replica so you don't hit
     * a felled tree, and read by the renderer to leave it out.
     */
    val felledScatter: MutableSet<Long> = com.rm.apogee.core.concurrentSetOf()

    /** Goes up whenever [felledScatter] grows, so a renderer can tell cheaply. */
    @Volatile var felledRevision: Int = 0
        private set

    /** The world's weather, from the welcome. Null until then, or for still air. */
    @Volatile var weather: com.rm.apogee.core.weather.WeatherConfig? = null
        private set

    /** Blows, breakages and blasts, for the presentation to show and play sounds for. */
    val partEvents: com.rm.apogee.core.ConcurrentQueue<ServerMessage.PartEvent> =
        com.rm.apogee.core.ConcurrentQueue()

    /** Lightning that hit something, for the presentation to flash and report. */
    val lightningHits: com.rm.apogee.core.ConcurrentQueue<ServerMessage.Lightning> =
        com.rm.apogee.core.ConcurrentQueue()

    /**
     * The craft you're flying, shared with another player: who they are, and who flies it (a client
     * id, or empty for either). Null when it isn't shared.
     */
    @Volatile var dockedWith: ServerMessage.DockedWith? = null

    private fun handle(message: ServerMessage) {
        when (message) {
            is ServerMessage.DockedWith -> dockedWith = message

            is ServerMessage.Welcome -> {
                weather = message.weather
                mode = message.mode
                serverName = message.serverName
                controlledVessel = message.controlledVessel.takeIf { it >= 0 }
                connected = true
            }

            is ServerMessage.Rejected -> {
                rejectionReason = message.reason
                connected = false
            }

            // Control moved: a launch, or a switch to a craft already parked.
            is ServerMessage.ControlChanged -> {
                controlledVessel = message.vessel.takeIf { it >= 0 }
            }

            is ServerMessage.StructureMessage -> {
                val update = message.update
                val design = update.design
                if (design == null) {
                    vesselsById.remove(update.vessel)
                } else {
                    val existing = vesselsById[update.vessel]
                    if (existing == null) {
                        vesselsById[update.vessel] = ClientVessel(
                            id = update.vessel,
                            design = design,
                            name = update.name,
                            currentStage = update.currentStage,
                            activatedParts = update.activatedParts,
                            owner = update.owner,
                            anchored = update.anchored,
                            burns = update.burns,
                            crew = update.crew,
                            stripe = update.stripe,
                            visor = update.visor,
                        )
                    } else {
                        existing.design = design
                        existing.owner = update.owner
                        existing.name = update.name
                        existing.currentStage = update.currentStage
                        existing.activatedParts = update.activatedParts
                        existing.anchored = update.anchored
                        existing.burns = update.burns
                        existing.crew = update.crew
                        existing.stripe = update.stripe
                        existing.visor = update.visor
                    }
                }
            }

            is ServerMessage.SnapshotMessage -> {
                val snapshot = message.snapshot
                latestSnapshot = snapshot
                latestSnapshotNanos = com.rm.apogee.core.nanoTime()
                for (kinematics in snapshot.vessels) {
                    // A new craft's snapshot can beat its structure here. Drop it; the next one
                    // carries it again.
                    vesselsById[kinematics.vessel]?.observe(kinematics, snapshot.time)
                }
            }

            is ServerMessage.Lightning -> {
                lightningHits.add(message)
            }

            is ServerMessage.PartEvent -> {
                partEvents.add(message)
                // Capped in case nobody drains it (a headless client or a test).
                while (partEvents.size > MAX_PART_EVENTS) partEvents.poll()
            }

            is ServerMessage.FuelLevels -> {
                vesselsById[message.vessel]?.fuel = message.amounts
            }

            is ServerMessage.Service -> service = message

            is ServerMessage.CraftSystems -> systems = message

            is ServerMessage.Surveyed -> surveyed = message.bodies.toSet()

            is ServerMessage.Roster -> roster = message.members
            is ServerMessage.Career -> {
                career = message.state
                firsts = message.firsts
            }
            is ServerMessage.WondersFound -> wondersFound = message.ids.toSet()
            is ServerMessage.Feat -> feats.add(message)
            is ServerMessage.CareerRefused -> refusals.add(message.reason)

            is ServerMessage.BaseStatus -> {
                nearBase = message
                nearBaseNanos = com.rm.apogee.core.nanoTime()
            }

            is ServerMessage.ScatterFelled -> {
                felledScatter.addAll(message.ids)
                felledRevision++
            }

            is ServerMessage.ChatMessage -> com.rm.apogee.core.guarded(chatLines) {
                chatLines.addLast("${message.from}: ${message.text}")
                while (chatLines.size > MAX_CHAT_LINES) chatLines.removeFirst()
            }
        }
    }

    private companion object {
        const val MAX_CHAT_LINES = 100
        const val MAX_PART_EVENTS = 256

        /** A base the server hasn't mentioned for this long is out of reach. */
        const val BASE_STALE_NANOS = 3_000_000_000L
    }
}

/** The bytes of a packet that carries its message as it is. */
private val NO_BYTES = ByteArray(0)
