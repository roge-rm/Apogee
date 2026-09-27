package com.rm.apogee.core.terrain

import com.rm.apogee.core.math.Vec3
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.sqrt

/**
 * The shape of a planet's surface: one height function, worked out everywhere.
 *
 * **There's deliberately no GLSL version of this.** The obvious way to draw a procedural planet is
 * to run the same noise in a fragment shader, and that's a trap. It means two versions of one
 * function in two languages, which drift apart the first time either one is touched, and then a
 * craft collides with a sea floor while the screen shows a mountain. Instead the renderer *samples
 * this* to build its mesh, so the ground you see is always the ground you land on.
 *
 * It comes from [seed] alone, with no floating-point surprises. The hash is integer maths and the
 * interpolation is plain smoothstep, so a phone and a server working out the same point get the
 * same metre.
 */
class TerrainField(
    /**
     * The radius of the body this describes, in metres.
     *
     * The field needs it because some of its features are sized in metres instead of fractions of a
     * sphere. The flat ground under the launch complex matters most, because it has to be about as
     * wide as a launch complex, not about as wide as a tenth of a degree.
     */
    override val bodyRadius: Double,
    val seed: Int = DEFAULT_SEED,
    /** Metres from the datum up to the highest peaks. */
    override val maxElevation: Double = 6_000.0,
    /** Metres from the datum down to the deepest ocean floor. */
    val oceanDepth: Double = 3_000.0,
    /**
     * Where to guarantee dry land, as a surface normal.
     *
     * The launch complex is a fixed point on the planet and can't be in the sea. Instead of
     * searching the noise for a suitable coastline, the terrain is raised around it, which is also
     * how real launch sites come about.
     */
    val homeDirection: Vec3? = null,
    /**
     * Where the launch complex itself stands (its pad levelled, its runway laid, the country around
     * it kept gentle), if it's not at [homeDirection]. The continent stays raised where it always
     * was, because moving that would move every coastline on the planet.
     */
    val padDirection: Vec3? = null,
    /**
     * A harbour: a broad natural bay in the coast here, reached from the sea by an inlet that winds
     * on its way in, so no swell runs straight into it. See [bay].
     */
    val harbourDirection: Vec3? = null,
    /** Which kind of world this is, which decides what shapes the land. */
    val profile: Profile = Profile.TERRA,
) : Terrain {

    enum class Profile { TERRA, LUNA }

    private val luna: LunaLand? = if (profile == Profile.LUNA) LunaLand(seed, bodyRadius) else null

    override val hasOcean: Boolean get() = profile == Profile.TERRA

    override val barren: Boolean get() = profile != Profile.TERRA
    override val world: String get() = if (profile == Profile.TERRA) "terra" else "luna"

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
     * What stands on the continents. Only for a body with a home to keep clear, which is Terra.
     * Other bodies get their own profiles.
     */
    private val homeUnit: Vec3? = homeDirection?.normalized()
    private val padUnit: Vec3? = (padDirection ?: homeDirection)?.normalized()
    private val land: TerraLand? = homeUnit?.let { home ->
        val pad = padUnit!!
        TerraLand(seed, bodyRadius, home.x, home.y, home.z, pad.x, pad.y, pad.z)
    }

    /**
     * How many of the works this world has: all of them where there's a harbour, and only the
     * launch complex and the airfield where there isn't. The road down to the quay, the quay and
     * its berth are laid out against the bay's own shore, and anywhere else they'd cut a pit.
     */
    private val workCount: Int = if (harbourDirection != null) WORK_FROM_EAST.size else HARBOUR_WORKS_FROM

    /** The harbour's centre, and its own east and north, for laying out the bay. */
    private val harbourUnit: Vec3? = harbourDirection?.normalized()
    private val harbourEast: Vec3? = harbourUnit?.let { Vec3(0.0, 1.0, 0.0).crossInPlace(it).normalizeInPlace() }
    private val harbourNorth: Vec3? = harbourUnit?.let { it.copy().crossInPlace(harbourEast!!) }

    /**
     * The runway's heading at home: east, which is the way the planet's spin around +Y carries the
     * ground, and the way a horizontal craft is pointed when it launches. Null at a pole, where
     * there's no east.
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
     * It's worked out once from the field before flattening, because the flattening is defined as
     * "level with this", and it can't be asked what that is without going round in a loop. It's
     * done up front instead of lazily because [elevation] is on the collision hot path and doesn't
     * want a synchronised read for every contact point.
     */
    private val homeElevation: Double =
        padUnit?.let { kotlin.math.max(shapedElevation(it.x, it.y, it.z), if (hasOcean) PAD_MIN_ELEVATION else -1e9) } ?: 0.0
    /**
     * Height above the datum at [direction], in metres. Negative is sea floor.
     *
     * [direction] doesn't need to be normalised.
     */
    override fun elevation(direction: Vec3): Double {
        // Plain numbers throughout, because this is the hottest function in the game, run on
        // several threads at once, and every temporary vector here became garbage a collector later
        // stopped the world to sweep up.
        val length = sqrt(direction.x * direction.x + direction.y * direction.y + direction.z * direction.z)
        if (length < 0.7) return 0.0
        val nx = direction.x / length; val ny = direction.y / length; val nz = direction.z / length

        luna?.let { return it.height(nx, ny, nz) }
        val shaped = shapedElevation(nx, ny, nz)
        val home = padUnit ?: return shaped

        // A level pad, and only a level pad. Rolling ground is what makes height and sideways drift
        // readable from the cockpit, so the flattening is kept to about the footprint of a launch
        // complex.
        //
        // It uses chord length instead of acos(dot). At these angles the dot product is within a
        // rounding error of 1 and acos throws away most of its precision, while the chord is still
        // exact.
        val ox = nx - home.x; val oy = ny - home.y; val oz = nz - home.z
        val a = runwayAlong ?: return shaped
        val c = runwayAcross!!
        val east = (a.x * ox + a.y * oy + a.z * oz) * bodyRadius
        val north = (c.x * ox + c.y * oy + c.z * oz) * bodyRadius
        if (abs(east) > WORKS_REACH || abs(north) > WORKS_REACH) return shaped
        // Every work close enough to reach here has a say, weighted by how close it is. The level
        // is their heights weighted that way, and the land gives way to it as far as the nearest of
        // them needs. Taking only the nearest put a fourteen metre step where a road's blend met
        // the quay's.
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
     * The Cape's works: the pad complex, the runway, the airfield's apron, roads, the harbour's
     * quay and its dredged berth. Each is a disc or a strip in metres east and north of the pad,
     * levelled to its own height and blended back into the land around it. See [WORK_FROM_EAST].
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
     * The height work [k] is levelled to where the point is: its own height, or sloping from one
     * end's to the other's.
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
     * What the ground under the launch complex is paved with, or null off it: a concrete pad around
     * the pads themselves, and an asphalt runway running east from it.
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
        // The last one listed wins where two overlap, so roads go over the land they cross and the
        // runway goes over the road that meets it.
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

    /**
     * The sea floor's own shapes and ground. See [Seabed]. It's Terra's, with the Cape's off its
     * coast.
     */
    private val seabed: Seabed? = if (profile == Profile.TERRA) Seabed(seed, bodyRadius, padDirection ?: homeDirection) else null

    /**
     * The Cape's low country lifted clear of the tide. The plain around the pad came out of the
     * field a metre or two above the datum, which is under the four-metre tides on this coast. So
     * at high water it was a tidal flat kilometres wide with the pad as an island in it, and seen
     * from above, the sea drawn around the craft was a disc of shallows and foam on land that
     * looked dry. Low land is raised the most, higher land less, and the shore not at all, so the
     * coastline stays where it was. Nothing further than [CAPE_LIFT_FADE] from the pad changes.
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
     * pad), paved [halfWidth] on either side of its line and around its ends. It's a disc where the
     * two ends are the same. They're listed in the order they're laid, each over the ones before
     * it.
     */
    class PavedWork(
        val fromEast: Double,
        val fromNorth: Double,
        val toEast: Double,
        val toNorth: Double,
        val halfWidth: Double,
        val material: SurfaceMaterial,
        /** Cut square across its ends, like a runway, instead of rounded. */
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

    /**
     * The unit direction [east], [north] metres from the pad, into [out], which is where
     * [pavedWorks] are.
     */
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
     * How much the land at [east], [north] of the pad is the Cape's green country, 0..1. It's fully
     * green on and around the works, fading out over a kilometre and more beyond them along an edge
     * bent by noise, so the grass meets the dry country around it as a ragged margin instead of a
     * ring.
     */
    private fun capeGreen(east: Double, north: Double): Double {
        var nearest = Double.MAX_VALUE
        for (k in 0 until workCount) nearest = minOf(nearest, workDistance(k, east, north) - WORK_FLAT[k])
        val bend = GREEN_BEND * Noise.simplex(GREEN_SEED, east / GREEN_BEND_SCALE, north / GREEN_BEND_SCALE, 0.5) +
            0.4 * GREEN_BEND * Noise.simplex(GREEN_SEED + 1, east / (0.3 * GREEN_BEND_SCALE), north / (0.3 * GREEN_BEND_SCALE), 0.5)
        return 1.0 - smoothstep(((nearest + bend) / GREEN_REACH).coerceIn(0.0, 1.0))
    }

    /**
     * The harbour's bay, cut into [ground]: a broad basin with a cove to its south-east, opening
     * northward into an inlet that winds north-east and then north-west out to sea, getting wider
     * between its headlands as it goes. The bend is the point. Nothing the ocean sends has a
     * straight run in to the harbour. The shore is bent and nibbled by noise so it looks like a
     * coast instead of a drawing, shelving gently from [BAY_FLOOR] to beaches, with banks rising
     * behind them. It only ever lowers the ground, so nothing changes beyond its banks, and out at
     * the mouth the sea floor it meets is the ocean's own.
     */
    private fun bay(nx: Double, ny: Double, nz: Double, ground: Double): Double {
        val centre = harbourUnit ?: return ground
        val ox = nx - centre.x; val oy = ny - centre.y; val oz = nz - centre.z
        val e = harbourEast!!; val n = harbourNorth!!
        val x0 = (e.x * ox + e.y * oy + e.z * oz) * bodyRadius
        val y0 = (n.x * ox + n.y * oy + n.z * oz) * bodyRadius
        if (abs(x0) > BAY_REACH_METRES || abs(y0) > BAY_REACH_METRES) return ground
        // Bent: the whole outline pushed around by a slow field, then its edge nibbled by a quicker
        // one.
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
        // Continents at the largest scale, then detail. Each octave halves in size and in
        // contribution, which is what makes the result look the same at every distance.
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

        // Push the spread away from the middle so coastlines are definite. Without it most of the
        // planet sits within a few metres of sea level and the whole world is beach.
        val centred = (shaped - SEA_FRACTION) / (1.0 - SEA_FRACTION)
        val base = if (centred >= 0.0) {
            StrictMath.pow(centred, LAND_SHARPNESS) * maxElevation
        } else {
            val depth = (-centred / SEA_FRACTION * (1.0 - SEA_FRACTION)).coerceIn(0.0, 1.0)
            -StrictMath.pow(depth, OCEAN_SHARPNESS) * oceanDepth
        }
        if (base <= 0.0) return seabed?.global(nx, ny, nz, base) ?: base

        // Hills, in metres instead of as another octave of the curve above.
        //
        // Adding them before the sharpening curve is the obvious approach and doesn't work.
        // LAND_SHARPNESS squashes everything near sea level, so exactly the lowland a craft
        // launches from comes out ironed flat. Added in metres afterwards, a hill is the same hill
        // wherever it stands.
        //
        // They fade in over the first few hundred metres of land so the shoreline stays where the
        // curve put it. HILL_SHORE_FADE is more than twice HILL_AMPLITUDE, which guarantees a hill
        // can never dig a patch of land back below the waterline and speckle the coast with ponds.
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
     * For now these are the same bands the renderer has always coloured by (shore, grass, dry
     * upland, rock, snow, and rock on anything steep), so that moving the classification out of the
     * shader changes nothing you can see. Biomes replace it.
     */
    override fun material(direction: Vec3, elevation: Double, slope: Double): SurfaceMaterial =
        materialOf(direction, elevation, slope, paved = true)

    /** The land under the Cape's paving, which is drawn as its own straight-edged meshes. See [pavedWorks]. */
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
            // Around the Cape's works it's green country: kept grass on the works themselves, and
            // grass country around them, where the dry coast's sand and clay give way to grass,
            // copses and bare patches. The beach right at the water's edge stays sand.
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
     * An approximate surface normal, for placing things flat on a slope.
     *
     * It's sampled instead of worked out directly, because the field has no analytic gradient, and
     * a finite difference over a few metres is both simpler and closer to what the collider
     * actually sees.
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
        const val GENERATION = 8

        /** The slope (0 flat, 1 wall) past which ground is bare rock, about 39 degrees. */
        private const val STEEP_SLOPE = 0.22

        /**
         * Octaves of detail.
         *
         * Ten puts the finest features at roughly three kilometres across on a 600km world. That's
         * fine enough for a near-field mesh to have something to show, and coarse enough that the
         * collider's hundred or so samples per craft per tick stay cheap.
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
         * The Cape's works, in metres east and north of the pad. Each is a strip from one point to
         * another (a disc where the two are the same), levelled flat out to [WORK_FLAT] from its
         * line, blended back into the land by [WORK_BLEND] beyond that, and paved with
         * [WORK_MATERIAL] out to [WORK_PAVED]. Heights of NaN are the pad's own.
         *
         * The pad complex is dead level out to three hundred metres and rises to it over seven
         * hundred more, so it's a low hill. A tighter blend made a flat-topped mesa out of it.
         *
         * The runway is 2.5 km east, parallel to the row of pads and 400 m south of it, clear of
         * every pad, and ends short of the bay's west shore so a plane climbs out over the water.
         *
         * The airfield's apron is at its west end, north of it, with two taxiways down to it.
         *
         * Roads run from the pad to the apron, along the runway from the apron, then wind down
         * inland of the shore to the harbour's quay, which runs along the bay's west shore. Its
         * berth, dredged deep enough for the Trawler, runs out east from the quay beside the jetty.
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

        /** The runway's place in the works table. */
        private const val RUNWAY_WORK = 1

        /**
         * Where the harbour's works start in the table: the road's third leg, heading down toward
         * the shore.
         */
        private const val HARBOUR_WORKS_FROM = 7

        /** The Cape's low country is lifted by up to this many metres. See [capeLift]. */
        private const val CAPE_LIFT = 7.0

        /**
         * How quickly the lift comes in above the shoreline, in metres, and how it dies away over
         * higher ground, in metres.
         */
        private const val CAPE_LIFT_SHORE = 0.4
        private const val CAPE_LIFT_HIGH = 6.0

        /**
         * Fully lifted within this many metres of the pad, and not at all past [CAPE_LIFT_FADE].
         */
        private const val CAPE_LIFT_REACH = 5_000.0
        private const val CAPE_LIFT_FADE = 8_000.0

        /**
         * The Cape's green country: how far beyond the works it fades out, in metres, and how bent
         * its edge is. See [capeGreen].
         */
        private const val GREEN_REACH = 1_200.0
        private const val GREEN_BEND = 350.0
        private const val GREEN_BEND_SCALE = 900.0
        private const val GREEN_SEED = 0x6EE

        /** Below this many metres, the Cape's country is still beach. */
        private const val GREEN_BEACH = 1.5

        /** None of the works reach further than this many metres east or north of the pad. */
        private const val WORKS_REACH = 3_500.0

        /**
         * The harbour's quay, in metres above the datum, clear of the highest tide the bay gets.
         */
        private const val QUAY_HEIGHT = 4.5

        /**
         * Its berth is dredged to this depth in metres, which leaves room under the Trawler at the
         * lowest tide.
         */
        private const val BERTH_DEPTH = -7.0

        /**
         * The lowest a launch complex by the sea is built, in metres above the datum, clear of the
         * highest tide the coast gets plus the surf on top of it. Lower ground gets built up to it.
         */
        private const val PAD_MIN_ELEVATION = 15.0


        /**
         * The harbour's bay, in metres east and north of its middle: a basin [BAY_RADIUS_X] by
         * [BAY_RADIUS_Y] around ([BAY_X], [BAY_Y]), five kilometres of water across with its west
         * shore just past the end of the runway, and a cove to its south-east, [BAY_FLOOR] deep,
         * shelving up to the shore over [BAY_SHELF_METRES].
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
         * The inlet's line, as east, north and half-width in metres: out of the basin to the
         * north-east, then north-west to the open sea, opening out between the headlands at its
         * mouth. The bend is the point. There's no straight line from the harbour to open water.
         */
        private val INLET = doubleArrayOf(
            300.0, 2_400.0, 1_100.0,
            1_000.0, 4_000.0, 600.0,
            0.0, 5_400.0, 650.0,
            -900.0, 6_100.0, 1_100.0,
        )

        /** How smoothly the basin, cove and inlet run into each other, in metres. */
        private const val BAY_BLEND_METRES = 700.0

        /**
         * The slow bending of the whole outline: how far in metres, over what distance in metres.
         */
        private const val BAY_WARP_METRES = 450.0
        private const val BAY_WARP_SCALE = 2_500.0

        /** The quicker nibbling of the shore itself. */
        private const val BAY_EDGE_METRES = 180.0
        private const val BAY_EDGE_SCALE = 900.0

        private const val BAY_SEED = 0xBA1

        /** How fast the banks rise behind the waterline, in metres per metre. */
        private const val BANK_GRADE = 0.08

        /**
         * Nothing of the bay reaches further than this many metres from its middle, east or north.
         */
        private const val BAY_REACH_METRES = 12_000.0

        /**
         * The hill band. It's sized in metres and added after the sharpening curve, so lowlands get
         * the same relief as highlands.
         */
        private const val HILL_FREQUENCY = 250.0

        /**
         * Five, not four. The fourth octave bottoms out at around two hundred and sixty metres,
         * which is bigger than anything a craft on the ground can see past, so the near field had
         * no texture at all and looked like a painted plane.
         */
        private const val HILL_OCTAVES = 5
        private const val HILL_AMPLITUDE = 150.0

        /** Land below this height gets proportionally fewer hills. */
        private const val HILL_SHORE_FADE = 400.0

    }
}
