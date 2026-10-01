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
import com.rm.apogee.core.math.Math

/** An attach node on a placed part, in design space. */
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
 * Works out where a part goes when joined to another. Two nodes meet: positions line up and outward
 * directions face each other. Orientation, position and whether a join is allowed all follow, so a
 * dropped part snaps into place without rotating it by hand.
 */
object Attachment {

    /**
     * Every node with nothing joined to it. Worked out from what's attached, not a flag, so undo
     * can't put it out of step.
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
                // Lower quarters only, or a part flipped by its mounting would offer them on its
                // back.
                if ((open.direction dot up) < -DOWNWARD) result.add(open)
            }
        }
        return result
    }

    /**
     * Whether [def] can go on [target] in a design built [orientation]-up. On a horizontal craft,
     * ground-touching parts (wheels, legs) go underneath.
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
     * Whether [candidateNode] can join [target]. Stack joins need matching sizes. Surface joins
     * ignore size but need surface nodes on both sides, so a surface part can't hang off a stack
     * end.
     */
    fun compatible(target: OpenNode, candidateNode: AttachNode): Boolean =
        if (target.kind == AttachNodeKind.SURFACE) {
            candidateNode.kind == AttachNodeKind.SURFACE
        } else {
            candidateNode.kind == AttachNodeKind.STACK && target.size == candidateNode.size
        }

    /**
     * The node a new part offers when joining [target]: a compatible one facing the other way if
     * there is one (for a stack, the part's bottom), else any compatible one.
     */
    fun mountNodeFor(def: PartDef, target: OpenNode, exclude: Set<String> = emptySet()): AttachNode? {
        val compatible = def.allAttachNodes.filter { it.id !in exclude && compatible(target, it) }
        if (compatible.isEmpty()) return null
        return compatible.firstOrNull { (it.direction dot target.direction) < -0.5 }
            ?: compatible.first()
    }

    /** Placement for [def] joined to [target] through [mountNode], in design space. */
    fun solve(def: PartDef, mountNode: AttachNode, target: OpenNode, turn: Int = 0): Placement {
        // Turn the part so its node points back into the target's.
        val opposed = target.direction.copy().negateInPlace()
        val rotation = turned(settleRoll(quatFromTo(mountNode.direction, opposed), opposed), opposed, turn)

        // Then place it so the two nodes meet.
        val mountOffset = rotation.rotate(mountNode.position)
        val position = target.position.copy().subInPlace(mountOffset)

        return Placement(position, rotation)
    }

    /** [rotation] with [quarters] quarter turns around [axis] (unit), the join. */
    fun turned(rotation: Quat, axis: Vec3, quarters: Int): Quat {
        val q = Math.floorMod(quarters, 4)
        if (q == 0) return rotation
        return Quat.fromAxisAngle(axis, q * Math.PI / 2.0) * rotation
    }

    /**
     * [turn] rolled about [axis] (unit) so the part's +Y runs as close as it can to the craft's
     * nose, +Y: a wing's chord leading edge first, a fin's or leg's length. Pointing one node into
     * another leaves the roll free, and for exactly opposite nodes the shortest turn's roll is
     * arbitrary.
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
     * [design] with parts on exactly opposite nodes re-rolled the way [solve] does now, fixing
     * older designs with parts upside down or wings swept the wrong way. Only a roll about the
     * join, so nothing moves; parts not sitting on their nodes are left alone.
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
            // Settle it unturned, then turn it again, or loading would add a quarter turn each
            // time.
            val base = turned(placed.rotation, opposed, -placed.turn)
            val rotation = turned(settleRoll(base, opposed), opposed, placed.turn)
            if (rotation.approxEqualsRotation(placed.rotation)) continue
            // The roll is about the join line, so the part turns about its mounting point.
            val node = placed.rotation.rotate(own.position).addInPlace(placed.position)
            val position = node.copy().subInPlace(rotation.rotate(own.position))
            parts[i] = placed.copy(rotation = rotation, position = position)
            changed = true
        }
        return if (changed) design.copy(parts = parts) else design
    }

    /**
     * Copies of a placement around the stack axis (+Y, which the meshes are built around). Returns
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
     * A placement reflected across the centre plane holding the nose (+Y) and sky (+Z) of a
     * horizontal design.
     *
     * Rotating about the nose would put a wheel's copy on the craft's back. A reflection isn't a
     * rotation, so the copy is reflected in space and again in its local Z, which restores
     * handedness. Local Z because a surface part's node lies on its local X axis, so the node stays
     * put and still meets the hull.
     */
    fun mirror(placement: Placement): Placement {
        val r = placement.rotation
        // Reflect across x = 0: a rotation about a becomes one about -(Ma), (w, x, -y, -z). Then
        // half a turn about local Y, which is local X reflected times local Z reflected.
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
