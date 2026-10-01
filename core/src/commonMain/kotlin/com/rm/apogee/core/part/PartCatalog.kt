package com.rm.apogee.core.part

import kotlinx.serialization.json.Json

/**
 * Every part the game knows, plus a hash of the set. A client and server with different parts drift
 * apart quietly, so the handshake compares [contentHash] and refuses to connect.
 *
 * Callers supply the JSON text, since :core doesn't read files.
 */
class PartCatalog private constructor(
    val parts: Map<String, PartDef>,
    val contentHash: String,
) {
    val size: Int get() = parts.size

    operator fun get(id: String): PartDef? = parts[id]

    /** Throws if the id isn't known, with a message listing what was available. */
    fun require(id: String): PartDef = parts[id] ?: throw IllegalArgumentException(
        "Unknown part '$id'. Catalogue holds ${parts.size} parts: " +
            parts.keys.sorted().joinToString(", ").take(200)
    )

    fun byCategory(category: PartCategory): List<PartDef> =
        parts.values.filter { it.category == category }.sortedBy { it.title }

    companion object {
        private val json = Json {
            ignoreUnknownKeys = false
            prettyPrint = false
            classDiscriminator = "type"
        }

        /** The JSON settings the part files are written for. */
        val format: Json = Json {
            ignoreUnknownKeys = false
            prettyPrint = true
            prettyPrintIndent = "  "
            classDiscriminator = "type"
        }

        /**
         * Builds a catalogue from JSON documents, each an array of [PartDef]. It checks everything
         * here, so a broken part fails at load with its name.
         */
        fun fromJson(sources: List<String>): PartCatalog {
            val all = sources.flatMapIndexed { index, source ->
                runCatching { format.decodeFromString<List<PartDef>>(source) }
                    .getOrElse { cause ->
                        throw IllegalArgumentException(
                            "Part source #$index is not a valid part list: ${cause.message}",
                            cause,
                        )
                    }
            }
            return of(all)
        }

        fun of(defs: List<PartDef>): PartCatalog {
            validate(defs)
            val byId = defs.associateBy { it.id }
            return PartCatalog(byId, hash(defs))
        }

        private fun validate(defs: List<PartDef>) {
            val seen = HashSet<String>(defs.size)
            for (def in defs) {
                require(def.id.isNotBlank()) { "A part has a blank id" }
                require(seen.add(def.id)) { "Duplicate part id '${def.id}'" }
                require(def.dryMass > 0.0) { "Part '${def.id}' has non-positive dryMass" }

                val nodeIds = HashSet<String>(def.attachNodes.size)
                for (node in def.attachNodes) {
                    require(nodeIds.add(node.id)) {
                        "Part '${def.id}' has duplicate attach node '${node.id}'"
                    }
                    require(node.direction.length > 1e-9) {
                        "Part '${def.id}' node '${node.id}' has a zero direction"
                    }
                }

                def.module<Engine>()?.let { engine ->
                    require(engine.ispVacuum > 0.0 && engine.ispSeaLevel > 0.0) {
                        "Part '${def.id}' has an engine with non-positive Isp"
                    }
                    // Some thrust somewhere; an air-breather has none in vacuum.
                    require(
                        engine.thrustVacuum >= 0.0 && engine.thrustSeaLevel >= 0.0 &&
                            engine.thrustVacuum + engine.thrustSeaLevel > 0.0
                    ) {
                        "Part '${def.id}' has an engine with no thrust anywhere"
                    }
                }
                def.module<Tank>()?.let { tank ->
                    require(tank.capacity > 0.0) {
                        "Part '${def.id}' has a tank with non-positive capacity"
                    }
                }
            }
        }

        /**
         * A stable hash of the parts, sorted by id and re-encoded compactly, so file order and
         * whitespace don't change it.
         */
        private fun hash(defs: List<PartDef>): String {
            val canonical = json.encodeToString(defs.sortedBy { it.id })
            return com.rm.apogee.core.hex(com.rm.apogee.core.Sha256.digest(canonical.encodeToByteArray())).take(16)
        }
    }
}
