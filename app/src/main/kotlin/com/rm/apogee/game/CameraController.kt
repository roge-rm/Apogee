package com.rm.apogee.game

import com.rm.apogee.core.math.Quat
import com.rm.apogee.core.math.Vec3
import com.rm.apogee.core.math.quatLookAt
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

/**
 * An orbit camera that stays with a craft.
 *
 * Orientation is built relative to the craft's *local up* - the direction away
 * from the planet's centre - rather than a fixed world axis. A world-fixed
 * camera looks fine on the launch pad and then slowly rolls onto its side as
 * the craft travels around the planet, because "up" is a different direction
 * ten degrees of longitude later.
 */
class CameraController {

    /** Rotation around the craft, radians. */
    var yaw: Double = 0.0

    /** Elevation above the craft's horizon, radians. Clamped short of the poles. */
    var pitch: Double = 0.25
        set(value) {
            field = value.coerceIn(-PI / 2.0 + 0.05, PI / 2.0 - 0.05)
        }

    var distance: Double = 30.0
        set(value) {
            field = value.coerceIn(MIN_DISTANCE, MAX_DISTANCE)
        }

    private val up = Vec3()
    private val east = Vec3()
    private val north = Vec3()
    private val offset = Vec3()

    /** Adjusts distance by a pinch factor. */
    fun zoomBy(factor: Double) {
        distance /= factor
    }

    fun orbitBy(deltaYaw: Double, deltaPitch: Double) {
        yaw += deltaYaw
        pitch += deltaPitch
    }

    /**
     * Places the camera relative to [target], which is in the attractor's
     * frame, and aims it back at the craft.
     *
     * @param outPosition receives the camera position.
     * @param outRotation receives the camera orientation.
     */
    fun solve(target: Vec3, outPosition: Vec3, outRotation: Quat) {
        // Build a local frame at the craft: up away from the planet, plus two
        // tangent directions to swing the camera around in.
        up.setTo(target).normalizeInPlace()
        if (up.lengthSq < 0.5) up.setTo(Vec3.unitY())

        north.setTo(Vec3.unitY())
        if (kotlin.math.abs(north dot up) > 0.99) north.setTo(Vec3.unitX())
        east.setTo(north).crossInPlace(up).normalizeInPlace()
        north.setTo(up).crossInPlace(east).normalizeInPlace()

        val horizontal = cos(pitch) * distance
        val vertical = sin(pitch) * distance

        offset.setTo(
            east.x * cos(yaw) * horizontal + north.x * sin(yaw) * horizontal + up.x * vertical,
            east.y * cos(yaw) * horizontal + north.y * sin(yaw) * horizontal + up.y * vertical,
            east.z * cos(yaw) * horizontal + north.z * sin(yaw) * horizontal + up.z * vertical,
        )

        outPosition.setTo(target).addInPlace(offset)
        // Look back at the craft, rolled so the planet's up stays up on screen.
        offset.negateInPlace()
        quatLookAt(offset, up, outRotation)
    }

    /** Frames a craft of the given size sensibly. */
    fun frame(craftSize: Double) {
        distance = (craftSize * 2.5).coerceIn(MIN_DISTANCE, MAX_DISTANCE)
    }

    private companion object {
        const val MIN_DISTANCE = 5.0
        const val MAX_DISTANCE = 2_000.0
    }
}
