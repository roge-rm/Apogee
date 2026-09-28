package com.rm.apogee.core.craft

import com.rm.apogee.core.math.Quat
import com.rm.apogee.core.math.Vec3
import com.rm.apogee.core.part.AttachNodeKind
import com.rm.apogee.core.part.PartCatalog
import com.rm.apogee.core.math.Math

/**
 * A piece of a craft you're holding: a part and everything hanging from it, not joined to anything.
 * It could be a part picked from the drawer, a booster lifted off so you can move it, or a copy of
 * a wing to put on the other side.
 *
 * [parts] are in the first part's own frame, so it sits at the origin and the rest sit where they
 * were relative to it, with parent indices into this list. Putting it on a node is then one rigid
 * move of the whole lot.
 */
data class Assembly(
    val parts: List<PlacedPart>,
    /** The node the first part hung from, to hang from again if it fits. Null to pick one. */
    val mountNodeId: String? = null,
    /** The stage each part fired in, when it came from a sequence arranged by hand. -1 for none. */
    val stages: List<Int> = emptyList(),
) {
    val rootPartId: String get() = parts[0].partId
    val size: Int get() = parts.size

    companion object {
        /** One new part, as picked from the drawer. */
        fun of(partId: String) = Assembly(listOf(PlacedPart(partId, Vec3.zero())))
    }
}

/**
 * Taking pieces off a design and putting them on. These are pure functions: each one returns a new
 * design, and the builder decides what becomes an undo step.
 */
object Assemblies {

    /**
     * [design] with [index]'s piece lifted off: what's left, what's being held, and how many parts
     * came off together.
     */
    class Lift(val rest: CraftDesign, val assembly: Assembly, val copies: Int)

    /** Where an assembly went: the design with it attached, and the indices of every part added. */
    class Attached(val design: CraftDesign, val added: List<Int>)

    /**
     * Part [index] and everything below it, as an assembly. The design itself isn't changed. Null
     * for a part that isn't in the design.
     */
    fun extract(design: CraftDesign, index: Int): Assembly? {
        if (index !in design.parts.indices) return null
        val subtree = design.subtreeOf(index)
        val local = HashMap<Int, Int>(subtree.size)
        subtree.forEachIndexed { i, old -> local[old] = i }
        val root = design.parts[index]
        val undo = root.rotation.conjugate()
        val parts = subtree.map { old ->
            val placed = design.parts[old]
            val offset = undo.rotate(Vec3().setTo(placed.position).subInPlace(root.position))
            placed.copy(
                position = offset,
                rotation = (undo * placed.rotation).normalizeInPlace(),
                parentIndex = if (old == index) -1 else local[placed.parentIndex] ?: -1,
                parentNodeId = if (old == index) null else placed.parentNodeId,
                ownNodeId = if (old == index) null else placed.ownNodeId,
                dockedTo = -1,
                dockedFrom = null,
            )
        }
        val stages = if (!design.manualStaging) emptyList()
            else subtree.map { old -> design.stages.indexOfFirst { old in it.activatedParts } }
        return Assembly(parts, root.ownNodeId, stages)
    }

    /**
     * Part [index] lifted off with everything below it, along with its symmetry partners and
     * theirs, because parts placed together move together. Null for the root, since everything else
     * hangs from it.
     */
    fun lift(design: CraftDesign, index: Int): Lift? {
        if (index !in design.parts.indices || design.parts[index].parentIndex < 0) return null
        val group = design.parts[index].symmetryGroup
        val roots = if (group >= 0) design.parts.indices.filter { design.parts[it].symmetryGroup == group } else listOf(index)
        val taken = roots.flatMap { design.subtreeOf(it) }.toSet()
        val kept = design.parts.indices.filter { it !in taken }
        if (kept.isEmpty()) return null
        val assembly = extract(design, index) ?: return null
        return Lift(design.keeping(kept), assembly, roots.size)
    }

    /**
     * [assembly] put on [target], and copied around by [symmetry] when the join is a surface one,
     * exactly like a single part. Null when it won't go there.
     */
    fun attach(
        design: CraftDesign,
        assembly: Assembly,
        target: OpenNode,
        symmetry: SymmetryMode,
        catalog: PartCatalog,
    ): Attached? {
        val def = catalog[assembly.rootPartId] ?: return null
        if (!Attachment.accepts(def, target, design.orientation)) return null
        // Use its own node if that still fits here, otherwise whichever one does, leaving alone the
        // nodes its own pieces hang from.
        val busy = assembly.parts.filter { it.parentIndex == 0 }.mapNotNull { it.parentNodeId }.toSet()
        val mountNode = assembly.mountNodeId
            ?.let { id -> def.allAttachNodes.firstOrNull { it.id == id && Attachment.compatible(target, it) } }
            ?: Attachment.mountNodeFor(def, target, busy)
            ?: return null
        val placement = Attachment.solve(def, mountNode, target, assembly.parts[0].turn)

        // Symmetry only makes sense radially, around the stack. Applying it to a stack join would
        // pile several parts in the same place, so it only happens when one side of the join is a
        // surface mount. A fin on a tank qualifies whichever side declares it.
        val surfaceJoin = target.kind == AttachNodeKind.SURFACE || mountNode.kind == AttachNodeKind.SURFACE
        val count = if (surfaceJoin) symmetry.count else 1
        // Which node each copy hangs from. Radial copies have only ever recorded the original's
        // node. A mirrored copy records its own, so the node it covers stops showing as open.
        val targets = ArrayList<OpenNode>()
        val copies: List<(Attachment.Placement) -> Attachment.Placement> = when {
            count <= 1 -> listOf { it }
            design.orientation == CraftOrientation.HORIZONTAL -> {
                val mirrored = Attachment.mirror(placement)
                // On the centreline, the reflection is the part itself.
                if (mirrored.position.distanceTo(placement.position) < CENTRELINE) {
                    listOf { it }
                } else {
                    val mirrorTarget = Attachment.openNodes(design, catalog).firstOrNull {
                        it.partIndex == target.partIndex && it.position.distanceTo(
                            Vec3(-target.position.x, target.position.y, target.position.z)
                        ) < CENTRELINE
                    }
                    targets.add(target)
                    targets.add(mirrorTarget ?: target)
                    listOf({ it }, { Attachment.mirror(it) })
                }
            }
            else -> {
                val step = 2.0 * Math.PI / count
                (0 until count).map { i -> spun(Quat.fromAxisAngle(Vec3.unitY(), step * i)) }
            }
        }

        // Each piece where it lands, before copying.
        val laid = assembly.parts.map { part ->
            Attachment.Placement(
                placement.rotation.rotate(part.position).addInPlace(placement.position),
                (placement.rotation * part.rotation).normalizeInPlace(),
            )
        }

        val parts = design.parts.toMutableList()
        val added = ArrayList<Int>(assembly.size * copies.size)
        var nextGroup = (design.parts.maxOfOrNull { it.symmetryGroup } ?: -1) + 1
        // Once copied around, a piece and its copies are one group. When it's placed once, pieces
        // that were placed together inside it stay together.
        val groupOf = IntArray(assembly.size) { -1 }
        if (copies.size > 1) {
            for (k in groupOf.indices) groupOf[k] = nextGroup++
        } else {
            val inner = assembly.parts.map { it.symmetryGroup }
            val renamed = HashMap<Int, Int>()
            for (k in 1 until assembly.size) {
                val g = inner[k]
                if (g >= 0 && inner.count { it == g } > 1) groupOf[k] = renamed.getOrPut(g) { nextGroup++ }
            }
        }
        val mirrored = copies.size == 2 && design.orientation == CraftOrientation.HORIZONTAL
        copies.forEachIndexed { c, copy ->
            val base = parts.size
            val flip = mirrored && c == 1
            assembly.parts.forEachIndexed { k, part ->
                val at = copy(laid[k])
                added.add(parts.size)
                parts.add(
                    part.copy(
                        position = at.position,
                        rotation = at.rotation,
                        parentIndex = if (k == 0) target.partIndex else base + part.parentIndex,
                        parentNodeId = if (k == 0) (targets.getOrNull(c) ?: target).node.id else part.parentNodeId,
                        ownNodeId = if (k == 0) mountNode.id else part.ownNodeId,
                        symmetryGroup = groupOf[k],
                        // A reflection turns the other way.
                        turn = if (flip) Math.floorMod(-part.turn, 4) else part.turn,
                        dockedTo = -1,
                        dockedFrom = null,
                    )
                )
            }
        }

        // A sequence arranged by hand keeps the pieces firing where they did before.
        val stages = if (!design.manualStaging || assembly.stages.isEmpty()) design.stages else {
            val lists = design.stages.map { it.activatedParts.toMutableList() }
            copies.indices.forEach { c ->
                for (k in 0 until assembly.size) {
                    val s = assembly.stages.getOrElse(k) { -1 }
                    if (s in lists.indices) lists[s].add(design.parts.size + c * assembly.size + k)
                }
            }
            lists.map { Stage(it) }
        }
        return Attached(design.copy(parts = parts, stages = stages, catalogHash = catalog.contentHash), added)
    }

    /**
     * [design] with part [index] (and its partners, turned the matching way) given [quarters] more
     * quarter turns around its join, along with everything hanging from it. Null for the root,
     * which has no join to turn around.
     */
    fun turn(design: CraftDesign, index: Int, quarters: Int, catalog: PartCatalog): CraftDesign? {
        if (index !in design.parts.indices || design.parts[index].parentIndex < 0) return null
        val group = design.parts[index].symmetryGroup
        val roots = if (group >= 0) design.parts.indices.filter { design.parts[it].symmetryGroup == group } else listOf(index)
        val parts = design.parts.toMutableList()
        for (root in roots) {
            val placed = parts[root]
            val parent = parts[placed.parentIndex]
            val parentDef = catalog[parent.partId] ?: continue
            val node = (parentDef.allAttachNodes + parentDef.quarterNodes).firstOrNull { it.id == placed.parentNodeId } ?: continue
            val open = Attachment.resolve(placed.parentIndex, parent, node)
            val axis = open.direction.copy().negateInPlace()
            // A mirrored partner turns the other way, because a reflection reverses the direction
            // of a turn.
            val sense = if (root != index && design.orientation == CraftOrientation.HORIZONTAL && roots.size == 2) -1 else 1
            val q = Math.floorMod(sense * quarters, 4)
            if (q == 0) continue
            val spin = Quat.fromAxisAngle(axis, q * Math.PI / 2.0)
            val pivot = open.position
            for (i in design.subtreeOf(root)) {
                val p = parts[i]
                val moved = spin.rotate(Vec3().setTo(p.position).subInPlace(pivot)).addInPlace(pivot)
                parts[i] = p.copy(position = moved, rotation = (spin * p.rotation).normalizeInPlace())
            }
            parts[root] = parts[root].copy(turn = Math.floorMod(placed.turn + q, 4))
        }
        return design.copy(parts = parts)
    }

    private fun spun(spin: Quat): (Attachment.Placement) -> Attachment.Placement =
        { p -> Attachment.Placement(spin.rotate(p.position), spin * p.rotation) }

    /** How many metres away a reflected part can land and still count as landing on itself. */
    private const val CENTRELINE = 0.05
}

/** This design with only the parts in [keep], in that order, with parents and stages renumbered. */
fun CraftDesign.keeping(keep: List<Int>): CraftDesign {
    val remap = HashMap<Int, Int>(keep.size)
    keep.forEachIndexed { newIndex, oldIndex -> remap[oldIndex] = newIndex }
    val kept = keep.map { oldIndex ->
        val part = parts[oldIndex]
        part.copy(
            parentIndex = remap[part.parentIndex] ?: -1,
            dockedTo = if (part.dockedTo >= 0) remap[part.dockedTo] ?: -1 else -1,
        )
    }
    val staged = stages.map { stage -> Stage(stage.activatedParts.mapNotNull { remap[it] }) }
    return copy(parts = kept, stages = staged)
}
