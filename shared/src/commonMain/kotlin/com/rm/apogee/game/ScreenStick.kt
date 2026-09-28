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

    /**
     * The stick read by the screen for a craft that flies level, a helicopter or a drone: it tips
     * over toward where the stick points, and flies that way. Up is away from the camera along the
     * ground and right is the camera's right, whichever way the craft is facing. [turn] (the roll
     * buttons) turns it about its own up, right for positive. The answer is the turn in the
     * craft's own axes, x pitch, y roll and z yaw, into [out], for a camera turned [camera], a craft
     * turned [craft] with [craftUp] its up in its own axes, and [worldUp] the way up from the
     * planet (unit), all in the same frame.
     */
    fun tilt(camera: Quat, craft: Quat, worldUp: Vec3, craftUp: Vec3, up: Double, right: Double, turn: Double, out: Vec3): Vec3 {
        val side = camera.rotate(Vec3.unitX(), Vec3())
        side.addScaledInPlace(worldUp, -(side dot worldUp))
        // The camera's forward flattened onto the ground. Looking straight down there isn't one,
        // and its top edge stands in for it.
        val ahead = camera.rotate(Vec3(0.0, 0.0, -1.0), Vec3())
        ahead.addScaledInPlace(worldUp, -(ahead dot worldUp))
        if (ahead.lengthSq < 0.01) {
            camera.rotate(Vec3.unitY(), ahead)
            ahead.addScaledInPlace(worldUp, -(ahead dot worldUp))
        }
        if (side.lengthSq > 1e-9) side.normalizeInPlace()
        if (ahead.lengthSq > 1e-9) ahead.normalizeInPlace()
        val toward = Vec3().addScaledInPlace(side, right).addScaledInPlace(ahead, up)
        // Tipping its up toward that is turning about up x toward.
        val lift = craft.rotate(craftUp, Vec3())
        val tipping = Vec3().setTo(lift).crossInPlace(toward)
        craft.inverseRotate(tipping, out)
        // Only a tip: turning about its own up is the buttons' job.
        out.addScaledInPlace(craftUp, -(out dot craftUp))
        val most = maxOf(abs(out.x), abs(out.y), abs(out.z))
        if (most > 1.0) out.mulInPlace(1.0 / most)
        return out.addScaledInPlace(craftUp, -turn)
    }
}
