package com.rm.apogee.game

import com.rm.apogee.core.craft.CraftDesign
import com.rm.apogee.core.craft.CraftOrientation
import com.rm.apogee.core.craft.StockCraft
import com.rm.apogee.core.math.Quat
import com.rm.apogee.core.math.Vec3
import com.rm.apogee.core.orbit.CelestialBody
import com.rm.apogee.core.orbit.Orbit
import com.rm.apogee.core.orbit.SolarSystem
import com.rm.apogee.core.orbit.SystemData
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
import com.rm.apogee.net.LoopbackTransportPair
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
import com.rm.apogee.platform.AtomicLong
import com.rm.apogee.platform.System
import com.rm.apogee.platform.synchronized
import com.rm.apogee.platform.format
import com.rm.apogee.platform.Log
import com.rm.apogee.core.math.Math
import kotlin.concurrent.Volatile

/**
 * One play session: a server, a client, and the bridge to the renderer.
 *
 * Single player is a one-player server. The client here talks over a [LoopbackTransportPair], but
 * through exactly the same [GameClient] and protocol a networked client uses, so the multiplayer
 * path gets used every time anyone plays solo, not only when two devices are in the room. Joining a
 * remote host is the same code with a socket transport in place of the loopback.
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
     * A craft to put on the pad once connected, or null to fly what's already there.
     *
     * It's sent as a command instead of handed to the server as its "starter craft", because in a
     * persistent world the player usually already owns something. The starter is only for an empty
     * world, and a launch has to work either way.
     */
    private val launchDesign: CraftDesign? = null,
    /** Where to put [launchDesign], or null to let the design decide. */
    private val launchSiteId: String? = null,
) {
    val camera = CameraController()

    /**
     * A second camera for map view, with its own distance range.
     *
     * It's separate instead of a mode flag on one camera, because the two want completely different
     * things. The flight camera frames a 14 m rocket from tens of metres, and the map camera frames
     * a 700 km orbit from thousands of kilometres. Sharing one would mean a zoom range spanning six
     * orders of magnitude, and the player's pinch would be useless at both ends.
     */
    val mapCamera = CameraController(
        upReference = UpReference.FIXED,
        // Close enough to see a rover's course across a few kilometres of ground.
        minDistance = 2_000.0,
        // Out to the whole system, because Ultima's orbit is five hundred million kilometres
        // across.
        maxDistance = 3.0e13,
    )

    /** Whether the map view is showing. */
    @Volatile var mapMode: Boolean = false

    /**
     * Local physics for the craft this client is flying.
     *
     * The server stays in charge. Every snapshot resets this and simulates forward again. It's here
     * so the throttle responds on the frame you move it, instead of after a round trip and up to a
     * snapshot interval.
     */
    private val prediction = ClientPrediction(catalog)

    /** Copies of the player's controls, so prediction sees the same inputs. */
    private var localThrottle = 0.0
    private var localPitch = 0.0
    private var localYaw = 0.0
    private var localRoll = 0.0
    private var localSas = false
    private var localSasMode = com.rm.apogee.core.world.SasMode.HOLD
    private var localNavFrame = com.rm.apogee.core.world.NavFrame.AUTO
    private var localTarget = -1L
    private var localBrakes = false
    private var localFlaps = false
    private var localReverse = false
    /** Thrusters armed, and the slide asked for: the stick's right/away and the buttons' down/up. */
    private var localRcs = false
    @Volatile private var slideRight = 0.0
    @Volatile private var slideAway = 0.0
    @Volatile private var slideLift = 0.0
    /** The slide as the craft was last told it, in its own axes, and when. */
    private val localTranslate = Vec3()
    private var translateSentNanos = 0L
    private var rcsFor = -1L
    private var seenFelledRevision = -1

    private var lastReconciledTick = -1L

    /** The server's time, followed smoothly. See [ServerClock]. */
    private val serverClock = ServerClock()
    private var clockSampledTick = -1L
    private var lastAdvanceNanos = 0L
    /** The server's time as followed, when the replica was last moved on. NaN for none. */
    private var lastPresent = Double.NaN

    /** Published for the debug overlay. */
    val lastFrameBuildNanos = AtomicLong(0)

    /** Debug: the sea is neither built nor drawn, to measure what it costs. */
    @Volatile var debugHideSea = false

    /** How long the last sea build took, in ms, for the debug performance log. */
    val seaBuildMillis: Double get() = seaScene?.lastBuildMillis ?: 0.0

    /**
     * How long the last cloud listing took, in ms, and how many cloud lobes are in view, for the
     * debug performance log.
     */
    val cloudListMillis: Double get() = cloudScene?.lastListMillis ?: 0.0
    val framesPublished = AtomicLong(0)

    /** Telemetry for the HUD, refreshed each published frame. */
    @Volatile var telemetry: FlightTelemetry = FlightTelemetry.EMPTY
        private set

    private var serverJob: Job? = null
    private var clientJob: Job? = null
    private var presentJob: Job? = null
    private var network: NetworkHost? = null

    /** The port this session is hosting on, or null when it isn't hosting. */
    var hostedPort: Int? = null
        private set

    /** Players connected to the game this session is hosting. */
    val hostedPlayerCount: Int get() = hostedServer?.playerCount ?: 0

    /**
     * Runs [task] on the hosted server's tick thread, between steps. Not at all when joined to
     * someone else's game.
     */
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
     * It's created once a quality tier is known, because how finely to sample is the one thing
     * about terrain that depends on the device.
     */
    private var terrainBuilder: TerrainBuilder? = null
    private var drawFarSurface = true
    private var chunkRange = 0.0

    private var lastRenderTime = 0.0

    /** How far a design reaches from its centre of mass, in metres, cached per design. */
    private val radii = com.rm.apogee.platform.identityMapOf<CraftDesign, Double>()

    /**
     * Where the pilot sits in [design]: its crewed command part, or a probe core if it has no
     * crew, by part index. -1 for neither.
     */
    private fun seatOf(design: CraftDesign): Int = seats.getOrPut(design) {
        val commands = design.parts.indices.filter { catalog[design.parts[it].partId]?.module<com.rm.apogee.core.part.Command>() != null }
        commands.firstOrNull { (catalog[design.parts[it].partId]?.module<com.rm.apogee.core.part.Command>()?.crewCapacity ?: 0) > 0 }
            ?: commands.firstOrNull() ?: -1
    }
    private val seats = HashMap<CraftDesign, Int>()

    /** The flown craft's part the cockpit camera sits inside, left undrawn, or -1. */
    @Volatile private var cockpitPart = -1

    private fun designRadius(design: CraftDesign, centreOfMass: Vec3): Double = radii.getOrPut(design) {
        design.parts.maxOfOrNull { it.position.distanceTo(centreOfMass) + 0.8 } ?: 1.0
    }

    /** Smoke, dust, spray, flames, rain and lightning. */
    private var effects: Effects? = null
    private val frameEmitters = ArrayList<EngineEmitter>()

    /** Clouds and the camera's air, while the world has weather and the camera is in it. */
    private var cloudScene: CloudScene? = null
    private var seaScene: SeaScene? = null

    // The sea at the camera this frame, for the ears.
    private var seaHeard = 0.0
    private var seaRough = 0.0
    private var seaStorm = 0.0

    /** Each hull's bow height over the water last frame, by craft and part, for hearing it slap into a wave. */
    private val bowGaps = HashMap<Long, Double>()
    private val slapSample = com.rm.apogee.core.sea.SeaSample()

    private fun smoothstepD(a: Double, b: Double, x: Double): Double {
        val t = ((x - a) / (b - a)).coerceIn(0.0, 1.0)
        return t * t * (3.0 - 2.0 * t)
    }

    private val cloudCamera = Vec3()

    /**
     * The solar system, built once.
     *
     * It used to be rebuilt on every frame: three bodies, their orbits and their terrain fields,
     * sixty times a second, thrown away each time. Nothing about it changes.
     */
    private val system = SolarSystem.defaultSystem()

    val connected: Boolean get() = client.connected
    val rejectionReason: String? get() = client.rejectionReason

    /** The scope for terrain builds, so they can outlive a single frame. */
    private lateinit var terrainScope: CoroutineScope

    /**
     * Supplies the renderer's terrain hand-off and the device's quality tier.
     *
     * It's called once the GL thread has judged the device. Until then there's no sensible answer
     * to how finely to sample.
     */
    private var scatterStreamer: ScatterStreamer? = null

    fun attachTerrain(source: com.rm.apogee.render.TerrainSource, quality: QualityTier) {
        // Called again whenever the GL surface is recreated. The builder already knows the GPU lost
        // its chunks (the renderer tells it), and rebuilds just those. Replacing it threw away
        // everything built and left the old one's workers running, publishing draw lists of their
        // own into the same source.
        if (terrainBuilder != null && terrainQuality == quality) return
        terrainBuilder?.let {
            Log.i("ApogeeTerrain", "builder replaced: $terrainQuality -> $quality")
            it.stop()
            // What the old builder left in the source, the new one would never know it had, and it
            // would wait on it forever behind the loading screen. A tier detected differently from
            // the one remembered did exactly that.
            source.releaseAllChunks()
        }
        terrainQuality = quality
        terrainBuilder = TerrainBuilder(source, quality)
        scatterStreamer = ScatterStreamer(source.scatter, quality)
    }

    private var terrainQuality: QualityTier? = null

    /**
     * Whether the world is fit to be shown, meaning the craft has ground under it.
     *
     * It's false until the first terrain patch is built, which is why the flight view holds back
     * instead of showing a craft hanging over a globe that hasn't caught up with it yet.
     */
    val surfaceReady: Boolean
        get() = terrainBuilder?.patchReady ?: false

    fun start(scope: CoroutineScope) {
        terrainScope = scope
        serverJob = hostedServer?.start(scope)
        clientJob = client.connect(scope)
        // Once the handshake lands, put the launched craft on the pad. It waits for `connected`
        // instead of sending straight away, because the server refuses anything before the
        // handshake is done.
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
                refreshScreenAttitude()
                lastFrameBuildNanos.set(System.nanoTime() - started)
                perfHints?.reportActualWorkDuration(lastFrameBuildNanos.get())
                delay(PRESENT_INTERVAL_MILLIS)
            }
        }
    }

    fun stop() {
        presentJob?.cancel(); presentJob = null
        // Everything held lets go, for good. Cancelling doesn't wait for a frame that's already
        // being built, and one finishing after this would put its scene back. The sea went on
        // playing over the menu, which I heard.
        synchronized(soundLock) {
            soundStopped = true
            AudioEngine.scene(0, IntArray(0), IntArray(0), IntArray(0), FloatArray(0))
        }
        clientJob?.cancel(); clientJob = null
        serverJob?.cancel(); serverJob = null
        network?.stop?.invoke(); network = null
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
     * This only means anything when hosting. The host's own client stays on the loopback transport
     * instead of looping back through a socket. There's no reason to serialise and checksum its own
     * commands, and keeping it in-process means a host with no network can still play.
     */
    private fun openToLan(server: GameServer, scope: CoroutineScope, serverName: String) {
        val opened = openToNetwork(server, scope, serverName, catalog.contentHash) ?: return
        network = opened
        hostedPort = opened.port
    }

    // --- commands ------------------------------------------------------------

    private suspend fun withControlledVessel(block: suspend (Long) -> Unit) {
        client.controlledVessel?.let { block(it) }
    }

    /** How the world's clock is running, as the last snapshot said. */
    val clock: com.rm.apogee.core.world.Snapshot? get() = client.latestSnapshot

    /** Asks for the world to run at [rate] times real time. 0 pauses it. */
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
        stickUp = pitch; stickRight = yaw; stickRoll = roll
        // Someone on foot: up walks on, and right turns them about their own height, their roll.
        if (controlledIsSuit) {
            sendAttitude(pitch, 0.0, yaw + roll)
            return
        }
        if (steerByScreen) {
            val (p, y, r) = screenCommand(pitch, yaw, roll) ?: Triple(pitch, yaw, roll)
            sendAttitude(p, y, r)
        } else {
            sendAttitude(pitch, yaw, roll)
        }
    }

    /** How Settings says to read the stick, for craft the chip hasn't been touched for. */
    @Volatile var steeringStyle: com.rm.apogee.settings.SteeringStyle = com.rm.apogee.settings.SteeringStyle.ROTORCRAFT_AND_ROCKETS

    // This flight's choice from the chip, until another craft is taken in hand.
    @Volatile private var steeringChoice: Boolean? = null
    @Volatile private var steeringFor: Long? = null

    /** Whether the stick is read by the screen for the craft being flown, or by its nose. */
    val steerByScreen: Boolean
        get() {
            val id = client.controlledVessel
            if (id != steeringFor) { steeringFor = id; steeringChoice = null }
            return steeringChoice ?: steeringStyle.byScreen(controlledKind, controlledOrientation)
        }

    /** Reads the stick by the screen, or by the nose, for this flight. */
    fun chooseSteering(byScreen: Boolean) {
        steeringFor = client.controlledVessel
        steeringChoice = byScreen
        val up = stickUp; val right = stickRight; val roll = stickRoll
        terrainScope.launch { setAttitude(up, right, roll) }
    }

    /**
     * Whether reading by the screen tips the craft toward the stick (anything that flies level, and
     * every rotorcraft) instead of pointing its nose there (a craft built standing up).
     */
    val screenTilts: Boolean
        get() = controlledOrientation != CraftOrientation.VERTICAL || controlledKind == com.rm.apogee.core.craft.CraftKind.ROTORCRAFT

    /** What kind of craft is being flown. */
    val controlledKind: com.rm.apogee.core.craft.CraftKind?
        get() {
            val id = client.controlledVessel ?: return null
            val design = client.vessels.firstOrNull { it.id == id }?.design ?: return null
            if (design !== kindDesign) {
                kindDesign = design
                kind = com.rm.apogee.core.craft.CraftKind.of(design, catalog)
            }
            return kind
        }
    @Volatile private var kindDesign: com.rm.apogee.core.craft.CraftDesign? = null
    @Volatile private var kind: com.rm.apogee.core.craft.CraftKind? = null

    /**
     * The stick read by the screen, as the craft's pitch, yaw and roll. A craft that tips leans
     * toward it, with the roll buttons turning it about its up. One built standing up turns its
     * nose toward it. Null before there's a view or a craft.
     */
    private fun screenCommand(up: Double, right: Double, roll: Double): Triple<Double, Double, Double>? {
        if (!screenTilts) {
            val (p, y) = screenAttitude(up, right) ?: return null
            return Triple(p, y, roll)
        }
        val replica = prediction.replica ?: return null
        val craft = replica.body.orientation
        val worldUp = replica.body.position.normalized()
        val turn = ScreenStick.tilt(cameraRotation, craft, worldUp, replica.design.orientation.up, up, right, roll, Vec3())
        // x is pitch, y roll and z yaw, in the craft's own axes.
        return Triple(turn.x, turn.z, turn.y)
    }

    private suspend fun sendAttitude(pitch: Double, yaw: Double, roll: Double) {
        localPitch = pitch; localYaw = yaw; localRoll = roll
        lastAttitudeNanos = System.nanoTime()
        pushControlsToPrediction()
        withControlledVessel { client.send(Command.SetAttitude(it, pitch, yaw, roll)) }
    }

    // The stick as the thumb holds it, before it's turned into the craft's axes.
    @Volatile private var stickUp = 0.0
    @Volatile private var stickRight = 0.0
    @Volatile private var stickRoll = 0.0
    @Volatile private var lastAttitudeNanos = 0L

    /**
     * The stick, read by the screen, as the craft's own pitch and yaw. Right tips the nose toward
     * the screen's right, and up tips it away from the camera, as if your thumb were holding the
     * craft itself. A rocket is round and the camera starts wherever it starts, so turning it about
     * its own axes went a different way on screen every time. On a symmetrical rocket I couldn't
     * tell which control went which way until I tested them. Null before there's a view or a craft.
     */
    private fun screenAttitude(up: Double, right: Double): Pair<Double, Double>? {
        val craft = prediction.replica?.body?.orientation ?: return null
        return ScreenStick.attitude(cameraRotation, craft, up, right)
    }

    /**
     * Keeps a held stick meaning the same thing on screen as the craft turns under it and the
     * camera moves round it. It gets sent again when it has drifted.
     */
    private fun refreshScreenAttitude() {
        if (stickUp == 0.0 && stickRight == 0.0 && stickRoll == 0.0) return
        if (controlledIsSuit || !steerByScreen) return
        if (System.nanoTime() - lastAttitudeNanos < ATTITUDE_REFRESH_NANOS) return
        val (pitch, yaw, roll) = screenCommand(stickUp, stickRight, stickRoll) ?: return
        if (kotlin.math.abs(pitch - localPitch) < 0.03 && kotlin.math.abs(yaw - localYaw) < 0.03 &&
            kotlin.math.abs(roll - localRoll) < 0.03) return
        lastAttitudeNanos = System.nanoTime()
        terrainScope.launch { sendAttitude(pitch, yaw, roll) }
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
     * Arms the thrusters, or stands them down (and stops any slide). It takes effect here straight
     * away (the button shows it on the next frame) and is sent on its way.
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
     * What the player's thumbs ask the thrusters to slide: [right] and [away] from the camera, and
     * [lift] up from the planet, each -1..1. It's turned into the craft's axes every frame, as the
     * camera and the craft turn.
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
     * Keeps the thrusters' slide pointing where the thumbs mean as the camera and craft turn. It's
     * worked out each frame, and sent when it has moved enough, at most ten times a second, and
     * straight away when it stops.
     */
    private fun updateSlide(focusId: Long, craftRotation: Quat, focusPosition: Vec3) {
        if (rcsFor != focusId) {
            // Another craft in hand, so its thrusters start stood down, on the server too, where
            // they might have been left armed.
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
        // Fine near the middle of the stick, and full at its edge: cubed. Linear, the lightest
        // touch was metres a second in orbit, because the thrusters are sized to walk a landed
        // module, not to dock.
        fun fine(x: Double) = x * x * x
        val grounded = telemetry.heightAboveGround < SLIDE_GROUNDED_BELOW
        val wanted = SlideControl.command(cameraRotation, craftRotation, up, fine(slideRight), fine(slideAway), fine(slideLift), Vec3(), grounded)
        // Lining up to dock, hands off: the thrusters take out drift across the line to the other
        // port, and leave the closing speed alone. Nudge it toward the port, let go, and it coasts
        // in on the line.
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
     * The flight HUD's stage cards, refreshed a few times a second from the replica: what's
     * burning, and what each stage still to fire will do.
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

    /** Whether the craft being flown has sun wings or dishes that fold out. */
    val controlledHasFoldouts: Boolean
        get() {
            val id = client.controlledVessel ?: return false
            val design = client.vessels.firstOrNull { it.id == id }?.design ?: return false
            return design.parts.any { placed ->
                val def = catalog[placed.partId] ?: return@any false
                def.module<com.rm.apogee.core.part.SolarPanel>()?.deployable == true ||
                    def.module<com.rm.apogee.core.part.Antenna>()?.deployable == true
            }
        }

    private val controlledClient: ClientVessel?
        get() = client.controlledVessel?.let { id -> client.vessels.firstOrNull { it.id == id } }

    /** How the craft being flown gets about, worked out again only when it changes. */
    val controlledGoing: Going
        get() {
            val vessel = controlledClient ?: return Going.FLY
            if (vessel.design !== goingDesign || vessel.anchored != goingAnchored) {
                goingDesign = vessel.design; goingAnchored = vessel.anchored
                going = Going.of(vessel.design, catalog, vessel.anchored)
            }
            return going
        }
    private var goingDesign: com.rm.apogee.core.craft.CraftDesign? = null
    private var goingAnchored = false
    private var going = Going.FLY

    /** Whether the craft being flown is someone out on EVA, in their suit. */
    val controlledIsSuit: Boolean
        get() = controlledClient?.design?.parts?.singleOrNull()?.partId == com.rm.apogee.core.world.World.SUIT_PART

    /** How many the craft being flown seats. */
    val controlledSeats: Int
        get() = controlledClient?.design?.parts?.sumOf { catalog[it.partId]?.let { d -> com.rm.apogee.core.crew.Crew.seatsIn(d) } ?: 0 } ?: 0

    /** Who's aboard the craft being flown, for the crew card. */
    val crewCard: List<HudState.CrewSeat>
        get() {
            val craft = controlledClient ?: return emptyList()
            val roster = client.roster.associateBy { it.id }
            val free = craft.design.parts.indices.filter { i ->
                (catalog[craft.design.parts[i].partId]?.let { com.rm.apogee.core.crew.Crew.seatsIn(it) } ?: 0) > (craft.crew.getOrNull(i)?.size ?: 0)
            }
            return craft.crew.flatMapIndexed { part, seat ->
                seat.map { id ->
                    val member = roster[id]
                    HudState.CrewSeat(
                        id = id,
                        name = member?.name ?: "Crew",
                        where = catalog[craft.design.parts[part].partId]?.title ?: "",
                        mine = member != null,
                        canMove = free.any { it != part },
                    )
                }
            }
        }

    /** Crew member [crewId] goes out on EVA. */
    suspend fun eva(crewId: Long) = withControlledVessel { client.send(Command.Eva(it, crewId)) }

    /** Crew member [crewId] moves to the next free seat after theirs in the craft being flown. */
    suspend fun moveCrew(crewId: Long) {
        val craft = controlledClient ?: return
        val at = craft.crew.indexOfFirst { crewId in it }
        val parts = craft.design.parts.indices
        val next = (1 until parts.count()).map { (at + it) % parts.count() }.firstOrNull { i ->
            (catalog[craft.design.parts[i].partId]?.let { com.rm.apogee.core.crew.Crew.seatsIn(it) } ?: 0) > (craft.crew.getOrNull(i)?.size ?: 0)
        } ?: return
        client.send(Command.TransferCrew(craft.id, crewId, next))
    }

    suspend fun board() = withControlledVessel { client.send(Command.Board(it)) }
    suspend fun jump() = withControlledVessel { client.send(Command.Jump(it)) }
    suspend fun grab(on: Boolean) = withControlledVessel { client.send(Command.Grab(it, on)) }
    suspend fun plantFlag() = withControlledVessel { client.send(Command.PlantFlag(it)) }

    /** Whether the craft being flown has drills, and converters. */
    val controlledHasDrill: Boolean get() = controlledHas { it.hasModule<com.rm.apogee.core.part.Drill>() }
    val controlledHasConverter: Boolean get() = controlledHas { it.hasModule<com.rm.apogee.core.part.Converter>() }

    private inline fun controlledHas(test: (com.rm.apogee.core.part.PartDef) -> Boolean): Boolean {
        val id = client.controlledVessel ?: return false
        val design = client.vessels.firstOrNull { it.id == id }?.design ?: return false
        return design.parts.any { placed -> catalog[placed.partId]?.let(test) == true }
    }

    /** The flown craft's power and link home, as the server last said. Null until it has. */
    val powerReadout: HudState.PowerReadout?
        get() {
            val id = client.controlledVessel ?: return null
            val systems = client.systems?.takeIf { it.vessel == id } ?: return null
            return HudState.PowerReadout(
                charge = systems.charge,
                capacity = systems.capacity,
                net = systems.net,
                powered = systems.powered,
                needsSignal = systems.needsSignal,
                signal = systems.signal,
                relays = systems.relays.size,
                controllable = systems.controllable,
                deployed = systems.deployed,
                drilling = systems.drilling,
                refining = systems.refining,
                drillState = systems.drillState,
                survey = systems.survey,
                ore = systems.ore,
                water = systems.water,
                blocked = systems.blocked,
                boardable = systems.boardable,
                canGrab = systems.canGrab,
                onLadder = systems.onLadder,
                passenger = systems.passenger,
                ballast = systems.ballast,
                ballastMode = systems.ballastMode,
                holdingDepth = systems.holdingDepth,
                crush = systems.crush,
                seabed = systems.seabed,
                findBearing = systems.findBearing,
                findRange = systems.findRange,
                cruiseHeight = systems.cruiseHeight,
                mayCruise = systems.mayCruise,
                groups = systems.groups,
                hasWinch = systems.hasWinch,
                canHook = systems.canHook,
                hooked = systems.hooked,
                reel = systems.reel,
                taut = systems.taut,
                hasKeeper = systems.hasKeeper,
                keeping = systems.keeping,
                lift = systems.lift,
                held = prediction.replica?.let { local ->
                    val ore = com.rm.apogee.core.part.ResourceType.ORE
                    val water = com.rm.apogee.core.part.ResourceType.WATER
                    floatArrayOf(
                        local.amountOf(ore).toFloat(), local.capacityOf(ore).toFloat(),
                        local.amountOf(water).toFloat(), local.capacityOf(water).toFloat(),
                    )
                },
            )
        }

    /**
     * Floods the flown craft's ballast tanks ([mode] 1), blows them (-1), or leaves them alone (0).
     */
    suspend fun setBallast(mode: Int) {
        withControlledVessel { client.send(Command.SetBallast(it, mode)) }
    }

    /** Holds the flown craft at the depth it's at now, or lets it go. */
    suspend fun holdDepth(on: Boolean) {
        withControlledVessel { client.send(Command.HoldDepth(it, on)) }
    }

    /** Whether the craft being flown has wings with flaps. */
    val controlledHasSails: Boolean get() = controlledHas { it.hasModule<com.rm.apogee.core.part.Sail>() }

    val controlledHasFlaps: Boolean get() = controlledHas { (it.module<com.rm.apogee.core.part.AeroSurface>()?.flapLift ?: 0.0) > 0.0 }

    /** The craft being flown, or null. */
    val controlledId: Long? get() = client.controlledVessel

    /** Whether the craft being flown is a plane in the air, which can hold height and heading. */
    val controlledCanCruise: Boolean
        get() {
            val replica = prediction.replica ?: return false
            return !replica.touchingGround && replica.defs.any { it.hasModule<com.rm.apogee.core.part.AeroSurface>() }
        }

    /** The action groups the craft being flown's parts are in, in order. */
    val controlledGroups: List<Int>
        get() {
            val id = client.controlledVessel ?: return emptyList()
            val design = client.vessels.firstOrNull { it.id == id }?.design ?: return emptyList()
            return design.parts.map { it.group }.filter { it > 0 }.distinct().sorted()
        }

    /** Flaps down or up, here straight away and on the server. */
    suspend fun setFlaps(down: Boolean) {
        localFlaps = down
        pushControlsToPrediction()
        withControlledVessel { client.send(Command.SetFlaps(it, down)) }
    }

    /** Holds the flown craft's height and heading as they are now, or lets go. */
    /** Holds the craft still where it is with its keeper core, or stops. */
    suspend fun setStationKeep(on: Boolean) = withControlledVessel { client.send(Command.SetStationKeep(it, on)) }

    suspend fun setCruise(on: Boolean) {
        withControlledVessel { client.send(Command.SetCruise(it, on)) }
    }

    /** Switches action group [group] on, or off if it's on. */
    suspend fun toggleGroup(group: Int) {
        withControlledVessel { client.send(Command.ToggleGroup(it, group)) }
    }

    /** The winch: hook on, wind in (1), let out (-1) or hold (0), or let go. */
    suspend fun hook() = withControlledVessel { client.send(Command.Hook(it)) }
    suspend fun reel(mode: Int) = withControlledVessel { client.send(Command.Reel(it, mode)) }
    suspend fun releaseLine() = withControlledVessel { client.send(Command.ReleaseLine(it)) }

    /** Folds the flown craft's sun wings and dishes out, or away. */
    suspend fun setDeployed(deployed: Boolean) {
        withControlledVessel { client.send(Command.Deploy(it, deployed)) }
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

    /** Hold a navball marker, and turn SAS on to do it. */
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
            if ((localTarget >= 0 && client.vessel(localTarget) != null) || localTargetBody.isNotEmpty()) add(com.rm.apogee.core.world.NavFrame.TARGET)
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
        val body = bodyOfTarget(target)
        localTarget = if (body != null) -1L else target
        localTargetBody = body ?: ""
        pushControlsToPrediction()
        withControlledVessel { client.send(Command.SetTarget(it, localTarget, localTargetBody)) }
    }

    /** The body a target id stands for (see [BODY_TARGET]), or null for a craft, or none. */
    private fun bodyOfTarget(target: Long): String? {
        if (target > BODY_TARGET) return null
        return targetBodies.getOrNull((BODY_TARGET - target).toInt())?.id
    }

    /** Bodies that can be targeted, in a fixed order: every one except the star. */
    private val targetBodies = system.bodies.values.filter { it.parentId != null }

    // --- planned burns and the autopilots -------------------------------------------

    /**
     * The flown craft's burns as edited here, ahead of the server's word, so the path redraws under
     * your finger. Null to follow the server's.
     */
    @Volatile private var editedBurns: List<com.rm.apogee.core.world.PlannedBurn>? = null
    @Volatile private var editedNanos = 0L
    private var burnsSentNanos = 0L

    /** The burns the flown craft has, as far as this client knows: edited, or the server's. */
    fun burnsOf(focus: ClientVessel): List<com.rm.apogee.core.world.PlannedBurn> = editedBurns ?: focus.burns

    /** Autopilots, as asked for here. See [setAutopilot]. */
    @Volatile var localAutoBurn = false
        private set
    @Volatile var localAutoLand = false
        private set
    private var pushedAutoBurn = false
    private var pushedAutoLand = false

    private fun editBurns(burns: List<com.rm.apogee.core.world.PlannedBurn>) {
        editedBurns = burns
        editedNanos = System.nanoTime()
    }

    /** Sends the edited burns, at most every [BURN_SEND_NANOS] unless [now]. */
    private suspend fun sendBurns(now: Boolean = false) {
        val burns = editedBurns ?: return
        val t = System.nanoTime()
        if (!now && t - burnsSentNanos < BURN_SEND_NANOS) return
        burnsSentNanos = t
        withControlledVessel { client.send(Command.PlanBurns(it, burns)) }
    }

    /** A burn of nothing yet at universe time [time], to be shaped in the burn panel. */
    suspend fun planBurnAt(time: Double) {
        val focus = client.controlledVessel?.let { client.vessel(it) } ?: return
        editBurns(listOf(com.rm.apogee.core.world.PlannedBurn(time)) + burnsOf(focus).drop(1).filter { it.time > time })
        sendBurns(now = true)
    }

    /** The next burn changed by this much along each axis, in m/s. */
    suspend fun nudgeBurn(prograde: Double, normal: Double, radial: Double) {
        val focus = client.controlledVessel?.let { client.vessel(it) } ?: return
        val burns = burnsOf(focus)
        val burn = burns.firstOrNull() ?: return
        editBurns(listOf(burn.copy(prograde = burn.prograde + prograde, normal = burn.normal + normal, radial = burn.radial + radial)) + burns.drop(1))
        sendBurns()
    }

    /** The next burn moved to universe time [time]. */
    suspend fun moveBurn(time: Double, final: Boolean = false) {
        val focus = client.controlledVessel?.let { client.vessel(it) } ?: return
        val burns = burnsOf(focus)
        val burn = burns.firstOrNull() ?: return
        val now = lastRenderTime
        editBurns(listOf(burn.copy(time = time.coerceAtLeast(now + 1.0))) + burns.drop(1))
        sendBurns(now = final)
    }

    /** The next burn [seconds] later, or earlier if negative. */
    suspend fun shiftBurn(seconds: Double) {
        val focus = client.controlledVessel?.let { client.vessel(it) } ?: return
        val burn = burnsOf(focus).firstOrNull() ?: return
        moveBurn(burn.time + seconds)
    }

    /** Sends what's being edited now, not in a moment, because a finger lifted. */
    suspend fun burnEdited() = sendBurns(now = true)

    /** The next burn is gone. */
    suspend fun deleteBurn() {
        val focus = client.controlledVessel?.let { client.vessel(it) } ?: return
        editBurns(burnsOf(focus).drop(1))
        sendBurns(now = true)
        if (localAutoBurn) setAutopilot(autoBurn = false, autoLand = localAutoLand)
    }

    /** Warps to a little before the next burn starts, with time to turn onto it. */
    suspend fun warpToBurn() {
        val focus = client.controlledVessel?.let { client.vessel(it) } ?: return
        val burn = burnsOf(focus).firstOrNull() ?: return
        val duration = prediction.replica?.let { com.rm.apogee.core.world.Burns.duration(it, burn.deltaV) } ?: 0.0
        val start = com.rm.apogee.core.world.Burns.startOf(burn, duration)
        val until = start - BURN_WARP_LEAD
        if (until > lastRenderTime + 5.0) client.send(Command.WarpTo(until))
    }

    /** Turns the autopilots on or off. */
    suspend fun setAutopilot(autoBurn: Boolean, autoLand: Boolean) {
        localAutoBurn = autoBurn
        localAutoLand = autoLand
        withControlledVessel { client.send(Command.SetAutopilot(it, autoBurn, autoLand)) }
    }

    /**
     * Puts the plan onto the replica: the burns, the target body, and the autopilots. They're
     * pushed when asked for here, and otherwise read back, so one that finishes by itself (a burn
     * done, a landing made) shows as off.
     */
    private fun syncPlan(focus: ClientVessel) {
        val replica = prediction.replica ?: return
        // The server has taken an edit now, or it was replaced long ago, so follow the server
        // again.
        editedBurns?.let { edited -> if (edited == focus.burns || System.nanoTime() - editedNanos > BURN_EDIT_HOLD_NANOS) editedBurns = null }
        val autoBurn = if (localAutoBurn != pushedAutoBurn) localAutoBurn else replica.control.autoBurn
        val autoLand = if (localAutoLand != pushedAutoLand) localAutoLand else replica.control.autoLand
        prediction.syncPlan(burnsOf(focus), autoBurn, autoLand, localTargetBody)
        pushedAutoBurn = autoBurn; pushedAutoLand = autoLand
        localAutoBurn = autoBurn; localAutoLand = autoLand
    }

    /** The next planned burn, for the HUD and the burn panel. */
    class BurnReadout(
        /** Seconds until it should start. Negative once it has. */
        val startsIn: Double,
        val burn: com.rm.apogee.core.world.PlannedBurn,
        /** What's left of it, in m/s. */
        val left: Double,
        /** At full throttle, in seconds. */
        val duration: Double,
        /**
         * After it: its high and low points above the ground, in metres (+inf for none, when
         * escaping).
         */
        val apoapsis: Double,
        val periapsis: Double,
        /**
         * A moon it meets afterwards, and how low it passes it, in metres above the ground. Null
         * for none.
         */
        val meets: String?,
        val meetsAt: Double,
        /** The auto-burn has it. */
        val auto: Boolean,
        /** Whether there's an autopilot to hand it to. A career has to unlock one. */
        val canAuto: Boolean = true,
    )

    @Volatile var burnReadout: BurnReadout? = null
        private set

    /** Coming down: when, how fast, and when to start braking, for the HUD. */
    class LandingReadout(val impactIn: Double, val impactSpeed: Double, val brakeIn: Double, val auto: Boolean, val canAuto: Boolean = true)

    /** A planet targeted from another: when to leave, and what it costs. Angles are in degrees. */
    class WindowReadout(
        val target: String,
        val waitFor: Double,
        val phase: Double,
        val needed: Double,
        val flight: Double,
        val departure: Double,
        val arrival: Double,
    )

    @Volatile var windowReadout: WindowReadout? = null

    /**
     * The next feat or visit the career credited, or a career refusal, to show once. Null when
     * there's none.
     */
    fun nextFeat(): com.rm.apogee.core.world.ServerMessage.Feat? = client.feats.poll()
    fun nextRefusal(): String? = client.refusals.poll()

    /**
     * This player's career in the world being flown in, as its server keeps it, and the world's
     * firsts. Null in a sandbox.
     */
    val career: com.rm.apogee.core.career.CareerState? get() = client.career
    val worldFirsts: List<com.rm.apogee.core.career.WorldFirst> get() = client.firsts

    /**
     * Spends insight on a node, in the career of whichever world this is (the host's, when joined).
     * Null if it was asked for, or the reason it can't be yet.
     */
    suspend fun unlock(nodeId: String): String? {
        val state = client.career ?: return "Not a career"
        val node = com.rm.apogee.core.career.TechTree.stock.node(nodeId) ?: return "No such node"
        state.blocker(com.rm.apogee.core.career.TechTree.stock, node)?.let { return it }
        client.send(Command.Unlock(nodeId))
        return null
    }

    /** Whether this is a career world, as the server said. */
    val careerWorld: Boolean get() = client.mode == com.rm.apogee.core.world.WorldSave.MODE_CAREER

    /**
     * Whether there's an autopilot to fly burns and landings. In a career, only once the Flight
     * Computer is unlocked.
     */
    val autopilotAllowed: Boolean get() = !careerWorld ||
        client.career?.ability(com.rm.apogee.core.career.TechTree.stock, com.rm.apogee.core.career.TechTree.AUTOPILOT) == true

    @Volatile var landingReadout: LandingReadout? = null
        private set

    /** Why the autopilot last gave up, or blank. */
    @Volatile var autopilotNote: String = ""
        private set

    /** The runway approach cue, while a plane is coming in to the Cape's runway, or null. */
    @Volatile var approachReadout: com.rm.apogee.core.world.Approach.Cue? = null
        private set

    /** The current the flown craft is floating in: speed in m/s and compass bearing it runs toward. Null for none. */
    @Volatile var currentReadout: Pair<Float, Float>? = null
        private set

    /** The runway's height above the datum, in metres, looked up once. */
    private val runwayHeight: Double by lazy {
        system.body("terra").terrain?.elevation(com.rm.apogee.core.orbit.SolarSystem.capeDirection(1_500.0, com.rm.apogee.core.terrain.TerrainField.RUNWAY_NORTH)) ?: 0.0
    }

    /** The cue for [replica] coming in to land on the runway, or null when it isn't. */
    private fun approachFor(replica: com.rm.apogee.core.craft.Vessel, attractor: CelestialBody, time: Double): com.rm.apogee.core.world.Approach.Cue? {
        if (attractor.id != "terra" || replica.touchingGround || replica.dormant) return null
        if (replica.defs.none { it.hasModule<com.rm.apogee.core.part.Wheel>() } || replica.defs.none { it.hasModule<com.rm.apogee.core.part.AeroSurface>() }) return null
        val height = attractor.altitudeOf(replica.body.position) - runwayHeight
        if (height > APPROACH_HIGHEST) return null
        val rotation = attractor.rotationAt(time)
        val position = attractor.toBodyFixed(replica.body.position, rotation)
        val velocity = Vec3().setTo(replica.body.linearVelocity).subInPlace(attractor.surfaceVelocityAt(replica.body.position, Vec3()))
        rotation.inverseRotate(velocity, velocity)
        val cue = com.rm.apogee.core.world.Approach.Cue()
        return if (com.rm.apogee.core.world.Approach.cue(position, velocity, height, attractor.radius, cue)) cue else null
    }

    /** The current [replica] is floating in, or null for none worth showing. */
    private fun currentFor(replica: com.rm.apogee.core.craft.Vessel, attractor: CelestialBody): Pair<Float, Float>? {
        if (attractor.ocean == null) return null
        val altitude = attractor.altitudeOf(replica.body.position)
        if (altitude > CURRENT_ABOVE) return null
        val current = prediction.currentAt() ?: return null
        if (current.length < CURRENT_SHOWN) return null
        return current.length.toFloat() to com.rm.apogee.core.world.Navigation.heading(replica.body.position, current).toFloat()
    }

    private fun updateReadouts(focus: ClientVessel, attractor: CelestialBody, time: Double) {
        val replica = prediction.replica
        autopilotNote = replica?.control?.autopilotNote ?: ""
        val plan = planner.plan?.takeIf { it.bodyId == attractor.id }
        val burn = burnsOf(focus).firstOrNull()
        burnReadout = if (burn == null) null else {
            val duration = replica?.let { com.rm.apogee.core.world.Burns.duration(it, burn.deltaV) } ?: Double.NaN
            val left = replica?.let { r -> (r.burnVector.takeIf { !it.x.isNaN() }?.let { Vec3().setTo(it).subInPlace(r.burnApplied).length }) } ?: burn.deltaV
            val after = plan?.planned?.segments?.firstOrNull()
            val met = plan?.planned?.segments?.drop(1)?.firstOrNull { it.bodyId != attractor.id && system.body(it.bodyId).parentId == attractor.id }
            BurnReadout(
                startsIn = com.rm.apogee.core.world.Burns.startOf(burn, duration) - time,
                burn = burn,
                left = left,
                duration = duration,
                apoapsis = after?.orbit?.let { if (it.isBound) it.apoapsis - attractor.radius else Double.POSITIVE_INFINITY } ?: Double.NaN,
                periapsis = after?.orbit?.let { it.periapsis - attractor.radius } ?: Double.NaN,
                meets = met?.let { system.body(it.bodyId).displayName },
                meetsAt = met?.let { it.orbit.periapsis - system.body(it.bodyId).radius } ?: Double.NaN,
                auto = localAutoBurn,
                canAuto = autopilotAllowed,
            )
        }
        windowReadout = windowFor(attractor, replica?.body?.position ?: focus.latest?.position, time)
        approachReadout = replica?.let { approachFor(it, attractor, time) }
        currentReadout = replica?.let { currentFor(it, attractor) }
        val impact = plan?.impact
        landingReadout = if (impact == null || replica == null || replica.touchingGround || replica.dormant) null else run {
            val up = replica.body.position.normalized()
            val surface = attractor.surfaceVelocityAt(replica.body.position, Vec3())
            val velocity = Vec3().setTo(replica.body.linearVelocity).subInPlace(surface)
            // Coming down, not standing or climbing.
            if ((velocity dot up) > -LANDING_FALLING) return@run null
            val g = attractor.gravityAt(replica.body.position, Vec3()).length
            val thrust = replicaThrust(replica, attractor)
            val most = thrust / replica.body.mass
            val speed = velocity.length
            // Stopping from here on most of the engine takes this much height, so start with that
            // much before the ground.
            val stopping = if (most * 0.9 > g) speed * speed / (2.0 * (most * 0.9 - g)) else Double.POSITIVE_INFINITY
            val falling = -(velocity dot up)
            val height = (impact.time - time) * falling.coerceAtLeast(1.0)
            return@run LandingReadout(
                impactIn = impact.time - time,
                impactSpeed = impact.speed,
                brakeIn = if (stopping.isFinite()) (height - stopping) / falling.coerceAtLeast(1.0) else Double.NaN,
                auto = localAutoLand,
                canAuto = autopilotAllowed,
            )
        }
    }

    /** Along what's left of the flown craft's next burn, in world axes, unit. Null for none. */
    private fun burnDirection(focus: ClientVessel, attractor: CelestialBody): Vec3? {
        val burn = burnsOf(focus).firstOrNull() ?: return null
        val replica = prediction.replica ?: return null
        val left = if (!replica.burnVector.x.isNaN()) Vec3().setTo(replica.burnVector).subInPlace(replica.burnApplied)
            else com.rm.apogee.core.world.Burns.vectorOf(
                burn, com.rm.apogee.core.orbit.Orbit(replica.body.position.copy(), replica.body.linearVelocity.copy(), attractor.gravitationalParameter, prediction.renderTime() ?: lastRenderTime),
            )
        return if (left.length < com.rm.apogee.core.world.Burns.DONE) null else left.normalizeInPlace()
    }

    /** What the replica's lit engines give in the air it's in, in N. */
    private fun replicaThrust(replica: com.rm.apogee.core.craft.Vessel, attractor: CelestialBody): Double {
        val pressure = attractor.atmosphere?.pressureRatioAt(attractor.altitudeOf(replica.body.position))?.coerceIn(0.0, 1.0) ?: 0.0
        var total = 0.0
        for (i in replica.activeEngines()) {
            val engine = replica.defs[i].module<com.rm.apogee.core.part.Engine>() ?: continue
            if (replica.isBroken(i) || replica.amountInGroupOf(i, engine.propellant) <= 0.0) continue
            total += engine.thrustVacuum + (engine.thrustSeaLevel - engine.thrustVacuum) * pressure
        }
        return total
    }

    /** The winch lines to draw this frame, and where their parts were drawn, by [lineKey]. */
    private var frameLines: List<com.rm.apogee.core.world.SavedLine> = emptyList()
    private val lineEnds = HashMap<Long, Pair<Vec3, Quat>>()

    private fun lineKey(vessel: Long, part: Int): Long = vessel * 65_536L + part

    /**
     * The winch lines: a thin dark cable from each drum to what it's hooked on, straight when it's
     * pulling and sagging when there's slack, in a few pieces.
     */
    private fun appendLines(out: MutableList<RenderItem>, attractor: CelestialBody) {
        for ((k, line) in frameLines.withIndex()) {
            if (line.bodyId != attractor.id && line.vesselB < 0) continue
            val (drum, drumTurn) = lineEnds[lineKey(line.vesselA, line.partA)] ?: continue
            val winch = catalog[client.vessels.firstOrNull { it.id == line.vesselA }?.design?.parts?.getOrNull(line.partA)?.partId ?: continue]
                ?.module<com.rm.apogee.core.part.Winch>() ?: continue
            val from = drumTurn.rotate(Vec3(0.0, winch.faceOffset, 0.0)).addInPlace(drum)
            val to = if (line.vesselB >= 0) {
                val (part, turn) = lineEnds[lineKey(line.vesselB, line.partB)] ?: continue
                turn.rotate(line.hook).addInPlace(part)
            } else {
                bodyRotation.rotate(line.ground, Vec3())
            }
            val span = to.distanceTo(from)
            if (span < 0.05) continue
            // Slack hangs down in the middle, about as far as a rope that long between those ends.
            val slack = (line.length - span).coerceAtLeast(0.0)
            val sag = if (line.taut) 0.0 else kotlin.math.sqrt(slack * (line.length + span)) / 2.0
            val down = Vec3().setTo(from).addInPlace(to).mulInPlace(0.5).normalizeInPlace().negateInPlace()
            val previous = Vec3().setTo(from)
            for (piece in 1..LINE_PIECES) {
                val t = piece.toDouble() / LINE_PIECES
                val point = Vec3().setTo(from).addScaledInPlace(Vec3().setTo(to).subInPlace(from), t)
                    .addScaledInPlace(down, 4.0 * sag * t * (1.0 - t))
                val along = Vec3().setTo(point).subInPlace(previous)
                val length = along.length
                if (length > 1e-6) {
                    out.add(
                        RenderItem(
                            WINCH_LINE, Vec3().setTo(previous).addScaledInPlace(along, 0.5),
                            com.rm.apogee.core.math.quatFromTo(Vec3.unitY(), along.mulInPlace(1.0 / length)), WINCH_LINE_COLOUR,
                            caps = 0, scale = Vec3(1.0, length, 1.0), ambient = 0.3f, wrap = false,
                            key = RenderItem.effectKey(LINE_KEY + k, piece),
                        ),
                    )
                }
                previous.setTo(point)
            }
        }
    }

    /** A craft that can be picked as a target, nearest first. */
    class TargetChoice(val id: Long, val name: String, val distance: Double)

    /**
     * Other craft around the same body, nearest first, then the other worlds, for the target
     * picker.
     */
    fun targetChoices(): List<TargetChoice> = craftChoices() + bodyChoices()

    /** The worlds except the one the craft is in, nearest first. */
    private fun bodyChoices(): List<TargetChoice> {
        val focusId = client.controlledVessel ?: return emptyList()
        val state = client.vessel(focusId)?.latest ?: return emptyList()
        val t = lastRenderTime
        val here = system.positionOf(state.referenceBodyId, t).addInPlace(state.position)
        return targetBodies.withIndex().filter { it.value.id != state.referenceBodyId }.map { (k, body) ->
            TargetChoice(BODY_TARGET - k, body.displayName, system.positionOf(body.id, t).distanceTo(here) - body.radius)
        }.sortedBy { it.distance }
    }

    private fun craftChoices(): List<TargetChoice> {
        val focusId = client.controlledVessel ?: return emptyList()
        val focus = client.vessel(focusId)?.latest ?: return emptyList()
        return client.vessels.mapNotNull { v ->
            if (v.id == focusId) return@mapNotNull null
            val state = v.latest ?: return@mapNotNull null
            if (state.referenceBodyId != focus.referenceBodyId) return@mapNotNull null
            TargetChoice(v.id, v.name.ifBlank { "Debris" } + if (v.anchored) " (base)" else "", state.position.distanceTo(focus.position))
        }.sortedBy { it.distance }.take(MAX_TARGET_CHOICES)
    }

    /**
     * Every craft of the player's, for the craft list: where each one is, in words, and how high.
     * The one being flown comes first.
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
                val only = vessel.design.parts.singleOrNull()?.partId
                val flag = only == com.rm.apogee.core.world.World.FLAG_PART
                // Under the sea, so below its datum, near enough, because the tide is only a metre
                // or two.
                val depth = if (body.terrain?.isOcean(bodyFixed) == true) -body.altitudeOf(state.position) else 0.0
                val situation = when {
                    flag -> "Planted on ${body.displayName}"
                    depth > UNDER_SEA -> "Under the sea off ${body.displayName}"
                    above < 2.0 && body.terrain?.isOcean(bodyFixed) == true -> "Afloat on ${body.displayName}"
                    above < 2.0 -> "Landed on ${body.displayName}"
                    orbit.isBound && orbit.periapsis > floor -> "In orbit of ${body.displayName}"
                    else -> "${Going.aloft(vessel.design, catalog)} over ${body.displayName}"
                }
                val height = if (depth > UNDER_SEA) "%.0f m down".format(depth)
                    else if (above < 2.0) "on the surface"
                    else if (above < 10_000.0) "%.0f m up".format(above) else "%.1f km up".format(above / 1000.0)
                com.rm.apogee.ui.screens.CraftSummary(
                    vessel.id, vessel.name, situation, height, canReset = false, canFly = !flag,
                    going = Going.of(vessel.design, catalog, vessel.anchored),
                )
            }
    }

    /** The craft being flown, if there is one. */
    val controlledCraft: Long? get() = client.controlledVessel

    /**
     * Removes the craft being flown, and waits (a second at most) for the server to say it's gone.
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

    /** How many craft the player has, for whether there's a list of them to choose from. */
    val ownedCraftCount: Int
        get() = client.vessels.count { it.owner == client.clientId }

    /** Puts [launchDesign] on the pad once the handshake is done. */
    private suspend fun launchPendingDesign() {
        val design = launchDesign ?: return
        client.send(Command.SpawnCraft(design, launchSiteId ?: World.launchSiteFor(design, catalog).id))
    }

    /**
     * Welds the controlled craft to whatever it's resting against.
     *
     * It isn't predicted locally, unlike staging. A merge rewrites both craft's structure and
     * destroys one of them, and guessing wrong about that would leave the client showing a craft
     * the server still has. Staging only flips a flag, which is cheap to get wrong for one
     * snapshot.
     */
    suspend fun join() {
        withControlledVessel { client.send(Command.Join(it)) }
    }

    suspend fun stage() {
        // Staged locally as well, so the button responds straight away. The server's own staging
        // arrives in the next structure update and overwrites this. It's staged locally on the
        // frame thread, though, not this one. Staging splits the replica, and splitting it mid-step
        // (the frame thread stepping it through its parts as they changed) ran off the end of its
        // part list and crashed the game.
        pendingLocalStages.incrementAndGet()
        withControlledVessel { client.send(Command.Stage(it)) }
    }

    /**
     * Craft close enough to the one being flown to touch it soon (a stage just dropped, a ring
     * being docked with), for the replica to meet the way the server does.
     */
    private fun neighboursOf(focus: ClientVessel, state: com.rm.apogee.core.world.VesselKinematics, time: Double): List<ClientPrediction.Neighbour> {
        val reach = designReach(focus.design)
        val out = ArrayList<ClientPrediction.Neighbour>()
        for (other in client.vessels) {
            if (other.id == focus.id) continue
            val seen = other.observed ?: continue
            if (seen.kinematics.referenceBodyId != state.referenceBodyId) continue
            // Measured at the same moment. A snapshot apart in orbit is tens of metres, and a stage
            // pressed against us came and went from the replica frame to frame, so the craft jumped
            // about at 4x.
            val there = Vec3().setTo(seen.kinematics.position).addScaledInPlace(seen.kinematics.velocity, time - seen.time)
            if (there.distanceTo(state.position) > reach + designReach(other.design) + NEIGHBOUR_MARGIN) continue
            out.add(ClientPrediction.Neighbour(other.id, other.design, seen.kinematics, seen.time, other.currentStage, other.activatedParts, other.anchored))
            if (out.size >= MAX_NEIGHBOURS) break
        }
        return out
    }

    /** Stage presses waiting for the frame thread to apply them to the replica. */
    private val pendingLocalStages = com.rm.apogee.platform.AtomicInteger()

    private fun pushControlsToPrediction() {
        prediction.applyControl(
            localThrottle, localPitch, localYaw, localRoll, localSas, localBrakes,
            localRcs, localReverse, localTranslate.x, localTranslate.y, localTranslate.z, localFlaps,
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
     * It's called at roughly display pace instead of the simulation's. The server streams 20 motion
     * samples a second and the renderer interpolates between the last two, so publishing faster
     * than the data changes would just hand the GL thread the same numbers over and over.
     */
    private fun buildFrame(timestampNanos: Long) {
        val focusId = client.controlledVessel ?: return
        // Gone, smashed or burnt up whole. The view stays where it went, on a stand-in with no
        // parts, so the wreckage, the fire and the smoke are still there to see.
        val live = client.vessel(focusId)?.takeIf { it.latest != null }
        val wrecked = live == null
        wreckedId = if (wrecked) focusId else null
        val focus = live ?: wreckStandIn(focusId) ?: return
        val focusState = focus.latest ?: return

        val attractor = system.bodies[focusState.referenceBodyId] ?: return

        soundCrafts.clear()
        val lines = ArrayList<RenderLine>(4)
        val items = ArrayList<RenderItem>(64)
        val farItems = ArrayList<RenderItem>()
        frameEmitters.clear()
        lampCount = 0

        // The one time this frame is drawn at. It's snapshot time plus how long ago it arrived,
        // unless the controlled craft is being predicted, in which case it's the craft's own time.
        // The ground, the craft on it and everything else have to be shown at the same instant,
        // because at the equator a few milliseconds of disagreement is a visible slide.
        val snapshotTime = client.latestSnapshot?.time ?: 0.0
        val snapshotAge = if (client.latestSnapshotNanos == 0L) 0.0
            else (System.nanoTime() - client.latestSnapshotNanos) / 1e9
        // The world's clock runs at the warp rate, and not at all while paused.
        val warp = client.latestSnapshot?.warp ?: 1.0
        val warping = warp != 1.0
        // Warped, the snapshots' own time is too jumpy to draw by, because a few milliseconds of
        // arrival jitter is four times as much world time at 4x. So a clock of our own runs at the
        // warp rate and gets eased toward it.
        var renderTime = if (warping) warpClock(snapshotTime + snapshotAge * warp, warp)
            else { warpClockTime = Double.NaN; snapshotTime + snapshotAge }
        lastRenderTime = renderTime
        val animationNow = System.nanoTime()
        animationDt = if (lastAnimationNanos == 0L || warp == 0.0) 0.0 else ((animationNow - lastAnimationNanos) / 1e9).coerceAtMost(0.1)
        lastAnimationNanos = animationNow
        // Where the focused craft is drawn this frame (the prediction, not the last snapshot),
        // which is what the ground's detail follows.
        var focusDrawn: Vec3 = focusState.position

        if (mapMode) {
            val orbit = Orbit(
                position = focusState.position,
                velocity = focusState.velocity,
                mu = attractor.gravitationalParameter,
            )
            // A hop that comes down before it gets anywhere, with no burn planned, goes by the fall
            // over the real ground: the planner's path is cut short at the datum, and in a basin
            // below it, like Luna's mare, that's before it's even started.
            val highest = attractor.radius + maxOf(attractor.terrain?.maxElevation ?: 0.0, 0.0)
            val plan = planner.plan?.takeIf { it.bodyId == attractor.id && (it.burn != null || orbit.periapsis > highest) }
            // Where it's going: falling free, the path predicted for it (with any burns planned) or
            // its orbit, and driving, sailing or flying in the air, its course over the ground.
            val free = fallingFree(attractor, focusState, renderTime, rocket = focus.design.let { d ->
                if (d !== mapKindDesign) { mapKindDesign = d; mapRocket = com.rm.apogee.core.craft.CraftKind.of(d, catalog) == com.rm.apogee.core.craft.CraftKind.ROCKET }
                mapRocket
            })
            mapPlannable = free
            val drawn = if (plan != null && free) planLines(plan, attractor.id) else emptyList()
            val course = if (!free) courseLine(attractor, focusState, renderTime) else null
            val fall = if (plan == null && free) fallLine(attractor, orbit, renderTime) else null
            val craft = focusState.position
            if (mapFramedFor != focusId) {
                // Opened over the craft, looking straight down on it, with where it's going in view.
                // After that the view is the player's, to pinch and turn.
                mapFramedFor = focusId
                var span = 0.0
                for (line in drawn + listOfNotNull(course?.first, fall?.first)) for (p in line.points) span = maxOf(span, p.distanceTo(craft))
                val down = craft.normalized()
                mapCamera.pitch = kotlin.math.asin(down.y.coerceIn(-1.0, 1.0))
                mapCamera.yaw = kotlin.math.atan2(down.x, down.z)
                mapCamera.frameExactly(if (!free) maxOf(span, MAP_LOCAL) else span)
            }
            mapCamera.solve(craft, cameraPosition, cameraRotation)
            // How much the view takes in, which the markers are sized to.
            val reach = mapCamera.distance / 2.4

            when {
                !free -> if (course != null) {
                    lines.addAll(thick(course.first, reach * COURSE_WIDTH))
                    // No bigger than a third of the way to the next, so they never run together.
                    val ticks = course.second
                    val gap = if (ticks.size > 1) ticks[0].distanceTo(ticks[1]) else Double.MAX_VALUE
                    for (tick in ticks) lines.add(dot(tick, minOf(reach * TICK_FRACTION, gap / 3.0), COURSE_COLOR))
                }
                plan != null -> {
                    lines.addAll(drawn)
                    planMarkers(plan, attractor.id, reach, lines)
                }
                fall != null -> {
                    lines.addAll(thick(fall.first, reach * COURSE_WIDTH))
                    val lands = fall.second
                    if (lands != null) lines.add(marker(lands, reach, IMPACT_COLOR))
                    else if (orbit.isBound) {
                        lines.add(marker(orbit.propagate(orbit.timeToApoapsis).position, reach, APOAPSIS_COLOR))
                        lines.add(marker(orbit.propagate(orbit.timeToPeriapsis).position, reach, PERIAPSIS_COLOR))
                    }
                }
            }
            keepMapView(plan?.takeIf { free }, attractor, renderTime, cameraPosition, cameraRotation, reach)
            moonLines(attractor, renderTime, lines)
            // The worlds in view, each one marked, because past the giants a planet is far less
            // than a pixel.
            val here = system.positionOf(attractor.id, renderTime)
            for (b in targetBodies) {
                if (b.id == attractor.id) continue
                val at = system.positionOf(b.id, renderTime).subInPlace(here)
                if (at.length < reach * MAP_LABEL_REACH) lines.add(marker(at, reach, BODY_MARKER_COLOR))
            }
            lines.add(marker(focusState.position, reach, CRAFT_COLOR))
            signalLines(focusId, focusState.position, attractor, renderTime, reach, lines)
            richnessDots(attractor, renderTime, lines)
            if (mapCurrents) currentArrows(attractor, renderTime, cameraPosition, lines)
            // Founded bases on this world (the player's own and the Cape's), where they stand.
            for (other in client.vessels) {
                if (!other.anchored || other.id == focusId) continue
                if (other.owner != World.WORLD_OWNER && other.owner != client.clientId) continue
                val seen = other.latest ?: continue
                if (seen.referenceBodyId != focusState.referenceBodyId) continue
                lines.add(marker(seen.position, reach, BASE_COLOR))
            }
            // Flags planted on this world, anyone's, because someone was here.
            for (other in client.vessels) {
                if (other.design.parts.singleOrNull()?.partId != com.rm.apogee.core.world.World.FLAG_PART) continue
                val seen = other.latest ?: continue
                if (seen.referenceBodyId != focusState.referenceBodyId) continue
                lines.add(marker(seen.position, reach * 0.6, FLAG_COLOR))
            }
        } else {
            // Next time the map opens, it opens over the craft again.
            mapFramedFor = Long.MIN_VALUE
            // Much smaller than it was, with the rest of it smashed or torn away, so come in to see
            // what's left.
            if (!wrecked && focus.design.parts.size < framedParts && framedFor == focusId) {
                camera.frameShrunk(designRadius(focus.design, designCentreOfMass(focus.design)))
            }
            if (!wrecked) { framedParts = focus.design.parts.size; framedFor = focusId }
            // Lost, so stand back far enough to take in the wreckage, instead of staying tucked in
            // where the craft was, which was sometimes inside a piece of it.
            if (wrecked && framedFor != -focusId) { camera.frameAtLeast(WRECK_VIEW); framedFor = -focusId }
            // Warped, the replica can't keep up with the server's clock. It isn't stepped or drawn,
            // only kept in step with the server's staging and fuel for the gauges, and adopted
            // afresh back at real time.
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
            val focusRotation = if (!wrecked && (warping || prediction.isReady)) predictedRotation else focusState.rotation
            val seat = seatOf(focus.design).takeIf { it >= 0 && !wrecked }?.let { i ->
                val design = focus.design
                val local = Vec3().setTo(design.parts[i].position).subInPlace(designCentreOfMass(design))
                    .addScaledInPlace(design.orientation.up, SEAT_RISE)
                focusRotation.rotate(local).addInPlace(focusPosition)
            }
            cockpitPart = if (camera.mode == CameraMode.COCKPIT && seat != null) seatOf(focus.design) else -1
            camera.solve(focusPosition, focusRotation, focus.design.orientation.forward, focus.design.orientation.up, seat, cameraPosition, cameraRotation)
            if (camera.mode != CameraMode.COCKPIT) keepCameraAboveGround(attractor, focusPosition, renderTime)
            if (!wrecked) updateSlide(focusId, prediction.replica?.body?.orientation ?: focusState.rotation, focusPosition)
            // Turn the planet to the frame's time before the craft are laid out, because their
            // lamps, dust and flames go into its frame by it. Left at the last frame's, they were a
            // frame's turn out (metres at the Cape), and a submarine's lamps lit the sea floor ten
            // metres off to one side.
            attractor.rotationAt(renderTime, bodyRotation)
            // The parts winch lines run between, so the craft loop can say where they're drawn.
            lineEnds.clear()
            frameLines = if (mapMode) emptyList() else client.latestSnapshot?.lines ?: emptyList()
            for (vessel in client.vessels) {
                if (vessel.id == focusId && !warping) {
                    // Staged here and not heard back yet, so draw the replica's own shape, and what
                    // it let go of. Otherwise the whole old stack gets drawn around the new,
                    // smaller craft's centre, a stage length out of place, until the server catches
                    // up.
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
                    val own = vessel.observed?.kinematics?.referenceBodyId?.let { system.bodies[it] } ?: attractor
                    if (!sampled(vessel, renderTime, own, warp, position, rotation)) continue
                    // In another body's pull, so from that body, then from this one.
                    if (own !== attractor) system.rebase(position, Vec3(), own.id, attractor.id, renderTime)
                    appendVessel(vessel, items, attractor, position, rotation, stateOverride = vessel.observed?.kinematics)
                } else {
                    // Carried from its own snapshot to the frame's time, and eased where a new
                    // snapshot disagrees with the last.
                    val observed = vessel.observed ?: continue
                    val own = system.bodies[observed.kinematics.referenceBodyId] ?: attractor
                    val position = carried(observed, renderTime, own, warp) ?: continue
                    if (own !== attractor) system.rebase(position, Vec3(), own.id, attractor.id, renderTime)
                    val rotation = spunOn(observed, renderTime)
                    smoothed(vessel.id, observed.time, renderTime, position, rotation, observed.kinematics.velocity)
                    appendVessel(vessel, items, attractor, position, rotation, stateOverride = observed.kinematics)
                }
            }
            if (drawn.size > client.vessels.size) {
                val alive = client.vessels.mapTo(HashSet()) { it.id }
                drawn.keys.retainAll(alive)
            }
            appendPaving(items, attractor, renderTime, cameraPosition)
            appendLines(items, attractor)
            appendApproachLights(items, attractor, renderTime, cameraPosition)
        }

        // Terrain turns with the planet, so the patch follows the craft's position in the body's
        // frame instead of its inertial one, at the frame's own time, the same one the craft is
        // drawn at.
        attractor.rotationAt(renderTime, bodyRotation)
        // Toward the star, this frame: seasons at the Cape, and faint light among the giants.
        system.sunDirection(attractor.id, focusDrawn, renderTime, frameSun)
        // How big the star looks from here, and how bright: a disc at home, and a spark past the
        // giants.
        frameSunStrength = system.sunStrength(attractor.id, focusDrawn, renderTime)
        frameSunSize = system.bodies[SolarSystem.STAR_ID]?.let { sol ->
            val d = SystemData.AU / kotlin.math.sqrt(frameSunStrength)
            kotlin.math.asin((sol.radius / d).coerceIn(0.0, 1.0))
        } ?: 0.0
        // The drawn position at the frame's time. The snapshot's position with the frame's rotation
        // could be up to 50 ms apart, which was metres of wobble in where the ground's detail was
        // centred, enough to flip a chunk at its split distance between parent and children every
        // other frame.
        attractor.toBodyFixed(focusDrawn, bodyRotation, bodyFixedCamera)
        terrainBuilder?.let { builder ->
            // The patch first, then the globe. Both are queued onto the same dispatcher, and the
            // globe is the bigger job by some way. Asking for it first leaves the ground the craft
            // is standing on waiting behind scenery, which is most of why there's a visible gap
            // before the world looks right.
            builder.collect()
            if (client.felledRevision != seenFelledRevision) {
                seenFelledRevision = client.felledRevision
                prediction.felled(client.felledScatter)
            }
            if (mapMode) {
                // On the map, the ground's detail goes where the map is looking from, the way it
                // would for a craft that high. Left around the craft, a rover's map was the coarse
                // globe everywhere but a few kilometres round it.
                attractor.toBodyFixed(cameraPosition, bodyRotation, mapTerrainCamera)
                builder.followCraft(attractor, mapTerrainCamera, attractor.heightAboveTerrain(cameraPosition, mapTerrainCamera), terrainScope)
            } else builder.followCraft(
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

        joinable = !wrecked && neighbourInWeldingRange(focus, focus.observed?.kinematics ?: focusState)
        if (!wrecked) updateDocking(focus, focus.observed?.kinematics ?: focusState) else { dockReadout = null; joints = emptyList() }
        // Where to stay, in case it's lost.
        lastSeen = LastSeen(focusId, attractor.id, bodyFixedCamera.copy(), focus.name)

        // The local vertical in world axes: the craft's own position direction.
        scratchUp.setTo(focusState.position).normalizeInPlace()
        if (!wrecked) peakParts[focusId] = maxOf(peakParts[focusId] ?: 0, focus.design.parts.size)
        // What it had when it was last seen whole, minus what it had lost by then. Lost outright,
        // that's what went with it, not the stages it dropped on the way up, which were never lost.
        if (!wrecked) lastParts[focusId] = focus.design.parts.size + (lostParts[focusId] ?: 0)
        // Never more than it ever had. A part torn off and then smashed is one part, however many
        // reports it made. Lost outright, every part it had is gone from it, whatever the count of
        // reports, because the pieces lying about are wreckage, not craft.
        val lost = if (wrecked) lastParts[focusId] ?: (lostParts[focusId] ?: 0)
            else minOf(lostParts[focusId] ?: 0, peakParts[focusId] ?: Int.MAX_VALUE)
        telemetry = if (wrecked) FlightTelemetry.lost(focus.name, crashReport(focusId), lost) else FlightTelemetry.from(
            focus, attractor, focusState.throttle, bodyFixedCamera,
            lowestPointOffset = lowestPointOffset(focus.design, focusState.rotation, scratchUp),
            seaHeight = attractor.ocean?.surfaceHeight(bodyFixedCamera, renderTime) ?: 0.0,
            air = prediction.replica?.air,
            bodyRotation = bodyRotation,
            forwardAxis = focus.design.orientation.forward,
            viewRotation = cameraRotation,
            navFrame = localNavFrame,
            target = client.vessel(localTarget)?.latest?.takeIf { it.referenceBodyId == focusState.referenceBodyId }
                ?: bodyTarget(attractor, renderTime),
            targetName = client.vessel(localTarget)?.name ?: system.bodies[localTargetBody]?.displayName,
            sasMode = if (localSas) localSasMode else null,
            condition = conditions[focus.id],
            defs = focus.design.parts.map { catalog[it.partId] },
            jointLoad = prediction.replica?.takeIf { !warping && it.design.parts.size == focus.design.parts.size }?.jointLoad,
            lost = lost,
            lunaWindow = if (focus.design.orientation == com.rm.apogee.core.craft.CraftOrientation.VERTICAL)
                moonWindowIn(attractor, focusState.position, bodyRotation, renderTime) else Double.NaN,
            moonName = system.bodies.values.firstOrNull { it.parentId == attractor.id }?.displayName ?: "",
        ).also { it.burn = if (wrecked) null else burnDirection(focus, attractor) }

        // The nearest thing in view, for the near plane: the closest part of any craft, allowing
        // for its size, and the ground under the camera.
        var nearest = Double.MAX_VALUE
        for (item in items) {
            val d = item.position.distanceTo(cameraPosition) - PartModels.boundingRadius(item.shape)
            if (d < nearest) nearest = d
        }
        attractor.toBodyFixed(cameraPosition, bodyRotation, scratchCameraBodyFixed)
        val cameraAboveGround = attractor.heightAboveTerrain(cameraPosition, scratchCameraBodyFixed)
        if (cameraAboveGround < nearest) nearest = cameraAboveGround

        // The weather: clouds to draw, and the air the camera is in. This comes after the
        // near-plane search, which is about the craft and the ground, because a cloud a kilometre
        // across isn't a reason to pull the near plane in.
        val weatherConfig = client.weather
        val clouds = if (weatherConfig != null && attractor.atmosphere != null) {
            cloudScene?.takeIf { it.body === attractor && it.config == weatherConfig }
                ?: CloudScene(attractor, weatherConfig, terrainQuality ?: QualityTier.MEDIUM, terrainScope) { t -> system.sunDirection(attractor.id, Vec3.zero(), t) }
                    .also { cloudScene = it }
        } else null
        if (clouds == null) cloudScene = null
        var mapCloud: com.rm.apogee.render.CloudShell? = null
        if (clouds != null) {
            attractor.toBodyFixed(cameraPosition, bodyRotation, cloudCamera)
            clouds.update(cloudCamera, renderTime)
            if (!mapMode) {
                clouds.append(bodyRotation, items)
                World.launchSites.firstOrNull { it.id == "cape" }?.let { cape ->
                    clouds.windsock(cape, cloudCamera, renderTime, bodyRotation, items)
                }
            } else {
                mapCloud = clouds.mapShell(renderTime)
            }
        }

        // The sea around the craft, from the same waves it floats on, out to the scene's reach.
        // Beyond that the terrain draws flat water.
        val ocean = attractor.ocean
        val sea = if (ocean != null && !mapMode) {
            seaScene?.takeIf { it.body === attractor && it.config == weatherConfig }
                ?: SeaScene(
                    attractor, system.bodies.values.firstOrNull { it.parentId == attractor.id && it.orbit != null },
                    weatherConfig, terrainQuality ?: QualityTier.MEDIUM, terrainScope,
                ).also { seaScene = it }
        } else null
        if (sea == null) seaScene = null
        var seaSurface: com.rm.apogee.render.SeaSurface? = null
        var seaReach = 0.0
        var tide = 0.0
        var underwater = false
        var cameraDepth = 0.0
        seaHeard = 0.0; seaRough = 0.0; seaStorm = 0.0
        if (sea != null) {
            if (!debugHideSea && attractor.altitudeOf(cameraPosition) < sea.reach) {
                sea.update(bodyFixedCamera, renderTime)
                seaSurface = sea.latest
                if (seaSurface != null) seaReach = sea.reach
            }
            val here = sea.sampleAt(scratchCameraBodyFixed, renderTime)
            tide = here.tide
            underwater = sea.isUnder(scratchCameraBodyFixed, here)
            cameraDepth = attractor.radius + here.height - scratchCameraBodyFixed.length
            // The open sea, heard near it, louder and rougher with its waves.
            if (here.depth > 2.0) {
                val above = scratchCameraBodyFixed.length - attractor.radius - here.height
                val near = 1.0 - smoothstepD(20.0, 250.0, above)
                seaRough = smoothstepD(0.3, 4.0, here.significantHeight)
                seaStorm = smoothstepD(2.0, 9.0, here.stormHeight)
                seaHeard = near * (0.35 + 0.65 * seaRough)
            }
        }

        // Flames, smoke and the rest, stepped by real frame time and drawn through the same weather
        // as the clouds.
        val fx = effects ?: Effects(terrainQuality ?: QualityTier.MEDIUM).also { effects = it }
        fx.sea = seaScene?.let { scene -> { p: Vec3, t: Double, o: com.rm.apogee.core.sea.SeaSample -> scene.sampleInto(p, t, o) } }
        // Where the ears are, and what air there is to carry anything to them.
        val cameraAir = clouds?.air
        val ear = attractor.toBodyFixed(cameraPosition, bodyRotation, scratchEar)
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
            sea = if (mapMode) 0.0 else seaHeard,
            seaRough = seaRough,
            seaStorm = seaStorm,
            complex = if (mapMode) 0.0 else nearCape(attractor, ear, CAPE_PADS),
            lampsLit = (scratchLamp.setTo(ear).normalizeInPlace() dot frameSun) < World.LAMP_DUSK,
            port = if (mapMode) 0.0 else nearCape(attractor, ear, CAPE_JETTY),
        )
        lastListener = listener
        // Blows, breakages and blasts since the last frame, where they happened on the turning
        // ground.
        while (true) {
            val event = client.partEvents.poll() ?: break
            // Lost means destroyed. A part torn off isn't gone. It might get smashed later, and be
            // counted then, or be lying there whole. Both used to be counted, and a part torn off
            // then smashed was two.
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
        // Where the craft went in, what's left of it burns for a while.
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
        val daylight = com.rm.apogee.render.NightLight.daylight(cameraPosition, attractor.radius, frameSun)
        if (!mapMode) {
            fx.flames(frameEmitters, attractor, renderTime, items)
            val (vertices, shapes) = fx.vertices(bodyRotation, cameraPosition, cameraRotation, clouds?.lightScale ?: 1f, renderTime, daylight, if (mapMode) 0f else fx.flash)
            particles = vertices
            particleShapes = shapes
        }
        val flash = if (mapMode) 0f else fx.flash
        appendBodies(farItems, attractor, renderTime, cameraPosition)
        askPlan(focus, focusState, renderTime, warping)
        updateReadouts(focus, attractor, renderTime)
        // Shadows around the craft, out to three times its size, within reason.
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
                    // Toward the star from here, as it really is.
                    sunDirection = frameSun.copy(),
                    homeDirection = HOME_DIRECTION,
                    cameraAltitude = attractor.altitudeOf(cameraPosition),
                    bodyRotation = bodyRotation.copy(),
                    maxElevation = attractor.terrain?.maxElevation ?: 1.0,
                    drawFarSurface = drawFarSurface,
                    chunkRange = chunkRange,
                    // Under the water, a murk a few tens of metres deep, closer where a storm stirs
                    // up the shallows, with its colour fading with the light as the camera goes
                    // down.
                    fogDistance = if (underwater) underwaterFog(cameraDepth) else if (mapMode || clouds == null) WorldView.CLEAR_FOG else clouds.fogDistance,
                    fogColor = if (underwater) underwaterColour(attractor.id, cameraDepth, daylight, clouds?.lightScale ?: 1f) else clouds?.fogColor ?: floatArrayOf(0.75f, 0.77f, 0.8f),
                    skyFog = if (mapMode) 0f else if (underwater) 1f else clouds?.skyFog ?: 0f,
                    // Lightning is its own light, not extra sun. Sun is nothing at night, and so
                    // was the flash.
                    lightScale = if (mapMode) 1f else (clouds?.lightScale ?: 1f),
                    flash = flash,
                    cloudShadow = if (mapMode) null else clouds?.shadowGrid,
                    surfaceWind = clouds?.surfaceWind?.copy() ?: Vec3(),
                    time = renderTime,
                    seaReach = seaReach,
                    tide = tide,
                    sea = seaSurface,
                    underwater = underwater,
                    cloudShell = mapCloud,
                    seaRadius = if (attractor.ocean != null && !mapMode) attractor.radius + tide else 0.0,
                    water = waterOf(attractor.id),
                    lamps = nearestLamps(cloudCamera),
                    sky = com.rm.apogee.render.SkyColours.of(attractor.id),
                    sunSize = frameSunSize,
                    sunStrength = frameSunStrength,
                ),
                nearestDistance = if (nearest == Double.MAX_VALUE) 0.0 else nearest.coerceAtLeast(0.0),
                particles = particles,
                particleShapes = particleShapes,
                farItems = farItems,
                // On the map, as far as the view reaches: the whole system, if that's what it
                // shows.
                farReach = if (mapMode) maxOf(com.rm.apogee.render.RenderFrame.FAR_REACH, mapCamera.distance * 4.0) else com.rm.apogee.render.RenderFrame.FAR_REACH,
                shadowFocus = if (mapMode) null else focusDrawn.copy(),
                shadowRadius = shadowReach,
            )
        )
        framesPublished.incrementAndGet()
    }

    /** Where the flown craft is going. See [PathPlanner]. */
    private val planner = PathPlanner(system)

    /** The target body, as a craft to steer by, relative to [attractor] at [time]. Null for none. */
    private fun bodyTarget(attractor: CelestialBody, time: Double): VesselKinematics? {
        val id = localTargetBody
        if (id.isEmpty() || id == attractor.id || id !in system.bodies) return null
        return VesselKinematics(
            vessel = -1L, referenceBodyId = attractor.id,
            position = system.positionOf(id, time).subInPlace(system.positionOf(attractor.id, time)),
            rotation = Quat.identity(),
            velocity = system.velocityOf(id, time).subInPlace(system.velocityOf(attractor.id, time)),
            angularVelocity = Vec3(),
        )
    }

    // --- touching the map -------------------------------------------------------------

    /** What the map last showed, for finding what a finger is on. See [keepMapView]. */
    private class MapView(
        val camera: Vec3,
        val rotation: Quat,
        /**
         * Along the path from now: times, and where (around the attractor), [MAP_SAMPLES] of each.
         */
        val times: DoubleArray,
        val points: Array<Vec3>,
        val burn: Vec3?,
        /** Other worlds: id, where, and radius. */
        val bodies: List<Triple<String, Vec3, Double>>,
        /** How far out the map shows, in metres. */
        val reach: Double = 0.0,
        /** The sea's named places on the world below that have been found: name and where. */
        val places: List<Pair<String, Vec3>> = emptyList(),
    )

    /**
     * A world's name on the map, where it is on a [width] x [height] screen, or with [place], a
     * named place on the world below.
     */
    class MapLabel(val name: String, val x: Float, val y: Float, val place: Boolean = false)

    /** The names of the worlds the map shows, placed on a [width] x [height] screen. */
    fun mapLabels(width: Float, height: Float): List<MapLabel> {
        if (!mapMode) return emptyList()
        val view = mapView ?: return emptyList()
        val at = FloatArray(2)
        return view.bodies.mapNotNull { (id, centre, _) ->
            if (centre.length > view.reach * MAP_LABEL_REACH) return@mapNotNull null
            if (!onScreen(view, centre, width, height, at)) return@mapNotNull null
            if (at[0] < 0f || at[1] < 0f || at[0] > width || at[1] > height) return@mapNotNull null
            MapLabel(system.body(id).displayName, at[0], at[1])
        } + placeLabels(view, width, height)
    }

    /** The found places on the world below, on its near side, each clear of the ones before it. */
    private fun placeLabels(view: MapView, width: Float, height: Float): List<MapLabel> {
        val out = ArrayList<MapLabel>()
        val at = FloatArray(2)
        for ((name, where) in view.places) {
            // On the side turned to the camera, not behind the world.
            if ((view.camera - where) dot where <= 0.0) continue
            if (!onScreen(view, where, width, height, at)) continue
            if (at[0] < 0f || at[1] < 0f || at[0] > width || at[1] > height) continue
            if (out.any { kotlin.math.hypot(it.x - at[0], it.y - at[1]) < MAP_PLACE_GAP }) continue
            out += MapLabel(name, at[0], at[1], place = true)
        }
        return out
    }

    /**
     * The sea's named places on [attractor] that this player has found, and where each one is now.
     * The rest stay hidden, in free play too.
     */
    private fun foundPlaces(attractor: CelestialBody, time: Double): List<Pair<String, Vec3>> {
        val found = client.wondersFound
        val wonders = com.rm.apogee.core.world.SeaWonders.all.filter { it.bodyId == attractor.id && it.id in found }
        if (wonders.isEmpty()) return emptyList()
        val rotation = attractor.rotationAt(time, Quat())
        return wonders.map {
            it.name to rotation.rotate(Vec3().setTo(it.direction).mulInPlace(attractor.radius), Vec3())
        }
    }

    @Volatile private var mapView: MapView? = null
    private var draggingBurn = false

    private fun keepMapView(plan: PathPlanner.Plan?, attractor: CelestialBody, time: Double, camera: Vec3, rotation: Quat, reach: Double) {
        val leg = plan?.current?.segments?.firstOrNull() ?: return
        val times = DoubleArray(MAP_SAMPLES)
        val points = Array(MAP_SAMPLES) { k ->
            val t = leg.start + (leg.end - leg.start) * k / (MAP_SAMPLES - 1)
            times[k] = t
            leg.stateAt(t).position
        }
        val here = system.positionOf(attractor.id, time)
        val bodies = targetBodies.filter { it.id != attractor.id }.map { b ->
            Triple(b.id, system.positionOf(b.id, time).subInPlace(here), b.radius)
        }
        mapView = MapView(camera.copy(), rotation.copy(), times, points, plan.burnPoint?.copy(), bodies, reach, foundPlaces(attractor, time))
    }

    private val mapTerrainCamera = Vec3()

    /** On the map, whether the path shown is one to plan burns on, or a course over the ground. */
    @Volatile var mapPlannable = true
        private set

    /** On (or under) a sea, near enough its surface. Ground below the datum isn't sea. */
    private fun afloat(attractor: CelestialBody, position: Vec3): Boolean =
        attractor.ocean != null && attractor.altitudeOf(position) < AFLOAT

    /** Whether the craft the map was last drawn for is a rocket, worked out once per design. */
    private var mapKindDesign: com.rm.apogee.core.craft.CraftDesign? = null
    private var mapRocket = false

    /** Which craft the map view was last opened over, so it only frames itself once. */
    private var mapFramedFor = Long.MIN_VALUE

    /**
     * Whether the craft in [state] is falling freely, so its orbit is where it's going: off the
     * ground and out of the water, and above the air, or where there's none, or near enough orbital
     * speed that the air hardly bends its path. Anything else (driving, sailing, flying on wings or
     * rotors) goes where it's steered, and gets its course over the ground drawn instead.
     */
    private fun fallingFree(attractor: CelestialBody, state: com.rm.apogee.core.world.VesselKinematics, time: Double, rocket: Boolean): Boolean {
        val position = state.position
        val fixed = attractor.toBodyFixed(position, attractor.rotationAt(time))
        if (attractor.heightAboveTerrain(position, fixed) < GROUNDED || afloat(attractor, position)) return false
        // A rocket off the ground is on its way up or down, and its path is what matters.
        if (rocket || attractor.atmosphere == null || attractor.altitudeOf(position) > attractor.atmosphereHeight) return true
        val over = attractor.surfaceVelocityAt(position, Vec3()).negateInPlace().addInPlace(state.velocity).length
        return over > kotlin.math.sqrt(attractor.gravitationalParameter / position.length) * NEAR_ORBITAL
    }

    /**
     * The craft's course over the ground for the next [COURSE_SECONDS] at the speed it's going now,
     * along a great circle, on the ground (or, flying, at its height), with a point at each minute.
     * Null when it's hardly moving.
     */
    private fun courseLine(attractor: CelestialBody, state: com.rm.apogee.core.world.VesselKinematics, time: Double): Pair<RenderLine, List<Vec3>>? {
        val position = state.position
        val up = position.normalized()
        val over = attractor.surfaceVelocityAt(position, Vec3()).negateInPlace().addInPlace(state.velocity)
        over.addScaledInPlace(up, -(over dot up))
        val speed = over.length
        if (speed < COURSE_SLOWEST) return null
        val along = over.mulInPlace(1.0 / speed)
        val rotation = attractor.rotationAt(time)
        val fixed = attractor.toBodyFixed(position, rotation)
        val flying = attractor.heightAboveTerrain(position, fixed) > GROUNDED && !afloat(attractor, position)
        val length = minOf(speed * COURSE_SECONDS, attractor.radius * COURSE_MOST)
        val sea = attractor.ocean != null
        fun at(distance: Double): Vec3 {
            val angle = distance / attractor.radius
            val d = Vec3().setTo(up).mulInPlace(kotlin.math.cos(angle)).addScaledInPlace(along, kotlin.math.sin(angle))
            val elevation = attractor.terrain?.elevation(rotation.inverseRotate(d, Vec3()))?.let { if (sea) maxOf(it, 0.0) else it } ?: 0.0
            val ground = attractor.radius + elevation
            return d.mulInPlace(if (flying) maxOf(position.length, ground) else ground)
        }
        val points = (0..COURSE_STEPS).map { at(length * it / COURSE_STEPS) }
        val ticks = ArrayList<Vec3>()
        // A mark a minute, or every few when they'd crowd together.
        val every = 60.0 * maxOf(1.0, kotlin.math.ceil(length / COURSE_TICKS_MOST / (speed * 60.0)))
        var minute = every
        while (speed * minute <= length) { ticks.add(at(speed * minute)); minute += every }
        return RenderLine(points, COURSE_COLOR) to ticks
    }

    /**
     * [line] drawn [width] wide, as copies of it either side along the ground, because a line on
     * its own is a single pixel and a course across the ground got lost in it.
     */
    private fun thick(line: RenderLine, width: Double): List<RenderLine> {
        val points = line.points
        if (points.size < 2) return listOf(line)
        val sides = points.indices.map { i ->
            val along = points[minOf(i + 1, points.size - 1)] - points[maxOf(i - 1, 0)]
            points[i].cross(along).normalizeInPlace()
        }
        return listOf(-1.0, -0.5, 0.0, 0.5, 1.0).map { k ->
            RenderLine(points.indices.map { i -> points[i] + sides[i] * (k * width) }, line.color)
        }
    }

    /**
     * The craft's path through space: the whole orbit, or, if it comes down, the arc until it meets
     * the ground, and where.
     */
    private fun fallLine(attractor: CelestialBody, orbit: Orbit, time: Double): Pair<RenderLine, Vec3?> {
        val highest = attractor.radius + maxOf(attractor.terrain?.maxElevation ?: 0.0, 0.0)
        if (orbit.periapsis > highest) return RenderLine(orbit.sample(192), ORBIT_COLOR) to null
        val span = if (orbit.isBound) orbit.period else FALL_LONGEST
        val sea = attractor.ocean != null
        val points = ArrayList<Vec3>(FALL_STEPS + 1)
        points.add(orbit.position.copy())
        for (k in 1..FALL_STEPS) {
            val t = span * k / FALL_STEPS
            val p = orbit.propagate(t).position
            val fixed = attractor.rotationAt(time + t).inverseRotate(p.normalized(), Vec3())
            val elevation = attractor.terrain?.elevation(fixed)?.let { if (sea) maxOf(it, 0.0) else it } ?: 0.0
            if (p.length <= attractor.radius + elevation) {
                points.add(p)
                return RenderLine(points, ORBIT_COLOR) to p
            }
            points.add(p)
        }
        return RenderLine(points, ORBIT_COLOR) to null
    }

    /**
     * Where [point] (around the attractor) is on a [width] x [height] screen, into [out]. False if
     * it's behind.
     */
    private fun onScreen(view: MapView, point: Vec3, width: Float, height: Float, out: FloatArray): Boolean {
        val d = view.rotation.inverseRotate(Vec3().setTo(point).subInPlace(view.camera))
        val depth = -d.z
        if (depth <= 1e-6) return false
        val f = (height / 2.0) / kotlin.math.tan(Math.toRadians(55.0) / 2.0)
        out[0] = (width / 2.0 + d.x / depth * f).toFloat()
        out[1] = (height / 2.0 - d.y / depth * f).toFloat()
        return true
    }

    /** The time on the path nearest [x], [y] on screen, and how far off it is, in px. */
    private fun nearestOnPath(view: MapView, x: Float, y: Float, width: Float, height: Float): Pair<Double, Float>? {
        val at = FloatArray(2)
        var best = -1
        var bestDistance = Float.MAX_VALUE
        for (k in view.points.indices) {
            if (!onScreen(view, view.points[k], width, height, at)) continue
            val d = kotlin.math.hypot(at[0] - x, at[1] - y)
            if (d < bestDistance) { bestDistance = d; best = k }
        }
        return if (best < 0) null else view.times[best] to bestDistance
    }

    /** A finger down on the map. True if it took hold of the burn, to drag it along the path. */
    fun mapPress(x: Float, y: Float, width: Float, height: Float): Boolean {
        val view = mapView ?: return false
        val burn = view.burn ?: return false
        val at = FloatArray(2)
        if (!onScreen(view, burn, width, height, at)) return false
        draggingBurn = kotlin.math.hypot(at[0] - x, at[1] - y) < MAP_GRAB_PIXELS
        return draggingBurn
    }

    /** The finger holding the burn moved, so move the burn to where it now is on the path. */
    fun mapDrag(x: Float, y: Float, width: Float, height: Float) {
        if (!draggingBurn) return
        val view = mapView ?: return
        val (time, _) = nearestOnPath(view, x, y, width, height) ?: return
        terrainScope.launch { moveBurn(time) }
    }

    /** Let go of the burn. */
    fun mapRelease() {
        if (!draggingBurn) return
        draggingBurn = false
        terrainScope.launch { burnEdited() }
    }

    /**
     * A tap on the map: a world, to target it, or the path, to plan a burn there or move the one
     * planned. True if it meant something.
     */
    fun mapTap(x: Float, y: Float, width: Float, height: Float): Boolean {
        val view = mapView ?: return false
        val at = FloatArray(2)
        for ((id, centre, radius) in view.bodies) {
            if (!onScreen(view, centre, width, height, at)) continue
            if (kotlin.math.hypot(at[0] - x, at[1] - y) < MAP_GRAB_PIXELS) {
                val index = targetBodies.indexOfFirst { it.id == id }
                terrainScope.launch { setTarget(if (localTargetBody == id) -1L else BODY_TARGET - index) }
                return true
            }
        }
        val (time, off) = nearestOnPath(view, x, y, width, height) ?: return false
        if (off > MAP_GRAB_PIXELS) return false
        val focus = client.controlledVessel?.let { client.vessel(it) } ?: return false
        terrainScope.launch { if (burnsOf(focus).isEmpty()) planBurnAt(time) else moveBurn(time, final = true) }
        return true
    }

    /**
     * The window to the targeted planet from the one the craft is at (or whose moon it's at), or
     * null when the target isn't a planet, or is this one.
     */
    private fun windowFor(attractor: CelestialBody, position: Vec3?, time: Double): WindowReadout? {
        val targetId = localTargetBody.takeIf { it.isNotEmpty() } ?: return null
        val target = system.bodies[targetId] ?: return null
        val star = target.parentId ?: return null
        val planet = when {
            attractor.parentId == star -> attractor
            attractor.parentId != null && system.body(attractor.parentId!!).parentId == star -> system.body(attractor.parentId!!)
            else -> return null
        }
        val parked = if (planet === attractor) position?.length ?: (attractor.radius * 1.1)
            else planet.radius * 1.2
        val w = com.rm.apogee.core.orbit.TransferWindow.between(system, planet.id, target.id, time, parked) ?: return null
        return WindowReadout(
            target.displayName, w.waitFor, Math.toDegrees(w.phase), Math.toDegrees(w.phaseNeeded),
            w.flight, w.departure, w.arrival,
        )
    }

    /** The planned body target, as the player last set it. Blank for none. */
    @Volatile var localTargetBody: String = ""
        private set

    /**
     * Has the path worked out afresh from where the craft is, going by the replica when it has one.
     */
    private fun askPlan(focus: ClientVessel, state: VesselKinematics, renderTime: Double, warping: Boolean) {
        val replica = prediction.replica?.takeIf { prediction.isReady && !warping }
        val bodyId = replica?.referenceBodyId ?: state.referenceBodyId
        val position = replica?.body?.position?.copy() ?: state.position.copy()
        val velocity = replica?.body?.linearVelocity?.copy() ?: state.velocity.copy()
        val time = if (replica != null) prediction.renderTime() ?: renderTime else client.latestSnapshot?.time ?: renderTime
        val target = client.vessel(localTarget)?.latest?.takeIf { it.referenceBodyId == bodyId }
        planner.ask(
            terrainScope,
            PathPlanner.Ask(
                bodyId, position, velocity, time, burnsOf(focus),
                mass = replica?.body?.mass ?: 0.0,
                dragArea = replica?.let { dragForces.dragArea(it) } ?: 0.0,
                targetBody = localTargetBody,
                targetPosition = target?.position?.copy(), targetVelocity = target?.velocity?.copy(),
            ),
        )
    }

    private val dragForces = com.rm.apogee.core.world.Forces()

    /**
     * [plan] as lines around body [aboutId]. Each leg is in its body's colour, with a moon's leg
     * drawn round the moon as it will be when the craft gets there. The coasting path only goes as
     * far as the planned burn, and the path after the burn is in the burn's own colour.
     */
    private fun planLines(plan: PathPlanner.Plan, aboutId: String): List<RenderLine> {
        val out = ArrayList<RenderLine>()
        val cut = plan.burn?.time ?: Double.MAX_VALUE
        fun legs(path: com.rm.apogee.core.orbit.Trajectory, until: Double, planned: Boolean) {
            for (segment in path.segments) {
                if (segment.start >= until) break
                val end = minOf(segment.end, until)
                val points = ArrayList<Vec3>(PLAN_POINTS)
                for (k in 0 until PLAN_POINTS) {
                    val t = segment.start + (end - segment.start) * k / (PLAN_POINTS - 1)
                    planner.drawnAbout(segment, segment.stateAt(t).position, aboutId, Vec3().also { points.add(it) })
                }
                val colour = if (planned) BURN_PATH_COLOR else if (segment.bodyId == aboutId) ORBIT_COLOR else MOON_PATH_COLOR
                out.add(RenderLine(points, colour))
            }
        }
        legs(plan.current, cut, planned = false)
        plan.planned?.let { legs(it, Double.MAX_VALUE, planned = true) }
        return out
    }

    /** The plan's landmarks: its high and low points, the burn, meeting a moon, and coming down. */
    private fun planMarkers(plan: PathPlanner.Plan, aboutId: String, reach: Double, lines: MutableList<RenderLine>) {
        val path = plan.planned ?: plan.current
        val first = path.segments.first()
        val o = first.orbit
        if (o.isBound && o.apoapsis < system.body(first.bodyId).sphereOfInfluence) {
            lines.add(marker(first.stateAt(first.start + o.timeToApoapsis).position, reach, APOAPSIS_COLOR))
        }
        if (o.timeToPeriapsis.isFinite() && first.start + o.timeToPeriapsis <= first.end) {
            lines.add(marker(first.stateAt(first.start + o.timeToPeriapsis).position, reach, PERIAPSIS_COLOR))
        }
        plan.burnPoint?.let { lines.add(marker(it, reach, BURN_COLOR)) }
        for (segment in path.segments.drop(1)) {
            // The moon it meets, where it will be then: its outline, and its reach.
            val met = system.body(segment.bodyId)
            if (segment.bodyId != aboutId && met.parentId == aboutId) {
                val centre = system.positionOf(met.id, segment.start).subInPlace(system.positionOf(aboutId, segment.start))
                val normal = met.orbit?.angularMomentum?.normalized() ?: Vec3.unitY()
                lines.add(ring(centre, normal, met.radius, MOON_PATH_COLOR))
                lines.add(ring(centre, normal, met.sphereOfInfluence, REACH_COLOR))
            }
            // Its low point around the moon it meets.
            val low = segment.orbit.timeToPeriapsis
            if (low.isFinite() && segment.start + low <= segment.end) {
                lines.add(marker(planner.drawnAbout(segment, segment.stateAt(segment.start + low).position, aboutId, Vec3()), reach, PERIAPSIS_COLOR))
            }
        }
    }

    /** A circle of [radius] around [centre], square to [normal]. */
    private fun ring(centre: Vec3, normal: Vec3, radius: Double, colour: FloatArray): RenderLine {
        val a = (if (kotlin.math.abs(normal.y) < 0.9) Vec3.unitY() else Vec3.unitX()).cross(normal).normalizeInPlace()
        val b = normal.cross(a)
        return RenderLine((0..96).map { k ->
            val angle = 2.0 * Math.PI * k / 96
            Vec3().setTo(centre).addScaledInPlace(a, radius * kotlin.math.cos(angle)).addScaledInPlace(b, radius * kotlin.math.sin(angle))
        }, colour)
    }

    /**
     * The moons of [attractor]: where each one goes round, and how far its pull reaches, at [time].
     */
    private val comms = com.rm.apogee.core.world.Comms(system)

    /** What the map shows of a surveyed body's ground: ore, water, or nothing. */
    @Volatile var mapResource: com.rm.apogee.core.part.ResourceType? = com.rm.apogee.core.part.ResourceType.ORE

    /** Whether the map shows the sea's currents. */
    @Volatile var mapCurrents: Boolean = false

    /** Whether the body the flown craft is around has a sea with currents to show. */
    val currentsHere: Boolean
        get() {
            val id = client.controlledVessel ?: return false
            val body = client.vessels.firstOrNull { it.id == id }?.latest?.referenceBodyId?.let { system.bodies[it] } ?: return false
            return body.ocean != null && body.terrain != null
        }

    /** Whether the body the flown craft is around has been surveyed. */
    val surveyedHere: Boolean
        get() {
            val id = client.controlledVessel ?: return false
            val body = client.vessels.firstOrNull { it.id == id }?.latest?.referenceBodyId ?: return false
            return body in client.surveyed
        }

    /** A surveyed [attractor]'s ore or water, as dots on its ground. The richer, the brighter. */
    private fun richnessDots(attractor: CelestialBody, time: Double, lines: MutableList<RenderLine>) {
        val resource = mapResource ?: return
        if (attractor.id !in client.surveyed) return
        val points = RichnessGrid.points(attractor, resource) ?: return
        val rotation = attractor.rotationAt(time)
        val base = if (resource == com.rm.apogee.core.part.ResourceType.WATER) WATER_COLOR else ORE_COLOR
        val size = attractor.radius * DOT_FRACTION
        val lift = attractor.radius + (attractor.terrain?.maxElevation ?: 0.0)
        val d = Vec3()
        val data = points.data
        for (k in 0 until points.count) {
            d.setTo(data[4 * k].toDouble(), data[4 * k + 1].toDouble(), data[4 * k + 2].toDouble())
            val at = rotation.rotate(d, Vec3()).mulInPlace(lift)
            val r = data[4 * k + 3]
            val color = floatArrayOf(base[0], base[1], base[2], 0.25f + 0.75f * r)
            lines.add(dot(at, size, color))
        }
    }

    /**
     * [attractor]'s sea currents as arrows on the water, longer and brighter the faster, on the
     * side facing [camera] only. The lines aren't hidden by the globe, and the far side's showed
     * through it.
     */
    private fun currentArrows(attractor: CelestialBody, time: Double, camera: Vec3, lines: MutableList<RenderLine>) {
        val points = CurrentGrid.points(attractor, null, client.weather?.seed ?: 0) ?: return
        val rotation = attractor.rotationAt(time)
        val gap = attractor.radius * Math.toRadians(CurrentGrid.STEP)
        val lift = attractor.radius * CURRENT_LIFT
        val data = points.data
        val d = Vec3(); val v = Vec3(); val side = Vec3()
        for (k in 0 until points.count) {
            d.setTo(data[6 * k].toDouble(), data[6 * k + 1].toDouble(), data[6 * k + 2].toDouble())
            v.setTo(data[6 * k + 3].toDouble(), data[6 * k + 4].toDouble(), data[6 * k + 5].toDouble())
            val speed = v.length
            rotation.rotate(d, d)
            if ((camera dot d) < attractor.radius) continue
            val fast = (speed / CURRENT_FAST).coerceAtMost(1.0)
            val length = gap * CURRENT_ARROW * (0.4 + 0.6 * fast)
            rotation.rotate(v, v).normalizeInPlace()
            val tail = Vec3().setTo(d).mulInPlace(lift).addScaledInPlace(v, -length / 2)
            val head = Vec3().setTo(tail).addScaledInPlace(v, length)
            side.setTo(d).crossInPlace(v)
            val colour = floatArrayOf(CURRENT_COLOR[0], CURRENT_COLOR[1], CURRENT_COLOR[2], (0.35 + 0.65 * fast).toFloat())
            val barb = length * 0.3
            lines.add(RenderLine(listOf(tail, head, head.copy().addScaledInPlace(v, -barb).addScaledInPlace(side, barb * 0.6)), colour))
            lines.add(RenderLine(listOf(head, head.copy().addScaledInPlace(v, -barb).addScaledInPlace(side, -barb * 0.6)), colour))
        }
    }

    /** A small diamond at [at], face-on to the planet. */
    private fun dot(at: Vec3, size: Double, color: FloatArray): RenderLine {
        val radial = at.normalized()
        val a = (if (kotlin.math.abs(radial.y) < 0.9) Vec3.unitY() else Vec3.unitX()).cross(radial).normalizeInPlace()
        val b = radial.cross(a).normalizeInPlace()
        return RenderLine(listOf(at + a * size, at + b * size, at - a * size, at - b * size, at + a * size), color)
    }

    /**
     * The ground stations, and a probe's link home: from the craft at [craft] through its relays to
     * the station it reaches, all in [attractor]'s frame.
     */
    private fun signalLines(focusId: Long, craft: Vec3, attractor: CelestialBody, time: Double, reach: Double, lines: MutableList<RenderLine>) {
        val here = system.positionOf(attractor.id, time)
        for (station in comms.stationPositions(time)) lines.add(marker(station.subInPlace(here), reach, STATION_COLOR))
        val systems = client.systems?.takeIf { it.vessel == focusId && it.needsSignal } ?: return
        if (systems.signal == com.rm.apogee.core.world.Signal.NONE) return
        val path = ArrayList<Vec3>()
        path.add(craft.copy())
        for (id in systems.relays) {
            val relay = client.vessels.firstOrNull { it.id == id }?.latest ?: return
            val body = system.bodies[relay.referenceBodyId] ?: return
            path.add(system.positionOf(body.id, time).subInPlace(here).addInPlace(relay.position))
        }
        val last = path.last().copy().addInPlace(here)
        val station = comms.stationInSight(last, time) ?: return
        path.add(station.subInPlace(here))
        lines.add(RenderLine(path, SIGNAL_COLOR))
    }

    private fun moonLines(attractor: CelestialBody, time: Double, lines: MutableList<RenderLine>) {
        for (moon in system.childrenOf(attractor.id)) {
            val orbit = moon.orbit ?: continue
            lines.add(RenderLine(orbit.sample(160), MOON_ORBIT_COLOR))
            lines.add(ring(orbit.stateAt(time).position, orbit.angularMomentum.normalized(), moon.sphereOfInfluence, REACH_COLOR))
        }
    }

    /**
     * The other worlds, seen from around [attractor]: a moon in the sky, the planet from its moon,
     * and on the map. Each is drawn where it really is, in the far pass, where the globe hides
     * whatever is behind it.
     */
    private fun appendBodies(farItems: MutableList<RenderItem>, attractor: CelestialBody, time: Double, camera: Vec3) {
        val here = system.positionOf(attractor.id, time)
        // The world here: a giant's rings around it, and the veil that hides a clouded world's
        // ground from above.
        val turned = attractor.rotationAt(time)
        com.rm.apogee.render.GiantLook.rings(attractor, Vec3(), turned, { RenderItem.partKey(BODY_KEY - 1, 0, it) }, farItems)
        com.rm.apogee.core.weather.Climate.of(attractor.id)?.takeIf { it.veil > 0.0 }?.let { climate ->
            if (attractor.altitudeOf(camera) > climate.veil) {
                val tint = com.rm.apogee.render.SkyColours.of(attractor.id).cloud
                farItems.add(
                    RenderItem(
                        shape = com.rm.apogee.core.part.MeshSpec.Sphere(attractor.radius + climate.veil),
                        position = Vec3(), rotation = turned,
                        color = floatArrayOf(tint[0] * 0.92f, tint[1] * 0.92f, tint[2] * 0.92f, 1f),
                        key = RenderItem.partKey(BODY_KEY - 2, 0, 0), sky = true,
                    )
                )
            }
        }
        for ((k, body) in system.bodies.values.withIndex()) {
            if (body.id == attractor.id || body.parentId == null) continue
            if (com.rm.apogee.render.GiantLook.isGiant(body.id)) {
                com.rm.apogee.render.GiantLook.items(
                    body, system.positionOf(body.id, time).subInPlace(here), body.rotationAt(time),
                    { RenderItem.partKey(BODY_KEY, k, 1 + it) }, farItems,
                )
                continue
            }
            farItems.add(
                RenderItem(
                    shape = com.rm.apogee.core.part.MeshSpec.Sphere(body.radius),
                    position = system.positionOf(body.id, time).subInPlace(here),
                    rotation = body.rotationAt(time),
                    color = BODY_COLOURS[body.id] ?: BODY_COLOUR,
                    key = RenderItem.partKey(BODY_KEY, k, 0),
                    sky = true,
                )
            )
        }
    }

    /**
     * A small diamond around a point, for apsis and craft markers.
     *
     * It's sized as a fraction of the view instead of in metres, so a marker stays the same size on
     * screen whether the orbit is 100 km or 10,000 km across.
     */
    private fun marker(at: Vec3, viewScale: Double, color: FloatArray): RenderLine {
        val size = viewScale * MARKER_FRACTION
        // Any two axes at right angles to the radius put the diamond face-on to the planet, which
        // is the way it gets read.
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
        // How old the server's word is by the time we act on it. It's measured against the server's
        // clock as followed over many snapshots, not this one's arrival alone, whose jitter moved
        // the present, and the craft drawn at it, by metres at orbital speed.
        if (snapshot != null && snapshot.tick != clockSampledTick) {
            clockSampledTick = snapshot.tick
            serverClock.sample(snapshot.time, client.latestSnapshotNanos / 1e9)
        }
        val present = serverClock.now(now / 1e9)
        val age = present?.let { (it - (snapshot?.time ?: it)).coerceIn(0.0, MAX_SNAPSHOT_AGE) }
            ?: ((now - client.latestSnapshotNanos) / 1e9)
        // Rebuilt (staging, a part lost), so the replica starts again from the server's word. Where
        // the craft was drawn gets carried over and eased away, not jumped from (7.6 m at a staging
        // in orbit).
        val carryFrom = if (prediction.needsAdopting(focus.design) && prediction.isReady && lastAdvanceNanos != 0L)
            predictedPosition.copy().addScaledInPlace(prediction.velocity() ?: Vec3(), (now - lastAdvanceNanos) / 1e9) else null
        if (prediction.needsAdopting(focus.design)) {
            // On the server's clock. See ClientPrediction.adopt.
            prediction.adopt(focus.design, state, snapshot?.time ?: 0.0, client.weather)
            pushControlsToPrediction()
            lastReconciledTick = -1
        }

        repeat(pendingLocalStages.getAndSet(0)) { prediction.stage() }

        // Move on first, then reconcile, so both are measured to the same moment. The other way
        // round spends the time since the last frame twice. It goes by the server's clock as
        // followed (the same present every other craft is drawn at), not the local one. Stepped by
        // the local clock, the flown craft drifted off the present the rest were drawn at by a
        // millisecond or so between snapshots, and each snapshot pulled it back. At a thousand
        // metres a second it shook by a metre a frame against a stage just let go of, which itself
        // ran smoothly.
        if (lastAdvanceNanos != 0L) {
            val elapsed = if (present != null && !lastPresent.isNaN()) (present - lastPresent).coerceAtLeast(0.0)
                else (now - lastAdvanceNanos) / 1e9
            prediction.advance(elapsed)
        }
        lastAdvanceNanos = now
        lastPresent = present ?: Double.NaN

        if (snapshot != null && snapshot.tick != lastReconciledTick) {
            lastReconciledTick = snapshot.tick
            prediction.reconcile(state, age, snapshot.time, neighboursOf(focus, state, snapshot.time), anchored = focus.anchored)
        }
        prediction.sync(focus.currentStage, focus.activatedParts, focus.fuel)
        client.systems?.takeIf { it.vessel == focus.id }?.let { prediction.syncSystems(it) }
        syncPlan(focus)
        refreshStageCards()

        prediction.renderPosition(predictedPosition, state.referenceBodyId)
        if (carryFrom != null && prediction.isReady) {
            prediction.carryOffset(carryFrom.subInPlace(predictedPosition))
            prediction.renderPosition(predictedPosition, state.referenceBodyId)
        }
        prediction.renderRotation(predictedRotation)
        return if (prediction.isReady) predictedPosition else state.position
    }

    /**
     * What each craft's moving parts are doing, kept between frames: the pose being eased towards
     * the latest one received, and each wheel's and propeller's turn so far.
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
    /**
     * Each craft's parts when it was last seen, plus the ones it had lost before, which is what a
     * total loss loses.
     */
    private val lastParts = HashMap<Long, Int>()

    /** The first hard blow each craft took, for the crash report. */
    private val firstBlow = HashMap<Long, ServerMessage.PartEvent>()
    /** What finished each part off, most recent last. */
    private val lastCause = HashMap<Long, String>()

    /** The craft the camera last framed, and how many parts it had. */
    private var framedFor = -1L
    private var framedParts = 0

    /**
     * A camera swung round a craft on a hillside can end up inside the hill, looking at the back of
     * the ground. It gets lifted clear, and turned back to look at the craft. The ground is all
     * that stops it. Water doesn't, so you can swing down under a boat to see its keel, or follow a
     * submarine anywhere above the sea floor. It used to be kept above the waves unless the craft
     * had gone well under, and that took away half the angles at sea. I didn't want that.
     */
    private fun keepCameraAboveGround(attractor: com.rm.apogee.core.orbit.CelestialBody, focus: Vec3, time: Double) {
        val terrain = attractor.terrain ?: return
        val rotation = attractor.rotationAt(time)
        attractor.toBodyFixed(cameraPosition, rotation, scratchCameraClear)
        // The solid ground, sea floor and all, not the sea's surface over it.
        val above = cameraPosition.length - terrain.solidRadius(scratchCameraClear.normalizeInPlace())
        if (above >= CAMERA_CLEARANCE) return
        val r = cameraPosition.length
        cameraPosition.mulInPlace((r + CAMERA_CLEARANCE - above) / r)
        scratchCameraClear.setTo(focus).subInPlace(cameraPosition)
        com.rm.apogee.core.math.quatLookAt(scratchCameraClear, cameraPosition.normalized(), cameraRotation)
    }

    private val scratchCameraClear = Vec3()

    /**
     * A craft's attitude at [time], turned on from its snapshot by its spin, so a tumbling spent
     * stage turns smoothly, not in twenty steps a second.
     */
    private fun spunOn(observed: ClientVessel.Observation, time: Double): Quat {
        val state = observed.kinematics
        val carry = (time - observed.time).coerceIn(-MAX_EXTRAPOLATION_SECONDS, MAX_EXTRAPOLATION_SECONDS)
        val w = state.angularVelocity
        val rate = w.length
        if (rate < 1e-6 || carry == 0.0) return state.rotation.copy()
        return Quat.fromAxisAngle(w.copy().mulInPlace(1.0 / rate), rate * carry) * state.rotation
    }

    /** Where each other craft was drawn last frame, and the easing still owed. */
    private class Drawn(val position: Vec3, val rotation: Quat, var observedAt: Double, var nanos: Long) {
        val offset = Vec3()
        var renderTime = 0.0
        val turn = Quat.identity()
    }
    private val drawn = HashMap<Long, Drawn>()

    /**
     * Eases [position] and [rotation] (where a craft would be drawn from its newest snapshot) out
     * of any jump from where it was drawn a frame ago. When a snapshot lands that disagrees with
     * the guess carried from the one before, the difference gets kept as an offset and let go over
     * a tenth of a second, instead of the craft hopping. A big difference is a real jump, and isn't
     * smoothed.
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
            // Where it would have been, carried on from last frame by the frame's own clock, which
            // is what the ground moved by, not the wall clock. The two drift apart by tens of
            // milliseconds, and at the ground's speed that looks like a jump to be eased.
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

    /** What can be heard, rebuilt each frame, with as many voices as the device's tier allows. */
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

    /** How close the camera is to waves breaking, 0..1, eased and checked four times a second. */
    private var shore = 0.0
    private var shoreTarget = 0.0
    private var shoreLookedNanos = 0L
    private var shoreEasedNanos = 0L

    /**
     * Surf: loud where land and sea meet within a couple of hundred metres of the camera, low down,
     * and nothing over open water, inland or from high up. It's a ring of points around the spot
     * below the camera, checked for land and for sea.
     */
    /**
     * How near [camera] (body-fixed) is to the Cape's [place] (a body-fixed unit direction) to hear
     * it: 1 within [CAPE_HEARD_FULL] m of it and low down, and nothing by [CAPE_HEARD_UNTIL] or
     * high above it. Terra's Cape only.
     */
    private fun nearCape(attractor: CelestialBody, camera: Vec3, place: Vec3): Double {
        if (attractor.id != com.rm.apogee.core.orbit.SolarSystem.HOMEWORLD_ID) return 0.0
        val height = camera.length - attractor.radius - (attractor.terrain?.elevation(place) ?: 0.0)
        val across = camera.length * kotlin.math.acos((scratchCape.setTo(camera).normalizeInPlace() dot place).coerceIn(-1.0, 1.0))
        val away = kotlin.math.sqrt(across * across + height.coerceAtLeast(0.0) * height.coerceAtLeast(0.0))
        return 1.0 - smoothstepD(CAPE_HEARD_FULL, CAPE_HEARD_UNTIL, away)
    }

    /** Each body's paving, once it's built (empty for one with none). See [Paving]. */
    private val pavings = com.rm.apogee.core.concurrentMapOf<String, com.rm.apogee.render.Paving.Built>()
    private val pavingAsked = com.rm.apogee.core.concurrentSetOf<String>()

    /**
     * The Cape's paving, laid on the turning ground at [time], when the camera is near enough to
     * see it. It's built once, off the frame thread, the first time it's wanted.
     */
    private fun appendPaving(items: MutableList<RenderItem>, attractor: CelestialBody, time: Double, camera: Vec3) {
        val field = attractor.terrain as? com.rm.apogee.core.terrain.TerrainField ?: return
        val built = pavings[attractor.id]
        if (built == null) {
            if (pavingAsked.add(attractor.id)) terrainScope.launch(Dispatchers.Default) {
                pavings[attractor.id] = com.rm.apogee.render.Paving.build(field)
                    ?: com.rm.apogee.render.Paving.Built(Vec3(), emptyList())
            }
            return
        }
        if (built.pieces.isEmpty()) return
        val rotation = attractor.rotationAt(time)
        val position = rotation.rotate(built.origin)
        if (position.distanceTo(camera) > PAVING_SEEN) return
        for ((k, piece) in built.pieces.withIndex()) {
            items.add(
                RenderItem(
                    shape = piece.shape,
                    position = position.copy(),
                    rotation = rotation.copy(),
                    color = piece.colour,
                    key = RenderItem.partKey(PAVING_KEY, k, 0),
                    decal = piece.shape.order,
                )
            )
        }
    }

    /**
     * The runway's approach lights, body-fixed: four beside each end, where the glide slope comes
     * down, to the left of it as you come in, with the lowest setting outermost. Null until worked
     * out, and empty away from Terra.
     */
    private val approachLights: List<Pair<Vec3, Double>> by lazy {
        val terra = system.body("terra")
        val field = terra.terrain ?: return@lazy emptyList()
        val out = ArrayList<Pair<Vec3, Double>>()
        for (sense in doubleArrayOf(1.0, -1.0)) {
            val threshold = if (sense > 0) com.rm.apogee.core.terrain.TerrainField.RUNWAY_WEST else com.rm.apogee.core.terrain.TerrainField.RUNWAY_EAST
            val east = threshold + sense * com.rm.apogee.core.world.Approach.AIM
            // Landing east, the left is north. Landing west, it's south.
            for ((k, setAt) in com.rm.apogee.core.world.Approach.LIGHTS.withIndex()) {
                val out3 = com.rm.apogee.core.terrain.TerrainField.RUNWAY_HALF_WIDTH + PAPI_CLEAR + (com.rm.apogee.core.world.Approach.LIGHTS.size - 1 - k) * PAPI_SPACING
                val direction = com.rm.apogee.core.orbit.SolarSystem.capeDirection(east, com.rm.apogee.core.terrain.TerrainField.RUNWAY_NORTH + sense * out3)
                out += direction.copy().mulInPlace(terra.radius + field.elevation(direction) + PAPI_HEIGHT) to setAt
            }
        }
        out
    }

    /**
     * The approach lights, lit white or red by the angle they're seen from, here from the camera,
     * the way a pilot sees them. Far off, they're drawn bigger, the way a light's glare is.
     */
    private fun appendApproachLights(items: MutableList<RenderItem>, attractor: CelestialBody, time: Double, camera: Vec3) {
        if (attractor.id != "terra" || mapMode) return
        val rotation = attractor.rotationAt(time)
        for ((k, light) in approachLights.withIndex()) {
            val (at, setAt) = light
            val position = rotation.rotate(at)
            val seen = Vec3().setTo(camera).subInPlace(position)
            val distance = seen.length
            if (distance > com.rm.apogee.core.world.Approach.REACH || distance < 1.0) continue
            val up = position.normalized()
            val angle = Math.toDegrees(kotlin.math.asin(((seen dot up) / distance).coerceIn(-1.0, 1.0)))
            val white = com.rm.apogee.core.world.Approach.white(angle, setAt)
            val size = (distance * PAPI_GLARE).coerceAtLeast(1.0)
            items.add(
                RenderItem(
                    shape = PAPI_LIGHT, position = position, rotation = Quat(),
                    color = if (white) PAPI_WHITE else PAPI_RED,
                    scale = Vec3(size, size, size), ambient = 1.2f, wrap = false,
                    key = RenderItem.partKey(PAPI_KEY, k, 0),
                ),
            )
        }
    }

    private val scratchCape = Vec3()
    private val scratchEar = Vec3()

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

    /** How much the flown craft's hull is still ticking with the change of pressure. */
    private val hullSettling = com.rm.apogee.audio.HullSettling()
    private var settlingFor = -1L

    /**
     * The flown craft as it was last heard, to catch it staging, lighting up and opening a chute.
     */
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
            PartEventKind.DOCKED, PartEventKind.HITCHED, PartEventKind.HOOKED -> SoundScene.Kind.LATCH
            PartEventKind.UNDOCKED, PartEventKind.UNHITCHED, PartEventKind.UNHOOKED -> SoundScene.Kind.RELEASE
            // A line snapping cracks like a part torn away.
            PartEventKind.SNAPPED -> SoundScene.Kind.DETACHED
        }
        val position = attractor.rotationAt(lastRenderTime).rotate(bodyFixed, Vec3())
        val shot = sound.shot(
            listener, kind, position, event.amount, own = event.vessel == focusId,
            material = groundMaterial(attractor, bodyFixed), water = event.cause == "water",
        ) ?: return
        AudioEngine.event(shot.recipe, shot.flags, (event.time * 1000).toInt() xor event.vessel.toInt(), shot.delay, shot.params)
    }

    /**
     * The ground under a craft at [position], as its wheels hear it: how much it crunches, and how
     * soft it is (0 rock to 1 sand or snow). Checked a few times a second.
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

    /** What the ground is at [bodyFixed], for how an impact would ring on it. */
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
                pumping = baseService?.refuelling == true,
            )
        }
        // Paused, the world is still, and so is everything in it.
        if ((client.latestSnapshot?.warp ?: 1.0) <= 0.0) {
            synchronized(soundLock) { if (!soundStopped) AudioEngine.scene(0, sound.keys, sound.recipes, sound.flags, sound.params) }
            return
        }
        sound.build(listener, soundCrafts.values.toList(), own)
        synchronized(soundLock) {
            if (soundStopped) return
            AudioEngine.scene(sound.count, sound.keys, sound.recipes, sound.flags, sound.params)
            AudioEngine.room(if (listener.inAir) 0.12f else 0.45f)
        }

        // A warning coming on, now and then at most.
        val caution = !wrecked && (telemetry.overheating || telemetry.straining)
        val now = System.nanoTime()
        if (caution && !heardCaution && now - lastCautionNanos > CAUTION_SPACING_NANOS) {
            com.rm.apogee.audio.Sounds.caution()
            lastCautionNanos = now
        }
        heardCaution = caution

        // The flown craft's own moments: a stage going, an engine lighting, and a chute opening.
        if (wrecked) return
        val hull = if (listener.inAir) 0 else VoiceFlags.HULL
        fun play(recipe: Int, strength: Float) {
            val v = FloatArray(com.rm.apogee.audio.SharedParams.COUNT)
            v[0] = strength
            AudioEngine.event(recipe, hull, recipe * 7919 + heardStage, 0f, v)
        }
        // Heard when it opens, not when it's armed.
        val deploy = animations[focus.id]?.target?.deploy
        chuteState = chuteStateOf(focus, deploy)
        if (chuteState != "OPEN") framedChute = false
        // Each opening is heard: the drogue, then the main (as part + 1000).
        val chutes = focus.activatedParts.filter { i ->
            focus.design.parts.getOrNull(i)?.let { catalog[it.partId]?.hasModule<com.rm.apogee.core.part.Parachute>() } == true &&
                (deploy?.getOrNull(i) ?: 0.0) > 0.0
        }.flatMap { i ->
            if ((deploy?.getOrNull(i) ?: 0.0) > com.rm.apogee.core.part.Parachute.DROGUE_FULL + 0.01) listOf(i, i + 1000) else listOf(i)
        }.toSet()
        val burning = soundCrafts[focus.id]?.let { c -> c.output.any { it > 0.05 } } ?: false
        // Legs locking down or stowing: a clunk as each one reaches the end of its travel.
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

    /** Whether the camera has widened out for the flown craft's open chute yet. */
    private var framedChute = false

    /** The flown craft's parachute, for the HUD: "ARMED", "OPEN", or null. */
    @Volatile
    var chuteState: String? = null
        private set

    // --- docking ----------------------------------------------------------------

    /** Lining up to dock: how far, how fast, how far off square, and whether the magnets would take it now. */
    class DockReadout(
        val distance: Double, val closing: Double, val angle: Double, val ready: Boolean, val partner: String,
        /** Our velocity relative to theirs, and the unit line from our port to theirs, inertial. */
        val relative: Vec3 = Vec3(), val line: Vec3 = Vec3(),
        /** Coming in faster than the magnets will take, and near enough for it to matter. */
        val tooFast: Boolean = false,
    )

    /** Somewhere the flown craft is joined and can let go: a docking part, and what it holds. */
    class Joint(val part: Int, val label: String, val hitch: Boolean)

    /**
     * Where the flown craft's nearest free docking part stands relative to another craft's, or null
     * for nothing near.
     */
    @Volatile var dockReadout: DockReadout? = null
        private set

    /** The flown craft's docked rings and clamps, and coupled hitches. */
    @Volatile var joints: List<Joint> = emptyList()
        private set

    /** Lets go at part [part] of the craft being flown. */
    suspend fun undock(part: Int) {
        withControlledVessel { client.send(Command.Undock(it, part)) }
    }

    /** Founds the flown craft where it rests, or lets it go if [founded] is false. */
    suspend fun found(founded: Boolean) {
        withControlledVessel { client.send(Command.Anchor(it, founded)) }
    }

    /** Fills the flown craft from the base it's on or docked to, or stops. */
    suspend fun refuel(on: Boolean) {
        withControlledVessel { client.send(Command.Refuel(it, on)) }
    }

    /** Empties the flown craft's ore and water into what it stands on or is docked to, or stops. */
    suspend fun unload(on: Boolean) {
        withControlledVessel { client.send(Command.Unload(it, on)) }
    }

    /** Switches the flown craft's drills and converters. */
    suspend fun setIndustry(drilling: Boolean, refining: Boolean) {
        withControlledVessel { client.send(Command.SetIndustry(it, drilling, refining)) }
    }

    /** Switches [base]'s refinery, leaving its drills as they are. */
    suspend fun refine(base: ServerMessage.BaseStatus, on: Boolean) {
        client.send(Command.SetIndustry(base.vessel, base.drilling, on))
    }

    /** What the flown craft can do with a base right now. Null until the server has said. */
    val baseService: ServerMessage.Service?
        get() = client.service?.takeIf { it.vessel == client.controlledVessel }

    /** The founded base nearest the flown craft (or the one being flown), for its card. */
    val nearestBase: ServerMessage.BaseStatus? get() = client.nearestBase

    /** Shared with another player: who flies it ("me", "them" or "both"). */
    val sharedWith: ServerMessage.DockedWith?
        get() = client.dockedWith?.takeIf { it.vessel == client.controlledVessel }

    /** This player's id, to tell "me" from "them" in [sharedWith]. */
    val myId: String get() = client.clientId

    suspend fun setDockPilot(pilot: String) {
        withControlledVessel { client.send(Command.SetDockPilot(it, pilot)) }
    }

    /**
     * A docking part's face on craft [design] at [state]: where it is (inertial) and which way it
     * faces.
     */
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

        // Our free docking parts, and theirs on the target, or on anything close.
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
            // Brought to the same moment as ours. Another craft's last word can be a few snapshots
            // older, and at orbital speed each one is a hundred metres.
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

    /** The warp clock: world time as drawn, moved on at the warp rate. */
    private var warpClockTime = Double.NaN
    private var warpClockNanos = 0L

    /**
     * The world time to draw a warped frame at: a clock that runs at [warp] times real time and
     * eases toward [target] (the snapshots' time), instead of jumping with every one. Under physics
     * warp it runs a snapshot and a half behind, so every craft has a snapshot either side of it to
     * be drawn between.
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
     * Where [vessel] is, and how it's turned, at [time], into [position] and [rotation]. Under
     * physics warp it's between its last two snapshots, on a curve through both positions with both
     * velocities, and the turn between the two attitudes, so it glides instead of stepping at the
     * snapshot rate. On rails, it's along its orbit from the latest one.
     */
    private fun sampled(
        vessel: ClientVessel,
        time: Double,
        attractor: com.rm.apogee.core.orbit.CelestialBody,
        warp: Double,
        position: Vec3,
        rotation: Quat,
    ): Boolean {
        // The two either side of the frame's time. The frame is drawn behind the newest snapshot,
        // and often before the one under it too.
        val (a, b) = vessel.around(time) ?: return false
        val newest = vessel.observed ?: b
        if (warp <= World.PHYSICS_WARP && a != null && b.time > a.time && time <= b.time &&
            a.kinematics.referenceBodyId == b.kinematics.referenceBodyId) {
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
        // Before everything kept, or past the newest, so it's carried from the nearest.
        val from = if (time < b.time && warp <= World.PHYSICS_WARP) b else newest
        position.setTo(carried(from, time, attractor, warp) ?: return false)
        rotation.setTo(from.kinematics.rotation)
        return true
    }

    /**
     * Where a craft last seen at [observed] is at [renderTime]. At real time that's along its
     * velocity for the fraction of a second between snapshots. Warped, when a snapshot can be most
     * of an orbit apart, it's exactly along its orbit, or round with the ground if it's sitting on
     * it.
     */
    private fun carried(
        observed: ClientVessel.Observation?,
        renderTime: Double,
        attractor: com.rm.apogee.core.orbit.CelestialBody,
        warp: Double,
    ): Vec3? {
        observed ?: return null
        val state = observed.kinematics
        // Backward as well as forward. The frame's time can sit a little behind the newest snapshot
        // (the flown craft's replica sets it), and a craft left where the snapshot put it, while
        // the ground under it is drawn a few hundredths of a second earlier, stands metres off, and
        // hops back each time the two clocks cross.
        val carry = (renderTime - observed.time)
            .coerceIn(-MAX_EXTRAPOLATION_SECONDS, MAX_EXTRAPOLATION_SECONDS * maxOf(warp, 1.0))
        if (carry < 0.5) return Vec3().setTo(state.position).addScaledInPlace(state.velocity, carry)
        val ground = attractor.surfaceVelocityAt(state.position, Vec3())
        return if (ground.distanceTo(state.velocity) < 0.5) {
            // Parked, so it turns with the planet.
            val bodyFixed = attractor.toBodyFixed(state.position, attractor.rotationAt(observed.time), Vec3())
            attractor.rotationAt(observed.time + carry).rotate(bodyFixed, Vec3())
        } else {
            com.rm.apogee.core.orbit.Orbit(state.position, state.velocity, attractor.gravitationalParameter)
                .propagate(carry).position
        }
    }

    /** Where each craft last had a part destroyed, body-fixed, which is where its wreck is. */
    private val wreckSite = HashMap<Long, Vec3>()
    private val wreckTime = HashMap<Long, Double>()

    /** Where the craft being flown was last drawn, body-fixed. */
    /** Each lost craft's wreckage, and where the camera is looking among it (body-fixed). */
    private val wreckPieces = HashMap<Long, Set<Long>>()
    private val wreckLook = HashMap<Long, Vec3>()

    private class LastSeen(val id: Long, val bodyId: String, val bodyFixed: Vec3, val name: String)
    private var lastSeen: LastSeen? = null

    /**
     * A craft with no parts standing where the flown one was last seen, moving with the ground.
     * It's something for the camera to keep looking at once the real one is gone. Null if it was
     * never seen, or it's somewhere else.
     */
    private fun wreckStandIn(id: Long): ClientVessel? {
        val seen = lastSeen?.takeIf { it.id == id } ?: return null
        val body = system.bodies[seen.bodyId] ?: return null
        val time = (client.latestSnapshot?.time ?: 0.0) +
            if (client.latestSnapshotNanos == 0L) 0.0 else (System.nanoTime() - client.latestSnapshotNanos) / 1e9
        // Where it came apart, if that's known, and otherwise where it was last seen. It was lost
        // at speed, and the last frame it was drawn in was well short of where it hit.
        val lost = wreckSite[id]?.copy() ?: seen.bodyFixed.copy()
        // Its wreckage, once it has any: the pieces that were beside it when it went, followed as
        // they tumble on. The camera used to watch the spot where the last part died, often dug
        // into the ground, while what was left skidded off out of sight, which I didn't like. It
        // keeps looking until some turn up, because they can arrive a snapshot after the loss.
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
        // Never inside the ground, but a couple of metres above it.
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

    /** What happened, in a line: the first blow and what it did, or whatever else took it. */
    /**
     * The player's crew who were aboard craft [id] when it was lost, by name: the ones the roster
     * now remembers as last aboard it.
     */
    @Volatile private var wreckedId: Long? = null

    val crewLostWith: List<String>
        get() {
            val id = wreckedId ?: return emptyList()
            return client.roster.filter { it.status == com.rm.apogee.core.crew.CrewStatus.LOST && it.lastVessel == id }.map { it.name }
        }

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

    /**
     * The wake and bow spray of a craft drawn at [position] near the camera, moving at [velocity]
     * (one wake from its hull as a whole, sternmost to foremost), and the slap of each hull's bow
     * into a wave.
     */
    private fun wakes(id: Long, design: CraftDesign, centreOfMass: Vec3, position: Vec3, rotation: Quat, velocity: Vec3, attractor: CelestialBody) {
        val fx = effects ?: return
        if (seaScene == null || animationDt <= 0.0 || position.distanceTo(cameraPosition) > WAKE_REACH) return
        val bodyRotation = attractor.rotationAt(lastRenderTime, Quat())
        var keelLine: Vec3? = null
        var aft = Double.MAX_VALUE; var fore = -Double.MAX_VALUE; var beam = 0.0
        val stern = Vec3(); val bow = Vec3()
        design.parts.forEachIndexed { index, placed ->
            val def = catalog[placed.partId] ?: return@forEachIndexed
            if (def.module<com.rm.apogee.core.part.Buoyancy>() == null) return@forEachIndexed
            val box = def.mesh as? com.rm.apogee.core.part.MeshSpec.Box ?: return@forEachIndexed
            val centre = rotation.rotate(Vec3().setTo(placed.position).subInPlace(centreOfMass)).addInPlace(position)
            val along = (rotation * placed.rotation).rotate(Vec3.unitY())
            val line = keelLine ?: along.also { keelLine = it }
            val end = box.height * 0.5 * kotlin.math.abs(along dot line)
            val middle = centre.copy().subInPlace(position) dot line
            if (middle - end < aft) { aft = middle - end; stern.setTo(centre).addScaledInPlace(line, -end) }
            if (middle + end > fore) { fore = middle + end; bow.setTo(centre).addScaledInPlace(line, end) }
            beam = maxOf(beam, box.width * 0.5)
            val partBow = centre.copy().addScaledInPlace(along, box.height * 0.5)
            // A bow driving down into a wave, so the hull slaps it.
            val scene = seaScene ?: return@forEachIndexed
            val bowFixed = bodyRotation.inverseRotate(partBow, Vec3())
            scene.sampleInto(bowFixed, lastRenderTime, slapSample)
            val keel = bowFixed.length - box.depth * 0.5 - attractor.radius - slapSample.height
            val key = id * 64 + index
            val before = bowGaps.put(key, keel)
            if (before != null && before > 0.0 && keel <= 0.0) {
                val into = (before - keel) / animationDt
                if (into > SLAP_SPEED) slap(attractor, partBow, ((into - SLAP_SPEED) / 4.0).coerceIn(0.1, 1.0), id == client.controlledVessel)
            }
        }
        if (keelLine != null) fx.wake(stern, bow, beam, velocity, attractor, bodyRotation, lastRenderTime, animationDt, (id * 31).toInt())
    }

    /** A hull slapping into a wave at [where] (world), with [energy] 0..1. */
    private fun slap(attractor: CelestialBody, where: Vec3, energy: Double, own: Boolean) {
        val listener = lastListener ?: return
        val shot = sound.shot(listener, SoundScene.Kind.SLAP, where, energy, own) ?: return
        AudioEngine.event(shot.recipe, shot.flags, (lastRenderTime * 1000).toInt() xor where.hashCode(), shot.delay, shot.params)
    }

    private var lastListener: SoundScene.Listener? = null

    /**
     * Seconds to the next window for the moon of the body the craft is on, negative while one is
     * open, or NaN with no moon to go to. It's worked out afresh now and then, not every frame,
     * because it only moves as the craft does.
     */
    private fun moonWindowIn(attractor: com.rm.apogee.core.orbit.CelestialBody, position: Vec3, bodyRotation: Quat, time: Double): Double {
        val moon = system.bodies.values.firstOrNull { it.parentId == attractor.id } ?: return Double.NaN
        if (moonWindowAt.isNaN() || moonWindowBody != attractor.id || kotlin.math.abs(time - moonWindowFrom) > MOON_WINDOW_REFRESH ||
            time > moonWindowAt + MOON_WINDOW_OPEN
        ) {
            val site = bodyRotation.inverseRotate(position, Vec3()).normalizeInPlace()
            moonWindowAt = com.rm.apogee.core.orbit.LaunchWindows.next(attractor, site, moon, time - MOON_WINDOW_OPEN) ?: Double.NaN
            moonWindowFrom = time
            moonWindowBody = attractor.id
        }
        return moonWindowAt - time
    }

    private var moonWindowAt = Double.NaN
    private var moonWindowFrom = Double.NaN
    private var moonWindowBody = ""

    /** Held between publishing a sound scene and [stop] silencing them all, so one can't undo the other. */
    private val soundLock = Any()
    @Volatile private var soundStopped = false

    /** Toward the star, inertial, as of this frame. */
    private val frameSun = Vec3(0.0, 1.0, 0.0)
    private var frameSunStrength = 1.0
    private var frameSunSize = 0.0

    private val scratchLamp = Vec3()
    private val scratchGlow = Vec3()

    /** Lit lamps that light what's around them this frame: body-fixed x, y, z and reach, four per lamp. */
    private var lamps = DoubleArray(4 * 16)
    private var lampCount = 0

    /**
     * The lamps nearest [camera] (body-fixed) whose light could reach anything in view, nearest
     * first. As many as the renderer takes, and fewer on a low tier.
     */
    /**
     * How far the camera sees under the sea [depth] m down: murkier in the stirred-up shallows of a
     * storm, and a little in the deep.
     */
    private fun underwaterFog(depth: Double): Double =
        UNDERWATER_FOG_DEEP + (UNDERWATER_FOG - UNDERWATER_FOG_DEEP) * kotlin.math.exp(-depth / UNDERWATER_FOG_FALL) -
            UNDERWATER_STIRRED * seaStorm * kotlin.math.exp(-depth / UNDERWATER_STIRRED_DEPTH)

    private fun waterOf(bodyId: String) =
        if (bodyId == "aurantia") com.rm.apogee.render.WorldView.AURANTIA_WATER else com.rm.apogee.render.WorldView.TERRA_WATER

    /**
     * The murk's colour [depth] m down in [bodyId]'s sea: its water lit by whatever daylight is
     * left there, and black in the deep.
     */
    private fun underwaterColour(bodyId: String, depth: Double, daylight: Float, lightScale: Float): FloatArray {
        val water = waterOf(bodyId)
        val tint = if (bodyId == "aurantia") AURANTIA_MURK else TERRA_MURK
        val light = 0.15f + 0.85f * daylight * lightScale
        return FloatArray(3) { tint[it] * light * kotlin.math.exp(-depth.coerceAtLeast(0.0) / water[it]).toFloat() }
    }

    private fun nearestLamps(camera: Vec3): DoubleArray {
        if (lampCount == 0 || mapMode) return com.rm.apogee.render.WorldView.NO_LAMPS
        val most = if (terrainQuality == QualityTier.LOW) LOW_TIER_LAMPS else com.rm.apogee.render.WorldView.MAX_LAMPS
        val order = (0 until lampCount).sortedBy { k ->
            val dx = lamps[4 * k] - camera.x; val dy = lamps[4 * k + 1] - camera.y; val dz = lamps[4 * k + 2] - camera.z
            kotlin.math.sqrt(dx * dx + dy * dy + dz * dz) - lamps[4 * k + 3]
        }.take(most)
        val out = DoubleArray(4 * order.size)
        for ((i, k) in order.withIndex()) lamps.copyInto(out, 4 * i, 4 * k, 4 * k + 4)
        return out
    }

    /**
     * Which way a windsock at [at] hangs, in world axes: out downwind (the wind the flown craft is
     * in, which is near enough the same across an airfield), and lower the lighter it blows, limp
     * in a calm.
     */
    private fun windsockHang(at: Vec3, bodyRotation: Quat): Vec3 {
        val up = at.copy().normalizeInPlace()
        val wind = prediction.replica?.air?.wind?.let { bodyRotation.rotate(it, Vec3()) } ?: Vec3()
        wind.addScaledInPlace(up, -(wind dot up))
        val speed = wind.length
        val out = if (speed > 0.1) wind.mulInPlace(1.0 / speed) else Vec3(0.0, 1.0, 0.0).crossInPlace(up).normalizeInPlace()
        val droop = Math.toRadians(80.0 - 75.0 * (speed / WINDSOCK_FULL_WIND).coerceIn(0.0, 1.0))
        return out.mulInPlace(kotlin.math.cos(droop)).addScaledInPlace(up, -kotlin.math.sin(droop)).normalizeInPlace()
    }

    private fun appendVessel(
        vessel: ClientVessel,
        out: MutableList<RenderItem>,
        attractor: CelestialBody,
        overridePosition: Vec3? = null,
        overrideRotation: Quat? = null,
        /** The state [overridePosition] was carried from, so both are the same sample. */
        stateOverride: VesselKinematics? = null,
        /** The pose from the prediction replica, for the craft being flown. */
        predicted: Boolean = false,
    ) {
        val emitters = frameEmitters
        val state = stateOverride ?: vessel.latest ?: return
        val design = vessel.design

        // The server sends the vessel's centre of mass, and part positions in the design are
        // relative to the design origin, so the offset between them has to be rebuilt here.
        val centreOfMass = designCentreOfMass(design)
        val position = overridePosition ?: state.position
        val rotation = overrideRotation ?: state.rotation
        wakes(vessel.id, design, centreOfMass, position, rotation, state.velocity, attractor)

        // The moving parts. For the craft being flown they come from the replica, so a surface
        // moves on the same frame the stick does. For everyone else's they come from the server's
        // pose, eased between snapshots.
        val defs = design.parts.map { catalog[it.partId] }
        val animation = animations.getOrPut(vessel.id) { VesselAnimation() }
        val n = design.parts.size
        // And only if it fits the design being drawn. Staging splits the replica straight away, and
        // until the server's new structure arrives it has fewer parts than the craft on screen.
        // Reading its pose by this design's parts ran off the end of it and crashed the game.
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
                sh.flap[i] += (t.flap[i] - sh.flap[i]) * ease
                // How hard each engine and rotor is going. Never copied across before, so rotors
                // read nothing: they never turned, blew no dust and weren't heard.
                sh.output[i] += (t.output[i] - sh.output[i]) * ease
                // The shorter way round, so a sail gybing across doesn't spin the long way.
                var swing = t.sailAngle[i] - sh.sailAngle[i]
                if (swing > Math.PI) swing -= 2.0 * Math.PI
                if (swing < -Math.PI) swing += 2.0 * Math.PI
                sh.sailAngle[i] += swing * ease
                sh.sailFill[i] += (t.sailFill[i] - sh.sailFill[i]) * ease
            }
            animation.initialised = true
        }

        // Wheels roll with the ground going by: angular velocity up x v / r, in the craft's own
        // axes. Off the ground they coast to a stop.
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
        // Joints near their limit: the parts beyond them shudder about the seam, and the seam
        // throws sparks, for everyone watching.
        val strain = strains.getOrPut(vessel.id) { com.rm.apogee.render.StrainLook() }
        val flexing = condition.any && !mapMode &&
            strain.compute(design, defs, condition.load, lastRenderTime, vessel.id.toInt())
        // What comes off the craft (sparks off a seam, a thruster's puff) leaves with it, in the
        // ground's frame.
        attractor.surfaceVelocityAt(position, scratchDrift)
        scratchDrift.mulInPlace(-1.0).addInPlace(state.velocity)
        bodyRotation.inverseRotate(scratchDrift, scratchDrift)
        val inAir = attractor.atmosphere != null && attractor.altitudeOf(position) < attractor.atmosphereHeight
        // Which way an open chute streams: away from the motion through the air.
        val chuteTrail = Vec3().setTo(state.velocity).subInPlace(attractor.surfaceVelocityAt(position, Vec3()))
        if (predicted) prediction.replica?.air?.let { air -> chuteTrail.subInPlace(bodyRotation.rotate(air.wind, Vec3())) }
        if (chuteTrail.length > 0.5) chuteTrail.normalizeInPlace().negateInPlace() else chuteTrail.setTo(position).normalizeInPlace()
        // Lamps: lit after dusk where it stands, or down in the dark of the sea, while it has the
        // power. The Cape's own always have it.
        val dark = (scratchLamp.setTo(position).normalizeInPlace() dot frameSun) < World.LAMP_DUSK ||
            (attractor.ocean != null && attractor.altitudeOf(position) < -World.LAMP_DEPTH)
        val lampsLit = dark &&
            (vessel.owner == World.WORLD_OWNER || client.nearestBase?.takeIf { it.vessel == vessel.id }?.powered != false)
        for ((index, placed) in design.parts.withIndex()) {
            val def = defs[index] ?: continue
            // From the cockpit, the cockpit itself would fill the view.
            if (index == cockpitPart && vessel.id == client.controlledVessel && !mapMode) continue

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

            if (frameLines.isNotEmpty() && frameLines.any { (it.vesselA == vessel.id && it.partA == index) || (it.vesselB == vessel.id && it.partB == index) }) {
                lineEnds[lineKey(vessel.id, index)] = scratch.copy() to rotation * placedRotation
            }

            val anim = PartAnim(
                deflection = animation.shown.deflection[index],
                steer = animation.shown.steer[index],
                compression = animation.shown.compression[index],
                deploy = animation.shown.deploy[index],
                gimbalPitch = animation.shown.gimbalPitch[index],
                gimbalYaw = animation.shown.gimbalYaw[index],
                flap = animation.shown.flap[index],
                sailAngle = animation.shown.sailAngle[index],
                sailFill = animation.shown.sailFill[index],
                jettisoned = def.module<com.rm.apogee.core.part.Fairing>() != null && index in vessel.activatedParts,
            )
            PartModels.alignWheel(def, placed.rotation, design.orientation.forward, design.orientation.up, anim)
            PartModels.alignSurface(def, placed.rotation, Vec3().setTo(placed.position).subInPlace(centreOfMass), anim)
            PartModels.alignFlap(placed.rotation, design.orientation.up, anim)
            val wheel = def.module<com.rm.apogee.core.part.Wheel>()
            if (wheel != null && anim.wheelAlign != null) {
                // The rate about this wheel's own axle, as mounted.
                val axle = placed.rotation.rotate(anim.wheelAlign!!.rotate(Vec3(1.0, 0.0, 0.0)))
                val rate = if (onGround) (scratchWheelSpin dot axle) / wheel.radius else 0.0
                animation.spin[index] += rate * animationDt
                // Dust off the tire where it meets the ground.
                if (onGround && !mapMode) {
                    val contact = Vec3().setTo(scratch).addScaledInPlace(position.normalized(), -wheel.radius)
                    effects?.wheelDust(contact, scratchGroundVelocity.length, attractor, bodyRotation, animationDt, (vessel.id * 131 + index).toInt())
                }
            } else if (def.module<com.rm.apogee.core.part.Engine>() != null) {
                // A propeller turns as fast as its engine is actually turning it, and an air
                // propeller with its engine off windmills a little in the wind of its flight.
                val engine = def.module<com.rm.apogee.core.part.Engine>()!!
                var turning = animation.shown.output.getOrElse(index) { 0.0 }
                if (engine.exhaustKind == com.rm.apogee.core.part.Exhaust.PROP && inAir) {
                    turning = maxOf(turning, (scratchGroundVelocity.length / WINDMILL_SPEED).coerceAtMost(WINDMILL_MOST))
                }
                PartModels.blades(def)?.let { blades ->
                    animation.spin[index] += turning * bladeRate(blades.radius) * animationDt
                    if (!mapMode) bladeDisc(out, vessel.id, index, blades, scratch, rotation * placedRotation, null, turning)
                }
            } else if (fresh) def.module<com.rm.apogee.core.part.Rotor>()?.let { rotor ->
                // A rotor turns as fast as it's actually turning, which is what it's lifting, and
                // close over the ground its downwash blows up dust, and it's heard.
                val speed = animation.shown.output.getOrElse(index) { 0.0 }
                val blades = PartModels.blades(def)
                if (speed > 0.0 && blades != null) {
                    animation.spin[index] += speed * bladeRate(blades.radius) * animationDt
                    if (!mapMode) {
                        // The flown craft's main rotor tilts its disc the way it's pulling.
                        val tilt = if (predicted && rotor.cyclic > 0.0) {
                            prediction.replica?.rotorTilt?.takeIf { index * 3 + 2 < it.size && (it[index * 3] != 0.0 || it[index * 3 + 1] != 0.0 || it[index * 3 + 2] != 0.0) }
                                ?.let { t -> rotation.rotate(Vec3(t[index * 3], t[index * 3 + 1], t[index * 3 + 2])).normalizeInPlace() }
                        } else null
                        bladeDisc(out, vessel.id, index, blades, scratch, rotation * placedRotation, tilt, speed)
                    }
                }
                if (speed > 0.0) {
                    if (!mapMode && !rotor.tail) {
                        val down = (rotation * placedRotation).rotate(rotor.liftDirection).normalizeInPlace().negateInPlace()
                        emitters.add(
                            EngineEmitter(
                                nozzle = scratch.copy(), out = down, radius = rotor.diameter * 0.5, exitRadius = rotor.diameter * 0.5,
                                kind = com.rm.apogee.core.part.Exhaust.PROP, throttle = speed, velocity = state.velocity.copy(),
                                seed = (vessel.id * 31 + index).toInt(),
                            ),
                        )
                    }
                    soundCraft(vessel.id, position, state.velocity, attractor).rotor(speed, rotor.diameter)
                }
            }
            // A thruster block firing: puffs come out of it, opposite its push, and there's a chuff
            // in the sound. The push is in the craft's axes, from the replica for the craft being
            // flown and from the server for the rest.
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
                // The replica's parts are only this design's while their counts agree. Staging
                // splits it straight away, before the server's new structure arrives, and indexing
                // it by this design's parts then ran off the end. What it's actually putting out
                // (nothing once its tank is dry, however far the throttle is open) comes from the
                // replica for the craft being flown, and the server's pose for the rest.
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
                    // The flown craft's chute: widen the view once to take the canopy in.
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

            // A sail that's set (the throttle's the sheet) with the wind gone out of it flogs, and
            // a big one louder than a small one.
            def.module<com.rm.apogee.core.part.Sail>()?.let { sail ->
                if (fresh && !mapMode && state.throttle > SAIL_SET) {
                    // How full it is for how far the sheet lets it out, so a reefed sail drawing well
                    // is quiet.
                    val empty = 1.0 - (animation.shown.sailFill.getOrElse(index) { 1.0 } / state.throttle).coerceIn(0.0, 1.0)
                    val flog = empty * (sail.area / SAIL_BIG).coerceAtMost(1.0)
                    soundCraft(vessel.id, position, state.velocity, attractor).let { it.luff = maxOf(it.luff, flog) }
                }
            }

            val partRotation = rotation * placedRotation
            val body = com.rm.apogee.render.PartModels.bodyColour(placed.partId)
            leaves.clear()
            PartModels.expand(def, caps[index], anim, leaves)
            val health = if (condition.any) condition.health[index] else 1f
            val heat = if (condition.any) condition.temperature[index] else 0f
            val dent = if (condition.any) ConditionLook.dent(condition.crumple, index * 3) else null
            // Badly hurt, it burns, in air. In vacuum there's nothing to burn in.
            if (health < BURNING_HEALTH && !mapMode && attractor.atmosphere != null &&
                attractor.altitudeOf(scratch) < attractor.atmosphereHeight * 0.6
            ) {
                attractor.toBodyFixed(scratch, bodyRotation, scratchBurn)
                effects?.burn(scratchBurn, 2.0 * def.jointRadius, animationDt, (vessel.id * 53 + index).toInt())
                soundCraft(vessel.id, position, state.velocity, attractor).burning++
            }
            // A lamp's lights, lit at night while it has power.
            val lamp = lampsLit && def.hasModule<com.rm.apogee.core.part.Lamp>()
            // One that lights the ground around it, from where its lights are.
            val lampModule = if (lamp) def.module<com.rm.apogee.core.part.Lamp>() else null
            val reach = lampModule?.reach ?: 0.0
            var glows = 0
            scratchGlow.setTo(0.0, 0.0, 0.0)
            val sock = placed.partId == WINDSOCK_PART
            for ((piece, leaf) in leaves.withIndex()) {
                val lit = lamp && leaf.tint == com.rm.apogee.core.part.Tint.LIGHT
                val base = when {
                    lit -> LAMP_COLOUR
                    // Someone in a suit wears their player's stripe and their own visor.
                    vessel.stripe >= 0 && leaf.tint == com.rm.apogee.core.part.Tint.ACCENT -> com.rm.apogee.render.SuitColours.stripe(vessel.stripe)
                    vessel.visor >= 0 && leaf.tint == com.rm.apogee.core.part.Tint.GLASS -> com.rm.apogee.render.SuitColours.visor(vessel.visor)
                    else -> PartModels.colour(leaf.tint, body)
                }
                val leafPosition = if (dent == null) leaf.position
                    else Vec3(leaf.position.x * dent.x, leaf.position.y * dent.y, leaf.position.z * dent.z)
                var leafWorld = partRotation.rotate(leafPosition).addInPlace(scratch)
                var leafRotation = partRotation * leaf.rotation
                if (sock && piece == WINDSOCK_PIECE) {
                    // Blown out downwind from the top of the mast, hanging lower the lighter the
                    // wind.
                    val pivot = partRotation.rotate(WINDSOCK_PIVOT.copy()).addInPlace(scratch)
                    val axis = leafRotation.rotate(Vec3.unitY(), Vec3())
                    val hang = windsockHang(position, bodyRotation)
                    leafRotation = com.rm.apogee.core.math.quatFromTo(axis, hang) * leafRotation
                    leafWorld = pivot.addScaledInPlace(hang, WINDSOCK_REACH)
                }
                if (lit && reach > 0.0) { scratchGlow.addInPlace(leafWorld); glows++ }
                out.add(
                    RenderItem(
                        caps = leaf.caps,
                        shape = leaf.shape,
                        position = leafWorld,
                        rotation = leafRotation,
                        color = if (condition.any && !lit) ConditionLook.colour(base, health, heat) else base,
                        // Lit, a lamp's glare makes it look bigger than it is.
                        scale = if (lit) LAMP_GLARE else dent?.let { ConditionLook.inLeaf(it, leaf.rotation) },
                        ambient = if (lit) 1.2f else if (condition.any) ConditionLook.ambient(0.28f, heat) else 0.28f,
                        wrap = false,
                        key = RenderItem.partKey(vessel.id, partIdentity(placed), piece),
                        // Runway paint lies on the paving, over it.
                        decal = if (placed.partId.startsWith(PAINT_PART)) PAINT_DECAL else 0,
                    )
                )
            }
            if (glows > 0) {
                scratchGlow.mulInPlace(1.0 / glows)
                // Aimed, so the light stands out in front, over the middle of the pool it throws.
                val aim = lampModule?.aim ?: 0.0
                if (aim > 0.0) {
                    val front = partRotation.rotate(Vec3.unitZ())
                    val up = scratch.normalized()
                    front.addScaledInPlace(up, -(front dot up))
                    if (front.length > 1e-3) scratchGlow.addScaledInPlace(front.normalizeInPlace(), aim)
                }
                attractor.toBodyFixed(scratchGlow, bodyRotation, scratchGlow)
                if (4 * lampCount + 4 > lamps.size) lamps = lamps.copyOf(lamps.size * 2)
                lamps[4 * lampCount] = scratchGlow.x
                lamps[4 * lampCount + 1] = scratchGlow.y
                lamps[4 * lampCount + 2] = scratchGlow.z
                lamps[4 * lampCount + 3] = reach
                lampCount++
            }
        }

        // The air it pushes through, made visible: vapour and re-entry glow.
        if (!mapMode) {
            val throughAir = Vec3().setTo(state.velocity).subInPlace(attractor.surfaceVelocityAt(position, Vec3()))
            if (predicted) prediction.replica?.air?.let { air -> throughAir.subInPlace(bodyRotation.rotate(air.wind, Vec3())) }
            // Where the craft meets the air first, and how thick it is there. The vapour collar and
            // the shock sit on the nose, not around the whole craft. Before, it matched neither the
            // nose nor the drag, which I spotted. The tip is the leading part's own end, on its own
            // axis. Found along the line of flight through the middle, it sat metres to the side
            // whenever the craft wasn't flying dead straight.
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
     * Which part this is, in a way staging doesn't change: its kind and where it sits in the
     * design. Its index moves when the parts before it go, and a part keyed by index got eased from
     * another part's place.
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
     * The mass-weighted centre of the design, using dry masses.
     *
     * It's an approximation. The server knows the true centre including propellant, and doesn't
     * send it at the moment. The error shifts a craft by tens of centimetres along its axis, which
     * you can't see at flight camera distances. It's a real gap, though, and the fix is a field on
     * the kinematics message, not better guessing here.
     */
    /**
     * How far the craft's lowest point sits below its centre, in metres.
     *
     * It's negative, and measured along [up] in world axes so it follows the craft as it tips. The
     * height readout is taken from here instead of from the centre of mass, because a player reads
     * "AGL" as the gap between their craft and the ground, and a thirteen-metre rocket parked on
     * the pad showed seven metres. That looks exactly like a bug in the terrain, even though the
     * craft is sitting on it.
     *
     * It's measured against the same centre the renderer places parts around, so the number and the
     * picture can't disagree.
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
    private val scratchPosition = Vec3()

    /**
     * Whether another craft is close enough and still enough to weld to.
     *
     * It's answered from the craft the client already has, so the button shows up exactly when
     * pressing it would do something. The server checks again before acting. This decides what to
     * draw, not what's allowed.
     */
    @Volatile
    var joinable: Boolean = false
        private set

    private var joinFocus = -1L
    private var joinParts = -1

    /** When each craft first showed up already beside the flown one (just parted from it), or 0. */
    private val firstSeenNear = HashMap<Long, Long>()

    private fun neighbourInWeldingRange(
        focus: ClientVessel,
        state: com.rm.apogee.core.world.VesselKinematics,
    ): Boolean {
        val reach = designReach(focus.design)
        val now = System.nanoTime()
        // A different craft in hand (a stage dropped, a ring undocked, a switch) starts afresh.
        // Whatever is beside it now was just parted from it, or is where it was left.
        if (joinFocus != focus.id || joinParts != focus.design.parts.size) {
            joinFocus = focus.id; joinParts = focus.design.parts.size
            firstSeenNear.clear()
        }
        val hitched = client.latestSnapshot?.hitches.orEmpty()
        for (other in client.vessels) {
            if (other.id == focus.id) continue
            // The Cape's own buildings are nobody's to weld to.
            if (other.owner == World.WORLD_OWNER) continue
            // Brought to the same moment as ours, the same as for docking. A snapshot apart at
            // orbital speed, a stage pressed against us read 43 m off one frame and 10 m the next.
            val seen = other.observed ?: continue
            val theirs = seen.kinematics
            val lag = (focus.observed?.time ?: seen.time) - seen.time
            val theirPosition = scratchPosition.setTo(theirs.position).addScaledInPlace(theirs.velocity, lag)
            // Towed or towing, so it's joined already, by the hitch.
            if (hitched.any { (it.vesselA == focus.id && it.vesselB == other.id) || (it.vesselB == focus.id && it.vesselA == other.id) }) continue
            // A stage that was just let go of isn't something to join back onto. Drifting off at a
            // metre a second, it met the rule, and the button blinked on and off as it went, which
            // I noticed.
            val born = firstSeenNear.getOrPut(other.id) {
                if (theirPosition.distanceTo(state.position) < reach + designReach(other.design) + 20.0) now else 0L
            }
            if (born != 0L && now - born < JUST_PARTED_NANOS) continue
            // Nor a stage let go of that's still burning, pushing the flown one for as long as its
            // tanks last. Joining it back isn't on offer.
            if (born != 0L && theirs.throttle > 0.0) continue
            scratchNeighbour.setTo(state.position).subInPlace(theirPosition)
            if (scratchNeighbour.length > reach + designReach(other.design)) continue
            scratchNeighbour.setTo(state.velocity).subInPlace(theirs.velocity)
            if (scratchNeighbour.length > World.JOIN_MAX_CLOSING_SPEED) continue
            return true
        }
        return false
    }

    /** Roughly how far this design reaches from its centre, in metres. */
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



    /**
     * How fast blades [radius] metres long are drawn turning at full speed, in radians a second.
     * Slower than the real thing, which on a screen strobes into blades standing still or turning
     * backwards. Big ones turn slower, the way they look to.
     */
    private fun bladeRate(radius: Double): Double = (BLADE_RATE_SCALE / (radius + 1.0)).coerceIn(BLADE_RATE_LEAST, BLADE_RATE_MOST)

    /**
     * The blur of blades turning fast, as a see-through disc over them, coming in as they pass a
     * third of full speed [speed]. It faces along [tilt] where given (a main rotor's lift, tilted by
     * the stick), or along the blades' own axis.
     */
    private fun bladeDisc(
        out: MutableList<RenderItem>, vesselId: Long, index: Int, blades: PartModels.Blades,
        partPosition: Vec3, partRotation: Quat, tilt: Vec3?, speed: Double,
    ) {
        val alpha = DISC_ALPHA * smooth01((speed - DISC_FROM) / (1.0 - DISC_FROM))
        if (alpha < 0.01) return
        val centre = partRotation.rotate(blades.centre).addInPlace(partPosition)
        val axis = tilt ?: partRotation.rotate(blades.axis).normalizeInPlace()
        out.add(
            RenderItem(
                shape = BLADE_DISC, position = centre,
                rotation = com.rm.apogee.core.math.quatFromTo(Vec3.unitY(), axis),
                color = floatArrayOf(0.34f, 0.35f, 0.37f, alpha.toFloat()),
                caps = 0, scale = Vec3(blades.radius, 1.0, blades.radius), ambient = 0.5f,
                key = RenderItem.effectKey(-1_000L - vesselId * 1_024L - index, 5),
            ),
        )
    }

    private fun smooth01(x: Double): Double { val t = x.coerceIn(0.0, 1.0); return t * t * (3 - 2 * t) }

    companion object {
        /** A lamp's light, lit: warm white, and drawn this much larger for its glare. */
        val LAMP_COLOUR = floatArrayOf(1.0f, 0.9f, 0.62f, 1.0f)
        val LAMP_GLARE = Vec3(2.0, 2.0, 2.0)

        /**
         * Path points kept for touching the map, and how near a finger has to be to take hold, in
         * px.
         */
        private const val MAP_SAMPLES = 256
        private const val MAP_GRAB_PIXELS = 60f

        /** Target ids at and below this are bodies, by their place in [targetBodies]. */
        const val BODY_TARGET = -100L

        /**
         * Edited burns get sent at most this often, and followed over the server's for this long
         * afterwards.
         */
        private const val BURN_SEND_NANOS = 200_000_000L
        private const val BURN_EDIT_HOLD_NANOS = 3_000_000_000L
        /**
         * Warping to a burn stops this long before it starts, in seconds, to give time to turn onto
         * it.
         */
        private const val BURN_WARP_LEAD = 45.0

        /** Coming down means falling faster than this, in m/s. */
        private const val LANDING_FALLING = 1.0

        /**
         * The approach lights: metres out from the runway's edge to the first, between them, and
         * up off the ground; how much bigger they're drawn per metre away; their look; draw key.
         */
        private const val PAPI_CLEAR = 15.0
        private const val PAPI_SPACING = 9.0
        private const val PAPI_HEIGHT = 0.6
        private const val PAPI_GLARE = 0.0012
        private val PAPI_LIGHT = com.rm.apogee.core.part.MeshSpec.Sphere(0.5)
        private val PAPI_WHITE = floatArrayOf(1.0f, 0.97f, 0.9f, 1f)
        private val PAPI_RED = floatArrayOf(1.0f, 0.12f, 0.08f, 1f)
        private const val PAPI_KEY = -78L

        /**
         * The map's current arrows: their colour, how far off the datum, the longest as a share of
         * the gap to the next, and the speed, in m/s, that's drawn longest and brightest.
         */
        private val CURRENT_COLOR = floatArrayOf(0.35f, 0.88f, 0.82f, 1f)
        private const val CURRENT_LIFT = 1.0005
        private const val CURRENT_ARROW = 0.85
        private const val CURRENT_FAST = 0.8

        /** Throttle over which a sail counts as set, and the sail area, in m², that flogs loudest. */
        private const val SAIL_SET = 0.02
        private const val SAIL_BIG = 20.0

        /** A winch line: how many straight pieces, how thick, its colour, and its draw keys. */
        private const val LINE_PIECES = 8
        private val WINCH_LINE = com.rm.apogee.core.part.MeshSpec.Cylinder(radius = 0.015, height = 1.0)
        private val WINCH_LINE_COLOUR = floatArrayOf(0.12f, 0.12f, 0.13f, 1f)
        private const val LINE_KEY = -79_000L

        /** The highest over the runway, in metres, the approach cue shows. */
        private const val APPROACH_HIGHEST = 3_000.0

        /**
         * The weakest current shown, in m/s, and the most above the datum, in metres, a craft can
         * be and still be floating in it.
         */
        private const val CURRENT_SHOWN = 0.3
        private const val CURRENT_ABOVE = 5.0


        /**
         * The paving is drawn from this near, in metres, and the craft id its pieces are keyed
         * under.
         */
        private const val PAVING_SEEN = 40_000.0
        private const val PAVING_KEY = -77L

        /** Runway markings, drawn over the paving they're painted on. */
        private const val PAINT_PART = "struct-paint"
        private const val PAINT_DECAL = 20

        /** Where the Cape is heard: the middle of the pads, and the head of the jetty. */
        private val CAPE_PADS = com.rm.apogee.core.orbit.SolarSystem.capeDirection(0.0, 0.0)
        private val CAPE_JETTY = com.rm.apogee.core.orbit.SolarSystem.capeDirection(2_660.0, 350.0)

        /**
         * Heard fully this near a Cape place, in metres, and not at all past [CAPE_HEARD_UNTIL].
         */
        private const val CAPE_HEARD_FULL = 120.0
        private const val CAPE_HEARD_UNTIL = 450.0

        /**
         * Lamps lighting the ground at once on a low tier. Each one costs a little more per pixel.
         */
        const val LOW_TIER_LAMPS = 4

        /** The windsock: which part, which of its pieces is the sock, where it hangs from and how far out its middle is. */
        const val WINDSOCK_PART = "struct-windsock"
        const val WINDSOCK_PIECE = 1
        val WINDSOCK_PIVOT = Vec3(0.0, 3.2, 0.1)
        const val WINDSOCK_REACH = 1.5

        /** Wind that blows a windsock straight out, in m/s. */
        const val WINDSOCK_FULL_WIND = 12.0

        /** Below this share of its health a part is on fire. */
        private const val BURNING_HEALTH = 0.35f

        /** How long a correction between snapshots takes to ease out, in seconds. */
        private const val SMOOTH_SECONDS = 0.1

        /** A disagreement bigger than this is a real jump, not jitter, in metres. */
        private const val SMOOTH_LIMIT = 20.0

        /** Real seconds between snapshots. The server sends them at 20 Hz. */
        private const val SNAPSHOT_SPACING = 0.05

        /** How much of the gap to the snapshots' time the warp clock closes each frame. */
        private const val WARP_CLOCK_EASE = 0.1

        /** Real seconds out, at the warp rate, past which the warp clock just jumps. */
        private const val WARP_CLOCK_SNAP = 0.5

        /** The least time between two warning tones. */
        private const val CAUTION_SPACING_NANOS = 4_000_000_000L

        /**
         * The slide is sent again when it has moved this much on an axis-length scale, at most this
         * often.
         */
        /**
         * Holding hands-off drift while lining up: ignored below this, in m/s, the command per m/s,
         * and the most it asks for.
         */
        private const val DRIFT_DEADBAND = 0.02
        private const val DRIFT_GAIN = 0.4
        private const val DRIFT_MOST = 0.15

        /**
         * Below this height, in metres, the stick slides along the ground. Above it, by the
         * camera's own axes.
         */
        private const val SLIDE_GROUNDED_BELOW = 500.0
        private const val SLIDE_RESEND = 0.01
        private const val SLIDE_RESEND_NANOS = 100_000_000L

        /** How long, in ns, a craft just parted from the flown one isn't offered for joining. */
        private const val JUST_PARTED_NANOS = 10_000_000_000L

        /** How fast a bow has to drive into the water, in m/s, to be heard slapping it. */
        private const val SLAP_SPEED = 1.2

        /** How far from the camera, in metres, a boat's wake is drawn. */
        private const val WAKE_REACH = 600.0

        /**
         * How far the camera sees under the water, in metres: near the top, and in the deep, and
         * how deep the change takes.
         */
        private const val UNDERWATER_FOG = 55.0

        /**
         * Deeper than this, in metres, a craft is under the sea instead of afloat on it, in the
         * craft lists.
         */
        const val UNDER_SEA = 3.0

        /**
         * When named places on the map are closer together than this on screen, in px, only the
         * first shows.
         */
        private const val MAP_PLACE_GAP = 60f
        private const val UNDERWATER_FOG_DEEP = 45.0
        private const val UNDERWATER_FOG_FALL = 100.0

        /** Taken off it by a storm stirring the water, in metres, over about this depth. */
        private const val UNDERWATER_STIRRED = 30.0
        private const val UNDERWATER_STIRRED_DEPTH = 20.0

        /** The water's own colour, lit by a full day: Terra's blue-green, and Aurantia's brown. */
        private val TERRA_MURK = floatArrayOf(0.05f, 0.24f, 0.30f)
        private val AURANTIA_MURK = floatArrayOf(0.22f, 0.14f, 0.07f)

        /** How often a held stick is read against the screen again, at most. */
        private const val ATTITUDE_REFRESH_NANOS = 60_000_000L

        /**
         * How far from where a craft was lost its pieces are looked for, in metres, and how quickly
         * the look follows them.
         */
        private const val WRECK_PIECE_REACH = 150.0
        private const val WRECK_EASE = 0.08
        /** The size the camera frames a wreck as, in metres. */
        private const val WRECK_VIEW = 25.0

        /**
         * Craft within this distance of touching ours, in metres, are in its replica too, at most
         * this many.
         */
        private const val NEIGHBOUR_MARGIN = 30.0
        private const val MAX_NEIGHBOURS = 4

        /**
         * How far, in metres, the dock readout looks for a partner's ring when no target is set.
         */
        private const val DOCK_READOUT_RANGE = 60.0

        /** A leg is at the end of its travel, locked or stowed, this near it. */
        private const val LEG_END = 0.995

        /**
         * Surf: checked this often, full at the water's edge up to [SHORE_FULL_BELOW] m, and gone
         * by [SHORE_SILENT_ABOVE].
         */
        private const val SHORE_LOOK_NANOS = 250_000_000L
        private const val SHORE_FULL_BELOW = 25.0
        private const val SHORE_SILENT_ABOVE = 250.0
        /** Rings checked for the water's edge, in metres, and how loud each one makes it. */
        private val SHORE_RINGS = listOf(60.0 to 1.0, 180.0 to 0.7, 400.0 to 0.35)

        /** How far above the ground the camera is kept, in metres. */
        private const val CAMERA_CLEARANCE = 2.0

        /**
         * A launch window is called open this long either side of its moment, in seconds. Two
         * minutes off, a launch due east is still within a degree of the moon's plane. It's worked
         * out again at least this often, in seconds.
         */
        const val MOON_WINDOW_OPEN = 120.0
        private const val MOON_WINDOW_REFRESH = 30.0

        /** How long the place a craft was lost keeps burning, in seconds. */
        private const val WRECK_BURN_SECONDS = 60.0

        /** The most craft the target picker lists. */
        private const val MAX_TARGET_CHOICES = 12

        /** About 60 Hz. The server streams slower, and the renderer interpolates. */
        private const val PRESENT_INTERVAL_MILLIS = 16L

        /** Stage cards at 5 Hz. They're gauges, read at a glance, not animated. */
        private const val STAGE_CARD_INTERVAL_NANOS = 200_000_000L

        /**
         * The furthest another craft is carried past its last snapshot. It's a few snapshot
         * intervals: enough to cover a late one, but not enough to fly a craft on through a stalled
         * connection.
         */
        private const val MAX_EXTRAPOLATION_SECONDS = 0.25

        /** The most a snapshot is taken to be behind the present, in seconds. */
        private const val MAX_SNAPSHOT_AGE = 0.5

        /** How quickly another craft's moving parts catch up with a new snapshot. */
        private const val POSE_EASING_SECONDS = 0.06

        /** Wheels turn with the ground below this height, and above it they coast. */
        private const val WHEEL_SPIN_HEIGHT = 1.5

        /** A propeller's turn at full throttle, in radians per second. */
        /** How far above its part's middle the cockpit camera sits, in metres. */
        private const val SEAT_RISE = 0.35

        /** Blades turn at [BLADE_RATE_SCALE] / (radius + 1) rad/s at full speed, within these. */
        private const val BLADE_RATE_SCALE = 60.0
        private const val BLADE_RATE_LEAST = 10.0
        private const val BLADE_RATE_MOST = 24.0

        /** The blades' blur: how solid at full speed, and the share of full speed it starts at. */
        private const val DISC_ALPHA = 0.3
        private const val DISC_FROM = 0.3

        /** A thin lens, drawn as the blur of turning blades, a unit across each way. */
        private val BLADE_DISC = com.rm.apogee.core.part.ModelSpec.Lathe(
            listOf(listOf(0.0, 0.015), listOf(1.0, 0.0), listOf(0.0, -0.015)),
            segments = 28,
        )

        /** Windmilling: an idle air propeller turns at airspeed / this, up to [WINDMILL_MOST] of full. */
        private const val WINDMILL_SPEED = 120.0
        private const val WINDMILL_MOST = 0.3


        /**
         * The direction to the star, in the planet's frame.
         *
         * It's fixed, and on purpose not straight down any axis. A sun exactly overhead the launch
         * site makes the terminator invisible and the planet look flat. It gets replaced by real
         * system geometry when the map view needs the star's true position.
         */

        /** The surface normal at the launch complex (latitude 0, longitude 0). */
        private val HOME_DIRECTION = com.rm.apogee.core.orbit.SolarSystem.surfaceDirection(
            com.rm.apogee.core.orbit.SolarSystem.PAD_LATITUDE, com.rm.apogee.core.orbit.SolarSystem.PAD_LONGITUDE,
        )

        /** Marker size as a fraction of the framed orbit. */
        private const val MARKER_FRACTION = 0.022

        private val ORBIT_COLOR = floatArrayOf(0.70f, 0.62f, 1.0f, 1f)

        /** A craft's course over the ground on the map, and its minute marks. */
        private val COURSE_COLOR = floatArrayOf(0.55f, 0.90f, 0.95f, 1f)
        private const val TICK_FRACTION = 0.012

        /** How wide the course is drawn, as a share of what the map takes in. About three pixels. */
        private const val COURSE_WIDTH = 0.0015

        /** Where a falling craft comes down. */
        private val IMPACT_COLOR = floatArrayOf(1.0f, 0.45f, 0.35f, 1f)

        /** How far ahead the course over the ground goes, in seconds, and at most, in radians of the world. */
        private const val COURSE_SECONDS = 600.0
        private const val COURSE_MOST = 1.0
        private const val COURSE_STEPS = 64
        private const val COURSE_TICKS_MOST = 10.0

        /** Slower than this over the ground, in m/s, there's no course to draw. */
        private const val COURSE_SLOWEST = 0.5

        /** Closer than this over the ground or the sea, in metres, it's on it, not falling. */
        private const val GROUNDED = 5.0
        private const val AFLOAT = 2.0

        /** In the air, this share of orbital speed and up, it's falling, not flying. */
        private const val NEAR_ORBITAL = 0.5

        /** How long ahead an escaping path is looked along for the ground, in seconds, and in how many steps. */
        private const val FALL_LONGEST = 3_600.0
        private const val FALL_STEPS = 240

        /** How much the map takes in, in metres, opened on something that isn't falling. */
        private const val MAP_LOCAL = 30_000.0
        private val APOAPSIS_COLOR = floatArrayOf(0.49f, 1.0f, 0.70f, 1f)
        private val PERIAPSIS_COLOR = floatArrayOf(1.0f, 0.83f, 0.50f, 1f)
        private val CRAFT_COLOR = floatArrayOf(1.0f, 1.0f, 1.0f, 1f)
        private val BODY_MARKER_COLOR = floatArrayOf(0.62f, 0.66f, 0.74f, 1f)

        /** Worlds within this many of the map's reach get marked and named. */
        private const val MAP_LABEL_REACH = 1.6
        /** A founded base on the map. */
        private val BASE_COLOR = floatArrayOf(0.55f, 0.85f, 1.0f, 1f)
        /** Ground stations, and a probe's link home through its relays. */
        private val STATION_COLOR = floatArrayOf(1.0f, 0.75f, 0.3f, 1f)
        /** A planted flag on the map. */
        private val FLAG_COLOR = floatArrayOf(1.0f, 0.4f, 0.55f, 1f)
        private val SIGNAL_COLOR = floatArrayOf(0.45f, 1.0f, 0.55f, 1f)
        /** A surveyed body's ore and water on the map, and how big each dot is, as a share of its radius. */
        private val ORE_COLOR = floatArrayOf(1.0f, 0.62f, 0.25f, 1f)
        private val WATER_COLOR = floatArrayOf(0.35f, 0.8f, 1.0f, 1f)
        private const val DOT_FRACTION = 0.012
        /**
         * A leg around a moon, the path after a planned burn, the burn, a moon's own path, and its
         * reach.
         */
        private val MOON_PATH_COLOR = floatArrayOf(0.85f, 0.85f, 0.88f, 1f)
        private val BURN_PATH_COLOR = floatArrayOf(1.0f, 0.62f, 0.25f, 1f)
        private val BURN_COLOR = floatArrayOf(0.31f, 0.64f, 1.0f, 1f)
        private val MOON_ORBIT_COLOR = floatArrayOf(0.45f, 0.45f, 0.5f, 1f)
        private val REACH_COLOR = floatArrayOf(0.3f, 0.3f, 0.36f, 1f)
        private const val PLAN_POINTS = 160

        /** Other worlds, seen from far off. See [appendBodies]. */
        private val BODY_COLOURS = mapOf(
            "terra" to floatArrayOf(0.24f, 0.44f, 0.70f, 1f),
            "luna" to floatArrayOf(0.56f, 0.56f, 0.57f, 1f),
            "celer" to floatArrayOf(0.45f, 0.43f, 0.41f, 1f),
            // Under its cloud, a plain cream ball.
            "caligo" to floatArrayOf(0.90f, 0.84f, 0.62f, 1f),
            "rubra" to floatArrayOf(0.70f, 0.38f, 0.22f, 1f),
            "timor" to floatArrayOf(0.35f, 0.32f, 0.30f, 1f),
            "pavor" to floatArrayOf(0.38f, 0.35f, 0.32f, 1f),
            "fornax" to floatArrayOf(0.85f, 0.75f, 0.35f, 1f),
            "crusta" to floatArrayOf(0.85f, 0.80f, 0.72f, 1f),
            "maxima" to floatArrayOf(0.55f, 0.52f, 0.48f, 1f),
            "cicatrix" to floatArrayOf(0.36f, 0.34f, 0.32f, 1f),
            "aurantia" to floatArrayOf(0.80f, 0.55f, 0.22f, 1f),
            "fons" to floatArrayOf(0.97f, 0.98f, 1.0f, 1f),
            "aversa" to floatArrayOf(0.72f, 0.68f, 0.66f, 1f),
            "ultima" to floatArrayOf(0.80f, 0.70f, 0.58f, 1f),
            "portitor" to floatArrayOf(0.50f, 0.50f, 0.50f, 1f),
        )
        private val BODY_COLOUR = floatArrayOf(0.6f, 0.55f, 0.5f, 1f)
        private const val BODY_KEY = -78L

        /** The port a host listens on unless it's taken. */
        const val DEFAULT_PORT = 45_678

        /**
         * Starts a game others can join over the local network.
         *
         * It's the same as [hostLocal] except that the server also listens on a socket and
         * announces itself, which is the whole point of building single player as a one-player
         * server in the first place. Nothing in the gameplay path knows the difference.
         */
        fun hostLan(
            frameBus: FrameBus,
            perfHints: PerfHints?,
            playerName: String,
            clientId: String,
            /** The stripe this player's crew wear, or -1 to leave it to the server. */
            stripe: Int = -1,
            serverName: String,
            design: CraftDesign? = null,
            catalog: PartCatalog = StockParts.catalog,
            scope: CoroutineScope,
            weather: com.rm.apogee.core.weather.WeatherIntensity? = null,
            clouds: com.rm.apogee.core.weather.CloudCover? = null,
            /** The world the others join: the host's own, career or sandbox. */
            world: World = World.default(catalog),
        ): GameSession {
            val session = hostLocal(
                frameBus, perfHints, playerName, clientId, stripe, design, catalog, scope,
                // The name has to reach the server config, not just the beacon. It's what the
                // welcome message reports, so a joining player sees the name they picked in the
                // browser.
                serverName = serverName,
                weather = weather,
                clouds = clouds,
                world = world,
            )
            session.hostedServer?.let { session.openToLan(it, scope, serverName) }
            return session
        }

        /**
         * Joins a game hosted somewhere else.
         *
         * It returns a failure if the socket won't open, because a host that has gone away between
         * being found and being tapped is completely normal.
         */
        suspend fun join(
            frameBus: FrameBus,
            perfHints: PerfHints?,
            playerName: String,
            clientId: String,
            /** The stripe this player's crew wear, or -1 to leave it to the server. */
            stripe: Int = -1,
            host: String,
            port: Int,
            catalog: PartCatalog = StockParts.catalog,
        ): Result<GameSession> = connectTo(host, port).map { transport ->
            GameSession(
                frameBus = frameBus,
                perfHints = perfHints,
                catalog = catalog,
                hostedServer = null,
                client = GameClient(transport, playerName, catalog.contentHash, clientId, stripe = stripe),
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
            /** The stripe this player's crew wear, or -1 to leave it to the server. */
            stripe: Int = -1,
            /** What to fly. Null falls back to the stock rocket. */
            design: CraftDesign? = null,
            catalog: PartCatalog = StockParts.catalog,
            scope: CoroutineScope,
            serverName: String = "Local Game",
            /**
             * The world to play in.
             *
             * It's passed in instead of made here, because single player isn't a series of
             * disconnected sandboxes. A craft landed on a hillside has to still be there when the
             * player comes back with the next module. Building one here is what made every launch a
             * fresh universe, and made the whole business of landing modules and welding them
             * together impossible to reach from inside the game.
             */
            world: World = World.default(catalog),
            /** The launch site for [design]. Null lets the design choose. */
            siteId: String? = null,
            /** Clear away the craft flown last time and start on a fresh one. */
            freshFlight: Boolean = false,
            /** Fly this craft of the player's, chosen from Resume Flight. */
            resumeVessel: Long? = null,
            /** How lively the weather is, from the player's setting. */
            weather: com.rm.apogee.core.weather.WeatherIntensity? = null,
            /** How cloudy it is, from the same place. */
            clouds: com.rm.apogee.core.weather.CloudCover? = null,
            /** When in the day to launch. The clock moves on to it first. Not for Resume Flight. */
            launchTime: com.rm.apogee.core.world.LaunchTime = com.rm.apogee.core.world.LaunchTime.NOW,
        ): GameSession {
            // A launch at a chosen time of day: the clock moves on to the next one at the site
            // before anyone joins. It's the player's own world, so nobody else's day gets changed
            // under them.
            if (launchTime != com.rm.apogee.core.world.LaunchTime.NOW && resumeVessel == null) {
                val site = siteId?.let { id -> World.launchSites.firstOrNull { it.id == id } }
                    ?: World.launchSiteFor(design ?: StockCraft.starterRocket(catalog), catalog)
                val body = world.system.body(site.bodyId)
                val up = Vec3(
                    kotlin.math.cos(site.latitude) * kotlin.math.cos(site.longitude),
                    kotlin.math.sin(site.latitude),
                    kotlin.math.cos(site.latitude) * kotlin.math.sin(site.longitude),
                )
                world.skipTo(launchTime.nextAt(world.system, body, up, world.time))
            }
            val server = GameServer(
                world = world,
                config = ServerConfig(
                    name = serverName,
                    starterCraft = { StockCraft.starterRocket(it) },
                    // A design of their own is coming, so don't hand them a stock rocket as well to
                    // leave standing on the pad.
                    assignCraftOnJoin = design == null,
                    freshFlight = freshFlight && design == null && resumeVessel == null,
                    resumeVessel = resumeVessel,
                    weatherIntensity = weather,
                    cloudCover = clouds,
                    // The phone's own game: pause and warp, while nobody else is in it.
                    allowWarp = true,
                ),
            )
            val link = LoopbackTransportPair()
            server.accept(link.serverSide, scope)

            val client = GameClient(link.clientSide, playerName, catalog.contentHash, clientId, stripe = stripe)
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
    /** Above the datum, which is what orbital mechanics and the atmosphere use. */
    val altitude: Double,
    /**
     * Above the ground directly below.
     *
     * It's quite different from [altitude] over a mountain range, and it's the one a pilot wants
     * when landing.
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
    /** Dynamic pressure, in Pa. The number that decides whether a craft survives the climb. */
    val dynamicPressure: Double,
    /** The craft's orientation, for the navball. */
    val rotation: Quat,
    /** The local vertical, for the navball. */
    val up: Vec3,
    /** The direction of travel relative to the surface, or null when it isn't moving. */
    val prograde: Vec3?,
    /** Speed through the air, in m/s: surface speed minus the wind. */
    val airspeed: Double = 0.0,
    /** The wind's speed across the ground, in m/s. */
    val windSpeed: Double = 0.0,
    /**
     * Where the wind comes from, in degrees, relative to the way the view looks across the ground,
     * so the arrow turns with the screen, the same as the windsock in view does. 0 is from straight
     * ahead into the screen, 90 from the right, and 180 from behind the camera.
     */
    val windFrom: Double = 0.0,
    /** Whether there's any air to speak of. The wind readouts are hidden in space. */
    val inAir: Boolean = false,
    /** How far under the sea the craft is, in metres. 0 or less out of it, or with no sea. */
    val depth: Double = 0.0,
    /** Under the sea, how far above its floor, in metres. NaN out of it. */
    val belowFloor: Double = Double.NaN,
    /**
     * Seconds to the next launch window for the moon (a launch due east then flies into its plane),
     * negative while one is open, and NaN for none.
     */
    val lunaWindow: Double = Double.NaN,
    /** Whose window [lunaWindow] is: the first moon of the world the craft is on. */
    val moonName: String = "",
    /**
     * The navball's frame as it stands ([com.rm.apogee.core.world.NavFrame.AUTO] resolved) and as
     * chosen. [prograde] and the markers below are in it.
     */
    val frame: com.rm.apogee.core.world.NavFrame = com.rm.apogee.core.world.NavFrame.SURFACE,
    val frameChosen: com.rm.apogee.core.world.NavFrame = com.rm.apogee.core.world.NavFrame.AUTO,
    /** Out of the plane of travel, by the right hand: orbit normal. Null when it's still. */
    val normal: Vec3? = null,
    /** In the plane of travel, away from the planet. Null when it's still. */
    val radialOut: Vec3? = null,
    /** Toward the target, or null for none. */
    val toTarget: Vec3? = null,
    val targetName: String? = null,
    val targetDistance: Double = 0.0,
    /** How fast the target is closing, in m/s. Positive when it's getting nearer. */
    val closingSpeed: Double = 0.0,
    /** The direction of travel through the air, when it's in it and moving through it. */
    val throughAir: Vec3? = null,
    /** Where the nose points, in degrees clockwise from north. */
    val heading: Double = 0.0,
    /** Up (positive) or down, over the ground, in m/s. */
    val verticalSpeed: Double = 0.0,
    /** What SAS is holding, or null when it's off. */
    val sasMode: com.rm.apogee.core.world.SasMode? = null,
    /** Every part, how hurt and how hot it is, for the damage list. Empty when all is well. */
    val parts: List<PartStatus> = emptyList(),
    /** The hottest part, as a share of what it can stand. */
    val heat: Double = 0.0,
    /** The hardest-loaded joint, as a share of its strength. */
    val structure: Double = 0.0,
    /** Parts that are hurt at all. */
    val damaged: Int = 0,
    /** Parts the craft has lost since it was launched. */
    val lost: Int = 0,
    /** What happened to it, if it's gone: the crash report. Null while it flies. */
    val destroyed: String? = null,
) {
    /** Along what's left of the next planned burn, in world axes. Null for none. */
    @Volatile var burn: Vec3? = null

    /** One part's state, for the damage list. */
    class PartStatus(val title: String, val health: Double, val heat: Double, val load: Double)

    /** Worth a warning: getting near the heat or load a part fails at, or hurt. */
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
         * It isn't a structural limit, because nothing breaks yet. It's the point where a player
         * steering hard is wasting thrust fighting the air, which is the lesson the readout is
         * there to teach.
         */
        const val MAX_Q_WARNING = 25_000.0

        /** The share of a limit where the HUD starts to warn. */
        const val CAUTION = 0.7


        val EMPTY = FlightTelemetry(
            altitude = 0.0, heightAboveGround = 0.0, surfaceSpeed = 0.0, orbitalSpeed = 0.0,
            apoapsisAltitude = 0.0, periapsisAltitude = 0.0, timeToApoapsis = 0.0,
            throttle = 0.0, stage = 0, inOrbit = false, craftName = "",
            dynamicPressure = 0.0, rotation = Quat.identity(), up = Vec3.unitY(),
            prograde = null,
        )

        /** For a craft that's gone: its name, what happened, and what it lost. */
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
            /** The sea's surface over it, in metres above the datum: the tide and the waves. */
            seaHeight: Double = 0.0,
            /** The air the craft is in, with body-fixed wind, or null for none. */
            air: com.rm.apogee.core.weather.AirSample? = null,
            /** The body's rotation now, to turn the wind into the world's frame. */
            bodyRotation: Quat? = null,
            /**
             * The craft's forward, as a design axis, for which way the wind comes from without a
             * view.
             */
            forwardAxis: Vec3 = Vec3.unitY(),
            /**
             * How the camera is turned, because the wind is read against the view, not the nose.
             */
            viewRotation: Quat? = null,
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
            /**
             * Seconds to the next launch window for the moon, negative while it's open, and NaN for
             * none.
             */
            lunaWindow: Double = Double.NaN,
            /** That moon's name. */
            moonName: String = "",
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
            // Under the sea: below its surface, tide and waves, and above its floor.
            val floor = attractor.terrain?.takeIf { attractor.ocean != null }?.elevation(bodyFixedPosition)
            val depth = if (floor != null && floor < seaHeight) seaHeight - altitude else 0.0
            val belowFloor = if (floor != null) altitude - floor - lowestPointOffset else Double.NaN

            // The wind, and the craft's motion through the air it makes.
            val up = state.position.normalized()
            val wind = if (air != null && bodyRotation != null) bodyRotation.rotate(air.wind, Vec3()) else Vec3()
            val throughAir = relative - wind

            // The navball's markers, worked out exactly the way stability assist works out what it
            // holds. It's the same function on both sides.
            val nav = com.rm.apogee.core.world.Navigation.compute(
                state.position, state.velocity, attractor, navFrame,
                target?.position, target?.velocity, com.rm.apogee.core.world.NavDirections(),
            )
            val moving = nav.velocity.length > 1.0
            val nose = state.rotation.rotate(Vec3.unitY(), Vec3())
            // Our motion toward it. Positive while the gap is shrinking.
            val closing = if (target != null && nav.hasTarget) (state.velocity - target.velocity) dot nav.toTarget else 0.0
            val horizontalWind = wind.copy().addScaledInPlace(up, -(wind dot up))
            // Ahead is into the screen, along the ground. Looking straight down, it's the screen's
            // top edge. Without a view, it's the nose.
            val heading = if (viewRotation != null) viewRotation.rotate(Vec3(0.0, 0.0, -1.0), Vec3())
                else state.rotation.rotate(forwardAxis, Vec3())
            heading.addScaledInPlace(up, -(heading dot up))
            if (viewRotation != null && heading.length < 0.1) {
                viewRotation.rotate(Vec3.unitY(), heading)
                heading.addScaledInPlace(up, -(heading dot up))
            }
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
                // Below walking pace the direction of travel is noise, and a prograde marker
                // jittering around the navball is worse than none at all.
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
                depth = depth,
                belowFloor = if (depth > 0.0) belowFloor else Double.NaN,
                lunaWindow = lunaWindow,
                moonName = moonName,
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
                // From a mild day's warmth to what it fails at. At ambient, nothing.
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
                depth = depth, belowFloor = belowFloor,
                lunaWindow = lunaWindow,
                moonName = moonName,
            )
        }
    }
}
