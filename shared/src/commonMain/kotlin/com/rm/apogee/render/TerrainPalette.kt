package com.rm.apogee.render

import com.rm.apogee.core.terrain.Noise
import com.rm.apogee.core.terrain.SurfaceMaterial

/**
 * What each kind of ground looks like, set on the CPU when a mesh is built. Keyed by
 * [SurfaceMaterial], the same thing the collider grips by, so ground that looks like ice is ice.
 * A small palette with hard edges, plus a little hashed variation per vertex.
 */
object TerrainPalette {

    /** Writes the colour of a vertex into [out] at [offset] (rgb). */
    fun colour(
        material: SurfaceMaterial,
        elevation: Double,
        jitterKey: Int,
        out: FloatArray,
        offset: Int,
        /** Whose ground it is, because each world's rock and ice has its own tint. */
        world: String = "terra",
    ) {
        var r: Float; var g: Float; var b: Float
        val own = WORLD_COLOURS[world]?.get(material)
        if (material == SurfaceMaterial.LAYERED_ROCK) {
            // Bands by height, so a cliff shows its layers, each facet one colour.
            val tones = BANDS[world] ?: BANDS.getValue("")
            val band = kotlin.math.floor(elevation / (BAND_HEIGHT[world] ?: 30.0)).toInt()
            val t = tones[(Noise.hashInt(JITTER_SEED + 3, band, 0, 0) ushr 4) % tones.size]
            r = t[0]; g = t[1]; b = t[2]
        } else if (own != null) {
            r = own[0]; g = own[1]; b = own[2]
        } else when (material) {
            SurfaceMaterial.GRASS -> {
                // Three shades by height: lowland, meadow and upland.
                when {
                    elevation < 220.0 -> { r = 0.22f; g = 0.42f; b = 0.18f }
                    elevation < 520.0 -> { r = 0.30f; g = 0.46f; b = 0.20f }
                    else -> { r = 0.35f; g = 0.40f; b = 0.21f }
                }
            }
            SurfaceMaterial.DIRT -> { r = 0.48f; g = 0.44f; b = 0.27f }
            SurfaceMaterial.SAND -> { r = 0.72f; g = 0.66f; b = 0.46f }
            SurfaceMaterial.ROCK -> { r = 0.38f; g = 0.35f; b = 0.32f }
            SurfaceMaterial.SCREE -> { r = 0.50f; g = 0.47f; b = 0.43f }
            SurfaceMaterial.MUD -> { r = 0.31f; g = 0.26f; b = 0.19f }
            SurfaceMaterial.SNOW -> { r = 0.92f; g = 0.94f; b = 0.97f }
            SurfaceMaterial.ICE -> { r = 0.78f; g = 0.87f; b = 0.95f }
            SurfaceMaterial.REGOLITH -> { r = 0.56f; g = 0.55f; b = 0.53f }
            SurfaceMaterial.BASALT -> { r = 0.26f; g = 0.26f; b = 0.28f }
            SurfaceMaterial.CLAY -> { r = 0.64f; g = 0.37f; b = 0.23f }
            SurfaceMaterial.FOREST -> { r = 0.15f; g = 0.31f; b = 0.14f }
            // The launch complex: pale poured concrete, and a dark runway.
            SurfaceMaterial.CONCRETE -> { r = 0.66f; g = 0.65f; b = 0.62f }
            SurfaceMaterial.ASPHALT -> { r = 0.17f; g = 0.17f; b = 0.18f }
            // The other worlds' own ground.
            SurfaceMaterial.RED_DUST -> { r = 0.62f; g = 0.33f; b = 0.18f }
            SurfaceMaterial.SULFUR -> {
                // Yellow to orange, patch by patch.
                val t = Noise.hash(JITTER_SEED + 1, jitterKey, 0, 0).toFloat()
                r = 0.86f; g = 0.76f - 0.22f * t; b = 0.30f - 0.10f * t
            }
            SurfaceMaterial.LAVA -> { r = 1.0f; g = 0.36f; b = 0.08f }
            SurfaceMaterial.ORGANIC_SAND -> { r = 0.30f; g = 0.24f; b = 0.16f }
            SurfaceMaterial.NITROGEN_ICE -> { r = 0.93f; g = 0.90f; b = 0.86f }
            SurfaceMaterial.THOLIN -> { r = 0.40f; g = 0.20f; b = 0.12f }
            SurfaceMaterial.TESSERA -> { r = 0.46f; g = 0.38f; b = 0.28f }
            // The deep sea floor: pale ooze, dark nodules, and rust-and-black vent crust.
            SurfaceMaterial.OOZE -> { r = 0.58f; g = 0.56f; b = 0.50f }
            SurfaceMaterial.NODULES -> { r = 0.30f; g = 0.28f; b = 0.26f }
            SurfaceMaterial.VENT_CRUST -> {
                val t = Noise.hash(JITTER_SEED + 2, jitterKey, 0, 0).toFloat()
                r = 0.45f - 0.28f * t; g = 0.24f - 0.14f * t; b = 0.14f - 0.07f * t
            }
            SurfaceMaterial.FROST -> { r = 0.86f; g = 0.88f; b = 0.92f }
            SurfaceMaterial.EJECTA -> { r = 0.74f; g = 0.73f; b = 0.70f }
            SurfaceMaterial.DARK_SAND -> { r = 0.20f; g = 0.19f; b = 0.18f }
            SurfaceMaterial.LAYERED_ROCK -> { r = 0.50f; g = 0.44f; b = 0.36f }
            SurfaceMaterial.FLOW_ROCK -> { r = 0.20f; g = 0.19f; b = 0.20f }
            SurfaceMaterial.RED_SULFUR -> { r = 0.70f; g = 0.26f; b = 0.12f }
            SurfaceMaterial.VENT_ICE -> { r = 0.70f; g = 0.84f; b = 0.95f }
            SurfaceMaterial.SALT -> { r = 0.80f; g = 0.78f; b = 0.72f }
        }
        // +-6% brightness per vertex, hashed so it's the same every build.
        val jitter = 0.94f + 0.12f * Noise.hash(JITTER_SEED, jitterKey, 0, 0).toFloat()
        out[offset] = r * jitter
        out[offset + 1] = g * jitter
        out[offset + 2] = b * jitter
    }

    /** Sea over ground [depth] metres down, lighter in the shallows. */
    fun water(depth: Double, out: FloatArray, offset: Int, world: String = "terra") {
        val t = (depth / 900.0).coerceIn(0.0, 1.0).toFloat()
        if (world == "aurantia") {
            // Liquid methane: dark, brown and glassy.
            out[offset] = 0.16f + (0.07f - 0.16f) * t
            out[offset + 1] = 0.12f + (0.05f - 0.12f) * t
            out[offset + 2] = 0.07f + (0.03f - 0.07f) * t
            return
        }
        out[offset] = 0.10f + (0.02f - 0.10f) * t
        out[offset + 1] = 0.30f + (0.09f - 0.30f) * t
        out[offset + 2] = 0.46f + (0.22f - 0.46f) * t
    }

    private const val JITTER_SEED = 0x7E11A

    private fun rgb(r: Float, g: Float, b: Float) = floatArrayOf(r, g, b)

    /** Layered rock's bands, by world. "" is for any world without its own. */
    private val BANDS: Map<String, Array<FloatArray>> = mapOf(
        "" to arrayOf(rgb(0.50f, 0.44f, 0.36f), rgb(0.40f, 0.34f, 0.27f), rgb(0.58f, 0.52f, 0.42f)),
        "rubra" to arrayOf(rgb(0.56f, 0.30f, 0.18f), rgb(0.44f, 0.24f, 0.15f), rgb(0.66f, 0.42f, 0.28f), rgb(0.50f, 0.34f, 0.24f)),
        "fornax" to arrayOf(rgb(0.78f, 0.62f, 0.26f), rgb(0.52f, 0.34f, 0.16f), rgb(0.30f, 0.22f, 0.14f), rgb(0.86f, 0.74f, 0.36f)),
        "portitor" to arrayOf(rgb(0.46f, 0.46f, 0.46f), rgb(0.36f, 0.35f, 0.35f), rgb(0.54f, 0.53f, 0.52f)),
    )

    /** How tall each band of layered rock is, in metres, by world. */
    private val BAND_HEIGHT: Map<String, Double> = mapOf("fornax" to 20.0, "portitor" to 40.0)

    /** Worlds whose ground differs in colour from Terra's and Luna's. */
    private val WORLD_COLOURS: Map<String, Map<SurfaceMaterial, FloatArray>> = mapOf(
        "celer" to mapOf(
            SurfaceMaterial.EJECTA to rgb(0.62f, 0.64f, 0.70f),
            SurfaceMaterial.REGOLITH to rgb(0.42f, 0.40f, 0.38f),
            SurfaceMaterial.ROCK to rgb(0.34f, 0.32f, 0.30f),
            SurfaceMaterial.BASALT to rgb(0.30f, 0.29f, 0.28f),
        ),
        "caligo" to mapOf(
            SurfaceMaterial.FLOW_ROCK to rgb(0.22f, 0.17f, 0.13f),
            SurfaceMaterial.FROST to rgb(0.70f, 0.70f, 0.72f),
            SurfaceMaterial.DARK_SAND to rgb(0.26f, 0.20f, 0.14f),
            SurfaceMaterial.BASALT to rgb(0.36f, 0.28f, 0.20f),
            SurfaceMaterial.ROCK to rgb(0.42f, 0.34f, 0.24f),
            SurfaceMaterial.REGOLITH to rgb(0.48f, 0.38f, 0.26f),
        ),
        "rubra" to mapOf(
            SurfaceMaterial.DARK_SAND to rgb(0.30f, 0.20f, 0.16f),
            SurfaceMaterial.SALT to rgb(0.78f, 0.70f, 0.62f),
            SurfaceMaterial.ROCK to rgb(0.45f, 0.25f, 0.15f),
            SurfaceMaterial.SCREE to rgb(0.55f, 0.32f, 0.20f),
            SurfaceMaterial.BASALT to rgb(0.30f, 0.20f, 0.16f),
            SurfaceMaterial.REGOLITH to rgb(0.55f, 0.30f, 0.18f),
            SurfaceMaterial.SNOW to rgb(0.94f, 0.92f, 0.90f),
            SurfaceMaterial.ICE to rgb(0.88f, 0.86f, 0.84f),
        ),
        "timor" to mapOf(
            SurfaceMaterial.REGOLITH to rgb(0.30f, 0.28f, 0.26f),
            SurfaceMaterial.ROCK to rgb(0.26f, 0.24f, 0.22f),
        ),
        "pavor" to mapOf(
            SurfaceMaterial.REGOLITH to rgb(0.32f, 0.29f, 0.26f),
            SurfaceMaterial.ROCK to rgb(0.27f, 0.25f, 0.23f),
        ),
        "fornax" to mapOf(
            SurfaceMaterial.FROST to rgb(0.92f, 0.90f, 0.82f),
            SurfaceMaterial.FLOW_ROCK to rgb(0.12f, 0.10f, 0.08f),
            SurfaceMaterial.BASALT to rgb(0.18f, 0.14f, 0.10f),
            SurfaceMaterial.ROCK to rgb(0.55f, 0.40f, 0.22f),
        ),
        "crusta" to mapOf(
            SurfaceMaterial.SALT to rgb(0.66f, 0.70f, 0.74f),
            SurfaceMaterial.ICE to rgb(0.86f, 0.84f, 0.78f),
            SurfaceMaterial.SNOW to rgb(0.93f, 0.92f, 0.88f),
        ),
        "maxima" to mapOf(
            SurfaceMaterial.DARK_SAND to rgb(0.26f, 0.24f, 0.21f),
            SurfaceMaterial.EJECTA to rgb(0.80f, 0.79f, 0.76f),
            SurfaceMaterial.REGOLITH to rgb(0.40f, 0.37f, 0.33f),
            SurfaceMaterial.ICE to rgb(0.72f, 0.70f, 0.66f),
        ),
        "cicatrix" to mapOf(
            SurfaceMaterial.DARK_SAND to rgb(0.14f, 0.13f, 0.12f),
            SurfaceMaterial.FROST to rgb(0.80f, 0.80f, 0.80f),
            SurfaceMaterial.REGOLITH to rgb(0.26f, 0.24f, 0.22f),
            SurfaceMaterial.ROCK to rgb(0.28f, 0.26f, 0.24f),
            SurfaceMaterial.SNOW to rgb(0.62f, 0.62f, 0.60f),
            SurfaceMaterial.ICE to rgb(0.70f, 0.70f, 0.68f),
        ),
        "aurantia" to mapOf(
            SurfaceMaterial.SALT to rgb(0.62f, 0.50f, 0.32f),
            SurfaceMaterial.ROCK to rgb(0.40f, 0.30f, 0.18f),
            SurfaceMaterial.REGOLITH to rgb(0.45f, 0.36f, 0.22f),
            SurfaceMaterial.ICE to rgb(0.55f, 0.45f, 0.30f),
            SurfaceMaterial.SNOW to rgb(0.62f, 0.52f, 0.36f),
        ),
        "fons" to mapOf(
            SurfaceMaterial.VENT_ICE to rgb(0.78f, 0.90f, 1.0f),
            SurfaceMaterial.ICE to rgb(0.96f, 0.97f, 0.99f),
            SurfaceMaterial.SNOW to rgb(0.98f, 0.98f, 1.0f),
        ),
        "aversa" to mapOf(
            SurfaceMaterial.VENT_ICE to rgb(0.76f, 0.80f, 0.86f),
            SurfaceMaterial.FROST to rgb(0.96f, 0.90f, 0.90f),
            SurfaceMaterial.NITROGEN_ICE to rgb(0.88f, 0.78f, 0.76f),
            SurfaceMaterial.ICE to rgb(0.70f, 0.68f, 0.66f),
            SurfaceMaterial.ROCK to rgb(0.42f, 0.40f, 0.40f),
        ),
        "ultima" to mapOf(
            SurfaceMaterial.FROST to rgb(0.94f, 0.94f, 0.95f),
            SurfaceMaterial.ICE to rgb(0.80f, 0.76f, 0.70f),
            SurfaceMaterial.ROCK to rgb(0.52f, 0.44f, 0.38f),
        ),
        "portitor" to mapOf(
            SurfaceMaterial.EJECTA to rgb(0.76f, 0.76f, 0.78f),
            SurfaceMaterial.REGOLITH to rgb(0.44f, 0.44f, 0.44f),
            SurfaceMaterial.THOLIN to rgb(0.36f, 0.14f, 0.10f),
        ),
    )
}
