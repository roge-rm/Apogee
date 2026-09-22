package com.rm.apogee.core.terrain

import com.rm.apogee.core.math.Vec3
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.pow

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
    val bodyRadius: Double,
    val seed: Int = DEFAULT_SEED,
    /** Metres from the datum to the highest peaks. */
    val maxElevation: Double = 6_000.0,
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
) {
    private val homeUnit: Vec3? = homeDirection?.normalized()

    /**
     * Ground level at the launch complex.
     *
     * Computed once from the unflattened field, because the flattening is
     * defined as "level with this" and cannot be asked what that is without
     * looping. Eager rather than lazy: [elevation] is on the collision hot
     * path and does not want a synchronised read per contact point.
     */
    private val homeElevation: Double =
        homeUnit?.let { shapedElevation(it) } ?: 0.0
    /**
     * Height above the datum at [direction], in metres. Negative is sea floor.
     *
     * [direction] need not be normalised.
     */
    fun elevation(direction: Vec3): Double {
        val n = direction.normalized()
        if (n.lengthSq < 0.5) return 0.0

        val shaped = shapedElevation(n)
        val home = homeUnit ?: return shaped

        // A level pad, and only a level pad. Rolling ground is what makes
        // altitude and lateral drift legible from the cockpit, so the
        // flattening is kept to about the footprint of a launch complex
        // rather than the several kilometres that used to make the whole
        // horizon a flat green sheet.
        //
        // Chord length rather than acos(dot): at these angles the dot product
        // is within a rounding error of 1 and acos throws away most of its
        // precision, while the chord is still exact.
        val metres = (n - home).length * bodyRadius
        if (metres >= PAD_BLEND_METRES) return shaped

        val t = smoothstep(
            ((metres - PAD_FLAT_METRES) / (PAD_BLEND_METRES - PAD_FLAT_METRES))
                .coerceIn(0.0, 1.0)
        )
        return homeElevation + (shaped - homeElevation) * t
    }

    /** The field proper, before the launch complex is levelled into it. */
    private fun shapedElevation(n: Vec3): Double {
        // Continents at the largest scale, then detail. Each octave halves in
        // size and in contribution, which is what makes the result look the
        // same at every distance.
        var amplitude = 1.0
        var frequency = CONTINENT_FREQUENCY
        var total = 0.0
        var normalisation = 0.0

        repeat(OCTAVES) {
            total += amplitude * noise(n.x * frequency, n.y * frequency, n.z * frequency)
            normalisation += amplitude
            amplitude *= PERSISTENCE
            frequency *= LACUNARITY
        }
        var shaped = total / normalisation

        // A continent under the launch complex.
        homeDirection?.let { home ->
            val closeness = ((n dot home.normalized()) - HOME_FALLOFF_START) /
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
            centred.pow(LAND_SHARPNESS) * maxElevation
        } else {
            val depth = (-centred / SEA_FRACTION * (1.0 - SEA_FRACTION)).coerceIn(0.0, 1.0)
            -depth.pow(OCEAN_SHARPNESS) * oceanDepth
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
        return base + hills(n) * HILL_AMPLITUDE * landness
    }

    /** Hill-scale detail, -1..1, as its own band of octaves. */
    private fun hills(n: Vec3): Double {
        var amplitude = 1.0
        var frequency = HILL_FREQUENCY
        var total = 0.0
        var normalisation = 0.0
        repeat(HILL_OCTAVES) {
            total += amplitude * noise(n.x * frequency, n.y * frequency, n.z * frequency)
            normalisation += amplitude
            amplitude *= PERSISTENCE
            frequency *= LACUNARITY
        }
        return (total / normalisation) * 2.0 - 1.0
    }

    /** True where the datum surface is above the terrain. */
    fun isOcean(direction: Vec3): Boolean = elevation(direction) < 0.0

    /**
     * Distance from the planet's centre to the surface a craft rests on.
     *
     * Water counts as solid for now: a craft that comes down in the sea sits
     * on it rather than sinking. Buoyancy replaces this when hulls arrive, and
     * this is the one line that changes.
     */
    fun surfaceRadius(direction: Vec3): Double =
        bodyRadius + max(elevation(direction), 0.0)

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

    // --- noise --------------------------------------------------------------

    /**
     * Value noise on an integer lattice.
     *
     * Value rather than gradient noise because it needs no permutation table
     * and no vector lookups: the whole function is a handful of integer
     * operations, which is what makes it reproducible everywhere without
     * shipping data alongside it.
     */
    private fun noise(x: Double, y: Double, z: Double): Double {
        val xi = kotlin.math.floor(x).toInt()
        val yi = kotlin.math.floor(y).toInt()
        val zi = kotlin.math.floor(z).toInt()

        val fx = smoothstep(x - xi)
        val fy = smoothstep(y - yi)
        val fz = smoothstep(z - zi)

        fun corner(dx: Int, dy: Int, dz: Int) = hash(xi + dx, yi + dy, zi + dz)

        val x00 = lerp(corner(0, 0, 0), corner(1, 0, 0), fx)
        val x10 = lerp(corner(0, 1, 0), corner(1, 1, 0), fx)
        val x01 = lerp(corner(0, 0, 1), corner(1, 0, 1), fx)
        val x11 = lerp(corner(0, 1, 1), corner(1, 1, 1), fx)

        return lerp(lerp(x00, x10, fy), lerp(x01, x11, fy), fz)
    }

    /**
     * Integer hash to a value in 0..1.
     *
     * Integer arithmetic throughout and wrapping on purpose, so the result
     * depends on nothing but the inputs - no platform float behaviour, no
     * library version, no order of evaluation.
     */
    private fun hash(x: Int, y: Int, z: Int): Double {
        var h = seed
        h = h * 374761393 + x * 668265263
        h = h * 1274126177 + y * 2246822519.toInt()
        h = h * 2654435761.toInt() + z * 3266489917.toInt()
        h = h xor (h ushr 15)
        h *= 2246822519.toInt()
        h = h xor (h ushr 13)
        h *= 3266489917.toInt()
        h = h xor (h ushr 16)
        return (h ushr 8) / UNSIGNED_24_BIT
    }

    private fun smoothstep(t: Double) = t * t * (3.0 - 2.0 * t)

    private fun lerp(a: Double, b: Double, t: Double) = a + (b - a) * t

    companion object {
        const val DEFAULT_SEED = 0x4A06EE

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

        /** Blended back into the real terrain by here, metres. */
        private const val PAD_BLEND_METRES = 1_200.0

        /**
         * Hill band. Sized in metres and added after the sharpening curve, so
         * lowlands get the same relief as highlands.
         */
        private const val HILL_FREQUENCY = 250.0
        private const val HILL_OCTAVES = 4
        private const val HILL_AMPLITUDE = 150.0

        /** Land below this height gets proportionally less hill. */
        private const val HILL_SHORE_FADE = 400.0

        private const val UNSIGNED_24_BIT = ((1 shl 24) - 1).toDouble()
    }
}
