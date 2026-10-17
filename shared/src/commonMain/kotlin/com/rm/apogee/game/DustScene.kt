package com.rm.apogee.game

import com.rm.apogee.core.math.Quat
import com.rm.apogee.core.math.Vec3
import com.rm.apogee.core.orbit.CelestialBody
import com.rm.apogee.core.terrain.Noise
import com.rm.apogee.core.terrain.SurfaceMaterial
import com.rm.apogee.core.weather.AirSample
import com.rm.apogee.core.weather.CloudType
import com.rm.apogee.core.weather.DustKind
import com.rm.apogee.core.weather.LiveDust
import com.rm.apogee.core.weather.Weather
import com.rm.apogee.core.weather.WeatherConfig
import com.rm.apogee.render.CloudPuff
import com.rm.apogee.render.CloudShapes
import com.rm.apogee.render.CloudShell
import com.rm.apogee.render.DustVeil
import com.rm.apogee.render.QualityTier
import com.rm.apogee.render.RenderItem
import com.rm.apogee.render.RenderItemPool
import com.rm.apogee.render.StackCaps
import kotlinx.coroutines.launch
import kotlin.concurrent.Volatile
import kotlin.math.PI
import kotlin.math.pow
import kotlin.math.sin

/**
 * Dust storms for seeing and hearing: brown haze closing in round a camera in dust, a haboob's
 * rolling wall of billowing dust seen from outside, the haboobs and a world storm as a veil from
 * high up and on the map, and how loud the hiss of blowing sand is.
 *
 * The haze follows the camera's own air sample, eased, on the frame thread; with no dust there
 * it does nothing. Haboobs near the camera are listed on a worker every couple of seconds where
 * the world and setting can have them, the veil and the map's listing now and then.
 */
class DustScene(
    private val body: CelestialBody,
    private val config: WeatherConfig,
    private val tier: QualityTier,
    private val scope: kotlinx.coroutines.CoroutineScope,
    /** For what's cheap to ask on the frame thread. */
    frameWeather: Weather,
    /** Makes the worker's own weather; a weather's caches aren't thread safe. */
    makeWeather: () -> Weather = { Weather(body, config) },
) {
    /** Off where the setting or world has no dust storms or windy-day dust. */
    val active = config.intensity.rare > 0.0 && body.terrain != null && body.atmosphere != null && frameWeather.dustPossible

    private val haboobs = active && frameWeather.haboobsPossible
    private val worldStorms = active && frameWeather.climate.stormCloud == CloudType.DUST

    private val weather by lazy(makeWeather)

    // --- haze ----------------------------------------------------------------------

    /** How thick the dust round the camera is, 0..1, eased so it never pops. */
    var haze = 0.0
        private set

    /** The dust's colour, from the ground under the camera, rgb. */
    val colour = floatArrayOf(0.62f, 0.48f, 0.34f)

    /** The camera's height over the ground (or sea), metres, with the ground looked up about once a second or 500 m. */
    var height = 0.0
        private set

    /** The same, for the listing and the veil. */
    val aboveGround: Double get() = height

    private var ground = 0.0
    private var groundAt = Double.NEGATIVE_INFINITY
    private val groundFrom = Vec3()

    private var hazeAt = Double.NaN
    private var colourAt = Double.NEGATIVE_INFINITY
    private val up = Vec3()

    /** How high over the ground the dust round the camera reaches, metres, as an even layer, eased with [haze]. */
    var top = 0.0
        private set

    /** Dust in a low layer under a camera above it and the layer's top, as the worker last found. */
    @Volatile private var below = 0.0
    @Volatile private var belowTop = 0.0
    @Volatile private var belowLook = 1.0

    /**
     * Eases [haze] toward the dust in [air] (the camera's own sample at [time]) and the layer
     * under it, and lists haboobs and the veil when due. Nothing per frame with no dust about.
     */
    fun update(camera: Vec3, time: Double, air: AirSample) {
        if (!active) return
        if (kotlin.math.abs(time - groundAt) >= GROUND_EVERY || camera.distanceTo(groundFrom) >= GROUND_MOVED) {
            groundAt = time; groundFrom.setTo(camera)
            val terrain = body.terrain!!
            ground = kotlin.math.max(terrain.elevation(up.setTo(camera).normalizeInPlace()), if (terrain.hasOcean) 0.0 else -1e9)
        }
        height = camera.length - body.radius - ground
        val target = kotlin.math.max(air.dust, below)
        if (target <= 0.0 && haze <= 0.0) {
            hazeAt = time
        } else {
            val step = if (hazeAt.isNaN()) 1.0 else (kotlin.math.abs(time - hazeAt) / HAZE_EASE).coerceAtMost(1.0)
            hazeAt = time
            haze += (target - haze) * step
            val lookTarget = if (air.dust >= below) (if (air.dust > 0.0) (air.dustLook / air.dust).coerceIn(0.0, 1.0) else 1.0) else belowLook
            looks += (lookTarget - looks) * step
            val topTarget = if (air.dust >= below) air.dustTop else belowTop
            top = if (haze <= 0.0) 0.0 else if (top <= 0.0) topTarget else top + (topTarget - top) * step
            if (haze < 1e-3 && target <= 0.0) haze = 0.0
            if (kotlin.math.abs(time - colourAt) >= COLOUR_EVERY) {
                colourAt = time
                up.setTo(camera).normalizeInPlace()
                val terrain = body.terrain!!
                groundColour(terrain.material(up, terrain.elevation(up), 0.0), colour)
                // In a world storm, the world's own dust, whatever the ground here.
                val w = ((1.0 - looks) / (1.0 - WORLD_LOOK)).coerceIn(0.0, 1.0).toFloat()
                for (i in 0..2) colour[i] += (worldColour[i] - colour[i]) * w
            }
        }
        list(camera, time)
    }

    /**
     * How far the camera sees in its dust, in metres, as the fog's visibility (where things are
     * almost gone). Infinite with none.
     */
    val sight: Double get() = (haze * looks).let { if (it <= 1e-4) Double.POSITIVE_INFINITY else DUST_SIGHT / it }

    /** How much of [haze] counts for sight, eased: 1, but a world storm's much less. */
    private var looks = 1.0

    /** How much of the fog's colour is dust, 0..1. */
    val tint: Double get() = smooth(0.0, 0.25, haze * looks)

    /** How much the dust hides the sky, 0..1: some in a windy day's haze, nearly all in a thick storm. */
    val skyHidden: Double get() = smooth(0.03, 0.55, haze) * 0.97

    /** How much sunlight the dust takes, 0..1. */
    val dimming: Double get() = 0.55 * smooth(0.05, 0.45, haze)

    // --- listing ---------------------------------------------------------------------

    /** One haboob near the camera, as listed: its columns along the face and the ground under each. */
    class Wall(
        val dust: LiveDust,
        val listedAt: Double,
        val key: Long,
        /** Unit, tangent at its centre: across its face. */
        val right: Vec3,
        /** Per column: body-fixed ground points (x, y, z), how much dust the ground gives, rgb. */
        val ground: DoubleArray,
        val dry: DoubleArray,
        val colours: FloatArray,
        /** The same a little way behind the face, for the dust's body. */
        val backGround: DoubleArray,
        val backDry: DoubleArray,
    )

    @Volatile var walls: List<Wall> = emptyList()
        private set

    /** Every dust storm on the world, as last listed for the map and the veil. */
    @Volatile var all: List<LiveDust> = emptyList()
        private set

    /** The haboobs and a world storm as a veil from high up and on the map, or null for none. */
    @Volatile var veil: CloudShell? = null
        private set

    @Volatile private var listing = false
    private var listedAt = Double.NEGATIVE_INFINITY
    private val listedFrom = Vec3()
    private var allAt = Double.NEGATIVE_INFINITY
    private var allNanos = 0L
    private var allWanted = false
    private var mapAt = Double.NEGATIVE_INFINITY

    /** Columns along a haboob's face. */
    val columns: Int get() = when (tier) { QualityTier.LOW -> 8; QualityTier.MEDIUM -> 12; QualityTier.HIGH -> 18 }

    /** At most this many haboobs drawn, nearest first. */
    val most: Int get() = when (tier) { QualityTier.LOW -> 2; QualityTier.MEDIUM -> 3; QualityTier.HIGH -> 4 }

    /** How far away walls are drawn, in metres. */
    val reach: Double get() = when (tier) { QualityTier.LOW -> 60_000.0; QualityTier.MEDIUM -> 80_000.0; QualityTier.HIGH -> 100_000.0 }

    /** The most items drawn at once. */
    val itemsMost: Int get() = most * (columns * PIECES + columns / 2)

    private fun list(camera: Vec3, time: Double) {
        if (listing) return
        // Everything keyed on the height over the ground, as the veil's shown.
        val altitude = height
        // Low down with no haboobs, or high up, nothing's listed: let the layer's dust go.
        if ((!haboobs && altitude <= BELOW_FROM) || altitude >= LIST_TOP) below = 0.0
        // Low down and off the map, the veil's not shown: let it go, so nothing's done for it.
        if (altitude < VEIL_FROM && kotlin.math.abs(time - mapAt) > MAP_KEEP && veil != null) veil = null
        val nearDue = (haboobs || altitude > BELOW_FROM) && altitude < LIST_TOP &&
            (kotlin.math.abs(time - listedAt) >= RELIST_SECONDS || camera.distanceTo(listedFrom) >= RELIST_DISTANCE)
        val now = com.rm.apogee.platform.System.nanoTime()
        val allDue = (allWanted || altitude > VEIL_FROM) && kotlin.math.abs(time - allAt) >= WORLD_RELIST_SECONDS &&
            (allNanos == 0L || now - allNanos >= WORLD_RELIST_NANOS)
        if (!nearDue && !allDue) {
            if (altitude >= LIST_TOP && walls.isNotEmpty()) walls = emptyList()
            return
        }
        if (nearDue) { listedAt = time; listedFrom.setTo(camera) }
        if (allDue) { allAt = time; allNanos = now; allWanted = false }
        val from = camera.copy()
        listing = true
        scope.launch(kotlinx.coroutines.Dispatchers.Default) {
            try {
                if (nearDue) {
                    walls = if (haboobs) listWalls(from, time) else emptyList()
                    below = layerBelow(from, time, walls)
                    belowTop = sample.dustTop
                    belowLook = if (sample.dust > 0.0) (sample.dustLook / sample.dust).coerceIn(0.0, 1.0) else 1.0
                }
                if (allDue) {
                    val live = ArrayList<LiveDust>()
                    weather.dustStorms(Vec3.unitY(), time, Double.MAX_VALUE, live)
                    all = live
                    veil = DustVeil.build(live, body.radius, VEIL_REVISIONS.incrementAndGet().toInt(),
                        dryness = { weather.dustiness(it) },
                        ground = { kotlin.math.max(body.terrain?.elevation(it) ?: 0.0, 0.0) },
                        worldCover = if (worldStorms) { d -> weather.worldDustCover(d, time) } else null,
                        colour = worldColour)
                }
            } finally {
                listing = false
            }
        }
    }

    /** The dust storms on the whole world for the map, as last listed, listing afresh when due. */
    fun forMap(camera: Vec3, time: Double): List<LiveDust> {
        if (!active) return emptyList()
        allWanted = true
        mapAt = time
        list(camera, time)
        return all
    }

    /** The veil's colour: a dusty world's red, or desert sand. */
    private val worldColour = if (worldStorms) floatArrayOf(0.74f, 0.50f, 0.34f) else floatArrayOf(0.78f, 0.64f, 0.46f)

    private fun listWalls(camera: Vec3, time: Double): List<Wall> {
        val centre = camera.copy().normalizeInPlace()
        val live = ArrayList<LiveDust>()
        weather.dustStorms(centre, time, reach, live)
        val terrain = body.terrain!!
        val near = live.filter { it.kind == DustKind.HABOOB && it.strength > 0.01 }
            .sortedBy { kotlin.math.max(0.0, it.centre.distanceTo(centre) * body.radius - it.halfWidth) }
            .take(most)
        val n = columns
        val back = n / 2
        val p = Vec3()
        return near.map { d ->
            val right = Vec3().setTo(d.heading).crossInPlace(d.centre).normalizeInPlace()
            val ground = DoubleArray(3 * n); val dry = DoubleArray(n); val colours = FloatArray(3 * n)
            for (i in 0 until n) {
                val q = -1.0 + (2.0 * i + 1.0) / n
                facePoint(d, right, q, 0.0, p)
                val h = kotlin.math.max(terrain.elevation(p), if (terrain.hasOcean) 0.0 else -1e9)
                val r = body.radius + h
                ground[3 * i] = p.x * r; ground[3 * i + 1] = p.y * r; ground[3 * i + 2] = p.z * r
                dry[i] = weather.dustiness(p)
                val c = FloatArray(3)
                groundColour(terrain.material(p, h, 0.0), c)
                colours[3 * i] = c[0]; colours[3 * i + 1] = c[1]; colours[3 * i + 2] = c[2]
            }
            val backGround = DoubleArray(3 * back); val backDry = DoubleArray(back)
            for (i in 0 until back) {
                val q = -1.0 + (2.0 * i + 1.0) / back
                facePoint(d, right, q, BACK, p)
                val h = kotlin.math.max(terrain.elevation(p), if (terrain.hasOcean) 0.0 else -1e9)
                val r = body.radius + h
                backGround[3 * i] = p.x * r; backGround[3 * i + 1] = p.y * r; backGround[3 * i + 2] = p.z * r
                backDry[i] = weather.dustiness(p)
            }
            Wall(d, time, Noise.hashInt(0xD057, d.id.toInt(), (d.id ushr 32).toInt(), 0).toLong() and 0xffffL,
                right, ground, dry, colours, backGround, backDry)
        }
    }

    /** Unit point on [d]'s face at [q] across (-1..1), [behind] metres back from it. */
    private fun facePoint(d: LiveDust, right: Vec3, q: Double, behind: Double, out: Vec3): Vec3 {
        val half = d.halfWidth
        val back = LiveDust.BOW * half * q * q + behind
        return out.setTo(d.centre).addScaledInPlace(right, q * half / body.radius).addScaledInPlace(d.heading, -back / body.radius).normalizeInPlace()
    }

    private val sample = AirSample()

    /**
     * Dust in a low layer under a camera above it, as thick as it looks through the layer: none
     * near a haboob (its wall and veil show it) or high up.
     */
    private fun layerBelow(camera: Vec3, time: Double, walls: List<Wall>): Double {
        val terrain = body.terrain ?: return 0.0
        val d = camera.copy().normalizeInPlace()
        val ground = kotlin.math.max(terrain.elevation(d), if (terrain.hasOcean) 0.0 else -1e9)
        val above = camera.length - body.radius - ground
        if (above < BELOW_FROM || above > BELOW_TOP) return 0.0
        for (w in walls) if (w.dust.centre.distanceTo(d) * body.radius < w.dust.reach) return 0.0
        weather.sample(Vec3().setTo(d).mulInPlace(body.radius + ground + 30.0), time, sample)
        if (sample.dust <= 0.0) return 0.0
        // The fog counts only the way through the layer, so its own thickness.
        return sample.dust * (1.0 - smooth(0.5 * BELOW_TOP, BELOW_TOP, above))
    }

    // --- drawing ---------------------------------------------------------------------

    private val puffs = Array(CloudShapes.VARIANTS) { CloudPuff(it, false, 1) }
    private val nearPuffs = Array(CloudShapes.VARIANTS) { CloudPuff(it, false, 2) }
    private val point = Vec3()
    private val pointUp = Vec3()
    private val shift = Vec3()
    private val upright = Quat()
    private val turn = Quat()

    /** How much of the walls to draw from a camera [altitude] metres up, 0..1: none from where the veil has them. */
    fun wallWeight(altitude: Double): Double = 1.0 - smooth(PUFFS_FADE, LIST_TOP, altitude)

    /** How much of the veil to draw from a camera [above] metres over the ground, 0..1: none low down. */
    fun veilAlpha(above: Double): Float = smooth(VEIL_LOW, VEIL_FULL, above).toFloat()

    /**
     * Haboobs' walls near body-fixed [camera], turned to where the planet is at [bodyRotation], to
     * [out], in items from [pool] (new ones without). Carried on along their heading since listed.
     */
    fun append(camera: Vec3, bodyRotation: Quat, time: Double, out: MutableList<RenderItem>, pool: RenderItemPool? = null) {
        val list = walls
        if (list.isEmpty()) return
        val weight = wallWeight(height)
        if (weight <= 0.01) return
        for (w in list) draw(w, camera, bodyRotation, time, weight, out, pool)
    }

    private fun draw(w: Wall, camera: Vec3, bodyRotation: Quat, time: Double, weight: Double, out: MutableList<RenderItem>, pool: RenderItemPool?) {
        val d = w.dust
        val since = time - w.listedAt
        // Carried on along its heading since listed, growing and fading by its life.
        shift.setTo(d.heading).mulInPlace(d.speed * since)
        val age = d.age + since
        if (age >= d.life) return
        val n = w.dry.size
        val spacing = 2.0 * d.halfWidth / n
        val height = d.height
        for (i in 0 until n + w.backDry.size) {
            val front = i < n
            val k = if (front) i else i - n
            val g = if (front) w.ground else w.backGround
            point.setTo(g[3 * k], g[3 * k + 1], g[3 * k + 2]).addInPlace(shift)
            val far = point.distanceTo(camera)
            if (far > reach) continue
            val q = if (front) -1.0 + (2.0 * k + 1.0) / n else -1.0 + (2.0 * k + 1.0) / w.backDry.size
            val dry = if (front) w.dry[k] else w.backDry[k]
            val edge = 1.0 - smooth(0.75, 1.0, kotlin.math.abs(q))
            val fade = (1.0 - smooth(0.7 * reach, reach, far)) * smooth(NEAR_CLEAR, NEAR_SOLID, far) * weight
            // Dense: even a middling wall is nearly opaque.
            val alpha = (d.strength * edge * dry).coerceAtLeast(0.0).pow(0.3) * fade
            if (alpha < 0.02) continue
            pointUp.setTo(point).normalizeInPlace()
            fromTo(UNIT_Y, pointUp, upright)
            val slot = if (front) k else 64 + k
            val closeUp = far < 0.35 * reach && tier != QualityTier.LOW
            val pieces = if (!front) 1 else if (closeUp || tier == QualityTier.HIGH) PIECES else 2
            val ci = if (front) k else (k * n / w.backDry.size).coerceAtMost(n - 1)
            for (j in 0 until pieces) {
                val h = hash(slot * 7 + j + w.key.toInt())
                // Each billow swells and settles on its own slow beat, so the wall rolls.
                val roll = sin(2.0 * PI * time / (ROLL_PERIOD * (0.7 + 0.6 * h)) + 6.0 * h)
                val wide: Double; val tall: Double; val lift: Double; val a: Float
                if (front) {
                    // The top billows vary most, so the top edge is lumpy.
                    val vary = if (j == PIECES - 1) 0.5 else 0.2
                    tall = height * PIECE_TALL[j] * (1.0 - 0.5 * vary + vary * hash(slot * 17 + j)) * (1.0 + 0.08 * roll)
                    // The top billows are round heads, not flat discs, however wide the columns.
                    val across = spacing * PIECE_WIDE[j] * (0.85 + 0.3 * h) * (1.0 + 0.06 * roll)
                    wide = if (j == PIECES - 1) kotlin.math.min(across, TOP_ROUND * tall) else across
                    lift = height * PIECE_LIFT[j] * (1.0 - 0.5 * vary + vary * hash(slot * 13 + j)) + height * 0.03 * roll
                    a = PIECE_ALPHA[j]
                } else {
                    // The dust's body behind the face: lower, wider and thinner.
                    wide = 2.0 * spacing * (0.9 + 0.2 * h)
                    tall = height * 0.35
                    lift = height * 0.25
                    a = 0.85f
                }
                val item = pool?.take() ?: RenderItemPool.blank()
                // The top billows lean out a little ahead of the foot, as a rolling front does.
                val ahead = if (front) PIECE_AHEAD[j] * spacing else 0.0
                bodyRotation.rotate(pointUp.mulInPlace(lift).addInPlace(point).addScaledInPlace(d.heading, ahead), item.position)
                pointUp.setTo(point).normalizeInPlace()
                Quat.fromAxisAngle(UNIT_Y, 2.0 * PI * h, turn)
                item.rotation.setTo(bodyRotation).mulInPlace(upright).mulInPlace(turn)
                val c = item.ownColour()
                // Dark in the shadowed foot, lighter up in the billows.
                val pale = if (front) PIECE_PALE[j] else 0.7f
                c[0] = w.colours[3 * ci] * pale; c[1] = w.colours[3 * ci + 1] * pale; c[2] = w.colours[3 * ci + 2] * pale
                c[3] = (alpha * a).toFloat().coerceAtMost(0.97f)
                val shape = if (closeUp) nearPuffs[(slot + j) % nearPuffs.size] else puffs[(slot + j) % puffs.size]
                out.add(item.refill(shape, c, StackCaps.BOTH, item.ownScale().setTo(wide, tall, wide),
                    -WALL_AMBIENT, true, RenderItem.cloudKey(DUST_KEY xor (w.key shl 16), 4 * slot + j), orderedTransparency = true))
            }
        }
    }

    /** The shortest turn taking unit [a] to unit [b], into [out], without making anything. */
    private fun fromTo(a: Vec3, b: Vec3, out: Quat): Quat {
        val d = a dot b
        if (d <= -1.0 + 1e-9) return out.setTo(1.0, 0.0, 0.0, 0.0)
        return out.setTo(a.y * b.z - a.z * b.y, a.z * b.x - a.x * b.z, a.x * b.y - a.y * b.x, 1.0 + d).normalizeInPlace()
    }

    // --- hearing -------------------------------------------------------------------

    /** How loud the dust's hiss and roar is, 0..1, and how close the sand is (near the ground, thick), 0..1, after [heard]. */
    var loudness = 0.0
        private set
    var closeness = 0.0
        private set

    private var heardAt = Double.NaN

    /** Works out [loudness] and [closeness] at [time] from the haze round the camera and the [wind] there, m/s. */
    fun heard(time: Double, wind: Double) {
        val step = if (heardAt.isNaN()) 1.0 else (kotlin.math.abs(time - heardAt) / HEARD_EASE).coerceAtMost(1.0)
        heardAt = time
        loudness += (hiss(haze, wind) - loudness) * step
        closeness += (sandNear(haze, height) - closeness) * step
        if (loudness < 1e-3) loudness = 0.0
    }

    companion object {
        /** How loud blowing dust is, 0..1, for [dust] round the ears in [wind] m/s. */
        fun hiss(dust: Double, wind: Double): Double =
            easeOut(HISS_LEAST, HISS_FULL, dust) * (0.35 + 0.65 * smooth(4.0, 22.0, wind))

        /** How much of it is sand close by, 0..1: thick dust near the ground. */
        fun sandNear(dust: Double, height: Double): Double =
            smooth(0.25, 0.7, dust) * (1.0 - smooth(15.0, 300.0, height))

        /** The colour dust off ground [m] takes, into [out]. */
        fun groundColour(m: SurfaceMaterial, out: FloatArray) {
            val (r, g, b) = when (m) {
                SurfaceMaterial.SAND, SurfaceMaterial.ORGANIC_SAND -> Triple(0.66f, 0.54f, 0.38f)
                SurfaceMaterial.RED_DUST, SurfaceMaterial.CLAY -> Triple(0.62f, 0.40f, 0.27f)
                SurfaceMaterial.REGOLITH, SurfaceMaterial.SCREE, SurfaceMaterial.ROCK, SurfaceMaterial.BASALT, SurfaceMaterial.EJECTA ->
                    Triple(0.52f, 0.48f, 0.43f)
                SurfaceMaterial.THOLIN -> Triple(0.66f, 0.50f, 0.34f)
                SurfaceMaterial.SALT -> Triple(0.72f, 0.66f, 0.56f)
                else -> Triple(0.58f, 0.46f, 0.34f)
            }
            out[0] = r; out[1] = g; out[2] = b
        }

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

        /** Sight in the thickest dust, metres, as the model has it. */
        const val DUST_SIGHT = 150.0
        /** How much of a world storm's dust counts for sight. */
        private const val WORLD_LOOK = LiveDust.WORLD_LOOK
        /** Seconds the haze takes to follow the air. */
        const val HAZE_EASE = 1.5
        const val COLOUR_EVERY = 1.0

        /** The hiss is heard from this much dust, fully at [HISS_FULL]. */
        const val HISS_LEAST = 0.03
        const val HISS_FULL = 0.6
        const val HEARD_EASE = 0.5

        /** Haboobs near the camera listed again this often, seconds, or after it moves this far, metres; none above [LIST_TOP]. */
        const val RELIST_SECONDS = 2.0
        const val RELIST_DISTANCE = 2_000.0
        const val LIST_TOP = 35_000.0
        /** Walls fade out from here up as the veil takes over, metres. */
        const val PUFFS_FADE = 15_000.0
        /** The veil and the world's listing: from this high, or for the map, every so many game seconds and at most once a real second. */
        const val VEIL_FROM = 8_000.0
        const val VEIL_LOW = 8_000.0
        const val VEIL_FULL = 20_000.0
        /** The veil's kept this many game seconds after the map last asked for it. */
        const val MAP_KEEP = 2.0
        /** The ground under the camera looked up again this often, seconds, or after it moves this far, metres. */
        const val GROUND_EVERY = 1.0
        const val GROUND_MOVED = 500.0
        const val WORLD_RELIST_SECONDS = 20.0
        const val WORLD_RELIST_NANOS = 1_000_000_000L

        /** Dust in the layer under a camera this far over the ground is looked at, metres. */
        const val BELOW_FROM = 300.0
        const val BELOW_TOP = 15_000.0

        /** Pieces per column of a wall: foot, body, top billow. Widths in column spacings, heights, lifts in wall heights. */
        const val PIECES = 3
        val PIECE_WIDE = doubleArrayOf(1.0, 0.75, 0.45)
        val PIECE_TALL = doubleArrayOf(0.38, 0.36, 0.34)
        val PIECE_LIFT = doubleArrayOf(0.15, 0.55, 0.88)
        val PIECE_AHEAD = doubleArrayOf(0.0, 0.1, 0.22)
        val PIECE_ALPHA = floatArrayOf(1.0f, 0.97f, 0.9f)
        val PIECE_PALE = floatArrayOf(0.6f, 0.75f, 0.88f)
        /** A top billow is at most this many times as wide as it's tall. */
        const val TOP_ROUND = 1.4
        /** The body's row, metres behind the face. */
        const val BACK = 2_500.0
        /** Each billow swells and settles over about this many seconds. */
        const val ROLL_PERIOD = 30.0
        /** Walls clear within this many metres of the camera, solid from [NEAR_SOLID]: inside, the haze does it. */
        const val NEAR_CLEAR = 300.0
        const val NEAR_SOLID = 1_500.0
        /** Low, so the wall is lit mostly by the sun and stays dark and heavy. */
        const val WALL_AMBIENT = 0.2f
        /** Keys for the walls' items, apart from the clouds', the spray's and the quake dust's. */
        const val DUST_KEY = 0xD0570L

        val VEIL_REVISIONS = com.rm.apogee.platform.AtomicLong(1_000_000L)
        private val UNIT_Y = Vec3(0.0, 1.0, 0.0)
    }
}
