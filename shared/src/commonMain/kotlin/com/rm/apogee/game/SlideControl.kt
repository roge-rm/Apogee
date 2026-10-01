package com.rm.apogee.game

import com.rm.apogee.core.math.Quat
import com.rm.apogee.core.math.Vec3

/**
 * Where the thrusters slide a craft for a thumb on the stick, by the camera's view, not the craft's
 * axes, so the same push goes the same way from any side.
 *
 * Near the ground, stick right is the camera's right, stick up is away along the ground, and the
 * down and up buttons are down and up from the planet. In the open it's the camera's right, into
 * the screen and up the screen. The answer is in the craft's axes, for the thrusters.
 */
object SlideControl {

    /**
     * The translation, -1..1 per craft axis, into [out]. [right] and [away] come from the stick and
     * [lift] from the buttons; [camera] and [craft] are their rotations and [up] is the unit up
     * from the planet, all in the same frame.
     */
    fun command(camera: Quat, craft: Quat, up: Vec3, right: Double, away: Double, lift: Double, out: Vec3, grounded: Boolean = true): Vec3 {
        if (!grounded) {
            // No ground to slide along: the camera's right, into the screen, and up the screen.
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
        // The camera's forward flattened onto the ground. Looking straight down, its top edge
        // stands in.
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
