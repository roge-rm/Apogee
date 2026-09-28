package com.rm.apogee.core.world

import com.rm.apogee.core.Folder
import kotlinx.serialization.json.Json

/**
 * A persistent world on disk.
 *
 * It writes to a temporary file and renames it, so a crash or a power cut during a save leaves the
 * previous world intact instead of a cut-off one. The previous save is also kept as a single
 * backup. An autosave that captures a broken state is rarer than one that captures a state you
 * didn't want, and having exactly one step back has saved more worlds than a full history would.
 *
 * It takes a [Folder] and a name instead of finding a location itself, because :core doesn't depend
 * on any platform. The dedicated server passes a path from its config, the browser its storage, and
 * tests a temporary folder.
 */
class WorldStore(private val folder: Folder, private val name: String) {

    private val format = Json {
        prettyPrint = true
        prettyPrintIndent = "  "
        classDiscriminator = "type"
        // An operator might edit a save by hand, and a stray field shouldn't cost them the world.
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
