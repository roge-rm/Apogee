package com.rm.apogee.core.sea

import com.rm.apogee.core.math.Vec3
import com.rm.apogee.core.orbit.CelestialBody
import com.rm.apogee.core.terrain.Noise
import com.rm.apogee.core.weather.Storms
import com.rm.apogee.core.weather.Weather
import kotlin.math.sqrt

/** The sea at one place and time, as [Sea.sample] leaves it. */
class SeaSample {
    /** Surface height above the datum, m: tide and waves. */
    var height = 0.0
    /** The tide alone, m: where the surface sits with the waves averaged out. */
    var tide = 0.0
    /** The surface's upward normal, body-fixed, unit. */
    val normal = Vec3()
    /** The water's velocity at the depth asked about, body-fixed, m/s: wave motion and wind drift. */
    val velocity = Vec3()
    /** How steep the surface is here: its slope, 0 flat. */
    var steepness = 0.0
    /** How much the sea is breaking here, 0..1: whitecaps, surf, storm crests. */
    var breaking = 0.0
    /** How deep the water is, m, surface to bed; 0 or less ashore. */
    var depth = 0.0
    /** How fast the surface is rising here, m/s. */
    var rise = 0.0
    /** Significant wave height here, m. */
    var significantHeight = 0.0
    /** The wind over the water, m/s, and a storm's own sea, m. */
    var wind = 0.0
    var stormHeight = 0.0
}

/**
 * The waves over one craft, worked out once at its middle: which trains are
 * running there, how big, and where each is in its cycle. A wave's phase is
 * straight-line in position, so anywhere under the craft is a sum of cosines
 * of the phase there plus the train's wavenumber times the distance along
 * it - no sea state, bed or tide to work out again for each of the hundreds
 * of cells a hull is weighed in. Across a hull the sea state does not
 * change; what comes out is the surface to within a fraction of a millimetre.
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

    /** The sea at the middle, as [Sea.sample] gave it: tide, breaking, heights, depth. */
    val middle = SeaSample()

    /** Whether there is sea under the craft at all. */
    val afloat: Boolean get() = middle.depth > 0.0

    /** Height of the surface above the datum at body-fixed [p] (any length), m. */
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

    /** The water's motion at body-fixed [p], [below] metres under the surface, into [out] (body-fixed, m/s). */
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
 * The sea of one body: its tide, and its waves, raised by its weather.
 *
 * The one definition of the water's surface. Buoyancy samples it at every
 * cell of every hull; the renderer samples it to build the sea it draws; so
 * a boat floats on exactly the water that is drawn. A pure function of
 * place, time and the weather's config and seed, worked out alike by the
 * server and every client.
 *
 * **Where waves are big** is the sea state ([SeaState]-like corners of a
 * [Lattice], a few kilometres apart, fixed per [STATE_EPOCH]):
 * - wind sea, grown over the fetch upwind - the stretch of open water the
 *   wind has blown across, marched upwind until the land, with the wind
 *   there when the waves passed it - up to the fully developed sea for the
 *   wind's strength: big seas downwind of open ocean, calm in the lee of
 *   land;
 * - swell from storms hundreds of kilometres off, arriving hours after they
 *   raised it;
 * - the storm sea under a storm: ten metres and more under the worst;
 * - and a ripple everywhere, so still water is never a mirror.
 *
 * **The waves themselves** are a fixed set of [COMPONENTS] wave trains, each
 * with its own wavelength and a fixed direction in 3D: its phase is its
 * wavenumber times the distance along that direction, which on a sphere is
 * continuous everywhere, with no seams or tiles. The sea state sets each
 * train's height from the wave spectrum - most in the trains near the peak
 * period running with the wind. Second-order crests: peaked tops, flat
 * troughs. Over shallows they grow and steepen, and break where they are
 * too high for the depth; ashore they are gone. And, rarely, in a storm, a
 * rogue - twice the sea around it for a minute.
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
            // A direction anywhere on the sphere, evenly.
            val z = 2.0 * Noise.hash(this.seed, i, 2, 0) - 1.0
            val a = 2.0 * Math.PI * Noise.hash(this.seed, i, 3, 0)
            val r = sqrt((1.0 - z * z).coerceAtLeast(0.0))
            dx[i] = r * StrictMath.cos(a); dy[i] = r * StrictMath.sin(a); dz[i] = z
            phase0[i] = 2.0 * Math.PI * Noise.hash(this.seed, i, 4, 0)
        }
    }

    /** What makes this sea the sea it is, for sharing corners with others the same. */
    private val identity = "${body.id}:$seed:${weather?.config?.hashCode() ?: 0}"

    private val depth = Lattice(radius, DEPTH_SPACING, 0.0, 1, DEPTH_CAPACITY, "bed:${body.id}:${terrain?.javaClass?.name}") { d, _, out ->
        out[0] = terrain?.elevation(d) ?: -DEFAULT_DEPTH
    }

    /** The sea bed coarsely, for surface far off: the fine lattice would fill with corners nobody looks at closely. */
    private val coarseDepth = Lattice(radius, COARSE_DEPTH_SPACING, 0.0, 1, DEPTH_CAPACITY / 4, "coarse-bed:${body.id}:${terrain?.javaClass?.name}") { d, _, out ->
        out[0] = terrain?.elevation(d) ?: -DEFAULT_DEPTH
    }

    private val state = Lattice(radius, STATE_SPACING, STATE_EPOCH, STATE_SIZE, STATE_CAPACITY, "state:$identity") { d, t, out -> seaState(d, t, out) }

    /**
     * How open the sea is to waves from each way, finely near a craft and
     * coarsely far off: see [shelterAt]. Fixed, like the bed.
     */
    private val shelter = Lattice(radius, SHELTER_SPACING, 0.0, SHELTER_SIZE, DEPTH_CAPACITY / 4, "shelter:${body.id}:${terrain?.javaClass?.name}") { d, _, out ->
        shelterAt(d, out, fine = true)
    }
    private val coarseShelter = Lattice(radius, COARSE_SHELTER_SPACING, 0.0, SHELTER_SIZE, DEPTH_CAPACITY / 4, "coarse-shelter:${body.id}:${terrain?.javaClass?.name}") { d, _, out ->
        shelterAt(d, out, fine = false)
    }

    private val rayPoint = Vec3()
    private val rayEast = Vec3()
    private val rayNorth = Vec3()
    private val rayBed = DoubleArray(1)
    private val open = DoubleArray(SHELTER_RAYS)

    /**
     * How open the water at unit [u] is to waves arriving from each way
     * round, as the first harmonics of a compass rose: open means the sea
     * runs [SHELTER_REACH] or more upwave before it meets land; land closer
     * than that cuts waves from that way in proportion - a bay's far shore a
     * few kilometres off lets in only what its own width of water raises. A
     * bay with a bend in its mouth is calm; a lee shore is calm to the waves
     * blowing off it and open to those rolling in. Into [out]: the mean, and
     * the cos and sin parts of the first two harmonics, over [u]'s own east
     * and north.
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

    /** East and north at unit [u], as the planet's spin has them. */
    private fun localFrame(u: Vec3, east: Vec3, north: Vec3) {
        east.setTo(u.z, 0.0, -u.x)
        if (east.lengthSq < 1e-12) east.setTo(1.0, 0.0, 0.0)
        east.normalizeInPlace()
        north.setTo(u).crossInPlace(east)
    }

    /**
     * Works out ahead of time what the sea round unit [direction] will need
     * at [time]: for a worker to do, so nobody else stops for it.
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
    private val systems = Array(4) { System(0.0, 1.0, Vec3(), 1.0, 1.0) }
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
            hsWind = kotlin.math.min(limited, developed)
        }
        // What storms do to it.
        if (weather != null) {
            weather.stormModel.seaAt(u, time, stormSea) { p -> (terrain?.elevation(p) ?: -1.0) > 0.0 }
        } else {
            stormSea.stormHs = 0.0; stormSea.swellHs = 0.0
        }

        val windDirection = if (speed > 0.5) Vec3().setTo(wind).mulInPlace(1.0 / speed) else tangentOf(u)
        // A world with no weather has a still sea: tides, and nothing else.
        set(systems[0], if (weather != null) FLOOR_HS else 0.0, FLOOR_PERIOD, windDirection, 1.0, 1.0)
        set(systems[1], hsWind, periodOf(hsWind), windDirection, 1.0, 3.3)
        set(systems[2], stormSea.swellHs, kotlin.math.max(stormSea.swellPeriod, 8.0), stormSea.swellDirection, 6.0, 5.0)
        // A storm's sea is young and steep: a shorter period than a sea of
        // its height grown over days.
        set(systems[3], stormSea.stormHs, 1.5 + 3.2 * sqrt(kotlin.math.max(stormSea.stormHs, 0.05)), stormSea.stormDirection, 0.5, 3.3)

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
                // Mostly with the wind; a confused storm sea some every way.
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
            // Heights shared so the whole comes to the system's significant height:
            // the sum of a²/2 is Hs²/16.
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

    /** The peak period of a sea this high, s: a metre sea about six seconds, a ten metre one fifteen. */
    private fun periodOf(hs: Double): Double = 1.5 + 4.2 * sqrt(kotlin.math.max(hs, 0.05))

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
    private val amplitude = DoubleArray(COMPONENTS)

    /** Height of the surface above the datum at body-fixed [position] (any length), m. */
    fun height(position: Vec3, time: Double): Double = evaluate(position, time, null, 0.0, 0.0)

    /**
     * Everything about the sea at body-fixed [position] (any length) and
     * [time], into [out]: the water's velocity [below] metres under the
     * surface. Wave trains too short to show at [spacing] metres between
     * samples are left out - 0 for all of them, which is what the physics
     * asks for.
     */
    fun sample(position: Vec3, time: Double, out: SeaSample, below: Double = 0.0, spacing: Double = 0.0): SeaSample {
        evaluate(position, time, out, below, spacing)
        return out
    }

    /**
     * The surface alone at [position], for drawing: height, how fast it is
     * rising, steepness, breaking, depth - not the water's motion. Trains
     * shorter than twice [spacing] left out, and the bed read coarsely
     * where the samples are far apart.
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
        return out
    }

    private var patching: WavePatch? = null

    private val gradient = Vec3()
    private val velocity = Vec3()
    private val sc = DetMath.SinCos()

    private fun evaluate(position: Vec3, time: Double, out: SeaSample?, below: Double, spacing: Double): Double {
        u.setTo(position).normalizeInPlace()
        (if (spacing > COARSE_BED_SPACING) coarseDepth else depth).sample(u, 0.0, depthOut)
        val bed = depthOut[0]
        val tide = tides.height(u, time, bed)
        val water = tide - bed
        if (out != null) {
            out.tide = tide; out.depth = water
            out.normal.setTo(u); out.velocity.setZero()
            out.steepness = 0.0; out.breaking = 0.0; out.significantHeight = 0.0; out.rise = 0.0
            out.wind = 0.0; out.stormHeight = 0.0
        }
        if (water <= 0.0) {
            out?.height = tide
            patching?.n = 0
            return tide
        }
        state.sample(u, time, stateOut)
        var hs = stateOut[COMPONENTS]
        var rogue = 1.0
        if (stateOut[COMPONENTS + 5] > ROGUE_SEA) rogue = rogue(u, time)

        // Shelter: each train cut by how soon land lies upwave of here.
        var sheltered = 1.0
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
                    // The shortest ripples the wind raises anew over any water at all.
                    if (k[i] > SHELTER_REGROW_K) e = kotlin.math.max(e, SHELTER_REGROW)
                    before += a * a
                    stateOut[i] = a * e
                    after += stateOut[i] * stateOut[i]
                }
                if (before > 0.0) sheltered = sqrt(after / before)
                hs *= sheltered
                stateOut[COMPONENTS + 5] *= sheltered
            }
        }

        // Shallows: waves grow as they slow, and can stand no higher than
        // the water is deep.
        var scale = rogue
        var breaking = 0.0
        val shoal = water < SHOAL_DEPTH
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
            amplitude[i] = a
        }
        if (shoal) {
            var power = 0.0
            for (i in 0 until COMPONENTS) power += amplitude[i] * amplitude[i]
            val local = 4.0 * sqrt(power / 2.0) * rogue
            val limit = BREAKING * water
            if (local > limit) {
                scale *= limit / local
                breaking = kotlin.math.min(1.0, (local - limit) / limit + 0.3)
            }
            hs = kotlin.math.min(local, limit)
        } else {
            hs *= rogue
        }

        val px = u.x * radius; val py = u.y * radius; val pz = u.z * radius
        // Trains too small beside the biggest to matter are left out.
        var biggest = 0.0
        for (i in 0 until COMPONENTS) if (amplitude[i] > biggest) biggest = amplitude[i]
        val least = kotlin.math.max(MIN_AMPLITUDE, RELATIVE_AMPLITUDE * biggest * scale)
        var height = tide
        val patch = patching
        if (patch != null) {
            patch.n = 0; patch.radius = radius
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
            }
            DetMath.sinCos(phase, sc)
            val s = sc.sin
            val c = sc.cos
            val c2 = 2.0 * c * c - 1.0
            height += a * c + 0.5 * ki * a * a * c2
            if (!wantSlope) continue
            // How fast it rises here, for drawing it a moment on.
            rise += omega[i] * (a * s + ki * a * a * 2.0 * s * c)
            // Slope, along the train's own direction on the surface.
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
            out.velocity.setTo(velocity)
                .addScaledInPlace(Vec3(stateOut[COMPONENTS + 2], stateOut[COMPONENTS + 3], stateOut[COMPONENTS + 4]), drift)
            out.steepness = gradient.length
            out.rise = rise
            out.significantHeight = hs
            out.wind = stateOut[COMPONENTS + 1]
            out.stormHeight = stateOut[COMPONENTS + 5]
            // Whitecaps where the sea is steep and the wind strong; surf where it breaks.
            val whitecap = smooth(0.18, 0.4, out.steepness) * smooth(5.0, 14.0, out.wind + 1.5 * out.stormHeight)
            // In a storm sea the high crests break: the tops of the waves
            // tumbling down their faces, onto anything small beneath.
            val crest = if (hs > 0.1) (height - tide) / hs else 0.0
            val stormBreak = smooth(0.35, 0.8, crest) * smooth(2.0, 7.0, out.stormHeight)
            out.breaking = kotlin.math.max(breaking, kotlin.math.max(whitecap, stormBreak))
            if (steep > 0.0 && out.stormHeight > ROGUE_SEA && rogue > 1.3) out.breaking = kotlin.math.max(out.breaking, 0.6)
        }
        return height
    }

    /**
     * A rogue wave's reach here, as a factor on the sea: rare events, one
     * to a patch of sea at most, each a couple of hundred metres across and
     * a minute or so long, doubling the waves at its heart. Only in a storm
     * sea, where they happen.
     */
    private fun rogue(u: Vec3, time: Double): Double {
        val cx = Math.floor(u.x * radius / ROGUE_CELL).toInt()
        val cy = Math.floor(u.y * radius / ROGUE_CELL).toInt()
        val cz = Math.floor(u.z * radius / ROGUE_CELL).toInt()
        val slot = Math.floor(time / ROGUE_SLOT).toInt()
        val h = Noise.hash(seed + 7, cx * 73_856_093 xor slot, cy, cz)
        if (h > ROGUE_CHANCE) return 1.0
        // Where in the patch and when in the slot: kept clear of the edges.
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

    companion object {
        /** Wave trains: [BANDS] wavelengths from [SHORTEST] to [LONGEST] m, [DIRECTIONS] each. */
        const val BANDS = 16
        const val DIRECTIONS = 4
        const val COMPONENTS = BANDS * DIRECTIONS
        const val SHORTEST = 3.0
        const val LONGEST = 500.0

        /**
         * Shelter: corners [SHELTER_SPACING] m apart near a craft and
         * [COARSE_SHELTER_SPACING] far off; [SHELTER_RAYS] ways round, each
         * looked along from [SHELTER_FIRST] m out, [SHELTER_STEP] times
         * further each look, to [SHELTER_REACH] - the fine bed out to
         * [SHELTER_FINE_REACH], the coarse beyond. Mean openness over
         * [SHELTER_OPEN] is open sea, left alone.
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

        /** The sea state's corners, m apart, and how long each stands, s. */
        const val STATE_SPACING = 5_000.0
        const val STATE_EPOCH = 30.0
        internal const val STATE_SIZE = COMPONENTS + 6
        private const val STATE_CAPACITY = 20_000

        /** The sea bed's corners, m apart. */
        const val DEPTH_SPACING = 200.0

        /** The coarse bed's corners, m apart, and the sample spacing beyond which it is used. */
        const val COARSE_DEPTH_SPACING = 1_000.0
        const val COARSE_BED_SPACING = 150.0
        private const val DEPTH_CAPACITY = 60_000
        private const val DEFAULT_DEPTH = 1_000.0

        /** Upwind, looking for the fetch: steps, m, and how fast waves carry what they grew, m/s. */
        private const val FETCH_STEPS = 12
        private const val FETCH_STEP = 20_000.0
        private const val FETCH_SPEED = 6.0

        /** The ripple on even the stillest sea: its height, m, and period, s. */
        private const val FLOOR_HS = 0.15
        private const val FLOOR_PERIOD = 2.5

        /** Trains running this nearly straight up or down here are left out. */
        private const val MIN_TANGENT = 0.2

        /** Steepest a train may be, as wavenumber times height. */
        private const val MAX_STEEPNESS = 0.3

        private const val MIN_AMPLITUDE = 0.002

        /** Trains smaller than this share of the biggest here are left out. */
        private const val RELATIVE_AMPLITUDE = 0.04

        /** Shallower than this, m, waves feel the bottom; they grow at most this much; and break at this share of the depth. */
        private const val SHOAL_DEPTH = 250.0
        private const val MAX_SHOALING = 2.0
        const val BREAKING = 0.6

        /** The surface water's drift, as a share of the wind. */
        private const val WIND_DRIFT = 0.015

        /** Rogues: only in a storm sea at least this high, m; one patch, m, one slot, s; how rare; how big. */
        private const val ROGUE_SEA = 5.0
        private const val ROGUE_CELL = 1_500.0
        private const val ROGUE_SLOT = 120.0
        private const val ROGUE_CHANCE = 0.02
        private const val ROGUE_RADIUS = 180.0
        private const val ROGUE_GAIN = 1.2
    }
}
