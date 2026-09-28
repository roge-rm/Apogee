package com.rm.apogee.render

import com.rm.apogee.core.math.Quat
import com.rm.apogee.core.math.Vec3
import com.rm.apogee.core.orbit.CelestialBody
import com.rm.apogee.core.part.ModelSpec
import com.rm.apogee.core.terrain.Noise
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.asin
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin
import com.rm.apogee.core.math.Math

/**
 * How the giants look: banded by latitude, torn along the bands' edges, each with its own mark
 * (Magna's great red storm, Aurea's hexagon round its north pole, Caerula's dark spot), and their
 * rings.
 *
 * There are two ways of drawing the same thing. [colour] is per vertex of the globe, when a giant
 * is the world a craft is at. [items] is a stack of banded rings of facets, for seeing one across
 * space from its moons.
 */
object GiantLook {

    private class Look(
        val light: FloatArray,
        val dark: FloatArray,
        val pole: FloatArray,
        /** Bands from pole to pole. */
        val bands: Int,
        /** How torn the bands' edges are, 0..1. */
        val turbulence: Double,
        /**
         * The storm: latitude, longitude (degrees), half-width and half-height (rad), and colour,
         * or null.
         */
        val spot: Spot?,
        val hexagon: Boolean = false,
        /** The rings' colours from the inside out, and how many gaps. */
        val ring: FloatArray? = null,
        val ringGap: Boolean = false,
    )

    private class Spot(val lat: Double, val lon: Double, val across: Double, val tall: Double, val colour: FloatArray)

    private fun rgb(r: Float, g: Float, b: Float) = floatArrayOf(r, g, b)

    private val LOOKS = mapOf(
        "magna" to Look(
            light = rgb(0.90f, 0.85f, 0.72f), dark = rgb(0.70f, 0.52f, 0.38f), pole = rgb(0.55f, 0.52f, 0.50f),
            bands = 16, turbulence = 0.8,
            spot = Spot(-22.0, 40.0, 0.22, 0.09, rgb(0.78f, 0.38f, 0.26f)),
            ring = rgb(0.40f, 0.36f, 0.32f),
        ),
        "aurea" to Look(
            light = rgb(0.92f, 0.84f, 0.60f), dark = rgb(0.80f, 0.68f, 0.44f), pole = rgb(0.52f, 0.58f, 0.62f),
            bands = 14, turbulence = 0.35, spot = null, hexagon = true,
            ring = rgb(0.86f, 0.80f, 0.66f), ringGap = true,
        ),
        "obliqua" to Look(
            light = rgb(0.66f, 0.87f, 0.90f), dark = rgb(0.60f, 0.82f, 0.86f), pole = rgb(0.70f, 0.88f, 0.90f),
            bands = 6, turbulence = 0.05, spot = null,
            ring = rgb(0.20f, 0.20f, 0.22f),
        ),
        "caerula" to Look(
            light = rgb(0.26f, 0.42f, 0.86f), dark = rgb(0.18f, 0.32f, 0.76f), pole = rgb(0.22f, 0.36f, 0.80f),
            bands = 8, turbulence = 0.25,
            spot = Spot(-20.0, 90.0, 0.16, 0.08, rgb(0.08f, 0.13f, 0.42f)),
        ),
    )

    /** Whether [bodyId] is a giant, drawn in bands instead of as ground. */
    fun isGiant(bodyId: String) = bodyId in LOOKS

    /** The colour at body-fixed unit [d] on giant [bodyId], into [out] at [o]. */
    fun colour(bodyId: String, d: Vec3, out: FloatArray, o: Int) {
        val look = LOOKS[bodyId] ?: return
        val lat = asin(d.y.coerceIn(-1.0, 1.0))
        val lon = atan2(d.z, d.x)
        // The bands' edges wander and tear, more on a stormy giant.
        val wobble = look.turbulence * 0.06 * Noise.simplex(bodyId.hashCode(), d.x * 6.0, d.y * 2.0, d.z * 6.0)
        val c = band(look, lat + wobble)
        var r = c[0]; var g = c[1]; var b = c[2]
        look.spot?.let { s ->
            val dl = (lat - Math.toRadians(s.lat)) / s.tall
            var dn = lon - Math.toRadians(s.lon)
            while (dn > PI) dn -= 2 * PI
            while (dn < -PI) dn += 2 * PI
            val q = dl * dl + (dn / s.across).let { it * it }
            if (q < 1.0) {
                val f = (1.0 - q).coerceAtMost(0.5).toFloat() * 2f
                r += (s.colour[0] - r) * f; g += (s.colour[1] - g) * f; b += (s.colour[2] - b) * f
            }
        }
        if (look.hexagon && lat > Math.toRadians(70.0)) {
            // A six-sided jet round the pole, with the pole inside it darker.
            val hex = cos(PI / 6) / cos(((lon % (PI / 3)) + PI / 3) % (PI / 3) - PI / 6)
            val edge = Math.toRadians(90.0 - 12.0 * hex)
            if (lat > edge) { r = look.pole[0]; g = look.pole[1]; b = look.pole[2] }
        }
        out[o] = r; out[o + 1] = g; out[o + 2] = b
    }

    private fun band(look: Look, lat: Double): FloatArray {
        val a = abs(lat)
        if (a > Math.toRadians(72.0)) return look.pole
        val k = ((lat + PI / 2) / PI * look.bands).toInt()
        return if (k % 2 == 0) look.light else look.dark
    }

    /**
     * Giant [body] seen from far off, at [position] (camera-relative) turned by [rotation]: its
     * bands as rings of facets, its storm, and its rings.
     */
    fun items(body: CelestialBody, position: Vec3, rotation: Quat, key: (Int) -> Long, out: MutableList<RenderItem>) {
        val look = LOOKS[body.id] ?: return
        val r = body.radius
        // The bands, pole to pole, each one a zone of the sphere.
        val zones = look.bands + 2
        for (z in 0 until zones) {
            val from = -PI / 2 + PI * z / zones
            val to = -PI / 2 + PI * (z + 1) / zones
            val profile = (0..3).map { s ->
                val lat = from + (to - from) * s / 3.0
                listOf(r * cos(lat), r * sin(lat))
            }
            out.add(
                RenderItem(
                    shape = ModelSpec.Lathe(profile, segments = 32),
                    position = position.copy(), rotation = rotation.copy(),
                    color = band(look, (from + to) / 2).let { floatArrayOf(it[0], it[1], it[2], 1f) },
                    caps = 0, key = key(z), sky = true,
                )
            )
        }
        body.rings?.let { rings(look, it.inner, it.outer, position, rotation, key, out) }
    }

    /** Rings: flat and thin, in the equator, with a gap where the giant has one. */
    fun rings(body: CelestialBody, position: Vec3, rotation: Quat, key: (Int) -> Long, out: MutableList<RenderItem>) {
        val look = LOOKS[body.id] ?: return
        body.rings?.let { rings(look, it.inner, it.outer, position, rotation, key, out) }
    }

    private fun rings(look: Look, inner: Double, outer: Double, position: Vec3, rotation: Quat, key: (Int) -> Long, out: MutableList<RenderItem>) {
        val colour = look.ring ?: return
        val spans = if (look.ringGap) {
            val gap = inner + (outer - inner) * 0.62
            listOf(inner to gap - (outer - inner) * 0.04, gap to outer)
        } else listOf(inner to outer)
        val thick = (outer - inner) * 0.002
        for ((k, span) in spans.withIndex()) {
            val (a, b) = span
            val shade = if (k == 0) 1f else 0.85f
            out.add(
                RenderItem(
                    shape = ModelSpec.Lathe(listOf(listOf(a, -thick), listOf(b, -thick), listOf(b, thick), listOf(a, thick), listOf(a, -thick)), segments = 96),
                    position = position.copy(), rotation = rotation.copy(),
                    color = floatArrayOf(colour[0] * shade, colour[1] * shade, colour[2] * shade, 1f),
                    caps = 0, key = key(100 + k), sky = true, ambient = 0.45f,
                )
            )
        }
    }
}
