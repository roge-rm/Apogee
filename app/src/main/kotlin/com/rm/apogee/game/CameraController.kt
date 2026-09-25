package com.rm.apogee.game

import com.rm.apogee.core.math.Quat
import com.rm.apogee.core.math.Vec3
import com.rm.apogee.core.math.quatLookAt
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

/**
 * Where "up" comes from when the camera builds its frame.
 */
enum class UpReference {
    /**
     * Away from the origin of the current frame - the planet's centre. Correct
     * in flight: a world-fixed camera looks fine on the launch pad and then
     * slowly rolls onto its side as the craft travels around the planet,
     * because "up" is a different direction ten degrees of longitude later.
     */
    RADIAL,

    /**
     * A fixed axis, [CameraController.fixedUp] - world +Y unless told
     * otherwise. Correct in the builder, where there is no planet and the
     * design sits near the origin, and where a horizontal design wants its
     * own +Z shown as up.
     *
     * Using RADIAL here is actively wrong, not merely arbitrary: a craft whose
     * parts hang below the origin has a centre at negative Y, so "away from the
     * origin" points *down* and the whole craft renders upside down.
     */
    FIXED,
}

/**
 * An orbit camera that stays with a craft.
 */
class CameraController(
    private val upReference: UpReference = UpReference.RADIAL,
    private val minDistance: Double = 5.0,
    private val maxDistance: Double = 2_000.0,
) {

    /** Which way is up under [UpReference.FIXED]. */
    val fixedUp = Vec3.unitY()

    /** Rotation around the craft, radians. */
    var yaw: Double = 0.0

    /** Elevation above the craft's horizon, radians. Clamped short of the poles. */
    var pitch: Double = 0.25
        set(value) {
            field = value.coerceIn(-PI / 2.0 + 0.05, PI / 2.0 - 0.05)
        }

    var distance: Double = 30.0
        set(value) {
            field = value.coerceIn(minDistance, maxDistance)
        }

    private val up = Vec3()
    private val carriedNorth = Vec3()
    private var carried = false
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
        // Build a local frame at the craft: up, plus two tangent directions to
        // swing the camera around in.
        when (upReference) {
            UpReference.RADIAL -> {
                up.setTo(target).normalizeInPlace()
                if (up.lengthSq < 0.5) up.setTo(Vec3.unitY())
            }
            UpReference.FIXED -> up.setTo(fixedUp)
        }

        // North carried on from last frame, straightened against the new up,
        // rather than taken from the planet's axis afresh: near a pole that
        // has to change to some other axis, and the swap turned the whole
        // view in one frame - twice, going over (Dan's video at 4x, flying
        // north across the polar cap).
        if (carried) {
            north.setTo(carriedNorth).addScaledInPlace(up, -(carriedNorth dot up))
        }
        if (!carried || north.lengthSq < 0.01) {
            north.setTo(Vec3.unitY())
            if (kotlin.math.abs(north dot up) > 0.99) north.setTo(Vec3.unitX())
        }
        east.setTo(north).crossInPlace(up).normalizeInPlace()
        north.setTo(up).crossInPlace(east).normalizeInPlace()
        carriedNorth.setTo(north)
        carried = true

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

    /**
     * Frames a craft of the given size, but only widens - never zooms back in.
     *
     * One-way so that a craft growing as it is assembled stays in view without
     * yanking the camera back every time the player deliberately zooms in to
     * place a small part.
     */
    fun frameAtLeast(craftSize: Double) {
        val wanted = (craftSize * 1.8 + 6.0).coerceIn(minDistance, maxDistance)
        if (wanted > distance) distance = wanted
    }

    /**
     * For a craft that has just become much smaller - a pod left from a
     * crash: comes in to frame it if the camera is now far too far out for
     * it, and otherwise leaves the player's zoom alone.
     */
    fun frameShrunk(craftSize: Double) {
        val wanted = (craftSize * 1.8 + 6.0).coerceIn(minDistance, maxDistance)
        if (distance > wanted * 2.0) distance = wanted
    }

    /** Frames a craft of this size, closer or further: back to the whole of it. */
    fun frameFor(craftSize: Double) {
        distance = (craftSize * 1.8 + 6.0).coerceIn(minDistance, maxDistance)
    }

    /** Snaps the distance to exactly frame something of this size. */
    fun frameExactly(size: Double) {
        distance = (size * 2.4).coerceIn(minDistance, maxDistance)
    }
}
