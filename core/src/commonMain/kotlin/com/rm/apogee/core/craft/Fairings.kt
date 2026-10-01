package com.rm.apogee.core.craft

import com.rm.apogee.core.math.Vec3
import com.rm.apogee.core.part.Fairing
import com.rm.apogee.core.part.PartDef

/** What a closed fairing holds. */
object Fairings {

    /** Margin around the shell in metres, so a part just touching it counts as inside. */
    private const val LEEWAY = 0.15

    /**
     * For each part, whether it's inside a closed fairing ([open] says which are open): wholly
     * within the shell on the fairing's base, above its top face, inside its radius and below its
     * height.
     */
    fun enclosed(design: CraftDesign, defs: List<PartDef>, open: (Int) -> Boolean): BooleanArray {
        val out = BooleanArray(design.parts.size)
        val axis = Vec3()
        val offset = Vec3()
        for (f in design.parts.indices) {
            val fairing = defs.getOrNull(f)?.module<Fairing>() ?: continue
            if (open(f)) continue
            val base = design.parts[f]
            base.rotation.rotate(Vec3.unitY(), axis)
            val floor = defs[f].boundsHalfExtents.y
            for (i in design.parts.indices) {
                if (i == f) continue
                val part = design.parts[i]
                val half = defs[i].boundsHalfExtents
                offset.setTo(part.position).subInPlace(base.position)
                val along = offset dot axis
                val across = offset.addScaledInPlace(axis, -along).length
                val reach = maxOf(half.x, half.z)
                if (along - half.y >= floor - LEEWAY && along + half.y <= floor + fairing.height + LEEWAY &&
                    across + reach <= fairing.radius + LEEWAY
                ) out[i] = true
            }
        }
        return out
    }
}
