package com.rm.apogee.core.world

import kotlinx.serialization.json.Json
import java.io.File

/**
 * A persistent world on disk.
 *
 * Writes to a temporary file and renames, so a crash or a power cut during a
 * save leaves the previous world intact rather than a truncated one. The
 * previous save is also kept as a single backup: an autosave that captures a
 * corrupted state is rarer than one that captures an unwanted one, and having
 * exactly one step back has saved more worlds than a full history would.
 *
 * Takes a [File] rather than reaching for a location, because :core has no
 * platform dependency - the dedicated server passes a path from its config and
 * tests pass a temporary folder.
 */
class WorldStore(private val file: File) {

    private val format = Json {
        prettyPrint = true
        prettyPrintIndent = "  "
        classDiscriminator = "type"
        // An operator may hand-edit a save, and a stray field should not cost
        // them the world.
        ignoreUnknownKeys = true
    }

    private val backupFile = File(file.parentFile, "${file.name}.bak")

    val exists: Boolean get() = file.exists()

    val path: String get() = file.absolutePath

    /** Bytes on disk, or 0 when there is no save yet. */
    val sizeBytes: Long get() = if (file.exists()) file.length() else 0L

    val lastSavedEpochMillis: Long get() = if (file.exists()) file.lastModified() else 0L

    fun save(world: WorldSave): Result<Unit> = runCatching {
        file.parentFile?.mkdirs()

        val temporary = File(file.parentFile, "${file.name}.tmp")
        temporary.writeText(format.encodeToString(world))

        // Keep one step back before replacing what is there.
        if (file.exists()) {
            runCatching { file.copyTo(backupFile, overwrite = true) }
        }
        if (!temporary.renameTo(file)) {
            temporary.copyTo(file, overwrite = true)
            temporary.delete()
        }
    }

    fun load(): Result<WorldSave> = runCatching {
        format.decodeFromString<WorldSave>(file.readText())
    }

    /**
     * Loads the world, falling back to the backup if the main file will not
     * parse.
     *
     * @return the save and any warning, or null when there is nothing to load.
     */
    fun loadWithFallback(): Pair<WorldSave, String?>? {
        if (file.exists()) {
            load().onSuccess { return it to null }
        }
        if (backupFile.exists()) {
            runCatching { format.decodeFromString<WorldSave>(backupFile.readText()) }
                .onSuccess {
                    return it to "Main save would not parse; loaded the previous one instead"
                }
        }
        return null
    }
}
