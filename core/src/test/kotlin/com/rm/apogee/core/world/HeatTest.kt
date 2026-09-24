package com.rm.apogee.core.world

import com.rm.apogee.core.craft.CraftDesign
import com.rm.apogee.core.craft.PlacedPart
import com.rm.apogee.core.craft.Stage
import com.rm.apogee.core.craft.StockCraft
import com.rm.apogee.core.craft.Vessel
import com.rm.apogee.core.math.Vec3
import com.rm.apogee.core.math.quatFromTo
import com.rm.apogee.core.part.ResourceType
import com.rm.apogee.core.part.StockParts
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.sqrt

/** Heat: what a return from orbit does, and what a heat shield is for. */
class HeatTest {

    private val catalog = StockParts.catalog
    private val dt = 1.0 / 60.0

    private fun pod(shielded: Boolean): CraftDesign {
        val parts = arrayListOf(
            PlacedPart("pod-halo", Vec3(0.0, 0.7, 0.0)),
            PlacedPart("chute-canopy", Vec3(0.0, 1.5, 0.0), parentIndex = 0),
        )
        if (shielded) parts.add(PlacedPart("shield-halo", Vec3.zero(), parentIndex = 0))
        return CraftDesign("Pod", parts, listOf(Stage(listOf(1))), catalog.contentHash)
    }

    private class Return(val world: World, val pod: Vessel, val peak: DoubleArray, val events: List<WorldEvent>)

    /**
     * Home from a 100 km orbit, dropped to a 35 km periapsis, held
     * retrograde - shield first, if there is one - until it is down to a few
     * kilometres or gone.
     */
    private fun comeHome(design: CraftDesign): Return {
        val world = World.default(catalog)
        val terra = world.system.body("terra")
        val r = terra.radius + 100_000.0
        val a = (r + terra.radius + 35_000.0) / 2
        val speed = sqrt(terra.gravitationalParameter * (2 / r - 1 / a))
        val velocity = Vec3(0.0, 0.0, speed)
        val pod = world.spawnAt(
            design, "terra", Vec3(r, 0.0, 0.0), velocity,
            quatFromTo(Vec3.unitY(), velocity.normalized().mulInPlace(-1.0)),
        )
        world.apply(Command.SetSas(pod.id.raw, true))
        world.apply(Command.SetSasMode(pod.id.raw, SasMode.RETROGRADE))
        val peak = DoubleArray(design.parts.size)
        val events = ArrayList<WorldEvent>()
        var t = 0.0
        while (t < 1_500.0) {
            world.step(dt)
            t += dt
            events += world.drainEvents()
            val p = world.vessel(pod.id) ?: break
            if (p.design.parts.size == peak.size) {
                for (i in peak.indices) peak[i] = maxOf(peak[i], p.temperature[i])
            }
            if (p.body.position.length - terra.radius < 5_000.0) break
        }
        return Return(world, pod, peak, events)
    }

    @Test
    fun `a bare capsule burns up coming home`() {
        val trip = comeHome(pod(shielded = false))
        assertNull("nothing between it and the fire, it should not have survived", trip.world.vessel(trip.pod.id))
        assertTrue(
            "burnt up, not broken",
            trip.events.filterIsInstance<WorldEvent.PartDestroyed>().any { it.cause == "burnt up" },
        )
    }

    @Test
    fun `behind its shield the same capsule comes home`() {
        val trip = comeHome(pod(shielded = true))
        val pod = trip.world.vessel(trip.pod.id)
        assertNotNull("the shielded capsule should survive", pod)
        pod!!
        val shield = pod.defs.indexOfFirst { it.id == "shield-halo" }
        assertTrue("the shield took the heat: ${trip.peak.toList()}", trip.peak[shield] > trip.peak[0])
        assertTrue(
            "and the capsule behind it stayed well inside its limit: ${trip.peak[0]} K",
            trip.peak[0] < 0.85 * pod.defs[0].heatLimit,
        )
        assertTrue("it charred ablator doing it", pod.amountInPart(shield, ResourceType.ABLATOR) < 150.0)
    }

    /**
     * A lit engine heats itself, and the tank it is bolted to a little - and
     * settles well inside what it is built for.
     */
    @Test
    fun `a burning engine runs hot but not too hot`() {
        val world = World.default(catalog)
        val terra = world.system.body("terra")
        val position = Vec3(terra.radius + 400_000.0, 0.0, 0.0)
        val velocity = Vec3(0.0, 0.0, sqrt(terra.gravitationalParameter / position.x))
        val rocket = world.spawnAt(
            StockCraft.starterRocket(catalog), "terra", position, velocity,
            quatFromTo(Vec3.unitY(), Vec3(1.0, 0.0, 0.0)),
        )
        world.stage(rocket)
        world.apply(Command.SetThrottle(rocket.id.raw, 1.0))
        repeat((60.0 / dt).toInt()) { world.step(dt) }
        val engine = rocket.defs.indexOfFirst { it.id == "engine-ember" }
        val tank = rocket.design.parts[engine].parentIndex
        val t = rocket.temperature[engine]
        assertTrue("the Ember should be hot after a minute's burn: $t K", t > 600.0)
        assertTrue("but well inside its ${rocket.defs[engine].heatLimit} K", t < 0.75 * rocket.defs[engine].heatLimit)
        assertTrue("the tank above it warms: ${rocket.temperature[tank]} K", rocket.temperature[tank] > Vessel.AMBIENT_TEMPERATURE + 5.0)
        assertTrue("the pod at the far end hardly does", rocket.temperature[0] < rocket.temperature[tank])
    }
}
