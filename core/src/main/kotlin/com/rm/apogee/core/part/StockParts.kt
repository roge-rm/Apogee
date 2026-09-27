package com.rm.apogee.core.part

/**
 * Loads the part catalogue that ships with the game, from the classpath.
 *
 * It lives in :core's resources instead of the app's Android assets so there's exactly one copy.
 * The app, the dedicated server and the unit tests all load the same bytes, and so all work out the
 * same [PartCatalog.contentHash]. Two copies of a catalogue is two catalogues, and the handshake
 * would start rejecting builds that match perfectly.
 */
object StockParts {

    /** The catalogue's files: craft parts, parts for building bases, and the Cape's buildings. */
    private val RESOURCE_PATHS = listOf("/parts/stock.json", "/parts/bases.json", "/parts/structures.json")

    /** Parsed once, because the catalogue never changes. */
    val catalog: PartCatalog by lazy {
        val texts = RESOURCE_PATHS.map { path ->
            StockParts::class.java.getResourceAsStream(path)
                ?.bufferedReader()
                ?.use { it.readText() }
                ?: error(
                    "Stock part catalogue missing from the classpath at $path. " +
                        "It ships in :core's resources - check that module's jar is on the path."
                )
        }
        PartCatalog.fromJson(texts)
    }
}
