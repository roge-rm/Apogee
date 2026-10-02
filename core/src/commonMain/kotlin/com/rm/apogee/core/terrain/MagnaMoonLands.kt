package com.rm.apogee.core.terrain

import com.rm.apogee.core.terrain.Landforms.at
import com.rm.apogee.core.terrain.Landforms.distance
import kotlin.math.abs
import com.rm.apogee.core.PerThread

/**
 * Fornax: kneaded by Magna's tides until it melts inside. Lava resurfaces it too fast for craters.
 * Yellow and orange sulfur plains, paterae (volcanic pits with dark floors, a few with open lava
 * lakes), and lone sheer mountains taller than anything on Terra.
 */
internal class FornaxLand(seed: Int, private val radius: Double) : WorldLand {
    private val detail = Noise.hashInt(seed, 1, 0, 0)
    private val pits = Noise.hashInt(seed, 2, 0, 0)
    private val peaks = Noise.hashInt(seed, 3, 0, 0)

    override fun height(nx: Double, ny: Double, nz: Double): Double {
        val px = nx * radius; val py = ny * radius; val pz = nz * radius
        var h = Landforms.fbm(detail, px, py, pz, 1.0 / 25_000.0, 4) * 250.0
        Landforms.scattered(peaks, px, py, pz, radius, 60_000.0, 0.18) { d, size, _ ->
            h += Landforms.mesa(d, 6_000.0 + 8_000.0 * size, 4_000.0 + 5_000.0 * size, 3_000.0) *
                (0.85 + 0.15 * Landforms.ridged(peaks + 9, px, py, pz, 1.0 / 2_000.0, 3))
        }
        var wall = 0.0
        Landforms.scattered(pits, px, py, pz, radius, 25_000.0, 0.35) { d, size, _ ->
            val r = 3_000.0 + 5_000.0 * size
            h -= Landforms.mesa(d, r, 300.0 + 300.0 * size, 800.0)
            wall = maxOf(wall, Landforms.within(abs(d - r), 600.0, 900.0))
        }
        walls.at(px, py, pz) { wall }
        for (l in LAVA_LAKES) h -= Landforms.mesa(distance(nx, ny, nz, l, radius), 6_000.0, 500.0, 1_500.0)
        return h
    }

    private val close = Noise.hashInt(seed, 20, 0, 0)
    private val relief = Relief(seed, radius, Relief.Character(hillWave = 2_500.0, hillHeight = 60.0, swellHeight = 5.0))

    private val walls = Remembered()

    /** 0..1 how far into a pit's wall, where the layers show: 1 on the wall, 0 out on the plain or the floor. */
    private fun pitWall(px: Double, py: Double, pz: Double): Double = walls.at(px, py, pz) {
        var w = 0.0
        Landforms.scattered(pits, px, py, pz, radius, 25_000.0, 0.35) { d, size, _ ->
            val r = 3_000.0 + 5_000.0 * size
            w = maxOf(w, Landforms.within(abs(d - r), 600.0, 900.0))
        }
        w
    }

    /** 1 on a dark young flow, spreading from a pit or a lake. */
    private fun flow(px: Double, py: Double, pz: Double): Double =
        Landforms.smooth((Noise.simplex(close + 2, px / 15_000.0, py / 15_000.0, pz / 15_000.0) - 0.35) / 0.1)

    override fun detail(nx: Double, ny: Double, nz: Double, base: Double): Double {
        val px = nx * radius; val py = ny * radius; val pz = nz * radius
        var h = relief.height(px, py, pz)
        // Pit walls stepping down in layers.
        val wall = pitWall(px, py, pz)
        if (wall > 0.0) h += wall * Closeup.benches(base + h, 60.0, 0.3)
        // Flows ending in fronts a few metres tall.
        val f = flow(px, py, pz)
        if (f > 0.0) h += f * Closeup.benches(base + h, 8.0, 0.25)
        // Fire Peak, the tallest of the mountains, sheer and craggy.
        val peak = Closeup.chord(nx, ny, nz, FIRE_PEAK, radius)
        if (peak < 26_000.0) h += Landforms.mesa(peak, 4_000.0, 9_000.0, 14_000.0) * (0.9 + 0.1 * Landforms.ridged(close + 3, px, py, pz, 1.0 / 1_500.0, 3))
        return h
    }

    override fun material(nx: Double, ny: Double, nz: Double, elevation: Double, slope: Double): SurfaceMaterial {
        for (l in LAVA_LAKES) if (distance(nx, ny, nz, l, radius) < 5_000.0) return SurfaceMaterial.LAVA
        val px = nx * radius; val py = ny * radius; val pz = nz * radius
        if (slope > 0.3) return if (pitWall(px, py, pz) > 0.3 || Closeup.chord(nx, ny, nz, FIRE_PEAK, radius) < 24_000.0) SurfaceMaterial.LAYERED_ROCK else SurfaceMaterial.ROCK
        // Red sulfur round the lakes still burning, and round the Fumaroles.
        for (l in LAVA_LAKES) if (distance(nx, ny, nz, l, radius) < 16_000.0 + 4_000.0 * Noise.simplex(close + 4, px / 6_000.0, py / 6_000.0, pz / 6_000.0)) return SurfaceMaterial.RED_SULFUR
        if (Closeup.chord(nx, ny, nz, FUMAROLES, radius) < 450.0) return SurfaceMaterial.RED_SULFUR
        var floor = false
        Landforms.scattered(pits, px, py, pz, radius, 25_000.0, 0.35) { d, size, _ -> if (d < (3_000.0 + 5_000.0 * size) * 0.9) floor = true }
        if (floor) return SurfaceMaterial.BASALT
        if (flow(px, py, pz) > 0.5) return SurfaceMaterial.FLOW_ROCK
        // White frost in patches on the high plains.
        if (elevation > 150.0 && Noise.simplex(close + 5, px / 4_000.0, py / 4_000.0, pz / 4_000.0) > 0.45) return SurfaceMaterial.FROST
        return SurfaceMaterial.SULFUR
    }

    companion object {
        /** Fire Peak, the tallest mountain, and the Fumaroles, near Marius. */
        val FIRE_PEAK = at(-20.0, 70.0)
        val FUMAROLES = at(-11.4, 58.6)
        /** The open lava lakes: the brightest spots, and the first a lander must avoid. */
        val LAVA_LAKES = listOf(at(-12.0, 50.0), at(25.0, -140.0), at(-40.0, 170.0))
    }
}

/**
 * Crusta: a shell of ice over a hidden ocean. Almost perfectly smooth and bright, except for long
 * reddish-brown cracks across the whole world (each a double ridge with a trough down the middle)
 * and patches of chaos, where the crust broke into blocks, drifted and refroze. Hardly any craters.
 */
internal class CrustaLand(seed: Int, private val radius: Double) : WorldLand {
    private val detail = Noise.hashInt(seed, 1, 0, 0)
    private val cracks = Noise.hashInt(seed, 2, 0, 0)
    private val chaos = Noise.hashInt(seed, 3, 0, 0)
    // Small ones only: the surface is young.
    private val craters = Craters(Noise.hashInt(seed, 4, 0, 0), radius, Craters.scaledFrom(radius, 0.3).drop(2))
    private val near = PerThread { DoubleArray(1) }

    private fun chaosAmount(px: Double, py: Double, pz: Double) =
        Landforms.smooth((Landforms.fbm(chaos + 5, px, py, pz, 1.0 / 35_000.0, 2) - 0.45) / 0.1)

    override fun height(nx: Double, ny: Double, nz: Double): Double {
        val px = nx * radius; val py = ny * radius; val pz = nz * radius
        var h = Landforms.fbm(detail, px, py, pz, 1.0 / 15_000.0, 4) * 60.0
        h += Landforms.lineae(cracks, nx, ny, nz, LINEAE, radius, 1_500.0, 180.0, near.get())
        val c = chaosAmount(px, py, pz)
        if (c > 0.0) h += c * (Landforms.chaos(chaos, px, py, pz, 2_500.0, 250.0) - 100.0)
        h += craters.height(px, py, pz, 1.0)
        return h
    }

    private val close = Noise.hashInt(seed, 20, 0, 0)
    private val relief = Relief(seed, radius, Relief.Character(hillWave = 1_200.0, hillHeight = 0.0, swellHeight = 3.0))

    override fun detail(nx: Double, ny: Double, nz: Double, base: Double): Double {
        val px = nx * radius; val py = ny * radius; val pz = nz * radius
        var h = relief.height(px, py, pz)
        // Ridged plains: low ridges packed close, the crust's older cracks.
        h += Closeup.wrinkles(close, px, py, pz, 400.0, 0.12, 22.0)
        // Domes where warm ice pushed up, and pits where it sank, in about half the world.
        val domed = Landforms.smooth((Noise.simplex(close + 4, px / 120_000.0, py / 120_000.0, pz / 120_000.0) + 0.1) / 0.2)
        if (domed > 0.0) Landforms.scattered(close + 1, px, py, pz, radius, 40_000.0, 0.25) { d, size, k ->
            val r = 2_500.0 + 2_500.0 * size
            if (d < r * 1.6) h += domed * if (k and 1 == 0) Landforms.pancake(d, r, 140.0 + 120.0 * size) else Closeup.pit(d, r * 0.6, 90.0, r * 0.3)
        }
        h += Landforms.pancake(Closeup.chord(nx, ny, nz, DOME, radius), DOME_RADIUS, 260.0)
        // Chaos blocks, tilted and rough.
        val c = chaosAmount(px, py, pz)
        if (c > 0.0) h += c * Closeup.rough(close + 2, px, py, pz, 120.0, 12.0)
        return h
    }

    override fun material(nx: Double, ny: Double, nz: Double, elevation: Double, slope: Double): SurfaceMaterial {
        val px = nx * radius; val py = ny * radius; val pz = nz * radius
        val n = near.get()
        Landforms.lineae(cracks, nx, ny, nz, LINEAE, radius, 1_500.0, 180.0, n)
        // Cracks and chaos are stained by what welled up through them, and so is the Dome's top.
        if (n[0] > 0.4 || chaosAmount(px, py, pz) > 0.5) return SurfaceMaterial.THOLIN
        if (Closeup.chord(nx, ny, nz, DOME, radius) < DOME_RADIUS * 0.7) return SurfaceMaterial.THOLIN
        // Snow in the Spires, where the ice is worn into blades.
        if (Closeup.chord(nx, ny, nz, SPIRES, radius) < 3_500.0) return SurfaceMaterial.SNOW
        // Grey bands where the crust pulled apart.
        if (abs(Noise.simplex(close + 3, px / 60_000.0, py / 60_000.0, pz / 60_000.0)) < 0.03) return SurfaceMaterial.SALT
        return if (slope > 0.3) SurfaceMaterial.SNOW else SurfaceMaterial.ICE
    }

    companion object {
        /** The Spires, the Dome and the Broken Field. */
        val SPIRES = at(-2.0, 10.0)
        val DOME = at(-15.0, 25.0)
        const val DOME_RADIUS = 6_000.0
        const val LINEAE = 18
        /** Where two big cracks cross, near the middle of the lit face. */
        val CROSSING = at(5.0, 0.0)
    }
}

/**
 * Maxima: the biggest moon. Old, dark, densely cratered polygons, and between them bright younger
 * ice torn into parallel grooves as the moon stretched. Frost caps at both poles.
 */
internal class MaximaLand(seed: Int, private val radius: Double) : WorldLand {
    private val detail = Noise.hashInt(seed, 1, 0, 0)
    private val bands = Noise.hashInt(seed, 2, 0, 0)
    private val craters = Craters(Noise.hashInt(seed, 3, 0, 0), radius, Craters.scaledFrom(radius, 0.9))

    /** 1 on bright grooved ground, 0 on dark. */
    private fun bright(px: Double, py: Double, pz: Double) =
        Landforms.smooth((abs(Landforms.fbm(bands, px, py, pz, 1.0 / 90_000.0, 2)) - 0.12) / -0.08 + 1.0)

    override fun height(nx: Double, ny: Double, nz: Double): Double {
        val px = nx * radius; val py = ny * radius; val pz = nz * radius
        val b = bright(px, py, pz)
        var h = Landforms.fbm(detail, px, py, pz, 1.0 / 40_000.0, 4) * 500.0 - b * 400.0
        // Grooves run along each band, with the axis turning slowly across the world.
        val axis = doubleArrayOf(
            Landforms.fbm(bands + 3, px, py, pz, 1.0 / 400_000.0, 1),
            Landforms.fbm(bands + 4, px, py, pz, 1.0 / 400_000.0, 1),
            Landforms.fbm(bands + 5, px, py, pz, 1.0 / 400_000.0, 1),
        ).let { a -> val l = kotlin.math.sqrt(a[0] * a[0] + a[1] * a[1] + a[2] * a[2]).coerceAtLeast(1e-6); doubleArrayOf(a[0] / l, a[1] / l, a[2] / l) }
        h += b * Landforms.grooves(px, py, pz, axis, 5_000.0, 350.0)
        h += craters.height(px, py, pz, 1.0 - 0.6 * b)
        return h
    }

    private val close = Noise.hashInt(seed, 20, 0, 0)
    private val relief = Relief(seed, radius, Relief.Character(hillWave = 2_000.0, hillHeight = 90.0, swellHeight = 5.0))

    /** The way the grooves run here, as [height] finds it. */
    private fun grooveAxis(px: Double, py: Double, pz: Double): DoubleArray = doubleArrayOf(
        Landforms.fbm(bands + 3, px, py, pz, 1.0 / 400_000.0, 1),
        Landforms.fbm(bands + 4, px, py, pz, 1.0 / 400_000.0, 1),
        Landforms.fbm(bands + 5, px, py, pz, 1.0 / 400_000.0, 1),
    ).let { a -> val l = kotlin.math.sqrt(a[0] * a[0] + a[1] * a[1] + a[2] * a[2]).coerceAtLeast(1e-6); doubleArrayOf(a[0] / l, a[1] / l, a[2] / l) }

    override fun detail(nx: Double, ny: Double, nz: Double, base: Double): Double {
        val px = nx * radius; val py = ny * radius; val pz = nz * radius
        val b = bright(px, py, pz)
        var h = relief.height(px, py, pz, 1.0 - b)
        // Fine grooves inside the big ones, on bright ground.
        if (b > 0.0) h += b * Landforms.grooves(px, py, pz, grooveAxis(px, py, pz), 500.0, 28.0)
        // The Furrows: arcs round an old impact in the dark.
        val furrow = Closeup.chord(nx, ny, nz, FURROWS, radius)
        if (furrow < 120_000.0 && b < 1.0) h -= (1.0 - b) * Closeup.ridge(((furrow % 9_000.0) - 4_500.0).let { abs(it) }, 1_200.0, 60.0) * Landforms.within(furrow, 90_000.0, 30_000.0)
        // The Rayed Crater: young, with a pit in its middle instead of a peak.
        val rayed = Closeup.chord(nx, ny, nz, RAYED, radius) / RAYED_RADIUS
        if (rayed < Craters.EJECTA_REACH) {
            h += Craters.bowl(rayed, RAYED_RADIUS, RAYED_RADIUS * 0.5)
            if (rayed < 0.2) h -= 300.0 * (1.0 - Landforms.smooth(rayed / 0.2))
        }
        return h
    }

    override fun material(nx: Double, ny: Double, nz: Double, elevation: Double, slope: Double): SurfaceMaterial {
        if (abs(ny) > 0.85) return SurfaceMaterial.SNOW
        if (slope > 0.3) return SurfaceMaterial.ROCK
        val px = nx * radius; val py = ny * radius; val pz = nz * radius
        // The Ghost: a flat bright disc where a crater was, its rim long gone.
        if (Closeup.chord(nx, ny, nz, GHOST, radius) < GHOST_RADIUS) return SurfaceMaterial.ICE
        // Bright rubble round the Rayed Crater, in rays.
        val rayed = Closeup.chord(nx, ny, nz, RAYED, radius) / RAYED_RADIUS
        if (rayed < 1.6) return SurfaceMaterial.EJECTA
        if (rayed < 6.0) {
            val dx = nx - RAYED[0]; val dy = ny - RAYED[1]; val dz = nz - RAYED[2]
            val d = kotlin.math.sqrt(dx * dx + dy * dy + dz * dz)
            if (abs(Noise.simplex(close + 1, dx / d * 30.0, dy / d * 30.0, dz / d * 30.0)) > 0.55 + 0.05 * (rayed - 1.6)) return SurfaceMaterial.EJECTA
        }
        if (bright(px, py, pz) > 0.5) return SurfaceMaterial.ICE
        // Dark dust lying in the low ground.
        if (elevation < -150.0 && slope < 0.08) return SurfaceMaterial.DARK_SAND
        return SurfaceMaterial.REGOLITH
    }

    companion object {
        /** The Rayed Crater, the Ghost and the Furrows. */
        val RAYED = at(20.0, 50.0)
        const val RAYED_RADIUS = 12_000.0
        val GHOST = at(-10.0, 10.0)
        const val GHOST_RADIUS = 18_000.0
        val FURROWS = at(40.0, -30.0)
        /** A band of grooved ground crossing the dark, to land between the two. */
        val GROOVED = at(10.0, 35.0)
    }
}

/**
 * Cicatrix: the most battered face in the system, dark and cratered shoulder to shoulder, with
 * bright ice on fresh rims. The Great Scar, a basin ringed by ridge after ridge, reaches across a
 * quarter of the world.
 */
internal class CicatrixLand(seed: Int, private val radius: Double) : WorldLand {
    private val detail = Noise.hashInt(seed, 1, 0, 0)
    private val craters = Craters(Noise.hashInt(seed, 2, 0, 0), radius, Craters.scaledFrom(radius, 1.4))

    override fun height(nx: Double, ny: Double, nz: Double): Double {
        val px = nx * radius; val py = ny * radius; val pz = nz * radius
        var h = Landforms.fbm(detail, px, py, pz, 1.0 / 30_000.0, 4) * 350.0
        val d = distance(nx, ny, nz, GREAT_SCAR, radius)
        h += Landforms.basin(d, 45_000.0, 1_200.0, rings = 7, ringHeight = 500.0)
        // The scar's floor was smoothed, so fewer craters in it.
        h += craters.height(px, py, pz, 1.0 - 0.6 * Landforms.within(d, 45_000.0, 20_000.0))
        return h
    }

    private val close = Noise.hashInt(seed, 20, 0, 0)
    private val relief = Relief(seed, radius, Relief.Character(hillWave = 1_800.0, hillHeight = 70.0, swellHeight = 5.0))

    /** How tall the knobs are here: what's left of old crater rims, worn into buttes. */
    private fun knobs(px: Double, py: Double, pz: Double): Double {
        // Only in the knobby country, about a third of the world.
        val field = Landforms.smooth((Closeup.region(close + 1, px, py, pz, 25_000.0) - 0.55) / 0.3)
        return if (field <= 0.0) 0.0 else Closeup.knobs(close, px, py, pz, radius, 700.0, 0.5, 140.0, 120.0, 80.0) * field
    }

    override fun detail(nx: Double, ny: Double, nz: Double, base: Double): Double {
        val px = nx * radius; val py = ny * radius; val pz = nz * radius
        var h = relief.height(px, py, pz)
        h += knobs(px, py, pz)
        // The Chain: craters in a line where a broken comet struck.
        val off = Landforms.arcDistance(nx, ny, nz, CHAIN[0], CHAIN[1], radius)
        if (off < 8_000.0) for (k in 0 until CHAIN_COUNT) {
            val t = (k + 0.5) / CHAIN_COUNT
            val c = doubleArrayOf(
                CHAIN[0][0] + (CHAIN[1][0] - CHAIN[0][0]) * t, CHAIN[0][1] + (CHAIN[1][1] - CHAIN[0][1]) * t, CHAIN[0][2] + (CHAIN[1][2] - CHAIN[0][2]) * t,
            ).let { v -> val l = kotlin.math.sqrt(v[0] * v[0] + v[1] * v[1] + v[2] * v[2]); doubleArrayOf(v[0] / l, v[1] / l, v[2] / l) }
            val r = 1_500.0 + 900.0 * Noise.hash(close + 2, k, 0, 0)
            val x = Closeup.chord(nx, ny, nz, c, radius) / r
            if (x < Craters.EJECTA_REACH) h += Craters.bowl(x, r, Double.MAX_VALUE)
        }
        return h
    }

    override fun material(nx: Double, ny: Double, nz: Double, elevation: Double, slope: Double): SurfaceMaterial {
        val px = nx * radius; val py = ny * radius; val pz = nz * radius
        // Frost on the knobs' tops.
        if (knobs(px, py, pz) > 60.0 && slope < 0.15) return SurfaceMaterial.FROST
        if (slope > 0.3) return SurfaceMaterial.ROCK
        if (distance(nx, ny, nz, GREAT_SCAR, radius) < 40_000.0) return SurfaceMaterial.SNOW
        if (craters.onRim(px, py, pz, 1.0)) return SurfaceMaterial.ICE
        // Dark dust settled in the hollows, the ice gone from it.
        if (slope < 0.05 && Noise.simplex(close + 3, px / 3_000.0, py / 3_000.0, pz / 3_000.0) > 0.35) return SurfaceMaterial.DARK_SAND
        return SurfaceMaterial.REGOLITH
    }

    companion object {
        /** The Chain's two ends, and how many craters lie along it. */
        val CHAIN = listOf(at(0.0, -20.0), at(5.0, -5.0))
        const val CHAIN_COUNT = 15
        val GREAT_SCAR = at(15.0, -60.0)
    }
}
