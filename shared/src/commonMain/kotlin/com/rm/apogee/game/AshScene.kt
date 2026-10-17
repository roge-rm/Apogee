package com.rm.apogee.game

import com.rm.apogee.core.math.Quat
import com.rm.apogee.core.math.Vec3
import com.rm.apogee.core.orbit.CelestialBody
import com.rm.apogee.core.terrain.Noise
import com.rm.apogee.core.weather.AirSample
import com.rm.apogee.core.weather.LiveEruption
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
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.pow
import kotlin.math.sin

/**
 * Volcanic ash for seeing and hearing: grey-brown haze closing in round a camera in ash with the
 * light dimmed under it, an eruption's dark column and the cloud it drifts downwind seen from
 * afar, a dull red glow at the vent by night, eruptions on the map, and the rumble and grit.
 *
 * The haze follows the camera's own air sample, eased, on the frame thread; with no ash there it
 * does nothing. Eruptions near the camera are listed on a worker every couple of seconds, and only
 * while one is going on somewhere on the world.
 */
class AshScene(
    private val body: CelestialBody,
    private val config: WeatherConfig,
    private val tier: QualityTier,
    private val scope: kotlinx.coroutines.CoroutineScope,
    /** For what's cheap to ask on the frame thread. */
    private val frameWeather: Weather,
    /** Makes the worker's own weather; a weather's caches aren't thread safe. */
    makeWeather: () -> Weather = { Weather(body, config) },
) {
    /** Off where the setting or world has no eruptions. */
    val active = config.intensity.rare > 0.0 && body.terrain != null && body.atmosphere != null && frameWeather.ashPossible

    private val weather by lazy(makeWeather)

    // --- haze ----------------------------------------------------------------------

    /** How thick the ash round the camera is, 0..1, eased so it never pops. */
    var haze = 0.0
        private set

    /** The ash's colour, rgb: a dark grey-brown. */
    val colour = floatArrayOf(0.38f, 0.34f, 0.30f)

    /** The camera's height over the ground (or sea), metres, looked up about once a second or 500 m while there's ash about. */
    var height = 0.0
        private set

    /** How high over the ground the ash round the camera reaches, metres, as an even layer, eased with [haze]. */
    var top = 0.0
        private set

    /** The thickest ash over the camera, 0..1, as the worker last found: what dims the light under a cloud. */
    @Volatile private var overhead = 0.0
    private var shade = 0.0

    private var ground = 0.0
    private var groundAt = Double.NEGATIVE_INFINITY
    private val groundFrom = Vec3()
    private var hazeAt = Double.NaN
    private val up = Vec3()

    /**
     * Eases [haze] toward the ash in [air] (the camera's own sample at [time]) and lists eruptions
     * when due. Nothing per frame with no ash about.
     */
    fun update(camera: Vec3, time: Double, air: AirSample) {
        if (!active) return
        val target = air.ash
        val dark = overhead
        if (target <= 0.0 && haze <= 0.0 && dark <= 0.0 && shade <= 0.0) {
            hazeAt = time
        } else {
            if (kotlin.math.abs(time - groundAt) >= GROUND_EVERY || camera.distanceTo(groundFrom) >= GROUND_MOVED) {
                groundAt = time; groundFrom.setTo(camera)
                val terrain = body.terrain!!
                ground = kotlin.math.max(terrain.elevation(up.setTo(camera).normalizeInPlace()), if (terrain.hasOcean) 0.0 else -1e9)
            }
            height = camera.length - body.radius - ground
            val step = if (hazeAt.isNaN()) 1.0 else (kotlin.math.abs(time - hazeAt) / HAZE_EASE).coerceAtMost(1.0)
            hazeAt = time
            haze += (target - haze) * step
            shade += (dark - shade) * step
            // In ash, the layer reaches at least a little over the camera, so the fog level with it
            // matches the model's sight.
            val topTarget = if (target > 0.0) kotlin.math.max(air.ashTop - ground, height + IN_ASH_OVER) else top
            top = if (haze <= 0.0) 0.0 else if (top <= 0.0) topTarget else top + (topTarget - top) * step
            if (haze < 1e-3 && target <= 0.0) { haze = 0.0; top = 0.0 }
            if (shade < 1e-3 && dark <= 0.0) shade = 0.0
        }
        list(camera, time)
    }

    /** How far the camera sees in its ash, in metres, as the fog's visibility. Infinite with none. */
    val sight: Double get() = if (haze <= 1e-4) Double.POSITIVE_INFINITY else ASH_SIGHT / haze

    /** How much of the fog's colour is ash, 0..1. */
    val tint: Double get() = smooth(0.0, 0.2, haze)

    /** How much sunlight the ash takes, 0..1: a lot in thick ash or under a thick cloud. */
    val dimming: Double get() = 0.65 * smooth(0.05, 0.6, kotlin.math.max(haze, OVERHEAD_SHARE * shade))

    // --- listing ---------------------------------------------------------------------

    /** One eruption near the camera, as listed: its cloud's thickness, top and width at stations along its way. */
    class Plume(
        val eruption: LiveEruption,
        val listedAt: Double,
        val key: Long,
        /** Unit, body-fixed: the way it drifts at the vent, and the normal of its great circle. */
        val along: Vec3,
        val side: Vec3,
        /** Per station, every [LiveEruption.length] / ([STATIONS] - 1) from the vent: ash 0..1, its top over datum, half width, ground. */
        val ash: DoubleArray,
        val top: DoubleArray,
        val width: DoubleArray,
        val ground: DoubleArray,
    ) {
        /** Unit point [s] metres downwind of the vent along its way, into [out]. */
        fun pointAt(s: Double, radius: Double, out: Vec3): Vec3 {
            val a = s / radius
            return out.setTo(eruption.vent).mulInPlace(cos(a)).addScaledInPlace(along, sin(a))
        }

        /** [values] at [s] metres along, between its stations. */
        fun at(values: DoubleArray, s: Double): Double {
            val n = values.size
            val len = eruption.length
            if (len <= 0.0) return values[0]
            val x = (s / len * (n - 1)).coerceIn(0.0, (n - 1).toDouble())
            val i = kotlin.math.min(x.toInt(), n - 2)
            val f = x - i
            return values[i] + (values[i + 1] - values[i]) * f
        }
    }

    @Volatile var plumes: List<Plume> = emptyList()
        private set

    /** Every eruption on the world, as last listed for the map. */
    @Volatile var all: List<LiveEruption> = emptyList()
        private set

    @Volatile private var listing = false
    private var listedAt = Double.NEGATIVE_INFINITY
    private val listedFrom = Vec3()
    private var allAt = Double.NEGATIVE_INFINITY
    private var allNanos = 0L
    private var allWanted = false

    /** Puffs along a cloud's way, and pieces up a column. */
    val puffs: Int get() = when (tier) { QualityTier.LOW -> 12; QualityTier.MEDIUM -> 20; QualityTier.HIGH -> 28 }
    val columnPieces: Int get() = when (tier) { QualityTier.LOW -> 8; QualityTier.MEDIUM -> 12; QualityTier.HIGH -> 16 }

    /** At most this many eruptions drawn, nearest first. */
    val most: Int get() = when (tier) { QualityTier.LOW -> 1; QualityTier.MEDIUM -> 2; QualityTier.HIGH -> 2 }

    /** How far away eruptions are drawn, in metres. */
    val reach: Double get() = when (tier) { QualityTier.LOW -> 150_000.0; QualityTier.MEDIUM -> 200_000.0; QualityTier.HIGH -> 250_000.0 }

    /** The most items drawn at once: the column, up to [ACROSS] puffs across the cloud and a billow and an ash fall under every other one, and the glow. */
    val itemsMost: Int get() = most * (columnPieces + puffs * (ACROSS + 1) + puffs / 2 + GLOWS)

    private fun list(camera: Vec3, time: Double) {
        if (listing) return
        val altitude = camera.length - body.radius
        val nearDue = altitude < LIST_TOP &&
            (kotlin.math.abs(time - listedAt) >= RELIST_SECONDS || camera.distanceTo(listedFrom) >= RELIST_DISTANCE)
        val now = com.rm.apogee.platform.System.nanoTime()
        val allDue = allWanted && kotlin.math.abs(time - allAt) >= WORLD_RELIST_SECONDS &&
            (allNanos == 0L || now - allNanos >= WORLD_RELIST_NANOS)
        if (!nearDue && !allDue) {
            if (altitude >= LIST_TOP && plumes.isNotEmpty()) { plumes = emptyList(); overhead = 0.0 }
            return
        }
        if (nearDue) { listedAt = time; listedFrom.setTo(camera) }
        if (allDue) { allAt = time; allNanos = now; allWanted = false }
        // Nothing going on anywhere on the world: nothing to list.
        if (!frameWeather.ashLive(time)) {
            if (plumes.isNotEmpty()) plumes = emptyList()
            overhead = 0.0
            if (all.isNotEmpty()) all = emptyList()
            return
        }
        val from = camera.copy()
        listing = true
        scope.launch(kotlinx.coroutines.Dispatchers.Default) {
            try {
                if (nearDue) {
                    plumes = listPlumes(from, time)
                    overhead = if (plumes.isEmpty()) 0.0 else ashOver(from, time)
                }
                if (allDue) {
                    val live = ArrayList<LiveEruption>()
                    weather.eruptions(Vec3.unitY(), time, Double.MAX_VALUE, live)
                    all = live
                }
            } finally {
                listing = false
            }
        }
    }

    /** The eruptions on the whole world for the map, as last listed, listing afresh when due. */
    fun forMap(camera: Vec3, time: Double): List<LiveEruption> {
        if (!active) return emptyList()
        allWanted = true
        list(camera, time)
        return all
    }

    private val sample = AirSample()

    private fun listPlumes(camera: Vec3, time: Double): List<Plume> {
        val centre = camera.copy().normalizeInPlace()
        val live = ArrayList<LiveEruption>()
        weather.eruptions(centre, time, reach, live)
        val terrain = body.terrain!!
        val near = live.sortedBy { it.vent.distanceTo(centre) }.take(most)
        val p = Vec3()
        return near.map { e ->
            val along = Vec3().setTo(e.drift).also { if (it.lengthSq < 1e-12) it.setTo(1.0, 0.0, 0.0) }
            along.addScaledInPlace(e.vent, -(along dot e.vent)).normalizeInPlace()
            val side = Vec3().setTo(e.vent).crossInPlace(along).normalizeInPlace()
            val n = STATIONS
            val ash = DoubleArray(n); val top = DoubleArray(n); val width = DoubleArray(n); val ground = DoubleArray(n)
            val rise = (e.top - e.ventHeight).coerceAtLeast(1_000.0)
            val plume = Plume(e, time, Noise.hashInt(0xA54, e.id.toInt(), (e.id ushr 32).toInt(), 0).toLong() and 0xffffL,
                along, side, ash, top, width, ground)
            for (i in 0 until n) {
                val s = e.length * i / (n - 1)
                plume.pointAt(s, body.radius, p)
                ground[i] = kotlin.math.max(terrain.elevation(p), if (terrain.hasOcean) 0.0 else -1e9)
                // The thickest of a few heights down from the cloud's top, and its top there.
                var most = 0.0; var at = 0.0
                for (k in 0 until HEIGHTS) {
                    val h = e.top - rise * (0.05 + 0.75 * k / (HEIGHTS - 1))
                    if (h < ground[i]) continue
                    sample.clear()
                    weather.sample(Vec3().setTo(p).mulInPlace(body.radius + h), time, sample)
                    if (sample.ash > most) { most = sample.ash; at = sample.ashTop }
                }
                ash[i] = most
                top[i] = if (most > 0.0) at else e.top
                width[i] = START_WIDTH + (e.halfWidth - START_WIDTH).coerceAtLeast(0.0) * i / (n - 1)
            }
            plume
        }
    }

    /** The thickest ash over the camera from its height up to the highest cloud listed. */
    private fun ashOver(camera: Vec3, time: Double): Double {
        val d = camera.copy().normalizeInPlace()
        val from = camera.length - body.radius
        val to = plumes.maxOf { it.eruption.top }
        if (from >= to) return 0.0
        var most = 0.0
        for (k in 0 until HEIGHTS) {
            val h = from + (to - from) * (k + 0.5) / HEIGHTS
            sample.clear()
            weather.sample(Vec3().setTo(d).mulInPlace(body.radius + h), time, sample)
            most = kotlin.math.max(most, sample.ash)
        }
        return most
    }

    // --- drawing ---------------------------------------------------------------------

    private val shapes = Array(CloudShapes.VARIANTS) { CloudPuff(it, false, 1) }
    private val nearShapes = Array(CloudShapes.VARIANTS) { CloudPuff(it, false, 2) }
    private val point = Vec3()
    private val pointUp = Vec3()
    private val placed = Vec3()
    private val upright = Quat()
    private val turn = Quat()

    /** How much to draw from a camera [altitude] metres up, 0..1: fading out high up. */
    fun weight(altitude: Double): Double = 1.0 - smooth(PUFFS_FADE, LIST_TOP, altitude)

    /**
     * Eruptions near body-fixed [camera] turned to where the planet is at [bodyRotation], to [out],
     * in items from [pool] (new ones without). [sun] is the way to the sun in the same frame as
     * [bodyRotation] turns to, for the vent's glow by night; null for none.
     */
    fun append(camera: Vec3, bodyRotation: Quat, time: Double, out: MutableList<RenderItem>, pool: RenderItemPool? = null, sun: Vec3? = null) {
        val list = plumes
        if (list.isEmpty()) return
        val weight = weight(camera.length - body.radius)
        if (weight <= 0.01) return
        for (p in list) draw(p, camera, bodyRotation, time, weight, sun, out, pool)
    }

    private fun item(pool: RenderItemPool?): RenderItem = pool?.take() ?: RenderItemPool.blank()

    private fun draw(p: Plume, camera: Vec3, bodyRotation: Quat, time: Double, weight: Double, sun: Vec3?, out: MutableList<RenderItem>, pool: RenderItemPool?) {
        val e = p.eruption
        val since = time - p.listedAt
        if (e.age + since >= e.life) return
        val radius = body.radius
        val key = p.key.toInt()

        // The column, while it erupts: dark, nearly solid, widening as it rises.
        val ventGround = radius + e.ventHeight
        point.setTo(e.vent).mulInPlace(ventGround)
        val ventFar = point.distanceTo(camera)
        val rise = e.columnTop - e.ventHeight
        if (e.strength > 0.01 && rise > 200.0 && ventFar < reach + e.columnTop) {
            val fade = (1.0 - smooth(0.7 * reach, reach, ventFar)) * weight
            val n = columnPieces
            pointUp.setTo(e.vent)
            fromTo(UNIT_Y, pointUp, upright)
            val alpha = e.strength.pow(0.3) * fade
            // By night the lava lights the column's foot from below, fading upward.
            val night = if (sun == null) 0.0 else {
                bodyRotation.rotate(e.vent, placed)
                1.0 - smooth(-0.12, 0.08, placed dot sun)
            }
            val glow = night * e.strength * fade
            val pulse = (0.85 + 0.15 * sin(2.0 * PI * time / 7.0 + key)).toFloat()
            for (j in 0 until n) {
                val h = hash(key * 31 + j)
                val f = (j + 0.5) / n
                // Billows boil upward slowly.
                val sway = sin(2.0 * PI * time / (SWAY_PERIOD * (0.7 + 0.6 * h)) + 6.0 * h)
                val wide = e.columnRadius * (0.85 + 0.9 * f * f) * (0.85 + 0.3 * h) * (1.0 + 0.05 * sway)
                val tall = kotlin.math.max(rise / n * 1.2, 0.6 * wide) * (0.9 + 0.2 * hash(key * 17 + j))
                val lift = e.ventHeight + rise * f
                // Lumpy: each billow a little off the middle, either way.
                val offset = e.columnRadius * 0.35 * (2.0 * hash(key * 13 + j) - 1.0)
                val ahead = e.columnRadius * 0.35 * (2.0 * hash(key * 19 + j) - 1.0)
                placed.setTo(e.vent).mulInPlace(radius + lift).addScaledInPlace(p.side, offset).addScaledInPlace(p.along, ahead)
                val far = placed.distanceTo(camera)
                val near = smooth(NEAR_CLEAR, NEAR_SOLID, far - wide)
                val a = alpha * near * (if (j == n - 1) 0.8 else 1.0)
                if (a < 0.02) continue
                val it = item(pool)
                bodyRotation.rotate(placed, it.position)
                Quat.fromAxisAngle(UNIT_Y, 2.0 * PI * h + 0.02 * time / (1.0 + h), turn)
                it.rotation.setTo(bodyRotation).mulInPlace(upright).mulInPlace(turn)
                val col = it.ownColour()
                // Darkest low down, where it's thickest.
                val s = COLUMN_SHADE * (0.8f + 0.25f * f.toFloat())
                col[0] = colour[0] * s; col[1] = colour[1] * s; col[2] = colour[2] * s
                col[3] = (a * 0.97).toFloat().coerceAtMost(0.97f)
                // Only well after dusk, so it doesn't jump from lit to glowing while there's day.
                val lit = if (night > GLOW_NIGHT) (GLOW_BRIGHT * glow * (1.0 - smooth(0.0, GLOW_UP, f)).pow(1.5)).toFloat() * pulse else 0f
                if (lit > 0f) {
                    col[0] = colour[0] * s * NIGHT_ASH + GLOW[0] * lit
                    col[1] = colour[1] * s * NIGHT_ASH + GLOW[1] * lit
                    col[2] = colour[2] * s * NIGHT_ASH + GLOW[2] * lit
                }
                val shape = if (far < 0.25 * reach && tier != QualityTier.LOW) nearShapes[(j + key) % nearShapes.size] else shapes[(j + key) % shapes.size]
                out.add(it.refill(shape, col, StackCaps.BOTH, it.ownScale().setTo(wide, tall, wide), if (lit > 0f) -1.2f else ASH_AMBIENT, true,
                    RenderItem.cloudKey(ASH_KEY xor (p.key shl 16), j), orderedTransparency = true))
            }
            // And a faint red haze round its foot.
            // Soft red puffs a little wider than the column round its foot, fainter going up, so
            // its underside looks lit from below.
            if (night > GLOW_NIGHT && glow > 0.02) for (g in 0 until GLOWS) {
                val wide = e.columnRadius * (1.25 + 0.3 * g)
                placed.setTo(e.vent).mulInPlace(radius + e.ventHeight + e.columnRadius * (0.3 + 1.3 * g))
                // A little toward the camera, so it lies over the column's near side.
                pointUp.setTo(camera).subInPlace(placed)
                val toward = pointUp.length
                if (toward > 1.0) placed.addScaledInPlace(pointUp, kotlin.math.min(0.5 * e.columnRadius, 0.5 * toward) / toward)
                val far = placed.distanceTo(camera)
                val a = glow * GLOW_ALPHA[g] * smooth(NEAR_CLEAR, NEAR_SOLID, far - wide)
                if (a < 0.02) continue
                val it = item(pool)
                bodyRotation.rotate(placed, it.position)
                it.rotation.setTo(bodyRotation).mulInPlace(upright)
                val col = it.ownColour()
                // Pulses slowly, never in step with anything.
                val deep = pulse * (1f - 0.18f * g)
                col[0] = GLOW[0] * deep; col[1] = GLOW[1] * deep * (1f - 0.15f * g); col[2] = GLOW[2] * deep
                col[3] = a.toFloat().coerceAtMost(0.5f)
                out.add(it.refill(shapes[g % shapes.size], col, StackCaps.BOTH, it.ownScale().setTo(wide, 0.9 * wide, wide), 1.2f, true,
                    RenderItem.cloudKey(ASH_KEY xor (p.key shl 16), 900 + g), orderedTransparency = true))
            }
        }

        // The cloud, carried downwind: puffs going with its drift, so it streams away from the vent.
        val length = e.length
        if (length < 1_000.0) return
        val n = puffs
        val spacing = length / n
        val speed = e.drift.length
        val travelled = speed * (e.age + since) / spacing
        val wraps = kotlin.math.floor(travelled)
        val phase = travelled - wraps
        val rise2 = (e.top - e.ventHeight).coerceAtLeast(1_000.0)
        val depth = DEPTH * rise2
        var falls = 0
        for (m in 0 until n) {
            val s = (m + phase) * spacing
            if (s > length) continue
            val g = (m - wraps).toLong()
            val h = hash((g * 7919 + key).toInt())
            // Out along it the cloud breaks up: more gaps, smaller lumps and fainter.
            val farOn = smooth(0.25 * length, length, s)
            if (h < GAPS_NEAR + (GAPS_FAR - GAPS_NEAR) * farOn) continue
            val ash = p.at(p.ash, s)
            if (ash < 0.01) continue
            val halfWidth = p.at(p.width, s)
            val cloudTop = p.at(p.top, s)
            val groundHere = p.at(p.ground, s)
            // Grows in at the vent and thins out at its far end.
            val ends = smooth(0.0, spacing, s) * (1.0 - smooth(length - spacing, length, s))
            // Scales are half sizes.
            // Lumps of uneven size, some half the size of others.
            val lump = (0.55 + 0.8 * hash((g * 131 + key).toInt())) * (1.0 - 0.4 * farOn)
            val tall = 0.5 * depth * lump
            // It wanders side to side and up and down along its length, fixed in the air, the
            // puffs flowing through it.
            val wave = s / length
            val sideways = halfWidth * (0.45 * sin(2.0 * PI * 1.7 * wave + 5.0 * hash(key * 3 + 1)) + 0.25 * sin(2.0 * PI * 4.3 * wave + 5.0 * hash(key * 3 + 2)))
            val upDown = depth * (0.22 * sin(2.0 * PI * 2.3 * wave + 5.0 * hash(key * 3 + 3)) + 0.12 * (2.0 * hash((g * 89 + key).toInt()) - 1.0))
            val mid = kotlin.math.max(cloudTop - 0.5 * depth + upDown, groundHere + tall)
            p.pointAt(s, radius, pointUp)
            fromTo(UNIT_Y, pointUp, upright)
            // One to three puffs across, so they stay round where it's wide, and a round billow on
            // top somewhere across, so its top is lumpy.
            val across = (halfWidth / (2.2 * tall)).toInt().coerceIn(1, ACROSS)
            for (c in 0..across) {
                val billow = c == across
                val off = if (billow) (2.0 * hash((g * 61 + key).toInt()) - 1.0) * 0.5 * halfWidth
                    else if (across == 1) 0.0 else (2.0 * c / (across - 1) - 1.0) * 0.55 * halfWidth
                // Round across, and long enough to meet the next one along.
                val wide = if (billow) 1.1 * tall * (0.8 + 0.4 * hash((g * 67 + key).toInt()))
                    else kotlin.math.max(halfWidth / across * 1.25 * (0.85 + 0.3 * hash((g * 53 + c + key).toInt())), 0.7 * spacing)
                val lift = if (billow) 0.75 * tall else tall * 0.3 * (2.0 * hash((g * 71 + c + key).toInt()) - 1.0)
                placed.setTo(pointUp).mulInPlace(radius + mid + lift).addScaledInPlace(p.side, off + sideways)
                val far = placed.distanceTo(camera)
                if (far > reach + wide) continue
                val fade = (1.0 - smooth(0.7 * reach, reach, far)) * smooth(NEAR_CLEAR, NEAR_SOLID, far - tall) * weight * ends * (1.0 - 0.6 * farOn)
                // Nearly solid where thick.
                val a = (CLOUD_ALPHA * smooth(0.0, 0.3, ash).pow(0.6)) * fade
                if (a < 0.02) continue
                val it = item(pool)
                bodyRotation.rotate(placed, it.position)
                Quat.fromAxisAngle(UNIT_Y, 2.0 * PI * hash((g * 37 + c + key).toInt()), turn)
                it.rotation.setTo(bodyRotation).mulInPlace(upright).mulInPlace(turn)
                val col = it.ownColour()
                // Paler as it spreads and thins.
                val pale = (CLOUD_SHADE + 0.2 * (1.0 - smooth(0.0, 0.4, ash))).toFloat()
                col[0] = colour[0] * pale; col[1] = colour[1] * pale; col[2] = colour[2] * pale
                col[3] = a.toFloat().coerceAtMost(0.98f)
                val shape = if (far < 0.2 * reach && tier != QualityTier.LOW) nearShapes[((g + c).toInt() and 0x7fffffff) % nearShapes.size]
                    else shapes[((g + c).toInt() and 0x7fffffff) % shapes.size]
                out.add(it.refill(shape, col, StackCaps.BOTH, it.ownScale().setTo(wide, if (billow) wide * 0.8 else tall, wide), ASH_AMBIENT, true,
                    RenderItem.cloudKey(ASH_KEY xor (p.key shl 16), 100 + 8 * (g.toInt() and 0xfff) + c), orderedTransparency = true))
            }
            // Ash falling out of every other puff, as a soft grey curtain to the ground.
            if (g % 2L == 0L && falls < n / 2) {
                val base = mid - tall
                val drop = base - groundHere
                if (drop > 300.0) {
                    placed.setTo(pointUp).mulInPlace(radius + groundHere + 0.5 * drop).addScaledInPlace(p.side, sideways)
                    val far = placed.distanceTo(camera)
                    val fade = (1.0 - smooth(0.7 * reach, reach, far)) * smooth(NEAR_CLEAR, NEAR_SOLID, far - halfWidth) * weight * ends * (1.0 - 0.6 * farOn)
                    val a = FALL_ALPHA * ash.pow(0.5) * fade
                    if (a >= 0.02 && far <= reach) {
                        falls++
                        val it = item(pool)
                        bodyRotation.rotate(placed, it.position)
                        it.rotation.setTo(bodyRotation).mulInPlace(upright)
                        val col = it.ownColour()
                        col[0] = colour[0] * FALL_SHADE; col[1] = colour[1] * FALL_SHADE; col[2] = colour[2] * FALL_SHADE
                        col[3] = a.toFloat()
                        val wide = 0.8 * halfWidth
                        out.add(it.refill(FALL_CURTAIN, col, 0, it.ownScale().setTo(wide, 0.5 * drop, wide), 0.5f, true,
                            RenderItem.cloudKey(ASH_KEY xor (p.key shl 16), 100 + 8 * (g.toInt() and 0xfff) + 7), curtain = true, orderedTransparency = true))
                    }
                }
            }
        }
    }

    private fun fromTo(a: Vec3, b: Vec3, out: Quat): Quat {
        val d = a dot b
        if (d <= -1.0 + 1e-9) return out.setTo(1.0, 0.0, 0.0, 0.0)
        return out.setTo(a.y * b.z - a.z * b.y, a.z * b.x - a.x * b.z, a.x * b.y - a.y * b.x, 1.0 + d).normalizeInPlace()
    }

    // --- hearing -------------------------------------------------------------------

    /** How loud the eruption's rumble is, 0..1, and the grit of ash round the camera, 0..1, after [heard]. */
    var loudness = 0.0
        private set
    var grit = 0.0
        private set

    /** Unit, body-fixed: the way to the vent heard, from the ears. */
    val heardFrom = Vec3()

    private var heardAt = Double.NaN

    /** Works out [loudness], [grit] and [heardFrom] for ears at body-fixed [ear] at [time]. */
    fun heard(ear: Vec3, time: Double) {
        val step = if (heardAt.isNaN()) 1.0 else (kotlin.math.abs(time - heardAt) / HEARD_EASE).coerceAtMost(1.0)
        heardAt = time
        var target = 0.0
        for (p in plumes) {
            val e = p.eruption
            if (e.strength <= 0.0) continue
            point.setTo(e.vent).mulInPlace(body.radius + e.ventHeight)
            val d = point.distanceTo(ear)
            val r = rumble(e.strength, d)
            if (r > target) {
                target = r
                heardFrom.setTo(point).subInPlace(ear)
                if (heardFrom.length > 1e-6) heardFrom.normalizeInPlace()
            }
        }
        loudness += (target - loudness) * step
        grit += (gritOf(haze) - grit) * step
        if (loudness < 1e-3) loudness = 0.0
        if (grit < 1e-3) grit = 0.0
    }

    companion object {
        /** How loud an eruption of [strength] is, 0..1, [distance] metres from its vent. */
        fun rumble(strength: Double, distance: Double): Double =
            strength.coerceIn(0.0, 1.0).pow(0.5) * (1.0 - smooth(RUMBLE_NEAR, RUMBLE_FAR, distance)).pow(2.0)

        /** How much ash grit is heard, 0..1, in [ash] round the ears. */
        fun gritOf(ash: Double): Double = easeOut(GRIT_LEAST, GRIT_FULL, ash)

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

        /** Sight in the thickest ash, metres, as the model has it. */
        const val ASH_SIGHT = 100.0
        /** Metres the layer reaches over a camera in ash at least. */
        const val IN_ASH_OVER = 30.0
        /** Seconds the haze takes to follow the air. */
        const val HAZE_EASE = 1.5
        /** How much ash over the camera counts for the light, as a share of the same ash round it. */
        const val OVERHEAD_SHARE = 0.8

        /** The rumble's heard fully within [RUMBLE_NEAR] of the vent, metres, and not past [RUMBLE_FAR]. */
        const val RUMBLE_NEAR = 4_000.0
        const val RUMBLE_FAR = 90_000.0
        /** The grit's heard from this much ash, fully at [GRIT_FULL]. */
        const val GRIT_LEAST = 0.03
        const val GRIT_FULL = 0.6
        const val HEARD_EASE = 0.5

        /** Eruptions near the camera listed again this often, seconds, or after it moves this far, metres; none above [LIST_TOP]. */
        const val RELIST_SECONDS = 2.0
        const val RELIST_DISTANCE = 2_000.0
        const val LIST_TOP = 60_000.0
        /** Fading out from here up, metres. */
        const val PUFFS_FADE = 30_000.0
        const val GROUND_EVERY = 1.0
        const val GROUND_MOVED = 500.0
        const val WORLD_RELIST_SECONDS = 20.0
        const val WORLD_RELIST_NANOS = 1_000_000_000L

        /** Stations along a cloud's way, and heights tried at each. */
        const val STATIONS = 16
        const val HEIGHTS = 5
        /** A cloud's half width at the vent as drawn, metres. */
        const val START_WIDTH = 4_000.0
        /** A cloud's depth, as a share of its top over the vent. */
        const val DEPTH = 0.45

        /** Pieces of glow at the vent by night. */
        const val GLOWS = 2
        /** How dark the column is by night where the glow lights it, and how high up it the glow reaches (share of its height). */
        const val NIGHT_ASH = 0.06f
        const val GLOW_UP = 0.55
        const val GLOW_BRIGHT = 0.75
        /** Night past which the column glows, 0..1. */
        const val GLOW_NIGHT = 0.9
        val GLOW = floatArrayOf(0.62f, 0.16f, 0.05f)
        val GLOW_ALPHA = doubleArrayOf(0.14, 0.07)

        const val SWAY_PERIOD = 50.0
        /** Clear within this many metres of the camera, solid from [NEAR_SOLID]: inside, the haze has it. */
        const val NEAR_CLEAR = 300.0
        const val NEAR_SOLID = 2_500.0
        /** Shaded softly like ground dust (negative), so it stays dark and solid from afar. */
        const val ASH_AMBIENT = -0.42f
        /** At most this many puffs across a cloud. */
        const val ACROSS = 3
        const val COLUMN_SHADE = 0.62f
        const val CLOUD_SHADE = 0.6
        /** Share of the cloud's puffs left out near the vent and at its far end, for gaps. */
        const val GAPS_NEAR = 0.08
        const val GAPS_FAR = 0.45
        const val CLOUD_ALPHA = 1.0
        const val FALL_ALPHA = 0.35
        const val FALL_SHADE = 0.8f
        /** Keys for its items, apart from the clouds', dust's and snow's. */
        const val ASH_KEY = 0xA5E0L

        /** Ash falling from far off: a soft column, open at both ends. */
        val FALL_CURTAIN = com.rm.apogee.core.part.ModelSpec.Lathe(
            listOf(listOf(1.05, -1.0), listOf(0.98, -0.55), listOf(0.9, 0.0), listOf(0.94, 0.5), listOf(0.85, 1.0)),
            segments = 12,
        )

        private val UNIT_Y = Vec3(0.0, 1.0, 0.0)
    }
}
