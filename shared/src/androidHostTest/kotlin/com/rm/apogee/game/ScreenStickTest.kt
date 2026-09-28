package com.rm.apogee.game

import com.rm.apogee.core.math.Quat
import com.rm.apogee.core.math.Vec3
import com.rm.apogee.core.math.quatLookAt
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Whichever way a rocket is turned about its axis, the stick moves its nose the way your thumb goes
 * on screen.
 */
class ScreenStickTest {

    /** Where the nose goes on screen, in camera space, for a moment of the stick. */
    private fun noseMoves(camera: Quat, craft: Quat, up: Double, right: Double): Vec3 {
        val (pitch, yaw) = ScreenStick.attitude(camera, craft, up, right)
        // Turned a little about the craft's own X by pitch and Z by yaw.
        val turn = Quat.fromAxisAngle(craft.rotate(Vec3.unitX()), 0.01 * pitch) *
            Quat.fromAxisAngle(craft.rotate(Vec3.unitZ()), 0.01 * yaw)
        val before = craft.rotate(Vec3.unitY())
        val after = turn.rotate(before)
        return camera.inverseRotate(after.subInPlace(before))
    }

    private val side = quatLookAt(Vec3(0.0, 0.0, -1.0), Vec3.unitY()) // looking along -Z, with +Y up the screen

    @Test
    fun `a rocket standing up tips right for right however it's turned about its axis`() {
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

    /** Where a level craft's up goes, in world space, for a moment of the stick read by the screen. */
    private fun liftMoves(camera: Quat, craft: Quat, up: Double, right: Double, turn: Double = 0.0): Vec3 {
        val designUp = Vec3.unitZ()
        val command = ScreenStick.tilt(camera, craft, Vec3.unitY(), designUp, up, right, turn, Vec3())
        val axis = craft.rotate(command.copy())
        val before = craft.rotate(designUp)
        if (axis.length < 1e-9) return Vec3()
        val after = Quat.fromAxisAngle(axis.copy().normalizeInPlace(), 0.01 * axis.length).rotate(before)
        return after.subInPlace(before)
    }

    /** A craft built lying down (up +Z, nose +Y), level, heading [deg] round the world's up. */
    private fun level(deg: Double): Quat =
        Quat.fromAxisAngle(Vec3.unitY(), Math.toRadians(deg)) * com.rm.apogee.core.math.quatFromTo(Vec3.unitZ(), Vec3.unitY())

    @Test
    fun `read by the screen, a helicopter tips away from the camera for stick up, whichever way it faces`() {
        for (deg in listOf(0.0, 37.0, 90.0, 180.0, 271.0)) {
            val move = liftMoves(side, level(deg), 1.0, 0.0)
            // The camera looks along -Z, so away is -Z.
            assertTrue("facing $deg: $move", move.z < -0.009 && kotlin.math.abs(move.x) < 1e-3)
        }
    }

    @Test
    fun `read by the screen, a helicopter tips to the screen's right for stick right`() {
        for (deg in listOf(0.0, 90.0, 200.0)) {
            val move = liftMoves(side, level(deg), 0.0, 1.0)
            assertTrue("facing $deg: $move", move.x > 0.009 && kotlin.math.abs(move.z) < 1e-3)
        }
    }

    @Test
    fun `read by the screen, the roll buttons turn a helicopter about its up, right for right`() {
        val craft = level(0.0)
        val command = ScreenStick.tilt(side, craft, Vec3.unitY(), Vec3.unitZ(), 0.0, 0.0, 1.0, Vec3())
        // About its up only, and clockwise seen from above: negative about the world's up.
        val axis = craft.rotate(command)
        assertTrue("$axis", axis.y < -0.99 && kotlin.math.abs(axis.x) < 1e-6 && kotlin.math.abs(axis.z) < 1e-6)
    }
}
