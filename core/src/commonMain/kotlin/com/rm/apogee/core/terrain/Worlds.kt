package com.rm.apogee.core.terrain

import com.rm.apogee.core.concurrentMapOf
import com.rm.apogee.core.putIfAbsentShared

/**
 * The ground of every world beyond Terra and Luna, made once and shared across the process. The
 * terrain never changes and its tile cache is thread-safe.
 */
object Worlds {
    private val made = concurrentMapOf<String, Terrain>()

    /** World [id]'s ground with radius [radius], or null for one without any, like a gas giant. */
    fun terrain(id: String, radius: Double): Terrain? {
        made[id]?.let { return it }
        val seed = Noise.hashInt(SEED, id.hashCode(), 0, 0)
        val field = when (id) {
            "celer" -> WorldField(radius, 5_000.0, CelerLand(seed, radius), world = id)
            "caligo" -> WorldField(radius, 10_000.0, CaligoLand(seed, radius), world = id)
            "rubra" -> WorldField(radius, 22_000.0, RubraLand(seed, radius), world = id)
            "timor" -> WorldField(radius, radius * 0.6, LumpLand(seed, radius, 0.25, bigCrater = true), world = id)
            "pavor" -> WorldField(radius, radius * 0.4, LumpLand(seed, radius, 0.15, bigCrater = false), world = id)
            "fornax" -> WorldField(radius, 10_000.0, FornaxLand(seed, radius), world = id)
            "crusta" -> WorldField(radius, 800.0, CrustaLand(seed, radius), world = id)
            "maxima" -> WorldField(radius, 3_000.0, MaximaLand(seed, radius), world = id)
            "cicatrix" -> WorldField(radius, 3_000.0, CicatrixLand(seed, radius), world = id)
            "aurantia" -> WorldField(radius, 2_000.0, AurantiaLand(seed, radius), hasSea = true, world = id)
            "fons" -> WorldField(radius, 900.0, FonsLand(seed, radius), world = id)
            "aversa" -> WorldField(radius, 1_600.0, AversaLand(seed, radius), world = id)
            "ultima" -> WorldField(radius, 8_000.0, UltimaLand(seed, radius), world = id)
            "portitor" -> WorldField(radius, 3_000.0, PortitorLand(seed, radius), world = id)
            else -> return null
        }
        return made.putIfAbsentShared(id, field) ?: field
    }

    /** World [id]'s sea, if it has one: Aurantia's liquid methane, less than half as dense as water. */
    fun ocean(id: String): Ocean? = when (id) {
        "aurantia" -> Ocean(density = 450.0)
        else -> null
    }

    private const val SEED = 0x5EED_0B

    /** A test site: straight onto a world's landmark without flying there. Latitude and longitude in radians. */
    class Site(val id: String, val name: String, val bodyId: String, val latitude: Double, val longitude: Double)

    private fun site(id: String, name: String, body: String, lat: Double, lon: Double) =
        Site(id, "$name (test)", body, com.rm.apogee.core.math.Math.toRadians(lat), com.rm.apogee.core.math.Math.toRadians(lon))

    /**
     * Every world's test sites, each on a flat patch near its landmark, found by looking. The
     * ground round them keeps its shape. See [Quiet].
     */
    val SITES: List<Site> = listOf(
        // On Luna's mare, where the ground under all the pads slopes under one in a hundred.
        Site("luna-mare", "Luna Mare (test)", "luna", 0.131822, 0.131733),
        site("celer-basin", "Celer Great Basin", "celer", 28.0, 165.0),
        site("caligo-ishtar", "Caligo Ishtar", "caligo", 63.25, 21.85),
        site("rubra-rift", "Rubra Rift", "rubra", -9.0, -74.55),
        site("rubra-mount", "Rubra Great Mount foot", "rubra", 17.1, -120.3),
        site("timor", "Timor", "timor", 0.0, 20.0),
        site("pavor", "Pavor", "pavor", 0.0, 0.0),
        site("fornax-lake", "Fornax lava lake shore", "fornax", -12.0, 58.0),
        site("crusta-lineae", "Crusta crossing", "crusta", 5.0, 0.0),
        site("maxima-grooves", "Maxima grooves", "maxima", 10.0, 35.0),
        site("cicatrix-scar", "Cicatrix Great Scar", "cicatrix", 15.0, -60.0),
        site("aurantia-dunes", "Aurantia dunes", "aurantia", 4.1, -39.55),
        site("aurantia-sea", "Aurantia north sea", "aurantia", 82.0, 20.0),
        site("fons-stripes", "Fons Stripes", "fons", -84.0, 0.0),
        site("aversa-cap", "Aversa polar cap", "aversa", -50.0, 30.0),
        site("ultima-heart", "Ultima Heart", "ultima", 14.4, 177.9),
        site("portitor-belt", "Portitor Belt", "portitor", -0.45, 0.75),
    )

    /** A place the close-up relief leaves alone: flat to [flat] metres, blending in by [blend] more. */
    class Spot(val direction: DoubleArray, val flat: Double, val blend: Double)

    /** Where a prop lies on [bodyId]: latitude and longitude in degrees. */
    class PropSpot(val bodyId: String, val latitude: Double, val longitude: Double) {
        val direction: DoubleArray get() = Landforms.at(latitude, longitude)
    }

    /** Where each prop on another world lies, by its place's id. The ground there keeps its shape. */
    val PROPS: Map<String, PropSpot> = mapOf(
        "first-lander" to PropSpot("luna", 8.05, 7.6),
        "old-probe" to PropSpot("celer", 28.4, 165.3),
        "old-rover" to PropSpot("rubra", -9.3, -74.3),
        "the-window" to PropSpot("rubra", 17.4, -120.0),
        "ice-probe" to PropSpot("crusta", 5.8, 0.4),
        "lost-probe" to PropSpot("ultima", 13.9, 178.4),
    )

    /** How far round a prop the ground keeps its shape. */
    const val PROP_FLAT = 40.0

    /** Props that need the ground they were placed on, by world: unit direction and metres kept flat. */
    val KEEP_CLEAR: Map<String, List<Pair<DoubleArray, Double>>> =
        PROPS.values.groupBy { it.bodyId }.mapValues { (_, spots) -> spots.map { it.direction to PROP_FLAT } }

    /** How far round a test site the ground keeps its shape, and how far it takes to blend in. */
    const val SITE_FLAT = 250.0
    const val SITE_BLEND = 250.0

    /** How far round a test site and a prop nothing is scattered. */
    const val KEPT_CLEAR = 300.0

    /** Every spot on world [id] the close-up relief leaves alone. */
    fun spots(id: String): List<Spot> =
        SITES.filter { it.bodyId == id }.map {
            val la = it.latitude; val lo = it.longitude
            Spot(doubleArrayOf(kotlin.math.cos(la) * kotlin.math.cos(lo), kotlin.math.sin(la), kotlin.math.cos(la) * kotlin.math.sin(lo)), SITE_FLAT, SITE_BLEND)
        } + (KEEP_CLEAR[id] ?: emptyList()).map { (d, flat) -> Spot(d, flat, flat) }
}
