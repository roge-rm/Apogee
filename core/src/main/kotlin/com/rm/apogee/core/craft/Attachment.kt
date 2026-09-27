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

/** An attach node belonging to a specific placed part, worked out in design space. */
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
 * Works out where a part goes when it's joined to another one.
 *
 * The rule is simply that two nodes meet: their positions line up and their outward directions face
 * each other. Everything the builder needs follows from that, including the orientation, the
 * position, and whether a join is even allowed. That's what lets you drop a part near a node and
 * have it snap into a sensible orientation, instead of having to rotate it into place by hand on a
 * touchscreen.
 */
object Attachment {

    /**
     * Every node on the craft that has nothing joined to it.
     *
     * Whether a node is taken is decided by what's actually attached, not by a flag, so it can't
     * get out of step with the part tree after an undo.
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
                // Lower quarters only. Otherwise a part turned over by the way it was mounted would
                // offer them on its back.
                if ((open.direction dot up) < -DOWNWARD) result.add(open)
            }
        }
        return result
    }

    /**
     * Whether [def] can go on [target] in a design built [orientation]-up.
     *
     * There's only one rule so far: on a horizontal craft, things that touch the ground, like
     * wheels and legs, go underneath. On a standing craft every side is "down" in the same way,
     * which is why the rover used to happily take wheels sticking out in all four directions as
     * well as below it.
     */
    fun accepts(def: PartDef, target: OpenNode, orientation: CraftOrientation): Boolean {
        if (orientation != CraftOrientation.HORIZONTAL) return true
        val touchesGround = def.hasModule<Wheel>() || def.hasModule<LandingLeg>()
        return !touchesGround || (target.direction dot orientation.up) < -DOWNWARD
    }

    /** Moves a part-local node into craft-design space. */
    fun resolve(partIndex: Int, placed: PlacedPart, node: AttachNode): OpenNode {
        val position = placed.rotation.rotate(node.position).addInPlace(placed.position)
        val direction = placed.rotation.rotate(node.direction).normalizeInPlace()
        return OpenNode(partIndex, node, position, direction)
    }

    /**
     * Whether [candidateNode] can join [target].
     *
     * The two kinds don't mix. A stack join needs matching size classes, which stops a 0.6m probe
     * core being bolted straight onto a 2.5m booster. A surface join ignores size, since a fin
     * doesn't care what it's stuck to, but it has to be a surface node on *both* sides. Otherwise a
     * surface-mountable part could be hung off the end of a stack it has no business joining.
     */
    fun compatible(target: OpenNode, candidateNode: AttachNode): Boolean =
        if (target.kind == AttachNodeKind.SURFACE) {
            candidateNode.kind == AttachNodeKind.SURFACE
        } else {
            candidateNode.kind == AttachNodeKind.STACK && target.size == candidateNode.size
        }

    /**
     * The node a new part should offer when joining [target].
     *
     * It prefers a compatible node facing the other way, which for a stack means the part's bottom
     * meets the node above it. If there isn't one it falls back to any compatible node, so an oddly
     * made part can still be placed.
     */
    fun mountNodeFor(def: PartDef, target: OpenNode, exclude: Set<String> = emptySet()): AttachNode? {
        val compatible = def.allAttachNodes.filter { it.id !in exclude && compatible(target, it) }
        if (compatible.isEmpty()) return null
        return compatible.firstOrNull { (it.direction dot target.direction) < -0.5 }
            ?: compatible.first()
    }

    /**
     * Placement for [def] joined to [target] through [mountNode].
     *
     * @return the rotation and position, in craft-design space.
     */
    fun solve(def: PartDef, mountNode: AttachNode, target: OpenNode, turn: Int = 0): Placement {
        // The part has to be turned so its node points back into the target's.
        val opposed = target.direction.copy().negateInPlace()
        val rotation = turned(settleRoll(quatFromTo(mountNode.direction, opposed), opposed), opposed, turn)

        // With the orientation fixed, the position is whatever puts the two nodes in the same
        // place.
        val mountOffset = rotation.rotate(mountNode.position)
        val position = target.position.copy().subInPlace(mountOffset)

        return Placement(position, rotation)
    }

    /**
     * [turn], rolled around [axis] (unit) so the part's own +Y runs as close as it can to the
     * craft's nose, +Y. For a wing that's its chord with the leading edge first, and for a fin or a
     * leg it's the length.
     *
     * Pointing one node back into another fixes everything except the roll around that line. When
     * the two nodes are exactly opposite, like a wing on the craft's left side with its root facing
     * -X into a node facing -X, the shortest turn is a half turn around any axis at right angles,
     * and the one that got picked was arbitrary. Around the vertical, it put the wing's leading
     * edge at the back, so wings were swept back on one side and forward on the other. Every other
     * join already came out this way, and now every join does.
     */
    /** [rotation] with [quarters] quarter turns around [axis] (unit), the join. */
    fun turned(rotation: Quat, axis: Vec3, quarters: Int): Quat {
        val q = Math.floorMod(quarters, 4)
        if (q == 0) return rotation
        return Quat.fromAxisAngle(axis, q * Math.PI / 2.0) * rotation
    }

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
     * [design] with any part that hangs off an exactly opposite node turned the way [solve] turns
     * it now. Parts placed before the roll was sorted out could be upside down or back to front,
     * with wings swept the wrong way. It's only a roll around the join, so nothing moves, and a
     * part whose join doesn't line up with its nodes is left alone.
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
            if ((mountDir dot opposed) < 0.999) continue // not sitting on its node, so leave it
            // Settle it as if it wasn't turned, then turn it again. Along a straight stack there's
            // no roll to settle, and turning something that was already turned would add a quarter
            // turn every time it loaded.
            val base = turned(placed.rotation, opposed, -placed.turn)
            val rotation = turned(settleRoll(base, opposed), opposed, placed.turn)
            if (rotation.approxEqualsRotation(placed.rotation)) continue
            // The roll is around the join line through the node, so the part turns around its own
            // mounting point.
            val node = placed.rotation.rotate(own.position).addInPlace(placed.position)
            val position = node.copy().subInPlace(rotation.rotate(own.position))
            parts[i] = placed.copy(rotation = rotation, position = position)
            changed = true
        }
        return if (changed) design.copy(parts = parts) else design
    }

    /**
     * Copies of a placement arranged around the craft's long axis.
     *
     * Radial symmetry is rotation around +Y, the stack axis, which is the same axis the meshes are
     * built around. So a booster placed on one side appears evenly spaced around the craft. Returns
     * [count] placements, including the original.
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
     * A placement's reflection across the craft's centre plane, the one that holds the nose (+Y)
     * and the sky (+Z) of a horizontal design.
     *
     * Rotating around the nose is the wrong symmetry for something lying down, because it puts the
     * copy of a wheel on the craft's back. A reflection isn't a rotation, so the copy is reflected
     * in space and then again in its own local Z. That cancels out the handedness and leaves a
     * proper rotation. I picked local Z because a surface part's mounting node lies on its local X
     * axis, so the second reflection leaves the node exactly where it was and the copy still meets
     * the hull.
     */
    fun mirror(placement: Placement): Placement {
        val r = placement.rotation
        // Reflect across x = 0: a rotation around a becomes one around -(Ma), which is (w, x, -y,
        // -z). Then half a turn around local Y, which is local X reflected times local Z reflected.
        val reflected = Quat(r.x, -r.y, -r.z, r.w)
        val rotation = reflected * Quat.fromAxisAngle(Vec3.unitY(), Math.PI)
        val p = placement.position
        return Placement(Vec3(-p.x, p.y, p.z), rotation)
    }

    /** Cosine of how far below level a node can face and still count as underneath. */
    private const val DOWNWARD = 0.5

    class Placement(val position: Vec3, val rotation: Quat) {
        override fun toString(): String = "Placement(pos=$position)"
    }
}
