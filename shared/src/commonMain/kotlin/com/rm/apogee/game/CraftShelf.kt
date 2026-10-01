package com.rm.apogee.game

import com.rm.apogee.core.craft.CraftDesign
import com.rm.apogee.core.craft.CraftKind
import com.rm.apogee.core.craft.CraftStats
import com.rm.apogee.core.craft.CraftStore
import com.rm.apogee.core.craft.SavedCraft
import com.rm.apogee.core.part.PartCatalog
import com.rm.apogee.platform.format

/**
 * The saved craft, read and described for the lists that offer them: Vehicle Assembly and Quick
 * Launch.
 */
object CraftShelf {

    /** A saved craft: its design, kind, a line or two about it, and its picture's name. */
    class Entry(val saved: SavedCraft, val design: CraftDesign, val kind: CraftKind, val summary: String, val picture: String)

    /**
     * Every craft in [list], read from [store], in order. It reads every design, so it's for a
     * background thread. Asks [thumbnails] for any pictures not drawn yet.
     */
    fun read(list: List<SavedCraft>, store: CraftStore, catalog: PartCatalog, thumbnails: com.rm.apogee.render.PartThumbnails?): List<Entry> =
        list.mapNotNull { saved ->
            val design = store.load(saved.fileName).getOrNull() ?: return@mapNotNull null
            val kind = CraftKind.of(design, catalog)
            val stats = runCatching { CraftStats.analyze(design, catalog) }.getOrNull()
            thumbnails?.requestCraft(design, catalog)
            Entry(saved, design, kind, summaryOf(kind, stats, saved.partCount), thumbnails?.craftKey(design) ?: "")
        }

    /**
     * The one or two figures that say most about a craft of [kind] on one line, and its mass and
     * parts on the next.
     */
    private fun summaryOf(kind: CraftKind, stats: CraftStats?, parts: Int): String {
        val key = ArrayList<String>()
        if (stats != null) {
            when (kind) {
                CraftKind.ROCKET, CraftKind.BASE -> {
                    if (stats.totalDeltaV > 1.0) key += "Δv ${"%,d".format(stats.totalDeltaV.toInt())} m/s"
                    if (stats.liftoffTwr > 0.0) key += "TWR ${"%.1f".format(stats.liftoffTwr)}"
                }
                CraftKind.PLANE -> {
                    val thrust = stats.stages.firstOrNull { it.isBurn }?.thrustSeaLevel ?: 0.0
                    if (thrust > 0.0 && stats.totalMass > 0.0) key += "thrust ${"%.2f".format(thrust / (stats.totalMass * 9.81))} of weight"
                }
                else -> {}
            }
        }
        val size = listOfNotNull(stats?.let { mass(it.totalMass) }, if (parts == 1) "1 part" else "$parts parts").joinToString(" · ")
        return if (key.isEmpty()) size else key.joinToString(" · ") + "\n" + size
    }

    private fun mass(kg: Double): String = if (kg >= 1_000.0) "${"%.1f".format(kg / 1_000.0)} t" else "${kg.toInt()} kg"
}
