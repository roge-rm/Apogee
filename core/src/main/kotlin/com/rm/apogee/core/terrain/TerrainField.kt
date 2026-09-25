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
     * A harbour: a bay carved into the coast here, round and sheltered, with
     * a channel that bends on its way out to sea so no swell runs straight
     * in. See [bay].
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
        val metres = sqrt(ox * ox + oy * oy + oz * oz) * bodyRadius
        if (metres < PAD_BLEND_METRES) return true
        return runwayBlend(ox, oy, oz) < 1.0
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
        val metres = sqrt(ox * ox + oy * oy + oz * oz) * bodyRadius
        if (metres >= PAD_BLEND_METRES + RUNWAY_LENGTH_METRES + RUNWAY_BLEND_METRES) return shaped
        val padBlend = if (metres >= PAD_BLEND_METRES) 1.0 else smoothstep(
            ((metres - PAD_FLAT_METRES) / (PAD_BLEND_METRES - PAD_FLAT_METRES))
                .coerceIn(0.0, 1.0)
        )
        val t = minOf(padBlend, runwayBlend(ox, oy, oz))
        if (t >= 1.0) return shaped
        return homeElevation + (shaped - homeElevation) * t
    }

    /**
     * 0 on the runway, rising to 1 where the real terrain takes over.
     *
     * A strip of dead-level ground running east from the pad, because a craft
     * that takes off along the ground needs somewhere to do it: the pad is
     * level for three hundred metres, and the stock aeroplane rolled off the
     * end of that into rising ground and was shoved up the hillside by the
     * contact solver while its log reported a take-off. Kept narrow, like the
     * pad, so the country either side still rolls.
     *
     * [offset] is from home to the point on the unit sphere; at these
     * distances that is as good as a flat map, which is all a strip a few
     * kilometres long needs.
     */
    private fun runwayBlend(ox: Double, oy: Double, oz: Double): Double {
        val a = runwayAlong ?: return 1.0
        val c = runwayAcross!!
        val along = (a.x * ox + a.y * oy + a.z * oz) * bodyRadius
        val across = abs((c.x * ox + c.y * oy + c.z * oz) * bodyRadius)
        // Distance outside the strip's rectangle; nought anywhere on it. The
        // near end starts at the pad, which covers everything west of it.
        val beyondEnd = max(0.0, max(along - RUNWAY_LENGTH_METRES, -along))
        val beyondEdge = max(0.0, across - RUNWAY_HALF_WIDTH_METRES)
        val outside = sqrt(beyondEnd * beyondEnd + beyondEdge * beyondEdge)
        if (outside >= RUNWAY_BLEND_METRES) return 1.0
        return smoothstep(outside / RUNWAY_BLEND_METRES)
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
        val metres = sqrt(ox * ox + oy * oy + oz * oz) * bodyRadius
        if (metres < CONCRETE_METRES) return SurfaceMaterial.CONCRETE
        val a = runwayAlong ?: return null
        val c = runwayAcross!!
        val along = (a.x * ox + a.y * oy + a.z * oz) * bodyRadius
        val across = abs((c.x * ox + c.y * oy + c.z * oz) * bodyRadius)
        if (along in 0.0..RUNWAY_LENGTH_METRES && across < ASPHALT_HALF_WIDTH_METRES) return SurfaceMaterial.ASPHALT
        return null
    }

    /** The field proper, with the harbour carved in, before the launch complex is levelled into it. */
    private fun shapedElevation(nx: Double, ny: Double, nz: Double): Double {
        val raw = naturalElevation(nx, ny, nz)
        return bay(nx, ny, nz, raw)
    }

    /**
     * The harbour's bay, cut into [ground]: a round basin [BAY_FLOOR] metres
     * deep out to [BAY_FLAT_METRES], shelving to its shore at
     * [BAY_SHORE_METRES], with banks rising gently behind; and a channel of
     * the same make from its north side, north and then north-west out to
     * sea. Only ever lowers the ground, so beyond its banks nothing changes.
     */
    private fun bay(nx: Double, ny: Double, nz: Double, ground: Double): Double {
        val centre = harbourUnit ?: return ground
        val ox = nx - centre.x; val oy = ny - centre.y; val oz = nz - centre.z
        val e = harbourEast!!; val n = harbourNorth!!
        val x = (e.x * ox + e.y * oy + e.z * oz) * bodyRadius
        val y = (n.x * ox + n.y * oy + n.z * oz) * bodyRadius
        if (abs(x) > BAY_REACH_METRES || abs(y) > BAY_REACH_METRES) return ground
        var cut = cutProfile(sqrt(x * x + y * y), BAY_FLAT_METRES, BAY_SHORE_METRES, BAY_FLOOR)
        var along = Double.MAX_VALUE
        for (k in 0 until CHANNEL.size / 2 - 1) {
            along = minOf(along, segmentDistance(x, y, CHANNEL[2 * k], CHANNEL[2 * k + 1], CHANNEL[2 * k + 2], CHANNEL[2 * k + 3]))
        }
        cut = minOf(cut, cutProfile(along, CHANNEL_FLAT_METRES, CHANNEL_SHORE_METRES, CHANNEL_FLOOR))
        return minOf(ground, cut)
    }

    /** A cut [floor] deep out to [flat] m from its middle, up to the waterline at [shore], then a gentle bank. */
    private fun cutProfile(d: Double, flat: Double, shore: Double, floor: Double): Double {
        if (d <= flat) return floor
        if (d <= shore) return floor * (1.0 - smoothstep((d - flat) / (shore - flat)))
        return (d - shore) * BANK_GRADE
    }

    private fun segmentDistance(x: Double, y: Double, ax: Double, ay: Double, bx: Double, by: Double): Double {
        val dx = bx - ax; val dy = by - ay
        val t = (((x - ax) * dx + (y - ay) * dy) / (dx * dx + dy * dy)).coerceIn(0.0, 1.0)
        val px = ax + dx * t - x; val py = ay + dy * t - y
        return sqrt(px * px + py * py)
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
            val landness = smoothstep((elevation / HILL_SHORE_FADE).coerceIn(0.0, 1.0))
            return it.material(nx, ny, nz, elevation, slope, landness)
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
        const val GENERATION = 3

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

        /** Dead level out to here, metres: the launch complex itself. */
        private const val PAD_FLAT_METRES = 300.0

        /**
         * Blended back into the real terrain by here, metres.
         *
         * Kept tight. A wide blend is invisible on a contour plot and very
         * visible from the cockpit: at twelve hundred metres it flattened
         * everything the eye can actually resolve from the pad, and the
         * homeworld looked like a billiard table with hills painted on the
         * horizon.
         */
        private const val PAD_BLEND_METRES = 600.0

        /**
         * The runway, metres. Long enough for the stock aeroplane's roll of
         * about three hundred metres several times over, since a player's
         * first design will be heavier and slower than it.
         */
        private const val RUNWAY_LENGTH_METRES = 2_500.0
        private const val RUNWAY_HALF_WIDTH_METRES = 40.0
        private const val RUNWAY_BLEND_METRES = 250.0

        /**
         * The lowest a launch complex by the sea is built, m above the
         * datum: clear of the highest tide the coast sees, and the surf on
         * top of it. Lower ground is made up to it.
         */
        private const val PAD_MIN_ELEVATION = 15.0

        /** Concrete round the pads, m from the middle; asphalt either side of the runway's line. */
        private const val CONCRETE_METRES = 110.0
        private const val ASPHALT_HALF_WIDTH_METRES = 25.0

        /**
         * The harbour's bay, m: a basin [BAY_FLOOR] deep to [BAY_FLAT_METRES]
         * from its middle, its shore at [BAY_SHORE_METRES] - four kilometres
         * of water across, small enough that the wind raises only a chop on
         * it - and a channel [CHANNEL_FLOOR] deep and some four hundred
         * metres wide at the waterline.
         */
        private const val BAY_FLOOR = -14.0
        private const val BAY_FLAT_METRES = 1_400.0
        private const val BAY_SHORE_METRES = 2_000.0
        private const val CHANNEL_FLOOR = -12.0
        private const val CHANNEL_FLAT_METRES = 110.0
        private const val CHANNEL_SHORE_METRES = 220.0

        /** Rise of the banks behind the waterline, m per m. */
        private const val BANK_GRADE = 0.08

        /** Nothing of the bay reaches past this, m from its middle, east or north. */
        private const val BAY_REACH_METRES = 12_000.0

        /**
         * The channel's line, m east and north of the bay's middle: out of
         * the basin northward, then turning north-west for the open sea. The
         * bend is the point - there is no straight line from the harbour to
         * open water, so nothing the ocean sends reaches it straight.
         */
        private val CHANNEL = doubleArrayOf(0.0, 1_500.0, 0.0, 4_500.0, -3_340.0, 7_500.0)

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
