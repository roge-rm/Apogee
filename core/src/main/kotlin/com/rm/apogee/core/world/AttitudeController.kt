package com.rm.apogee.core.world

import com.rm.apogee.core.craft.Vessel
import com.rm.apogee.core.math.Vec3
import kotlin.math.atan2

/**
 * Points a craft where it is told, using only the controls a player has.
 *
 * Deliberately drives [Vessel.control] rather than setting orientation
 * directly. An autopilot that writes the rotation would be able to do things no
 * player could - turn instantly, hold an attitude a craft has no authority to
 * hold - and would stop being a test of the control path at the moment it was
 * most useful as one. Everything here goes through the same gimbal and reaction
 * wheels a thumb does.
 *
 * A PD controller: proportional to angular error, damped by angular rate. The
 * damping term is what stops it oscillating past the target and back, which a
 * pure proportional controller on a low-drag body always will.
 */
class AttitudeController(
    private val proportionalGain: Double = 5.0,
    private val derivativeGain: Double = 4.0,
) {
    private val forward = Vec3()
    private val errorAxis = Vec3()
    private val errorBody = Vec3()
    private val rateBody = Vec3()

    /** Steers [vessel] so its nose points along [desiredForward]. */
    fun steer(vessel: Vessel, desiredForward: Vec3) {
        vessel.forward(forward)

        // Rotation taking the current heading to the desired one, as an
        // axis-angle vector in world space.
        errorAxis.setTo(forward).crossInPlace(desiredForward)
        val sine = errorAxis.length
        val cosine = forward dot desiredForward
        val angle = atan2(sine, cosine)

        if (sine > 1e-9) {
            errorAxis.mulInPlace(angle / sine)
        } else if (cosine < 0.0) {
            // Pointing exactly backwards: the error axis is degenerate, so pick
            // one. Any perpendicular will start the turn, and the next tick
            // will have a well-defined axis to continue on.
            errorAxis.setTo(0.0, 0.0, angle)
        } else {
            errorAxis.setZero()
        }

        // Both error and rate are wanted in body axes, because that is what the
        // controls act on: X pitches, Y rolls, Z yaws.
        vessel.body.orientation.inverseRotate(errorAxis, errorBody)
        vessel.body.orientation.inverseRotate(vessel.body.angularVelocity, rateBody)

        vessel.control.pitch = command(errorBody.x, rateBody.x)
        vessel.control.roll = command(errorBody.y, rateBody.y)
        vessel.control.yaw = command(errorBody.z, rateBody.z)
    }

    private fun command(error: Double, rate: Double): Double =
        (proportionalGain * error - derivativeGain * rate).coerceIn(-1.0, 1.0)
}
