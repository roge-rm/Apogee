package com.rm.apogee.core.world

import com.rm.apogee.core.craft.StockCraft
import com.rm.apogee.core.part.StockParts
import org.junit.Assert.assertTrue
import org.junit.Test

/** Launching onto a pad something is already standing on. */
class LaunchPadTest {

    private val catalog = StockParts.catalog
    private val cape = World.launchSites.first()

    private fun launch(world: World, design: com.rm.apogee.core.craft.CraftDesign) =
        world.spawnFor(Command.SpawnCraft(design, cape.id), owner = "someone")

    @Test
    fun `a launch onto an empty site uses the site itself`() {
        val world = World.default(catalog)
        val first = launch(world, StockCraft.starterRocket(catalog))
        val reference = World.default(catalog).spawnOnSurface(StockCraft.starterRocket(catalog), cape)
        assertTrue(first.body.position.distanceTo(reference.body.position) < 0.01)
    }

    @Test
    fun `each launch goes to the nearest clear pad, and nothing overlaps`() {
        val world = World.default(catalog)
        val craft = listOf(
            launch(world, StockCraft.starterRocket(catalog)),
            launch(world, StockCraft.aeroplane(catalog)),
            launch(world, StockCraft.rover(catalog)),
            launch(world, StockCraft.starterRocket(catalog)),
        )
        for (i in craft.indices) for (j in i + 1 until craft.size) {
            val a = craft[i]; val b = craft[j]
            val gap = a.body.position.distanceTo(b.body.position) - a.contactRadius - b.contactRadius
            assertTrue("craft $i and $j are only ${"%.1f".format(gap)} m apart", gap >= 5.0)
        }
        // Close, too: four craft, none further than a few pads out.
        val spread = craft.maxOf { it.body.position.distanceTo(craft[0].body.position) }
        assertTrue("spread over ${spread.toInt()} m", spread < 200.0)
        // And they stay put rather than being flung apart by the contact solver.
        repeat(120) { world.step(1.0 / 60.0) }
        assertTrue("every craft survives", craft.all { world.vessel(it.id) != null })
    }

    /** Reset: the craft as it was is gone, and a fresh one stands on the pad, still the player's. */
    @Test
    fun `a craft reset to its site is fresh, on the pad, and still its owner's`() {
        val world = World.default(catalog)
        val rover = launch(world, StockCraft.rover(catalog))
        rover.name = "Trundler Two"
        // Somewhere far off and upside down.
        rover.body.position.addScaledInPlace(com.rm.apogee.core.math.Vec3(0.0, 0.0, 1.0), 800.0)
        rover.body.orientation.setTo(com.rm.apogee.core.math.Quat.fromAxisAngle(com.rm.apogee.core.math.Vec3(1.0, 0.0, 0.0), Math.PI))
        val fresh = world.resetToSite(rover.id)!!
        assertTrue("the old craft is gone", world.vessel(rover.id) == null)
        assertTrue(fresh.name == "Trundler Two" && fresh.owner == "someone")
        val reference = World.default(catalog).spawnOnSurface(StockCraft.rover(catalog), cape)
        assertTrue("on the pad", fresh.body.position.distanceTo(reference.body.position) < 0.5)
    }
}
