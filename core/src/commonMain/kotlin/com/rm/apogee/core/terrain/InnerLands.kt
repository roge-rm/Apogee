package com.rm.apogee.core.terrain

import com.rm.apogee.core.terrain.Landforms.at
import com.rm.apogee.core.terrain.Landforms.distance
import kotlin.math.abs

/**
 * Celer: nearest the sun, airless and scorched. Like Luna but darker and more cratered. One huge
 * impact basin, the Great Basin, flooded smooth and ringed by mountains, with jumbled ground at its
 * antipode. Long lobed cliffs a kilometre high where the world shrank as it cooled, and ice in the
 * shaded floors of polar craters.
 */
internal class CelerLand(seed: Int, private val radius: Double) : WorldLand {
    private val craters = Craters(Noise.hashInt(seed, 1, 0, 0), radius, Craters.scaledFrom(radius, 1.15))
    private val detail = Noise.hashInt(seed, 2, 0, 0)
    private val scarpSeed = Noise.hashInt(seed, 3, 0, 0)

    override fun height(nx: Double, ny: Double, nz: Double): Double {
        val px = nx * radius; val py = ny * radius; val pz = nz * radius
        val fromBasin = distance(nx, ny, nz, GREAT_BASIN, radius)
        val inBasin = Landforms.within(fromBasin, BASIN_RADIUS * 0.9, BASIN_RADIUS * 0.2)
        var h = Landforms.fbm(detail, px, py, pz, 1.0 / 20_000.0, 4) * 500.0
        h += Landforms.basin(fromBasin, BASIN_RADIUS, 2_000.0, rings = 2, ringHeight = 900.0)
        // The antipode: hilly, broken ground where the shock met itself.
        val fromAntipode = distance(nx, ny, nz, ANTIPODE, radius)
        h += Landforms.within(fromAntipode, 40_000.0, 25_000.0) * Landforms.ridged(detail + 5, px, py, pz, 1.0 / 3_000.0, 3) * 900.0
        // The basin floor was flooded smooth, so fewer craters there.
        h += craters.height(px, py, pz, 1.0 - 0.7 * inBasin)
        for (k in 0 until SCARPS) {
            val pole = Landforms.pole(scarpSeed, k)
            val d = Landforms.signedFromCircle(nx, ny, nz, pole, radius)
            // A cliff along part of the circle only.
            val along = Landforms.fbm(scarpSeed + k, px, py, pz, 1.0 / 150_000.0, 1)
            if (along > 0.1) h += Landforms.scarp(d, 3_000.0, 900.0 * Landforms.smooth((along - 0.1) / 0.2))
        }
        return h
    }

    private val close = Noise.hashInt(seed, 20, 0, 0)
    private val relief = Relief(seed, radius, Relief.Character(hillWave = 2_000.0, hillHeight = 180.0, swellHeight = 5.0))

    /** 1 round the Hollows, where bright pits pock the ground. */
    private fun hollowed(nx: Double, ny: Double, nz: Double): Double =
        Landforms.within(Closeup.chord(nx, ny, nz, HOLLOWS, radius), 10_000.0, 6_000.0)

    /** How deep the bright pits are here, as a share of their full depth. */
    private fun hollow(px: Double, py: Double, pz: Double): Double =
        Closeup.knobs(close + 2, px, py, pz, radius, 900.0, 0.45, 160.0, 1.0, 50.0)

    override fun detail(nx: Double, ny: Double, nz: Double, base: Double): Double {
        val px = nx * radius; val py = ny * radius; val pz = nz * radius
        val fromBasin = distance(nx, ny, nz, GREAT_BASIN, radius)
        // Smoother on the flooded floor.
        var h = relief.height(px, py, pz, 1.0 - 0.7 * Landforms.within(fromBasin, BASIN_RADIUS * 0.85, BASIN_RADIUS * 0.15))
        // Blocky mountains on the Basin's first ring.
        val ring = Landforms.within(abs(fromBasin - BASIN_RADIUS * 1.35), 8_000.0, 6_000.0)
        if (ring > 0.0) h += ring * Closeup.knobs(close, px, py, pz, radius, 5_000.0, 0.6, 1_800.0, 700.0, 900.0)
        // The Long Cliff: steep on its face, a long gentle slope behind.
        val along = Landforms.arcDistance(nx, ny, nz, LONG_CLIFF[0], LONG_CLIFF[1], radius)
        if (along < 30_000.0) {
            val side = Landforms.signedFromCircle(nx, ny, nz, cliffPole, radius)
            val fade = 1.0 - Landforms.smooth((Closeup.chord(nx, ny, nz, cliffMiddle, radius) - 50_000.0) / 15_000.0)
            val rise = if (side < 0.0) Landforms.smooth(0.5 + side / 1_200.0) else 0.5 + 0.5 * Landforms.smooth(side / 25_000.0)
            h += (rise - 0.5) * 2.0 * LONG_CLIFF_HEIGHT * fade * (1.0 - Landforms.smooth((along - 2_000.0) / 25_000.0))
        }
        // Shallow bright pits on the floors round the Hollows.
        val pits = hollowed(nx, ny, nz)
        if (pits > 0.0) h -= pits * hollow(px, py, pz) * 25.0
        return h
    }

    override fun material(nx: Double, ny: Double, nz: Double, elevation: Double, slope: Double): SurfaceMaterial {
        if (slope > 0.25) return SurfaceMaterial.ROCK
        val px = nx * radius; val py = ny * radius; val pz = nz * radius
        // Ice in the shade of the polar craters.
        if (abs(ny) > 0.985 && craters.onFloor(px, py, pz, 1.0)) return SurfaceMaterial.ICE
        if (hollowed(nx, ny, nz) > 0.3 && hollow(px, py, pz) > 0.3) return SurfaceMaterial.EJECTA
        if (distance(nx, ny, nz, GREAT_BASIN, radius) < BASIN_RADIUS * 0.85) return SurfaceMaterial.BASALT
        if (craters.onRim(px, py, pz, 1.0)) return if (abs(ny) > 0.95) SurfaceMaterial.FROST else SurfaceMaterial.SCREE
        // Bright rays from the youngest craters a few kilometres across.
        if (craters.onRay(px, py, pz, from = 1)) return SurfaceMaterial.EJECTA
        return SurfaceMaterial.REGOLITH
    }

    private val cliffPole = run {
        val a = LONG_CLIFF[0]; val b = LONG_CLIFF[1]
        val p = doubleArrayOf(a[1] * b[2] - a[2] * b[1], a[2] * b[0] - a[0] * b[2], a[0] * b[1] - a[1] * b[0])
        val l = kotlin.math.sqrt(p[0] * p[0] + p[1] * p[1] + p[2] * p[2])
        doubleArrayOf(p[0] / l, p[1] / l, p[2] / l)
    }
    private val cliffMiddle = run {
        val a = LONG_CLIFF[0]; val b = LONG_CLIFF[1]
        val m = doubleArrayOf(a[0] + b[0], a[1] + b[1], a[2] + b[2])
        val l = kotlin.math.sqrt(m[0] * m[0] + m[1] * m[1] + m[2] * m[2])
        doubleArrayOf(m[0] / l, m[1] / l, m[2] / l)
    }

    companion object {
        /** The Long Cliff's ends, and how tall it stands. */
        val LONG_CLIFF = listOf(at(40.0, -60.0), at(10.0, -50.0))
        const val LONG_CLIFF_HEIGHT = 1_100.0

        /** A crater floor pocked with bright hollows, south-west of Le Verrier. */
        val HOLLOWS = at(25.0, 160.0)
        val GREAT_BASIN = at(30.0, 170.0)
        val ANTIPODE = at(-30.0, -10.0)
        const val BASIN_RADIUS = 60_000.0
        const val SCARPS = 7
    }
}

/**
 * Caligo: a world under a lid. Mostly young dark basalt plains with few craters, lava channels and
 * pancake domes (flat-topped blisters of thick lava). Two highland continents of tesserae (rock
 * folded into criss-crossing ridges): Ishtar in the far north, a walled plateau topped by Maxwell,
 * the tallest peak, and Aphrodite, long and ragged along the equator. A few great shield volcanoes,
 * one with a glowing lava lake.
 */
internal class CaligoLand(seed: Int, private val radius: Double) : WorldLand {
    private val detail = Noise.hashInt(seed, 1, 0, 0)
    private val tessera = Noise.hashInt(seed, 2, 0, 0)
    private val domes = Noise.hashInt(seed, 3, 0, 0)
    private val flows = Noise.hashInt(seed, 4, 0, 0)
    private val craters = Craters(Noise.hashInt(seed, 5, 0, 0), radius, Craters.scaledFrom(radius, 0.12))

    private val highlands = Remembered()

    /** 1 on a highland continent, 0 on the plains. */
    private fun highland(nx: Double, ny: Double, nz: Double, px: Double, py: Double, pz: Double): Double =
        highlands.at(nx, ny, nz) { highlandAt(nx, ny, nz, px, py, pz) }

    private fun highlandAt(nx: Double, ny: Double, nz: Double, px: Double, py: Double, pz: Double): Double {
        val ishtar = Landforms.within(distance(nx, ny, nz, ISHTAR, radius), 110_000.0, 40_000.0)
        // Aphrodite is long, so use the distance to the nearest point on its spine.
        val aphrodite = Landforms.within(Landforms.pathDistance(nx, ny, nz, APHRODITE, radius), 55_000.0, 35_000.0)
        val ragged = 0.75 + 0.25 * Landforms.fbm(detail + 9, px, py, pz, 1.0 / 60_000.0, 3)
        return (maxOf(ishtar, aphrodite) * ragged).coerceIn(0.0, 1.0)
    }

    override fun height(nx: Double, ny: Double, nz: Double): Double {
        val px = nx * radius; val py = ny * radius; val pz = nz * radius
        val high = highland(nx, ny, nz, px, py, pz)
        var h = Landforms.fbm(detail, px, py, pz, 1.0 / 80_000.0, 4) * 400.0
        h += high * (2_800.0 + Landforms.ridged(tessera, px, py, pz, 1.0 / 7_000.0, 4) * 1_400.0)
        // Maxwell.
        h += Landforms.shield(distance(nx, ny, nz, MAXWELL, radius), 45_000.0, 6_500.0)
        for (v in VOLCANOES) h += Landforms.shield(distance(nx, ny, nz, v, radius), 70_000.0, 3_000.0, 7_000.0, 400.0)
        // Lava channels across the plains.
        h += Landforms.rivers(flows, px, py, pz, 90_000.0, 0.03, 120.0) * (1.0 - high)
        // Pancake domes, only on the plains.
        if (high < 0.5) Landforms.scattered(domes, px, py, pz, radius, 30_000.0, 0.25) { d, size, _ ->
            h += Landforms.pancake(d, 6_000.0 + 6_000.0 * size, 600.0 + 400.0 * size) * (1.0 - high * 2)
        }
        h += craters.height(px, py, pz, 1.0)
        return h
    }

    private val close = Noise.hashInt(seed, 20, 0, 0)
    private val relief = Relief(seed, radius, Relief.Character(hillWave = 1_800.0, hillHeight = 90.0, swellHeight = 6.0))

    /** 1 near a volcano, where fresh flows lie on the plains. */
    private fun flowField(nx: Double, ny: Double, nz: Double): Double {
        var f = 0.0
        for (v in VOLCANOES) f = maxOf(f, Landforms.within(distance(nx, ny, nz, v, radius), 85_000.0, 30_000.0))
        return f
    }

    override fun detail(nx: Double, ny: Double, nz: Double, base: Double): Double {
        val px = nx * radius; val py = ny * radius; val pz = nz * radius
        val high = highland(nx, ny, nz, px, py, pz)
        var h = relief.height(px, py, pz, 0.3 + 0.7 * high)
        // The tesserae's second set of folds, cutting across the first.
        if (high > 0.0) h -= high * Closeup.wrinkles(close, px, py, pz, 1_500.0, 0.07, 80.0)
        // Fresh flows stepping down from the volcanoes, front after front.
        val flows = flowField(nx, ny, nz)
        if (flows > 0.0) h += flows * Closeup.benches(base + h, 18.0, 0.25)
        // The Crown: a ring of ridges round a sunken middle.
        val crown = Closeup.chord(nx, ny, nz, CROWN, radius)
        if (crown < CROWN_RADIUS * 1.6) {
            h += Closeup.ridge(abs(crown - CROWN_RADIUS), 5_000.0, 450.0) + Closeup.ridge(abs(crown - CROWN_RADIUS * 1.18), 3_000.0, 220.0)
            h -= 250.0 * (1.0 - Landforms.smooth((crown - CROWN_RADIUS * 0.7) / (CROWN_RADIUS * 0.25)))
        }
        return h
    }

    override fun material(nx: Double, ny: Double, nz: Double, elevation: Double, slope: Double): SurfaceMaterial {
        val px = nx * radius; val py = ny * radius; val pz = nz * radius
        // The live volcano's lake.
        if (distance(nx, ny, nz, VOLCANOES[0], radius) < 4_500.0) return SurfaceMaterial.LAVA
        // A shiny frost of metal on the highest peaks, where it's cool enough to settle.
        if (elevation > 5_800.0) return SurfaceMaterial.FROST
        if (slope > 0.3) return SurfaceMaterial.ROCK
        if (highland(nx, ny, nz, px, py, pz) > 0.5) return SurfaceMaterial.TESSERA
        if (flowField(nx, ny, nz) > 0.3 && Noise.simplex(close + 3, px / 12_000.0, py / 12_000.0, pz / 12_000.0) > 0.0) return SurfaceMaterial.FLOW_ROCK
        // Dark streaks of dust blown out behind every rise.
        if (abs(Noise.simplex(close + 4, px / 1_200.0, py / 4_000.0, pz / 1_200.0)) < 0.008) return SurfaceMaterial.DARK_SAND
        return SurfaceMaterial.BASALT
    }

    companion object {
        /** The Crown: a ring of ridges on the southern plains. */
        val CROWN = at(-30.0, 20.0)
        const val CROWN_RADIUS = 20_000.0
        val ISHTAR = at(68.0, 10.0)
        val MAXWELL = at(66.0, 5.0)
        val APHRODITE = listOf(at(-5.0, 60.0), at(-3.0, 90.0), at(-8.0, 120.0), at(-12.0, 150.0))
        val VOLCANOES = listOf(at(24.0, -110.0), at(-20.0, -60.0), at(32.0, 160.0))
    }
}

/**
 * Rubra: the red world. Low smooth northern plains a few kilometres down, and old cratered southern
 * highlands cut by dry river channels. On the rise between stand the Great Mount, the tallest
 * volcano anywhere, and the Three, shields in a line beside it. East of them the Rift, a canyon a
 * continent long. Ice caps at both poles, the northern one ringed by dunes. Red dust everywhere,
 * with rock on slopes too steep to hold it.
 */
internal class RubraLand(seed: Int, private val radius: Double) : WorldLand {
    private val detail = Noise.hashInt(seed, 1, 0, 0)
    private val craters = Craters(Noise.hashInt(seed, 2, 0, 0), radius, Craters.scaledFrom(radius, 0.8))
    private val riverSeed = Noise.hashInt(seed, 3, 0, 0)
    private val duneSeed = Noise.hashInt(seed, 4, 0, 0)

    /** 1 in the northern lowlands, 0 in the southern highlands, with a wandering boundary. */
    private fun lowland(ny: Double, px: Double, py: Double, pz: Double): Double {
        val wander = Landforms.fbm(detail + 7, px, py, pz, 1.0 / 120_000.0, 3) * 0.18
        return Landforms.smooth((ny - 0.05 + wander) / 0.25)
    }

    override fun height(nx: Double, ny: Double, nz: Double): Double {
        val px = nx * radius; val py = ny * radius; val pz = nz * radius
        val low = lowland(ny, px, py, pz)
        var h = 1_200.0 * (1.0 - low) - 2_500.0 * low
        h += Landforms.fbm(detail, px, py, pz, 1.0 / 30_000.0, 5) * (300.0 + 500.0 * (1.0 - low))
        h += Landforms.shield(distance(nx, ny, nz, GREAT_MOUNT, radius), 32_000.0, 19_000.0, 4_000.0, 1_500.0)
        for (v in THE_THREE) h += Landforms.shield(distance(nx, ny, nz, v, radius), 22_000.0, 9_000.0, 3_000.0, 1_200.0)
        // The bulge they stand on.
        h += Landforms.within(distance(nx, ny, nz, THE_THREE[1], radius), 60_000.0, 80_000.0) * 3_000.0
        h += Landforms.canyon(toRift(nx, ny, nz), 18_000.0, 5_000.0)
        // Dry rivers in the old south.
        h += Landforms.rivers(riverSeed, px, py, pz, 40_000.0, 0.04, 250.0) * (1.0 - low)
        // Craters: lots in the south, few on the young northern plains.
        h += craters.height(px, py, pz, 1.0 - 0.75 * low)
        // Layered polar caps, and the dune seas around the northern one.
        val polar = abs(ny)
        if (polar > 0.94) h += Landforms.smooth((polar - 0.94) / 0.04) * 1_500.0
        if (ny > 0.85 && ny < 0.95) {
            val wind = doubleArrayOf(nz, 0.0, -nx)
            h += Landforms.dunes(duneSeed, px, py, pz, wind[0], wind[1], wind[2], 400.0, 30.0)
        }
        return h
    }

    private val close = Noise.hashInt(seed, 20, 0, 0)
    private val relief = Relief(seed, radius, Relief.Character(hillWave = 2_200.0, hillHeight = 220.0, swellHeight = 7.0))

    private val rifts = Remembered()

    /** Metres to the Rift's line. */
    private fun toRift(nx: Double, ny: Double, nz: Double): Double = rifts.at(nx, ny, nz) { Landforms.pathDistance(nx, ny, nz, RIFT, radius) }

    /** 1 near the Rift and in the Steps, where the ground is laid down in layers. */
    private fun layered(nx: Double, ny: Double, nz: Double): Double {
        val rift = Landforms.within(toRift(nx, ny, nz), 12_000.0, 8_000.0)
        val steps = Landforms.within(Landforms.arcDistance(nx, ny, nz, STEPS[0], STEPS[1], radius), 25_000.0, 12_000.0)
        return maxOf(rift, steps)
    }

    /** 0..1: where dark sand has drifted into dunes on the northern plains. */
    private fun dunefield(px: Double, py: Double, pz: Double, ny: Double): Double {
        if (ny < 0.1) return 0.0
        return Landforms.smooth((Noise.simplex(close + 9, px / 18_000.0, py / 18_000.0, pz / 18_000.0) - 0.45) / 0.15)
    }

    override fun detail(nx: Double, ny: Double, nz: Double, base: Double): Double {
        val px = nx * radius; val py = ny * radius; val pz = nz * radius
        // The highlands, roughly: the boundary's wander is left out, this only weighs the relief.
        val high = 1.0 - Landforms.smooth((ny - 0.05) / 0.25)
        // Hills in the old south, bumps and young craters everywhere.
        var h = relief.height(px, py, pz, high)
        // Dry washes in the south.
        if (high > 0.05) h += Closeup.gullies(close + 5, px, py, pz, 5_000.0, 0.03, 16.0) * high
        // The Steps: a field of layered mesas at the Rift's east end.
        val steps = Landforms.within(Landforms.arcDistance(nx, ny, nz, STEPS[0], STEPS[1], radius), 18_000.0, 10_000.0)
        if (steps > 0.0) h += steps * Closeup.knobs(close + 6, px, py, pz, radius, 6_000.0, 0.6, 2_200.0, 650.0, 450.0)
        // Layered walls: the Rift's, and the mesas'.
        val layers = layered(nx, ny, nz)
        if (layers > 0.0) h += layers * Closeup.benches(base + h, 90.0, 0.35)
        // Dark dunes on the plains.
        val dunes = dunefield(px, py, pz, ny)
        if (dunes > 0.0) h += dunes * Landforms.dunes(close + 7, px, py, pz, nz, 0.0, -nx, 260.0, 12.0)
        return h
    }

    override fun material(nx: Double, ny: Double, nz: Double, elevation: Double, slope: Double): SurfaceMaterial {
        if (abs(ny) > 0.955) return SurfaceMaterial.ICE
        if (slope > 0.22 && layered(nx, ny, nz) > 0.3) return SurfaceMaterial.LAYERED_ROCK
        if (slope > 0.28) return SurfaceMaterial.ROCK
        val px = nx * radius; val py = ny * radius; val pz = nz * radius
        if (lowland(ny, px, py, pz) < 0.5 && craters.onRim(px, py, pz, 1.0)) return SurfaceMaterial.SCREE
        // The volcanoes' dark flanks, before the dust settles on them.
        if (distance(nx, ny, nz, GREAT_MOUNT, radius) < 26_000.0) return SurfaceMaterial.BASALT
        if (dunefield(px, py, pz, ny) > 0.5) return SurfaceMaterial.DARK_SAND
        // Dark tracks where dust devils swept the dust away.
        if (slope < 0.1 && abs(Noise.simplex(close + 10, px / 900.0, py / 2_600.0, pz / 900.0)) < 0.007) return SurfaceMaterial.DARK_SAND
        // Pale salt on the floors of a few of the south's hollows.
        if (ny < 0.0 && slope < 0.03 && elevation > -1_000.0 && elevation < 600.0 &&
            Noise.simplex(close + 11, px / 1_800.0, py / 1_800.0, pz / 1_800.0) > 0.62) return SurfaceMaterial.SALT
        return SurfaceMaterial.RED_DUST
    }

    companion object {
        /** The Steps: layered mesas at the Rift's east end. */
        val STEPS = listOf(at(-11.0, -58.0), at(-11.0, -46.0))
        val GREAT_MOUNT = at(18.0, -134.0)
        val THE_THREE = listOf(at(12.0, -113.0), at(1.0, -104.0), at(-9.0, -121.0))
        val RIFT = listOf(at(-7.0, -90.0), at(-9.0, -75.0), at(-12.0, -60.0), at(-10.0, -45.0), at(-5.0, -35.0))
    }
}

/**
 * Rubra's two little moons: too small to pull themselves round, so lumpy, cratered and grey. Timor,
 * the larger and closer, has one crater nearly a third its own size, and long parallel grooves.
 */
internal class LumpLand(seed: Int, private val radius: Double, private val lumpiness: Double, private val bigCrater: Boolean) : WorldLand {
    private val shape = Noise.hashInt(seed, 1, 0, 0)
    private val craters = Craters(Noise.hashInt(seed, 2, 0, 0), radius, Craters.scaledFrom(radius * 30.0, 1.2).map {
        // Scaled to the body: only the small crater sizes, shrunk to fit.
        Craters.CraterClass(it.cell / 30.0, it.chance, it.minRadius / 30.0, it.maxRadius / 30.0)
    }.drop(1))

    override fun height(nx: Double, ny: Double, nz: Double): Double {
        val px = nx * radius; val py = ny * radius; val pz = nz * radius
        // A potato: big slow swellings, up to a good share of its size.
        var h = Landforms.fbm(shape, px, py, pz, 1.0 / (radius * 1.2), 3) * radius * lumpiness
        h += craters.height(px, py, pz, 1.0)
        if (bigCrater) {
            h += Landforms.basin(distance(nx, ny, nz, STICKNEY, radius), radius * 0.45, radius * 0.12)
            h += Landforms.grooves(px, py, pz, GROOVE_AXIS, radius * 0.08, radius * 0.006) *
                (1.0 - Landforms.within(distance(nx, ny, nz, STICKNEY, radius), radius * 0.5, radius * 0.2))
        }
        return h
    }

    private val close = Noise.hashInt(seed, 20, 0, 0)
    private val relief = Relief(
        seed, radius,
        Relief.Character(
            hillWave = radius * 0.12, hillHeight = radius * 0.008,
            swellWave = 120.0, swellHeight = 1.5, patch = radius * 0.5,
        ),
    )

    override fun detail(nx: Double, ny: Double, nz: Double, base: Double): Double {
        val px = nx * radius; val py = ny * radius; val pz = nz * radius
        var h = relief.height(px, py, pz)
        if (bigCrater) {
            // Chains of pits along the grooves.
            val grooves = 1.0 - Landforms.within(distance(nx, ny, nz, STICKNEY, radius), radius * 0.5, radius * 0.2)
            val inGroove = Landforms.smooth((-Landforms.grooves(px, py, pz, GROOVE_AXIS, radius * 0.08, 1.0) - 0.8) / 0.15)
            if (grooves > 0.0 && inGroove > 0.0) h -= grooves * inGroove * Closeup.knobs(close, px, py, pz, radius, 90.0, 0.5, 14.0, 9.0, 6.0)
            // The Big Rock.
            h += Closeup.ridge(Closeup.chord(nx, ny, nz, BIG_ROCK, radius), 60.0, 25.0)
        } else {
            // The Saddle, a broad dip at the south pole.
            h += Landforms.basin(distance(nx, ny, nz, SADDLE, radius), radius * 0.35, radius * 0.04)
            // The Lone Boulder.
            h += Closeup.ridge(Closeup.chord(nx, ny, nz, LONE_BOULDER, radius), 36.0, 15.0)
            // A small sharp crater with bright streaks down its walls.
            val streaks = Closeup.chord(nx, ny, nz, STREAKED, radius) / STREAKED_RADIUS
            if (streaks < Craters.EJECTA_REACH) h += Craters.bowl(streaks, STREAKED_RADIUS, Double.MAX_VALUE)
        }
        return h
    }

    override fun material(nx: Double, ny: Double, nz: Double, elevation: Double, slope: Double): SurfaceMaterial {
        if (slope > 0.35) return SurfaceMaterial.ROCK
        // Bright slides down the steeper walls: Stickney's, and the streaked crater's.
        val px = nx * radius; val py = ny * radius; val pz = nz * radius
        val wall = if (bigCrater) distance(nx, ny, nz, STICKNEY, radius) < radius * 0.48 else Closeup.chord(nx, ny, nz, STREAKED, radius) < STREAKED_RADIUS * 1.1
        if (wall && slope > 0.08 && Noise.simplex(close + 1, px / 60.0, py / 60.0, pz / 60.0) > 0.1) return SurfaceMaterial.EJECTA
        return SurfaceMaterial.REGOLITH
    }

    companion object {
        /** Timor's Big Rock, and Pavor's Saddle, Lone Boulder and streaked crater. */
        val BIG_ROCK = at(8.0, 35.0)
        val SADDLE = at(-80.0, 0.0)
        val LONE_BOULDER = at(10.0, 40.0)
        val STREAKED = at(30.0, 120.0)
        const val STREAKED_RADIUS = 180.0
        val STICKNEY = at(0.0, 50.0)
        val GROOVE_AXIS = doubleArrayOf(0.64, 0.0, 0.77)
    }
}
