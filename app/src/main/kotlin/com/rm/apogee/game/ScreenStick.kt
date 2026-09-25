package com.rm.apogee.game

import com.rm.apogee.core.math.Quat
import com.rm.apogee.core.math.Vec3
import kotlin.math.abs

/**
 * The stick read by the screen, for a craft built standing up: a rocket is
 * round, and turned about its own axes the stick went a different way on
 * screen every flight (Dan: on a symmetrical rocket I don't know which
 * control goes which way until I test them).
 *
 * Right tips the nose toward the screen's right, from wherever it points.
 * Up tips it as if the thumb held the craft itself: a nose standing up the
 * screen goes away from the camera, one pointing into the screen goes down.
 */
object ScreenStick {

    /**
     * [up] and [right] from the stick, -1..1, as the craft's own pitch (about
     * its X) and yaw (about its Z), for a camera turned [camera] and a craft
     * turned [craft], both in the same frame. Scaled back together, not
     * clipped apart, when one would pass 1.
     */
    fun attitude(camera: Quat, craft: Quat, up: Double, right: Double): Pair<Double, Double> {
        val screenRight = camera.rotate(Vec3.unitX(), Vec3())
        val nose = craft.rotate(Vec3.unitY(), Vec3())
        // Up turns about the camera's left; right about nose x right, which
        // carries the nose toward the screen's right and fades only as the
        // nose itself comes to point right.
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
