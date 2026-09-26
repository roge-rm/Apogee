package com.rm.apogee.core.craft

import com.rm.apogee.core.math.Vec3
import com.rm.apogee.core.part.Decoupler
import com.rm.apogee.core.part.Engine
import com.rm.apogee.core.part.LandingLeg
import com.rm.apogee.core.part.Parachute
import com.rm.apogee.core.part.PartCatalog
import com.rm.apogee.core.part.PartDef

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
    fun attach(partId: String, target: OpenNode): List<Int> = attachAssembly(Assembly.of(partId), target)

    /**
     * Puts [assembly] on [target], copied round by the current symmetry
     * mode as a single part would be.
     *
     * @return the indices of the parts added, empty if it will not go there.
     */
    fun attachAssembly(assembly: Assembly, target: OpenNode): List<Int> {
        val done = Assemblies.attach(design, assembly, target, symmetry, catalog) ?: return emptyList()
        mutate { Attachment.settled(done.design, catalog) }
        return done.added
    }

    /**
     * What lifting part [index] would leave and hold, without lifting it:
     * the builder shows the craft without the piece while a finger carries
     * it, and only [move] changes the design. Null for the root.
     */
    fun lift(index: Int): Assemblies.Lift? = Assemblies.lift(design, index)

    /**
     * Moves part [index] - with its partners and everything below - on to
     * [target], a node of [lift]'s [Assemblies.Lift.rest]. One undo step.
     */
    fun move(index: Int, target: OpenNode, symmetry: SymmetryMode = this.symmetry): List<Int> {
        val lifted = lift(index) ?: return emptyList()
        val done = Assemblies.attach(lifted.rest, lifted.assembly, target, symmetry, catalog) ?: return emptyList()
        mutate { Attachment.settled(done.design, catalog) }
        return done.added
    }

    /** A copy of part [index] and everything below it, to put somewhere else. */
    fun duplicate(index: Int): Assembly? = Assemblies.extract(design, index)

    /** Turns part [index] - and its partners - a quarter [quarters] times about its join. */
    fun turn(index: Int, quarters: Int = 1): Boolean {
        val turned = Assemblies.turn(design, index, quarters, catalog) ?: return false
        mutate { Attachment.settled(turned, catalog) }
        return true
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

    // --- staging -------------------------------------------------------------

    /** Whether a stage can fire [index]: an engine, decoupler, parachute or leg. */
    fun isStageable(index: Int): Boolean =
        index in design.parts.indices && catalog[design.parts[index].partId]?.let { stageable(it) } == true

    /** The stage [index] fires in, or -1 if none. */
    fun stageOf(index: Int): Int = design.stages.indexOfFirst { index in it.activatedParts }

    /**
     * Moves [index] into [stage], with its symmetry partners: four boosters
     * placed together are staged together, as they were removed together.
     */
    fun moveToStage(index: Int, stage: Int): Boolean {
        if (!isStageable(index) || stage !in design.stages.indices) return false
        val group = design.parts[index].symmetryGroup
        val moving = if (group < 0) setOf(index) else {
            design.parts.indices.filter { design.parts[it].symmetryGroup == group && isStageable(it) }.toSet()
        }
        if (design.stages[stage].activatedParts.containsAll(moving)) return false
        editStages { stages ->
            stages.mapIndexed { i, s ->
                val rest = s.activatedParts.filter { it !in moving }
                Stage(if (i == stage) rest + moving.sorted() else rest)
            }
        }
        return true
    }

    /** Inserts an empty stage at [at], so it fires in that place in the order. */
    fun addStage(at: Int): Boolean {
        if (at !in 0..design.stages.size) return false
        editStages { it.toMutableList().apply { add(at, Stage()) } }
        return true
    }

    /**
     * Removes [stage]. Whatever it fired joins the stage that fires next -
     * or, for the last, the one before - rather than dropping out of the
     * sequence: a part in no stage would never fire at all.
     */
    fun removeStage(stage: Int): Boolean {
        val stages = design.stages
        if (stage !in stages.indices) return false
        val orphans = stages[stage].activatedParts
        val heir = if (stage + 1 < stages.size) stage + 1 else stage - 1
        if (orphans.isNotEmpty() && heir < 0) return false
        editStages { current ->
            current.mapIndexed { i, s -> if (i == heir) Stage(s.activatedParts + orphans) else s }
                .filterIndexed { i, _ -> i != stage }
        }
        return true
    }

    /** Moves [from] to fire at position [to] in the order. */
    fun moveStage(from: Int, to: Int): Boolean {
        val stages = design.stages
        if (from !in stages.indices || to !in stages.indices || from == to) return false
        editStages { it.toMutableList().apply { add(to, removeAt(from)) } }
        return true
    }

    /** Hands staging back to the builder, discarding the hand arrangement. */
    fun useAutomaticStaging() {
        if (!design.manualStaging) return
        mutate { it.copy(manualStaging = false) }
    }

    private fun editStages(transform: (List<Stage>) -> List<Stage>) {
        mutate { it.copy(stages = transform(it.stages), manualStaging = true) }
    }

    private fun mutate(transform: (CraftDesign) -> CraftDesign) {
        undoStack.addLast(design)
        if (undoStack.size > MAX_UNDO) undoStack.removeFirst()
        redoStack.clear()
        val updated = transform(design)
        // Structure edits invalidate the staging sequence, so it is rebuilt on
        // every change rather than left for the player to notice is wrong -
        // unless the player arranged it, when it is kept and only fitted to
        // what was added or taken away.
        design = updated.copy(
            stages = if (updated.manualStaging) fitStages(updated, catalog) else autoStage(updated, catalog),
        )
    }


    /** Rebuilds a design from a subset of parts, remapping parent indices. */
    private fun rebuild(source: CraftDesign, keep: List<Int>): CraftDesign = source.keeping(keep)

    companion object {
        private const val MAX_UNDO = 64


        /** Parts a stage can fire. */
        fun stageable(def: PartDef): Boolean =
            def.module<Engine>() != null || def.module<Decoupler>() != null ||
                def.module<Parachute>() != null || def.module<LandingLeg>() != null ||
                def.module<com.rm.apogee.core.part.Fairing>() != null

        /**
         * A hand-arranged sequence, fitted to the design as it now stands:
         * parts no longer there, or that nothing fires, dropped; stageable
         * parts in no stage placed where the automatic sequence would put
         * them - beside a part that fires with them automatically if it has
         * a stage, or else ahead of the first stage holding anything the
         * automatic sequence fires later. Empty stages are kept: the player
         * may have just made one to fill.
         */
        fun fitStages(design: CraftDesign, catalog: PartCatalog): List<Stage> {
            fun canStage(i: Int) = i in design.parts.indices &&
                catalog[design.parts[i].partId]?.let { stageable(it) } == true
            val seen = HashSet<Int>()
            val stages = design.stages.map { s ->
                s.activatedParts.filter { canStage(it) && seen.add(it) }.toMutableList()
            }.toMutableList()

            val auto = autoStage(design, catalog)
            for ((k, fired) in auto.withIndex()) {
                val newcomers = fired.activatedParts.filter { it !in seen }
                if (newcomers.isEmpty()) continue
                val home = stages.indexOfFirst { s -> s.any { it in fired.activatedParts } }
                if (home >= 0) {
                    stages[home].addAll(newcomers)
                } else {
                    val later = auto.drop(k + 1).flatMap { it.activatedParts }.toSet()
                    val at = stages.indexOfFirst { s -> s.any { it in later } }
                    stages.add(if (at < 0) stages.size else at, newcomers.toMutableList())
                }
                seen.addAll(newcomers)
            }
            return stages.map { Stage(it) }
        }

        /**
         * Derives a staging sequence from the part tree.
         *
         * Parts are grouped by what a decoupler separates, exactly as fuel
         * crossfeed is, and the groups fire from the outside in: the group
         * furthest from the root burns first, then its decoupler releases it
         * and the next group lights. Parachutes go last, then legs.
         *
         * Deriving this rather than asking the player to build it means a craft
         * is flyable the moment it is assembled. The player can rearrange it
         * afterwards; see [moveToStage] and [fitStages].
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

            fun isLeg(index: Int) =
                catalog[design.parts[index].partId]?.module<LandingLeg>() != null

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

            // Fairings thrown open just before anything they hold fires - out
            // of the air by then - or, holding nothing that fires, once the
            // engines are done.
            val fairings = design.parts.indices.filter { catalog[design.parts[it].partId]?.module<com.rm.apogee.core.part.Fairing>() != null }
            if (fairings.isNotEmpty()) {
                val defs = design.parts.map { catalog[it.partId] }
                if (defs.all { it != null }) {
                    val inside = Fairings.enclosed(design, defs.map { it!! }) { false }
                    val first = stages.indexOfFirst { stage -> stage.activatedParts.any { inside[it] } }
                    stages.add(if (first >= 0) first else stages.size, Stage(fairings))
                }
            }

            val parachutes = design.parts.indices.filter { isParachute(it) }
            if (parachutes.isNotEmpty()) stages.add(Stage(parachutes))

            // Legs last of all: out for the landing, after the chute has
            // slowed the craft, and not dragging through the ascent.
            val legs = design.parts.indices.filter { isLeg(it) }
            if (legs.isNotEmpty()) stages.add(Stage(legs))

            return stages
        }
    }
}
