package com.rm.apogee.core.world

import com.rm.apogee.core.craft.StockCraft
import com.rm.apogee.core.craft.Vessel
import com.rm.apogee.core.math.Vec3
import com.rm.apogee.core.math.quatFromTo
import com.rm.apogee.core.part.StockParts
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Crashes, in order: the part that meets the ground takes the blow first,
 * soaks up what it can as it is crushed, and passes the rest on - so a
 * crash strips a craft from the end that hit, and what is left of it is
 * still there.
 */
class CrashTest {

    private val catalog = StockParts.catalog
    private val dt = 1.0 / 60.0

    /** The Starter I dropped tail first onto the pad at [speed]. */
    private fun dropRocket(world: World, speed: Double): Vessel {
        val rocket = world.spawnOnSurface(StockCraft.starterRocket(catalog), World.launchSites.first())
        val up = rocket.body.position.copy().normalizeInPlace()
        rocket.body.position.addScaledInPlace(up, 1.0)
        world.attractorFor(rocket).surfaceVelocityAt(rocket.body.position, rocket.body.linearVelocity)
        rocket.body.linearVelocity.addScaledInPlace(up, -speed)
        return rocket
    }

    private fun run(world: World, seconds: Double, events: MutableList<WorldEvent> = ArrayList()): List<WorldEvent> {
        repeat((seconds / dt).toInt()) {
            world.step(dt)
            events.addAll(world.drainEvents())
        }
        return events
    }

    @Test
    fun `below its tolerance nothing is hurt`() {
        val world = World.default(catalog)
        val rocket = dropRocket(world, 5.0) // the engine takes 7
        run(world, 5.0)
        assertTrue("damaged at 5 m/s: ${rocket.health.toList()}", rocket.health.all { it > 0.999 })
    }

    /**
     * Tail first at 25 m/s: the engine is crushed, the tank above it takes
     * what gets through, and the pod at the far end is spared - and still a
     * craft.
     */
    @Test
    fun `a crash crushes from the end that hit`() {
        val world = World.default(catalog)
        val rocket = dropRocket(world, 25.0)
        val id = rocket.id
        val partsBefore = rocket.design.parts.map { it.partId }
        val events = run(world, 8.0)

        val destroyed = events.filterIsInstance<WorldEvent.PartDestroyed>().map { it.partId }
        assertTrue("the engine should be crushed: $destroyed", destroyed.contains("engine-ember"))
        assertTrue("but not the pod: $destroyed", !destroyed.contains("pod-halo"))

        val left = world.vessel(id)
        assertNotNull("what is left is still a craft", left)
        assertTrue("it lost parts", left!!.design.parts.size < partsBefore.size)
        val pod = left.defs.indices.first { left.defs[it].id == "pod-halo" }
        assertTrue("the pod survives: ${left.health[pod]}", left.health[pod] > 0.5)
    }

    /** Harder still, the crushing goes further up the stack than at 25. */
    @Test
    fun `a harder crash reaches further`() {
        fun lost(speed: Double): Int {
            val world = World.default(catalog)
            val rocket = dropRocket(world, speed)
            val events = run(world, 8.0)
            return events.filterIsInstance<WorldEvent.PartDestroyed>().count { it.id == rocket.id }
        }
        val gentle = lost(25.0)
        val hard = lost(60.0)
        assertTrue("60 m/s destroyed $hard parts, 25 m/s $gentle", hard > gentle)
    }

    /**
     * A crushed part absorbs energy: the pod at the top of a rocket that hit
     * tail first survives a speed that would destroy it if it hit first.
     */
    @Test
    fun `the parts that hit first protect the rest`() {
        val world = World.default(catalog)
        val rocket = dropRocket(world, 40.0)
        run(world, 8.0)
        val left = world.vessel(rocket.id)
        val pod = left?.defs?.indices?.firstOrNull { left.defs[it].id == "pod-halo" }
        // Straight onto the ground at 40 m/s the pod (tolerance 14) would be
        // gone: ((40 - 14) / 28)^1.5 is 0.89 of its health.
        assertTrue("the pod is gone: nothing protected it", pod != null)
        assertTrue("the pod took ${1 - left!!.health[pod!!]} of the blow", left.health[pod] > 0.4)
    }

    /**
     * At 55 m/s the crushing reaches the bottom tank, full, and it goes up -
     * taking the tanks beside it, hurting and shoving what is near. The pod,
     * built to take a beating, is the one thing left.
     */
    @Test
    fun `a full tank explodes and damages its neighbours`() {
        val world = World.default(catalog)
        val rocket = dropRocket(world, 55.0)
        // A probe parked beside the pad.
        val up = rocket.body.position.copy().normalizeInPlace()
        val east = Vec3(up.z, 0.0, -up.x).normalizeInPlace()
        val beside = world.spawnAt(
            StockCraft.probe(catalog), "terra",
            rocket.body.position.copy().addScaledInPlace(east, 8.0).addScaledInPlace(up, -3.0),
            rocket.body.linearVelocity.copy().addScaledInPlace(up, 45.0), quatFromTo(Vec3.unitY(), up),
        )
        val events = run(world, 6.0)
        val blasts = events.filterIsInstance<WorldEvent.Explosion>()
        assertTrue("a fuelled tank should have exploded", blasts.isNotEmpty())
        val probe = world.vessel(beside.id)
        assertTrue(
            "the probe beside it was hurt: ${probe?.health?.toList()}",
            probe == null || probe.health.any { it < 1.0 },
        )
        val left = world.vessel(rocket.id)
        assertNotNull("the pod rides it out", left)
        assertTrue("and is all that is left: ${left!!.defs.map { it.id }}", left.defs.none { it.id.startsWith("tank") })
    }

    /** Break-up keeps the player in the piece with the controls on it. */
    @Test
    fun `the piece with the pod stays the craft`() {
        val world = World.default(catalog)
        val rocket = dropRocket(world, 30.0)
        val id = rocket.id
        val before = world.vessels.size
        run(world, 8.0)
        val left = world.vessel(id)
        assertNotNull(left)
        assertTrue("the pod is in the craft that kept its id", left!!.design.parts.any { it.partId == "pod-halo" })
        assertTrue("and anything knocked loose is its own debris", world.vessels.size >= before)
        assertEquals("one piece, one root", 1, left.design.parts.count { it.parentIndex == -1 })
    }

    /**
     * Cut the fins and the stack below the decoupler loose: the fins are
     * fragments and are cleared after a while; the tanks are wreckage and
     * stay; the pod is still the craft.
     */
    @Test
    fun `fragments are cleared and wreckage stays`() {
        val world = World.default(catalog)
        val rocket = world.spawnOnSurface(StockCraft.starterRocket(catalog), World.launchSites.first())
        val fins = rocket.defs.indices.filter { rocket.defs[it].id == "fin-vane" }.toSet()
        val decoupler = rocket.defs.indexOfFirst { it.id == "decoupler-ring" }
        val below = rocket.design.parts.indices.first { rocket.design.parts[it].parentIndex == decoupler }
        world.failParts(rocket, destroyed = emptySet(), detached = fins + below, cause = "test")

        val pieces = world.vessels.toList()
        assertEquals("the craft, the lower stack and four fins", 6, pieces.size)
        val finIds = pieces.filter { v -> v.defs.all { it.id == "fin-vane" } }.map { it.id }
        val stackId = pieces.first { v -> v.defs.any { it.id == "engine-ember" } }.id
        assertEquals(4, finIds.size)
        assertTrue(world.vessel(rocket.id)!!.defs.any { it.id == "pod-halo" })

        run(world, 60.0)
        assertTrue("the fins lie there a while", finIds.all { world.vessel(it) != null })
        run(world, 70.0)
        assertTrue("then they are gone", finIds.none { world.vessel(it) != null })
        assertNotNull("the tanks are wreckage and stay", world.vessel(stackId))
        assertNotNull(world.vessel(rocket.id))
    }

    /** Where the harbour's water is: over the sea, [height] up, still in the ground's frame. */
    private fun overTheSea(world: World, design: com.rm.apogee.core.craft.CraftDesign, height: Double, fall: Double): Vessel {
        val boatSite = World.launchSiteFor(StockCraft.boat(catalog), catalog)
        val craft = world.spawnOnSurface(design, boatSite)
        val up = craft.body.position.copy().normalizeInPlace()
        val attractor = world.attractorFor(craft)
        craft.body.position.setTo(up).mulInPlace(attractor.radius + height)
        attractor.surfaceVelocityAt(craft.body.position, craft.body.linearVelocity)
        craft.body.linearVelocity.addScaledInPlace(up, -fall)
        craft.wake()
        return craft
    }

    /** The sea is not soft at speed: a rocket driven into it breaks as it would on the ground. */
    @Test
    fun `hitting the sea hard breaks a craft`() {
        val world = World.default(catalog)
        val rocket = overTheSea(world, StockCraft.starterRocket(catalog), 12.0, 60.0)
        val events = run(world, 3.0)
        val blows = events.filterIsInstance<WorldEvent.Impact>()
        assertTrue("it should have hit the water", blows.any { it.water })
        assertTrue(
            "and broken: ${events.filterIsInstance<WorldEvent.PartDestroyed>().map { it.partId }}",
            events.any { it is WorldEvent.PartDestroyed },
        )
    }

    /** Under a chute, a capsule comes down into the sea unhurt. */
    @Test
    fun `a gentle splashdown hurts nothing`() {
        val world = World.default(catalog)
        val pod = com.rm.apogee.core.craft.CraftDesign(
            "Pod",
            listOf(
                com.rm.apogee.core.craft.PlacedPart("pod-halo", Vec3(0.0, 0.7, 0.0)),
                com.rm.apogee.core.craft.PlacedPart("shield-halo", Vec3.zero(), parentIndex = 0),
            ),
            catalogHash = catalog.contentHash,
        )
        val capsule = overTheSea(world, pod, 4.0, 8.0)
        val events = run(world, 5.0)
        assertTrue("it went in", events.none { it is WorldEvent.PartDestroyed })
        assertTrue("unhurt: ${capsule.health.toList()}", capsule.health.all { it > 0.99 })
    }
}
