package com.rm.apogee.render

import com.rm.apogee.core.craft.CraftDesign
import com.rm.apogee.core.math.Vec3
import com.rm.apogee.core.part.MeshSpec
import com.rm.apogee.core.part.PartCatalog
import com.rm.apogee.platform.synchronized

/**
 * Which end caps of each part are actually showing.
 *
 * Parts in a stack meet exactly, which leaves each joint with two cap discs in the same plane, and
 * back-face culling then does something unhelpful. From above, the lower part's top cap faces the
 * camera and gets drawn, while the surface that ought to hide it (the upper part's *bottom* cap)
 * faces away and gets culled. The wall of the upper part can't help, because a ray heading inward
 * to the cap stays inside the cylinder and never crosses it. The result is a dark disc across every
 * joint, which looks like the parts don't fit together.
 *
 * Making the parts longer so they overlap doesn't fix it, for the same reason: the thing in the way
 * is still a culled back face. Sinking the caps in is worse, because it opens a well you can see
 * into from a steeper angle. The only thing that works is not drawing a cap that something else is
 * covering, and that's what this decides.
 *
 * It's geometric, not taken from the attachment tree. Attach nodes say what was joined to what in
 * the builder, and a merged base (modules welded where they stood) has parts that cover each other
 * without any node saying so. Asking where the faces actually are answers both.
 */
object StackCaps {

    const val TOP = 1
    const val BOTTOM = 2
    const val BOTH = TOP or BOTTOM

    /** Metres two faces can differ by and still count as the same plane. */
    private const val PLANE_TOLERANCE = 0.05

    /**
     * Designs don't change, so the answer is cached against the instance.
     *
     * It's limited, because a craft gets a *new* design object every time its structure changes
     * (staging, decoupling, welding), and a long flight would otherwise leave one entry per event
     * behind it. Dropping the lot when it grows costs one recomputation of a few dozen comparisons.
     */
    private val cache = com.rm.apogee.platform.identityMapOf<CraftDesign, IntArray>()

    private const val MAX_CACHED_DESIGNS = 64

    fun forDesign(design: CraftDesign, catalog: PartCatalog): IntArray {
        return synchronized(this) {
            cache[design]?.let { return it }
            if (cache.size >= MAX_CACHED_DESIGNS) cache.clear()
            return compute(design, catalog).also { cache[design] = it }
        }
    }

    private fun compute(design: CraftDesign, catalog: PartCatalog): IntArray {
        val count = design.parts.size
        val masks = IntArray(count) { BOTH }

        val axis = arrayOfNulls<Vec3>(count)
        val topFace = arrayOfNulls<Vec3>(count)
        val bottomFace = arrayOfNulls<Vec3>(count)
        val topRadius = DoubleArray(count)
        val bottomRadius = DoubleArray(count)

        for (i in 0 until count) {
            val def = catalog[design.parts[i].partId] ?: continue
            val mesh = def.mesh
            val half = axialHalfHeight(mesh) ?: continue
            val placed = design.parts[i]

            val up = placed.rotation.rotate(Vec3.unitY(), Vec3())
            axis[i] = up
            topFace[i] = Vec3().setTo(placed.position).addScaledInPlace(up, half)
            bottomFace[i] = Vec3().setTo(placed.position).addScaledInPlace(up, -half)
            topRadius[i] = endRadius(mesh, top = true)
            bottomRadius[i] = endRadius(mesh, top = false)
        }

        for (i in 0 until count) {
            val ai = axis[i] ?: continue
            for (j in 0 until count) {
                if (i == j) continue
                val aj = axis[j] ?: continue
                // Only parts standing the same way up can cap each other.
                if (kotlin.math.abs(ai dot aj) < 0.99) continue

                // i's top is covered by a face of j lying in the same plane, as long as j is at
                // least as wide there. A narrower part leaves a visible shoulder, which is real
                // geometry and should be drawn.
                if (masks[i] and TOP != 0 && coversFace(
                        topFace[i]!!, topRadius[i], bottomFace[j]!!, bottomRadius[j],
                    ) || masks[i] and TOP != 0 && coversFace(
                        topFace[i]!!, topRadius[i], topFace[j]!!, topRadius[j],
                    )
                ) {
                    masks[i] = masks[i] and TOP.inv()
                }
                if (masks[i] and BOTTOM != 0 && coversFace(
                        bottomFace[i]!!, bottomRadius[i], topFace[j]!!, topRadius[j],
                    ) || masks[i] and BOTTOM != 0 && coversFace(
                        bottomFace[i]!!, bottomRadius[i], bottomFace[j]!!, bottomRadius[j],
                    )
                ) {
                    masks[i] = masks[i] and BOTTOM.inv()
                }
            }
        }
        return masks
    }

    private fun coversFace(
        face: Vec3,
        radius: Double,
        otherFace: Vec3,
        otherRadius: Double,
    ): Boolean {
        if (otherRadius + PLANE_TOLERANCE < radius) return false
        val dx = face.x - otherFace.x
        val dy = face.y - otherFace.y
        val dz = face.z - otherFace.z
        return dx * dx + dy * dy + dz * dz <= PLANE_TOLERANCE * PLANE_TOLERANCE
    }

    /** Half the extent along the part's own +Y, or null for shapes with no caps. */
    private fun axialHalfHeight(mesh: MeshSpec): Double? = when (mesh) {
        is MeshSpec.Cylinder -> mesh.height * 0.5
        is MeshSpec.Cone -> mesh.height * 0.5
        else -> null
    }

    private fun endRadius(mesh: MeshSpec, top: Boolean): Double = when (mesh) {
        is MeshSpec.Cylinder -> mesh.radius
        is MeshSpec.Cone -> if (top) mesh.topRadius else mesh.bottomRadius
        else -> 0.0
    }
}
