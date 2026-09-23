package com.rm.apogee.core.world

import com.rm.apogee.core.craft.StockCraft
import com.rm.apogee.core.craft.Vessel
import com.rm.apogee.core.math.Vec3
import com.rm.apogee.core.part.StockParts
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Craft against craft.
 *
 * Every one of these passed trivially before the resolver existed, because
 * two vessels simply occupied the same space without noticing - which is why
 * they are written as "is it still above the other one" rather than as
 * assertions about impulses.
 */
class CraftCollisionTest {

    private val catalog = StockParts.catalog
    private val dt = 1.0 / 60.0
    private val site = World.launchSites.first()

    private fun world() = World.default(catalog)

    /** Puts [vessel] [metres] straight up from [reference], at rest. */
    private fun stackAbove(world: World, vessel: Vessel, reference: Vessel, metres: Double) {
        val up = Vec3().setTo(reference.body.position).normalizeInPlace()
        vessel.body.position.setTo(reference.body.position).addScaledInPlace(up, metres)
        world.attractorFor(vessel)
            .surfaceVelocityAt(vessel.body.position, vessel.body.linearVelocity)
        vessel.body.angularVelocity.setTo(Vec3.zero())
    }

    /** Height of [vessel]'s centre above [other]'s, metres. */
    private fun separation(vessel: Vessel, other: Vessel) =
        vessel.body.position.length - other.body.position.length

    @Test
    fun `a craft settling onto another rests on it instead of sinking through`() {
        val world = world()
        val lower = world.spawnOnSurface(StockCraft.lander(catalog), site)
        world.gearDown(lower)
        val upper = world.spawnOnSurface(StockCraft.lander(catalog), site, pad = 1)
        // A short drop: enough to settle, well under the canopy's 8 m/s.
        stackAbove(world, upper, lower, 6.0)

        repeat(900) { world.step(dt) }

        val settledUpper = world.vessel(upper.id)
        val settledLower = world.vessel(lower.id)
        assertNotNull("the lower craft should have survived being landed on", settledLower)
        assertNotNull("the upper craft should have survived the landing", settledUpper)

        // The landers are about 4.6m tall. Anything under a couple of metres
        // means one has sunk into the other.
        val gap = separation(settledUpper!!, settledLower!!)
        assertTrue("the upper craft ended up $gap m above the lower one", gap > 2.0)
    }

    /**
     * The control. Without it the test above would also pass if the resolver
     * simply froze everything in place.
     */
    @Test
    fun `craft on separate pads do not touch`() {
        val world = world()
        val first = world.spawnOnSurface(StockCraft.lander(catalog), site, pad = 0)
        val second = world.spawnOnSurface(StockCraft.lander(catalog), site, pad = 1)
        repeat(3) { world.stage(first) }
        repeat(3) { world.stage(second) }

        val startApart = Vec3().setTo(first.body.position)
            .subInPlace(second.body.position).length
        repeat(600) { world.step(dt) }

        val endApart = Vec3().setTo(first.body.position)
            .subInPlace(second.body.position).length
        assertTrue(
            "pads $startApart m apart should not interact, moved to $endApart m",
            kotlin.math.abs(endApart - startApart) < 1.0,
        )
    }

    @Test
    fun `a craft driven into another at speed wrecks something`() {
        val world = world()
        val target = world.spawnOnSurface(StockCraft.lander(catalog), site)
        repeat(3) { world.stage(target) }
        val missile = world.spawnOnSurface(StockCraft.lander(catalog), site, pad = 1)
        stackAbove(world, missile, target, 40.0)

        // Straight down, far past anything's crash tolerance.
        val up = Vec3().setTo(missile.body.position).normalizeInPlace()
        missile.body.linearVelocity.addScaledInPlace(up, -45.0)

        val events = ArrayList<WorldEvent>()
        repeat(600) {
            world.step(dt)
            events.addAll(world.drainEvents())
        }

        val wrecked = events.filterIsInstance<WorldEvent.VesselDestroyed>()
        assertTrue(
            "a 45 m/s collision should destroy something, events were $events",
            wrecked.isNotEmpty(),
        )
    }

    /**
     * A collision has two sides. If only the moving craft responds, a base is
     * a wall rather than an object, and nothing built in orbit would work.
     */
    @Test
    fun `both craft feel the impact, the lighter one more`() {
        val world = world()
        val heavy = world.spawnOnSurface(StockCraft.starterRocket(catalog), site)
        val light = world.spawnOnSurface(StockCraft.probe(catalog), site, pad = 1)

        // Put the probe just above the rocket's nose and shove it downward.
        stackAbove(world, light, heavy, 11.0)
        val up = Vec3().setTo(light.body.position).normalizeInPlace()
        light.body.linearVelocity.addScaledInPlace(up, -3.0)

        val heavyStart = Vec3().setTo(heavy.body.linearVelocity)
        val lightStart = Vec3().setTo(light.body.linearVelocity)
        repeat(240) { world.step(dt) }

        val survivingHeavy = world.vessel(heavy.id)
        assertNotNull("the rocket should survive a 3 m/s nudge", survivingHeavy)

        val heavyChange = Vec3().setTo(survivingHeavy!!.body.linearVelocity)
            .subInPlace(heavyStart).length
        val lightChange = world.vessel(light.id)?.let {
            Vec3().setTo(it.body.linearVelocity).subInPlace(lightStart).length
        } ?: Double.MAX_VALUE

        assertTrue("the struck craft should have felt something", heavyChange > 1e-6)
        assertTrue(
            "the lighter craft should react more ($lightChange vs $heavyChange)",
            lightChange > heavyChange,
        )
    }

    @Test
    fun `a craft alone in the world is unaffected by the new pass`() {
        val world = world()
        val solo = world.spawnOnSurface(StockCraft.lander(catalog), site)
        world.gearDown(solo)
        val start = solo.body.position.length
        repeat(600) { world.step(dt) }
        assertNull("nothing should have happened to it", world.drainEvents()
            .filterIsInstance<WorldEvent.VesselDestroyed>().firstOrNull())
        assertTrue(
            "it should still be sitting where it was",
            kotlin.math.abs(solo.body.position.length - start) < 0.5,
        )
    }
}
