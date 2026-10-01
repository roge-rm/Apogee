package com.rm.apogee.game

import com.rm.apogee.core.math.Quat
import com.rm.apogee.core.math.Vec3
import org.junit.Assert.assertTrue
import org.junit.Test

class CameraControllerTest {

    /** Over a pole the view turns smoothly, with no swap of north inside the polar cap. */
    @Test
    fun `passing over a pole the view never jumps`() {
        val camera = CameraController()
        val radius = 6_800_000.0
        val position = Vec3(); val rotation = Quat.identity()
        val lastRotation = Quat.identity()
        var worst = 0.0
        // From 20 degrees short of the north pole to 20 past it.
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

    // A craft at the top of a planet (+Y up), built lying down (up +Z, nose +Y), heading +X.
    private val target = Vec3(0.0, 6_000_000.0, 0.0)
    private val craft = Quat.fromAxisAngle(Vec3.unitY(), -Math.PI / 2) *
        com.rm.apogee.core.math.quatFromTo(Vec3.unitZ(), Vec3.unitY())
    private val nose = Vec3.unitY()
    private val up = Vec3.unitZ()

    private fun looksAlong(rotation: Quat): Vec3 = rotation.rotate(Vec3(0.0, 0.0, -1.0))

    @Test
    fun `the chase camera ends up behind the craft, looking the way it's heading`() {
        val camera = CameraController()
        camera.mode = CameraMode.CHASE
        val position = Vec3(); val rotation = Quat.identity()
        // It swings round over a moment, so a few seconds of frames.
        repeat(200) {
            camera.solve(target, craft, nose, up, null, position, rotation)
            Thread.sleep(5)
        }
        val behind = Vec3().setTo(position).subInPlace(target)
        assertTrue("camera at $behind", behind.x < -0.5 * camera.distance)
        assertTrue("looking ${looksAlong(rotation)}", looksAlong(rotation).x > 0.5)
    }

    @Test
    fun `the locked camera sits behind the craft in its own axes, and rolls with it`() {
        val camera = CameraController()
        camera.mode = CameraMode.LOCKED
        camera.pitch = 0.0
        val position = Vec3(); val rotation = Quat.identity()
        // Rolled a quarter turn onto its side.
        val rolled = Quat.fromAxisAngle(Vec3.unitX(), Math.PI / 2) * craft
        camera.solve(target, rolled, nose, up, null, position, rotation)
        val behind = Vec3().setTo(position).subInPlace(target)
        assertTrue("camera at $behind", behind.x < -0.99 * camera.distance)
        // The top of the screen is the craft's up, now sideways.
        val screenUp = rotation.rotate(Vec3.unitY())
        val craftUp = rolled.rotate(up)
        assertTrue("screen up $screenUp, craft up $craftUp", (screenUp dot craftUp) > 0.99)
    }

    @Test
    fun `from the cockpit the camera is at the seat, looking out ahead`() {
        val camera = CameraController()
        camera.mode = CameraMode.COCKPIT
        val seat = Vec3(1.0, 6_000_001.0, 0.0)
        val position = Vec3(); val rotation = Quat.identity()
        camera.solve(target, craft, nose, up, seat, position, rotation)
        assertTrue("at $position", position.distanceTo(seat) < 1e-9)
        assertTrue("looking ${looksAlong(rotation)}", looksAlong(rotation).x > 0.99)
    }
}
