package com.rm.apogee.core.world

import com.rm.apogee.core.craft.StockCraft
import com.rm.apogee.core.craft.Vessel
import com.rm.apogee.core.math.Quat
import com.rm.apogee.core.math.Vec3
import com.rm.apogee.core.math.quatFromTo
import com.rm.apogee.core.orbit.SolarSystem
import com.rm.apogee.core.part.StockParts
import com.rm.apogee.core.terrain.CubeSphere
import com.rm.apogee.core.terrain.ScatterField
import com.rm.apogee.core.terrain.ScatterKind
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Rocks and trees: where they are, and what happens when something hits one. */
class ScatterTest {

    private val catalog = StockParts.catalog
    private val dt = 1.0 / 60.0
    private val terra = SolarSystem.defaultSystem().body("terra")!!.terrain!!

    /** The nearest object of [kind] to the Cape, as a body-fixed base position and id. */
    private fun nearest(kind: ScatterKind): Pair<Vec3, Long> {
        val field = terra.scatter!!
        val coords = Vec3()
        val face = CubeSphere.locate(Vec3(1.0, 0.0, 0.0), coords)
        val n = field.tilesPerFace
        val ci = ((coords.x + 1) * 0.5 * n).toInt()
        val cj = ((coords.y + 1) * 0.5 * n).toInt()
        for (ring in 0..40) for (di in -ring..ring) for (dj in -ring..ring) {
            if (maxOf(kotlin.math.abs(di), kotlin.math.abs(dj)) != ring) continue
            val block = field.block(face, ci + di, cj + dj)
            for (k in 0 until block.count) {
                if (ScatterKind.of(block.kinds[k].toInt()) == kind) {
                    return Vec3(block.x[k], block.y[k], block.z[k]) to block.ids[k]
                }
            }
        }
        error("no $kind near the Cape")
    }

    @Test
    fun `the same ground grows the same things`() {
        val a = ScatterField(terra).block(0, 4100, 4100)
        val b = ScatterField(terra).block(0, 4100, 4100)
        assertEquals(a.count, b.count)
        assertTrue(a.ids.contentEquals(b.ids))
        assertTrue(a.x.contentEquals(b.x))
        assertTrue(a.kinds.contentEquals(b.kinds))
    }

    @Test
    fun `nothing grows on the launch complex`() {
        val field = terra.scatter!!
        val coords = Vec3()
        val face = CubeSphere.locate(Vec3(1.0, 0.0, 0.0), coords)
        val n = field.tilesPerFace
        val ci = ((coords.x + 1) * 0.5 * n).toInt()
        val cj = ((coords.y + 1) * 0.5 * n).toInt()
        val d = Vec3()
        for (di in -30..30) for (dj in -8..8) {
            val block = field.block(face, ci + di, cj + dj)
            for (k in 0 until block.count) {
                d.setTo(block.x[k], block.y[k], block.z[k])
                assertTrue("something at the launch complex", !terra.isLaunchComplex(d))
            }
        }
    }

    /** A rover sent at [target] from 20 m away at [speed], in a fresh world. */
    private fun charge(target: Vec3, speed: Double): Pair<World, Vessel> {
        val world = World.default(catalog)
        val body = world.system.body("terra")!!
        val up = target.normalized()
        // Twenty metres away, to the west, heading east at it.
        val east = Vec3(0.0, 1.0, 0.0).crossInPlace(up).normalizeInPlace()
        val start = up.copy().addScaledInPlace(east, -20.0 / body.radius).normalizeInPlace()
        val ground = body.surfaceRadiusInBodyFrame(start)
        val position = start.copy().mulInPlace(ground + 1.3)
        val rotation = quatFromTo(Vec3(0.0, 1.0, 0.0), start)
        // The rover's forward is +Z, so turn it about the vertical to face east.
        val forward = rotation.rotate(Vec3(0.0, 0.0, 1.0))
        rotation.setTo(quatFromTo(forward, east) * rotation)
        val velocity = body.surfaceVelocityAt(position, Vec3()).addScaledInPlace(east, speed)
        val rover = world.spawnAt(StockCraft.rover(catalog), "terra", position, velocity, rotation)
        return world to rover
    }

    /**
     * Nothing drives through a boulder. A fast rover may ride up its buried lower slope to a stop,
     * but never ends up inside it or past it.
     */
    @Test
    fun `a boulder stops a rover`() {
        val (boulder, _) = nearest(ScatterKind.BOULDER_LARGE)
        val (world, rover) = charge(boulder, 15.0)
        val body = world.system.body("terra")!!
        val east = Vec3(0.0, 1.0, 0.0).crossInPlace(boulder.normalized()).normalizeInPlace()
        var closest = Double.MAX_VALUE
        repeat((4.0 / dt).toInt()) {
            world.step(dt)
            val bf = body.toBodyFixed(rover.body.position, body.rotationAt(world.time), Vec3())
            closest = minOf(closest, bf.distanceTo(boulder))
        }
        val bf = body.toBodyFixed(rover.body.position, body.rotationAt(world.time), Vec3())
        val past = bf.copy().subInPlace(boulder) dot east
        assertTrue("got within $closest m of the boulder's base", closest > 1.5)
        assertTrue("ended up $past m past the boulder", past < 0.0)
    }

    @Test
    fun `a tree hit hard falls, and stays fallen after a restart`() {
        val (tree, id) = nearest(ScatterKind.BROADLEAF)
        // Hard: a glancing blow from one wheel at 20 m/s may not fell a big tree.
        val (world, _) = charge(tree, 30.0)
        repeat((4.0 / dt).toInt()) { world.step(dt) }
        assertTrue("the tree is still standing", id in world.felledScatter)

        val reloaded = World.default(catalog)
        reloaded.restore(world.save())
        assertTrue("the tree grew back", id in reloaded.felledScatter)
    }
}
