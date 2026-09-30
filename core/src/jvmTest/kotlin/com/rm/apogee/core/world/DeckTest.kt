package com.rm.apogee.core.world

import com.rm.apogee.core.craft.CraftDesign
import com.rm.apogee.core.craft.PlacedPart
import com.rm.apogee.core.craft.StockCraft
import com.rm.apogee.core.craft.Vessel
import com.rm.apogee.core.math.Vec3
import com.rm.apogee.core.math.quatFromTo
import com.rm.apogee.core.part.StockParts
import com.rm.apogee.core.weather.WeatherConfig
import com.rm.apogee.core.weather.WeatherIntensity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Another craft's deck is somewhere to stand, drive, land and take off: wheels roll on it, legs
 * take a landing on their springs, brakes hold, and people walk, all moving with the deck.
 */
class DeckTest {
    private val catalog = StockParts.catalog
    private val dt = 1.0 / 60.0

    private fun run(world: World, seconds: Double) = repeat((seconds / dt).toInt()) { world.step(dt) }

    /** [vessel]'s speed across [deck], leaving out the deck's own heave. */
    private fun speedOn(vessel: Vessel, deck: Vessel): Double {
        val under = deck.body.velocityAtOffset(vessel.body.position.copy().subInPlace(deck.body.position), Vec3())
        val rel = vessel.body.linearVelocity.copy().subInPlace(under)
        val up = vessel.body.position.normalized()
        return rel.addScaledInPlace(up, -(rel dot up)).length
    }

    /** The Sea Platform founded out on the open sea west of the Cape, in [weather]. */
    private fun platform(world: World, weather: WeatherIntensity = WeatherIntensity.CALM): Vessel {
        world.weatherConfig = WeatherConfig(intensity = weather)
        val spot = com.rm.apogee.core.orbit.SolarSystem.capeDirection(-6_000.0, 2_000.0)
        val site = LaunchSite("open", "Open sea", "terra", com.rm.apogee.core.orbit.SolarSystem.latitudeOf(spot), com.rm.apogee.core.orbit.SolarSystem.longitudeOf(spot))
        val platform = world.spawnOnSurface(StockCraft.seaPlatform(catalog), site)
        world.assignOwner(platform, "p1")
        run(world, 30.0)
        assertTrue(world.anchor(platform))
        return platform
    }

    private fun deckOf(platform: Vessel) = platform.defs.indices.first { platform.defs[it].id == "deck-sea" }

    @Test
    fun `a buggy drives about on the Sea Platform's deck, and stops on its brakes`() {
        val world = World.default(catalog)
        val platform = platform(world)
        val buggy = world.spawnOnBasePad(StockCraft.buggy(catalog), platform, deckOf(platform))
        run(world, 3.0)
        assertSame("not on the deck", platform, buggy.standingOn)
        world.apply(Command.Stage(buggy.id.raw))
        world.apply(Command.SetThrottle(buggy.id.raw, 1.0))
        // Not for long. It's a deck sixteen metres across.
        run(world, 0.6)
        assertTrue("it only made ${speedOn(buggy, platform)} m/s", speedOn(buggy, platform) > 2.0)
        world.apply(Command.SetThrottle(buggy.id.raw, 0.0))
        world.apply(Command.SetBrakes(buggy.id.raw, true))
        run(world, 3.0)
        assertTrue("still going at ${speedOn(buggy, platform)} m/s", speedOn(buggy, platform) < 0.3)
        assertSame("drove off the deck", platform, buggy.standingOn)
        assertTrue("broke: ${buggy.broken.count { it }}", buggy.broken.none { it })
    }

    @Test
    fun `braked on a platform riding a wild sea, a buggy stays where it was left`() {
        val world = World.default(catalog)
        val platform = platform(world, WeatherIntensity.WILD)
        val deck = deckOf(platform)
        val buggy = world.spawnOnBasePad(StockCraft.buggy(catalog), platform, deck)
        world.apply(Command.SetBrakes(buggy.id.raw, true))
        run(world, 3.0)
        val start = platform.body.orientation.inverseRotate(buggy.body.position.copy().subInPlace(platform.partPositionWorld(deck)), Vec3())
        run(world, 60.0)
        val end = platform.body.orientation.inverseRotate(buggy.body.position.copy().subInPlace(platform.partPositionWorld(deck)), Vec3())
        // A little creep is fair, on brakes that grip at a third of its weight on a deck heaving
        // about under it.
        assertTrue("it wandered ${start.distanceTo(end)} m across the deck", start.distanceTo(end) < 2.0)
        assertSame("not on the deck", platform, buggy.standingOn)
    }

    @Test
    fun `someone walks about on the Sea Platform at walking pace`() {
        val world = World.default(catalog)
        val platform = platform(world)
        val walker = CraftDesign(
            name = "Walker", parts = listOf(PlacedPart(World.SUIT_PART, Vec3.zero())),
            stages = emptyList(), manualStaging = true, catalogHash = catalog.contentHash,
        )
        val suit = world.spawnOnBasePad(walker, platform, deckOf(platform))
        run(world, 2.0)
        assertTrue("not on their feet", suit.touchingGround)
        assertSame(platform, suit.standingOn)
        world.apply(Command.SetAttitude(suit.id.raw, 1.0, 0.0, 0.0))
        run(world, 3.0)
        assertEquals("walking speed", 1.6, speedOn(suit, platform), 0.3)
        val head = suit.body.orientation.rotate(Vec3.unitY(), Vec3())
        assertTrue("fell over", (head dot suit.body.position.normalized()) > 0.9)
    }

    @Test
    fun `a lander dropped onto the deck takes it on its legs`() {
        val world = World.default(catalog)
        val platform = platform(world)
        val lander = world.spawnOnBasePad(StockCraft.lander(catalog), platform, deckOf(platform), legsOut = true)
        run(world, 2.0)
        assertSame("not stood on its legs", platform, lander.standingOn)
        // Lifted clear, and let fall onto the deck from three metres.
        lander.body.position.addScaledInPlace(lander.body.position.normalized(), 3.0)
        run(world, 10.0)
        assertSame("not on the deck", platform, lander.standingOn)
        assertTrue("still moving at ${speedOn(lander, platform)} m/s", speedOn(lander, platform) < 0.3)
        assertTrue("broke: ${lander.broken.count { it }}", lander.broken.none { it })
    }

    /**
     * A deck [tiles] Sea Platform decks long, laid on the airfield's runway under a Sparrow parked
     * thirty metres from its west end, and the Sparrow on it.
     */
    private fun sparrowOnALongDeck(world: World, tiles: Int): Pair<Vessel, Vessel> {
        val jet = world.spawnFor(Command.SpawnCraft(StockCraft.sparrow(catalog), "airfield"), "p1")
        run(world, 2.0)
        val terra = world.attractorFor(jet)
        val up = jet.body.position.normalized()
        val east = jet.forward().addScaledInPlace(up, -(jet.forward() dot up)).normalizeInPlace()
        val fixed = terra.toBodyFixed(jet.body.position, terra.rotationAt(world.time), Vec3()).normalizeInPlace()
        val ground = terra.surfaceRadiusInBodyFrame(fixed)
        val design = CraftDesign(
            name = "Test Deck",
            parts = (0 until tiles).map { PlacedPart("deck-sea", Vec3(TILE * it, 0.0, 0.0), parentIndex = it - 1) },
            stages = emptyList(), manualStaging = true, catalogHash = catalog.contentHash,
        )
        // Out of the way first, then the deck under it.
        jet.body.position.addScaledInPlace(up, DECK_THICKNESS + 0.05)
        val middle = jet.body.position.copy().addScaledInPlace(east, TILE * (tiles - 1) / 2.0 + TILE / 2.0 - 30.0)
        middle.normalizeInPlace().mulInPlace(ground + DECK_THICKNESS / 2.0 + 0.02)
        val stand = quatFromTo(Vec3.unitY(), up)
        val turn = quatFromTo(stand.rotate(Vec3.unitX(), Vec3()), east)
        val deck = world.spawnAt(design, "terra", middle, terra.surfaceVelocityAt(middle, Vec3()), turn * stand, seat = false)
        run(world, 3.0)
        assertSame("the Sparrow isn't on the deck", deck, jet.standingOn)
        return jet to deck
    }

    @Test
    fun `a Sparrow takes off from a long deck`() {
        val world = World.default(catalog)
        val (jet, deck) = sparrowOnALongDeck(world, 32)
        // Along the deck, in the deck's own frame, which goes round with the planet.
        fun along() = deck.body.orientation.inverseRotate(jet.body.position.copy().subInPlace(deck.body.position), Vec3()).x
        val start = along()
        world.apply(Command.Stage(jet.id.raw))
        world.apply(Command.SetThrottle(jet.id.raw, 1.0))
        var off = Double.NaN
        var t = 0.0
        while (t < 40.0) {
            // Stick back at flying speed, to ten degrees nose up.
            val up = jet.body.position.normalized()
            val nose = Math.toDegrees(kotlin.math.asin((jet.forward() dot up).coerceIn(-1.0, 1.0)))
            val pitch = if (speedOn(jet, deck) > 50.0) ((10.0 - nose) / 6.0).coerceIn(-1.0, 1.0) else 0.0
            world.apply(Command.SetAttitude(jet.id.raw, pitch, 0.0, 0.0))
            world.step(dt); t += dt
            if (off.isNaN() && !jet.touchingGround) off = along() - start
        }
        assertTrue("never left the deck", !off.isNaN())
        assertTrue("it rolled $off m, off the end of the deck", off < TILE * 32 - 30.0)
        assertTrue("it's only ${world.attractorFor(jet).altitudeOf(jet.body.position)} m up", world.attractorFor(jet).altitudeOf(jet.body.position) > 50.0)
        assertTrue("broke: ${jet.broken.count { it }}", jet.broken.none { it })
    }

    @Test
    fun `a Sparrow set down on a long deck at forty metres a second brakes to a stop on it`() {
        val world = World.default(catalog)
        val (jet, deck) = sparrowOnALongDeck(world, 32)
        val up = jet.body.position.normalized()
        jet.body.position.addScaledInPlace(up, 1.0)
        jet.body.linearVelocity.addScaledInPlace(jet.forward(), 40.0)
        world.apply(Command.SetBrakes(jet.id.raw, true))
        run(world, 30.0)
        assertSame("not on the deck", deck, jet.standingOn)
        assertTrue("still going at ${speedOn(jet, deck)} m/s", speedOn(jet, deck) < 0.5)
        assertTrue("broke: ${jet.broken.count { it }}", jet.broken.none { it })
    }

    private companion object {
        /** The Sea Platform's deck: its side, and how thick it is. */
        const val TILE = 16.0
        const val DECK_THICKNESS = 0.6
    }

    @Test
    fun `a buggy that was out before the barge it's set on meets her deck where it really is`() {
        // Craft are stepped in the order they came into the world. The buggy's first, so when it
        // meets her deck she hasn't moved on yet this tick, and on Terra, going round with it at a
        // couple of hundred metres a second, that was three metres behind. The buggy fell off one
        // end of her deck well short of it, and stood on air past the other.
        for ((along, on) in listOf(16.5 to true, -15.5 to true, -17.5 to false)) {
            val world = World.default(catalog)
            world.weatherConfig = WeatherConfig(intensity = WeatherIntensity.CALM)
            val d = com.rm.apogee.core.orbit.SolarSystem.capeDirection(-6_000.0, 2_000.0)
            val site = LaunchSite("open", "Open sea", "terra", com.rm.apogee.core.orbit.SolarSystem.latitudeOf(d), com.rm.apogee.core.orbit.SolarSystem.longitudeOf(d))
            val buggy = world.spawnOnSurface(StockCraft.buggy(catalog), World.launchSites.first { it.id == "cape" })
            val barge = world.spawnOnSurface(StockCraft.deckBarge(catalog), site)
            run(world, 10.0)
            val up = barge.body.position.normalized()
            buggy.body.position.setTo(barge.body.position).addScaledInPlace(up, 2.7).addScaledInPlace(barge.forward(), along)
            buggy.body.orientation.setTo(barge.body.orientation)
            buggy.body.linearVelocity.setTo(barge.body.linearVelocity)
            buggy.body.angularVelocity.setTo(barge.body.angularVelocity)
            buggy.wake()
            world.apply(Command.SetBrakes(buggy.id.raw, true))
            run(world, 3.0)
            assertEquals("$along m along her", on, buggy.standingOn === barge || buggy.ridingOn == barge.id)
        }
    }
}
