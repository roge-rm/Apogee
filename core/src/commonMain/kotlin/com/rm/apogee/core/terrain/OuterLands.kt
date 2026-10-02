package com.rm.apogee.core.terrain

import com.rm.apogee.core.terrain.Landforms.at
import com.rm.apogee.core.terrain.Landforms.distance
import kotlin.math.abs

/**
 * Aurantia: the only moon with real air, thick and orange, and the only other world with seas, of
 * dark glassy liquid methane. The seas lie in northern lowlands below the datum, with lakes around
 * them and rivers winding down from icy highlands. Belts of dark organic dunes circle the equator,
 * lined up with the wind. Weather has worn away most craters.
 */
internal class AurantiaLand(seed: Int, private val radius: Double) : WorldLand {
    private val detail = Noise.hashInt(seed, 1, 0, 0)
    private val seas = Noise.hashInt(seed, 2, 0, 0)
    private val rivers = Noise.hashInt(seed, 3, 0, 0)
    private val duneSeed = Noise.hashInt(seed, 4, 0, 0)
    private val craters = Craters(Noise.hashInt(seed, 5, 0, 0), radius, Craters.scaledFrom(radius, 0.15))

    /** How deep the northern basins are. Negative in the seas. */
    private fun basins(ny: Double, px: Double, py: Double, pz: Double): Double {
        val north = Landforms.smooth((ny - 0.72) / 0.18)
        val lakes = Landforms.fbm(seas, px, py, pz, 1.0 / 30_000.0, 4)
        return north * (-600.0 + lakes * 350.0) + (1.0 - north) * (lakes - 0.45).coerceAtLeast(0.0) * -1_200.0
    }

    override fun height(nx: Double, ny: Double, nz: Double): Double {
        val px = nx * radius; val py = ny * radius; val pz = nz * radius
        var h = 250.0 + Landforms.fbm(detail, px, py, pz, 1.0 / 20_000.0, 5) * 450.0
        h += basins(ny, px, py, pz)
        h += Landforms.rivers(rivers, px, py, pz, 25_000.0, 0.035, 80.0) * Landforms.smooth((ny - 0.2) / 0.3)
        // The dune belt, along the prevailing west wind.
        val equator = 1.0 - Landforms.smooth((abs(ny) - 0.3) / 0.15)
        if (equator > 0.0) h += equator * Landforms.dunes(duneSeed, px, py, pz, -nz, 0.0, nx, 1_500.0, 90.0)
        h += craters.height(px, py, pz, 1.0)
        return h
    }

    private val close = Noise.hashInt(seed, 20, 0, 0)
    private val relief = Relief(seed, radius, Relief.Character(hillWave = 2_400.0, hillHeight = 140.0, swellHeight = 4.0))

    /** How tall the dunes are here, as [height] builds them. */
    private fun dune(nx: Double, ny: Double, nz: Double, px: Double, py: Double, pz: Double): Double {
        val equator = 1.0 - Landforms.smooth((abs(ny) - 0.3) / 0.15)
        return if (equator > 0.0) equator * Landforms.dunes(duneSeed, px, py, pz, -nz, 0.0, nx, 1_500.0, 90.0) else 0.0
    }

    override fun detail(nx: Double, ny: Double, nz: Double, base: Double): Double {
        // Nothing near the sea, so its shores stay where they are.
        val dry = Landforms.smooth((base - 30.0) / 60.0)
        if (dry <= 0.0) return 0.0
        val px = nx * radius; val py = ny * radius; val pz = nz * radius
        // Ridged hills in the highlands, not in the dune belt.
        val belt = 1.0 - Landforms.smooth((abs(ny) - 0.3) / 0.15)
        var h = relief.height(px, py, pz, 1.0 - belt)
        // The Labyrinth: a plateau cut to pieces by canyons.
        val maze = Landforms.within(Closeup.chord(nx, ny, nz, LABYRINTH, radius), 30_000.0, 12_000.0)
        if (maze > 0.0) h += maze * (Closeup.gullies(close, px, py, pz, 6_000.0, 0.08, 260.0) + Closeup.benches(base + h, 70.0, 0.3))
        // Ring Lake: a dry lake bed, flat, with a raised rim.
        val lake = Closeup.chord(nx, ny, nz, RING_LAKE, radius)
        if (lake < RING_LAKE_RADIUS * 1.5) h += Closeup.ridge(abs(lake - RING_LAKE_RADIUS), 900.0, 70.0) - 60.0 * (1.0 - Landforms.smooth((lake - RING_LAKE_RADIUS * 0.85) / (RING_LAKE_RADIUS * 0.15)))
        // The Great Dune, three times any other.
        h += Closeup.ridge(Landforms.arcDistance(nx, ny, nz, GREAT_DUNE[0], GREAT_DUNE[1], radius), 3_200.0, 250.0)
        return h * dry
    }

    override fun material(nx: Double, ny: Double, nz: Double, elevation: Double, slope: Double): SurfaceMaterial {
        if (slope > 0.3) return SurfaceMaterial.ICE
        val px = nx * radius; val py = ny * radius; val pz = nz * radius
        if (abs(ny) < 0.3) {
            // Bright flat ground between the dunes.
            if (elevation > 30.0 && dune(nx, ny, nz, px, py, pz) < 6.0) return SurfaceMaterial.SALT
            return SurfaceMaterial.ORGANIC_SAND
        }
        // Sea floors and beaches: dark sediment.
        if (elevation < 30.0) return SurfaceMaterial.ORGANIC_SAND
        // A dry lake bed's pale floor.
        if (Closeup.chord(nx, ny, nz, RING_LAKE, radius) < RING_LAKE_RADIUS * 0.85) return SurfaceMaterial.SALT
        return SurfaceMaterial.THOLIN
    }

    companion object {
        /** Ring Lake, the Labyrinth and the Great Dune. */
        val RING_LAKE = at(50.0, 60.0)
        const val RING_LAKE_RADIUS = 9_000.0
        val LABYRINTH = at(-50.0, 100.0)
        val GREAT_DUNE = listOf(at(1.6, -31.5), at(2.4, -28.5))
        /** On the shore of the great northern sea. */
        val NORTH_SHORE = at(72.0, 20.0)
        /** In the equatorial dune belt. */
        val DUNES = at(5.0, -40.0)
    }
}

/**
 * Fons: small, and the brightest thing in the system, snowed on by its own geysers. Old cratered
 * north, smooth young south, and at the south pole four long parallel cracks, the Stripes, venting
 * ice into space.
 */
internal class FonsLand(seed: Int, private val radius: Double) : WorldLand {
    private val detail = Noise.hashInt(seed, 1, 0, 0)
    private val craters = Craters(Noise.hashInt(seed, 2, 0, 0), radius, Craters.scaledFrom(radius * 4.0, 1.0).map {
        Craters.CraterClass(it.cell / 4.0, it.chance, it.minRadius / 4.0, it.maxRadius / 4.0)
    })

    override fun height(nx: Double, ny: Double, nz: Double): Double {
        val px = nx * radius; val py = ny * radius; val pz = nz * radius
        var h = Landforms.fbm(detail, px, py, pz, 1.0 / 4_000.0, 4) * 150.0
        // Cratered north, smooth south.
        h += craters.height(px, py, pz, Landforms.smooth((ny + 0.2) / 0.4))
        if (ny < -0.6) {
            for (k in 0 until STRIPES) {
                val offset = (k - 1.5) * 0.09
                val d = abs(pz / radius - offset) * radius
                val alongOk = abs(px / radius) < 0.35
                if (alongOk) h += (Landforms.canyon(d, 700.0, 250.0) + 90.0 * Landforms.within(d, 450.0, 400.0)) * Landforms.smooth((-ny - 0.6) / 0.1)
            }
        }
        return h
    }

    private val close = Noise.hashInt(seed, 20, 0, 0)
    private val relief = Relief(seed, radius, Relief.Character(hillWave = 600.0, hillHeight = 40.0, swellWave = 150.0, swellHeight = 2.0, patch = 6_000.0))

    /** Metres to the nearest Stripe, or far if not in the south. */
    private fun toStripe(nx: Double, ny: Double, nz: Double): Double {
        if (ny > -0.55 || abs(nx) > 0.35) return Double.MAX_VALUE
        var best = Double.MAX_VALUE
        for (k in 0 until STRIPES) best = minOf(best, abs(nz - (k - 1.5) * 0.09) * radius)
        return best
    }

    override fun detail(nx: Double, ny: Double, nz: Double, base: Double): Double {
        val px = nx * radius; val py = ny * radius; val pz = nz * radius
        // Hills in the middle latitudes, smooth at the poles.
        var h = relief.height(px, py, pz, 1.0 - Landforms.smooth((abs(ny) - 0.5) / 0.3))
        // The Stripes' walls step down in ledges.
        val stripe = toStripe(nx, ny, nz)
        if (stripe < 700.0) h += Landforms.within(stripe, 450.0, 250.0) * Closeup.benches(base + h, 40.0, 0.3)
        // Ice House, a block of ice standing alone.
        h += Landforms.mesa(Closeup.chord(nx, ny, nz, ICE_HOUSE, radius), 14.0, 20.0, 4.0)
        return h
    }

    override fun material(nx: Double, ny: Double, nz: Double, elevation: Double, slope: Double): SurfaceMaterial {
        // Coarse ice frozen out of the spray, along the Stripes.
        if (toStripe(nx, ny, nz) < 500.0) return SurfaceMaterial.VENT_ICE
        return if (slope > 0.35) SurfaceMaterial.ICE else SurfaceMaterial.SNOW
    }

    companion object {
        /** Ice House, an ice block standing alone near the south pole. */
        val ICE_HOUSE = at(-75.0, 40.0)
        const val STRIPES = 4
        val SOUTH_POLE = at(-88.0, 0.0)
    }
}

/**
 * Aversa: a captured wanderer orbiting backwards. Its western half is melon skin (shallow round pits
 * packed edge to edge). The south is capped in pinkish nitrogen ice streaked dark by geyser soot.
 * Young, so hardly any craters.
 */
internal class AversaLand(seed: Int, private val radius: Double) : WorldLand {
    private val detail = Noise.hashInt(seed, 1, 0, 0)
    private val melon = Noise.hashInt(seed, 2, 0, 0)
    private val streaks = Noise.hashInt(seed, 3, 0, 0)
    private val craters = Craters(Noise.hashInt(seed, 4, 0, 0), radius, Craters.scaledFrom(radius, 0.1))

    override fun height(nx: Double, ny: Double, nz: Double): Double {
        val px = nx * radius; val py = ny * radius; val pz = nz * radius
        var h = Landforms.fbm(detail, px, py, pz, 1.0 / 20_000.0, 4) * 250.0
        val west = Landforms.smooth((-nx + 0.1) / 0.3) * (1.0 - Landforms.smooth((-ny - 0.3) / 0.2))
        if (west > 0.0) h += west * (melonCells(px, py, pz) * 400.0 - 200.0)
        h += craters.height(px, py, pz, 1.0)
        return h
    }

    private val close = Noise.hashInt(seed, 20, 0, 0)
    private val relief = Relief(seed, radius, Relief.Character(hillWave = 1_500.0, hillHeight = 50.0, swellHeight = 4.0))

    private val melons = Remembered()

    /** The melon-skin pits: 0 in one's middle, 1 on the ridges between. */
    private fun melonCells(px: Double, py: Double, pz: Double): Double = melons.at(px, py, pz) { Landforms.cells(melon, px, py, pz, 12_000.0) }

    /** Whether the place is in one of the cap's geyser fields. */
    private fun geyserField(nx: Double, ny: Double, nz: Double, px: Double, py: Double, pz: Double): Boolean =
        ny < -0.45 && (Noise.simplex(close + 2, px / 2_500.0, py / 2_500.0, pz / 2_500.0) > 0.62 || Closeup.chord(nx, ny, nz, DARK_PLUME, radius) < 250.0)

    override fun detail(nx: Double, ny: Double, nz: Double, base: Double): Double {
        val px = nx * radius; val py = ny * radius; val pz = nz * radius
        var h = relief.height(px, py, pz)
        // Knobs along the ridges between the melon-skin pits.
        val west = Landforms.smooth((-nx + 0.1) / 0.3) * (1.0 - Landforms.smooth((-ny - 0.3) / 0.2))
        if (west > 0.0) {
            val ridge = melonCells(px, py, pz)
            if (ridge > 0.5) h += west * (ridge - 0.5) * 2.0 * Closeup.knobs(close, px, py, pz, radius, 1_200.0, 0.6, 250.0, 120.0, 150.0)
        }
        // The Walled Plains: flat floors flooded by ice lava, with walls stepping down to them.
        val walled = Closeup.chord(nx, ny, nz, WALLED_PLAINS, radius)
        if (walled < 40_000.0) {
            val inside = 1.0 - Landforms.smooth((walled - 25_000.0) / 6_000.0)
            h += -180.0 * inside + Landforms.within(abs(walled - 26_000.0), 3_000.0, 2_000.0) * Closeup.benches(base + h, 60.0, 0.3)
        }
        return h
    }

    override fun material(nx: Double, ny: Double, nz: Double, elevation: Double, slope: Double): SurfaceMaterial {
        if (ny < -0.45) {
            val px = nx * radius; val py = ny * radius; val pz = nz * radius
            if (geyserField(nx, ny, nz, px, py, pz)) return SurfaceMaterial.VENT_ICE
            // The fallout from dark plumes, streaked downwind.
            val streak = abs(Landforms.fbm(streaks, px * 0.3, py, pz * 3.0, 1.0 / 20_000.0, 2))
            if (streak < 0.05) return SurfaceMaterial.THOLIN
            // Fresh frost toward the pole.
            return if (ny < -0.8) SurfaceMaterial.FROST else SurfaceMaterial.NITROGEN_ICE
        }
        return if (slope > 0.3) SurfaceMaterial.ROCK else SurfaceMaterial.ICE
    }

    companion object {
        /** The Dark Plume, a geyser near Lassell, and the Walled Plains. */
        val DARK_PLUME = at(-50.8, 30.9)
        val WALLED_PLAINS = at(10.0, 60.0)
        val SOUTH_CAP = at(-50.0, 30.0)
    }
}

/**
 * Ultima, the last world: small and strange. The Heart is a huge low plain of frozen nitrogen
 * churning in big cells, with mountains of water ice kilometres high floating along its west edge.
 * A dark reddened belt wraps the equator, the east is bladed with knife-edge ridges, frost whitens
 * the north, and the rest is old craters.
 */
internal class UltimaLand(seed: Int, private val radius: Double) : WorldLand {
    private val detail = Noise.hashInt(seed, 1, 0, 0)
    private val cellSeed = Noise.hashInt(seed, 2, 0, 0)
    private val blades = Noise.hashInt(seed, 3, 0, 0)
    private val peaks = Noise.hashInt(seed, 4, 0, 0)
    private val craters = Craters(Noise.hashInt(seed, 5, 0, 0), radius, Craters.scaledFrom(radius, 0.7))

    private val hearts = Remembered()
    private val heartCells = Remembered()

    /** The Heart's cells: 0 in one's middle, 1 on the edges between. */
    private fun heartCell(px: Double, py: Double, pz: Double): Double = heartCells.at(px, py, pz) { Landforms.cells(cellSeed, px, py, pz, 8_000.0) }

    /** 1 inside the Heart, 0 outside: two round lobes meeting in a point at the bottom. */
    fun heart(nx: Double, ny: Double, nz: Double): Double = hearts.at(nx, ny, nz) { heartAt(nx, ny, nz) }

    private fun heartAt(nx: Double, ny: Double, nz: Double): Double {
        val a = distance(nx, ny, nz, LOBE_WEST, radius)
        val b = distance(nx, ny, nz, LOBE_EAST, radius)
        val tip = Landforms.arcDistance(nx, ny, nz, LOBE_WEST, TIP, radius).coerceAtMost(Landforms.arcDistance(nx, ny, nz, LOBE_EAST, TIP, radius))
        val lobes = minOf(a, b) - LOBE_RADIUS
        val point = tip - LOBE_RADIUS * 0.55 * (1.0 - (distance(nx, ny, nz, TIP, radius) / (LOBE_RADIUS * 2.2)).coerceIn(0.0, 1.0))
        return 1.0 - Landforms.smooth(minOf(lobes, point) / 3_000.0)
    }

    override fun height(nx: Double, ny: Double, nz: Double): Double {
        val px = nx * radius; val py = ny * radius; val pz = nz * radius
        val inHeart = heart(nx, ny, nz)
        var h = Landforms.fbm(detail, px, py, pz, 1.0 / 12_000.0, 4) * 600.0 * (1.0 - inHeart)
        h += craters.height(px, py, pz, 1.0 - inHeart)
        // The Heart: low, flat, with its cells.
        h -= inHeart * (1_800.0 - (if (inHeart > 0.0) heartCell(px, py, pz) else 0.0) * 60.0)
        // Ice mountains along its west edge.
        val west = distance(nx, ny, nz, LOBE_WEST, radius)
        if (west > LOBE_RADIUS * 0.7 && west < LOBE_RADIUS * 1.6) {
            // Faded in and out at the band's edges, so a mountain cut by them leaves no cliff.
            val band = Landforms.smooth((west - LOBE_RADIUS * 0.7) / (LOBE_RADIUS * 0.1)) * (1.0 - Landforms.smooth((west - LOBE_RADIUS * 1.5) / (LOBE_RADIUS * 0.1)))
            Landforms.scattered(peaks, px, py, pz, radius, 9_000.0, 0.5) { d, size, _ ->
                h += band * Landforms.mesa(d, 1_500.0 + 2_500.0 * size, 2_000.0 + 1_500.0 * size, 1_200.0)
            }
        }
        // Bladed ground in the east.
        val east = Landforms.smooth((nz - 0.3) / 0.2) * (1.0 - inHeart)
        if (east > 0.0) h += east * Landforms.ridged(blades, px * 2.0, py * 0.4, pz, 1.0 / 4_000.0, 3) * 900.0
        return h
    }

    private val close = Noise.hashInt(seed, 20, 0, 0)
    private val relief = Relief(seed, radius, Relief.Character(hillWave = 1_800.0, hillHeight = 110.0, swellHeight = 4.0))

    override fun detail(nx: Double, ny: Double, nz: Double, base: Double): Double {
        val px = nx * radius; val py = ny * radius; val pz = nz * radius
        val inHeart = heart(nx, ny, nz)
        var h = relief.height(px, py, pz, 1.0 - inHeart)
        if (inHeart > 0.0) {
            // Troughs along the cells' edges, and pits where the ice has gone to vapour.
            h -= inHeart * heartCell(px, py, pz) * 45.0
            h -= inHeart * Closeup.knobs(close, px, py, pz, radius, 900.0, 0.35, 120.0, 25.0, 150.0)
        }
        // Finer blades among the big ones.
        val east = Landforms.smooth((nz - 0.3) / 0.2) * (1.0 - inHeart)
        if (east > 0.0) h += east * Landforms.ridged(blades + 7, px * 2.0, py * 0.4, pz, 1.0 / 400.0, 2) * 60.0
        // The Hollow Mountain: a broad dome with a pit for a top, hummocky all over.
        val hollow = Closeup.chord(nx, ny, nz, HOLLOW_MOUNTAIN, radius)
        if (hollow < 26_000.0) {
            h += Landforms.shield(hollow, 22_000.0, 3_500.0, 3_000.0, 1_400.0)
            h += Closeup.knobs(close + 1, px, py, pz, radius, 2_000.0, 0.6, 500.0, 220.0, 400.0) * Landforms.within(hollow, 18_000.0, 6_000.0)
        }
        return h
    }

    override fun material(nx: Double, ny: Double, nz: Double, elevation: Double, slope: Double): SurfaceMaterial {
        if (heart(nx, ny, nz) > 0.5) return SurfaceMaterial.NITROGEN_ICE
        if (slope > 0.3) return SurfaceMaterial.ICE
        if (abs(ny) < 0.3) return SurfaceMaterial.THOLIN
        // Frost whitening the north.
        if (ny > 0.75) return SurfaceMaterial.FROST
        if (ny > 0.6) return SurfaceMaterial.NITROGEN_ICE
        return SurfaceMaterial.REGOLITH
    }

    companion object {
        /** The Hollow Mountain, south of the Heart. */
        val HOLLOW_MOUNTAIN = at(-30.0, 170.0)
        val LOBE_WEST = at(25.0, 166.0)
        val LOBE_EAST = at(25.0, 188.0)
        val TIP = at(-18.0, 177.0)
        const val LOBE_RADIUS = 28_000.0
        /** The middle of the Heart. */
        val HEART = at(15.0, 177.0)
    }
}

/**
 * Portitor, Ultima's companion, half its size. Grey and cratered, with the Belt, a band of canyons
 * all the way round near the equator where it split as its insides froze. Its north pole is
 * stained dark red.
 */
internal class PortitorLand(seed: Int, private val radius: Double) : WorldLand {
    private val detail = Noise.hashInt(seed, 1, 0, 0)
    private val craters = Craters(Noise.hashInt(seed, 2, 0, 0), radius, Craters.scaledFrom(radius, 0.9))

    override fun height(nx: Double, ny: Double, nz: Double): Double {
        val px = nx * radius; val py = ny * radius; val pz = nz * radius
        var h = Landforms.fbm(detail, px, py, pz, 1.0 / 8_000.0, 4) * 400.0
        // The Belt: three parallel canyons wandering round the world.
        val wander = Landforms.fbm(detail + 9, px, py, pz, 1.0 / 40_000.0, 2) * 0.05
        for (k in -1..1) {
            val d = abs(ny - 0.05 * k - wander) * radius
            h += Landforms.canyon(d, 2_500.0, 1_500.0 - 300.0 * abs(k))
        }
        // The south is smooth plains with fewer craters.
        h += craters.height(px, py, pz, 0.35 + 0.65 * Landforms.smooth((ny + 0.15) / 0.1))
        return h
    }

    private val close = Noise.hashInt(seed, 20, 0, 0)
    private val relief = Relief(seed, radius, Relief.Character(hillWave = 1_500.0, hillHeight = 90.0, swellHeight = 4.0))

    /** 1 along the Belt, where the canyon walls are. */
    private fun belt(ny: Double, px: Double, py: Double, pz: Double): Double {
        val wander = Landforms.fbm(detail + 9, px, py, pz, 1.0 / 40_000.0, 2) * 0.05
        var w = 0.0
        for (k in -1..1) w = maxOf(w, Landforms.within(abs(ny - 0.05 * k - wander) * radius, 2_000.0, 800.0))
        return w
    }

    override fun detail(nx: Double, ny: Double, nz: Double, base: Double): Double {
        val px = nx * radius; val py = ny * radius; val pz = nz * radius
        var h = relief.height(px, py, pz)
        // The Belt's walls stepping down in ledges.
        val b = belt(ny, px, py, pz)
        if (b > 0.0) h += b * Closeup.benches(base + h, 100.0, 0.35)
        // The Moat Mountain: a block standing in a trough of its own.
        val moat = Closeup.chord(nx, ny, nz, MOAT_MOUNTAIN, radius)
        if (moat < 16_000.0) h += Landforms.mesa(moat, 4_000.0, 3_000.0, 2_500.0) + Landforms.canyon(abs(moat - 9_500.0), 3_000.0, 700.0)
        // The White Crater, young and sharp.
        val white = Closeup.chord(nx, ny, nz, WHITE_CRATER, radius) / WHITE_RADIUS
        if (white < Craters.EJECTA_REACH) h += Craters.bowl(white, WHITE_RADIUS, Double.MAX_VALUE)
        return h
    }

    override fun material(nx: Double, ny: Double, nz: Double, elevation: Double, slope: Double): SurfaceMaterial {
        if (ny > 0.8) return SurfaceMaterial.THOLIN
        val px = nx * radius; val py = ny * radius; val pz = nz * radius
        if (slope > 0.3) return if (belt(ny, px, py, pz) > 0.3) SurfaceMaterial.LAYERED_ROCK else SurfaceMaterial.ROCK
        // Bright rays round the White Crater.
        val white = Closeup.chord(nx, ny, nz, WHITE_CRATER, radius) / WHITE_RADIUS
        if (white < 1.5) return SurfaceMaterial.EJECTA
        if (white < 6.0) {
            val dx = nx - WHITE_CRATER[0]; val dy = ny - WHITE_CRATER[1]; val dz = nz - WHITE_CRATER[2]
            val d = kotlin.math.sqrt(dx * dx + dy * dy + dz * dz)
            if (abs(Noise.simplex(close + 1, dx / d * 30.0, dy / d * 30.0, dz / d * 30.0)) > 0.55 + 0.05 * (white - 1.5)) return SurfaceMaterial.EJECTA
        }
        return SurfaceMaterial.REGOLITH
    }

    companion object {
        /** The Moat Mountain and the White Crater. */
        val MOAT_MOUNTAIN = at(-40.0, 30.0)
        val WHITE_CRATER = at(20.0, -60.0)
        const val WHITE_RADIUS = 2_500.0
        val BELT = at(0.0, 0.0)
    }
}
