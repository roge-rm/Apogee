package com.rm.apogee.core.craft

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.File

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
class AssemblyStore(private val directory: File) {

    private val format = Json {
        prettyPrint = true
        prettyPrintIndent = "  "
        classDiscriminator = "type"
        ignoreUnknownKeys = true
    }

    init {
        if (!directory.exists()) directory.mkdirs()
    }

    /** Each saved piece and its file's name. */
    fun list(): List<Pair<SavedAssembly, String>> =
        directory.listFiles { file -> file.isFile && file.extension == EXTENSION }
            ?.sortedByDescending { it.lastModified() }
            ?.mapNotNull { file ->
                runCatching { format.decodeFromString<SavedAssembly>(file.readText()) to file.name }.getOrNull()
            }
            ?.filter { it.first.parts.isNotEmpty() }
            ?: emptyList()

    /** Saves [assembly] as [name], under a new file if the name's taken, so nothing's written over. */
    fun save(name: String, assembly: Assembly): Result<String> = runCatching {
        // Where it came from doesn't go with it: its stages and any docking are the craft's own.
        val parts = assembly.parts.map { it.copy(dockedTo = -1, dockedFrom = null) }
        val base = fileNameFor(name)
        var file = File(directory, "$base.$EXTENSION")
        var n = 2
        while (file.exists()) file = File(directory, "$base-${n++}.$EXTENSION")
        val temporary = File(directory, "${file.name}.tmp")
        temporary.writeText(format.encodeToString(SavedAssembly(name.trim().ifBlank { "Assembly" }, parts, assembly.mountNodeId)))
        if (!temporary.renameTo(file)) {
            temporary.copyTo(file, overwrite = true)
            temporary.delete()
        }
        file.name
    }

    fun delete(fileName: String): Boolean = File(directory, fileName).delete()

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
