package com.rm.apogee.core.craft

import com.rm.apogee.core.math.Quat
import com.rm.apogee.core.math.SerialQuat
import com.rm.apogee.core.math.SerialVec3
import com.rm.apogee.core.math.Vec3
import com.rm.apogee.core.part.PartCatalog
import com.rm.apogee.core.part.PartDef
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * One part placed in a design.
 *
 * It carries both a local transform and a parent link, which doubles up on purpose. The transform
 * is what physics and rendering want, and it's the one that counts. The parent link is what
 * *staging* wants, because decoupling needs to know which part of the tree comes away. Working one
 * out from the other whenever it's needed would be slower and more fragile than storing both and
 * checking they agree when it loads.
 */
@Serializable
data class PlacedPart(
    val partId: String,
    /** Position in craft-local space, in metres. */
    val position: SerialVec3,
    val rotation: SerialQuat = Quat.identity(),
    /** Index into [CraftDesign.parts], or -1 for the root. */
    val parentIndex: Int = -1,
    /** Which of the parent's attach nodes this hangs from. */
    val parentNodeId: String? = null,
    /** Which of this part's own nodes makes the join. */
    val ownNodeId: String? = null,
    /**
     * Parts placed together by a symmetry mode share a group id, so the builder can move or delete
     * them as one.
     */
    val symmetryGroup: Int = -1,
    /**
     * Quarter turns around the join, on top of how [Attachment.solve] settles the part. For example
     * a cockpit turned to face sideways, or a wheel turned around its strut. 0..3.
     */
    val turn: Int = 0,
    /**
     * Which action group it's in, 1 to 3, or 0 for none. A group is switched on and off together
     * from one button in flight.
     */
    val group: Int = 0,
    /**
     * If this is a docking part latched to another in this craft, that part's index, or -1 for
     * none. It's set on both.
     */
    val dockedTo: Int = -1,
    /**
     * On the docking part a craft docked with: what that craft was, so it can get back its name,
     * owner and staging when it undocks.
     */
    val dockedFrom: DockedOrigin? = null,
)

/** What a craft was before it docked onto another one, given back when it undocks. */
@Serializable
data class DockedOrigin(
    val name: String,
    val owner: String = "",
    val ownerName: String = "",
    val orientation: CraftOrientation = CraftOrientation.VERTICAL,
    val currentStage: Int = 0,
    val throttle: Double = 0.0,
)

/**
 * One step of the staging sequence.
 *
 * Holds the indices of the parts *activated* when the stage fires: engines ignite, decouplers let
 * go, parachutes open. Stage 0 fires first.
 */
@Serializable
data class Stage(
    val activatedParts: List<Int> = emptyList(),
)

/**
 * Which way up a design is built, and so which way up it stands on the ground.
 *
 * The nose is always +Y in design space, whatever this says. Engines, fins, the attitude controller
 * and the navball all read +Y as "where it's going", and a plane is just a stack flown on its side,
 * not a different kind of object. What this decides is the other axis: which way is the *sky* when
 * the craft is sitting on the ground, and which way it rolls along it.
 *
 * Without it the builder could only make things that stand on their tails, a rover's forward had to
 * be a hard-coded +Z that happened to work, and you could only test a plane already in the air
 * because there was no way to put one on a runway.
 */
@Serializable
enum class CraftOrientation(
    /** The design axis that points at the sky when the craft sits on the ground. */
    val up: Vec3,
    /** The design axis it rolls along when driven. At right angles to [up]. */
    val forward: Vec3,
    val label: String,
) {
    /** Stands on its tail. Rockets and landers, and rovers built that way. */
    @SerialName("vertical") VERTICAL(Vec3(0.0, 1.0, 0.0), Vec3(0.0, 0.0, 1.0), "Vertical"),

    /** Lies along the ground, nose forward, +Z to the sky. Planes, boats, cars. */
    @SerialName("horizontal") HORIZONTAL(Vec3(0.0, 0.0, 1.0), Vec3(0.0, 1.0, 0.0), "Horizontal");

    fun other(): CraftOrientation = if (this == VERTICAL) HORIZONTAL else VERTICAL
}

/**
 * A saved vehicle: the blueprint, not a thing in the world.
 *
 * This same type is both the save file format *and* what gets sent over the network to spawn a
 * craft. Keeping them the same means a craft that loads fine can't fail to send, and there's no
 * second format to keep in step.
 */
@Serializable
data class CraftDesign(
    val name: String,
    val parts: List<PlacedPart>,
    val stages: List<Stage> = emptyList(),
    /**
     * The catalogue this was made against. A design that refers to parts the loader doesn't have
     * gets refused with a useful message, instead of quietly losing pieces.
     */
    val catalogHash: String = "",
    /** Defaults to vertical, because that's what every design saved before this existed was. */
    val orientation: CraftOrientation = CraftOrientation.VERTICAL,
    /**
     * Whether [stages] were arranged by hand.
     *
     * When false, the builder works out staging from the part tree on every edit, as it always has.
     * When true, it keeps your arrangement and only fits new parts into it and drops removed ones,
     * because rebuilding it would throw away the order you picked.
     */
    val manualStaging: Boolean = false,
) {
    val partCount: Int get() = parts.size

    /** Indices of every part hanging below [index], including itself. */
    /**
     * The same parts in the same order, with the tree turned around so part [index] is the root.
     * Every joint on the way from it up to the old root is reversed, and the nodes at each end swap
     * sides. Nothing moves.
     */
    fun rerootedAt(index: Int): CraftDesign {
        if (index !in parts.indices || parts[index].parentIndex < 0) return this
        val out = parts.toMutableList()
        var child = index
        var parent = parts[index].parentIndex
        out[index] = out[index].copy(parentIndex = -1, parentNodeId = null, ownNodeId = null)
        while (parent >= 0) {
            val next = parts[parent].parentIndex
            // Child-of-parent becomes parent-of-child, so the node the child hung from is now the
            // one the old parent hangs from.
            out[parent] = out[parent].copy(
                parentIndex = child,
                parentNodeId = parts[child].ownNodeId,
                ownNodeId = parts[child].parentNodeId,
            )
            child = parent
            parent = next
        }
        return copy(parts = out)
    }

    fun subtreeOf(index: Int): List<Int> {
        val result = ArrayList<Int>()
        val queue = ArrayDeque<Int>()
        queue.add(index)
        while (queue.isNotEmpty()) {
            val current = queue.removeFirst()
            result.add(current)
            parts.forEachIndexed { i, part -> if (part.parentIndex == current) queue.add(i) }
        }
        return result
    }

    fun rootIndex(): Int = parts.indexOfFirst { it.parentIndex == -1 }

    /**
     * The design as it flies, with stages that fire nothing dropped. The builder keeps an empty
     * stage you've just made so you can fill it, but in flight it would be a button press that did
     * nothing.
     */
    fun withoutEmptyStages(): CraftDesign =
        if (stages.none { it.activatedParts.isEmpty() }) this
        else copy(stages = stages.filter { it.activatedParts.isNotEmpty() })

    /**
     * Checks the design against a catalogue.
     *
     * Returns the problems instead of throwing, because the builder wants to show all of them at
     * once and the network wants to reject with a reason.
     */
    fun validate(catalog: PartCatalog): List<String> {
        val problems = ArrayList<String>()
        if (parts.isEmpty()) {
            problems.add("Design '$name' has no parts")
            return problems
        }

        val roots = parts.count { it.parentIndex == -1 }
        if (roots != 1) problems.add("Design '$name' must have exactly one root part, found $roots")

        parts.forEachIndexed { index, placed ->
            if (catalog[placed.partId] == null) {
                problems.add("Part #$index refers to unknown part '${placed.partId}'")
            }
            if (placed.parentIndex !in -1 until parts.size) {
                problems.add("Part #$index has out-of-range parentIndex ${placed.parentIndex}")
            }
            if (placed.parentIndex == index) {
                problems.add("Part #$index is its own parent")
            }
        }

        // A cycle would make subtreeOf loop forever when decoupling, which is the worst possible
        // moment to find out.
        if (problems.isEmpty() && hasCycle()) {
            problems.add("Design '$name' has a cycle in its part tree")
        }

        stages.forEachIndexed { stageIndex, stage ->
            stage.activatedParts.forEach { partIndex ->
                if (partIndex !in parts.indices) {
                    problems.add("Stage $stageIndex activates out-of-range part #$partIndex")
                }
            }
        }
        return problems
    }

    private fun hasCycle(): Boolean {
        for (start in parts.indices) {
            var current = start
            var hops = 0
            while (current != -1) {
                current = parts[current].parentIndex
                if (++hops > parts.size) return true
            }
        }
        return false
    }

    /** Looks up each placed part's definition, in order. */
    fun resolve(catalog: PartCatalog): List<Pair<PlacedPart, PartDef>> =
        parts.map { it to catalog.require(it.partId) }

    companion object {
        /** A design with a single part, used by tests and by the "new craft" path. */
        fun single(name: String, partId: String): CraftDesign =
            CraftDesign(name, listOf(PlacedPart(partId, Vec3.zero())))
    }
}
