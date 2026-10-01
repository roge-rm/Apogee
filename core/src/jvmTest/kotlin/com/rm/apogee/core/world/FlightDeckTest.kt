package com.rm.apogee.core.world

import com.rm.apogee.core.craft.CraftDesign
import com.rm.apogee.core.craft.StockCraft
import com.rm.apogee.core.craft.Vessel
import com.rm.apogee.core.math.Quat
import com.rm.apogee.core.math.Vec3
import com.rm.apogee.core.orbit.SolarSystem
import com.rm.apogee.core.part.StockParts
import com.rm.apogee.core.weather.WeatherConfig
import com.rm.apogee.core.weather.WeatherIntensity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Platforms to fly from at sea: the Landing Barge for a helicopter, and the Flat Top, whose
 * catapult throws a Petrel off the bow and whose wires catch one landing from astern.
 */
class FlightDeckTest {
    private val catalog = StockParts.catalog
    private val dt = 1.0 / 60.0

    private fun run(world: World, seconds: Double) = repeat((seconds / dt).toInt()) { world.step(dt) }

    private fun calm(): World = World.default(catalog).also {
        it.weatherConfig = WeatherConfig(intensity = WeatherIntensity.CALM)
        it.steadyWind = Vec3(0.0, 0.0, 0.0)
    }

    /** [design] afloat on the open sea off the Cape, facing east, settled. */
    private fun afloat(world: World, design: CraftDesign): Vessel {
        val d = SolarSystem.capeDirection(-6_000.0, 2_000.0)
        val craft = world.spawnOnSurface(design, LaunchSite("open", "Open sea", "terra", SolarSystem.latitudeOf(d), SolarSystem.longitudeOf(d)))
        world.assignOwner(craft, "p1")
        world.seatCrew(craft)
        run(world, 20.0)
        return craft
    }

    private fun tilt(v: Vessel): Double {
        val deck = v.body.orientation.rotate(v.design.orientation.up, Vec3())
        return Math.toDegrees(kotlin.math.acos((deck dot v.body.position.normalized()).coerceIn(-1.0, 1.0)))
    }

    /** Where design-space point [p] of [deck] is in the world. */
    private fun onDeck(deck: Vessel, p: Vec3): Vec3 =
        deck.body.orientation.rotate(p.copy().subInPlace(deck.centerOfMass()), Vec3()).addInPlace(deck.body.position)

    /** [design] put on [deck] at design-space point [at] on its deck, facing the way the deck does, [above] metres up. */
    private fun placeOn(world: World, deck: Vessel, design: CraftDesign, at: Vec3, above: Double): Vessel {
        val up = deck.body.orientation.rotate(deck.design.orientation.up, Vec3())
        val where = onDeck(deck, at).addScaledInPlace(up, above)
        val craft = world.spawnAt(design, "terra", where, deck.body.velocityAtOffset(where.copy().subInPlace(deck.body.position), Vec3()), deck.body.orientation.copy())
        world.assignOwner(craft, "p1")
        return craft
    }

    private fun speedAcross(craft: Vessel, deck: Vessel): Double {
        val under = deck.body.velocityAtOffset(craft.body.position.copy().subInPlace(deck.body.position), Vec3())
        return craft.body.linearVelocity.copy().subInPlace(under).length
    }

    @Test
    fun `the Landing Barge floats level, and a Hummingbird lands on her on its own`() {
        val world = calm()
        val barge = afloat(world, StockCraft.landingBarge(catalog))
        assertTrue("not afloat", barge.buoyed && !barge.submerged)
        assertTrue("she lists ${tilt(barge)} degrees", tilt(barge) < 2.0)
        val heli = placeOn(world, barge, StockCraft.hummingbird(catalog), Vec3(0.0, -10.0, 1.5), 40.0)
        Aloft.spinUp(heli, 0.6)
        world.apply(Command.SetThrottle(heli.id.raw, 0.6))
        world.apply(Command.SetAutopilot(heli.id.raw, autoBurn = false, autoLand = true))
        run(world, 90.0)
        assertSame("not down on her deck", barge, heli.standingOn)
        assertTrue("it broke: ${heli.broken.count { it }}", heli.broken.none { it })
    }

    @Test
    fun `the Flat Top floats level`() {
        val world = calm()
        val ship = afloat(world, StockCraft.flatTop(catalog))
        assertTrue("not afloat", ship.buoyed && !ship.submerged)
        assertTrue("she lists ${tilt(ship)} degrees", tilt(ship) < 2.0)
    }

    @Test
    fun `the catapult throws a Petrel off the bow, and it climbs away`() {
        val world = calm()
        val ship = afloat(world, StockCraft.flatTop(catalog))
        val track = ship.design.parts.indexOfFirst { it.partId == "catapult-deck" }
        // The near end of the track, a few metres along it.
        val start = Vec3().setTo(ship.design.parts[track].position).addInPlace(Vec3(0.0, -32.0, 0.0))
        val plane = placeOn(world, ship, StockCraft.petrel(catalog), start, 2.2)
        world.apply(Command.SetBrakes(plane.id.raw, false))
        run(world, 3.0)
        assertSame("the Petrel isn't on her deck", ship, plane.standingOn)
        // Standing on her isn't joined to her, and isn't offered.
        assertTrue("not listed as her rider", plane.id.raw in world.systemsOf(ship).riders)
        assertEquals(ship.id.raw, world.systemsOf(plane).standingOn)
        assertNull("joined to her deck", world.joinToNeighbour(plane))
        assertNull("joined to what's on her", world.joinToNeighbour(ship))
        world.apply(Command.Stage(plane.id.raw))
        world.apply(Command.SetThrottle(plane.id.raw, 1.0))
        var fastest = 0.0
        var off = false
        repeat((20.0 / dt).toInt()) {
            val up = plane.body.position.normalized()
            val nose = Math.toDegrees(kotlin.math.asin((plane.forward() dot up).coerceIn(-1.0, 1.0)))
            // Off the end, the stick back to eight degrees.
            val pitch = if (off) ((8.0 - nose) / 6.0).coerceIn(-1.0, 1.0) else 0.0
            world.apply(Command.SetAttitude(plane.id.raw, pitch, 0.0, 0.0))
            world.step(dt)
            fastest = maxOf(fastest, speedAcross(plane, ship))
            if (!plane.touchingGround) off = true
        }
        assertTrue("it only got to $fastest m/s", fastest > 45.0)
        val height = world.attractorFor(plane).altitudeOf(plane.body.position)
        assertTrue("it's only $height m up", height > 40.0)
        assertTrue("it broke: ${plane.broken.count { it }}", plane.broken.none { it })
    }

    @Test
    fun `a Petrel landing from astern with its hook down catches a wire and stops on the deck`() {
        val world = calm()
        val ship = afloat(world, StockCraft.flatTop(catalog))
        val wires = ship.design.parts.indexOfFirst { it.partId == "gear-arrest" }
        val gear = ship.design.parts[wires].position
        // 20 m astern of the wires, just over the deck, at 35 m/s, nose a little up, gear and hook down.
        val plane = placeOn(world, ship, StockCraft.petrel(catalog), Vec3().setTo(gear).addInPlace(Vec3(0.0, -20.0, 0.0)), 2.6)
        val ahead = ship.body.orientation.rotate(ship.design.orientation.forward, Vec3())
        val right = ahead.copy().crossInPlace(ship.body.orientation.rotate(ship.design.orientation.up, Vec3())).normalizeInPlace()
        plane.body.orientation.setTo(Quat.fromAxisAngle(right, Math.toRadians(5.0)) * plane.body.orientation).normalizeInPlace()
        plane.body.linearVelocity.addScaledInPlace(ahead, 35.0)
        plane.control.deployed = true
        world.apply(Command.Stage(plane.id.raw))
        world.apply(Command.SetThrottle(plane.id.raw, 0.0))
        run(world, 15.0)
        assertTrue("never stopped: ${speedAcross(plane, ship)} m/s", speedAcross(plane, ship) < 0.5)
        assertSame("not on her deck", ship, plane.standingOn)
        // Caught: stopped within the wires' runout, well short of the bow.
        val onWires = ship.body.orientation.inverseRotate(plane.body.position.copy().subInPlace(onDeck(ship, gear)), Vec3())
        assertTrue("it ran ${onWires.y} m past the wires", onWires.y < 70.0)
        assertTrue("it broke: ${plane.broken.count { it }}", plane.broken.none { it })
    }

    @Test
    fun `thrown off hands off with SAS off, the Petrel flies away instead of into the sea`() {
        val world = calm()
        val ship = afloat(world, StockCraft.flatTop(catalog))
        val track = ship.design.parts.indexOfFirst { it.partId == "catapult-deck" }
        val start = Vec3().setTo(ship.design.parts[track].position).addInPlace(Vec3(0.0, -32.0, 0.0))
        val plane = placeOn(world, ship, StockCraft.petrel(catalog), start, 2.2)
        world.apply(Command.SetBrakes(plane.id.raw, false))
        world.apply(Command.SetSas(plane.id.raw, false))
        run(world, 3.0)
        world.apply(Command.Stage(plane.id.raw))
        world.apply(Command.SetThrottle(plane.id.raw, 1.0))
        var lowest = Double.MAX_VALUE
        var off = false
        repeat((12.0 / dt).toInt()) {
            world.step(dt)
            if (!plane.touchingGround) off = true
            if (off) lowest = minOf(lowest, world.attractorFor(plane).altitudeOf(plane.body.position))
        }
        assertTrue("never left the deck", off)
        assertTrue("SAS still off", plane.control.sasEnabled)
        assertTrue("it came down to $lowest m", lowest > 3.0)
        assertTrue("it broke: ${plane.broken.count { it }}", plane.broken.none { it })
    }

    @Test
    fun `a Petrel parked on her deck is known to be there after a save`() {
        val world = calm()
        val ship = afloat(world, StockCraft.flatTop(catalog))
        val plane = placeOn(world, ship, StockCraft.petrel(catalog), Vec3(0.0, -60.0, 1.5), 2.2)
        run(world, 5.0)
        assertSame(ship, world.deckUnder(plane))
        assertNull("the ship on her own plane", world.deckUnder(ship))
        val restored = World.default(catalog)
        restored.restore(world.save())
        val again = restored.vessels.single { it.name == "Petrel" }
        assertEquals("Flat Top", restored.deckUnder(again)?.name)
    }

    @Test
    fun `the Flat Top's deck gear loads lying flat, the way it was built`() {
        val built = StockCraft.flatTop(catalog)
        val loaded = SaveMigration.migrate(built, catalog).design!!
        for (i in built.parts.indices) {
            assertTrue("${built.parts[i].partId} turned on loading", loaded.parts[i].rotation.approxEqualsRotation(built.parts[i].rotation, 1e-9))
        }
    }
}
