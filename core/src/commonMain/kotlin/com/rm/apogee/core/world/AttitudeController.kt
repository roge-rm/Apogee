package com.rm.apogee.core.world

import com.rm.apogee.core.craft.Vessel
import com.rm.apogee.core.math.Vec3
import kotlin.math.atan2

/**
 * Points a craft where it's told, using only the controls a player has.
 *
 * It drives [Vessel.control] rather than setting the orientation, so it can't turn instantly or
 * hold an attitude the craft lacks authority for, and it tests the real control path through the
 * same gimbal and reaction wheels your thumb does.
 *
 * A PD controller: proportional to the angle error, damped by the turn rate so it doesn't swing
 * past the target on a low-drag body.
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

        // The rotation from the current heading to the wanted one, as an axis-angle vector in world
        // space.
        errorAxis.setTo(forward).crossInPlace(desiredForward)
        val sine = errorAxis.length
        val cosine = forward dot desiredForward
        val angle = atan2(sine, cosine)

        if (sine > 1e-9) {
            errorAxis.mulInPlace(angle / sine)
        } else if (cosine < 0.0) {
            // Pointing exactly backwards there's no clear axis, so pick any at right angles. The
            // next tick will have a clear one.
            errorAxis.setTo(0.0, 0.0, angle)
        } else {
            errorAxis.setZero()
        }

        // Error and rate in body axes, since that's what the controls act on: X pitches, Y rolls,
        // Z yaws.
        vessel.body.orientation.inverseRotate(errorAxis, errorBody)
        vessel.body.orientation.inverseRotate(vessel.body.angularVelocity, rateBody)

        vessel.control.pitch = command(errorBody.x, rateBody.x)
        vessel.control.roll = command(errorBody.y, rateBody.y)
        vessel.control.yaw = command(errorBody.z, rateBody.z)
    }

    private fun command(error: Double, rate: Double): Double =
        (proportionalGain * error - derivativeGain * rate).coerceIn(-1.0, 1.0)
}
