package com.rm.apogee.core.world

import com.rm.apogee.core.Folder
import kotlinx.serialization.json.Json

/**
 * A persistent world on disk. It writes a temporary file and renames it, so a crash mid-save leaves
 * the previous world intact, and keeps the previous save as one backup. It takes a [Folder] because
 * :core doesn't depend on any platform.
 */
class WorldStore(private val folder: Folder, private val name: String) {

    private val format = Json {
        prettyPrint = true
        prettyPrintIndent = "  "
        classDiscriminator = "type"
        // A stray field from hand editing shouldn't cost the world.
        ignoreUnknownKeys = true
    }

    private val backupName = "$name.bak"

    val exists: Boolean get() = folder.exists(name)

    val path: String get() = folder.path(name)

    /** Bytes on disk, or 0 when there's no save yet. */
    val sizeBytes: Long get() = folder.size(name)

    val lastSavedEpochMillis: Long get() = folder.lastModified(name)

    fun save(world: WorldSave): Result<Unit> = runCatching {
        val text = format.encodeToString(world)
        // Keep one step back before replacing what's there.
        folder.read(name)?.let { previous -> runCatching { folder.write(backupName, previous) } }
        folder.write(name, text)
    }

    fun load(): Result<WorldSave> = runCatching {
        format.decodeFromString<WorldSave>(folder.read(name) ?: error("There's no save"))
    }

    /**
     * Loads the world, falling back to the backup if the main file won't parse.
     *
     * @return the save and any warning, or null when there's nothing to load.
     */
    fun loadWithFallback(): Pair<WorldSave, String?>? {
        if (folder.exists(name)) {
            load().onSuccess { return it to null }
        }
        folder.read(backupName)?.let { backup ->
            runCatching { format.decodeFromString<WorldSave>(backup) }
                .onSuccess {
                    return it to "The main save couldn't be read, so the previous one was loaded instead"
                }
        }
        return null
    }
}
