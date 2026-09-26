package com.rm.apogee.core.orbit

import com.rm.apogee.core.math.Vec3
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.sqrt

/** A fast fly-by from far out: where the analytic first guess gives out. */
class HyperbolaTest {
    @Test
    fun `a craft falling in from the edge of Rubra's reach is carried along its hyperbola, not flung away`() {
        val mu = 3.77e11
        // At the edge of its reach, heading in to pass 200 km up.
        val r0 = 5.2e7
        val vInf = 983.0
        val rp = 5.19e5
        val v0 = sqrt(vInf * vInf + 2 * mu / r0)
        // Angular momentum from the periapsis: rp * vp.
        val h = rp * sqrt(vInf * vInf + 2 * mu / rp)
        val tangential = h / r0
        val radial = -sqrt(v0 * v0 - tangential * tangential)
        val orbit = Orbit(Vec3(r0, 0.0, 0.0), Vec3(radial, 0.0, tangential), mu)
        for (dt in listOf(1.0, 500.0, 5_000.0, 20_000.0, orbit.timeToPeriapsis, -500.0)) {
            val s = orbit.propagate(dt)
            // Energy is kept: the same speed at infinity everywhere along it.
            val energy = s.velocity.lengthSq / 2 - mu / s.position.length
            assertEquals("energy after $dt s", vInf * vInf / 2, energy, 5.0)
            assertTrue("flung to ${s.position.length} m after $dt s", s.position.length < r0 * 2)
        }
        assertEquals(rp, orbit.propagate(orbit.timeToPeriapsis).position.length, 1_000.0)
    }
}
