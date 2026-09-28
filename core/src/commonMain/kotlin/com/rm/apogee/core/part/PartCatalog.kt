package com.rm.apogee.core.part

import kotlinx.serialization.json.Json

/**
 * Every part the game knows about, plus a hash of the whole set.
 *
 * The hash is the important bit. A client and a server that disagree about what "tank-t200" weighs
 * don't fail loudly. They drift apart, and the player sees their craft slide around as the server
 * corrects a path worked out from different numbers. Comparing [contentHash] during the handshake
 * turns that into a clean, immediate refusal to connect.
 *
 * :core can't read Android assets or open files, on purpose, because it doesn't depend on any
 * platform. Callers supply the JSON text: the app reads it from assets, the server from disk, and
 * tests from string literals.
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
         * Builds a catalogue from one or more JSON documents, each an array of [PartDef].
         *
         * Checking happens here instead of on first use, so a broken catalogue fails when it loads
         * with a message naming the part, instead of turning up as a null dereference in the middle
         * of a flight.
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
                    // Some thrust somewhere. Not necessarily in vacuum, since an air-breather has
                    // none there by definition.
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
         * A stable hash of the catalogue's content.
         *
         * It's sorted by id and re-encoded through the compact format, so it depends on what the
         * parts *are* and not on file order, whitespace or which file each one came from. Two
         * machines that loaded the same parts arranged differently still have to agree.
         */
        private fun hash(defs: List<PartDef>): String {
            val canonical = json.encodeToString(defs.sortedBy { it.id })
            return com.rm.apogee.core.hex(com.rm.apogee.core.Sha256.digest(canonical.encodeToByteArray())).take(16)
        }
    }
}
