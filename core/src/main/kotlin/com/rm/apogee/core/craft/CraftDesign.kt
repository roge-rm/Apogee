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
 * Carries both an explicit local transform and a parent link, which is
 * redundant on purpose. The transform is what physics and rendering want and
 * is authoritative; the parent link is what *staging* wants, because
 * decoupling has to know which subtree separates. Deriving either from the
 * other at the moment it is needed would be slower and more fragile than
 * storing both and validating that they agree at load.
 */
@Serializable
data class PlacedPart(
    val partId: String,
    /** Position in craft-local space, metres. */
    val position: SerialVec3,
    val rotation: SerialQuat = Quat.identity(),
    /** Index into [CraftDesign.parts], or -1 for the root. */
    val parentIndex: Int = -1,
    /** Which of the parent's attach nodes this hangs from. */
    val parentNodeId: String? = null,
    /** Which of this part's own nodes does the joining. */
    val ownNodeId: String? = null,
    /**
     * Parts placed together by a symmetry mode share a group id, so the
     * builder can move or delete them as one.
     */
    val symmetryGroup: Int = -1,
)

/**
 * One step of the staging sequence.
 *
 * Holds indices of the parts *activated* when the stage fires - engines ignite,
 * decouplers release, parachutes deploy. Stage 0 fires first.
 */
@Serializable
data class Stage(
    val activatedParts: List<Int> = emptyList(),
)

/**
 * Which way up a design is built, and so which way up it stands on the ground.
 *
 * The nose is +Y in design space whatever this says - engines, fins, the
 * attitude controller and the navball all read +Y as "where it is going", and
 * a plane is a stack flown on its side rather than a different kind of
 * object. What this decides is the other axis: which way is the *sky* when the
 * craft is sitting on the ground, and which way does it roll along it.
 *
 * Without it the builder could only make things that stand on their tails, a
 * rover's forward had to be a hard-coded +Z that happened to work, and a plane
 * could only ever be tested already in the air because there was no way to
 * put one on a runway.
 */
@Serializable
enum class CraftOrientation(
    /** Design axis pointing at the sky when the craft sits on the ground. */
    val up: Vec3,
    /** Design axis it rolls along when driven. Perpendicular to [up]. */
    val forward: Vec3,
    val label: String,
) {
    /** Stands on its tail. Rockets, landers - and rovers built as one. */
    @SerialName("vertical") VERTICAL(Vec3(0.0, 1.0, 0.0), Vec3(0.0, 0.0, 1.0), "Vertical"),

    /** Lies along the ground, nose forward, +Z to the sky. Planes, boats, cars. */
    @SerialName("horizontal") HORIZONTAL(Vec3(0.0, 0.0, 1.0), Vec3(0.0, 1.0, 0.0), "Horizontal");

    fun other(): CraftOrientation = if (this == VERTICAL) HORIZONTAL else VERTICAL
}

/**
 * A saved vehicle: the blueprint, not a thing in the world.
 *
 * This same type is the save-file format *and* the network payload for
 * spawning a craft. Keeping them identical means a craft that loads correctly
 * cannot fail to transmit correctly, and there is no second schema to keep in
 * step.
 */
@Serializable
data class CraftDesign(
    val name: String,
    val parts: List<PlacedPart>,
    val stages: List<Stage> = emptyList(),
    /**
     * The catalogue this was authored against. A design referring to parts the
     * loader does not have is refused with a useful message rather than
     * silently losing pieces.
     */
    val catalogHash: String = "",
    /** Defaults to vertical, which is what every design saved before it existed was. */
    val orientation: CraftOrientation = CraftOrientation.VERTICAL,
    /**
     * Whether [stages] were arranged by hand.
     *
     * False, the builder derives staging from the part tree on every edit,
     * as it always has. True, it keeps the player's arrangement and only
     * fits new parts into it and drops removed ones - rebuilding it would
     * throw away the order they chose.
     */
    val manualStaging: Boolean = false,
) {
    val partCount: Int get() = parts.size

    /** Indices of every part hanging below [index], inclusive. */
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
     * As it flies: stages that fire nothing dropped. The builder keeps an
     * empty stage the player has just made to fill; in flight it would be a
     * press of the button that did nothing.
     */
    fun withoutEmptyStages(): CraftDesign =
        if (stages.none { it.activatedParts.isEmpty() }) this
        else copy(stages = stages.filter { it.activatedParts.isNotEmpty() })

    /**
     * Checks the design against a catalogue.
     *
     * Returns the problems rather than throwing, because the builder wants to
     * show all of them at once and the network wants to reject with a reason.
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

        // A cycle would make subtreeOf loop forever at decouple time, which is
        // the worst possible moment to discover it.
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

    /** Resolves each placed part to its definition, in order. */
    fun resolve(catalog: PartCatalog): List<Pair<PlacedPart, PartDef>> =
        parts.map { it to catalog.require(it.partId) }

    companion object {
        /** A design holding a single part, used by tests and the "new craft" path. */
        fun single(name: String, partId: String): CraftDesign =
            CraftDesign(name, listOf(PlacedPart(partId, Vec3.zero())))
    }
}
