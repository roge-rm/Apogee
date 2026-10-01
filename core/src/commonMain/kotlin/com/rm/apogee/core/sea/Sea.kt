package com.rm.apogee.core.sea

import com.rm.apogee.core.math.Vec3
import com.rm.apogee.core.orbit.CelestialBody
import com.rm.apogee.core.terrain.Noise
import com.rm.apogee.core.weather.Storms
import com.rm.apogee.core.weather.Weather
import kotlin.math.sqrt
import com.rm.apogee.core.math.Math
import kotlin.concurrent.Volatile
import com.rm.apogee.core.math.StrictMath

/** The sea at one place and time, as [Sea.sample] leaves it. */
class SeaSample {
    /** The surface height above the datum, in metres, counting tide and waves. */
    var height = 0.0
    /** The tide alone, in metres: the surface with the waves averaged out. */
    var tide = 0.0
    /** The surface's upward normal, body-fixed, unit length. */
    val normal = Vec3()
    /** The water's velocity at the depth asked, body-fixed, in m/s, from waves and wind drift. */
    val velocity = Vec3()
    /** The surface's slope. 0 is flat. */
    var steepness = 0.0
    /** How much the sea is breaking here, 0..1: whitecaps, surf and storm crests. */
    var breaking = 0.0
    /** How deep the water is in metres, surface to bed. 0 or less on land. */
    var depth = 0.0
    /** How fast the surface is rising here, in m/s. */
    var rise = 0.0
    /** The significant wave height here, in metres. */
    var significantHeight = 0.0
    /** The wind over the water in m/s, and a storm's own sea in metres. */
    var wind = 0.0
    var stormHeight = 0.0
}

/**
 * The waves over one craft, worked out once at its middle. Phase is linear in position, so any
 * point under the craft is a sum of cosines with nothing else to redo per hull cell. Matches the
 * surface to a fraction of a millimetre.
 */
class WavePatch {
    internal var n = 0
    internal val a = DoubleArray(Sea.COMPONENTS)
    internal val k = DoubleArray(Sea.COMPONENTS)
    internal val omega = DoubleArray(Sea.COMPONENTS)
    internal val dx = DoubleArray(Sea.COMPONENTS)
    internal val dy = DoubleArray(Sea.COMPONENTS)
    internal val dz = DoubleArray(Sea.COMPONENTS)
    internal val phase = DoubleArray(Sea.COMPONENTS)
    internal var radius = 0.0
    internal val centre = Vec3()
    private val sc = DetMath.SinCos()

    /**
     * The most the surface can be above or below the tide anywhere in the patch, in metres, with
     * every train at its crest. A point further from the tide than this is wet or dry without
     * summing the waves.
     */
    var reach = 0.0
        internal set

    /** The sea at the middle, as [Sea.sample] gave it: tide, breaking, heights and depth. */
    val middle = SeaSample()

    /** Whether there's any sea under the craft at all. */
    val afloat: Boolean get() = middle.depth > 0.0

    /** The time its phases are for. */
    var time = Double.NaN
        internal set

    /**
     * Moves each train's phase on to [to] by its frequency. Sizes, tide and the middle sample stay
     * as built, which is fine for the hundredths of a second between builds.
     */
    fun advanceTo(to: Double) {
        val dt = to - time
        if (dt == 0.0 || time.isNaN()) return
        for (i in 0 until n) phase[i] -= omega[i] * dt
        time = to
    }

    /** The height of the surface above the datum at body-fixed [p] (any length), in metres. */
    fun height(p: Vec3): Double {
        if (!afloat) return middle.tide
        val l = p.length
        val qx = p.x / l * radius - centre.x; val qy = p.y / l * radius - centre.y; val qz = p.z / l * radius - centre.z
        var h = middle.tide
        for (i in 0 until n) {
            val ph = phase[i] + k[i] * (dx[i] * qx + dy[i] * qy + dz[i] * qz)
            DetMath.sinCos(ph, sc)
            val c = sc.cos
            h += a[i] * c + 0.5 * k[i] * a[i] * a[i] * (2.0 * c * c - 1.0)
        }
        return h
    }

    /** The surface's upward normal at body-fixed [p], into [out]. */
    fun normal(p: Vec3, out: Vec3): Vec3 {
        val l = p.length
        val ux = p.x / l; val uy = p.y / l; val uz = p.z / l
        out.setTo(ux, uy, uz)
        if (!afloat) return out
        val qx = ux * radius - centre.x; val qy = uy * radius - centre.y; val qz = uz * radius - centre.z
        var gx = 0.0; var gy = 0.0; var gz = 0.0
        for (i in 0 until n) {
            val ph = phase[i] + k[i] * (dx[i] * qx + dy[i] * qy + dz[i] * qz)
            DetMath.sinCos(ph, sc)
            val slope = -a[i] * k[i] * sc.sin - k[i] * k[i] * a[i] * a[i] * 2.0 * sc.sin * sc.cos
            val dot = dx[i] * ux + dy[i] * uy + dz[i] * uz
            gx += (dx[i] - dot * ux) * slope; gy += (dy[i] - dot * uy) * slope; gz += (dz[i] - dot * uz) * slope
        }
        return out.subInPlace(Vec3(gx, gy, gz)).normalizeInPlace()
    }

    /**
     * The water's motion at body-fixed [p], [below] metres under the surface, into [out]
     * (body-fixed, m/s).
     */
    fun velocity(p: Vec3, below: Double, out: Vec3): Vec3 {
        out.setZero()
        if (!afloat) return out
        val l = p.length
        val ux = p.x / l; val uy = p.y / l; val uz = p.z / l
        val qx = ux * radius - centre.x; val qy = uy * radius - centre.y; val qz = uz * radius - centre.z
        for (i in 0 until n) {
            val ph = phase[i] + k[i] * (dx[i] * qx + dy[i] * qy + dz[i] * qz)
            DetMath.sinCos(ph, sc)
            val decay = if (below > 0.0) DetMath.exp(-k[i] * below) else 1.0
            val speed = a[i] * omega[i] * decay
            val dot = dx[i] * ux + dy[i] * uy + dz[i] * uz
            val tl = kotlin.math.sqrt((1.0 - dot * dot).coerceAtLeast(1e-9))
            out.x += (dx[i] - dot * ux) / tl * speed * sc.cos + ux * speed * sc.sin
            out.y += (dy[i] - dot * uy) / tl * speed * sc.cos + uy * speed * sc.sin
            out.z += (dz[i] - dot * uz) / tl * speed * sc.cos + uz * speed * sc.sin
        }
        return out
    }
}

/**
 * The sea of one body: tide, plus waves raised by its weather. The one definition of the water
 * surface: buoyancy and the drawn sea both sample it. A pure function of place, time and the
 * weather's config and seed, so server and clients agree.
 *
 * Wave size comes from a sea state on [Lattice] corners a few kilometres apart, fixed per
 * [STATE_EPOCH]: wind sea grown over the upwind fetch, swell from distant storms arriving hours
 * later, the storm sea under a storm, and a ripple everywhere.
 *
 * The waves are [COMPONENTS] fixed trains, each with a wavelength and a 3D direction, so phase is
 * seamless on the sphere. Heights come from the spectrum. Crests are second order. Waves steepen
 * over shallows, break when too high for the depth, and stop at land. Rarely, a storm makes a rogue
 * wave twice the sea around it.
 */
class Sea(
    val body: CelestialBody,
    moon: CelestialBody?,
    private val weather: Weather?,
    seed: Int,
) {
    internal val tides = Tides(body, moon)
    private val terrain = body.terrain
    private val radius = body.radius
    private val g = body.gravitationalParameter / (radius * radius)
    private val seed = seed * 131 + 0x5EA

    // The wave trains.
    private val k = DoubleArray(COMPONENTS)
    private val omega = DoubleArray(COMPONENTS)
    private val dx = DoubleArray(COMPONENTS)
    private val dy = DoubleArray(COMPONENTS)
    private val dz = DoubleArray(COMPONENTS)
    private val phase0 = DoubleArray(COMPONENTS)

    init {
        for (i in 0 until COMPONENTS) {
            val band = i / DIRECTIONS
            val jitter = Noise.hash(this.seed, i, 1, 0) - 0.5
            val f = ((band + 0.5 + 0.8 * jitter) / BANDS).coerceIn(0.0, 1.0)
            val wavelength = SHORTEST * StrictMath.pow(LONGEST / SHORTEST, f)
            k[i] = 2.0 * Math.PI / wavelength
            omega[i] = sqrt(g * k[i])
            // A direction anywhere on the sphere, spread evenly.
            val z = 2.0 * Noise.hash(this.seed, i, 2, 0) - 1.0
            val a = 2.0 * Math.PI * Noise.hash(this.seed, i, 3, 0)
            val r = sqrt((1.0 - z * z).coerceAtLeast(0.0))
            dx[i] = r * StrictMath.cos(a); dy[i] = r * StrictMath.sin(a); dz[i] = z
            phase0[i] = 2.0 * Math.PI * Noise.hash(this.seed, i, 4, 0)
        }
    }

    /**
     * How big the world's weather makes its seas. See
     * [com.rm.apogee.core.weather.WeatherIntensity.sea].
     */
    private val seaScale = weather?.config?.intensity?.sea ?: 1.0

    /** What makes this sea what it is, so it can share corners with identical ones. */
    private val identity = "${body.id}:$seed:${weather?.config?.hashCode() ?: 0}"

    private val depth = Lattice(radius, DEPTH_SPACING, 0.0, 1, DEPTH_CAPACITY, "bed:${body.id}:${terrain?.let { it::class.simpleName }}") { d, _, out ->
        out[0] = terrain?.elevation(d) ?: -DEFAULT_DEPTH
    }

    /** The bed roughly, for the far surface, so the fine lattice doesn't fill with distant corners. */
    private val coarseDepth = Lattice(radius, COARSE_DEPTH_SPACING, 0.0, 1, DEPTH_CAPACITY / 4, "coarse-bed:${body.id}:${terrain?.let { it::class.simpleName }}") { d, _, out ->
        out[0] = terrain?.elevation(d) ?: -DEFAULT_DEPTH
    }

    private val state = Lattice(radius, STATE_SPACING, STATE_EPOCH, STATE_SIZE, STATE_CAPACITY, "state:$identity") { d, t, out -> seaState(d, t, out) }

    /**
     * How open the sea is to waves from each direction, fine near a craft and rough far away. See
     * [shelterAt]. Fixed, like the bed.
     */
    private val shelter = Lattice(radius, SHELTER_SPACING, 0.0, SHELTER_SIZE, DEPTH_CAPACITY / 4, "shelter:${body.id}:${terrain?.let { it::class.simpleName }}") { d, _, out ->
        shelterAt(d, out, fine = true)
    }
    private val coarseShelter = Lattice(radius, COARSE_SHELTER_SPACING, 0.0, SHELTER_SIZE, DEPTH_CAPACITY / 4, "coarse-shelter:${body.id}:${terrain?.let { it::class.simpleName }}") { d, _, out ->
        shelterAt(d, out, fine = false)
    }

    /**
     * The currents, on a world with ground and a sea. Full strength on Terra, much weaker elsewhere
     * (Aurantia's methane). See [Currents].
     */
    private val currents: Currents? = if (terrain == null) null else Currents(
        body,
        if (body.id == "terra") 1.0 else OTHER_CURRENTS,
        this.seed,
        { d, out ->
            coarseShelter.sample(d, 0.0, shelterSample)
            coarseDepth.sample(d, 0.0, bedSample)
            out[0] = shelterSample[0]
            out[1] = -bedSample[0]
        },
        { d ->
            coarseDepth.sample(d, 0.0, roughBed)
            -roughBed[0]
        },
        calmPlaces(body),
        { calmBases },
    )
    private val roughBed = DoubleArray(1)

    /**
     * Founded bases on this sea, as body-fixed unit directions and how far round each the water is
     * kept calm, in metres. Kept up to date by the world.
     */
    @Volatile var calmBases: List<Pair<Vec3, Double>> = emptyList()
    private val shelterSample = DoubleArray(SHELTER_SIZE)
    private val bedSample = DoubleArray(1)
    private val currentPoint = Vec3()

    /**
     * The current at body-fixed [position] (metres from the centre), [below] metres under the
     * surface, in m/s along the ground, body-fixed, into [out]. Zero without currents or in calm
     * water.
     */
    fun current(position: Vec3, below: Double, out: Vec3): Vec3 {
        val c = currents ?: return out.setZero()
        return c.velocity(currentPoint.setTo(position).normalizeInPlace(), below, out)
    }

    /**
     * The surface current at body-fixed [position], roughly, for a whole-world map. See
     * [Currents.velocity]. Much quicker, and the same in open sea.
     */
    fun roughCurrent(position: Vec3, out: Vec3): Vec3 {
        val c = currents ?: return out.setZero()
        return c.velocity(currentPoint.setTo(position).normalizeInPlace(), 0.0, out, rough = true)
    }

    private val rayPoint = Vec3()
    private val rayEast = Vec3()
    private val rayNorth = Vec3()
    private val rayBed = DoubleArray(1)
    private val open = DoubleArray(SHELTER_RAYS)

    /**
     * How open the water at unit [u] is to waves from each direction, as compass-rose harmonics.
     * Open means [SHELTER_REACH] of sea upwave; closer land cuts waves from that way in proportion.
     * Into [out]: the mean, then cos and sin of the first two harmonics over [u]'s east and north.
     */
    private fun shelterAt(u: Vec3, out: DoubleArray, fine: Boolean) {
        localFrame(u, rayEast, rayNorth)
        for (j in 0 until SHELTER_RAYS) {
            val a = 2.0 * Math.PI * j / SHELTER_RAYS
            val ce = StrictMath.cos(a); val cn = StrictMath.sin(a)
            var reach = SHELTER_REACH
            var r = if (fine) SHELTER_FIRST else SHELTER_FIRST * 8.0
            var last = 0.0
            while (r <= SHELTER_REACH) {
                rayPoint.setTo(u).mulInPlace(radius).addScaledInPlace(rayEast, ce * r).addScaledInPlace(rayNorth, cn * r).normalizeInPlace()
                (if (fine && r < SHELTER_FINE_REACH) depth else coarseDepth).sample(rayPoint, 0.0, rayBed)
                if (rayBed[0] > 0.0) { reach = 0.5 * (last + r); break }
                last = r
                r *= SHELTER_STEP
            }
            open[j] = reach / SHELTER_REACH
        }
        for (n in 0 until SHELTER_SIZE) out[n] = 0.0
        for (j in 0 until SHELTER_RAYS) {
            val a = 2.0 * Math.PI * j / SHELTER_RAYS
            val o = open[j]
            out[0] += o
            out[1] += o * StrictMath.cos(a); out[2] += o * StrictMath.sin(a)
            out[3] += o * StrictMath.cos(2.0 * a); out[4] += o * StrictMath.sin(2.0 * a)
        }
        out[0] /= SHELTER_RAYS
        for (n in 1 until SHELTER_SIZE) out[n] *= 2.0 / SHELTER_RAYS
    }

    /** East and north at unit [u], from the planet's spin. */
    private fun localFrame(u: Vec3, east: Vec3, north: Vec3) {
        east.setTo(u.z, 0.0, -u.x)
        if (east.lengthSq < 1e-12) east.setTo(1.0, 0.0, 0.0)
        east.normalizeInPlace()
        north.setTo(u).crossInPlace(east)
    }

    /**
     * Works out ahead what the sea around unit [direction] will need at [time]. For a worker
     * thread.
     */
    fun prefetch(direction: Vec3, time: Double) {
        u.setTo(direction).normalizeInPlace()
        state.prefetch(u, time)
        state.prefetch(u, time + STATE_EPOCH)
    }

    // --- the sea state at a corner --------------------------------------------

    private val wind = Vec3()
    private val probe = Vec3()
    private val stormSea = Storms.StormSea()

    private class System(var hs: Double, var period: Double, val direction: Vec3, var spread: Double, var gamma: Double)
    private val systems = Array(5) { System(0.0, 1.0, Vec3(), 1.0, 1.0) }
    private val power = DoubleArray(COMPONENTS)
    private val weight = DoubleArray(COMPONENTS)

    private fun seaState(u: Vec3, time: Double, out: DoubleArray) {
        // The wind, and the fetch it has blown across.
        if (weather != null) weather.boundaryWind(u, time, wind) else wind.setZero()
        wind.addScaledInPlace(u, -(wind dot u))
        val speed = wind.length
        var hsWind = 0.0
        if (speed > 0.5) {
            val from = Vec3().setTo(wind).mulInPlace(-1.0 / speed)
            var fetch = 0.0
            var sum = speed
            var n = 1
            for (step in 1..FETCH_STEPS) {
                probe.setTo(u).mulInPlace(radius).addScaledInPlace(from, step * FETCH_STEP).normalizeInPlace()
                if ((terrain?.elevation(probe) ?: -1.0) > 0.0) break
                fetch += FETCH_STEP
                if (weather != null) {
                    val there = weather.boundaryWind(probe, time - step * FETCH_STEP / FETCH_SPEED, Vec3())
                    sum += there.length; n++
                }
            }
            val u10 = sum / n
            val limited = 0.0016 * u10 * sqrt(kotlin.math.max(fetch, FETCH_STEP * 0.25) / g)
            val developed = 0.21 * u10 * u10 / g
            hsWind = kotlin.math.min(limited, developed) * seaScale
        }
        // What storms do to it.
        if (weather != null) {
            weather.stormModel.seaAt(u, time, stormSea) { p -> (terrain?.elevation(p) ?: -1.0) > 0.0 }
        } else {
            stormSea.stormHs = 0.0; stormSea.swellHs = 0.0
        }

        val windDirection = if (speed > 0.5) Vec3().setTo(wind).mulInPlace(1.0 / speed) else tangentOf(u)
        // A world with no weather has a still sea: tides and nothing else.
        set(systems[0], if (weather != null) FLOOR_HS else 0.0, FLOOR_PERIOD, windDirection, 1.0, 1.0)
        set(systems[1], hsWind, periodOf(hsWind), windDirection, 1.0, 3.3)
        set(systems[2], stormSea.swellHs * seaScale, kotlin.math.max(stormSea.swellPeriod, 8.0), stormSea.swellDirection, 6.0, 5.0)
        // A storm sea is young and steep: shorter period than a sea of the same height grown over
        // days.
        set(systems[3], stormSea.stormHs, 1.5 + 3.2 * sqrt(kotlin.math.max(stormSea.stormHs, 0.05)), stormSea.stormDirection, 0.5, 3.3)
        // The ocean's own swell from far-off weather, always running in toward the shore, so a
        // coast with land upwind still has waves. Shelter stops most of it in the bay and harbour.
        if (weather != null) {
            val k = radius / OCEAN_SWELL_SCALE
            val swell = OCEAN_SWELL_HS * seaScale * (1.0 + OCEAN_SWELL_VARY * Noise.simplex(seed + 7, u.x * k + time / OCEAN_SWELL_TIME, u.y * k, u.z * k))
            set(systems[4], swell, OCEAN_SWELL_PERIOD, shoreward(u, time), 6.0, 3.0)
        } else {
            set(systems[4], 0.0, OCEAN_SWELL_PERIOD, windDirection, 6.0, 3.0)
        }

        for (i in 0 until COMPONENTS) power[i] = 0.0
        var total = 0.0
        for (s in systems) {
            if (s.hs <= 0.0 || s.direction.lengthSq < 0.5) continue
            total += s.hs * s.hs
            val wp = 2.0 * Math.PI / s.period
            var sum = 0.0
            for (i in 0 until COMPONENTS) {
                val dot = dx[i] * u.x + dy[i] * u.y + dz[i] * u.z
                val tl = sqrt((1.0 - dot * dot).coerceAtLeast(0.0))
                if (tl < MIN_TANGENT) { weight[i] = 0.0; continue }
                val tx = (dx[i] - dot * u.x) / tl; val ty = (dy[i] - dot * u.y) / tl; val tz = (dz[i] - dot * u.z) / tl
                val c = tx * s.direction.x + ty * s.direction.y + tz * s.direction.z
                // Mostly with the wind. A confused storm sea goes every which way.
                val spread = if (c > 0.0) StrictMath.pow(c, 2.0 * s.spread) else 0.0
                val d = if (s.spread < 1.0) 0.25 + 0.75 * spread else spread
                if (d <= 0.0) { weight[i] = 0.0; continue }
                val w = omega[i]
                val ratio = wp / w
                val sigma = if (w <= wp) 0.07 else 0.09
                val peak = StrictMath.exp(-((w - wp) * (w - wp)) / (2.0 * sigma * sigma * wp * wp))
                val spectrum = StrictMath.exp(-1.25 * ratio * ratio * ratio * ratio) / (w * w * w * w) *
                    StrictMath.pow(s.gamma, peak)
                weight[i] = spectrum * d * tl * tl
                sum += weight[i]
            }
            if (sum <= 0.0) continue
            // Share heights so the total is the system's significant height: sum of a²/2 is Hs²/16.
            for (i in 0 until COMPONENTS) power[i] += s.hs * s.hs / 8.0 * weight[i] / sum
        }
        for (i in 0 until COMPONENTS) {
            // No train steeper than a real wave can stand.
            out[i] = kotlin.math.min(sqrt(power[i]), MAX_STEEPNESS / k[i])
        }
        out[COMPONENTS] = sqrt(total)
        out[COMPONENTS + 1] = speed
        out[COMPONENTS + 2] = wind.x; out[COMPONENTS + 3] = wind.y; out[COMPONENTS + 4] = wind.z
        out[COMPONENTS + 5] = stormSea.stormHs
    }

    private fun set(s: System, hs: Double, period: Double, direction: Vec3, spread: Double, gamma: Double) {
        s.hs = hs; s.period = period; s.direction.setTo(direction); s.spread = spread; s.gamma = gamma
    }

    /** The peak period of a sea this high, in seconds. About 6 s for one metre, 15 s for ten. */
    private fun periodOf(hs: Double): Double = 1.5 + 4.2 * sqrt(kotlin.math.max(hs, 0.05))

    private val swellEast = Vec3()
    private val swellNorth = Vec3()

    /**
     * Which way the ocean swell runs at unit [u]: toward the shore, from which way the ground rises,
     * as swell turns to meet a shoaling coast. In open deep ocean it wanders slowly across the world.
     */
    private fun shoreward(u: Vec3, time: Double): Vec3 {
        localFrame(u, swellEast, swellNorth)
        val out = Vec3()
        val t = terrain
        if (t != null) {
            fun at(e: Double, n: Double): Double {
                probe.setTo(u).mulInPlace(radius).addScaledInPlace(swellEast, e).addScaledInPlace(swellNorth, n).normalizeInPlace()
                return t.elevation(probe).coerceAtMost(0.0)
            }
            val ge = at(SHORE_PROBE, 0.0) - at(-SHORE_PROBE, 0.0)
            val gn = at(0.0, SHORE_PROBE) - at(0.0, -SHORE_PROBE)
            if (kotlin.math.hypot(ge, gn) > SHORE_RISE) {
                return out.setTo(swellEast).mulInPlace(ge).addScaledInPlace(swellNorth, gn).normalizeInPlace()
            }
        }
        val angle = Math.PI * 2.0 * Noise.simplex(seed + 8, u.x * 3.0, u.y * 3.0, u.z * 3.0 + time / OCEAN_SWELL_TIME)
        return out.setTo(swellEast).mulInPlace(kotlin.math.cos(angle)).addScaledInPlace(swellNorth, kotlin.math.sin(angle))
    }

    private fun tangentOf(u: Vec3): Vec3 {
        val t = Vec3(0.0, 1.0, 0.0).addScaledInPlace(u, -u.y)
        if (t.lengthSq < 1e-6) t.setTo(1.0, 0.0, 0.0).addScaledInPlace(u, -u.x)
        return t.normalizeInPlace()
    }

    // --- the surface ------------------------------------------------------------

    private val u = Vec3()
    private val stateOut = DoubleArray(STATE_SIZE)
    private val depthOut = DoubleArray(1)
    private val shelterOut = DoubleArray(SHELTER_SIZE)
    private val east = Vec3()
    private val north = Vec3()

    /** The height of the surface above the datum at body-fixed [position] (any length), in metres. */
    fun height(position: Vec3, time: Double): Double = evaluate(position, time, null, 0.0, 0.0)

    /**
     * Everything about the sea at body-fixed [position] (any length) and [time], into [out], with
     * the water's velocity [below] metres down. Trains too short to show at [spacing] metres between
     * samples are skipped; 0 skips none, which is what physics uses.
     */
    fun sample(position: Vec3, time: Double, out: SeaSample, below: Double = 0.0, spacing: Double = 0.0): SeaSample {
        evaluate(position, time, out, below, spacing)
        return out
    }

    /**
     * The surface alone at [position], for drawing: height, rise, steepness, breaking and depth, but
     * no water motion. Trains shorter than twice [spacing] are skipped, and the bed is read roughly
     * when samples are far apart.
     */
    fun surface(position: Vec3, time: Double, out: SeaSample, spacing: Double): SeaSample {
        motion = false
        try {
            evaluate(position, time, out, 0.0, spacing)
        } finally {
            motion = true
        }
        return out
    }

    private var motion = true

    /** The waves over a craft at body-fixed [position], at [time], into [out]. See [WavePatch]. */
    fun patch(position: Vec3, time: Double, out: WavePatch): WavePatch {
        patching = out
        try {
            evaluate(position, time, out.middle, 0.0, 0.0)
        } finally {
            patching = null
        }
        out.time = time
        return out
    }

    private var patching: WavePatch? = null

    private val gradient = Vec3()
    private val velocity = Vec3()
    private val sc = DetMath.SinCos()

    /**
     * The sea at a place apart from the wave phases: tide, depth, and each train's sheltered,
     * shoaled size. These change slowly, so a renderer can prepare now and then and only pay for
     * the waves each time. See [prepare].
     */
    class Prepared {
        internal val amplitude = DoubleArray(COMPONENTS)
        internal var tide = 0.0
        internal var water = 0.0
        internal var dry = true
        internal var shoal = false
        internal var power = 0.0
        internal var hs = 0.0
        internal var biggest = 0.0
        internal var rogueSea = false
        internal var wind = 0.0
        internal val windVector = Vec3()
        internal var stormHeight = 0.0
    }

    private val evaluated = Prepared()

    /**
     * [Prepared] for body-fixed [position] (any length) at [time], sampled [spacing] m apart, into
     * [into].
     */
    fun prepare(position: Vec3, time: Double, spacing: Double, into: Prepared): Prepared {
        u.setTo(position).normalizeInPlace()
        prepareAt(time, spacing, into)
        return into
    }

    /** Same as [surface], from a [Prepared] made nearby a little while ago. */
    fun surface(position: Vec3, time: Double, prepared: Prepared, out: SeaSample, spacing: Double): SeaSample {
        motion = false
        try {
            u.setTo(position).normalizeInPlace()
            waves(prepared, time, out, 0.0, spacing)
        } finally {
            motion = true
        }
        return out
    }

    private fun evaluate(position: Vec3, time: Double, out: SeaSample?, below: Double, spacing: Double): Double {
        u.setTo(position).normalizeInPlace()
        prepareAt(time, spacing, evaluated)
        return waves(evaluated, time, out, below, spacing)
    }

    /** [Prepared] at [u]. */
    private fun prepareAt(time: Double, spacing: Double, p: Prepared) {
        (if (spacing > COARSE_BED_SPACING) coarseDepth else depth).sample(u, 0.0, depthOut)
        val bed = depthOut[0]
        val tide = tides.height(u, time, bed)
        val water = tide - bed
        p.tide = tide; p.water = water
        p.dry = water <= 0.0
        if (p.dry) return
        state.sample(u, time, stateOut)
        var hs = stateOut[COMPONENTS]
        p.rogueSea = stateOut[COMPONENTS + 5] > ROGUE_SEA

        // Shelter: cut each train by how soon there's land upwave.
        if (terrain != null) {
            (if (spacing > COARSE_BED_SPACING) coarseShelter else shelter).sample(u, 0.0, shelterOut)
            if (shelterOut[0] < SHELTER_OPEN) {
                localFrame(u, east, north)
                var before = 0.0; var after = 0.0
                for (i in 0 until COMPONENTS) {
                    val a = stateOut[i]
                    if (a <= 0.0) continue
                    // Where this train comes from: against its run over the surface.
                    val dot = dx[i] * u.x + dy[i] * u.y + dz[i] * u.z
                    val tx = dx[i] - dot * u.x; val ty = dy[i] - dot * u.y; val tz = dz[i] - dot * u.z
                    val tl = sqrt((tx * tx + ty * ty + tz * tz).coerceAtLeast(1e-12))
                    val fe = -(tx * east.x + ty * east.y + tz * east.z) / tl
                    val fn = -(tx * north.x + ty * north.y + tz * north.z) / tl
                    var e = shelterOut[0] + shelterOut[1] * fe + shelterOut[2] * fn +
                        shelterOut[3] * (fe * fe - fn * fn) + shelterOut[4] * (2.0 * fe * fn)
                    e = e.coerceIn(0.0, 1.0)
                    // The shortest ripples, which wind raises fresh over any water.
                    if (k[i] > SHELTER_REGROW_K) e = kotlin.math.max(e, SHELTER_REGROW)
                    // Long swell bends round a headland into a bay, so a coast in the lee still gets
                    // some. Only as much as the water here is open all round, so it stays out of an
                    // enclosed harbour.
                    if (k[i] < SHELTER_WRAP_K) e = kotlin.math.max(e, SHELTER_WRAP * shelterOut[0].coerceIn(0.0, 1.0))
                    before += a * a
                    stateOut[i] = a * e
                    after += stateOut[i] * stateOut[i]
                }
                val sheltered = if (before > 0.0) sqrt(after / before) else 1.0
                hs *= sheltered
                stateOut[COMPONENTS + 5] *= sheltered
            }
        }

        // Shallows: waves grow as they slow down.
        val shoal = water < SHOAL_DEPTH
        var power = 0.0
        var biggest = 0.0
        for (i in 0 until COMPONENTS) {
            var a = stateOut[i]
            if (shoal) {
                val kd = k[i] * water
                if (kd < Math.PI) {
                    val e = DetMath.exp(-2.0 * kd)
                    val t = (1.0 - e) / (1.0 + e)
                    val sinh2 = (1.0 / e - e) * 0.5
                    val cg = t * (1.0 + 2.0 * kd / kotlin.math.max(sinh2, 1e-9))
                    a *= kotlin.math.min(1.0 / sqrt(kotlin.math.max(cg, 1e-6)), MAX_SHOALING)
                }
            }
            p.amplitude[i] = a
            power += a * a
            if (a > biggest) biggest = a
        }
        p.shoal = shoal
        p.power = power
        p.biggest = biggest
        p.hs = hs
        p.wind = stateOut[COMPONENTS + 1]
        p.windVector.setTo(stateOut[COMPONENTS + 2], stateOut[COMPONENTS + 3], stateOut[COMPONENTS + 4])
        p.stormHeight = stateOut[COMPONENTS + 5]
    }

    /** The waves at [u] and [time], from [p]. See [evaluate]. */
    private fun waves(p: Prepared, time: Double, out: SeaSample?, below: Double, spacing: Double): Double {
        val tide = p.tide
        if (out != null) {
            out.tide = tide; out.depth = p.water
            out.normal.setTo(u); out.velocity.setZero()
            out.steepness = 0.0; out.breaking = 0.0; out.significantHeight = 0.0; out.rise = 0.0
            out.wind = 0.0; out.stormHeight = 0.0
        }
        if (p.dry) {
            out?.height = tide
            patching?.n = 0
            return tide
        }
        val rogue = if (p.rogueSea) rogue(u, time) else 1.0

        // Waves can't stand higher than the water is deep.
        var scale = rogue
        var breaking = 0.0
        val hs: Double
        if (p.shoal) {
            val local = 4.0 * sqrt(p.power / 2.0) * rogue
            val limit = BREAKING * p.water
            if (local > limit) {
                scale *= limit / local
                breaking = kotlin.math.min(1.0, (local - limit) / limit + 0.3)
            }
            hs = kotlin.math.min(local, limit)
        } else {
            hs = p.hs * rogue
        }

        val amplitude = p.amplitude
        val px = u.x * radius; val py = u.y * radius; val pz = u.z * radius
        // Trains too small next to the biggest to matter are left out.
        val least = kotlin.math.max(MIN_AMPLITUDE, RELATIVE_AMPLITUDE * p.biggest * scale)
        var height = tide
        val patch = patching
        if (patch != null) {
            patch.n = 0; patch.radius = radius; patch.reach = 0.0
            patch.centre.setTo(u).mulInPlace(radius)
        }
        gradient.setZero(); velocity.setZero()
        val wantSlope = out != null
        val wantMotion = out != null && motion
        var steep = 0.0
        var rise = 0.0
        for (i in 0 until COMPONENTS) {
            val a = amplitude[i] * scale
            if (a < least) continue
            val ki = k[i]
            if (spacing > 0.0 && ki * spacing > Math.PI) continue
            val phase = ki * (px * dx[i] + py * dy[i] + pz * dz[i]) - omega[i] * time + phase0[i]
            if (patch != null) {
                val m = patch.n++
                patch.a[m] = a; patch.k[m] = ki; patch.omega[m] = omega[i]
                patch.dx[m] = dx[i]; patch.dy[m] = dy[i]; patch.dz[m] = dz[i]
                patch.phase[m] = phase
                patch.reach += a + 0.5 * ki * a * a
            }
            DetMath.sinCos(phase, sc)
            val s = sc.sin
            val c = sc.cos
            val c2 = 2.0 * c * c - 1.0
            height += a * c + 0.5 * ki * a * a * c2
            if (!wantSlope) continue
            // How fast it's rising, for drawing it a moment ahead.
            rise += omega[i] * (a * s + ki * a * a * 2.0 * s * c)
            // The slope, along the train's direction on the surface.
            val dot = dx[i] * u.x + dy[i] * u.y + dz[i] * u.z
            val tx = dx[i] - dot * u.x; val ty = dy[i] - dot * u.y; val tz = dz[i] - dot * u.z
            val slope = -a * ki * s - ki * ki * a * a * 2.0 * s * c
            gradient.x += tx * slope; gradient.y += ty * slope; gradient.z += tz * slope
            steep += a * ki
            if (!wantMotion) continue
            // The water's own motion, dying away with depth.
            val decay = if (below > 0.0) DetMath.exp(-ki * below) else 1.0
            val speed = a * omega[i] * decay
            val tl = sqrt((1.0 - dot * dot).coerceAtLeast(1e-9))
            velocity.x += (tx / tl) * speed * c + u.x * speed * s
            velocity.y += (ty / tl) * speed * c + u.y * speed * s
            velocity.z += (tz / tl) * speed * c + u.z * speed * s
        }
        if (out != null) {
            out.height = height
            out.normal.setTo(u).subInPlace(gradient).normalizeInPlace()
            // A little drift with the wind at the top.
            val drift = if (below < 1.0) WIND_DRIFT * (1.0 - below) else 0.0
            out.velocity.setTo(velocity).addScaledInPlace(p.windVector, drift)
            out.steepness = gradient.length
            out.rise = rise
            out.significantHeight = hs
            out.wind = p.wind
            out.stormHeight = p.stormHeight
            // Whitecaps where the sea is steep and the wind strong, and surf where it breaks.
            val whitecap = smooth(0.18, 0.4, out.steepness) * smooth(5.0, 14.0, out.wind + 1.5 * out.stormHeight)
            // In a storm sea the high crests break, tumbling onto anything small underneath.
            val crest = if (hs > 0.1) (height - tide) / hs else 0.0
            val stormBreak = smooth(0.35, 0.8, crest) * smooth(2.0, 7.0, out.stormHeight)
            out.breaking = kotlin.math.max(breaking, kotlin.math.max(whitecap, stormBreak))
            if (steep > 0.0 && out.stormHeight > ROGUE_SEA && rogue > 1.3) out.breaking = kotlin.math.max(out.breaking, 0.6)
        }
        return height
    }

    /**
     * A rogue wave's effect here, as a multiplier on the sea. Rare, at most one per patch, each a
     * couple of hundred metres across and about a minute long, doubling the waves at its centre.
     * Storm seas only.
     */
    private fun rogue(u: Vec3, time: Double): Double {
        val cx = Math.floor(u.x * radius / ROGUE_CELL).toInt()
        val cy = Math.floor(u.y * radius / ROGUE_CELL).toInt()
        val cz = Math.floor(u.z * radius / ROGUE_CELL).toInt()
        val slot = Math.floor(time / ROGUE_SLOT).toInt()
        val h = Noise.hash(seed + 7, cx * 73_856_093 xor slot, cy, cz)
        if (h > ROGUE_CHANCE) return 1.0
        // Where in the patch and when in the slot, kept clear of the edges.
        val ox = (cx + 0.3 + 0.4 * Noise.hash(seed + 8, cx, cy, cz xor slot)) * ROGUE_CELL
        val oy = (cy + 0.3 + 0.4 * Noise.hash(seed + 9, cx, cy, cz xor slot)) * ROGUE_CELL
        val oz = (cz + 0.3 + 0.4 * Noise.hash(seed + 10, cx, cy, cz xor slot)) * ROGUE_CELL
        val ex = u.x * radius - ox; val ey = u.y * radius - oy; val ez = u.z * radius - oz
        val d2 = ex * ex + ey * ey + ez * ez
        val mid = (slot + 0.5) * ROGUE_SLOT
        val dt = (time - mid) / (ROGUE_SLOT * 0.25)
        return 1.0 + ROGUE_GAIN * DetMath.exp(-d2 / (ROGUE_RADIUS * ROGUE_RADIUS) - dt * dt)
    }

    private fun smooth(a: Double, b: Double, x: Double): Double {
        val t = ((x - a) / (b - a)).coerceIn(0.0, 1.0)
        return t * t * (3.0 - 2.0 * t)
    }

    private fun calmPlaces(body: CelestialBody): List<Pair<Vec3, Double>> {
        val places = ArrayList<Pair<Vec3, Double>>()
        // Every launch site at sea: the harbour berth and the test sites over the deep.
        for (site in com.rm.apogee.core.world.World.launchSites) {
            if (site.bodyId != body.id) continue
            val d = com.rm.apogee.core.orbit.SolarSystem.surfaceDirection(site.latitude, site.longitude)
            if ((terrain?.elevation(d) ?: 0.0) < 0.0) places.add(d to CALM_SITE)
        }
        // And every named place under the sea.
        for (wonder in com.rm.apogee.core.world.SeaWonders.all) {
            if (wonder.bodyId == body.id) places.add(wonder.direction.copy() to CALM_WONDER)
        }
        return places
    }

    companion object {
        /** How strong another world's currents are, as a share of Terra's. */
        const val OTHER_CURRENTS = 0.3

        /** How far round a sea launch site, and a named place, the water's kept calm, in metres. */
        const val CALM_SITE = 2_500.0
        const val CALM_WONDER = 1_200.0

        /** How far round a founded base the water's kept calm, in metres. */
        const val CALM_BASE = 1_500.0

        /**
         * Wave trains: [BANDS] wavelengths from [SHORTEST] to [LONGEST] m, with [DIRECTIONS] of
         * each.
         */
        const val BANDS = 16
        const val DIRECTIONS = 4
        const val COMPONENTS = BANDS * DIRECTIONS
        const val SHORTEST = 3.0
        const val LONGEST = 500.0

        /**
         * Shelter: corners [SHELTER_SPACING] m apart near a craft and [COARSE_SHELTER_SPACING] far
         * away. It looks [SHELTER_RAYS] ways round, from [SHELTER_FIRST] m out, each step
         * [SHELTER_STEP] times further, to [SHELTER_REACH]. Fine bed out to [SHELTER_FINE_REACH],
         * coarse beyond. Mean openness over [SHELTER_OPEN] counts as open sea.
         */
        private const val SHELTER_SPACING = 400.0
        private const val COARSE_SHELTER_SPACING = 2_000.0
        private const val SHELTER_SIZE = 5
        private const val SHELTER_RAYS = 16
        private const val SHELTER_FIRST = 100.0
        private const val SHELTER_STEP = 1.414
        private const val SHELTER_REACH = 16_000.0
        private const val SHELTER_FINE_REACH = 3_000.0
        private const val SHELTER_OPEN = 0.995

        /** Trains shorter than eight metres keep at least this much of themselves in any shelter. */
        private const val SHELTER_REGROW_K = 2.0 * Math.PI / 8.0
        private const val SHELTER_REGROW = 0.5

        /** Trains longer than a hundred metres (swell) keep at least this much in any shelter. */
        private const val SHELTER_WRAP_K = 2.0 * Math.PI / 100.0
        private const val SHELTER_WRAP = 0.45

        /** Sea state corner spacing in metres, and how long each lasts in seconds. */
        const val STATE_SPACING = 5_000.0
        const val STATE_EPOCH = 30.0
        internal const val STATE_SIZE = COMPONENTS + 6
        private const val STATE_CAPACITY = 20_000

        /** How far apart the sea bed's corners are, in metres. */
        const val DEPTH_SPACING = 200.0

        /** Coarse bed corner spacing in metres, and the sample spacing beyond which it's used. */
        const val COARSE_DEPTH_SPACING = 1_000.0
        const val COARSE_BED_SPACING = 150.0
        private const val DEPTH_CAPACITY = 60_000
        private const val DEFAULT_DEPTH = 1_000.0

        /**
         * The upwind fetch search: steps in metres, and how fast waves carry what they grew, in
         * m/s.
         */
        private const val FETCH_STEPS = 12
        private const val FETCH_STEP = 20_000.0
        private const val FETCH_SPEED = 6.0

        /** The ripple on even the stillest sea: height in metres and period in seconds. */
        private const val FLOOR_HS = 0.15

        /**
         * The ocean swell: significant height (m), how much it varies as a share, over what distance
         * (m) and time (s), and its period. Then how far either way a coast is felt for (m), and the
         * least bed rise across that (m) that counts as one.
         */
        private const val OCEAN_SWELL_HS = 1.0
        private const val OCEAN_SWELL_VARY = 0.35
        private const val OCEAN_SWELL_SCALE = 400_000.0
        private const val OCEAN_SWELL_TIME = 20_000.0
        private const val OCEAN_SWELL_PERIOD = 11.0
        private const val SHORE_PROBE = 12_000.0
        private const val SHORE_RISE = 40.0
        private const val FLOOR_PERIOD = 2.5

        /** Trains running nearly straight up or down here are skipped. */
        private const val MIN_TANGENT = 0.2

        /** The steepest a train can be, as wavenumber times height. */
        private const val MAX_STEEPNESS = 0.3

        private const val MIN_AMPLITUDE = 0.002

        /** Trains smaller than this share of the biggest here are skipped. */
        private const val RELATIVE_AMPLITUDE = 0.04

        /**
         * Waves feel the bottom shallower than this (m), grow at most this much, and break at this
         * share of the depth.
         */
        private const val SHOAL_DEPTH = 250.0
        private const val MAX_SHOALING = 2.0
        const val BREAKING = 0.6

        /** The drift of the surface water, as a share of the wind. */
        private const val WIND_DRIFT = 0.015

        /**
         * Rogue waves: least storm sea height (m), patch size (m), time slot (s), how rare, how big.
         */
        private const val ROGUE_SEA = 5.0
        private const val ROGUE_CELL = 1_500.0
        private const val ROGUE_SLOT = 120.0
        private const val ROGUE_CHANCE = 0.02
        private const val ROGUE_RADIUS = 180.0
        private const val ROGUE_GAIN = 1.2
    }
}
