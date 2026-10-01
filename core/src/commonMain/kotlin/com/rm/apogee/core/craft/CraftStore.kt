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
 * Craft designs on disk as JSON, from the same `@Serializable` schema the network sends as protobuf,
 * so people can read and edit a save. The platform supplies the [Folder].
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
                // Skip a broken file rather than lose the whole list.
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
     * A design from a shared craft file. Fails with the reason if it isn't one, or uses parts
     * [catalog] doesn't have.
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
     * Writes each stock design the store hasn't been offered yet. A ledger next to the saves records
     * what's been offered, so a deleted design stays deleted but new ones still arrive.
     */
    fun seedStockDesigns(catalog: com.rm.apogee.core.part.PartCatalog) {
        val ledger = directory.read(SEEDED_LEDGER)
        val offered = if (ledger != null) {
            ledger.lines().map { it.trim() }.filter { it.isNotEmpty() }.toMutableSet()
        } else if (list().isNotEmpty()) {
            // Seeded before the ledger existed, which offered just these. Missing ones were deleted.
            LEGACY_STOCK.toMutableSet()
        } else {
            mutableSetOf()
        }

        val stock = listOf(
            StockCraft.starterRocket(catalog),
            // The career's first rocket: just the starting kit.
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
            StockCraft.seaFloorBase(catalog),
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
            // Never over your own craft of the same name.
            if (!exists(design.name)) save(design)
            offered.add(design.name)
        }
        directory.write(SEEDED_LEDGER, offered.sorted().joinToString("\n", postfix = "\n"))
    }

    private fun fileNameFor(name: String): String {
        // Anything not obviously safe (a slash, say) becomes an underscore.
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

        /** What was seeded before the ledger existed. */
        val LEGACY_STOCK = setOf("Starter I", "Stilt Lander", "Stilt Tug")
    }
}
