package com.rm.apogee.core.career

import com.rm.apogee.core.craft.StockCraft
import com.rm.apogee.core.craft.Vessel
import com.rm.apogee.core.math.Quat
import com.rm.apogee.core.math.Vec3
import com.rm.apogee.core.part.ResourceType
import com.rm.apogee.core.part.StockParts
import com.rm.apogee.core.world.Command
import com.rm.apogee.core.world.World
import com.rm.apogee.core.world.WorldEvent
import com.rm.apogee.core.world.WorldSave
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.sqrt

/** The career: its tree, what it lets a player launch, and the feats it sees them pull off. */
class CareerTest {
    private val catalog = StockParts.catalog
    private val tree = TechTree.stock
    private val dt = 1.0 / 60.0

    private fun careerWorld(): World = World.default(catalog).also { it.program = Program() }

    private fun spawn(world: World, design: com.rm.apogee.core.craft.CraftDesign, site: String = "cape", owner: String = "p1"): Vessel =
        world.spawnFor(Command.SpawnCraft(design, site), owner).also { it.ownerName = owner }

    private fun earned(world: World, owner: String = "p1"): Map<String, Int> = world.program!!.careerOf(owner).feats

    /** A pod behind its heat shield, with its chute: what comes home through the air. */
    private fun capsule(): com.rm.apogee.core.craft.CraftDesign {
        val parts = listOf(
            com.rm.apogee.core.craft.PlacedPart("pod-halo", Vec3(0.0, 0.0, 0.0), Quat.identity()),
            com.rm.apogee.core.craft.PlacedPart("chute-canopy", Vec3(0.0, 0.8, 0.0), Quat.identity(), parentIndex = 0),
            com.rm.apogee.core.craft.PlacedPart("shield-halo", Vec3(0.0, -0.7, 0.0), Quat.identity(), parentIndex = 0),
        )
        return com.rm.apogee.core.craft.CraftDesign(
            name = "Capsule", parts = parts,
            stages = listOf(com.rm.apogee.core.craft.Stage(listOf(1))),
            catalogHash = catalog.contentHash,
        )
    }

    // --- the tree ------------------------------------------------------------------

    @Test
    fun `every part a player can build is in the starting kit or exactly one node`() {
        val buildable = catalog.parts.values.filter { !it.hidden && it.category != com.rm.apogee.core.part.PartCategory.STRUCTURE }.map { it.id }
        val given = tree.start.parts + tree.nodes.flatMap { it.parts }
        for (id in buildable) assertEquals("$id is given ${given.count { it == id }} times", 1, given.count { it == id })
        for (id in given) assertTrue("$id is not a part", catalog[id] != null)
    }

    @Test
    fun `the tree hangs together - known nodes and feats, no loops, and more insight on offer than it costs`() {
        val ids = tree.nodes.map { it.id }
        assertEquals("duplicate node ids", ids.size, ids.toSet().size)
        for (node in tree.nodes) {
            for (r in node.requires) assertTrue("${node.id} requires unknown $r", r in ids)
            node.needs?.let { assertNotNull("${node.id} needs unknown feat $it", Feat.byId(it)) }
        }
        // No loops: every node reachable by unlocking in some order.
        val done = HashSet<String>()
        var progress = true
        while (progress) {
            progress = false
            for (node in tree.nodes) if (node.id !in done && node.requires.all { it in done }) { done.add(node.id); progress = true }
        }
        assertEquals("nodes in a loop: ${ids - done}", ids.toSet(), done)
        val onOffer = tree.start.insight + Feat.entries.sumOf { Insight.worth(it, Grade.GOLD) } +
            tree.worlds.values.sumOf { it * (Visit.ORBIT.multiplier + Visit.LAND.multiplier + Visit.RETURN.multiplier) }
        val cost = tree.nodes.sumOf { it.cost }
        assertTrue("the tree costs $cost, only $onOffer on offer", onOffer > cost)
        // And the start can afford a first step in each branch.
        val firsts = tree.nodes.filter { it.requires.isEmpty() && it.needs == null }
        assertTrue(firsts.isNotEmpty())
        assertTrue(firsts.all { it.cost <= tree.start.insight + Insight.UNGRADED })
    }

    @Test
    fun `the Sounder is all a new career needs, and the stock rocket is not allowed yet`() {
        val state = CareerState("p1", insight = tree.start.insight)
        val sounder = StockCraft.sounder(catalog)
        assertTrue(sounder.validate(catalog).toString(), sounder.validate(catalog).isEmpty())
        assertNull(CareerRules.refusal(tree, state, sounder, "cape", catalog))
        val refused = CareerRules.refusal(tree, state, StockCraft.starterRocket(catalog), "cape", catalog)
        assertTrue("$refused", refused != null && refused.contains("Not unlocked"))
        // Nowhere but the Cape and your own bases.
        assertTrue(CareerRules.refusal(tree, state, sounder, "luna-mare", catalog) != null)
        // No airfield yet.
        assertTrue(CareerRules.refusal(tree, state, sounder, "airfield", catalog)!!.contains("hangar"))
    }

    @Test
    fun `too heavy or too many parts for the pad is refused, and a bigger pad takes it`() {
        val everything = CareerState("p1", nodes = tree.nodes.map { it.id }.filter { !it.startsWith("pad-") })
        val moonshot = StockCraft.moonshot(catalog)
        val refused = CareerRules.refusal(tree, everything, moonshot, "cape", catalog)
        assertTrue("$refused", refused != null && refused.startsWith("Too"))
        val bigPad = everything.copy(nodes = everything.nodes + listOf("pad-2", "pad-3"))
        assertNull(CareerRules.refusal(tree, bigPad, moonshot, "cape", catalog))
    }

    @Test
    fun `unlocking spends insight, and waits for what a node needs`() {
        val program = Program()
        val start = program.careerOf("p1").insight
        assertNull(program.unlock("p1", "tanks"))
        // Through the world too: done is null, not a complaint.
        val world = careerWorld()
        assertNull(world.unlock("p1", "tanks"))
        assertEquals("Not a career", World.default(catalog).unlock("p1", "tanks"))
        assertEquals(start - tree.node("tanks")!!.cost, program.careerOf("p1").insight)
        assertTrue(program.careerOf("p1").has("tanks"))
        // Needs the Staging feat.
        assertTrue(program.unlock("p1", "vacuum")!!.contains("Staging"))
        // Needs more than there is.
        assertTrue(program.unlock("p1", "pad-4") != null)
        // Someone else's career is their own.
        assertFalse(program.careerOf("p2").has("tanks"))
    }

    // --- feats, flown ---------------------------------------------------------------

    @Test
    fun `the Sounder up, its stage dropped, down under the chute - Hop and Staging`() {
        val world = careerWorld()
        val craft = spawn(world, StockCraft.sounder(catalog))
        world.stage(craft)
        world.apply(Command.SetSas(craft.id.raw, true))
        world.apply(Command.SetThrottle(craft.id.raw, 1.0))
        var t = 0.0
        var dropped = false
        var chute = false
        while (t < 2_400.0) {
            world.step(dt); t += dt
            val vertical = craft.body.linearVelocity dot craft.body.position.normalized()
            if (!dropped && craft.amountOf(ResourceType.PROPELLANT) < 1.0) { world.stage(craft); dropped = true }
            if (dropped && !chute && vertical < -5.0) { world.stage(craft); chute = true }
            if (chute && (craft.touchingGround || craft.afloat) && craft.body.linearVelocity.length < 30.0 && t > 60.0 && craft.log?.resting == true) break
        }
        val feats = earned(world)
        assertTrue("no Hop: $feats, peak ${craft.log?.peak}, t $t, grounded ${craft.touchingGround}/${craft.afloat}, resting ${craft.log?.resting}, crew ${craft.crewAboard}, alive ${world.vessel(craft.id) != null}", Feat.HOP.id in feats)
        assertTrue("no Staging: $feats, dropped ${craft.log?.stagesDropped}", Feat.STAGING.id in feats)
        // Graded by how high it went: one engine's worth, thirty-odd kilometres, is bronze.
        assertEquals("peak ${craft.log?.peak}", Grade.BRONZE.ordinal, feats[Feat.STAGING.id])
        assertTrue(world.program!!.careerOf("p1").insight > tree.start.insight)
    }

    @Test
    fun `a splashdown brings the crew home, and the chute lets go in the sea`() {
        val world = careerWorld()
        world.weatherConfig = com.rm.apogee.core.weather.WeatherConfig(intensity = com.rm.apogee.core.weather.WeatherIntensity.WILD)
        val terra = world.system.body("terra")
        // A capsule under its chute, a kilometre and a half over open sea off the Cape.
        val sea = (1..200).asSequence().flatMap { k -> listOf(k * 1_000.0 to 0.0, -k * 1_000.0 to 0.0, 0.0 to k * 1_000.0, 0.0 to -k * 1_000.0) }
            .map { (e, n) -> com.rm.apogee.core.orbit.SolarSystem.capeDirection(e, n) }
            .first { terra.terrain!!.elevation(it) < -100.0 }
        val up = terra.rotationAt(world.time).rotate(sea)
        val position = up.copy().mulInPlace(terra.radius + 1_500.0)
        val craft = world.spawnAt(capsule(), "terra", position, terra.surfaceVelocityAt(position, Vec3()), com.rm.apogee.core.math.quatFromTo(Vec3.unitY(), up))
        world.assignOwner(craft, "p1")
        world.seatCrew(craft)
        world.stage(craft)
        var t = 0.0
        var wet = -1.0
        while (t < 600.0 && Feat.HOP.id !in earned(world)) {
            world.step(dt); t += dt
            if (wet < 0.0 && craft.buoyed) wet = t
        }
        assertTrue("never reached the sea", wet >= 0.0)
        assertTrue("no Hop from a splashdown: ${earned(world)}, ${t - wet} s in the water", Feat.HOP.id in earned(world))
        // And its chute let go in the water, not left to tow it along.
        val chute = craft.defs.indexOfFirst { it.id == "chute-canopy" }
        assertTrue("the chute is still out: ${craft.legDeploy.getOrNull(chute)}", chute < 0 || craft.legDeploy[chute] < 0.0)
    }

    @Test
    fun `holding an orbit above the air is the Orbit feat, graded by what it weighed at launch`() {
        val world = careerWorld()
        val terra = world.system.body("terra")
        val r = terra.radius + 150_000.0
        val craft = world.spawnAt(StockCraft.sounder(catalog), "terra", Vec3(r, 0.0, 0.0), Vec3(0.0, 0.0, sqrt(terra.gravitationalParameter / r)), Quat.identity())
        world.assignOwner(craft, "p1")
        repeat(30) { world.step(dt) }
        val feats = earned(world)
        assertTrue("no Orbit: $feats", Feat.ORBIT.id in feats)
        assertEquals(Grade.GOLD.ordinal, feats[Feat.ORBIT.id])
    }

    @Test
    fun `two craft side by side in orbit is a rendezvous`() {
        val world = careerWorld()
        val terra = world.system.body("terra")
        val r = terra.radius + 200_000.0
        val v = Vec3(0.0, 0.0, sqrt(terra.gravitationalParameter / r))
        val a = world.spawnAt(StockCraft.sounder(catalog), "terra", Vec3(r, 0.0, 0.0), v, Quat.identity())
        world.assignOwner(a, "p1")
        val b = world.spawnAt(StockCraft.sounder(catalog), "terra", Vec3(r, 0.0, 30.0), v.copy(), Quat.identity())
        world.assignOwner(b, "p2")
        repeat(30) { world.step(dt) }
        assertTrue(Feat.RENDEZVOUS.id in earned(world, "p1"))
        assertTrue(Feat.RENDEZVOUS.id in earned(world, "p2"))
    }

    @Test
    fun `setting down on Luna is a Touchdown and a first landing there, and the world remembers who`() {
        val world = careerWorld()
        val site = World.launchSites.first { it.id == "luna-mare" }
        val craft = world.spawnOnSurface(StockCraft.lander(catalog), site)
        world.assignOwner(craft, "p1")
        craft.ownerName = "Pilot One"
        craft.wake()
        // Dropped from a little way up.
        val up = craft.body.position.normalized()
        craft.body.position.addScaledInPlace(up, 30.0)
        world.stage(craft)
        var t = 0.0
        while (t < 60.0) { world.step(dt); t += dt }
        val state = world.program!!.careerOf("p1")
        assertTrue("no Touchdown: ${state.feats}", Feat.TOUCHDOWN.id in state.feats)
        assertTrue(state.visited("luna", Visit.LAND))
        assertEquals("p1", world.program!!.firsts.first { it.bodyId == "luna" && it.visit == Visit.LAND.id }.owner)
    }

    @Test
    fun `an aerobraking pass lowers the orbit, and counts only with the engines off`() {
        val world = careerWorld()
        val terra = world.system.body("terra")
        // Coming down from a high point 250 km up, the low point 52 km up: a skim through the air.
        val rp = terra.radius + 52_000.0
        val ra = terra.radius + 250_000.0
        val a = (rp + ra) / 2.0
        val vp = sqrt(terra.gravitationalParameter * (2.0 / rp - 1.0 / a))
        val craft = world.spawnAt(capsule(), "terra", Vec3(rp, 0.0, 0.0), Vec3(0.0, 0.0, vp), Quat.identity())
        world.assignOwner(craft, "p1")
        // Back up the orbit to before the air, and fly through it.
        val before = world.orbitOf(craft)
        val entry = before.propagate(-240.0)
        craft.body.position.setTo(entry.position)
        craft.body.linearVelocity.setTo(entry.velocity)
        // Nose to retrograde, held there: the shield, underneath, meets the air.
        craft.body.orientation.setTo(com.rm.apogee.core.math.quatFromTo(Vec3.unitY(), entry.velocity.normalized().mulInPlace(-1.0)))
        world.apply(Command.SetSas(craft.id.raw, true))
        world.apply(Command.SetSasMode(craft.id.raw, com.rm.apogee.core.world.SasMode.RETROGRADE))
        // Through the air and out the other side.
        val terraAir = terra.atmosphereHeight
        var t = 0.0
        var entered = false
        while (t < 3_000.0) {
            world.step(dt); t += dt
            val alt = terra.altitudeOf(craft.body.position)
            if (alt < terraAir) entered = true
            if (entered && alt > terraAir + 1_000.0) break
        }
        val after = world.orbitOf(craft)
        assertTrue("never bound after: $after", after.isBound)
        val feats = earned(world)
        assertTrue("no Aerobrake: apoapsis ${before.apoapsis} -> ${after.apoapsis}, $feats", Feat.AEROBRAKE.id in feats)
    }

    @Test
    fun `a new career has no flight computer, and only room for its first crew`() {
        val world = careerWorld()
        val craft = spawn(world, StockCraft.sounder(catalog))
        assertFalse(world.mayAutopilot(craft))
        world.program!!.restore(listOf(CareerState("p1", nodes = listOf("flight-computer"))), emptyList())
        assertTrue(world.mayAutopilot(craft))
        // A sandbox lets anyone.
        assertTrue(World.default(catalog).let { w -> w.mayAutopilot(w.spawnFor(Command.SpawnCraft(StockCraft.sounder(catalog), "cape"), "p1")) })

        val crewed = careerWorld()
        val pods = (0 until tree.start.crew + 2).map { spawn(crewed, StockCraft.sounder(catalog)) }
        assertEquals(tree.start.crew, pods.count { it.crewAboard > 0 })
    }

    @Test
    fun `a career world saves as one, and an old world loads as a sandbox`() {
        val world = careerWorld()
        world.program!!.unlock("p1", "tanks")
        spawn(world, StockCraft.sounder(catalog))
        val save = world.save()
        assertEquals(WorldSave.MODE_CAREER, save.mode)
        val restored = World(world.system, catalog).also { it.restore(save) }
        assertNotNull(restored.program)
        assertTrue(restored.program!!.careerOf("p1").has("tanks"))
        assertNotNull(restored.vessels.first().log)
        val old = World.default(catalog).save()
        assertEquals(WorldSave.MODE_SANDBOX, old.mode)
        assertNull(World(world.system, catalog).also { it.restore(old) }.program)
    }
}
