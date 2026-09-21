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
import com.rm.apogee.net.ClientVessel
import com.rm.apogee.net.GameClient
import com.rm.apogee.net.LoopbackTransportPair
import com.rm.apogee.net.Transport
import com.rm.apogee.platform.PerfHints
import com.rm.apogee.render.FrameBus
import com.rm.apogee.render.RenderFrame
import com.rm.apogee.render.RenderItem
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

    /** Published for the debug overlay. */
    val lastFrameBuildNanos = AtomicLong(0)
    val framesPublished = AtomicLong(0)

    /** Telemetry for the HUD, refreshed each published frame. */
    @Volatile var telemetry: FlightTelemetry = FlightTelemetry.EMPTY
        private set

    private var serverJob: Job? = null
    private var clientJob: Job? = null
    private var presentJob: Job? = null

    private val cameraPosition = Vec3()
    private val cameraRotation = Quat.identity()
    private val scratch = Vec3()

    val connected: Boolean get() = client.connected
    val rejectionReason: String? get() = client.rejectionReason

    fun start(scope: CoroutineScope) {
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
        client.close()
        transport.close()
        frameBus.clear()
    }

    // --- commands ------------------------------------------------------------

    private suspend fun withControlledVessel(block: suspend (Long) -> Unit) {
        client.controlledVessel?.let { block(it) }
    }

    suspend fun setThrottle(value: Double) =
        withControlledVessel { client.send(Command.SetThrottle(it, value)) }

    suspend fun setAttitude(pitch: Double, yaw: Double, roll: Double) =
        withControlledVessel { client.send(Command.SetAttitude(it, pitch, yaw, roll)) }

    suspend fun setSas(enabled: Boolean) =
        withControlledVessel { client.send(Command.SetSas(it, enabled)) }

    suspend fun stage() = withControlledVessel { client.send(Command.Stage(it)) }

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

        val system = SolarSystem.defaultSystem()
        val attractor = system.bodies[focusState.referenceBodyId] ?: return

        camera.solve(focusState.position, cameraPosition, cameraRotation)

        val items = ArrayList<RenderItem>(64)
        for (vessel in client.vessels) {
            appendVessel(vessel, items)
        }

        telemetry = FlightTelemetry.from(focus, attractor, focusState.throttle)

        frameBus.publish(
            RenderFrame(
                simTick = client.latestSnapshot?.tick ?: 0L,
                timestampNanos = timestampNanos,
                cameraPosition = cameraPosition.copy(),
                cameraRotation = cameraRotation.copy(),
                fovYRadians = Math.toRadians(55.0),
                items = items,
                attractorRadius = attractor.radius,
            )
        )
        framesPublished.incrementAndGet()
    }

    /** Turns one craft's design plus its motion into per-part draw items. */
    private fun appendVessel(vessel: ClientVessel, out: MutableList<RenderItem>) {
        val state = vessel.latest ?: return
        val design = vessel.design

        // The server sends the vessel's centre of mass; part positions in the
        // design are relative to the design origin, so the offset between them
        // has to be reconstructed here.
        val centreOfMass = designCentreOfMass(design)

        for (placed in design.parts) {
            val def = catalog[placed.partId] ?: continue

            scratch.setTo(placed.position).subInPlace(centreOfMass)
            state.rotation.rotate(scratch, scratch)
            scratch.addInPlace(state.position)

            out.add(
                RenderItem(
                    meshSpec = def.mesh,
                    position = scratch.copy(),
                    rotation = state.rotation * placed.rotation,
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
        ): GameSession {
            val server = GameServer(
                world = World.default(catalog),
                config = ServerConfig(
                    name = "Local Game",
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
    val altitude: Double,
    val surfaceSpeed: Double,
    val orbitalSpeed: Double,
    val apoapsisAltitude: Double,
    val periapsisAltitude: Double,
    val throttle: Double,
    val stage: Int,
    val inOrbit: Boolean,
    val craftName: String,
) {
    companion object {
        val EMPTY = FlightTelemetry(0.0, 0.0, 0.0, 0.0, 0.0, 0.0, 0, false, "")

        fun from(
            vessel: ClientVessel,
            attractor: com.rm.apogee.core.orbit.CelestialBody,
            throttle: Double,
        ): FlightTelemetry {
            val state = vessel.latest ?: return EMPTY
            val orbit = Orbit(
                position = state.position,
                velocity = state.velocity,
                mu = attractor.gravitationalParameter,
            )
            val surfaceVelocity = attractor.surfaceVelocityAt(state.position)
            return FlightTelemetry(
                altitude = attractor.altitudeOf(state.position),
                surfaceSpeed = (state.velocity - surfaceVelocity).length,
                orbitalSpeed = state.velocity.length,
                apoapsisAltitude = orbit.apoapsis - attractor.radius,
                periapsisAltitude = orbit.periapsis - attractor.radius,
                throttle = throttle,
                stage = vessel.currentStage,
                inOrbit = orbit.isBound && orbit.periapsis > attractor.radius + attractor.atmosphereHeight,
                craftName = vessel.name,
            )
        }
    }
}
