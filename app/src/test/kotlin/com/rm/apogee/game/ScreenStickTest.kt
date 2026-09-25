package com.rm.apogee.game

import com.rm.apogee.core.math.Quat
import com.rm.apogee.core.math.Vec3
import com.rm.apogee.core.math.quatLookAt
import org.junit.Assert.assertTrue
import org.junit.Test

/** Whatever way a rocket is turned about its axis, the stick moves its nose the way the thumb goes on screen. */
class ScreenStickTest {

    /** Where the nose goes on screen, camera-space, for a moment of the stick. */
    private fun noseMoves(camera: Quat, craft: Quat, up: Double, right: Double): Vec3 {
        val (pitch, yaw) = ScreenStick.attitude(camera, craft, up, right)
        // Turned a little about the craft's own X by pitch and Z by yaw.
        val turn = Quat.fromAxisAngle(craft.rotate(Vec3.unitX()), 0.01 * pitch) *
            Quat.fromAxisAngle(craft.rotate(Vec3.unitZ()), 0.01 * yaw)
        val before = craft.rotate(Vec3.unitY())
        val after = turn.rotate(before)
        return camera.inverseRotate(after.subInPlace(before))
    }

    private val side = quatLookAt(Vec3(0.0, 0.0, -1.0), Vec3.unitY()) // looking along -Z, +Y up the screen

    @Test
    fun `a rocket standing up, however it is turned about its axis, tips right for right`() {
        for (deg in listOf(0.0, 37.0, 90.0, 180.0, 271.0)) {
            val craft = Quat.fromAxisAngle(Vec3.unitY(), Math.toRadians(deg))
            val move = noseMoves(side, craft, 0.0, 1.0)
            assertTrue("turned $deg: $move", move.x > 0.009 && kotlin.math.abs(move.y) < 1e-3 && kotlin.math.abs(move.z) < 1e-3)
        }
    }

    @Test
    fun `stick up tips a standing rocket away from the camera`() {
        for (deg in listOf(0.0, 90.0, 200.0)) {
            val craft = Quat.fromAxisAngle(Vec3.unitY(), Math.toRadians(deg))
            val move = noseMoves(side, craft, 1.0, 0.0)
            assertTrue("turned $deg: $move", move.z < -0.009 && kotlin.math.abs(move.x) < 1e-3)
        }
    }

    @Test
    fun `seen from behind in flight, up is nose down and right is nose right`() {
        // The nose along -Z, into the screen, rolled some way about itself.
        val craft = Quat.fromAxisAngle(Vec3.unitZ(), 0.7) * Quat.fromAxisAngle(Vec3.unitX(), -Math.PI / 2)
        val right = noseMoves(side, craft, 0.0, 1.0)
        assertTrue("$right", right.x > 0.009 && kotlin.math.abs(right.y) < 1e-3)
        val up = noseMoves(side, craft, 1.0, 0.0)
        assertTrue("$up", up.y < -0.009 && kotlin.math.abs(up.x) < 1e-3)
    }
}
