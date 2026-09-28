package com.rm.apogee.game

import com.rm.apogee.core.math.Quat
import com.rm.apogee.core.math.Vec3
import com.rm.apogee.core.math.quatLookAt
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin
import com.rm.apogee.platform.System

/**
 * Where "up" comes from when the camera builds its frame.
 */
enum class UpReference {
    /**
     * Away from the origin of the current frame, which is the planet's centre. This is right in
     * flight. A world-fixed camera looks fine on the launch pad and then slowly rolls onto its side
     * as the craft travels around the planet, because "up" is a different direction ten degrees of
     * longitude later.
     */
    RADIAL,

    /**
     * A fixed axis, [CameraController.fixedUp], which is world +Y unless told otherwise. This is
     * right in the builder, where there's no planet and the design sits near the origin, and where
     * a horizontal design wants its own +Z shown as up.
     *
     * Using RADIAL here is actually wrong, not just arbitrary. A craft whose parts hang below the
     * origin has a centre at negative Y, so "away from the origin" points *down* and the whole
     * craft draws upside down.
     */
    FIXED,
}

/**
 * How the flight camera follows the craft. The camera button on the flight screen goes through
 * them in turn, and C does on a keyboard.
 */
enum class CameraMode(val label: String) {
    /** Round the craft, keeping its compass direction as the craft turns under it. */
    FREE("Free"),

    /** Behind the craft, swinging round as it turns, so its way ahead is up the screen. */
    CHASE("Chase"),

    /** From the pilot's seat, looking out ahead, turning and rolling with the craft. */
    COCKPIT("Cockpit"),

    /** Fixed to the craft behind it, pitching and rolling with it. */
    LOCKED("Locked");

    fun next(): CameraMode = entries[(ordinal + 1) % entries.size]
}

/**
 * An orbit camera that stays with a craft, in one of the [CameraMode]s.
 */
class CameraController(
    private val upReference: UpReference = UpReference.RADIAL,
    private val minDistance: Double = 5.0,
    private val maxDistance: Double = 2_000.0,
) {

    /** Which way is up under [UpReference.FIXED]. */
    val fixedUp = Vec3.unitY()

    /** Rotation around the craft, in radians. */
    var yaw: Double = 0.0

    /** Elevation above the craft's horizon, in radians. Clamped short of the poles. */
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

    /** Changes the distance by a pinch factor. From the cockpit there's nothing to zoom. */
    fun zoomBy(factor: Double) {
        if (mode == CameraMode.COCKPIT) return
        distance /= factor
    }

    fun orbitBy(deltaYaw: Double, deltaPitch: Double) {
        when (mode) {
            CameraMode.FREE, CameraMode.LOCKED -> { yaw += deltaYaw; pitch += deltaPitch }
            // Looking round from behind, and it swings back once you let go.
            CameraMode.CHASE -> { lookYaw += deltaYaw; pitch += deltaPitch; lookedNanos = System.nanoTime() }
            // Turning your head, the other way from a drag, as if you'd pulled the view.
            CameraMode.COCKPIT -> {
                lookYaw -= deltaYaw
                lookPitch = (lookPitch - deltaPitch).coerceIn(-HEAD_PITCH, HEAD_PITCH)
                lookedNanos = System.nanoTime()
            }
        }
    }

    /** How it follows the craft. Switching leaves any looking round behind. */
    var mode: CameraMode = CameraMode.FREE
        set(value) {
            if (field == value) return
            field = value
            lookYaw = 0.0; lookPitch = 0.0
            chaseHeading = Double.NaN
        }

    // Looking round in chase and from the cockpit, eased back to straight ahead after a moment.
    private var lookYaw = 0.0
    private var lookPitch = 0.0
    private var lookedNanos = 0L
    private var chaseHeading = Double.NaN
    private var solvedNanos = 0L
    private val craftUp = Vec3()
    private val craftAhead = Vec3()
    private val craftRight = Vec3()
    private val look = Vec3()

    /**
     * Places the camera for [mode] on a craft whose middle is at [target] (attractor frame), turned
     * [craft], with [forward] and [upward] its own ahead and up in its design's axes, and [seat]
     * where its pilot sits (for the cockpit), or null to use the middle.
     */
    fun solve(
        target: Vec3, craft: Quat, forward: Vec3, upward: Vec3, seat: Vec3?,
        outPosition: Vec3, outRotation: Quat,
    ) {
        val now = System.nanoTime()
        val dt = if (solvedNanos == 0L) 0.0 else ((now - solvedNanos) / 1e9).coerceIn(0.0, 0.1)
        solvedNanos = now
        // Let go a moment, and the view eases back to straight ahead.
        if (now - lookedNanos > LOOK_HOLD_NANOS) {
            val back = kotlin.math.exp(-dt / LOOK_EASE)
            lookYaw *= back; lookPitch *= back
        }
        when (mode) {
            CameraMode.FREE -> solve(target, outPosition, outRotation)
            CameraMode.CHASE -> {
                frame(target)
                // Its heading along the ground: the nose, or for a craft built standing up, which
                // way it's leaning. Nearly straight up, it keeps the heading it had.
                craft.rotate(if (upward.y > 0.5) upward else forward, craftAhead)
                craftAhead.addScaledInPlace(up, -(craftAhead dot up))
                if (craftAhead.length > HEADING_LEAST) {
                    val behind = kotlin.math.atan2(-(craftAhead dot north), -(craftAhead dot east))
                    chaseHeading = if (chaseHeading.isNaN()) behind
                    else chaseHeading + wrap(behind - chaseHeading) * (1.0 - kotlin.math.exp(-dt / CHASE_SWING))
                }
                if (chaseHeading.isNaN()) chaseHeading = yaw
                yaw = chaseHeading + lookYaw
                place(target, outPosition, outRotation)
            }
            CameraMode.LOCKED -> {
                craft.rotate(upward, craftUp).normalizeInPlace()
                craft.rotate(forward, craftAhead).normalizeInPlace()
                craftRight.setTo(craftAhead).crossInPlace(craftUp)
                // Behind it, swung round its up by the yaw, and up by the pitch, all in its axes.
                look.setTo(craftAhead).mulInPlace(-cos(yaw)).addScaledInPlace(craftRight, sin(yaw))
                look.mulInPlace(cos(pitch)).addScaledInPlace(craftUp, sin(pitch)).mulInPlace(distance)
                outPosition.setTo(target).addInPlace(look)
                look.negateInPlace()
                quatLookAt(look, craftUp, outRotation)
            }
            CameraMode.COCKPIT -> {
                // A rocket's pilot looks up its nose, and anyone else out ahead.
                val lying = upward.y < 0.5
                craft.rotate(if (lying) upward else forward, craftUp).normalizeInPlace()
                craft.rotate(if (lying) forward else upward, craftAhead).normalizeInPlace()
                craftRight.setTo(craftAhead).crossInPlace(craftUp).normalizeInPlace()
                look.setTo(craftAhead).mulInPlace(cos(lookYaw)).addScaledInPlace(craftRight, sin(lookYaw))
                look.mulInPlace(cos(lookPitch)).addScaledInPlace(craftUp, sin(lookPitch))
                outPosition.setTo(seat ?: target)
                quatLookAt(look, craftUp, outRotation)
            }
        }
    }

    /** [angle] brought within half a turn either way. */
    private fun wrap(angle: Double): Double {
        var a = angle % (2.0 * PI)
        if (a > PI) a -= 2.0 * PI
        if (a < -PI) a += 2.0 * PI
        return a
    }

    /**
     * Places the camera relative to [target], which is in the attractor's frame, and aims it back
     * at the craft.
     *
     * @param outPosition receives the camera position.
     * @param outRotation receives the camera orientation.
     */
    fun solve(target: Vec3, outPosition: Vec3, outRotation: Quat) {
        frame(target)
        place(target, outPosition, outRotation)
    }

    /** The frame at [target]: up, and east and north to swing the camera round in. */
    private fun frame(target: Vec3) {
        // Build a local frame at the craft: up, plus two tangent directions to swing the camera
        // around in.
        when (upReference) {
            UpReference.RADIAL -> {
                up.setTo(target).normalizeInPlace()
                if (up.lengthSq < 0.5) up.setTo(Vec3.unitY())
            }
            UpReference.FIXED -> up.setTo(fixedUp)
        }

        // North carried on from the last frame and straightened against the new up, instead of
        // taken from the planet's axis again. Near a pole that has to switch to some other axis,
        // and the swap turned the whole view in one frame, twice on the way over. I caught it on
        // video at 4x, flying north across the polar cap.
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
    }

    /** The camera at [yaw], [pitch] and [distance] round [target], in the frame, looking back at it. */
    private fun place(target: Vec3, outPosition: Vec3, outRotation: Quat) {
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
     * Frames a craft of the given size, but only widens. It never zooms back in.
     *
     * It only goes one way so that a craft growing as it's put together stays in view, without
     * yanking the camera back every time the player zooms in on purpose to place a small part.
     */
    fun frameAtLeast(craftSize: Double) {
        val wanted = (craftSize * 1.8 + 6.0).coerceIn(minDistance, maxDistance)
        if (wanted > distance) distance = wanted
    }

    /**
     * For a craft that has just got much smaller, like a pod left over from a crash. It comes in to
     * frame it if the camera is now far too far out for it, and otherwise leaves the player's zoom
     * alone.
     */
    fun frameShrunk(craftSize: Double) {
        val wanted = (craftSize * 1.8 + 6.0).coerceIn(minDistance, maxDistance)
        if (distance > wanted * 2.0) distance = wanted
    }

    /** Frames a craft of this size, closer or further, so it's back to the whole of it. */
    fun frameFor(craftSize: Double) {
        distance = (craftSize * 1.8 + 6.0).coerceIn(minDistance, maxDistance)
    }

    /** Snaps the distance to exactly frame something of this size. */
    fun frameExactly(size: Double) {
        distance = (size * 2.4).coerceIn(minDistance, maxDistance)
    }

    private companion object {
        /** How long a look round is held after letting go, and how quickly it then eases back. */
        const val LOOK_HOLD_NANOS = 1_500_000_000L
        const val LOOK_EASE = 0.5

        /** How quickly the chase camera swings round behind a turning craft, in seconds. */
        const val CHASE_SWING = 0.6

        /** Below this, a craft's heading along the ground is too steep to follow. */
        const val HEADING_LEAST = 0.2

        /** How far up or down you can look from the cockpit, in radians. */
        const val HEAD_PITCH = 1.3
    }
}
