package com.rm.apogee.game

import com.rm.apogee.core.math.Vec3
import org.junit.Assert.assertEquals
import org.junit.Test

/** How big a part looks to the air, which sizes its vapour and glow. */
class AeroPartTest {
    // An 8 m wing, 2.4 m chord, 0.14 m thick, lying flat.
    private val wing = AeroPart(Vec3(), Vec3.unitX(), Vec3.unitY(), Vec3.unitZ(), Vec3(4.0, 1.2, 0.07), round = false, wing = true)

    @Test
    fun `a wing edge-on is wide along its span and thin across it`() {
        val flow = Vec3(0.0, -1.0, 0.0)
        val wide = wing.longestAcross(flow)
        assertEquals(1.0, kotlin.math.abs(wide.x), 1e-9)
        assertEquals(4.0, wing.extent(wide), 1e-9)
        assertEquals(0.07, wing.extent(flow.cross(wide)), 1e-9)
        assertEquals(1.2, wing.extent(flow), 1e-9)
    }

    @Test
    fun `a wing face-on to the air is wide both ways`() {
        val flow = Vec3(0.0, 0.0, -1.0)
        val wide = wing.longestAcross(flow)
        assertEquals(4.0, wing.extent(wide), 1e-9)
        assertEquals(1.2, wing.extent(flow.cross(wide)), 1e-9)
        assertEquals(0.07, wing.extent(flow), 1e-9)
    }

    @Test
    fun `a round part side-on is as wide as it is long, and as deep as its radius`() {
        val tank = AeroPart(Vec3(), Vec3.unitX(), Vec3.unitY(), Vec3.unitZ(), Vec3(1.25, 3.0, 1.25), round = true)
        val flow = Vec3(1.0, 0.0, 0.0)
        assertEquals(3.0, tank.extent(tank.longestAcross(flow)), 1e-9)
        assertEquals(1.25, tank.extent(flow), 1e-9)
    }
}
