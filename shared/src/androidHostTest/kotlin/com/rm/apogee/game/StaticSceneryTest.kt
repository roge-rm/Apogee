package com.rm.apogee.game

import com.rm.apogee.core.craft.CraftDesign
import com.rm.apogee.core.craft.PlacedPart
import com.rm.apogee.core.math.Quat
import com.rm.apogee.core.math.Vec3
import com.rm.apogee.core.part.PartCatalog
import com.rm.apogee.render.PartModels
import com.rm.apogee.render.FrameBus
import com.rm.apogee.render.RenderItem
import com.rm.apogee.render.RenderItemPool
import com.rm.apogee.render.StackCaps
import org.junit.Assert.*
import org.junit.Test

class StaticSceneryTest {
    private fun catalog() = PartCatalog.fromJson(listOf(
        javaClass.getResource("/parts/structures.json")!!.readText(),
    ))

    @Test fun `cached building matches ordinary leaf transforms and survives planet rotation`() {
        val catalog = catalog()
        val placed = PlacedPart("struct-assembly", Vec3(10.0, 5.0, -3.0), Quat.fromAxisAngle(Vec3.unitY(), 0.7))
        val design = CraftDesign("Building", listOf(placed))
        val cache = StaticScenery(37L, design, catalog)
        val pool = RenderItemPool(FrameBus())
        val centre = Vec3(2.0, 3.0, 1.0)
        val position = Vec3(600_000.0, 20.0, 50.0)
        val leaves = ArrayList<PartModels.Leaf>()
        PartModels.expand(catalog.require(placed.partId), StackCaps.BOTH, null, leaves)
        val previous = ArrayList<RenderItem>()
        for (angle in listOf(0.0, 0.4, 1.0)) {
            val rotation = Quat.fromAxisAngle(Vec3.unitZ(), angle)
            val out = ArrayList<RenderItem>()
            assertTrue(cache.append(0, centre, position, rotation, out, pool))
            assertEquals(leaves.size, out.size)
            val origin = rotation.rotate(placed.position.copy().subInPlace(centre)).addInPlace(position)
            val partRotation = rotation * placed.rotation
            for ((piece, leaf) in leaves.withIndex()) {
                val expected = partRotation.rotate(leaf.position).addInPlace(origin)
                assertEquals(0.0, expected.distanceTo(out[piece].position), 1e-8)
                val axis = (partRotation * leaf.rotation).rotate(Vec3.unitY())
                assertEquals(0.0, axis.distanceTo(out[piece].rotation.rotate(Vec3.unitY())), 1e-8)
                assertEquals(leaf.shape, out[piece].shape)
                assertEquals(leaf.caps, out[piece].caps)
                var identity = placed.partId.hashCode()
                for (component in listOf(placed.position.x, placed.position.y, placed.position.z))
                    identity = identity * 31 + kotlin.math.round(component * 1000).toInt()
                assertEquals(RenderItem.partKey(37, identity, piece), out[piece].key)
            }
            if (previous.isEmpty()) previous.addAll(out)
        }
        // Publishing subsequent frames must never mutate a previously published frame.
        assertEquals(600_008.0, previous.first().position.x - leaves.first().position.let { placed.rotation.rotate(it).x }, 1e-8)
    }

    @Test fun `lamps and windsocks stay dynamic and paint retains depth bias`() {
        val catalog = catalog()
        val design = CraftDesign("Fittings", listOf("struct-floodlight", "struct-windsock", "struct-paint-bar").map { PlacedPart(it, Vec3()) })
        val cache = StaticScenery(7, design, catalog)
        val pool = RenderItemPool(FrameBus())
        val out = ArrayList<RenderItem>()
        assertFalse(cache.append(0, Vec3(), Vec3(), Quat.identity(), out, pool))
        assertFalse(cache.append(1, Vec3(), Vec3(), Quat.identity(), out, pool))
        assertTrue(cache.append(2, Vec3(), Vec3(), Quat.identity(), out, pool))
        assertTrue(out.isNotEmpty())
        assertTrue(out.all { it.decal == 20 })
    }
}
