package com.rm.apogee.core.world

import com.rm.apogee.core.craft.CraftDesign
import com.rm.apogee.core.craft.StockCraft
import com.rm.apogee.core.craft.Vessel
import com.rm.apogee.core.math.Vec3
import com.rm.apogee.core.part.ResourceType
import com.rm.apogee.core.part.StockParts
import com.rm.apogee.core.weather.WeatherConfig
import com.rm.apogee.core.weather.WeatherIntensity
import org.junit.Assert.assertTrue
import org.junit.Test

/** Ships: 4.5 m hulls with a diesel or electric drive and a ship's rudder, like the Coaster. */
class ShipTest {
    private val catalog = StockParts.catalog
    private val dt = 1.0 / 60.0

    private fun run(world: World, seconds: Double) = repeat((seconds / dt).toInt()) { world.step(dt) }

    private fun tilt(v: Vessel): Double {
        val deck = v.body.orientation.rotate(v.design.orientation.up, Vec3())
        return Math.toDegrees(kotlin.math.acos((deck dot v.body.position.normalized()).coerceIn(-1.0, 1.0)))
    }

    private fun speed(world: World, v: Vessel) =
        v.body.linearVelocity.copy().subInPlace(world.attractorFor(v).surfaceVelocityAt(v.body.position, Vec3())).length

    /** Which way it's pointing, in degrees north of east. */
    private fun heading(world: World, v: Vessel): Double {
        val up = v.body.position.normalized()
        val ahead = v.forward().addScaledInPlace(up, -(v.forward() dot up)).normalizeInPlace()
        val east = world.attractorFor(v).rotationAt(world.time).rotate(Vec3.unitY()).crossInPlace(up).normalizeInPlace()
        val north = up.copy().crossInPlace(east)
        return Math.toDegrees(kotlin.math.atan2(ahead dot north, ahead dot east))
    }

    /** [design] launched from the harbour in a calm sea, and left to settle. */
    private fun inHarbour(design: CraftDesign): Pair<World, Vessel> {
        val world = World.default(catalog)
        world.weatherConfig = WeatherConfig(intensity = WeatherIntensity.CALM)
        val ship = world.spawnOnSurface(design, World.launchSites.first { it.id == "harbour" })
        run(world, 20.0)
        return world to ship
    }

    @Test
    fun `the Coaster floats level at her waterline`() {
        val (world, ship) = inHarbour(StockCraft.coaster(catalog))
        val terra = world.attractorFor(ship)
        val fixed = terra.toBodyFixed(ship.body.position, terra.rotationAt(world.time), Vec3()).normalizeInPlace()
        val water = terra.radius + terra.ocean!!.surfaceHeight(fixed, world.time)
        val above = ship.body.position.length - water
        assertTrue("not afloat", ship.buoyed && !ship.submerged)
        assertTrue("she lists ${tilt(ship)} degrees", tilt(ship) < 3.0)
        // Her middle a little above the water, in a hull 2.2 m deep: floating, not sitting on it.
        assertTrue("her middle is $above m above the water", above in 0.0..1.0)
    }

    @Test
    fun `on her diesel the Coaster makes six metres a second, and charges her batteries`() {
        val (world, ship) = inHarbour(StockCraft.coaster(catalog))
        val charge = ResourceType.ELECTRIC_CHARGE
        ship.drawCharge(ship.amountOf(charge) / 2.0)
        val flat = ship.amountOf(charge)
        val fuel = ship.amountOf(ResourceType.PROPELLANT)
        world.apply(Command.Stage(ship.id.raw))
        world.apply(Command.SetThrottle(ship.id.raw, 1.0))
        run(world, 60.0)
        assertTrue("she only makes ${speed(world, ship)} m/s", speed(world, ship) > 6.0)
        assertTrue("burnt nothing", ship.amountOf(ResourceType.PROPELLANT) < fuel)
        assertTrue("no charge made: $flat to ${ship.amountOf(charge)}", ship.amountOf(charge) > flat)
    }

    @Test
    fun `she turns on her rudder, and doesn't heel in the turn`() {
        val (world, ship) = inHarbour(StockCraft.coaster(catalog))
        world.apply(Command.Stage(ship.id.raw))
        world.apply(Command.SetThrottle(ship.id.raw, 1.0))
        run(world, 30.0)
        var last = heading(world, ship)
        var turned = 0.0
        var heel = 0.0
        world.apply(Command.SetAttitude(ship.id.raw, 0.0, 1.0, 0.0))
        repeat(40) {
            run(world, 1.0)
            val now = heading(world, ship)
            var step = now - last
            if (step > 180.0) step -= 360.0
            if (step < -180.0) step += 360.0
            turned += step
            last = now
            heel = maxOf(heel, tilt(ship))
        }
        assertTrue("she only turned $turned degrees in forty seconds", kotlin.math.abs(turned) > 90.0)
        assertTrue("she heeled $heel degrees", heel < 10.0)
    }

    @Test
    fun `the Coaster rides out a wild sea where a jet boat goes over`() {
        for (t in listOf(12_160.0, 40_000.0, 80_000.0)) {
            val world = World.default(catalog)
            world.weatherConfig = WeatherConfig(intensity = WeatherIntensity.WILD)
            world.restore(WorldSave(catalogHash = catalog.contentHash, universeTime = t, nextVesselId = 1L))
            val ship = world.spawnOnSurface(StockCraft.coaster(catalog), World.launchSites.first { it.id == "roaring-sea" })
            var most = 0.0
            repeat(600) { run(world, 1.0); most = maxOf(most, tilt(ship)) }
            assertTrue("she went down at t=$t", ship.buoyed && !ship.submerged)
            assertTrue("she rolled $most degrees at t=$t", most < 45.0)
            assertTrue("she flooded at t=$t", ship.flooded.sum() == 0.0)
            assertTrue("she broke at t=$t", ship.broken.none { it })
        }
    }

    @Test
    fun `an electric Coaster runs on her batteries, and runs them down`() {
        val (world, ship) = inHarbour(StockCraft.coaster(catalog, electric = true))
        val charge = ship.amountOf(ResourceType.ELECTRIC_CHARGE)
        world.apply(Command.Stage(ship.id.raw))
        world.apply(Command.SetThrottle(ship.id.raw, 1.0))
        run(world, 60.0)
        assertTrue("she only makes ${speed(world, ship)} m/s", speed(world, ship) > 5.0)
        assertTrue("no charge used", ship.amountOf(ResourceType.ELECTRIC_CHARGE) < charge - 30.0)
    }

    @Test
    fun `the Schooner sails in a fresh breeze, keeps her masts, and doesn't lie over`() {
        // On the beam, and dead astern, where she's slower since the wind over her deck is less.
        for ((wind, least) in listOf(Vec3(0.0, 7.0, 0.0) to 3.0, Vec3(7.0, 0.0, 0.0) to 2.5)) {
            val world = World.default(catalog)
            world.steadyWind = wind
            val d = com.rm.apogee.core.orbit.SolarSystem.capeDirection(-8_000.0, 12_000.0)
            val ship = world.spawnOnSurface(StockCraft.schooner(catalog), LaunchSite("sea", "Sea", "terra", com.rm.apogee.core.orbit.SolarSystem.latitudeOf(d), com.rm.apogee.core.orbit.SolarSystem.longitudeOf(d)))
            world.assignOwner(ship, "p1")
            world.seatCrew(ship)
            world.apply(Command.SetSas(ship.id.raw, true))
            run(world, 10.0)
            world.apply(Command.SetThrottle(ship.id.raw, 1.0))
            var heel = 0.0
            repeat(180) { run(world, 1.0); heel = maxOf(heel, tilt(ship)) }
            val bow = ship.body.orientation.rotate(ship.design.orientation.forward, Vec3())
            val ahead = ship.body.linearVelocity.copy().subInPlace(world.attractorFor(ship).surfaceVelocityAt(ship.body.position, Vec3())) dot bow
            assertTrue("only $ahead m/s with the wind at $wind", ahead > least)
            assertTrue("lost a mast", ship.broken.none { it } && ship.defs.count { it.id == "sail-schooner" } == 2)
            assertTrue("she heeled $heel degrees", heel < 20.0)
        }
    }
}
