package com.rm.apogee.render

import com.rm.apogee.core.math.Vec3
import com.rm.apogee.core.weather.LiveHurricane
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.pow
import kotlin.math.sin

/**
 * Hurricanes seen from high up and on the map: each a disc of cloud on rings round its eye, as
 * thick as its cloud is there, so the eye, the eyewall and the spiral bands show. The rings
 * crowd in toward the eye, where the detail is.
 *
 * Built as a [CloudShell] (body radii, then colour), a little above the map's veil. The eyewall
 * stands up as a rim round a deep eye, which the shader lights by its slope.
 */
object HurricaneVeil {
    /** Rings out from the eye and spokes round it, per hurricane. */
    const val RINGS = 32
    const val SPOKES = 72

    /**
     * How high its bands float, in metres, and its eyewall's top, both at most this share of the
     * world's radius. The eye goes down to [EYE_FLOOR], so the wall stands up round a deep hole.
     */
    const val HEIGHT = 7_000.0
    const val WALL = 15_000.0
    const val EYE_FLOOR = 1_000.0
    private const val LIFT = 0.025

    /** The veil for [hurricanes] on a world of [radius] metres, or null for none. */
    fun build(hurricanes: List<LiveHurricane>, radius: Double, revision: Int): CloudShell? {
        val shown = hurricanes.filter { it.wet > 0.0 }
        if (shown.isEmpty()) return null
        val perOne = (RINGS + 1) * (SPOKES + 1)
        val vertices = FloatArray(shown.size * perOne * CloudShell.STRIDE)
        val indices = IntArray(shown.size * RINGS * SPOKES * 6)
        // Heights squeezed together on a small world, so it doesn't stand off it.
        val squeeze = minOf(1.0, LIFT * radius / WALL)
        val p = Vec3()
        var v = 0
        var k = 0
        for ((n, h) in shown.withIndex()) {
            val wet = h.wet
            val rm = h.maxWindRadius
            val wallTop = WALL * (0.75 + 0.25 * h.strength)
            val first = n * perOne
            for (ring in 0..RINGS) {
                val r = h.outerRadius * (ring.toDouble() / RINGS).pow(1.5)
                // Down into the eye, up the wall, and out over the bands, lower toward the edge.
                val height = if (r < 1.15 * rm) {
                    EYE_FLOOR + (wallTop - EYE_FLOOR) * smooth(0.45 * rm, 1.1 * rm, r)
                } else {
                    val bands = HEIGHT * (0.6 + 0.4 * (1.0 - smooth(2.0 * rm, h.outerRadius, r)))
                    bands + (wallTop - bands) * kotlin.math.exp(-((r - 1.15 * rm) / (0.7 * rm)).let { it * it })
                }
                val lift = 1.0 + height * squeeze / radius
                // The inside of the wall is solid all the way down, and in shadow low in the hole.
                val face = if (r < 1.15 * rm) smooth(0.4 * rm, 0.8 * rm, r) else 0.0
                val deep = 0.5 + 0.5 * smooth(0.5 * rm, 1.15 * rm, r)
                for (spoke in 0..SPOKES) {
                    val bearing = 2.0 * PI * spoke / SPOKES
                    val ax = cos(bearing); val ay = sin(bearing)
                    h.pointAt(r, ax, ay, radius, p)
                    val cover = if (ring == RINGS) 0.0 else maxOf(face, h.cloudAt(r, ax, ay, radius))
                    // The eyewall's towers bright, the bands greyer round them.
                    val grey = (0.8 + 0.2 * cover) * deep
                    vertices[v] = (p.x * lift).toFloat()
                    vertices[v + 1] = (p.y * lift).toFloat()
                    vertices[v + 2] = (p.z * lift).toFloat()
                    vertices[v + 3] = (0.96 * grey).toFloat()
                    vertices[v + 4] = (0.97 * grey).toFloat()
                    vertices[v + 5] = grey.toFloat()
                    vertices[v + 6] = (cover * wet).toFloat()
                    v += CloudShell.STRIDE
                }
            }
            for (ring in 0 until RINGS) for (spoke in 0 until SPOKES) {
                val a = first + ring * (SPOKES + 1) + spoke
                val b = a + SPOKES + 1
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
