package com.rm.apogee.core.terrain

/**
 * The ground of every world beyond Terra and Luna, made once and shared by every world object in
 * the process, the same as Terra's and Luna's. The terrain never changes, and its tile cache is
 * thread-safe.
 */
object Worlds {
    private val made = java.util.concurrent.ConcurrentHashMap<String, Terrain>()

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
        return made.putIfAbsent(id, field) ?: field
    }

    /** World [id]'s sea, if it has one: Aurantia's liquid methane, less than half as dense as water. */
    fun ocean(id: String): Ocean? = when (id) {
        "aurantia" -> Ocean(density = 450.0)
        else -> null
    }

    private const val SEED = 0x5EED_0B
}
