package com.rm.apogee.core.craft

import com.rm.apogee.core.math.Vec3
import com.rm.apogee.core.part.Decoupler
import com.rm.apogee.core.part.Engine
import com.rm.apogee.core.part.Parachute
import com.rm.apogee.core.part.PartCatalog

/** Radial symmetry modes the builder offers. */
enum class SymmetryMode(val count: Int, val label: String) {
    NONE(1, "1x"),
    MIRROR(2, "2x"),
    TRIPLE(3, "3x"),
    QUAD(4, "4x");

    /**
     * The next mode a design of this [orientation] can use.
     *
     * A craft lying down is symmetric left to right and nothing else - three
     * or four wheels spaced round the fuselage would put some of them on its
     * roof - so a horizontal design cycles between one and a mirrored pair.
     */
    fun next(orientation: CraftOrientation = CraftOrientation.VERTICAL): SymmetryMode {
        val offered = offeredFor(orientation)
        return offered[(offered.indexOf(this) + 1) % offered.size]
    }

    companion object {
        fun offeredFor(orientation: CraftOrientation): List<SymmetryMode> =
            if (orientation == CraftOrientation.HORIZONTAL) listOf(NONE, MIRROR) else entries
    }
}

/**
 * The mutable editing model behind the vehicle assembly building.
 *
 * Every mutation produces a whole new [CraftDesign] and pushes the old one onto
 * an undo stack. Designs are small - a few dozen parts of plain data - so
 * copying one is cheap, and it buys an undo that cannot possibly get out of
 * step with the model. Given that the hardest part of this whole project is
 * three-dimensional assembly on a touchscreen, a reliable undo is not a
 * convenience feature; it is what makes the interaction forgiving enough to
 * use at all.
 */
class CraftBuilder(
    private val catalog: PartCatalog,
    initial: CraftDesign = CraftDesign("Untitled", emptyList()),
) {
    var design: CraftDesign = initial
        private set

    /**
     * The current symmetry mode. A radial mode chosen on a standing craft
     * reads as a mirrored pair once the craft is laid down, whether that
     * happened by toggling, loading or undoing.
     */
    var symmetry: SymmetryMode = SymmetryMode.NONE
        get() = if (field in SymmetryMode.offeredFor(orientation)) field else SymmetryMode.MIRROR

    private val undoStack = ArrayDeque<CraftDesign>()
    private val redoStack = ArrayDeque<CraftDesign>()

    val isEmpty: Boolean get() = design.parts.isEmpty()
    val partCount: Int get() = design.parts.size
    val canUndo: Boolean get() = undoStack.isNotEmpty()
    val canRedo: Boolean get() = redoStack.isNotEmpty()

    /**
     * Which way up the craft is built. Changing it moves no parts; it changes
     * which way is the sky, and so where wheels may go and what symmetry
     * means from here on.
     */
    var orientation: CraftOrientation
        get() = design.orientation
        set(value) {
            if (value == design.orientation) return
            mutate { it.copy(orientation = value) }
        }

    var name: String
        get() = design.name
        set(value) {
            mutate { it.copy(name = value) }
        }

    /** Nodes currently free to attach to. */
    fun openNodes(): List<OpenNode> = Attachment.openNodes(design, catalog)

    /**
     * Places the first part, which becomes the root.
     *
     * The root is whatever is placed first, and the builder nudges players
     * toward a command pod by ordering the drawer that way - a craft rooted at
     * its pod is one where staging discards the spent half and leaves the crew
     * flying, rather than the reverse.
     */
    fun placeRoot(partId: String): Boolean {
        if (!isEmpty) return false
        catalog[partId] ?: return false
        mutate {
            it.copy(
                parts = listOf(PlacedPart(partId, Vec3.zero())),
                catalogHash = catalog.contentHash,
            )
        }
        return true
    }

    /**
     * Attaches [partId] to [target], applying the current symmetry mode.
     *
     * @return the indices of the parts added, empty if the join was illegal.
     */
    fun attach(partId: String, target: OpenNode): List<Int> {
        val def = catalog[partId] ?: return emptyList()
        if (!Attachment.accepts(def, target, design.orientation)) return emptyList()
        val mountNode = Attachment.mountNodeFor(def, target) ?: return emptyList()
        val placement = Attachment.solve(def, mountNode, target)

        // Symmetry only makes sense radially, around the stack. Applying it to
        // a stack join would pile several parts in the same place, so it is
        // gated on either side of the join being a surface mount - a fin on a
        // tank qualifies whichever side declares it.
        val surfaceJoin = target.kind == com.rm.apogee.core.part.AttachNodeKind.SURFACE ||
            mountNode.kind == com.rm.apogee.core.part.AttachNodeKind.SURFACE
        val useSymmetry = surfaceJoin && symmetry.count > 1
        // Which node each copy hangs from. Radial copies have only ever
        // recorded the original's; a mirrored copy records its own, so the
        // node it covers stops being offered as open.
        val targets = ArrayList<OpenNode>(symmetry.count)
        val placements = when {
            !useSymmetry -> listOf(placement)
            design.orientation == CraftOrientation.HORIZONTAL -> {
                val mirrored = Attachment.mirror(placement)
                // On the centreline the reflection is the part itself.
                if (mirrored.position.distanceTo(placement.position) < CENTRELINE) {
                    listOf(placement)
                } else {
                    val mirrorTarget = openNodes().firstOrNull {
                        it.partIndex == target.partIndex && it.position.distanceTo(
                            Vec3(-target.position.x, target.position.y, target.position.z)
                        ) < CENTRELINE
                    }
                    targets.add(target)
                    targets.add(mirrorTarget ?: target)
                    listOf(placement, mirrored)
                }
            }
            else -> Attachment.radialSymmetry(placement, symmetry.count)
        }

        val group = if (placements.size > 1) nextSymmetryGroup() else -1
        val added = ArrayList<Int>(placements.size)

        mutate { current ->
            val parts = current.parts.toMutableList()
            placements.forEachIndexed { i, p ->
                added.add(parts.size)
                parts.add(
                    PlacedPart(
                        partId = partId,
                        position = p.position,
                        rotation = p.rotation,
                        parentIndex = target.partIndex,
                        parentNodeId = (targets.getOrNull(i) ?: target).node.id,
                        ownNodeId = mountNode.id,
                        symmetryGroup = group,
                    )
                )
            }
            current.copy(parts = parts, catalogHash = catalog.contentHash)
        }
        return added
    }

    /**
     * Removes a part and everything hanging below it.
     *
     * Symmetry partners go with it: parts placed together are removed together,
     * because leaving three of four boosters behind is never what was meant.
     */
    fun remove(index: Int): Boolean {
        if (index !in design.parts.indices) return false
        // Removing the root would orphan the entire craft.
        if (design.parts[index].parentIndex == -1) return false

        val group = design.parts[index].symmetryGroup
        val roots = if (group >= 0) {
            design.parts.indices.filter { design.parts[it].symmetryGroup == group }
        } else {
            listOf(index)
        }

        val doomed = roots.flatMap { design.subtreeOf(it) }.toSet()
        val kept = design.parts.indices.filter { it !in doomed }
        if (kept.isEmpty()) return false

        mutate { rebuild(it, kept) }
        return true
    }

    fun undo(): Boolean {
        val previous = undoStack.removeLastOrNull() ?: return false
        redoStack.addLast(design)
        design = previous
        return true
    }

    fun redo(): Boolean {
        val next = redoStack.removeLastOrNull() ?: return false
        undoStack.addLast(design)
        design = next
        return true
    }

    fun clear() {
        // Keeps the orientation: clearing a plane to start again is starting
        // another plane.
        mutate {
            CraftDesign("Untitled", emptyList(), emptyList(), catalog.contentHash, it.orientation)
        }
    }

    fun load(loaded: CraftDesign) {
        mutate { loaded }
    }

    /** Recomputes staging from the structure. See [autoStage]. */
    fun restage() {
        mutate { it.copy(stages = autoStage(it, catalog)) }
    }

    private fun mutate(transform: (CraftDesign) -> CraftDesign) {
        undoStack.addLast(design)
        if (undoStack.size > MAX_UNDO) undoStack.removeFirst()
        redoStack.clear()
        val updated = transform(design)
        // Structure edits invalidate the staging sequence, so it is rebuilt on
        // every change rather than left for the player to notice is wrong.
        design = updated.copy(stages = autoStage(updated, catalog))
    }

    private fun nextSymmetryGroup(): Int =
        (design.parts.maxOfOrNull { it.symmetryGroup } ?: -1) + 1

    /** Rebuilds a design from a subset of parts, remapping parent indices. */
    private fun rebuild(source: CraftDesign, keep: List<Int>): CraftDesign {
        val remap = HashMap<Int, Int>(keep.size)
        keep.forEachIndexed { newIndex, oldIndex -> remap[oldIndex] = newIndex }
        val parts = keep.map { oldIndex ->
            val part = source.parts[oldIndex]
            part.copy(parentIndex = remap[part.parentIndex] ?: -1)
        }
        return source.copy(parts = parts)
    }

    companion object {
        private const val MAX_UNDO = 64

        /** Metres within which a reflected part counts as landing on itself. */
        private const val CENTRELINE = 0.05

        /**
         * Derives a staging sequence from the part tree.
         *
         * Parts are grouped by what a decoupler separates, exactly as fuel
         * crossfeed is, and the groups fire from the outside in: the group
         * furthest from the root burns first, then its decoupler releases it
         * and the next group lights. Parachutes go last.
         *
         * Deriving this rather than asking the player to build it means a craft
         * is flyable the moment it is assembled. Manual reordering is a later
         * refinement, not a prerequisite.
         */
        fun autoStage(design: CraftDesign, catalog: PartCatalog): List<Stage> {
            if (design.parts.isEmpty()) return emptyList()

            val depth = IntArray(design.parts.size) { -1 }
            fun depthOf(index: Int): Int {
                if (depth[index] >= 0) return depth[index]
                val parent = design.parts[index].parentIndex
                depth[index] = if (parent < 0) 0 else depthOf(parent) + 1
                return depth[index]
            }

            fun isDecoupler(index: Int) =
                catalog[design.parts[index].partId]?.module<Decoupler>() != null

            fun isEngine(index: Int) =
                catalog[design.parts[index].partId]?.module<Engine>() != null

            fun isParachute(index: Int) =
                catalog[design.parts[index].partId]?.module<Parachute>() != null

            // Each part's separation group: how many decouplers lie between it
            // and the root. Higher means it is discarded sooner.
            val separation = IntArray(design.parts.size) { -1 }
            fun separationOf(index: Int): Int {
                if (separation[index] >= 0) return separation[index]
                val parent = design.parts[index].parentIndex
                val inherited = if (parent < 0) 0 else separationOf(parent)
                separation[index] = if (isDecoupler(index)) inherited + 1 else inherited
                return separation[index]
            }
            design.parts.indices.forEach { separationOf(it); depthOf(it) }

            val deepest = separation.maxOrNull() ?: 0
            val stages = ArrayList<Stage>()

            for (level in deepest downTo 0) {
                val engines = design.parts.indices.filter {
                    separation[it] == level && isEngine(it)
                }
                // The decoupler that releases the level below this one fires
                // together with this level's engines, so there is no coasting
                // gap between separation and ignition.
                val decouplers = design.parts.indices.filter {
                    separation[it] == level + 1 && isDecoupler(it)
                }
                val activated = decouplers + engines
                if (activated.isNotEmpty()) stages.add(Stage(activated))
            }

            val parachutes = design.parts.indices.filter { isParachute(it) }
            if (parachutes.isNotEmpty()) stages.add(Stage(parachutes))

            return stages
        }
    }
}
