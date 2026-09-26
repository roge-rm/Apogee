package com.rm.apogee.core.world

import com.rm.apogee.core.craft.CraftDesign
import com.rm.apogee.core.craft.PlacedPart
import com.rm.apogee.core.craft.StockCraft
import com.rm.apogee.core.craft.Vessel
import com.rm.apogee.core.crew.CrewStatus
import com.rm.apogee.core.math.Quat
import com.rm.apogee.core.math.Vec3
import com.rm.apogee.core.part.ResourceType
import com.rm.apogee.core.part.StockParts
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlin.math.sqrt

/** Crew: seated at launch, flying, lost, recovered, out on EVA and back. */
class CrewTest {
    private val catalog = StockParts.catalog
    private val dt = 1.0 / 60.0
    private val cape = World.launchSites.first { it.id == "cape" }
    private val lunaSite = World.launchSites.first { it.id == World.LUNA_TEST_SITE }

    private fun run(world: World, seconds: Double) = repeat((seconds / dt).toInt()) { world.step(dt) }
    private fun member(world: World, craft: Vessel) = world.crew.getValue(craft.crew.first { it.isNotEmpty() }.first())

    /** A Halo alone in a circular orbit 100 km up. */
    private fun podInOrbit(world: World): Vessel {
        val terra = world.system.body("terra")
        val r = terra.radius + 100_000.0
        return world.spawnAt(StockCraft.probe(catalog), "terra", Vec3(r, 0.0, 0.0), Vec3(0.0, 0.0, sqrt(terra.gravitationalParameter / r)), Quat.identity())
    }

    /** Someone standing on [site], alone, in their suit. */
    private fun suitOn(world: World, site: LaunchSite): Vessel {
        val design = CraftDesign(
            name = "Walker", parts = listOf(PlacedPart(World.SUIT_PART, Vec3.zero())),
            stages = emptyList(), manualStaging = true, catalogHash = catalog.contentHash,
        )
        val suit = world.spawnOnSurface(design, site, pad = 2)
        run(world, 2.0)
        return suit
    }

    private fun groundSpeed(world: World, v: Vessel): Double {
        val a = world.attractorFor(v)
        val rel = a.surfaceVelocityAt(v.body.position, Vec3()).subInPlace(v.body.linearVelocity).mulInPlace(-1.0)
        val up = v.body.position.normalized()
        return rel.addScaledInPlace(up, -(rel dot up)).length
    }

    @Test
    fun `a craft launches with its seats filled from its owner's crew, who come home when it is recovered`() {
        val world = World.default(catalog)
        val rocket = world.spawnFor(Command.SpawnCraft(StockCraft.starterRocket(catalog), "cape"), owner = "alice")
        run(world, 1.0)
        val pilot = member(world, rocket)
        assertEquals("alice", pilot.owner)
        assertEquals(CrewStatus.ABOARD, pilot.status)
        assertEquals(rocket.id.raw, pilot.vessel)
        world.apply(Command.RemoveVessel(rocket.id.raw))
        assertEquals("not home", CrewStatus.AVAILABLE, world.crew.getValue(pilot.id).status)
        // The next launch takes them again, rather than a new recruit.
        val again = world.spawnFor(Command.SpawnCraft(StockCraft.starterRocket(catalog), "cape"), owner = "alice")
        assertEquals(pilot.id, member(world, again).id)
        assertEquals(1, world.crewOf("alice").size)
    }

    @Test
    fun `crew in a craft destroyed, or removed away from home, are lost for good`() {
        val world = World.default(catalog)
        val pod = podInOrbit(world)
        val pilot = member(world, pod)
        world.destroy(pod.id, "broke up on re-entry")
        val lost = world.crew.getValue(pilot.id)
        assertEquals(CrewStatus.LOST, lost.status)
        assertEquals("broke up on re-entry", lost.lostHow)
        assertTrue(world.drainEvents().any { it is WorldEvent.CrewLost && it.crewId == pilot.id })

        val other = podInOrbit(world)
        val second = member(world, other)
        world.apply(Command.RemoveVessel(other.id.raw))
        assertEquals("removed in orbit and not lost", CrewStatus.LOST, world.crew.getValue(second.id).status)
    }

    @Test
    fun `an empty pod cannot be flown - once its pilot is out - and a probe core can`() {
        val world = World.default(catalog)
        val pod = podInOrbit(world)
        assertTrue(world.controllable(pod))
        val suit = world.eva(pod.id.raw, member(world, pod).id)
        assertNotNull("could not get out", suit)
        assertEquals(0, pod.crewAboard)
        assertFalse(world.controllable(pod))
        assertEquals("NO CREW", world.whyNotControllable(pod))
        world.apply(Command.SetThrottle(pod.id.raw, 1.0))
        assertEquals("an empty pod took a throttle", 0.0, pod.control.throttle, 0.0)
        // The one in the suit can fly the suit.
        assertTrue(world.controllable(suit!!))
    }

    @Test
    fun `out in orbit beside their pod, they can climb straight back in`() {
        val world = World.default(catalog)
        val pod = podInOrbit(world)
        val pilot = member(world, pod)
        val suit = world.eva(pod.id.raw, pilot.id)!!
        assertEquals(suit.id.raw, world.crew.getValue(pilot.id).vessel)
        assertTrue("stepped out too far: ${suit.body.position.distanceTo(pod.body.position)} m", suit.body.position.distanceTo(pod.body.position) < 3.0)
        val back = world.boardCraft(suit.id.raw)
        assertEquals(pod, back)
        assertNull("the suit is still there", world.vessel(suit.id))
        assertEquals(1, pod.crewAboard)
        assertEquals(pod.id.raw, world.crew.getValue(pilot.id).vessel)
        assertEquals(CrewStatus.ABOARD, world.crew.getValue(pilot.id).status)
    }

    @Test
    fun `the jetpack flies them in orbit, on monopropellant`() {
        val world = World.default(catalog)
        val pod = podInOrbit(world)
        val suit = world.eva(pod.id.raw, member(world, pod).id)!!
        val v0 = suit.body.linearVelocity.copy()
        val mono = suit.amountOf(ResourceType.MONOPROPELLANT)
        world.apply(Command.SetTranslation(suit.id.raw, 0.0, 0.0, 1.0))
        run(world, 2.0)
        world.apply(Command.SetTranslation(suit.id.raw, 0.0, 0.0, 0.0))
        assertTrue("no push: ${suit.body.linearVelocity.distanceTo(v0)}", suit.body.linearVelocity.distanceTo(v0) > 1.0)
        assertTrue(suit.amountOf(ResourceType.MONOPROPELLANT) < mono)
    }

    @Test
    fun `they walk on Terra at a walk, stand upright, and jump`() {
        val world = World.default(catalog)
        val suit = suitOn(world, cape)
        assertTrue("not on the ground", suit.touchingGround)
        val start = suit.body.position.copy()
        world.apply(Command.SetAttitude(suit.id.raw, 1.0, 0.0, 0.0))
        var widest = 0.0
        repeat((4.0 / dt).toInt()) { world.step(dt); widest = maxOf(widest, kotlin.math.abs(suit.surfaceDeflection[0])) }
        assertEquals("walking speed", 1.6, groundSpeed(world, suit), 0.25)
        assertTrue("legs never swung: $widest", widest > 0.8)
        world.apply(Command.SetAttitude(suit.id.raw, 0.0, 0.0, 0.0))
        run(world, 2.0)
        assertTrue("stood still ${groundSpeed(world, suit)}", groundSpeed(world, suit) < 0.2)
        assertTrue("frozen mid-stride", kotlin.math.abs(suit.surfaceDeflection[0]) < 0.05)
        assertTrue("went nowhere", suit.body.position.distanceTo(start) > 4.0)
        // Upright: their head up.
        val head = suit.body.orientation.rotate(Vec3.unitY(), Vec3())
        assertTrue("fell over", (head dot suit.body.position.normalized()) > 0.95)
        val ground = world.attractorFor(suit).altitudeOf(suit.body.position)
        world.apply(Command.Jump(suit.id.raw))
        run(world, 0.2)
        assertFalse("still on the ground", suit.touchingGround)
        assertTrue(world.attractorFor(suit).altitudeOf(suit.body.position) > ground + 0.2)
    }

    @Test
    fun `on Luna they lope, slower to get going`() {
        val world = World.default(catalog)
        val suit = suitOn(world, lunaSite)
        world.apply(Command.SetAttitude(suit.id.raw, 1.0, 0.0, 0.0))
        run(world, 0.5)
        val early = groundSpeed(world, suit)
        run(world, 4.0)
        assertTrue("barely moving on Luna: ${groundSpeed(world, suit)}", groundSpeed(world, suit) > 1.0)
        assertTrue("got going as fast as on Terra: $early", early < 1.2)
    }

    @Test
    fun `a hard fall kills`() {
        val world = World.default(catalog)
        val suit = suitOn(world, cape)
        val pilot = member(world, suit)
        suit.body.position.addScaledInPlace(suit.body.position.normalized(), 12.0)
        suit.wake()
        run(world, 4.0)
        assertNull("walked away from twelve metres", world.vessel(suit.id))
        assertEquals(CrewStatus.LOST, world.crew.getValue(pilot.id).status)
    }

    @Test
    fun `on Luna they climb the lander's ladder back to the pod, and board`() {
        val world = World.default(catalog)
        val lander = world.spawnOnSurface(StockCraft.lander(catalog), lunaSite, pad = 1)
        run(world, 3.0)
        val pilot = member(world, lander)
        val suit = world.eva(lander.id.raw, pilot.id)!!
        run(world, 2.0)
        assertTrue("on the ground", suit.touchingGround)
        assertNull("in reach of the pod from the ground", world.seatInReach(suit))
        // To the foot of the ladder.
        val ladder = lander.defs.indexOfFirst { it.id == "ladder-rung" }
        val foot = lander.partPositionWorld(ladder, Vec3())
        val out = foot.copy().subInPlace(lander.body.position)
        val up = lander.body.position.normalized()
        out.addScaledInPlace(up, -(out dot up)).normalizeInPlace()
        suit.body.position.setTo(foot).addScaledInPlace(out, 0.5).addScaledInPlace(up, -0.6)
        world.apply(Command.Grab(suit.id.raw, true))
        assertTrue("could not take hold", world.onLadder(suit))
        world.apply(Command.SetAttitude(suit.id.raw, 1.0, 0.0, 0.0))
        run(world, 6.0)
        assertNotNull("not up to the hatch", world.seatInReach(suit))
        world.apply(Command.Board(suit.id.raw))
        assertNull(world.vessel(suit.id))
        assertEquals(1, lander.crewAboard)
    }

    @Test
    fun `standing still, they plant a flag beside them, theirs`() {
        val world = World.default(catalog)
        val suit = suitOn(world, lunaSite)
        suit.owner = "alice"
        world.apply(Command.PlantFlag(suit.id.raw))
        val flag = world.vessels.firstOrNull { it.design.parts.single().partId == World.FLAG_PART }
        assertNotNull("no flag", flag)
        assertEquals("alice", flag!!.owner)
        assertTrue("not planted fast", flag.anchored)
        assertTrue(flag.name.endsWith("'s flag"))
        run(world, 3.0)
        val up = flag.body.orientation.rotate(Vec3.unitY(), Vec3())
        assertTrue("the flag fell over", (up dot flag.body.position.normalized()) > 0.95)
    }

    @Test
    fun `crew move between seats of one craft`() {
        val world = World.default(catalog)
        val design = CraftDesign(
            name = "Two Pods",
            parts = listOf(PlacedPart("pod-halo", Vec3.zero()), PlacedPart("pod-halo", Vec3(0.0, -1.2, 0.0), parentIndex = 0)),
            stages = emptyList(), catalogHash = catalog.contentHash,
        )
        val terra = world.system.body("terra")
        val r = terra.radius + 100_000.0
        val craft = world.spawnAt(design, "terra", Vec3(r, 0.0, 0.0), Vec3(0.0, 0.0, sqrt(terra.gravitationalParameter / r)), Quat.identity())
        val lower = craft.crew[1].single()
        val upper = craft.crew[0].single()
        assertFalse("into a full seat", world.transferCrew(craft.id.raw, upper, 1))
        world.eva(craft.id.raw, lower)
        assertTrue(world.transferCrew(craft.id.raw, upper, 1))
        assertEquals(0, craft.crew[0].size)
        assertEquals(upper, craft.crew[1].single())
    }

    @Test
    fun `the roster, the seats and a flag survive a save - and a save from before crew seats everyone`() {
        val world = World.default(catalog)
        val pod = podInOrbit(world)
        val pilot = member(world, pod)
        val again = World.default(catalog)
        again.restore(world.save())
        assertEquals(pilot, again.crew.getValue(pilot.id))
        assertEquals(pilot.id, again.vessel(pod.id)!!.crew[0].single())

        // As a build before crew wrote it: no roster, no seats.
        val json = kotlinx.serialization.json.Json { ignoreUnknownKeys = true }
        val tree = json.encodeToJsonElement(WorldSave.serializer(), world.save()).jsonObject
        val oldVessels = kotlinx.serialization.json.JsonArray(
            tree.getValue("vessels").jsonArray.map { v -> kotlinx.serialization.json.JsonObject(v.jsonObject - "crew") },
        )
        val oldTree = kotlinx.serialization.json.JsonObject(tree - "crew" - "crewSeated" + ("vessels" to oldVessels))
        val old = json.decodeFromJsonElement(WorldSave.serializer(), oldTree)
        val migrated = World.default(catalog)
        migrated.restore(old)
        val back = migrated.vessel(pod.id)!!
        assertEquals("nobody in an old save's pod", 1, back.crewAboard)
        assertTrue(migrated.controllable(back))
    }
}
