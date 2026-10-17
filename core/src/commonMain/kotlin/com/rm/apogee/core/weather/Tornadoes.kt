package com.rm.apogee.core.weather

import com.rm.apogee.core.math.StrictMath
import com.rm.apogee.core.math.Vec3
import com.rm.apogee.core.orbit.SolarSystem
import com.rm.apogee.core.sea.DetMath
import com.rm.apogee.core.terrain.EarthMoisture
import com.rm.apogee.core.terrain.EarthRelief
import com.rm.apogee.core.terrain.Noise
import com.rm.apogee.core.terrain.SurfaceMaterial
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

/** One tornado or waterspout as it is at one moment, for drawing, the map and warnings. */
class LiveTornado(
    /** Stable for its whole life. */
    val id: Long,
    /** Unit, body-fixed: the middle of the funnel at the ground. */
    val centre: Vec3,
    /** The core's radius at the ground, where the wind is strongest, in metres. */
    val coreRadius: Double,
    /** How far from the centre its wind reaches at the ground, in metres. */
    val reach: Double,
    /** 0..1. Its core blows [Tornadoes.MAX_WIND] times this, after the sea and launch sites. */
    val strength: Double,
    /** A waterspout: over the sea, and weaker. */
    val overWater: Boolean,
    /** Seconds since it touched down, and how long it lasts. It ropes out over the last quarter. */
    val age: Double,
    val life: Double,
    /** How it's moving along the ground, body-fixed, tangent, in m/s. */
    val velocity: Vec3,
    /** 1 turning anticlockwise seen from above, -1 clockwise. */
    val turn: Double,
    /** Spun up in a hurricane's rain band rather than under a supercell. */
    val inHurricane: Boolean,
)

/**
 * Tornadoes and waterspouts, when the setting has rare events.
 *
 * A supercell ([StormKind.SUPERCELL]) has two chances of one in its mature stage, the second
 * after the first, each [SUPERCELL_CHANCE] times [WeatherIntensity.rare]. A hurricane spins up
 * weaker ones in its rain bands, [BAND_TRIES] chances every [BAND_SLOT] seconds. Each touches
 * down, rides along with its storm for a few minutes and ropes out, thinner and weaker, at the
 * end. All of it is hashed from the seed and the parent storm, so nothing is stored.
 *
 * The wind is a narrow vortex: the strongest wind at the core's edge, falling off outward, air
 * pulled in low down and a strong updraught in the core, fading out a few kilometres up. Over the
 * sea it's a waterspout, [SPOUT] as strong.
 *
 * [apply] only looks at the supercells and hurricanes [Storms.apply] and [Hurricanes.apply] just
 * found for the same point, so it costs nothing away from them. Not thread-safe: one per [Weather].
 */
internal class Tornadoes(
    private val weather: Weather,
    private val bodyRadius: Double,
    private val seed: Int,
    intensity: WeatherIntensity,
    internal val storms: Storms,
    internal val hurricanes: Hurricanes,
) {
    private val climate = weather.climate
    private val terrain = weather.body.terrain

    /** Whether this world has tornadoes at all, at this setting. */
    val active = intensity.rare > 0.0 && climate.storms > 0.0 && climate.ground

    /** Off in tests, to compare the air with and without them. */
    internal var enabled = true

    private val chance = (SUPERCELL_CHANCE * intensity.rare).coerceAtMost(0.95)

    /** One tornado at one moment, before and after the ground under it. */
    private class Vortex {
        var id = 0L
        val centre = Vec3()
        var core = 0.0
        /** Its strength from its life alone, before the sea and launch sites. */
        var peak = 0.0
        var strength = 0.0
        var water = 0.0
        var age = 0.0
        var life = 0.0
        var turn = 1.0
        var inHurricane = false
        val reach: Double get() = REACH_CORES * core
    }

    private val v = Vortex()

    /** How many tornadoes [apply] has worked out, for tests. */
    internal var tries = 0L
    private val seenBand = Hurricanes.Seen()
    private val stormCentre = Vec3()
    private val track = Vec3()
    private val right = Vec3()
    private val rel = Vec3()
    private val radial = Vec3()
    private val tangential = Vec3()
    private val sc = DetMath.SinCos()

    /** The launch sites on this world, unit, a few kilometres apart at least. */
    private val sites: List<Vec3> by lazy {
        val out = ArrayList<Vec3>()
        for (site in com.rm.apogee.core.world.World.launchSites) {
            if (site.bodyId != weather.body.id) continue
            val d = com.rm.apogee.core.orbit.SolarSystem.surfaceDirection(site.latitude, site.longitude)
            if (out.none { it.distanceTo(d) * bodyRadius < 2_000.0 }) out.add(d)
        }
        out
    }

    /** How much of a tornado's strength is left at unit [p] for the launch sites near it, 1 far off. */
    internal fun siteFactor(p: Vec3): Double {
        var most = 0.0
        for (d in sites) most = max(most, 1.0 - smooth(SITE_NEAR, SITE_FAR, d.distanceTo(p) * bodyRadius))
        return 1.0 - SITE_SHY * most
    }

    /**
     * [s]'s tornado in mature-stage window [w] (0 or 1) at [time], into [v]. False if it has none
     * then.
     */
    private fun supercellVortex(s: Storms.Storm, w: Int, time: Double, v: Vortex): Boolean {
        tries++
        val c = s.cycle.toInt()
        fun h(k: Int) = Noise.hash(seed + 400 + 16 * w + k, s.cx, s.cy, c)
        if (h(0) >= chance) return false
        val window = (WINDOW_TO - WINDOW_FROM) * Storms.CYCLE / 2.0
        // Fewer over mountains, deserts, ice and cold country, judged where the storm is mid-window.
        if (h(0) >= chance * countryFor(s, w, s.start + WINDOW_FROM * Storms.CYCLE + (w + 0.5) * window)) return false
        val life = LIFE_SHORTEST + (LIFE_LONGEST - LIFE_SHORTEST) * h(1)
        val start = s.start + WINDOW_FROM * Storms.CYCLE + w * window + h(2) * (window - life)
        val age = time - start
        if (age <= 0.0 || age >= life) return false
        val f = age / life
        storms.frameAt(s, time, stormCentre, track, right)
        // The hemisphere it formed in, so a storm drifting over the equator keeps its spin.
        val turn = if (s.origin.y >= 0.0) 1.0 else -1.0
        // Under the back of the updraught on the side it turns toward, drifting back and in as it
        // dies, with a slow wander.
        val core = s.core
        DetMath.sinCos(2.0 * kotlin.math.PI * (age / (WANDER_SHORTEST + WANDER_SPREAD * h(5)) + h(6)), sc)
        val along = -(0.2 + 0.3 * h(3)) * core - 0.3 * core * f + WANDER * sc.sin
        val across = turn * ((0.25 + 0.35 * h(4)) * core - 0.2 * core * f) + WANDER * sc.cos
        storms.place(stormCentre, track, right, along, across, 1.0, v.centre)
        val wide = h(7)
        v.id = (((s.cx.toLong() shl 42) xor (s.cy.toLong() shl 21) xor (s.cycle and 0x1fffffL)) shl 1 or w.toLong()) and Long.MAX_VALUE
        v.core = (CORE_SMALLEST + (CORE_LARGEST - CORE_SMALLEST) * wide) * shrink(f)
        v.peak = (0.3 + 0.7 * (0.6 * h(8) + 0.4 * wide)) * lifeShape(f) * smooth(0.3, 0.8, storms.envelope(s, time))
        v.age = age; v.life = life; v.turn = turn; v.inHurricane = false
        return v.peak > 0.0
    }

    /**
     * Try [i] of slot [k] in the rain bands of hurricane [b] at [time], into [v]. False if there's
     * none then.
     */
    private fun bandVortex(b: Hurricanes.Seen, k: Long, i: Int, time: Double, v: Vortex): Boolean {
        tries++
        val hid = (b.id xor (b.id ushr 32)).toInt()
        fun h(n: Int) = Noise.hash(seed + 450 + n, hid, k.toInt(), i)
        if (h(0) >= BAND_CHANCE) return false
        val life = BAND_LIFE_SHORTEST + (BAND_LIFE_LONGEST - BAND_LIFE_SHORTEST) * h(1)
        val start = k * BAND_SLOT + h(2) * BAND_SLOT
        val age = time - start
        if (age <= 0.0 || age >= life) return false
        val rm = b.maxWindRadius
        val r = rm * (1.8 + 4.5 * h(3))
        val bearing = 2.0 * kotlin.math.PI * h(4)
        // Only where a band was when it formed, so it doesn't come and go.
        DetMath.sinCos(bearing, sc)
        if (Hurricanes.bandAt(sc.cos, sc.sin, r, rm, b.outerRadius, b.turn, sc) < BAND_NEEDS) return false
        // Carried round the eye with the band.
        DetMath.sinCos(bearing + b.turn * BAND_SPIN * age / r, sc)
        val t = min(r / bodyRadius, 1.0)
        v.centre.setTo(b.centre).mulInPlace(sqrt(1.0 - t * t))
            .addScaledInPlace(b.east, sc.cos * t).addScaledInPlace(b.north, sc.sin * t).normalizeInPlace()
        val f = age / life
        v.id = Long.MIN_VALUE or ((b.id and 0xffffffffffL) shl 22) or ((k and 0x3ffffL) shl 3) or i.toLong()
        v.core = (BAND_CORE_SMALLEST + (BAND_CORE_LARGEST - BAND_CORE_SMALLEST) * h(6)) * shrink(f)
        v.peak = (0.25 + 0.45 * h(5)) * lifeShape(f) * smooth(0.25, 0.5, b.strength)
        v.age = age; v.life = life; v.turn = b.turn; v.inHurricane = true
        return v.peak > 0.0
    }

    private val terra = weather.body.id == SolarSystem.HOMEWORLD_ID
    private val heightScale = bodyRadius / EarthRelief.EARTH_RADIUS

    // [country] per supercell window, direct-mapped.
    private val cKey = LongArray(COUNTRY_SLOTS) { -1L }
    private val cValue = DoubleArray(COUNTRY_SLOTS)
    private val countryAt = Vec3()

    /** [country] where [s] is at [time], kept for its window [w]. */
    private fun countryFor(s: Storms.Storm, w: Int, time: Double): Double {
        val key = ((((s.cx.toLong() shl 42) xor (s.cy.toLong() shl 21) xor (s.cycle and 0x1fffffL)) shl 1) or w.toLong()) and Long.MAX_VALUE
        var hash = key * -0x61c8864680b583ebL
        hash = hash xor (hash ushr 31)
        val slot = (hash and (COUNTRY_SLOTS - 1).toLong()).toInt()
        if (cKey[slot] == key) return cValue[slot]
        val v = country(storms.centreAt(s, time, countryAt))
        cKey[slot] = key; cValue[slot] = v
        return v
    }

    /**
     * How much tornado country unit [p] is, [COUNTRY_LEAST]..1: warm, moist, low and flat land is
     * 1; mountains, deserts, snow, ice and cold country are few. The sea is 1, for waterspouts.
     */
    internal fun country(p: Vec3): Double {
        val t = terrain ?: return 1.0
        val elevation = t.elevation(p)
        if (t.hasOcean && elevation < 0.5) return 1.0
        val m = t.material(p, elevation, 0.0)
        val ground = when (m) {
            SurfaceMaterial.SNOW, SurfaceMaterial.ICE,
            SurfaceMaterial.FROST, SurfaceMaterial.NITROGEN_ICE -> 0.0
            SurfaceMaterial.SAND, SurfaceMaterial.RED_DUST,
            SurfaceMaterial.DARK_SAND, SurfaceMaterial.SALT -> 0.15
            else -> 1.0
        }
        // Flat and low, in Earth metres so it reads the same on a smaller world.
        frame(p, e1, e2)
        var lo = elevation; var hi = elevation
        for (k in 0 until 4) {
            val c = if (k == 0) 1.0 else if (k == 2) -1.0 else 0.0
            val sn = if (k == 1) 1.0 else if (k == 3) -1.0 else 0.0
            ring.setTo(p).mulInPlace(bodyRadius).addScaledInPlace(e1, c * RELIEF_REACH).addScaledInPlace(e2, sn * RELIEF_REACH).normalizeInPlace()
            val h = t.elevation(ring)
            lo = min(lo, h); hi = max(hi, h)
        }
        val flat = (1.0 - smooth(HIGH_FROM, HIGH_FULL, elevation / heightScale)) *
            (1.0 - smooth(RELIEF_FROM, RELIEF_FULL, (hi - lo) / heightScale))
        var v = ground * flat
        if (terra && v > 0.0) {
            // Mid-latitudes most, the tropics some, none toward the poles; dry country few.
            val latitude = kotlin.math.abs(StrictMath.atan2(p.y, sqrt(p.x * p.x + p.z * p.z))) * (180.0 / kotlin.math.PI)
            val warm = (TROPICS + (1.0 - TROPICS) * smooth(12.0, 24.0, latitude)) * (1.0 - smooth(44.0, 54.0, latitude)) *
                (1.0 - weather.blizzardModel.coldness(p))
            val longitude = StrictMath.atan2(p.z, p.x) * (180.0 / kotlin.math.PI)
            val moist = smooth(DRY, MOIST, EarthMoisture.sample(longitude, if (p.y >= 0.0) latitude else -latitude))
            v *= warm * moist
        }
        return COUNTRY_LEAST + (1.0 - COUNTRY_LEAST) * v
    }

    // Ground results kept per tornado and 100 m cell of where it is, direct-mapped. They come from
    // the cell's middle, so the answer doesn't depend on what was asked before.
    private val gId = LongArray(GROUND_SLOTS)
    private val gX = LongArray(GROUND_SLOTS)
    private val gY = LongArray(GROUND_SLOTS)
    private val gZ = LongArray(GROUND_SLOTS)
    private val gWater = DoubleArray(GROUND_SLOTS)
    private val gSite = DoubleArray(GROUND_SLOTS)
    private val gUsed = BooleanArray(GROUND_SLOTS)
    private val cell = Vec3()
    private val ring = Vec3()
    private val e1 = Vec3()
    private val e2 = Vec3()

    /** Works out how strong [v] is for the ground under it: weaker over the sea and near launch sites. */
    private fun ground(v: Vortex) {
        val ix = floor(v.centre.x * bodyRadius / GROUND_CELL + 0.5).toLong()
        val iy = floor(v.centre.y * bodyRadius / GROUND_CELL + 0.5).toLong()
        val iz = floor(v.centre.z * bodyRadius / GROUND_CELL + 0.5).toLong()
        var hash = v.id * -0x61c8864680b583ebL + ix * 0x9E3779B1L + iy * 0x85EBCA6BL + iz * 0xC2B2AE35L
        hash = hash xor (hash ushr 29)
        val slot = (hash and (GROUND_SLOTS - 1).toLong()).toInt()
        if (!(gUsed[slot] && gId[slot] == v.id && gX[slot] == ix && gY[slot] == iy && gZ[slot] == iz)) {
            cell.setTo(ix * GROUND_CELL / bodyRadius, iy * GROUND_CELL / bodyRadius, iz * GROUND_CELL / bodyRadius).normalizeInPlace()
            gWater[slot] = overWater(cell)
            gSite[slot] = siteFactor(cell)
            gId[slot] = v.id; gX[slot] = ix; gY[slot] = iy; gZ[slot] = iz; gUsed[slot] = true
        }
        v.water = gWater[slot]
        v.strength = v.peak * (1.0 - (1.0 - SPOUT) * v.water) * gSite[slot]
    }

    /**
     * How much of the sea is round unit [p], 0..1: the middle and a ring [SHORE_RING] metres out,
     * so it eases in over a few kilometres of coast instead of a few metres.
     */
    private fun overWater(p: Vec3): Double {
        if (terrain?.hasOcean != true) return 0.0
        var sum = 1.0 - smooth(-3.0, 3.0, terrain.elevation(p))
        e1.setTo(1.0, 0.0, 0.0).crossInPlace(p)
        if (e1.lengthSq < 0.25) e1.setTo(0.0, 1.0, 0.0).crossInPlace(p)
        e1.normalizeInPlace()
        e2.setTo(p).crossInPlace(e1)
        for (k in 0 until 6) {
            ring.setTo(p).mulInPlace(bodyRadius)
                .addScaledInPlace(e1, RING_COS[k] * SHORE_RING).addScaledInPlace(e2, RING_SIN[k] * SHORE_RING)
                .normalizeInPlace()
            sum += 1.0 - smooth(-3.0, 3.0, terrain.elevation(ring))
        }
        return sum / 7.0
    }

    /**
     * Adds the wind, updraught and rough air of any tornado near [position] (body-fixed; unit
     * [up]) to [out]. Must follow [Storms.apply] and [Hurricanes.apply] for the same point.
     */
    fun apply(position: Vec3, up: Vec3, agl: Double, time: Double, out: AirSample) {
        if (!active || !enabled || agl > TOP) return
        val cells = storms.supercellsSeen
        for (n in 0 until cells.size) {
            val s = cells[n]
            for (w in 0 until 2) if (supercellVortex(s, w, time, v)) blow(v, position, up, agl, out)
        }
        val count = hurricanes.seenCount
        if (count == 0) return
        val slot = floor(time / BAND_SLOT).toLong()
        for (q in 0 until count) {
            val b = hurricanes.seen[q]
            for (k in slot - 1..slot) for (i in 0 until BAND_TRIES) {
                if (bandVortex(b, k, i, time, v)) blow(v, position, up, agl, out)
            }
        }
    }

    /** Adds [v]'s wind at [position] to [out], if it's near enough to feel. */
    private fun blow(v: Vortex, position: Vec3, up: Vec3, agl: Double, out: AirSample) {
        // Wider higher up, toward the cloud.
        val widen = 1.0 + agl / WIDEN_HEIGHT
        val core = v.core * widen
        val reach = REACH_CORES * core
        rel.setTo(position).addScaledInPlace(v.centre, -bodyRadius)
        rel.addScaledInPlace(up, -(rel dot up))
        val d = rel.length
        if (d >= reach) return
        ground(v)
        val s = v.strength
        if (s <= 0.0) return
        val height = 1.0 - smooth(FADE_FROM, TOP, agl)
        if (d > 1e-6) radial.setTo(rel).mulInPlace(1.0 / d) else radial.setTo(1.0, 0.0, 0.0).crossInPlace(up).normalizeInPlace()
        tangential.setTo(up).crossInPlace(radial).mulInPlace(v.turn)
        val x = d / core
        val edge = 1.0 - smooth(EDGE * reach, reach, d)
        // Strongest at the core's edge and falling off outward, solid-body inside.
        val speed = MAX_WIND * s * (2.0 * x / (1.0 + x * x)) * edge * height
        val inflow = INFLOW * speed * (1.0 - smooth(INFLOW_FROM, INFLOW_TO, agl))
        out.wind.addScaledInPlace(tangential, speed).addScaledInPlace(radial, -inflow)
        // The core updraught, a little even at the ground so it can pick things up.
        val w = CORE_LIFT * s * DetMath.exp(-(x / 1.3).let { it * it }) * (0.3 + 0.7 * smooth(0.0, 150.0, agl)) * height
        out.lift += w
        out.wind.addScaledInPlace(up, w)
        out.turbulence += s * (0.45 * DetMath.exp(-(x / 2.5).let { it * it }) + 0.15 * edge) * height
        out.storm = max(out.storm, s * edge)
    }

    private val debrisCells = ArrayList<Storms.Storm>(8)
    private val debrisEast = Vec3()
    private val debrisNorth = Vec3()

    /**
     * How much debris a land tornado throws at unit [up], [agl] metres over the ground, at [time],
     * 0..1: its strength inside [DEBRIS_CORES] core radii of its middle, low down. Supercells' only,
     * not hurricane bands'; none from waterspouts.
     */
    fun debrisAt(up: Vec3, agl: Double, time: Double): Double {
        if (!active || !enabled || agl > DEBRIS_TOP) return 0.0
        frame(up, debrisEast, debrisNorth)
        storms.supercellsAround(up, debrisEast, debrisNorth, time, debrisCells)
        var most = 0.0
        for (n in 0 until debrisCells.size) {
            val s = debrisCells[n]
            for (w in 0 until 2) {
                if (!supercellVortex(s, w, time, v)) continue
                val ring = DEBRIS_CORES * v.core
                val d = v.centre.distanceTo(up) * bodyRadius
                if (d >= ring) continue
                ground(v)
                if (v.water >= 0.5) continue
                val k = v.strength * (1.0 - v.water) * (1.0 - smooth(0.6 * ring, ring, d)) * (1.0 - smooth(DEBRIS_LOW, DEBRIS_TOP, agl))
                most = max(most, k)
            }
        }
        return most.coerceAtMost(1.0)
    }

    /**
     * Whether [debrisAt] could be over 0 at unit [up] at any time from [from] to [to]: some
     * supercell round it has a tornado window then. Never false where [debrisAt] isn't 0.
     */
    fun debrisMaybe(up: Vec3, from: Double, to: Double): Boolean {
        if (!active || !enabled || to < from) return false
        frame(up, debrisEast, debrisNorth)
        storms.supercellsAroundBetween(up, debrisEast, debrisNorth, from, to, debrisCells)
        val window = (WINDOW_TO - WINDOW_FROM) * Storms.CYCLE / 2.0
        for (n in 0 until debrisCells.size) {
            val s = debrisCells[n]
            val c = s.cycle.toInt()
            for (w in 0 until 2) {
                fun h(k: Int) = Noise.hash(seed + 400 + 16 * w + k, s.cx, s.cy, c)
                if (h(0) >= chance) continue
                val life = LIFE_SHORTEST + (LIFE_LONGEST - LIFE_SHORTEST) * h(1)
                val start = s.start + WINDOW_FROM * Storms.CYCLE + w * window + h(2) * (window - life)
                if (start < to && start + life > from) return true
            }
        }
        return false
    }

    /**
     * The tornadoes alive at [time] whose reach comes within [reach] metres of unit [up], into
     * [out]. Makes new objects, so not for every sample.
     */
    fun near(up: Vec3, time: Double, reach: Double, out: MutableList<LiveTornado>) {
        if (!active) return
        val e = Vec3(); val n = Vec3()
        frame(up, e, n)
        val found = ArrayList<Storms.Storm>()
        storms.supercells(up, e, n, reach + REACH_CORES * CORE_LARGEST, time, found)
        val later = Vortex()
        for (s in found) for (w in 0 until 2) {
            if (!supercellVortex(s, w, time, v)) continue
            if (!supercellVortex(s, w, time + 1.0, later)) later.centre.setTo(v.centre)
            emit(up, reach, later, out)
        }
        if (!hurricanes.active) return
        val live = ArrayList<LiveHurricane>()
        hurricanes.near(up, time, reach, live)
        val slot = floor(time / BAND_SLOT).toLong()
        for (h in live) {
            val b = seenBand.setTo(h)
            for (k in slot - 1..slot) for (i in 0 until BAND_TRIES) {
                if (!bandVortex(b, k, i, time, v)) continue
                // Its hurricane's own track over that second is too small to matter.
                if (!bandVortex(b, k, i, time + 1.0, later)) later.centre.setTo(v.centre)
                emit(up, reach, later, out)
            }
        }
    }

    /** Lists [v] if it reaches within [reach] of [up], moving to where [later] is a second on. */
    private fun emit(up: Vec3, reach: Double, later: Vortex, out: MutableList<LiveTornado>) {
        if (v.centre.distanceTo(up) * bodyRadius > reach + v.reach) return
        ground(v)
        val velocity = later.centre.copy().subInPlace(v.centre).mulInPlace(bodyRadius)
        velocity.addScaledInPlace(v.centre, -(velocity dot v.centre))
        out.add(LiveTornado(v.id, v.centre.copy(), v.core, v.reach, v.strength, v.water > 0.5, v.age, v.life, velocity, v.turn, v.inHurricane))
    }

    companion object {
        /** How strong it is through its life, 0..1: touching down, then roping out at the end. */
        fun lifeShape(f: Double): Double = smooth(0.0, 0.12, f) * (1.0 - smooth(ROPE, 1.0, f))

        /** How much of its width is left at [f] of its life: thinning to a rope at the end. */
        fun shrink(f: Double): Double = 1.0 - 0.6 * smooth(ROPE - 0.1, 1.0, f)

        /** The core's wind at full strength, in m/s, and its updraught. */
        const val MAX_WIND = 100.0
        const val CORE_LIFT = 60.0

        /** Debris flies within this many core radii of the middle, thinning from [DEBRIS_LOW] to [DEBRIS_TOP] metres up. */
        const val DEBRIS_CORES = 3.0
        const val DEBRIS_LOW = 80.0
        const val DEBRIS_TOP = 350.0

        /** The least [country] there is, so the worst land still sees one now and then. */
        const val COUNTRY_LEAST = 0.06
        const val COUNTRY_SLOTS = 64

        /** Land gets fewer from [HIGH_FROM] to [HIGH_FULL] Earth metres up, and rising and falling [RELIEF_FROM] to [RELIEF_FULL] within [RELIEF_REACH] metres. */
        const val HIGH_FROM = 700.0
        const val HIGH_FULL = 1_800.0
        const val RELIEF_FROM = 250.0
        const val RELIEF_FULL = 900.0
        const val RELIEF_REACH = 4_000.0

        /** Terra: the tropics' share, and moisture from [DRY] to [MOIST]. */
        const val TROPICS = 0.3
        const val DRY = 0.28
        const val MOIST = 0.45

        /** Ground results are kept per this many metres, in this many slots. */
        const val GROUND_CELL = 100.0
        const val GROUND_SLOTS = 64

        /** How far out the shore is looked for, in metres. */
        const val SHORE_RING = 3_000.0
        private val RING_COS = doubleArrayOf(1.0, 0.5, -0.5, -1.0, -0.5, 0.5)
        private val RING_SIN = doubleArrayOf(0.0, 0.8660254037844386, 0.8660254037844386, 0.0, -0.8660254037844386, -0.8660254037844386)

        /** How strong a waterspout is next to a tornado on land. */
        const val SPOUT = 0.35

        /** The chance of one in each half of a supercell's mature stage at [WeatherIntensity.rare] 1. */
        const val SUPERCELL_CHANCE = 0.75

        /** The mature stage it touches down in, as shares of the storm's cycle. */
        const val WINDOW_FROM = 0.28
        const val WINDOW_TO = 0.78

        /** How long one lasts, in seconds. */
        const val LIFE_SHORTEST = 180.0
        const val LIFE_LONGEST = 540.0

        /** Its core's radius at the ground, in metres. */
        const val CORE_SMALLEST = 50.0
        const val CORE_LARGEST = 300.0

        /** How far it wanders off its place under the storm, in metres, and how long one loop takes. */
        const val WANDER = 150.0
        const val WANDER_SHORTEST = 60.0
        const val WANDER_SPREAD = 90.0

        /** Where it starts to rope out, as a share of its life. */
        const val ROPE = 0.75

        /** How far its wind reaches, in core radii, fading out from [EDGE] of that. */
        const val REACH_CORES = 15.0
        const val EDGE = 0.35

        /** How much it widens with height: by its own width per this many metres. */
        const val WIDEN_HEIGHT = 2_500.0

        /** Its wind fades out from [FADE_FROM] to [TOP] metres above the ground. */
        const val FADE_FROM = 1_500.0
        const val TOP = 5_000.0

        /** Air pulled in low down, as a share of the wind round it, gone from [INFLOW_FROM] to [INFLOW_TO] metres up. */
        const val INFLOW = 0.4
        const val INFLOW_FROM = 100.0
        const val INFLOW_TO = 600.0

        /** A hurricane's band tornadoes: a slot's length in seconds, tries per slot and the chance of each. */
        const val BAND_SLOT = 600.0
        const val BAND_TRIES = 4
        const val BAND_CHANCE = 0.6

        /** How much band there must be where one forms, 0..1. */
        const val BAND_NEEDS = 0.35

        /** How fast a band carries one round the eye, in m/s. */
        const val BAND_SPIN = 25.0

        /** A band tornado's life in seconds and core radius in metres. */
        const val BAND_LIFE_SHORTEST = 120.0
        const val BAND_LIFE_LONGEST = 480.0
        const val BAND_CORE_SMALLEST = 40.0
        const val BAND_CORE_LARGEST = 160.0

        /** How much weaker one is on a launch site, fading out from [SITE_NEAR] to [SITE_FAR] metres away. */
        const val SITE_SHY = 0.5
        const val SITE_NEAR = 3_000.0
        const val SITE_FAR = 20_000.0
    }
}
