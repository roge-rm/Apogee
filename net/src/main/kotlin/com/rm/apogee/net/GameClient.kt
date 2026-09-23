package com.rm.apogee.net

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
import java.util.concurrent.ConcurrentHashMap

/** A vessel as the client knows it: structure, plus the last two motion states. */
class ClientVessel(
    val id: Long,
    @Volatile var design: CraftDesign,
    @Volatile var name: String,
    @Volatile var currentStage: Int = 0,
    @Volatile var activatedParts: List<Int> = emptyList(),
    /** Blank for debris and other players' craft are their own name. */
    @Volatile var owner: String = "",
) {
    /** The two most recent snapshots, kept so the renderer can interpolate. */
    @Volatile var previous: VesselKinematics? = null
        private set

    @Volatile var latest: VesselKinematics? = null
        private set

    /** [latest] and the universe time it describes, read together. */
    class Observation(val kinematics: VesselKinematics, val time: Double)

    /**
     * The latest state paired with its own snapshot's time, in one reference
     * so they cannot be read torn. The client's latest-snapshot time is set
     * before each craft's state is, and a frame built in between paired the
     * new time with the old state - 50 ms of error, nine metres of a parked
     * craft carried along the equator, for a frame.
     */
    @Volatile var observed: Observation? = null
        private set

    fun observe(kinematics: VesselKinematics, time: Double = 0.0) {
        previous = latest
        latest = kinematics
        observed = Observation(kinematics, time)
    }
}

/**
 * The client's view of a server's world.
 *
 * Holds structure and motion separately, matching how they arrive: a craft's
 * part list is pushed when it changes, its motion streams continuously. The two
 * most recent motion samples are retained because the server sends 20 per
 * second and the display wants 60 or more - without something to interpolate
 * between, a perfectly smooth simulation renders as a stutter.
 *
 * Client-side prediction of the locally controlled craft is M4 work. For now
 * even the local player watches interpolated server state, which is honest
 * about the latency the networked build will have rather than hiding it behind
 * a code path that only exists in single-player.
 */
class GameClient(
    private val transport: Transport,
    val playerName: String,
    private val catalogHash: String,
    /**
     * This install's identity. Opaque and never typed by the player; the
     * server hangs craft ownership off it rather than off [playerName].
     */
    val clientId: String,
    /** The ground this build simulates. Only a test would pass anything else. */
    private val terrainGeneration: Int = com.rm.apogee.core.terrain.TerrainField.GENERATION,
) {
    private val vesselsById = ConcurrentHashMap<Long, ClientVessel>()

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

    /**
     * When the newest snapshot arrived, by [System.nanoTime].
     *
     * Prediction needs to know how stale the server's state is, not just what
     * it said - a snapshot describes the world as of when it was sent.
     */
    @Volatile var latestSnapshotNanos: Long = 0L
        private set

    /** Chat lines, newest last. Bounded so a long session cannot grow forever. */
    private val chatLines = ArrayDeque<String>()

    val vessels: Collection<ClientVessel> get() = vesselsById.values

    fun vessel(id: Long): ClientVessel? = vesselsById[id]

    fun chatHistory(): List<String> = synchronized(chatLines) { chatLines.toList() }

    /** Starts reading from the transport and sends the handshake. */
    fun connect(scope: CoroutineScope): Job = scope.launch(Dispatchers.Default) {
        val reader = launch {
            transport.incoming.collect { packet ->
                val message = runCatching { Codec.decodeServerMessage(packet.bytes) }.getOrNull()
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
                    )
                ),
            )
        )
        reader.join()
    }

    suspend fun send(command: Command) {
        transport.send(
            Packet(Channel.CONTROL, Codec.encode(ClientMessage.CommandMessage(command)))
        )
    }

    fun close() {
        connected = false
        transport.close()
    }

    /**
     * Scatter the server says has been knocked down. Shared with the
     * prediction replica, so the local craft does not collide with a tree
     * that is already down, and read by the renderer to leave it out.
     */
    val felledScatter: MutableSet<Long> = java.util.concurrent.ConcurrentHashMap.newKeySet()

    /** Bumped whenever [felledScatter] grows, so a renderer can tell cheaply. */
    @Volatile var felledRevision: Int = 0
        private set

    private fun handle(message: ServerMessage) {
        when (message) {
            is ServerMessage.Welcome -> {
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
                        )
                    } else {
                        existing.design = design
                        existing.owner = update.owner
                        existing.name = update.name
                        existing.currentStage = update.currentStage
                        existing.activatedParts = update.activatedParts
                    }
                }
            }

            is ServerMessage.SnapshotMessage -> {
                val snapshot = message.snapshot
                latestSnapshot = snapshot
                latestSnapshotNanos = System.nanoTime()
                for (kinematics in snapshot.vessels) {
                    // Motion can legitimately arrive before structure - the
                    // snapshot for a craft that just spawned may overtake its
                    // structure message. Dropping it is correct; the next
                    // snapshot after the structure lands will carry it again.
                    vesselsById[kinematics.vessel]?.observe(kinematics, snapshot.time)
                }
            }

            is ServerMessage.ScatterFelled -> {
                felledScatter.addAll(message.ids)
                felledRevision++
            }

            is ServerMessage.ChatMessage -> synchronized(chatLines) {
                chatLines.addLast("${message.from}: ${message.text}")
                while (chatLines.size > MAX_CHAT_LINES) chatLines.removeFirst()
            }
        }
    }

    private companion object {
        const val MAX_CHAT_LINES = 100
    }
}
