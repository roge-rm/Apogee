package com.rm.apogee.core.weather

import com.rm.apogee.core.lruMapOf
import com.rm.apogee.core.math.StrictMath
import com.rm.apogee.core.math.Vec3
import com.rm.apogee.core.orbit.SolarSystem
import com.rm.apogee.core.sea.DetMath
import com.rm.apogee.core.terrain.EarthMoisture
import com.rm.apogee.core.terrain.Noise
import com.rm.apogee.core.terrain.SurfaceMaterial
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

/** What kind of dust storm. */
enum class DustKind {
    /** A wall of dust rolling out ahead of a storm's outflow over dry land. */
    HABOOB,
    /** Dust over a whole thin, dusty world for days. */
    WORLD,
}

/** One dust storm as it is at one moment, for drawing, the map and warnings. */
class LiveDust(
    /** Stable for its whole life. */
    val id: Long,
    val kind: DustKind,
    /** Unit, body-fixed: the middle of a haboob's front, or where a world storm began. */
    val centre: Vec3,
    /** Unit, body-fixed: where it began, under its storm for a haboob. */
    val origin: Vec3,
    /** Unit, tangent at [centre]: the way a haboob's wall is going. Zero for a world storm. */
    val heading: Vec3,
    /** How fast a haboob's wall moves, m/s. */
    val speed: Double,
    /** Half a haboob's width across its front, in metres. */
    val halfWidth: Double,
    /** How tall its wall stands over the ground, in metres. */
    val height: Double,
    /** How far from [centre] it reaches along the surface, in metres. */
    val reach: Double,
    /** 0..1, through its life, before the ground and launch sites. */
    val strength: Double,
    /** Seconds since it began, and how long it lasts. */
    val age: Double,
    val life: Double,
) {
    companion object {
        /** How far back a haboob's front bows at its edges, in half widths. */
        const val BOW = DustStorms.BOW
        /** Its dust is thick this many metres behind the face, then thins. */
        const val THICK = DustStorms.THICK
        const val WORLD_LOOK = DustStorms.WORLD_LOOK
    }
}

/**
 * Dust raised by the wind over dry ground.
 *
 * - Haboobs: a strong storm over dry land ([HABOOB_CHANCE] x [WeatherIntensity.rare] in its cell
 *   per cycle, likelier the drier the cell) sends a wall of dust out ahead of its outflow. The wall
 *   starts at the storm's rain shaft as it matures, rolls out along the storm's track at 10-25 m/s,
 *   slowing as it spreads, 1-2 km tall and tens of kilometres wide, with a gust front at its face,
 *   and lasts half an hour to two and a half.
 * - Broad dust: on windy days the free wind over dry ground past [BROAD_FROM] raises a thinner,
 *   wider haze. Full on Extreme, [BROAD_WILD] as much on Wild, none on Calm or Normal.
 * - World storms: on a thin dusty world (storms of dust) a rare storm starts at low latitude,
 *   spreads over the whole world in a day or so and lasts days, with gentler wind and heavy dust.
 *
 * Dust only stands over dry ground ([dustiness]): on Terra from the climate's moisture, deserts
 * first, then steppe; elsewhere from the ground kind (sand, dust, regolith). None over sea, ice,
 * forest or wet ground. Up to [SITE_SHY] weaker near launch sites. All of it is hashed from the
 * seed, the storms and the time, so nothing is stored.
 *
 * Haboobs near a sample are listed once per [SLOT] and [MOVE] (a superset, sorted by id, so the
 * answer doesn't depend on what was asked before); with none near and no wind, a sample costs a
 * couple of comparisons. Not thread-safe: one per [Weather].
 */
internal class DustStorms(
    private val weather: Weather,
    private val bodyRadius: Double,
    seed: Int,
    private val intensity: WeatherIntensity,
    private val storms: Storms,
) {
    private val climate = weather.climate
    private val terrain = weather.body.terrain
    private val seed = seed + 0xD057
    private val terra = weather.body.id == SolarSystem.HOMEWORLD_ID
    private val hasGround = terrain != null && climate.ground

    /** Haboobs at all, at this setting. */
    val haboobs = hasGround && intensity.rare > 0.0 && intensity.storms > 0.0 && climate.storms > 0.0

    /** How much broad dust windy days raise, 0 for none. */
    private val broad = if (!hasGround) 0.0 else when (intensity) {
        WeatherIntensity.EXTREME -> 1.0
        WeatherIntensity.WILD -> BROAD_WILD
        else -> 0.0
    }

    /** World storms at all: a world whose storms are dust. */
    val worldStorms = hasGround && intensity.rare > 0.0 && climate.stormCloud == CloudType.DUST

    val active = haboobs || broad > 0.0 || worldStorms

    /** Off in tests, to compare the air with and without dust storms, and with and without launch sites. */
    internal var enabled = true
    internal var shySites = true

    private val chance = HABOOB_CHANCE * intensity.rare
    private val worldChance = (WORLD_CHANCE * intensity.rare).coerceAtMost(0.95)

    // --- dry ground -------------------------------------------------------------------

    /** The launch sites on this world, unit. */
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

    /** How much of the dust is left at unit [p] for the launch sites near it: 1 far off, 1 - [SITE_SHY] on one. */
    internal fun siteShare(p: Vec3): Double {
        if (!shySites) return 1.0
        var most = 0.0
        for (d in sites) most = max(most, 1.0 - smooth(SITE_NEAR, SITE_FAR, d.distanceTo(p) * bodyRadius))
        return 1.0 - SITE_SHY * most
    }

    /** How dry the ground at unit [p] is, 0..1: 1 for open desert, 0 for sea, ice, forest or wet ground. */
    internal fun dryness(p: Vec3): Double {
        val t = terrain ?: return 0.0
        val elevation = t.elevation(p)
        if (t.hasOcean && elevation < 0.5) return 0.0
        val kind = when (t.material(p, elevation, 0.0)) {
            SurfaceMaterial.SAND, SurfaceMaterial.RED_DUST, SurfaceMaterial.DARK_SAND, SurfaceMaterial.ORGANIC_SAND -> 1.0
            SurfaceMaterial.SALT -> 0.85
            SurfaceMaterial.CLAY, SurfaceMaterial.REGOLITH -> 0.75
            SurfaceMaterial.DIRT, SurfaceMaterial.EJECTA, SurfaceMaterial.THOLIN -> 0.6
            SurfaceMaterial.SCREE -> 0.45
            else -> 0.0
        }
        if (kind <= 0.0 || !terra) return kind
        // Terra by its climate: deserts, then steppe, nothing where it's wet.
        val latitude = StrictMath.atan2(p.y, sqrt(p.x * p.x + p.z * p.z)) * (180.0 / kotlin.math.PI)
        val longitude = StrictMath.atan2(p.z, p.x) * (180.0 / kotlin.math.PI)
        return kind * smooth(TERRA_WET, TERRA_DRY, EarthMoisture.sample(longitude, latitude))
    }

    // Dryness times the launch sites' share, on a [DRY_CELL] lattice in body-fixed metres, blended
    // between the 8 corners; corners kept direct-mapped.
    private val dryKey = LongArray(DRY_SLOTS) { Long.MIN_VALUE }
    private val dryValue = DoubleArray(DRY_SLOTS)
    private val corner = Vec3()

    // The last lattice cell's corners, since samples come in runs from one place.
    private val last = DoubleArray(8)
    private var lastX = 0L
    private var lastY = 0L
    private var lastZ = 0L
    private var lastSet = false

    private fun cornerValue(ix: Long, iy: Long, iz: Long): Double {
        val key = ((ix + OFFSET) shl 42) or ((iy + OFFSET) shl 21) or (iz + OFFSET)
        val slot = (mix(key) and (DRY_SLOTS - 1).toLong()).toInt()
        if (dryKey[slot] == key) return dryValue[slot]
        corner.setTo(ix * DRY_CELL, iy * DRY_CELL, iz * DRY_CELL)
        val v = if (corner.lengthSq < 1.0) 0.0 else {
            corner.normalizeInPlace()
            dryness(corner).let { if (it > 0.0) it * siteShare(corner) else 0.0 }
        }
        dryKey[slot] = key; dryValue[slot] = v
        return v
    }

    /**
     * How much dust the ground at unit [p], [elevation] metres up, gives, 0..1: [blend], but no
     * more than the ground's own ([ownDryness]) times the launch sites' share, so none spills onto
     * ice, forest or wet ground beside a desert.
     */
    fun dustiness(p: Vec3, elevation: Double): Double {
        val blended = blend(p, elevation)
        if (blended <= 0.0) return 0.0
        val own = ownDryness(p)
        return if (own <= 0.0) 0.0 else min(blended, own * siteShare(p))
    }

    // The ground's own dryness per [OWN_CELL] node, direct-mapped.
    private val ownKey = LongArray(OWN_SLOTS) { Long.MIN_VALUE }
    private val ownValue = DoubleArray(OWN_SLOTS)
    private val ownAt = Vec3()

    /** [dryness] at the [OWN_CELL] lattice node nearest unit [p]: the ground's own, without blending. */
    internal fun ownDryness(p: Vec3): Double {
        val ix = floor(p.x * bodyRadius / OWN_CELL + 0.5).toLong()
        val iy = floor(p.y * bodyRadius / OWN_CELL + 0.5).toLong()
        val iz = floor(p.z * bodyRadius / OWN_CELL + 0.5).toLong()
        val key = ((ix + OWN_OFFSET) shl 42) or ((iy + OWN_OFFSET) shl 21) or (iz + OWN_OFFSET)
        val slot = (mix(key) and (OWN_SLOTS - 1).toLong()).toInt()
        if (ownKey[slot] == key) return ownValue[slot]
        ownAt.setTo(ix * OWN_CELL, iy * OWN_CELL, iz * OWN_CELL)
        val v = if (ownAt.lengthSq < 1.0) 0.0 else dryness(ownAt.normalizeInPlace())
        ownKey[slot] = key; ownValue[slot] = v
        return v
    }

    /**
     * [dryness] times the launch sites' share, blended over a [DRY_CELL] lattice so it changes
     * smoothly, and none over the sea. Smooth enough for wind.
     */
    internal fun blend(p: Vec3, elevation: Double): Double {
        if (!hasGround) return 0.0
        val shore = if (terrain!!.hasOcean) smooth(0.5, 3.0, elevation) else 1.0
        if (shore <= 0.0) return 0.0
        val x = p.x * bodyRadius / DRY_CELL; val y = p.y * bodyRadius / DRY_CELL; val z = p.z * bodyRadius / DRY_CELL
        val fx = floor(x); val fy = floor(y); val fz = floor(z)
        val ix = fx.toLong(); val iy = fy.toLong(); val iz = fz.toLong()
        val tx = x - fx; val ty = y - fy; val tz = z - fz
        if (!(lastSet && ix == lastX && iy == lastY && iz == lastZ)) {
            last[0] = cornerValue(ix, iy, iz); last[1] = cornerValue(ix + 1, iy, iz)
            last[2] = cornerValue(ix, iy + 1, iz); last[3] = cornerValue(ix + 1, iy + 1, iz)
            last[4] = cornerValue(ix, iy, iz + 1); last[5] = cornerValue(ix + 1, iy, iz + 1)
            last[6] = cornerValue(ix, iy + 1, iz + 1); last[7] = cornerValue(ix + 1, iy + 1, iz + 1)
            lastX = ix; lastY = iy; lastZ = iz; lastSet = true
        }
        val c000 = last[0]; val c100 = last[1]; val c010 = last[2]; val c110 = last[3]
        val c001 = last[4]; val c101 = last[5]; val c011 = last[6]; val c111 = last[7]
        val a = (c000 + (c100 - c000) * tx) * (1.0 - ty) + (c010 + (c110 - c010) * tx) * ty
        val b = (c001 + (c101 - c001) * tx) * (1.0 - ty) + (c011 + (c111 - c011) * tx) * ty
        return (a * (1.0 - tz) + b * tz) * shore
    }

    // How dry each storm cell's middle is, direct-mapped.
    private val cellKey = LongArray(CELL_SLOTS) { Long.MIN_VALUE }
    private val cellDry = DoubleArray(CELL_SLOTS)
    private val cellCentre = Vec3()

    private fun cellDryness(key: Long): Double {
        val slot = (mix(key) and (CELL_SLOTS - 1).toLong()).toInt()
        if (cellKey[slot] == key) return cellDry[slot]
        val v = dryness(cells.centre(key, cellCentre))
        cellKey[slot] = key; cellDry[slot] = v
        return v
    }

    // --- haboobs ----------------------------------------------------------------------

    internal class Haboob(
        val id: Long,
        val origin: Vec3,
        /** Unit, tangent at [origin]. */
        val forward: Vec3,
        val right: Vec3,
        val start: Double,
        val life: Double,
        val speed: Double,
        /** Seconds over which it slows to 1/e of [speed]. */
        val slowing: Double,
        val width: Double,
        val height: Double,
        val strength: Double,
    ) {
        /** How far its front has come from [origin] at [age], in metres. */
        fun distance(age: Double): Double = speed * slowing * (1.0 - DetMath.exp(-age / slowing))

        /** How fast its front moves at [age], m/s. */
        fun speedAt(age: Double): Double = speed * DetMath.exp(-age / slowing)

        /** Half its width at [age], in metres. */
        fun halfWidth(age: Double): Double = width + SPREAD * distance(age)
    }

    private val cells = SphereCells(bodyRadius, Storms.CELL)
    private val built = lruMapOf<Long, Any>(64, 4_000)
    private val stormCentre = Vec3()
    private val stormTrack = Vec3()
    private val stormRight = Vec3()
    private val sc = DetMath.SinCos()

    /** Cell [key]'s haboob from its storm in [cycle], or null if it has none. */
    internal fun haboob(key: Long, cycle: Long): Haboob? {
        val cx = cells.hashX(key); val cy = cells.hashY(key); val c = cycle.toInt()
        fun h(k: Int) = Noise.hash(seed + k, cx, cy, c)
        val roll = h(0)
        if (roll >= chance * 1.2) return null
        val cacheKey = key * 1_000_003L + cycle
        built[cacheKey]?.let { return it as? Haboob }
        val made = makeHaboob(key, cycle, roll, ::h)
        built[cacheKey] = made ?: NONE
        return made
    }

    private fun makeHaboob(key: Long, cycle: Long, roll: Double, h: (Int) -> Double): Haboob? {
        val dry = smooth(0.2, 0.55, cellDryness(key))
        if (dry <= 0.0) return null
        val s = storms.stormIn(key, cycle)
        if (!s.exists || s.strength < 0.45) return null
        val kindWeight = when (s.kind) {
            StormKind.SINGLE -> 0.5
            StormKind.MULTICELL, StormKind.SUPERCELL -> 1.0
            StormKind.SQUALL -> 1.2
        }
        if (roll >= chance * kindWeight * dry) return null
        val start = s.start + (START_FROM + START_SPREAD * h(1)) * Storms.CYCLE
        // Not from a storm a hurricane has taken over.
        if (storms.envelope(s, start) < 0.25) return null
        storms.frameAt(s, start, stormCentre, stormTrack, stormRight)
        // From under the biggest tower's rain, or a line's gust front.
        val main = s.mainCell
        val along = if (s.kind == StormKind.SQUALL) 2_500.0 else s.cellAlong[main] + s.shaftAlong * s.cellRadius[main]
        val across = if (s.kind == StormKind.SQUALL) 0.0 else s.cellAcross[main]
        val origin = storms.place(stormCentre, stormTrack, stormRight, along, across, 1.0, Vec3())
        // Out along the storm's track, turned a little.
        DetMath.sinCos((h(2) - 0.5) * TURN, sc)
        val forward = Vec3().setTo(stormTrack).mulInPlace(sc.cos).addScaledInPlace(stormRight, sc.sin)
        forward.addScaledInPlace(origin, -(forward dot origin))
        if (forward.lengthSq < 1e-12) return null
        forward.normalizeInPlace()
        val right = Vec3().setTo(forward).crossInPlace(origin).normalizeInPlace()
        val id = (((s.cx.toLong() shl 42) xor (s.cy.toLong() shl 21) xor (s.cycle and 0x1fffffL))) and Long.MAX_VALUE
        val u = h(5)
        return Haboob(
            id = id, origin = origin, forward = forward, right = right, start = start,
            life = LIFE_SHORTEST + (LIFE_LONGEST - LIFE_SHORTEST) * u * sqrt(u),
            speed = SPEED_SLOWEST + (SPEED_FASTEST - SPEED_SLOWEST) * h(3),
            slowing = SLOWING_SHORTEST + (SLOWING_LONGEST - SLOWING_SHORTEST) * h(4),
            width = WIDTH_NARROWEST + (WIDTH_WIDEST - WIDTH_NARROWEST) * h(6),
            height = HEIGHT_LOWEST + (HEIGHT_TALLEST - HEIGHT_LOWEST) * h(7),
            strength = (0.55 + 0.45 * h(8)) * (0.6 + 0.4 * s.strength),
        )
    }

    /** How far from a haboob's origin anything of it reaches at [age], in metres, along the surface plane. */
    private fun extent(b: Haboob, age: Double): Double {
        val a = min(age, b.life)
        return b.distance(a) + 1.3 * b.halfWidth(a) + EDGE_MARGIN
    }

    /** The most metres a haboob can reach from the middle of its storm's cell. */
    private val farthest: Double by lazy {
        val d = SPEED_FASTEST * SLOWING_LONGEST * (1.0 - DetMath.exp(-LIFE_LONGEST / SLOWING_LONGEST))
        // Its origin: jitter in the cell, the storm's drift before it starts, the shaft's offset.
        d + 1.3 * (WIDTH_WIDEST + SPREAD * d) + EDGE_MARGIN + 0.75 * Storms.CELL + Storms.MAX_STEER * Storms.CYCLE + 25_000.0
    }

    private var searchKeys = LongArray(0)
    private val searchE = Vec3()
    private val searchN = Vec3()
    private val rel = Vec3()

    /**
     * The haboobs alive at some time in [from]..[to] that may reach within [reach] metres of unit
     * [up] then, into [out], sorted by id. A superset: [blow] still checks.
     */
    internal fun haboobsNear(up: Vec3, from: Double, to: Double, reach: Double, out: MutableList<Haboob>) {
        out.clear()
        if (!haboobs) return
        // A cell's width is at least about three quarters of [Storms.CELL] on the cube.
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
                val b = haboob(key, c) ?: continue
                if (b.start > to || b.start + b.life < from) continue
                if (reach < bodyRadius && (b.origin dot up) < 0.5) continue
                rel.setTo(up).mulInPlace(bodyRadius).addScaledInPlace(b.origin, -bodyRadius)
                rel.addScaledInPlace(b.origin, -(rel dot b.origin))
                if (rel.length > reach + extent(b, to - b.start)) continue
                out.add(b)
            }
        }
        out.sortBy { it.id }
    }

    // Haboobs listed for samples near a few places, each for one [SLOT], so several craft far
    // apart sampled in turn don't relist every time. Each list is a superset sorted by id, so which
    // one a sample uses doesn't change its answer.
    private class Listing {
        val haboobs = ArrayList<Haboob>()
        val up = Vec3()
        var from = Double.NaN
        var to = Double.NaN
    }

    private val listings = Array(PLACES) { Listing() }
    private var nextListing = 0

    /** How many times a list has been made, for tests. */
    internal var relisted = 0L

    private fun listFor(up: Vec3, time: Double): ArrayList<Haboob> {
        for (l in listings) {
            if (l.from == l.from && time >= l.from && time <= l.to && l.up.distanceTo(up) * bodyRadius <= MOVE) return l.haboobs
        }
        val l = listings[nextListing]
        nextListing = (nextListing + 1) % PLACES
        val slot = floor(time / SLOT)
        l.from = slot * SLOT
        l.to = l.from + SLOT
        l.up.setTo(up)
        relisted++
        haboobsNear(up, l.from, l.to, MOVE, l.haboobs)
        return l.haboobs
    }

    /** What one haboob does to a sample, before the ground's dust. */
    private var hDust = 0.0
    private var hTop = 0.0
    private var hTurbulence = 0.0
    private var hLift = 0.0
    private var touched = false
    private val hWind = Vec3()
    private val push = Vec3()

    /**
     * Adds [b]'s wind, lift and rough air at unit [up] (the ground under the sample), [agl] up, to
     * [hWind], [hLift] and [hTurbulence], and its dust to [hDust]. Nothing outside its wall.
     */
    private fun blow(b: Haboob, up: Vec3, agl: Double, time: Double) {
        val age = time - b.start
        if (age <= 0.0 || age >= b.life) return
        if ((b.origin dot up) < 0.5) return
        rel.setTo(up).mulInPlace(bodyRadius).addScaledInPlace(b.origin, -bodyRadius)
        val along = rel dot b.forward
        val across = rel dot b.right
        val half = b.halfWidth(age)
        if (kotlin.math.abs(across) >= half) return
        val distance = b.distance(age)
        // The front bows out in the middle.
        val q = across / half
        val front = distance - BOW * half * q * q
        val x = along - front
        val behind = -x
        if (x > AHEAD || behind > distance + 2_000.0) return
        val top = b.height * (0.75 + 0.25 * DetMath.exp(-max(behind, 0.0) / 3_000.0))
        if (agl > 1.3 * top) return
        val life = smooth(0.0, GROW, age) * (1.0 - smooth(FADE * b.life, b.life, age))
        val s = b.strength * life * (1.0 - smooth(0.75, 1.0, kotlin.math.abs(q)))
        if (s <= 0.0) return
        touched = true

        // Thick behind the face, thinning toward where it began.
        val face = 1.0 - smooth(-150.0, 250.0, x)
        val body = (0.35 + 0.65 * DetMath.exp(-max(behind, 0.0) / THICK)) * (1.0 - smooth(0.75, 1.0, behind / (distance + 2_000.0)))
        hDust = 1.0 - (1.0 - hDust) * (1.0 - s * face * body * (1.0 - smooth(0.55 * top, top, agl)))
        // Full to 0.55 of the top, then thinning: as an even layer, about 0.78 of it.
        hTop = max(hTop, 0.78 * top)

        // Wind and rough air fade out before the back edge and AHEAD of the face, so nothing steps.
        val ends = (1.0 - smooth(0.75, 1.0, behind / (distance + 2_000.0))) * (1.0 - smooth(0.4 * AHEAD, AHEAD, x))

        // The gust front at the face, then the outflow behind, outward from the front.
        val g = GUST * ends * s * DetMath.exp(-((x + 400.0) / 700.0).let { it * it }) * (1.0 - smooth(0.5 * top, 1.2 * top, agl))
        val behindWind = OUTFLOW * b.speedAt(age) * ends * s * face * DetMath.exp(-max(behind, 0.0) / 15_000.0) * (1.0 - smooth(0.4 * top, top, agl))
        push.setTo(b.forward).addScaledInPlace(b.right, q * 0.35)
        push.addScaledInPlace(up, -(push dot up))
        if (push.lengthSq > 1e-12) {
            push.normalizeInPlace()
            hWind.addScaledInPlace(push, g + behindWind)
        }
        // Dust heaved up at the face.
        val w = LIFT * ends * s * DetMath.exp(-((x + 300.0) / 400.0).let { it * it }) * (1.0 - smooth(0.6 * top, top, agl))
        hLift += w
        hWind.addScaledInPlace(up, w)
        hTurbulence += ends * s * (0.6 * DetMath.exp(-((x + 500.0) / 1_200.0).let { it * it }) + 0.3 * face * body) * (1.0 - smooth(top, 1.3 * top, agl))
    }

    // --- world storms -----------------------------------------------------------------

    internal class WorldStorm(
        val id: Long,
        val origin: Vec3,
        val start: Double,
        val life: Double,
        val strength: Double,
    )

    private val worldCache = arrayOfNulls<Any>(2)
    private val worldCycle = LongArray(2) { Long.MIN_VALUE }
    private val worldPhase = Noise.hash(this.seed + 900, 0, 0, 0) * WORLD_CYCLE

    /** The world storm of cycle [cycle], or null if it has none. */
    internal fun worldStorm(cycle: Long): WorldStorm? {
        val slot = cycle.mod(2L).toInt()
        if (worldCycle[slot] == cycle) return worldCache[slot] as? WorldStorm
        val c = cycle.toInt()
        fun h(k: Int) = Noise.hash(seed + 900 + k, c, 0x5D, 0)
        val made = if (!worldStorms || h(1) >= worldChance) null else {
            val life = WORLD_LIFE_SHORTEST + (WORLD_LIFE_LONGEST - WORLD_LIFE_SHORTEST) * h(2)
            val start = cycle * WORLD_CYCLE - worldPhase + h(3) * (WORLD_CYCLE - life)
            // From a low southern latitude, where the ground warms most.
            val lat = (-35.0 + 50.0 * h(4)) * (kotlin.math.PI / 180.0)
            val lon = 2.0 * kotlin.math.PI * h(5)
            val cl = DetMath.cos(lat)
            val origin = Vec3(cl * DetMath.cos(lon), DetMath.sin(lat), cl * DetMath.sin(lon))
            WorldStorm(Long.MIN_VALUE or 0x5D_0000_0000L or (cycle and 0xffffffffL), origin, start, life, 0.65 + 0.35 * h(6))
        }
        worldCycle[slot] = cycle; worldCache[slot] = made
        return made
    }

    /** The world storm alive at [time], or null. */
    internal fun worldStormAt(time: Double): WorldStorm? {
        if (!worldStorms) return null
        val cycle = floor((time + worldPhase) / WORLD_CYCLE).toLong()
        val w = worldStorm(cycle) ?: return null
        return if (time > w.start && time < w.start + w.life) w else null
    }

    /** How much of [w] there is at [time], 0..1: spreading, then clearing at the end. */
    private fun worldLife(w: WorldStorm, time: Double): Double {
        val f = (time - w.start) / w.life
        return w.strength * smooth(0.0, 0.1, f) * (1.0 - smooth(0.7, 1.0, f))
    }

    /** How far [w] has spread from its origin at [time], as a chord on the unit sphere (2 is everywhere). */
    private fun worldSpread(w: WorldStorm, time: Double): Double =
        2.3 * smooth(0.0, SPREAD_SHARE, (time - w.start) / w.life)

    /** How much of the world storm covers unit [up] at [time], 0..1, before the ground and height. */
    fun worldCover(up: Vec3, time: Double): Double {
        val w = worldStormAt(time) ?: return 0.0
        val spread = worldSpread(w, time)
        return worldLife(w, time) * (1.0 - smooth(spread - 0.25, spread + 0.05, up.distanceTo(w.origin)))
    }

    /** About how much of the whole world the world storm covers at [time], 0..1. */
    fun worldShare(time: Double): Double {
        val w = worldStormAt(time) ?: return 0.0
        // A cap of chord c covers c^2 / 4 of the sphere.
        val c = min(worldSpread(w, time), 2.0)
        return worldLife(w, time) * c * c / 4.0
    }

    // --- the sample -------------------------------------------------------------------

    /**
     * Adds the dust storms' wind, lift and rough air to [out] at [up] (unit, body-fixed), [agl]
     * metres above the ground [elevation] metres up, and their dust ([AirSample.dust]) and sight
     * range. [freeWind] is the circulation's wind speed there (before height and the ground), [dir]
     * its direction.
     */
    fun apply(up: Vec3, elevation: Double, agl: Double, freeWind: Double, dir: Vec3, time: Double, out: AirSample) {
        if (!active || !enabled || agl > TOP) return
        hDust = 0.0; hTop = 0.0; hTurbulence = 0.0; hLift = 0.0; hWind.setZero(); touched = false
        if (haboobs) {
            val listed = listFor(up, time)
            for (i in 0 until listed.size) blow(listed[i], up, agl, time)
        }
        // Broad dust on windy days.
        var broadDust = 0.0
        var broadTop = 0.0
        if (broad > 0.0 && freeWind > BROAD_FROM) {
            val windiness = smooth(BROAD_FROM, BROAD_FULL, freeWind)
            val top = 400.0 + 1_600.0 * windiness
            if (agl < top) {
                val k = bodyRadius / 40_000.0
                val drift = time / 3_600.0
                val n = Noise.simplex(seed + 950, up.x * k + drift, up.y * k, up.z * k - drift * 0.6)
                broadDust = BROAD_DENSITY * broad * windiness * (0.55 + 0.45 * n) * (1.0 - smooth(0.4 * top, top, agl))
                // Drawn as blowing dust held low, as it mostly is, however high the thinnest goes.
                broadTop = min(0.7 * top, BROAD_DRAWN)
            }
        }
        // A world storm.
        var worldDust = 0.0
        var worldWind = 0.0
        val w = if (worldStorms) worldStormAt(time) else null
        if (w != null) {
            val spread = worldSpread(w, time)
            val cover = 1.0 - smooth(spread - 0.25, spread + 0.05, up.distanceTo(w.origin))
            val s = worldLife(w, time) * cover
            if (s > 0.0) {
                val k = bodyRadius / 150_000.0
                val drift = time / 20_000.0
                val n = Noise.simplex(seed + 960, up.x * k + drift, up.y * k - drift, up.z * k)
                worldDust = WORLD_DENSITY * s * (0.75 + 0.25 * n) * (1.0 - smooth(WORLD_DUST_FROM, WORLD_DUST_TOP, agl))
                worldWind = WORLD_WIND * s * (1.0 - smooth(1_000.0, WORLD_DUST_TOP, agl))
            }
        }
        if (!touched && broadDust <= 0.0 && worldDust <= 0.0 && worldWind <= 0.0) return

        val smoothGround = blend(up, elevation)
        val ground = if (smoothGround <= 0.0) 0.0 else ownDryness(up).let { if (it <= 0.0) 0.0 else min(smoothGround, it * siteShare(up)) }
        // Dust only over dry ground; the haboob's wind blows on, weaker, where it's wetter.
        val windShare = 0.3 * siteShare(up) + 0.7 * smoothGround
        val dust = (1.0 - (1.0 - hDust) * (1.0 - broadDust) * (1.0 - worldDust)) * ground
        out.wind.addScaledInPlace(hWind, windShare)
        out.lift += hLift * windShare
        if (worldWind > 0.0) out.wind.addScaledInPlace(dir, worldWind * smoothGround)
        out.turbulence += hTurbulence * windShare + (BROAD_GUSTS * broadDust + WORLD_GUSTS * worldDust) * smoothGround
        if (dust > 0.0) {
            // A world storm drawn as a lower, thinner layer than it is, so its ground and sun show.
            val top = max(max(if (hDust > 0.0) hTop else 0.0, if (broadDust > 0.0) broadTop else 0.0),
                if (worldDust > 0.0) WORLD_DRAWN else 0.0)
            val look = (1.0 - (1.0 - hDust) * (1.0 - broadDust) * (1.0 - WORLD_LOOK * worldDust)) * ground
            if (dust >= out.dust) out.dustTop = top
            out.dustLook = max(out.dustLook, look)
            out.dust = max(out.dust, dust)
            out.visibility = min(out.visibility, DUST_SIGHT / dust)
        }
    }

    // --- listing ----------------------------------------------------------------------

    private val listing = ArrayList<Haboob>()

    /**
     * The dust storms alive at [time] that come within [reach] metres of unit [up], into [out].
     * Makes new objects, so not for every sample.
     */
    fun near(up: Vec3, time: Double, reach: Double, out: MutableList<LiveDust>) {
        if (haboobs) {
            haboobsNear(up, time, time, reach, listing)
            for (b in listing) {
                val age = time - b.start
                if (age <= 0.0 || age >= b.life) continue
                val d = b.distance(age)
                val centre = Vec3().setTo(b.origin).mulInPlace(bodyRadius).addScaledInPlace(b.forward, d).normalizeInPlace()
                val r = sqrt(d * d + b.halfWidth(age).let { it * it }) + 2_000.0
                if (centre.distanceTo(up) * bodyRadius > reach + 2.0 * r) continue
                val heading = Vec3().setTo(b.forward).addScaledInPlace(centre, -(b.forward dot centre)).normalizeInPlace()
                val life = smooth(0.0, GROW, age) * (1.0 - smooth(FADE * b.life, b.life, age))
                out.add(LiveDust(b.id, DustKind.HABOOB, centre, b.origin.copy(), heading, b.speedAt(age), b.halfWidth(age),
                    b.height, r, b.strength * life, age, b.life))
            }
        }
        val w = worldStormAt(time)
        if (w != null) {
            val spread = worldSpread(w, time)
            // A chord of the unit sphere to metres along the surface.
            val arc = 2.0 * DetMath.asin(min(spread, 2.0) / 2.0) * bodyRadius
            out.add(LiveDust(w.id, DustKind.WORLD, w.origin.copy(), w.origin.copy(), Vec3(), 0.0, 0.0, WORLD_DUST_TOP,
                arc, worldLife(w, time), time - w.start, w.life))
        }
    }

    companion object {
        private val NONE = Any()

        /** Spreads every bit of [key] over the low ones, for picking a slot. */
        private fun mix(key: Long): Long {
            var h = key xor (key ushr 33)
            h *= -0xae502812aa7333L
            h = h xor (h ushr 33)
            h *= -0x3b314601e57a13adL
            return h xor (h ushr 33)
        }

        private const val OFFSET = 1L shl 20

        /** The chance of a haboob from a strong storm over desert per cycle at [WeatherIntensity.rare] 1. */
        const val HABOOB_CHANCE = 0.08

        /** When in its storm's cycle it starts, as a share of the cycle. */
        const val START_FROM = 0.3
        const val START_SPREAD = 0.25

        /** How long one lasts, in seconds. */
        const val LIFE_SHORTEST = 1_800.0
        const val LIFE_LONGEST = 9_000.0

        /** How fast its wall starts out, in m/s, and how many seconds it takes to slow to 1/e of that. */
        const val SPEED_SLOWEST = 10.0
        const val SPEED_FASTEST = 25.0
        const val SLOWING_SHORTEST = 3_000.0
        const val SLOWING_LONGEST = 5_400.0

        /** Half its width at the start, in metres, and how much wider per metre it comes. */
        const val WIDTH_NARROWEST = 5_000.0
        const val WIDTH_WIDEST = 11_000.0
        const val SPREAD = 0.25

        /** How tall the wall stands, in metres. */
        const val HEIGHT_LOWEST = 1_000.0
        const val HEIGHT_TALLEST = 2_000.0

        /** How far it turns off its storm's track, radians, either way half of it. */
        const val TURN = 0.6

        /** How far back its front bows at the edges, in half widths. */
        const val BOW = 0.25

        /** Thick for this many metres behind the face. */
        const val THICK = 8_000.0

        /** Nothing more than this many metres ahead of its face. */
        const val AHEAD = 1_500.0
        private const val EDGE_MARGIN = 4_000.0

        /** Growing over this many seconds, fading over the last share of its life from this. */
        const val GROW = 600.0
        const val FADE = 0.7

        /** The gust front at full strength, m/s, the outflow behind as a share of the wall's speed, and the lift at the face. */
        const val GUST = 24.0
        const val OUTFLOW = 0.9
        const val LIFT = 4.0

        /** Broad dust: the free wind it starts at and is full at, m/s, how thick at most, and on Wild. */
        const val BROAD_FROM = 32.0
        const val BROAD_FULL = 52.0
        const val BROAD_DENSITY = 0.15
        const val BROAD_WILD = 0.4
        const val BROAD_GUSTS = 2.0
        /** How deep broad dust is drawn, metres, as an even layer. */
        const val BROAD_DRAWN = 200.0
        /** A world storm drawn this deep, metres, and this much as thick for sight: a dim sky, a few km seen. */
        const val WORLD_DRAWN = 2_000.0
        const val WORLD_LOOK = 0.12

        /** World storms: one chance per cycle of seconds, how long one lasts, and its dust and wind. */
        const val WORLD_CYCLE = 8.0 * 86_400.0
        const val WORLD_CHANCE = 0.35
        const val WORLD_LIFE_SHORTEST = 1.5 * 86_400.0
        const val WORLD_LIFE_LONGEST = 4.0 * 86_400.0
        const val SPREAD_SHARE = 0.35
        const val WORLD_DENSITY = 0.7
        const val WORLD_WIND = 9.0
        const val WORLD_GUSTS = 0.4
        const val WORLD_DUST_FROM = 3_000.0
        const val WORLD_DUST_TOP = 15_000.0

        /** Nothing above this many metres over the ground. */
        const val TOP = WORLD_DUST_TOP

        /** How far you see in dust as thick as it gets, in metres. Thinner dust, further. */
        const val DUST_SIGHT = 150.0

        /** Terra's moisture where dust starts and where it's full. */
        const val TERRA_WET = 0.48
        const val TERRA_DRY = 0.26

        /** The dry ground lattice in metres, and slots kept. */
        const val DRY_CELL = 3_000.0
        const val DRY_SLOTS = 512
        const val CELL_SLOTS = 1_024

        /** Haboobs near a sample are listed once per this many seconds and place this many metres across, for up to [PLACES] places. */
        const val SLOT = 600.0
        const val MOVE = 5_000.0
        const val PLACES = 6

        /** The ground's own dryness is kept per node this many metres apart, in this many slots. */
        const val OWN_CELL = 20.0
        const val OWN_SLOTS = 256
        private const val OWN_OFFSET = 1L shl 20

        /** How much weaker near a launch site, fading out from [SITE_NEAR] to [SITE_FAR] metres away. */
        const val SITE_SHY = 0.4
        const val SITE_NEAR = 20_000.0
        const val SITE_FAR = 40_000.0
    }
}
