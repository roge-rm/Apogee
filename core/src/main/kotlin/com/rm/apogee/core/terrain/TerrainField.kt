package com.rm.apogee.core.terrain

import com.rm.apogee.core.math.Vec3
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.sqrt

/**
 * The shape of a planet's surface: one height function, evaluated everywhere.
 *
 * **There is deliberately no GLSL version of this.** The obvious way to draw a
 * procedural planet is to run the same noise in a fragment shader, and it is a
 * trap: two implementations of one function in two languages, which drift the
 * first time either is touched, leaving a craft colliding with a sea floor
 * while the screen shows a mountain. Instead the renderer *samples this* to
 * build its mesh, so the ground you see is by construction the ground you
 * land on.
 *
 * Deterministic from [seed] alone, with no floating-point surprises: the hash
 * is integer arithmetic and the interpolation is plain smoothstep, so a phone
 * and a server evaluating the same point get the same metre.
 */
class TerrainField(
    /**
     * Radius of the body this describes, metres.
     *
     * The field needs it because some of its features are sized in metres
     * rather than in fractions of a sphere - the flat ground under the launch
     * complex most of all, which has to be about as wide as a launch complex
     * and not about as wide as a tenth of a degree.
     */
    override val bodyRadius: Double,
    val seed: Int = DEFAULT_SEED,
    /** Metres from the datum to the highest peaks. */
    override val maxElevation: Double = 6_000.0,
    /** Metres from the datum down to the deepest ocean floor. */
    val oceanDepth: Double = 3_000.0,
    /**
     * Where to guarantee dry land, as a surface normal.
     *
     * The launch complex is a fixed point on the planet and cannot be in the
     * sea. Rather than searching the noise for a suitable coastline, terrain
     * is raised around it - which is also how real launch sites come about.
     */
    val homeDirection: Vec3? = null,
    /**
     * Where the launch complex itself stands - its pad levelled, its runway
     * laid, the country round it kept gentle - if not at [homeDirection].
     * The continent stays raised where it always was: moving that would move
     * every coastline on the planet.
     */
    val padDirection: Vec3? = null,
    /**
     * A harbour: a broad natural bay in the coast here, reached from the sea
     * by an inlet that winds on its way in, so no swell runs straight to
     * it. See [bay].
     */
    val harbourDirection: Vec3? = null,
    /** Which kind of world this is: what shapes the land. */
    val profile: Profile = Profile.TERRA,
) : Terrain {

    enum class Profile { TERRA, LUNA }

    private val luna: LunaLand? = if (profile == Profile.LUNA) LunaLand(seed, bodyRadius) else null

    override val hasOcean: Boolean get() = profile == Profile.TERRA

    override val generation: Int get() = GENERATION

    override val tiles: TerrainTileCache by lazy { TerrainTileCache(this) }

    private val scatterField: ScatterField by lazy { ScatterField(this) }
    override val scatter: ScatterField? get() = scatterField

    override fun isLaunchComplex(direction: Vec3): Boolean {
        val home = padUnit ?: return false
        val length = sqrt(direction.x * direction.x + direction.y * direction.y + direction.z * direction.z)
        val ox = direction.x / length - home.x
        val oy = direction.y / length - home.y
        val oz = direction.z / length - home.z
        val a = runwayAlong ?: return false
        val c = runwayAcross!!
        val east = (a.x * ox + a.y * oy + a.z * oz) * bodyRadius
        val north = (c.x * ox + c.y * oy + c.z * oz) * bodyRadius
        if (abs(east) > WORKS_REACH || abs(north) > WORKS_REACH) return false
        return nearestWork(east, north) >= 0
    }

    /**
     * What stands on the continents. Only for a body with a home to keep
     * clear - that is, Terra; other bodies get their own profiles.
     */
    private val homeUnit: Vec3? = homeDirection?.normalized()
    private val padUnit: Vec3? = (padDirection ?: homeDirection)?.normalized()
    private val land: TerraLand? = homeUnit?.let { home ->
        val pad = padUnit!!
        TerraLand(seed, bodyRadius, home.x, home.y, home.z, pad.x, pad.y, pad.z)
    }

    /**
     * How many of the works this world has: all of them where there is a
     * harbour, only the launch complex and the airfield where there is not -
     * the road down to the quay, the quay and its berth are laid out
     * against the bay's own shore, and anywhere else would cut a pit.
     */
    private val workCount: Int = if (harbourDirection != null) WORK_FROM_EAST.size else HARBOUR_WORKS_FROM

    /** The harbour's centre, and its own east and north, for laying out the bay. */
    private val harbourUnit: Vec3? = harbourDirection?.normalized()
    private val harbourEast: Vec3? = harbourUnit?.let { Vec3(0.0, 1.0, 0.0).crossInPlace(it).normalizeInPlace() }
    private val harbourNorth: Vec3? = harbourUnit?.let { it.copy().crossInPlace(harbourEast!!) }

    /**
     * The runway's heading at home: east, the way the ground is carried by
     * the planet's spin about +Y, and the way a horizontal craft is pointed
     * when it is launched. Null at a pole, where there is no east.
     */
    private val runwayAlong: Vec3? = padUnit?.let { home ->
        Vec3(0.0, 1.0, 0.0).crossInPlace(home).takeIf { it.lengthSq > 1e-12 }?.normalizeInPlace()
    }
    private val runwayAcross: Vec3? = runwayAlong?.let { along ->
        padUnit!!.copy().crossInPlace(along)
    }

    /**
     * Ground level at the launch complex.
     *
     * Computed once from the unflattened field, because the flattening is
     * defined as "level with this" and cannot be asked what that is without
     * looping. Eager rather than lazy: [elevation] is on the collision hot
     * path and does not want a synchronised read per contact point.
     */
    private val homeElevation: Double =
        padUnit?.let { kotlin.math.max(shapedElevation(it.x, it.y, it.z), if (hasOcean) PAD_MIN_ELEVATION else -1e9) } ?: 0.0
    /**
     * Height above the datum at [direction], in metres. Negative is sea floor.
     *
     * [direction] need not be normalised.
     */
    override fun elevation(direction: Vec3): Double {
        // Scalars throughout: this is the hottest function in the game, run
        // concurrently on several threads, and every temporary vector here
        // was garbage a collector later stopped the world to sweep up.
        val length = sqrt(direction.x * direction.x + direction.y * direction.y + direction.z * direction.z)
        if (length < 0.7) return 0.0
        val nx = direction.x / length; val ny = direction.y / length; val nz = direction.z / length

        luna?.let { return it.height(nx, ny, nz) }
        val shaped = shapedElevation(nx, ny, nz)
        val home = padUnit ?: return shaped

        // A level pad, and only a level pad. Rolling ground is what makes
        // altitude and lateral drift legible from the cockpit, so the
        // flattening is kept to about the footprint of a launch complex.
        //
        // Chord length rather than acos(dot): at these angles the dot product
        // is within a rounding error of 1 and acos throws away most of its
        // precision, while the chord is still exact.
        val ox = nx - home.x; val oy = ny - home.y; val oz = nz - home.z
        val a = runwayAlong ?: return shaped
        val c = runwayAcross!!
        val east = (a.x * ox + a.y * oy + a.z * oz) * bodyRadius
        val north = (c.x * ox + c.y * oy + c.z * oz) * bodyRadius
        if (abs(east) > WORKS_REACH || abs(north) > WORKS_REACH) return shaped
        // Every work near enough to reach here has its say, by how near: the
        // level is their heights weighed so, and the land gives way to it as
        // far as the nearest of them demands. Taking only the nearest put a
        // fourteen-metre step where a road's blend met the quay's.
        var total = 0.0
        var weighed = 0.0
        var strongest = 0.0
        for (k in 0 until workCount) {
            val a = 1.0 - workBlend(k, east, north)
            if (a <= 0.0) continue
            total += a
            weighed += a * workHeight(k, east, north)
            if (a > strongest) strongest = a
        }
        if (total <= 0.0) return shaped
        val level = weighed / total
        return level + (shaped - level) * (1.0 - strongest)
    }

    /**
     * The Cape's works: the pad complex, the runway, the airfield's apron,
     * roads, the harbour's quay and its dredged berth - each a disc or a
     * strip in metres east and north of the pad, levelled to its own height
     * and blended back into the land round it. See [WORK_FROM_EAST].
     */
    private fun nearestWork(east: Double, north: Double): Int {
        var best = -1
        var bestT = 1.0
        for (k in 0 until workCount) {
            val t = workBlend(k, east, north)
            if (t < bestT) { bestT = t; best = k }
        }
        return best
    }

    /** 0 on work [k], rising to 1 where the land takes over again. */
    private fun workBlend(k: Int, east: Double, north: Double): Double {
        val outside = max(0.0, workDistance(k, east, north) - WORK_FLAT[k])
        if (outside >= WORK_BLEND[k]) return 1.0
        return smoothstep(outside / WORK_BLEND[k])
    }

    /** How far the point is from work [k]'s line, m. */
    private fun workDistance(k: Int, east: Double, north: Double): Double {
        val ax = WORK_FROM_EAST[k]; val ay = WORK_FROM_NORTH[k]
        val dx = WORK_TO_EAST[k] - ax; val dy = WORK_TO_NORTH[k] - ay
        val lengthSq = dx * dx + dy * dy
        val t = if (lengthSq <= 0.0) 0.0 else (((east - ax) * dx + (north - ay) * dy) / lengthSq).coerceIn(0.0, 1.0)
        val px = ax + dx * t - east; val py = ay + dy * t - north
        return sqrt(px * px + py * py)
    }

    /** The height work [k] is levelled to where the point is: its own, or sloping from one end's to the other's. */
    private fun workHeight(k: Int, east: Double, north: Double): Double {
        val from = WORK_FROM_HEIGHT[k].let { if (it.isNaN()) homeElevation else it }
        val to = WORK_TO_HEIGHT[k].let { if (it.isNaN()) homeElevation else it }
        if (from == to) return from
        val ax = WORK_FROM_EAST[k]; val ay = WORK_FROM_NORTH[k]
        val dx = WORK_TO_EAST[k] - ax; val dy = WORK_TO_NORTH[k] - ay
        val lengthSq = dx * dx + dy * dy
        val t = if (lengthSq <= 0.0) 0.0 else (((east - ax) * dx + (north - ay) * dy) / lengthSq).coerceIn(0.0, 1.0)
        return from + (to - from) * smoothstep(t)
    }

    /**
     * What the ground under the launch complex is paved with, or null off
     * it: a concrete pad round the pads themselves, and an asphalt runway
     * running east from it.
     */
    fun paving(direction: Vec3): SurfaceMaterial? {
        val home = padUnit ?: return null
        val length = sqrt(direction.x * direction.x + direction.y * direction.y + direction.z * direction.z)
        val ox = direction.x / length - home.x
        val oy = direction.y / length - home.y
        val oz = direction.z / length - home.z
        val a = runwayAlong ?: return null
        val c = runwayAcross!!
        val east = (a.x * ox + a.y * oy + a.z * oz) * bodyRadius
        val north = (c.x * ox + c.y * oy + c.z * oz) * bodyRadius
        if (abs(east) > WORKS_REACH || abs(north) > WORKS_REACH) return null
        // The last listed wins where two overlap: roads over the land they
        // cross, the runway over the road that meets it.
        var found: SurfaceMaterial? = null
        for (k in 0 until workCount) {
            val material = WORK_MATERIAL[k] ?: continue
            if (workDistance(k, east, north) <= WORK_PAVED[k]) found = material
        }
        return found
    }

    /** The field proper, with the harbour carved in, before the launch complex is levelled into it. */
    private fun shapedElevation(nx: Double, ny: Double, nz: Double): Double {
        val raw = capeLift(nx, ny, nz, naturalElevation(nx, ny, nz))
        return bay(nx, ny, nz, raw)
    }

    /**
     * The Cape's low country lifted clear of the tide. The plain round the
     * pad came out of the field a metre or two above the datum - under the
     * four-metre tides of this coast, so at high water it was a tidal flat
     * kilometres wide, the pad an island in it, and seen from above the
     * drawn sea round the craft a disc of shallows and foam on dry-looking
     * land (Dan). Low land is raised most, higher land less, the shore not
     * at all, so the coastline stays where it was; nothing beyond
     * [CAPE_LIFT_FADE] of the pad changes.
     */
    private fun capeLift(nx: Double, ny: Double, nz: Double, ground: Double): Double {
        if (ground <= 0.0) return ground
        val pad = padUnit ?: return ground
        if (harbourUnit == null) return ground
        val ox = nx - pad.x; val oy = ny - pad.y; val oz = nz - pad.z
        val metres = sqrt(ox * ox + oy * oy + oz * oz) * bodyRadius
        if (metres >= CAPE_LIFT_FADE) return ground
        val fade = 1.0 - smoothstep(((metres - CAPE_LIFT_REACH) / (CAPE_LIFT_FADE - CAPE_LIFT_REACH)).coerceIn(0.0, 1.0))
        return ground + CAPE_LIFT * (1.0 - kotlin.math.exp(-ground / CAPE_LIFT_SHORE)) * kotlin.math.exp(-ground / CAPE_LIFT_HIGH) * fade
    }

    /** Whether [east], [north] of the pad is on the levelled part of any work. */
    private fun onWorks(east: Double, north: Double): Boolean {
        for (k in 0 until workCount) if (workDistance(k, east, north) <= WORK_FLAT[k]) return true
        return false
    }

    /**
     * How much the land at [east], [north] of the pad is the Cape's green
     * country, 0..1: fully on and round the works, fading out over the
     * kilometre and more beyond them along an edge bent by noise, so the
     * grass meets the dry country round it as a ragged margin, not a ring.
     */
    private fun capeGreen(east: Double, north: Double): Double {
        var nearest = Double.MAX_VALUE
        for (k in 0 until workCount) nearest = minOf(nearest, workDistance(k, east, north) - WORK_FLAT[k])
        val bend = GREEN_BEND * Noise.simplex(GREEN_SEED, east / GREEN_BEND_SCALE, north / GREEN_BEND_SCALE, 0.5) +
            0.4 * GREEN_BEND * Noise.simplex(GREEN_SEED + 1, east / (0.3 * GREEN_BEND_SCALE), north / (0.3 * GREEN_BEND_SCALE), 0.5)
        return 1.0 - smoothstep(((nearest + bend) / GREEN_REACH).coerceIn(0.0, 1.0))
    }

    /**
     * The harbour's bay, cut into [ground]: a broad basin with a cove to its
     * south-east, opening northward into an inlet that winds north-east and
     * then north-west out to sea, widening between its headlands as it
     * goes. The bend is the point - nothing the ocean sends has a straight
     * run in to the harbour. The shore is bent and bitten by noise, so it
     * reads as a coast rather than a drawing, shelving gently from
     * [BAY_FLOOR] to beaches, with banks rising behind. Only ever lowers the
     * ground, so beyond its banks nothing changes, and out at the mouth the
     * sea floor it meets is the ocean's own.
     */
    private fun bay(nx: Double, ny: Double, nz: Double, ground: Double): Double {
        val centre = harbourUnit ?: return ground
        val ox = nx - centre.x; val oy = ny - centre.y; val oz = nz - centre.z
        val e = harbourEast!!; val n = harbourNorth!!
        val x0 = (e.x * ox + e.y * oy + e.z * oz) * bodyRadius
        val y0 = (n.x * ox + n.y * oy + n.z * oz) * bodyRadius
        if (abs(x0) > BAY_REACH_METRES || abs(y0) > BAY_REACH_METRES) return ground
        // Bent: the whole outline pushed about by a slow field, then its
        // edge nibbled by a quicker one.
        val x = x0 + BAY_WARP_METRES * Noise.simplex(BAY_SEED, x0 / BAY_WARP_SCALE, y0 / BAY_WARP_SCALE, 0.5)
        val y = y0 + BAY_WARP_METRES * Noise.simplex(BAY_SEED + 1, x0 / BAY_WARP_SCALE, y0 / BAY_WARP_SCALE, 0.5)
        var d = ellipseDistance(x - BAY_X, y - BAY_Y, BAY_RADIUS_X, BAY_RADIUS_Y)
        d = smoothMin(d, sqrt((x - COVE_X) * (x - COVE_X) + (y - COVE_Y) * (y - COVE_Y)) - COVE_RADIUS, BAY_BLEND_METRES)
        for (k in 0 until INLET.size / 3 - 1) {
            val i = 3 * k
            d = smoothMin(d, taperedDistance(x, y, INLET[i], INLET[i + 1], INLET[i + 2], INLET[i + 3], INLET[i + 4], INLET[i + 5]), BAY_BLEND_METRES)
        }
        d += BAY_EDGE_METRES * Noise.simplex(BAY_SEED + 2, x0 / BAY_EDGE_SCALE, y0 / BAY_EDGE_SCALE, 0.5) +
            0.2 * BAY_EDGE_METRES * Noise.simplex(BAY_SEED + 3, x0 / (0.35 * BAY_EDGE_SCALE), y0 / (0.35 * BAY_EDGE_SCALE), 0.5)
        val cut = if (d < 0.0) BAY_FLOOR * smoothstep((-d / BAY_SHELF_METRES).coerceAtMost(1.0)) else d * BANK_GRADE
        return minOf(ground, cut)
    }

    /** Roughly how far outside (positive) or inside an ellipse of radii [rx], [ry] the point [x], [y] from its middle is, m. */
    private fun ellipseDistance(x: Double, y: Double, rx: Double, ry: Double): Double =
        (sqrt((x / rx) * (x / rx) + (y / ry) * (y / ry)) - 1.0) * minOf(rx, ry)

    /** How far outside (positive) or inside a stroke from a to b is, its half-width [ra] at a narrowing or widening to [rb] at b, m. */
    private fun taperedDistance(x: Double, y: Double, ax: Double, ay: Double, ra: Double, bx: Double, by: Double, rb: Double): Double {
        val dx = bx - ax; val dy = by - ay
        val t = (((x - ax) * dx + (y - ay) * dy) / (dx * dx + dy * dy)).coerceIn(0.0, 1.0)
        val px = ax + dx * t - x; val py = ay + dy * t - y
        return sqrt(px * px + py * py) - (ra + (rb - ra) * t)
    }

    /** The smaller of [a] and [b], rounded over [k]: two shapes joined without a crease. */
    private fun smoothMin(a: Double, b: Double, k: Double): Double {
        val h = (k - abs(a - b)).coerceAtLeast(0.0) / k
        return minOf(a, b) - h * h * k * 0.25
    }

    /** The field as it comes, before anything is built into it. */
    private fun naturalElevation(nx: Double, ny: Double, nz: Double): Double {
        // Continents at the largest scale, then detail. Each octave halves in
        // size and in contribution, which is what makes the result look the
        // same at every distance.
        var amplitude = 1.0
        var frequency = CONTINENT_FREQUENCY
        var total = 0.0
        var normalisation = 0.0

        repeat(OCTAVES) {
            total += amplitude * noise(nx * frequency, ny * frequency, nz * frequency)
            normalisation += amplitude
            amplitude *= PERSISTENCE
            frequency *= LACUNARITY
        }
        var shaped = total / normalisation

        // A continent under the launch complex.
        homeUnit?.let { home ->
            val closeness = ((nx * home.x + ny * home.y + nz * home.z) - HOME_FALLOFF_START) /
                (1.0 - HOME_FALLOFF_START)
            if (closeness > 0.0) {
                shaped += HOME_LIFT * smoothstep(closeness.coerceIn(0.0, 1.0))
            }
        }

        // Push the distribution away from the middle so coastlines are
        // definite. Without it most of the planet sits within a few metres of
        // sea level and the whole world is beach.
        val centred = (shaped - SEA_FRACTION) / (1.0 - SEA_FRACTION)
        val base = if (centred >= 0.0) {
            StrictMath.pow(centred, LAND_SHARPNESS) * maxElevation
        } else {
            val depth = (-centred / SEA_FRACTION * (1.0 - SEA_FRACTION)).coerceIn(0.0, 1.0)
            -StrictMath.pow(depth, OCEAN_SHARPNESS) * oceanDepth
        }
        if (base <= 0.0) return base

        // Hills, in metres rather than as another octave of the curve above.
        //
        // Adding them before the sharpening curve is the obvious approach and
        // does not work: LAND_SHARPNESS squashes everything near sea level, so
        // exactly the lowland a craft launches from comes out ironed flat. In
        // metres afterwards, a hill is the same hill wherever it stands.
        //
        // Faded in over the first few hundred metres of land so the shoreline
        // stays where the curve put it. HILL_SHORE_FADE is more than twice
        // HILL_AMPLITUDE, which is what guarantees a hill can never dig a
        // patch of land back below the waterline and speckle the coast with
        // ponds.
        val landness = smoothstep((base / HILL_SHORE_FADE).coerceIn(0.0, 1.0))
        land?.let { return it.height(nx, ny, nz, base, landness) }
        return base + hills(nx, ny, nz) * HILL_AMPLITUDE * landness
    }

    /** Hill-scale detail, -1..1, as its own band of octaves. */
    private fun hills(nx: Double, ny: Double, nz: Double): Double {
        var amplitude = 1.0
        var frequency = HILL_FREQUENCY
        var total = 0.0
        var normalisation = 0.0
        repeat(HILL_OCTAVES) {
            total += amplitude * noise(nx * frequency, ny * frequency, nz * frequency)
            normalisation += amplitude
            amplitude *= PERSISTENCE
            frequency *= LACUNARITY
        }
        return (total / normalisation) * 2.0 - 1.0
    }

    /**
     * What the ground here is made of.
     *
     * For now the same bands the renderer has always coloured by - shore,
     * grass, dry upland, rock, snow, and rock on anything steep - so that
     * moving the classification out of the shader changes nothing a player
     * can see. Biomes replace it.
     */
    override fun material(direction: Vec3, elevation: Double, slope: Double): SurfaceMaterial {
        val length = sqrt(direction.x * direction.x + direction.y * direction.y + direction.z * direction.z)
        val nx = direction.x / length; val ny = direction.y / length; val nz = direction.z / length
        luna?.let { return it.material(nx, ny, nz, slope) }
        if (elevation < 0.0) return SurfaceMaterial.SAND
        paving(direction)?.let { return it }
        land?.let {
            var landness = smoothstep((elevation / HILL_SHORE_FADE).coerceIn(0.0, 1.0))
            // Round the Cape's works: green country - kept grass on the works
            // themselves, and grass country round them, the dry coast's
            // sand and clay giving way to grass, copses and bare patches.
            // The beach at the water's very edge stays sand.
            var watered = 0.0
            val pad = padUnit
            val a = runwayAlong
            if (pad != null && a != null && harbourUnit != null) {
                val ox = nx - pad.x; val oy = ny - pad.y; val oz = nz - pad.z
                val c = runwayAcross!!
                val east = (a.x * ox + a.y * oy + a.z * oz) * bodyRadius
                val north = (c.x * ox + c.y * oy + c.z * oz) * bodyRadius
                if (abs(east) < WORKS_REACH + GREEN_REACH && abs(north) < WORKS_REACH + GREEN_REACH) {
                    if (elevation < GREEN_BEACH) return SurfaceMaterial.SAND
                    // Kept grass on the works' own levelled ground.
                    if (slope < 0.15 && onWorks(east, north)) return SurfaceMaterial.GRASS
                    val green = capeGreen(east, north)
                    landness = maxOf(landness, green)
                    watered = green
                }
            }
            return it.material(nx, ny, nz, elevation, slope, landness, watered)
        }
        return when {
            slope > STEEP_SLOPE -> SurfaceMaterial.ROCK
            elevation < 30.0 -> SurfaceMaterial.SAND
            elevation < 900.0 -> SurfaceMaterial.GRASS
            elevation < 1_350.0 -> SurfaceMaterial.DIRT
            elevation < 1_800.0 -> SurfaceMaterial.ROCK
            else -> SurfaceMaterial.SNOW
        }
    }

    /**
     * Approximate surface normal, for placing things flat on a slope.
     *
     * Sampled rather than derived: the field has no analytic gradient, and a
     * finite difference over a few metres is both simpler and closer to what
     * the collider actually sees.
     */
    fun surfaceNormal(direction: Vec3, sample: Double = 30.0): Vec3 {
        val up = direction.normalized()
        val east = pickTangent(up)
        val north = up.cross(east).normalizeInPlace()

        val step = sample / bodyRadius
        val here = surfaceRadius(up)
        val alongEast = surfaceRadius(up + east * step) - here
        val alongNorth = surfaceRadius(up + north * step) - here

        // The surface tilts away from vertical by the slope in each direction.
        return Vec3(
            up.x - (east.x * alongEast + north.x * alongNorth) / sample,
            up.y - (east.y * alongEast + north.y * alongNorth) / sample,
            up.z - (east.z * alongEast + north.z * alongNorth) / sample,
        ).normalizeInPlace()
    }

    private fun pickTangent(up: Vec3): Vec3 {
        val axis = if (abs(up.y) < 0.9) Vec3.unitY() else Vec3.unitX()
        return axis.cross(up).normalizeInPlace()
    }

    private fun noise(x: Double, y: Double, z: Double): Double = Noise.value(seed, x, y, z)

    private fun smoothstep(t: Double) = Noise.smoothstep(t)

    companion object {
        const val DEFAULT_SEED = 0x4A06EE

        /** See [Terrain.generation]. 1 is the terrain every save before M7 was made on. */
        const val GENERATION = 5

        /** Slope (0 flat, 1 wall) past which ground is bare rock: about 39 degrees. */
        private const val STEEP_SLOPE = 0.22

        /**
         * Octaves of detail.
         *
         * Ten puts the finest features at roughly three kilometres across on
         * a 600km world - fine enough that a near-field mesh has something to
         * resolve, coarse enough that the collider's hundred-odd samples per
         * craft per tick stay cheap.
         */
        private const val OCTAVES = 10

        /** Lattice cells across the planet at the coarsest octave. */
        private const val CONTINENT_FREQUENCY = 1.9

        private const val LACUNARITY = 2.07
        private const val PERSISTENCE = 0.5

        /** Fraction of the surface below sea level. */
        private const val SEA_FRACTION = 0.52

        /** Above 1 flattens lowlands and steepens peaks. */
        private const val LAND_SHARPNESS = 1.9
        private const val OCEAN_SHARPNESS = 1.4

        /** How wide the guaranteed-land region around home is. */
        private const val HOME_FALLOFF_START = 0.985
        private const val HOME_LIFT = 0.22

        /**
         * The Cape's works, in metres east and north of the pad - each a
         * strip from one point to another (a disc where the two are the
         * same) levelled flat out to [WORK_FLAT] from its line and blended
         * back into the land by [WORK_BLEND] beyond, paved with
         * [WORK_MATERIAL] out to [WORK_PAVED]. Heights NaN are the pad's own.
         *
         * The pad complex: dead level to three hundred metres, rising to it
         * over seven hundred more - a low hill, where a tighter blend made a
         * flat-topped mesa of it (Dan).
         * The runway: 2.5 km east, parallel to the pad row and 400 m south
         * of it, clear of every pad, ending short of the bay's west shore
         * so a plane climbs out over the water.
         * The airfield's apron at its west end, north of it, and two
         * taxiways down to it.
         * Roads: pad to apron, apron along the runway, then winding down
         * inland of the shore to the harbour's quay, which runs along the
         * bay's west shore; its berth, dredged deep enough for the Trawler,
         * runs out east from the quay alongside the jetty.
         */
        // pad, runway, apron, two taxiways, roads (four legs), quay, berth
        private val WORK_FROM_EAST = doubleArrayOf(0.0, 250.0, 350.0, 420.0, 640.0, 0.0, 700.0, 2_650.0, 2_600.0, 2_560.0, 2_570.0, 2_625.0)
        private val WORK_FROM_NORTH = doubleArrayOf(0.0, -400.0, -285.0, -330.0, -330.0, -110.0, -235.0, -235.0, 0.0, 200.0, 270.0, 350.0)
        private val WORK_TO_EAST = doubleArrayOf(0.0, 2_750.0, 700.0, 420.0, 640.0, 350.0, 2_650.0, 2_600.0, 2_560.0, 2_560.0, 2_570.0, 2_770.0)
        private val WORK_TO_NORTH = doubleArrayOf(0.0, -400.0, -285.0, -390.0, -390.0, -235.0, -235.0, 0.0, 200.0, 270.0, 430.0, 350.0)
        private val WORK_FLAT = doubleArrayOf(300.0, 40.0, 70.0, 16.0, 16.0, 7.0, 7.0, 7.0, 7.0, 7.0, 45.0, 32.0)
        private val WORK_BLEND = doubleArrayOf(700.0, 250.0, 150.0, 40.0, 40.0, 40.0, 40.0, 40.0, 40.0, 40.0, 100.0, 40.0)
        private val WORK_FROM_HEIGHT = doubleArrayOf(
            Double.NaN, Double.NaN, Double.NaN, Double.NaN, Double.NaN, Double.NaN, Double.NaN, Double.NaN, 10.0, 6.0, QUAY_HEIGHT, BERTH_DEPTH,
        )
        private val WORK_TO_HEIGHT = doubleArrayOf(
            Double.NaN, Double.NaN, Double.NaN, Double.NaN, Double.NaN, Double.NaN, Double.NaN, 10.0, 6.0, QUAY_HEIGHT, QUAY_HEIGHT, BERTH_DEPTH,
        )
        private val WORK_PAVED = doubleArrayOf(110.0, 25.0, 62.0, 12.0, 12.0, 4.0, 4.0, 4.0, 4.0, 4.0, 42.0, 0.0)
        private val WORK_MATERIAL = arrayOf<SurfaceMaterial?>(
            SurfaceMaterial.CONCRETE, SurfaceMaterial.ASPHALT, SurfaceMaterial.CONCRETE, SurfaceMaterial.ASPHALT, SurfaceMaterial.ASPHALT,
            SurfaceMaterial.ASPHALT, SurfaceMaterial.ASPHALT, SurfaceMaterial.ASPHALT, SurfaceMaterial.ASPHALT, SurfaceMaterial.ASPHALT,
            SurfaceMaterial.CONCRETE, null,
        )

        /** Where the harbour's works start in the table: the road's third leg, down toward the shore. */
        private const val HARBOUR_WORKS_FROM = 7

        /** The Cape's low country is lifted by up to this, m: see [capeLift]. */
        private const val CAPE_LIFT = 7.0

        /** How quickly the lift comes in above the shoreline, m, and dies away over higher ground, m. */
        private const val CAPE_LIFT_SHORE = 0.4
        private const val CAPE_LIFT_HIGH = 6.0

        /** Lifted fully within this of the pad, m, and not at all past [CAPE_LIFT_FADE]. */
        private const val CAPE_LIFT_REACH = 5_000.0
        private const val CAPE_LIFT_FADE = 8_000.0

        /** The Cape's green country: how far beyond the works it fades out, m, and how bent its edge. See [capeGreen]. */
        private const val GREEN_REACH = 1_200.0
        private const val GREEN_BEND = 350.0
        private const val GREEN_BEND_SCALE = 900.0
        private const val GREEN_SEED = 0x6EE

        /** Below this, m, the Cape's country is still beach. */
        private const val GREEN_BEACH = 1.5

        /** Nothing of the works reaches past this, m east or north of the pad. */
        private const val WORKS_REACH = 3_500.0

        /** The harbour's quay, m above the datum: clear of the highest tide the bay sees. */
        private const val QUAY_HEIGHT = 4.5

        /** Its berth, dredged to this, m: room under the Trawler at the lowest tide. */
        private const val BERTH_DEPTH = -7.0

        /**
         * The lowest a launch complex by the sea is built, m above the
         * datum: clear of the highest tide the coast sees, and the surf on
         * top of it. Lower ground is made up to it.
         */
        private const val PAD_MIN_ELEVATION = 15.0


        /**
         * The harbour's bay, m east and north of its middle: a basin
         * [BAY_RADIUS_X] by [BAY_RADIUS_Y] about ([BAY_X], [BAY_Y]) - five
         * kilometres of water across, its west shore just past the runway's
         * end - and a cove to its south-east, [BAY_FLOOR] deep, shelving to
         * the shore over [BAY_SHELF_METRES].
         */
        private const val BAY_FLOOR = -16.0
        private const val BAY_SHELF_METRES = 600.0
        private const val BAY_X = 300.0
        private const val BAY_Y = 700.0
        private const val BAY_RADIUS_X = 2_800.0
        private const val BAY_RADIUS_Y = 2_300.0
        private const val COVE_X = 2_000.0
        private const val COVE_Y = -900.0
        private const val COVE_RADIUS = 1_300.0

        /**
         * The inlet's line, as east, north and half-width, m: out of the
         * basin north-east, then north-west to the open sea, opening out
         * between the headlands at its mouth. The bend is the point - there
         * is no straight line from the harbour to open water.
         */
        private val INLET = doubleArrayOf(
            300.0, 2_400.0, 1_100.0,
            1_000.0, 4_000.0, 600.0,
            0.0, 5_400.0, 650.0,
            -900.0, 6_100.0, 1_100.0,
        )

        /** How smoothly the basin, cove and inlet run into one another, m. */
        private const val BAY_BLEND_METRES = 700.0

        /** The slow bending of the whole outline: how far, m, over what distance, m. */
        private const val BAY_WARP_METRES = 450.0
        private const val BAY_WARP_SCALE = 2_500.0

        /** The quicker nibbling of the shore itself. */
        private const val BAY_EDGE_METRES = 180.0
        private const val BAY_EDGE_SCALE = 900.0

        private const val BAY_SEED = 0xBA1

        /** Rise of the banks behind the waterline, m per m. */
        private const val BANK_GRADE = 0.08

        /** Nothing of the bay reaches past this, m from its middle, east or north. */
        private const val BAY_REACH_METRES = 12_000.0

        /**
         * Hill band. Sized in metres and added after the sharpening curve, so
         * lowlands get the same relief as highlands.
         */
        private const val HILL_FREQUENCY = 250.0

        /**
         * Five, not four. The fourth octave bottoms out around two hundred
         * and sixty metres, which is larger than anything a craft on the
         * ground can see past - so the near field had no texture at all and
         * read as a painted plane.
         */
        private const val HILL_OCTAVES = 5
        private const val HILL_AMPLITUDE = 150.0

        /** Land below this height gets proportionally less hill. */
        private const val HILL_SHORE_FADE = 400.0

    }
}
