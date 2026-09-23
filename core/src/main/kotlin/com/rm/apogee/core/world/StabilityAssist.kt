package com.rm.apogee.core.world

import com.rm.apogee.core.craft.Vessel
import com.rm.apogee.core.math.Quat
import com.rm.apogee.core.math.Vec3
import kotlin.math.atan2
import kotlin.math.sqrt

/**
 * SAS as attitude hold: let go of the stick and the craft stays pointing
 * where it was.
 *
 * Damping rotation - which is all SAS used to do - is not enough for anything
 * with wings. The aerodynamic model acts on airflow *across* the fuselage, so
 * at zero angle of attack every surface is silent, and a stable aircraft
 * weathervanes back to exactly that: no angle of attack, no lift, and the
 * nose drops the moment the stick is released. No placement of wings or
 * centre of mass changes it. What does is the pilot holding the nose where
 * they want it, and that is what this does for them - through the same
 * elevons, gimbals and reaction wheels a thumb uses, written as
 * [com.rm.apogee.core.craft.ControlState.assistPitch] and its siblings.
 *
 * Airborne only. On the ground the ground decides a craft's attitude, and a
 * rover whose SAS held its orientation would fight every bump it drove over.
 *
 * The hold is in inertial axes. Over a flight of minutes the local horizon
 * turns by a degree or so under an aircraft; over an orbit, inertial is what
 * a spacecraft wants held anyway.
 *
 * PID rather than the [AttitudeController]'s PD: an aircraft needs a steady
 * elevator to hold a steady angle of attack, and a controller with no
 * integral term only produces one by settling short of the target - the test
 * pilot held seven and a half degrees when asked for ten.
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

    /**
     * @param direction when holding a navball marker, where the nose should
     *   point, inertial; null to hold the attitude at release.
     */
    fun update(vessel: Vessel, dt: Double, direction: Vec3? = null) {
        val control = vessel.control
        val holding = control.sasEnabled && !control.hasAttitudeInput && !vessel.touchingGround
        if (!holding) {
            release(vessel)
            return
        }

        val body = vessel.body
        if (!vessel.assistHolding) {
            vessel.assistHeld.setTo(body.orientation)
            vessel.assistIntegral.setZero()
            vessel.assistHolding = true
        }
        if (direction != null) {
            // A marker moves as the craft does: the attitude to hold is the
            // one that turns the nose onto it by the shortest way, keeping
            // whatever roll the craft has - so it swings onto prograde
            // without spinning about it.
            vessel.forward(nose)
            com.rm.apogee.core.math.quatFromTo(nose, direction, turn)
            vessel.assistHeld.setTo(turn).mulInPlace(body.orientation)
        }

        // The turn from where the craft points to where it should, in its own
        // axes: conj(current) * held. Shortest way round.
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

        // Integral clamped, so a craft held against something it cannot
        // overcome does not wind up a command it then spends seconds
        // unwinding once it can.
        val integral = vessel.assistIntegral
        integral.addScaledInPlace(errorBody, integralGain * dt)
        integral.setTo(
            integral.x.coerceIn(-INTEGRAL_LIMIT, INTEGRAL_LIMIT),
            integral.y.coerceIn(-INTEGRAL_LIMIT, INTEGRAL_LIMIT),
            integral.z.coerceIn(-INTEGRAL_LIMIT, INTEGRAL_LIMIT),
        )

        // X pitches, Y rolls, Z yaws - the reaction wheels' convention.
        control.assistPitch = command(errorBody.x, rateBody.x, integral.x)
        control.assistRoll = command(errorBody.y, rateBody.y, integral.y)
        control.assistYaw = command(errorBody.z, rateBody.z, integral.z)
    }

    private fun release(vessel: Vessel) {
        vessel.assistHolding = false
        vessel.control.assistPitch = 0.0
        vessel.control.assistYaw = 0.0
        vessel.control.assistRoll = 0.0
    }

    private fun command(error: Double, rate: Double, integral: Double): Double =
        (proportionalGain * error - derivativeGain * rate + integral).coerceIn(-1.0, 1.0)

    private companion object {
        const val INTEGRAL_LIMIT = 0.6
    }
}
