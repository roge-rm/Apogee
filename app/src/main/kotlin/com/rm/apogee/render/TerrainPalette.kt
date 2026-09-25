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
    ) {
        var r: Float; var g: Float; var b: Float
        when (material) {
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
        }
        // +-6% brightness, fixed per vertex. Hashed rather than random so the
        // same ground looks the same every time it is built.
        val jitter = 0.94f + 0.12f * Noise.hash(JITTER_SEED, jitterKey, 0, 0).toFloat()
        out[offset] = r * jitter
        out[offset + 1] = g * jitter
        out[offset + 2] = b * jitter
    }

    /** Sea over ground [depth] metres down: lighter in the shallows. */
    fun water(depth: Double, out: FloatArray, offset: Int) {
        val t = (depth / 900.0).coerceIn(0.0, 1.0).toFloat()
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
}
