package com.rm.apogee.core.craft

import kotlinx.serialization.json.Json
import java.io.File

/** A saved design, as the load list shows it. */
class SavedCraft(
    val name: String,
    val fileName: String,
    val partCount: Int,
    val lastModified: Long,
    /** Which parts it's made of, so a career can say what it still needs. */
    val partIds: Set<String> = emptySet(),
)

/**
 * Craft designs on disk, stored as JSON.
 *
 * It's JSON rather than the protobuf the network uses, from the same `@Serializable` definition. A
 * save file is something a person might open, compare, edit by hand or paste into a bug report, and
 * a network packet isn't. One schema, two encodings, and no second format to keep in step.
 *
 * It takes a directory instead of finding one itself, because :core doesn't depend on any platform.
 * The app passes its files directory, the dedicated server passes a path from its config, and tests
 * pass a temporary folder.
 */
class CraftStore(private val directory: File) {

    private val format = Json {
        prettyPrint = true
        prettyPrintIndent = "  "
        classDiscriminator = "type"
        ignoreUnknownKeys = true
    }

    init {
        if (!directory.exists()) directory.mkdirs()
    }

    fun list(): List<SavedCraft> =
        directory.listFiles { file -> file.isFile && file.extension == EXTENSION }
            ?.mapNotNull { file ->
                // A broken or half-written file mustn't take the whole list down with it. You
                // should still see your other craft.
                runCatching {
                    val design = format.decodeFromString<CraftDesign>(file.readText())
                    SavedCraft(design.name, file.name, design.parts.size, file.lastModified(), design.parts.map { it.partId }.toSet())
                }.getOrNull()
            }
            ?.sortedByDescending { it.lastModified }
            ?: emptyList()

    fun save(design: CraftDesign): Result<SavedCraft> = runCatching {
        val file = File(directory, fileNameFor(design.name))
        // Write to a temporary file and then rename it, so a crash in the middle of writing leaves
        // the previous version intact instead of a cut-off one.
        val temporary = File(directory, "${file.name}.tmp")
        temporary.writeText(format.encodeToString(design))
        if (!temporary.renameTo(file)) {
            temporary.copyTo(file, overwrite = true)
            temporary.delete()
        }
        SavedCraft(design.name, file.name, design.parts.size, file.lastModified())
    }

    fun load(fileName: String): Result<CraftDesign> = runCatching {
        format.decodeFromString<CraftDesign>(File(directory, fileName).readText())
    }

    fun delete(fileName: String): Boolean = File(directory, fileName).delete()

    fun exists(name: String): Boolean = File(directory, fileNameFor(name)).exists()

    /**
     * Writes any reference craft into the store that it hasn't been given before.
     *
     * Each stock design is only offered once, ever, and remembered in a small ledger next to the
     * saves. If you deleted one you meant to, and having it come back on every launch is the kind
     * of small betrayal that makes a tool feel untrustworthy. A stock design added in a later
     * version still arrives, though. The old rule, "only into an empty store", meant nobody who had
     * ever opened the game would see a new one.
     */
    fun seedStockDesigns(catalog: com.rm.apogee.core.part.PartCatalog) {
        val ledger = File(directory, SEEDED_LEDGER)
        val offered = if (ledger.exists()) {
            ledger.readLines().map { it.trim() }.filter { it.isNotEmpty() }.toMutableSet()
        } else if (list().isNotEmpty()) {
            // Seeded before the ledger existed, under the empty-store rule, which offered exactly
            // these. Any of them that are missing now were deleted on purpose.
            LEGACY_STOCK.toMutableSet()
        } else {
            mutableSetOf()
        }

        val stock = listOf(
            StockCraft.starterRocket(catalog),
            // The first rocket you'd build in a career: nothing but the starting kit.
            StockCraft.sounder(catalog),
            StockCraft.moteProbe(catalog),
            StockCraft.lander(catalog),
            StockCraft.moduleTug(catalog),
            StockCraft.rover(catalog),
            StockCraft.aeroplane(catalog),
            StockCraft.boat(catalog),
            StockCraft.sparrow(catalog),
            StockCraft.buggy(catalog),
            StockCraft.hauler(catalog),
            StockCraft.skiff(catalog),
            StockCraft.cutter(catalog),
            StockCraft.trawler(catalog),
            StockCraft.portTug(catalog),
            StockCraft.dockProbe(catalog),
            StockCraft.towBuggy(catalog),
            StockCraft.cart(catalog),
            StockCraft.baseCore(catalog),
            StockCraft.padBase(catalog),
            StockCraft.baseCoreHauler(catalog),
            StockCraft.moduleHauler(catalog),
            StockCraft.depotHauler(catalog),
            StockCraft.baseCoreLander(catalog),
            StockCraft.moonshot(catalog),
            StockCraft.prospector(catalog),
            StockCraft.surveyor(catalog),
            // Under the sea, one for each hull tier.
            StockCraft.minnow(catalog),
            StockCraft.nautilus(catalog),
            StockCraft.abyss(catalog),
        )
        for (design in stock) {
            if (design.name in offered) continue
            // Never over one of your own craft that happens to have the same name.
            if (!exists(design.name)) save(design)
            offered.add(design.name)
        }
        ledger.writeText(offered.sorted().joinToString("\n", postfix = "\n"))
    }

    private fun fileNameFor(name: String): String {
        // Anything that isn't obviously safe becomes an underscore. A craft name is free text and
        // sooner or later it will have a slash in it.
        val sanitised = name.trim()
            .map { if (it.isLetterOrDigit() || it == '-' || it == ' ') it else '_' }
            .joinToString("")
            .replace(' ', '-')
            .ifBlank { "untitled" }
            .take(64)
        return "$sanitised.$EXTENSION"
    }

    private companion object {
        const val EXTENSION = "craft"
        const val SEEDED_LEDGER = "stock-offered.txt"

        /** What the empty-store rule seeded, before the ledger existed. */
        val LEGACY_STOCK = setOf("Starter I", "Stilt Lander", "Stilt Tug")
    }
}
