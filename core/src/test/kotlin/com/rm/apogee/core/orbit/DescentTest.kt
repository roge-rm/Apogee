package com.rm.apogee.core.orbit

import com.rm.apogee.core.craft.StockCraft
import com.rm.apogee.core.math.Quat
import com.rm.apogee.core.math.Vec3
import com.rm.apogee.core.part.StockParts
import com.rm.apogee.core.world.Forces
import com.rm.apogee.core.world.World
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class DescentTest {
    private val catalog = StockParts.catalog
    private val dt = 1.0 / 60.0

    /** A pod [height] m over the Luna Mare site, moving [across] m/s over the ground, and the world. */
    private fun falling(bodyId: String, siteId: String, height: Double, across: Double): Pair<World, com.rm.apogee.core.craft.Vessel> {
        val world = World.default(catalog)
        val site = World.launchSites.first { it.id == siteId }
        val pod = world.spawnOnSurface(StockCraft.probe(catalog), site)
        val body = world.attractorFor(pod)
        assertEquals(bodyId, body.id)
        pod.wake()
        val up = pod.body.position.copy().normalizeInPlace()
        val east = Vec3(0.0, 1.0, 0.0).crossInPlace(up).normalizeInPlace()
        pod.body.position.addScaledInPlace(up, height)
        body.surfaceVelocityAt(pod.body.position, pod.body.linearVelocity).addScaledInPlace(east, across)
        return world to pod
    }

    /**
     * Steps [world] until [pod] touches down, and returns the time and where (a body-fixed unit
     * direction).
     */
    private fun land(world: World, pod: com.rm.apogee.core.craft.Vessel): Pair<Double, Vec3> {
        val body = world.attractorFor(pod)
        while (!pod.touchingGround && world.time < 3_600.0) world.step(dt)
        return world.time to body.toBodyFixed(pod.body.position, body.rotationAt(world.time)).normalizeInPlace()
    }

    @Test
    fun `on Luna it predicts where a falling pod meets the ground`() {
        val (world, pod) = falling("luna", "luna-mare", 3_000.0, 100.0)
        val body = world.attractorFor(pod)
        val foretold = Descent.predict(body, pod.body.position, pod.body.linearVelocity, world.time, pod.body.mass, 0.0)
        assertNotNull("not coming down", foretold)
        val impact = foretold!!
        val (time, where) = land(world, pod)
        val apart = where.distanceTo(impact.direction) * body.radius
        assertTrue("landed $apart m from where it was foretold", apart < 40.0)
        assertEquals(time, impact.time, 1.0)
    }

    @Test
    fun `on Terra the air brings it down later and slower than vacuum would`() {
        val (world, pod) = falling("terra", "cape", 5_000.0, 0.0)
        val body = world.attractorFor(pod)
        val area = Forces().dragArea(pod)
        assertTrue("no drag area", area > 0.5)
        val airless = Descent.predict(body, pod.body.position, pod.body.linearVelocity, world.time, pod.body.mass, 0.0)!!
        val dragged = Descent.predict(body, pod.body.position, pod.body.linearVelocity, world.time, pod.body.mass, area)!!
        assertTrue("air made it no later", dragged.time > airless.time + 5.0)
        assertTrue("air made it no slower", dragged.speed < airless.speed * 0.7)
        // And the world, flying it, agrees about when to within a few per cent. It has wind, and a
        // pod that turns as it falls.
        val start = world.time
        val (time, _) = land(world, pod)
        val fall = dragged.time - start
        assertTrue("came down after ${time - start} s, foretold $fall s", kotlin.math.abs(time - dragged.time) < 0.06 * fall)
    }

    @Test
    fun `a craft in a clear orbit isn't coming down`() {
        val world = World.default(catalog)
        val terra = world.system.body("terra")
        val r = terra.radius + 100_000.0
        assertNull(Descent.predict(terra, Vec3(r, 0.0, 0.0), Vec3(0.0, 0.0, terra.circularVelocityAt(r)), 0.0, 1_000.0, 1.0))
    }
}
