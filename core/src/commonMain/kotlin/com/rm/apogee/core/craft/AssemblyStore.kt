package com.rm.apogee.core.craft

import kotlinx.serialization.Serializable
import com.rm.apogee.core.Folder
import kotlinx.serialization.json.Json

/**
 * A piece of a craft kept to use again: a part and everything hanging from it, named. A pair of
 * boosters with their nose cones, a wing with its elevon and engine, a lander's legs.
 */
@Serializable
data class SavedAssembly(
    val name: String,
    val parts: List<PlacedPart>,
    val mountNodeId: String? = null,
) {
    val assembly: Assembly get() = Assembly(parts, mountNodeId)
    val rootPartId: String get() = parts[0].partId
}

/**
 * Saved pieces on disk, one JSON file each, the same way [CraftStore] keeps whole craft. They're
 * listed newest first.
 */
class AssemblyStore(private val directory: Folder) {

    private val format = Json {
        prettyPrint = true
        prettyPrintIndent = "  "
        classDiscriminator = "type"
        ignoreUnknownKeys = true
    }

    /** Each saved piece and its file's name. */
    fun list(): List<Pair<SavedAssembly, String>> =
        directory.names().filter { it.endsWith(".$EXTENSION") }
            .sortedByDescending { directory.lastModified(it) }
            .mapNotNull { name ->
                runCatching { format.decodeFromString<SavedAssembly>(directory.read(name)!!) to name }.getOrNull()
            }
            .filter { it.first.parts.isNotEmpty() }

    /** Saves [assembly] as [name], under a new file if the name's taken, so nothing's written over. */
    fun save(name: String, assembly: Assembly): Result<String> = runCatching {
        // Where it came from doesn't go with it: its stages and any docking are the craft's own.
        val parts = assembly.parts.map { it.copy(dockedTo = -1, dockedFrom = null) }
        val base = fileNameFor(name)
        var file = "$base.$EXTENSION"
        var n = 2
        while (directory.exists(file)) file = "$base-${n++}.$EXTENSION"
        directory.write(file, format.encodeToString(SavedAssembly(name.trim().ifBlank { "Assembly" }, parts, assembly.mountNodeId)))
        file
    }

    fun delete(fileName: String): Boolean = directory.delete(fileName)

    private fun fileNameFor(name: String): String = name.trim()
        .map { if (it.isLetterOrDigit() || it == '-' || it == ' ') it else '_' }
        .joinToString("")
        .replace(' ', '-')
        .ifBlank { "assembly" }
        .take(64)

    private companion object {
        const val EXTENSION = "assembly"
    }
}
