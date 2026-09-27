package com.rm.apogee.core.world

import com.rm.apogee.core.craft.CraftDesign
import com.rm.apogee.core.part.PartCatalog

/**
 * Carrying old saves forward across changes to the game.
 *
 * People leave bases and come back months later, and by then the catalogue has moved on. Parts get
 * renamed, split and retired. Without somewhere to record that, a rename quietly costs somebody
 * their base. The design still names a part that doesn't exist any more, the craft fails its check,
 * and it gets dropped with a line in a log nobody reads.
 *
 * This is that somewhere. It's nearly empty today, on purpose. The value is in having the mechanism
 * and its tests in place *before* the first rename, because afterwards is too late for every world
 * already written.
 */
object SaveMigration {

    /**
     * Part ids that have been renamed, old to new.
     *
     * Entries are permanent. A rename recorded here has to keep working for as long as saves
     * written before it might still exist, which for a game people leave things in is forever.
     */
    val renamedParts: Map<String, String> = mapOf(
        // "old-part-id" to "new-part-id",
    )

    /**
     * Parts that don't exist any more and have no replacement, with the reason.
     *
     * They're named instead of left to fail validation, so a player is told their craft was
     * carrying a retired part instead of getting a generic "unknown part".
     */
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
     * Brings one craft forward to the current catalogue.
     *
     * Renames get applied first, then what's left is checked against the catalogue. A part that
     * can't be found fails the whole craft instead of being dropped from it. Removing a part
     * quietly re-roots everything attached to it, and a base that comes back subtly rearranged is
     * worse than one that comes back with an explanation.
     */
    fun migrate(
        design: CraftDesign,
        catalog: PartCatalog,
        /**
         * Overridable so the mechanism can be tested while the real tables are empty. Waiting for
         * the first rename to find out whether any of this works would mean finding out at the
         * worst possible moment.
         */
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

        // Stock craft from older builds had their fins, legs and wheels all pointing one way, so
        // turn them to face out the way the builder would have.
        return Result(com.rm.apogee.core.craft.StockCraft.facingOutward(migrated, catalog), notes)
    }

    /**
     * Whether a save of [formatVersion] can be read at all.
     *
     * Older can be upgraded. There's nothing to do yet, but this is where it would go. Newer can't
     * be. A build can't invent a format it has never seen, and half-reading one would be worse than
     * refusing.
     */
    fun canRead(formatVersion: Int): Boolean = formatVersion <= WorldSave.FORMAT_VERSION
}
