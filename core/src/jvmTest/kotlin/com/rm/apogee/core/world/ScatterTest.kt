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

    /** An object of [kind] in suitable Terra country, as a body-fixed base position and id. */
    private fun nearest(kind: ScatterKind): Pair<Vec3, Long> {
        val field = terra.scatter!!
        val coords = Vec3()
        // The collision fixtures use a forest and a mountain belt on the redesigned Terra.
        val at = com.rm.apogee.core.terrain.Landforms.at(
            if (kind == ScatterKind.BROADLEAF) 0.0 else 21.0,
            if (kind == ScatterKind.BROADLEAF) 25.0 else 17.0,
        )
        val face = CubeSphere.locate(Vec3(at[0], at[1], at[2]), coords)
        val n = field.tilesPerFace
        val ci = ((coords.x + 1) * 0.5 * n).toInt()
        val cj = ((coords.y + 1) * 0.5 * n).toInt()
        for (ring in 0..40) for (di in -ring..ring) for (dj in -ring..ring) {
            if (maxOf(kotlin.math.abs(di), kotlin.math.abs(dj)) != ring) continue
            val block = field.block(face, ci + di, cj + dj)
            for (k in 0 until block.count) {
                if (ScatterKind.of(block.kinds[k].toInt()) == kind) {
                    val target = Vec3(block.x[k], block.y[k], block.z[k])
                    if (kind == ScatterKind.BROADLEAF) {
                        val up = target.normalized()
                        val east = Vec3.unitY().cross(up).normalizeInPlace()
                        val height = terra.elevation(up)
                        // Keep the impact fixture on level ground, so the rover reaches the trunk.
                        if ((0..4).any { step ->
                            val d = up.copy().addScaledInPlace(east, -step * 5.0 / terra.bodyRadius).normalizeInPlace()
                            kotlin.math.abs(terra.elevation(d) - height) > 0.15
                        }) continue
                        var blocked = false
                        val north = up.cross(east)
                        field.forEachBlockNear(target, 30.0, Vec3()) { nearby ->
                            for (other in 0 until nearby.count) {
                                if (nearby.ids[other] == block.ids[k]) continue
                                val offset = Vec3(nearby.x[other], nearby.y[other], nearby.z[other]).subInPlace(target)
                                if ((offset dot east) in -25.0..2.0 && kotlin.math.abs(offset dot north) < 5.0) blocked = true
                            }
                        }
                        if (blocked) continue
                    }
                    return target to block.ids[k]
                }
            }
        }
        error("no $kind in the test region")
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
        val face = CubeSphere.locate((terra as com.rm.apogee.core.terrain.TerrainField).padDirection!!, coords)
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
    private fun charge(target: Vec3, speed: Double, side: Double = 0.0): Pair<World, Vessel> {
        val world = World.default(catalog)
        val body = world.system.body("terra")!!
        val up = target.normalized()
        // Twenty metres away, to the west, heading east at it.
        val east = Vec3(0.0, 1.0, 0.0).crossInPlace(up).normalizeInPlace()
        val start = up.copy().addScaledInPlace(east, -20.0 / body.radius)
            .addScaledInPlace(up.cross(east), side / body.radius).normalizeInPlace()
        val ground = body.surfaceRadiusInBodyFrame(start)
        val position = start.copy().mulInPlace(ground + 1.3)
        val design = StockCraft.rover(catalog)
        val rotation = quatFromTo(design.orientation.up, start)
        // Turn the rover about the vertical to face east.
        val forward = rotation.rotate(design.orientation.forward)
        rotation.setTo(quatFromTo(forward, east) * rotation)
        val velocity = body.surfaceVelocityAt(position, Vec3()).addScaledInPlace(east, speed)
        val rover = world.spawnAt(design, "terra", position, velocity, rotation)
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
        // Line the front corner of the chassis up with the thin trunk.
        val (world, rover) = charge(tree, 30.0, side = 1.0)
        var closest = Double.MAX_VALUE
        repeat((4.0 / dt).toInt()) {
            world.step(dt)
            val body = world.system.body("terra")
            val bf = body.toBodyFixed(rover.body.position, body.rotationAt(world.time), Vec3())
            closest = minOf(closest, bf.distanceTo(tree))
        }
        assertTrue("tree impact: closest $closest, felled ${world.felledScatter.size}", id in world.felledScatter)

        val reloaded = World.default(catalog)
        reloaded.restore(world.save())
        assertTrue("the tree grew back", id in reloaded.felledScatter)
    }
}
