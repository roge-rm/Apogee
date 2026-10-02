package com.rm.apogee.core.terrain

import com.rm.apogee.core.terrain.Noise.simplex
import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.floor
import kotlin.math.sqrt

/**
 * An airless, battered moon: dark basalt maria about 1.5 km below cratered highlands, rilles across
 * the maria, and craters in five size classes (basins 25 km across to pits of tens of metres) that
 * overlap freely. The biggest have flat floors and central peaks.
 *
 * Craters come one or none per cell of a 3D lattice, from cells near the surface only, or a deep
 * cell projects its crater where its neighbours can't see it and the ground steps.
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

        // Rilles: winding channels, only in the maria.
        if (mare > 0.0) {
            val w = simplex(rilleSeed + 1, px / 6_000.0, py / 6_000.0, pz / 6_000.0) * 2_000.0
            val c = abs(simplex(rilleSeed, (px + w) / 30_000.0, (py - w) / 30_000.0, (pz + w) / 30_000.0))
            h -= RILLE_DEPTH * (1.0 - Noise.smoothstep((c / 0.02).coerceIn(0.0, 1.0))) * mare
        }

        // Craters, biggest first. Highlands keep more, since maria were resurfaced after the early
        // bombardment.
        val keep = 0.45 + 0.55 * highland
        for (c in CLASSES.indices) {
            h += craters(px, py, pz, c, keep)
        }

        h += fbm(detailSeed, px, py, pz, 1.0 / 60.0, 2) * 0.6
        return h
    }

    /**
     * Every crater of size class [c] near the point, added up. The ones on the edge of being kept
     * are faded, or a crater would pop in whole where the maria meet the highlands.
     */
    private fun craters(px: Double, py: Double, pz: Double, c: Int, keep: Double): Double {
        var total = 0.0
        val chance = CLASSES[c].chance
        forEachCrater(px, py, pz, c, keep) { x01, r, age, drawn ->
            val fade = Noise.smoothstep(((chance * keep - drawn) / (chance * Craters.KEEP_FADE)).coerceIn(0.0, 1.0))
            if (fade > 0.0) total += profile(x01, r) * age * fade
        }
        return total
    }

    /**
     * Calls [action] with (distance in crater radii, radius, age) for every crater of class [c]
     * whose ejecta reaches the point. Age is 1 when fresh, falling toward 0.35 when worn.
     */
    private inline fun forEachCrater(
        px: Double, py: Double, pz: Double, c: Int, keep: Double,
        action: (Double, Double, Double, Double) -> Unit,
    ) {
        val cls = CLASSES[c]
        val cell = cls.cell
        val cx = floor(px / cell).toInt()
        val cy = floor(py / cell).toInt()
        val cz = floor(pz / cell).toInt()
        val s = craterSeed + c * 977
        for (i in -1..1) for (j in -1..1) for (k in -1..1) {
            val x = cx + i; val y = cy + j; val z = cz + k
            val drawn = Noise.hash(s, x, y, z)
            if (drawn > cls.chance * keep) continue
            val vx = (x + Noise.hash(s + 1, x, y, z)) * cell
            val vy = (y + Noise.hash(s + 2, x, y, z)) * cell
            val vz = (z + Noise.hash(s + 3, x, y, z)) * cell
            val vl = sqrt(vx * vx + vy * vy + vz * vz)
            // Only cells near the surface place a crater. See the class notes.
            if (abs(vl - radius) > cell * 0.4) continue
            val sx = vx / vl * radius; val sy = vy / vl * radius; val sz = vz / vl * radius
            val dx = px - sx; val dy = py - sy; val dz = pz - sz
            val d = sqrt(dx * dx + dy * dy + dz * dz)
            val r = cls.minRadius + (cls.maxRadius - cls.minRadius) * Noise.hash(s + 4, x, y, z)
            val x01 = d / r
            if (x01 >= EJECTA_REACH) continue
            action(x01, r, 0.35 + 0.65 * Noise.hash(s + 5, x, y, z), drawn)
        }
    }

    /**
     * A crater's height at [x] crater radii from its centre: a bowl inside, a rim on the edge, and
     * ejecta fading outward to exactly zero at [EJECTA_REACH]. Anything nonzero where a crater stops
     * being counted shows as a step.
     */
    private fun profile(x: Double, r: Double): Double {
        val complex = r > COMPLEX_RADIUS
        val depth = r * (if (complex) 0.1 else 0.2)
        val rim = depth * 0.25
        if (x < 1.0) {
            // Every shape reaches the rim with zero slope, so the crest is rounded. A kink there
            // shows as a hairline seam.
            val s = if (complex) {
                // A flat floor, then walls steepening toward the rim.
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

    private val relief = Relief(Noise.hashInt(seed, 106, 0, 0), radius, Relief.Character(hillWave = 1_600.0, hillHeight = 150.0, swellHeight = 4.0))
    private val wrinkleSeed = Noise.hashInt(seed, 107, 0, 0)

    /** The same craters as [height]'s, for their rays. */
    private val rayed = Craters(craterSeed, radius, Craters.scaledFrom(radius))

    /**
     * The close-up relief over [base], the height there, in metres: hummocks on the highlands,
     * wrinkle ridges on the maria, young craters everywhere, and the Skylight and the Bright Crater.
     */
    fun detail(nx: Double, ny: Double, nz: Double, base: Double): Double {
        val px = nx * radius; val py = ny * radius; val pz = nz * radius
        val mare = mare(px, py, pz)
        var h = relief.height(px, py, pz, 1.0 - mare)
        // Where the maria's lava sagged and buckled.
        if (mare > 0.0) h += Closeup.wrinkles(wrinkleSeed, px, py, pz, 9_000.0, 0.12, 50.0) * mare
        // A sheer pit down into a lava tube.
        val sky = Closeup.chord(nx, ny, nz, SKYLIGHT, radius)
        if (sky < 120.0) h += Closeup.pit(sky, SKYLIGHT_RADIUS, 55.0, 5.0)
        // Young and sharp, with a peak in the middle.
        val bright = Closeup.chord(nx, ny, nz, BRIGHT, radius) / BRIGHT_RADIUS
        if (bright < Craters.EJECTA_REACH) h += Craters.bowl(bright, BRIGHT_RADIUS, COMPLEX_RADIUS)
        return h
    }

    /** Whether the place is on the Bright Crater's rays or rubble. */
    private fun bright(nx: Double, ny: Double, nz: Double): Boolean {
        val dx = nx - BRIGHT[0]; val dy = ny - BRIGHT[1]; val dz = nz - BRIGHT[2]
        val d = sqrt(dx * dx + dy * dy + dz * dz)
        val x = d * radius / BRIGHT_RADIUS
        if (x > 7.0) return false
        if (x < 1.5) return true
        val ray = abs(simplex(wrinkleSeed + 3, dx / d * 30.0, dy / d * 30.0, dz / d * 30.0))
        return ray > 0.55 + 0.05 * (x - 1.5)
    }

    fun material(nx: Double, ny: Double, nz: Double, slope: Double): SurfaceMaterial {
        if (slope > 0.25) return SurfaceMaterial.ROCK
        val px = nx * radius; val py = ny * radius; val pz = nz * radius
        val mare = mare(px, py, pz)
        // Fresh craters have a ring of boulders round the rim, drawn and driven as scree.
        val keep = 0.45 + 0.55 * (1.0 - mare)
        // Near the poles the sun never reaches a crater's floor, so there's ice there.
        if (kotlin.math.abs(ny) > POLAR_ICE) {
            for (c in 1..3) {
                var floor = false
                forEachCrater(px, py, pz, c, keep) { x01, _, _, _ -> if (x01 < ICE_FLOOR) floor = true }
                if (floor) return SurfaceMaterial.ICE
            }
        }
        val polar = kotlin.math.abs(ny) > POLAR_FROST
        for (c in 1..3) {
            var rubble = false
            forEachCrater(px, py, pz, c, keep) { x01, _, age, _ ->
                if (age > FRESH && x01 > 0.85 && x01 < 1.6) rubble = true
            }
            // Near the poles the rims catch frost.
            if (rubble) return if (polar) SurfaceMaterial.FROST else SurfaceMaterial.SCREE
        }
        // Bright rubble thrown out by the youngest big craters, in rays.
        if (bright(nx, ny, nz) || rayed.onRay(px, py, pz, keep)) return SurfaceMaterial.EJECTA
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
        /** Closer to the pole than this (sine of 78 degrees latitude), crater floors are ice. */
        const val POLAR_ICE = 0.978

        /** Closer to the pole than this (sine of 74 degrees), crater rims are frosted. */
        const val POLAR_FROST = 0.961

        /** The Skylight, a pit into a lava tube near Riccioli, and the Bright Crater, and their sizes in metres. */
        val SKYLIGHT = Landforms.at(7.2, 8.3)
        const val SKYLIGHT_RADIUS = 30.0
        val BRIGHT = Landforms.at(-20.0, 40.0)
        const val BRIGHT_RADIUS = 4_500.0
        /** Where the ice lies: a crater's floor, in its radii from the centre. */
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
