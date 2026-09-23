package com.rm.apogee.core.craft

import com.rm.apogee.core.math.Quat
import com.rm.apogee.core.math.Vec3
import com.rm.apogee.core.math.quatFromTo
import com.rm.apogee.core.part.AttachNode
import com.rm.apogee.core.part.AttachNodeKind
import com.rm.apogee.core.part.LandingLeg
import com.rm.apogee.core.part.PartCatalog
import com.rm.apogee.core.part.PartDef
import com.rm.apogee.core.part.Wheel

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

        val horizontal = design.orientation == CraftOrientation.HORIZONTAL
        val up = design.orientation.up
        val result = ArrayList<OpenNode>()
        design.parts.forEachIndexed { index, placed ->
            val def = catalog[placed.partId] ?: return@forEachIndexed
            for (node in def.allAttachNodes) {
                if ((index to node.id) in taken) continue
                result.add(resolve(index, placed, node))
            }
            if (!horizontal) return@forEachIndexed
            for (node in def.quarterNodes) {
                if ((index to node.id) in taken) continue
                val open = resolve(index, placed, node)
                // Lower quarters only. A part turned over by the way it was
                // mounted would otherwise offer them on its back.
                if ((open.direction dot up) < -DOWNWARD) result.add(open)
            }
        }
        return result
    }

    /**
     * Whether [def] may go on [target] in a design built [orientation]-up.
     *
     * The one rule so far: on a horizontal craft, things that touch the
     * ground - wheels, legs - go underneath. On a standing craft every side
     * is "down" in the same sense, which is why the rover used to have wheels
     * sticking out in all four directions as readily as below it.
     */
    fun accepts(def: PartDef, target: OpenNode, orientation: CraftOrientation): Boolean {
        if (orientation != CraftOrientation.HORIZONTAL) return true
        val touchesGround = def.hasModule<Wheel>() || def.hasModule<LandingLeg>()
        return !touchesGround || (target.direction dot orientation.up) < -DOWNWARD
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
        val rotation = settleRoll(quatFromTo(mountNode.direction, opposed), opposed)

        // With the orientation fixed, the position is whatever puts the two
        // nodes in the same place.
        val mountOffset = rotation.rotate(mountNode.position)
        val position = target.position.copy().subInPlace(mountOffset)

        return Placement(position, rotation)
    }

    /**
     * [turn], rolled about [axis] (unit) so the part's own +Y - a wing's
     * chord, leading edge first; a fin's; a leg's length - runs as nearly as
     * it can along the craft's nose, +Y.
     *
     * Pointing one node back into another fixes everything but the roll
     * about that line, and when the two nodes are exactly opposed - a wing
     * on the craft's left side, its root facing -X into a node facing -X -
     * the shortest turn is a half turn about any axis at right angles, and
     * the one picked was arbitrary. About the vertical, it put the wing's
     * leading edge at the back: swept back on one side, forward on the
     * other. Every non-opposed join already came out this way; now every
     * join does.
     */
    fun settleRoll(turn: Quat, axis: Vec3): Quat {
        val chord = turn.rotate(Vec3.unitY())
        val want = Vec3.unitY().addScaledInPlace(axis, -axis.y)
        val have = chord.copy().addScaledInPlace(axis, -(chord dot axis))
        if (want.lengthSq < 1e-9 || have.lengthSq < 1e-9) return turn
        want.normalizeInPlace(); have.normalizeInPlace()
        val angle = kotlin.math.atan2((have.cross(want)) dot axis, (have dot want).coerceIn(-1.0, 1.0))
        if (kotlin.math.abs(angle) < 1e-9) return turn
        return Quat.fromAxisAngle(axis, angle) * turn
    }

    /**
     * [design] with any part that hangs off an exactly opposed node turned
     * the way [solve] turns it now: parts placed before the roll was settled
     * could be upside-down or back to front, and wings swept the wrong way.
     * Only a roll about the join, so nothing moves - a part whose join does
     * not line up with its nodes is left as it is.
     */
    fun settled(design: CraftDesign, catalog: PartCatalog): CraftDesign {
        var changed = false
        val parts = design.parts.toMutableList()
        for (i in parts.indices) {
            val placed = parts[i]
            val parent = parts.getOrNull(placed.parentIndex) ?: continue
            val def = catalog[placed.partId] ?: continue
            val parentDef = catalog[parent.partId] ?: continue
            val own = def.allAttachNodes.firstOrNull { it.id == placed.ownNodeId } ?: continue
            val target = parentDef.allAttachNodes.firstOrNull { it.id == placed.parentNodeId }
                ?: parentDef.quarterNodes.firstOrNull { it.id == placed.parentNodeId } ?: continue
            val open = resolve(placed.parentIndex, parent, target)
            val opposed = open.direction.copy().negateInPlace()
            val mountDir = placed.rotation.rotate(own.direction)
            if ((mountDir dot opposed) < 0.999) continue // not seated on its node: leave it
            val rotation = settleRoll(placed.rotation, opposed)
            if (rotation.approxEqualsRotation(placed.rotation)) continue
            // The roll is about the join line through the node, so the part
            // turns about its own mounting point.
            val node = placed.rotation.rotate(own.position).addInPlace(placed.position)
            val position = node.copy().subInPlace(rotation.rotate(own.position))
            parts[i] = placed.copy(rotation = rotation, position = position)
            changed = true
        }
        return if (changed) design.copy(parts = parts) else design
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

    /**
     * A placement's reflection across the craft's centre plane, the one
     * holding the nose (+Y) and the sky (+Z) of a horizontal design.
     *
     * Rotation about the nose is the wrong symmetry for something lying down:
     * it puts the copy of a wheel on the craft's back. A reflection is not a
     * rotation, so the copy is reflected in space and again in its own local
     * Z, which cancels the handedness and leaves a proper rotation. Local Z is
     * chosen because a surface part's mounting node lies on its local X axis,
     * so that second reflection leaves the node exactly where it was and the
     * copy still meets the hull.
     */
    fun mirror(placement: Placement): Placement {
        val r = placement.rotation
        // Reflect across x = 0: a rotation about a turns into one about
        // -(Ma), which is (w, x, -y, -z). Then half a turn about local Y,
        // which is local X reflected times local Z reflected.
        val reflected = Quat(r.x, -r.y, -r.z, r.w)
        val rotation = reflected * Quat.fromAxisAngle(Vec3.unitY(), Math.PI)
        val p = placement.position
        return Placement(Vec3(-p.x, p.y, p.z), rotation)
    }

    /** Cosine of how far below level a node may face and still count as underneath. */
    private const val DOWNWARD = 0.5

    class Placement(val position: Vec3, val rotation: Quat) {
        override fun toString(): String = "Placement(pos=$position)"
    }
}
