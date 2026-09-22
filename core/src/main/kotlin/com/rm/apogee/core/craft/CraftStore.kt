package com.rm.apogee.core.craft

import kotlinx.serialization.json.Json
import java.io.File

/** A saved design, as the load list shows it. */
class SavedCraft(
    val name: String,
    val fileName: String,
    val partCount: Int,
    val lastModified: Long,
)

/**
 * Craft designs on disk, as JSON.
 *
 * JSON rather than the protobuf the network uses, from the same
 * `@Serializable` definition. A save file is something a person might open,
 * diff, hand-edit or paste into a bug report; a network packet is not. One
 * schema, two encodings, no second format to keep in step.
 *
 * Takes a directory rather than reaching for one, because :core has no platform
 * dependency - the app passes its files directory, the dedicated server passes
 * a path from its config, and tests pass a temporary folder.
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
                // A corrupt or half-written file must not take the whole list
                // down with it - the player should still see their other craft.
                runCatching {
                    val design = format.decodeFromString<CraftDesign>(file.readText())
                    SavedCraft(design.name, file.name, design.parts.size, file.lastModified())
                }.getOrNull()
            }
            ?.sortedByDescending { it.lastModified }
            ?: emptyList()

    fun save(design: CraftDesign): Result<SavedCraft> = runCatching {
        val file = File(directory, fileNameFor(design.name))
        // Write to a temporary file and rename, so a crash mid-write leaves the
        // previous version intact rather than a truncated one.
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
     * Writes the reference craft into an empty store.
     *
     * Only into an empty one: a player who has deleted the stock designs
     * meant to delete them, and having them reappear on every launch is the
     * kind of small betrayal that makes a tool feel untrustworthy.
     */
    fun seedStockDesigns(catalog: com.rm.apogee.core.part.PartCatalog) {
        if (list().isNotEmpty()) return
        save(StockCraft.starterRocket(catalog))
        save(StockCraft.lander(catalog))
    }

    private fun fileNameFor(name: String): String {
        // Anything that is not obviously safe becomes an underscore: a craft
        // name is free text and will eventually contain a slash.
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
    }
}
