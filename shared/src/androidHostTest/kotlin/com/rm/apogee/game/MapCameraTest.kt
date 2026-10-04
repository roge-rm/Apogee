package com.rm.apogee.game

import com.rm.apogee.core.math.Quat
import com.rm.apogee.core.math.Vec3
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** The map camera: turning round over a pole, gliding zoom, and eased focus. */
class MapCameraTest {
    private fun camera() = MapCamera(2_000.0, 3e13).apply {
        lookFrom(Vec3(1.0, 0.0, 0.0), Vec3.unitY())
        frame(1e7, now = true)
    }

    @Test
    fun `dragging down raises the view toward north, and on over the pole without stopping`() {
        val c = camera()
        val before = c.outward().y
        c.orbitBy(0.0, 0.2)
        assertTrue("north grows: ${c.outward().y}", c.outward().y > before)
        // Up and over: the camera keeps going and comes down the far side.
        repeat(12) { c.orbitBy(0.0, 0.2) }
        val out = c.outward()
        assertTrue("past the pole and down the far side: $out", out.x < -0.5)
        assertEquals(1.0, out.length, 1e-9)
    }

    @Test
    fun `turning across keeps the screen level with north`() {
        val c = camera()
        c.orbitBy(0.5, 0.3); c.orbitBy(1.2, -0.1); c.orbitBy(-3.0, 0.4)
        val across = c.rotation.rotate(Vec3.unitX())
        assertEquals(0.0, across dot Vec3.unitY(), 1e-9)
    }

    @Test
    fun `zoom glides to where it was asked, and the same in log space at any scale`() {
        val c = camera()
        c.zoomBy(10.0)
        assertEquals(1e7, c.distance, 1.0)
        c.step(0.12)
        // One time constant: about 63% of the way, in log space.
        val logGone = (kotlin.math.ln(1e7) - kotlin.math.ln(c.distance)) / kotlin.math.ln(10.0)
        assertEquals(0.632, logGone, 0.02)
        repeat(60) { c.step(1.0 / 60.0) }
        assertEquals(1e6, c.distance, 1e6 * 0.01)
    }

    @Test
    fun `a new focus is eased to, and arrives in about half a second`() {
        val c = camera()
        val position = Vec3(); val rotation = Quat()
        val old = Vec3(0.0, 0.0, 0.0); val new = Vec3(5e6, 0.0, 0.0)
        c.moveFocus(old, new)
        var t = 0L
        c.solve(new, position, rotation, t)
        val start = position.copy().subInPlace(new).subInPlace(c.outward().mulInPlace(c.distance))
        assertEquals("starts where it was", -5e6, start.x, 1.0)
        t += 450_000_000L
        repeat(27) { t += 16_666_666L; c.solve(new, position, rotation, t - 450_000_000L + it * 0) }
        c.step(0.45)
        c.solve(new, position, rotation, t + 1)
        val left = position.copy().subInPlace(new).subInPlace(c.outward().mulInPlace(c.distance)).length
        assertTrue("still ${left} m to go", left < 5e6 * 0.06)
    }

    @Test
    fun `a new north is levelled to, a little at a time`() {
        val c = camera()
        c.setNorth(Vec3(0.0, 0.6, 0.8).normalizeInPlace())
        val tilts = (0 until 4).map {
            repeat(60) { c.step(1.0 / 60.0) }
            kotlin.math.abs(c.rotation.rotate(Vec3.unitX()) dot Vec3(0.0, 0.6, 0.8).normalizeInPlace())
        }
        assertTrue("settling: $tilts", tilts.zipWithNext().all { (a, b) -> b <= a + 1e-9 })
        assertTrue("level: $tilts", tilts.last() < 1e-3)
    }
}
