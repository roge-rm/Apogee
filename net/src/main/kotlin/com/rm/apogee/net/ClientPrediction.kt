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
     *
     * Held in the body's rotating frame - relative to the ground - not the
     * inertial one. Each snapshot moves the prediction's clock by however
     * unevenly the server has been ticking, a few milliseconds; measured
     * inertially that is 175 m/s times the slip at the equator, and the
     * smoothing faithfully kept the craft where it had been while the ground
     * was drawn at the new time - metres of craft off its ground, fading over
     * a few frames, every snapshot. Against the ground, a craft that has not
     * moved over it has no error to smooth.
     */
    private val renderOffset = Vec3()

    /** Set when the local replica cannot be trusted and must be rebuilt. */
    private var designHash: Int = 0

    val isReady: Boolean get() = vessel != null

    /**
     * Rebuilds the local replica. Called when the craft's structure changes.
     *
     * [time] is the universe time [state] describes. It matters as much as the
     * position: on a turning planet the ground under a craft is wherever the
     * planet has turned to by then, and a replica on its own clock puts a
     * craft parked on the runway onto whatever ground its wrong time says is
     * there - and every snapshot drags it back.
     */
    fun adopt(design: CraftDesign, state: VesselKinematics, time: Double = 0.0) {
        val replica = World.default(catalog)
        replica.syncClock(time)
        vessel = replica.spawnAt(
            design = design,
            bodyId = state.referenceBodyId,
            position = state.position.copy(),
            velocity = state.velocity.copy(),
            rotation = state.rotation.copy(),
            angularVelocity = state.angularVelocity.copy(),
        )
        // Parked on the server: parked here from the start, rather than
        // settling onto its legs until the first snapshot says otherwise.
        if (serverHasItAsleep(state)) {
            vessel?.sleep(system.body(state.referenceBodyId).rotationAt(time, scratchRotation))
        }
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
        // A hand on the controls is the same signal the server wakes on.
        if (!inputsNeutral(control)) vessel?.wake()
    }

    private fun inputsNeutral(control: com.rm.apogee.core.craft.ControlState): Boolean =
        control.throttle == 0.0 && control.pitch == 0.0 && control.yaw == 0.0 &&
            control.roll == 0.0 && control.translateX == 0.0 &&
            control.translateY == 0.0 && control.translateZ == 0.0

    /** Scatter the server says is down, so the replica does not collide with it. */
    fun felled(ids: Collection<Long>) {
        world?.felledScatter?.addAll(ids)
    }

    /** Fires the next stage locally, so staging feels immediate too. */
    fun stage() {
        val replica = world ?: return
        val local = vessel ?: return
        local.wake()
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
    fun reconcile(state: VesselKinematics, ageSeconds: Double, snapshotTime: Double? = null) {
        val replica = world ?: return
        val local = vessel ?: return

        // Where it was being drawn, not where it last stepped to: the two
        // differ by the fraction of a step since, and counting that as error
        // would twitch the craft on every snapshot.
        val before = local.body.position.copy().addScaledInPlace(local.body.linearVelocity, accumulator)
        toGround(local, before, replica.time + accumulator)

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
        // Back to the moment the snapshot describes, so the ground is where
        // it was then; the catch-up brings both forward together. Without the
        // server's time, the replica's own clock less the snapshot's age.
        val describes = snapshotTime ?: (replica.time + accumulator - ageSeconds)
        replica.syncClock(describes)

        // Asleep on the server, and nobody touching the controls: asleep here
        // too, at exactly the server's pose. Woken instead, the replica's
        // craft settles onto its sprung legs for the three frames until the
        // next snapshot resets it - a few centimetres of bounce, which with
        // the camera following the craft is the ground jittering under it.
        if (serverHasItAsleep(state) && inputsNeutral(local.control)) {
            local.sleep(system.body(state.referenceBodyId).rotationAt(describes, scratchRotation))
        }

        val catchUp = (ageSeconds / DT).toInt().coerceIn(0, MAX_CATCHUP_TICKS)
        repeat(catchUp) { replica.step(DT) }
        // The part of the age too short for a whole step waits in the
        // accumulator, where the next advance spends it and [renderTime]
        // counts it, rather than being dropped.
        accumulator = (ageSeconds - catchUp * DT).coerceIn(0.0, DT)

        // Carry the difference so the correction is smoothed out rather than
        // applied as a jump.
        val after = local.body.position.copy().addScaledInPlace(local.body.linearVelocity, accumulator)
        toGround(local, after, replica.time + accumulator)
        renderOffset.addInPlace(before).subInPlace(after)
        if (renderOffset.length > MAX_SMOOTHED_ERROR) {
            // Too far out to hide - the replica was wrong about something real,
            // so show the truth rather than sliding toward it for a second.
            renderOffset.setZero()
        }
    }

    /**
     * Whether [state] is a sleeping craft. The server rebuilds a sleeping
     * craft's velocity from the planet's rotation every tick, so it matches
     * the surface to rounding error; an awake one resting on its gear never
     * does - it carries the tick's contact impulse, a tenth of a metre a
     * second or more. No flag on the wire needed.
     */
    private fun serverHasItAsleep(state: VesselKinematics): Boolean {
        val body = system.bodies[state.referenceBodyId] ?: return false
        body.surfaceVelocityAt(state.position, scratchVelocity)
        if (scratchVelocity.subInPlace(state.velocity).length > ASLEEP_SPEED) return false
        body.angularVelocity(scratchVelocity)
        return scratchVelocity.subInPlace(state.angularVelocity).length <= ASLEEP_SPIN
    }

    private val scratchVelocity = Vec3()
    private val scratchRotation = Quat.identity()

    /**
     * The universe time the predicted craft is drawn at: the replica's last
     * step plus the part of a step since. Everything else in the frame - the
     * planet's rotation above all - must be drawn at this same time, or the
     * ground and the craft on it disagree by 175 m/s times the difference.
     */
    fun renderTime(): Double? {
        val replica = world ?: return null
        return replica.time + accumulator
    }

    /**
     * Where to draw the craft: the prediction carried forward to
     * [renderTime], plus the decaying correction.
     *
     * Carried forward because the replica steps at 60 Hz and a display runs
     * at 60 to 120: drawn at its last step, a craft parked on the equator
     * moves in 2.9 m jumps while the ground under it turns smoothly.
     */
    fun renderPosition(out: Vec3 = Vec3()): Vec3? {
        val local = vessel ?: return null
        out.setTo(local.body.position).addScaledInPlace(local.body.linearVelocity, accumulator)
        if (renderOffset.lengthSq == 0.0) return out
        // The correction is held against the ground; turn it with the ground.
        val replica = world ?: return out
        system.body(local.referenceBodyId).rotationAt(replica.time + accumulator, scratchRotation)
        scratchRotation.rotate(renderOffset, scratchVelocity)
        return out.addInPlace(scratchVelocity)
    }

    /** [position] (inertial, at [time]) into the body's rotating frame, in place. */
    private fun toGround(local: Vessel, position: Vec3, time: Double) {
        system.body(local.referenceBodyId).rotationAt(time, scratchRotation)
        scratchRotation.inverseRotate(position.copy(), position)
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

        /** Relative speed, m/s, below which a snapshot can only be a sleeping craft. */
        const val ASLEEP_SPEED = 1e-4

        /** The same for spin, rad/s. */
        const val ASLEEP_SPIN = 1e-9

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
