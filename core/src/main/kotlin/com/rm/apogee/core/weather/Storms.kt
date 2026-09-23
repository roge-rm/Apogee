package com.rm.apogee.core.weather

import com.rm.apogee.core.math.Vec3
import com.rm.apogee.core.terrain.Noise
import kotlin.math.exp
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min

/** One cloud-to-ground strike: when, where it hits the ground, and how hard. */
class Strike(
    val time: Double,
    /** Unit, body-fixed: the point on the ground under the bolt. */
    val direction: Vec3,
    /** 0..1: how much damage a hit does. */
    val energy: Double,
    /** Which storm, and which of its strikes, for a stable identity. */
    val id: Long,
)

/**
 * Thunderstorms.
 *
 * At most one per [CELL] per [CYCLE], each cell on its own phase, more
 * likely where the pressure is low and the air moist. A storm is carried
 * along by the wind at its steering level, builds for the first stretch of
 * its life, matures and then rains itself out. While it lives it has:
 * - an updraught core under a cumulonimbus tower, with an anvil blown ahead;
 * - a downdraught and rain shaft just downwind of the core;
 * - a gust front of outflow running across the ground ahead of it;
 * - rough air everywhere near it; and lightning.
 */
internal class Storms(
    private val weather: Weather,
    private val bodyRadius: Double,
    private val seed: Int,
    private val intensity: WeatherIntensity,
) {
    class Storm {
        var exists = false
        var cx = 0; var cy = 0; var cycle = 0L
        val origin = Vec3()
        val steer = Vec3()        // tangent, m/s
        val east = Vec3(); val north = Vec3()
        var start = 0.0
        var core = 0.0            // radius of the core, m
        var top = 0.0             // tower top at maturity, m above datum
        var base = 0.0            // cloud base, m above datum
        var strength = 0.0        // 0.6..1
        var strikeInterval = 0.0  // s between strikes at maturity
    }

    private val cells = SphereCells(bodyRadius, CELL)
    private val cache = object : LinkedHashMap<Long, Storm>(64, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<Long, Storm>?) = size > 1_000
    }
    private val keys = LongArray(32)
    private val centre = Vec3()
    private val rel = Vec3()
    private val shaftRel = Vec3()
    private val steerDir = Vec3()

    fun storm(key: Long, time: Double): Storm {
        val cx = cells.hashX(key); val cy = cells.hashY(key)
        val phase = Noise.hash(seed + 71, cx, cy, 0) * CYCLE
        val cycle = floor((time + phase) / CYCLE).toLong()
        val cacheKey = key * 1_000_003L + cycle
        cache[cacheKey]?.let { return it }
        val s = Storm()
        s.cx = cx; s.cy = cy; s.cycle = cycle
        s.start = cycle * CYCLE - phase
        val c = cycle.toInt()
        cells.centre(key, s.origin)
        frame(s.origin, s.east, s.north)
        s.origin.addScaledInPlace(s.east, (Noise.hash(seed + 72, cx, cy, c) - 0.5) * 0.8 * CELL / bodyRadius)
            .addScaledInPlace(s.north, (Noise.hash(seed + 73, cx, cy, c) - 0.5) * 0.8 * CELL / bodyRadius)
            .normalizeInPlace()
        frame(s.origin, s.east, s.north)
        // Lows breed storms; highs suppress them.
        val pressure = weather.pressure(s.origin, s.start)
        val chance = intensity.storms * 0.12 * (0.6 - 0.5 * pressure).coerceIn(0.1, 1.2)
        s.exists = intensity.storms > 0.0 && Noise.hash(seed + 74, cx, cy, c) < chance
        if (s.exists) {
            s.strength = 0.6 + 0.4 * Noise.hash(seed + 75, cx, cy, c)
            s.core = 2_500.0 + 3_000.0 * Noise.hash(seed + 76, cx, cy, c)
            s.top = 8_000.0 + 4_000.0 * s.strength
            s.base = 900.0 + 500.0 * Noise.hash(seed + 77, cx, cy, c)
            s.strikeInterval = (6.0 + 18.0 * Noise.hash(seed + 78, cx, cy, c)) / s.strength
            weather.steeringWind(s.origin, s.start, s.steer)
            val speed = s.steer.length
            if (speed > MAX_STEER) s.steer.mulInPlace(MAX_STEER / speed)
        }
        cache[cacheKey] = s
        return s
    }

    fun centreAt(s: Storm, time: Double, out: Vec3): Vec3 =
        out.setTo(s.origin).mulInPlace(bodyRadius).addScaledInPlace(s.steer, time - s.start).normalizeInPlace()

    /** Builds, matures, dies: 0..1. */
    fun envelope(s: Storm, time: Double): Double {
        val u = ((time - s.start) / CYCLE).coerceIn(0.0, 1.0)
        return smooth(0.0, 0.3, u) * (1.0 - smooth(0.7, 1.0, u))
    }

    fun apply(up: Vec3, east: Vec3, north: Vec3, position: Vec3, altitude: Double, groundTop: Double, time: Double, out: AirSample) {
        if (intensity.storms <= 0.0) return
        val n = cells.around(up, east, north, CELL / bodyRadius, keys)
        for (k in 0 until n) {
            val s = storm(keys[k], time)
            if (!s.exists) continue
            val envelope = envelope(s, time) * s.strength
            if (envelope <= 0.0) continue
            centreAt(s, time, centre)
            rel.setTo(position).addScaledInPlace(centre, -bodyRadius)
            rel.addScaledInPlace(centre, -(rel dot centre))
            val d = rel.length
            if (d > s.core * 6.0) continue

            steerDir.setTo(s.steer)
            if (steerDir.lengthSq > 1e-9) steerDir.normalizeInPlace() else steerDir.setTo(s.east)
            // The rain shaft hangs half a core downwind of the updraught.
            shaftRel.setTo(rel).addScaledInPlace(steerDir, -0.5 * s.core)
            val dShaft = shaftRel.length

            val nearness = exp(-(d / (3.0 * s.core)).let { it * it })
            out.storm = max(out.storm, envelope * nearness)

            // Updraught under the tower, inflow to it.
            val towerTop = s.base + (s.top - s.base) * smooth(0.0, 0.35, (time - s.start) / CYCLE)
            if (altitude > s.base * 0.5 && altitude < towerTop) {
                val w = 12.0 * envelope * exp(-(d / (0.6 * s.core)).let { it * it })
                out.lift += w
                out.wind.addScaledInPlace(up, w)
            }
            // Downdraught and rain below the base, downwind.
            val shaft = exp(-(dShaft / (0.7 * s.core)).let { it * it })
            if (altitude < s.base + 500.0) {
                val w = -9.0 * envelope * shaft * smooth(0.0, 200.0, altitude - groundTop)
                out.lift += w
                out.wind.addScaledInPlace(up, w)
            }
            out.precipitation = max(out.precipitation, envelope * exp(-(dShaft / (0.9 * s.core)).let { it * it }))

            // The gust front: outflow spreading across the ground from the shaft.
            val agl = altitude - groundTop
            if (agl < 1_500.0 && dShaft > 1.0) {
                val ring = (dShaft - 1.3 * s.core) / (0.8 * s.core)
                val outward = 18.0 * envelope * exp(-ring * ring) * min(dShaft / s.core, 1.0) *
                    (1.0 - smooth(300.0, 1_500.0, agl))
                out.wind.addScaledInPlace(shaftRel, outward / dShaft)
            }
            out.turbulence += 0.9 * envelope * exp(-(d / (1.8 * s.core)).let { it * it })

            // The cloud: a tower over the core, an anvil blown out ahead of it.
            var density = 0.0
            if (altitude > s.base && altitude < towerTop) {
                val q = d / (0.9 * s.core)
                density = 1.0 - q * q
            }
            val anvilHeight = towerTop - 800.0
            rel.addScaledInPlace(steerDir, -0.8 * s.core)
            val ah = rel.length / (2.4 * s.core)
            val av = (altitude - anvilHeight) / 900.0
            density = max(density, 1.0 - ah * ah - av * av)
            if (density > 0.0) addCloud(out, (density * 2.0).coerceAtMost(1.0) * envelope, CloudType.CUMULONIMBUS)
        }
    }

    /** Storms living near [up] at [time], for drawing. */
    fun around(up: Vec3, east: Vec3, north: Vec3, radiusCells: Int, time: Double, out: MutableList<Storm>) {
        if (intensity.storms <= 0.0) return
        val found = LongArray((2 * radiusCells + 1) * (2 * radiusCells + 1) * 4 + 16)
        val n = cells.around(up, east, north, CELL / bodyRadius, found, reach = radiusCells)
        for (k in 0 until n) {
            val s = storm(found[k], time)
            if (s.exists && envelope(s, time) > 0.0) out.add(s)
        }
    }

    /**
     * Strikes between [from] and [to] (exclusive, inclusive) from the storms
     * around [up], in time order.
     */
    fun strikes(up: Vec3, east: Vec3, north: Vec3, from: Double, to: Double, out: MutableList<Strike>) {
        if (intensity.storms <= 0.0 || to <= from) return
        val n = cells.around(up, east, north, CELL / bodyRadius, keys)
        for (k in 0 until n) {
            val s = storm(keys[k], from)
            collect(s, from, to, out)
            // A window that straddles a cycle boundary sees the next storm too.
            val next = storm(keys[k], to)
            if (next !== s) collect(next, from, to, out)
        }
        out.sortBy { it.time }
    }

    private fun collect(s: Storm, from: Double, to: Double, out: MutableList<Strike>) {
        if (!s.exists) return
        // Strikes only while it is mature enough to have them.
        val first = s.start + 0.25 * CYCLE
        val last = s.start + 0.8 * CYCLE
        val a = max(from, first); val b = min(to, last)
        if (b <= a) return
        var index = floor((a - first) / s.strikeInterval).toInt()
        while (true) {
            val slot = first + index * s.strikeInterval
            if (slot > b) break
            val c = (s.cycle.toInt() * 131 + index)
            val t = slot + Noise.hash(seed + 80, s.cx, s.cy, c) * s.strikeInterval * 0.8
            if (t > a && t <= b) {
                val where = centreAt(s, t, Vec3())
                val e = Vec3(); val nn = Vec3()
                frame(where, e, nn)
                val angle = Noise.hash(seed + 81, s.cx, s.cy, c) * 2.0 * Math.PI
                val radius = 0.7 * s.core * Noise.hash(seed + 82, s.cx, s.cy, c)
                where.mulInPlace(bodyRadius)
                    .addScaledInPlace(e, kotlin.math.cos(angle) * radius)
                    .addScaledInPlace(nn, kotlin.math.sin(angle) * radius)
                    .normalizeInPlace()
                val id = ((s.cx.toLong() shl 40) xor (s.cy.toLong() shl 20) xor (s.cycle shl 8)) * 4_096 + index
                out.add(Strike(t, where, (0.5 + 0.5 * Noise.hash(seed + 83, s.cx, s.cy, c)) * s.strength, id))
            }
            index++
        }
    }

    companion object {
        /** Spacing of storm cells, m. */
        const val CELL = 60_000.0

        /** One storm's life, s. */
        const val CYCLE = 2_400.0

        /** Fastest a storm travels, m/s: keeps it within reach of its own cell's neighbours. */
        const val MAX_STEER = 15.0
    }
}
