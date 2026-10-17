package com.rm.apogee.game

import com.rm.apogee.core.math.Quat
import com.rm.apogee.core.math.Vec3
import com.rm.apogee.core.orbit.CelestialBody
import com.rm.apogee.core.terrain.SurfaceMaterial
import com.rm.apogee.core.weather.LiveQuake
import com.rm.apogee.core.weather.Weather
import com.rm.apogee.core.weather.WeatherConfig
import com.rm.apogee.render.CloudPuff
import com.rm.apogee.render.CloudShapes
import com.rm.apogee.render.QualityTier
import com.rm.apogee.render.RenderItem
import com.rm.apogee.render.RenderItemPool
import com.rm.apogee.render.StackCaps
import kotlinx.coroutines.launch
import kotlin.concurrent.Volatile
import kotlin.math.cos
import kotlin.math.sin

/**
 * Earthquakes for seeing, hearing and the map: the camera shaking near shaking ground, dust off
 * steep and loose ground round it, a low rumble, and each live shock on the map with its reach.
 *
 * The shaking at the camera's ground point is looked up every frame on the frame thread from
 * [frameWeather], only while a live or coming shock reaches the camera, which a worker checks
 * about once a second while any quake is live on the world. Dust is listed on the worker about
 * once a second while the shaking is strong, the map's listing every few seconds while it's open.
 */
class QuakeScene(
    private val body: CelestialBody,
    private val config: WeatherConfig,
    private val tier: QualityTier,
    private val scope: kotlinx.coroutines.CoroutineScope,
    /** For the shaking at the camera, on the frame thread. */
    private val frameWeather: Weather,
    /** Makes the worker's own weather; a weather's caches aren't thread safe. */
    makeWeather: () -> Weather = { Weather(body, config) },
) {
    /** Off where the setting has no rare events or the world has no ground. */
    val active = config.intensity.rare > 0.0 && body.terrain != null

    private val weather by lazy(makeWeather)

    /** A live shock for the map, with its felt ring and the front of its waves as unit, body-fixed points. */
    class MapEntry(val quake: LiveQuake, val ring: List<Vec3>, val front: List<Vec3>)

    /** Every shock of the last hour on the world, as last listed for the map. Empty until the map asks. */
    @Volatile var all: List<MapEntry> = emptyList()
        private set

    /** How hard the ground under the camera shakes this frame, m/s^2, at the height of its swings. */
    var level = 0.0
        private set

    /** The camera's height over the ground under it this frame, metres. */
    var height = 0.0
        private set

    /** How much the camera shakes, 0..1, eased. */
    var shake = 0.0
        private set

    @Volatile private var listing = false
    private var dustAt = Double.NEGATIVE_INFINITY
    private val dustFrom = Vec3()
    // Whether any shaking reaches the camera, as the worker last found, and when and where it looked.
    @Volatile private var felt = false
    private var checkAt = Double.NEGATIVE_INFINITY
    private val checkFrom = Vec3()
    private var allAt = Double.NEGATIVE_INFINITY
    private var shakeAt = Double.NaN
    private val up = Vec3()
    private val groundVelocity = Vec3()

    /**
     * Works out the shaking under a camera at body-fixed [camera] at [time], and lists dust and
     * the map's shocks when due. Nothing but a quick check while no quake is live, or while none
     * live or coming soon reaches the camera, as the worker last found.
     */
    fun update(camera: Vec3, time: Double) {
        if (!active) return
        val live = frameWeather.quakesLive(time)
        val checkDue = live && (kotlin.math.abs(time - checkAt) >= RELIST_SECONDS || camera.distanceTo(checkFrom) >= CHECK_DISTANCE)
        if (!live || !felt) {
            level = 0.0
            ease(0.0, time)
        } else {
            up.setTo(camera).normalizeInPlace()
            val terrain = body.terrain!!
            val ground = terrain.elevation(up)
            height = camera.length - body.radius - ground
            // The sea doesn't carry it to anything on it.
            if (terrain.hasOcean && ground < 0.0 && camera.length - body.radius > ground + UNDER_SEA_FLOOR) {
                level = 0.0
            } else {
                frameWeather.quakeShake(up, time, groundVelocity)
                level = frameWeather.quakeLevel()
            }
            ease(shakeAmount(level, height), time)
        }
        if (!live) felt = false
        val dustDue = live && felt && level >= DUST_LEAST && height < DUST_ABOVE &&
            (kotlin.math.abs(time - dustAt) >= RELIST_SECONDS || camera.distanceTo(dustFrom) >= RELIST_DISTANCE)
        if (!dustDue && !checkDue || listing) return
        if (checkDue) { checkAt = time; checkFrom.setTo(camera) }
        if (dustDue) { dustAt = time; dustFrom.setTo(camera) }
        val at = camera.copy()
        listing = true
        scope.launch(kotlinx.coroutines.Dispatchers.Default) {
            try {
                if (checkDue) felt = reaches(at, time)
                if (dustDue) dust = dustAt(at, time)
            } finally {
                listing = false
            }
        }
    }

    /** Whether shaking enough to hear reaches body-fixed [camera] at [time] or within the next check. */
    private fun reaches(camera: Vec3, time: Double): Boolean =
        weather.quakeShakeIn(camera.copy().normalizeInPlace(), time, CHECK_LOOK, RUMBLE_LEAST) <= CHECK_LOOK

    private fun ease(target: Double, time: Double) {
        val step = if (shakeAt.isNaN()) 1.0 else (kotlin.math.abs(time - shakeAt) / SHAKE_EASE).coerceAtMost(1.0)
        shakeAt = time
        shake += (target - shake) * step
        if (shake < 1e-3) shake = 0.0
    }

    /** The shocks on the whole world for the map at [time], as last listed, listing them afresh on a worker when due. */
    fun forMap(time: Double): List<MapEntry> {
        if (!active || listing || kotlin.math.abs(time - allAt) < WORLD_RELIST_SECONDS) return all
        allAt = time
        // One worker at a time: they share the worker's weather.
        listing = true
        scope.launch(kotlinx.coroutines.Dispatchers.Default) {
            try { all = listAll(time) } finally { listing = false }
        }
        return all
    }

    private fun listAll(time: Double): List<MapEntry> {
        val live = ArrayList<LiveQuake>()
        weather.quakes(Vec3.unitY(), time, Double.MAX_VALUE, live)
        return live.map { q ->
            val ring = if (q.id == q.mainId && q.reach > 0.0) circle(q.epicentre, q.reach) else emptyList()
            val travelled = q.age * WAVE_SPEED
            val front = if (travelled > 0.0 && travelled < q.reach) circle(q.epicentre, travelled) else emptyList()
            MapEntry(q, ring, front)
        }
    }

    /** Points round a circle [radius] metres over the ground from unit [centre], closed. */
    private fun circle(centre: Vec3, radius: Double): List<Vec3> = circle(centre, radius, body.radius)

    // --- camera ----------------------------------------------------------------

    private val shakeTurn = Quat()
    private val shakeAxis = Vec3()
    private val shakeOffset = Vec3()

    /**
     * Shakes a camera at [position] looking along [rotation] (both in the frame's own space) by
     * [shake] at [time]: a few cm of jolting and a fraction of a degree of turn, quicker than the
     * ground's own swing so it reads as a jolt. Nothing while still.
     */
    fun shakeCamera(position: Vec3, rotation: Quat, time: Double) {
        val s = shake
        if (s <= 0.0) return
        val t = time
        // Three sines a channel at uneven rates, so it never settles into a beat.
        fun wobble(k: Int): Double {
            val p = 1.7 * k
            return 0.5 * sin(2.0 * kotlin.math.PI * 4.7 * t + p) + 0.3 * sin(2.0 * kotlin.math.PI * 6.9 * t + 2.3 * p + 1.1) +
                0.2 * sin(2.0 * kotlin.math.PI * 9.3 * t + 3.1 * p + 2.0)
        }
        shakeOffset.setTo(wobble(0), wobble(1), 0.6 * wobble(2)).mulInPlace(s * SHAKE_METRES)
        rotation.rotate(shakeOffset, shakeOffset)
        position.addInPlace(shakeOffset)
        val pitch = wobble(3) * s * SHAKE_RADIANS
        val roll = wobble(4) * s * SHAKE_RADIANS * 0.7
        Quat.fromAxisAngle(shakeAxis.setTo(1.0, 0.0, 0.0), pitch, shakeTurn)
        rotation.mulInPlace(shakeTurn)
        Quat.fromAxisAngle(shakeAxis.setTo(0.0, 0.0, 1.0), roll, shakeTurn)
        rotation.mulInPlace(shakeTurn)
    }

    // --- hearing ---------------------------------------------------------------

    /** How loud the rumble is, 0..1, after [heard]. */
    var loudness = 0.0
        private set

    /** How hard the shaking is, 0..1, for the rattle on top, after [heard]. */
    var closeness = 0.0
        private set

    private var heardAt = Double.NaN

    /** Works out [loudness] and [closeness] at [time] from the shaking under the camera, eased. */
    fun heard(time: Double) {
        val step = if (heardAt.isNaN()) 1.0 else (kotlin.math.abs(time - heardAt) / HEARD_EASE).coerceAtMost(1.0)
        heardAt = time
        loudness += (rumble(level, height) - loudness) * step
        closeness += ((if (level > 0.0) smooth(CLOSE_LEAST, CLOSE_FULL, level) else 0.0) - closeness) * step
        if (loudness < 1e-3) loudness = 0.0
    }

    // --- dust ------------------------------------------------------------------

    /** Dust as last listed: per slot, a body-fixed point on the ground, how strong, and its colour. */
    private class Dust(val time: Double, val count: Int, val slot: IntArray, val at: DoubleArray, val strength: DoubleArray, val colour: FloatArray)
    @Volatile private var dust: Dust? = null

    private val bearings = when (tier) { QualityTier.LOW -> 10; QualityTier.MEDIUM -> 14; QualityTier.HIGH -> 18 }

    /** Dust is looked for and drawn out to here round the camera, in metres. */
    val dustReach: Double get() = when (tier) { QualityTier.LOW -> 700.0; QualityTier.MEDIUM -> 1_000.0; QualityTier.HIGH -> 1_300.0 }

    /** At most this many puffs at once. */
    val dustMost: Int get() = when (tier) { QualityTier.LOW -> 8; QualityTier.MEDIUM -> 14; QualityTier.HIGH -> 20 }

    private val sample = Vec3()
    private val probe = Vec3()
    private val velocity = Vec3()

    /** The steep or loose ground round body-fixed [camera] where the shaking raises dust, the strongest first. */
    private fun dustAt(camera: Vec3, time: Double): Dust? {
        val terrain = body.terrain ?: return null
        val centre = camera.copy().normalizeInPlace()
        val east = Vec3(centre.z, 0.0, -centre.x).let { if (it.length < 1e-6) it.setTo(1.0, 0.0, 0.0) else it }.normalizeInPlace()
        val north = Vec3().setTo(centre).crossInPlace(east)
        val rings = DUST_RINGS.size
        val score = DoubleArray(rings * bearings)
        val points = DoubleArray(3 * rings * bearings)
        val colours = FloatArray(3 * rings * bearings)
        for (ring in 0 until rings) for (k in 0 until bearings) {
            val i = ring * bearings + k
            val r = DUST_RINGS[ring] * dustReach
            // Off the even bearings on alternate rings, nudged a little by the slot's own hash.
            val a = 2.0 * kotlin.math.PI * (k + 0.5 * (ring and 1) + 0.3 * (hash(i) - 0.5)) / bearings
            sample.setTo(centre).addScaledInPlace(east, r * cos(a) / body.radius).addScaledInPlace(north, r * sin(a) / body.radius).normalizeInPlace()
            val h = terrain.elevation(sample)
            if (terrain.hasOcean && h < DUST_SHORE) continue
            // The slope from the ground a few metres each way.
            val step = SLOPE_STEP / body.radius
            val he = terrain.elevation(probe.setTo(sample).addScaledInPlace(east, step).normalizeInPlace())
            val hn = terrain.elevation(probe.setTo(sample).addScaledInPlace(north, step).normalizeInPlace())
            val ge = (he - h) / SLOPE_STEP; val gn = (hn - h) / SLOPE_STEP
            val slope = 1.0 - 1.0 / kotlin.math.sqrt(1.0 + ge * ge + gn * gn)
            // Paving raises none; the packed earth round it does.
            val material = terrain.material(sample, h, slope)
            val dustiness = kotlin.math.max(smooth(STEEP_FROM, STEEP_FULL, slope), loose(material))
            if (dustiness <= 0.05) continue
            weather.quakeShake(sample, time, velocity)
            val shaking = easeOut(DUST_LEAST, DUST_FULL, weather.quakeLevel())
            val s = dustiness * shaking
            if (s <= 0.05) continue
            score[i] = s
            val level = body.radius + h
            points[3 * i] = sample.x * level; points[3 * i + 1] = sample.y * level; points[3 * i + 2] = sample.z * level
            dustColour(material, colours, 3 * i)
        }
        // The strongest, nearer ones first.
        val order = score.indices.filter { score[it] > 0.0 }.sortedByDescending { score[it] * (1.3 - DUST_RINGS[it / bearings]) }.take(dustMost)
        if (order.isEmpty()) return null
        val n = order.size
        val slot = IntArray(n); val at = DoubleArray(3 * n); val strength = DoubleArray(n); val colour = FloatArray(3 * n)
        for ((j, i) in order.withIndex()) {
            slot[j] = i; strength[j] = score[i]
            for (c in 0 until 3) { at[3 * j + c] = points[3 * i + c]; colour[3 * j + c] = colours[3 * i + c] }
        }
        return Dust(time, n, slot, at, strength, colour)
    }

    private val puffs = Array(CloudShapes.VARIANTS) { CloudPuff(it, false, 1) }
    private val nearPuffs = Array(CloudShapes.VARIANTS) { CloudPuff(it, false, 2) }
    private val dustOff = Vec3()
    private val dustAtFrame = Vec3()
    private val dustUp = Vec3()
    private val upright = Quat()
    private val turn = Quat()

    /**
     * Dust rising off the ground listed round body-fixed [camera], turned to where the planet is
     * at [bodyRotation], to [out], in items from [pool] (new ones without). Fades with the shaking
     * and its age, none past [dustReach].
     */
    fun append(camera: Vec3, bodyRotation: Quat, time: Double, out: MutableList<RenderItem>, pool: RenderItemPool? = null) {
        val d0 = dust ?: return
        val age = time - d0.time
        if (age < -1.0 || age > DUST_HOLD) return
        // Fed while the shaking lasts, settling once it's gone.
        val fade = (1.0 - smooth(DUST_HOLD * 0.4, DUST_HOLD, age)) * (0.35 + 0.65 * easeOut(0.0, DUST_FULL, level))
        val d = dustAtFrame
        for (i in 0 until d0.count) {
            d.setTo(d0.at[3 * i], d0.at[3 * i + 1], d0.at[3 * i + 2])
            val far = d.distanceTo(camera)
            if (far > dustReach) continue
            val alpha = (DUST_ALPHA * kotlin.math.sqrt(d0.strength[i]) * fade * (1.0 - smooth(dustReach * 0.7, dustReach, far))).coerceAtMost(DUST_MOST).toFloat()
            if (alpha < 0.02f) continue
            dustUp.setTo(d).normalizeInPlace()
            fromTo(UNIT_Y, dustUp, upright)
            val k = d0.slot[i]
            val near = far < dustReach * 0.5
            val pieces = if (tier == QualityTier.LOW) 2 else if (near) 3 else 2
            val strong = 0.7 + 0.6 * d0.strength[i]
            // Grows and rises a little over the listing's second, slot by slot out of step.
            val grow = 1.0 + 0.25 * ((age + hash(k)) % 1.0)
            for (j in 0 until pieces) {
                // A wide, solid foot hugging the ground, then smaller, thinner billows rising off it.
                val wide = DUST_WIDE * strong * grow * DUST_PIECE_WIDE[j] * (0.8 + 0.4 * hash(k + 101 * (j + 1)))
                val tall = DUST_TALL * strong * grow * DUST_PIECE_TALL[j]
                val item = pool?.take() ?: RenderItemPool.blank()
                val lift = DUST_TALL * strong * grow * DUST_PIECE_LIFT[j]
                // Each billow drifts a little off its foot.
                val drift = 0.25 * wide * j
                val sideways = 2.3 * k + 1.7 * j
                bodyRotation.rotate(dustUp.mulInPlace(lift).addInPlace(d), item.position)
                dustUp.setTo(d).normalizeInPlace()
                Quat.fromAxisAngle(UNIT_Y, sideways, turn)
                item.rotation.setTo(bodyRotation).mulInPlace(upright).mulInPlace(turn)
                if (drift > 0.0) {
                    dustOff.setTo(drift, 0.0, 0.0)
                    item.rotation.rotate(dustOff, dustOff)
                    item.position.addInPlace(dustOff)
                }
                val c = item.ownColour()
                // Paler as it rises and thins.
                val pale = 1f + 0.06f * j
                c[0] = d0.colour[3 * i] * pale; c[1] = d0.colour[3 * i + 1] * pale; c[2] = d0.colour[3 * i + 2] * pale
                c[3] = alpha * DUST_PIECE_ALPHA[j]
                val shape = if (near && tier != QualityTier.LOW) nearPuffs[(k + j) % nearPuffs.size] else puffs[(k + j) % puffs.size]
                out.add(item.refill(shape, c, StackCaps.BOTH, item.ownScale().setTo(wide, tall, wide),
                    -DUST_AMBIENT, true, RenderItem.cloudKey(DUST_KEY, 3 * k + j), orderedTransparency = true))
            }
        }
    }

    /** The shortest turn taking unit [a] to unit [b], into [out], without making anything. */
    private fun fromTo(a: Vec3, b: Vec3, out: Quat): Quat {
        val d = a dot b
        if (d <= -1.0 + 1e-9) return out.setTo(1.0, 0.0, 0.0, 0.0)
        return out.setTo(a.y * b.z - a.z * b.y, a.z * b.x - a.x * b.z, a.x * b.y - a.y * b.x, 1.0 + d).normalizeInPlace()
    }

    companion object {
        /** Unit points round a circle [radius] metres over the ground from unit [centre] on a world of [bodyRadius], closed. */
        fun circle(centre: Vec3, radius: Double, bodyRadius: Double): List<Vec3> {
            val e = Vec3(centre.z, 0.0, -centre.x).let { if (it.length < 1e-6) it.setTo(1.0, 0.0, 0.0) else it }.normalizeInPlace()
            val n = Vec3().setTo(centre).crossInPlace(e)
            val angle = radius / bodyRadius
            val c = cos(angle); val s = sin(angle)
            return List(RING_POINTS + 1) { k ->
                val b = 2.0 * kotlin.math.PI * k / RING_POINTS
                Vec3().setTo(centre).mulInPlace(c).addScaledInPlace(e, s * cos(b)).addScaledInPlace(n, s * sin(b)).normalizeInPlace()
            }
        }

        /**
         * How much the camera shakes, 0..1, for ground shaking [level] m/s^2 under a camera
         * [height] metres over it: from a light shake to full at [SHAKE_FULL], fading out from
         * [SHAKE_LOW] to [SHAKE_HIGH] up.
         */
        fun shakeAmount(level: Double, height: Double): Double =
            easeOut(SHAKE_LEAST, SHAKE_FULL, level) * (1.0 - smooth(SHAKE_LOW, SHAKE_HIGH, height))

        /** How loud the rumble is, 0..1, for shaking [level] m/s^2 under ears [height] metres over the ground. */
        fun rumble(level: Double, height: Double): Double =
            easeOut(RUMBLE_LEAST, RUMBLE_FULL, level) * (1.0 - smooth(RUMBLE_LOW, RUMBLE_HIGH, height))

        /** How readily [m] goes up as dust when shaken, 0..1. */
        fun loose(m: SurfaceMaterial): Double = when (m) {
            SurfaceMaterial.SAND, SurfaceMaterial.SCREE, SurfaceMaterial.REGOLITH, SurfaceMaterial.RED_DUST,
            SurfaceMaterial.ORGANIC_SAND, SurfaceMaterial.EJECTA, SurfaceMaterial.THOLIN -> 1.0
            SurfaceMaterial.DIRT, SurfaceMaterial.CLAY -> 0.7
            SurfaceMaterial.SNOW, SurfaceMaterial.FROST -> 0.6
            SurfaceMaterial.GRASS, SurfaceMaterial.SULFUR -> 0.3
            SurfaceMaterial.FOREST, SurfaceMaterial.MUD -> 0.15
            else -> 0.0
        }

        private fun dustColour(m: SurfaceMaterial, out: FloatArray, o: Int) {
            val (r, g, b) = when (m) {
                SurfaceMaterial.SAND, SurfaceMaterial.ORGANIC_SAND -> Triple(0.50f, 0.42f, 0.30f)
                SurfaceMaterial.SNOW, SurfaceMaterial.FROST -> Triple(0.92f, 0.94f, 0.97f)
                SurfaceMaterial.REGOLITH, SurfaceMaterial.SCREE, SurfaceMaterial.ROCK, SurfaceMaterial.BASALT, SurfaceMaterial.EJECTA ->
                    Triple(0.38f, 0.37f, 0.35f)
                SurfaceMaterial.RED_DUST, SurfaceMaterial.CLAY -> Triple(0.46f, 0.30f, 0.20f)
                SurfaceMaterial.THOLIN -> Triple(0.54f, 0.41f, 0.28f)
                SurfaceMaterial.SULFUR -> Triple(0.80f, 0.74f, 0.45f)
                else -> Triple(0.36f, 0.29f, 0.22f)
            }
            out[o] = r; out[o + 1] = g; out[o + 2] = b
        }

        private fun hash(i: Int): Double {
            var x = i * 0x2545F491 + 0x6A09E667
            x = x xor (x ushr 15); x *= 0x2C1B3C6D; x = x xor (x ushr 12)
            return (x.toLong() and 0xFFFF) / 65_536.0
        }

        /** 0 at [a], rising quickest at first, 1 from [b]. */
        private fun easeOut(a: Double, b: Double, x: Double): Double {
            val t = ((x - a) / (b - a)).coerceIn(0.0, 1.0)
            return t * (2.0 - t)
        }

        private fun smooth(a: Double, b: Double, x: Double): Double {
            val t = ((x - a) / (b - a)).coerceIn(0.0, 1.0)
            return t * t * (3 - 2 * t)
        }

        /** The camera shakes from this much ground shaking, m/s^2, fully at [SHAKE_FULL]. */
        const val SHAKE_LEAST = 0.2
        const val SHAKE_FULL = 3.0
        /** And fades out between these heights over the ground, in metres. */
        const val SHAKE_LOW = 15.0
        const val SHAKE_HIGH = 250.0
        /** At full shake: how far the camera jolts, in metres, and how far it turns, in radians (0.5 deg). */
        const val SHAKE_METRES = 0.08
        const val SHAKE_RADIANS = 0.0087
        /** Seconds the shake takes to follow the ground. */
        const val SHAKE_EASE = 0.15

        /** The rumble's heard from this much shaking, m/s^2, fully at [RUMBLE_FULL], fading out between these heights. */
        const val RUMBLE_LEAST = 0.1
        const val RUMBLE_FULL = 2.0
        const val RUMBLE_LOW = 60.0
        const val RUMBLE_HIGH = 1_500.0
        /** The rattle on top, from this much shaking to full. */
        const val CLOSE_LEAST = 1.0
        const val CLOSE_FULL = 6.0
        const val HEARD_EASE = 0.3

        /** A camera this far over the sea floor at sea feels nothing, in metres. */
        const val UNDER_SEA_FLOOR = 30.0

        /** Dust from this much shaking, m/s^2, fully at [DUST_FULL]; none looked for with the camera over [DUST_ABOVE] metres up. */
        const val DUST_LEAST = 0.8
        const val DUST_FULL = 3.0
        const val DUST_ABOVE = 2_500.0
        /** Ground lower than this over the sea, in metres, raises none. */
        const val DUST_SHORE = 1.0
        /** Slopes (1 - cos) raising dust, from some to full. */
        const val STEEP_FROM = 0.08
        const val STEEP_FULL = 0.3
        const val SLOPE_STEP = 8.0
        /** Rings dust is looked for on, as shares of the reach. */
        val DUST_RINGS = doubleArrayOf(0.08, 0.2, 0.35, 0.5, 0.7, 0.9)
        /** A dust puff's size in metres, its alpha for full strength and the most it takes, its light, and how long a listing shows, s. */
        const val DUST_WIDE = 60.0
        const val DUST_TALL = 20.0
        const val DUST_ALPHA = 1.6
        const val DUST_MOST = 0.95
        const val DUST_AMBIENT = 0.5f
        /** Per piece of a puff, foot first: width and height as shares of the above, lift over the ground in heights, alpha. */
        val DUST_PIECE_WIDE = doubleArrayOf(1.0, 0.7, 0.45)
        val DUST_PIECE_TALL = doubleArrayOf(1.0, 1.3, 1.5)
        val DUST_PIECE_LIFT = doubleArrayOf(0.2, 0.9, 1.8)
        val DUST_PIECE_ALPHA = floatArrayOf(1f, 0.92f, 0.8f)
        const val DUST_HOLD = 3.0
        /** Keys for the dust's items, apart from the clouds' and the spray's. */
        const val DUST_KEY = 0x9A4EL

        /** Worked out again this often, in seconds, or after the camera moves this far, in metres. */
        const val RELIST_SECONDS = 1.0
        const val RELIST_DISTANCE = 300.0
        /** Whether shaking reaches the camera is looked at again after it moves this far, in metres, looking this far ahead, in seconds. */
        const val CHECK_DISTANCE = 2_000.0
        const val CHECK_LOOK = 2.5
        /** The map's listing, in seconds. */
        const val WORLD_RELIST_SECONDS = 5.0
        /** How fast the shaking spreads, m/s, as the model has it. */
        const val WAVE_SPEED = 3_500.0
        /** Points round a ring on the map. */
        const val RING_POINTS = 72

        private val UNIT_Y = Vec3(0.0, 1.0, 0.0)
    }
}
