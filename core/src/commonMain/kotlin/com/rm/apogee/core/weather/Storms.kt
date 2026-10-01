package com.rm.apogee.core.weather

import com.rm.apogee.core.math.Vec3
import com.rm.apogee.core.terrain.Noise
import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min
import com.rm.apogee.core.math.Math
import com.rm.apogee.core.lruMapOf

/** One cloud-to-ground strike: when, where it hits the ground, and how hard. */
class Strike(
    val time: Double,
    /** Unit, body-fixed: the point on the ground under the bolt. */
    val direction: Vec3,
    /** 0..1: how much damage a hit does. */
    val energy: Double,
    /** Which storm, and which of its strikes, so it has a stable identity. */
    val id: Long,
)

/** What kind of storm. They differ most in how they spread. */
enum class StormKind {
    /** One cell, about as wide as it is tall: the afternoon shower grown up. */
    SINGLE,
    /** Several towers of different ages on one shared base, 15-30 km across. */
    MULTICELL,
    /**
     * One huge rotating updraught under a broad layered base, with a wall cloud and a long anvil.
     */
    SUPERCELL,
    /** A line of towers 40-100 km long across its track, a shelf cloud in front and rain behind. */
    SQUALL,
}

/**
 * Thunderstorms.
 *
 * At most one per [CELL] per [CYCLE], each cell on its own phase, likelier where pressure is low,
 * the air is moist, and in bands. A storm rides the wind at its steering level, builds, matures and
 * rains itself out. Each is one of the [StormKind]s and has:
 * - a broad dark base over its whole footprint, kilometres to tens of kilometres across;
 * - updraught towers ([Storm.cells]), with an anvil blown out ahead of the tallest;
 * - downdraughts and rain curtains, and light rain over the back of the base;
 * - a gust front of outflow running along the ground ahead of it;
 * - rough air everywhere near it, and lightning.
 */
internal class Storms(
    private val weather: Weather,
    private val bodyRadius: Double,
    private val seed: Int,
    private val intensity: WeatherIntensity,
) {
    private val climate = weather.climate

    /** Whether this world has storms at all, at this setting. */
    private val active = intensity.storms > 0.0 && climate.storms > 0.0

    /** Whether anything falls from them. */
    private val wet = climate.precipitation != Climate.Precipitation.NONE

    class Storm {
        var exists = false
        var cx = 0; var cy = 0; var cycle = 0L
        val origin = Vec3()
        val steer = Vec3()        // tangent, m/s
        val east = Vec3(); val north = Vec3()
        var start = 0.0
        var kind = StormKind.SINGLE
        var core = 0.0            // radius of the main updraught, m
        var top = 0.0             // the tallest tower's top at maturity, m above datum
        var base = 0.0            // cloud base, m above datum
        var strength = 0.0        // 0.3..1: from a passing shower to a monster
        var strikeInterval = 0.0  // seconds between strikes at maturity
        /**
         * How far the towers lean downwind with height, in cores, and the anvil's spread vs usual.
         */
        var lean = 0.0
        var anvilSpread = 1.0

        /**
         * The updraught towers in the storm's frame: [cellAlong] metres along its track (downwind
         * positive) and [cellAcross] to its right. Each is [cellRadius] across its core,
         * [cellHeight] of [top] tall, and [cellPhase] ahead of or behind the storm in its life,
         * so a multicell's towers grow and die in turn.
         */
        var cellCount = 0
        val cellAlong = DoubleArray(MAX_CELLS)
        val cellAcross = DoubleArray(MAX_CELLS)
        val cellRadius = DoubleArray(MAX_CELLS)
        val cellHeight = DoubleArray(MAX_CELLS)
        val cellPhase = DoubleArray(MAX_CELLS)

        /**
         * The base: an ellipse [halfAlong] by [halfAcross], centred [deckAlong] along the track.
         */
        var deckAlong = 0.0
        var halfAlong = 0.0
        var halfAcross = 0.0

        /**
         * Where each tower's rain falls, in its radii along the track: ahead, or behind for a
         * squall line.
         */
        var shaftAlong = 0.5

        /** How much light rain falls over the back of the base, 0..1. */
        var stratiform = 0.0

        /** How far from its centre any part reaches, in metres: base, towers and gust front. */
        val reach: Double get() = kotlin.math.abs(deckAlong) + kotlin.math.hypot(halfAlong, halfAcross) + 3.0 * core + 4_000.0

        /** The tallest tower. */
        val mainCell: Int get() {
            var best = 0
            for (i in 1 until cellCount) if (cellHeight[i] * cellRadius[i] > cellHeight[best] * cellRadius[best]) best = i
            return best
        }
    }

    private val cells = SphereCells(bodyRadius, CELL)
    private val cache = lruMapOf<Long, Storm>(64, 4_000)
    private val keys = LongArray(160)

    /** The cells [apply] last looked in, and where from: unit, body-fixed. */
    private val nearKeys = LongArray(160)
    private var nearCount = -1
    private val nearUp = Vec3()
    private val centre = Vec3()
    private val rel = Vec3()
    private val shaftRel = Vec3()
    private val steerDir = Vec3()
    private val right = Vec3()

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
        // Lows breed storms, and highs suppress them.
        val pressure = weather.pressure(s.origin, s.start)
        // And they come in bands hundreds of kilometres long with quieter country between.
        val chance = (intensity.storms * climate.storms * 0.34 * (0.6 - 0.5 * pressure).coerceIn(0.1, 1.2) *
            (0.2 + 1.6 * bandAt(s.origin, s.start))).coerceAtMost(0.9)
        s.exists = active && Noise.hash(seed + 74, cx, cy, c) < chance
        if (s.exists) {
            fun h(k: Int) = Noise.hash(seed + k, cx, cy, c)
            // Strength, size and height loosely go together, so a big storm is usually a strong
            // one.
            val power = h(75)
            s.strength = 0.3 + 0.7 * power
            val pick = h(89)
            // Lines where the bands are strongest, and a supercell only from a strong storm.
            val band = bandAt(s.origin, s.start)
            s.kind = when {
                s.strength > 0.65 && pick < 0.14 -> StormKind.SUPERCELL
                pick < 0.20 + 0.30 * smooth(0.4, 0.8, band) -> StormKind.SQUALL
                pick < 0.72 -> StormKind.MULTICELL
                else -> StormKind.SINGLE
            }
            s.lean = 0.1 + 0.4 * h(87)
            s.anvilSpread = 0.6 + 0.9 * h(88)
            when (s.kind) {
                StormKind.SINGLE -> {
                    s.core = 2_000.0 + 2_000.0 * (0.6 * power + 0.4 * h(76))
                    s.top = 6_000.0 + 6_000.0 * s.strength * (0.8 + 0.4 * h(79))
                    addCell(s, 0.0, 0.0, s.core, 1.0, 0.0)
                    // Sometimes one or two smaller towers next to it.
                    val extra = if (h(80) > 0.75) 2 else if (h(80) > 0.4) 1 else 0
                    for (t in 0 until extra) {
                        val a = h(81 + t) * 2.0 * Math.PI
                        val r = (1.2 + 0.4 * h(83 + t)) * s.core
                        addCell(s, kotlin.math.cos(a) * r, kotlin.math.sin(a) * r, s.core * (0.5 + 0.2 * h(85 + t)), 0.45 + 0.35 * h(86 + t), (h(90 + t) - 0.5) * 0.2)
                    }
                    fitDeck(s, 1.6)
                    s.shaftAlong = 0.5
                    s.stratiform = 0.15
                }
                StormKind.MULTICELL -> {
                    s.core = 1_800.0 + 1_700.0 * h(76)
                    s.top = 7_000.0 + 7_000.0 * s.strength * (0.8 + 0.4 * h(79))
                    // Towers along a line, old at the back, new ones growing on the front edge.
                    val n = 3 + (h(80) * 5.99).toInt()
                    val spread = 9_000.0 + 9_000.0 * h(81)
                    val slant = (h(82) - 0.5) * 1.2
                    for (i in 0 until n) {
                        val f = if (n == 1) 0.0 else i / (n - 1.0) - 0.5
                        val along = f * spread * 1.2 + (h(100 + i) - 0.5) * 2_500.0
                        val across = f * spread * slant * 2.0 + (h(120 + i) - 0.5) * spread * 0.9
                        val phase = f * 0.5 + (h(140 + i) - 0.5) * 0.12
                        // The tallest where the oldest one still standing is at its best.
                        val height = (0.45 + 0.4 * h(160 + i)) + 0.3 * (1.0 - kotlin.math.abs(f) * 2.0)
                        addCell(s, along, across, s.core * (0.65 + 0.45 * h(180 + i)), height.coerceAtMost(1.0), phase)
                    }
                    fitDeck(s, 1.6)
                    s.shaftAlong = 0.5
                    s.stratiform = 0.35
                }
                StormKind.SUPERCELL -> {
                    s.core = 4_500.0 + 2_500.0 * h(76)
                    s.top = 12_000.0 + 4_000.0 * h(79)
                    s.lean = 0.05 + 0.15 * h(87)
                    s.anvilSpread = 2.0 + 0.6 * h(88)
                    addCell(s, 0.0, 0.0, s.core, 1.0, 0.0)
                    // A flanking line of smaller towers trailing off behind and to the right.
                    val flank = 2 + (h(80) * 1.99).toInt()
                    for (k in 0 until flank) {
                        addCell(
                            s, -(1.2 + 0.8 * k) * s.core, (0.6 + 0.5 * k) * s.core,
                            s.core * (0.35 + 0.15 * h(181 + k)), 0.3 + 0.25 * h(161 + k), 0.0,
                        )
                    }
                    s.halfAlong = s.core * (1.5 + 0.3 * h(91)); s.halfAcross = s.core * (1.5 + 0.3 * h(92))
                    s.deckAlong = 0.2 * s.core
                    // Rain and hail on the forward flank, clear of the base under the updraught.
                    s.shaftAlong = 0.9
                    s.stratiform = 0.2
                }
                StormKind.SQUALL -> {
                    val half = 20_000.0 + 30_000.0 * h(81)
                    s.core = 1_800.0 + 1_500.0 * h(76)
                    s.top = 8_000.0 + 5_000.0 * s.strength * (0.8 + 0.4 * h(79))
                    s.lean = 0.2 + 0.4 * h(87)
                    // Towers shoulder to shoulder in one dark wall. Unevenly spaced so they bunch,
                    // and wide enough to overlap, so a far line doesn't look like a picket fence.
                    val n = (2.0 * half / (1.8 * s.core)).toInt().coerceIn(6, MAX_CELLS)
                    val steps = DoubleArray(n) { 0.6 + 0.8 * h(120 + it) }
                    val total = steps.sum()
                    var at = -half
                    for (i in 0 until n) {
                        val gap = 2.0 * half * steps[i] / total
                        val across = at + gap * 0.5
                        at += gap
                        // Bowed forward in the middle, like a line pushed along by its own outflow.
                        val bow = 0.08 * half * (1.0 - (across / half).let { it * it })
                        val radius = max(s.core * (0.8 + 0.4 * h(180 + i)), 0.75 * 2.0 * half / n)
                        // Most up at the ceiling in the anvil, a few young ones climbing.
                        val height = if (h(160 + i) < 0.25) 0.55 + 0.25 * h(165 + i) else 0.9 + 0.1 * h(165 + i)
                        addCell(s, bow + (h(100 + i) - 0.5) * 2_000.0, across, radius, height, (h(140 + i) - 0.5) * 0.16)
                    }
                    s.halfAcross = half + 3_000.0
                    s.halfAlong = 7_000.0 + 8_000.0 * h(91)
                    // The line at the front of its base, with the rest trailing behind.
                    s.deckAlong = -(s.halfAlong - 3_000.0)
                    s.shaftAlong = -0.8
                    s.stratiform = 0.6
                }
            }
            // Its base about a kilometre above the ground it forms over, so there's room for its
            // rain on high ground. With no sea it uses the ground, however low.
            val terrain = weather.body.terrain
            val elevation = terrain?.elevation(s.origin) ?: 0.0
            val ground = if (terrain == null || terrain.hasOcean) max(elevation, 0.0) else elevation
            // A dust storm's wall stands on the ground itself.
            s.base = ground + 900.0 * climate.stormBase + 500.0 * h(77) * climate.stormBase
            s.top = s.top * climate.stormHeight + ground
            s.strikeInterval = (6.0 + 18.0 * h(78)) / s.strength
            weather.steeringWind(s.origin, s.start, s.steer)
            val speed = s.steer.length
            if (speed > MAX_STEER) s.steer.mulInPlace(MAX_STEER / speed)
        }
        cache[cacheKey] = s
        return s
    }

    private fun addCell(s: Storm, along: Double, across: Double, radius: Double, height: Double, phase: Double) {
        if (s.cellCount >= MAX_CELLS) return
        val i = s.cellCount++
        s.cellAlong[i] = along; s.cellAcross[i] = across; s.cellRadius[i] = radius
        s.cellHeight[i] = height; s.cellPhase[i] = phase
    }

    /** The base around all of [s]'s towers, reaching [margin] of each one's radius beyond it. */
    private fun fitDeck(s: Storm, margin: Double) {
        var minA = Double.MAX_VALUE; var maxA = -Double.MAX_VALUE
        var minC = Double.MAX_VALUE; var maxC = -Double.MAX_VALUE
        for (i in 0 until s.cellCount) {
            val r = s.cellRadius[i] * margin
            minA = min(minA, s.cellAlong[i] - r); maxA = max(maxA, s.cellAlong[i] + r)
            minC = min(minC, s.cellAcross[i] - r); maxC = max(maxC, s.cellAcross[i] + r)
        }
        s.deckAlong = (minA + maxA) * 0.5
        s.halfAlong = (maxA - minA) * 0.5
        s.halfAcross = max(abs(minC), abs(maxC))
    }

    /** Tower [i] of [s] at [time]: 0 before it grows or after it dies, 1 at its best. */
    fun cellLife(s: Storm, i: Int, time: Double): Double {
        val u = ((time - s.start) / CYCLE) - s.cellPhase[i]
        return smooth(0.0, 0.3, u) * (1.0 - smooth(0.7, 1.0, u))
    }

    /** How tall tower [i] of [s] has grown by [time], in metres above datum. */
    fun cellTop(s: Storm, i: Int, time: Double): Double =
        s.base + (s.top - s.base) * s.cellHeight[i] * smooth(0.0, 0.35, (time - s.start) / CYCLE - s.cellPhase[i])

    /**
     * [s]'s frame at [time]: centre (unit), track (unit, tangent) and right (unit, tangent).
     * Positions are `centre * radius + along * track + across * right`.
     */
    fun frameAt(s: Storm, time: Double, centre: Vec3, track: Vec3, right: Vec3) {
        centreAt(s, time, centre)
        track.setTo(s.steer)
        track.addScaledInPlace(centre, -(track dot centre))
        if (track.lengthSq > 1e-9) track.normalizeInPlace() else track.setTo(s.east)
        right.setTo(track).crossInPlace(centre).normalizeInPlace()
    }

    /** The point [along], [across] in [s]'s frame, on the sphere of [radius], into [out]. */
    fun place(centre: Vec3, track: Vec3, right: Vec3, along: Double, across: Double, radius: Double, out: Vec3): Vec3 =
        out.setTo(centre).mulInPlace(bodyRadius).addScaledInPlace(track, along).addScaledInPlace(right, across)
            .normalizeInPlace().mulInPlace(radius)

    /**
     * How stormy the country round unit [at] is, 0..1: long front-like bands drifting over hours.
     */
    private fun bandAt(at: Vec3, time: Double): Double {
        val k = bodyRadius / BAND_SCALE
        val drift = time / BAND_DRIFT_SECONDS
        val n = Noise.simplex(seed + 91, at.x * k * 0.35 + drift, at.y * k, at.z * k)
        return smooth(-0.2, 0.5, n)
    }

    fun centreAt(s: Storm, time: Double, out: Vec3): Vec3 =
        out.setTo(s.origin).mulInPlace(bodyRadius).addScaledInPlace(s.steer, time - s.start).normalizeInPlace()

    /** Builds, matures and dies: 0..1. */
    fun envelope(s: Storm, time: Double): Double {
        val u = ((time - s.start) / CYCLE).coerceIn(0.0, 1.0)
        return smooth(0.0, 0.3, u) * (1.0 - smooth(0.7, 1.0, u))
    }

    fun apply(up: Vec3, east: Vec3, north: Vec3, position: Vec3, altitude: Double, groundTop: Double, time: Double, out: AirSample) {
        if (!active) return
        // The cells to search depend only on where, and a craft moves a few metres between samples,
        // so keep the search until it's moved [NEAR_MOVE].
        if (nearCount < 0 || nearUp.distanceTo(up) * bodyRadius > NEAR_MOVE) {
            nearCount = cells.around(up, east, north, CELL / bodyRadius, nearKeys, reach = SEARCH)
            nearUp.setTo(up)
        }
        val n = nearCount
        for (k in 0 until n) {
            val s = storm(nearKeys[k], time)
            if (!s.exists) continue
            val envelope = envelope(s, time) * s.strength
            if (envelope <= 0.0) continue
            frameAt(s, time, centre, steerDir, right)
            rel.setTo(position).addScaledInPlace(centre, -bodyRadius)
            rel.addScaledInPlace(centre, -(rel dot centre))
            if (rel.length > s.reach) continue
            val along = rel dot steerDir
            val across = rel dot right
            val agl = altitude - groundTop

            // Under or near its base, over the whole footprint.
            val da = (along - s.deckAlong) / s.halfAlong
            val dc = across / s.halfAcross
            val q = kotlin.math.sqrt(da * da + dc * dc)
            val nearness = if (q <= 1.0) 1.0 else exp(-((q - 1.0) / 0.6).let { it * it })
            out.storm = max(out.storm, envelope * nearness)

            var density = 0.0
            var turbulence = 0.0
            var gust = 0.0
            var inflowPull = 0.0
            var inflowAlong = 0.0; var inflowAcross = 0.0
            for (i in 0 until s.cellCount) {
                val life = envelope * cellLife(s, i, time)
                if (life <= 0.0) continue
                val r = s.cellRadius[i]
                val dx = along - s.cellAlong[i]; val dy = across - s.cellAcross[i]
                val d = kotlin.math.hypot(dx, dy)
                if (d > r * 6.0) continue
                val top = cellTop(s, i, time)

                // The updraught under the tower.
                if (altitude > s.base * 0.5 && altitude < top) {
                    val w = UPDRAUGHT * life * exp(-(d / (0.6 * r)).let { it * it })
                    out.lift += w
                    out.wind.addScaledInPlace(up, w)
                }
                // Its rain and the downdraught in it.
                val sx = dx - s.shaftAlong * r
                val dShaft = kotlin.math.hypot(sx, dy)
                if (altitude < s.base + 500.0) {
                    val w = -DOWNDRAUGHT * life * exp(-(dShaft / (0.7 * r)).let { it * it }) * smooth(0.0, 200.0, agl)
                    out.lift += w
                    out.wind.addScaledInPlace(up, w)
                }
                if (wet) out.precipitation = max(out.precipitation, life * exp(-(dShaft / (0.9 * r)).let { it * it }))

                // A lone storm's gust front, spreading out from each shaft. A line's is below.
                if (s.kind != StormKind.SQUALL && agl < 1_500.0 && dShaft > 1.0) {
                    val ring = (dShaft - 1.3 * r) / (0.8 * r)
                    val outward = GUST_FRONT * life * exp(-ring * ring) * min(dShaft / r, 1.0) * (1.0 - smooth(300.0, 1_500.0, agl))
                    if (outward > gust) {
                        gust = outward
                        shaftRel.setTo(steerDir).mulInPlace(sx / dShaft).addScaledInPlace(right, dy / dShaft)
                    }
                }
                // The tower draws low air in. The strongest pull wins.
                val pull = life * (d / r) * exp(-(d / (2.0 * r)).let { it * it })
                if (pull > inflowPull && d > 1.0) { inflowPull = pull; inflowAlong = dx / d; inflowAcross = dy / d }
                turbulence = max(turbulence, TURBULENCE * life * exp(-(d / (1.8 * r)).let { it * it }))

                // The tower itself.
                if (altitude > s.base && altitude < top) {
                    val t = d / (0.9 * r)
                    density = max(density, 1.0 - t * t)
                }
            }

            // A squall line's gust front: along its whole front, blowing the way the line goes, a
            // few kilometres ahead of the towers.
            if (s.kind == StormKind.SQUALL && agl < 1_500.0 && abs(across) < s.halfAcross) {
                val half = s.halfAcross - 3_000.0
                val bow = 0.08 * half * (1.0 - (across / half).coerceIn(-1.0, 1.0).let { it * it })
                val ahead = along - bow
                val ring = (ahead - 2_500.0) / 4_000.0
                val g = GUST_FRONT * envelope * exp(-ring * ring) * (1.0 - smooth(300.0, 1_500.0, agl)) *
                    (1.0 - smooth(0.85, 1.0, abs(across) / s.halfAcross))
                if (g > gust) { gust = g; shaftRel.setTo(steerDir) }
            }
            if (gust > 0.0) out.wind.addScaledInPlace(shaftRel, gust)
            if (inflowPull > 0.0 && agl < 2_000.0) {
                val inflow = INFLOW * inflowPull * (1.0 - smooth(500.0, 2_000.0, agl))
                out.wind.addScaledInPlace(steerDir, -inflow * inflowAlong).addScaledInPlace(right, -inflow * inflowAcross)
            }
            // Rough air everywhere under and around the base, worst next to the towers.
            out.turbulence += max(turbulence, TURBULENCE * 0.4 * envelope * nearness)

            // Light rain over the back of the base.
            if (q < 1.0 && s.stratiform > 0.0 && da < 0.3) {
                if (wet) out.precipitation = max(out.precipitation, envelope * s.stratiform * 0.6 * (1.0 - q * q))
            }

            // The base: a dark ceiling over the whole footprint.
            val deckTop = s.base + DECK_DEPTH * (0.6 + 0.6 * s.strength)
            if (q < 1.0 && altitude > s.base && altitude < deckTop) density = max(density, 1.0 - q * q)

            // The anvil, blown out ahead of the tallest tower.
            val main = s.mainCell
            val anvilHeight = cellTop(s, main, time) - 800.0
            val anvilAlong = s.cellAlong[main] + 0.8 * s.core + s.lean * s.core
            val ah = kotlin.math.hypot((along - anvilAlong) / anvilHalfAlong(s), (across - anvilAcross(s)) / anvilHalfAcross(s))
            val av = (altitude - anvilHeight) / 900.0
            density = max(density, 1.0 - ah * ah - av * av)
            if (density > 0.0) addCloud(out, (density * 2.0).coerceAtMost(1.0) * envelope, climate.stormCloud)
        }
    }

    /**
     * How much of the sky over unit [up] storm bases cover, seen from [altitude] below them, 0..1.
     */
    fun overcastAbove(up: Vec3, east: Vec3, north: Vec3, altitude: Double, time: Double): Double {
        if (!active) return 0.0
        val n = cells.around(up, east, north, CELL / bodyRadius, keys, reach = SEARCH)
        var most = 0.0
        val at = Vec3().setTo(up).mulInPlace(bodyRadius)
        for (k in 0 until n) {
            val s = storm(keys[k], time)
            if (!s.exists) continue
            val envelope = envelope(s, time)
            if (envelope <= 0.0 || altitude > s.base + DECK_DEPTH) continue
            frameAt(s, time, centre, steerDir, right)
            rel.setTo(at).addScaledInPlace(centre, -bodyRadius)
            val da = ((rel dot steerDir) - s.deckAlong) / s.halfAlong
            val dc = (rel dot right) / s.halfAcross
            val q = kotlin.math.sqrt(da * da + dc * dc)
            val cover = if (q <= 1.0) 1.0 else exp(-((q - 1.0) / 0.3).let { it * it })
            most = max(most, envelope * cover)
        }
        return most
    }

    /**
     * What storms do to the sea at one place: the storm sea under them, and swell from further
     * away.
     */
    class StormSea {
        /**
         * Significant height in metres of the sea raised under a storm here, and its direction
         * (unit, tangent).
         */
        var stormHs = 0.0
        val stormDirection = Vec3()
        /**
         * The biggest swell from storms further away: height in metres, period in seconds,
         * direction.
         */
        var swellHs = 0.0
        var swellPeriod = 0.0
        val swellDirection = Vec3()
    }

    private val seaKeys = LongArray(2_048)
    private val seaCentre = Vec3()
    private val seaTrack = Vec3()
    private val seaRight = Vec3()
    private val seaRel = Vec3()
    private val seaE = Vec3()
    private val seaN = Vec3()

    /**
     * The sea storms make at unit [up] at [time], into [out].
     *
     * Under a storm, a confused sea up to [STORM_SEA_HS] over its footprint, running out from its
     * towers or ahead of a squall line. Further away, its swell arrives at group speed, hours later
     * and smaller with distance, from where the storm was when it sent it, so swell from a storm
     * long gone keeps coming. Land in between stops it.
     */
    fun seaAt(up: Vec3, time: Double, out: StormSea, land: (Vec3) -> Boolean) {
        out.stormHs = 0.0; out.swellHs = 0.0; out.swellPeriod = 0.0
        out.stormDirection.setZero(); out.swellDirection.setZero()
        if (!active) return
        frame(up, seaE, seaN)
        val at = Vec3().setTo(up).mulInPlace(bodyRadius)

        // The storm sea, under the storms here now.
        val n = cells.around(up, seaE, seaN, CELL / bodyRadius, keys, reach = SEARCH)
        for (k in 0 until n) {
            val s = storm(keys[k], time)
            if (!s.exists) continue
            val env = envelope(s, time) * s.strength
            if (env <= 0.0) continue
            frameAt(s, time, seaCentre, seaTrack, seaRight)
            seaRel.setTo(at).addScaledInPlace(seaCentre, -bodyRadius)
            val along = seaRel dot seaTrack
            val across = seaRel dot seaRight
            val da = (along - s.deckAlong) / s.halfAlong
            val dc = across / s.halfAcross
            val q = kotlin.math.sqrt(da * da + dc * dc)
            val cover = if (q <= 1.0) 1.0 else exp(-((q - 1.0) / 0.5).let { it * it })
            val boost = if (s.kind == StormKind.SUPERCELL || s.kind == StormKind.SQUALL) 1.1 else 1.0
            val hs = STORM_SEA_HS * env * cover * boost
            if (hs <= out.stormHs) continue
            out.stormHs = hs
            if (s.kind == StormKind.SQUALL) {
                out.stormDirection.setTo(seaTrack)
            } else {
                // Out from the biggest tower, and on along the track.
                val main = s.mainCell
                val ra = along - s.cellAlong[main]; val rc = across - s.cellAcross[main]
                val rl = kotlin.math.hypot(ra, rc).coerceAtLeast(1.0)
                out.stormDirection.setTo(seaTrack).mulInPlace(1.0 + 0.7 * ra / rl).addScaledInPlace(seaRight, 0.7 * rc / rl)
            }
            out.stormDirection.addScaledInPlace(up, -(out.stormDirection dot up))
            if (out.stormDirection.lengthSq > 1e-12) out.stormDirection.normalizeInPlace() else out.stormDirection.setTo(seaE)
        }

        // Swell from storms further away, as they were when they sent it.
        val far = cells.around(up, seaE, seaN, CELL / bodyRadius, seaKeys, reach = SWELL_CELLS)
        val cellCentre = Vec3()
        for (k in 0 until far) {
            cells.centre(seaKeys[k], cellCentre)
            val distance0 = kotlin.math.acos((cellCentre dot up).coerceIn(-1.0, 1.0)) * bodyRadius
            if (distance0 > SWELL_REACH) continue
            val then = time - distance0 / SWELL_GROUP_SPEED
            val s = storm(seaKeys[k], then)
            if (!s.exists) continue
            val env = envelope(s, then) * s.strength
            if (env <= 0.0) continue
            centreAt(s, then, seaCentre)
            val distance = kotlin.math.acos((seaCentre dot up).coerceIn(-1.0, 1.0)) * bodyRadius
            val source = kotlin.math.max(s.halfAlong, s.halfAcross)
            if (distance < source) continue
            val hs = STORM_SEA_HS * SWELL_SHARE * env * kotlin.math.sqrt(source / distance) *
                (1.0 - smooth(0.7 * SWELL_REACH, SWELL_REACH, distance))
            if (hs <= out.swellHs) continue
            // Across the sea only. A coast in between stops it.
            var blocked = false
            for (step in 1..3) {
                val f = step / 4.0
                val probe = Vec3().setTo(up).mulInPlace(1.0 - f).addScaledInPlace(seaCentre, f).normalizeInPlace()
                if (land(probe)) { blocked = true; break }
            }
            if (blocked) continue
            out.swellHs = hs
            out.swellPeriod = 10.0 + 5.0 * s.strength
            out.swellDirection.setTo(up).addScaledInPlace(seaCentre, -1.0)
            out.swellDirection.addScaledInPlace(up, -(out.swellDirection dot up))
            if (out.swellDirection.lengthSq > 1e-12) out.swellDirection.normalizeInPlace() else out.swellDirection.setTo(seaE)
        }
    }

    /** Half the anvil's length downwind, in metres. Tens of kilometres for a supercell's. */
    fun anvilHalfAlong(s: Storm): Double = max(2.4 * s.core, s.halfAlong * 0.8) * s.anvilSpread

    /**
     * The anvil's place across the track, in metres: over the tallest tower, or a line's middle.
     */
    fun anvilAcross(s: Storm): Double = if (s.kind == StormKind.SQUALL) 0.0 else s.cellAcross[s.mainCell]

    /** Half the anvil's width, in metres. A squall line's runs its whole length. */
    fun anvilHalfAcross(s: Storm): Double =
        if (s.kind == StormKind.SQUALL) s.halfAcross * 0.9 else max(1.3 * s.core, s.halfAcross * 0.7) * min(s.anvilSpread, 1.4)

    /** Storms alive near [up] at [time], for drawing. */
    fun around(up: Vec3, east: Vec3, north: Vec3, radiusCells: Int, time: Double, out: MutableList<Storm>) {
        if (!active) return
        val found = LongArray((4 * radiusCells + 3) * (4 * radiusCells + 3) + (2 * radiusCells + 1) * (2 * radiusCells + 1) + 16)
        val n = cells.around(up, east, north, CELL / bodyRadius, found, reach = radiusCells)
        for (k in 0 until n) {
            val s = storm(found[k], time)
            if (s.exists && envelope(s, time) > 0.0) out.add(s)
        }
    }

    /** Strikes after [from] up to [to] from the storms around [up], in time order. */
    fun strikes(up: Vec3, east: Vec3, north: Vec3, from: Double, to: Double, out: MutableList<Strike>) {
        if (!active || !climate.lightning || to <= from) return
        val n = cells.around(up, east, north, CELL / bodyRadius, keys, reach = SEARCH)
        for (k in 0 until n) {
            val s = storm(keys[k], from)
            collect(s, from, to, out)
            // A window that spans a cycle boundary sees the next storm too.
            val next = storm(keys[k], to)
            if (next !== s) collect(next, from, to, out)
        }
        out.sortBy { it.time }
    }

    private fun collect(s: Storm, from: Double, to: Double, out: MutableList<Strike>) {
        if (!s.exists) return
        // Strikes only while it's mature enough to have them.
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
                // From one of its towers, near its core.
                val cell = (Noise.hash(seed + 84, s.cx, s.cy, c) * s.cellCount).toInt().coerceIn(0, s.cellCount - 1)
                val at = Vec3(); val track = Vec3(); val side = Vec3()
                frameAt(s, t, at, track, side)
                val angle = Noise.hash(seed + 81, s.cx, s.cy, c) * 2.0 * Math.PI
                val radius = 0.7 * s.cellRadius[cell] * Noise.hash(seed + 82, s.cx, s.cy, c)
                val where = place(
                    at, track, side,
                    s.cellAlong[cell] + kotlin.math.cos(angle) * radius, s.cellAcross[cell] + kotlin.math.sin(angle) * radius,
                    1.0, Vec3(),
                )
                val id = ((s.cx.toLong() shl 40) xor (s.cy.toLong() shl 20) xor (s.cycle shl 8)) * 4_096 + index
                out.add(Strike(t, where, (0.5 + 0.5 * Noise.hash(seed + 83, s.cx, s.cy, c)) * s.strength, id))
            }
            index++
        }
    }

    companion object {
        /**
         * The storm's winds at full strength, in m/s: up through the core, down out of the rain
         * shaft, out along the gust front, and in toward the core low down. Also how rough the air
         * round it is. A strong storm throws a light plane around and is no place for a parachute.
         */
        const val UPDRAUGHT = 25.0
        const val DOWNDRAUGHT = 18.0
        const val GUST_FRONT = 35.0
        const val INFLOW = 14.0
        const val TURBULENCE = 1.6

        /** The spacing of storm cells, in metres. */
        const val CELL = 60_000.0

        /**
         * How many cells to search each way for storms that reach a point. A line runs over a cell.
         */
        const val SEARCH = 2

        /**
         * How far in metres a sample point can move before the search is redone. A storm two cells
         * off is over 100 km away, so a couple more metres don't matter.
         */
        const val NEAR_MOVE = 2_000.0

        /** The most towers one storm has. */
        const val MAX_CELLS = 14

        /** The significant wave height under the worst storm, in metres. */
        const val STORM_SEA_HS = 12.0

        /**
         * How far swell travels in metres, and its speed in m/s: a ten-second swell's group speed.
         */
        const val SWELL_REACH = 400_000.0
        const val SWELL_GROUP_SPEED = 9.0

        /** How many cells to search for storms whose swell could arrive. */
        const val SWELL_CELLS = 7

        /** How big a storm's swell is compared with the sea under it. */
        const val SWELL_SHARE = 0.5

        /** How thick a storm's base is in metres, before its strength thickens it. */
        const val DECK_DEPTH = 1_400.0

        /** One storm's lifetime, in seconds. */
        const val CYCLE = 2_400.0

        /** The fastest a storm travels, in m/s, so it stays within its cell's neighbours. */
        const val MAX_STEER = 15.0

        /** Roughly how wide a band of storms is in metres. They're several times that long. */
        const val BAND_SCALE = 180_000.0

        /** Roughly how long a band takes to drift its own width, in seconds. */
        const val BAND_DRIFT_SECONDS = 6.0 * 3_600.0
    }
}
