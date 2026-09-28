package com.rm.apogee.render

import com.rm.apogee.core.craft.CraftDesign
import com.rm.apogee.core.craft.PlacedPart
import com.rm.apogee.core.craft.Shroud
import com.rm.apogee.core.craft.Shrouds
import com.rm.apogee.core.math.Vec3
import com.rm.apogee.core.math.quatFromTo
import com.rm.apogee.core.part.ModelSpec
import com.rm.apogee.core.part.PartCatalog
import com.rm.apogee.core.part.PartDef
import com.rm.apogee.core.part.Tint

/** Engine shrouds, drawn as one more piece of the part that carries them. See [Shrouds]. */
object ShroudLook {

    private val cache = com.rm.apogee.platform.identityMapOf<CraftDesign, Array<Shroud?>>()

    /** Each part's shroud in [design]. Designs don't change, so it's kept against the instance. */
    fun forDesign(design: CraftDesign, catalog: PartCatalog): Array<Shroud?> = com.rm.apogee.platform.synchronized(this) {
        cache[design]?.let { return it }
        if (cache.size >= 64) cache.clear()
        Shrouds.of(design, catalog).also { cache[design] = it }
    }

    /**
     * [shroud] as a piece of [placed] (a [def]), in its own space: a shell standing on the node it's
     * joined by, as wide there as that node's stack and as wide at the top as the engine's.
     */
    fun leaf(def: PartDef, placed: PlacedPart, shroud: Shroud): PartModels.Leaf? {
        val node = def.attachNodes.firstOrNull { it.id == shroud.node } ?: def.attachNodes.firstOrNull { it.direction.y > 0.5 } ?: return null
        if (node.size <= 0) return null
        val bottom = Shrouds.nodeRadius(node.size) + 0.012
        val shell = ModelSpec.Lathe(listOf(listOf(bottom, 0.0), listOf(shroud.radius, shroud.height)), segments = 24)
        return PartModels.Leaf(shell, node.position.copy(), quatFromTo(Vec3.unitY(), node.direction), Tint.STACK, 0)
    }
}
