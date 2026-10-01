package com.rm.apogee.core.world

import com.rm.apogee.core.craft.CraftDesign
import com.rm.apogee.core.part.PartCatalog

/**
 * Carries old saves forward when parts get renamed or retired, so a rename doesn't cost somebody
 * their base. Nearly empty for now; the mechanism and its tests are here before the first rename.
 */
object SaveMigration {

    /** Part ids that have been renamed, old to new. Entries are permanent. */
    val renamedParts: Map<String, String> = mapOf(
        // "old-part-id" to "new-part-id",
    )

    /** Parts gone with no replacement, with the reason, so the player is told why. */
    val retiredParts: Map<String, String> = mapOf(
        // "old-part-id" to "retired in 0.4; nothing replaces it",
    )

    class Result(
        /** The design as this build understands it, or null if it can't be saved. */
        val design: CraftDesign?,
        /** What had to be done, or why it couldn't be. */
        val notes: List<String>,
    )

    /**
     * Brings one craft forward to the current catalogue: renames first, then a check. A part that
     * can't be found fails the whole craft, since dropping it would re-root everything on it.
     */
    fun migrate(
        design: CraftDesign,
        catalog: PartCatalog,
        /** Overridable so tests can run while the real tables are empty. */
        renames: Map<String, String> = renamedParts,
        retired: Map<String, String> = retiredParts,
    ): Result {
        val notes = ArrayList<String>()
        var changed = false

        val parts = design.parts.map { placed ->
            val replacement = renames[placed.partId]
            if (replacement != null) {
                changed = true
                notes.add("'${placed.partId}' is now '$replacement'")
                placed.copy(partId = replacement)
            } else {
                placed
            }
        }

        val migrated = if (changed) design.copy(parts = parts) else design

        val unknown = migrated.parts.map { it.partId }.distinct()
            .filter { catalog[it] == null }
        if (unknown.isNotEmpty()) {
            for (id in unknown) {
                notes.add(retired[id]?.let { "'$id': $it" } ?: "'$id' is not in the catalogue")
            }
            return Result(null, notes)
        }

        // Older stock craft had fins, legs and wheels all pointing one way; turn them to face out.
        return Result(com.rm.apogee.core.craft.StockCraft.facingOutward(migrated, catalog), notes)
    }

    /** Whether a save of [formatVersion] can be read. Older can be upgraded; newer is refused. */
    fun canRead(formatVersion: Int): Boolean = formatVersion <= WorldSave.FORMAT_VERSION
}
