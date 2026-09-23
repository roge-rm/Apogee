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
    private var localBrakes = false
    private var seenFelledRevision = -1

    private var lastReconciledTick = -1L
    private var lastAdvanceNanos = 0L

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
        terrainBuilder?.stop()
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

    suspend fun setBrakes(engaged: Boolean) {
        localBrakes = engaged
        pushControlsToPrediction()
        withControlledVessel { client.send(Command.SetBrakes(it, engaged)) }
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
        // overwrites this.
        prediction.stage()
        withControlledVessel { client.send(Command.Stage(it)) }
    }

    private fun pushControlsToPrediction() =
        prediction.applyControl(localThrottle, localPitch, localYaw, localRoll, localSas, localBrakes)

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
        val focus = client.vessel(focusId) ?: return
        val focusState = focus.latest ?: return

        val attractor = system.bodies[focusState.referenceBodyId] ?: return

        val lines = ArrayList<RenderLine>(4)
        val items = ArrayList<RenderItem>(64)
        frameEmitters.clear()

        // The one time this frame is drawn at. Snapshot time plus how long
        // ago it arrived, unless the controlled craft is being predicted, in
        // which case its own time: the ground, the craft on it and everything
        // else have to be shown at the same instant, because at the equator a
        // few milliseconds' disagreement is a visible slide.
        val snapshotTime = client.latestSnapshot?.time ?: 0.0
        val snapshotAge = if (client.latestSnapshotNanos == 0L) 0.0
            else (System.nanoTime() - client.latestSnapshotNanos) / 1e9
        var renderTime = snapshotTime + snapshotAge
        lastRenderTime = renderTime
        val animationNow = System.nanoTime()
        animationDt = if (lastAnimationNanos == 0L) 0.0 else ((animationNow - lastAnimationNanos) / 1e9).coerceAtMost(0.1)
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
            val focusPosition = updatePrediction(focus, focusState)
            focusDrawn = focusPosition
            if (prediction.isReady) prediction.renderTime()?.let { renderTime = it }
            camera.solve(focusPosition, cameraPosition, cameraRotation)
            for (vessel in client.vessels) {
                if (vessel.id == focusId) {
                    appendVessel(vessel, items, attractor, predictedPosition, predictedRotation, predicted = prediction.isReady)
                } else {
                    // Carried from its own snapshot to the frame's time along
                    // its velocity, which for a parked craft is the ground's.
                    val observed = vessel.observed ?: continue
                    val state = observed.kinematics
                    val carry = (renderTime - observed.time).coerceIn(0.0, MAX_EXTRAPOLATION_SECONDS)
                    appendVessel(
                        vessel, items, attractor,
                        Vec3().setTo(state.position).addScaledInPlace(state.velocity, carry),
                        stateOverride = state,
                    )
                }
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

        joinable = neighbourInWeldingRange(focus, focusState)

        // Local vertical in world axes: the craft's own position direction.
        scratchUp.setTo(focusState.position).normalizeInPlace()
        telemetry = FlightTelemetry.from(
            focus, attractor, focusState.throttle, bodyFixedCamera,
            lowestPointOffset = lowestPointOffset(focus.design, focusState.rotation, scratchUp),
            air = prediction.replica?.air,
            bodyRotation = bodyRotation,
            forwardAxis = focus.design.orientation.forward,
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
            if (!mapMode) clouds.append(bodyRotation, items)
        }

        // Flames, smoke and the rest: stepped by real frame time, drawn
        // through the same weather as the clouds.
        val fx = effects ?: Effects(terrainQuality ?: QualityTier.MEDIUM).also { effects = it }
        attractor.toBodyFixed(cameraPosition, bodyRotation, cloudCamera)
        fx.step(animationDt, renderTime, attractor, bodyRotation, frameEmitters, clouds?.weather, cloudCamera, clouds?.air)
        var particles: FloatArray? = null
        var particleShapes = 0
        if (!mapMode) {
            fx.flames(frameEmitters, attractor, renderTime, items)
            val (vertices, shapes) = fx.vertices(bodyRotation, cameraPosition, cameraRotation, clouds?.lightScale ?: 1f, renderTime)
            particles = vertices
            particleShapes = shapes
        }
        val flash = if (mapMode) 0f else fx.flash

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
                    lightScale = if (mapMode) 1f else ((clouds?.lightScale ?: 1f) + 1.4f * flash).coerceAtMost(2.2f),
                    surfaceWind = clouds?.surfaceWind?.copy() ?: Vec3(),
                    time = renderTime,
                ),
                nearestDistance = if (nearest == Double.MAX_VALUE) 0.0 else nearest.coerceAtLeast(0.0),
                particles = particles,
                particleShapes = particleShapes,
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
        // How stale the server's word is by the time we act on it.
        val age = (now - client.latestSnapshotNanos) / 1e9
        if (prediction.needsAdopting(focus.design)) {
            // On the server's clock: see ClientPrediction.adopt.
            prediction.adopt(focus.design, state, snapshot?.time ?: 0.0, client.weather)
            pushControlsToPrediction()
            lastReconciledTick = -1
        }

        // Advance first, then reconcile, so both are measured to the same
        // moment - the reverse spends the time since the last frame twice.
        if (lastAdvanceNanos != 0L) {
            prediction.advance((now - lastAdvanceNanos) / 1e9)
        }
        lastAdvanceNanos = now

        if (snapshot != null && snapshot.tick != lastReconciledTick) {
            lastReconciledTick = snapshot.tick
            prediction.reconcile(state, age, snapshot.time)
        }
        prediction.sync(focus.currentStage, focus.activatedParts, focus.fuel)
        refreshStageCards()

        prediction.renderPosition(predictedPosition)
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
        for ((index, placed) in design.parts.withIndex()) {
            val def = defs[index] ?: continue

            scratch.setTo(placed.position).subInPlace(centreOfMass)
            rotation.rotate(scratch, scratch)
            scratch.addInPlace(position)

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
            // A lit engine leaves a flame and smoke behind it.
            def.module<com.rm.apogee.core.part.Engine>()?.let { engine ->
                // The replica's parts are only this design's while their counts
                // agree: staging splits it at once, before the server's new
                // structure arrives, and indexing it by this design's parts
                // then ran off the end.
                val replica = prediction.replica?.takeIf { predicted && it.defs.size == design.parts.size }
                val lit = if (replica != null) replica.isWorking(index) && replica.control.throttle > 0.0
                    else index in vessel.activatedParts
                if (lit && state.throttle > 0.01) {
                    val partRotation = rotation * placed.rotation
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
                            kind = engine.exhaustKind,
                            throttle = if (predicted) (prediction.replica?.control?.throttle ?: state.throttle) else state.throttle,
                            velocity = state.velocity.copy(),
                            seed = (vessel.id * 31 + index).toInt(),
                        ),
                    )
                }
            }
            anim.spin = animation.spin[index] % (2 * Math.PI)

            val partRotation = rotation * placed.rotation
            val body = com.rm.apogee.render.PartModels.bodyColour(placed.partId)
            leaves.clear()
            PartModels.expand(def, caps[index], anim, leaves)
            for (leaf in leaves) {
                out.add(
                    RenderItem(
                        caps = leaf.caps,
                        shape = leaf.shape,
                        position = partRotation.rotate(leaf.position).addInPlace(scratch),
                        rotation = partRotation * leaf.rotation,
                        color = PartModels.colour(leaf.tint, body),
                    )
                )
            }
        }

        // The air it pushes through, made visible: vapour and re-entry glow.
        if (!mapMode) {
            val throughAir = Vec3().setTo(state.velocity).subInPlace(attractor.surfaceVelocityAt(position, Vec3()))
            if (predicted) prediction.replica?.air?.let { air -> throughAir.subInPlace(bodyRotation.rotate(air.wind, Vec3())) }
            effects?.aero(
                position, throughAir, designRadius(design, centreOfMass), attractor, bodyRotation,
                animationDt, lastRenderTime, vessel.id.toInt(), out,
            )
        }
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

    private fun neighbourInWeldingRange(
        focus: ClientVessel,
        state: com.rm.apogee.core.world.VesselKinematics,
    ): Boolean {
        val reach = designReach(focus.design)
        for (other in client.vessels) {
            if (other.id == focus.id) continue
            val theirs = other.latest ?: continue
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
        private val SUN_DIRECTION = Vec3(0.62, 0.45, 0.64).normalizeInPlace()

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
        ): GameSession {
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
) {
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

        val EMPTY = FlightTelemetry(
            altitude = 0.0, heightAboveGround = 0.0, surfaceSpeed = 0.0, orbitalSpeed = 0.0,
            apoapsisAltitude = 0.0, periapsisAltitude = 0.0, timeToApoapsis = 0.0,
            throttle = 0.0, stage = 0, inOrbit = false, craftName = "",
            dynamicPressure = 0.0, rotation = Quat.identity(), up = Vec3.unitY(),
            prograde = null,
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
                prograde = if (relative.length > 1.0) relative.normalized() else null,
                airspeed = throughAir.length,
                windSpeed = horizontalWind.length,
                windFrom = windFrom,
                inAir = density > 1e-3,
            )
        }
    }
}
