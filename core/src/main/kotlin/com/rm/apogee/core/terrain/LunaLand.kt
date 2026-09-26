package com.rm.apogee.core.terrain

import com.rm.apogee.core.terrain.Noise.simplex
import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.floor
import kotlin.math.sqrt

/**
 * The shape of an airless, battered moon.
 *
 * No weather has ever smoothed it, so it is made of two things: old volcanic
 * plains and the craters punched into everything since.
 *
 *  - **Maria and highlands.** Broad dark plains of basalt lying about a
 *    kilometre and a half below bright, rolling, heavily cratered uplands.
 *  - **Craters**, in five size classes from basins twenty-five kilometres
 *    across to pits a few tens of metres wide, each a bowl with a raised rim
 *    and an apron of ejecta fading away from it. The biggest have flat floors
 *    and central peaks. Older ones are softened. They overlap freely, which is
 *    what makes a landscape read as ancient.
 *  - **Rilles** - sinuous channels wandering across the maria.
 *
 * Craters are placed one or none per cell of a 3D lattice, from cells near the
 * surface only - the lesson from Terra's volcanoes, where a cell deep inside
 * the body projected its crater somewhere its neighbours could not see it and
 * the ground stepped.
 */
internal class LunaLand(seed: Int, private val radius: Double) {

    private val mareSeed = Noise.hashInt(seed, 101, 0, 0)
    private val highlandSeed = Noise.hashInt(seed, 102, 0, 0)
    private val rilleSeed = Noise.hashInt(seed, 103, 0, 0)
    private val craterSeed = Noise.hashInt(seed, 104, 0, 0)
    private val detailSeed = Noise.hashInt(seed, 105, 0, 0)

    /** 1 deep in a mare, 0 on the highlands. */
    fun mare(px: Double, py: Double, pz: Double): Double {
        val n = simplex(mareSeed, px * MARE_FREQUENCY, py * MARE_FREQUENCY, pz * MARE_FREQUENCY) * 0.8 +
            simplex(mareSeed + 1, px * MARE_FREQUENCY * 2.7, py * MARE_FREQUENCY * 2.7, pz * MARE_FREQUENCY * 2.7) * 0.2
        return Noise.smoothstep(((n - 0.1) / 0.25).coerceIn(0.0, 1.0))
    }

    fun height(nx: Double, ny: Double, nz: Double): Double {
        val px = nx * radius; val py = ny * radius; val pz = nz * radius
        val mare = mare(px, py, pz)
        val highland = 1.0 - mare

        // Highlands roll; maria are nearly flat.
        var h = MARE_DEPTH * -mare
        h += fbm(highlandSeed, px, py, pz, HIGHLAND_FREQUENCY, 4) * (120.0 + 520.0 * highland)

        // Rilles: sinuous channels, only in the maria.
        if (mare > 0.0) {
            val w = simplex(rilleSeed + 1, px / 6_000.0, py / 6_000.0, pz / 6_000.0) * 2_000.0
            val c = abs(simplex(rilleSeed, (px + w) / 30_000.0, (py - w) / 30_000.0, (pz + w) / 30_000.0))
            h -= RILLE_DEPTH * (1.0 - Noise.smoothstep((c / 0.02).coerceIn(0.0, 1.0))) * mare
        }

        // Craters, largest first. Highlands keep more of them: maria are
        // younger and have been resurfaced since the early bombardment.
        val keep = 0.45 + 0.55 * highland
        for (c in CLASSES.indices) {
            h += craters(px, py, pz, c, keep)
        }

        h += fbm(detailSeed, px, py, pz, 1.0 / 60.0, 2) * 0.6
        return h
    }

    /** Every crater of size class [c] near the point, summed. */
    private fun craters(px: Double, py: Double, pz: Double, c: Int, keep: Double): Double {
        var total = 0.0
        forEachCrater(px, py, pz, c, keep) { x01, r, age -> total += profile(x01, r) * age }
        return total
    }

    /**
     * Calls [action] with (distance in crater radii, radius, age) for every
     * crater of class [c] whose ejecta reaches the point. Age is 1 for a
     * fresh crater and falls towards 0.35 for a worn one.
     */
    private inline fun forEachCrater(
        px: Double, py: Double, pz: Double, c: Int, keep: Double,
        action: (Double, Double, Double) -> Unit,
    ) {
        val cls = CLASSES[c]
        val cell = cls.cell
        val cx = floor(px / cell).toInt()
        val cy = floor(py / cell).toInt()
        val cz = floor(pz / cell).toInt()
        val s = craterSeed + c * 977
        for (i in -1..1) for (j in -1..1) for (k in -1..1) {
            val x = cx + i; val y = cy + j; val z = cz + k
            if (Noise.hash(s, x, y, z) > cls.chance * keep) continue
            val vx = (x + Noise.hash(s + 1, x, y, z)) * cell
            val vy = (y + Noise.hash(s + 2, x, y, z)) * cell
            val vz = (z + Noise.hash(s + 3, x, y, z)) * cell
            val vl = sqrt(vx * vx + vy * vy + vz * vz)
            // Only cells near the surface place a crater; see the class notes.
            if (abs(vl - radius) > cell * 0.4) continue
            val sx = vx / vl * radius; val sy = vy / vl * radius; val sz = vz / vl * radius
            val dx = px - sx; val dy = py - sy; val dz = pz - sz
            val d = sqrt(dx * dx + dy * dy + dz * dz)
            val r = cls.minRadius + (cls.maxRadius - cls.minRadius) * Noise.hash(s + 4, x, y, z)
            val x01 = d / r
            if (x01 >= EJECTA_REACH) continue
            action(x01, r, 0.35 + 0.65 * Noise.hash(s + 5, x, y, z))
        }
    }

    /**
     * A crater's height at [x] crater radii from its centre.
     *
     * A parabolic bowl inside, a rim peaking on the edge, and ejecta decaying
     * outward - windowed to exactly zero at [EJECTA_REACH], because anything
     * that is not zero where a crater stops being counted is a step.
     */
    private fun profile(x: Double, r: Double): Double {
        val complex = r > COMPLEX_RADIUS
        val depth = r * (if (complex) 0.1 else 0.2)
        val rim = depth * 0.25
        if (x < 1.0) {
            // Every shape here reaches the rim with zero slope, so the crest
            // is rounded; a kink there reads as a hairline seam.
            val s = if (complex) {
                // A flat floor, then walls that steepen towards the rim.
                val u = ((x - 0.7) / 0.3).coerceIn(0.0, 1.0)
                Noise.smoothstep(u)
            } else {
                val q = 1.0 - x * x
                1.0 - q * q
            }
            var h = -depth + (depth + rim) * s
            if (complex) h += depth * 0.55 * exp(-(x / 0.13) * (x / 0.13))
            return h
        }
        val e = (x - 1.0) / 0.3
        val h = rim * (0.6 * exp(-e * e) + 0.4 / (1.0 + 4.0 * (x - 1.0) * (x - 1.0)))
        if (x < 1.4) return h
        return h * Noise.smoothstep(((EJECTA_REACH - x) / (EJECTA_REACH - 1.4)).coerceIn(0.0, 1.0))
    }

    fun material(nx: Double, ny: Double, nz: Double, slope: Double): SurfaceMaterial {
        if (slope > 0.25) return SurfaceMaterial.ROCK
        val px = nx * radius; val py = ny * radius; val pz = nz * radius
        val mare = mare(px, py, pz)
        // Fresh craters throw out rubble: a ring of boulders around the rim
        // that weathering has not yet ground down to regolith. Scree carries
        // the boulders, and drives like it.
        val keep = 0.45 + 0.55 * (1.0 - mare)
        // Near the poles the sun never climbs high enough to reach a crater's
        // floor: ice has lain there since it arrived.
        if (kotlin.math.abs(ny) > POLAR_ICE) {
            for (c in 1..3) {
                var floor = false
                forEachCrater(px, py, pz, c, keep) { x01, _, _ -> if (x01 < ICE_FLOOR) floor = true }
                if (floor) return SurfaceMaterial.ICE
            }
        }
        for (c in 1..3) {
            var rubble = false
            forEachCrater(px, py, pz, c, keep) { x01, _, age ->
                if (age > FRESH && x01 > 0.85 && x01 < 1.6) rubble = true
            }
            if (rubble) return SurfaceMaterial.SCREE
        }
        return if (mare > 0.5) SurfaceMaterial.BASALT else SurfaceMaterial.REGOLITH
    }

    private fun fbm(seed: Int, x: Double, y: Double, z: Double, frequency: Double, octaves: Int): Double {
        var f = frequency
        var a = 1.0
        var total = 0.0
        var norm = 0.0
        for (o in 0 until octaves) {
            total += simplex(seed + o, x * f, y * f, z * f) * a
            norm += a
            a *= 0.5
            f *= 2.07
        }
        return total / norm
    }

    private class CraterClass(val cell: Double, val chance: Double, val minRadius: Double, val maxRadius: Double)

    private companion object {
        const val MARE_FREQUENCY = 1.0 / 90_000.0
        const val MARE_DEPTH = 1_500.0
        const val HIGHLAND_FREQUENCY = 1.0 / 25_000.0
        const val RILLE_DEPTH = 90.0
        const val EJECTA_REACH = 2.6
        const val COMPLEX_RADIUS = 4_000.0
        const val FRESH = 0.88
        /** Poleward of this (the sine of 78 degrees of latitude), crater floors are ice. */
        const val POLAR_ICE = 0.978
        /** A crater's floor, in its radii from the centre: where the ice lies. */
        const val ICE_FLOOR = 0.6

        val CLASSES = arrayOf(
            CraterClass(cell = 90_000.0, chance = 0.30, minRadius = 8_000.0, maxRadius = 25_000.0),
            CraterClass(cell = 22_000.0, chance = 0.40, minRadius = 1_500.0, maxRadius = 6_000.0),
            CraterClass(cell = 4_000.0, chance = 0.45, minRadius = 200.0, maxRadius = 1_000.0),
            CraterClass(cell = 700.0, chance = 0.45, minRadius = 30.0, maxRadius = 160.0),
            CraterClass(cell = 120.0, chance = 0.30, minRadius = 5.0, maxRadius = 22.0),
        )
    }
}
