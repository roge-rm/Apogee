package com.rm.apogee.render

import com.rm.apogee.core.math.Vec3
import com.rm.apogee.core.weather.DustKind
import com.rm.apogee.core.weather.LiveDust
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.pow
import kotlin.math.sin

/**
 * Dust storms seen from high up and on the map: each haboob as a brown sheet over the ground
 * from its face back to where it began, thickest just behind the face, and a world storm as a
 * veil over the part of the world it covers.
 *
 * Built as a [CloudShell] (body radii, then colour), drawn like [HurricaneVeil].
 */
object DustVeil {
    /** Points across a haboob's face and back from it. */
    const val ACROSS = 17
    const val BACK = 10

    /** A world storm's sphere: latitude rings, longitude twice as many. */
    const val RINGS = 48

    /** How high a world storm's veil floats, metres, at most this share of the radius. */
    const val WORLD_HEIGHT = 4_000.0
    private const val LIFT = 0.01

    /**
     * The veil for [dust] on a world of [radius] metres, or null for none. [dryness] gives how
     * much dust the ground gives at a unit direction, [ground] its height over the datum, and
     * [worldCover] a world storm's cover there (null for none), coloured [colour].
     */
    fun build(
        dust: List<LiveDust>,
        radius: Double,
        revision: Int,
        dryness: (Vec3) -> Double,
        ground: (Vec3) -> Double,
        worldCover: ((Vec3) -> Double)?,
        colour: FloatArray,
    ): CloudShell? {
        val haboobs = dust.filter { it.kind == DustKind.HABOOB && it.strength > 0.01 }
        val world = worldCover != null && dust.any { it.kind == DustKind.WORLD && it.strength > 0.01 }
        if (haboobs.isEmpty() && !world) return null
        val perOne = ACROSS * BACK
        val segments = RINGS * 2
        val sphere = if (world) (RINGS + 1) * (segments + 1) else 0
        val vertices = FloatArray((haboobs.size * perOne + sphere) * CloudShell.STRIDE)
        val indices = IntArray(haboobs.size * (ACROSS - 1) * (BACK - 1) * 6 + (if (world) RINGS * segments * 6 else 0))
        var v = 0
        var k = 0
        val p = Vec3()
        val right = Vec3()
        for ((n, d) in haboobs.withIndex()) {
            right.setTo(d.heading).crossInPlace(d.centre).normalizeInPlace()
            val half = d.halfWidth
            // From a little ahead of the face back to where it began.
            val travelled = d.centre.distanceTo(d.origin) * radius
            val length = travelled + 2_000.0
            val first = n * perOne
            for (row in 0 until BACK) {
                val x = 600.0 - (length + 600.0) * (row.toDouble() / (BACK - 1)).pow(1.6)
                val behind = -x
                val face = 1.0 - smooth(-150.0, 250.0, x)
                val body = (0.35 + 0.65 * exp(-kotlin.math.max(behind, 0.0) / LiveDust.THICK)) * (1.0 - smooth(0.75, 1.0, behind / length))
                for (col in 0 until ACROSS) {
                    val q = -1.0 + 2.0 * col / (ACROSS - 1)
                    val back = LiveDust.BOW * half * q * q - x
                    p.setTo(d.centre).addScaledInPlace(right, q * half / radius).addScaledInPlace(d.heading, -back / radius).normalizeInPlace()
                    val edge = 1.0 - smooth(0.75, 1.0, kotlin.math.abs(q))
                    val thick = (d.strength * edge * face * body * dryness(p)).coerceIn(0.0, 1.0)
                    val lift = 1.0 + (ground(p) + 0.6 * d.height) / radius
                    vertices[v] = (p.x * lift).toFloat()
                    vertices[v + 1] = (p.y * lift).toFloat()
                    vertices[v + 2] = (p.z * lift).toFloat()
                    vertices[v + 3] = colour[0]
                    vertices[v + 4] = colour[1]
                    vertices[v + 5] = colour[2]
                    vertices[v + 6] = (0.9 * kotlin.math.sqrt(thick)).toFloat()
                    v += CloudShell.STRIDE
                }
            }
            for (row in 0 until BACK - 1) for (col in 0 until ACROSS - 1) {
                val a = first + row * ACROSS + col
                val b = a + ACROSS
                indices[k++] = a; indices[k++] = a + 1; indices[k++] = b
                indices[k++] = a + 1; indices[k++] = b + 1; indices[k++] = b
            }
        }
        if (world) {
            val first = haboobs.size * perOne
            val lift = 1.0 + minOf(WORLD_HEIGHT / radius, LIFT)
            val direction = Vec3()
            for (ring in 0..RINGS) {
                val phi = PI * ring / RINGS
                val y = cos(phi); val across = sin(phi)
                for (segment in 0..segments) {
                    val theta = 2.0 * PI * segment / segments
                    direction.setTo(across * cos(theta), y, across * sin(theta))
                    val cover = worldCover!!(direction)
                    vertices[v] = (direction.x * lift).toFloat()
                    vertices[v + 1] = (direction.y * lift).toFloat()
                    vertices[v + 2] = (direction.z * lift).toFloat()
                    vertices[v + 3] = colour[0]
                    vertices[v + 4] = colour[1]
                    vertices[v + 5] = colour[2]
                    vertices[v + 6] = (0.93 * smooth(0.05, 0.6, cover)).toFloat()
                    v += CloudShell.STRIDE
                }
            }
            for (ring in 0 until RINGS) for (segment in 0 until segments) {
                val a = first + ring * (segments + 1) + segment
                val b = a + segments + 1
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
