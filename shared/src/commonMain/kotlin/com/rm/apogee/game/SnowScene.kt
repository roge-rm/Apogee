package com.rm.apogee.game

import com.rm.apogee.core.math.Quat
import com.rm.apogee.core.math.Vec3
import com.rm.apogee.core.orbit.CelestialBody
import com.rm.apogee.core.terrain.Noise
import com.rm.apogee.core.weather.AirSample
import com.rm.apogee.core.weather.LiveBlizzard
import com.rm.apogee.core.weather.Weather
import com.rm.apogee.core.weather.WeatherConfig
import com.rm.apogee.render.CloudPuff
import com.rm.apogee.render.CloudShapes
import com.rm.apogee.render.CloudShell
import com.rm.apogee.render.QualityTier
import com.rm.apogee.render.RenderItem
import com.rm.apogee.render.RenderItemPool
import com.rm.apogee.render.SnowVeil
import com.rm.apogee.render.StackCaps
import kotlinx.coroutines.launch
import kotlin.concurrent.Volatile
import kotlin.math.PI
import kotlin.math.pow
import kotlin.math.sin

/**
 * Blizzards for seeing and hearing: white snow closing in round a camera in it with the light
 * gone flat, a blizzard's grey-white curtain of snow seen from outside, blizzards as white sheets
 * from high up and on the map, and how loud the howl is.
 *
 * The whiteout follows the camera's own air sample, eased, on the frame thread; with no snow there
 * it does nothing. Blizzards near the camera are listed on a worker every couple of seconds where
 * the world and setting can have them, the veil and the map's listing now and then.
 */
class SnowScene(
    private val body: CelestialBody,
    private val config: WeatherConfig,
    private val tier: QualityTier,
    private val scope: kotlinx.coroutines.CoroutineScope,
    /** For what's cheap to ask on the frame thread. */
    frameWeather: Weather,
    /** Makes the worker's own weather; a weather's caches aren't thread safe. */
    makeWeather: () -> Weather = { Weather(body, config) },
) {
    /** Off where the setting or world has no blizzards or snow lifted by the wind. */
    val active = config.intensity.rare > 0.0 && body.terrain != null && body.atmosphere != null && frameWeather.snowPossible

    private val stormy = active && frameWeather.blizzardsPossible

    private val weather by lazy(makeWeather)

    // --- whiteout ------------------------------------------------------------------

    /** How thick the snow round the camera is, 0..1, eased so it never pops. */
    var haze = 0.0
        private set

    /** The snow's colour, rgb: a cold white. */
    val colour = floatArrayOf(0.86f, 0.89f, 0.93f)

    /** The camera's height over the ground (or sea), metres, with the ground looked up about once a second or 500 m. */
    var height = 0.0
        private set

    private var ground = 0.0
    private var groundAt = Double.NEGATIVE_INFINITY
    private val groundFrom = Vec3()
    private var hazeAt = Double.NaN
    private val up = Vec3()

    /** How high over the ground the snow round the camera reaches, metres, as an even layer, eased with [haze]. */
    var top = 0.0
        private set

    /** Snow in a low layer under a camera above it and the layer's top, as the worker last found. */
    @Volatile private var below = 0.0
    @Volatile private var belowTop = 0.0

    /**
     * Eases [haze] toward the snow in [air] (the camera's own sample at [time]) and the layer under
     * it, and lists blizzards and the veil when due. Nothing per frame with no snow about.
     */
    fun update(camera: Vec3, time: Double, air: AirSample) {
        if (!active) return
        if (kotlin.math.abs(time - groundAt) >= GROUND_EVERY || camera.distanceTo(groundFrom) >= GROUND_MOVED) {
            groundAt = time; groundFrom.setTo(camera)
            val terrain = body.terrain!!
            ground = kotlin.math.max(terrain.elevation(up.setTo(camera).normalizeInPlace()), if (terrain.hasOcean) 0.0 else -1e9)
        }
        height = camera.length - body.radius - ground
        val target = kotlin.math.max(air.snow, below)
        if (target <= 0.0 && haze <= 0.0) {
            hazeAt = time
        } else {
            val step = if (hazeAt.isNaN()) 1.0 else (kotlin.math.abs(time - hazeAt) / HAZE_EASE).coerceAtMost(1.0)
            hazeAt = time
            haze += (target - haze) * step
            // In snow, the layer reaches at least a little over the camera, so the fog level with
            // it matches the model's sight wherever the snow is drawn shallower than it reaches.
            val topTarget = if (air.snow >= below) (if (air.snow > 0.0) kotlin.math.max(air.snowTop, height + IN_SNOW_OVER) else air.snowTop) else belowTop
            top = if (haze <= 0.0) 0.0 else if (top <= 0.0) topTarget else top + (topTarget - top) * step
            if (haze < 1e-3 && target <= 0.0) { haze = 0.0; top = 0.0 }
        }
        list(camera, time)
    }

    /** How far the camera sees in its snow, in metres, as the fog's visibility. Infinite with none. */
    val sight: Double get() = if (haze <= 1e-4) Double.POSITIVE_INFINITY else SNOW_SIGHT / haze

    /** How much of the fog's colour is snow, 0..1. */
    val tint: Double get() = smooth(0.0, 0.2, haze)

    /** How flat the light is, 0..1: no shadows in a whiteout. */
    val flat: Double get() = smooth(0.1, 0.55, haze)

    /** How much sunlight the snow takes, 0..1: some, since a whiteout is bright. */
    val dimming: Double get() = 0.15 * smooth(0.1, 0.7, haze)

    // --- listing ---------------------------------------------------------------------

    /** One blizzard near the camera, as listed: its curtain's columns round its edge and the ground under each. */
    class Curtain(
        val blizzard: LiveBlizzard,
        val listedAt: Double,
        val key: Long,
        /** Per column: body-fixed ground points (x, y, z), and how cold the country is there. */
        val ground: DoubleArray,
        val cold: DoubleArray,
    )

    @Volatile var curtains: List<Curtain> = emptyList()
        private set

    /** Every blizzard on the world, as last listed for the map and the veil. */
    @Volatile var all: List<LiveBlizzard> = emptyList()
        private set

    /** The blizzards as white sheets from high up and on the map, or null for none. */
    @Volatile var veil: CloudShell? = null
        private set

    @Volatile private var listing = false
    private var listedAt = Double.NEGATIVE_INFINITY
    private val listedFrom = Vec3()
    private var allAt = Double.NEGATIVE_INFINITY
    private var allNanos = 0L
    private var allWanted = false
    private var mapAt = Double.NEGATIVE_INFINITY

    /** Columns round a blizzard's edge. */
    val columns: Int get() = when (tier) { QualityTier.LOW -> 32; QualityTier.MEDIUM -> 48; QualityTier.HIGH -> 72 }

    /** At most this many blizzards drawn, nearest first. */
    val most: Int get() = when (tier) { QualityTier.LOW -> 1; QualityTier.MEDIUM -> 2; QualityTier.HIGH -> 2 }

    /** How far away curtains are drawn, in metres. */
    val reach: Double get() = when (tier) { QualityTier.LOW -> 50_000.0; QualityTier.MEDIUM -> 70_000.0; QualityTier.HIGH -> 90_000.0 }

    /** The most items drawn at once: only the side of each facing the camera is drawn, so a little over half its columns. */
    val itemsMost: Int get() = most * (columns * FACING_SHARE).toInt() * PIECES

    private fun list(camera: Vec3, time: Double) {
        if (listing) return
        val altitude = height
        if ((!stormy && altitude <= BELOW_FROM) || altitude >= LIST_TOP) below = 0.0
        if (altitude < VEIL_FROM && kotlin.math.abs(time - mapAt) > MAP_KEEP && veil != null) veil = null
        val nearDue = (stormy || altitude > BELOW_FROM) && altitude < LIST_TOP &&
            (kotlin.math.abs(time - listedAt) >= RELIST_SECONDS || camera.distanceTo(listedFrom) >= RELIST_DISTANCE)
        val now = com.rm.apogee.platform.System.nanoTime()
        val allDue = stormy && (allWanted || altitude > VEIL_FROM) && kotlin.math.abs(time - allAt) >= WORLD_RELIST_SECONDS &&
            (allNanos == 0L || now - allNanos >= WORLD_RELIST_NANOS)
        if (!nearDue && !allDue) {
            if (altitude >= LIST_TOP && curtains.isNotEmpty()) curtains = emptyList()
            return
        }
        if (nearDue) { listedAt = time; listedFrom.setTo(camera) }
        if (allDue) { allAt = time; allNanos = now; allWanted = false }
        val from = camera.copy()
        listing = true
        scope.launch(kotlinx.coroutines.Dispatchers.Default) {
            try {
                if (nearDue) {
                    curtains = if (stormy) listCurtains(from, time) else emptyList()
                    below = layerBelow(from, time)
                    belowTop = sample.snowTop
                }
                if (allDue) {
                    val live = ArrayList<LiveBlizzard>()
                    weather.blizzards(Vec3.unitY(), time, Double.MAX_VALUE, live)
                    all = live
                    veil = SnowVeil.build(live, body.radius, VEIL_REVISIONS.incrementAndGet().toInt(),
                        cold = { weather.blizzardCold(it) },
                        ground = { kotlin.math.max(body.terrain?.elevation(it) ?: 0.0, 0.0) })
                }
            } finally {
                listing = false
            }
        }
    }

    /** The blizzards on the whole world for the map, as last listed, listing afresh when due. */
    fun forMap(camera: Vec3, time: Double): List<LiveBlizzard> {
        if (!stormy) return emptyList()
        allWanted = true
        mapAt = time
        list(camera, time)
        return all
    }

    private fun listCurtains(camera: Vec3, time: Double): List<Curtain> {
        val centre = camera.copy().normalizeInPlace()
        val live = ArrayList<LiveBlizzard>()
        weather.blizzards(centre, time, reach, live)
        val terrain = body.terrain!!
        val near = live.filter { it.strength > 0.02 }
            .sortedBy { kotlin.math.max(0.0, it.centre.distanceTo(centre) * body.radius - it.reach) }
            .take(most)
        val n = columns
        val p = Vec3()
        return near.map { z ->
            val ground = DoubleArray(3 * n); val cold = DoubleArray(n)
            for (i in 0 until n) {
                edgePoint(z, i, n, p)
                val h = kotlin.math.max(terrain.elevation(p), if (terrain.hasOcean) 0.0 else -1e9)
                val r = body.radius + h
                ground[3 * i] = p.x * r; ground[3 * i + 1] = p.y * r; ground[3 * i + 2] = p.z * r
                cold[i] = weather.blizzardCold(p)
            }
            Curtain(z, time, Noise.hashInt(0xB112, z.id.toInt(), (z.id ushr 32).toInt(), 0).toLong() and 0xffffL, ground, cold)
        }
    }

    /** Unit point [i] of [n] round [z]'s curtain, a little inside its edge. */
    private fun edgePoint(z: LiveBlizzard, i: Int, n: Int, out: Vec3): Vec3 {
        val a = 2.0 * PI * (i + 0.5) / n
        val right = Vec3().setTo(z.heading).crossInPlace(z.centre).normalizeInPlace()
        return out.setTo(z.centre)
            .addScaledInPlace(z.heading, CURTAIN_AT * z.halfLength * kotlin.math.cos(a) / body.radius)
            .addScaledInPlace(right, CURTAIN_AT * z.halfWidth * kotlin.math.sin(a) / body.radius)
            .normalizeInPlace()
    }

    private val sample = AirSample()

    /** Snow in a low layer under a camera above it, as thick as it looks through the layer: none high up. */
    private fun layerBelow(camera: Vec3, time: Double): Double {
        val terrain = body.terrain ?: return 0.0
        val d = camera.copy().normalizeInPlace()
        val ground = kotlin.math.max(terrain.elevation(d), if (terrain.hasOcean) 0.0 else -1e9)
        val above = camera.length - body.radius - ground
        if (above < BELOW_FROM || above > BELOW_TOP) { sample.clear(); return 0.0 }
        weather.sample(Vec3().setTo(d).mulInPlace(body.radius + ground + 20.0), time, sample)
        if (sample.snow <= 0.0) return 0.0
        return sample.snow * (1.0 - smooth(0.5 * BELOW_TOP, BELOW_TOP, above))
    }

    // --- drawing ---------------------------------------------------------------------

    private val puffs = Array(CloudShapes.VARIANTS) { CloudPuff(it, false, 1) }
    private val nearPuffs = Array(CloudShapes.VARIANTS) { CloudPuff(it, false, 2) }
    private val outward = Vec3()
    private val toCamera = Vec3()
    private val along = Vec3()
    private val point = Vec3()
    private val pointUp = Vec3()
    private val shift = Vec3()
    private val upright = Quat()
    private val turn = Quat()

    /** How much of the curtains to draw from a camera [altitude] metres up, 0..1: none from where the veil has them. */
    fun curtainWeight(altitude: Double): Double = 1.0 - smooth(PUFFS_FADE, LIST_TOP, altitude)

    /** How much of the veil to draw from a camera [above] metres over the ground, 0..1: none low down. */
    fun veilAlpha(above: Double): Float = smooth(VEIL_LOW, VEIL_FULL, above).toFloat()

    /**
     * Blizzards' curtains near body-fixed [camera], turned to where the planet is at [bodyRotation],
     * to [out], in items from [pool] (new ones without). Carried on along their heading since listed.
     */
    fun append(camera: Vec3, bodyRotation: Quat, time: Double, out: MutableList<RenderItem>, pool: RenderItemPool? = null) {
        val list = curtains
        if (list.isEmpty()) return
        val weight = curtainWeight(height)
        if (weight <= 0.01) return
        for (c in list) draw(c, camera, bodyRotation, time, weight, out, pool)
    }

    private fun draw(c: Curtain, camera: Vec3, bodyRotation: Quat, time: Double, weight: Double, out: MutableList<RenderItem>, pool: RenderItemPool?) {
        val z = c.blizzard
        val since = time - c.listedAt
        if (z.age + since >= z.life) return
        shift.setTo(z.heading).mulInPlace(z.speed * since)
        val n = c.cold.size
        // Round its edge, the gap between columns.
        val spacing = 2.0 * PI * CURTAIN_AT * kotlin.math.sqrt(0.5 * (z.halfLength * z.halfLength + z.halfWidth * z.halfWidth)) / n
        val centre = outward
        var drawn = 0
        val most = (n * FACING_SHARE).toInt()
        for (i in 0 until n) {
            point.setTo(c.ground[3 * i], c.ground[3 * i + 1], c.ground[3 * i + 2]).addInPlace(shift)
            val far = point.distanceTo(camera)
            if (far > reach) continue
            // Only the side facing the camera, fading round the sides so columns never pop.
            centre.setTo(z.centre).mulInPlace(point.length).addInPlace(shift)
            toCamera.setTo(camera).subInPlace(point)
            val facing = ((point.x - centre.x) * toCamera.x + (point.y - centre.y) * toCamera.y + (point.z - centre.z) * toCamera.z) /
                (point.distanceTo(centre) * far).coerceAtLeast(1e-6)
            val side = smooth(-0.15, 0.25, facing)
            if (side <= 0.0 || drawn >= most) continue
            val fade = (1.0 - smooth(0.7 * reach, reach, far)) * smooth(NEAR_CLEAR, NEAR_SOLID, far) * weight * side
            // Dense: even a middling blizzard's curtain hides what's behind it.
            val alpha = (z.strength * c.cold[i]).coerceAtLeast(0.0).pow(0.25) * fade
            if (alpha < 0.02) continue
            drawn++
            pointUp.setTo(point).normalizeInPlace()
            fromTo(UNIT_Y, pointUp, upright)
            val closeUp = far < 0.3 * reach && tier != QualityTier.LOW
            // Along the curtain here, from the columns either side, so billows can shift along it.
            val a = (i + n - 1) % n; val b = (i + 1) % n
            along.setTo(c.ground[3 * b] - c.ground[3 * a], c.ground[3 * b + 1] - c.ground[3 * a + 1], c.ground[3 * b + 2] - c.ground[3 * a + 2]).normalizeInPlace()
            for (j in 0 until PIECES) {
                val h = hash(i * 7 + j + c.key.toInt())
                // Some columns have no top billow, so the top edge is ragged.
                if (j == PIECES - 1 && hash(i * 29 + c.key.toInt()) < 0.35) continue
                // Each billow swells and thins on its own slow beat, so the curtain shifts.
                val sway = sin(2.0 * PI * time / (SWAY_PERIOD * (0.7 + 0.6 * h)) + 6.0 * h)
                val tall = z.height * PIECE_TALL[j] * (0.65 + 0.7 * hash(i * 17 + j)) * (1.0 + 0.06 * sway)
                // Round billows, never flat panels, however far apart the columns are.
                val wide = kotlin.math.min(spacing * PIECE_WIDE[j] * (0.9 + 0.25 * h), PIECE_ROUND[j] * tall)
                val lift = z.height * PIECE_LIFT[j] * (0.85 + 0.3 * hash(i * 13 + j))
                val item = pool?.take() ?: RenderItemPool.blank()
                val slide = spacing * PIECE_SLIDE[j] * (2.0 * hash(i * 31 + j) - 1.0)
                bodyRotation.rotate(pointUp.mulInPlace(lift).addInPlace(point).addScaledInPlace(along, slide), item.position)
                pointUp.setTo(point).normalizeInPlace()
                Quat.fromAxisAngle(UNIT_Y, 2.0 * PI * h, turn)
                item.rotation.setTo(bodyRotation).mulInPlace(upright).mulInPlace(turn)
                val col = item.ownColour()
                val shade = PIECE_SHADE[j]
                col[0] = colour[0] * shade; col[1] = colour[1] * shade; col[2] = colour[2] * shade
                col[3] = (alpha * PIECE_ALPHA[j] * (0.92 + 0.08 * sway)).toFloat().coerceAtMost(0.97f)
                val shape = if (closeUp) nearPuffs[(i + j) % nearPuffs.size] else puffs[(i + j) % puffs.size]
                out.add(item.refill(shape, col, StackCaps.BOTH, item.ownScale().setTo(wide, tall, wide),
                    -CURTAIN_AMBIENT, true, RenderItem.cloudKey(SNOW_KEY xor (c.key shl 16), 4 * i + j), orderedTransparency = true))
            }
        }
    }

    private fun fromTo(a: Vec3, b: Vec3, out: Quat): Quat {
        val d = a dot b
        if (d <= -1.0 + 1e-9) return out.setTo(1.0, 0.0, 0.0, 0.0)
        return out.setTo(a.y * b.z - a.z * b.y, a.z * b.x - a.x * b.z, a.x * b.y - a.y * b.x, 1.0 + d).normalizeInPlace()
    }

    // --- hearing -------------------------------------------------------------------

    /** How loud the blizzard's howl is, 0..1, and how close the driven snow is (near the ground, thick), 0..1, after [heard]. */
    var loudness = 0.0
        private set
    var closeness = 0.0
        private set

    private var heardAt = Double.NaN

    /** Works out [loudness] and [closeness] at [time] from the snow round the camera and the [wind] there, m/s. */
    fun heard(time: Double, wind: Double) {
        val step = if (heardAt.isNaN()) 1.0 else (kotlin.math.abs(time - heardAt) / HEARD_EASE).coerceAtMost(1.0)
        heardAt = time
        loudness += (howl(haze, wind) - loudness) * step
        closeness += (snowNear(haze, height) - closeness) * step
        if (loudness < 1e-3) loudness = 0.0
    }

    companion object {
        /** How loud a blizzard is, 0..1, for [snow] round the ears in [wind] m/s. */
        fun howl(snow: Double, wind: Double): Double =
            easeOut(HOWL_LEAST, HOWL_FULL, snow) * (0.3 + 0.7 * smooth(5.0, 25.0, wind))

        /** How much of it is snow driven past close by, 0..1: thick snow near the ground. */
        fun snowNear(snow: Double, height: Double): Double =
            smooth(0.2, 0.7, snow) * (1.0 - smooth(15.0, 300.0, height))

        private fun hash(i: Int): Double {
            var x = i * 0x2545F491 + 0x6A09E667
            x = x xor (x ushr 15); x *= 0x2C1B3C6D; x = x xor (x ushr 12)
            return (x.toLong() and 0xFFFF) / 65_536.0
        }

        private fun easeOut(a: Double, b: Double, x: Double): Double {
            val t = ((x - a) / (b - a)).coerceIn(0.0, 1.0)
            return t * (2.0 - t)
        }

        private fun smooth(a: Double, b: Double, x: Double): Double {
            val t = ((x - a) / (b - a)).coerceIn(0.0, 1.0)
            return t * t * (3 - 2 * t)
        }

        /** Sight in the thickest snow, metres, as the model has it. */
        const val SNOW_SIGHT = 60.0
        /** Metres the layer reaches over a camera in snow at least. */
        const val IN_SNOW_OVER = 30.0
        /** Seconds the whiteout takes to follow the air. */
        const val HAZE_EASE = 1.5

        /** The howl is heard from this much snow, fully at [HOWL_FULL]. */
        const val HOWL_LEAST = 0.03
        const val HOWL_FULL = 0.6
        const val HEARD_EASE = 0.5

        /** Blizzards near the camera listed again this often, seconds, or after it moves this far, metres; none above [LIST_TOP]. */
        const val RELIST_SECONDS = 2.0
        const val RELIST_DISTANCE = 2_000.0
        const val LIST_TOP = 30_000.0
        /** Curtains fade out from here up as the veil takes over, metres. */
        const val PUFFS_FADE = 12_000.0
        /** The veil and the world's listing: from this high, or for the map. */
        const val VEIL_FROM = 6_000.0
        const val VEIL_LOW = 6_000.0
        const val VEIL_FULL = 16_000.0
        const val MAP_KEEP = 2.0
        const val GROUND_EVERY = 1.0
        const val GROUND_MOVED = 500.0
        const val WORLD_RELIST_SECONDS = 20.0
        const val WORLD_RELIST_NANOS = 1_000_000_000L

        /** Snow in the layer under a camera this far over the ground is looked at, metres. */
        const val BELOW_FROM = 300.0
        const val BELOW_TOP = 12_000.0

        /** Where the curtain stands, as a share of the way out to the edge. */
        const val CURTAIN_AT = 0.85
        /** Pieces per column: the thick foot, the body and the thinner snow falling above. Widths in column spacings (at most [PIECE_ROUND] times as wide as tall), heights and lifts in its height. */
        const val PIECES = 3
        val PIECE_WIDE = doubleArrayOf(1.6, 1.4, 1.2)
        val PIECE_ROUND = doubleArrayOf(2.0, 1.6, 1.3)
        val PIECE_TALL = doubleArrayOf(0.35, 0.35, 0.3)
        val PIECE_LIFT = doubleArrayOf(0.12, 0.42, 0.72)
        val PIECE_ALPHA = doubleArrayOf(1.0, 0.95, 0.7)
        val PIECE_SHADE = floatArrayOf(0.72f, 0.78f, 0.86f)
        /** How far each billow is shifted along the curtain at most, in column spacings. */
        val PIECE_SLIDE = doubleArrayOf(0.15, 0.45, 0.6)
        /** At most this share of a curtain's columns is drawn: the side facing the camera. */
        const val FACING_SHARE = 0.6
        const val SWAY_PERIOD = 40.0
        /** Curtains clear within this many metres of the camera, solid from [NEAR_SOLID]. */
        const val NEAR_CLEAR = 400.0
        const val NEAR_SOLID = 2_000.0
        /** Soft light, so the curtain reads as falling snow, not cloud. */
        const val CURTAIN_AMBIENT = 0.55f
        /** Keys for the curtains' items, apart from the clouds', the dust's and the quake dust's. */
        const val SNOW_KEY = 0x5A0E0L

        val VEIL_REVISIONS = com.rm.apogee.platform.AtomicLong(2_000_000L)
        private val UNIT_Y = Vec3(0.0, 1.0, 0.0)
    }
}
