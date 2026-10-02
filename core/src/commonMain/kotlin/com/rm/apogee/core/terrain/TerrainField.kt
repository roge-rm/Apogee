package com.rm.apogee.core.terrain

import com.rm.apogee.core.math.Vec3
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.sqrt
import com.rm.apogee.core.math.StrictMath

/**
 * A planet's surface height, worked out everywhere from [seed] with integer hashing, so every
 * machine gets the same metre. There's no GLSL copy on purpose: the renderer samples this, so the
 * ground you see is the ground you land on.
 */
class TerrainField(
    /**
     * The body's radius, in metres. Needed because some features are sized in metres, like the flat
     * ground under the launch complex.
     */
    override val bodyRadius: Double,
    val seed: Int = DEFAULT_SEED,
    /** Metres from the datum up to the highest peaks. */
    override val maxElevation: Double = 6_000.0,
    /** Metres from the datum down to the deepest ocean floor. */
    val oceanDepth: Double = 3_000.0,
    /**
     * Where to guarantee dry land, as a surface normal. The launch complex can't be in the sea, so
     * the terrain is raised around it.
     */
    val homeDirection: Vec3? = null,
    /**
     * Where the launch complex itself stands (pad levelled, runway laid, country kept gentle), if
     * it's not at [homeDirection]. The continent stays raised where it was, since moving that would
     * move every coastline.
     */
    val padDirection: Vec3? = null,
    /**
     * A harbour: a broad natural bay in the coast here, reached by a winding inlet so no swell runs
     * straight in. See [bay].
     */
    val harbourDirection: Vec3? = null,
    /** Which kind of world this is, which picks what shapes the land. */
    val profile: Profile = Profile.TERRA,
) : Terrain {

    enum class Profile { TERRA, LUNA }

    private val luna: LunaLand? = if (profile == Profile.LUNA) LunaLand(seed, bodyRadius) else null

    /** Where Luna's close-up relief stays out. See [Quiet]. */
    private val lunaQuiet: Quiet? = if (luna != null) Quiet(Worlds.spots("luna"), bodyRadius) else null

    override val hasOcean: Boolean get() = profile == Profile.TERRA

    override val barren: Boolean get() = profile != Profile.TERRA
    override val world: String get() = if (profile == Profile.TERRA) "terra" else "luna"

    override val generation: Int get() = GENERATION

    override val tiles: TerrainTileCache by lazy { TerrainTileCache(this) }

    private val scatterField: ScatterField by lazy { ScatterField(this) }
    override val scatter: ScatterField? get() = scatterField

    /** Luna's height without the close-up relief, for tests. */
    internal fun lunaWithoutDetail(direction: Vec3): Double {
        val length = sqrt(direction.x * direction.x + direction.y * direction.y + direction.z * direction.z)
        return luna!!.height(direction.x / length, direction.y / length, direction.z / length)
    }

    override fun isKeptClear(direction: Vec3): Boolean {
        val quiet = lunaQuiet ?: return isLaunchComplex(direction)
        val length = sqrt(direction.x * direction.x + direction.y * direction.y + direction.z * direction.z)
        return quiet.near(direction.x / length, direction.y / length, direction.z / length, Worlds.KEPT_CLEAR)
    }

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

    /** What stands on the continents. Only for a body with a home to keep clear (Terra). */
    private val homeUnit: Vec3? = homeDirection?.normalized()
    private val padUnit: Vec3? = (padDirection ?: homeDirection)?.normalized()
    private val land: TerraLand? = homeUnit?.let { home ->
        val pad = padUnit!!
        TerraLand(seed, bodyRadius, home.x, home.y, home.z, pad.x, pad.y, pad.z)
    }

    /**
     * How many of the works this world has: all of them with a harbour, else only the launch complex
     * and airfield. The quay road, quay and berth are laid against the bay's shore and would cut a
     * pit anywhere else.
     */
    private val workCount: Int = if (harbourDirection != null) WORK_FROM_EAST.size else HARBOUR_WORKS_FROM

    /** The harbour's centre, and its own east and north, for laying out the bay. */
    private val harbourUnit: Vec3? = harbourDirection?.normalized()
    private val harbourEast: Vec3? = harbourUnit?.let { Vec3(0.0, 1.0, 0.0).crossInPlace(it).normalizeInPlace() }
    private val harbourNorth: Vec3? = harbourUnit?.let { it.copy().crossInPlace(harbourEast!!) }

    /**
     * The runway's heading at home: east, the way the spin about +Y carries the ground and the way a
     * horizontal craft launches. Null at a pole.
     */
    private val runwayAlong: Vec3? = padUnit?.let { home ->
        Vec3(0.0, 1.0, 0.0).crossInPlace(home).takeIf { it.lengthSq > 1e-12 }?.normalizeInPlace()
    }
    private val runwayAcross: Vec3? = runwayAlong?.let { along ->
        padUnit!!.copy().crossInPlace(along)
    }

    /**
     * Ground level at the launch complex, worked out once from the field before flattening, since
     * flattening is defined as "level with this". Done up front because [elevation] is on the
     * collision hot path and can't afford a synchronised lazy read.
     */
    private val homeElevation: Double =
        padUnit?.let { kotlin.math.max(shapedElevation(it.x, it.y, it.z), if (hasOcean) PAD_MIN_ELEVATION else -1e9) } ?: 0.0
    /**
     * Height above the datum at [direction] (needn't be normalised), in metres. Negative is sea
     * floor.
     */
    override fun elevation(direction: Vec3): Double {
        // Plain numbers only: this is the hottest function in the game, run on several threads, and
        // temporary vectors cause GC pauses.
        val length = sqrt(direction.x * direction.x + direction.y * direction.y + direction.z * direction.z)
        if (length < 0.7) return 0.0
        val nx = direction.x / length; val ny = direction.y / length; val nz = direction.z / length

        luna?.let {
            val base = it.height(nx, ny, nz)
            val q = lunaQuiet!!.at(nx, ny, nz)
            return if (q > 0.0) base + q * it.detail(nx, ny, nz, base) else base
        }
        val shaped = shapedElevation(nx, ny, nz)
        val home = padUnit ?: return shaped

        // A level pad, and only about a launch complex's footprint of it: rolling ground makes
        // height and drift readable from the cockpit. Offsets use the chord, since acos(dot) loses
        // its precision at these tiny angles.
        val ox = nx - home.x; val oy = ny - home.y; val oz = nz - home.z
        val a = runwayAlong ?: return shaped
        val c = runwayAcross!!
        val east = (a.x * ox + a.y * oy + a.z * oz) * bodyRadius
        val north = (c.x * ox + c.y * oy + c.z * oz) * bodyRadius
        if (abs(east) > WORKS_REACH || abs(north) > WORKS_REACH) return shaped
        // Every work in reach has a say, weighted by closeness. The level is their weighted heights,
        // and the land gives way as far as the nearest needs. Taking only the nearest leaves steps
        // where two blends meet.
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
     * The Cape's works: pad complex, runway, apron, roads, quay and dredged berth. Each is a disc or
     * strip in metres east and north of the pad, levelled to its own height and blended into the
     * land. See [WORK_FROM_EAST].
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

    /** How far the point is from work [k]'s line, in metres. */
    private fun workDistance(k: Int, east: Double, north: Double): Double {
        val ax = WORK_FROM_EAST[k]; val ay = WORK_FROM_NORTH[k]
        val dx = WORK_TO_EAST[k] - ax; val dy = WORK_TO_NORTH[k] - ay
        val lengthSq = dx * dx + dy * dy
        val t = if (lengthSq <= 0.0) 0.0 else (((east - ax) * dx + (north - ay) * dy) / lengthSq).coerceIn(0.0, 1.0)
        val px = ax + dx * t - east; val py = ay + dy * t - north
        return sqrt(px * px + py * py)
    }

    /**
     * The height work [k] is levelled to at the point: its own, or sloping from one end's to the
     * other's.
     */
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
     * What the ground under the launch complex is paved with, or null off it: concrete around the
     * pads and an asphalt runway east from them.
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
        // The last listed wins where two overlap, so roads go over land and the runway over roads.
        var found: SurfaceMaterial? = null
        for (k in 0 until workCount) {
            val material = WORK_MATERIAL[k] ?: continue
            if (workDistance(k, east, north) <= WORK_PAVED[k]) found = material
        }
        return found
    }

    /** The field itself, with the harbour carved in, before the launch complex is levelled into it. */
    private fun shapedElevation(nx: Double, ny: Double, nz: Double): Double {
        val raw = capeLift(nx, ny, nz, naturalElevation(nx, ny, nz))
        val ground = bay(nx, ny, nz, raw)
        return seabed?.cape(nx, ny, nz, ground) ?: ground
    }

    override fun ventField(direction: Vec3): Double {
        val s = seabed ?: return 0.0
        val l = sqrt(direction.x * direction.x + direction.y * direction.y + direction.z * direction.z)
        return s.ventField(direction.x / l, direction.y / l, direction.z / l)
    }

    /** The sea floor's shapes and ground: Terra's, with the Cape's off its coast. See [Seabed]. */
    private val seabed: Seabed? = if (profile == Profile.TERRA) Seabed(seed, bodyRadius, padDirection ?: homeDirection) else null

    /**
     * The Cape's low country lifted clear of its four-metre tides. Low land rises most, higher land
     * less, the shore not at all, so the coastline stays put. Nothing past [CAPE_LIFT_FADE] changes.
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

    /**
     * One paved work: a strip from [fromEast], [fromNorth] to [toEast], [toNorth] (metres from the
     * pad), paved [halfWidth] either side of its line and round its ends. A disc when both ends are
     * the same. Listed in laying order, each over the ones before.
     */
    class PavedWork(
        val fromEast: Double,
        val fromNorth: Double,
        val toEast: Double,
        val toNorth: Double,
        val halfWidth: Double,
        val material: SurfaceMaterial,
        /** Square ends, like a runway, instead of rounded. */
        val squareEnds: Boolean = false,
    )

    /** The Cape's paving, for drawing. Empty on a body without it. */
    val pavedWorks: List<PavedWork> by lazy {
        if (padUnit == null || runwayAlong == null) return@lazy emptyList()
        (0 until workCount).mapNotNull { k ->
            val material = WORK_MATERIAL[k] ?: return@mapNotNull null
            if (WORK_PAVED[k] <= 0.0) return@mapNotNull null
            PavedWork(WORK_FROM_EAST[k], WORK_FROM_NORTH[k], WORK_TO_EAST[k], WORK_TO_NORTH[k], WORK_PAVED[k], material, squareEnds = k == RUNWAY_WORK)
        }
    }

    /** The unit direction [east], [north] metres from the pad, into [out]. Where [pavedWorks] are. */
    fun worksDirection(east: Double, north: Double, out: Vec3 = Vec3()): Vec3 {
        val pad = padUnit!!
        val a = runwayAlong!!
        val c = runwayAcross!!
        return out.setTo(pad).addScaledInPlace(a, east / bodyRadius).addScaledInPlace(c, north / bodyRadius).normalizeInPlace()
    }

    /** Whether [east], [north] of the pad is on the levelled part of any work. */
    private fun onWorks(east: Double, north: Double): Boolean {
        for (k in 0 until workCount) if (workDistance(k, east, north) <= WORK_FLAT[k]) return true
        return false
    }

    /**
     * How much the land at [east], [north] of the pad is the Cape's green country, 0..1. Fully green
     * on and round the works, fading over a kilometre or more along a noise-bent edge, so the grass
     * meets the dry country raggedly.
     */
    private fun capeGreen(east: Double, north: Double): Double {
        var nearest = Double.MAX_VALUE
        for (k in 0 until workCount) nearest = minOf(nearest, workDistance(k, east, north) - WORK_FLAT[k])
        val bend = GREEN_BEND * Noise.simplex(GREEN_SEED, east / GREEN_BEND_SCALE, north / GREEN_BEND_SCALE, 0.5) +
            0.4 * GREEN_BEND * Noise.simplex(GREEN_SEED + 1, east / (0.3 * GREEN_BEND_SCALE), north / (0.3 * GREEN_BEND_SCALE), 0.5)
        return 1.0 - smoothstep(((nearest + bend) / GREEN_REACH).coerceIn(0.0, 1.0))
    }

    /**
     * The harbour's bay cut into [ground]: a basin with a cove to the south-east, opening north into
     * an inlet that bends north-east then north-west to sea, so nothing from the ocean has a straight
     * run in. The shore is noise-bent and shelves from [BAY_FLOOR]. It only lowers the ground.
     */
    private fun bay(nx: Double, ny: Double, nz: Double, ground: Double): Double {
        val centre = harbourUnit ?: return ground
        val ox = nx - centre.x; val oy = ny - centre.y; val oz = nz - centre.z
        val e = harbourEast!!; val n = harbourNorth!!
        val x0 = (e.x * ox + e.y * oy + e.z * oz) * bodyRadius
        val y0 = (n.x * ox + n.y * oy + n.z * oz) * bodyRadius
        if (abs(x0) > BAY_REACH_METRES || abs(y0) > BAY_REACH_METRES) return ground
        // Bent: the outline pushed round by a slow field, then its edge nibbled by a quicker one.
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

    /**
     * Roughly how far outside (positive) or inside an ellipse of radii [rx], [ry] the point [x],
     * [y] from its middle is, in metres.
     */
    private fun ellipseDistance(x: Double, y: Double, rx: Double, ry: Double): Double =
        (sqrt((x / rx) * (x / rx) + (y / ry) * (y / ry)) - 1.0) * minOf(rx, ry)

    /**
     * How far outside (positive) or inside a stroke from a to b the point is, with half-width [ra]
     * at a, narrowing or widening to [rb] at b, in metres.
     */
    private fun taperedDistance(x: Double, y: Double, ax: Double, ay: Double, ra: Double, bx: Double, by: Double, rb: Double): Double {
        val dx = bx - ax; val dy = by - ay
        val t = (((x - ax) * dx + (y - ay) * dy) / (dx * dx + dy * dy)).coerceIn(0.0, 1.0)
        val px = ax + dx * t - x; val py = ay + dy * t - y
        return sqrt(px * px + py * py) - (ra + (rb - ra) * t)
    }

    /** The smaller of [a] and [b], rounded over [k], so two shapes join without a crease. */
    private fun smoothMin(a: Double, b: Double, k: Double): Double {
        val h = (k - abs(a - b)).coerceAtLeast(0.0) / k
        return minOf(a, b) - h * h * k * 0.25
    }

    /** The field as it comes, before anything is built into it. */
    private fun naturalElevation(nx: Double, ny: Double, nz: Double): Double {
        // Continents at the largest scale, then detail. Each octave halves in size and weight, so it
        // looks alike at every distance.
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

        // Push the spread away from the middle so coastlines are definite, or most of the world is
        // beach.
        val centred = (shaped - SEA_FRACTION) / (1.0 - SEA_FRACTION)
        val base = if (centred >= 0.0) {
            StrictMath.pow(centred, LAND_SHARPNESS) * maxElevation
        } else {
            val depth = (-centred / SEA_FRACTION * (1.0 - SEA_FRACTION)).coerceIn(0.0, 1.0)
            -StrictMath.pow(depth, OCEAN_SHARPNESS) * oceanDepth
        }
        if (base <= 0.0) return seabed?.global(nx, ny, nz, base) ?: base

        // Hills, added in metres after sharpening, which would iron the lowlands flat. They fade in
        // over [HILL_SHORE_FADE], over twice [HILL_AMPLITUDE], so no hill digs a pond on the coast.
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
     * What the ground here is made of. Without [land] it falls back to plain height bands (shore,
     * grass, dry upland, rock, snow, and rock on anything steep).
     */
    override fun material(direction: Vec3, elevation: Double, slope: Double): SurfaceMaterial =
        materialOf(direction, elevation, slope, paved = true)

    /** The land under the Cape's paving, which is drawn as its own meshes. See [pavedWorks]. */
    override fun groundMaterial(direction: Vec3, elevation: Double, slope: Double): SurfaceMaterial =
        materialOf(direction, elevation, slope, paved = false)

    private fun materialOf(direction: Vec3, elevation: Double, slope: Double, paved: Boolean): SurfaceMaterial {
        val length = sqrt(direction.x * direction.x + direction.y * direction.y + direction.z * direction.z)
        val nx = direction.x / length; val ny = direction.y / length; val nz = direction.z / length
        luna?.let { return it.material(nx, ny, nz, slope) }
        if (elevation < 0.0) return seabed?.material(nx, ny, nz, elevation, slope) ?: SurfaceMaterial.SAND
        if (paved) paving(direction)?.let { return it }
        land?.let {
            var landness = smoothstep((elevation / HILL_SHORE_FADE).coerceIn(0.0, 1.0))
            // Round the Cape's works it's green country: kept grass on the works, and grass,
            // copses and bare patches around them in place of the dry coast's sand and clay. The
            // beach at the water's edge stays sand.
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
     * An approximate surface normal, for placing things flat on a slope. Sampled by finite
     * difference, since the field has no analytic gradient, which is also closer to what the
     * collider sees.
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
        const val GENERATION = 9

        /** The slope (0 flat, 1 wall) past which ground is bare rock, about 39 degrees. */
        private const val STEEP_SLOPE = 0.22

        /**
         * Octaves of detail. Ten puts the finest features about 3 km across on a 600 km world: enough
         * for the near mesh, and cheap for the collider's hundred-odd samples per craft per tick.
         */
        private const val OCTAVES = 10

        /** Lattice cells across the planet at the coarsest octave. */
        private const val CONTINENT_FREQUENCY = 1.9

        private const val LACUNARITY = 2.07
        private const val PERSISTENCE = 0.5

        /** The fraction of the surface below sea level. */
        private const val SEA_FRACTION = 0.52

        /** Above 1 flattens lowlands and steepens peaks. */
        private const val LAND_SHARPNESS = 1.9
        private const val OCEAN_SHARPNESS = 1.4

        /** How wide the guaranteed land around home is. */
        private const val HOME_FALLOFF_START = 0.985
        private const val HOME_LIFT = 0.22

        /**
         * The Cape's works, in metres east and north of the pad. Each is a strip between two points
         * (a disc if they're the same), level to [WORK_FLAT] from its line, blended over
         * [WORK_BLEND], and paved with [WORK_MATERIAL] to [WORK_PAVED]. NaN heights are the pad's.
         *
         * The pad complex blends over 700 m so it's a low hill, not a mesa. The runway ends short of
         * the bay so planes climb out over water. The berth is dredged for the Trawler.
         */
        // pad, runway, apron, two taxiways, roads (four legs), quay, berth
        private val WORK_FROM_EAST = doubleArrayOf(0.0, RUNWAY_WEST, 350.0, 420.0, 640.0, 0.0, 700.0, 2_650.0, 2_600.0, 2_560.0, 2_570.0, 2_625.0)
        private val WORK_FROM_NORTH = doubleArrayOf(0.0, RUNWAY_NORTH, -285.0, -330.0, -330.0, -110.0, -235.0, -235.0, 0.0, 200.0, 270.0, 350.0)
        private val WORK_TO_EAST = doubleArrayOf(0.0, RUNWAY_EAST, 700.0, 420.0, 640.0, 350.0, 2_650.0, 2_600.0, 2_560.0, 2_560.0, 2_570.0, 2_770.0)
        private val WORK_TO_NORTH = doubleArrayOf(0.0, RUNWAY_NORTH, -285.0, -390.0, -390.0, -235.0, -235.0, 0.0, 200.0, 270.0, 430.0, 350.0)
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

        /**
         * The runway's centreline, in metres east and north of the pad: west end, east end, its line
         * [RUNWAY_NORTH] metres north (so south), and its paved half-width.
         */
        const val RUNWAY_WEST = 250.0
        const val RUNWAY_EAST = 2_750.0
        const val RUNWAY_NORTH = -400.0
        const val RUNWAY_HALF_WIDTH = 25.0

        /** The runway's place in the works table. */
        private const val RUNWAY_WORK = 1

        /**
         * Where the harbour's works start in the table: the road's third leg, down toward the
         * shore.
         */
        private const val HARBOUR_WORKS_FROM = 7

        /** The Cape's low country is lifted by up to this many metres. See [capeLift]. */
        private const val CAPE_LIFT = 7.0

        /**
         * How quickly the lift comes in above the shoreline, and how it dies away over high ground,
         * in metres.
         */
        private const val CAPE_LIFT_SHORE = 0.4
        private const val CAPE_LIFT_HIGH = 6.0

        /** Fully lifted within this many metres of the pad, and not at all past [CAPE_LIFT_FADE]. */
        private const val CAPE_LIFT_REACH = 5_000.0
        private const val CAPE_LIFT_FADE = 8_000.0

        /**
         * The Cape's green country: how far past the works it fades, in metres, and how bent its edge
         * is. See [capeGreen].
         */
        private const val GREEN_REACH = 1_200.0
        private const val GREEN_BEND = 350.0
        private const val GREEN_BEND_SCALE = 900.0
        private const val GREEN_SEED = 0x6EE

        /** Below this many metres, the Cape's country is still beach. */
        private const val GREEN_BEACH = 1.5

        /** None of the works reach further than this many metres east or north of the pad. */
        private const val WORKS_REACH = 3_500.0

        /** The harbour's quay, in metres above the datum, clear of the bay's highest tide. */
        private const val QUAY_HEIGHT = 4.5

        /** The berth's dredged depth in metres, leaving room under the Trawler at lowest tide. */
        private const val BERTH_DEPTH = -7.0

        /**
         * The lowest a seaside launch complex is built, in metres above the datum, clear of the
         * highest tide plus surf. Lower ground is built up to it.
         */
        private const val PAD_MIN_ELEVATION = 15.0


        /**
         * The harbour's bay, in metres east and north of its middle: a basin [BAY_RADIUS_X] by
         * [BAY_RADIUS_Y] round ([BAY_X], [BAY_Y]), its west shore just past the runway's end, and a
         * cove to the south-east. [BAY_FLOOR] deep, shelving to the shore over [BAY_SHELF_METRES].
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
         * The inlet's line, as east, north and half-width in metres: north-east out of the basin,
         * then north-west to open sea, widening between the headlands. No straight line from harbour
         * to open water.
         */
        private val INLET = doubleArrayOf(
            300.0, 2_400.0, 1_100.0,
            1_000.0, 4_000.0, 600.0,
            0.0, 5_400.0, 650.0,
            -900.0, 6_100.0, 1_100.0,
        )

        /** How smoothly the basin, cove and inlet run into each other, in metres. */
        private const val BAY_BLEND_METRES = 700.0

        /** The slow bending of the whole outline: how far, over what distance, in metres. */
        private const val BAY_WARP_METRES = 450.0
        private const val BAY_WARP_SCALE = 2_500.0

        /** The quicker nibbling of the shore itself. */
        private const val BAY_EDGE_METRES = 180.0
        private const val BAY_EDGE_SCALE = 900.0

        private const val BAY_SEED = 0xBA1

        /** How fast the banks rise behind the waterline, in metres per metre. */
        private const val BANK_GRADE = 0.08

        /** Nothing of the bay reaches further than this many metres from its middle, east or north. */
        private const val BAY_REACH_METRES = 12_000.0

        /**
         * The hill band. Sized in metres and added after sharpening, so lowlands get the same
         * relief.
         */
        private const val HILL_FREQUENCY = 250.0

        /**
         * Five, so the finest octave is small enough to give the near field texture. At four it
         * bottomed out around 260 m and the ground looked painted.
         */
        private const val HILL_OCTAVES = 5
        private const val HILL_AMPLITUDE = 150.0

        /** Land below this height gets proportionally fewer hills. */
        private const val HILL_SHORE_FADE = 400.0

    }
}
