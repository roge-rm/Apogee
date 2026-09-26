package com.rm.apogee.core.orbit

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** When to leave for another world, and what it costs. */
class TransferWindowTest {
    private val system = SolarSystem.defaultSystem()
    private val terra = system.body("terra")

    private fun degrees(x: Double) = Math.toDegrees(x)

    @Test
    fun `Rubra is caught by leaving with it some way ahead, and the window comes round when the phase does`() {
        val w = TransferWindow.between(system, "terra", "rubra", 0.0, terra.radius + 100_000.0)!!
        // The textbook figure for this pair of orbits: about 44 degrees ahead.
        assertEquals(44.0, degrees(w.phaseNeeded), 3.0)
        assertTrue("wait ${w.waitFor} beyond a synodic period ${w.synodic}", w.waitFor in 0.0..w.synodic)
        // At the window, the phase is what was needed.
        val then = TransferWindow.between(system, "terra", "rubra", w.waitFor, terra.radius + 100_000.0)!!
        val off = Math.abs(degrees(then.phase - then.phaseNeeded)).let { if (it > 180.0) 360.0 - it else it }
        assertTrue("phase at the window off by $off degrees", off < 8.0)
        assertTrue("departure ${w.departure} m/s", w.departure in 700.0..1_600.0)
        assertTrue("arrival ${w.arrival} m/s", w.arrival in 300.0..2_000.0)
    }

    @Test
    fun `going inward to Caligo, the target has to be behind`() {
        val w = TransferWindow.between(system, "terra", "caligo", 0.0, terra.radius + 100_000.0)!!
        assertTrue("phase needed ${degrees(w.phaseNeeded)}", degrees(w.phaseNeeded) > 180.0)
        assertTrue(w.waitFor in 0.0..w.synodic)
        val then = TransferWindow.between(system, "terra", "caligo", w.waitFor, terra.radius + 100_000.0)!!
        val off = Math.abs(degrees(then.phase - then.phaseNeeded)).let { if (it > 180.0) 360.0 - it else it }
        assertTrue("phase at the window off by $off degrees", off < 8.0)
    }

    @Test
    fun `only between planets of the same star`() {
        assertNull(TransferWindow.between(system, "terra", "luna", 0.0, 1e6))
        assertNull(TransferWindow.between(system, "terra", "terra", 0.0, 1e6))
        assertNull(TransferWindow.between(system, "luna", "rubra", 0.0, 1e6))
    }

    @Test
    fun `a path leaving Terra for good is followed out among the planets`() {
        // Hyperbolic out of a low orbit: past the old horizon of a few days.
        val r = terra.radius + 200_000.0
        val speed = kotlin.math.sqrt(2.0 * terra.gravitationalParameter / r) + 1_200.0
        val path = Trajectory.predict(system, "terra", com.rm.apogee.core.math.Vec3(r, 0.0, 0.0), com.rm.apogee.core.math.Vec3(0.0, 0.0, speed), 0.0)
        assertEquals(Trajectory.Ending.ESCAPE, path.segments.first().ending)
        val helio = path.segments[1]
        assertEquals("sol", helio.bodyId)
        assertTrue("the leg about the star ends after ${helio.end - helio.start} s", helio.end - helio.start > 1e6)
    }
}
