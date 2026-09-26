package com.rm.apogee.render

import com.rm.apogee.core.terrain.Noise
import com.rm.apogee.core.terrain.SurfaceMaterial

/**
 * What each kind of ground looks like, decided on the CPU when a mesh is built.
 *
 * Colour used to be worked out per pixel in the terrain shader from height and
 * slope. It lives here now, keyed by [SurfaceMaterial], because the material is
 * what the collider grips by: ground that looks like ice has to *be* ice, and
 * the only way to guarantee that is one classification feeding both.
 *
 * Deliberately few colours with hard edges between them - a low-poly look is
 * as much a small palette as it is big facets - plus a small hashed variation
 * per vertex, which is what stops a hillside of identical triangles reading as
 * a flat sheet of paint.
 */
object TerrainPalette {

    /** Writes the colour of a vertex into [out] at [offset] (rgb). */
    fun colour(
        material: SurfaceMaterial,
        elevation: Double,
        jitterKey: Int,
        out: FloatArray,
        offset: Int,
        /** Whose ground: each world's rock and ice has its own cast. */
        world: String = "terra",
    ) {
        var r: Float; var g: Float; var b: Float
        val own = WORLD_COLOURS[world]?.get(material)
        if (own != null) {
            r = own[0]; g = own[1]; b = own[2]
        } else when (material) {
            SurfaceMaterial.GRASS -> {
                // Three shades by height, as the shader had: lowland grass,
                // meadow, upland.
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
        }
        // +-6% brightness, fixed per vertex. Hashed rather than random so the
        // same ground looks the same every time it is built.
        val jitter = 0.94f + 0.12f * Noise.hash(JITTER_SEED, jitterKey, 0, 0).toFloat()
        out[offset] = r * jitter
        out[offset + 1] = g * jitter
        out[offset + 2] = b * jitter
    }

    /** Sea over ground [depth] metres down: lighter in the shallows. */
    fun water(depth: Double, out: FloatArray, offset: Int, world: String = "terra") {
        val t = (depth / 900.0).coerceIn(0.0, 1.0).toFloat()
        if (world == "aurantia") {
            // Liquid methane: dark, brown, glassy.
            out[offset] = 0.16f + (0.07f - 0.16f) * t
            out[offset + 1] = 0.12f + (0.05f - 0.12f) * t
            out[offset + 2] = 0.07f + (0.03f - 0.07f) * t
            return
        }
        out[offset] = 0.10f + (0.02f - 0.10f) * t
        out[offset + 1] = 0.30f + (0.09f - 0.30f) * t
        out[offset + 2] = 0.46f + (0.22f - 0.46f) * t
    }

    /**
     * The sea bed at [depth] m, seen through the water: pale sand in the
     * shallows, weed-dark further down, lost to the deep beyond.
     */
    fun seabed(depth: Double, jitterKey: Int, out: FloatArray, offset: Int) {
        val shallow = (depth / 12.0).coerceIn(0.0, 1.0)
        val deep = ((depth - 12.0) / 250.0).coerceIn(0.0, 1.0)
        var r = 0.78 + (0.40 - 0.78) * shallow
        var g = 0.72 + (0.46 - 0.72) * shallow
        var b = 0.52 + (0.40 - 0.52) * shallow
        r += (0.10 - r) * deep; g += (0.18 - g) * deep; b += (0.22 - b) * deep
        val j = 1.0 + (((jitterKey * -0x61c88647) ushr 24) and 0xFF) / 255.0 * 0.12 - 0.06
        out[offset] = (r * j).toFloat()
        out[offset + 1] = (g * j).toFloat()
        out[offset + 2] = (b * j).toFloat()
    }

    private const val JITTER_SEED = 0x7E11A

    private fun rgb(r: Float, g: Float, b: Float) = floatArrayOf(r, g, b)

    /**
     * Where a world's rock, dust or ice is not the colour Terra's and Luna's
     * is: Rubra's rock rusty, Cicatrix's regolith near black, Crusta's ice
     * cream, Aversa's nitrogen pink.
     */
    private val WORLD_COLOURS: Map<String, Map<SurfaceMaterial, FloatArray>> = mapOf(
        "celer" to mapOf(
            SurfaceMaterial.REGOLITH to rgb(0.42f, 0.40f, 0.38f),
            SurfaceMaterial.ROCK to rgb(0.34f, 0.32f, 0.30f),
            SurfaceMaterial.BASALT to rgb(0.30f, 0.29f, 0.28f),
        ),
        "caligo" to mapOf(
            SurfaceMaterial.BASALT to rgb(0.36f, 0.28f, 0.20f),
            SurfaceMaterial.ROCK to rgb(0.42f, 0.34f, 0.24f),
            SurfaceMaterial.REGOLITH to rgb(0.48f, 0.38f, 0.26f),
        ),
        "rubra" to mapOf(
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
            SurfaceMaterial.BASALT to rgb(0.18f, 0.14f, 0.10f),
            SurfaceMaterial.ROCK to rgb(0.55f, 0.40f, 0.22f),
        ),
        "crusta" to mapOf(
            SurfaceMaterial.ICE to rgb(0.86f, 0.84f, 0.78f),
            SurfaceMaterial.SNOW to rgb(0.93f, 0.92f, 0.88f),
        ),
        "maxima" to mapOf(
            SurfaceMaterial.REGOLITH to rgb(0.40f, 0.37f, 0.33f),
            SurfaceMaterial.ICE to rgb(0.72f, 0.70f, 0.66f),
        ),
        "cicatrix" to mapOf(
            SurfaceMaterial.REGOLITH to rgb(0.26f, 0.24f, 0.22f),
            SurfaceMaterial.ROCK to rgb(0.28f, 0.26f, 0.24f),
            SurfaceMaterial.SNOW to rgb(0.62f, 0.62f, 0.60f),
            SurfaceMaterial.ICE to rgb(0.70f, 0.70f, 0.68f),
        ),
        "aurantia" to mapOf(
            SurfaceMaterial.ROCK to rgb(0.40f, 0.30f, 0.18f),
            SurfaceMaterial.REGOLITH to rgb(0.45f, 0.36f, 0.22f),
            SurfaceMaterial.ICE to rgb(0.55f, 0.45f, 0.30f),
            SurfaceMaterial.SNOW to rgb(0.62f, 0.52f, 0.36f),
        ),
        "fons" to mapOf(
            SurfaceMaterial.ICE to rgb(0.96f, 0.97f, 0.99f),
            SurfaceMaterial.SNOW to rgb(0.98f, 0.98f, 1.0f),
        ),
        "aversa" to mapOf(
            SurfaceMaterial.NITROGEN_ICE to rgb(0.88f, 0.78f, 0.76f),
            SurfaceMaterial.ICE to rgb(0.70f, 0.68f, 0.66f),
            SurfaceMaterial.ROCK to rgb(0.42f, 0.40f, 0.40f),
        ),
        "ultima" to mapOf(
            SurfaceMaterial.ICE to rgb(0.80f, 0.76f, 0.70f),
            SurfaceMaterial.ROCK to rgb(0.52f, 0.44f, 0.38f),
        ),
        "portitor" to mapOf(
            SurfaceMaterial.REGOLITH to rgb(0.44f, 0.44f, 0.44f),
            SurfaceMaterial.THOLIN to rgb(0.36f, 0.14f, 0.10f),
        ),
    )
}
