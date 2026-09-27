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

/**
 * The keeper core flying: it holds a craft over a spot on the ground, at a height, where it was
 * when it was asked to, using whatever the craft has.
 *
 * - Something that lifts it (rotors, or engines pointing down, like a hovering lander): it has the
 *   throttle, on the climb it wants for the height, with a trim that creeps to whatever holds it
 *   level. It tips the craft toward the spot through stability assist, the way a drone pilot does,
 *   no more than [MOST_TILT].
 * - Gas cells and nothing to lift it: the ballonets hold the height, and whatever pushes it
 *   along (props, fans, an outboard) points into the wind and holds it over the spot.
 * - Afloat, it's the same without the height.
 *
 * Steered by hand, it lets the stick fly the craft over the ground and keeps only the height, and
 * holds wherever it's let go.
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

        // Over the spot: the speed over the ground wanted, toward it and slowing as it gets there.
        // Something floating can't brake, only turn and push, and it coasts a long way, so it
        // comes back gently.
        val floating = means != Means.LIFT
        wanted.setTo(across).mulInPlace(if (floating) FLOAT_POSITION_GAIN else POSITION_GAIN)
        val most = if (floating) FLOAT_DRIFT else MOST_DRIFT
        if (wanted.length > most) wanted.mulInPlace(most / wanted.length)

        when (means) {
            Means.LIFT -> {
                val climbWanted = (below * HEIGHT_GAIN).coerceIn(-MOST_CLIMB, MOST_CLIMB)
                control.keepTrim = (control.keepTrim + (climbWanted - climb) * TRIM_RATE * dt).coerceIn(0.0, 1.0)
                control.throttle = (control.keepTrim + (climbWanted - climb) * CLIMB_GAIN).coerceIn(0.0, 1.0)
                // With gas cells too, the ballonets take the weight off the rotors slowly: let out
                // while they're working hard, and taken in while it still rises with them idle.
                if (vessel.defs.any { it.hasModule<LiftGas>() }) {
                    control.ballast = when {
                        control.throttle > GAS_OFFLOAD -> -1
                        control.throttle < GAS_IDLE && climb > climbWanted -> 1
                        else -> 0
                    }
                }
                if (steered) return
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
                // Near enough over the spot and hardly moving, it just holds its attitude and lets
                // the ballonets or the water do the rest.
                if (across.length < SLACK && flat.length < STILL * 2.0) {
                    control.throttle = 0.0
                    control.keepTrim = 0.0
                    desiredUp.setTo(up)
                    hold(vessel, null)
                    return
                }
                // Through the air (or water) it has to go to make the speed wanted over the ground:
                // nose into it first, and then the throttle on how fast, never flat out, since a
                // propeller hung below the middle pitches the whole ship up.
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
                control.throttle = ((control.keepTrim + along * PUSH_GAIN).coerceIn(0.0, PUSH_MOST)) * facing * facing
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
        // From the attitude it's already holding, so its heading stays put instead of wandering
        // with whatever the craft happens to point at.
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

        /** m/s of climb wanted per metre off the height, and the most. */
        const val HEIGHT_GAIN = 0.5
        const val MOST_CLIMB = 3.0

        /** Throttle per m/s off the climb wanted, and how fast the trim creeps, a second per m/s. */
        const val CLIMB_GAIN = 0.12
        const val TRIM_RATE = 0.08

        /**
         * Rotors working harder than this share let the ballonets out, and idling under the other
         * with the craft still rising take air in.
         */
        const val GAS_OFFLOAD = 0.08
        const val GAS_IDLE = 0.02

        /** Throttle per m/s short along the way it's pushing, and the trim's creep. */
        const val PUSH_GAIN = 0.15
        const val PUSH_TRIM_RATE = 0.03

        /** The most throttle it pushes a floating craft with. */
        const val PUSH_MOST = 0.5

        /** Metres off the spot a floating craft is left to drift before it's pushed back. */
        const val SLACK = 10.0

        /** Slower than this through the air or water wanted, in m/s, it doesn't push at all. */
        const val STILL = 0.2
    }
}
