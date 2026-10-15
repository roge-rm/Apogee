package com.rm.apogee.game

import com.rm.apogee.core.craft.CraftDesign
import com.rm.apogee.core.math.Quat
import com.rm.apogee.core.math.Vec3
import com.rm.apogee.core.part.ModelSpec
import com.rm.apogee.core.part.PartCatalog
import com.rm.apogee.core.part.PartCategory
import com.rm.apogee.core.part.PieceRole
import com.rm.apogee.render.PartModels
import com.rm.apogee.render.RenderItem
import com.rm.apogee.render.StackCaps

/** Immutable building pieces in design space. The planet's rotation is still applied each frame. */
internal class StaticScenery(vessel: Long, val design: CraftDesign, catalog: PartCatalog) {
    private val parts: Array<List<RenderItem>?> = arrayOfNulls(design.parts.size)

    init {
        val caps = StackCaps.forDesign(design, catalog)
        for ((index, placed) in design.parts.withIndex()) {
            val def = catalog[placed.partId] ?: continue
            // Fittings with lights, moving models and windsocks keep their ordinary presentation.
            if (def.category != PartCategory.STRUCTURE || def.modules.isNotEmpty() ||
                placed.partId == "struct-windsock" || placed.shroud != null ||
                def.model?.let { !fixed(it) } == true) continue
            val leaves = ArrayList<PartModels.Leaf>()
            PartModels.expand(def, caps[index], null, leaves)
            val body = PartModels.bodyColour(placed.partId)
            val identity = identity(placed.partId, placed.position)
            parts[index] = leaves.mapIndexed { piece, leaf ->
                RenderItem(
                    shape = leaf.shape,
                    position = placed.rotation.rotate(leaf.position).addInPlace(placed.position),
                    rotation = placed.rotation * leaf.rotation,
                    color = PartModels.colour(leaf.tint, body),
                    caps = leaf.caps,
                    wrap = false,
                    key = RenderItem.partKey(vessel, identity, piece),
                    decal = if (placed.partId.startsWith("struct-paint")) 20 else 0,
                )
            }
        }
    }

    /** False means this part needs the animated/damaged presentation path. Published items are never mutated. */
    fun append(index: Int, centre: Vec3, position: Vec3, rotation: Quat, out: MutableList<RenderItem>): Boolean {
        val pieces = parts[index] ?: return false
        for (piece in pieces) out.add(RenderItem(
            shape = piece.shape,
            position = rotation.rotate(piece.position.copy().subInPlace(centre)).addInPlace(position),
            rotation = rotation * piece.rotation,
            color = piece.color,
            caps = piece.caps,
            wrap = false,
            key = piece.key,
            decal = piece.decal,
        ))
        return true
    }

    private fun fixed(model: ModelSpec): Boolean = when (model) {
        is ModelSpec.Compound -> model.pieces.all { it.role == PieceRole.FIXED && fixed(it.model) }
        else -> true
    }

    private fun identity(id: String, position: Vec3): Int {
        var hash = id.hashCode()
        hash = hash * 31 + kotlin.math.round(position.x * 1_000.0).toInt()
        hash = hash * 31 + kotlin.math.round(position.y * 1_000.0).toInt()
        return hash * 31 + kotlin.math.round(position.z * 1_000.0).toInt()
    }
}
