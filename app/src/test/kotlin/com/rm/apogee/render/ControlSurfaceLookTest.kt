package com.rm.apogee.render

import com.rm.apogee.core.craft.StockCraft
import com.rm.apogee.core.math.Vec3
import com.rm.apogee.core.part.AeroSurface
import com.rm.apogee.core.part.PieceRole
import com.rm.apogee.core.part.ModelSpec
import com.rm.apogee.core.part.StockParts
import com.rm.apogee.core.world.World
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * A control surface is drawn deflecting the way that makes the force the
 * physics applies: its trailing edge goes one way, the air pushes the surface
 * the other. Drawn backwards, an elevon would visibly contradict what the
 * plane then does.
 */
class ControlSurfaceLookTest {

    @Test
    fun `surfaces are drawn deflecting against the force they make`() {
        val catalog = StockParts.catalog
        val world = World.default(catalog)
        val plane = world.spawnOnSurface(StockCraft.aeroplane(catalog), World.launchSites.first())
        var checked = 0
        for (command in listOf(Triple(1.0, 0.0, 0.0), Triple(0.0, 1.0, 0.0), Triple(0.0, 0.0, 1.0))) {
            plane.control.pitch = command.first
            plane.control.roll = command.second
            plane.control.yaw = command.third
            world.step(1.0 / 60.0)
            val centre = plane.centerOfMass(Vec3())
            for (i in plane.defs.indices) {
                val def = plane.defs[i]
                val surface = def.module<AeroSurface>() ?: continue
                val d = plane.surfaceDeflection[i]
                if (!surface.controllable || kotlin.math.abs(d) < 1e-3) continue
                val placed = plane.design.parts[i]
                // The force the physics applies: across the fuselage (+Y) and
                // the mounting radius, signed by the deflection.
                val offset = Vec3().setTo(placed.position).subInPlace(centre)
                val radial = Vec3(offset.x, 0.0, offset.z).normalizeInPlace()
                val force = Vec3(0.0, 1.0, 0.0).cross(radial).normalizeInPlace().mulInPlace(Math.signum(d))

                // Where the drawn trailing edge moves: the hinged piece's
                // trailing edge, posed at rest and at this deflection.
                val model = def.model as ModelSpec.Compound
                val hinged = model.pieces.first { it.role == PieceRole.HINGED }
                check(hinged.model is ModelSpec.Fin)
                val anim = PartAnim()
                PartModels.alignSurface(def, placed.rotation, offset, anim)
                val rest = trailingEdge(def, 0.0, anim.hingeSign)
                val moved = trailingEdge(def, d, anim.hingeSign)
                val shift = placed.rotation.rotate(moved.subInPlace(rest))
                assertTrue(
                    "${def.id} #$i: trailing edge moved ${shift} with the force ${force}",
                    (shift dot force) < 0.0,
                )
                checked++
            }
        }
        assertTrue("expected control surfaces to check, checked $checked", checked >= 3)
    }

    /** Where the hinged piece's root trailing edge is drawn, in part space, at [deflection]. */
    private fun trailingEdge(def: com.rm.apogee.core.part.PartDef, deflection: Double, hingeSign: Double): Vec3 {
        val leaves = ArrayList<PartModels.Leaf>()
        PartModels.expand(def, StackCaps.BOTH, PartAnim(deflection = deflection, hingeSign = hingeSign), leaves)
        // The hinged piece is the second leaf of these models.
        val leaf = leaves[1]
        val chord = (leaf.shape as ModelSpec.Fin).rootChord
        return leaf.rotation.rotate(Vec3(0.0, -chord * 0.5, 0.0)).addInPlace(leaf.position)
    }
}
