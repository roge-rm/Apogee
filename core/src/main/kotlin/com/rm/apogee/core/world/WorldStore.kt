package com.rm.apogee.core.world

import kotlinx.serialization.json.Json
import java.io.File

/**
 * A persistent world on disk.
 *
 * It writes to a temporary file and renames it, so a crash or a power cut during a save leaves the
 * previous world intact instead of a cut-off one. The previous save is also kept as a single
 * backup. An autosave that captures a broken state is rarer than one that captures a state you
 * didn't want, and having exactly one step back has saved more worlds than a full history would.
 *
 * It takes a [File] instead of finding a location itself, because :core doesn't depend on any
 * platform. The dedicated server passes a path from its config and tests pass a temporary folder.
 */
class WorldStore(private val file: File) {

    private val format = Json {
        prettyPrint = true
        prettyPrintIndent = "  "
        classDiscriminator = "type"
        // An operator might edit a save by hand, and a stray field shouldn't cost them the world.
        ignoreUnknownKeys = true
    }

    private val backupFile = File(file.parentFile, "${file.name}.bak")

    val exists: Boolean get() = file.exists()

    val path: String get() = file.absolutePath

    /** Bytes on disk, or 0 when there's no save yet. */
    val sizeBytes: Long get() = if (file.exists()) file.length() else 0L

    val lastSavedEpochMillis: Long get() = if (file.exists()) file.lastModified() else 0L

    fun save(world: WorldSave): Result<Unit> = runCatching {
        file.parentFile?.mkdirs()

        val temporary = File(file.parentFile, "${file.name}.tmp")
        temporary.writeText(format.encodeToString(world))

        // Keep one step back before replacing what's there.
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
     * Loads the world, falling back to the backup if the main file won't parse.
     *
     * @return the save and any warning, or null when there's nothing to load.
     */
    fun loadWithFallback(): Pair<WorldSave, String?>? {
        if (file.exists()) {
            load().onSuccess { return it to null }
        }
        if (backupFile.exists()) {
            runCatching { format.decodeFromString<WorldSave>(backupFile.readText()) }
                .onSuccess {
                    return it to "The main save couldn't be read, so the previous one was loaded instead"
                }
        }
        return null
    }
}
