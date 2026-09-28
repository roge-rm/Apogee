package com.rm.apogee.core.terrain

import com.rm.apogee.core.terrain.Noise.simplex
import kotlin.math.abs
import kotlin.math.floor
import kotlin.math.sqrt
import com.rm.apogee.core.math.StrictMath

/**
 * The shape of Terra's land: everything above the sea that makes one place different from another.
 *
 * The continents come first, from [TerrainField], and they fix the coastlines. This adds what
 * stands on them, in layers:
 *
 * 1. **Regions**: broad fields, tens to hundreds of kilometres across, that say what kind of
 *    country a place is. How mountainous, how hilly, whether it's plateau, how wet and how warm.
 *    They're domain-warped so their edges wander instead of following the noise lattice.
 * 2. **Landforms**: a height profile for each kind of country, weighted by the regions. Ridged
 *    ranges along mountain belts, rolling hills, plateaus cut into terraces, mesas, dunes running
 *    across the wind, and volcanic cones. The weights blend smoothly, and the blends are the land
 *    in between. Foothills are what a mountain belt's weight looks like as it fades out.
 * 3. **Carving**: river valleys and canyons along the zero crossings of a warped noise field,
 *    shaped by where they run. They're V-cut in the mountains, flat-floored in the lowlands, and
 *    slot canyons through plateau country. They're dry for now. Water comes with the sea's own
 *    milestone.
 * 4. **Detail**: small bumps a wheel can feel, and rock outcrops on the hills.
 *
 * Then [material] reads the same fields to decide what the ground is made of.
 *
 * Everything is worked out from local values, with no objects and no shared scratch, because this
 * gets called by the collider's tile thread, the renderer's chunk workers and the simulation all at
 * once, tens of thousands of times a second. Allocating here turned into garbage collections that
 * stalled the tick.
 */
internal class TerraLand(
    seed: Int,
    private val radius: Double,
    homeX: Double, homeY: Double, homeZ: Double,
    /**
     * Where the launch complex stands now, if it's not at home, so the country around it is calm
     * too.
     */
    padX: Double = homeX, padY: Double = homeY, padZ: Double = homeZ,
) {

    private val warpSeedX = Noise.hashInt(seed, 11, 0, 0)
    private val warpSeedY = Noise.hashInt(seed, 12, 0, 0)
    private val warpSeedZ = Noise.hashInt(seed, 13, 0, 0)
    private val beltSeed = Noise.hashInt(seed, 21, 0, 0)
    private val hillSeed = Noise.hashInt(seed, 22, 0, 0)
    private val plateauSeed = Noise.hashInt(seed, 23, 0, 0)
    private val wetSeed = Noise.hashInt(seed, 24, 0, 0)
    private val warmSeed = Noise.hashInt(seed, 25, 0, 0)
    private val ridgeSeed = Noise.hashInt(seed, 31, 0, 0)
    private val rollSeed = Noise.hashInt(seed, 32, 0, 0)
    private val mesaSeed = Noise.hashInt(seed, 33, 0, 0)
    private val duneSeed = Noise.hashInt(seed, 34, 0, 0)
    private val riverSeed = Noise.hashInt(seed, 41, 0, 0)
    private val riverWarpSeed = Noise.hashInt(seed, 42, 0, 0)
    private val detailSeed = Noise.hashInt(seed, 51, 0, 0)
    private val volcanoSeed = Noise.hashInt(seed, 61, 0, 0)
    private val patchSeed = Noise.hashInt(seed, 71, 0, 0)

    private val hx = homeX * radius
    private val hy = homeY * radius
    private val hz = homeZ * radius
    private val px = padX * radius
    private val py = padY * radius
    private val pz = padZ * radius
    private val padEastX: Double
    private val padEastZ: Double

    /**
     * The runway's heading: east at home, the way the planet's spin around +Y carries the ground.
     * The basin is stretched out this way.
     */
    private val eastX: Double
    private val eastZ: Double

    init {
        // (0, 1, 0) x home, normalised. y is always zero.
        val ex = homeZ
        val ez = -homeX
        val l = sqrt(ex * ex + ez * ez).coerceAtLeast(1e-12)
        eastX = ex / l
        eastZ = ez / l
        val pl = sqrt(padZ * padZ + padX * padX).coerceAtLeast(1e-12)
        padEastX = padZ / pl
        padEastZ = -padX / pl
    }

    // --- regions --------------------------------------------------------------

    /** Warp offsets only apply to region lookups, which gives natural-looking borders cheaply. */
    private fun warp(seed: Int, x: Double, y: Double, z: Double): Double =
        simplex(seed, x * WARP_FREQUENCY, y * WARP_FREQUENCY, z * WARP_FREQUENCY) * WARP_METRES

    /**
     * Mountain belts, 0..1: long ranges along the zero lines of a very broad field, like the
     * crumple zones where plates meet. It's 1 on the crest line and fades out over tens of
     * kilometres into foothills.
     */
    private fun mountains(qx: Double, qy: Double, qz: Double, base: Double): Double {
        val belt = 1.0 - abs(simplex(beltSeed, qx * BELT_FREQUENCY, qy * BELT_FREQUENCY, qz * BELT_FREQUENCY))
        // Higher ground is more often mountain ground, so the continents' own uplands carry the
        // ranges instead of the coastal plains.
        val lift = (base / 2_500.0).coerceIn(0.0, 0.25)
        return Noise.smoothstep(((belt + lift - 0.72) / 0.22).coerceIn(0.0, 1.0))
    }

    private fun hilliness(qx: Double, qy: Double, qz: Double): Double =
        Noise.smoothstep(((simplex(hillSeed, qx * HILL_REGION_FREQUENCY, qy * HILL_REGION_FREQUENCY, qz * HILL_REGION_FREQUENCY) + 0.25) / 0.9).coerceIn(0.0, 1.0))

    private fun plateau(qx: Double, qy: Double, qz: Double): Double =
        Noise.smoothstep(((simplex(plateauSeed, qx * PLATEAU_FREQUENCY, qy * PLATEAU_FREQUENCY, qz * PLATEAU_FREQUENCY) - 0.25) / 0.3).coerceIn(0.0, 1.0))

    /**
     * Moisture, 0..1. Broad weather noise, wetter near coasts, and drier in the subtropical belts
     * around 25 degrees either side of the equator. That's where Earth keeps its great deserts, for
     * the same reason.
     */
    private fun moisture(qx: Double, qy: Double, qz: Double, sinLatitude: Double, landness: Double): Double {
        val weather = simplex(wetSeed, qx * WET_FREQUENCY, qy * WET_FREQUENCY, qz * WET_FREQUENCY) * 0.5 + 0.5 +
            edge(wetSeed + 3, qx, qy, qz)
        val coast = (1.0 - landness) * 0.35
        val band = (abs(sinLatitude) - 0.42) / 0.16
        val subtropics = 0.3 / (1.0 + band * band)
        return (weather + coast - subtropics).coerceIn(0.0, 1.0)
    }

    /** Temperature, 0..1: cold at the poles and up high. */
    private fun warmth(qx: Double, qy: Double, qz: Double, sinLatitude: Double, elevation: Double): Double {
        val weather = simplex(warmSeed, qx * WET_FREQUENCY, qy * WET_FREQUENCY, qz * WET_FREQUENCY) * 0.08 +
            edge(warmSeed + 3, qx, qy, qz)
        return (1.05 - abs(sinLatitude) * 1.15 - elevation / 6_500.0 + weather).coerceIn(0.0, 1.0)
    }

    /**
     * A few kilometres of noise on a climate field, so the boundaries between kinds of country
     * interleave and wander. Without it they followed latitude and height lines, and looked like
     * they'd been drawn with a ruler.
     */
    private fun edge(seed: Int, qx: Double, qy: Double, qz: Double): Double =
        simplex(seed, qx * EDGE_FREQUENCY, qy * EDGE_FREQUENCY, qz * EDGE_FREQUENCY) * 0.07 +
            simplex(seed + 1, qx * EDGE_FREQUENCY * 3.3, qy * EDGE_FREQUENCY * 3.3, qz * EDGE_FREQUENCY * 3.3) * 0.03

    /**
     * 0 at the launch complex, rising to 1 by [HOME_BASIN_OUTER] away.
     *
     * This uses true distance, so the basin stays centred on the pad, with its radius wobbling by
     * several kilometres so the edge wanders like any other. A plain radius drew a perfect circle
     * through everything that depended on it, and measuring in warped coordinates moved the whole
     * basin off the pad.
     */
    private fun homeCalm(px: Double, py: Double, pz: Double): Double =
        kotlin.math.min(calmAround(px, py, pz, hx, hy, hz, eastX, eastZ), calmAround(px, py, pz, this.px, this.py, this.pz, padEastX, padEastZ))

    /** [homeCalm] around one centre, with its runway running along [eastX], [eastZ]. */
    private fun calmAround(px: Double, py: Double, pz: Double, hx: Double, hy: Double, hz: Double, eastX: Double, eastZ: Double): Double {
        val dx = px - hx; val dy = py - hy; val dz = pz - hz
        // Stretched out ahead of the runway. A plane climbing out of the Cape should have open
        // country in front of it, not a mountain range fifteen kilometres off the end of the
        // tarmac.
        val along = dx * eastX + dz * eastZ
        val stretched = if (along > 0.0) along * (1.0 / RUNWAY_STRETCH - 1.0) else 0.0
        val sx = dx + eastX * stretched
        val sz = dz + eastZ * stretched
        val d = sqrt(sx * sx + dy * dy + sz * sz) +
            simplex(warmSeed + 9, px * BASIN_EDGE_FREQUENCY, py * BASIN_EDGE_FREQUENCY, pz * BASIN_EDGE_FREQUENCY) * BASIN_EDGE_METRES
        return Noise.smoothstep(((d - HOME_BASIN_INNER) / (HOME_BASIN_OUTER - HOME_BASIN_INNER)).coerceIn(0.0, 1.0))
    }

    // --- landforms --------------------------------------------------------------

    /**
     * The height of the land at a point, given the continents' [base] and how far inland it is
     * ([landness], 0 on the shore). [nx], [ny], [nz] is the unit direction.
     */
    fun height(nx: Double, ny: Double, nz: Double, base: Double, landness: Double): Double {
        val px = nx * radius; val py = ny * radius; val pz = nz * radius
        val qx = px + warp(warpSeedX, px, py, pz)
        val qy = py + warp(warpSeedY, px, py, pz)
        val qz = pz + warp(warpSeedZ, px, py, pz)

        // The launch complex sits in a broad basin of gentle country. The high ground rises beyond
        // it, where it makes a horizon, instead of all around it, where it made a pit with the
        // runway running into a wall.
        val calm = homeCalm(px, py, pz)
        val mountain = mountains(qx, qy, qz, base) * landness * calm
        val hills = hilliness(qx, qy, qz) * (0.6 + 0.4 * calm)
        // Every landform fades in from the shore. Anything added at full strength right up to the
        // coast ends there in a cliff when the continents drop below the sea. Plateaus did, by two
        // hundred metres.
        val plateau = plateau(qx, qy, qz) * (1.0 - mountain) * calm * landness
        val wet = moisture(qx, qy, qz, ny, landness)
        val dry = Noise.smoothstep(((0.42 - wet) / 0.2).coerceIn(0.0, 1.0))

        var h = base

        // Rolling hills everywhere, stronger where the region says hilly. The lowlands keep a
        // gentle swell even at the minimum, which is what makes height and drift readable from the
        // ground.
        h += fbm(rollSeed, px, py, pz, ROLL_FREQUENCY, 4) * (35.0 + 140.0 * hills) * landness

        // Mountains: ridged noise, with each octave weighted by the one above it, so crests are
        // sharp and the valleys between them stay smooth. That's the difference between a mountain
        // range and a field of bumps.
        //
        // Every skip below is at exactly zero weight. Skipping at "small", meaning anything above
        // zero, leaves a step the size of the weight times the amplitude wherever the test flips,
        // and a mountain range at a thousandth of full strength is still two and a half metres
        // high.
        if (mountain > 0.0) {
            // A light warp of its own, not the regions' big one. Ridged noise on perfectly straight
            // coordinates lays some crests out in long parallel lines. On the regions' twenty-five
            // kilometre warp it got sheared into streaks like brushed cloth. A couple of kilometres
            // bends the crests without dragging them.
            val fine = simplex(ridgeSeed + 17, px * RIDGE_WARP_FREQUENCY, py * RIDGE_WARP_FREQUENCY, pz * RIDGE_WARP_FREQUENCY) * RIDGE_WARP_METRES
            h += ridged(ridgeSeed, px + fine, py - fine, pz + fine, RIDGE_FREQUENCY, 6) * MOUNTAIN_METRES * mountain
        }

        // Plateaus: the ground raised and cut into terraces, so it climbs in steps with cliffs
        // between them. Mesas stand out on their own in dry plateau country.
        if (plateau > 0.0) {
            h += 220.0 * plateau
            // The step height changes from place to place, and the steps are cut into a wobbled
            // copy of the ground. Terraces cut straight into a smoothly tilted plain follow its
            // contours, and those are dead straight lines tens of kilometres long.
            val step = TERRACE_METRES * (0.7 + 0.6 * (simplex(mesaSeed + 2, qx * TERRACE_VARY_FREQUENCY, qy * TERRACE_VARY_FREQUENCY, qz * TERRACE_VARY_FREQUENCY) * 0.5 + 0.5))
            val wobble = simplex(mesaSeed + 3, px * TERRACE_WOBBLE_FREQUENCY, py * TERRACE_WOBBLE_FREQUENCY, pz * TERRACE_WOBBLE_FREQUENCY) * step * 0.35
            val t = (h + wobble) / step
            val f = t - floor(t)
            // Flat for most of each step, then a steep rise, like the tread and riser of a
            // staircase instead of a ramp.
            val terraced = (floor(t) + Noise.smoothstep(((f - 0.72) / 0.28).coerceIn(0.0, 1.0))) * step - wobble
            h += (terraced - h) * plateau
            val mesa = simplex(mesaSeed, px * MESA_FREQUENCY, py * MESA_FREQUENCY, pz * MESA_FREQUENCY)
            h += Noise.smoothstep(((mesa - 0.45) / 0.08).coerceIn(0.0, 1.0)) * 140.0 * plateau * dry
        }

        // Dunes where it's dry, low and flat: ridges running across the prevailing wind, which
        // blows from the west, so they're long north to south and closely spaced east to west.
        val duneWeight = dry * (1.0 - mountain) * (1.0 - plateau) * landness
        if (duneWeight > 0.0) {
            // Along the wind means along a parallel, so longitude, in metres.
            val ring = sqrt(px * px + pz * pz)
            val along = StrictMath.atan2(pz, px) * ring
            val d = simplex(duneSeed, along * DUNE_ALONG, py * DUNE_ACROSS, 0.5)
            val crest = 1.0 - abs(d)
            h += crest * crest * DUNE_METRES * duneWeight
        }

        h += volcanoes(px, py, pz, landness)  // already kept clear of home

        // Valleys and canyons, carved last so they cut through everything above. Never below a few
        // metres above the sea, or the coast would fill up with inlets that are dry and below sea
        // level.
        val carve = valley(px, py, pz, mountain, plateau, dry, landness) * (0.6 + 0.4 * calm)
        if (carve > 0.0) h = kotlin.math.max(h - carve, kotlin.math.min(h, SHORE_FLOOR_METRES))

        // Small bumps a wheel can feel, and outcrops on the high ground. Half a metre in the
        // lowlands, and more only in the mountains. At 1.8 m on a forty-five metre wavelength, a
        // rover at twenty metres a second hit a bump every two seconds with more than a g of heave
        // in it, got thrown into the air on every crest, and rolled over within half a kilometre of
        // the pad. Rough going belongs in rough country.
        h += fbm(detailSeed, px, py, pz, DETAIL_FREQUENCY, 2) * (0.5 + 1.0 * mountain) * landness
        val outcrops = Noise.smoothstep(((mountain + hills * 0.35 - 0.1) / 0.3).coerceIn(0.0, 1.0))
        if (outcrops > 0.0) {
            h += ridged(detailSeed + 1, px, py, pz, OUTCROP_FREQUENCY, 2) * 22.0 * outcrops * landness
        }
        return h
    }

    /**
     * How deep a valley is cut here, in metres.
     *
     * Channels follow the zero lines of a warped noise field, which wander and branch like real
     * drainage without having to simulate it. Their profile depends on the country: V-shaped and
     * deep in mountains, broad and flat-floored in the lowlands, and slot canyons in dry plateau.
     */
    private fun valley(
        px: Double, py: Double, pz: Double,
        mountain: Double, plateau: Double, dry: Double, landness: Double,
    ): Double {
        val channel = channel(px, py, pz)
        val lowland = VALLEY_LOW_METRES * (1.0 - Noise.smoothstep((channel / 0.09).coerceIn(0.0, 1.0)))
        val vShape = (1.0 - (channel / 0.16).coerceIn(0.0, 1.0))
        val mountainCut = VALLEY_MOUNTAIN_METRES * vShape * vShape * mountain
        val slot = 1.0 - Noise.smoothstep(((channel - 0.012) / 0.012).coerceIn(0.0, 1.0))
        val canyon = CANYON_METRES * slot * plateau * (0.4 + 0.6 * dry)
        return (lowland + mountainCut + canyon) * Noise.smoothstep((landness * 1.5).coerceIn(0.0, 1.0))
    }

    /** A distance-like channel value: 0 on a valley's line, growing away from it. */
    private fun channel(px: Double, py: Double, pz: Double): Double {
        val w = simplex(riverWarpSeed, px * RIVER_WARP_FREQUENCY, py * RIVER_WARP_FREQUENCY, pz * RIVER_WARP_FREQUENCY) * RIVER_WARP_METRES
        val x = px + w; val y = py - w; val z = pz + w
        // Two octaves, with the second on a turned lattice. One octave alone ran some valleys dead
        // straight for ten kilometres. See [ridged].
        val broad = simplex(riverSeed, x * RIVER_FREQUENCY, y * RIVER_FREQUENCY, z * RIVER_FREQUENCY)
        val rx = R00 * x + R01 * y + R02 * z
        val ry = R10 * x + R11 * y + R12 * z
        val rz = R20 * x + R21 * y + R22 * z
        val fine = simplex(riverSeed + 1, rx * RIVER_FREQUENCY * 2.3, ry * RIVER_FREQUENCY * 2.3, rz * RIVER_FREQUENCY * 2.3)
        return abs(broad * 0.8 + fine * 0.2)
    }

    /**
     * Volcanic cones, one or none per sixty kilometre cell: a cone with a crater at the top.
     * They're kept away from the launch complex, which shouldn't be on a volcano.
     */
    private fun volcanoes(px: Double, py: Double, pz: Double, landness: Double): Double {
        if (landness <= 0.0) return 0.0
        val cx = floor(px / VOLCANO_CELL).toInt()
        val cy = floor(py / VOLCANO_CELL).toInt()
        val cz = floor(pz / VOLCANO_CELL).toInt()
        var total = 0.0
        for (i in -1..1) for (j in -1..1) for (k in -1..1) {
            val x = cx + i; val y = cy + j; val z = cz + k
            if (Noise.hash(volcanoSeed, x, y, z) > VOLCANO_CHANCE) continue
            val vx = (x + Noise.hash(volcanoSeed + 1, x, y, z)) * VOLCANO_CELL
            val vy = (y + Noise.hash(volcanoSeed + 2, x, y, z)) * VOLCANO_CELL
            val vz = (z + Noise.hash(volcanoSeed + 3, x, y, z)) * VOLCANO_CELL
            // Cell centres float off the surface, so project them onto it by normalising. Only from
            // cells near the surface, though. A cell centred deep in the planet projects its
            // volcano somewhere far from itself, where some neighbouring cells see it and others
            // don't, and the ground steps wherever the set of neighbours changes.
            val vl = sqrt(vx * vx + vy * vy + vz * vz)
            if (abs(vl - radius) > VOLCANO_CELL * 0.4) continue
            val sx = vx / vl * radius; val sy = vy / vl * radius; val sz = vz / vl * radius
            val homeDistance = sqrt((sx - hx) * (sx - hx) + (sy - hy) * (sy - hy) + (sz - hz) * (sz - hz))
            if (homeDistance < VOLCANO_HOME_CLEARANCE) continue
            val dx = px - sx; val dy = py - sy; val dz = pz - sz
            val d = sqrt(dx * dx + dy * dy + dz * dz)
            val size = 3_500.0 + 5_500.0 * Noise.hash(volcanoSeed + 4, x, y, z)
            if (d >= size) continue
            val peak = 700.0 + 1_600.0 * Noise.hash(volcanoSeed + 5, x, y, z)
            val t = 1.0 - d / size
            var cone = peak * t * t * (3.0 - 2.0 * t) * sqrt(t)
            val crater = size * 0.13
            if (d < crater) cone -= peak * 0.22 * (1.0 - d / crater) * (1.0 - d / crater)
            total += cone
        }
        return total * landness
    }

    // --- material ---------------------------------------------------------------

    /**
     * What the ground is made of, from the same fields that shaped it. Steep ground is rock, high
     * and cold is snow, dry dunes are sand, badlands are clay, wet valley floors are mud, and
     * forests have their own floor.
     */
    /**
     * What the ground is made of. [watered], 0..1, pulls the country's moisture toward grassland's,
     * for somewhere kept green.
     */
    fun material(nx: Double, ny: Double, nz: Double, elevation: Double, slope: Double, landness: Double, watered: Double = 0.0): SurfaceMaterial {
        val px = nx * radius; val py = ny * radius; val pz = nz * radius
        val qx = px + warp(warpSeedX, px, py, pz)
        val qy = py + warp(warpSeedY, px, py, pz)
        val qz = pz + warp(warpSeedZ, px, py, pz)
        val warm = warmth(qx, qy, qz, ny, elevation)
        // Watered country gets pulled toward grassland's moisture, up from dry and down from bog,
        // so it's green instead of sand or mud.
        val soaked = moisture(qx, qy, qz, ny, landness)
        val wet = soaked + (GRASSLAND_MOISTURE - soaked) * watered

        if (warm < 0.1) return if (slope < 0.08) SurfaceMaterial.ICE else SurfaceMaterial.SNOW
        val snowLine = 1_300.0 + 3_600.0 * warm
        if (elevation > snowLine) return if (slope > 0.35) SurfaceMaterial.ROCK else SurfaceMaterial.SNOW
        if (slope > 0.30) return SurfaceMaterial.ROCK

        // Not calmed near home, because what the ground is made of shouldn't know about the launch
        // complex. Calming the classification drew the basin's outline straight through the
        // badlands.
        val mountain = mountains(qx, qy, qz, elevation)
        val plateau = plateau(qx, qy, qz)
        val dry = wet < 0.32 && warm > 0.4
        if (slope > 0.15) {
            return when {
                dry && plateau > 0.3 -> SurfaceMaterial.CLAY
                mountain > 0.25 || plateau > 0.3 -> SurfaceMaterial.SCREE
                else -> SurfaceMaterial.DIRT
            }
        }
        if (elevation < BEACH_METRES && landness < 0.35) return SurfaceMaterial.SAND
        if (dry) return if (plateau > 0.35) SurfaceMaterial.CLAY else SurfaceMaterial.SAND

        // Wet, flat and low, or the floor of a valley in wet country: mud. It's kept to properly
        // wet places. As a lowland default it covered two fifths of whole regions.
        val valleyFloor = channel(px, py, pz) < 0.02
        if (slope < 0.04 && ((wet > 0.68 && elevation < 60.0) || (wet > 0.58 && valleyFloor))) {
            return SurfaceMaterial.MUD
        }
        if (warm < 0.28) return SurfaceMaterial.DIRT

        // A patchwork within each kind of country (copses, clearings, bare ground, rocky patches) a
        // few kilometres across, so driving in a straight line crosses several instead of one green
        // sheet. It's warped and uses two octaves, so the patches have ragged edges and come in
        // lots of sizes. One octave of plain noise made polka dots.
        val pw = simplex(patchSeed + 7, px * PATCH_FREQUENCY * 1.7, py * PATCH_FREQUENCY * 1.7, pz * PATCH_FREQUENCY * 1.7) * PATCH_WARP_METRES
        val patch = simplex(patchSeed, (px + pw) * PATCH_FREQUENCY, (py - pw) * PATCH_FREQUENCY, (pz + pw) * PATCH_FREQUENCY) * 0.7 +
            simplex(patchSeed + 1, px * PATCH_FREQUENCY * 3.1, py * PATCH_FREQUENCY * 3.1, pz * PATCH_FREQUENCY * 3.1) * 0.3
        val forestCut = 0.62 - (patch - 0.2) * 0.25
        if (wet > forestCut) return SurfaceMaterial.FOREST
        if (patch < -0.55 && slope > 0.06) return SurfaceMaterial.SCREE
        if (patch < -0.35 || wet < 0.42) return SurfaceMaterial.DIRT
        return SurfaceMaterial.GRASS
    }

    // --- noise helpers -----------------------------------------------------------

    private fun fbm(seed: Int, x0: Double, y0: Double, z0: Double, frequency: Double, octaves: Int): Double {
        var x = x0; var y = y0; var z = z0
        var f = frequency
        var amplitude = 1.0
        var total = 0.0
        var norm = 0.0
        for (o in 0 until octaves) {
            total += simplex(seed + o, x * f, y * f, z * f) * amplitude
            norm += amplitude
            amplitude *= 0.5
            f *= 2.03
            // Turn the coordinates between octaves. See [ridged].
            val rx = R00 * x + R01 * y + R02 * z
            val ry = R10 * x + R11 * y + R12 * z
            val rz = R20 * x + R21 * y + R22 * z
            x = rx; y = ry; z = rz
        }
        return total / norm
    }

    /**
     * Ridged multifractal, 0..~1: sharp crests, each octave gated by the one before.
     *
     * The coordinates get turned by a fixed, irrational-looking rotation between octaves. Simplex
     * noise is nearly linear across parts of its lattice, so the crease `1 - |n|` makes along a
     * zero crossing can come out dead straight for kilometres, and with every octave on the same
     * lattice those straight bits line up. Rotating each octave's lattice away from the last breaks
     * that up.
     */
    private fun ridged(seed: Int, x0: Double, y0: Double, z0: Double, frequency: Double, octaves: Int): Double {
        var x = x0; var y = y0; var z = z0
        var f = frequency
        var amplitude = 1.0
        var total = 0.0
        var norm = 0.0
        var weight = 1.0
        for (o in 0 until octaves) {
            var r = 1.0 - abs(simplex(seed + o, x * f, y * f, z * f))
            r *= r
            r *= weight
            weight = (r * 1.6).coerceIn(0.0, 1.0)
            total += r * amplitude
            norm += amplitude
            amplitude *= 0.5
            f *= 2.05
            val rx = R00 * x + R01 * y + R02 * z
            val ry = R10 * x + R11 * y + R12 * z
            val rz = R20 * x + R21 * y + R22 * z
            x = rx; y = ry; z = rz
        }
        return total / norm
    }

    private companion object {
        // A fixed rotation, applied between noise octaves.
        const val R00 = 0.6706493163827862
        const val R01 = -0.5612757302191576
        const val R02 = 0.48497324575924067
        const val R10 = 0.692047325184816
        const val R11 = 0.7087910315811033
        const val R12 = -0.13669591528182917
        const val R20 = -0.26702058748314333
        const val R21 = 0.4272994596499589
        const val R22 = 0.8637795885774392

        const val WARP_FREQUENCY = 1.0 / 60_000.0
        const val WARP_METRES = 25_000.0
        const val BELT_FREQUENCY = 1.0 / 420_000.0
        const val HILL_REGION_FREQUENCY = 1.0 / 90_000.0
        const val PLATEAU_FREQUENCY = 1.0 / 140_000.0
        const val WET_FREQUENCY = 1.0 / 260_000.0

        const val ROLL_FREQUENCY = 1.0 / 3_200.0
        const val RIDGE_FREQUENCY = 1.0 / 22_000.0
        const val MOUNTAIN_METRES = 2_600.0
        const val RIDGE_WARP_FREQUENCY = 1.0 / 14_000.0
        const val RIDGE_WARP_METRES = 1_800.0
        const val TERRACE_METRES = 70.0
        const val MESA_FREQUENCY = 1.0 / 5_000.0
        const val TERRACE_VARY_FREQUENCY = 1.0 / 40_000.0
        const val TERRACE_WOBBLE_FREQUENCY = 1.0 / 2_500.0

        const val DUNE_ALONG = 1.0 / 180.0
        const val DUNE_ACROSS = 1.0 / 1_400.0
        const val DUNE_METRES = 18.0

        const val RIVER_FREQUENCY = 1.0 / 38_000.0
        const val RIVER_WARP_FREQUENCY = 1.0 / 9_000.0
        const val RIVER_WARP_METRES = 2_500.0
        const val VALLEY_LOW_METRES = 28.0
        const val VALLEY_MOUNTAIN_METRES = 520.0
        const val CANYON_METRES = 190.0
        const val SHORE_FLOOR_METRES = 3.0

        const val DETAIL_FREQUENCY = 1.0 / 45.0
        const val OUTCROP_FREQUENCY = 1.0 / 260.0

        const val VOLCANO_CELL = 60_000.0
        const val VOLCANO_CHANCE = 0.12
        const val VOLCANO_HOME_CLEARANCE = 40_000.0

        const val BEACH_METRES = 10.0

        /**
         * The moisture of grass country: wet enough not to be sand, but not so wet it becomes
         * forest.
         */
        const val GRASSLAND_MOISTURE = 0.5
        const val PATCH_FREQUENCY = 1.0 / 3_500.0
        const val EDGE_FREQUENCY = 1.0 / 6_000.0
        const val PATCH_WARP_METRES = 1_800.0

        /** Gentle country around the launch complex, fading into the rest by the outer edge. */
        const val HOME_BASIN_INNER = 12_000.0
        const val HOME_BASIN_OUTER = 45_000.0
        const val BASIN_EDGE_FREQUENCY = 1.0 / 25_000.0
        const val BASIN_EDGE_METRES = 9_000.0

        /** How much further the basin reaches ahead of the runway than out to either side. */
        const val RUNWAY_STRETCH = 2.5
    }
}
