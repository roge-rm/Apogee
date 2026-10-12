package com.rm.apogee.core.world

import com.rm.apogee.core.Folder
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * The universes kept in [folder] of one kind: [kind] is "solo" for sandbox ones or "career". The
 * first is kind.json, the rest kind-2.json, kind-3.json and so on, each with its own save point.
 * Names are kept in worlds.json beside them.
 */
class WorldSlots(private val folder: Folder, private val kind: String) {

    /** One world: its file's base name (solo, solo-2) and what the player calls it. */
    data class Slot(val id: String, val name: String, val lastSaved: Long, val sizeBytes: Long) {
        val file: String get() = "$id.json"
    }

    @Serializable
    private class Index(val names: Map<String, String> = emptyMap())

    private val format = Json { ignoreUnknownKeys = true; prettyPrint = true }

    private fun index(): Index =
        folder.read(INDEX)?.let { runCatching { format.decodeFromString<Index>(it) }.getOrNull() } ?: Index()

    private fun writeIndex(names: Map<String, String>) = folder.write(INDEX, format.encodeToString(Index.serializer(), Index(names)))

    /** Every world of this kind, the first first. The first is there even before it's been saved. */
    fun list(): List<Slot> {
        val names = index().names
        // Saved ones, and new ones named but not saved yet.
        val ids = (folder.names().filter { it.endsWith(".json") }.map { it.removeSuffix(".json") } + names.keys)
            .filter { it == kind || (it.startsWith("$kind-") && it.removePrefix("$kind-").toIntOrNull() != null) }
            .toMutableSet()
        ids += kind
        return ids.sortedBy { if (it == kind) 0 else it.removePrefix("$kind-").toInt() }.map { id ->
            Slot(id, names[id] ?: defaultName(id), folder.lastModified("$id.json"), folder.size("$id.json"))
        }
    }

    /** The world [id], or the first if it's gone. */
    fun find(id: String): Slot = list().firstOrNull { it.id == id } ?: list().first()

    /** Its save. */
    fun store(slot: Slot): WorldStore = WorldStore(folder, slot.file)

    /** Its save point, and the note with its craft and time. */
    fun savePointStore(slot: Slot): WorldStore = WorldStore(folder, "${slot.id}-savepoint.json")
    fun savePointNote(slot: Slot): String = "${slot.id}-savepoint.txt"

    /** A new, empty world called [name], with nothing saved yet. */
    fun create(name: String): Slot {
        val taken = list().map { it.id }.toSet()
        var n = 2
        while ("$kind-$n" in taken) n++
        val id = "$kind-$n"
        writeIndex(index().names + (id to name.ifBlank { defaultName(id) }))
        return Slot(id, name.ifBlank { defaultName(id) }, 0L, 0L)
    }

    fun rename(slot: Slot, name: String) {
        if (name.isBlank()) return
        writeIndex(index().names + (slot.id to name.trim()))
    }

    /** Deletes [slot] and everything kept with it. The first world is emptied but stays. */
    fun delete(slot: Slot) {
        for (file in listOf(slot.file, "${slot.file}.bak", "${slot.id}-savepoint.json", "${slot.id}-savepoint.json.bak", savePointNote(slot))) {
            folder.delete(file)
        }
        if (slot.id != kind) writeIndex(index().names - slot.id)
    }

    private fun defaultName(id: String): String {
        val n = if (id == kind) 1 else id.removePrefix("$kind-").toInt()
        return if (kind == "career") "Career $n" else "Universe $n"
    }

    private companion object {
        const val INDEX = "worlds.json"
    }
}
