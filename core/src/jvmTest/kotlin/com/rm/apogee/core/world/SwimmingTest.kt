package com.rm.apogee.core.world

import com.rm.apogee.core.craft.CraftDesign
import com.rm.apogee.core.craft.StockCraft
import com.rm.apogee.core.craft.Vessel
import com.rm.apogee.core.crew.CrewStatus
import com.rm.apogee.core.math.Vec3
import com.rm.apogee.core.orbit.SolarSystem
import com.rm.apogee.core.part.StockParts
import com.rm.apogee.core.weather.WeatherConfig
import com.rm.apogee.core.weather.WeatherIntensity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs

/**
 * Someone in the sea: swimming, diving and walking on the bottom, the cold, what their suit can
 * take, and getting back aboard.
 */
class SwimmingTest {
    private val catalog = StockParts.catalog
    private val dt = 1.0 / 60.0

    private fun run(world: World, seconds: Double) = repeat((seconds / dt).toInt()) { world.step(dt) }

    private fun world(): World = World.default(catalog).also {
        it.weatherConfig = WeatherConfig(intensity = WeatherIntensity.CALM)
        it.steadyWind = Vec3(0.0, 0.0, 0.0)
    }

    /** A spot [east] and [north] metres from the Cape. Out at (-3 km, 2 km) the sea's 24 m deep. */
    private fun site(east: Double = -3_000.0, north: Double = 2_000.0): LaunchSite {
        val d = SolarSystem.capeDirection(east, north)
        return LaunchSite("sea", "Sea", "terra", SolarSystem.latitudeOf(d), SolarSystem.longitudeOf(d))
    }

    private fun launch(world: World, design: CraftDesign, where: LaunchSite = site()): Vessel {
        val craft = world.spawnOnSurface(design, where)
        world.assignOwner(craft, "p1")
        world.seatCrew(craft)
        return craft
    }

    /**
     * Someone out of a Coaster and over her side, [off] metres from her middle, settled in the
     * water there. Going outside puts them on her deck.
     */
    private fun overboard(world: World, ship: Vessel = launch(world, StockCraft.coaster(catalog)), off: Double = 8.0): Pair<Vessel, Vessel> {
        run(world, 10.0)
        val member = ship.crew.first { it.isNotEmpty() }.first()
        val suit = world.eva(ship.id.raw, member)
        assertNotNull("couldn't go outside", suit)
        val up = ship.body.position.normalized()
        val side = ship.forward().crossInPlace(up).normalizeInPlace()
        suit!!.body.position.setTo(ship.body.position).addScaledInPlace(side, off)
        val terra = world.attractorFor(ship)
        val fixed = terra.toBodyFixed(suit.body.position, terra.rotationAt(world.time), Vec3()).normalizeInPlace()
        suit.body.position.normalizeInPlace().mulInPlace(terra.radius + terra.ocean!!.surfaceHeight(fixed, world.time) - 0.75)
        terra.surfaceVelocityAt(suit.body.position, suit.body.linearVelocity)
        run(world, 10.0)
        return ship to suit
    }

    private fun up(v: Vessel): Double {
        val axis = v.body.orientation.rotate(Vec3.unitY(), Vec3())
        return Math.toDegrees(kotlin.math.acos((axis dot v.body.position.normalized()).coerceIn(-1.0, 1.0)))
    }

    /** Speed across the water, which drifts with the tide and the waves. */
    private fun flatSpeed(world: World, v: Vessel): Double {
        val terra = world.attractorFor(v)
        val rotation = terra.rotationAt(world.time)
        val fixed = terra.toBodyFixed(v.body.position, rotation, Vec3()).normalizeInPlace()
        val sample = terra.ocean!!.sample(fixed, world.time, com.rm.apogee.core.sea.SeaSample(), below = maxOf(0.0, world.depthOf(v)))
        val rel = v.body.linearVelocity.copy().subInPlace(terra.surfaceVelocityAt(v.body.position, Vec3())).subInPlace(rotation.rotate(sample.velocity, Vec3()))
        val up = v.body.position.normalized()
        return rel.addScaledInPlace(up, -(rel dot up)).length
    }

    @Test
    fun `over the side they tread water, upright with their head out`() {
        val world = world()
        val (_, suit) = overboard(world)
        assertTrue("not swimming", suit.swimming)
        assertTrue("not upright: ${up(suit)} degrees", up(suit) < 15.0)
        // Their middle three quarters of a metre down, so their head's out.
        val depth = world.depthOf(suit)
        assertTrue("treading water ${depth} m down", depth in 0.4..1.1)
        assertTrue("alive", suit.broken.none { it })
    }

    @Test
    fun `they swim along at about a metre a second`() {
        val world = world()
        // Well clear of her, so they don't swim into her.
        val (_, suit) = overboard(world, off = 25.0)
        world.apply(Command.SetAttitude(suit.id.raw, 1.0, 0.0, 0.0))
        run(world, 10.0)
        assertEquals("swimming at ${flatSpeed(world, suit)} m/s", 1.0, flatSpeed(world, suit), 0.25)
        assertTrue("sank swimming: ${world.depthOf(suit)} m", world.depthOf(suit) < 1.5)
    }

    @Test
    fun `they dive, hold the depth they stop at, and come back up`() {
        val world = world()
        val (_, suit) = overboard(world)
        world.apply(Command.SetBallast(suit.id.raw, 1))
        run(world, 12.0)
        world.apply(Command.SetBallast(suit.id.raw, 0))
        val down = world.depthOf(suit)
        assertTrue("only got to $down m", down > 6.0)
        run(world, 20.0)
        assertEquals("didn't hold $down m", down, world.depthOf(suit), 1.0)
        world.apply(Command.SetBallast(suit.id.raw, -1))
        run(world, 30.0)
        assertTrue("still ${world.depthOf(suit)} m down", world.depthOf(suit) < 1.2)
    }

    @Test
    fun `down on the bottom they walk on it, slowly, and swim back up off it`() {
        val world = world()
        val (_, suit) = overboard(world)
        world.apply(Command.SetBallast(suit.id.raw, 1))
        run(world, 50.0)
        assertTrue("never got down: ${world.depthOf(suit)} m", world.depthOf(suit) > 20.0)
        assertTrue("not on their feet", suit.onFeet && !suit.swimming)
        world.apply(Command.SetBallast(suit.id.raw, 0))
        world.apply(Command.SetAttitude(suit.id.raw, 1.0, 0.0, 0.0))
        run(world, 8.0)
        assertEquals("walking at ${flatSpeed(world, suit)} m/s", Walking.SEABED_SPEED, flatSpeed(world, suit), 0.2)
        world.apply(Command.SetAttitude(suit.id.raw, 0.0, 0.0, 0.0))
        world.apply(Command.SetBallast(suit.id.raw, -1))
        run(world, 45.0)
        assertTrue("still ${world.depthOf(suit)} m down", world.depthOf(suit) < 1.2)
    }

    @Test
    fun `they won't swim down deeper than their suit can take, and put there, it crushes them`() {
        val world = world()
        val (_, suit) = overboard(world, launch(world, StockCraft.coaster(catalog), site(-6_000.0, 3_000.0)))
        world.apply(Command.SetBallast(suit.id.raw, 1))
        run(world, 90.0)
        val limit = world.suitDeepest(world.attractorFor(suit))
        assertTrue("dived to ${world.depthOf(suit)} m, past $limit", world.depthOf(suit) < limit)
        assertTrue("crushed", suit.broken.none { it })
        // Carried down, well past it, though clear of the floor sixty metres down, and left there.
        // Still diving, they'd swim back up to where it's safe.
        world.apply(Command.SetBallast(suit.id.raw, 0))
        val member = suit.crew[0].first()
        suit.body.position.addScaledInPlace(suit.body.position.normalized(), -(50.0 - world.depthOf(suit)))
        suit.control.holdDepthAt = world.depthOf(suit)
        run(world, 180.0)
        assertEquals("at ${world.depthOf(suit)} m, health ${suit.health.toList()}", CrewStatus.LOST, world.crew.getValue(member).status)
    }

    @Test
    fun `the cold gets to them in the water, and kills them if they stay`() {
        val world = world()
        val (_, suit) = overboard(world)
        assertTrue("not getting cold", suit.chill > 0.0)
        val member = suit.crew[0].first()
        suit.chill = 0.999
        run(world, 30.0)
        val lost = world.crew.getValue(member)
        assertEquals(CrewStatus.LOST, lost.status)
        assertEquals(World.COLD_REASON, lost.lostHow)
    }

    @Test
    fun `how long someone lasts goes by how cold the sea is`() {
        val world = world()
        val terra = world.system.bodies.getValue("terra")
        val equator = Vec3(terra.radius, 0.0, 0.0)
        val pole = Vec3(0.0, terra.radius, 0.0)
        val warm = SeaCold.lasts(terra, terra.rotationAt(0.0).rotate(equator, Vec3()), 0.0, 0.0)
        val cold = SeaCold.lasts(terra, terra.rotationAt(0.0).rotate(pole, Vec3()), 0.0, 0.0)
        assertTrue("$warm s at the equator", warm > 3.0 * 3_600.0)
        assertTrue("$cold s at the pole", cold < 20.0 * 60.0)
        val aurantia = world.system.bodies.getValue("aurantia")
        assertEquals(SeaCold.ALIEN_SEA, SeaCold.lasts(aurantia, Vec3(aurantia.radius, 0.0, 0.0), 0.0, 0.0), 1e-9)
    }

    @Test
    fun `from the water they can't climb straight into a ship's wheelhouse, but they can up her ladder`() {
        val world = world()
        val (ship, suit) = overboard(world)
        assertNull("boarded from the water", world.seatInReach(suit))
        // Beside her ladder.
        val ladder = ship.defs.indices.first { ship.defs[it].id == "ladder-boat" }
        val at = ship.partPositionWorld(ladder, Vec3())
        val out = ship.body.orientation.rotate(ship.design.parts[ladder].rotation.rotate(Vec3.unitX(), Vec3()), Vec3())
        val up = at.normalized()
        suit.body.position.setTo(at).addScaledInPlace(out, 0.6).addScaledInPlace(up, -1.2)
        suit.body.linearVelocity.setTo(ship.body.linearVelocity)
        run(world, 1.0)
        assertNull("climbed out up her side", world.climbSpot(suit))
        world.apply(Command.Grab(suit.id.raw, true))
        world.apply(Command.SetAttitude(suit.id.raw, 1.0, 0.0, 0.0))
        run(world, 6.0)
        val spot = world.climbSpot(suit)
        assertNotNull("nowhere to climb out at the top", spot)
        assertSame(ship, spot!!.first)
        world.apply(Command.SetAttitude(suit.id.raw, 0.0, 0.0, 0.0))
        world.apply(Command.ClimbOut(suit.id.raw))
        run(world, 3.0)
        assertSame("not on her deck", ship, suit.standingOn)
        assertFalse(suit.swimming)
        assertTrue("hurt climbing out", suit.broken.none { it })
    }

    @Test
    fun `in a swell, a ship's side still can't be climbed without her ladder`() {
        // Rolled a little by the swell, her side leans out over the water, and going down it from
        // the air the climb found her side halfway and called it somewhere to stand.
        val world = World.default(catalog).also { it.weatherConfig = WeatherConfig(intensity = WeatherIntensity.NORMAL) }
        val (_, suit) = overboard(world, off = 2.9)
        var spin = 0.0
        repeat(60) {
            run(world, 0.5)
            assertNull("offered a climb up her side at ${it * 0.5} s", world.climbSpot(suit))
            spin = maxOf(spin, abs(suit.body.angularVelocity dot suit.body.position.normalized()))
        }
        // Left alone in it, they don't spin round and round either.
        assertTrue("turning at $spin rad/s", spin < 0.2)
    }

    @Test
    fun `stopped halfway up a ladder, they hang there instead of sliding back down it`() {
        val world = world()
        val (ship, suit) = overboard(world)
        val ladder = ship.defs.indices.first { ship.defs[it].id == "ladder-boat" }
        val at = ship.partPositionWorld(ladder, Vec3())
        val out = ship.body.orientation.rotate(ship.design.parts[ladder].rotation.rotate(Vec3.unitX(), Vec3()), Vec3())
        suit.body.position.setTo(at).addScaledInPlace(out, 0.6).addScaledInPlace(at.normalized(), -1.2)
        suit.body.linearVelocity.setTo(ship.body.linearVelocity)
        run(world, 1.0)
        world.apply(Command.Grab(suit.id.raw, true))
        world.apply(Command.SetAttitude(suit.id.raw, 1.0, 0.0, 0.0))
        run(world, 2.0)
        world.apply(Command.SetAttitude(suit.id.raw, 0.0, 0.0, 0.0))
        run(world, 0.5)
        fun height() = ship.body.orientation.inverseRotate(suit.body.position.copy().subInPlace(ship.body.position), Vec3()).z
        val stopped = height()
        run(world, 5.0)
        assertTrue("let go of the ladder", world.onLadder(suit))
        assertEquals("slid ${stopped - height()} m down it", stopped, height(), 0.1)
    }

    @Test
    fun `they climb out onto a jet ski, which sits low in the water, and straight into its saddle`() {
        for (weather in listOf(WeatherIntensity.CALM, WeatherIntensity.NORMAL)) {
            val world = world().also { it.weatherConfig = WeatherConfig(intensity = weather) }
            val ski = launch(world, StockCraft.jetSki(catalog))
            val (_, suit) = overboard(world, ski, 1.6)
            val member = suit.crew[0].first()
            var spot = world.climbSpot(suit)
            var waited = 0
            while (spot == null && waited++ < 20) { run(world, 0.25); spot = world.climbSpot(suit) }
            assertNotNull("$weather: nowhere to climb out", spot)
            world.apply(Command.ClimbOut(suit.id.raw))
            run(world, 0.5)
            // Stood up on it, the little thing rolls them off, so they sit straight in its saddle.
            assertTrue("$weather: not aboard it", ski.crew.any { member in it })
        }
    }

    @Test
    fun `a jump off a ship's deck into the sea is nothing, and a fall from high up kills`() {
        for ((height, lives) in listOf(5.0 to true, 25.0 to false)) {
            val world = world()
            val (_, suit) = overboard(world)
            val member = suit.crew[0].first()
            suit.body.position.addScaledInPlace(suit.body.position.normalized(), height)
            suit.body.linearVelocity.setTo(world.attractorFor(suit).surfaceVelocityAt(suit.body.position, Vec3()))
            run(world, 6.0)
            assertEquals("from $height m", lives, world.crew.getValue(member).status != CrewStatus.LOST)
        }
    }

    @Test
    fun `out of a base on the bottom they come out at its depth, and swim straight back in`() {
        val world = world()
        val base = launch(world, StockCraft.seaFloorBase(catalog))
        run(world, 10.0)
        world.apply(Command.SetBallast(base.id.raw, 1))
        run(world, 200.0)
        assertTrue("not founded", world.anchor(base))
        val member = base.crew.first { it.isNotEmpty() }.first()
        val suit = world.eva(base.id.raw, member)
        assertNotNull("couldn't go outside: ${world.evaRefusal}", suit)
        run(world, 5.0)
        assertTrue("teleported up to ${world.depthOf(suit!!)} m", world.depthOf(suit) > 15.0)
        assertTrue("not holding there", abs(world.depthOf(suit) - world.depthOf(base)) < 5.0)
        // Back in through the hatch.
        val back = world.boardCraft(suit.id.raw, base.id.raw)
        assertSame("couldn't get back in", base, back)
    }

    @Test
    fun `too deep for a suit, nobody goes outside`() {
        val world = world()
        val base = launch(world, StockCraft.seaFloorBase(catalog), site(-6_000.0, 2_000.0))
        run(world, 10.0)
        world.apply(Command.SetBallast(base.id.raw, 1))
        run(world, 240.0)
        val member = base.crew.first { it.isNotEmpty() }.first()
        assertNull("out at ${world.depthOf(base)} m", world.eva(base.id.raw, member))
        assertTrue(world.evaRefusal, world.evaRefusal.startsWith(World.TOO_DEEP))
        // And the crew panel says so before anyone tries.
        assertEquals(World.TOO_DEEP, world.systemsOf(base).evaBlocked)
    }
}
