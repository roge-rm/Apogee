package com.rm.apogee.core.math

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.PI
import kotlin.math.abs

class Vec3Test {

    @Test
    fun `cross product follows the right-hand rule`() {
        val result = Vec3.unitX().cross(Vec3.unitY())
        assertTrue("x cross y should be +z, was $result", result.approxEquals(Vec3.unitZ(), 1e-15))
    }

    @Test
    fun `addScaledInPlace accumulates without allocating a temporary`() {
        val acc = Vec3.zero()
        val force = Vec3(0.0, -9.81, 0.0)
        repeat(10) { acc.addScaledInPlace(force, 0.5) }
        assertEquals(-49.05, acc.y, 1e-12)
        assertEquals(0.0, acc.x, 0.0)
    }

    @Test
    fun `normalize leaves a degenerate vector alone instead of producing NaN`() {
        val degenerate = Vec3.zero().normalizeInPlace()
        assertTrue("normalising zero must not produce NaN, was $degenerate", degenerate.isFinite)
    }

    @Test
    fun `length is exact for a pythagorean triple`() {
        assertEquals(13.0, Vec3(3.0, 4.0, 12.0).length, 1e-15)
    }
}

class QuatTest {

    @Test
    fun `a quarter turn about Z takes +X to +Y`() {
        val q = Quat.fromAxisAngle(Vec3.unitZ(), PI / 2.0)
        val rotated = q.rotate(Vec3.unitX())
        assertTrue("expected +Y, was $rotated", rotated.approxEquals(Vec3.unitY(), 1e-12))
    }

    @Test
    fun `inverseRotate undoes rotate`() {
        val q = Quat.fromAxisAngle(Vec3(1.0, 2.0, 3.0), 0.7)
        val original = Vec3(0.3, -1.7, 4.2)
        val roundTripped = q.inverseRotate(q.rotate(original))
        assertTrue(roundTripped.approxEquals(original, 1e-12))
    }

    @Test
    fun `integrating angular velocity tracks the analytic rotation and stays normalised`() {
        // 1 rad/s about Y, stepped at the simulation's fixed 60 Hz for a little over a full turn.
        val q = Quat.identity()
        val omega = Vec3(0.0, 1.0, 0.0)
        val dt = 1.0 / 60.0
        val steps = 400
        repeat(steps) { q.integrateAngularVelocity(omega, dt) }

        // Staying on the unit sphere is the part that can't give. Drifting off it shears the craft
        // instead of just aiming it wrong.
        assertEquals("quaternion must stay normalised", 1.0, q.length, 1e-12)

        // Explicit Euler plus renormalisation under-rotates a little each step, so compare against
        // the analytic result instead of against identity.
        val expected = Quat.fromAxisAngle(Vec3.unitY(), steps * dt)
        val error = angleBetween(q, expected)
        assertTrue("integrator drifted $error rad over $steps steps", error < 1e-3)
    }

    private fun angleBetween(a: Quat, b: Quat): Double {
        val d = abs(a dot b).coerceIn(0.0, 1.0)
        return 2.0 * kotlin.math.acos(d)
    }

    @Test
    fun `slerp hits its endpoints exactly and its midpoint halfway`() {
        val a = Quat.identity()
        val b = Quat.fromAxisAngle(Vec3.unitZ(), PI / 2.0)

        assertTrue(Quat.slerp(a, b, 0.0).approxEqualsRotation(a))
        assertTrue(Quat.slerp(a, b, 1.0).approxEqualsRotation(b))

        val mid = Quat.slerp(a, b, 0.5)
        val expected = Quat.fromAxisAngle(Vec3.unitZ(), PI / 4.0)
        assertTrue("midpoint was $mid, expected $expected", mid.approxEqualsRotation(expected, 1e-9))
    }

    @Test
    fun `slerp takes the short way round when the inputs are in opposite hemispheres`() {
        val a = Quat.fromAxisAngle(Vec3.unitY(), 0.1)
        // The same orientation as a small positive rotation, but negated. The naive path would spin
        // almost all the way round.
        val b = Quat.fromAxisAngle(Vec3.unitY(), 0.2).let { Quat(-it.x, -it.y, -it.z, -it.w) }

        val mid = Quat.slerp(a, b, 0.5)
        val expected = Quat.fromAxisAngle(Vec3.unitY(), 0.15)
        assertTrue("slerp went the long way: got $mid", mid.approxEqualsRotation(expected, 1e-9))
    }

    @Test
    fun `composition applies the right-hand operand first`() {
        val yaw = Quat.fromAxisAngle(Vec3.unitY(), PI / 2.0)
        val pitch = Quat.fromAxisAngle(Vec3.unitX(), PI / 2.0)
        val combined = yaw * pitch

        val viaCombined = combined.rotate(Vec3.unitZ())
        val viaSequence = yaw.rotate(pitch.rotate(Vec3.unitZ()))
        assertTrue(viaCombined.approxEquals(viaSequence, 1e-12))
    }
}

class Mat4Test {

    /**
     * The floating-origin guarantee, as a test. A part 1 cm from the camera but 6400 km from the
     * world origin still has to land 1 cm from the scene origin. Narrowing world coordinates to
     * float before subtracting the camera would quantise this to zero, which is the bug this whole
     * double-precision core exists to prevent.
     */
    @Test
    fun `setFromTrs preserves centimetre offsets at planetary distance`() {
        val cameraPos = Vec3(6_400_000.0, 0.0, 0.0)
        val objectPos = Vec3(6_400_000.01, 0.0, 0.0)

        val model = Mat4().setFromTrs(objectPos, Quat.identity(), cameraPos)

        assertEquals(0.01f, model.m[12], 1e-4f)

        // And the counterexample: the naive float-first path really does lose it.
        val naive = objectPos.x.toFloat() - cameraPos.x.toFloat()
        assertEquals("float32 cannot resolve this, which is the point", 0.0f, naive, 0.0f)
    }

    @Test
    fun `setFromTrs writes a rotation matching the quaternion`() {
        val rot = Quat.fromAxisAngle(Vec3.unitZ(), PI / 2.0)
        val model = Mat4().setFromTrs(Vec3.zero(), rot, Vec3.zero())

        // Column 0 is the local +X axis in world space, and a quarter turn about Z sends it to +Y.
        assertEquals(0.0f, model.m[0], 1e-6f)
        assertEquals(1.0f, model.m[1], 1e-6f)
        assertEquals(0.0f, model.m[2], 1e-6f)
    }

    @Test
    fun `identity is the multiplicative identity`() {
        val a = Mat4().setFromTrs(Vec3(1.0, 2.0, 3.0), Quat.fromAxisAngle(Vec3.unitY(), 0.4), Vec3.zero())
        val result = Mat4().setMultiplied(a, Mat4())
        for (i in 0..15) {
            assertEquals("element $i", a.m[i], result.m[i], 1e-6f)
        }
    }

    @Test
    fun `perspective maps the near plane to NDC -1`() {
        val near = 0.1
        val far = 1000.0
        val p = Mat4().setPerspective(PI / 3.0, 16.0 / 9.0, near, far)

        // Project a point sitting exactly on the near plane (GL looks down -Z).
        val z = -near
        val clipZ = p.m[10].toDouble() * z + p.m[14].toDouble()
        val clipW = -z
        assertEquals(-1.0, clipZ / clipW, 1e-5)
    }
}
