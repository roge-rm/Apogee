package com.rm.apogee.core.world

import com.rm.apogee.core.craft.Vessel
import com.rm.apogee.core.math.Quat
import com.rm.apogee.core.math.Vec3
import com.rm.apogee.core.math.quatFromTo
import com.rm.apogee.core.orbit.CelestialBody
import com.rm.apogee.core.part.Engine
import com.rm.apogee.core.part.LiftGas
import com.rm.apogee.core.part.Rotor
import kotlin.math.tan
import com.rm.apogee.core.math.Math

/**
 * The keeper core: holds a craft over a spot on the ground at a height, with whatever it has.
 *
 * - Rotors or down-pointing engines: throttle holds the height, with a creeping trim, and it tips
 *   toward the spot through stability assist like a drone, no more than [MOST_TILT].
 * - Gas cells only: ballonets hold the height, and whatever pushes it points into the wind.
 * - Afloat: the same without the height.
 *
 * Steered by hand, the stick flies it and it keeps only the height, then holds where it's let go.
 */
internal class StationKeeping {
    private val up = Vec3()
    private val target = Vec3()
    private val error = Vec3()
    private val across = Vec3()
    private val ground = Vec3()
    private val flat = Vec3()
    private val wanted = Vec3()
    private val gravity = Vec3()
    private val desiredUp = Vec3()
    private val forward = Vec3()
    private val wind = Vec3()
    private val scratch = Vec3()
    private val turnUp = Quat()
    private val turnForward = Quat()

    /** What the core can hold [vessel] with, or null for nothing. */
    enum class Means { LIFT, GAS, WATER }

    fun means(vessel: Vessel, attractor: CelestialBody, afloat: Boolean): Means? {
        val air = attractor.atmosphere != null
        val lifting = vessel.defs.indices.any { i ->
            !vessel.isBroken(i) && ((air && vessel.defs[i].module<Rotor>()?.tail == false) || liftEngine(vessel, i))
        }
        return when {
            lifting -> Means.LIFT
            air && vessel.defs.any { it.hasModule<LiftGas>() } -> Means.GAS
            afloat -> Means.WATER
            else -> null
        }
    }

    /** Whether part [i] is an engine pointing down, so it holds the craft up. */
    private fun liftEngine(vessel: Vessel, i: Int): Boolean {
        val engine = vessel.defs[i].module<Engine>() ?: return false
        val thrust = vessel.design.parts[i].rotation.rotate(engine.thrustDirection, scratch).normalizeInPlace()
        return (thrust dot vessel.design.orientation.up) > LIFT_ALIGNED
    }

    /**
     * One tick of holding [vessel] over its [com.rm.apogee.core.craft.ControlState.keepPoint], with
     * the planet at [rotation]. [steered] while the stick is being used.
     */
    fun fly(vessel: Vessel, attractor: CelestialBody, rotation: Quat, means: Means, steered: Boolean, dt: Double) {
        val control = vessel.control
        val body = vessel.body
        up.setTo(body.position).normalizeInPlace()
        rotation.rotate(control.keepPoint, target)
        error.setTo(target).subInPlace(body.position)
        val below = error dot up
        across.setTo(error).addScaledInPlace(up, -below)
        attractor.surfaceVelocityAt(body.position, ground).negateInPlace().addInPlace(body.linearVelocity)
        val climb = ground dot up
        flat.setTo(ground).addScaledInPlace(up, -climb)
        val g = attractor.gravityAt(body.position, gravity).length
        rotation.rotate(vessel.air.wind, wind)
        wind.addScaledInPlace(up, -(wind dot up))

        // Speed over the ground wanted, toward the spot and slowing near it. Floating craft can't
        // brake and coast a long way, so they come back gently.
        val floating = means != Means.LIFT
        wanted.setTo(across).mulInPlace(if (floating) FLOAT_POSITION_GAIN else POSITION_GAIN)
        val most = if (floating) FLOAT_DRIFT else MOST_DRIFT
        if (wanted.length > most) wanted.mulInPlace(most / wanted.length)

        when (means) {
            Means.LIFT -> {
                val climbWanted = (below * HEIGHT_GAIN).coerceIn(-MOST_CLIMB, MOST_CLIMB)
                control.keepTrim = (control.keepTrim + (climbWanted - climb) * TRIM_RATE * dt).coerceIn(0.0, 1.0)
                control.throttle = (control.keepTrim + (climbWanted - climb) * CLIMB_GAIN).coerceIn(0.0, 1.0)
                // With gas cells too, the ballonets slowly take the weight off the rotors.
                if (vessel.defs.any { it.hasModule<LiftGas>() }) {
                    control.ballast = when {
                        control.throttle > GAS_OFFLOAD -> -1
                        control.throttle < GAS_IDLE && climb > climbWanted -> 1
                        else -> 0
                    }
                }
                if (steered) {
                    // By hand it flies like a drone: the stick sets a tilt (capped so it can hold
                    // its height), never a turn rate, and yaw turns it.
                    vessel.body.orientation.rotate(vessel.design.orientation.forward, forward)
                    forward.addScaledInPlace(up, -(forward dot up))
                    if (forward.length > 1e-6) {
                        forward.normalizeInPlace()
                        scratch.setTo(forward).crossInPlace(up)
                        val tilt = g * tan(Math.toRadians(MOST_HAND_TILT))
                        desiredUp.setTo(up).mulInPlace(g)
                            .addScaledInPlace(forward, -control.pitch * tilt)
                            .addScaledInPlace(scratch, control.roll * tilt)
                            .normalizeInPlace()
                        if (control.yaw != 0.0 && vessel.assistHolding) {
                            Quat.fromAxisAngle(up, -control.yaw * HAND_YAW_RATE * dt, turnForward)
                            vessel.assistHeld.setTo(turnForward * vessel.assistHeld)
                        }
                        hold(vessel, null)
                        control.stickTilts = true
                    }
                    return
                }
                // Tipped into the push it needs, like a drone.
                val push = scratch.setTo(wanted).subInPlace(flat).mulInPlace(SPEED_GAIN)
                val most = g * tan(Math.toRadians(MOST_TILT))
                if (push.length > most) push.mulInPlace(most / push.length)
                desiredUp.setTo(up).mulInPlace(g).addInPlace(push).normalizeInPlace()
                hold(vessel, null)
            }
            Means.GAS, Means.WATER -> {
                if (means == Means.GAS) {
                    // The ballonets for the height, filled to whatever gives the lift wanted.
                    val trim = Aerostatics.trimFor(vessel, below, climb, g, dt)
                    control.ballast = when {
                        trim > vessel.ballonet + Aerostatics.TRIM_NEAR -> 1
                        trim < vessel.ballonet - Aerostatics.TRIM_NEAR -> -1
                        else -> 0
                    }
                }
                if (steered) return
                // Near the spot and hardly moving, it just holds its attitude.
                if (across.length < SLACK && flat.length < STILL * 2.0) {
                    control.throttle = 0.0
                    control.keepTrim = 0.0
                    desiredUp.setTo(up)
                    hold(vessel, null)
                    return
                }
                // The way through the air or water that gives the speed wanted over the ground. Nose
                // into it, then throttle, never flat out, since a low prop pitches the ship up.
                val through = scratch.setTo(wanted).apply { if (means == Means.GAS) subInPlace(wind) }
                val speed = through.length
                if (speed < STILL) {
                    control.throttle = 0.0
                    desiredUp.setTo(up)
                    hold(vessel, null)
                    return
                }
                through.mulInPlace(1.0 / speed)
                val along = (wanted.copy().subInPlace(flat)) dot through
                vessel.body.orientation.rotate(vessel.design.orientation.forward, forward)
                forward.addScaledInPlace(up, -(forward dot up))
                val facing = if (forward.length > 1e-6) ((forward dot through) / forward.length).coerceAtLeast(0.0) else 0.0
                control.keepTrim = (control.keepTrim + along * PUSH_TRIM_RATE * dt).coerceIn(0.0, PUSH_MOST)
                // Only push once it faces the way it's going, or it circles the spot.
                val ready = ((facing - FACING_FROM) / (1.0 - FACING_FROM)).coerceIn(0.0, 1.0)
                control.throttle = ((control.keepTrim + along * PUSH_GAIN).coerceIn(0.0, PUSH_MOST)) * ready
                desiredUp.setTo(up)
                hold(vessel, through.copy())
            }
        }
    }

    /**
     * Stability assist to hold the craft with its up along [desiredUp], and its nose on [nose] if
     * one's given, or on the heading it has.
     */
    private fun hold(vessel: Vessel, nose: Vec3?) {
        val orientation = vessel.design.orientation
        // From the attitude it's already holding, so the heading doesn't wander.
        val from = if (vessel.assistHolding) vessel.assistHeld else vessel.body.orientation
        quatFromTo(from.rotate(orientation.up, scratch), desiredUp, turnUp)
        turnUp.mulInPlace(from)
        turnUp.rotate(orientation.forward, forward)
        if (nose != null) {
            val flatNose = nose.copy().addScaledInPlace(desiredUp, -(nose dot desiredUp))
            if (flatNose.length > 1e-6) {
                quatFromTo(forward, flatNose.normalizeInPlace(), turnForward)
                turnUp.setTo(turnForward * turnUp)
            }
        }
        vessel.assistHeld.setTo(turnUp)
        vessel.assistHolding = true
        vessel.control.sasEnabled = true
    }

    companion object {
        /** How close an engine's push has to be to straight up the craft to count as holding it up. */
        const val LIFT_ALIGNED = 0.8

        /** m/s over the ground wanted per metre off the spot, and the most. */
        const val POSITION_GAIN = 0.25
        const val MOST_DRIFT = 6.0

        /** The same for something floating, which can only push and coasts: m/s per metre, and the most. */
        const val FLOAT_POSITION_GAIN = 0.05
        const val FLOAT_DRIFT = 1.5

        /** m/s² of push per m/s off the speed wanted, and the most the craft tips for it, in degrees. */
        const val SPEED_GAIN = 0.8
        const val MOST_TILT = 15.0

        /** Degrees the stick tips it at full deflection, flown by hand, and how fast the yaw turns it, rad/s. */
        const val MOST_HAND_TILT = 25.0
        const val HAND_YAW_RATE = 1.0

        /** m/s of climb wanted per metre off the height, and the most. */
        const val HEIGHT_GAIN = 0.5
        const val MOST_CLIMB = 3.0

        /** Throttle per m/s off the climb wanted, and how fast the trim creeps, a second per m/s. */
        const val CLIMB_GAIN = 0.12
        const val TRIM_RATE = 0.08

        /** Rotor throttle over this lets the ballonets out; under the other while rising takes air in. */
        const val GAS_OFFLOAD = 0.08
        const val GAS_IDLE = 0.02

        /** Throttle per m/s short along the way it's pushing, and the trim's creep. */
        const val PUSH_GAIN = 0.15
        const val PUSH_TRIM_RATE = 0.03

        /** The most throttle it pushes a floating craft with. */
        const val PUSH_MOST = 0.5

        /** How nearly it has to face the way it's going, as a cosine, before it pushes at all. */
        const val FACING_FROM = 0.85

        /** Metres off the spot a floating craft is left to drift before it's pushed back. */
        const val SLACK = 10.0

        /** Slower than this through the air or water wanted, in m/s, it doesn't push at all. */
        const val STILL = 0.2
    }
}
