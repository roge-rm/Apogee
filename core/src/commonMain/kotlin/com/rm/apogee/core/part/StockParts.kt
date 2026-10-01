package com.rm.apogee.core.part

/**
 * Loads the stock part catalogue from :core's resources. It's the one copy the app, server and tests
 * all read, so they agree on [PartCatalog.contentHash] at the handshake.
 */
object StockParts {

    /** The catalogue's files: craft parts, parts for building bases, and the Cape's buildings. */
    private val RESOURCE_PATHS = listOf("/parts/stock.json", "/parts/bases.json", "/parts/structures.json")

    /** Parsed once. */
    val catalog: PartCatalog by lazy {
        val texts = RESOURCE_PATHS.map { path ->
            com.rm.apogee.core.resourceText(path)
                ?: error(
                    "Stock part catalogue missing from the resources at $path. " +
                        "It ships in :core's resources - check that module's jar is on the path."
                )
        }
        PartCatalog.fromJson(texts)
    }
}
