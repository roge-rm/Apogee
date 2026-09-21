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
) {
    /** The two most recent snapshots, kept so the renderer can interpolate. */
    @Volatile var previous: VesselKinematics? = null
        private set

    @Volatile var latest: VesselKinematics? = null
        private set

    fun observe(kinematics: VesselKinematics) {
        previous = latest
        latest = kinematics
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
    private val playerName: String,
    private val catalogHash: String,
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
                Codec.encode(ClientMessage.Hello(Protocol.VERSION, catalogHash, playerName)),
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
                        )
                    } else {
                        existing.design = design
                        existing.name = update.name
                        existing.currentStage = update.currentStage
                        existing.activatedParts = update.activatedParts
                    }
                }
            }

            is ServerMessage.SnapshotMessage -> {
                val snapshot = message.snapshot
                latestSnapshot = snapshot
                for (kinematics in snapshot.vessels) {
                    // Motion can legitimately arrive before structure - the
                    // snapshot for a craft that just spawned may overtake its
                    // structure message. Dropping it is correct; the next
                    // snapshot after the structure lands will carry it again.
                    vesselsById[kinematics.vessel]?.observe(kinematics)
                }
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
