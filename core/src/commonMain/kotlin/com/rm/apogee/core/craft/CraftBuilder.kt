package com.rm.apogee.core.craft

import com.rm.apogee.core.math.Vec3
import com.rm.apogee.core.part.Decoupler
import com.rm.apogee.core.part.Engine
import com.rm.apogee.core.part.LandingLeg
import com.rm.apogee.core.part.Parachute
import com.rm.apogee.core.part.PartCatalog
import com.rm.apogee.core.part.PartDef

/** The radial symmetry modes the builder offers. */
enum class SymmetryMode(val count: Int, val label: String) {
    NONE(1, "1x"),
    MIRROR(2, "2x"),
    TRIPLE(3, "3x"),
    QUAD(4, "4x");

    /**
     * The next mode for [orientation]. A horizontal craft only switches between one and a mirrored
     * pair, since radial copies would put wheels on its roof.
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
 * The editing model behind the assembly building. Every change makes a new [CraftDesign] and pushes
 * the old one on the undo stack. Designs are small, so copying is cheap and undo can't get out of
 * step. Building in 3D on a touchscreen needs a reliable undo.
 */
class CraftBuilder(
    private val catalog: PartCatalog,
    initial: CraftDesign = CraftDesign("Untitled", emptyList()),
) {
    var design: CraftDesign = initial
        private set

    /**
     * The symmetry mode. A radial mode reads as a mirrored pair once the craft is horizontal,
     * however that happened.
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
     * Which way up the craft is built. Moves no parts, but changes where the sky is, so where
     * wheels go and what symmetry means.
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

    /** Nodes that are free to attach to right now. */
    fun openNodes(): List<OpenNode> = Attachment.openNodes(design, catalog)

    /**
     * Places the first part as the root. The drawer lists command pods first, since a craft rooted
     * at its pod drops the spent half when staging and keeps the crew flying.
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
     * Attaches [partId] to [target] with the current symmetry. Returns the indices added, or empty
     * if not allowed.
     */
    fun attach(partId: String, target: OpenNode): List<Int> = attachAssembly(Assembly.of(partId), target)

    /**
     * Puts [assembly] on [target], copied by the current symmetry like a single part. Returns the
     * indices added, or empty if it won't go.
     */
    fun attachAssembly(assembly: Assembly, target: OpenNode): List<Int> {
        val done = Assemblies.attach(design, assembly, target, symmetry, catalog) ?: return emptyList()
        mutate { Attachment.settled(done.design, catalog) }
        return done.added
    }

    /**
     * What lifting part [index] would leave and hold, without lifting it. The builder shows the
     * craft without it while you drag; only [move] changes the design. Null for the root.
     */
    fun lift(index: Int): Assemblies.Lift? = Assemblies.lift(design, index)

    /**
     * Moves part [index], with partners and everything below, onto [target], a node of [lift]'s
     * [Assemblies.Lift.rest]. One undo step.
     */
    fun move(index: Int, target: OpenNode, symmetry: SymmetryMode = this.symmetry): List<Int> {
        val lifted = lift(index) ?: return emptyList()
        val done = Assemblies.attach(lifted.rest, lifted.assembly, target, symmetry, catalog) ?: return emptyList()
        mutate { Attachment.settled(done.design, catalog) }
        return done.added
    }

    /** A copy of part [index] and everything below it, to put somewhere else. */
    fun duplicate(index: Int): Assembly? = Assemblies.extract(design, index)

    /** Turns part [index] and its partners [quarters] quarter turns about its join. */
    fun turn(index: Int, quarters: Int = 1): Boolean {
        val turned = Assemblies.turn(design, index, quarters, catalog) ?: return false
        mutate { Attachment.settled(turned, catalog) }
        return true
    }

    /**
     * Whether part [index] can go in an action group: an engine, driven wheel, rotor, lamp, drill,
     * converter, fold-out, flapped wing or sail.
     */
    fun groupable(index: Int): Boolean {
        val def = design.parts.getOrNull(index)?.let { catalog[it.partId] } ?: return false
        return def.module<com.rm.apogee.core.part.Engine>() != null ||
            (def.module<com.rm.apogee.core.part.Wheel>()?.motorForce ?: 0.0) > 0.0 ||
            def.module<com.rm.apogee.core.part.Rotor>() != null ||
            def.module<com.rm.apogee.core.part.Lamp>() != null ||
            def.module<com.rm.apogee.core.part.Drill>() != null ||
            def.module<com.rm.apogee.core.part.Converter>() != null ||
            def.module<com.rm.apogee.core.part.SolarPanel>()?.deployable == true ||
            def.module<com.rm.apogee.core.part.Antenna>()?.deployable == true ||
            (def.module<com.rm.apogee.core.part.AeroSurface>()?.flapLift ?: 0.0) > 0.0 ||
            def.module<com.rm.apogee.core.part.Sail>() != null
    }

    /**
     * Puts part [index] and its symmetry partners in action group [group] (1 to 3), or none with 0.
     * False if a group can't switch it.
     */
    fun setGroup(index: Int, group: Int): Boolean {
        if (!groupable(index) || group !in 0..Vessel.GROUPS) return false
        val partners = design.parts[index].symmetryGroup
        mutate { d ->
            d.copy(parts = d.parts.mapIndexed { i, p ->
                if (i == index || (partners >= 0 && p.symmetryGroup == partners)) p.copy(group = group) else p
            })
        }
        return true
    }

    /** Removes a part and everything below it, with its symmetry partners. */
    fun remove(index: Int): Boolean {
        if (index !in design.parts.indices) return false
        // The root can't be removed.
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
        // Keeps the orientation: clearing a plane starts another plane.
        mutate {
            CraftDesign("Untitled", emptyList(), emptyList(), catalog.contentHash, it.orientation)
        }
    }

    fun load(loaded: CraftDesign) {
        mutate { loaded }
    }

    /** Rebuilds staging from the structure. See [autoStage]. */
    fun restage() {
        mutate { it.copy(stages = autoStage(it, catalog)) }
    }

    // --- staging -------------------------------------------------------------

    /** Whether a stage can fire [index]: an engine, decoupler, parachute or leg. */
    fun isStageable(index: Int): Boolean =
        index in design.parts.indices && catalog[design.parts[index].partId]?.let { stageable(it) } == true

    /** The stage [index] fires in, or -1 if none. */
    fun stageOf(index: Int): Int = design.stages.indexOfFirst { index in it.activatedParts }

    /** Moves [index] and its symmetry partners into [stage]. */
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

    /** Inserts an empty stage at [at]. */
    fun addStage(at: Int): Boolean {
        if (at !in 0..design.stages.size) return false
        editStages { it.toMutableList().apply { add(at, Stage()) } }
        return true
    }

    /**
     * Removes [stage]. Its parts join the next stage (or the one before, for the last), since a
     * part in no stage never fires.
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

    /** Moves [from] so it fires at position [to] in the order. */
    fun moveStage(from: Int, to: Int): Boolean {
        val stages = design.stages
        if (from !in stages.indices || to !in stages.indices || from == to) return false
        editStages { it.toMutableList().apply { add(to, removeAt(from)) } }
        return true
    }

    /** Goes back to automatic staging, dropping the hand-made sequence. */
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
        // Structure changes break staging, so it's rebuilt every time, unless you arranged it
        // yourself; then it's kept and only adjusted for parts added or removed.
        design = updated.copy(
            stages = if (updated.manualStaging) fitStages(updated, catalog) else autoStage(updated, catalog),
        )
    }


    /** Rebuilds a design from some of its parts, remapping parents. */
    private fun rebuild(source: CraftDesign, keep: List<Int>): CraftDesign = source.keeping(keep)

    companion object {
        private const val MAX_UNDO = 64


        /** Parts a stage can fire. */
        fun stageable(def: PartDef): Boolean =
            def.module<Engine>() != null || def.module<Decoupler>() != null ||
                def.module<Parachute>() != null || def.module<com.rm.apogee.core.part.Fairing>() != null

        /**
         * A hand-arranged sequence fitted to the design as it is now. Missing or unstageable parts
         * are dropped. Unstaged stageable parts go next to a part they'd auto-fire with, if that
         * has a stage, else ahead of the first stage holding something auto-fired later. Empty
         * stages are kept, since you may have just made one.
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
         * A staging sequence from the part tree, so a craft can fly as soon as it's built. You can
         * rearrange it after; see [moveToStage] and [fitStages].
         *
         * Parts are grouped by what decouplers separate, like crossfeed, and fire from the outside
         * in: the furthest group burns, its decoupler lets go, the next lights. Then parachutes,
         * then legs.
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

            // Each part's separation group: the decouplers between it and the root. Higher is
            // dropped sooner.
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
                // The decoupler dropping the level below fires with this level's engines, so
                // there's no coast between.
                val decouplers = design.parts.indices.filter {
                    separation[it] == level + 1 && isDecoupler(it)
                }
                val activated = decouplers + engines
                if (activated.isNotEmpty()) stages.add(Stage(activated))
            }

            // Fairings open just before anything inside fires, being out of the air by then;
            // holding nothing that fires, after the engines.
            val fairings = design.parts.indices.filter { catalog[design.parts[it].partId]?.module<com.rm.apogee.core.part.Fairing>() != null }
            if (fairings.isNotEmpty()) {
                val defs = design.parts.map { catalog[it.partId] }
                if (defs.all { it != null }) {
                    val inside = Fairings.enclosed(design, defs.map { it!! }) { false }
                    val first = stages.indexOfFirst { stage -> stage.activatedParts.any { inside[it] } }
                    stages.add(if (first >= 0) first else stages.size, Stage(fairings))
                }
            }

            // Legs aren't staged: they go up and down with the gear.
            val parachutes = design.parts.indices.filter { isParachute(it) }
            if (parachutes.isNotEmpty()) stages.add(Stage(parachutes))

            return stages
        }
    }
}
