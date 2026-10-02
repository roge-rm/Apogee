package com.rm.apogee.core.world

import com.rm.apogee.core.craft.StockCraft
import com.rm.apogee.core.math.Vec3
import com.rm.apogee.core.orbit.SolarSystem
import com.rm.apogee.core.part.StockParts
import com.rm.apogee.core.terrain.SurfaceMaterial
import com.rm.apogee.core.terrain.Worlds
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.hypot
import kotlin.math.sqrt

/** The places to find out on other worlds: where they are, what's there, and finding them. */
class WondersTest {
    private val system = SolarSystem.defaultSystem()
    private val catalog = StockParts.catalog

    private fun sites(body: String) = Worlds.SITES.filter { it.bodyId == body }.map { SolarSystem.surfaceDirection(it.latitude, it.longitude) }

    /** Metres along the ground between two directions on [body]. */
    private fun apart(body: String, a: Vec3, b: Vec3) = a.normalized().distanceTo(b.normalized()) * system.body(body).radius

    @Test
    fun `every place has its own id, and every world with ground has some`() {
        assertEquals(Wonders.all.size, Wonders.all.map { it.id }.toSet().size)
        for (body in system.bodies.values.filter { it.terrain != null && it.id != "terra" }) {
            assertTrue("nothing to find on ${body.id}", Wonders.land.count { it.bodyId == body.id } >= 3)
        }
    }

    @Test
    fun `places on land are dry, not molten, clear of the sites, and fair ground to set down on`() {
        for (w in Wonders.land) {
            val terrain = system.body(w.bodyId).terrain!!
            val h = terrain.elevation(w.direction)
            assertTrue("${w.name} is under the sea", !terrain.hasOcean || h > 0.0)
            assertTrue("${w.name} is in lava", terrain.material(w.direction, h, 0.0) != SurfaceMaterial.LAVA)
            for (s in sites(w.bodyId)) assertTrue("${w.name} is on a test site", apart(w.bodyId, w.direction, s) > 600.0)
            // Somewhere within reach a lander can stand.
            val up = w.direction.normalized()
            val east = Vec3(0.0, 1.0, 0.0).crossInPlace(up).normalizeInPlace(); val north = up.cross(east).normalizeInPlace()
            val r = terrain.bodyRadius
            var flat = false
            for (i in -4..4) for (j in -4..4) {
                if (flat) break
                val e = i * w.reach / 5; val n = j * w.reach / 5
                val d = Vec3().setTo(up).mulInPlace(r).addScaledInPlace(east, e).addScaledInPlace(north, n).normalizeInPlace()
                val g = terrain.elevation(d)
                val ge = terrain.elevation(Vec3().setTo(d).mulInPlace(r).addScaledInPlace(east, 3.0).normalizeInPlace())
                val gn = terrain.elevation(Vec3().setTo(d).mulInPlace(r).addScaledInPlace(north, 3.0).normalizeInPlace())
                if (hypot(ge - g, gn - g) / 3.0 < 0.25 && terrain.material(d, g, 0.0) != SurfaceMaterial.LAVA) flat = true
            }
            assertTrue("nowhere to set down at ${w.name}", flat)
        }
    }

    @Test
    fun `every world with a base has a place to find a short drive from it`() {
        for (base in WorldBases.all) {
            val site = sites(base.bodyId)
            val near = Wonders.land.filter { it.bodyId == base.bodyId }.minOf { w -> site.minOf { apart(base.bodyId, w.direction, it) } }
            assertTrue("nothing within reach of ${base.name}: nearest $near m", near < 3_500.0)
        }
    }

    @Test
    fun `the props on other worlds lie on the ground where their places are`() {
        val world = World.default(catalog)
        world.ensureStructures()
        for (w in Wonders.land.filter { it.landmark.isNotEmpty() }) {
            val mark = world.landmark(w)
            assertNotNull("no ${w.landmark}", mark)
            val body = world.attractorFor(mark!!)
            val at = body.toBodyFixed(mark.body.position, body.rotationAt(world.time))
            assertTrue("${w.landmark} is ${at.normalized().distanceTo(w.direction) * body.radius} m off", at.normalized().distanceTo(w.direction) * body.radius < 30.0)
            // A wreck lies on its side; an arch stands, its middle half its height up.
            val over = at.length - body.radius - body.terrain!!.elevation(at)
            assertTrue("${w.landmark} is $over m over the ground", over in -3.0..12.0)
        }
    }

    @Test
    fun `driving up to a place on land finds it, and flying over it doesn't`() {
        val place = Wonders.byId("old-rover")!!
        val body = system.body("rubra")
        val terrain = body.terrain!!
        val up = place.direction.normalized()
        val east = Vec3(0.0, 1.0, 0.0).crossInPlace(up).normalizeInPlace()
        fun at(eastMetres: Double): LaunchSite {
            val d = Vec3().setTo(up).mulInPlace(body.radius).addScaledInPlace(east, eastMetres).normalizeInPlace()
            return LaunchSite("by", "By", "rubra", kotlin.math.asin(d.y), kotlin.math.atan2(d.z, d.x))
        }
        // Overhead, a kilometre up: not found.
        run {
            val world = World.default(catalog)
            val over = Vec3().setTo(up).mulInPlace(body.radius + terrain.elevation(up) + 1_000.0)
            val rotation = body.rotationAt(world.time)
            val position = rotation.rotate(over, Vec3())
            val craft = world.spawnAt(StockCraft.sounder(catalog), "rubra", position, body.surfaceVelocityAt(position, Vec3()), com.rm.apogee.core.math.Quat.identity())
            world.assignOwner(craft, "p1")
            repeat(30) { world.step(1.0 / 60.0) }
            assertTrue(place.id !in world.wondersFoundBy("p1"))
        }
        // A rover parked a hundred metres off: found.
        val world = World.default(catalog)
        val rover = world.spawnOnSurface(StockCraft.rover(catalog), at(100.0))
        world.assignOwner(rover, "p1")
        repeat(120) { world.step(1.0 / 60.0) }
        assertTrue("not found from ${apart("rubra", place.direction, world.attractorFor(rover).toBodyFixed(rover.body.position, body.rotationAt(world.time)))} m", place.id in world.wondersFoundBy("p1"))
        assertTrue(world.drainEvents().any { it is WorldEvent.FeatEarned && it.title == place.name })
    }

    @Test
    fun `on a surveyed world the finder points to the nearest place, until it's found`() {
        val place = Wonders.byId("old-rover")!!
        val body = system.body("rubra")
        val up = place.direction.normalized()
        val east = Vec3(0.0, 1.0, 0.0).crossInPlace(up).normalizeInPlace()
        val d = Vec3().setTo(up).mulInPlace(body.radius).addScaledInPlace(east, 1_500.0).normalizeInPlace()
        val world = World.default(catalog)
        val rover = world.spawnOnSurface(StockCraft.rover(catalog), LaunchSite("by", "By", "rubra", kotlin.math.asin(d.y), kotlin.math.atan2(d.z, d.x)))
        world.assignOwner(rover, "p1")
        repeat(60) { world.step(1.0 / 60.0) }
        // Not surveyed and no scanner: nothing.
        assertTrue(world.systemsOf(rover).findRange < 0f)
        world.surveyed += "rubra"
        val range = world.systemsOf(rover).findRange
        assertTrue("finder says $range m", range in 1_300f..1_700f)
        world.wondersFound.getOrPut("p1") { com.rm.apogee.core.concurrentSetOf() }.add(place.id)
        val next = world.systemsOf(rover).findRange
        assertTrue("still pointing at it: $next m", next < 0f || next > 1_800f)
    }

    @Test
    fun `a place can be a craft's target, and it stays one through a save`() {
        val world = World.default(catalog)
        val probe = Aloft.spawn(world, StockCraft.sounder(catalog), 2_000.0, bodyId = "rubra")
        val place = Wonders.byId("the-caldera")!!
        world.apply(Command.SetTarget(probe.id.raw, -1L, Wonders.TARGET_PREFIX + place.id))
        assertEquals(Wonders.TARGET_PREFIX + place.id, probe.control.targetBody)
        val body = world.attractorFor(probe)
        val at = Vec3(); val moving = Vec3()
        assertTrue(world.targetBodyFor(probe, body, world.time, at, moving))
        // On the ground where the place is, turning with it.
        val ground = body.radius + body.terrain!!.elevation(place.direction)
        assertEquals(ground, at.length, 1.0)
        assertTrue(moving.length > 0.0)
        // Nonsense isn't kept.
        world.apply(Command.SetTarget(probe.id.raw, -1L, Wonders.TARGET_PREFIX + "nowhere"))
        assertEquals("", probe.control.targetBody)
        world.apply(Command.SetTarget(probe.id.raw, -1L, Wonders.TARGET_PREFIX + place.id))
        val restored = World(world.system, catalog).also { it.restore(world.save()) }
        assertEquals(Wonders.TARGET_PREFIX + place.id, restored.vessel(probe.id)!!.control.targetBody)
    }
}
