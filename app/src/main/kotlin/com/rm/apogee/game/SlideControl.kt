package com.rm.apogee.game

import com.rm.apogee.core.math.Quat
import com.rm.apogee.core.math.Vec3

/**
 * Where the thrusters slide a craft for a thumb on the stick: as seen from
 * the camera, not by the craft's own axes. A pod standing on its legs has no
 * front, and a craft seen from behind, beside or above would otherwise go a
 * different way for the same push.
 *
 * Stick right is the camera's right, stick up is away from the camera along
 * the ground, and the down/up buttons are down and up from the planet -
 * near the ground. Up in the open, where there is no ground to slide along,
 * the camera's own axes: right, into the screen, and up the screen. The
 * answer is in the craft's own axes, which is what the thrusters are told.
 */
object SlideControl {

    /**
     * The translation, -1..1 per craft axis, into [out]: [right] and [away]
     * from the stick, [lift] from the buttons, for a camera turned by
     * [camera] looking at a craft turned by [craft], with [up] the way up
     * from the planet there (unit). All in the same frame.
     */
    fun command(camera: Quat, craft: Quat, up: Vec3, right: Double, away: Double, lift: Double, out: Vec3, grounded: Boolean = true): Vec3 {
        if (!grounded) {
            // Out in the open there is no ground to slide along: the camera's
            // own right, into the screen, and up the screen.
            val world = Vec3()
                .addScaledInPlace(camera.rotate(Vec3.unitX(), Vec3()), right)
                .addScaledInPlace(camera.rotate(Vec3(0.0, 0.0, -1.0), Vec3()), away)
                .addScaledInPlace(camera.rotate(Vec3.unitY(), Vec3()), lift)
            val length = world.length
            if (length > 1.0) world.mulInPlace(1.0 / length)
            return craft.inverseRotate(world, out)
        }
        val side = camera.rotate(Vec3.unitX(), Vec3())
        side.addScaledInPlace(up, -(side dot up))
        // The camera's forward flattened onto the ground; looking straight
        // down there is none, and its top edge stands in for it.
        val ahead = camera.rotate(Vec3(0.0, 0.0, -1.0), Vec3())
        ahead.addScaledInPlace(up, -(ahead dot up))
        if (ahead.lengthSq < 0.01) {
            camera.rotate(Vec3.unitY(), ahead)
            ahead.addScaledInPlace(up, -(ahead dot up))
        }
        if (side.lengthSq > 1e-9) side.normalizeInPlace()
        if (ahead.lengthSq > 1e-9) ahead.normalizeInPlace()
        val world = Vec3().addScaledInPlace(side, right).addScaledInPlace(ahead, away).addScaledInPlace(up, lift)
        val length = world.length
        if (length > 1.0) world.mulInPlace(1.0 / length)
        return craft.inverseRotate(world, out)
    }
}
