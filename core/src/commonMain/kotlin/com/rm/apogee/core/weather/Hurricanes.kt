package com.rm.apogee.core.weather

import com.rm.apogee.core.math.Vec3
import com.rm.apogee.core.sea.DetMath
import com.rm.apogee.core.terrain.Noise
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

/** One hurricane as it is at one moment, for drawing, the map and warnings. */
class LiveHurricane(
    /** Stable for its whole life. */
    val id: Long,
    /** Unit, body-fixed: the middle of the eye. */
    val centre: Vec3,
    /** How far from the centre the eyewall is, where the wind is strongest, in metres. */
    val maxWindRadius: Double,
    /** How far its wind and rain reach, in metres. */
    val outerRadius: Double,
    /** 0..1. At 1 the eyewall blows [Hurricanes.MAX_WIND]. */
    val strength: Double,
    /** Which way it's going (unit, tangent) and how fast, in m/s. */
    val heading: Vec3,
    val speed: Double,
    /** 1 turning anticlockwise seen from above, in the north; -1 clockwise, in the south. */
    val turn: Double,
) {
    /** East and north at the centre, which [cloudAt] and [pointAt] measure bearings from. */
    val east = Vec3()
    val north = Vec3()

    init {
        frame(centre, east, north)
    }

    private val sc = DetMath.SinCos()

    /**
     * How much cloud there is at [pointAt] ([r], [ax], [ay]) on a world of [radius], 0..1, before
     * [strength]. Not thread-safe.
     */
    fun cloudAt(r: Double, ax: Double, ay: Double, radius: Double): Double {
        // The air measures bearings in the plane at the point, which leans by the angle round.
        val lean = sqrt(1.0 - min(r / radius, 1.0).let { it * it })
        return Hurricanes.cloudAt(ax * lean, ay * lean, r, maxWindRadius, outerRadius, turn, sc)
    }

    /** How much of its cloud shows, 0..1, by [strength]. */
    val wet: Double get() = Hurricanes.wetAt(strength)

    /**
     * The unit direction [r] metres from the centre, as the air measures it, on bearing ([ax],
     * [ay]), a unit direction in [east] and [north], on a world of [radius], into [out].
     */
    fun pointAt(r: Double, ax: Double, ay: Double, radius: Double, out: Vec3): Vec3 {
        val t = min(r / radius, 1.0)
        return out.setTo(centre).mulInPlace(sqrt(1.0 - t * t))
            .addScaledInPlace(east, ax * t).addScaledInPlace(north, ay * t).normalizeInPlace()
    }
}

/**
 * Hurricanes, on a world with a sea and rain, when the setting has rare events.
 *
 * At most one per [CELL] per [CYCLE], each cell on its own phase, with a chance scaled by
 * [WeatherIntensity.rare]. One forms over warm open sea in the tropics, drifts west and poleward
 * and turns east past about 25 degrees. It strengthens over warm sea, weakens fast over land and
 * slowly over cold water, and dies within its cycle. Its track and strength are worked out once,
 * in [STEP] nodes, from the seed and terrain alone.
 *
 * Wind is a vortex with a calm eye and the strongest wind in the eyewall, falling off outward
 * (Holland's profile), turning in toward the eye low down. Rain and cloud are heaviest in the
 * eyewall and in two spiral bands. It also raises a big sea, swell that runs out from it, and a
 * surge on shallow coasts ([seaAt], [surge]).
 *
 * Not thread-safe: one per [Weather].
 */
internal class Hurricanes(
    private val weather: Weather,
    private val bodyRadius: Double,
    private val seed: Int,
    intensity: WeatherIntensity,
) {
    private val climate = weather.climate
    private val terrain = weather.body.terrain

    /** Whether this world has hurricanes at all, at this setting. */
    val active = intensity.rare > 0.0 && climate.storms > 0.0 &&
        climate.precipitation == Climate.Precipitation.RAIN &&
        climate.circulation == Climate.Circulation.THREE_CELL && climate.ground && terrain?.hasOcean == true

    private val chance = (CHANCE * intensity.rare).coerceAtMost(0.95)

    private class Hurricane {
        var cycle = Long.MIN_VALUE
        var slot = 0
        var exists = false
        var start = 0.0
        var life = 0.0
        var maxWindRadius = 0.0
        var outerRadius = 0.0
        var turn = 1.0
        var nodes = 0
        val x = DoubleArray(MAX_NODES)
        val y = DoubleArray(MAX_NODES)
        val z = DoubleArray(MAX_NODES)
        val strength = DoubleArray(MAX_NODES)
        /** Its velocity at each node, body-fixed, in m/s. */
        val vx = DoubleArray(MAX_NODES)
        val vy = DoubleArray(MAX_NODES)
        val vz = DoubleArray(MAX_NODES)
        /** 1 - cos of the angle within which a point can be reached, for a quick test on a node. */
        var cull = 0.0
        /** Where it formed (unit), and the same test for anywhere it could get to from there. */
        val formed = Vec3()
        var looseCull = 0.0
        /** Whether its track has been worked out yet, which is only done once something needs it. */
        var tracked = false
        var potential = 0.0
        var west = 0.0
        var pole = 0.0
    }

    /** One hurricane at one moment. */
    private class At {
        val centre = Vec3()
        val heading = Vec3()
        var speed = 0.0
        var strength = 0.0
    }

    private val cells = SphereCells(bodyRadius, CELL)
    private val slots = 6 * cells.perFace * cells.perFace
    private val cache = if (active) Array(slots * WAYS) { Hurricane() } else emptyArray()
    private val nextWay = IntArray(if (active) slots else 0)
    /** Whether any of a slot's cell is in the band where they form. Most of the poles' aren't. */
    private val possible = BooleanArray(if (active) slots else 0) { slot ->
        val d = Vec3()
        var any = false
        for (i in 0..8) for (j in 0..8) {
            cells.point(slot.toLong(), i / 8.0, j / 8.0, d)
            if (kotlin.math.abs(d.y) in FORM_FROM..FORM_TO) any = true
        }
        any
    }
    private val phases = DoubleArray(if (active) slots else 0) { Noise.hash(seed + 301, it, 0, 0) * CYCLE }

    private val at = At()
    private val formed = Vec3()
    private val probe = Vec3()
    private val walker = Vec3()
    private val e = Vec3()
    private val n = Vec3()
    private val rel = Vec3()
    private val radial = Vec3()
    private val tangential = Vec3()
    private val centreEast = Vec3()
    private val centreNorth = Vec3()
    private val horizontal = Vec3()
    private val sc = DetMath.SinCos()

    /** How many times the terrain has been asked for its height. For tests. */
    internal var elevations = 0
        private set

    private fun elevation(p: Vec3): Double {
        elevations++
        return terrain?.elevation(p) ?: -1.0
    }

    /** The launch sites on this world, unit, a few kilometres apart at least. */
    private val sites: List<Vec3> by lazy {
        val out = ArrayList<Vec3>()
        for (site in com.rm.apogee.core.world.World.launchSites) {
            if (site.bodyId != weather.body.id) continue
            val d = com.rm.apogee.core.orbit.SolarSystem.surfaceDirection(site.latitude, site.longitude)
            if (out.none { it.distanceTo(d) * bodyRadius < 10_000.0 }) out.add(d)
        }
        out
    }

    /** How close unit [p] is to a launch site, 0 far off to 1 on one. */
    private fun nearSite(p: Vec3): Double {
        var most = 0.0
        for (d in sites) most = max(most, 1.0 - smooth(SITE_NEAR, SITE_FAR, d.distanceTo(p) * bodyRadius))
        return most
    }

    /** The hurricane of [slot] whose cycle holds [time], made the first time it's asked for. */
    private fun hurricane(slot: Int, time: Double): Hurricane {
        val phase = phases[slot]
        val cycle = floor((time + phase) / CYCLE).toLong()
        val base = slot * WAYS
        for (w in 0 until WAYS) if (cache[base + w].cycle == cycle) return cache[base + w]
        val h = cache[base + nextWay[slot]]
        nextWay[slot] = (nextWay[slot] + 1) % WAYS
        build(h, slot, cycle, cycle * CYCLE - phase)
        return h
    }

    private fun build(h: Hurricane, slot: Int, cycle: Long, cycleStart: Double) {
        h.cycle = cycle; h.slot = slot; h.exists = false; h.nodes = 0; h.tracked = false
        if (!possible[slot]) return
        val c = cycle.toInt()
        fun hh(k: Int) = Noise.hash(seed + k, slot, c, 0)
        if (hh(302) >= chance) return
        // Where it forms: the first of a few tries in its cell that's over warm open sea.
        var found = false
        for (t in 0 until TRIES) {
            cells.point(slot.toLong(), hh(310 + t), hh(330 + t), formed)
            if (forms(formed)) { found = true; break }
        }
        if (!found) return
        h.exists = true
        h.life = LIFE_SHORTEST + (LIFE_LONGEST - LIFE_SHORTEST) * hh(303)
        h.start = cycleStart + hh(304) * (CYCLE - h.life)
        h.maxWindRadius = MAX_WIND_RADIUS * (1.0 + hh(305))
        h.outerRadius = OUTER * h.maxWindRadius
        h.turn = if (formed.y >= 0.0) 1.0 else -1.0
        h.formed.setTo(formed)
        h.potential = (0.75 + 0.35 * hh(306)).coerceAtMost(1.0)
        h.west = 3.5 + 2.5 * hh(307)
        h.pole = 2.5 + 2.0 * hh(308)
        val reach = (h.outerRadius + STEP * MAX_SPEED) / bodyRadius
        h.cull = 1.0 - 0.5 * reach * reach
        val loose = (h.outerRadius + h.life * MAX_SPEED) / bodyRadius
        h.looseCull = 1.0 - 0.5 * loose * loose
    }

    /** Works out [h]'s track and strength, node by node, if it hasn't been yet. */
    private fun track(h: Hurricane) {
        if (h.tracked || !h.exists) return
        h.tracked = true
        val grow = 1.0 - DetMath.exp(-STEP / GROW)
        val cool = 1.0 - DetMath.exp(-STEP / COOL)
        val onLand = DetMath.exp(-STEP / LAND_DECAY)
        val count = min((h.life / STEP).toInt() + 2, MAX_NODES)
        var s = SEED_STRENGTH
        val p = walker.setTo(h.formed)
        for (k in 0 until count) {
            h.x[k] = p.x; h.y[k] = p.y; h.z[k] = p.z
            val f = k * STEP / h.life
            // Weaker passing over a launch site, so one is seldom hit at full strength.
            h.strength[k] = s * smooth(0.0, 0.06, f) * (1.0 - smooth(0.85, 1.0, f)) * (1.0 - SITE_SHY * nearSite(p))
            // Warm sea feeds it; land and cold water starve it.
            val a = kotlin.math.abs(p.y)
            if (elevation(p) > 0.0) {
                s *= onLand
            } else {
                val target = h.potential * warmth(a)
                s += (target - s) * (if (target > s) grow else cool)
            }
            // West and poleward, then turning east.
            val recurve = smooth(RECURVE_FROM, RECURVE_TO, a)
            val ue = -h.west * (1.0 - recurve) + EAST_AFTER * recurve
            val un = h.turn * (h.pole + 1.5 * recurve)
            frame(p, e, n)
            h.vx[k] = e.x * ue + n.x * un; h.vy[k] = e.y * ue + n.y * un; h.vz[k] = e.z * ue + n.z * un
            p.addScaledInPlace(e, ue * STEP / bodyRadius).addScaledInPlace(n, un * STEP / bodyRadius).normalizeInPlace()
        }
        h.nodes = count
    }

    /** Whether a hurricane can form at unit [p]: warm tropical sea, deep and open all round. */
    private fun forms(p: Vec3): Boolean {
        val a = kotlin.math.abs(p.y)
        if (a < FORM_FROM || a > FORM_TO) return false
        if (terrain == null) return false
        if (elevation(p) > -OPEN_DEPTH) return false
        frame(p, e, n)
        for (k in 0 until 4) {
            val de = if (k == 0) 1.0 else if (k == 1) -1.0 else 0.0
            val dn = if (k == 2) 1.0 else if (k == 3) -1.0 else 0.0
            rel.setTo(p).addScaledInPlace(e, de * OPEN_REACH / bodyRadius).addScaledInPlace(n, dn * OPEN_REACH / bodyRadius).normalizeInPlace()
            if (elevation(rel) >= 0.0) return false
        }
        return true
    }

    /** How warm the sea is for a hurricane at |sin(latitude)| [a], 0..1: the tropics. */
    private fun warmth(a: Double): Double = smooth(0.03, 0.08, a) * (1.0 - smooth(0.42, 0.6, a))

    /** Whether [h] is alive at [time] and could reach unit [up]. A quick test on its nearest node. */
    private fun reaches(h: Hurricane, up: Vec3, time: Double): Boolean {
        if (!h.exists) return false
        val u = time - h.start
        if (u <= 0.0 || u >= h.life) return false
        if (up.x * h.formed.x + up.y * h.formed.y + up.z * h.formed.z < h.looseCull) return false
        track(h)
        val k = min((u / STEP).toInt(), h.nodes - 1)
        return up.x * h.x[k] + up.y * h.y[k] + up.z * h.z[k] >= h.cull
    }

    /** [h] at [time], into [at]: where it is, where it's going and how strong. Needs its track. */
    private fun state(h: Hurricane, time: Double, at: At) {
        val f = ((time - h.start) / STEP).coerceIn(0.0, h.nodes - 1.0)
        var k = floor(f).toInt()
        if (k >= h.nodes - 1) k = h.nodes - 2
        val t = f - k
        val k1 = k + 1
        at.centre.setTo(
            h.x[k] + (h.x[k1] - h.x[k]) * t, h.y[k] + (h.y[k1] - h.y[k]) * t, h.z[k] + (h.z[k1] - h.z[k]) * t,
        ).normalizeInPlace()
        at.strength = h.strength[k] + (h.strength[k1] - h.strength[k]) * t
        at.heading.setTo(h.vx[k] + (h.vx[k1] - h.vx[k]) * t, h.vy[k] + (h.vy[k1] - h.vy[k]) * t, h.vz[k] + (h.vz[k1] - h.vz[k]) * t)
        at.heading.addScaledInPlace(at.centre, -(at.heading dot at.centre))
        val l = at.heading.length
        at.speed = l
        if (l > 1e-12) at.heading.mulInPlace(1.0 / l) else frame(at.centre, at.heading, n)
    }

    /**
     * Where unit [up] is from [at]'s centre: [rel] in metres along the ground at [up], and the
     * unit [radial] (outward) and [tangential] (with the wind) directions. Returns the distance.
     */
    private fun place(up: Vec3, at: At, turn: Double): Double {
        rel.setTo(up).subInPlace(at.centre).mulInPlace(bodyRadius)
        rel.addScaledInPlace(up, -(rel dot up))
        val r = rel.length
        if (r > 1e-6) radial.setTo(rel).mulInPlace(1.0 / r) else radial.setTo(at.heading)
        tangential.setTo(up).crossInPlace(radial).mulInPlace(turn)
        return r
    }

    /**
     * The wind at [r] metres from the centre as a share of the eyewall's, 0..1, with the eye calm:
     * Holland's profile with B = 2, faded out by the outer edge.
     */
    private fun windShape(r: Double, rm: Double, outer: Double): Double {
        if (r <= 0.0) return 0.0
        val x = rm / r
        val x2 = x * x
        if (x2 > 40.0) return 0.0
        return sqrt(x2 * DetMath.exp(1.0 - x2)) * (1.0 - smooth(0.5 * outer, outer, r))
    }

    /** The two spiral rain bands at the point [place] last found, 0..1. */
    private fun bands(r: Double, rm: Double, outer: Double, turn: Double): Double {
        if (r < 1.0) return 0.0
        frame(at.centre, centreEast, centreNorth)
        return bandAt((rel dot centreEast) / r, (rel dot centreNorth) / r, r, rm, outer, turn, sc)
    }

    /**
     * Adds the hurricanes' wind, rain, cloud and rough air at [position] (body-fixed; unit [up])
     * to [out]. [ocean] is whether it's over the sea. Nothing to do far from any.
     */
    fun apply(up: Vec3, altitude: Double, agl: Double, ocean: Boolean, time: Double, out: AirSample) {
        seenCount = 0
        // High up only the seen list matters, for a tornado over high ground.
        val high = altitude > TOP
        if (!active || (high && agl > Tornadoes.TOP)) return
        for (slot in 0 until slots) {
            val h = hurricane(slot, time)
            if (!reaches(h, up, time)) continue
            state(h, time, at)
            val s = at.strength
            if (s <= 0.0) continue
            val r = place(up, at, h.turn)
            val outer = h.outerRadius
            if (r >= outer) continue
            val rm = h.maxWindRadius
            if (seenCount < seen.size) {
                val q = seen[seenCount++]
                q.id = (h.cycle shl 16) xor slot.toLong()
                q.centre.setTo(at.centre)
                frame(at.centre, q.east, q.north)
                q.strength = s; q.maxWindRadius = rm; q.outerRadius = outer; q.turn = h.turn
            }
            if (high) continue

            val shape = windShape(r, rm, outer)
            val band = bands(r, rm, outer, h.turn)
            val eyewall = eyewallAt(r, rm)
            val eye = eyeAt(r, rm)
            val gap = 1.0 - MOAT * moatAt(r, rm)
            val inner = (1.0 - smooth(2.0 * rm, 5.0 * rm, r)) * gap
            val wet = wetAt(s)

            // Less wind at the surface, more so over land, and none high up.
            var height = (0.78 + 0.22 * smooth(0.0, 600.0, agl)) * (1.0 - smooth(8_000.0, TOP, altitude))
            if (!ocean) height *= 0.8 + 0.2 * smooth(0.0, 1_000.0, agl)
            val speed = MAX_WIND * s * shape * height * (1.0 + GUST_BAND * band)
            // It takes over the wind round it, so the eye is calm.
            val calm = 0.6 * s * (1.0 - smooth(rm, 4.0 * rm, r))
            if (calm > 0.0) {
                val vertical = out.wind dot up
                horizontal.setTo(out.wind).addScaledInPlace(up, -vertical).mulInPlace(1.0 - calm)
                out.wind.setTo(horizontal).addScaledInPlace(up, vertical)
            }
            // Turning in toward the eye near the surface.
            val inflow = INFLOW * (1.0 - smooth(0.0, 1_500.0, agl))
            val around = sqrt(1.0 - inflow * inflow)
            out.wind.addScaledInPlace(tangential, speed * around).addScaledInPlace(radial, -speed * inflow)
            out.wind.addScaledInPlace(at.heading, at.speed * TRANSLATION * s * shape * height)

            // Air rising in the eyewall.
            val w = EYEWALL_LIFT * s * eyewall * smooth(300.0, 1_500.0, agl) * (1.0 - smooth(10_000.0, 14_000.0, altitude))
            if (w > 0.0) { out.lift += w; out.wind.addScaledInPlace(up, w) }

            // Rain and cloud in the eyewall and the bands, a clear eye, and a canopy on top.
            val rain = max(eyewall, max(0.85 * band * gap, 0.3 * inner)) * (1.0 - eye)
            val top = 9_000.0 + 5_000.0 * s
            if (altitude < top) out.precipitation = max(out.precipitation, wet * rain)
            if (agl > 400.0 && altitude < top) addCloud(out, wet * rain, climate.stormCloud)
            if (altitude > top - 4_000.0 && altitude < top) {
                addCloud(out, 0.6 * wet * (1.0 - smooth(0.25 * outer, 0.55 * outer, r)) * (1.0 - eye), CloudType.CIRRUS)
            }
            out.turbulence += s * (0.35 * eyewall + 0.25 * band + 0.1 * inner) * (1.0 - 0.7 * eye)
            out.storm = max(out.storm, wet * max(rain, 0.4 * (1.0 - smooth(0.4 * outer, outer, r))) * (1.0 - 0.8 * eye))
            if (altitude < top) {
                // Darker under it than under any storm, and murky: rain, and spray off the sea
                // where the wind is strongest.
                val under = wet * max(rain, 0.75 * (1.0 - smooth(0.4 * outer, 0.8 * outer, r))) * (1.0 - 0.8 * eye) *
                    (1.0 - smooth(3_000.0, top, altitude))
                out.gloom = max(out.gloom, under)
                val p = wet * rain
                var seen = AirSample.CLEAR_VISIBILITY / (1.0 + RAIN_MURK * p * p)
                if (ocean) {
                    val spray = smooth(SPRAY_FROM, SPRAY_FULL, speed) * (1.0 - smooth(30.0, 250.0, agl))
                    seen = min(seen, AirSample.CLEAR_VISIBILITY / (1.0 + SPRAY_MURK * spray))
                }
                out.visibility = min(out.visibility, seen)
            }
        }
    }

    /**
     * Lightning from the hurricanes within [STRIKE_REACH] of unit [up] after [from] up to [to],
     * into [out]: some in the eyewall and more out in the bands, wherever the cloud is.
     */
    fun strikes(up: Vec3, from: Double, to: Double, out: MutableList<Strike>) {
        if (!active || !climate.lightning || to <= from) return
        for (slot in 0 until slots) {
            val h = hurricane(slot, from)
            if (!reaches(h, up, from)) continue
            val id = (h.cycle shl 16) xor slot.toLong()
            val c = h.cycle.toInt()
            val a = max(from, h.start); val b = min(to, h.start + h.life)
            var k = floor(a / STRIKE_INTERVAL).toLong()
            while (k * STRIKE_INTERVAL <= b) {
                val ki = k.toInt()
                fun hh(n: Int) = Noise.hash(seed + 340 + n, slot, c, ki)
                val t = (k + 0.8 * hh(0)) * STRIKE_INTERVAL
                k++
                if (t <= a || t > b) continue
                state(h, t, at)
                if (hh(1) >= wetAt(at.strength)) continue
                val rm = h.maxWindRadius
                val r = if (hh(2) < STRIKE_EYEWALL) rm * (0.9 + 0.5 * hh(3)) else rm * (2.0 + 5.0 * hh(3))
                val bearing = 2.0 * kotlin.math.PI * hh(4)
                DetMath.sinCos(bearing, sc)
                val q = min(r / bodyRadius, 1.0)
                frame(at.centre, centreEast, centreNorth)
                val where = Vec3().setTo(at.centre).mulInPlace(sqrt(1.0 - q * q))
                    .addScaledInPlace(centreEast, sc.cos * q).addScaledInPlace(centreNorth, sc.sin * q).normalizeInPlace()
                if (where.distanceTo(up) * bodyRadius > STRIKE_REACH) continue
                if (hh(5) >= cloudAt(sc.cos, sc.sin, r, rm, h.outerRadius, h.turn, sc)) continue
                out.add(Strike(t, where, (0.5 + 0.5 * hh(6)) * at.strength, Long.MIN_VALUE or (id shl 24) or (ki.toLong() and 0xffffffL)))
            }
        }
    }

    /**
     * Adds the hurricanes' sea at unit [up] at [time] to [out], where it's bigger than what's
     * there: the sea under one, up to [SEA_HS] at the eyewall, and swell running out from where
     * one was when it sent it.
     */
    fun seaAt(up: Vec3, time: Double, out: Storms.StormSea) {
        if (!active) return
        for (slot in 0 until slots) {
            // Under it now.
            val h = hurricane(slot, time)
            if (reaches(h, up, time)) {
                state(h, time, at)
                val r = place(up, at, h.turn)
                if (at.strength > 0.0 && r < h.outerRadius) {
                    val rm = h.maxWindRadius
                    val shape = if (r < rm) 0.7 + 0.3 * windShape(r, rm, h.outerRadius) else windShape(r, rm, h.outerRadius)
                    // Biggest on the side where the wind runs with the storm.
                    val hs = SEA_HS * at.strength * shape * (0.85 + 0.15 * (tangential dot at.heading))
                    if (hs > out.stormHs) {
                        out.stormHs = hs
                        out.stormDirection.setTo(tangential).addScaledInPlace(radial, 0.35).addScaledInPlace(at.heading, 0.3)
                        out.stormDirection.addScaledInPlace(up, -(out.stormDirection dot up))
                        if (out.stormDirection.lengthSq > 1e-12) out.stormDirection.normalizeInPlace() else out.stormDirection.setTo(tangential)
                    }
                }
            }
            swellFrom(slot, up, time, out)
        }
    }

    /**
     * Swell at unit [up] at [time] from [slot]'s hurricanes: from where one was at the moment
     * whose swell, at [SWELL_SPEED], arrives now. Cycles whose swell can't be here now are passed
     * over before their tracks are worked out.
     */
    private fun swellFrom(slot: Int, up: Vec3, time: Double, out: Storms.StormSea) {
        if (!possible[slot]) return
        val oldest = time - SWELL_REACH / SWELL_SPEED
        var cycleTime = time
        while (cycleTime > oldest - CYCLE) {
            val h = hurricane(slot, cycleTime)
            cycleTime -= CYCLE
            if (!h.exists || h.start + h.life <= oldest || h.start >= time) continue
            // From where it formed, it can't have got further than its life at the fastest.
            val fromFormed = h.formed.distanceTo(up) * bodyRadius
            val travel = h.life * MAX_SPEED
            val nearest = max(fromFormed - travel, 0.0)
            if (nearest > SWELL_REACH) continue
            if (time < h.start + nearest / SWELL_SPEED || time > h.start + h.life + (fromFormed + travel) / SWELL_SPEED) continue
            track(h)
            // When it was sent: what's left of the travel time back from now shrinks as it's sent
            // later, since it moves slower than its swell, so halve the span until it's found.
            var lo = h.start
            var hi = min(h.start + h.life, time)
            if (lo >= hi || arrival(h, lo, up, time) < 0.0 || arrival(h, hi, up, time) > 0.0) continue
            for (round in 0 until SEND_ROUNDS) {
                val mid = 0.5 * (lo + hi)
                if (arrival(h, mid, up, time) > 0.0) lo = mid else hi = mid
            }
            state(h, 0.5 * (lo + hi), at)
            val distance = at.centre.distanceTo(up) * bodyRadius
            val source = 3.0 * h.maxWindRadius
            if (distance > SWELL_REACH) continue
            // Fading in past its own sea, and out toward its reach.
            var hs = SWELL_SHARE * SEA_HS * at.strength * sqrt(source / max(distance, source)) *
                smooth(0.5 * source, source, distance) * (1.0 - smooth(0.7 * SWELL_REACH, SWELL_REACH, distance))
            if (hs <= out.swellHs) continue
            // Across the sea only. Land in between cuts it, by how far each point is above the sea.
            for (step in 1..SWELL_PROBES) {
                val f = step / (SWELL_PROBES + 1.0)
                probe.setTo(up).mulInPlace(1.0 - f).addScaledInPlace(at.centre, f).normalizeInPlace()
                hs *= 1.0 - smooth(-20.0, 5.0, elevation(probe))
                if (hs <= out.swellHs) break
            }
            if (hs <= out.swellHs) continue
            out.swellHs = hs
            out.swellPeriod = 12.0 + 4.0 * at.strength
            out.swellDirection.setTo(up).subInPlace(at.centre)
            out.swellDirection.addScaledInPlace(up, -(out.swellDirection dot up))
            if (out.swellDirection.lengthSq > 1e-18) out.swellDirection.normalizeInPlace() else frame(up, out.swellDirection, n)
        }
    }

    /**
     * How long ago, in seconds, swell sent by [h] at [sent] would have reached unit [up] by [time]:
     * positive if it's already past. Falls as [sent] gets later.
     */
    private fun arrival(h: Hurricane, sent: Double, up: Vec3, time: Double): Double {
        state(h, sent, at)
        return time - sent - at.centre.distanceTo(up) * bodyRadius / SWELL_SPEED
    }

    /**
     * How far the hurricanes raise the sea at unit [up] at [time] where the bed is [bed] metres
     * (negative under the sea), in metres. Only on shallow water and the lowest land by it, most
     * near the eyewall on the side where the wind runs with the storm. 0 almost everywhere.
     */
    fun surge(up: Vec3, time: Double, bed: Double): Double {
        if (!active || bed >= SURGE_LAND || bed <= -SURGE_DEPTH) return 0.0
        val shallow = (1.0 - smooth(SURGE_FULL_DEPTH, SURGE_DEPTH, -bed)) * (1.0 - smooth(0.0, SURGE_LAND, bed))
        var most = 0.0
        for (slot in 0 until slots) {
            val h = hurricane(slot, time)
            if (!reaches(h, up, time)) continue
            state(h, time, at)
            if (at.strength <= 0.0) continue
            val r = place(up, at, h.turn)
            if (r >= 0.6 * h.outerRadius) continue
            val shape = windShape(r, h.maxWindRadius, h.outerRadius) * (1.0 - smooth(0.4 * h.outerRadius, 0.6 * h.outerRadius, r))
            val side = 0.6 + 0.4 * (tangential dot at.heading)
            most = max(most, SURGE * at.strength * shape * shape * side * shallow)
        }
        return most
    }

    /**
     * How much the hurricanes hold ordinary storms back at unit [up] at [time], 0..1: all of it
     * inside most of one's reach, fading out toward its edge, by how much of its cloud shows.
     */
    fun calm(up: Vec3, time: Double): Double {
        if (!active) return 0.0
        var most = 0.0
        for (slot in 0 until slots) {
            val h = hurricane(slot, time)
            if (!reaches(h, up, time)) continue
            state(h, time, at)
            val r = place(up, at, h.turn)
            if (r >= h.outerRadius) continue
            most = max(most, wetAt(at.strength) * (1.0 - smooth(CALM_FROM * h.outerRadius, h.outerRadius, r)))
        }
        return most
    }

    /** One hurricane at one moment, as the tornadoes in its bands need it. */
    internal class Seen {
        var id = 0L
        val centre = Vec3()
        val east = Vec3()
        val north = Vec3()
        var strength = 0.0
        var maxWindRadius = 0.0
        var outerRadius = 0.0
        var turn = 1.0

        fun setTo(h: LiveHurricane): Seen {
            id = h.id; centre.setTo(h.centre); east.setTo(h.east); north.setTo(h.north)
            strength = h.strength; maxWindRadius = h.maxWindRadius; outerRadius = h.outerRadius; turn = h.turn
            return this
        }
    }

    /** The hurricanes the last [apply] found its point inside, [seenCount] of them. */
    internal val seen = Array(4) { Seen() }
    internal var seenCount = 0
        private set

    /** The hurricanes alive at [time] within [reach] metres of unit [up]'s edge, into [out]. */
    fun near(up: Vec3, time: Double, reach: Double, out: MutableList<LiveHurricane>) {
        if (!active) return
        for (slot in 0 until slots) {
            val h = hurricane(slot, time)
            if (!h.exists) continue
            val u = time - h.start
            if (u <= 0.0 || u >= h.life) continue
            track(h)
            state(h, time, at)
            if (at.centre.distanceTo(up) * bodyRadius > reach + h.outerRadius) continue
            val id = (h.cycle shl 16) xor slot.toLong()
            out.add(LiveHurricane(id, at.centre.copy(), h.maxWindRadius, h.outerRadius, at.strength, at.heading.copy(), at.speed, h.turn))
        }
    }

    companion object {
        /**
         * The two spiral rain bands, 0..1, at [r] metres from the centre, with ([ax], [ay]) the unit
         * direction to there in the centre's east and north. Going outward they curl against the
         * wind, trailing like a pinwheel's. [sc] is scratch.
         */
        fun bandAt(ax: Double, ay: Double, r: Double, rm: Double, outer: Double, turn: Double, sc: DetMath.SinCos): Double {
            val range = smooth(1.5 * rm, 2.5 * rm, r) * (1.0 - smooth(0.5 * outer, 0.85 * outer, r))
            if (range <= 0.0) return 0.0
            val c2 = ax * ax - ay * ay
            val s2 = 2.0 * ax * ay * turn
            DetMath.sinCos(2.0 * kotlin.math.PI * r / (BAND_SPACING * rm), sc)
            val b = c2 * sc.cos - s2 * sc.sin
            return if (b > 0.0) b * b * range else 0.0
        }

        /** The eyewall's ring at [r] metres from the centre, 0..1, strongest just outside [rm]. */
        fun eyewallAt(r: Double, rm: Double): Double = DetMath.exp(-((r - 1.15 * rm) / (0.4 * rm)).let { it * it })

        /** The moat, 0..1: thinner cloud between the eyewall and the bands, where the wall shows. */
        fun moatAt(r: Double, rm: Double): Double = DetMath.exp(-((r - 2.2 * rm) / (0.6 * rm)).let { it * it })

        /** How clear the eye is at [r], 1 in the middle. */
        fun eyeAt(r: Double, rm: Double): Double = 1.0 - smooth(0.35 * rm, 0.8 * rm, r)

        /**
         * How much cloud is drawn at [r] metres from the centre, 0..1, before its strength: the
         * eyewall, the bands and the canopy over the middle, with a clear eye. The same shape the
         * air's rain and cloud come from.
         */
        fun cloudAt(ax: Double, ay: Double, r: Double, rm: Double, outer: Double, turn: Double, sc: DetMath.SinCos): Double {
            if (r >= outer) return 0.0
            val band = if (r < 1.0) 0.0 else bandAt(ax, ay, r, rm, outer, turn, sc)
            val gap = 1.0 - MOAT * moatAt(r, rm)
            val inner = (1.0 - smooth(2.0 * rm, 5.0 * rm, r)) * gap
            val canopy = (1.0 - smooth(0.25 * outer, 0.55 * outer, r)) * gap
            return max(eyewallAt(r, rm), max(0.85 * band * gap, max(0.3 * inner, 0.6 * canopy))) * (1.0 - eyeAt(r, rm))
        }

        /** How much of its cloud and rain shows at [strength], 0..1. */
        fun wetAt(strength: Double): Double = smooth(0.08, 0.45, strength)

        /** The eyewall's wind at full strength, in m/s. */
        const val MAX_WIND = 70.0

        /** The sea's significant height under the eyewall at full strength, in metres. */
        const val SEA_HS = 15.0

        /** The most the sea rises on a shallow coast, in metres. */
        const val SURGE = 4.0

        /** The spacing of hurricane cells, in metres. */
        const val CELL = 2_000_000.0

        /** How long one cell's turn is, in seconds, and how long a hurricane lives in it. */
        const val CYCLE = 8.0 * 3_600.0
        const val LIFE_SHORTEST = 5.0 * 3_600.0
        const val LIFE_LONGEST = 7.0 * 3_600.0

        /** The chance one forms in a cell's cycle at [WeatherIntensity.rare] 1. */
        const val CHANCE = 0.7

        /** How many places in its cell one tries to form at. */
        const val TRIES = 12

        /** The track's node spacing, in seconds, and the most nodes one can have: the longest life and two. */
        const val STEP = 300.0
        const val MAX_NODES = 86

        /** Faster than it ever moves, in m/s, for the quick tests on where it can be. */
        const val MAX_SPEED = 10.0

        /** The eyewall's smallest radius in metres, up to twice that, and the outer edge in eyewall radii. */
        const val MAX_WIND_RADIUS = 15_000.0
        const val OUTER = 9.0

        /** Where they form, as |sin(latitude)|: 5 to 22 degrees. */
        const val FORM_FROM = 0.087
        const val FORM_TO = 0.375

        /** How deep the sea must be to form over, in metres, and how far round it must be sea. */
        const val OPEN_DEPTH = 200.0
        const val OPEN_REACH = 50_000.0

        /** Where it turns east, as |sin(latitude)|: 20 to 32 degrees, and how fast it goes then, m/s. */
        const val RECURVE_FROM = 0.34
        const val RECURVE_TO = 0.53
        const val EAST_AFTER = 5.0

        /** Strength when it forms, and how fast it changes, in seconds: growing, cooling and on land. */
        const val SEED_STRENGTH = 0.12
        const val GROW = 5_400.0
        const val COOL = 4_800.0
        const val LAND_DECAY = 3_600.0

        /** The height above which nothing of it is left, in metres. */
        const val TOP = 16_000.0

        /** How far the wind turns in toward the eye at the surface, as a share of its speed. */
        const val INFLOW = 0.35

        /** How much of its own motion adds to its wind. */
        const val TRANSLATION = 0.5

        /** How much stronger the wind is in a rain band. */
        const val GUST_BAND = 0.15

        /** The eyewall's updraught at full strength, in m/s. */
        const val EYEWALL_LIFT = 4.0

        /** The rain bands' spacing outward, in eyewall radii. */
        const val BAND_SPACING = 3.0

        /** How far its swell reaches in metres, at what group speed in m/s, and how big next to its sea. */
        const val SWELL_REACH = 1_200_000.0
        const val SWELL_SPEED = 11.0
        const val SWELL_SHARE = 0.45
        const val SWELL_PROBES = 6

        /** How many times the span is halved finding when swell was sent: to well under a second. */
        const val SEND_ROUNDS = 20

        /**
         * How much weaker one is on a launch site, fading out from [SITE_NEAR] to [SITE_FAR] metres
         * away.
         */
        const val SITE_SHY = 0.4
        const val SITE_NEAR = 40_000.0
        const val SITE_FAR = 150_000.0

        /**
         * The surge's depths in metres: full in water shallower than [SURGE_FULL_DEPTH], none past
         * [SURGE_DEPTH], fading out over land up to [SURGE_LAND] above the datum.
         */
        const val SURGE_FULL_DEPTH = 15.0
        const val SURGE_DEPTH = 150.0
        const val SURGE_LAND = 3.0

        /** Ordinary storms are held back fully inside this share of the outer radius, fading out to it. */
        const val CALM_FROM = 0.7

        /** How much cloud the moat takes away at its middle. */
        const val MOAT = 0.85

        /** How much the rain and the spray cut how far you can see: clear air over 1 + this at full, less in light rain. */
        const val RAIN_MURK = 50.0
        const val SPRAY_MURK = 120.0

        /** The wind in m/s where spray starts to fly off the sea, and where it's at its thickest. */
        const val SPRAY_FROM = 30.0
        const val SPRAY_FULL = 60.0

        /**
         * One chance of a strike per this many seconds per hurricane, kept where its cloud is,
         * [STRIKE_EYEWALL] of them in the eyewall, and only those within [STRIKE_REACH] metres
         * listed.
         */
        const val STRIKE_INTERVAL = 0.75
        const val STRIKE_EYEWALL = 0.1
        const val STRIKE_REACH = 80_000.0

        /** How many of each cell's cycles are kept. Swell needs a day or so of them. */
        const val WAYS = 8
    }
}
