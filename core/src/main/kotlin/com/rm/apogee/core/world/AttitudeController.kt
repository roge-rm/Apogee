package com.rm.apogee.core.world

import com.rm.apogee.core.craft.Vessel
import com.rm.apogee.core.math.Vec3
import kotlin.math.atan2

/**
 * Points a craft where it's told to, using only the controls a player has.
 *
 * It drives [Vessel.control] on purpose instead of setting the orientation directly. An autopilot
 * that writes the rotation would be able to do things no player could, like turning instantly or
 * holding an attitude a craft doesn't have the authority to hold, and it would stop being a test of
 * the control path at exactly the moment it was most useful as one. Everything here goes through
 * the same gimbal and reaction wheels your thumb does.
 *
 * It's a PD controller: proportional to the angle error, damped by the turn rate. The damping term
 * is what stops it swinging past the target and back, which a purely proportional controller on a
 * low-drag body always does.
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

        // The rotation taking the current heading to the one we want, as an axis-angle vector in
        // world space.
        errorAxis.setTo(forward).crossInPlace(desiredForward)
        val sine = errorAxis.length
        val cosine = forward dot desiredForward
        val angle = atan2(sine, cosine)

        if (sine > 1e-9) {
            errorAxis.mulInPlace(angle / sine)
        } else if (cosine < 0.0) {
            // Pointing exactly backwards, the error axis doesn't have a clear direction, so pick
            // one. Any axis at right angles will start the turn, and the next tick will have a
            // clear axis to carry on with.
            errorAxis.setTo(0.0, 0.0, angle)
        } else {
            errorAxis.setZero()
        }

        // Both the error and the rate are wanted in body axes, because that's what the controls act
        // on: X pitches, Y rolls, Z yaws.
        vessel.body.orientation.inverseRotate(errorAxis, errorBody)
        vessel.body.orientation.inverseRotate(vessel.body.angularVelocity, rateBody)

        vessel.control.pitch = command(errorBody.x, rateBody.x)
        vessel.control.roll = command(errorBody.y, rateBody.y)
        vessel.control.yaw = command(errorBody.z, rateBody.z)
    }

    private fun command(error: Double, rate: Double): Double =
        (proportionalGain * error - derivativeGain * rate).coerceIn(-1.0, 1.0)
}
