package com.rm.apogee.core.world

import com.rm.apogee.core.craft.CraftDesign
import com.rm.apogee.core.craft.DockedOrigin
import com.rm.apogee.core.craft.PlacedPart
import com.rm.apogee.core.craft.StockCraft
import com.rm.apogee.core.craft.Vessel
import com.rm.apogee.core.math.Quat
import com.rm.apogee.core.math.Vec3
import com.rm.apogee.core.part.ResourceType
import com.rm.apogee.core.part.StockParts
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.sqrt

/** Digging, refining, unloading and surveying. */
class IndustryTest {
    private val catalog = StockParts.catalog
    private val dt = 1.0 / 60.0
    private val lunaSite = World.launchSites.first { it.id == World.LUNA_TEST_SITE }

    private fun run(world: World, seconds: Double) = repeat((seconds / dt).toInt()) { world.step(dt) }
    private fun ore(v: Vessel) = v.amountOf(ResourceType.ORE)

    /** The Prospector standing on Luna's mare, with its legs down, settled. */
    private fun landed(world: World): Vessel {
        val craft = world.spawnOnSurface(StockCraft.prospector(catalog), lunaSite, pad = 1)
        run(world, 3.0)
        return craft
    }

    @Test
    fun `a landed drill digs ore as rich as the ground, once its bit is down`() {
        val world = World.default(catalog)
        val craft = landed(world)
        world.apply(Command.SetIndustry(craft.id.raw, drilling = true, refining = false))
        run(world, 1.0)
        assertEquals(DrillState.EXTENDING, craft.drillState)
        run(world, 5.0)
        assertEquals("not digging", DrillState.DIGGING, craft.drillState)
        assertTrue("mare ore ${craft.drillOre}", craft.drillOre in 0.4..0.7)
        val had = ore(craft)
        run(world, 10.0)
        assertEquals(10.0 * craft.drillOre, ore(craft) - had, 0.2)
        assertEquals("water from basalt", 0.0, craft.amountOf(ResourceType.WATER), 1e-9)
    }

    @Test
    fun `a flat drill, or one in flight, digs nothing`() {
        val world = World.default(catalog)
        val craft = landed(world)
        world.apply(Command.SetIndustry(craft.id.raw, drilling = true, refining = false))
        run(world, 6.0)
        craft.drawCharge(craft.amountOf(ResourceType.ELECTRIC_CHARGE))
        run(world, 1.0)
        val had = ore(craft)
        run(world, 5.0)
        assertEquals(DrillState.NO_POWER, craft.drillState)
        assertEquals(had, ore(craft), 1e-9)

        val flying = World.default(catalog)
        val luna = flying.system.body("luna")
        val r = luna.radius + 20_000.0
        val probe = flying.spawnAt(StockCraft.prospector(catalog), "luna", Vec3(r, 0.0, 0.0), Vec3(0.0, 0.0, sqrt(luna.gravitationalParameter / r)), Quat.identity())
        flying.apply(Command.SetIndustry(probe.id.raw, drilling = true, refining = false))
        run(flying, 2.0)
        assertEquals(DrillState.MOVING, probe.drillState)
        assertEquals(0.0, ore(probe), 1e-9)
    }

    @Test
    fun `a converter makes propellant from water at its recipe rate, and stops when dry or full`() {
        val world = World.default(catalog)
        val luna = world.system.body("luna")
        val r = luna.radius + 20_000.0
        val craft = world.spawnAt(StockCraft.prospector(catalog), "luna", Vec3(r, 0.0, 0.0), Vec3(0.0, 0.0, sqrt(luna.gravitationalParameter / r)), Quat.identity())
        val all = craft.defs.indices.toList()
        craft.takeFrom(all, ResourceType.PROPELLANT, 100.0)
        craft.takeFrom(all, ResourceType.MONOPROPELLANT, 5.0)
        craft.putInto(all, ResourceType.WATER, 50.0)
        val prop = craft.amountOf(ResourceType.PROPELLANT)
        val mono = craft.amountOf(ResourceType.MONOPROPELLANT)
        world.apply(Command.SetIndustry(craft.id.raw, drilling = false, refining = true))
        run(world, 10.0)
        assertEquals("water used", 40.0, craft.amountOf(ResourceType.WATER), 0.2)
        assertEquals("propellant made", prop + 8.0, craft.amountOf(ResourceType.PROPELLANT), 0.2)
        assertEquals("monopropellant made", mono + 1.0, craft.amountOf(ResourceType.MONOPROPELLANT), 0.05)
        // Dry, so it stops.
        run(world, 60.0)
        assertEquals(0.0, craft.amountOf(ResourceType.WATER), 1e-6)
        // Full, so nothing more goes in and nothing more gets used.
        craft.putInto(all, ResourceType.PROPELLANT, 1_000.0)
        craft.putInto(all, ResourceType.MONOPROPELLANT, 1_000.0)
        craft.putInto(all, ResourceType.ORE, 20.0)
        run(world, 5.0)
        assertEquals("ore used with nowhere to put it", 20.0, ore(craft), 1e-6)
    }

    @Test
    fun `parked, a drill keeps digging on the ledger the same as it would stepped`() {
        val stepped = World.default(catalog)
        val a = landed(stepped)
        stepped.apply(Command.SetIndustry(a.id.raw, drilling = true, refining = false))
        run(stepped, 6.0)
        val ledger = World.default(catalog)
        val b = landed(ledger)
        ledger.apply(Command.SetIndustry(b.id.raw, drilling = true, refining = false))
        run(ledger, 6.0)
        val startA = ore(a)
        val startB = ore(b)
        run(stepped, 60.0)
        // The same minute, worked out in one go.
        b.powerSettledAt = ledger.time
        ledger.settlePower(b, ledger.time + 60.0)
        assertEquals(ore(a) - startA, ore(b) - startB, 0.02 * (ore(a) - startA))
    }

    /** A pad deck with an Ore Silo beside it, founded at the Cape. */
    private fun siloPad(world: World): Vessel {
        val pad = StockCraft.padBase(catalog)
        val design = pad.copy(name = "Silo Pad", parts = pad.parts.map { if (it.partId == "base-depot") it.copy(partId = "base-silo") else it })
        val site = World.launchSites.first { it.id == "cape" }
        val base = world.spawnOnSurface(design, site)
        run(world, 3.0)
        assertTrue("could not found it", world.anchor(base))
        return base
    }

    @Test
    fun `a craft on a base's pad unloads its ore into the silo`() {
        val world = World.default(catalog)
        val base = siloPad(world)
        val craft = world.spawnOnSurface(StockCraft.prospector(catalog), World.launchSites.first { it.id == "cape" }, pad = 3)
        run(world, 1.0)
        // Set down on the deck, the way the base tests do it.
        val deck = base.defs.indexOfFirst { it.id == "base-pad" }
        val up = base.body.position.copy().normalizeInPlace()
        craft.wake()
        craft.body.position.setTo(up).mulInPlace(base.partPositionWorld(deck).length + 3.0)
        world.attractorFor(craft).surfaceVelocityAt(craft.body.position, craft.body.linearVelocity)
        craft.body.orientation.setTo(com.rm.apogee.core.math.quatFromTo(craft.body.orientation.rotate(Vec3.unitY(), Vec3()), up) * craft.body.orientation)
        run(world, 4.0)
        assertTrue("not on the pad", world.serviceFor(craft)?.base === base)
        assertFalse("unloads with nothing aboard", world.canUnload(craft))
        craft.putInto(craft.defs.indices.toList(), ResourceType.ORE, 100.0)
        assertTrue(world.canUnload(craft))
        world.apply(Command.Unload(craft.id.raw, true))
        run(world, 6.0)
        assertEquals("in the silo", 100.0, base.amountOf(ResourceType.ORE), 1e-6)
        assertEquals(0.0, ore(craft), 1e-6)
        assertFalse("still unloading", world.isUnloading(craft.id))
    }

    /** A Prospector with a Broad Ore Bin docked onto it in flight, as one craft. */
    private fun dockedPair(): CraftDesign {
        val prospector = StockCraft.prospector(catalog)
        val parts = ArrayList(prospector.parts)
        val chute = parts.indexOfFirst { it.partId == "chute-canopy" }
        parts.add(
            PlacedPart(
                "bin-ore-broad", Vec3(0.0, 10.5, 0.0), parentIndex = chute,
                dockedFrom = DockedOrigin(name = "Hold"),
            ),
        )
        return prospector.copy(name = "Pair", parts = parts)
    }

    @Test
    fun `two craft docked in flight move ore between them, both ways`() {
        val world = World.default(catalog)
        val luna = world.system.body("luna")
        val r = luna.radius + 20_000.0
        val pair = world.spawnAt(dockedPair(), "luna", Vec3(r, 0.0, 0.0), Vec3(0.0, 0.0, sqrt(luna.gravitationalParameter / r)), Quat.identity())
        val hold = pair.defs.indexOfFirst { it.id == "bin-ore-broad" }
        val own = pair.defs.indexOfFirst { it.id == "bin-ore" }
        pair.putInto(listOf(own), ResourceType.ORE, 100.0)
        assertTrue(world.canUnload(pair))
        world.apply(Command.Unload(pair.id.raw, true))
        run(world, 10.0)
        assertEquals("not moved into the hold", 100.0, pair.amountInPart(hold, ResourceType.ORE), 1e-6)
        assertEquals(0.0, pair.amountInPart(own, ResourceType.ORE), 1e-6)
        assertFalse("still unloading", world.isUnloading(pair.id))
    }

    /** The Surveyor around Luna at [height], with its orbit tipped [inclination] degrees. */
    private fun surveyor(world: World, inclination: Double, height: Double = 40_000.0): Vessel {
        val luna = world.system.body("luna")
        val r = luna.radius + height
        val speed = sqrt(luna.gravitationalParameter / r)
        val tilt = Math.toRadians(inclination)
        val velocity = Vec3(0.0, kotlin.math.sin(tilt) * speed, kotlin.math.cos(tilt) * speed)
        val craft = world.spawnAt(StockCraft.surveyor(catalog), "luna", Vec3(r, 0.0, 0.0), velocity, Quat.identity())
        // Its wings out, the way they would be by now.
        craft.control.deployed = true
        for (i in craft.defs.indices) if (craft.defs[i].id == "wing-kite") craft.setLegDeploy(i, 1.0)
        return craft
    }

    private fun coast(world: World, seconds: Double) {
        var t = 0.0
        while (t < seconds) {
            val got = world.advanceOnRails(minOf(60.0, seconds - t))
            if (got <= 0.0) { run(world, 1.0); t += 1.0 } else t += got
        }
    }

    @Test
    fun `a polar orbit surveys Luna in half an orbit, and an equatorial one never does`() {
        val world = World.default(catalog)
        val probe = surveyor(world, inclination = 88.0)
        val period = world.orbitOf(probe).period
        coast(world, 0.45 * period)
        assertFalse("surveyed too soon", "luna" in world.surveyed)
        assertTrue("under way: ${world.surveyShare(probe)}", world.surveyShare(probe) > 0.8)
        coast(world, 0.1 * period)
        assertTrue("not surveyed", "luna" in world.surveyed)
        assertTrue(world.drainEvents().any { it is WorldEvent.Surveyed && it.bodyId == "luna" })
        // Everyone's, and kept.
        val again = World.default(catalog)
        again.restore(world.save())
        assertTrue("luna" in again.surveyed)

        val flat = World.default(catalog)
        val equatorial = surveyor(flat, inclination = 5.0)
        coast(flat, 1.2 * flat.orbitOf(equatorial).period)
        assertFalse("luna" in flat.surveyed)
        assertEquals(0.0, flat.surveyShare(equatorial), 0.0)
    }

    @Test
    fun `drilling and refining survive a save`() {
        val world = World.default(catalog)
        val craft = landed(world)
        world.apply(Command.SetIndustry(craft.id.raw, drilling = true, refining = true))
        run(world, 1.0)
        val again = World.default(catalog)
        again.restore(world.save())
        val back = again.vessel(craft.id)!!
        assertTrue(back.control.drilling)
        assertTrue(back.control.refining)
    }
}
