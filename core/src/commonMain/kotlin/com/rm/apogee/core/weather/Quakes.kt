package com.rm.apogee.core.weather

import com.rm.apogee.core.math.StrictMath
import com.rm.apogee.core.math.Vec3
import com.rm.apogee.core.orbit.SolarSystem
import com.rm.apogee.core.sea.DetMath
import com.rm.apogee.core.terrain.Noise
import com.rm.apogee.core.terrain.Terrain
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

/** One shock of a quake as it is at one moment, for the map, warnings and looks. */
class LiveQuake(
    /** Stable for its whole life. */
    val id: Long,
    /** The main shock's id: its own for a main shock, else the one it follows. */
    val mainId: Long,
    /** Unit, body-fixed: the point on the surface over it. */
    val epicentre: Vec3,
    /** When it struck, in world seconds. */
    val start: Double,
    val magnitude: Double,
    /** How far under the surface it struck, in metres. */
    val depth: Double,
    /** The most the ground shakes, m/s^2, right over a quake at no depth. */
    val peak: Double,
    /** Within about this many metres the shaking is near its peak. */
    val strongRadius: Double,
    /** How far from the epicentre it's felt at all, in metres along the surface. */
    val reach: Double,
    /** Seconds of strong shaking near the epicentre. */
    val duration: Double,
    /** Seconds since it struck. */
    val age: Double,
    /** Whether it struck under the sea. Aftershocks take their main shock's. */
    val underSea: Boolean,
    /** When the shaking reaches the place asked about, in world seconds, or NaN if it's out of reach. */
    val arrivesHere: Double,
    /** The most the ground shakes at the place asked about, m/s^2, 0 out of reach. */
    val peakHere: Double,
)

/**
 * Earthquakes, on a solid world with weather, when the setting has rare events.
 *
 * Up to [SLOTS] main shocks per world per [CYCLE], each at [CHANCE] x [WeatherIntensity.rare],
 * with a few weaker aftershocks over the next two hours. On Terra they strike along the real
 * plate edges (a small table of trenches, ranges and rifts; on trenches the deepest of a few tries
 * near the line), elsewhere anywhere. Fewer and up to [SITE_SHY] weaker near a launch site.
 *
 * Shaking spreads at [WAVE_SPEED] and falls off with distance from the quake under the ground;
 * at a place it's a sum of a few sines of 1 to 3 Hz along, across and up, under an
 * envelope that lasts longer the bigger the quake and the further away. Ground contacts take its
 * velocity and lift ([shake]); the sea floor quakes big enough make [Tsunamis] ([seaShock]).
 *
 * Not thread-safe: one per [Weather].
 */
internal class Quakes(
    weather: Weather,
    private val bodyRadius: Double,
    seed: Int,
    intensity: WeatherIntensity,
    /** Along Terra's plate edges, or anywhere. */
    private val plates: Boolean = weather.body.id == SolarSystem.HOMEWORLD_ID,
) {
    private val terrain: Terrain? = weather.body.terrain
    private val bodyId = weather.body.id
    private val seed = seed + (bodyRadius / 1_000.0).toInt() * 7 + 0x51E

    /** Whether this world has quakes at all, at this setting. */
    val active = intensity.rare > 0.0 && terrain != null && weather.body.atmosphere?.deep != true

    private val chance = (CHANCE * intensity.rare).coerceAtMost(0.95)

    /** Where each cycle starts, in seconds before a whole number of [CYCLE]s. */
    val phase = Noise.hash(this.seed + 401, 0, 0, 0) * CYCLE

    private class Shock {
        var exists = false
        var id = 0L
        var mainId = 0L
        var start = 0.0
        val epicentre = Vec3()
        var magnitude = 0.0
        var depth = 0.0
        var underSea = false
        var bed = 0.0
        /** m/s^2 at no distance, with a launch site's shyness in. */
        var peak = 0.0
        var radius = 0.0
        /** Felt out to here, metres from the quake under the ground, and along the surface. */
        var reachDeep = 0.0
        var reach = 0.0
        var duration = 0.0
        /** Base frequency, rad/s. */
        var omega = 0.0
        val phases = DoubleArray(9)
        /** Shaking anywhere is over by this time. */
        var end = 0.0
    }

    private class Cycle {
        var cycle = Long.MIN_VALUE
        val shocks = Array(SLOTS * PER_MAIN) { Shock() }
    }

    private val cache = Array(WAYS) { Cycle() }

    /** Shocks placed by a test in place of the hashed ones. */
    private val forced = ArrayList<Shock>()

    private val e = Vec3()
    private val n = Vec3()
    private val probe = Vec3()
    private val radial = Vec3()
    private val across = Vec3()
    private val sc = DetMath.SinCos()

    /** The launch sites on this world, unit. */
    private val sites: List<Vec3> by lazy {
        val out = ArrayList<Vec3>()
        for (site in com.rm.apogee.core.world.World.launchSites) {
            if (site.bodyId != bodyId) continue
            val c = DetMath.cos(site.latitude)
            out.add(Vec3(c * DetMath.cos(site.longitude), DetMath.sin(site.latitude), c * DetMath.sin(site.longitude)))
        }
        out
    }

    /** 1 with a launch site within strong shaking of a quake at [p] of radius [radius], fading to 0. */
    private fun nearSite(p: Vec3, radius: Double): Double {
        var most = 0.0
        for (d in sites) most = max(most, 1.0 - smooth(SITE_NEAR + radius, SITE_FAR + 4.0 * radius, d.distanceTo(p) * bodyRadius))
        return most
    }

    private fun cycleOf(c: Long): Cycle {
        val slot = cache[c.mod(WAYS.toLong()).toInt()]
        if (slot.cycle != c) build(slot, c)
        return slot
    }

    private fun newest(time: Double): Long = floor((time + phase) / CYCLE).toLong()

    private fun build(cy: Cycle, cycle: Long) {
        cy.cycle = cycle
        for (s in cy.shocks) s.exists = false
        val t = terrain ?: return
        if (!active) return
        val c = cycle.toInt()
        for (slot in 0 until SLOTS) {
            fun h(k: Int) = Noise.hash(seed + k, c, slot, 0)
            if (h(1) >= chance) continue
            val main = cy.shocks[slot * PER_MAIN]
            if (plates) placeOnPlates(t, c, slot, main.epicentre) else {
                val z = 2.0 * h(2) - 1.0
                DetMath.sinCos(2.0 * kotlin.math.PI * h(3), sc)
                val r = sqrt(max(0.0, 1.0 - z * z))
                main.epicentre.setTo(r * sc.cos, z, r * sc.sin).normalizeInPlace()
            }
            val u = h(4)
            val magnitude = MAGNITUDE_LEAST + (MAGNITUDE_MOST - MAGNITUDE_LEAST) * u * sqrt(u)
            val near = nearSite(main.epicentre, radiusOf(magnitude))
            if (h(5) < SITE_SKIP * near) continue
            val shy = SITE_SHY * h(6) * near
            val d = h(7)
            val depth = DEPTH_LEAST + (DEPTH_MOST - DEPTH_LEAST) * d * d
            main.bed = t.elevation(main.epicentre)
            main.underSea = t.hasOcean && main.bed < 0.0
            val start = cycle * CYCLE - phase + h(8) * CYCLE
            val id = cycle * ID_STRIDE + slot * PER_MAIN
            fill(main, id, id, start, magnitude, depth, shy, c, slot, 0)
            frame(main.epicentre, e, n)
            for (k in 1 until PER_MAIN) {
                fun a(j: Int) = Noise.hash(seed + 20 + j, c, slot, k)
                val after = cy.shocks[slot * PER_MAIN + k]
                val m = magnitude - AFTER_DROP - AFTER_SPREAD * a(1)
                if (m < AFTER_LEAST) continue
                val delay = main.duration + AFTER_SOONEST * DetMath.exp(a(2) * AFTER_LN_RATIO)
                val off = 1.5 * main.radius * sqrt(a(3)) / bodyRadius
                DetMath.sinCos(2.0 * kotlin.math.PI * a(4), sc)
                after.epicentre.setTo(main.epicentre).addScaledInPlace(e, off * sc.cos).addScaledInPlace(n, off * sc.sin).normalizeInPlace()
                after.bed = main.bed
                after.underSea = main.underSea
                fill(after, id + k, id, start + delay, m, depth * (0.7 + 0.6 * a(5)), shy, c, slot, k)
            }
        }
    }

    private fun fill(s: Shock, id: Long, mainId: Long, start: Double, magnitude: Double, depth: Double, shy: Double, c: Int, slot: Int, k: Int) {
        s.exists = true
        s.id = id; s.mainId = mainId; s.start = start; s.magnitude = magnitude; s.depth = depth
        s.peak = min(PEAK_MOST, PEAK_AT * tenTo(PEAK_GROWTH * (magnitude - 6.5))) * (1.0 - shy)
        s.radius = radiusOf(magnitude)
        s.duration = DURATION_AT * tenTo(DURATION_GROWTH * (magnitude - 6.0))
        s.omega = 2.0 * kotlin.math.PI * FREQUENCY_AT * tenTo(FREQUENCY_GROWTH * (magnitude - 6.5))
        // Felt out to where the falloff takes it under [FELT].
        s.reachDeep = if (s.peak <= FELT) 0.0 else s.radius * (StrictMath.pow(s.peak / FELT, 1.0 / FALLOFF) - 1.0)
        s.reach = if (s.reachDeep <= depth) 0.0 else sqrt(s.reachDeep * s.reachDeep - depth * depth)
        for (j in 0 until 9) s.phases[j] = 2.0 * kotlin.math.PI * Noise.hash(seed + 60 + j, c, slot, k)
        s.end = start + s.reachDeep / WAVE_SPEED + TAIL * (s.duration + s.reachDeep / SPREAD)
    }

    private fun radiusOf(magnitude: Double): Double = RADIUS_AT * tenTo(RADIUS_GROWTH * (magnitude - 6.5))

    /** On Terra's plate edges: a point along one weighted by length and how active it is, jittered off the line. */
    private fun placeOnPlates(t: Terrain, c: Int, slot: Int, out: Vec3) {
        fun h(k: Int) = Noise.hash(seed + 100 + k, c, slot, 0)
        val table = Plates.table
        val pick = h(0) * table.total
        var i = 0
        while (i < table.count - 1 && table.cumulative[i] < pick) i++
        val f = h(1)
        val ax = table.x[i]; val ay = table.y[i]; val az = table.z[i]
        probe.setTo(ax + (table.x2[i] - ax) * f, ay + (table.y2[i] - ay) * f, az + (table.z2[i] - az) * f).normalizeInPlace()
        frame(probe, e, n)
        val trench = table.trench[i]
        val tries = if (trench) TRENCH_TRIES else 1
        val jitter = (if (trench) TRENCH_JITTER else LAND_JITTER) * kotlin.math.PI / 180.0
        var best = Double.MAX_VALUE
        for (k in 0 until tries) {
            val de = (2.0 * h(2 + 2 * k) - 1.0) * jitter
            val dn = (2.0 * h(3 + 2 * k) - 1.0) * jitter
            radial.setTo(probe).addScaledInPlace(e, de).addScaledInPlace(n, dn).normalizeInPlace()
            // On a trench, the deepest try: the trench itself rather than the land beside it.
            val bed = if (tries > 1) t.elevation(radial) else 0.0
            if (k == 0 || bed < best) { best = bed; out.setTo(radial) }
        }
    }

    /** The shocks of cycles [from] to [to] and any a test placed, one at a time. */
    private inline fun each(from: Long, to: Long, body: (Shock) -> Unit) {
        if (forced.isNotEmpty()) {
            for (s in forced) body(s)
            return
        }
        if (!active) return
        for (c in from..to) for (s in cycleOf(c).shocks) if (s.exists) body(s)
    }

    /** Whether any shock may be shaking somewhere at [time]. Quick. */
    fun live(time: Double): Boolean {
        if (!active && forced.isEmpty()) return false
        val newest = newest(time)
        each(newest - 1, newest) { if (time >= it.start && time < it.end) return true }
        return false
    }

    /** Whether any shock is shaking or strikes between [time] and [look] seconds on. Quick. */
    fun due(time: Double, look: Double): Boolean {
        if (!active && forced.isEmpty()) return false
        val newest = newest(time + look)
        each(newest - 2, newest) { if (it.start <= time + look && it.end > time) return true }
        return false
    }

    // What [shake] found.
    private var levelOut = 0.0

    /** The strongest shaking where [shake] last looked, m/s^2, at its envelope's height. */
    fun level(): Double = levelOut

    /** Hypocentral distance from [s] to unit [up], metres. */
    private fun distance(s: Shock, up: Vec3): Double {
        val chord = s.epicentre.distanceTo(up) * bodyRadius
        return sqrt(chord * chord + s.depth * s.depth)
    }

    /** How strong [s] is at [r] metres from it, m/s^2, before the envelope. */
    private fun strength(s: Shock, r: Double): Double {
        if (r >= s.reachDeep) return 0.0
        val q = s.radius / (s.radius + r)
        return s.peak * q * sqrt(q) * (1.0 - smooth(0.7 * s.reachDeep, s.reachDeep, r))
    }

    /**
     * How the ground moves at unit [up] at [time]: its velocity into [velocity] and acceleration
     * into [acceleration], body-fixed, m/s and m/s^2, and how far it's lifted, metres (returned).
     * All 0 where no quake is shaking it. Leaves the strongest shaking for [level].
     */
    fun shake(up: Vec3, time: Double, velocity: Vec3, acceleration: Vec3?): Double {
        velocity.setZero(); acceleration?.setZero(); levelOut = 0.0
        if (!active && forced.isEmpty()) return 0.0
        var lift = 0.0
        val newest = newest(time)
        each(newest - 1, newest) { s ->
            if (time < s.start || time >= s.end) return@each
            val r = distance(s, up)
            val a = strength(s, r)
            if (a <= 0.0) return@each
            val tau = time - s.start - r / WAVE_SPEED
            val held = s.duration + r / SPREAD
            if (tau <= 0.0 || tau >= TAIL * held) return@each
            // Up fast, held, then dying away.
            val rise = min(RISE, 0.25 * held)
            val x = tau / rise
            val up0 = if (x >= 1.0) 1.0 else x * x * (3.0 - 2.0 * x)
            val upDot = if (x >= 1.0) 0.0 else 6.0 * x * (1.0 - x) / rise
            val fadeWidth = (TAIL - HOLD) * held
            val y = ((tau - HOLD * held) / fadeWidth).coerceIn(0.0, 1.0)
            val down = 1.0 - y * y * (3.0 - 2.0 * y)
            val downDot = if (y <= 0.0 || y >= 1.0) 0.0 else -6.0 * y * (1.0 - y) / fadeWidth
            val env = up0 * down
            val envDot = upDot * down + up0 * downDot
            if (a * env > levelOut) levelOut = a * env
            // Along (away from the quake), across and up.
            radial.setTo(up).mulInPlace(up dot s.epicentre).subInPlace(s.epicentre)
            if (radial.lengthSq < 1e-18) frame(up, radial, across) else radial.normalizeInPlace()
            across.setTo(up).crossInPlace(radial)
            for (axis in 0 until 3) {
                var d = 0.0; var v = 0.0; var acc = 0.0
                for (k in 0 until 3) {
                    val w = s.omega * FREQUENCIES[k]
                    DetMath.sinCos(w * tau + s.phases[axis * 3 + k], sc)
                    val weight = WEIGHTS[k]
                    d += weight / (w * w) * sc.sin
                    v += weight / w * sc.cos
                    acc -= weight * sc.sin
                }
                val gain = a * GAINS[axis]
                val dv = gain * (envDot * d + env * v)
                val da = gain * env * acc
                when (axis) {
                    0 -> { velocity.addScaledInPlace(radial, dv); acceleration?.addScaledInPlace(radial, da) }
                    1 -> { velocity.addScaledInPlace(across, dv); acceleration?.addScaledInPlace(across, da) }
                    else -> { velocity.addScaledInPlace(up, dv); acceleration?.addScaledInPlace(up, da); lift += gain * env * d }
                }
            }
        }
        return lift
    }

    /**
     * Seconds from [time] until shaking over [least] m/s^2 reaches unit [up], within [look]: 0
     * while it's there, infinite where none comes.
     */
    fun shakeIn(up: Vec3, time: Double, look: Double, least: Double): Double {
        if (!active && forced.isEmpty()) return Double.POSITIVE_INFINITY
        var soonest = Double.POSITIVE_INFINITY
        val newest = newest(time + look)
        each(newest - 2, newest) { s ->
            if (s.start > time + look || s.end <= time) return@each
            val r = distance(s, up)
            if (strength(s, r) < least) return@each
            val arrives = s.start + r / WAVE_SPEED
            if (time >= arrives + TAIL * (s.duration + r / SPREAD)) return@each
            soonest = min(soonest, max(0.0, arrives - time))
        }
        return soonest
    }

    /**
     * The shocks that struck in the last [RECENT] seconds up to [time] and are felt within
     * [reach] metres of unit [up], into [out]. Makes new objects, so not for every sample.
     */
    fun near(up: Vec3, time: Double, reach: Double, out: MutableList<LiveQuake>) {
        val newest = newest(time)
        each(newest - 2, newest) { s ->
            if (time < s.start || time - s.start > RECENT) return@each
            val surface = StrictMath.atan2(probe.setTo(s.epicentre).crossInPlace(up).length, up dot s.epicentre) * bodyRadius
            if (surface - s.reach > reach) return@each
            val r = distance(s, up)
            val here = strength(s, r)
            out.add(LiveQuake(s.id, s.mainId, s.epicentre.copy(), s.start, s.magnitude, s.depth, s.peak, s.radius, s.reach,
                s.duration, time - s.start, s.underSea, if (here > 0.0) s.start + r / WAVE_SPEED else Double.NaN, here))
        }
    }

    /**
     * The soonest aftershock still to come after [time] whose main shock has struck, felt at unit
     * [up] over [least] m/s^2, or null. Makes a new object, so not for every sample.
     */
    fun aftershockDue(up: Vec3, time: Double, least: Double): LiveQuake? {
        if (!active || forced.isNotEmpty()) return null
        var best: Shock? = null
        var bestHere = 0.0
        val newest = newest(time)
        for (c in newest - 1..newest) {
            val cy = cycleOf(c)
            for (slot in 0 until SLOTS) {
                val main = cy.shocks[slot * PER_MAIN]
                if (!main.exists || main.start > time) continue
                for (k in 1 until PER_MAIN) {
                    val s = cy.shocks[slot * PER_MAIN + k]
                    if (!s.exists || s.start <= time) continue
                    if (best != null && s.start >= best.start) continue
                    val here = strength(s, distance(s, up))
                    if (here < least) continue
                    best = s; bestHere = here
                }
            }
        }
        val s = best ?: return null
        return LiveQuake(s.id, s.mainId, s.epicentre.copy(), s.start, s.magnitude, s.depth, s.peak, s.radius, s.reach,
            s.duration, time - s.start, s.underSea, s.start + distance(s, up) / WAVE_SPEED, bestHere)
    }

    /**
     * The main shock of [cycle] that makes a tsunami: the biggest of [TSUNAMI_MAGNITUDE] or more
     * under sea at least [Tsunamis.ORIGIN_DEPTH] deep. Its epicentre, start and magnitude into
     * [out] (x, y, z, start, magnitude); false if none.
     */
    fun seaShock(cycle: Long, out: DoubleArray): Boolean {
        if (!active) return false
        var best: Shock? = null
        val cy = cycleOf(cycle)
        for (slot in 0 until SLOTS) {
            val s = cy.shocks[slot * PER_MAIN]
            if (!s.exists || s.magnitude < TSUNAMI_MAGNITUDE || s.bed > -Tsunamis.ORIGIN_DEPTH) continue
            if (best == null || s.magnitude > best.magnitude) best = s
        }
        val b = best ?: return false
        out[0] = b.epicentre.x; out[1] = b.epicentre.y; out[2] = b.epicentre.z; out[3] = b.start; out[4] = b.magnitude
        return true
    }

    /** The main shocks of [cycle]: magnitude, start, under sea and epicentre. For tests. */
    internal fun mainShocks(cycle: Long): List<LiveQuake> {
        val cy = cycleOf(cycle)
        val out = ArrayList<LiveQuake>()
        for (slot in 0 until SLOTS) {
            val s = cy.shocks[slot * PER_MAIN]
            if (!s.exists) continue
            out.add(LiveQuake(s.id, s.mainId, s.epicentre.copy(), s.start, s.magnitude, s.depth, s.peak, s.radius, s.reach,
                s.duration, 0.0, s.underSea, Double.NaN, 0.0))
        }
        return out
    }

    /**
     * For tests: one shock at unit [epicentre] at [start], in place of all the hashed ones, or
     * with [also] one more beside those already placed.
     */
    internal fun force(epicentre: Vec3, start: Double, magnitude: Double, depth: Double = 3_000.0, also: Boolean = false) {
        if (!also) forced.clear()
        val s = Shock()
        s.epicentre.setTo(epicentre).normalizeInPlace()
        s.bed = terrain?.elevation(s.epicentre) ?: 0.0
        s.underSea = terrain?.hasOcean == true && s.bed < 0.0
        val id = -1L - forced.size
        fill(s, id, id, start, magnitude, depth, 0.0, -1 - forced.size, 0, 0)
        forced += s
    }

    private class Table(
        val count: Int,
        val x: DoubleArray, val y: DoubleArray, val z: DoubleArray,
        val x2: DoubleArray, val y2: DoubleArray, val z2: DoubleArray,
        val trench: BooleanArray,
        val cumulative: DoubleArray,
        val total: Double,
    ) {
        companion object {
            fun make(): Table {
                val ax = ArrayList<Double>(); val ay = ArrayList<Double>(); val az = ArrayList<Double>()
                val bx = ArrayList<Double>(); val by = ArrayList<Double>(); val bz = ArrayList<Double>()
                val kinds = ArrayList<Boolean>(); val weights = ArrayList<Double>()
                fun unit(lat: Double, lon: Double, out: DoubleArray) {
                    val la = lat * kotlin.math.PI / 180.0; val lo = lon * kotlin.math.PI / 180.0
                    val c = DetMath.cos(la)
                    out[0] = c * DetMath.cos(lo); out[1] = DetMath.sin(la); out[2] = c * DetMath.sin(lo)
                }
                val p = DoubleArray(3); val q = DoubleArray(3)
                for (line in LINES) {
                    val weight = line[0]
                    val isTrench = line[1] > 0.5
                    var i = 2
                    while (i + 3 < line.size) {
                        unit(line[i], line[i + 1], p); unit(line[i + 2], line[i + 3], q)
                        ax += p[0]; ay += p[1]; az += p[2]; bx += q[0]; by += q[1]; bz += q[2]
                        val dx = q[0] - p[0]; val dy = q[1] - p[1]; val dz = q[2] - p[2]
                        weights += weight * sqrt(dx * dx + dy * dy + dz * dz)
                        kinds += isTrench
                        i += 2
                    }
                }
                val n = weights.size
                val cumulative = DoubleArray(n)
                var total = 0.0
                for (k in 0 until n) { total += weights[k]; cumulative[k] = total }
                return Table(n, ax.toDoubleArray(), ay.toDoubleArray(), az.toDoubleArray(),
                    bx.toDoubleArray(), by.toDoubleArray(), bz.toDoubleArray(), kinds.toBooleanArray(), cumulative, total)
            }

            /** Each: how active, 1 for a trench (else 0), then latitude, longitude pairs. */
            private val LINES = listOf(
                // Peru-Chile trench.
                doubleArrayOf(1.0, 1.0, -46.0, -76.5, -40.0, -74.8, -33.0, -72.8, -27.0, -71.6, -20.0, -71.3, -15.0, -76.0, -10.0, -80.0, -4.0, -81.8, 2.0, -80.0, 5.0, -78.5),
                // Middle America trench.
                doubleArrayOf(1.0, 1.0, 8.0, -84.0, 12.0, -88.5, 14.5, -93.0, 16.0, -98.0, 18.0, -104.0),
                // Cascadia.
                doubleArrayOf(0.6, 1.0, 40.5, -125.5, 44.0, -125.3, 48.0, -126.5),
                // Alaska, the Aleutians, Kamchatka, the Kurils, Japan, Izu-Bonin and the Marianas.
                doubleArrayOf(1.0, 1.0, 59.0, -145.0, 56.5, -153.0, 54.0, -160.0, 52.0, -168.0, 51.0, -175.0, 50.5, 178.0, 51.0, 170.0,
                    52.0, 165.0, 54.0, 163.5, 51.0, 160.0, 47.0, 155.0, 43.0, 148.5, 40.0, 144.5, 36.0, 142.5, 34.0, 141.8,
                    30.0, 142.5, 25.0, 143.0, 20.0, 147.5, 15.0, 147.5, 12.0, 144.0, 11.0, 141.0),
                // Nankai, Ryukyu and the Philippines.
                doubleArrayOf(1.0, 1.0, 34.0, 137.0, 32.5, 134.0, 31.0, 132.0, 27.0, 129.0, 24.0, 125.0, 20.0, 122.5, 13.0, 127.0, 8.0, 127.3, 4.0, 128.0),
                // Andaman, Sumatra and Java.
                doubleArrayOf(1.0, 1.0, 14.0, 93.0, 10.0, 92.5, 5.0, 94.0, 2.0, 95.5, -2.0, 98.5, -6.0, 102.0, -9.0, 107.0, -10.5, 113.0, -11.0, 118.0, -10.5, 122.0),
                // New Britain, the Solomons and Vanuatu.
                doubleArrayOf(0.8, 1.0, -6.0, 150.0, -6.5, 155.0, -9.0, 158.0, -11.0, 162.0, -14.0, 166.5, -17.0, 167.5, -21.0, 169.5),
                // Tonga, Kermadec and Hikurangi.
                doubleArrayOf(1.0, 1.0, -15.0, -173.0, -20.0, -173.5, -25.0, -175.5, -30.0, -177.0, -35.0, -178.5, -39.0, 178.5, -41.0, 177.0),
                // Puerto Rico trench and the Lesser Antilles.
                doubleArrayOf(0.5, 1.0, 19.7, -67.0, 19.5, -62.0, 16.0, -59.5, 12.0, -60.0),
                // Hellenic arc.
                doubleArrayOf(0.5, 1.0, 36.0, 20.5, 34.8, 23.0, 35.0, 27.0),
                // The Cameroon line, out from Mount Cameroon past Bioko and Sao Tome: the Cape's own.
                doubleArrayOf(0.4, 1.0, 4.0, 9.0, 2.0, 7.5, 0.3, 6.6, -1.5, 5.6),
                // South Sandwich.
                doubleArrayOf(0.4, 1.0, -55.0, -27.0, -58.0, -25.5, -60.0, -27.0),
                // The Atlas to Burma: the Alps' south side, Greece, Turkey, the Zagros and the Himalaya.
                doubleArrayOf(0.6, 0.0, 35.5, -5.0, 36.0, 3.0, 36.5, 8.0, 38.0, 15.5, 40.0, 15.5, 42.0, 13.5, 39.0, 21.0, 39.0, 28.0,
                    40.0, 33.0, 39.5, 40.0, 38.0, 44.0, 35.0, 46.0, 32.0, 49.0, 28.0, 54.0, 30.0, 60.0, 33.0, 70.0, 35.0, 73.5,
                    33.0, 76.0, 30.5, 79.5, 28.5, 84.0, 27.5, 88.0, 27.5, 92.0, 26.0, 95.5, 22.0, 95.0, 16.0, 94.5),
                // San Andreas.
                doubleArrayOf(0.5, 0.0, 32.5, -115.5, 34.5, -118.5, 36.5, -121.0, 38.0, -122.5, 40.0, -124.0),
                // Central Asia: the Tien Shan, and Sichuan to Yunnan.
                doubleArrayOf(0.4, 0.0, 40.0, 73.0, 42.0, 80.0, 43.0, 87.0),
                doubleArrayOf(0.4, 0.0, 33.0, 104.0, 31.0, 103.0, 26.0, 101.0),
                // New Zealand's Alpine fault.
                doubleArrayOf(0.5, 0.0, -41.0, 174.0, -42.0, 172.0, -44.5, 168.5),
                // The East African rift.
                doubleArrayOf(0.25, 0.0, 12.0, 43.0, 9.0, 40.0, 5.0, 37.5, 0.0, 36.0, -4.0, 35.5, -9.0, 34.0, -14.0, 34.5),
                // The Mid-Atlantic ridge: shallow sea, few tsunamis.
                doubleArrayOf(0.15, 0.0, 65.0, -18.0, 55.0, -35.0, 45.0, -28.0, 35.0, -36.0, 20.0, -45.0, 5.0, -33.0, -5.0, -12.0, -20.0, -13.0, -35.0, -16.0, -50.0, -8.0),
            )
        }
    }

    /**
     * Terra's plate edges as segments: the trenches round the Pacific and the Sunda, Caribbean,
     * Hellenic and South Sandwich arcs, and the Cameroon line by the Cape; the ranges from the
     * Atlas to Burma, the San Andreas, the Alpine fault, central Asia and the East African rift;
     * the Mid-Atlantic ridge. Latitude and longitude in degrees, each line with how active it is.
     */
    private object Plates {
        val table: Table by lazy { Table.make() }
    }

    companion object {
        /** Main shocks per world per this many seconds at most, each at [CHANCE] at rare 1. */
        const val CYCLE = 3.0 * 3_600.0
        const val SLOTS = 5
        const val CHANCE = 0.85
        /** A main shock and up to three aftershocks. */
        private const val PER_MAIN = 4
        private const val ID_STRIDE = 32L
        private const val WAYS = 4

        /** Main shocks from this to this, most near the bottom (u^1.5). */
        const val MAGNITUDE_LEAST = 5.5
        const val MAGNITUDE_MOST = 9.0

        /** Aftershocks: this much weaker plus up to [AFTER_SPREAD], none under [AFTER_LEAST], from 30 s to 2 h after. */
        const val AFTER_DROP = 1.0
        const val AFTER_SPREAD = 1.2
        const val AFTER_LEAST = 4.5
        const val AFTER_SOONEST = 30.0
        /** ln(2 h / 30 s). */
        private const val AFTER_LN_RATIO = 5.480638923341991

        /** How deep under the surface, metres, most near the top. Terra is a tenth of Earth's size. */
        const val DEPTH_LEAST = 1_000.0
        const val DEPTH_MOST = 15_000.0

        /** Peak shaking, m/s^2: [PEAK_AT] at magnitude 6.5, x10 per 1/[PEAK_GROWTH] magnitudes, at most [PEAK_MOST]. */
        const val PEAK_AT = 1.5
        const val PEAK_GROWTH = 0.45
        const val PEAK_MOST = 10.0

        /** Near peak within this, metres at magnitude 6.5, x10 per 2.5 magnitudes; falls off as (R / (R + r))^1.5. */
        const val RADIUS_AT = 3_000.0
        const val RADIUS_GROWTH = 0.4
        private const val FALLOFF = 1.5

        /** Seconds of strong shaking at magnitude 6, x2 per magnitude. */
        const val DURATION_AT = 4.0
        const val DURATION_GROWTH = 0.3

        /** Base frequency, Hz at magnitude 6.5, lower for bigger ones. */
        const val FREQUENCY_AT = 2.2
        const val FREQUENCY_GROWTH = -0.12
        private val FREQUENCIES = doubleArrayOf(0.6, 1.0, 1.6)
        private val WEIGHTS = doubleArrayOf(0.3, 0.45, 0.25)
        /** Along, across and up. */
        private val GAINS = doubleArrayOf(1.0, 0.8, 0.55)

        /** Felt at all above this, m/s^2. */
        const val FELT = 0.1

        /** How fast shaking spreads, m/s, and how much longer it lasts further out (1 s per this many metres). */
        const val WAVE_SPEED = 3_500.0
        const val SPREAD = 4_000.0
        /** Seconds it takes to build, and its hold and end as parts of its length. */
        private const val RISE = 2.0
        private const val HOLD = 0.4
        private const val TAIL = 1.5

        /** Listed for this long after they strike, seconds. */
        const val RECENT = 3_600.0

        /** Near a launch site: up to this much weaker, and up to this share don't happen. Within [SITE_NEAR] + R, gone by [SITE_FAR] + 4R. */
        const val SITE_SHY = 0.4
        const val SITE_SKIP = 0.5
        const val SITE_NEAR = 20_000.0
        const val SITE_FAR = 40_000.0

        /** On a trench, the deepest of this many tries this far off the line in degrees; on land one try. */
        private const val TRENCH_TRIES = 5
        private const val TRENCH_JITTER = 1.2
        private const val LAND_JITTER = 1.0

        /** A main shock this big under deep enough sea makes a tsunami. */
        const val TSUNAMI_MAGNITUDE = 6.5

        private fun tenTo(x: Double): Double = DetMath.exp(x * 2.302585092994046)
    }
}
