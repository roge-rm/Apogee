package com.rm.apogee.game

import com.rm.apogee.core.math.Quat
import com.rm.apogee.core.math.Vec3
import kotlin.math.abs

/**
 * The stick read by the screen, for a craft built standing up. A rocket is round, and when the
 * stick turned it about its own axes, it went a different way on screen every flight. On a
 * symmetrical rocket I couldn't tell which control went which way until I tested them.
 *
 * Right tips the nose toward the screen's right, from wherever it points. Up tips it as if your
 * thumb were holding the craft itself, so a nose standing up the screen goes away from the camera,
 * and one pointing into the screen goes down.
 */
object ScreenStick {

    /**
     * [up] and [right] from the stick, -1..1, as the craft's own pitch (about its X) and yaw (about
     * its Z), for a camera turned [camera] and a craft turned [craft], both in the same frame.
     * They're scaled back together, not clipped separately, when one would go past 1.
     */
    fun attitude(camera: Quat, craft: Quat, up: Double, right: Double): Pair<Double, Double> {
        val screenRight = camera.rotate(Vec3.unitX(), Vec3())
        val nose = craft.rotate(Vec3.unitY(), Vec3())
        // Up turns about the camera's left, and right about nose x right, which carries the nose
        // toward the screen's right and only fades as the nose itself comes to point right.
        val turn = Vec3().addScaledInPlace(screenRight, -up)
            .addScaledInPlace(Vec3().setTo(nose).crossInPlace(screenRight), right)
        val body = craft.inverseRotate(turn, Vec3())
        var pitch = body.x
        var yaw = body.z
        val most = maxOf(abs(pitch), abs(yaw))
        if (most > 1.0) { pitch /= most; yaw /= most }
        return pitch to yaw
    }
}
