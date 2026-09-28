package com.rm.apogee.core.world

import com.rm.apogee.core.math.Vec3
import com.rm.apogee.core.orbit.SolarSystem
import com.rm.apogee.core.terrain.TerrainField
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** The runway approach cue: on the slope, high, low, off to one side, and nothing heading away. */
class ApproachTest {

    private val radius = 600_000.0

    /** A craft [out] metres west of the runway's west end, [north] metres off its line, [height] up, flying [ve] east. */
    private fun cue(out: Double, north: Double, height: Double, ve: Double = 60.0): Approach.Cue? {
        val position = SolarSystem.capeDirection(TerrainField.RUNWAY_WEST - out, TerrainField.RUNWAY_NORTH + north).mulInPlace(radius + height)
        val pad = SolarSystem.capeDirection(0.0, 0.0)
        val eastward = Vec3(0.0, 1.0, 0.0).crossInPlace(pad).normalizeInPlace()
        val velocity = eastward.mulInPlace(ve)
        val c = Approach.Cue()
        return if (Approach.cue(position, velocity, height, radius, c)) c else null
    }

    private fun slope(out: Double) = (out + Approach.AIM) * kotlin.math.tan(Math.toRadians(Approach.GLIDE_DEGREES))

    @Test
    fun `on a three degree slope it reads on the slope`() {
        val c = cue(5_000.0, 0.0, slope(5_000.0))!!
        assertEquals(0.0, c.aboveSlope, 1.0)
        assertEquals(Approach.GLIDE_DEGREES, c.angle, 0.05)
        assertEquals(5_000.0, c.toThreshold, 5.0)
        // Two white and two red.
        assertEquals(2, Approach.LIGHTS.count { Approach.white(c.angle, it) })
    }

    @Test
    fun `a hundred metres high reads high, and every light is white`() {
        val c = cue(5_000.0, 0.0, slope(5_000.0) + 100.0)!!
        assertEquals(100.0, c.aboveSlope, 1.0)
        assertEquals(4, Approach.LIGHTS.count { Approach.white(c.angle, it) })
    }

    @Test
    fun `two hundred metres north, landing east, is off to the left`() {
        val c = cue(5_000.0, 200.0, slope(5_000.0))!!
        assertEquals(-200.0, c.offCentre, 2.0)
    }

    @Test
    fun `heading away or far off, there's no cue`() {
        assertEquals(null, cue(5_000.0, 0.0, 300.0, ve = -60.0)?.takeIf { it.toThreshold > 0.0 && it.sense > 0.0 })
        assertEquals(null, cue(30_000.0, 0.0, 1_000.0))
        assertFalse(cue(5_000.0, 0.0, 300.0, ve = -60.0)?.sense == 1.0)
        assertTrue(cue(19_000.0, 0.0, 1_000.0) != null)
    }
}
