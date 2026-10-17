package com.rm.apogee.render

import com.rm.apogee.core.math.Vec3
import com.rm.apogee.core.weather.LiveBlizzard
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

/**
 * Blizzards seen from high up and on the map: each one as a white oval sheet over the ground,
 * thickest in the middle, thinning where the country isn't cold.
 *
 * Built as a [CloudShell] (body radii, then colour), drawn like [HurricaneVeil].
 */
object SnowVeil {
    /** Rings out from the middle and points round each. */
    const val RINGS = 6
    const val AROUND = 24

    /** The veil's colour, rgb: past white, since the veil's shader dims what the sun lights side-on and snow stays bright. */
    val COLOUR = floatArrayOf(1.25f, 1.28f, 1.33f)

    /**
     * The veil for [blizzards] on a world of [radius] metres, or null for none. [cold] gives how
     * cold the country is at a unit direction, 0..1, and [ground] its height over the datum.
     */
    fun build(
        blizzards: List<LiveBlizzard>,
        radius: Double,
        revision: Int,
        cold: (Vec3) -> Double,
        ground: (Vec3) -> Double,
    ): CloudShell? {
        val live = blizzards.filter { it.strength > 0.01 }
        if (live.isEmpty()) return null
        val perOne = 1 + RINGS * AROUND
        val vertices = FloatArray(live.size * perOne * CloudShell.STRIDE)
        val indices = IntArray(live.size * (AROUND * 3 + (RINGS - 1) * AROUND * 6))
        var v = 0
        var k = 0
        val p = Vec3()
        val right = Vec3()
        for ((n, z) in live.withIndex()) {
            right.setTo(z.heading).crossInPlace(z.centre).normalizeInPlace()
            val first = n * perOne
            for (i in 0 until perOne) {
                val ring = if (i == 0) 0 else 1 + (i - 1) / AROUND
                val a = if (i == 0) 0.0 else 2.0 * PI * ((i - 1) % AROUND) / AROUND
                // Out to a little past the edge, where the snow thins away.
                val r = 1.1 * ring / RINGS
                p.setTo(z.centre)
                    .addScaledInPlace(z.heading, r * z.halfLength * cos(a) / radius)
                    .addScaledInPlace(right, r * z.halfWidth * sin(a) / radius)
                    .normalizeInPlace()
                val edge = 1.0 - smooth(0.6, 1.1, r)
                val thick = (z.strength * edge * cold(p)).coerceIn(0.0, 1.0)
                val lift = 1.0 + (ground(p) + 0.6 * z.height) / radius
                vertices[v] = (p.x * lift).toFloat()
                vertices[v + 1] = (p.y * lift).toFloat()
                vertices[v + 2] = (p.z * lift).toFloat()
                vertices[v + 3] = COLOUR[0]
                vertices[v + 4] = COLOUR[1]
                vertices[v + 5] = COLOUR[2]
                vertices[v + 6] = (0.7 * kotlin.math.sqrt(thick)).toFloat()
                v += CloudShell.STRIDE
            }
            for (j in 0 until AROUND) {
                indices[k++] = first; indices[k++] = first + 1 + j; indices[k++] = first + 1 + (j + 1) % AROUND
            }
            for (ring in 1 until RINGS) for (j in 0 until AROUND) {
                val a = first + 1 + (ring - 1) * AROUND + j
                val b = first + 1 + (ring - 1) * AROUND + (j + 1) % AROUND
                indices[k++] = a; indices[k++] = b; indices[k++] = a + AROUND
                indices[k++] = b; indices[k++] = b + AROUND; indices[k++] = a + AROUND
            }
        }
        return CloudShell(vertices, indices, revision)
    }

    private fun smooth(a: Double, b: Double, x: Double): Double {
        val t = ((x - a) / (b - a)).coerceIn(0.0, 1.0)
        return t * t * (3 - 2 * t)
    }
}
