package com.rm.apogee.render

import com.rm.apogee.core.math.Vec3
import com.rm.apogee.core.weather.LiveEruption

/**
 * Volcanic ash seen from high up and on the map: each eruption's cloud as a grey-brown ribbon
 * along its track, bending with it, widening downwind as the cloud does and thinning toward its
 * far end.
 *
 * Built as a [CloudShell] (body radii, then colour), drawn like [HurricaneVeil].
 */
object AshVeil {
    /** Points along a cloud's track and across it. */
    const val ALONG = 24
    const val ACROSS = 9

    /** The veil's colour, rgb: lighter than the ash itself, since the veil's shader dims it with the light. */
    val COLOUR = floatArrayOf(0.62f, 0.56f, 0.50f)

    /**
     * The veil for [eruptions] on a world of [radius] metres, or null for none. [startWidth] is a
     * cloud's half width at the vent, metres, and [ground] the ground's height over the datum at a
     * unit direction.
     */
    fun build(
        eruptions: List<LiveEruption>,
        radius: Double,
        revision: Int,
        startWidth: Double,
        ground: (Vec3) -> Double,
    ): CloudShell? {
        val live = eruptions.filter { it.strength > 0.01 && it.length > it.near + 1_000.0 }
        if (live.isEmpty()) return null
        val perOne = ALONG * ACROSS
        val vertices = FloatArray(live.size * perOne * CloudShell.STRIDE)
        val indices = IntArray(live.size * (ALONG - 1) * (ACROSS - 1) * 6)
        var v = 0
        var k = 0
        val centre = Vec3()
        val side = Vec3()
        val p = Vec3()
        for ((n, e) in live.withIndex()) {
            val first = n * perOne
            val rise = (e.top - e.ventHeight).coerceAtLeast(1_000.0)
            for (i in 0 until ALONG) {
                val f = i.toDouble() / (ALONG - 1)
                val s = e.near + (e.length - e.near) * f
                val share = s / e.length
                e.track.pointAt(s, centre)
                e.track.sideAt(s, side)
                // As wide as the cloud there, out a little past its edge where it thins away.
                val half = startWidth + (e.halfWidth - startWidth).coerceAtLeast(0.0) * share
                // Thin where it starts, thinning out downwind as the ash falls out, gone at the far end.
                val along = smooth(0.0, 0.08, f) * (1.0 - smooth(0.75, 1.0, f)) * (1.0 - 0.5 * share)
                // Its top settles as it goes.
                val height = e.top - 0.3 * rise * share
                for (j in 0 until ACROSS) {
                    val r = 1.1 * (2.0 * j / (ACROSS - 1) - 1.0)
                    p.setTo(centre).addScaledInPlace(side, r * half / radius).normalizeInPlace()
                    val thick = (e.strength * along * (1.0 - smooth(0.5, 1.1, kotlin.math.abs(r)))).coerceIn(0.0, 1.0)
                    val lift = 1.0 + kotlin.math.max(height, ground(p) + 500.0) / radius
                    vertices[v] = (p.x * lift).toFloat()
                    vertices[v + 1] = (p.y * lift).toFloat()
                    vertices[v + 2] = (p.z * lift).toFloat()
                    vertices[v + 3] = COLOUR[0]
                    vertices[v + 4] = COLOUR[1]
                    vertices[v + 5] = COLOUR[2]
                    vertices[v + 6] = (0.8 * kotlin.math.sqrt(thick)).toFloat()
                    v += CloudShell.STRIDE
                }
            }
            for (i in 0 until ALONG - 1) for (j in 0 until ACROSS - 1) {
                val a = first + i * ACROSS + j
                val b = a + ACROSS
                indices[k++] = a; indices[k++] = a + 1; indices[k++] = b
                indices[k++] = a + 1; indices[k++] = b + 1; indices[k++] = b
            }
        }
        return CloudShell(vertices, indices, revision)
    }

    private fun smooth(a: Double, b: Double, x: Double): Double {
        val t = ((x - a) / (b - a)).coerceIn(0.0, 1.0)
        return t * t * (3 - 2 * t)
    }
}
