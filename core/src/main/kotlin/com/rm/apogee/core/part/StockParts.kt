package com.rm.apogee.core.part

/**
 * Loads the shipped part catalogue from the classpath.
 *
 * Lives in :core's resources rather than in the app's Android assets so there
 * is exactly one copy: the app, the dedicated server and the unit tests all
 * load the same bytes and therefore compute the same [PartCatalog.contentHash].
 * Two copies of a catalogue is two catalogues, and the handshake would start
 * rejecting perfectly matched builds.
 */
object StockParts {

    private const val RESOURCE_PATH = "/parts/stock.json"

    /** Parsed once; the catalogue is immutable. */
    val catalog: PartCatalog by lazy {
        val text = StockParts::class.java.getResourceAsStream(RESOURCE_PATH)
            ?.bufferedReader()
            ?.use { it.readText() }
            ?: error(
                "Stock part catalogue missing from the classpath at $RESOURCE_PATH. " +
                    "It ships in :core's resources - check that module's jar is on the path."
            )
        PartCatalog.fromJson(listOf(text))
    }
}
