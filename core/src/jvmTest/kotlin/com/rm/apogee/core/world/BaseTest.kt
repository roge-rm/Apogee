package com.rm.apogee.core.world

import com.rm.apogee.core.craft.StockCraft
import com.rm.apogee.core.craft.Vessel
import com.rm.apogee.core.math.Vec3
import com.rm.apogee.core.part.DockingPort
import com.rm.apogee.core.part.StockParts
import com.rm.apogee.core.physics.PortRef
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * Bases: founded where they stand, impossible to move from then on, and built out by bringing
 * modules up to their connectors.
 */
class BaseTest {
    private val catalog = StockParts.catalog
    private val dt = 1.0 / 60.0
    private val cape = World.launchSites.first { it.id == "cape" }

    /** Where [vessel]'s centre is on the ground, body-fixed. A founded base has to keep it. */
    private fun groundPosition(world: World, vessel: Vessel): Vec3 {
        val body = world.attractorFor(vessel)
        return body.toBodyFixed(vessel.body.position, body.rotationAt(world.time))
    }

    private fun settle(world: World, seconds: Double = 3.0) = repeat((seconds / dt).toInt()) { world.step(dt) }

    private fun foundedBase(world: World): Vessel {
        val base = world.spawnOnSurface(StockCraft.baseCore(catalog), cape)
        settle(world)
        assertTrue("could not found the base", world.anchor(base))
        return base
    }

    @Test
    fun `a founded base isn't moved by a rover driven into it, though it gets hurt`() {
        val world = World.default(catalog)
        val base = foundedBase(world)
        val before = groundPosition(world, base)
        val body = world.attractorFor(base)

        // A rover twenty metres away, coming at it at twenty metres a second.
        val rover = world.spawnOnSurface(StockCraft.rover(catalog), cape, pad = 1)
        settle(world, 1.0)
        val toward = Vec3().setTo(base.body.position).subInPlace(rover.body.position)
        val up = rover.body.position.copy().normalizeInPlace()
        toward.addScaledInPlace(up, -(toward dot up)).normalizeInPlace()
        rover.body.position.setTo(base.body.position).addScaledInPlace(toward, -20.0)
            .addScaledInPlace(up, (rover.body.position.length - base.body.position.length))
        body.surfaceVelocityAt(rover.body.position, rover.body.linearVelocity).addScaledInPlace(toward, 20.0)
        rover.wake()
        repeat((4.0 / dt).toInt()) { world.step(dt) }

        val moved = groundPosition(world, base).distanceTo(before)
        assertTrue("still founded", base.anchored)
        assertTrue("the base moved $moved m", moved < 0.01)
        val hurt = world.vessels.sumOf { v -> v.health.count { it < 1.0 } + v.broken.count { it } }
        assertTrue("nothing was hurt by a twenty-metre-a-second crash", hurt > 0)
    }

    @Test
    fun `only a craft with a foundation, at rest on the ground, can be founded`() {
        val world = World.default(catalog)
        val rover = world.spawnOnSurface(StockCraft.rover(catalog), cape)
        settle(world)
        assertFalse("a rover has no foundation", world.anchor(rover))

        val base = world.spawnOnSurface(StockCraft.baseCore(catalog), cape, pad = 1)
        settle(world)
        // Pushed along, so it's moving and can't be founded.
        base.wake()
        val surface = world.attractorFor(base).surfaceVelocityAt(base.body.position, Vec3())
        base.body.linearVelocity.setTo(surface).addScaledInPlace(Vec3(0.0, 1.0, 0.0).crossInPlace(base.body.position).normalizeInPlace(), 2.0)
        assertFalse("founded while sliding at two metres a second", world.anchor(base))
        settle(world, 4.0)
        assertTrue("founded once still", world.anchor(base))
        // And let go again, so it's an ordinary craft.
        assertTrue(world.unanchor(base))
        assertFalse(base.anchored)
        assertFalse(base.dormant)
    }

    private fun tilt(vessel: Vessel): Double {
        val up = vessel.body.position.copy().normalizeInPlace()
        val craftUp = vessel.body.orientation.rotate(vessel.design.orientation.up, Vec3())
        return Math.toDegrees(kotlin.math.acos((craftUp dot up).coerceIn(-1.0, 1.0)))
    }

    private fun groundSpeed(world: World, vessel: Vessel): Double =
        world.attractorFor(vessel).surfaceVelocityAt(vessel.body.position, Vec3()).subInPlace(vessel.body.linearVelocity).length

    @Test
    fun `a module hauler drives, and staged it sets its load down on its own feet`() {
        val world = World.default(catalog)
        val truck = world.spawnOnSurface(StockCraft.moduleHauler(catalog), cape)
        truck.owner = "p1"
        settle(world)
        assertTrue("tipped ${tilt(truck)} degrees standing", tilt(truck) < 3.0)
        truck.wake()
        truck.control.throttle = 1.0
        repeat((6.0 / dt).toInt()) { world.step(dt) }
        val speed = groundSpeed(world, truck)
        assertTrue("only $speed m/s after six seconds", speed > 3.0)
        truck.control.throttle = 0.0
        truck.control.brakes = true
        repeat((8.0 / dt).toInt()) { world.step(dt) }
        assertTrue("still rolling at ${groundSpeed(world, truck)} m/s", groundSpeed(world, truck) < 0.3)

        assertFalse("founded with its foundation still up on the flatbed", world.canAnchor(truck))
        val before = world.vessels.size
        world.stage(truck)
        assertEquals("the load should come away as a craft of its own", before + 1, world.vessels.size)
        val load = world.vessels.first { it.id != truck.id && it.defs.any { d -> d.id == "base-habitat" } }
        assertEquals("the load is still the player's", "p1", load.owner)
        assertTrue("the release clamp stays on the truck", truck.defs.any { it.id == "base-release-clamp" })
        repeat((5.0 / dt).toInt()) { world.step(dt) }
        assertTrue("the load landed tipped ${tilt(load)} degrees", tilt(load) < 5.0)
        assertTrue("the load broke", load.broken.none { it })
        assertTrue("the load is not resting on the ground", load.touchingGround || load.dormant)
        assertTrue("the truck broke", truck.broken.none { it })
        assertTrue("the load set down on its feet cannot be founded", world.canAnchor(load))
    }

    @Test
    fun `a habitat delivered by flatbed beside a founded base joins it`() {
        val world = World.default(catalog)
        val base = foundedBase(world)
        val truck = world.spawnOnSurface(StockCraft.moduleHauler(catalog), cape, pad = 2)
        truck.owner = "p1"
        settle(world, 2.0)

        // Parked with the base to its right: the truck's right-hand side toward the base's +x
        // connector, a few metres out.
        val baseConnector = base.defs.indices.first { base.defs[it].id == "base-connector" && base.design.parts[it].position.x > 0.5 }
        val baseRef = PortRef(base, baseConnector, base.defs[baseConnector].module<DockingPort>()!!).update()
        val up = truck.body.position.copy().normalizeInPlace()
        val away = Vec3().setTo(baseRef.axis).addScaledInPlace(up, -(baseRef.axis dot up)).normalizeInPlace()
        val right = truck.body.orientation.rotate(Vec3(1.0, 0.0, 0.0), Vec3())
        right.addScaledInPlace(up, -(right dot up)).normalizeInPlace()
        truck.wake()
        truck.body.orientation.setTo(com.rm.apogee.core.math.quatFromTo(right, Vec3().setTo(away).mulInPlace(-1.0)) * truck.body.orientation)
        val height = truck.body.position.length
        truck.body.position.setTo(baseRef.face).addScaledInPlace(away, 7.8)
        truck.body.position.normalizeInPlace().mulInPlace(height)
        world.attractorFor(truck).surfaceVelocityAt(truck.body.position, truck.body.linearVelocity)
        settle(world, 1.0)

        world.stage(truck)
        val load = world.vessels.first { it.id != truck.id && it.defs.any { d -> d.id == "base-habitat" } }
        val loadConnector = load.defs.indexOfFirst { it.id == "base-connector" }
        val gap = PortRef(load, loadConnector, load.defs[loadConnector].module<DockingPort>()!!).update().face.distanceTo(PortRef(base, baseConnector, base.defs[baseConnector].module<DockingPort>()!!).update().face)
        var joined = false
        repeat((12.0 / dt).toInt()) {
            world.step(dt)
            if (world.vessel(load.id) == null) joined = true
        }
        assertTrue("the habitat, set down $gap m from the connector, never joined", joined)
        assertTrue(base.anchored)
        assertEquals(2, base.defs.count { it.id == "base-foundation" })
        assertTrue("the truck went into the base", truck.defs.none { it.id == "base-habitat" } && world.vessel(truck.id) != null)
    }

    private fun charge(v: Vessel) = v.amountOf(com.rm.apogee.core.part.ResourceType.ELECTRIC_CHARGE)

    /** A time when the sun stands [height] (sine of elevation) over [base], rising if [rising]. */
    private fun whenSun(world: World, base: Vessel, height: Double, rising: Boolean): Double {
        val site = base.sleepDirection(Vec3())
        val body = world.attractorFor(base)
        var t = world.time
        var last = world.sunHeight(body, site, t)
        while (true) {
            t += 30.0
            val now = world.sunHeight(body, site, t)
            if (if (rising) (last < height && now >= height) else (last > height && now <= height)) return t
            last = now
        }
    }

    @Test
    fun `a base's power worked out once across three days agrees with working it out hour by hour`() {
        fun run(stepsOf: Double): Double {
            val world = World.default(catalog)
            val base = foundedBase(world)
            // Empty to start with, because full, both would sit at the top all along.
            base.drawCharge(charge(base))
            val start = world.time
            // To a midnight three days on, part of the way through the night's drain.
            val end = run {
                val site = base.sleepDirection(Vec3())
                var e = start + 3 * 86_400.0
                while (!(world.sunHeight(world.attractorFor(base), site, e) < -0.3 &&
                        world.sunHeight(world.attractorFor(base), site, e + 30.0) < world.sunHeight(world.attractorFor(base), site, e))) e += 30.0
                e
            }
            var t = start
            while (t < end) {
                t = minOf(t + stepsOf, end)
                world.settlePower(base, t)
            }
            return charge(base)
        }
        val once = run(4 * 86_400.0)
        val hourly = run(3_600.0)
        val capacity = World.default(catalog).let { it.spawnOnSurface(StockCraft.baseCore(catalog), cape).capacityOf(com.rm.apogee.core.part.ResourceType.ELECTRIC_CHARGE) }
        println("power once $once hourly $hourly of $capacity")
        assertTrue("once $once, hourly $hourly of $capacity", kotlin.math.abs(once - hourly) < 0.01 * capacity)
        assertTrue("nothing to compare: $once", once > 0.0 && once < capacity)
    }

    @Test
    fun `a base with no charge left goes dark at night and comes back with the sun`() {
        val world = World.default(catalog)
        val base = foundedBase(world)
        val midnight = whenSun(world, base, -0.5, rising = false)
        world.settlePower(base, midnight)
        base.drawCharge(charge(base))
        world.settlePower(base, midnight + 600.0)
        assertFalse("still powered at midnight with nothing in its batteries", base.powered)
        assertTrue("net ${base.powerNet} at midnight", base.powerNet < 0.0)
        val morning = whenSun(world, base, 0.5, rising = true).let { if (it < midnight) it + 21_549.0 else it }
        world.settlePower(base, morning)
        assertTrue("dark still, in the morning", base.powered)
        assertTrue("nothing stored by morning: ${charge(base)}", charge(base) > 0.0)
    }

    @Test
    fun `a founded base's stored charge survives a save`() {
        val world = World.default(catalog)
        val base = foundedBase(world)
        base.drawCharge(charge(base) * 0.5)
        val had = charge(base)
        val again = World.default(catalog)
        again.restore(world.save())
        assertTrue("had $had, has ${charge(again.vessel(base.id)!!)}", kotlin.math.abs(charge(again.vessel(base.id)!!) - had) < 5.0)
    }

    private fun propellant(v: Vessel) = v.amountOf(com.rm.apogee.core.part.ResourceType.PROPELLANT)

    /** A founded pad base, and a lander set down in the middle of its deck, half empty. */
    private fun landerOnPad(world: World): Pair<Vessel, Vessel> {
        val pad = world.spawnOnSurface(StockCraft.padBase(catalog), cape)
        settle(world)
        assertTrue("could not found the pad", world.anchor(pad))
        val lander = world.spawnOnSurface(StockCraft.lander(catalog), cape, pad = 3)
        settle(world, 1.0)
        val deck = pad.defs.indexOfFirst { it.id == "base-pad" }
        val up = pad.body.position.copy().normalizeInPlace()
        val drop = lander.body.position.length - world.attractorFor(lander).radius
        lander.wake()
        lander.body.position.setTo(pad.partPositionWorld(deck)).normalizeInPlace()
            .mulInPlace(pad.partPositionWorld(deck).length + 0.3 + drop + 0.2)
        world.attractorFor(lander).surfaceVelocityAt(lander.body.position, lander.body.linearVelocity)
        lander.body.orientation.setTo(com.rm.apogee.core.math.quatFromTo(lander.body.orientation.rotate(Vec3.unitY(), Vec3()), up) * lander.body.orientation)
        settle(world, 3.0)
        lander.takeFrom(lander.defs.indices.toList(), com.rm.apogee.core.part.ResourceType.PROPELLANT, propellant(lander) / 2)
        return pad to lander
    }

    @Test
    fun `a craft standing on a base's pad is filled from its depot`() {
        val world = World.default(catalog)
        val (pad, lander) = landerOnPad(world)
        assertTrue("the lander is not on the pad", world.serviceFor(lander)?.base === pad)
        val had = propellant(lander)
        val depot = propellant(pad)
        world.apply(Command.Refuel(lander.id.raw, true))
        repeat((30.0 / dt).toInt()) { world.step(dt) }
        val full = lander.capacityOf(com.rm.apogee.core.part.ResourceType.PROPELLANT)
        assertTrue("filled to ${propellant(lander)} of $full from $had", propellant(lander) > full - 0.5)
        assertEquals("what the lander took, the depot gave", depot - (propellant(lander) - had), propellant(pad), 0.5)
        assertFalse("still pumping once full", world.isRefuelling(lander.id))
    }

    @Test
    fun `a dark base doesn't pump`() {
        val world = World.default(catalog)
        val (pad, lander) = landerOnPad(world)
        pad.drawCharge(charge(pad))
        // Midnight, so there's no sun to run on either.
        world.settlePower(pad, whenSun(world, pad, -0.5, rising = false))
        pad.drawCharge(charge(pad))
        pad.powered = false
        val had = propellant(lander)
        world.apply(Command.Refuel(lander.id.raw, true))
        repeat(30) { world.step(dt) }
        assertEquals("pumped with no power", had, propellant(lander), 1e-6)
        assertFalse(world.isRefuelling(lander.id))
    }

    @Test
    fun `a craft launched from a base's pad fills from its stores, and from an empty one it goes empty`() {
        val world = World.default(catalog)
        val pad = world.spawnOnSurface(StockCraft.padBase(catalog), cape)
        pad.owner = "p1"
        settle(world)
        assertTrue(world.anchor(pad))
        assertTrue("someone else's base offered as a launch site", world.baseSites("p2").isEmpty())
        val site = world.baseSites("p1").single()
        assertEquals(pad.referenceBodyId, site.bodyId)

        val depot = propellant(pad)
        val lander = world.spawnFor(Command.SpawnCraft(StockCraft.lander(catalog), site.id), "p1")
        val full = lander.capacityOf(com.rm.apogee.core.part.ResourceType.PROPELLANT)
        assertEquals("launched with its tanks full from the depot", full, propellant(lander), 1e-6)
        assertEquals("the depot gave what it took", depot - full, propellant(pad), 1e-6)
        settle(world, 2.0)
        assertTrue("the lander is not standing on the pad", world.serviceFor(lander)?.base === pad)
        assertTrue("the lander came down hard on the pad", lander.broken.none { it })

        world.destroy(lander.id, "moved")
        pad.takeFrom(pad.defs.indices.toList(), com.rm.apogee.core.part.ResourceType.PROPELLANT, propellant(pad))
        val second = world.spawnFor(Command.SpawnCraft(StockCraft.lander(catalog), site.id), "p1")
        assertEquals("launched with propellant from an empty depot", 0.0, propellant(second), 1e-9)
    }

    @Test
    fun `the Cape's buildings stand founded, and get rebuilt once nobody is near`() {
        val world = World.default(catalog)
        world.ensureStructures()
        val complexes = com.rm.apogee.core.craft.StockStructures.complexes
        for (complex in complexes) {
            val standing = world.structureOf(complex)
            assertTrue("no ${complex.name}", standing != null && standing.anchored && standing.owner == World.WORLD_OWNER)
        }
        val tower = world.structureOf(complexes.first())!!
        val first = tower.id
        tower.health[0] = 0.4

        // Someone awake nearby, so it's left as it is.
        val rover = world.spawnOnSurface(StockCraft.rover(catalog), cape, pad = 2)
        repeat((10.0 / dt).toInt()) { world.step(dt); rover.wake() }
        assertEquals("rebuilt with someone watching", first, world.structureOf(complexes.first())!!.id)

        // Nobody, so a minute later it stands whole again.
        world.destroy(rover.id, "gone")
        repeat(((World.REPAIR_QUIET + 3.0) / dt).toInt()) { world.step(dt) }
        val rebuilt = world.structureOf(complexes.first())!!
        assertTrue("never rebuilt", rebuilt.id != first)
        assertTrue("rebuilt broken", rebuilt.health.all { it >= 1.0 } && rebuilt.anchored)
        assertEquals("one of it, not two", 1, world.vessels.count { it.name == complexes.first().name })
    }

    @Test
    fun `a world saved with an older layout of a complex gets the new one on load`() {
        val world = World.default(catalog)
        world.ensureStructures()
        val complex = com.rm.apogee.core.craft.StockStructures.launchComplex
        // As 0.6.0 had it: the tower turned round, with its arms away from the pads.
        val file = java.io.File.createTempFile("old-cape", ".json").also { it.deleteOnExit() }
        WorldStore(file).save(world.save()).getOrThrow()
        val json = kotlinx.serialization.json.Json.parseToJsonElement(file.readText()).jsonObject
        val vessels = json["vessels"]!!.jsonArray.map { v ->
            if (v.jsonObject["name"]!!.jsonPrimitive.content != complex.name) v else {
                val design = v.jsonObject["design"]!!.jsonObject
                val parts = design["parts"]!!.jsonArray.mapIndexed { i, p ->
                    if (i != 0) p else kotlinx.serialization.json.JsonObject(p.jsonObject + ("rotation" to
                        kotlinx.serialization.json.Json.parseToJsonElement("""{"x":0.0,"y":1.0,"z":0.0,"w":0.0}""")))
                }
                kotlinx.serialization.json.JsonObject(v.jsonObject + ("design" to kotlinx.serialization.json.JsonObject(design + ("parts" to kotlinx.serialization.json.JsonArray(parts)))))
            }
        }
        file.writeText(kotlinx.serialization.json.JsonObject(json + ("vessels" to kotlinx.serialization.json.JsonArray(vessels))).toString())
        val loaded = World.default(catalog)
        loaded.restore(WorldStore(file).load().getOrThrow())
        loaded.ensureStructures()
        val before = loaded.structureOf(complex)!!.id
        loaded.repairStructures(now = true)
        val now = loaded.structureOf(complex)!!
        assertTrue("the old layout was kept", now.id != before)
        val canon = com.rm.apogee.core.craft.StockStructures.design(complex, catalog).parts[0].rotation
        assertTrue("not the new layout", kotlin.math.abs(now.design.parts[0].rotation dot canon) > 0.99999)
        assertEquals("one of it, not two", 1, loaded.vessels.count { it.name == complex.name })
        // And one as designed is left alone.
        loaded.repairStructures(now = true)
        assertEquals("rebuilt again for nothing", now.id, loaded.structureOf(complex)!!.id)
    }

    @Test
    fun `the launch tower's arms and the floodlights face the pads`() {
        val design = com.rm.apogee.core.craft.StockStructures.design(com.rm.apogee.core.craft.StockStructures.launchComplex, catalog)
        for (part in design.parts) {
            if (part.partId != "struct-launch-tower" && part.partId != "struct-floodlight") continue
            // Design axes: +X east, +Z south, and the pads at the origin.
            val front = part.rotation.rotate(Vec3(0.0, 0.0, 1.0))
            val toPads = Vec3(-part.position.x, 0.0, -part.position.z).normalizeInPlace()
            assertTrue("${part.partId} at ${part.position} faces away from the pads", (front dot toPads) > 0.95)
        }
    }

    @Test
    fun `a world without the Cape's buildings doesn't grow them`() {
        val world = World.default(catalog)
        repeat(((World.REPAIR_QUIET + 3.0) / dt).toInt()) { world.step(dt) }
        assertTrue(world.vessels.isEmpty())
    }

    @Test
    fun `a base founds on Luna`() {
        val world = World.default(catalog)
        val mare = World.launchSites.first { it.id == "luna-mare" }
        val base = world.spawnOnSurface(StockCraft.baseCore(catalog), mare)
        settle(world, 4.0)
        assertTrue("could not found on the mare", world.anchor(base))
        // Luna's nights are long (a core alone goes dark in one), but its panels charge it again
        // once the sun is well up.
        world.settlePower(base, whenSun(world, base, 0.5, rising = true))
        assertTrue("no power on Luna with the sun up", charge(base) > 0.0 && base.powerNet > 0.0 && base.powered)
    }

    @Test
    fun `a base core lander flies itself down to the mare and is founded where it lands`() {
        val world = World.default(catalog)
        val mare = World.launchSites.first { it.id == "luna-mare" }
        val lander = world.spawnOnSurface(StockCraft.baseCoreLander(catalog), mare, pad = 2)
        repeat(2) { world.stage(lander) }
        lander.control.sasEnabled = true
        lander.control.rcsEnabled = true
        val luna = world.attractorFor(lander)
        assertEquals("luna", luna.id)

        // A hundred and fifty metres up, still over the ground.
        val up = lander.body.position.copy().normalizeInPlace()
        lander.wake()
        lander.body.position.addScaledInPlace(up, 150.0)
        luna.surfaceVelocityAt(lander.body.position, lander.body.linearVelocity)
        val thrust = 2 * 60_000.0
        val surface = Vec3()
        val direction = Vec3()
        fun height(): Double {
            luna.toBodyFixed(lander.body.position, luna.rotationAt(world.time), direction).normalizeInPlace()
            return lander.body.position.length - luna.surfaceRadiusInBodyFrame(direction)
        }
        // Down the way a pilot would: slowing as the ground comes up, a metre a second at the end,
        // with the engines cut once the legs are down.
        var t = 0.0
        while (t < 120.0 && !lander.touchingGround) {
            val g = luna.gravitationalParameter / lander.body.position.lengthSq
            val climb = (lander.body.linearVelocity - luna.surfaceVelocityAt(lander.body.position, surface)) dot up
            val want = -(height() * 0.12).coerceIn(1.0, 10.0)
            lander.control.throttle = ((g + 2.0 * (want - climb)) * lander.body.mass / thrust).coerceIn(0.0, 1.0)
            world.step(dt)
            t += dt
        }
        assertTrue("never came down", lander.touchingGround)
        lander.control.throttle = 0.0
        settle(world, 20.0)

        assertFalse("something broke landing", lander.broken.any { it })
        val tilt = Math.toDegrees(kotlin.math.acos((lander.forward() dot lander.body.position.copy().normalizeInPlace()).coerceIn(-1.0, 1.0)))
        assertTrue("landed tilted $tilt degrees", tilt < 6.0)
        val tanks = lander.defs.indices.filter { lander.defs[it].id == "tank-cask4" }
        val left = lander.amountIn(tanks, com.rm.apogee.core.part.ResourceType.PROPELLANT)
        assertTrue("could not found where it landed", world.anchor(lander))
        // Whatever the descent didn't burn is the base's store.
        assertTrue("nothing left in the tanks", left > 100.0)
        assertTrue("the base cannot see its store", lander.amountOf(com.rm.apogee.core.part.ResourceType.PROPELLANT) >= left - 1e-6)
    }

    @Test
    fun `Riccioli Base stands founded on Luna, anyone can launch from it in free play, and it never runs dry`() {
        val world = World.default(catalog)
        world.ensureStructures()
        val base = world.lunaBase()
        assertTrue("no base on Luna", base != null && base.anchored && base.referenceBodyId == "luna")
        assertEquals("Riccioli Base", base!!.name)
        val site = world.baseSites("anyone").single { it.bodyId == "luna" }
        val depot = propellant(base!!)
        val lander = world.spawnFor(Command.SpawnCraft(StockCraft.lander(catalog), site.id), "anyone")
        assertEquals("launched full", lander.capacityOf(com.rm.apogee.core.part.ResourceType.PROPELLANT), propellant(lander), 1e-6)
        settle(world, 2.0)
        assertTrue("the lander is not standing on the pad", world.serviceFor(lander)?.base === base)
        lander.takeFrom(lander.defs.indices.toList(), com.rm.apogee.core.part.ResourceType.PROPELLANT, propellant(lander) / 2)
        world.apply(Command.Refuel(lander.id.raw, true))
        repeat((20.0 / dt).toInt()) { world.step(dt) }
        assertEquals("refilled", lander.capacityOf(com.rm.apogee.core.part.ResourceType.PROPELLANT), propellant(lander), 0.5)
        assertEquals("the world's depot never runs down", depot, propellant(base), 1e-6)
    }

    @Test
    fun `free play has a base on every world that can hold one, each standing level and whole, and can launch from them`() {
        val world = World.default(catalog)
        world.ensureStructures()
        settle(world, 60.0)
        for (spec in WorldBases.all) {
            val base = world.worldBase(spec.bodyId)
            assertTrue("no ${spec.name} on ${spec.bodyId}", base != null && base.anchored && base.referenceBodyId == spec.bodyId)
            assertTrue("${spec.name} is hurt", base!!.broken.none { it } && base.health.all { it >= 1.0 })
            val up = base.body.position.normalized()
            val deck = base.body.orientation.rotate(base.design.orientation.up)
            assertTrue("${spec.name} leans ${Math.toDegrees(kotlin.math.acos((deck dot up).coerceIn(-1.0, 1.0)))} degrees", (deck dot up) > 0.99)
        }
        // None on Caligo, because its air would crush one.
        assertTrue(world.worldBase("caligo") == null)
        // A craft put on each base's pad stands on it.
        for (site in world.baseSites("anyone")) {
            val craft = world.spawnFor(Command.SpawnCraft(StockCraft.lander(catalog), site.id), "anyone")
            settle(world, 3.0)
            assertTrue("not standing on its pad at ${site.displayName}", world.serviceFor(craft)?.base?.owner == World.WORLD_OWNER)
            world.destroy(craft.id, "done")
        }
    }

    @Test
    fun `a career has none of the world's bases, and can't launch from them`() {
        val world = World.default(catalog)
        world.program = com.rm.apogee.core.career.Program()
        world.ensureStructures()
        assertTrue("world bases in a career: ${world.worldBases().map { it.name }}", world.worldBases().isEmpty())
        assertTrue(world.baseSites("anyone").isEmpty())
        // One from a world played in free play before is gone once it's a career.
        val sandbox = World.default(catalog).also { it.ensureStructures() }
        val career = World(sandbox.system, catalog).also { it.restore(sandbox.save()); it.program = com.rm.apogee.core.career.Program(); it.ensureStructures() }
        assertTrue(career.worldBases().isEmpty())
        // The Cape stays, because it's home.
        assertTrue(career.vessels.any { it.owner == World.WORLD_OWNER && it.anchored && it.referenceBodyId == "terra" })
    }

    @Test
    fun `an old world's Luna Test Base is Riccioli Base now`() {
        val world = World.default(catalog)
        world.ensureStructures()
        world.lunaBase()!!.name = WorldBases.OLD_LUNA_NAME
        val restored = World(world.system, catalog).also { it.restore(world.save()); it.ensureStructures() }
        assertEquals(1, restored.vessels.count { it.referenceBodyId == "luna" && it.owner == World.WORLD_OWNER })
        assertEquals("Riccioli Base", restored.lunaBase()?.name)
    }

    /** Ground rising to the north at [degrees], around a site at +X. */
    private class Slope(degrees: Double) : com.rm.apogee.core.terrain.Terrain {
        private val grade = kotlin.math.tan(Math.toRadians(degrees))
        override val bodyRadius = 600_000.0
        override val maxElevation = 200.0
        override val generation = 0
        override fun elevation(direction: Vec3) = direction.normalized().y * bodyRadius * grade
        override fun material(direction: Vec3, elevation: Double, slope: Double) = com.rm.apogee.core.terrain.SurfaceMaterial.CONCRETE
        override val tiles by lazy { com.rm.apogee.core.terrain.TerrainTileCache(this) }
    }

    private fun worldOnSlope(degrees: Double): Pair<World, LaunchSite> {
        val body = com.rm.apogee.core.orbit.CelestialBody(
            id = "test", displayName = "Test", gravitationalParameter = 3.5316e12, radius = 600_000.0, terrain = Slope(degrees),
        )
        return World(com.rm.apogee.core.orbit.SolarSystem(listOf(body), "test"), catalog) to LaunchSite("site", "Site", "test", 0.0, 0.0)
    }

    @Test
    fun `founded on a slope, a base stands level on its jacks`() {
        val (world, site) = worldOnSlope(8.0)
        val base = world.spawnOnSurface(StockCraft.baseCore(catalog), site)
        settle(world)
        assertTrue("could not found on eight degrees", world.anchor(base))
        settle(world, 0.5)
        val up = base.body.position.copy().normalizeInPlace()
        val craftUp = base.body.orientation.rotate(base.design.orientation.up, Vec3())
        val lean = Math.toDegrees(kotlin.math.acos((craftUp dot up).coerceIn(-1.0, 1.0)))
        assertTrue("leaning $lean degrees", lean < 0.2)
        // One foot on the ground, none in it, and none further off than the jacks reach.
        val body = world.attractorFor(base)
        val foundation = base.defs.indexOfFirst { it.id == "base-foundation" }
        val def = base.defs[foundation]
        val bottom = def.contactPoints.minOf { it.y }
        val gaps = def.contactPoints.indices.filter { def.contactPoints[it].y <= bottom + 1e-6 }.map { p ->
            val point = base.contactPointWorld(foundation, p, Vec3())
            val direction = body.toBodyFixed(point, body.rotationAt(world.time)).normalizeInPlace()
            point.length - body.solidRadiusInBodyFrame(direction)
        }
        assertTrue("feet $gaps", gaps.min() > -0.02 && gaps.min() < 0.02)
        assertTrue("feet $gaps", gaps.max() < 1.0 && gaps.max() > 0.3)
    }

    @Test
    fun `too steep, a base won't found`() {
        val (world, site) = worldOnSlope(14.0)
        val base = world.spawnOnSurface(StockCraft.baseCore(catalog), site)
        settle(world)
        assertFalse("founded on fourteen degrees", world.anchor(base))
        assertFalse(base.anchored)
    }

    @Test
    fun `a founded base stays founded where it was across a save`() {
        val world = World.default(catalog)
        val base = foundedBase(world)
        settle(world, 1.0)
        val before = groundPosition(world, base)
        val again = World.default(catalog)
        again.restore(world.save())
        val restored = again.vessel(base.id)!!
        assertTrue("founded after loading", restored.anchored)
        settle(again, 1.0)
        assertTrue("moved ${groundPosition(again, restored).distanceTo(before)} m across the save", groundPosition(again, restored).distanceTo(before) < 0.01)
    }

    @Test
    fun `a module brought up to a founded base's connector joins it, and the base stays put`() {
        val world = World.default(catalog)
        val base = foundedBase(world)
        val corePart = base.defs.indexOfFirst { it.id == "base-core" }
        val coreBefore = world.attractorFor(base).let { it.toBodyFixed(base.partPositionWorld(corePart), it.rotationAt(world.time)) }

        // The connector on the core's +x side, and the habitat's on its -x side.
        val baseConnector = base.defs.indices.first { base.defs[it].id == "base-connector" && base.design.parts[it].position.x > 0.5 }
        val module = world.spawnOnSurface(StockCraft.habitatModule(catalog), cape, pad = 2)
        settle(world, 2.0)
        // Taken now, since positions are inertial and the ground has turned a long way since.
        val baseRef = PortRef(base, baseConnector, base.defs[baseConnector].module<DockingPort>()!!).update()
        val moduleConnector = module.defs.indexOfFirst { it.id == "base-connector" }
        val moduleRef = PortRef(module, moduleConnector, module.defs[moduleConnector].module<DockingPort>()!!).update()
        assertTrue("the module's connector should face the base's: ${moduleRef.axis dot baseRef.axis}", (moduleRef.axis dot baseRef.axis) < -0.99)
        // Set it down a metre and a quarter out from the base's connector, face to face.
        val target = Vec3().setTo(baseRef.face).addScaledInPlace(baseRef.axis, 1.25)
        module.wake()
        module.body.position.addInPlace(Vec3().setTo(target).subInPlace(moduleRef.update().face))
        world.attractorFor(module).surfaceVelocityAt(module.body.position, module.body.linearVelocity)
            .addScaledInPlace(baseRef.axis, -0.3)

        var joined = false
        repeat((20.0 / dt).toInt()) {
            world.step(dt)
            if (world.vessel(module.id) == null) joined = true
        }
        assertTrue("the module never joined the base", joined)
        assertTrue("the base is still founded", base.anchored)
        assertEquals("one base with both modules in it", 2, base.defs.count { it.id == "base-foundation" })
        val coreAfter = world.attractorFor(base).let { it.toBodyFixed(base.partPositionWorld(base.defs.indexOfFirst { d -> d.id == "base-core" }), it.rotationAt(world.time)) }
        assertTrue("the base's core moved ${coreAfter.distanceTo(coreBefore)} m", coreAfter.distanceTo(coreBefore) < 0.01)
    }
}
