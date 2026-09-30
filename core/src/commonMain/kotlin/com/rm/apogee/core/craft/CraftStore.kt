package com.rm.apogee.core.craft

import com.rm.apogee.core.Folder
import kotlinx.serialization.json.Json

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
 * It takes a [Folder] instead of finding one itself, because :core doesn't depend on any platform.
 * The app passes its files directory, the browser its storage, the dedicated server a path from its
 * config, and tests a temporary folder.
 */
class CraftStore(private val directory: Folder) {

    private val format = Json {
        prettyPrint = true
        prettyPrintIndent = "  "
        classDiscriminator = "type"
        ignoreUnknownKeys = true
    }

    fun list(): List<SavedCraft> =
        directory.names().filter { it.endsWith(".$EXTENSION") }
            .mapNotNull { name ->
                // A broken or half-written file mustn't take the whole list down with it. You
                // should still see your other craft.
                runCatching {
                    val design = format.decodeFromString<CraftDesign>(directory.read(name)!!)
                    SavedCraft(design.name, name, design.parts.size, directory.lastModified(name), design.parts.map { it.partId }.toSet())
                }.getOrNull()
            }
            .sortedByDescending { it.lastModified }

    fun save(design: CraftDesign): Result<SavedCraft> = runCatching {
        val name = fileNameFor(design.name)
        directory.write(name, format.encodeToString(design))
        SavedCraft(design.name, name, design.parts.size, directory.lastModified(name))
    }

    fun load(fileName: String): Result<CraftDesign> = runCatching {
        format.decodeFromString<CraftDesign>(directory.read(fileName) ?: error("There's no $fileName"))
    }

    fun delete(fileName: String): Boolean = directory.delete(fileName)

    fun exists(name: String): Boolean = directory.exists(fileNameFor(name))

    /** [design] as the text of a craft file, to share. */
    fun encode(design: CraftDesign): String = format.encodeToString(design)

    /**
     * A design from the text of a shared craft file. It's refused, with the reason, if it isn't a
     * craft file, or if it's made of parts [catalog] doesn't have, say from a newer version.
     */
    fun decode(text: String, catalog: com.rm.apogee.core.part.PartCatalog): Result<CraftDesign> = runCatching {
        val design = runCatching { format.decodeFromString<CraftDesign>(text) }
            .getOrElse { throw IllegalArgumentException("That isn't a craft file") }
        if (design.parts.isEmpty()) throw IllegalArgumentException("That craft has no parts")
        val unknown = design.parts.map { it.partId }.filter { catalog[it] == null }.distinct()
        if (unknown.isNotEmpty()) throw IllegalArgumentException("Parts this version doesn't have: ${unknown.take(4).joinToString()}")
        design
    }

    /** [name], or with a number after it if a craft here already has that name. */
    fun freeName(name: String): String {
        val base = name.trim().ifBlank { "Shared craft" }
        if (!exists(base)) return base
        var n = 2
        while (exists("$base $n")) n++
        return "$base $n"
    }

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
        val ledger = directory.read(SEEDED_LEDGER)
        val offered = if (ledger != null) {
            ledger.lines().map { it.trim() }.filter { it.isNotEmpty() }.toMutableSet()
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
            StockCraft.petrel(catalog),
            StockCraft.buggy(catalog),
            StockCraft.hauler(catalog),
            StockCraft.skiff(catalog),
            StockCraft.sloop(catalog),
            StockCraft.jetBoat(catalog),
            StockCraft.jetSki(catalog),
            // Rotors and lighter than air, and platforms for the sky and the sea.
            StockCraft.hummingbird(catalog),
            StockCraft.quad(catalog),
            StockCraft.skylark(catalog),
            StockCraft.zeppelin(catalog),
            StockCraft.skyPlatform(catalog),
            StockCraft.seaPlatform(catalog),
            StockCraft.cutter(catalog),
            StockCraft.trawler(catalog),
            StockCraft.coaster(catalog),
            StockCraft.coaster(catalog, electric = true),
            StockCraft.schooner(catalog),
            StockCraft.deckBarge(catalog),
            StockCraft.harbourTug(catalog),
            StockCraft.landingBarge(catalog),
            StockCraft.flatTop(catalog),
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
        directory.write(SEEDED_LEDGER, offered.sorted().joinToString("\n", postfix = "\n"))
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
