package com.rm.apogee.game

import com.rm.apogee.core.craft.CraftDesign
import com.rm.apogee.core.craft.StockCraft
import com.rm.apogee.core.math.Quat
import com.rm.apogee.core.math.Vec3
import com.rm.apogee.core.orbit.Orbit
import com.rm.apogee.core.orbit.SolarSystem
import com.rm.apogee.core.part.PartCatalog
import com.rm.apogee.core.part.StockParts
import com.rm.apogee.core.world.Command
import com.rm.apogee.core.world.World
import com.rm.apogee.core.world.VesselKinematics
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
import com.rm.apogee.render.RenderItem
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

    private val cameraPosition = Vec3()
    private val cameraRotation = Quat.identity()
    private val scratch = Vec3()
    private val predictedPosition = Vec3()
    private val predictedRotation = Quat.identity()
    private val bodyRotation = Quat.identity()
    private val bodyFixedCamera = Vec3()

    /**
     * Builds terrain geometry from the simulation's own height field.
     *
     * Created once a quality tier is known, because how finely to sample is
     * the one thing about terrain that depends on the device.
     */
    private var terrainBuilder: TerrainBuilder? = null
    private var drawFarSurface = true

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
    fun attachTerrain(source: com.rm.apogee.render.TerrainSource, quality: QualityTier) {
        terrainBuilder = TerrainBuilder(source, quality)
    }

    fun start(scope: CoroutineScope) {
        terrainScope = scope
        serverJob = hostedServer?.start(scope)
        clientJob = client.connect(scope)
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

    suspend fun setSas(enabled: Boolean) {
        localSas = enabled
        pushControlsToPrediction()
        withControlledVessel { client.send(Command.SetSas(it, enabled)) }
    }

    suspend fun stage() {
        // Staged locally as well, so the button responds immediately; the
        // server's own staging arrives in the next structure update and
        // overwrites this.
        prediction.stage()
        withControlledVessel { client.send(Command.Stage(it)) }
    }

    private fun pushControlsToPrediction() =
        prediction.applyControl(localThrottle, localPitch, localYaw, localRoll, localSas)

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
            camera.solve(focusPosition, cameraPosition, cameraRotation)
            for (vessel in client.vessels) {
                if (vessel.id == focusId) {
                    appendVessel(vessel, items, predictedPosition, predictedRotation)
                } else {
                    appendVessel(vessel, items)
                }
            }
        }

        // Terrain turns with the planet, so the patch follows the craft's
        // position in the body's frame rather than its inertial one.
        attractor.rotationAt(client.latestSnapshot?.time ?: 0.0, bodyRotation)
        attractor.toBodyFixed(focusState.position, bodyRotation, bodyFixedCamera)
        terrainBuilder?.let { builder ->
            builder.requestGlobe(attractor, terrainScope)
            builder.followCraft(
                attractor,
                bodyFixedCamera,
                attractor.heightAboveTerrain(focusState.position, bodyFixedCamera),
                terrainScope,
            )
            drawFarSurface = builder.farSurfaceNeeded
        }

        telemetry = FlightTelemetry.from(focus, attractor, focusState.throttle, bodyFixedCamera)

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
                ),
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
        if (prediction.needsAdopting(focus.design)) {
            prediction.adopt(focus.design, state)
            pushControlsToPrediction()
        }

        val snapshot = client.latestSnapshot
        if (snapshot != null && snapshot.tick != lastReconciledTick) {
            lastReconciledTick = snapshot.tick
            // How stale the server's word is by the time we act on it.
            val age = (System.nanoTime() - client.latestSnapshotNanos) / 1e9
            prediction.reconcile(state, age)
        }

        val now = System.nanoTime()
        if (lastAdvanceNanos != 0L) {
            prediction.advance((now - lastAdvanceNanos) / 1e9)
        }
        lastAdvanceNanos = now

        prediction.renderPosition(predictedPosition)
        prediction.renderRotation(predictedRotation)
        return if (prediction.isReady) predictedPosition else state.position
    }

    /** Turns one craft's design plus its motion into per-part draw items. */
    private fun appendVessel(
        vessel: ClientVessel,
        out: MutableList<RenderItem>,
        overridePosition: Vec3? = null,
        overrideRotation: Quat? = null,
    ) {
        val state = vessel.latest ?: return
        val design = vessel.design

        // The server sends the vessel's centre of mass; part positions in the
        // design are relative to the design origin, so the offset between them
        // has to be reconstructed here.
        val centreOfMass = designCentreOfMass(design)
        val position = overridePosition ?: state.position
        val rotation = overrideRotation ?: state.rotation

        for (placed in design.parts) {
            val def = catalog[placed.partId] ?: continue

            scratch.setTo(placed.position).subInPlace(centreOfMass)
            rotation.rotate(scratch, scratch)
            scratch.addInPlace(position)

            out.add(
                RenderItem(
                    meshSpec = def.mesh,
                    position = scratch.copy(),
                    rotation = rotation * placed.rotation,
                    color = colorFor(placed.partId),
                )
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

    private fun colorFor(partId: String): FloatArray = when {
        partId.startsWith("engine") -> floatArrayOf(0.45f, 0.45f, 0.50f, 1f)
        partId.startsWith("tank") -> floatArrayOf(0.82f, 0.82f, 0.86f, 1f)
        partId.startsWith("pod") -> floatArrayOf(0.70f, 0.62f, 1.00f, 1f)
        partId.startsWith("decoupler") -> floatArrayOf(0.90f, 0.70f, 0.35f, 1f)
        partId.startsWith("fin") -> floatArrayOf(0.60f, 0.20f, 0.20f, 1f)
        partId.startsWith("parachute") -> floatArrayOf(0.55f, 0.55f, 0.60f, 1f)
        else -> floatArrayOf(0.75f, 0.75f, 0.78f, 1f)
    }

    companion object {
        /** ~60 Hz. The server streams slower; the renderer interpolates. */
        private const val PRESENT_INTERVAL_MILLIS = 16L

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
            serverName: String,
            design: CraftDesign? = null,
            catalog: PartCatalog = StockParts.catalog,
            scope: CoroutineScope,
        ): GameSession {
            val session = hostLocal(
                frameBus, perfHints, playerName, design, catalog, scope,
                // The name has to reach the server config, not just the beacon:
                // it is what the welcome message reports, so a joining player
                // sees the name they picked in the browser.
                serverName = serverName,
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
            host: String,
            port: Int,
            catalog: PartCatalog = StockParts.catalog,
        ): Result<GameSession> = TcpTransport.connect(host, port).map { transport ->
            GameSession(
                frameBus = frameBus,
                perfHints = perfHints,
                catalog = catalog,
                hostedServer = null,
                client = GameClient(transport, playerName, catalog.contentHash),
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
            /** What to fly. Null falls back to the stock rocket. */
            design: CraftDesign? = null,
            catalog: PartCatalog = StockParts.catalog,
            scope: CoroutineScope,
            serverName: String = "Local Game",
        ): GameSession {
            val server = GameServer(
                world = World.default(catalog),
                config = ServerConfig(
                    name = serverName,
                    starterCraft = { design ?: StockCraft.starterRocket(it) },
                ),
            )
            val link = LoopbackTransportPair()
            server.accept(link.serverSide, scope)

            val client = GameClient(link.clientSide, playerName, catalog.contentHash)
            return GameSession(frameBus, perfHints, catalog, server, client, link.clientSide)
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

            return FlightTelemetry(
                altitude = altitude,
                heightAboveGround = attractor.heightAboveTerrain(state.position, bodyFixedPosition),
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
            )
        }
    }
}
