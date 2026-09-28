package com.rm.apogee.core.world

import com.rm.apogee.core.craft.StockCraft
import com.rm.apogee.core.part.StockParts
import com.rm.apogee.core.craft.Vessel
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Platforms that float, in the sky and on the sea, left and come back to, and founded as bases. */
class PlatformTest {

    private val catalog = StockParts.catalog

    @Test
    fun `held by its keeper, the Sky Platform sleeps in the sky and is where it was ten minutes on`() {
        val world = World.default(catalog)
        val platform = Aloft.spawn(world, StockCraft.skyPlatform(catalog), 150.0)
        world.apply(Command.SetStationKeep(platform.id.raw, true))
        Aloft.run(world, 60.0)
        assertTrue("awake", platform.dormant)
        val spot = Aloft.fixed(world, platform)
        Aloft.run(world, 600.0)
        assertTrue("moved ${Aloft.moved(world, platform, spot)} m", Aloft.moved(world, platform, spot) < 1.0)
    }

    @Test
    fun `the Sky Platform founds in the sky, and its pad carries a craft`() {
        val world = World.default(catalog)
        val platform = Aloft.spawn(world, StockCraft.skyPlatform(catalog), 150.0)
        world.apply(Command.SetStationKeep(platform.id.raw, true))
        Aloft.run(world, 20.0)
        platform.wake()
        assertTrue(world.canAnchor(platform))
        assertTrue(world.anchor(platform))
        val pad = world.baseSites("p1").single()
        assertTrue(pad.id.startsWith(LaunchSite.BASE_SITE_PREFIX))
        val deck = platform.defs.indices.first { platform.defs[it].id == "deck-sky" }
        val quad = world.spawnOnBasePad(StockCraft.quad(catalog), platform, deck)
        val height = Aloft.altitude(world, quad)
        Aloft.run(world, 10.0)
        assertTrue("fell from $height to ${Aloft.altitude(world, quad)}", kotlin.math.abs(Aloft.altitude(world, quad) - height) < 1.0)
        assertTrue(Aloft.altitude(world, quad) > 100.0)
    }

    @Test
    fun `a platform sinking below its lift can't be founded in the sky`() {
        val world = World.default(catalog)
        val platform = Aloft.spawn(world, StockCraft.skyPlatform(catalog), 150.0)
        platform.ballonet = 1.0
        Aloft.run(world, 5.0)
        assertFalse(world.canAnchor(platform))
    }

    @Test
    fun `the Sea Platform founds afloat`() {
        val world = World.default(catalog)
        val platform = world.spawnOnSurface(StockCraft.seaPlatform(catalog), World.launchSiteFor(StockCraft.seaPlatform(catalog), catalog))
        world.assignOwner(platform, "p1")
        Aloft.run(world, 60.0)
        assertTrue(world.canAnchor(platform))
        assertTrue(world.anchor(platform))
        assertTrue(platform.anchored)
    }

    private fun foundedOutOnTheSea(world: World): Vessel {
        val spot = com.rm.apogee.core.orbit.SolarSystem.capeDirection(-6_000.0, 2_000.0)
        val site = LaunchSite("open", "Open sea", "terra", com.rm.apogee.core.orbit.SolarSystem.latitudeOf(spot), com.rm.apogee.core.orbit.SolarSystem.longitudeOf(spot))
        val platform = world.spawnOnSurface(StockCraft.seaPlatform(catalog), site)
        world.assignOwner(platform, "p1")
        Aloft.run(world, 30.0)
        assertTrue(world.anchor(platform))
        assertTrue("not riding the sea", platform.afloat)
        return platform
    }

    @Test
    fun `founded out on a rough sea, the Sea Platform rides the swell`() {
        val world = World.default(catalog)
        world.weatherConfig = com.rm.apogee.core.weather.WeatherConfig(intensity = com.rm.apogee.core.weather.WeatherIntensity.WILD)
        val platform = foundedOutOnTheSea(world)
        var low = Double.MAX_VALUE
        var high = -Double.MAX_VALUE
        Aloft.run(world, 60.0) {
            val a = Aloft.altitude(world, platform)
            low = minOf(low, a); high = maxOf(high, a)
        }
        assertTrue("heaved only ${high - low} m", high - low > 0.1)
    }

    @Test
    fun `a craft set on the Sea Platform's pad rides along on its deck`() {
        val world = World.default(catalog)
        val platform = foundedOutOnTheSea(world)
        val deck = platform.defs.indices.first { platform.defs[it].id == "deck-sea" }
        val quad = world.spawnOnBasePad(StockCraft.quad(catalog), platform, deck)
        Aloft.run(world, 120.0)
        val offDeck = quad.body.position.distanceTo(platform.partPositionWorld(deck))
        assertTrue("$offDeck m from the deck's middle", offDeck < 6.0)
        assertTrue("fell in", Aloft.altitude(world, quad) > Aloft.altitude(world, platform))
    }

    @Test
    fun `a base founded afloat rides the sea again after a save`() {
        val world = World.default(catalog)
        val platform = world.spawnOnSurface(StockCraft.seaPlatform(catalog), World.launchSiteFor(StockCraft.seaPlatform(catalog), catalog))
        world.assignOwner(platform, "p1")
        Aloft.run(world, 30.0)
        assertTrue(world.anchor(platform))
        val restored = World.default(catalog)
        restored.restore(world.save())
        val again = restored.vessels.single { it.id == platform.id }
        assertTrue(again.anchored && again.afloat)
    }
}
