package com.rm.apogee.core.craft

import com.rm.apogee.core.math.Vec3
import com.rm.apogee.core.part.AttachNodeKind
import com.rm.apogee.core.part.Engine
import com.rm.apogee.core.part.Exhaust
import com.rm.apogee.core.part.PartCatalog
import kotlinx.serialization.Serializable

/**
 * A shell round an engine that has something attached under it, carried by that part: [height]
 * metres up from the join, narrowing or widening to [radius] at the top where the engine's tank
 * sits. When the stage below drops away the shell splits and falls away in pieces, leaving the
 * engine above bare to fire. Without it an upper stage's engine hung in the open between the
 * stages.
 */
@Serializable
data class Shroud(
    val radius: Double,
    val height: Double,
    /** The carrying part's node it stands on. */
    val node: String = "",
)

object Shrouds {

    /** How much wider than the stack a shell stands, in metres, so it doesn't flicker against it. */
    private const val CLEARANCE = 0.012

    /** A stack node's radius in metres, by its size: 1.25, 2.5 or 3.75 metres across. */
    fun nodeRadius(size: Int): Double = size * 0.625

    /** How near two nodes have to be to count as joined, in metres. */
    private const val JOINED = 0.05

    /**
     * Each part's shroud, or null: one it carries for a rocket engine whose bottom it's joined to.
     * It goes by where the nodes are, not the tree, because the stock craft are put together by
     * position, and an engine can be built onto the part under it as well as the other way round.
     * A shell shed when its stage dropped isn't one of them (see [PlacedPart.shroud]).
     */
    fun of(design: CraftDesign, catalog: PartCatalog): Array<Shroud?> {
        val out = arrayOfNulls<Shroud>(design.parts.size)
        val parts = design.parts
        val at = Vec3(); val axis = Vec3(); val there = Vec3(); val facing = Vec3()
        for ((e, engine) in parts.withIndex()) {
            val def = catalog[engine.partId] ?: continue
            if (def.module<Engine>()?.exhaustKind != Exhaust.ROCKET) continue
            val nodes = def.attachNodes.filter { it.kind == AttachNodeKind.STACK && it.size > 0 }
            val bottom = nodes.firstOrNull { it.direction.y < -0.5 } ?: continue
            val top = nodes.firstOrNull { it.direction.y > 0.5 } ?: continue
            engine.rotation.rotate(bottom.position, at).addInPlace(engine.position)
            engine.rotation.rotate(bottom.direction, axis)
            // Its neighbours in the tree: what hangs from it, and what it hangs from.
            for ((c, carrier) in parts.withIndex()) {
                if (c == e || !(carrier.parentIndex == e || engine.parentIndex == c)) continue
                val carrierDef = catalog[carrier.partId] ?: continue
                val node = carrierDef.attachNodes.firstOrNull { n ->
                    n.kind == AttachNodeKind.STACK && n.size > 0 &&
                        carrier.rotation.rotate(n.position, there).addInPlace(carrier.position).distanceTo(at) < JOINED &&
                        (carrier.rotation.rotate(n.direction, facing) dot axis) < -0.9
                } ?: continue
                out[c] = Shroud(nodeRadius(top.size) + CLEARANCE, top.position.y - bottom.position.y, node.id)
            }
        }
        return out
    }
}
