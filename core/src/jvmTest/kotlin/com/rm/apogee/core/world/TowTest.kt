package com.rm.apogee.core.world

import com.rm.apogee.core.craft.CraftDesign
import com.rm.apogee.core.craft.StockCraft
import com.rm.apogee.core.craft.Vessel
import com.rm.apogee.core.math.Vec3
import com.rm.apogee.core.orbit.SolarSystem
import com.rm.apogee.core.part.StockParts
import com.rm.apogee.core.weather.WeatherConfig
import com.rm.apogee.core.weather.WeatherIntensity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Barges, and moving big things on the water: a tug takes a barge in tow on its winch, hooked to a
 * tow bitt, or pushes it from astern on its knees, and tows a platform that isn't founded yet.
 */
class TowTest {
    private val catalog = StockParts.catalog
    private val dt = 1.0 / 60.0

    private fun run(world: World, seconds: Double) = repeat((seconds / dt).toInt()) { world.step(dt) }

    /** A calm world with [design] afloat [east] metres east of a spot on the open sea off the Cape, facing east. */
    private fun afloat(world: World, design: CraftDesign, east: Double): Vessel {
        val d = SolarSystem.capeDirection(-6_000.0 + east, 2_000.0)
        val craft = world.spawnOnSurface(design, LaunchSite("sea$east", "Sea", "terra", SolarSystem.latitudeOf(d), SolarSystem.longitudeOf(d)))
        world.assignOwner(craft, "p1")
        world.seatCrew(craft)
        return craft
    }

    private fun calm(): World = World.default(catalog).also {
        it.weatherConfig = WeatherConfig(intensity = WeatherIntensity.CALM)
        it.steadyWind = Vec3(0.0, 0.0, 0.0)
    }

    private fun speed(world: World, v: Vessel) =
        v.body.linearVelocity.copy().subInPlace(world.attractorFor(v).surfaceVelocityAt(v.body.position, Vec3())).length

    private fun tilt(v: Vessel): Double {
        val deck = v.body.orientation.rotate(v.design.orientation.up, Vec3())
        return Math.toDegrees(kotlin.math.acos((deck dot v.body.position.normalized()).coerceIn(-1.0, 1.0)))
    }

    @Test
    fun `the Deck Barge floats level, high in the water, and carries a buggy on her deck`() {
        val world = calm()
        val barge = afloat(world, StockCraft.deckBarge(catalog), 0.0)
        run(world, 20.0)
        assertTrue("not afloat", barge.buoyed && !barge.submerged)
        assertTrue("she lists ${tilt(barge)} degrees", tilt(barge) < 2.0)
        // A buggy set down on her deck, a little aft of the middle.
        val up = barge.body.position.normalized()
        val buggy = afloat(world, StockCraft.buggy(catalog), -4.0)
        buggy.body.position.setTo(barge.body.position).addScaledInPlace(up, 1.5 + 1.2)
            .addScaledInPlace(barge.forward(), -4.0)
        buggy.body.orientation.setTo(barge.body.orientation)
        buggy.body.linearVelocity.setTo(barge.body.linearVelocity)
        run(world, 10.0)
        assertSame("the buggy isn't on her deck", barge, buggy.standingOn)
        assertTrue("the buggy broke", buggy.broken.none { it })
    }

    @Test
    fun `the Harbour Tug takes the barge in tow by its bitt and tows her away`() {
        val world = calm()
        val barge = afloat(world, StockCraft.deckBarge(catalog), 0.0)
        val tug = afloat(world, StockCraft.harbourTug(catalog), 45.0)
        run(world, 10.0)
        val target = world.hookTarget(tug)
        assertNotNull("nothing in reach to hook", target)
        assertSame(barge, target!!.craft)
        assertEquals("not hooked to a bitt", "bitt-tow", barge.defs[target.part].id)
        assertTrue(world.hook(tug))
        world.apply(Command.Stage(tug.id.raw))
        world.apply(Command.SetThrottle(tug.id.raw, 1.0))
        run(world, 120.0)
        val line = world.lineOf(tug)
        assertNotNull("the line parted", line)
        assertTrue("the barge only makes ${speed(world, barge)} m/s", speed(world, barge) > 1.0)
        assertTrue("the tug broke", tug.broken.none { it })
        assertTrue("the barge broke", barge.broken.none { it })
    }

    @Test
    fun `the tug pushes the barge from astern on its knees`() {
        val world = calm()
        val barge = afloat(world, StockCraft.deckBarge(catalog), 0.0)
        // Her stern is seventeen metres aft of her middle, and the tug's knees five and a bit ahead
        // of its own.
        val tug = afloat(world, StockCraft.harbourTug(catalog), -23.3)
        run(world, 5.0)
        world.apply(Command.Stage(tug.id.raw))
        world.apply(Command.SetThrottle(tug.id.raw, 1.0))
        run(world, 90.0)
        assertTrue("the barge only makes ${speed(world, barge)} m/s", speed(world, barge) > 1.0)
        assertTrue("the tug fell away from her: ${tug.body.position.distanceTo(barge.body.position)} m", tug.body.position.distanceTo(barge.body.position) < 30.0)
        assertTrue("the tug broke", tug.broken.none { it })
        assertTrue("the barge broke", barge.broken.none { it })
    }

    @Test
    fun `a tug tows a Sea Platform before it's founded`() {
        val world = calm()
        val platform = afloat(world, StockCraft.seaPlatform(catalog), 0.0)
        val tug = afloat(world, StockCraft.harbourTug(catalog), 30.0)
        run(world, 10.0)
        assertTrue(world.hook(tug))
        assertEquals(platform.id, world.lineOf(tug)!!.b)
        world.apply(Command.Stage(tug.id.raw))
        world.apply(Command.SetThrottle(tug.id.raw, 1.0))
        run(world, 90.0)
        assertTrue("the platform only makes ${speed(world, platform)} m/s", speed(world, platform) > 0.8)
    }

    @Test
    fun `a buggy parked on the barge sleeps on her deck, and goes where she's towed`() {
        val world = calm()
        val barge = afloat(world, StockCraft.deckBarge(catalog), 0.0)
        val tug = afloat(world, StockCraft.harbourTug(catalog), 45.0)
        run(world, 10.0)
        val up = barge.body.position.normalized()
        val buggy = afloat(world, StockCraft.buggy(catalog), -4.0)
        buggy.body.position.setTo(barge.body.position).addScaledInPlace(up, 1.5 + 1.2).addScaledInPlace(barge.forward(), -4.0)
        buggy.body.orientation.setTo(barge.body.orientation)
        buggy.body.linearVelocity.setTo(barge.body.linearVelocity)
        world.apply(Command.SetBrakes(buggy.id.raw, true))
        run(world, 20.0)
        assertTrue("the buggy never slept", buggy.dormant)
        assertEquals("asleep, but not on the barge", barge.id, buggy.ridingOn)
        fun onDeck() = barge.body.orientation.inverseRotate(buggy.body.position.copy().subInPlace(barge.body.position), Vec3())
        val where = onDeck()
        assertTrue(world.hook(tug))
        world.apply(Command.Stage(tug.id.raw))
        world.apply(Command.SetThrottle(tug.id.raw, 1.0))
        run(world, 60.0)
        assertTrue("the barge never moved", speed(world, barge) > 0.5)
        assertTrue("the buggy shifted ${onDeck().distanceTo(where)} m on her deck", onDeck().distanceTo(where) < 0.05)
        // Woken, it's on her deck still, going her way.
        world.apply(Command.SetBrakes(buggy.id.raw, false))
        world.apply(Command.SetBrakes(buggy.id.raw, true))
        assertTrue("still asleep", !buggy.dormant)
        run(world, 5.0)
        assertSame("woke up off her deck", barge, buggy.standingOn)
        assertTrue("the buggy broke", buggy.broken.none { it })
    }
}
