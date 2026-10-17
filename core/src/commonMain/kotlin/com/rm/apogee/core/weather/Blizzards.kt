package com.rm.apogee.core.weather

import com.rm.apogee.core.lruMapOf
import com.rm.apogee.core.math.StrictMath
import com.rm.apogee.core.math.Vec3
import com.rm.apogee.core.orbit.SolarSystem
import com.rm.apogee.core.sea.DetMath
import com.rm.apogee.core.terrain.EarthRelief
import com.rm.apogee.core.terrain.Noise
import com.rm.apogee.core.terrain.SurfaceMaterial
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

/** One blizzard as it is at one moment, for drawing, the map and warnings. */
class LiveBlizzard(
    /** Stable for its whole life. */
    val id: Long,
    /** Unit, body-fixed: its middle now, and where it began, under its storm. */
    val centre: Vec3,
    val origin: Vec3,
    /** Unit, tangent at [centre]: the way it's going. */
    val heading: Vec3,
    /** How fast it moves, m/s. */
    val speed: Double,
    /** Half its length along [heading] and half its width across it, in metres. */
    val halfLength: Double,
    val halfWidth: Double,
    /** How high its falling snow reaches over the ground, in metres. */
    val height: Double,
    /** How far from [centre] it reaches along the surface, in metres. */
    val reach: Double,
    /** 0..1, through its life, before the ground and launch sites. */
    val strength: Double,
    /** Seconds since it began, and how long it lasts. */
    val age: Double,
    val life: Double,
) {
    /** How far into it unit [p] is, as a share of the way out to its edge (1 at the edge, past 1 outside). */
    fun inside(p: Vec3, radius: Double): Double {
        val rel = Vec3().setTo(p).mulInPlace(radius).addScaledInPlace(centre, -radius)
        rel.addScaledInPlace(centre, -(rel dot centre))
        val right = Vec3().setTo(heading).crossInPlace(centre)
        val a = (rel dot heading) / halfLength
        val c = (rel dot right) / halfWidth
        return sqrt(a * a + c * c)
    }
}

/**
 * Blizzards: snow driven by strong wind over snow, ice and cold land.
 *
 * - Storm blizzards: a strong storm over cold country ([CHANCE] x [WeatherIntensity.rare] in its
 *   cell per cycle, likelier the colder the cell) turns into a blizzard, an oval of heavy falling
 *   snow and strong gusty wind tens of kilometres across, carried along its storm's track for two
 *   to five hours. Over lying snow the wind lifts it too, so near the ground you see 60-100 m.
 * - Ground blizzards: on windy days the free wind past [GROUND_FROM] lifts lying snow without any
 *   storm, a low white layer with clear sky above. Full on Extreme, [GROUND_WILD] as much on Wild.
 *
 * None on Calm or Normal. Snow falls only where it's cold ([coldness]): on Terra lying snow and
 * ice, high latitudes and high ground; elsewhere snow, ice and frost. It's lifted only off loose
 * snow ([snowiness]). Up to [SITE_SHY] weaker near launch sites. All hashed from the seed, the
 * storms and the time.
 *
 * Blizzards near a sample are listed once per [SLOT] and [MOVE] for up to [PLACES] places (a
 * superset, sorted by id, so the answer doesn't depend on what was asked before); with none near
 * and no strong wind, a sample costs a couple of comparisons. Not thread-safe: one per [Weather].
 */
internal class Blizzards(
    private val weather: Weather,
    private val bodyRadius: Double,
    seed: Int,
    private val intensity: WeatherIntensity,
    private val storms: Storms,
) {
    private val climate = weather.climate
    private val terrain = weather.body.terrain
    private val seed = seed + 0xB112
    private val terra = weather.body.id == SolarSystem.HOMEWORLD_ID
    private val hasGround = terrain != null && climate.ground && climate.circulation != Climate.Circulation.STILL
    private val heightScale = bodyRadius / EarthRelief.EARTH_RADIUS

    /** Storm blizzards at all: a world whose storms snow. */
    val stormy = hasGround && intensity.rare > 0.0 && intensity.storms > 0.0 && climate.storms > 0.0 &&
        climate.stormCloud == CloudType.CUMULONIMBUS && climate.precipitation != Climate.Precipitation.NONE

    /** How much snow windy days lift, 0 for none. */
    private val ground = if (!hasGround) 0.0 else when (intensity) {
        WeatherIntensity.EXTREME -> 1.0
        WeatherIntensity.WILD -> GROUND_WILD
        else -> 0.0
    }

    val active = stormy || ground > 0.0

    /** Off in tests, to compare the air with and without blizzards, and with and without launch sites. */
    internal var enabled = true
    internal var shySites = true

    private val chance = CHANCE * intensity.rare

    // --- cold and snowy ground --------------------------------------------------------

    private val sites: List<Vec3> by lazy {
        val out = ArrayList<Vec3>()
        for (site in com.rm.apogee.core.world.World.launchSites) {
            if (site.bodyId != weather.body.id) continue
            val c = DetMath.cos(site.latitude)
            val d = Vec3(c * DetMath.cos(site.longitude), DetMath.sin(site.latitude), c * DetMath.sin(site.longitude))
            if (out.none { it.distanceTo(d) * bodyRadius < 2_000.0 }) out.add(d)
        }
        out
    }

    /** How much of the snow is left at unit [p] for the launch sites near it: 1 far off, 1 - [SITE_SHY] on one. */
    internal fun siteShare(p: Vec3): Double {
        if (!shySites) return 1.0
        var most = 0.0
        for (d in sites) most = max(most, 1.0 - smooth(SITE_NEAR, SITE_FAR, d.distanceTo(p) * bodyRadius))
        return 1.0 - SITE_SHY * most
    }

    private fun materialAt(p: Vec3, elevation: Double): SurfaceMaterial? {
        val t = terrain ?: return null
        val m = t.material(p, elevation, 0.0)
        val icy = m == SurfaceMaterial.SNOW || m == SurfaceMaterial.ICE || m == SurfaceMaterial.FROST || m == SurfaceMaterial.NITROGEN_ICE
        // Open sea; sea ice is ice.
        if (t.hasOcean && elevation < 0.5 && !icy) return null
        return m
    }

    /** How cold the ground at unit [p] is, 0..1: 1 on snow and ice, on Terra also far north or south and high up; 0 over open sea. */
    internal fun coldness(p: Vec3): Double {
        val t = terrain ?: return 0.0
        val elevation = t.elevation(p)
        val m = materialAt(p, elevation) ?: return 0.0
        when (m) {
            SurfaceMaterial.SNOW, SurfaceMaterial.ICE, SurfaceMaterial.FROST, SurfaceMaterial.NITROGEN_ICE -> return 1.0
            else -> {}
        }
        if (!terra) return 0.0
        val latitude = kotlin.math.abs(StrictMath.atan2(p.y, sqrt(p.x * p.x + p.z * p.z))) * (180.0 / kotlin.math.PI)
        val high = smooth(TERRA_HIGH_FROM, TERRA_HIGH_FULL, elevation / heightScale)
        return max(smooth(TERRA_COLD_FROM, TERRA_COLD_FULL, latitude), high)
    }

    /** How much loose snow the ground at unit [p] has for the wind to lift, 0..1. */
    internal fun snowiness(p: Vec3): Double {
        val t = terrain ?: return 0.0
        val elevation = t.elevation(p)
        return when (materialAt(p, elevation)) {
            SurfaceMaterial.SNOW -> 1.0
            SurfaceMaterial.FROST -> 0.8
            // Rubra's caps are frost all through; elsewhere ice, scoured, with snow blown over it.
            SurfaceMaterial.ICE -> if (climate.precipitation == Climate.Precipitation.NONE) 0.8 else 0.55
            SurfaceMaterial.NITROGEN_ICE -> 0.6
            else -> 0.0
        }
    }

    private val cold = Lattice { coldness(it).let { c -> if (c > 0.0) c * siteShare(it) else 0.0 } }
    private val snowy = Lattice { snowiness(it).let { c -> if (c > 0.0) c * siteShare(it) else 0.0 } }

    // The ground's own snowiness per [OWN_CELL] node, direct-mapped.
    private val ownKey = LongArray(OWN_SLOTS) { Long.MIN_VALUE }
    private val ownValue = DoubleArray(OWN_SLOTS)
    private val ownAt = Vec3()

    /** [snowiness] at the [OWN_CELL] lattice node nearest unit [p]: the ground's own, without blending. */
    internal fun ownSnowiness(p: Vec3): Double {
        val ix = floor(p.x * bodyRadius / OWN_CELL + 0.5).toLong()
        val iy = floor(p.y * bodyRadius / OWN_CELL + 0.5).toLong()
        val iz = floor(p.z * bodyRadius / OWN_CELL + 0.5).toLong()
        val key = ((ix + OFFSET) shl 42) or ((iy + OFFSET) shl 21) or (iz + OFFSET)
        val slot = (mix(key) and (OWN_SLOTS - 1).toLong()).toInt()
        if (ownKey[slot] == key) return ownValue[slot]
        ownAt.setTo(ix * OWN_CELL, iy * OWN_CELL, iz * OWN_CELL)
        val v = if (ownAt.lengthSq < 1.0) 0.0 else snowiness(ownAt.normalizeInPlace())
        ownKey[slot] = key; ownValue[slot] = v
        return v
    }

    /** How cold the country round unit [p] is, 0..1, smooth: none over open sea. */
    fun coldAround(p: Vec3): Double = cold.blend(p)

    /** How much snow the wind lifts off the ground at unit [p], 0..1: smooth, but none past the snow's own edge. */
    fun snowCover(p: Vec3): Double {
        val blended = snowy.blend(p)
        if (blended <= 0.0) return 0.0
        val own = ownSnowiness(p)
        return if (own <= 0.0) 0.0 else min(blended, own * siteShare(p))
    }

    /**
     * A value of unit direction blended over a [CELL] lattice in body-fixed metres between the 8
     * corners; corners kept direct-mapped, and the last cell's corners kept.
     */
    private inner class Lattice(private val value: (Vec3) -> Double) {
        private val keys = LongArray(SLOTS) { Long.MIN_VALUE }
        private val values = DoubleArray(SLOTS)
        private val corner = Vec3()
        private val last = DoubleArray(8)
        private var lastX = 0L
        private var lastY = 0L
        private var lastZ = 0L
        private var lastSet = false

        private fun at(ix: Long, iy: Long, iz: Long): Double {
            val key = ((ix + OFFSET) shl 42) or ((iy + OFFSET) shl 21) or (iz + OFFSET)
            val slot = (mix(key) and (SLOTS - 1).toLong()).toInt()
            if (keys[slot] == key) return values[slot]
            corner.setTo(ix * CELL, iy * CELL, iz * CELL)
            val v = if (corner.lengthSq < 1.0) 0.0 else value(corner.normalizeInPlace())
            keys[slot] = key; values[slot] = v
            return v
        }

        fun blend(p: Vec3): Double {
            if (!hasGround) return 0.0
            val x = p.x * bodyRadius / CELL; val y = p.y * bodyRadius / CELL; val z = p.z * bodyRadius / CELL
            val fx = floor(x); val fy = floor(y); val fz = floor(z)
            val ix = fx.toLong(); val iy = fy.toLong(); val iz = fz.toLong()
            val tx = x - fx; val ty = y - fy; val tz = z - fz
            if (!(lastSet && ix == lastX && iy == lastY && iz == lastZ)) {
                last[0] = at(ix, iy, iz); last[1] = at(ix + 1, iy, iz)
                last[2] = at(ix, iy + 1, iz); last[3] = at(ix + 1, iy + 1, iz)
                last[4] = at(ix, iy, iz + 1); last[5] = at(ix + 1, iy, iz + 1)
                last[6] = at(ix, iy + 1, iz + 1); last[7] = at(ix + 1, iy + 1, iz + 1)
                lastX = ix; lastY = iy; lastZ = iz; lastSet = true
            }
            val a = (last[0] + (last[1] - last[0]) * tx) * (1.0 - ty) + (last[2] + (last[3] - last[2]) * tx) * ty
            val b = (last[4] + (last[5] - last[4]) * tx) * (1.0 - ty) + (last[6] + (last[7] - last[6]) * tx) * ty
            return a * (1.0 - tz) + b * tz
        }
    }

    // How cold each storm cell's middle is, direct-mapped.
    private val cellKey = LongArray(CELL_SLOTS) { Long.MIN_VALUE }
    private val cellCold = DoubleArray(CELL_SLOTS)
    private val cellCentre = Vec3()

    private fun cellColdness(key: Long): Double {
        val slot = (mix(key) and (CELL_SLOTS - 1).toLong()).toInt()
        if (cellKey[slot] == key) return cellCold[slot]
        val v = coldness(cells.centre(key, cellCentre))
        cellKey[slot] = key; cellCold[slot] = v
        return v
    }

    // --- storm blizzards --------------------------------------------------------------

    internal class Blizzard(
        val id: Long,
        val origin: Vec3,
        /** Unit, tangent at [origin]. */
        val forward: Vec3,
        val right: Vec3,
        val start: Double,
        val life: Double,
        val speed: Double,
        val halfLength: Double,
        val halfWidth: Double,
        val height: Double,
        val strength: Double,
    ) {
        /** How much of it there is at [age], 0..1: growing, then dying away. */
        fun life(age: Double): Double = smooth(0.0, GROW, age) * (1.0 - smooth(FADE * life, life, age))
    }

    private val cells = SphereCells(bodyRadius, Storms.CELL)
    private val built = lruMapOf<Long, Any>(64, 4_000)
    private val stormCentre = Vec3()
    private val stormTrack = Vec3()
    private val stormRight = Vec3()

    /** Cell [key]'s blizzard from its storm in [cycle], or null if it has none. */
    internal fun blizzard(key: Long, cycle: Long): Blizzard? {
        val cx = cells.hashX(key); val cy = cells.hashY(key); val c = cycle.toInt()
        fun h(k: Int) = Noise.hash(seed + k, cx, cy, c)
        val roll = h(0)
        if (roll >= chance * 1.2) return null
        val cacheKey = key * 1_000_003L + cycle
        built[cacheKey]?.let { return it as? Blizzard }
        val made = make(key, cycle, roll, ::h)
        built[cacheKey] = made ?: NONE
        return made
    }

    private fun make(key: Long, cycle: Long, roll: Double, h: (Int) -> Double): Blizzard? {
        val coldCell = smooth(0.25, 0.7, cellColdness(key))
        if (coldCell <= 0.0) return null
        val s = storms.stormIn(key, cycle)
        if (!s.exists || s.strength < 0.4) return null
        val kindWeight = when (s.kind) {
            StormKind.SINGLE -> 0.6
            StormKind.MULTICELL, StormKind.SUPERCELL -> 1.0
            StormKind.SQUALL -> 1.2
        }
        if (roll >= chance * kindWeight * coldCell) return null
        val start = s.start + (START_FROM + START_SPREAD * h(1)) * Storms.CYCLE
        // Not from a storm a hurricane has taken over.
        if (storms.envelope(s, start) < 0.25) return null
        storms.frameAt(s, start, stormCentre, stormTrack, stormRight)
        val origin = stormCentre.copy()
        val forward = stormTrack.copy()
        val right = Vec3().setTo(forward).crossInPlace(origin).normalizeInPlace()
        val id = (((s.cx.toLong() shl 42) xor (s.cy.toLong() shl 21) xor (s.cycle and 0x1fffffL))) and Long.MAX_VALUE
        val u = h(5)
        val length = LENGTH_SHORTEST + (LENGTH_LONGEST - LENGTH_SHORTEST) * h(6)
        return Blizzard(
            id = id, origin = origin, forward = forward, right = right, start = start,
            life = LIFE_SHORTEST + (LIFE_LONGEST - LIFE_SHORTEST) * u,
            speed = s.steer.length.coerceIn(SPEED_SLOWEST, SPEED_FASTEST),
            halfLength = length,
            halfWidth = length * (0.55 + 0.35 * h(7)),
            height = HEIGHT_LOWEST + (HEIGHT_TALLEST - HEIGHT_LOWEST) * h(8),
            strength = (0.6 + 0.4 * h(9)) * (0.6 + 0.4 * s.strength),
        )
    }

    /** How far from a blizzard's origin anything of it reaches at [age], in metres. */
    private fun extent(b: Blizzard, age: Double): Double = b.speed * min(age, b.life) + b.halfLength + EDGE_MARGIN

    /** The most metres a blizzard can reach from the middle of its storm's cell. */
    private val farthest: Double =
        SPEED_FASTEST * LIFE_LONGEST + LENGTH_LONGEST + EDGE_MARGIN + 0.75 * Storms.CELL + Storms.MAX_STEER * Storms.CYCLE + 25_000.0

    private var searchKeys = LongArray(0)
    private val searchE = Vec3()
    private val searchN = Vec3()
    private val rel = Vec3()

    /**
     * The blizzards alive at some time in [from]..[to] that may reach within [reach] metres of unit
     * [up] then, into [out], sorted by id. A superset: [blow] still checks.
     */
    internal fun blizzardsNear(up: Vec3, from: Double, to: Double, reach: Double, out: MutableList<Blizzard>) {
        out.clear()
        if (!stormy) return
        val cellWidth = 0.5 * kotlin.math.PI * bodyRadius / cells.perFace
        val radiusCells = ceil((min(reach, 4.0 * bodyRadius) + farthest) / (0.7 * cellWidth)).toInt() + 1
        val whole = 2 * radiusCells + 1 >= cells.perFace
        val n: Int
        if (whole) {
            n = 6 * cells.perFace * cells.perFace
        } else {
            frame(up, searchE, searchN)
            val size = (4 * radiusCells + 3) * (4 * radiusCells + 3) + (2 * radiusCells + 1) * (2 * radiusCells + 1) + 16
            if (searchKeys.size < size) searchKeys = LongArray(size)
            n = cells.around(up, searchE, searchN, cellWidth / bodyRadius, searchKeys, reach = radiusCells)
        }
        for (k in 0 until n) {
            val key = if (whole) k.toLong() else searchKeys[k]
            val last = storms.cycleAt(key, to)
            val first = storms.cycleAt(key, from - LIFE_LONGEST - (START_FROM + START_SPREAD) * Storms.CYCLE) - 1
            for (c in first..last) {
                val b = blizzard(key, c) ?: continue
                if (b.start > to || b.start + b.life < from) continue
                if (reach < bodyRadius && (b.origin dot up) < 0.3) continue
                rel.setTo(up).mulInPlace(bodyRadius).addScaledInPlace(b.origin, -bodyRadius)
                rel.addScaledInPlace(b.origin, -(rel dot b.origin))
                if (rel.length > reach + extent(b, to - b.start)) continue
                out.add(b)
            }
        }
        out.sortBy { it.id }
    }

    // Blizzards listed for samples near a few places, each for one [SLOT], so several craft far
    // apart sampled in turn don't relist every time. Each list is a superset sorted by id, so which
    // one a sample uses doesn't change its answer.
    private class Listing {
        val blizzards = ArrayList<Blizzard>()
        val up = Vec3()
        var from = Double.NaN
        var to = Double.NaN
    }

    private val listings = Array(PLACES) { Listing() }
    private var nextListing = 0

    /** How many times a list has been made, for tests. */
    internal var relisted = 0L

    private fun listFor(up: Vec3, time: Double): ArrayList<Blizzard> {
        for (l in listings) {
            if (l.from == l.from && time >= l.from && time <= l.to && l.up.distanceTo(up) * bodyRadius <= MOVE) return l.blizzards
        }
        val l = listings[nextListing]
        nextListing = (nextListing + 1) % PLACES
        val slot = floor(time / SLOT)
        l.from = slot * SLOT
        l.to = l.from + SLOT
        l.up.setTo(up)
        relisted++
        blizzardsNear(up, l.from, l.to, MOVE, l.blizzards)
        return l.blizzards
    }

    /**
     * Whether a storm blizzard may reach unit [up] within [look] seconds of [time]: one alive then
     * that comes near. Quick; may say yes a little early, never no. Windy days' lifted snow isn't
     * counted.
     */
    internal fun dueNear(up: Vec3, time: Double, look: Double): Boolean {
        if (!stormy) return false
        val to = time + look
        var t = time
        while (true) {
            for (b in listFor(up, t)) if (b.start <= to && b.start + b.life >= time) return true
            if (t >= to) return false
            t = minOf(t + SLOT, to)
        }
    }

    /** What the blizzards do to a sample, before the ground. */
    private var bFall = 0.0
    private var bLift = 0.0
    private var bWind = 0.0
    private var bTurbulence = 0.0
    private var bTop = 0.0

    /**
     * Adds [b]'s falling snow, the share of it the wind can lift, wind and rough air at unit [up]
     * (the ground under the sample), [agl] up. Nothing outside its oval.
     */
    private fun blow(b: Blizzard, up: Vec3, agl: Double, time: Double) {
        val age = time - b.start
        if (age <= 0.0 || age >= b.life) return
        if ((b.origin dot up) < 0.3) return
        if (agl > TOP_SHARE * b.height) return
        rel.setTo(up).mulInPlace(bodyRadius).addScaledInPlace(b.origin, -bodyRadius)
        val along = (rel dot b.forward) - b.speed * age
        val across = rel dot b.right
        val a = along / b.halfLength
        val c = across / b.halfWidth
        val q2 = a * a + c * c
        if (q2 >= 1.0) return
        val q = sqrt(q2)
        // A sharper edge ahead, where it comes in, than behind, where it clears.
        val inner = EDGE_BEHIND + (EDGE_AHEAD - EDGE_BEHIND) * smooth(-0.3, 0.3, a)
        val s = b.strength * b.life(age) * (1.0 - smooth(inner, 1.0, q))
        if (s <= 0.0) return
        bFall = 1.0 - (1.0 - bFall) * (1.0 - s * (1.0 - smooth(0.6 * b.height, TOP_SHARE * b.height, agl)))
        bLift = max(bLift, s)
        bTop = max(bTop, 0.8 * b.height)
        val windHeight = 1.0 - smooth(0.5 * b.height, TOP_SHARE * b.height, agl)
        bWind = min(1.0, bWind + s * windHeight)
        bTurbulence += TURBULENCE * s * windHeight
    }

    // --- the sample -------------------------------------------------------------------

    /**
     * Adds the blizzards' wind and rough air to [out] at [up] (unit, body-fixed), [agl] metres above
     * the ground, and their snow ([AirSample.snow], [AirSample.snowfall]) and sight range.
     * [freeWind] is the circulation's wind speed there (before height and the ground), [dir] its
     * direction.
     */
    fun apply(up: Vec3, agl: Double, freeWind: Double, dir: Vec3, time: Double, out: AirSample) {
        if (!active || !enabled || agl > TOP) return
        bFall = 0.0; bLift = 0.0; bWind = 0.0; bTurbulence = 0.0; bTop = 0.0
        if (stormy) {
            val listed = listFor(up, time)
            for (i in 0 until listed.size) blow(listed[i], up, agl, time)
        }
        val windy = ground > 0.0 && freeWind > GROUND_FROM && agl < GROUND_TOP
        if (bFall <= 0.0 && bWind <= 0.0 && !windy) return

        val cover = snowCover(up)
        // Ground blizzards: lying snow lifted by the wind, low. Gusts from the snow's smooth
        // spread, so they don't step where the snow's own edge cuts the drift off.
        var drift = 0.0
        var driftGusts = 0.0
        var driftTop = 0.0
        val spread = if (windy) snowy.blend(up) else 0.0
        if (spread > 0.0) {
            val windiness = smooth(GROUND_FROM, GROUND_FULL, freeWind)
            val top = GROUND_LOWEST + (GROUND_TOP - GROUND_LOWEST) * windiness
            if (agl < top) {
                val k = bodyRadius / 20_000.0
                val t = time / 1_800.0
                val n = Noise.simplex(seed + 950, up.x * k + t, up.y * k, up.z * k - t * 0.6)
                val d = GROUND_DENSITY * ground * windiness * (0.6 + 0.4 * n) * (1.0 - smooth(0.3 * top, top, agl))
                drift = d * cover
                driftGusts = d * spread
                driftTop = DRIFT_DRAWN * top
            }
        }
        if (bFall <= 0.0 && bWind <= 0.0 && drift <= 0.0 && driftGusts <= 0.0) return

        val coldHere = if (bFall > 0.0 || bWind > 0.0) cold.blend(up) else 0.0
        // Snow falls only where it's cold; the wind blows on, weaker, where it isn't.
        val fall = FALL_DENSITY * bFall * coldHere
        val lifted = LIFT_DENSITY * bLift * cover * (1.0 - smooth(LIFT_LOW, LIFT_TOP, agl))
        val snow = 1.0 - (1.0 - fall) * (1.0 - lifted) * (1.0 - drift)
        // Its wind brings the wind along the free wind's way up toward [WIND], never past.
        if (bWind > 0.0) {
            val along = out.wind dot dir
            val target = WIND * bWind * (0.3 * siteShare(up) + 0.7 * coldHere)
            if (target > along) out.wind.addScaledInPlace(dir, target - along)
        }
        // Gusts from the snow's smooth spread, so they don't step at its edge.
        out.turbulence += bTurbulence * (0.3 + 0.7 * coldHere) + GROUND_GUSTS * driftGusts
        if (snow > 0.0) {
            val top = max(if (fall > 0.0) bTop else 0.0, max(if (lifted > 0.0) LIFT_DRAWN else 0.0, driftTop))
            if (snow >= out.snow) out.snowTop = top
            out.snow = max(out.snow, snow)
            out.snowfall = max(out.snowfall, fall)
            out.visibility = min(out.visibility, SNOW_SIGHT / snow)
        }
    }

    // --- listing ----------------------------------------------------------------------

    private val listing = ArrayList<Blizzard>()

    /**
     * The storm blizzards alive at [time] that come within [reach] metres of unit [up], into [out].
     * Makes new objects, so not for every sample.
     */
    fun near(up: Vec3, time: Double, reach: Double, out: MutableList<LiveBlizzard>) {
        if (!stormy) return
        blizzardsNear(up, time, time, reach, listing)
        for (b in listing) {
            val age = time - b.start
            if (age <= 0.0 || age >= b.life) continue
            val centre = Vec3().setTo(b.origin).mulInPlace(bodyRadius).addScaledInPlace(b.forward, b.speed * age).normalizeInPlace()
            val r = max(b.halfLength, b.halfWidth)
            if (centre.distanceTo(up) * bodyRadius > reach + r) continue
            val heading = Vec3().setTo(b.forward).addScaledInPlace(centre, -(b.forward dot centre)).normalizeInPlace()
            out.add(LiveBlizzard(b.id, centre, b.origin.copy(), heading, b.speed, b.halfLength, b.halfWidth,
                b.height, r, b.strength * b.life(age), age, b.life))
        }
    }

    companion object {
        private val NONE = Any()

        private fun mix(key: Long): Long {
            var h = key xor (key ushr 33)
            h *= -0xae502812aa7333L
            h = h xor (h ushr 33)
            h *= -0x3b314601e57a13adL
            return h xor (h ushr 33)
        }

        private const val OFFSET = 1L shl 20

        /** The chance of a blizzard from a strong storm over cold country per cycle at [WeatherIntensity.rare] 1. */
        const val CHANCE = 0.025

        /** When in its storm's cycle it starts, as a share of the cycle. */
        const val START_FROM = 0.25
        const val START_SPREAD = 0.2

        /** How long one lasts, in seconds; growing over [GROW], fading from [FADE] of its life. */
        const val LIFE_SHORTEST = 2.0 * 3_600.0
        const val LIFE_LONGEST = 5.0 * 3_600.0
        const val GROW = 1_800.0
        const val FADE = 0.75

        /** How fast it moves, m/s: its storm's steering, kept to this. */
        const val SPEED_SLOWEST = 3.0
        const val SPEED_FASTEST = 12.0

        /** Half its length along its way, metres; half its width is 0.55-0.9 of that. */
        const val LENGTH_SHORTEST = 15_000.0
        const val LENGTH_LONGEST = 35_000.0

        /** How high its snow falls from, metres over the ground; nothing above [TOP_SHARE] of it. */
        const val HEIGHT_LOWEST = 2_000.0
        const val HEIGHT_TALLEST = 3_500.0
        const val TOP_SHARE = 1.2

        /** Where its edge starts, as a share of the way out: ahead, and behind. */
        const val EDGE_AHEAD = 0.75
        const val EDGE_BEHIND = 0.45

        private const val EDGE_MARGIN = 4_000.0

        /** Its wind at full strength along the free wind, m/s (what's there already counts), and rough air. */
        const val WIND = 30.0
        const val TURBULENCE = 0.55

        /** Falling snow at full strength, and snow the wind lifts in it under [LIFT_LOW] m, gone by [LIFT_TOP]. */
        const val FALL_DENSITY = 0.6
        const val LIFT_DENSITY = 0.7
        const val LIFT_LOW = 40.0
        const val LIFT_TOP = 400.0
        /** Snow drifting off the ground drawn as an even layer this share of how high it goes: most of it is low. */
        const val DRIFT_DRAWN = 0.15
        /** Lifted snow drawn as an even layer this deep, metres. */
        const val LIFT_DRAWN = 150.0

        /** Ground blizzards: the free wind they start at and are full at, m/s, how thick at most, how high, and on Wild. */
        const val GROUND_FROM = 24.0
        const val GROUND_FULL = 42.0
        const val GROUND_DENSITY = 0.55
        const val GROUND_LOWEST = 40.0
        const val GROUND_TOP = 300.0
        const val GROUND_WILD = 0.4
        const val GROUND_GUSTS = 0.6

        /** Nothing above this many metres over the ground. */
        const val TOP = HEIGHT_TALLEST * TOP_SHARE

        /** How far you see in snow as thick as it gets, metres. Thinner snow, further. */
        const val SNOW_SIGHT = 60.0

        /** Terra: cold from these latitudes, degrees, and from this high, Earth metres. */
        const val TERRA_COLD_FROM = 50.0
        const val TERRA_COLD_FULL = 64.0
        const val TERRA_HIGH_FROM = 2_800.0
        const val TERRA_HIGH_FULL = 4_200.0

        /** The cold and snow lattices in metres, and slots kept. */
        const val CELL = 3_000.0
        const val SLOTS = 512
        const val CELL_SLOTS = 1_024

        /** Blizzards near a sample listed once per this many seconds and place this many metres across, for up to [PLACES] places. */
        const val SLOT = 600.0
        const val MOVE = 5_000.0
        const val PLACES = 6

        /** The ground's own snow kept per node this many metres apart, in this many slots. */
        const val OWN_CELL = 20.0
        const val OWN_SLOTS = 256

        /** How much weaker near a launch site, fading out from [SITE_NEAR] to [SITE_FAR] metres away. */
        const val SITE_SHY = 0.4
        const val SITE_NEAR = 20_000.0
        const val SITE_FAR = 40_000.0
    }
}
