package com.rm.apogee.core.craft

import com.rm.apogee.core.math.Quat
import com.rm.apogee.core.math.Vec3
import com.rm.apogee.core.math.quatFromTo
import com.rm.apogee.core.part.AttachNode
import com.rm.apogee.core.part.AttachNodeKind
import com.rm.apogee.core.part.PartCatalog
import com.rm.apogee.core.part.PartDef

/** An attach node belonging to a specific placed part, resolved into design space. */
class OpenNode(
    /** Index into [CraftDesign.parts], or -1 when the craft is empty. */
    val partIndex: Int,
    val node: AttachNode,
    /** Position in craft-design space. */
    val position: Vec3,
    /** Outward direction in craft-design space. */
    val direction: Vec3,
) {
    val kind: AttachNodeKind get() = node.kind
    val size: Int get() = node.size

    override fun toString(): String = "OpenNode(part=$partIndex, node=${node.id})"
}

/**
 * Works out where a part goes when it is joined to another.
 *
 * The rule is simply that two nodes meet: their positions coincide and their
 * outward directions oppose. Everything the builder needs - orientation,
 * position, whether a join is even legal - falls out of that, which is what
 * lets the player drop a part near a node and have it snap into a sensible
 * orientation rather than having to rotate it into place by hand on a
 * touchscreen.
 */
object Attachment {

    /**
     * Every node on the craft that has nothing joined to it.
     *
     * Occupancy is decided by what is actually attached rather than by a flag,
     * so it cannot drift out of step with the part tree after an undo.
     */
    fun openNodes(design: CraftDesign, catalog: PartCatalog): List<OpenNode> {
        val taken = HashSet<Pair<Int, String>>()
        design.parts.forEachIndexed { index, placed ->
            val parent = placed.parentIndex
            if (parent >= 0) {
                placed.parentNodeId?.let { taken.add(parent to it) }
                placed.ownNodeId?.let { taken.add(index to it) }
            }
        }

        val result = ArrayList<OpenNode>()
        design.parts.forEachIndexed { index, placed ->
            val def = catalog[placed.partId] ?: return@forEachIndexed
            for (node in def.allAttachNodes) {
                if ((index to node.id) in taken) continue
                result.add(resolve(index, placed, node))
            }
        }
        return result
    }

    /** Lifts a part-local node into craft-design space. */
    fun resolve(partIndex: Int, placed: PlacedPart, node: AttachNode): OpenNode {
        val position = placed.rotation.rotate(node.position).addInPlace(placed.position)
        val direction = placed.rotation.rotate(node.direction).normalizeInPlace()
        return OpenNode(partIndex, node, position, direction)
    }

    /**
     * Whether [candidateNode] may join [target].
     *
     * The two kinds do not mix. A stack join needs matching size classes, which
     * is what stops a 0.6m probe core being bolted straight onto a 2.5m
     * booster. A surface join ignores size - a fin does not care what it is
     * stuck to - but must be surface on *both* sides, or a surface-mountable
     * part could be hung off the end of a stack it has no business joining.
     */
    fun compatible(target: OpenNode, candidateNode: AttachNode): Boolean =
        if (target.kind == AttachNodeKind.SURFACE) {
            candidateNode.kind == AttachNodeKind.SURFACE
        } else {
            candidateNode.kind == AttachNodeKind.STACK && target.size == candidateNode.size
        }

    /**
     * The node a new part should present when joining [target].
     *
     * Prefers a compatible node facing the other way, which for a stack means
     * the part's bottom mates with the node above it. Falls back to any
     * compatible node so that an oddly-authored part is still placeable.
     */
    fun mountNodeFor(def: PartDef, target: OpenNode): AttachNode? {
        val compatible = def.allAttachNodes.filter { compatible(target, it) }
        if (compatible.isEmpty()) return null
        return compatible.firstOrNull { (it.direction dot target.direction) < -0.5 }
            ?: compatible.first()
    }

    /**
     * Placement for [def] joined to [target] through [mountNode].
     *
     * @return the rotation and position, in craft-design space.
     */
    fun solve(def: PartDef, mountNode: AttachNode, target: OpenNode): Placement {
        // The part must be turned so its node points back into the target's.
        val opposed = target.direction.copy().negateInPlace()
        val rotation = quatFromTo(mountNode.direction, opposed)

        // With the orientation fixed, the position is whatever puts the two
        // nodes in the same place.
        val mountOffset = rotation.rotate(mountNode.position)
        val position = target.position.copy().subInPlace(mountOffset)

        return Placement(position, rotation)
    }

    /**
     * Copies of a placement mirrored around the craft's long axis.
     *
     * Radial symmetry is rotation about +Y, the stack axis - the same axis the
     * meshes are built around - so a booster placed on one side appears evenly
     * spaced around the craft. Returns [count] placements including the
     * original.
     */
    fun radialSymmetry(placement: Placement, count: Int): List<Placement> {
        if (count <= 1) return listOf(placement)
        val step = 2.0 * Math.PI / count
        return (0 until count).map { index ->
            if (index == 0) return@map placement
            val spin = Quat.fromAxisAngle(Vec3.unitY(), step * index)
            Placement(
                position = spin.rotate(placement.position),
                rotation = spin * placement.rotation,
            )
        }
    }

    class Placement(val position: Vec3, val rotation: Quat) {
        override fun toString(): String = "Placement(pos=$position)"
    }
}
