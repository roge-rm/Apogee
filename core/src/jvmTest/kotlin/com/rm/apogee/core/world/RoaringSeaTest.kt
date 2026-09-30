package com.rm.apogee.core.world

import com.rm.apogee.core.craft.StockCraft
import com.rm.apogee.core.math.Vec3
import com.rm.apogee.core.part.StockParts
import org.junit.Assert.assertTrue
import org.junit.Test

/** The Roaring Sea: somewhere in free play to go and see big waves, and a boat launched there floats. */
class RoaringSeaTest {
    private val catalog = StockParts.catalog
    private val site = World.launchSites.first { it.id == "roaring-sea" }
    private val where = Vec3(
        kotlin.math.cos(site.latitude) * kotlin.math.cos(site.longitude),
        kotlin.math.sin(site.latitude),
        kotlin.math.cos(site.latitude) * kotlin.math.sin(site.longitude),
    )

    @Test
    fun `it's deep open water, rougher than most of the ocean`() {
        val world = World.default(catalog)
        world.weatherConfig = com.rm.apogee.core.weather.WeatherConfig()
        val terra = world.system.body("terra")
        assertTrue(terra.terrain!!.elevation(where) < -300.0)
        val sample = com.rm.apogee.core.sea.SeaSample()
        var sum = 0.0; var n = 0
        var t = 3_000.0
        while (t < 3_000.0 + 2 * 86_400.0) { terra.ocean!!.sample(where, t, sample); sum += sample.significantHeight; n++; t += 3 * 3600.0 }
        assertTrue("only ${sum / n} m", sum / n > 3.0)
    }

    @Test
    fun `a Cutter launched there floats`() {
        val world = World.default(catalog)
        val boat = world.spawnOnSurface(StockCraft.cutter(catalog), site)
        repeat(20 * 60) { world.step(1.0 / 60.0) }
        assertTrue("not afloat", boat.buoyed && !boat.touchingGround)
        assertTrue("broke: ${boat.broken.count { it }}", boat.broken.none { it })
    }

    @Test
    fun `the small open boats ride out a wild sea there`() {
        for (t in listOf(12_160.0, 40_000.0)) for (design in listOf(StockCraft.jetBoat(catalog), StockCraft.skiff(catalog))) {
            val world = World.default(catalog)
            world.weatherConfig = com.rm.apogee.core.weather.WeatherConfig(intensity = com.rm.apogee.core.weather.WeatherIntensity.WILD)
            world.restore(WorldSave(catalogHash = catalog.contentHash, universeTime = t, nextVesselId = 1L))
            val boat = world.spawnOnSurface(design, site)
            repeat(60 * 60) { world.step(1.0 / 60.0) }
            assertTrue("the ${design.name} went down at t=$t", boat.buoyed && !boat.submerged)
        }
    }

    @Test
    fun `the Jet Boat can be driven flat out there on an ordinary day`() {
        val world = World.default(catalog)
        world.weatherConfig = com.rm.apogee.core.weather.WeatherConfig()
        world.restore(WorldSave(catalogHash = catalog.contentHash, universeTime = 11_440.0, nextVesselId = 1L))
        val boat = world.spawnOnSurface(StockCraft.jetBoat(catalog), site)
        repeat(10 * 60) { world.step(1.0 / 60.0) }
        world.apply(Command.Stage(boat.id.raw))
        world.apply(Command.SetThrottle(boat.id.raw, 1.0))
        var most = 0.0
        repeat(50 * 60) {
            world.step(1.0 / 60.0)
            val deck = boat.body.orientation.rotate(boat.design.orientation.up)
            most = maxOf(most, Math.toDegrees(kotlin.math.acos((deck dot boat.body.position.normalized()).coerceIn(-1.0, 1.0))))
        }
        assertTrue("she went down", boat.buoyed && !boat.submerged)
        assertTrue("she heeled $most degrees", most < 45.0)
    }
}
