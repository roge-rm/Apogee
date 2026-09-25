package com.rm.apogee.game

import com.rm.apogee.core.craft.CraftDesign
import com.rm.apogee.core.craft.CraftOrientation
import com.rm.apogee.core.craft.StockCraft
import com.rm.apogee.core.math.Quat
import com.rm.apogee.core.math.Vec3
import com.rm.apogee.core.orbit.CelestialBody
import com.rm.apogee.core.orbit.Orbit
import com.rm.apogee.core.orbit.SolarSystem
import com.rm.apogee.core.part.PartCatalog
import com.rm.apogee.core.part.StockParts
import com.rm.apogee.core.world.Command
import com.rm.apogee.core.world.World
import com.rm.apogee.core.world.VesselKinematics
import com.rm.apogee.audio.AudioEngine
import com.rm.apogee.audio.SoundScene
import com.rm.apogee.audio.Recipes
import com.rm.apogee.audio.Materials
import com.rm.apogee.audio.VoiceFlags
import com.rm.apogee.core.world.PartEventKind
import com.rm.apogee.core.world.ServerMessage
import kotlin.math.roundToInt
import com.rm.apogee.core.world.VesselCondition
import com.rm.apogee.core.world.VesselPose
import com.rm.apogee.net.ClientPrediction
import com.rm.apogee.net.ClientVessel
import com.rm.apogee.net.GameClient
import com.rm.apogee.core.world.Protocol
import com.rm.apogee.net.LanDiscovery
import com.rm.apogee.net.LoopbackTransportPair
import com.rm.apogee.net.ServerBeacon
import com.rm.apogee.net.TcpListener
import com.rm.apogee.net.TcpTransport
import com.rm.apogee.net.Transport
import com.rm.apogee.platform.PerfHints
import com.rm.apogee.render.FrameBus
import com.rm.apogee.render.RenderFrame
import com.rm.apogee.render.PartAnim
import com.rm.apogee.render.PartModels
import com.rm.apogee.render.ConditionLook
import com.rm.apogee.render.RenderItem
import com.rm.apogee.render.StackCaps
import com.rm.apogee.render.QualityTier
import com.rm.apogee.render.RenderLine
import com.rm.apogee.render.WorldView
import com.rm.apogee.server.GameServer
import com.rm.apogee.server.ServerConfig
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.util.concurrent.atomic.AtomicLong

/**
 * One play session: a server, a client, and the bridge to the renderer.
 *
 * Single-player is a one-player server. The client here talks over a
 * [LoopbackTransportPair], but through exactly the same [GameClient] and
 * protocol a networked client will use - so the multiplayer path is exercised
 * every time anyone plays solo, rather than only when two devices are in the
 * room. Joining a remote host is the same code with a socket transport in
 * place of the loopback.
 */
class GameSession private constructor(
    private val frameBus: FrameBus,
    private val perfHints: PerfHints?,
    private val catalog: PartCatalog,
    /** Null when joined to someone else's game. */
    private val hostedServer: GameServer?,
    private val client: GameClient,
    private val transport: Transport,
    /**
     * A craft to put on the pad once connected, or null to fly what is
     * already there.
     *
     * Sent as a command rather than handed to the server as its "starter
     * craft", because in a persistent world the player usually already owns
     * something - the starter is only for an empty world, and a launch has to
     * work either way.
     */
    private val launchDesign: CraftDesign? = null,
    /** Where to put [launchDesign], or null to let the design decide. */
    private val launchSiteId: String? = null,
) {
    val camera = CameraController()

    /**
     * A second camera for map view, with its own distance range.
     *
     * Separate rather than a mode flag on one camera, because the two want
     * completely different things: the flight camera frames a 14 m rocket from
     * tens of metres, the map camera frames a 700 km orbit from thousands of
     * kilometres. Sharing one would mean a zoom range spanning six orders of
     * magnitude, and the player's pinch would be useless at both ends.
     */
    val mapCamera = CameraController(
        upReference = UpReference.FIXED,
        minDistance = 1.0e6,
        maxDistance = 6.0e7,
    )

    /** Whether the map view is showing. */
    @Volatile var mapMode: Boolean = false

    /**
     * Local physics for the craft this client is flying.
     *
     * The server stays authoritative - every snapshot resets this and
     * re-simulates forward. It exists so the throttle responds on the frame it
     * is moved rather than after a round trip and up to a snapshot interval.
     */
    private val prediction = ClientPrediction(catalog)

    /** Mirrors of the player's controls, so prediction sees the same inputs. */
    private var localThrottle = 0.0
    private var localPitch = 0.0
    private var localYaw = 0.0
    private var localRoll = 0.0
    private var localSas = false
    private var localSasMode = com.rm.apogee.core.world.SasMode.HOLD
    private var localNavFrame = com.rm.apogee.core.world.NavFrame.AUTO
    private var localTarget = -1L
    private var localBrakes = false
    private var localReverse = false
    /** Thrusters armed, and the slide asked for: the stick's right/away and the buttons' down/up. */
    private var localRcs = false
    @Volatile private var slideRight = 0.0
    @Volatile private var slideAway = 0.0
    @Volatile private var slideLift = 0.0
    /** The slide as last told to the craft, in its own axes, and when. */
    private val localTranslate = Vec3()
    private var translateSentNanos = 0L
    private var rcsFor = -1L
    private var seenFelledRevision = -1

    private var lastReconciledTick = -1L

    /** The server's time, followed smoothly: see [ServerClock]. */
    private val serverClock = ServerClock()
    private var clockSampledTick = -1L
    private var lastAdvanceNanos = 0L
    /** The server's time as followed, when the replica was last advanced; NaN for none. */
    private var lastPresent = Double.NaN

    /** Published for the debug overlay. */
    val lastFrameBuildNanos = AtomicLong(0)
    val framesPublished = AtomicLong(0)

    /** Telemetry for the HUD, refreshed each published frame. */
    @Volatile var telemetry: FlightTelemetry = FlightTelemetry.EMPTY
        private set

    private var serverJob: Job? = null
    private var clientJob: Job? = null
    private var presentJob: Job? = null
    private var listener: TcpListener? = null
    private var beaconJob: Job? = null

    /** Port this session is hosting on, or null when not hosting. */
    var hostedPort: Int? = null
        private set

    /** Players connected to the game this session is hosting. */
    val hostedPlayerCount: Int get() = hostedServer?.playerCount ?: 0

    /** Runs [task] on the hosted server's tick thread, between steps; not at all when joined to someone else's. */
    fun betweenTicks(task: () -> Unit): Boolean {
        val server = hostedServer ?: return false
        server.runBetweenTicks(task)
        return true
    }

    private val cameraPosition = Vec3()
    private val cameraRotation = Quat.identity()
    private val scratch = Vec3()
    private val predictedPosition = Vec3()
    private val predictedRotation = Quat.identity()
    private val bodyRotation = Quat.identity()
    private val scratchCameraBodyFixed = Vec3()
    private val scratchGroundVelocity = Vec3()
    private val scratchWheelSpin = Vec3()
    private val leaves = ArrayList<PartModels.Leaf>()

    private val bodyFixedCamera = Vec3()

    /**
     * Builds terrain geometry from the simulation's own height field.
     *
     * Created once a quality tier is known, because how finely to sample is
     * the one thing about terrain that depends on the device.
     */
    private var terrainBuilder: TerrainBuilder? = null
    private var drawFarSurface = true
    private var chunkRange = 0.0

    private var lastRenderTime = 0.0

    /** How far a design reaches from its centre of mass, m, cached per design. */
    private val radii = java.util.IdentityHashMap<CraftDesign, Double>()

    private fun designRadius(design: CraftDesign, centreOfMass: Vec3): Double = radii.getOrPut(design) {
        design.parts.maxOfOrNull { it.position.distanceTo(centreOfMass) + 0.8 } ?: 1.0
    }

    /** Smoke, dust, spray, flames, rain and lightning. */
    private var effects: Effects? = null
    private val frameEmitters = ArrayList<EngineEmitter>()

    /** Clouds and the camera's air, while the world has weather and the camera is in it. */
    private var cloudScene: CloudScene? = null
    private val cloudCamera = Vec3()

    /**
     * The solar system, built once.
     *
     * It was being reconstructed on every frame - three bodies, their orbits
     * and their terrain fields, sixty times a second, thrown away each time.
     * Nothing about it changes.
     */
    private val system = SolarSystem.defaultSystem()

    val connected: Boolean get() = client.connected
    val rejectionReason: String? get() = client.rejectionReason

    /** Scope for terrain builds, so they can outlive a single frame. */
    private lateinit var terrainScope: CoroutineScope

    /**
     * Supplies the renderer's terrain hand-off and the device's quality tier.
     *
     * Called once the GL thread has judged the device; until then there is no
     * sensible answer to how finely to sample.
     */
    private var scatterStreamer: ScatterStreamer? = null

    fun attachTerrain(source: com.rm.apogee.render.TerrainSource, quality: QualityTier) {
        // Called again whenever the GL surface is recreated. The builder
        // already knows the GPU lost its chunks - the renderer says so - and
        // rebuilds just those; replacing it threw away everything built and
        // left the old one's workers running, publishing draw lists of their
        // own into the same source.
        if (terrainBuilder != null && terrainQuality == quality) return
        terrainBuilder?.let {
            android.util.Log.i("ApogeeTerrain", "builder replaced: $terrainQuality -> $quality")
            it.stop()
            // What the old builder left in the source the new one would
            // never know it had - and wait on for ever behind the loading
            // screen (a tier detected unlike the one remembered did it).
            source.releaseAllChunks()
        }
        terrainQuality = quality
        terrainBuilder = TerrainBuilder(source, quality)
        scatterStreamer = ScatterStreamer(source.scatter, quality)
    }

    private var terrainQuality: QualityTier? = null

    /**
     * Whether the world is fit to be shown - the craft has ground under it.
     *
     * False until the first terrain patch is built, which is why the flight
     * view holds back rather than showing a craft suspended over a globe that
     * has not caught up with it yet.
     */
    val surfaceReady: Boolean
        get() = terrainBuilder?.patchReady ?: false

    fun start(scope: CoroutineScope) {
        terrainScope = scope
        serverJob = hostedServer?.start(scope)
        clientJob = client.connect(scope)
        // Once the handshake lands, put the launched craft on the pad. Waiting
        // for `connected` rather than sending immediately, because the server
        // refuses anything before the handshake completes.
        scope.launch(Dispatchers.Default) {
            while (isActive && !client.connected && client.rejectionReason == null) {
                delay(16)
            }
            if (client.connected) launchPendingDesign()
        }
        presentJob = scope.launch(Dispatchers.Default) {
            while (isActive) {
                val started = System.nanoTime()
                buildFrame(started)
                lastFrameBuildNanos.set(System.nanoTime() - started)
                perfHints?.reportActualWorkDuration(lastFrameBuildNanos.get())
                delay(PRESENT_INTERVAL_MILLIS)
            }
        }
    }

    fun stop() {
        presentJob?.cancel(); presentJob = null
        // Everything held lets go - once the frames that publish scenes have stopped.
        AudioEngine.scene(0, IntArray(0), IntArray(0), IntArray(0), FloatArray(0))
        clientJob?.cancel(); clientJob = null
        serverJob?.cancel(); serverJob = null
        beaconJob?.cancel(); beaconJob = null
        listener?.stop(); listener = null
        hostedPort = null
        client.close()
        transport.close()
        prediction.reset()
        terrainBuilder?.stop()
        scatterStreamer?.stop()
        terrainBuilder = null
        frameBus.clear()
    }

    /**
     * Opens this session's server to the network and announces it.
     *
     * Only meaningful when hosting. The host's own client stays on the loopback
     * transport rather than looping back through a socket: there is no reason
     * to serialise and checksum its own commands, and keeping it in-process
     * means a host with no network still plays.
     */
    private fun openToLan(server: GameServer, scope: CoroutineScope, serverName: String) {
        val tcp = TcpListener(DEFAULT_PORT) { transport -> server.accept(transport, scope) }
        tcp.start(scope)
        listener = tcp
        hostedPort = tcp.boundPort

        beaconJob = LanDiscovery.announce(
            ServerBeacon(
                serverName = serverName,
                port = tcp.boundPort,
                players = server.playerCount,
                protocolVersion = Protocol.VERSION,
                catalogHash = catalog.contentHash,
            ),
            scope,
        )
    }

    // --- commands ------------------------------------------------------------

    private suspend fun withControlledVessel(block: suspend (Long) -> Unit) {
        client.controlledVessel?.let { block(it) }
    }

    /** How the world's clock is running, as the last snapshot said. */
    val clock: com.rm.apogee.core.world.Snapshot? get() = client.latestSnapshot

    /** Asks for the world to run at [rate] times real time; 0 pauses it. */
    suspend fun setWarp(rate: Double) {
        client.send(Command.SetWarp(rate))
    }

    /** Takes craft [id] out of the world for good. */
    suspend fun removeCraft(id: Long) {
        client.send(Command.RemoveVessel(id))
    }

    suspend fun setThrottle(value: Double) {
        localThrottle = value
        pushControlsToPrediction()
        withControlledVessel { client.send(Command.SetThrottle(it, value)) }
    }

    suspend fun setAttitude(pitch: Double, yaw: Double, roll: Double) {
        localPitch = pitch; localYaw = yaw; localRoll = roll
        pushControlsToPrediction()
        withControlledVessel { client.send(Command.SetAttitude(it, pitch, yaw, roll)) }
    }

    /** How the craft being flown was built, or null with none in hand yet. */
    val controlledOrientation: CraftOrientation?
        get() {
            val id = client.controlledVessel ?: return null
            return client.vessels.firstOrNull { it.id == id }?.design?.orientation
        }

    suspend fun setReverse(engaged: Boolean) {
        localReverse = engaged
        pushControlsToPrediction()
        withControlledVessel { client.send(Command.SetReverse(it, engaged)) }
    }

    suspend fun setBrakes(engaged: Boolean) {
        localBrakes = engaged
        pushControlsToPrediction()
        withControlledVessel { client.send(Command.SetBrakes(it, engaged)) }
    }

    /**
     * Arms the thrusters, or stands them down (and stops any slide). Takes
     * effect here at once - the button shows it on the next frame - and is
     * sent on its way.
     */
    fun setRcs(armed: Boolean) {
        localRcs = armed
        if (!armed) { slideRight = 0.0; slideAway = 0.0; slideLift = 0.0; localTranslate.setZero() }
        pushControlsToPrediction()
        terrainScope.launch {
            withControlledVessel {
                client.send(Command.SetRcs(it, armed))
                if (!armed) client.send(Command.SetTranslation(it, 0.0, 0.0, 0.0))
            }
        }
    }

    /**
     * What the player's thumbs ask the thrusters to slide: [right] and [away]
     * from the camera, [lift] up from the planet, each -1..1. Turned into the
     * craft's axes every frame, as the camera and the craft turn.
     */
    fun setSlide(right: Double, away: Double, lift: Double) {
        slideRight = right; slideAway = away; slideLift = lift
    }

    /** Whether the thrusters of the craft being flown are armed. */
    val rcsArmed: Boolean get() = localRcs

    /** Whether the craft being flown has thruster blocks. */
    val controlledHasRcs: Boolean
        get() {
            val id = client.controlledVessel ?: return false
            val design = client.vessels.firstOrNull { it.id == id }?.design ?: return false
            return design.parts.any { catalog[it.partId]?.hasModule<com.rm.apogee.core.part.Rcs>() == true }
        }

    /** Monopropellant left in the craft being flown, 0..1, or null for none carried. */
    val rcsLeft: Float?
        get() {
            val vessel = prediction.replica ?: return null
            val capacity = vessel.capacityOf(com.rm.apogee.core.part.ResourceType.MONOPROPELLANT)
            if (capacity <= 0.0) return null
            return (vessel.amountOf(com.rm.apogee.core.part.ResourceType.MONOPROPELLANT) / capacity).toFloat().coerceIn(0f, 1f)
        }

    /**
     * Keeps the thrusters' slide pointing where the thumbs mean as the camera
     * and craft turn: worked out each frame, sent when it has moved enough,
     * at most ten times a second - and at once when it stops.
     */
    private fun updateSlide(focusId: Long, craftRotation: Quat, focusPosition: Vec3) {
        if (rcsFor != focusId) {
            // Another craft in hand: its thrusters start stood down - on the
            // server too, where it may have been left armed.
            rcsFor = focusId
            localRcs = false
            slideRight = 0.0; slideAway = 0.0; slideLift = 0.0
            localTranslate.setZero()
            terrainScope.launch {
                client.send(Command.SetRcs(focusId, false))
                client.send(Command.SetTranslation(focusId, 0.0, 0.0, 0.0))
            }
            return
        }
        if (!localRcs) return
        val up = focusPosition.normalized()
        // Fine near the middle of the stick, full at its edge: cubed. Linear,
        // the lightest touch was metres a second in orbit - the thrusters
        // are sized to walk a landed module, not to dock.
        fun fine(x: Double) = x * x * x
        val grounded = telemetry.heightAboveGround < SLIDE_GROUNDED_BELOW
        val wanted = SlideControl.command(cameraRotation, craftRotation, up, fine(slideRight), fine(slideAway), fine(slideLift), Vec3(), grounded)
        // Lining up to dock, hands off: the thrusters take out drift across
        // the line to the other port, and leave the closing speed alone - a
        // nudge toward it, let go, and it coasts in on the line.
        val readout = dockReadout
        if (!grounded && wanted.lengthSq < 1e-12 && readout != null && readout.line.lengthSq > 0.5) {
            val across = Vec3().setTo(readout.relative).addScaledInPlace(readout.line, -(readout.relative dot readout.line))
            if (across.length > DRIFT_DEADBAND) {
                craftRotation.inverseRotate(across.mulInPlace(-DRIFT_GAIN), wanted)
                if (wanted.length > DRIFT_MOST) wanted.mulInPlace(DRIFT_MOST / wanted.length)
            }
        }
        val now = System.nanoTime()
        val stopping = wanted.lengthSq < 1e-12 && localTranslate.lengthSq > 0.0
        val moved = wanted.distanceTo(localTranslate) > SLIDE_RESEND
        if (!stopping && !(moved && now - translateSentNanos > SLIDE_RESEND_NANOS)) return
        localTranslate.setTo(wanted)
        translateSentNanos = now
        pushControlsToPrediction()
        val x = wanted.x; val y = wanted.y; val z = wanted.z
        terrainScope.launch { client.send(Command.SetTranslation(focusId, x, y, z)) }
    }

    /** Whether the craft being flown has any wheels to brake. */
    /**
     * The flight HUD's stage cards, refreshed a few times a second from the
     * replica: what is burning, and what each stage still to fire will do.
     */
    @Volatile var stageCards: List<StageCard> = emptyList()
        private set
    private var stageCardsNanos = 0L

    private fun refreshStageCards() {
        val now = System.nanoTime()
        if (now - stageCardsNanos < STAGE_CARD_INTERVAL_NANOS) return
        stageCardsNanos = now
        val vessel = prediction.replica ?: return
        stageCards = StageCard.from(vessel, com.rm.apogee.core.craft.CraftStats.analyzeLive(vessel), localThrottle)
    }

    val controlledHasWheels: Boolean
        get() {
            val id = client.controlledVessel ?: return false
            val design = client.vessels.firstOrNull { it.id == id }?.design ?: return false
            return design.parts.any {
                catalog[it.partId]?.hasModule<com.rm.apogee.core.part.Wheel>() == true
            }
        }

    suspend fun setSas(enabled: Boolean) {
        localSas = enabled
        pushControlsToPrediction()
        withControlledVessel { client.send(Command.SetSas(it, enabled)) }
    }

    /** Hold a navball marker - and turn SAS on to do it. */
    suspend fun setSasMode(mode: com.rm.apogee.core.world.SasMode) {
        localSasMode = mode
        localSas = true
        pushControlsToPrediction()
        withControlledVessel {
            client.send(Command.SetSasMode(it, mode))
            client.send(Command.SetSas(it, true))
        }
    }

    /** The navball's next frame: automatic, surface, orbit, and target when there is one. */
    suspend fun cycleNavFrame() {
        val order = buildList {
            add(com.rm.apogee.core.world.NavFrame.AUTO)
            add(com.rm.apogee.core.world.NavFrame.SURFACE)
            add(com.rm.apogee.core.world.NavFrame.ORBIT)
            if (localTarget >= 0 && client.vessel(localTarget) != null) add(com.rm.apogee.core.world.NavFrame.TARGET)
        }
        val next = order[(order.indexOf(localNavFrame) + 1).mod(order.size)]
        setNavFrame(next)
    }

    suspend fun setNavFrame(frame: com.rm.apogee.core.world.NavFrame) {
        localNavFrame = frame
        pushControlsToPrediction()
        withControlledVessel { client.send(Command.SetNavFrame(it, frame)) }
    }

    /** Steer by [target], or by nothing for -1. */
    suspend fun setTarget(target: Long) {
        localTarget = target
        pushControlsToPrediction()
        withControlledVessel { client.send(Command.SetTarget(it, target)) }
    }

    /** A craft that can be picked as a target: nearest first. */
    class TargetChoice(val id: Long, val name: String, val distance: Double)

    /** Other craft round the same body, nearest first, for the target picker. */
    fun targetChoices(): List<TargetChoice> {
        val focusId = client.controlledVessel ?: return emptyList()
        val focus = client.vessel(focusId)?.latest ?: return emptyList()
        return client.vessels.mapNotNull { v ->
            if (v.id == focusId) return@mapNotNull null
            val state = v.latest ?: return@mapNotNull null
            if (state.referenceBodyId != focus.referenceBodyId) return@mapNotNull null
            TargetChoice(v.id, v.name.ifBlank { "Debris" }, state.position.distanceTo(focus.position))
        }.sortedBy { it.distance }.take(MAX_TARGET_CHOICES)
    }

    /**
     * Welds the controlled craft to whatever it is resting against.
     *
     * Not predicted locally, unlike staging. A merge rewrites both craft's
     * structure and destroys one of them, and guessing wrong about that would
     * leave the client showing a craft the server still has - staging only
     * flips a flag, which is cheap to be wrong about for one snapshot.
     */
    /**
     * Moves to the next craft the player owns, in id order.
     *
     * Cycling rather than a chooser: with a handful of craft it is one tap,
     * and a list is worth building when there are enough of them to need one.
     */
    suspend fun switchCraft() {
        val mine = client.vessels
            .filter { it.owner == client.clientId }
            .sortedBy { it.id }
        if (mine.size < 2) return
        val current = client.controlledVessel
        val next = mine.indexOfFirst { it.id == current }.let { mine[(it + 1) % mine.size] }
        client.send(Command.SwitchVessel(next.id))
    }

    /**
     * Every craft of the player's, for the craft list: where each is, in
     * words, and how high. The one being flown first.
     */
    fun myCraft(): List<com.rm.apogee.ui.screens.CraftSummary> {
        val current = client.controlledVessel
        return client.vessels
            .filter { it.owner == client.clientId }
            .sortedWith(compareBy({ it.id != current }, { it.name }))
            .mapNotNull { vessel ->
                val state = vessel.latest ?: return@mapNotNull null
                val body = system.bodies[state.referenceBodyId] ?: return@mapNotNull null
                val bodyFixed = body.toBodyFixed(state.position, body.rotationAt(client.latestSnapshot?.time ?: 0.0))
                val up = state.position.normalized()
                val above = (body.heightAboveTerrain(state.position, bodyFixed) +
                    lowestPointOffset(vessel.design, state.rotation, up)).coerceAtLeast(0.0)
                val orbit = com.rm.apogee.core.orbit.Orbit(state.position, state.velocity, body.gravitationalParameter)
                val floor = body.radius + body.atmosphereHeight + (body.terrain?.maxElevation ?: 0.0)
                val situation = when {
                    above < 2.0 && body.terrain?.isOcean(bodyFixed) == true -> "Afloat on ${body.displayName}"
                    above < 2.0 -> "Landed on ${body.displayName}"
                    orbit.isBound && orbit.periapsis > floor -> "In orbit of ${body.displayName}"
                    else -> "Flying over ${body.displayName}"
                }
                val height = if (above < 2.0) "on the surface"
                    else if (above < 10_000.0) "%.0f m up".format(above) else "%.1f km up".format(above / 1000.0)
                com.rm.apogee.ui.screens.CraftSummary(vessel.id, vessel.name, situation, height)
            }
    }

    /** The craft being flown, if any. */
    val controlledCraft: Long? get() = client.controlledVessel

    /**
     * Removes the craft being flown, and waits - a second at most - for the
     * server to say it is gone.
     */
    suspend fun retire() {
        val id = client.controlledVessel ?: return
        client.send(Command.RemoveVessel(id))
        kotlinx.coroutines.withTimeoutOrNull(1_000) {
            while (client.vessel(id) != null) kotlinx.coroutines.delay(20)
        }
    }

    /** Takes the controls of craft [id]. */
    suspend fun flyCraft(id: Long) {
        client.send(Command.SwitchVessel(id))
    }

    /** How many craft the player could switch between. */
    val ownedCraftCount: Int
        get() = client.vessels.count { it.owner == client.clientId }

    /** Puts [launchDesign] on the pad once the handshake is done. */
    private suspend fun launchPendingDesign() {
        val design = launchDesign ?: return
        client.send(Command.SpawnCraft(design, launchSiteId ?: World.launchSiteFor(design, catalog).id))
    }

    suspend fun join() {
        withControlledVessel { client.send(Command.Join(it)) }
    }

    suspend fun stage() {
        // Staged locally as well, so the button responds immediately; the
        // server's own staging arrives in the next structure update and
        // overwrites this. Locally on the frame thread, though, not this
        // one: staging splits the replica, and splitting it mid-step - the
        // frame thread stepping it through its parts as they changed - ran
        // off the end of its part list and took the game down.
        pendingLocalStages.incrementAndGet()
        withControlledVessel { client.send(Command.Stage(it)) }
    }

    /** Stage presses waiting for the frame thread to apply them to the replica. */
    private val pendingLocalStages = java.util.concurrent.atomic.AtomicInteger()

    private fun pushControlsToPrediction() {
        prediction.applyControl(
            localThrottle, localPitch, localYaw, localRoll, localSas, localBrakes,
            localRcs, localReverse, localTranslate.x, localTranslate.y, localTranslate.z,
        )
        prediction.replica?.control?.let {
            it.sasMode = localSasMode
            it.navFrame = localNavFrame
            it.target = localTarget
        }
    }

    // --- presentation --------------------------------------------------------

    /**
     * Builds one render frame from the client's view of the world.
     *
     * Called at roughly display cadence rather than the simulation's: the
     * server streams 20 motion samples a second and the renderer interpolates
     * between the last two, so publishing faster than the data changes would
     * just hand the GL thread the same numbers repeatedly.
     */
    private fun buildFrame(timestampNanos: Long) {
        val focusId = client.controlledVessel ?: return
        // Gone - smashed or burnt up whole: the view stays where it went,
        // on a stand-in with no parts, so the wreckage, the fire and the
        // smoke go on being there to see.
        val live = client.vessel(focusId)?.takeIf { it.latest != null }
        val wrecked = live == null
        val focus = live ?: wreckStandIn(focusId) ?: return
        val focusState = focus.latest ?: return

        val attractor = system.bodies[focusState.referenceBodyId] ?: return

        soundCrafts.clear()
        val lines = ArrayList<RenderLine>(4)
        val items = ArrayList<RenderItem>(64)
        val farItems = ArrayList<RenderItem>()
        frameEmitters.clear()

        // The one time this frame is drawn at. Snapshot time plus how long
        // ago it arrived, unless the controlled craft is being predicted, in
        // which case its own time: the ground, the craft on it and everything
        // else have to be shown at the same instant, because at the equator a
        // few milliseconds' disagreement is a visible slide.
        val snapshotTime = client.latestSnapshot?.time ?: 0.0
        val snapshotAge = if (client.latestSnapshotNanos == 0L) 0.0
            else (System.nanoTime() - client.latestSnapshotNanos) / 1e9
        // The world's clock runs at the warp rate - not at all while paused.
        val warp = client.latestSnapshot?.warp ?: 1.0
        val warping = warp != 1.0
        // Warped, the snapshots' own time is too jumpy to draw by - a few
        // milliseconds of arrival jitter is four times as much world time
        // at 4x - so a clock of our own runs at the warp rate and is eased
        // toward it.
        var renderTime = if (warping) warpClock(snapshotTime + snapshotAge * warp, warp)
            else { warpClockTime = Double.NaN; snapshotTime + snapshotAge }
        lastRenderTime = renderTime
        val animationNow = System.nanoTime()
        animationDt = if (lastAnimationNanos == 0L || warp == 0.0) 0.0 else ((animationNow - lastAnimationNanos) / 1e9).coerceAtMost(0.1)
        lastAnimationNanos = animationNow
        // Where the focused craft is drawn this frame - the prediction, not
        // the last snapshot - which is what the ground's detail follows.
        var focusDrawn: Vec3 = focusState.position

        if (mapMode) {
            // Look at the planet, not the craft: in map view the question is
            // the shape of the trajectory, and that is only legible against the
            // body it goes around.
            val orbit = Orbit(
                position = focusState.position,
                velocity = focusState.velocity,
                mu = attractor.gravitationalParameter,
            )
            val reach = if (orbit.isBound) orbit.apoapsis else orbit.periapsis * 4.0
            mapCamera.frameExactly(maxOf(reach, attractor.radius * 1.5))
            mapCamera.solve(Vec3.zero(), cameraPosition, cameraRotation)

            lines.add(RenderLine(orbit.sample(192), ORBIT_COLOR))
            if (orbit.isBound) {
                lines.add(marker(orbit.propagate(orbit.timeToApoapsis).position, reach, APOAPSIS_COLOR))
                lines.add(marker(orbit.propagate(orbit.timeToPeriapsis).position, reach, PERIAPSIS_COLOR))
            }
            lines.add(marker(focusState.position, reach, CRAFT_COLOR))
        } else {
            // Much smaller than it was - the rest of it smashed or torn
            // away: come in to see what is left.
            if (!wrecked && focus.design.parts.size < framedParts && framedFor == focusId) {
                camera.frameShrunk(designRadius(focus.design, designCentreOfMass(focus.design)))
            }
            if (!wrecked) { framedParts = focus.design.parts.size; framedFor = focusId }
            // Lost: stand back far enough to take in the wreckage, not tucked
            // in where the craft was - inside a piece of it, at times.
            if (wrecked && framedFor != -focusId) { camera.frameAtLeast(WRECK_VIEW); framedFor = -focusId }
            // Warped, the replica cannot keep up with the server's clock: it
            // is not stepped or drawn, only kept in step with the server's
            // staging and fuel for the gauges - and adopted afresh back at
            // real time.
            if (warping) {
                if (prediction.needsAdopting(focus.design)) prediction.adopt(focus.design, focusState, snapshotTime, client.weather)
                prediction.sync(focus.currentStage, focus.activatedParts, focus.fuel)
                refreshStageCards()
                lastAdvanceNanos = 0L; lastPresent = Double.NaN
                wasWarping = true
            } else if (wasWarping) {
                prediction.reset()
                serverClock.reset()
                wasWarping = false
            }
            val focusPosition = when {
                wrecked -> focusState.position
                warping -> if (sampled(focus, renderTime, attractor, warp, predictedPosition, predictedRotation)) predictedPosition
                    else focusState.position
                else -> updatePrediction(focus, focusState)
            }
            focusDrawn = focusPosition
            if (prediction.isReady && !wrecked && !warping) prediction.renderTime()?.let { renderTime = it }
            camera.solve(focusPosition, cameraPosition, cameraRotation)
            keepCameraAboveGround(attractor, focusPosition, renderTime)
            if (!wrecked) updateSlide(focusId, prediction.replica?.body?.orientation ?: focusState.rotation, focusPosition)
            for (vessel in client.vessels) {
                if (vessel.id == focusId && !warping) {
                    // Staged here and not yet heard back: draw the replica's
                    // own shape, and what it let go of - otherwise the whole
                    // old stack is drawn round the new, smaller craft's
                    // centre, a stage-length out of place, until the server
                    // catches up.
                    val replica = prediction.replica?.takeIf { prediction.isReady && it.defs.size != vessel.design.parts.size }
                    if (replica != null) {
                        val shape = ClientVessel(vessel.id, replica.design, vessel.name, replica.currentStage)
                        appendVessel(shape, items, attractor, predictedPosition, predictedRotation, stateOverride = vessel.latest, predicted = true)
                        prediction.droppedPieces().forEachIndexed { k, (piece, at) ->
                            val id = -1L - k
                            val kinematics = VesselKinematics(
                                vessel = id, referenceBodyId = piece.referenceBodyId,
                                position = at, rotation = piece.body.orientation.copy(),
                                velocity = piece.body.linearVelocity.copy(), angularVelocity = piece.body.angularVelocity.copy(),
                                pose = VesselPose.encode(piece),
                            )
                            appendVessel(
                                ClientVessel(id, piece.design, piece.name), items, attractor, at,
                                piece.body.orientation, stateOverride = kinematics,
                            )
                        }
                    } else {
                        appendVessel(vessel, items, attractor, predictedPosition, predictedRotation, predicted = prediction.isReady)
                    }
                } else if (warping) {
                    // Between its last two snapshots, at the frame's time.
                    val position = Vec3()
                    val rotation = Quat()
                    if (!sampled(vessel, renderTime, attractor, warp, position, rotation)) continue
                    appendVessel(vessel, items, attractor, position, rotation, stateOverride = vessel.observed?.kinematics)
                } else {
                    // Carried from its own snapshot to the frame's time, and
                    // eased where a new snapshot disagrees with the last.
                    val observed = vessel.observed ?: continue
                    val position = carried(observed, renderTime, attractor, warp) ?: continue
                    val rotation = spunOn(observed, renderTime)
                    smoothed(vessel.id, observed.time, renderTime, position, rotation, observed.kinematics.velocity)
                    appendVessel(vessel, items, attractor, position, rotation, stateOverride = observed.kinematics)
                }
            }
            if (drawn.size > client.vessels.size) {
                val alive = client.vessels.mapTo(HashSet()) { it.id }
                drawn.keys.retainAll(alive)
            }
        }

        // Terrain turns with the planet, so the patch follows the craft's
        // position in the body's frame rather than its inertial one - at the
        // frame's own time, the same one the craft is drawn at.
        attractor.rotationAt(renderTime, bodyRotation)
        // The drawn position at the frame's time. The snapshot's position with
        // the frame's rotation was up to 50 ms apart - metres of wobble in
        // where the ground's detail was centred, enough to flip a chunk at its
        // split distance between parent and children every other frame.
        attractor.toBodyFixed(focusDrawn, bodyRotation, bodyFixedCamera)
        terrainBuilder?.let { builder ->
            // The patch first, then the globe. Both are queued onto the same
            // dispatcher, and the globe is the larger job by some way - asking
            // for it first leaves the ground the craft is standing on waiting
            // behind scenery, which is most of why there is a visible gap
            // before the world looks right.
            builder.collect()
            if (client.felledRevision != seenFelledRevision) {
                seenFelledRevision = client.felledRevision
                prediction.felled(client.felledScatter)
            }
            builder.followCraft(
                attractor,
                bodyFixedCamera,
                attractor.heightAboveTerrain(focusState.position, bodyFixedCamera),
                terrainScope,
            )
            builder.requestGlobe(attractor, terrainScope)
            drawFarSurface = builder.farSurfaceNeeded
            chunkRange = builder.chunkRange
            scatterStreamer?.follow(
                attractor,
                bodyFixedCamera,
                attractor.heightAboveTerrain(focusState.position, bodyFixedCamera),
                client.felledScatter,
                client.felledRevision,
                terrainScope,
            )
        }

        joinable = !wrecked && neighbourInWeldingRange(focus, focusState)
        if (!wrecked) updateDocking(focus, focus.observed?.kinematics ?: focusState) else { dockReadout = null; joints = emptyList() }
        // Where to stay, should it be lost.
        lastSeen = LastSeen(focusId, attractor.id, bodyFixedCamera.copy(), focus.name)

        // Local vertical in world axes: the craft's own position direction.
        scratchUp.setTo(focusState.position).normalizeInPlace()
        if (!wrecked) peakParts[focusId] = maxOf(peakParts[focusId] ?: 0, focus.design.parts.size)
        // What it had when last seen whole, less what it had lost by then:
        // lost outright, that is what went with it - not the stages it had
        // dropped on the way up, which were never lost.
        if (!wrecked) lastParts[focusId] = focus.design.parts.size + (lostParts[focusId] ?: 0)
        // Never more than it ever had: a part torn off and then smashed is
        // one part, however many reports it made.
        // Lost outright, every part it had is gone from it, whatever the
        // count of reports: the pieces lying about are wreckage, not craft.
        val lost = if (wrecked) lastParts[focusId] ?: (lostParts[focusId] ?: 0)
            else minOf(lostParts[focusId] ?: 0, peakParts[focusId] ?: Int.MAX_VALUE)
        telemetry = if (wrecked) FlightTelemetry.lost(focus.name, crashReport(focusId), lost) else FlightTelemetry.from(
            focus, attractor, focusState.throttle, bodyFixedCamera,
            lowestPointOffset = lowestPointOffset(focus.design, focusState.rotation, scratchUp),
            air = prediction.replica?.air,
            bodyRotation = bodyRotation,
            forwardAxis = focus.design.orientation.forward,
            navFrame = localNavFrame,
            target = client.vessel(localTarget)?.latest?.takeIf { it.referenceBodyId == focusState.referenceBodyId },
            targetName = client.vessel(localTarget)?.name,
            sasMode = if (localSas) localSasMode else null,
            condition = conditions[focus.id],
            defs = focus.design.parts.map { catalog[it.partId] },
            jointLoad = prediction.replica?.takeIf { !warping && it.design.parts.size == focus.design.parts.size }?.jointLoad,
            lost = lost,
        )

        // The nearest thing in view, for the near plane: the closest part of
        // any craft, allowing for its size, and the ground under the camera.
        var nearest = Double.MAX_VALUE
        for (item in items) {
            val d = item.position.distanceTo(cameraPosition) - PartModels.boundingRadius(item.shape)
            if (d < nearest) nearest = d
        }
        attractor.toBodyFixed(cameraPosition, bodyRotation, scratchCameraBodyFixed)
        val cameraAboveGround = attractor.heightAboveTerrain(cameraPosition, scratchCameraBodyFixed)
        if (cameraAboveGround < nearest) nearest = cameraAboveGround

        // The weather: clouds to draw, and the air the camera is in. After
        // the near-plane search, which is about the craft and the ground -
        // a cloud a kilometre across is not a reason to pull it in.
        val weatherConfig = client.weather
        val clouds = if (weatherConfig != null && attractor.atmosphere != null) {
            cloudScene?.takeIf { it.body === attractor && it.config == weatherConfig }
                ?: CloudScene(attractor, weatherConfig, terrainQuality ?: QualityTier.MEDIUM, terrainScope)
                    .also { cloudScene = it }
        } else null
        if (clouds == null) cloudScene = null
        if (clouds != null) {
            attractor.toBodyFixed(cameraPosition, bodyRotation, cloudCamera)
            clouds.update(cloudCamera, renderTime)
            if (!mapMode) {
                clouds.append(bodyRotation, items)
                World.launchSites.firstOrNull { it.id == "cape" }?.let { cape ->
                    clouds.windsock(cape, cloudCamera, renderTime, bodyRotation, items)
                }
            } else {
                clouds.mapItems(renderTime, bodyRotation, farItems)
            }
        }

        // Flames, smoke and the rest: stepped by real frame time, drawn
        // through the same weather as the clouds.
        val fx = effects ?: Effects(terrainQuality ?: QualityTier.MEDIUM).also { effects = it }
        // Where the ears are, and what air there is to carry anything to them.
        val cameraAir = clouds?.air
        val listener = SoundScene.Listener(
            position = cameraPosition.copy(),
            right = cameraRotation.rotate(Vec3.unitX(), Vec3()),
            density = if (mapMode) 0.0 else attractor.atmosphere?.densityAt(attractor.altitudeOf(cameraPosition)) ?: 0.0,
            wind = cameraAir?.wind?.length ?: 0.0,
            turbulence = cameraAir?.turbulence ?: 0.0,
            rain = cameraAir?.precipitation ?: 0.0,
            // The camera rides with the craft being flown.
            velocity = Vec3().setTo(focusState.velocity).subInPlace(attractor.surfaceVelocityAt(focusDrawn, Vec3())),
            shore = if (mapMode) 0.0 else shoreNear(attractor, cameraPosition, bodyRotation),
        )
        // Blows, breakages and blasts since last frame, where they happened
        // on the turning ground.
        while (true) {
            val event = client.partEvents.poll() ?: break
            // Lost is destroyed: a part torn off is not gone - it may be
            // smashed later, and counted then, or be lying there whole. Both
            // were counted, and a part torn off then smashed was two.
            if (event.kind == PartEventKind.DESTROYED) {
                lostParts[event.vessel] = (lostParts[event.vessel] ?: 0) + 1
            }
            if (event.kind == PartEventKind.DESTROYED || event.kind == PartEventKind.DETACHED) {
                if (event.cause.isNotEmpty()) lastCause[event.vessel] = event.cause
            }
            if (event.kind == PartEventKind.IMPACT && event.vessel !in firstBlow) firstBlow[event.vessel] = event
            if (event.bodyId != attractor.id || mapMode) continue
            val at = attractor.toBodyFixed(event.position, attractor.rotationAt(event.time), Vec3())
            if (event.kind == PartEventKind.DESTROYED) {
                wreckSite[event.vessel] = at
                wreckTime[event.vessel] = event.time
            }
            val colour = if (event.partId.isNotEmpty()) com.rm.apogee.render.PartModels.bodyColour(event.partId) else null
            fx.partEvent(event.kind, at, event.amount, colour, (event.vessel * 977 + event.time * 1000).toInt(), water = event.cause == "water")
            hear(listener, event, attractor, at, focusId)
        }
        // Where the craft went in, what is left of it burns a while.
        if (wrecked && !mapMode && attractor.atmosphere != null) {
            val site = wreckSite[focusId]
            val since = renderTime - (wreckTime[focusId] ?: renderTime)
            if (site != null && since < WRECK_BURN_SECONDS) {
                val fade = 1.0 - since / WRECK_BURN_SECONDS
                fx.burn(site, 1.0 + 3.0 * fade, animationDt * fade, (focusId * 7).toInt())
            }
        }
        attractor.toBodyFixed(cameraPosition, bodyRotation, cloudCamera)
        fx.step(animationDt, renderTime, attractor, bodyRotation, frameEmitters, clouds?.weather, cloudCamera, clouds?.air)
        for (bolt in fx.thunder) {
            val closeness = kotlin.math.exp(-bolt[0] / 4_000.0)
            val delay = (bolt[0] / SoundScene.SPEED_OF_SOUND).coerceAtMost(SoundScene.MAX_DELAY * 2)
            val v = FloatArray(com.rm.apogee.audio.SharedParams.COUNT)
            v[0] = closeness.toFloat()
            v[com.rm.apogee.audio.SharedParams.GAIN] = (0.3 + 0.7 * closeness * bolt[1].coerceIn(0.3, 1.0)).toFloat()
            v[com.rm.apogee.audio.SharedParams.LOWPASS] = (18_000.0 / (1.0 + bolt[0] / 800.0)).coerceAtLeast(200.0).toFloat()
            AudioEngine.event(com.rm.apogee.audio.Recipes.THUNDER, 0, bolt[0].toInt(), delay.toFloat(), v)
        }
        fx.thunder.clear()
        playScene(listener, focus, wrecked, attractor)
        var particles: FloatArray? = null
        var particleShapes = 0
        if (!mapMode) {
            fx.flames(frameEmitters, attractor, renderTime, items)
            val daylight = com.rm.apogee.render.NightLight.daylight(cameraPosition, attractor.radius, SUN_DIRECTION)
            val (vertices, shapes) = fx.vertices(bodyRotation, cameraPosition, cameraRotation, clouds?.lightScale ?: 1f, renderTime, daylight, if (mapMode) 0f else fx.flash)
            particles = vertices
            particleShapes = shapes
        }
        val flash = if (mapMode) 0f else fx.flash
        // Shadows round the craft: out to three times its size, within reason.
        val shadowReach = (designRadius(focus.design, designCentreOfMass(focus.design)) * 3.0).coerceIn(40.0, 300.0)

        frameBus.publish(
            RenderFrame(
                simTick = client.latestSnapshot?.tick ?: 0L,
                timestampNanos = timestampNanos,
                cameraPosition = cameraPosition.copy(),
                cameraRotation = cameraRotation.copy(),
                fovYRadians = Math.toRadians(55.0),
                items = items,
                lines = lines,
                world = WorldView(
                    radius = attractor.radius,
                    atmosphereHeight = attractor.atmosphereHeight,
                    atmosphereScaleHeight = attractor.atmosphere?.scaleHeight ?: 1.0,
                    // One fixed star direction for now. The real one comes from
                    // the system's geometry once map view needs it too.
                    sunDirection = SUN_DIRECTION,
                    homeDirection = HOME_DIRECTION,
                    cameraAltitude = attractor.altitudeOf(cameraPosition),
                    bodyRotation = bodyRotation.copy(),
                    maxElevation = attractor.terrain?.maxElevation ?: 1.0,
                    drawFarSurface = drawFarSurface,
                    chunkRange = chunkRange,
                    fogDistance = if (mapMode || clouds == null) WorldView.CLEAR_FOG else clouds.fogDistance,
                    fogColor = clouds?.fogColor ?: floatArrayOf(0.75f, 0.77f, 0.8f),
                    skyFog = if (mapMode) 0f else clouds?.skyFog ?: 0f,
                    // Lightning is its own light, not more sun: sun is
                    // nothing at night, and neither was the flash.
                    lightScale = if (mapMode) 1f else (clouds?.lightScale ?: 1f),
                    flash = flash,
                    cloudShadow = if (mapMode) null else clouds?.shadowGrid,
                    surfaceWind = clouds?.surfaceWind?.copy() ?: Vec3(),
                    time = renderTime,
                ),
                nearestDistance = if (nearest == Double.MAX_VALUE) 0.0 else nearest.coerceAtLeast(0.0),
                particles = particles,
                particleShapes = particleShapes,
                farItems = farItems,
                shadowFocus = if (mapMode) null else focusDrawn.copy(),
                shadowRadius = shadowReach,
            )
        )
        framesPublished.incrementAndGet()
    }

    /**
     * A small diamond around a point, for apsis and craft markers.
     *
     * Sized as a fraction of the view rather than in metres, so a marker stays
     * the same visual size whether the orbit is 100 km or 10,000 km across.
     */
    private fun marker(at: Vec3, viewScale: Double, color: FloatArray): RenderLine {
        val size = viewScale * MARKER_FRACTION
        // Any two axes perpendicular to the radius put the diamond face-on to
        // the planet, which is the orientation it is read from.
        val radial = at.normalized()
        val a = (if (kotlin.math.abs(radial.y) < 0.9) Vec3.unitY() else Vec3.unitX())
            .cross(radial).normalizeInPlace()
        val b = radial.cross(a).normalizeInPlace()
        return RenderLine(
            listOf(
                at + a * size,
                at + b * size,
                at - a * size,
                at - b * size,
                at + a * size,
            ),
            color,
        )
    }

    /**
     * Steps the local replica and reconciles it with the newest snapshot.
     *
     * @return where the controlled craft should be drawn.
     */
    private fun updatePrediction(focus: ClientVessel, state: VesselKinematics): Vec3 {
        val now = System.nanoTime()
        val snapshot = client.latestSnapshot
        // How stale the server's word is by the time we act on it: against
        // the server's clock as followed over many snapshots, not this one's
        // arrival alone - whose jitter moved the present, and the craft drawn
        // at it, by metres at orbital speed.
        if (snapshot != null && snapshot.tick != clockSampledTick) {
            clockSampledTick = snapshot.tick
            serverClock.sample(snapshot.time, client.latestSnapshotNanos / 1e9)
        }
        val present = serverClock.now(now / 1e9)
        val age = present?.let { (it - (snapshot?.time ?: it)).coerceIn(0.0, MAX_SNAPSHOT_AGE) }
            ?: ((now - client.latestSnapshotNanos) / 1e9)
        // Rebuilt - staging, a part lost - the replica starts again from the
        // server's word; where the craft was drawn is carried over and eased
        // away, not jumped from (7.6 m at a staging in orbit).
        val carryFrom = if (prediction.needsAdopting(focus.design) && prediction.isReady && lastAdvanceNanos != 0L)
            predictedPosition.copy().addScaledInPlace(prediction.velocity() ?: Vec3(), (now - lastAdvanceNanos) / 1e9) else null
        if (prediction.needsAdopting(focus.design)) {
            // On the server's clock: see ClientPrediction.adopt.
            prediction.adopt(focus.design, state, snapshot?.time ?: 0.0, client.weather)
            pushControlsToPrediction()
            lastReconciledTick = -1
        }

        repeat(pendingLocalStages.getAndSet(0)) { prediction.stage() }

        // Advance first, then reconcile, so both are measured to the same
        // moment - the reverse spends the time since the last frame twice.
        // By the server's clock as followed - the same present every other
        // craft is drawn at - not the local one. Stepped by the local clock,
        // the flown craft drifted off the present the rest were drawn at, a
        // millisecond or so between snapshots, and each snapshot pulled it
        // back: at a thousand metres a second it shook by a metre a frame
        // against a stage just let go of, which itself ran smooth.
        if (lastAdvanceNanos != 0L) {
            val elapsed = if (present != null && !lastPresent.isNaN()) (present - lastPresent).coerceAtLeast(0.0)
                else (now - lastAdvanceNanos) / 1e9
            prediction.advance(elapsed)
        }
        lastAdvanceNanos = now
        lastPresent = present ?: Double.NaN

        if (snapshot != null && snapshot.tick != lastReconciledTick) {
            lastReconciledTick = snapshot.tick
            prediction.reconcile(state, age, snapshot.time)
        }
        prediction.sync(focus.currentStage, focus.activatedParts, focus.fuel)
        refreshStageCards()

        prediction.renderPosition(predictedPosition)
        if (carryFrom != null && prediction.isReady) {
            prediction.carryOffset(carryFrom.subInPlace(predictedPosition))
            prediction.renderPosition(predictedPosition)
        }
        prediction.renderRotation(predictedRotation)
        return if (prediction.isReady) predictedPosition else state.position
    }

    /**
     * What each craft's moving parts are doing, kept between frames: the
     * pose being eased towards the latest one received, and each wheel's and
     * propeller's accumulated turn.
     */
    private class VesselAnimation {
        val target = VesselPose.Values()
        val shown = VesselPose.Values()
        var spin = DoubleArray(0)
        var initialised = false
    }

    private val animations = HashMap<Long, VesselAnimation>()

    private val scratchBurn = Vec3()

    /** Parts each craft has lost, as the events said, for the HUD. */
    private val lostParts = HashMap<Long, Int>()
    /** The most parts each craft has had, since launch. */
    private val peakParts = HashMap<Long, Int>()
    /** Each craft's parts when last seen, plus those it had lost before: what a total loss loses. */
    private val lastParts = HashMap<Long, Int>()

    /** The first hard blow each craft took, for the crash report. */
    private val firstBlow = HashMap<Long, ServerMessage.PartEvent>()
    /** What finished each part off, most recent last. */
    private val lastCause = HashMap<Long, String>()

    /** The craft the camera last framed, and how many parts it had. */
    private var framedFor = -1L
    private var framedParts = 0

    /**
     * A camera swung round a craft on a hillside can end up inside the hill,
     * looking at the back of the ground. Lifted clear, and turned back to
     * look at the craft.
     */
    private fun keepCameraAboveGround(attractor: com.rm.apogee.core.orbit.CelestialBody, focus: Vec3, time: Double) {
        if (attractor.terrain == null) return
        attractor.toBodyFixed(cameraPosition, attractor.rotationAt(time), scratchCameraClear)
        val above = attractor.heightAboveTerrain(cameraPosition, scratchCameraClear)
        if (above >= CAMERA_CLEARANCE) return
        val r = cameraPosition.length
        cameraPosition.mulInPlace((r + CAMERA_CLEARANCE - above) / r)
        scratchCameraClear.setTo(focus).subInPlace(cameraPosition)
        com.rm.apogee.core.math.quatLookAt(scratchCameraClear, cameraPosition.normalized(), cameraRotation)
    }

    private val scratchCameraClear = Vec3()

    /**
     * A craft's attitude at [time], turned on from its snapshot by its spin
     * - a tumbling spent stage turns smoothly, not in twenty steps a second.
     */
    private fun spunOn(observed: ClientVessel.Observation, time: Double): Quat {
        val state = observed.kinematics
        val carry = (time - observed.time).coerceIn(-MAX_EXTRAPOLATION_SECONDS, MAX_EXTRAPOLATION_SECONDS)
        val w = state.angularVelocity
        val rate = w.length
        if (rate < 1e-6 || carry == 0.0) return state.rotation.copy()
        return Quat.fromAxisAngle(w.copy().mulInPlace(1.0 / rate), rate * carry) * state.rotation
    }

    /** Where each other craft was drawn last frame, and the ease still owed. */
    private class Drawn(val position: Vec3, val rotation: Quat, var observedAt: Double, var nanos: Long) {
        val offset = Vec3()
        var renderTime = 0.0
        val turn = Quat.identity()
    }
    private val drawn = HashMap<Long, Drawn>()

    /**
     * Eases [position] and [rotation] - where a craft would be drawn from
     * its newest snapshot - out of any jump from where it was drawn a frame
     * ago. When a snapshot lands that disagrees with the guess carried from
     * the one before, the difference is kept as an offset and let go over a
     * tenth of a second, instead of the craft hopping. A big difference is a
     * real jump, and is not smoothed.
     */
    private fun smoothed(id: Long, observedAt: Double, renderTime: Double, position: Vec3, rotation: Quat, velocity: Vec3) {
        val now = System.nanoTime()
        val last = drawn[id]
        if (last == null) {
            drawn[id] = Drawn(position.copy(), rotation.copy(), observedAt, now).also { it.renderTime = renderTime }
            return
        }
        val dt = ((now - last.nanos) / 1e9).coerceIn(0.0, 0.1)
        if (observedAt != last.observedAt) {
            // Where it would have been, carried on from last frame - by the
            // frame's own clock, which is what the ground moved by, not the
            // wall clock: the two part by tens of milliseconds, and at the
            // ground's speed that reads as a jump to be eased.
            val step = (renderTime - last.renderTime).coerceIn(-0.5, 0.5)
            val expected = last.position.copy().addScaledInPlace(velocity, step)
            val jump = expected.subInPlace(position)
            if (jump.length < SMOOTH_LIMIT) last.offset.setTo(jump) else last.offset.setZero()
            last.turn.setTo(last.rotation * rotation.conjugate())
            last.observedAt = observedAt
        }
        val keep = kotlin.math.exp(-dt / SMOOTH_SECONDS)
        last.offset.mulInPlace(keep)
        Quat.slerp(Quat.identity(), last.turn, keep, last.turn)
        position.addInPlace(last.offset)
        rotation.setTo(last.turn * rotation).normalizeInPlace()
        last.position.setTo(position)
        last.rotation.setTo(rotation)
        last.nanos = now
        last.renderTime = renderTime
    }

    // --- sound ------------------------------------------------------------------

    /** What can be heard, rebuilt each frame; as many voices as the device's tier allows. */
    private val sound by lazy {
        SoundScene(
            when (terrainQuality) {
                QualityTier.LOW -> 16
                QualityTier.HIGH -> 48
                else -> 32
            },
        )
    }

    /** Each craft making a sound this frame. */
    private val soundCrafts = LinkedHashMap<Long, SoundScene.Craft>()

    /** How close the camera is to waves breaking, 0..1: eased, and looked for four times a second. */
    private var shore = 0.0
    private var shoreTarget = 0.0
    private var shoreLookedNanos = 0L
    private var shoreEasedNanos = 0L

    /**
     * Surf: loud where land and sea meet within a couple of hundred metres
     * of the camera, low down; nothing over open water, inland or from
     * high up. A ring of points round the spot below the camera, looked at
     * for land and for sea.
     */
    private fun shoreNear(attractor: CelestialBody, cameraPosition: Vec3, bodyRotation: Quat): Double {
        val now = System.nanoTime()
        val terrain = attractor.terrain
        if (terrain == null || !terrain.hasOcean || attractor.ocean == null) {
            shore = 0.0; shoreTarget = 0.0
            return 0.0
        }
        if (now - shoreLookedNanos > SHORE_LOOK_NANOS) {
            shoreLookedNanos = now
            val up = attractor.toBodyFixed(cameraPosition, bodyRotation, Vec3())
            val height = up.length - attractor.radius
            up.normalizeInPlace()
            val low = (1.0 - (height - SHORE_FULL_BELOW) / (SHORE_SILENT_ABOVE - SHORE_FULL_BELOW)).coerceIn(0.0, 1.0)
            shoreTarget = if (low <= 0.0) 0.0 else {
                val east = Vec3(0.0, 1.0, 0.0).crossInPlace(up).let { if (it.lengthSq < 1e-9) Vec3(1.0, 0.0, 0.0) else it.normalizeInPlace() }
                val north = Vec3().setTo(up).crossInPlace(east).normalizeInPlace()
                fun seaAt(distance: Double, angle: Double): Boolean {
                    val a = distance / attractor.radius
                    val d = Vec3().setTo(up)
                        .addScaledInPlace(east, kotlin.math.cos(angle) * a)
                        .addScaledInPlace(north, kotlin.math.sin(angle) * a)
                        .normalizeInPlace()
                    return terrain.isOcean(d)
                }
                val here = terrain.isOcean(up)
                var near = 0.0
                for ((distance, weight) in SHORE_RINGS) {
                    val mixed = (0 until 8).any { k -> seaAt(distance, k * Math.PI / 4) != here }
                    if (mixed) { near = weight; break }
                }
                near * low
            }
        }
        val dt = if (shoreEasedNanos == 0L) 0.0 else ((now - shoreEasedNanos) / 1e9).coerceAtMost(0.2)
        shoreEasedNanos = now
        shore += (shoreTarget - shore) * (dt / 1.5).coerceAtMost(1.0)
        return shore
    }

    private fun soundCraft(id: Long, position: Vec3, velocity: Vec3, attractor: com.rm.apogee.core.orbit.CelestialBody) =
        soundCrafts.getOrPut(id) {
            SoundScene.Craft(
                id, own = id == client.controlledVessel, position = position.copy(),
                pressure = attractor.atmosphere?.pressureRatioAt(attractor.altitudeOf(position)) ?: 0.0,
                // Through the air, which turns with the ground.
                velocity = Vec3().setTo(velocity).subInPlace(attractor.surfaceVelocityAt(position, Vec3())),
            )
        }

    /** How much the flown craft's hull is still ticking to the change of pressure. */
    private val hullSettling = com.rm.apogee.audio.HullSettling()
    private var settlingFor = -1L

    /** The flown craft as last heard, to catch it staging, lighting up and opening a chute. */
    private var heardFor = -1L
    private var heardStage = 0
    private var heardBurning = false
    private var heardChutes = emptySet<Int>()
    private var heardLegs: Map<Int, Double> = emptyMap()
    private var heardCaution = false
    private var lastCautionNanos = 0L

    /** A blow, a breakage or a blast, as heard from here. */
    private fun hear(
        listener: SoundScene.Listener,
        event: ServerMessage.PartEvent,
        attractor: com.rm.apogee.core.orbit.CelestialBody,
        bodyFixed: Vec3,
        focusId: Long,
    ) {
        val kind = when (event.kind) {
            PartEventKind.IMPACT -> SoundScene.Kind.IMPACT
            PartEventKind.DESTROYED -> SoundScene.Kind.DESTROYED
            PartEventKind.DETACHED -> SoundScene.Kind.DETACHED
            PartEventKind.EXPLOSION -> SoundScene.Kind.EXPLOSION
            PartEventKind.DOCKED, PartEventKind.HITCHED -> SoundScene.Kind.LATCH
            PartEventKind.UNDOCKED, PartEventKind.UNHITCHED -> SoundScene.Kind.RELEASE
        }
        val position = attractor.rotationAt(lastRenderTime).rotate(bodyFixed, Vec3())
        val shot = sound.shot(
            listener, kind, position, event.amount, own = event.vessel == focusId,
            material = groundMaterial(attractor, bodyFixed), water = event.cause == "water",
        ) ?: return
        AudioEngine.event(shot.recipe, shot.flags, (event.time * 1000).toInt() xor event.vessel.toInt(), shot.delay, shot.params)
    }

    /**
     * The ground under a craft at [position], as its wheels hear it: how
     * much it crunches, and how soft it is (0 rock to 1 sand or snow).
     * Looked at a few times a second.
     */
    private fun groundUnderWheels(attractor: CelestialBody, position: Vec3): Pair<Double, Double> {
        val now = System.nanoTime()
        wheelGround?.let { if (now - wheelGroundNanos < SHORE_LOOK_NANOS) return it }
        val bodyFixed = attractor.toBodyFixed(position, attractor.rotationAt(lastRenderTime), Vec3())
        val direction = bodyFixed.normalized()
        val found = if (attractor.terrain?.isLaunchComplex(direction) == true) 0.1 to 0.0 else when (groundMaterial(attractor, bodyFixed)) {
            Materials.ROCK -> 0.9 to 0.0
            Materials.SAND -> 0.7 to 1.0
            Materials.SNOW -> 0.35 to 0.9
            Materials.WOOD -> 0.5 to 0.5
            else -> 0.45 to 0.35
        }
        wheelGround = found
        wheelGroundNanos = now
        return found
    }
    private var wheelGround: Pair<Double, Double>? = null
    private var wheelGroundNanos = 0L

    /** What the ground is at [bodyFixed], as an impact would ring on it. */
    private fun groundMaterial(attractor: com.rm.apogee.core.orbit.CelestialBody, bodyFixed: Vec3): Int {
        val terrain = attractor.terrain ?: return Materials.ROCK
        val direction = bodyFixed.normalized()
        val material = terrain.material(direction, terrain.elevation(direction), 0.0)
        return when (material) {
            com.rm.apogee.core.terrain.SurfaceMaterial.ROCK, com.rm.apogee.core.terrain.SurfaceMaterial.BASALT,
            com.rm.apogee.core.terrain.SurfaceMaterial.ICE, com.rm.apogee.core.terrain.SurfaceMaterial.SCREE -> Materials.ROCK
            com.rm.apogee.core.terrain.SurfaceMaterial.SAND, com.rm.apogee.core.terrain.SurfaceMaterial.REGOLITH -> Materials.SAND
            com.rm.apogee.core.terrain.SurfaceMaterial.SNOW -> Materials.SNOW
            com.rm.apogee.core.terrain.SurfaceMaterial.FOREST -> Materials.WOOD
            else -> Materials.EARTH
        }
    }

    /** This frame's held sounds, and the flown craft's staging and ignition, to the engine. */
    private fun playScene(
        listener: SoundScene.Listener,
        focus: ClientVessel,
        wrecked: Boolean,
        attractor: com.rm.apogee.core.orbit.CelestialBody,
    ) {
        val own = if (wrecked) null else {
            val t = telemetry
            val rise = t.airspeed * t.airspeed / (2.0 * 1_005.0)
            val heat = ((rise - 1_400.0) / 1_200.0).coerceIn(0.0, 1.0).let { it * it * (3 - 2 * it) }
            val defs = focus.design.parts.mapNotNull { catalog[it.partId] }
            val onWheels = defs.any { it.hasModule<com.rm.apogee.core.part.Wheel>() } && t.heightAboveGround < 1.0
            val ground = if (onWheels) focus.latest?.let { groundUnderWheels(attractor, it.position) } else null
            if (settlingFor != focus.id) { hullSettling.reset(); settlingFor = focus.id }
            val settling = soundCrafts[focus.id]?.let { hullSettling.update(it.pressure, lastRenderTime) } ?: 0.0
            SoundScene.Own(
                airspeed = t.airspeed,
                dynamicPressure = t.dynamicPressure,
                mach = t.airspeed / SoundScene.SPEED_OF_SOUND,
                heat = if (t.inAir) heat else 0.0,
                stress = t.structure,
                crewed = defs.any { (it.module<com.rm.apogee.core.part.Command>()?.crewCapacity ?: 0) > 0 },
                wheelSpeed = if (onWheels) t.surfaceSpeed else 0.0,
                wheelLoad = if (onWheels) kotlin.math.abs(localThrottle) else 0.0,
                grit = ground?.first ?: 0.4,
                softness = ground?.second ?: 0.3,
                settling = settling,
            )
        }
        // Paused, the world is still - and so is everything in it.
        if ((client.latestSnapshot?.warp ?: 1.0) <= 0.0) {
            AudioEngine.scene(0, sound.keys, sound.recipes, sound.flags, sound.params)
            return
        }
        sound.build(listener, soundCrafts.values.toList(), own)
        AudioEngine.scene(sound.count, sound.keys, sound.recipes, sound.flags, sound.params)
        AudioEngine.room(if (listener.inAir) 0.12f else 0.45f)

        // A warning coming on, now and then at most.
        val caution = !wrecked && (telemetry.overheating || telemetry.straining)
        val now = System.nanoTime()
        if (caution && !heardCaution && now - lastCautionNanos > CAUTION_SPACING_NANOS) {
            com.rm.apogee.audio.Sounds.caution()
            lastCautionNanos = now
        }
        heardCaution = caution

        // The flown craft's own moments: a stage going, an engine lighting, a chute opening.
        if (wrecked) return
        val hull = if (listener.inAir) 0 else VoiceFlags.HULL
        fun play(recipe: Int, strength: Float) {
            val v = FloatArray(com.rm.apogee.audio.SharedParams.COUNT)
            v[0] = strength
            AudioEngine.event(recipe, hull, recipe * 7919 + heardStage, 0f, v)
        }
        // Heard when it opens, not when it is armed.
        val deploy = animations[focus.id]?.target?.deploy
        chuteState = chuteStateOf(focus, deploy)
        if (chuteState != "OPEN") framedChute = false
        // Each opening heard: the drogue, then the main (as part + 1000).
        val chutes = focus.activatedParts.filter { i ->
            focus.design.parts.getOrNull(i)?.let { catalog[it.partId]?.hasModule<com.rm.apogee.core.part.Parachute>() } == true &&
                (deploy?.getOrNull(i) ?: 0.0) > 0.0
        }.flatMap { i ->
            if ((deploy?.getOrNull(i) ?: 0.0) > com.rm.apogee.core.part.Parachute.DROGUE_FULL + 0.01) listOf(i, i + 1000) else listOf(i)
        }.toSet()
        val burning = soundCrafts[focus.id]?.let { c -> c.output.any { it > 0.05 } } ?: false
        // Legs locking down or stowing: a clunk as each reaches the end of its travel.
        var locked = 0
        val legs = HashMap<Int, Double>()
        for ((i, placed) in focus.design.parts.withIndex()) {
            if (catalog[placed.partId]?.hasModule<com.rm.apogee.core.part.LandingLeg>() != true) continue
            if (catalog[placed.partId]?.hasModule<com.rm.apogee.core.part.Wheel>() == true) continue
            val now = deploy?.getOrNull(i) ?: continue
            legs[i] = now
            val before = heardLegs[i] ?: continue
            if ((now >= LEG_END && before < LEG_END) || (now <= 1.0 - LEG_END && before > 1.0 - LEG_END)) locked++
        }
        if (heardFor == focus.id) {
            if (locked > 0) play(Recipes.CLUNK, (0.6f + 0.1f * locked).coerceAtMost(1f))
            if (focus.currentStage > heardStage) play(Recipes.STAGE, 1f)
            if (burning && !heardBurning) play(Recipes.IGNITION, 0.8f)
            if ((chutes - heardChutes).isNotEmpty()) play(Recipes.CHUTE, 1f)
        }
        heardLegs = legs
        heardFor = focus.id
        heardStage = focus.currentStage
        heardBurning = burning
        heardChutes = chutes
    }

    /** Whether the camera has widened for the flown craft's open chute yet. */
    private var framedChute = false

    /** The flown craft's parachute, for the HUD: "ARMED", "OPEN", or null. */
    @Volatile
    var chuteState: String? = null
        private set

    // --- docking ----------------------------------------------------------------

    /** Lining up to dock: how far, how fast, how far off square, and whether the magnets would take it now. */
    class DockReadout(
        val distance: Double, val closing: Double, val angle: Double, val ready: Boolean, val partner: String,
        /** Our velocity relative to theirs, and the unit line from our port to theirs: inertial. */
        val relative: Vec3 = Vec3(), val line: Vec3 = Vec3(),
        /** Coming in faster than the magnets will take, near enough for it to matter. */
        val tooFast: Boolean = false,
    )

    /** Somewhere the flown craft is joined and can let go: a docking part, and what it holds. */
    class Joint(val part: Int, val label: String, val hitch: Boolean)

    /** Where the flown craft's nearest free docking part stands to another craft's, or null for nothing near. */
    @Volatile var dockReadout: DockReadout? = null
        private set

    /** The flown craft's docked rings and clamps, and coupled hitches. */
    @Volatile var joints: List<Joint> = emptyList()
        private set

    /** Lets go at part [part] of the craft being flown. */
    suspend fun undock(part: Int) {
        withControlledVessel { client.send(Command.Undock(it, part)) }
    }

    /** Shared with another player: who flies it - "me", "them" or "both". */
    val sharedWith: ServerMessage.DockedWith?
        get() = client.dockedWith?.takeIf { it.vessel == client.controlledVessel }

    /** This player's id, to tell "me" from "them" in [sharedWith]. */
    val myId: String get() = client.clientId

    suspend fun setDockPilot(pilot: String) {
        withControlledVessel { client.send(Command.SetDockPilot(it, pilot)) }
    }

    /** A docking part's face on craft [design] at [state]: where (inertial) and which way it faces. */
    private fun portFace(design: CraftDesign, state: com.rm.apogee.core.world.VesselKinematics, part: Int, port: com.rm.apogee.core.part.DockingPort): Pair<Vec3, Vec3> {
        val placed = design.parts[part]
        val axis = state.rotation.rotate(placed.rotation.rotate(Vec3.unitY(), Vec3()), Vec3()).normalizeInPlace()
        val at = Vec3().setTo(placed.position).subInPlace(designCentreOfMass(design))
        state.rotation.rotate(at, at).addInPlace(state.position).addScaledInPlace(axis, port.faceOffset)
        return at to axis
    }

    /** Works out [dockReadout] and [joints] for the craft being flown. */
    private fun updateDocking(focus: ClientVessel, state: com.rm.apogee.core.world.VesselKinematics) {
        val design = focus.design
        val hitches = client.latestSnapshot?.hitches.orEmpty()
        val list = ArrayList<Joint>()
        for ((i, placed) in design.parts.withIndex()) {
            val from = placed.dockedFrom ?: continue
            list.add(Joint(i, from.name, hitch = false))
        }
        for (h in hitches) {
            if (h.vesselA == focus.id) list.add(Joint(h.partA, client.vessel(h.vesselB)?.name ?: "", hitch = true))
            else if (h.vesselB == focus.id) list.add(Joint(h.partB, client.vessel(h.vesselA)?.name ?: "", hitch = true))
        }
        joints = list

        // Our free docking parts; theirs on the target, or on anything close.
        val mine = design.parts.indices.mapNotNull { i ->
            val port = catalog[design.parts[i].partId]?.module<com.rm.apogee.core.part.DockingPort>() ?: return@mapNotNull null
            if (design.parts[i].dockedTo >= 0 || hitches.any { (it.vesselA == focus.id && it.partA == i) || (it.vesselB == focus.id && it.partB == i) }) null
            else Triple(i, port, portFace(design, state, i, port))
        }
        if (mine.isEmpty()) { dockReadout = null; return }
        var best: DockReadout? = null
        var bestDistance = DOCK_READOUT_RANGE
        for (other in client.vessels) {
            if (other.id == focus.id) continue
            if (localTarget >= 0 && other.id != localTarget) continue
            // Brought to the same moment as ours: another craft's last word
            // can be a few snapshots older, and at orbital speed each one is
            // a hundred metres.
            val seen = other.observed ?: continue
            val ours = focus.observed?.time ?: seen.time
            val theirs = seen.kinematics.let { k ->
                k.copy(position = Vec3().setTo(k.position).addScaledInPlace(k.velocity, ours - seen.time))
            }
            if (theirs.referenceBodyId != state.referenceBodyId) continue
            if (theirs.position.distanceTo(state.position) > DOCK_READOUT_RANGE + 60.0) continue
            for ((j, placed) in other.design.parts.withIndex()) {
                val port = catalog[placed.partId]?.module<com.rm.apogee.core.part.DockingPort>() ?: continue
                if (placed.dockedTo >= 0) continue
                val (face, axis) = portFace(other.design, theirs, j, port)
                for ((_, own, ownFace) in mine) {
                    if (!own.matesWith(port)) continue
                    val d = ownFace.first.distanceTo(face)
                    if (d >= bestDistance) continue
                    bestDistance = d
                    val angle = Math.toDegrees(kotlin.math.acos((-(ownFace.second dot axis)).coerceIn(-1.0, 1.0)))
                    val to = Vec3().setTo(face).subInPlace(ownFace.first)
                    val rel = Vec3().setTo(state.velocity).subInPlace(theirs.velocity)
                    val closing = if (d > 1e-3) (rel dot to) / d else 0.0
                    val range = minOf(own.captureRange, port.captureRange)
                    val allowed = minOf(own.captureAngle, port.captureAngle) * (2.0 - minOf(1.0, d / range))
                    val fast = rel.length > minOf(own.captureSpeed, port.captureSpeed)
                    val ready = d <= range && (!own.rigid || angle <= allowed) && !fast
                    best = DockReadout(
                        d, closing, if (own.rigid) angle else 0.0, ready, other.name, rel.copy(),
                        if (d > 1e-3) to.copy().mulInPlace(1.0 / d) else Vec3(), tooFast = fast && d < 30.0,
                    )
                }
            }
        }
        dockReadout = best
    }

    private fun chuteStateOf(focus: ClientVessel, deploy: DoubleArray?): String? {
        var armed = false
        var open = false
        for (i in focus.activatedParts) {
            val placed = focus.design.parts.getOrNull(i) ?: continue
            if (catalog[placed.partId]?.hasModule<com.rm.apogee.core.part.Parachute>() != true) continue
            val d = deploy?.getOrNull(i) ?: 0.0
            if (d > com.rm.apogee.core.part.Parachute.DROGUE_FULL + 0.01) return "FULL"
            if (d > 0.0) open = true
            if (d == 0.0) armed = true
        }
        return if (open) "OPEN" else if (armed) "ARMED" else null
    }

    /** Whether the last frame was warped, to start the replica afresh coming out of it. */
    private var wasWarping = false

    /** The warp clock: world time as drawn, advanced at the warp rate. */
    private var warpClockTime = Double.NaN
    private var warpClockNanos = 0L

    /**
     * World time to draw a warped frame at: a clock that runs at [warp]
     * times real time and eases toward [target] - the snapshots' time -
     * rather than jumping with every one. Under physics warp it runs a
     * snapshot and a half behind, so every craft has a snapshot either side
     * of it to be drawn between.
     */
    private fun warpClock(target: Double, warp: Double): Double {
        val now = System.nanoTime()
        val dt = if (warpClockNanos == 0L) 0.0 else ((now - warpClockNanos) / 1e9).coerceAtMost(0.1)
        warpClockNanos = now
        warpClockTime = if (warpClockTime.isNaN() || kotlin.math.abs(target - warpClockTime) > WARP_CLOCK_SNAP * maxOf(warp, 1.0)) target
            else warpClockTime + dt * warp + (target - warpClockTime) * WARP_CLOCK_EASE
        val behind = if (warp <= World.PHYSICS_WARP) 1.5 * SNAPSHOT_SPACING * warp else 0.0
        return warpClockTime - behind
    }

    /**
     * Where [vessel] is, and how it is turned, at [time], into [position]
     * and [rotation]. Under physics warp, between its last two snapshots -
     * a curve through both positions with both velocities, and the turn
     * between the two attitudes - so it glides rather than stepping at the
     * snapshot rate. On rails, along its orbit from the latest.
     */
    private fun sampled(
        vessel: ClientVessel,
        time: Double,
        attractor: com.rm.apogee.core.orbit.CelestialBody,
        warp: Double,
        position: Vec3,
        rotation: Quat,
    ): Boolean {
        val b = vessel.observed ?: return false
        val a = vessel.previousObserved
        if (warp <= World.PHYSICS_WARP && a != null && b.time > a.time && time <= b.time) {
            val h = b.time - a.time
            val s = ((time - a.time) / h).coerceIn(0.0, 1.0)
            val s2 = s * s
            val s3 = s2 * s
            val h00 = 2 * s3 - 3 * s2 + 1
            val h10 = s3 - 2 * s2 + s
            val h01 = -2 * s3 + 3 * s2
            val h11 = s3 - s2
            val pa = a.kinematics.position; val pb = b.kinematics.position
            val va = a.kinematics.velocity; val vb = b.kinematics.velocity
            position.setTo(
                h00 * pa.x + h10 * h * va.x + h01 * pb.x + h11 * h * vb.x,
                h00 * pa.y + h10 * h * va.y + h01 * pb.y + h11 * h * vb.y,
                h00 * pa.z + h10 * h * va.z + h01 * pb.z + h11 * h * vb.z,
            )
            Quat.slerp(a.kinematics.rotation, b.kinematics.rotation, s, rotation)
            return true
        }
        position.setTo(carried(b, time, attractor, warp) ?: return false)
        rotation.setTo(b.kinematics.rotation)
        return true
    }

    /**
     * Where a craft last seen at [observed] is at [renderTime]: along its
     * velocity for the fraction of a second between snapshots at real time;
     * warped, when a snapshot can be most of an orbit apart, along its orbit
     * exactly, or round with the ground if it is sitting on it.
     */
    private fun carried(
        observed: ClientVessel.Observation?,
        renderTime: Double,
        attractor: com.rm.apogee.core.orbit.CelestialBody,
        warp: Double,
    ): Vec3? {
        observed ?: return null
        val state = observed.kinematics
        // Back as well as forward: the frame's time can sit a little behind
        // the newest snapshot (the flown craft's replica sets it), and a craft
        // left where the snapshot put it, while the ground under it is drawn a
        // few hundredths of a second earlier, stands metres off - and hops
        // back each time the two clocks cross.
        val carry = (renderTime - observed.time)
            .coerceIn(-MAX_EXTRAPOLATION_SECONDS, MAX_EXTRAPOLATION_SECONDS * maxOf(warp, 1.0))
        if (carry < 0.5) return Vec3().setTo(state.position).addScaledInPlace(state.velocity, carry)
        val ground = attractor.surfaceVelocityAt(state.position, Vec3())
        return if (ground.distanceTo(state.velocity) < 0.5) {
            // Parked: it turns with the planet.
            val bodyFixed = attractor.toBodyFixed(state.position, attractor.rotationAt(observed.time), Vec3())
            attractor.rotationAt(observed.time + carry).rotate(bodyFixed, Vec3())
        } else {
            com.rm.apogee.core.orbit.Orbit(state.position, state.velocity, attractor.gravitationalParameter)
                .propagate(carry).position
        }
    }

    /** Where each craft last had a part destroyed, body-fixed: where its wreck is. */
    private val wreckSite = HashMap<Long, Vec3>()
    private val wreckTime = HashMap<Long, Double>()

    /** Where the craft being flown was last drawn, body-fixed. */
    /** Each lost craft's wreckage, and where the camera is looking among it (body-fixed). */
    private val wreckPieces = HashMap<Long, Set<Long>>()
    private val wreckLook = HashMap<Long, Vec3>()

    private class LastSeen(val id: Long, val bodyId: String, val bodyFixed: Vec3, val name: String)
    private var lastSeen: LastSeen? = null

    /**
     * A craft of no parts standing where the flown one was last seen, moving
     * with the ground: something for the camera to keep looking at once the
     * real one is gone. Null if it was never seen, or somewhere else.
     */
    private fun wreckStandIn(id: Long): ClientVessel? {
        val seen = lastSeen?.takeIf { it.id == id } ?: return null
        val body = system.bodies[seen.bodyId] ?: return null
        val time = (client.latestSnapshot?.time ?: 0.0) +
            if (client.latestSnapshotNanos == 0L) 0.0 else (System.nanoTime() - client.latestSnapshotNanos) / 1e9
        // Where it came apart, if that is known; else where it was last
        // seen - it was lost at speed, and the last frame it was drawn in
        // was well short of where it hit.
        val lost = wreckSite[id]?.copy() ?: seen.bodyFixed.copy()
        // Its wreckage, once it has any: the pieces that were beside it when
        // it went, followed as they tumble on - the camera watched the spot
        // where the last part died, often dug into the ground, while what
        // was left skidded off out of sight (Dan).
        // Looked for until some turn up: they can arrive a snapshot after the loss.
        val pieces = wreckPieces[id] ?: client.vessels.filter { v ->
            val at = v.latest?.let { body.toBodyFixed(it.position, body.rotationAt(time), Vec3()) }
            at != null && at.distanceTo(lost) < WRECK_PIECE_REACH
        }.map { it.id }.toSet().also { if (it.isNotEmpty()) wreckPieces[id] = it }
        val target = Vec3()
        var count = 0
        for (piece in pieces) {
            val v = client.vessel(piece)?.latest ?: continue
            target.addInPlace(body.toBodyFixed(v.position, body.rotationAt(time), Vec3()))
            count++
        }
        if (count > 0) target.mulInPlace(1.0 / count) else target.setTo(lost)
        // Never inside the ground: a couple of metres above it.
        val over = body.heightAboveTerrain(body.rotationAt(time).rotate(target, Vec3()), target)
        if (over < 2.0) target.mulInPlace((target.length - over + 2.0) / target.length)
        // Eased from where it looked last, not jumped.
        val site = wreckLook[id]?.let { last -> last.addScaledInPlace(target.subInPlace(last), WRECK_EASE) } ?: target
        wreckLook[id] = site.copy()
        val position = body.rotationAt(time).rotate(site, Vec3())
        return ClientVessel(id, CraftDesign(seen.name, emptyList()), seen.name).also {
            it.observe(
                VesselKinematics(
                    vessel = id, referenceBodyId = seen.bodyId,
                    position = position, rotation = Quat.identity(),
                    velocity = body.surfaceVelocityAt(position, Vec3()), angularVelocity = Vec3(),
                ),
                time,
            )
        }
    }

    /** What happened, in a line: the first blow and what it did, or what else took it. */
    private fun crashReport(id: Long): String {
        val blow = firstBlow[id]
        val cause = lastCause[id]
        return when {
            cause == "burnt up" -> "Burnt up coming down"
            blow != null -> {
                val part = catalog[blow.partId]?.title ?: "It"
                "$part hit at ${blow.amount.roundToInt()} m/s"
            }
            cause != null -> cause.replaceFirstChar { it.uppercase() }
            else -> "Destroyed"
        }
    }

    /** Each craft's condition, unpacked, reused frame to frame. */
    private val conditions = HashMap<Long, VesselCondition.Values>()
    /** Each craft's straining joints, as drawn: the flex and where the seams are. */
    private val strains = HashMap<Long, com.rm.apogee.render.StrainLook>()
    private val scratchSeam = Vec3()
    private val scratchDrift = Vec3()
    private val scratchRcs = Vec3()
    private val scratchRcsAt = Vec3()
    private var lastAnimationNanos = 0L
    private var animationDt = 0.0

    /** Turns one craft's design plus its motion into per-part draw items. */
    private fun appendVessel(
        vessel: ClientVessel,
        out: MutableList<RenderItem>,
        attractor: CelestialBody,
        overridePosition: Vec3? = null,
        overrideRotation: Quat? = null,
        /** The state [overridePosition] was carried from, so both are the same sample. */
        stateOverride: VesselKinematics? = null,
        /** Pose from the prediction replica, for the craft being flown. */
        predicted: Boolean = false,
    ) {
        val emitters = frameEmitters
        val state = stateOverride ?: vessel.latest ?: return
        val design = vessel.design

        // The server sends the vessel's centre of mass; part positions in the
        // design are relative to the design origin, so the offset between them
        // has to be reconstructed here.
        val centreOfMass = designCentreOfMass(design)
        val position = overridePosition ?: state.position
        val rotation = overrideRotation ?: state.rotation

        // The moving parts: from the replica for the craft being flown, so a
        // surface moves the frame the stick does; from the server's pose for
        // everyone else's, eased between snapshots.
        val defs = design.parts.map { catalog[it.partId] }
        val animation = animations.getOrPut(vessel.id) { VesselAnimation() }
        val n = design.parts.size
        // And only if it fits the design drawn: staging splits the replica at
        // once, and until the server's new structure arrives it has fewer
        // parts than the craft on screen - reading its pose by this design's
        // parts ran off the end of it and took the game down with it.
        val fresh = (if (predicted) prediction.pose(animation.target)
            else defs.all { it != null } && VesselPose.decode(defs.map { it!! }, state.pose, animation.target)) &&
            animation.target.deflection.size == n
        animation.shown.fit(n)
        if (animation.spin.size != n) animation.spin = DoubleArray(n)
        if (fresh) {
            val ease = if (predicted || !animation.initialised) 1.0 else (animationDt / POSE_EASING_SECONDS).coerceIn(0.0, 1.0)
            val t = animation.target; val sh = animation.shown
            for (i in 0 until n) {
                sh.deflection[i] += (t.deflection[i] - sh.deflection[i]) * ease
                sh.steer[i] += (t.steer[i] - sh.steer[i]) * ease
                sh.compression[i] += (t.compression[i] - sh.compression[i]) * ease
                sh.deploy[i] += (t.deploy[i] - sh.deploy[i]) * ease
                sh.gimbalPitch[i] += (t.gimbalPitch[i] - sh.gimbalPitch[i]) * ease
                sh.gimbalYaw[i] += (t.gimbalYaw[i] - sh.gimbalYaw[i]) * ease
            }
            animation.initialised = true
        }

        // Wheels roll with the ground going by: angular velocity up x v / r,
        // in the craft's own axes. Off the ground they coast to a stop.
        attractor.surfaceVelocityAt(position, scratchGroundVelocity)
        scratchGroundVelocity.mulInPlace(-1.0).addInPlace(state.velocity)
        rotation.inverseRotate(scratchGroundVelocity, scratchGroundVelocity)
        attractor.toBodyFixed(position, bodyRotation, scratchCameraBodyFixed)
        val onGround = attractor.heightAboveTerrain(position, scratchCameraBodyFixed) < WHEEL_SPIN_HEIGHT
        val up = design.orientation.up
        scratchWheelSpin.setTo(up).crossInPlace(scratchGroundVelocity)

        val caps = StackCaps.forDesign(design, catalog)
        // How hurt, hot and dented it is, as the server last said.
        val condition = conditions.getOrPut(vessel.id) { VesselCondition.Values() }
        VesselCondition.decode(n, state.condition, condition)
        // Joints near their limit: the parts beyond them shudder about the
        // seam, and the seam throws sparks - for everyone watching.
        val strain = strains.getOrPut(vessel.id) { com.rm.apogee.render.StrainLook() }
        val flexing = condition.any && !mapMode &&
            strain.compute(design, defs, condition.load, lastRenderTime, vessel.id.toInt())
        // What comes off the craft - sparks off a seam, a thruster's puff -
        // leaves with it, in the ground's frame.
        attractor.surfaceVelocityAt(position, scratchDrift)
        scratchDrift.mulInPlace(-1.0).addInPlace(state.velocity)
        bodyRotation.inverseRotate(scratchDrift, scratchDrift)
        val inAir = attractor.atmosphere != null && attractor.altitudeOf(position) < attractor.atmosphereHeight
        // Which way an open chute streams: away from the motion through the air.
        val chuteTrail = Vec3().setTo(state.velocity).subInPlace(attractor.surfaceVelocityAt(position, Vec3()))
        if (predicted) prediction.replica?.air?.let { air -> chuteTrail.subInPlace(bodyRotation.rotate(air.wind, Vec3())) }
        if (chuteTrail.length > 0.5) chuteTrail.normalizeInPlace().negateInPlace() else chuteTrail.setTo(position).normalizeInPlace()
        for ((index, placed) in design.parts.withIndex()) {
            val def = defs[index] ?: continue

            if (flexing) strain.apply(index, placed.position, scratch) else scratch.setTo(placed.position)
            scratch.subInPlace(centreOfMass)
            rotation.rotate(scratch, scratch)
            scratch.addInPlace(position)
            val placedRotation = if (flexing) strain.turn[index] * placed.rotation else placed.rotation
            if (flexing && condition.load[index] >= com.rm.apogee.render.StrainLook.SPARKS_FROM && placed.parentIndex in 0 until n) {
                // The seam moves with the part it hangs from.
                strain.apply(placed.parentIndex, strain.seams[index], scratchSeam)
                scratchSeam.subInPlace(centreOfMass)
                rotation.rotate(scratchSeam, scratchSeam).addInPlace(position)
                attractor.toBodyFixed(scratchSeam, bodyRotation, scratchSeam)
                effects?.strain(
                    scratchSeam, scratchDrift, def.jointRadius.coerceAtLeast(0.2),
                    com.rm.apogee.render.StrainLook.sparkRate(condition.load[index].toDouble()), inAir,
                    com.rm.apogee.render.PartModels.bodyColour(placed.partId), animationDt, (vessel.id * 71 + index).toInt(),
                )
            }

            val anim = PartAnim(
                deflection = animation.shown.deflection[index],
                steer = animation.shown.steer[index],
                compression = animation.shown.compression[index],
                deploy = animation.shown.deploy[index],
                gimbalPitch = animation.shown.gimbalPitch[index],
                gimbalYaw = animation.shown.gimbalYaw[index],
            )
            PartModels.alignWheel(def, placed.rotation, design.orientation.forward, design.orientation.up, anim)
            PartModels.alignSurface(def, placed.rotation, Vec3().setTo(placed.position).subInPlace(centreOfMass), anim)
            val wheel = def.module<com.rm.apogee.core.part.Wheel>()
            if (wheel != null && anim.wheelAlign != null) {
                // The rate about this wheel's own axle, as mounted.
                val axle = placed.rotation.rotate(anim.wheelAlign!!.rotate(Vec3(1.0, 0.0, 0.0)))
                val rate = if (onGround) (scratchWheelSpin dot axle) / wheel.radius else 0.0
                animation.spin[index] += rate * animationDt
                // Dust off the tyre where it meets the ground.
                if (onGround && !mapMode) {
                    val contact = Vec3().setTo(scratch).addScaledInPlace(position.normalized(), -wheel.radius)
                    effects?.wheelDust(contact, scratchGroundVelocity.length, attractor, bodyRotation, animationDt, (vessel.id * 131 + index).toInt())
                }
            } else if (def.module<com.rm.apogee.core.part.Engine>() != null) {
                // A propeller turns with the throttle.
                animation.spin[index] += state.throttle * PROPELLER_RATE * animationDt
            }
            // A thruster block firing: puffs out of it, opposite its push, and
            // a chuff in the sound. The push is in the craft's axes, from the
            // replica for the craft being flown and the server for the rest.
            if (fresh && def.module<com.rm.apogee.core.part.Rcs>() != null) {
                val base = index * 3
                val push = animation.target.rcs
                if (base + 2 < push.size) {
                    scratchRcs.setTo(push[base], push[base + 1], push[base + 2])
                    val strength = scratchRcs.length.coerceAtMost(1.0)
                    if (strength > 0.02) {
                        soundCraft(vessel.id, position, state.velocity, attractor).rcs(strength)
                        if (!mapMode) {
                            // Out the way the gas goes: against the push, in the body's frame.
                            rotation.rotate(scratchRcs, scratchRcs).mulInPlace(-1.0 / strength)
                            bodyRotation.inverseRotate(scratchRcs, scratchRcs)
                            attractor.toBodyFixed(scratch, bodyRotation, scratchRcsAt)
                            effects?.rcsPuff(scratchRcsAt, scratchRcs, scratchDrift, strength, inAir, animationDt, (vessel.id * 97 + index).toInt())
                        }
                    }
                }
            }
            // A lit engine leaves a flame and smoke behind it.
            def.module<com.rm.apogee.core.part.Engine>()?.let { engine ->
                // The replica's parts are only this design's while their counts
                // agree: staging splits it at once, before the server's new
                // structure arrives, and indexing it by this design's parts
                // then ran off the end.
                // What it is actually putting out - nothing once its tank is
                // dry, however far the throttle is open - from the replica
                // for the craft being flown, the server's pose for the rest.
                val output = if (fresh) animation.target.output.getOrElse(index) { 0.0 } else 0.0
                if (output > 0.01) {
                    soundCraft(vessel.id, position, state.velocity, attractor).engine(
                        engine.exhaustKind, output, maxOf(engine.thrustVacuum, engine.thrustSeaLevel),
                        SoundScene.vacuumBuilt(engine.thrustSeaLevel, engine.thrustVacuum),
                    )
                    val partRotation = rotation * placedRotation
                    val mesh = def.mesh
                    val half = when (mesh) {
                        is com.rm.apogee.core.part.MeshSpec.Cylinder -> mesh.height * 0.5
                        is com.rm.apogee.core.part.MeshSpec.Cone -> mesh.height * 0.5
                        is com.rm.apogee.core.part.MeshSpec.Box -> mesh.height * 0.5
                        else -> 0.5
                    }
                    val radius = when (mesh) {
                        is com.rm.apogee.core.part.MeshSpec.Cylinder -> mesh.radius
                        is com.rm.apogee.core.part.MeshSpec.Cone -> mesh.bottomRadius
                        else -> 0.4
                    }
                    val thrust = engine.thrustDirection
                    val local = engine.waterProp?.copy() ?: Vec3(-thrust.x * half, -thrust.y * half, -thrust.z * half)
                    emitters.add(
                        EngineEmitter(
                            nozzle = partRotation.rotate(local).addInPlace(scratch),
                            out = partRotation.rotate(Vec3(-thrust.x, -thrust.y, -thrust.z)).normalizeInPlace(),
                            radius = radius * 0.8,
                            exitRadius = radius,
                            kind = engine.exhaustKind,
                            throttle = output,
                            velocity = state.velocity.copy(),
                            seed = (vessel.id * 31 + index).toInt(),
                        ),
                    )
                }
            }
            anim.spin = animation.spin[index] % (2 * Math.PI)
            // An open chute: its canopy, on its lines.
            def.module<com.rm.apogee.core.part.Parachute>()?.let { parachute ->
                val open = if (fresh) animation.shown.deploy.getOrElse(index) { 0.0 } else 0.0
                if (open > 0.01 && !mapMode) {
                    val chuteRadius = com.rm.apogee.render.ChuteLook.radius(parachute.deployedDragCoefficient * def.referenceArea)
                    // The flown craft's: widen the view once to take the canopy in.
                    if (vessel.id == client.controlledVessel && !framedChute) {
                        framedChute = true
                        camera.frameAtLeast(chuteRadius * 3.0)
                    }
                    // Its size by its drag: a small drogue, then the full canopy.
                    com.rm.apogee.render.ChuteLook.append(
                        scratch, chuteTrail, kotlin.math.sqrt(com.rm.apogee.core.part.Parachute.dragShare(open.coerceAtMost(1.0))),
                        com.rm.apogee.render.ChuteLook.radius(parachute.deployedDragCoefficient * def.referenceArea),
                        vessel.id * 131 + index, out,
                    )
                }
            }

            val partRotation = rotation * placedRotation
            val body = com.rm.apogee.render.PartModels.bodyColour(placed.partId)
            leaves.clear()
            PartModels.expand(def, caps[index], anim, leaves)
            val health = if (condition.any) condition.health[index] else 1f
            val heat = if (condition.any) condition.temperature[index] else 0f
            val dent = if (condition.any) ConditionLook.dent(condition.crumple, index * 3) else null
            // Badly hurt, it burns - in air; in vacuum there is nothing to burn in.
            if (health < BURNING_HEALTH && !mapMode && attractor.atmosphere != null &&
                attractor.altitudeOf(scratch) < attractor.atmosphereHeight * 0.6
            ) {
                attractor.toBodyFixed(scratch, bodyRotation, scratchBurn)
                effects?.burn(scratchBurn, 2.0 * def.jointRadius, animationDt, (vessel.id * 53 + index).toInt())
                soundCraft(vessel.id, position, state.velocity, attractor).burning++
            }
            for ((piece, leaf) in leaves.withIndex()) {
                val base = PartModels.colour(leaf.tint, body)
                val leafPosition = if (dent == null) leaf.position
                    else Vec3(leaf.position.x * dent.x, leaf.position.y * dent.y, leaf.position.z * dent.z)
                out.add(
                    RenderItem(
                        caps = leaf.caps,
                        shape = leaf.shape,
                        position = partRotation.rotate(leafPosition).addInPlace(scratch),
                        rotation = partRotation * leaf.rotation,
                        color = if (condition.any) ConditionLook.colour(base, health, heat) else base,
                        scale = dent?.let { ConditionLook.inLeaf(it, leaf.rotation) },
                        ambient = if (condition.any) ConditionLook.ambient(0.28f, heat) else 0.28f,
                        wrap = false,
                        key = RenderItem.partKey(vessel.id, partIdentity(placed), piece),
                    )
                )
            }
        }

        // The air it pushes through, made visible: vapour and re-entry glow.
        if (!mapMode) {
            val throughAir = Vec3().setTo(state.velocity).subInPlace(attractor.surfaceVelocityAt(position, Vec3()))
            if (predicted) prediction.replica?.air?.let { air -> throughAir.subInPlace(bodyRotation.rotate(air.wind, Vec3())) }
            // Where the craft meets the air first, and how thick it is there:
            // the vapour collar and the shock sit on the nose, not round the
            // whole craft (Dan: it matched neither the nose nor the drag).
            // The tip is the leading part's own end, on its own axis - found
            // along the line of flight through the middle, it sat metres to
            // the side whenever the craft was not flying dead straight.
            val nose = Vec3().setTo(position)
            val back = Vec3()
            var girth = 0.5
            val speed = throughAir.length
            if (speed > 1.0) {
                val ahead = Vec3().setTo(throughAir).mulInPlace(1.0 / speed)
                var lead = -Double.MAX_VALUE
                val offset = Vec3()
                val axis = Vec3()
                for ((index, placed) in design.parts.withIndex()) {
                    val def = defs[index] ?: continue
                    val (half, radius) = when (val mesh = def.mesh) {
                        is com.rm.apogee.core.part.MeshSpec.Cylinder -> mesh.height * 0.5 to mesh.radius
                        is com.rm.apogee.core.part.MeshSpec.Cone -> mesh.height * 0.5 to maxOf(mesh.topRadius, mesh.bottomRadius)
                        is com.rm.apogee.core.part.MeshSpec.Sphere -> mesh.radius to mesh.radius
                        is com.rm.apogee.core.part.MeshSpec.Box -> mesh.height * 0.5 to 0.0
                    }
                    if (def.module<com.rm.apogee.core.part.AeroSurface>() == null && radius > girth) girth = radius
                    rotation.rotate(offset.setTo(placed.position).subInPlace(centreOfMass), offset)
                    (rotation * placed.rotation).rotate(Vec3.unitY(), axis)
                    if ((axis dot ahead) < 0.0) axis.negateInPlace()
                    val tip = offset.addScaledInPlace(axis, half)
                    val reach = tip dot ahead
                    if (reach > lead) {
                        lead = reach
                        nose.setTo(position).addInPlace(tip)
                        back.setTo(axis).negateInPlace()
                    }
                }
            }
            effects?.aero(
                position, throughAir, designRadius(design, centreOfMass), attractor, bodyRotation,
                animationDt, lastRenderTime, vessel.id.toInt(), out, nose, girth, back.takeIf { it.lengthSq > 0.5 },
            )
        }
    }

    /**
     * Which part this is, in a way staging does not change: its kind and
     * where it sits in the design. Its index moves when the parts before it
     * go, and a part keyed by index was eased from another part's place.
     */
    private fun partIdentity(placed: com.rm.apogee.core.craft.PlacedPart): Int {
        val p = placed.position
        var h = placed.partId.hashCode()
        h = h * 31 + kotlin.math.round(p.x * 1_000.0).toInt()
        h = h * 31 + kotlin.math.round(p.y * 1_000.0).toInt()
        h = h * 31 + kotlin.math.round(p.z * 1_000.0).toInt()
        return h
    }

    /**
     * Mass-weighted centre of the design, using dry masses.
     *
     * An approximation: the server knows the true centre including propellant,
     * and does not currently send it. The error shifts a craft by tens of
     * centimetres along its axis, which is invisible at flight camera
     * distances - but it is a real gap, and the fix is a field on the
     * kinematics message rather than better guessing here.
     */
    /**
     * How far the craft's lowest point sits below its centre, metres.
     *
     * Negative, and measured along [up] in world axes so it follows the craft
     * as it tips. The height readout is taken from here rather than from the
     * centre of mass, because a player reads "AGL" as the gap between their
     * craft and the ground - and a thirteen-metre rocket parked on the pad
     * reported seven metres, which looks exactly like a bug in the terrain
     * even though the craft is seated.
     *
     * Measured against the same centre the renderer places parts around, so
     * the number and the picture cannot disagree.
     */
    private fun lowestPointOffset(design: CraftDesign, rotation: Quat, up: Vec3): Double {
        val centre = designCentreOfMass(design)
        var lowest = 0.0
        for (placed in design.parts) {
            val def = catalog[placed.partId] ?: continue
            for (local in def.contactPoints) {
                placed.rotation.rotate(local, scratchLowest)
                scratchLowest.addInPlace(placed.position).subInPlace(centre)
                rotation.rotate(scratchLowest, scratchLowest)
                val along = scratchLowest dot up
                if (along < lowest) lowest = along
            }
        }
        return lowest
    }

    private val scratchLowest = Vec3()
    private val scratchUp = Vec3()
    private val scratchNeighbour = Vec3()

    /**
     * Whether another craft is close enough and still enough to weld to.
     *
     * Answered from the craft the client already has, so the button appears
     * exactly when pressing it would do something. The server re-checks before
     * acting; this decides what to draw, not what is allowed.
     */
    @Volatile
    var joinable: Boolean = false
        private set

    private var joinFocus = -1L
    private var joinParts = -1

    /** When each craft first showed up already beside the flown one - just parted from it - or 0. */
    private val firstSeenNear = HashMap<Long, Long>()

    private fun neighbourInWeldingRange(
        focus: ClientVessel,
        state: com.rm.apogee.core.world.VesselKinematics,
    ): Boolean {
        val reach = designReach(focus.design)
        val now = System.nanoTime()
        // A different craft in hand - a stage dropped, a ring undocked, a
        // switch - starts afresh: whatever is beside it now was just parted
        // from it, or is where it was left.
        if (joinFocus != focus.id || joinParts != focus.design.parts.size) {
            joinFocus = focus.id; joinParts = focus.design.parts.size
            firstSeenNear.clear()
        }
        val hitched = client.latestSnapshot?.hitches.orEmpty()
        for (other in client.vessels) {
            if (other.id == focus.id) continue
            val theirs = other.latest ?: continue
            // Towed or towing: joined already, by the hitch.
            if (hitched.any { (it.vesselA == focus.id && it.vesselB == other.id) || (it.vesselB == focus.id && it.vesselA == other.id) }) continue
            // A stage just let go of is not something to join back on to:
            // drifting off at a metre a second it met the rule, and the
            // button blinked on and off as it went (Dan).
            val born = firstSeenNear.getOrPut(other.id) {
                if (theirs.position.distanceTo(state.position) < reach + designReach(other.design) + 20.0) now else 0L
            }
            if (born != 0L && now - born < JUST_PARTED_NANOS) continue
            scratchNeighbour.setTo(state.position).subInPlace(theirs.position)
            if (scratchNeighbour.length > reach + designReach(other.design)) continue
            scratchNeighbour.setTo(state.velocity).subInPlace(theirs.velocity)
            if (scratchNeighbour.length > World.JOIN_MAX_CLOSING_SPEED) continue
            return true
        }
        return false
    }

    /** Roughly how far this design reaches from its centre, metres. */
    private fun designReach(design: CraftDesign): Double {
        val centre = designCentreOfMass(design)
        var furthest = 0.0
        for (placed in design.parts) {
            val def = catalog[placed.partId] ?: continue
            scratchNeighbour.setTo(placed.position).subInPlace(centre)
            val reach = scratchNeighbour.length + def.boundsHalfExtents.length
            if (reach > furthest) furthest = reach
        }
        return furthest
    }

    private fun designCentreOfMass(design: CraftDesign): Vec3 {
        val centre = Vec3.zero()
        var total = 0.0
        for (placed in design.parts) {
            val def = catalog[placed.partId] ?: continue
            val mass = def.wetMass
            centre.addScaledInPlace(placed.position, mass)
            total += mass
        }
        return if (total > 0.0) centre.mulInPlace(1.0 / total) else centre
    }


    companion object {
        /** Below this share of its health a part is on fire. */
        private const val BURNING_HEALTH = 0.35f

        /** How long a correction between snapshots takes to ease out, s. */
        private const val SMOOTH_SECONDS = 0.1

        /** A disagreement bigger than this is a real jump, not jitter, m. */
        private const val SMOOTH_LIMIT = 20.0

        /** Real seconds between snapshots: the server sends them at 20 Hz. */
        private const val SNAPSHOT_SPACING = 0.05

        /** How much of the gap to the snapshots' time the warp clock closes each frame. */
        private const val WARP_CLOCK_EASE = 0.1

        /** Real seconds out, at the warp rate, past which the warp clock just jumps. */
        private const val WARP_CLOCK_SNAP = 0.5

        /** The least time between two warning tones. */
        private const val CAUTION_SPACING_NANOS = 4_000_000_000L

        /** The slide is resent when it has moved this much on an axis-length scale, at most this often. */
        /** Hands-off drift holding while lining up: ignored below this, m/s; command per m/s; most it asks for. */
        private const val DRIFT_DEADBAND = 0.02
        private const val DRIFT_GAIN = 0.4
        private const val DRIFT_MOST = 0.15

        /** Below this height, m, the stick slides along the ground; above, by the camera's own axes. */
        private const val SLIDE_GROUNDED_BELOW = 500.0
        private const val SLIDE_RESEND = 0.01
        private const val SLIDE_RESEND_NANOS = 100_000_000L

        /** How long, ns, a craft just parted from the flown one is not offered for joining. */
        private const val JUST_PARTED_NANOS = 10_000_000_000L

        /** How far from where a craft was lost its pieces are looked for, m; how quickly the look follows them. */
        private const val WRECK_PIECE_REACH = 150.0
        private const val WRECK_EASE = 0.08
        /** The size the camera frames a wreck as, m. */
        private const val WRECK_VIEW = 25.0

        /** How far, m, the dock readout looks for a partner's ring when no target is set. */
        private const val DOCK_READOUT_RANGE = 60.0

        /** A leg is at the end of its travel, locked or stowed, this near it. */
        private const val LEG_END = 0.995

        /** Surf: looked for this often; full at the water's edge up to [SHORE_FULL_BELOW] m, gone by [SHORE_SILENT_ABOVE]. */
        private const val SHORE_LOOK_NANOS = 250_000_000L
        private const val SHORE_FULL_BELOW = 25.0
        private const val SHORE_SILENT_ABOVE = 250.0
        /** Rings looked at for the water's edge, m, and how loud each makes it. */
        private val SHORE_RINGS = listOf(60.0 to 1.0, 180.0 to 0.7, 400.0 to 0.35)

        /** How far above the ground the camera is kept, m. */
        private const val CAMERA_CLEARANCE = 2.0

        /** How long the place a craft was lost goes on burning, s. */
        private const val WRECK_BURN_SECONDS = 60.0

        /** Most craft the target picker lists. */
        private const val MAX_TARGET_CHOICES = 12

        /** ~60 Hz. The server streams slower; the renderer interpolates. */
        private const val PRESENT_INTERVAL_MILLIS = 16L

        /** Stage cards at 5 Hz: gauges, read at a glance, not animated. */
        private const val STAGE_CARD_INTERVAL_NANOS = 200_000_000L

        /**
         * Furthest another craft is carried past its last snapshot. A few
         * snapshot intervals: enough to cover a late one, not enough to fly a
         * craft on through a stalled connection.
         */
        private const val MAX_EXTRAPOLATION_SECONDS = 0.25

        /** The most a snapshot is taken to be behind the present, s. */
        private const val MAX_SNAPSHOT_AGE = 0.5

        /** How quickly another craft's moving parts catch up with a new snapshot. */
        private const val POSE_EASING_SECONDS = 0.06

        /** Wheels turn with the ground below this height; above it they coast. */
        private const val WHEEL_SPIN_HEIGHT = 1.5

        /** A propeller's turn at full throttle, radians per second. */
        private const val PROPELLER_RATE = 60.0

        /**
         * Direction to the star, in the planet's frame.
         *
         * Fixed, and deliberately not straight down any axis: a sun exactly
         * overhead the launch site makes the terminator invisible and the
         * planet look flat. Replaced by real system geometry when the map view
         * needs the star's true position.
         */
        private val SUN_DIRECTION = com.rm.apogee.core.world.LaunchTime.SUN_DIRECTION

        /** Surface normal at the launch complex (latitude 0, longitude 0). */
        private val HOME_DIRECTION = Vec3(1.0, 0.0, 0.0)

        /** Marker size as a fraction of the framed orbit. */
        private const val MARKER_FRACTION = 0.022

        private val ORBIT_COLOR = floatArrayOf(0.70f, 0.62f, 1.0f, 1f)
        private val APOAPSIS_COLOR = floatArrayOf(0.49f, 1.0f, 0.70f, 1f)
        private val PERIAPSIS_COLOR = floatArrayOf(1.0f, 0.83f, 0.50f, 1f)
        private val CRAFT_COLOR = floatArrayOf(1.0f, 1.0f, 1.0f, 1f)

        /** The port a host listens on unless it is taken. */
        const val DEFAULT_PORT = 45_678

        /**
         * Starts a game others can join over the local network.
         *
         * Identical to [hostLocal] except that the server also listens on a
         * socket and announces itself - which is the point of having built
         * single-player as a one-player server in the first place. Nothing in
         * the gameplay path knows the difference.
         */
        fun hostLan(
            frameBus: FrameBus,
            perfHints: PerfHints?,
            playerName: String,
            clientId: String,
            serverName: String,
            design: CraftDesign? = null,
            catalog: PartCatalog = StockParts.catalog,
            scope: CoroutineScope,
            weather: com.rm.apogee.core.weather.WeatherIntensity? = null,
            clouds: com.rm.apogee.core.weather.CloudCover? = null,
        ): GameSession {
            val session = hostLocal(
                frameBus, perfHints, playerName, clientId, design, catalog, scope,
                // The name has to reach the server config, not just the beacon:
                // it is what the welcome message reports, so a joining player
                // sees the name they picked in the browser.
                serverName = serverName,
                weather = weather,
                clouds = clouds,
            )
            session.hostedServer?.let { session.openToLan(it, scope, serverName) }
            return session
        }

        /**
         * Joins a game hosted elsewhere.
         *
         * Returns a failure if the socket will not open - a host that has gone
         * away between being discovered and being tapped is entirely normal.
         */
        suspend fun join(
            frameBus: FrameBus,
            perfHints: PerfHints?,
            playerName: String,
            clientId: String,
            host: String,
            port: Int,
            catalog: PartCatalog = StockParts.catalog,
        ): Result<GameSession> = TcpTransport.connect(host, port).map { transport ->
            GameSession(
                frameBus = frameBus,
                perfHints = perfHints,
                catalog = catalog,
                hostedServer = null,
                client = GameClient(transport, playerName, catalog.contentHash, clientId),
                transport = transport,
            )
        }

        /**
         * Starts a solo game: a server in this process, reached over loopback.
         */
        fun hostLocal(
            frameBus: FrameBus,
            perfHints: PerfHints?,
            playerName: String,
            clientId: String,
            /** What to fly. Null falls back to the stock rocket. */
            design: CraftDesign? = null,
            catalog: PartCatalog = StockParts.catalog,
            scope: CoroutineScope,
            serverName: String = "Local Game",
            /**
             * The world to play in.
             *
             * Passed in rather than made here, because single player is not a
             * series of disconnected sandboxes: a craft landed on a hillside
             * has to still be there when the player comes back with the next
             * module. Building one here was what made every launch a fresh
             * universe - and made the whole business of landing modules and
             * welding them together unreachable from inside the game.
             */
            world: World = World.default(catalog),
            /** Launch site for [design]; null lets the design choose. */
            siteId: String? = null,
            /** Clear away the craft flown last time and start on a fresh one. */
            freshFlight: Boolean = false,
            /** Fly this craft of the player's, chosen from Resume Flight. */
            resumeVessel: Long? = null,
            /** How lively the weather is, from the player's setting. */
            weather: com.rm.apogee.core.weather.WeatherIntensity? = null,
            /** How cloudy, likewise. */
            clouds: com.rm.apogee.core.weather.CloudCover? = null,
            /** When in the day to launch: the clock moves on to it first. Not for Resume Flight. */
            launchTime: com.rm.apogee.core.world.LaunchTime = com.rm.apogee.core.world.LaunchTime.NOW,
        ): GameSession {
            // A launch at a chosen time of day: the clock moves on to the next
            // one at the site before anyone joins. The player's own world, so
            // nobody else's day is changed under them.
            if (launchTime != com.rm.apogee.core.world.LaunchTime.NOW && resumeVessel == null) {
                val site = siteId?.let { id -> World.launchSites.firstOrNull { it.id == id } }
                    ?: World.launchSiteFor(design ?: StockCraft.starterRocket(catalog), catalog)
                val body = world.system.body(site.bodyId)
                val up = Vec3(
                    kotlin.math.cos(site.latitude) * kotlin.math.cos(site.longitude),
                    kotlin.math.sin(site.latitude),
                    kotlin.math.cos(site.latitude) * kotlin.math.sin(site.longitude),
                )
                world.skipTo(launchTime.nextAt(body, up, com.rm.apogee.core.world.LaunchTime.SUN_DIRECTION, world.time))
            }
            val server = GameServer(
                world = world,
                config = ServerConfig(
                    name = serverName,
                    starterCraft = { StockCraft.starterRocket(it) },
                    // A design of their own is coming; do not also hand them a
                    // stock rocket to leave standing on the pad.
                    assignCraftOnJoin = design == null,
                    freshFlight = freshFlight && design == null && resumeVessel == null,
                    resumeVessel = resumeVessel,
                    weatherIntensity = weather,
                    cloudCover = clouds,
                    // The phone's own game: pause and warp, while nobody
                    // else is in it.
                    allowWarp = true,
                ),
            )
            val link = LoopbackTransportPair()
            server.accept(link.serverSide, scope)

            val client = GameClient(link.clientSide, playerName, catalog.contentHash, clientId)
            return GameSession(
                frameBus, perfHints, catalog, server, client, link.clientSide,
                launchDesign = design,
                launchSiteId = siteId,
            )
        }
    }
}

/** Everything the flight HUD shows, sampled once per published frame. */
class FlightTelemetry(
    /** Above the datum - what orbital mechanics and the atmosphere use. */
    val altitude: Double,
    /**
     * Above the ground directly below.
     *
     * Quite different from [altitude] over a mountain range, and the one a
     * pilot wants when landing.
     */
    val heightAboveGround: Double,
    val surfaceSpeed: Double,
    val orbitalSpeed: Double,
    val apoapsisAltitude: Double,
    val periapsisAltitude: Double,
    val timeToApoapsis: Double,
    val throttle: Double,
    val stage: Int,
    val inOrbit: Boolean,
    val craftName: String,
    /** Dynamic pressure, Pa. The number that decides whether a craft survives ascent. */
    val dynamicPressure: Double,
    /** Craft orientation, for the navball. */
    val rotation: Quat,
    /** Local vertical, for the navball. */
    val up: Vec3,
    /** Direction of travel relative to the surface, or null when stationary. */
    val prograde: Vec3?,
    /** Speed through the air, m/s: surface speed less the wind. */
    val airspeed: Double = 0.0,
    /** The wind's speed across the ground, m/s. */
    val windSpeed: Double = 0.0,
    /**
     * Where the wind comes from, degrees, relative to the craft's heading:
     * 0 dead ahead, 90 from the right, 180 from behind.
     */
    val windFrom: Double = 0.0,
    /** Whether there is air to speak of: the wind readouts are hidden in space. */
    val inAir: Boolean = false,
    /**
     * The navball's frame as it stands - [com.rm.apogee.core.world.NavFrame.AUTO]
     * resolved - and as chosen. [prograde] and the markers below are in it.
     */
    val frame: com.rm.apogee.core.world.NavFrame = com.rm.apogee.core.world.NavFrame.SURFACE,
    val frameChosen: com.rm.apogee.core.world.NavFrame = com.rm.apogee.core.world.NavFrame.AUTO,
    /** Out of the plane of travel, by the right hand: orbit normal. Null when still. */
    val normal: Vec3? = null,
    /** In the plane of travel, away from the planet. Null when still. */
    val radialOut: Vec3? = null,
    /** Toward the target, or null for none. */
    val toTarget: Vec3? = null,
    val targetName: String? = null,
    val targetDistance: Double = 0.0,
    /** How fast the target is closing, m/s: positive when getting nearer. */
    val closingSpeed: Double = 0.0,
    /** Direction of travel through the air, when in it and moving through it. */
    val throughAir: Vec3? = null,
    /** Where the nose points, degrees clockwise from north. */
    val heading: Double = 0.0,
    /** Up (positive) or down, over the ground, m/s. */
    val verticalSpeed: Double = 0.0,
    /** What SAS is holding, or null when it is off. */
    val sasMode: com.rm.apogee.core.world.SasMode? = null,
    /** Every part, how hurt and how hot, for the damage list; empty when all is well. */
    val parts: List<PartStatus> = emptyList(),
    /** The hottest part, as a share of what it can stand. */
    val heat: Double = 0.0,
    /** The hardest-loaded joint, as a share of its strength. */
    val structure: Double = 0.0,
    /** Parts hurt at all. */
    val damaged: Int = 0,
    /** Parts the craft has lost since it was launched. */
    val lost: Int = 0,
    /** What became of it, if it is gone: the crash report. Null while it flies. */
    val destroyed: String? = null,
) {
    /** One part's state, for the damage list. */
    class PartStatus(val title: String, val health: Double, val heat: Double, val load: Double)

    /** Worth a warning: nearing the heat or load a part fails at, or hurt. */
    val overheating: Boolean get() = heat > CAUTION
    val straining: Boolean get() = structure > CAUTION
    val hurt: Boolean get() = damaged > 0 || lost > 0

    val orbitalFrame: Boolean get() = frame == com.rm.apogee.core.world.NavFrame.ORBIT
    /** Above this, aerodynamic loads are worth warning about. */
    val highDynamicPressure: Boolean get() = dynamicPressure > MAX_Q_WARNING

    companion object {
        /**
         * Pascals at which the HUD starts warning.
         *
         * Not a structural limit - nothing breaks yet - but the point at which
         * a player steering hard is wasting thrust fighting the air, which is
         * the lesson the readout is there to teach.
         */
        const val MAX_Q_WARNING = 25_000.0

        /** Share of a limit where the HUD starts to warn. */
        const val CAUTION = 0.7


        val EMPTY = FlightTelemetry(
            altitude = 0.0, heightAboveGround = 0.0, surfaceSpeed = 0.0, orbitalSpeed = 0.0,
            apoapsisAltitude = 0.0, periapsisAltitude = 0.0, timeToApoapsis = 0.0,
            throttle = 0.0, stage = 0, inOrbit = false, craftName = "",
            dynamicPressure = 0.0, rotation = Quat.identity(), up = Vec3.unitY(),
            prograde = null,
        )

        /** For a craft that is gone: its name, what happened, and what it lost. */
        fun lost(name: String, report: String, lost: Int) = FlightTelemetry(
            altitude = 0.0, heightAboveGround = 0.0, surfaceSpeed = 0.0, orbitalSpeed = 0.0,
            apoapsisAltitude = 0.0, periapsisAltitude = 0.0, timeToApoapsis = 0.0,
            throttle = 0.0, stage = 0, inOrbit = false, craftName = name,
            dynamicPressure = 0.0, rotation = Quat.identity(), up = Vec3.unitY(),
            prograde = null, lost = lost, destroyed = report,
        )

        fun from(
            vessel: ClientVessel,
            attractor: com.rm.apogee.core.orbit.CelestialBody,
            throttle: Double,
            /** The craft's position in the body's own frame, for ground height. */
            bodyFixedPosition: Vec3,
            /** Metres from the craft's centre down to its lowest point. */
            lowestPointOffset: Double = 0.0,
            /** The air the craft is in, body-fixed wind, or null for none. */
            air: com.rm.apogee.core.weather.AirSample? = null,
            /** The body's rotation now, to turn the wind into the world's frame. */
            bodyRotation: Quat? = null,
            /** The craft's forward, design axis, for which way the wind comes from. */
            forwardAxis: Vec3 = Vec3.unitY(),
            navFrame: com.rm.apogee.core.world.NavFrame = com.rm.apogee.core.world.NavFrame.AUTO,
            /** The target's last state, or null for none. */
            target: com.rm.apogee.core.world.VesselKinematics? = null,
            targetName: String? = null,
            sasMode: com.rm.apogee.core.world.SasMode? = null,
            /** Its parts' condition, as the server last said. */
            condition: com.rm.apogee.core.world.VesselCondition.Values? = null,
            /** Its part definitions, in design order. */
            defs: List<com.rm.apogee.core.part.PartDef?> = emptyList(),
            /** Joint loads from the local replica, or null. */
            jointLoad: FloatArray? = null,
            /** How many parts it has lost. */
            lost: Int = 0,
        ): FlightTelemetry {
            val state = vessel.latest ?: return EMPTY
            val orbit = Orbit(
                position = state.position,
                velocity = state.velocity,
                mu = attractor.gravitationalParameter,
            )
            val surfaceVelocity = attractor.surfaceVelocityAt(state.position)
            val relative = state.velocity - surfaceVelocity
            val altitude = attractor.altitudeOf(state.position)
            val density = attractor.atmosphere?.densityAt(altitude) ?: 0.0

            // The wind, and the craft's motion through the air it makes.
            val up = state.position.normalized()
            val wind = if (air != null && bodyRotation != null) bodyRotation.rotate(air.wind, Vec3()) else Vec3()
            val throughAir = relative - wind

            // The navball's markers, computed exactly as stability assist
            // computes what it holds - the same function on both sides.
            val nav = com.rm.apogee.core.world.Navigation.compute(
                state.position, state.velocity, attractor, navFrame,
                target?.position, target?.velocity, com.rm.apogee.core.world.NavDirections(),
            )
            val moving = nav.velocity.length > 1.0
            val nose = state.rotation.rotate(Vec3.unitY(), Vec3())
            // Our motion toward it: positive while the gap is shrinking.
            val closing = if (target != null && nav.hasTarget) (state.velocity - target.velocity) dot nav.toTarget else 0.0
            val horizontalWind = wind.copy().addScaledInPlace(up, -(wind dot up))
            val heading = state.rotation.rotate(forwardAxis, Vec3())
            heading.addScaledInPlace(up, -(heading dot up))
            val windFrom = if (horizontalWind.length > 0.3 && heading.length > 1e-6) {
                heading.normalizeInPlace()
                val from = horizontalWind.copy().mulInPlace(-1.0).normalizeInPlace()
                val right = heading.cross(up)
                Math.toDegrees(kotlin.math.atan2(from dot right, from dot heading))
            } else 0.0

            return FlightTelemetry(
                altitude = altitude,
                heightAboveGround = (
                    attractor.heightAboveTerrain(state.position, bodyFixedPosition) +
                        lowestPointOffset
                    ).coerceAtLeast(0.0),
                surfaceSpeed = relative.length,
                orbitalSpeed = state.velocity.length,
                apoapsisAltitude = orbit.apoapsis - attractor.radius,
                periapsisAltitude = orbit.periapsis - attractor.radius,
                timeToApoapsis = orbit.timeToApoapsis,
                throttle = throttle,
                stage = vessel.currentStage,
                inOrbit = orbit.isBound &&
                    orbit.periapsis > attractor.radius + attractor.atmosphereHeight,
                craftName = vessel.name,
                dynamicPressure = 0.5 * density * relative.lengthSq,
                rotation = state.rotation.copy(),
                up = state.position.normalized(),
                // Below walking pace the direction of travel is noise, and a
                // prograde marker jittering around the navball is worse than
                // none at all.
                prograde = if (moving) nav.prograde.copy() else null,
                frame = nav.frame,
                frameChosen = navFrame,
                normal = if (moving) nav.normal.copy() else null,
                radialOut = if (moving) nav.radialOut.copy() else null,
                toTarget = if (nav.hasTarget) nav.toTarget.copy() else null,
                targetName = if (nav.hasTarget) targetName else null,
                targetDistance = nav.targetDistance,
                closingSpeed = closing,
                throughAir = if (density > 1e-3 && throughAir.length > 5.0) throughAir.normalized() else null,
                heading = com.rm.apogee.core.world.Navigation.heading(state.position, nose),
                verticalSpeed = relative dot up,
                sasMode = sasMode,
                airspeed = throughAir.length,
                windSpeed = horizontalWind.length,
                windFrom = windFrom,
                inAir = density > 1e-3,
            ).withCondition(condition, defs, jointLoad, lost)
        }

        private fun FlightTelemetry.withCondition(
            condition: com.rm.apogee.core.world.VesselCondition.Values?,
            defs: List<com.rm.apogee.core.part.PartDef?>,
            jointLoad: FloatArray?,
            lost: Int,
        ): FlightTelemetry {
            val loads = jointLoad?.takeIf { it.size == defs.size }
            val hot = condition?.takeIf { it.any && it.health.size == defs.size }
            if (hot == null && loads == null && lost == 0) return this
            var heat = 0.0; var structure = 0.0; var damaged = 0
            val parts = defs.indices.mapNotNull { i ->
                val def = defs[i] ?: return@mapNotNull null
                val health = hot?.health?.get(i)?.toDouble() ?: 1.0
                // From a mild day's warmth to what it fails at: at ambient, nothing.
                val ambient = com.rm.apogee.core.craft.Vessel.AMBIENT_TEMPERATURE
                val share = hot?.let { ((it.temperature[i] - ambient) / (def.heatLimit - ambient)).coerceAtLeast(0.0) } ?: 0.0
                val load = loads?.get(i)?.toDouble() ?: 0.0
                heat = maxOf(heat, share)
                structure = maxOf(structure, load)
                if (health < 0.99) damaged++
                PartStatus(def.title, health, share, load)
            }
            return FlightTelemetry(
                altitude = altitude, heightAboveGround = heightAboveGround, surfaceSpeed = surfaceSpeed,
                orbitalSpeed = orbitalSpeed, apoapsisAltitude = apoapsisAltitude, periapsisAltitude = periapsisAltitude,
                timeToApoapsis = timeToApoapsis, throttle = throttle, stage = stage, inOrbit = inOrbit,
                craftName = craftName, dynamicPressure = dynamicPressure, rotation = rotation, up = up,
                prograde = prograde, airspeed = airspeed, windSpeed = windSpeed, windFrom = windFrom, inAir = inAir,
                frame = frame, frameChosen = frameChosen, normal = normal, radialOut = radialOut, toTarget = toTarget,
                targetName = targetName, targetDistance = targetDistance, closingSpeed = closingSpeed,
                throughAir = throughAir, heading = heading, verticalSpeed = verticalSpeed, sasMode = sasMode,
                parts = parts, heat = heat, structure = structure, damaged = damaged, lost = lost,
            )
        }
    }
}
