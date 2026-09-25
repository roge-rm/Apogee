package com.rm.apogee.game

import com.rm.apogee.core.math.Quat
import com.rm.apogee.core.math.Vec3
import org.junit.Assert.assertTrue
import org.junit.Test

class CameraControllerTest {

    /**
     * Flying over a pole, the view turns smoothly: it used to swap its idea
     * of north for another axis inside the polar cap, and the whole view
     * turned in a frame (Dan's video, at 4x, heading north at 415 km).
     */
    @Test
    fun `passing over a pole the view never jumps`() {
        val camera = CameraController()
        val radius = 6_800_000.0
        val position = Vec3(); val rotation = Quat.identity()
        val lastRotation = Quat.identity()
        var worst = 0.0
        // From 20 degrees short of the north pole to 20 past it, a small step a frame.
        val steps = 4_000
        for (k in 0..steps) {
            val lat = Math.toRadians(70.0 + 40.0 * k / steps)
            val target = Vec3(kotlin.math.cos(lat) * radius, kotlin.math.sin(lat) * radius, 0.0)
            camera.solve(target, position, rotation)
            if (k > 0) {
                val turn = Math.toDegrees(2.0 * kotlin.math.acos(kotlin.math.abs(rotation.x * lastRotation.x + rotation.y * lastRotation.y + rotation.z * lastRotation.z + rotation.w * lastRotation.w).coerceAtMost(1.0)))
                worst = maxOf(worst, turn)
            }
            lastRotation.setTo(rotation)
        }
        assertTrue("the view turns no more than a sliver a frame (${"%.3f".format(worst)} deg)", worst < 0.2)
    }
}
