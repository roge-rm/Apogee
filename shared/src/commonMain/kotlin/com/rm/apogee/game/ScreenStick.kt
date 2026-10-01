package com.rm.apogee.game

import com.rm.apogee.core.math.Quat
import com.rm.apogee.core.math.Vec3
import kotlin.math.abs

/**
 * The stick read by the screen, for a craft built standing up. A round rocket has no obvious
 * axes, so turning it by its own made the stick go a different way on screen every flight.
 *
 * Right tips the nose toward the screen's right, wherever it points. Up tips it as if your thumb
 * held the craft: a nose pointing up the screen goes away from the camera, one pointing into the
 * screen goes down.
 */
object ScreenStick {

    /**
     * [up] and [right] from the stick, -1..1, as the craft's pitch (about X) and yaw (about Z), for
     * rotations [camera] and [craft] in the same frame. Scaled back together, not clipped
     * separately, when one would pass 1.
     */
    fun attitude(camera: Quat, craft: Quat, up: Double, right: Double): Pair<Double, Double> {
        val screenRight = camera.rotate(Vec3.unitX(), Vec3())
        val nose = craft.rotate(Vec3.unitY(), Vec3())
        // Up turns about the camera's left, right about nose x right, which carries the nose toward
        // the screen's right and fades only as the nose comes to point right.
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
     * The stick read by the screen for a craft that flies level (helicopter, drone): it tips toward
     * where the stick points and flies that way. Up is away from the camera along the ground, right
     * is the camera's right, whichever way the craft faces. [turn] (the roll buttons) turns it
     * about its own up, positive right. The answer is the turn in craft axes (x pitch, y roll, z
     * yaw) into [out]. [craftUp] is its up in its own axes and [worldUp] the unit up from the
     * planet; all else is in one frame.
     */
    fun tilt(camera: Quat, craft: Quat, worldUp: Vec3, craftUp: Vec3, up: Double, right: Double, turn: Double, out: Vec3): Vec3 {
        val side = camera.rotate(Vec3.unitX(), Vec3())
        side.addScaledInPlace(worldUp, -(side dot worldUp))
        // The camera's forward flattened onto the ground. Looking straight down, its top edge
        // stands in.
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
