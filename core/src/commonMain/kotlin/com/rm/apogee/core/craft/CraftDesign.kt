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
 * One part placed in a design. It keeps both a transform, which physics and rendering use and which
 * wins, and a parent link, which staging uses to know what comes away when a decoupler fires.
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
    /** Shared by parts placed together in symmetry, so the builder moves or deletes them as one. */
    val symmetryGroup: Int = -1,
    /** Quarter turns around the join, 0..3, on top of how [Attachment.solve] settles the part. */
    val turn: Int = 0,
    /** Its action group, 1 to 3, or 0 for none. One button in flight toggles a group. */
    val group: Int = 0,
    /** The docking part this one is latched to in this craft, or -1. Set on both. */
    val dockedTo: Int = -1,
    /** On the docking part a craft docked with: what that craft was, restored when it undocks. */
    val dockedFrom: DockedOrigin? = null,
    /**
     * The engine shell this was attached under, recorded as its stage drops so everyone sees it
     * split and fall away. See [Shrouds].
     */
    val shroud: Shroud? = null,
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

/** One step of staging: indices of the parts activated when it fires. Stage 0 fires first. */
@Serializable
data class Stage(
    val activatedParts: List<Int> = emptyList(),
)

/**
 * Which way up a design stands on the ground. The nose is always +Y in design space, and everything
 * reads +Y as where it's going. This only picks which axis is the sky at rest and which it rolls
 * along.
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

/** A saved vehicle's blueprint. It's both the save format and what the network sends to spawn one. */
@Serializable
data class CraftDesign(
    val name: String,
    val parts: List<PlacedPart>,
    val stages: List<Stage> = emptyList(),
    /** The catalogue this was made against. */
    val catalogHash: String = "",
    /** Vertical by default, which old saves were. */
    val orientation: CraftOrientation = CraftOrientation.VERTICAL,
    /**
     * Whether [stages] were arranged by hand. If not, the builder rebuilds staging from the part tree
     * on every edit. If so, it keeps your order and only adds new parts and drops removed ones.
     */
    val manualStaging: Boolean = false,
) {
    val partCount: Int get() = parts.size

    /**
     * The same parts in order, with the tree turned so part [index] is the root. Joints up to the
     * old root reverse and swap nodes. Nothing moves.
     */
    fun rerootedAt(index: Int): CraftDesign {
        if (index !in parts.indices || parts[index].parentIndex < 0) return this
        val out = parts.toMutableList()
        var child = index
        var parent = parts[index].parentIndex
        out[index] = out[index].copy(parentIndex = -1, parentNodeId = null, ownNodeId = null)
        while (parent >= 0) {
            val next = parts[parent].parentIndex
            // The old parent now hangs from the child, so the nodes swap.
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

    /** Indices of every part hanging below [index], including itself. */
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

    /** The design as it flies, without empty stages, which the builder keeps for you to fill. */
    fun withoutEmptyStages(): CraftDesign =
        if (stages.none { it.activatedParts.isEmpty() }) this
        else copy(stages = stages.filter { it.activatedParts.isNotEmpty() })

    /** Checks the design against a catalogue and lists every problem, without throwing. */
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

        // A cycle would make subtreeOf loop forever when decoupling.
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
