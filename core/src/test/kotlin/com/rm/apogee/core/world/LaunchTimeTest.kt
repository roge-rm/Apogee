package com.rm.apogee.core.world

import com.rm.apogee.core.craft.StockCraft
import com.rm.apogee.core.math.Vec3
import com.rm.apogee.core.part.StockParts
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class LaunchTimeTest {

    private val catalog = StockParts.catalog

    @Test
    fun `each time of day comes next at the site, lit as it should be`() {
        val world = World.default(catalog)
        val terra = world.system.body("terra")
        val site = World.launchSiteFor(StockCraft.starterRocket(catalog), catalog)
        val up = world.spawnAtSite(StockCraft.starterRocket(catalog), site).let { v ->
            terra.toBodyFixed(v.body.position, terra.rotationAt(world.time), Vec3()).normalizeInPlace()
        }
        val system = world.system
        // The sun as it really stands, moving a little through the year.
        fun sunHeight(t: Double) = terra.rotationAt(t).rotate(up, Vec3()) dot system.sunDirection("terra", Vec3.zero(), t)
        for (from in listOf(0.0, 5_000.0, 13_000.0)) {
            val noon = LaunchTime.NOON.nextAt(system, terra, up, from)
            val midnight = LaunchTime.MIDNIGHT.nextAt(system, terra, up, from)
            val dawn = LaunchTime.DAWN.nextAt(system, terra, up, from)
            val dusk = LaunchTime.DUSK.nextAt(system, terra, up, from)
            for (t in listOf(noon, midnight, dawn, dusk)) {
                assertTrue("never back in time", t >= from)
                assertTrue("within a day", t < from + terra.rotationPeriod)
            }
            assertTrue("noon: the sun high", sunHeight(noon) > 0.5)
            assertTrue("midnight: the sun far below", sunHeight(midnight) < -0.5)
            assertTrue("dawn: the sun just up", sunHeight(dawn) in 0.0..0.3)
            assertTrue("and rising", sunHeight(dawn + 60.0) > sunHeight(dawn))
            assertTrue("dusk: the sun just up", sunHeight(dusk) in 0.0..0.3)
            assertTrue("and setting", sunHeight(dusk + 60.0) < sunHeight(dusk))
        }
        assertEquals("now is now", 123.0, LaunchTime.NOW.nextAt(system, terra, up, 123.0), 0.0)
    }

    @Test
    fun `skipping time leaves a parked craft on its ground and a flying one over its own`() {
        val world = World.default(catalog)
        val terra = world.system.body("terra")
        val site = World.launchSiteFor(StockCraft.starterRocket(catalog), catalog)
        val parked = world.spawnAtSite(StockCraft.starterRocket(catalog), site)
        repeat(60 * 30) { world.step(1.0 / 60.0) }
        assertTrue("parked", parked.dormant)
        // One hanging in the air, drifting.
        val up = parked.body.position.copy().normalizeInPlace()
        val airPos = parked.body.position.copy().addScaledInPlace(up, 2_000.0)
        val flying = world.spawnAt(
            StockCraft.starterRocket(catalog), "terra", airPos,
            terra.surfaceVelocityAt(airPos, Vec3()).addScaledInPlace(up, -5.0),
            com.rm.apogee.core.math.quatFromTo(Vec3.unitY(), up),
        )
        fun bodyFixed(v: com.rm.apogee.core.craft.Vessel) = terra.toBodyFixed(v.body.position, terra.rotationAt(world.time), Vec3())
        val parkedBefore = bodyFixed(parked)
        val flyingBefore = bodyFixed(flying)
        world.skipTo(world.time + 7_777.0)
        assertTrue("parked where it was: ${bodyFixed(parked).distanceTo(parkedBefore)} m", bodyFixed(parked).distanceTo(parkedBefore) < 0.5)
        assertTrue("flying over the same ground: ${bodyFixed(flying).distanceTo(flyingBefore)} m", bodyFixed(flying).distanceTo(flyingBefore) < 0.5)
        val relative = flying.body.linearVelocity.copy().subInPlace(terra.surfaceVelocityAt(flying.body.position, Vec3()))
        assertEquals("still sinking as it was", -5.0, relative dot flying.body.position.copy().normalizeInPlace(), 0.05)
    }
}
