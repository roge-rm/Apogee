package com.rm.apogee.core.world

import com.rm.apogee.core.craft.StockCraft
import com.rm.apogee.core.math.Vec3
import com.rm.apogee.core.part.StockParts
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class VesselConditionTest {

    private val catalog = StockParts.catalog

    @Test
    fun `a whole cool craft sends nothing`() {
        val world = World.default(catalog)
        val rocket = world.spawnOnSurface(StockCraft.starterRocket(catalog), World.launchSites.first())
        assertEquals(0, VesselCondition.encode(rocket).size)
        val values = VesselCondition.decode(rocket.design.parts.size, ByteArray(0), VesselCondition.Values())
        assertFalse(values.any)
        assertTrue(values.health.all { it == 1f })
    }

    @Test
    fun `hurt, hot and dented parts arrive close to how they left`() {
        val world = World.default(catalog)
        val rocket = world.spawnOnSurface(StockCraft.starterRocket(catalog), World.launchSites.first())
        rocket.damage(2, 0.3, Vec3(1.0, 0.0, 0.0))
        rocket.temperature[5] = 1_750.0
        val bytes = VesselCondition.encode(rocket)
        assertEquals(rocket.design.parts.size * VesselCondition.BYTES_PER_PART, bytes.size)

        val values = VesselCondition.decode(rocket.design.parts.size, bytes, VesselCondition.Values())
        assertTrue(values.any)
        assertEquals(0.7f, values.health[2], 0.005f)
        assertEquals(1_750f, values.temperature[5], 10f)
        assertTrue("dented along +x", values.crumple[2 * 3] > 0.25f)
        assertEquals(1f, values.health[0], 0.001f)
    }

    @Test
    fun `a straining joint is sent, and a quiet one isn't`() {
        val world = World.default(catalog)
        val rocket = world.spawnOnSurface(StockCraft.starterRocket(catalog), World.launchSites.first())
        rocket.jointLoad[3] = 0.5f
        assertEquals("under what shows, nothing to send", 0, VesselCondition.encode(rocket).size)
        rocket.jointLoad[3] = 0.95f
        val values = VesselCondition.decode(rocket.design.parts.size, VesselCondition.encode(rocket), VesselCondition.Values())
        assertTrue(values.any)
        assertEquals(0.95f, values.load[3], 0.01f)
        assertEquals(0f, values.load[1], 0.001f)
        assertEquals("whole, for all its straining", 1f, values.health[3], 0.001f)
    }

    @Test
    fun `a block for the wrong shape reads as whole`() {
        val values = VesselCondition.decode(4, ByteArray(3 * VesselCondition.BYTES_PER_PART) { 7 }, VesselCondition.Values())
        assertFalse(values.any)
        assertTrue(values.health.all { it == 1f })
    }
}
