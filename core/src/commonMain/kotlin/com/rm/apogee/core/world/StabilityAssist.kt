package com.rm.apogee.core.world

import com.rm.apogee.core.craft.Vessel
import com.rm.apogee.core.math.Quat
import com.rm.apogee.core.math.Vec3
import kotlin.math.atan2
import kotlin.math.sqrt
import com.rm.apogee.core.math.Math

/**
 * SAS as attitude hold: let go of the stick and the craft stays pointing where it was.
 *
 * Damping rotation, which is all SAS used to do, isn't enough for anything with wings. The
 * aerodynamic model acts on airflow *across* the fuselage, so at zero angle of attack every surface
 * is silent, and a stable aircraft weathervanes back to exactly that: no angle of attack, no lift,
 * and the nose drops the moment you let go of the stick. No placement of wings or centre of mass
 * changes that. What does is the pilot holding the nose where they want it, and that's what this
 * does for you, through the same elevons, gimbals and reaction wheels your thumb uses, written as
 * [com.rm.apogee.core.craft.ControlState.assistPitch] and its siblings.
 *
 * It only works in the air. On the ground the ground decides a craft's attitude, and a rover whose
 * SAS held its orientation would fight every bump it drove over.
 *
 * The hold is in inertial axes. Over a flight of minutes the local horizon turns by a degree or so
 * under an aircraft, and in orbit, inertial is what a spacecraft wants held anyway.
 *
 * It's PID instead of the [AttitudeController]'s PD. An aircraft needs a steady elevator to hold a
 * steady angle of attack, and a controller with no integral term only gets one by settling short of
 * the target. The test pilot held seven and a half degrees when asked for ten.
 *
 * Its commands move no faster than [SLEW] a second, the same as a real actuator. Slammed from one
 * end to the other in a tick, a craft with a lot of control (full-span elevons at speed) overshot
 * within the tick, got slammed back the next, and chattered at the tick rate. Its wings flipped
 * sixty times a second between a hundred kilonewtons up and down, until the fuselage tore off.
 *
 * Afloat it acts as a helmsman instead. It holds the heading, and brings the hull back level after
 * a turn instead of keeping whatever heel it had when you let go of the wheel, and it leaves the
 * pitch to the sea.
 */
class StabilityAssist(
    private val proportionalGain: Double = 5.0,
    private val derivativeGain: Double = 4.0,
    private val integralGain: Double = 1.5,
) {
    private val error = Quat()
    private val nose = Vec3()
    private val turn = Quat()
    private val errorBody = Vec3()
    private val rateBody = Vec3()
    private val up = Vec3()
    private val level = Quat()
    private val heading = Vec3()

    /**
     * @param direction when holding a navball marker, where the nose should point, inertial. Null
     *     to hold the attitude at release.
     */
    fun update(vessel: Vessel, dt: Double, direction: Vec3? = null) {
        val control = vessel.control
        val body = vessel.body
        // Afloat, it's a helmsman, keeping the deck level even while the wheel is being turned, and
        // holding the heading once it's let go.
        //
        // Under the water it's a pilot again, and holds pitch too. A submarine left to pitch
        // however the sea takes it goes down nose first.
        val afloat = control.sasEnabled && vessel.buoyed && !vessel.submerged && !vessel.touchingGround && direction == null
        control.assistLevelling = afloat
        if (afloat && control.stickOverrides) {
            vessel.assistHeld.setTo(body.orientation)
            levelled(vessel)
            // Taken again when the wheel is let go, meaning the heading at that moment.
            vessel.assistHolding = false
            vessel.assistIntegral.setZero()
            errorOf(vessel)
            control.assistPitch = 0.0; control.assistYaw = 0.0
            control.assistRoll = slew(control.assistRoll, command(errorBody.y, rateBody.y, 0.0), SLEW * dt)
            return
        }
        val holding = control.sasEnabled && !control.stickOverrides && !vessel.touchingGround
        if (!holding) {
            release(vessel)
            return
        }

        if (!vessel.assistHolding) {
            vessel.assistHeld.setTo(body.orientation)
            vessel.assistIntegral.setZero()
            vessel.assistHolding = true
        }
        if (afloat) levelled(vessel)
        if (direction != null) {
            // A marker moves as the craft does. The attitude to hold is the one that turns the nose
            // onto it by the shortest way while keeping whatever roll the craft has, so it swings
            // onto prograde without spinning around it.
            vessel.forward(nose)
            com.rm.apogee.core.math.quatFromTo(nose, direction, turn)
            vessel.assistHeld.setTo(turn).mulInPlace(body.orientation)
        }

        errorOf(vessel)

        // The integral is clamped, so a craft held against something it can't overcome doesn't wind
        // up a command it then spends seconds unwinding once it can.
        val integral = vessel.assistIntegral
        integral.addScaledInPlace(errorBody, integralGain * dt)
        integral.setTo(
            integral.x.coerceIn(-INTEGRAL_LIMIT, INTEGRAL_LIMIT),
            integral.y.coerceIn(-INTEGRAL_LIMIT, INTEGRAL_LIMIT),
            integral.z.coerceIn(-INTEGRAL_LIMIT, INTEGRAL_LIMIT),
        )

        // X pitches, Y rolls, Z yaws, the reaction wheels' convention.
        val step = SLEW * dt
        control.assistPitch = if (afloat) 0.0 else slew(control.assistPitch, command(errorBody.x, rateBody.x, integral.x), step)
        control.assistRoll = slew(control.assistRoll, command(errorBody.y, rateBody.y, integral.y), step)
        control.assistYaw = slew(control.assistYaw, command(errorBody.z, rateBody.z, integral.z), step)
        if (afloat) integral.x = 0.0
    }

    /**
     * The turn from where [vessel] points to where it should point, in its own axes, into
     * [errorBody], and its spin into [rateBody].
     */
    private fun errorOf(vessel: Vessel) {
        val body = vessel.body
        // The turn from where the craft points to where it should, in its own axes: conj(current) *
        // held. The shortest way round.
        error.setTo(body.orientation).conjugateInPlace().mulInPlace(vessel.assistHeld)
        if (error.w < 0.0) error.setTo(-error.x, -error.y, -error.z, -error.w)
        val sine = sqrt(error.x * error.x + error.y * error.y + error.z * error.z)
        if (sine > 1e-12) {
            val angle = 2.0 * atan2(sine, error.w)
            errorBody.setTo(error.x, error.y, error.z).mulInPlace(angle / sine)
        } else {
            errorBody.setZero()
        }
        body.orientation.inverseRotate(body.angularVelocity, rateBody)
    }

    private fun slew(from: Double, to: Double, step: Double): Double = from + (to - from).coerceIn(-step, step)

    /**
     * The attitude a boat's helmsman holds: nose on the heading it had when the wheel was let go,
     * and deck level with the horizon here. It's worked out again every tick, because the horizon
     * turns as the boat moves over the planet and the planet turns under it.
     */
    private fun levelled(vessel: Vessel) {
        val body = vessel.body
        val orientation = vessel.design.orientation
        up.setTo(body.position).normalizeInPlace()
        vessel.assistHeld.rotate(orientation.forward, heading)
        heading.addScaledInPlace(up, -(heading dot up))
        if (heading.lengthSq < 1e-9) return
        heading.normalizeInPlace()
        com.rm.apogee.core.math.quatFromTo(orientation.up, up, level)
        level.rotate(orientation.forward, nose)
        nose.addScaledInPlace(up, -(nose dot up)).normalizeInPlace()
        val swing = if ((nose dot heading) < -0.999999) Quat.fromAxisAngle(up, Math.PI) else com.rm.apogee.core.math.quatFromTo(nose, heading)
        vessel.assistHeld.setTo(swing).mulInPlace(level)
    }

    /** Holds nothing and commands nothing, because there's no power to hold with. */
    fun idle(vessel: Vessel) = release(vessel)

    private fun release(vessel: Vessel) {
        vessel.assistHolding = false
        vessel.control.assistLevelling = false
        vessel.control.assistPitch = 0.0
        vessel.control.assistYaw = 0.0
        vessel.control.assistRoll = 0.0
    }

    private fun command(error: Double, rate: Double, integral: Double): Double =
        (proportionalGain * error - derivativeGain * rate + integral).coerceIn(-1.0, 1.0)

    private companion object {
        const val INTEGRAL_LIMIT = 0.6

        /**
         * How fast a command can move, as a share of full travel per second. End to end in a fifth
         * of a second.
         */
        const val SLEW = 10.0
    }
}
