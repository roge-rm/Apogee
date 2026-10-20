package com.rm.apogee.game

import com.rm.apogee.core.math.Quat
import com.rm.apogee.core.math.Vec3
import org.junit.Assert.*
import org.junit.Test

class MapPickingTest {
    private val picker = MapPicking(Vec3(0.0, 0.0, 10.0), Quat.identity(), 1000f, 1000f)

    @Test fun `a world is picked across its disk but not in empty space or behind the camera`() {
        assertTrue(picker.sphere(650f, 500f, Vec3(), 2.0))
        assertFalse(picker.sphere(800f, 500f, Vec3(), 2.0))
        assertFalse(picker.sphere(500f, 500f, Vec3(0.0, 0.0, 20.0), 2.0))
    }

    @Test fun `orbit picking interpolates through wide gaps between samples`() {
        val points = arrayOf(Vec3(-3.0, 0.0, 0.0), Vec3(3.0, 0.0, 0.0))
        val hit = picker.path(500f, 510f, points, doubleArrayOf(100.0, 200.0)) { true }!!
        assertEquals(150.0, hit.time, 1e-5)
        assertEquals(10f, hit.distance, 1e-5f)
        assertTrue(hit.at.length < 1e-5)
    }

    @Test fun `hidden and behind camera orbit segments cannot be picked`() {
        val times = doubleArrayOf(100.0, 200.0)
        assertNull(picker.path(500f, 500f, arrayOf(Vec3(-3.0, 0.0, 0.0), Vec3(3.0, 0.0, 0.0)), times) { false })
        assertNull(picker.path(500f, 500f, arrayOf(Vec3(-3.0, 0.0, 20.0), Vec3(3.0, 0.0, 20.0)), times) { true })
    }

    @Test fun `taps past a segment stop at its endpoint`() {
        val hit = picker.path(100f, 500f, arrayOf(Vec3(), Vec3(3.0, 0.0, 0.0)), doubleArrayOf(100.0, 200.0)) { true }!!
        assertEquals(100.0, hit.time, 0.0)
        assertTrue(hit.at.length < 1e-5)
    }
}
