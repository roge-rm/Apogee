package com.rm.apogee.core.weather

import com.rm.apogee.core.math.Vec3
import com.rm.apogee.core.terrain.Noise
import kotlin.math.exp
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.sin

/**
 * Thermals, and the cumulus that caps each one.
 *
 * One chance of a thermal per cell of [CELL] and per [CYCLE], each cell on
 * its own phase so they do not all pop at once. Whether one forms depends on
 * the ground: rock, sand and dry dirt heat and send air up; water, snow and
 * forest hardly do. A thermal stays over the ground that feeds it and leans
 * downwind as it rises, swells, peaks and fades, with a ring of sinking air
 * round it - and its cloud grows on top once it has been going a while, so
 * a pilot can find the lift by the cumulus above it, the way glider pilots
 * do. (Letting it drift over its whole life carried it three cells from its
 * own, out of reach of any search cheap enough to run every tick.)
 */
internal class Convection(
    private val weather: Weather,
    private val terrainWind: TerrainWind?,
    private val bodyRadius: Double,
    private val seed: Int,
    private val intensity: WeatherIntensity,
) {
    /** One thermal: fixed for its cell and cycle. */
    class Thermal {
        var exists = false
        val origin = Vec3()      // unit, body-fixed, at its start
        val drift = Vec3()       // tangent, m/s: the wind it leans with
        val east = Vec3(); val north = Vec3()
        var start = 0.0          // time its cycle began
        var ground = 0.0         // elevation under it, m
        var strength = 0.0       // peak updraught, m/s
        var radius = 0.0         // core, m
        var base = 0.0           // cloud base above its ground, m
        var depth = 0.0          // cloud depth at its fullest, m
        var consistency = 0.0    // how solid its cloud is, 0..1
        val lobes = DoubleArray(LOBES * 5) // east, north, up offsets; horizontal and vertical radii
        var lobeCount = 0
    }

    private val cells = SphereCells(bodyRadius, CELL)
    private val cache = object : LinkedHashMap<Long, Thermal>(256, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<Long, Thermal>?) = size > 4_000
    }
    private val keys = LongArray(64)
    private val centre = Vec3()
    private val rel = Vec3()
    private val descriptor = DoubleArray(TerrainWind.SIZE)

    /** The thermal of cell [key] in the cycle running at [time]. */
    fun thermal(key: Long, time: Double): Thermal {
        val cx = cells.hashX(key); val cy = cells.hashY(key)
        val phase = Noise.hash(seed + 11, cx, cy, 0) * CYCLE
        val cycle = floor((time + phase) / CYCLE).toLong()
        val cacheKey = key * 1_000_003L + cycle
        cache[cacheKey]?.let { return it }
        val th = Thermal()
        th.start = cycle * CYCLE - phase
        val c = cycle.toInt()
        cells.centre(key, th.origin)
        frame(th.origin, th.east, th.north)
        // Jitter within the cell so they do not sit on a grid.
        th.origin.addScaledInPlace(th.east, (Noise.hash(seed + 12, cx, cy, c) - 0.5) * 0.7 * CELL / bodyRadius)
            .addScaledInPlace(th.north, (Noise.hash(seed + 13, cx, cy, c) - 0.5) * 0.7 * CELL / bodyRadius)
            .normalizeInPlace()
        frame(th.origin, th.east, th.north)
        // One sample of the ground, not the wind's nine-sample description:
        // on a fresh flight none of those are cached, and listing the sky's
        // thermals took ten seconds.
        val terrain = weather.body.terrain
        val elevation = terrain?.elevation(th.origin) ?: 0.0
        val heat = if (terrain == null || terrain.hasOcean && elevation < 0.0) 0.0
            else TerrainWind.heat(terrain.material(th.origin, elevation, 0.0))
        // Cumulus gather: fields of them where the air is ripe for it, clear
        // sky between - not one here and there across the whole map, which is
        // what an even chance per cell gave (Dan: very well spread out). The
        // field is tens of kilometres across and drifts over hours; the
        // cloud-cover setting makes it busier or quieter.
        val field = fieldAt(th.origin, time)
        val chance = (heat * intensity.thermals * (0.06 + 1.15 * field) * weather.config.clouds.pockets).coerceAtMost(0.95)
        th.exists = heat > 0.0 && Noise.hash(seed + 14, cx, cy, c) < chance
        if (th.exists) {
            th.ground = elevation
            val roll = Noise.hash(seed + 15, cx, cy, c)
            th.strength = (1.5 + 4.5 * roll) * (0.6 + 0.4 * heat) * intensity.thermals.coerceAtMost(1.2)
            th.radius = 150.0 + 250.0 * Noise.hash(seed + 16, cx, cy, c)
            th.base = 900.0 + 900.0 * Noise.hash(seed + 17, cx, cy, c)
            th.depth = 300.0 + 1_300.0 * roll
            th.consistency = 0.6 + 0.4 * Noise.hash(seed + 18, cx, cy, c)
            weather.boundaryWind(th.origin, th.start, th.drift)
            th.drift.mulInPlace(0.8)
            // A heap of rounded lobes, the widest at the base.
            val width = th.radius * 2.5 + 200.0 + th.depth * 0.3
            // Many smaller lobes round a broad core, heaped higher towards
            // the middle: a few big ones read as solid lumps floating alone.
            th.lobeCount = 4 + (Noise.hash(seed + 19, cx, cy, c) * (LOBES - 3)).toInt().coerceAtMost(LOBES - 4)
            for (l in 0 until th.lobeCount) {
                val a = Noise.hash(seed + 20 + l, cx, cy, c) * 2.0 * Math.PI
                val out = Noise.hash(seed + 30 + l, cx, cy, c)
                val r = if (l == 0) 0.0 else width * 0.5 * out
                val o = l * 5
                th.lobes[o] = kotlin.math.cos(a) * r
                th.lobes[o + 1] = kotlin.math.sin(a) * r
                th.lobes[o + 2] = if (l == 0) 0.0 else
                    (Noise.hash(seed + 40 + l, cx, cy, c) - 0.25) * th.depth * 0.6 * (1.2 - out)
                th.lobes[o + 3] = if (l == 0) width * (0.35 + 0.2 * Noise.hash(seed + 50, cx, cy, c)) * 1.25
                    else width * (0.2 + 0.2 * Noise.hash(seed + 50 + l, cx, cy, c))
                th.lobes[o + 4] = th.depth * (if (l == 0) 0.45 else 0.25 + 0.25 * Noise.hash(seed + 60 + l, cx, cy, c))
            }
        }
        cache[cacheKey] = th
        return th
    }

    /**
     * How ripe the air is for cumulus around unit [at], 0..1: a slow,
     * broad pattern, most of the map a little and some of it a lot.
     */
    private fun fieldAt(at: Vec3, time: Double): Double {
        val k = bodyRadius / FIELD_SCALE
        val drift = time / FIELD_DRIFT_SECONDS
        val n = Noise.simplex(seed + 77, at.x * k + drift, at.y * k, at.z * k) +
            0.35 * Noise.simplex(seed + 78, at.x * k * 2.3, at.y * k * 2.3 - drift, at.z * k * 2.3)
        return smooth(-0.25, 0.55, n)
    }

    /**
     * Where [th]'s column stands [height] metres above its ground, as a unit
     * direction into [out]: over its source at the bottom, leaning downwind
     * by as far as the wind carries the air while it climbs there.
     */
    fun columnAt(th: Thermal, height: Double, out: Vec3): Vec3 {
        val speed = th.drift.length
        out.setTo(th.origin).mulInPlace(bodyRadius)
        if (speed > 1e-6) {
            val lean = (speed * height.coerceAtLeast(0.0) / th.strength.coerceAtLeast(1.0)).coerceAtMost(MAX_LEAN)
            out.addScaledInPlace(th.drift, lean / speed)
        }
        return out.normalizeInPlace()
    }

    /** Its strength envelope at [time]: swells, peaks, fades. */
    fun envelope(th: Thermal, time: Double): Double {
        val u = ((time - th.start) / CYCLE).coerceIn(0.0, 1.0)
        return Math.pow(sin(Math.PI * u), 0.7)
    }

    /** How much of its cloud there is at [time], 0..1: none until it has been going a while. */
    fun cloudAmount(th: Thermal, time: Double): Double {
        val u = ((time - th.start) / CYCLE).coerceIn(0.0, 1.0)
        return smooth(0.12, 0.35, u) * (1.0 - smooth(0.75, 0.98, u))
    }

    /**
     * Adds the thermals around [up] (unit) at [altitude] to [out]'s lift, and
     * their cumulus to its cloud; [position] is the point in metres.
     */
    fun apply(up: Vec3, east: Vec3, north: Vec3, position: Vec3, altitude: Double, time: Double, out: AirSample) {
        if (terrainWind == null || intensity.thermals <= 0.0) return
        val n = cells.around(up, east, north, CELL / bodyRadius, keys, reach = 2)
        for (k in 0 until n) {
            val th = thermal(keys[k], time)
            if (!th.exists) continue
            val envelope = envelope(th, time)
            if (envelope <= 0.0) continue
            columnAt(th, altitude - th.ground, centre)
            rel.setTo(position).addScaledInPlace(centre, -bodyRadius)
            val upness = rel dot centre
            rel.addScaledInPlace(centre, -upness)
            val distance = rel.length
            if (distance > th.radius * 4.0 + 1_500.0) continue

            val cloud = cloudAmount(th, time)
            val height = altitude - th.ground
            val top = th.base + th.depth * cloud
            val column = smooth(0.0, 150.0, height) * (1.0 - smooth(top, top + 250.0, height))
            val q = distance / th.radius
            val core = exp(-q * q)
            val ring = (distance - 2.2 * th.radius) / th.radius
            val lift = th.strength * envelope * (core - 0.22 * exp(-ring * ring)) * column
            out.lift += lift
            out.wind.addScaledInPlace(up, lift)
            // The edge of a thermal is rough air.
            out.turbulence += 0.35 * (kotlin.math.abs(lift) / 6.0) * (1.0 - core)

            if (cloud > 0.0) {
                val cloudMiddle = th.ground + th.base + th.depth * cloud * 0.5
                val dy = altitude - cloudMiddle
                val relE = rel dot th.east
                val relN = rel dot th.north
                var density = 0.0
                for (l in 0 until th.lobeCount) {
                    val o = l * 5
                    val he = (relE - th.lobes[o]) / (th.lobes[o + 3] * (0.4 + 0.6 * cloud))
                    val hn = (relN - th.lobes[o + 1]) / (th.lobes[o + 3] * (0.4 + 0.6 * cloud))
                    val v = (dy - th.lobes[o + 2] * cloud) / max(th.lobes[o + 4] * cloud, 40.0)
                    // A flat base: nothing below the condensation level.
                    if (altitude < th.ground + th.base) continue
                    density = max(density, 1.0 - (he * he + hn * hn + v * v))
                }
                if (density > 0.0) addCloud(out, (density * 2.0).coerceAtMost(1.0) * th.consistency * cloud, CloudType.CUMULUS)
            }
        }
    }

    /** Every thermal near [up] whose cloud is showing, for drawing. */
    fun clouds(up: Vec3, east: Vec3, north: Vec3, radiusCells: Int, time: Double, out: MutableList<Thermal>) {
        if (terrainWind == null || intensity.thermals <= 0.0) return
        val found = LongArray((2 * radiusCells + 1) * (2 * radiusCells + 1) * 4 + 16)
        val n = cells.around(up, east, north, CELL / bodyRadius, found, reach = radiusCells)
        for (k in 0 until n) {
            val th = thermal(found[k], time)
            if (th.exists && cloudAmount(th, time) > 0.0) out.add(th)
        }
    }

    companion object {
        /** Spacing of thermal cells, m. */
        const val CELL = 2_500.0

        /** One thermal's life, s. */
        const val CYCLE = 900.0

        const val LOBES = 10

        /** How far across a field of cumulus is, near enough, m. */
        const val FIELD_SCALE = 24_000.0

        /** How long the fields take to drift their own width, near enough, s. */
        const val FIELD_DRIFT_SECONDS = 4.0 * 3_600.0

        /** Furthest a column leans from its source, m: within reach of a 5x5 search. */
        const val MAX_LEAN = 2_000.0
    }
}

/** Cloud of [type] at [density]: the densest cloud here decides what it is. */
internal fun addCloud(out: AirSample, density: Double, type: CloudType) {
    if (density > out.cloudDensity) {
        out.cloudDensity = density
        out.cloudType = type
    }
}
