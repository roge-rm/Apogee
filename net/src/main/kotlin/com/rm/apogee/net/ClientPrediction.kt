package com.rm.apogee.net

import com.rm.apogee.core.craft.CraftDesign
import com.rm.apogee.core.craft.Vessel
import com.rm.apogee.core.math.Quat
import com.rm.apogee.core.math.Vec3
import com.rm.apogee.core.orbit.SolarSystem
import com.rm.apogee.core.part.PartCatalog
import com.rm.apogee.core.world.VesselKinematics
import com.rm.apogee.core.world.World

/**
 * Runs a local copy of the physics for the craft this client is flying.
 *
 * Without it, pressing the throttle does nothing visible until a command has
 * reached the server, been stepped, and come back in a snapshot - at 20 Hz that
 * is up to 50 ms of state age on top of the round trip, and controls feel
 * spongy even on a LAN. With it the craft responds on the same frame as the
 * input, and the server's authority is preserved by correction rather than by
 * waiting.
 *
 * Only the *controlled* craft is predicted. Everything else is interpolated
 * between snapshots, because there is no input to predict from and guessing at
 * another player's throttle would produce confident, wrong motion.
 *
 * The server remains authoritative throughout: every snapshot resets the local
 * state to what the server said and re-simulates forward from there. This is a
 * latency-hiding device, not a second opinion.
 */
class ClientPrediction(
    private val catalog: PartCatalog,
    private val system: SolarSystem = SolarSystem.defaultSystem(),
) {
    private var world: World? = null
    private var vessel: Vessel? = null
    private var accumulator = 0.0

    /**
     * Difference between where prediction had the craft and where reconciling
     * put it, decayed toward zero over a few frames.
     *
     * Snapping straight to the corrected state makes the craft visibly twitch
     * on every snapshot; carrying the error and bleeding it off hides the
     * correction without lying about where the craft actually is.
     */
    private val renderOffset = Vec3()

    /** Set when the local replica cannot be trusted and must be rebuilt. */
    private var designHash: Int = 0

    val isReady: Boolean get() = vessel != null

    /** Rebuilds the local replica. Called when the craft's structure changes. */
    fun adopt(design: CraftDesign, state: VesselKinematics) {
        val replica = World.default(catalog)
        vessel = replica.spawnAt(
            design = design,
            bodyId = state.referenceBodyId,
            position = state.position.copy(),
            velocity = state.velocity.copy(),
            rotation = state.rotation.copy(),
            angularVelocity = state.angularVelocity.copy(),
        )
        world = replica
        designHash = design.hashCode()
        renderOffset.setZero()
        accumulator = 0.0
    }

    /** True when [design] is not what the replica was built from. */
    fun needsAdopting(design: CraftDesign): Boolean =
        vessel == null || design.hashCode() != designHash

    /** Mirrors the player's controls onto the local replica. */
    fun applyControl(
        throttle: Double,
        pitch: Double,
        yaw: Double,
        roll: Double,
        sas: Boolean,
        brakes: Boolean = false,
    ) {
        val control = vessel?.control ?: return
        control.throttle = throttle
        control.pitch = pitch
        control.yaw = yaw
        control.roll = roll
        control.sasEnabled = sas
        control.brakes = brakes
    }

    /** Scatter the server says is down, so the replica does not collide with it. */
    fun felled(ids: Collection<Long>) {
        world?.felledScatter?.addAll(ids)
    }

    /** Fires the next stage locally, so staging feels immediate too. */
    fun stage() {
        val replica = world ?: return
        val local = vessel ?: return
        replica.stage(local)
    }

    /** Advances the replica by real elapsed time, in fixed steps. */
    fun advance(elapsedSeconds: Double) {
        val replica = world ?: return
        accumulator += elapsedSeconds.coerceAtMost(MAX_CATCHUP_SECONDS)
        while (accumulator >= DT) {
            replica.step(DT)
            accumulator -= DT
        }
        // Bleed off any outstanding correction.
        renderOffset.mulInPlace(OFFSET_DECAY)
        if (renderOffset.lengthSq < 1e-6) renderOffset.setZero()
    }

    /**
     * Resets to the server's state and re-simulates forward by [ageSeconds].
     *
     * The snapshot describes the world as it was when the server sent it. Using
     * it directly would rubber-band the craft backwards on every update; the
     * catch-up re-runs the same controls over the elapsed time so the local
     * state is the server's answer brought up to date.
     */
    fun reconcile(state: VesselKinematics, ageSeconds: Double) {
        val replica = world ?: return
        val local = vessel ?: return

        val before = local.body.position.copy()

        // Writing state into a sleeping replica would be ignored: a dormant
        // craft rides the planet's rotation from its stored ground position
        // and is not integrated, so it would sit there while the server's
        // craft flew away. The replica sleeps for the same reason the server's
        // world does - it is the same World - and reconciliation is precisely
        // the moment to say it is no longer parked.
        local.wake()
        local.body.position.setTo(state.position)
        local.body.linearVelocity.setTo(state.velocity)
        local.body.orientation.setTo(state.rotation)
        local.body.angularVelocity.setTo(state.angularVelocity)

        val catchUp = (ageSeconds / DT).toInt().coerceIn(0, MAX_CATCHUP_TICKS)
        repeat(catchUp) { replica.step(DT) }

        // Carry the difference so the correction is smoothed out rather than
        // applied as a jump.
        renderOffset.addInPlace(before).subInPlace(local.body.position)
        if (renderOffset.length > MAX_SMOOTHED_ERROR) {
            // Too far out to hide - the replica was wrong about something real,
            // so show the truth rather than sliding toward it for a second.
            renderOffset.setZero()
        }
    }

    /** Where to draw the craft: the prediction, plus the decaying correction. */
    fun renderPosition(out: Vec3 = Vec3()): Vec3? {
        val local = vessel ?: return null
        return out.setTo(local.body.position).addInPlace(renderOffset)
    }

    fun renderRotation(out: Quat = Quat()): Quat? {
        val local = vessel ?: return null
        return out.setTo(local.body.orientation)
    }

    fun velocity(out: Vec3 = Vec3()): Vec3? {
        val local = vessel ?: return null
        return out.setTo(local.body.linearVelocity)
    }

    fun reset() {
        world = null
        vessel = null
        designHash = 0
        renderOffset.setZero()
    }

    private companion object {
        const val DT = 1.0 / 60.0

        /** Most real time one advance may try to make up. */
        const val MAX_CATCHUP_SECONDS = 0.25

        /** Cap on reconciliation catch-up, so a stalled connection cannot spiral. */
        const val MAX_CATCHUP_TICKS = 30

        /** Per-frame decay of the smoothed correction. */
        const val OFFSET_DECAY = 0.85

        /**
         * Errors beyond this are shown immediately.
         *
         * Smoothing a large error means drawing the craft somewhere it is not
         * for a noticeable time - if prediction was wrong by more than a craft
         * length, the honest thing is to correct visibly.
         */
        const val MAX_SMOOTHED_ERROR = 25.0
    }
}
