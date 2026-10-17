package com.rm.apogee.game

import com.rm.apogee.core.math.Quat
import com.rm.apogee.core.math.Vec3
import com.rm.apogee.core.orbit.CelestialBody
import com.rm.apogee.core.terrain.Noise
import com.rm.apogee.core.terrain.SurfaceMaterial
import com.rm.apogee.core.weather.CloudType
import com.rm.apogee.core.weather.LiveTornado
import com.rm.apogee.core.weather.Weather
import com.rm.apogee.core.weather.WeatherConfig
import com.rm.apogee.render.CloudPuff
import com.rm.apogee.render.FunnelPiece
import com.rm.apogee.render.QualityTier
import com.rm.apogee.render.RenderItem
import com.rm.apogee.render.RenderItemPool
import com.rm.apogee.render.StackCaps
import kotlinx.coroutines.launch
import kotlin.concurrent.Volatile
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Tornadoes and waterspouts for drawing and hearing: a funnel hanging from a lowered wall cloud,
 * bent along its track and wobbling, a debris ring at its foot over land or spray over water.
 *
 * The live ones near the camera are listed off the frame thread about once a second and carried
 * on along their track between listings. Each frame builds a few items per tornado from scratch
 * it keeps, in items from the frame's pool, with keys that stay the same for its whole life.
 * Nothing past [reach]; fewer pieces far off.
 */
class TornadoScene(
    private val body: CelestialBody,
    private val config: WeatherConfig,
    private val tier: QualityTier,
    private val scope: kotlinx.coroutines.CoroutineScope,
) {
    /** Off where the setting has no rare events. */
    val active = config.intensity.rare > 0.0 && body.atmosphere != null

    /** Its own, for listing on a worker; a weather's caches aren't thread safe. */
    private val weather by lazy { Weather(body, config) }
    private val climate get() = weather.climate

    /** One listed tornado and what drawing it needs that doesn't change. */
    class Entry(
        val tornado: LiveTornado,
        /** The ground under it when listed, metres above datum, never under the sea. */
        val ground: Double,
        /** How high its wall cloud hangs over [ground], in metres. */
        val base: Double,
        val key: Long,
        val variant: Int,
        /** How fast its funnel turns, radians a second, and its wobble's periods in seconds and phases. */
        val spin: Double,
        val period1: Double, val period2: Double, val period3: Double,
        val phase1: Double, val phase2: Double, val phase3: Double,
        /** The wall cloud's width, in metres. */
        val wall: Double,
        val listedAt: Double,
        /** What it picks up off the ground. */
        val kind: DebrisKind = DebrisKind.SOIL,
    ) {
        /** Which way it leans, eased toward its track on the frame thread, body-fixed, unit. */
        internal val heading = Vec3()
        internal var headingAt = Double.NaN
    }

    @Volatile var near: List<Entry> = emptyList()
        private set

    /** For pictures: the ground kind every tornado's debris takes, instead of the ground's own. */
    var kindOverride: DebrisKind? = null

    /** The bits thrown this frame, after [append]. */
    val debris = TornadoDebris(tier)

    /** Every live tornado on the world, as last listed for the map. Empty until the map asks. */
    @Volatile var all: List<LiveTornado> = emptyList()
        private set

    @Volatile private var listing = false
    private var listedAt = Double.NEGATIVE_INFINITY
    private val listedFrom = Vec3()
    private var allAt = Double.NEGATIVE_INFINITY
    private var allWanted = false

    /** How far away they're drawn, in metres. */
    val reach: Double get() = when (tier) {
        QualityTier.LOW -> 55_000.0
        QualityTier.MEDIUM -> 70_000.0
        QualityTier.HIGH -> 80_000.0
    }

    /** At most this many drawn, nearest first. */
    private val most: Int get() = when (tier) {
        QualityTier.LOW -> 3
        QualityTier.MEDIUM -> 4
        QualityTier.HIGH -> 6
    }

    /** Lists them again when due, for a camera at body-fixed [camera]. */
    fun update(camera: Vec3, time: Double) {
        if (!active || listing) return
        val nearDue = kotlin.math.abs(time - listedAt) >= RELIST_SECONDS || camera.distanceTo(listedFrom) >= RELIST_DISTANCE
        val allDue = allWanted && kotlin.math.abs(time - allAt) >= WORLD_RELIST_SECONDS
        if (!nearDue && !allDue) return
        if (nearDue) { listedAt = time; listedFrom.setTo(camera) }
        // The map asks again each frame it's open.
        if (allDue) { allAt = time; allWanted = false }
        val from = camera.copy().normalizeInPlace()
        val far = camera.length - body.radius
        listing = true
        scope.launch(kotlinx.coroutines.Dispatchers.Default) {
            try {
                if (nearDue) near = list(from, time, if (far > MOST_ABOVE) 0.0 else reach)
                if (allDue) all = ArrayList<LiveTornado>().also { weather.tornadoes(Vec3.unitY(), time, Double.MAX_VALUE, it) }
            } finally {
                listing = false
            }
        }
    }

    /** The tornadoes on the whole world for the map, as last listed, and a fresh listing when due. */
    fun forMap(camera: Vec3, time: Double): List<LiveTornado> {
        allWanted = true
        update(camera, time)
        return all
    }

    private fun list(direction: Vec3, time: Double, reach: Double): List<Entry> {
        if (reach <= 0.0) return emptyList()
        val live = ArrayList<LiveTornado>()
        weather.tornadoes(direction, time, reach, live)
        if (live.isEmpty()) return emptyList()
        val terrain = body.terrain
        val before = near
        return live.map { t ->
            fun h(k: Int) = Noise.hash(0x70A0 + k, t.id.toInt(), (t.id ushr 32).toInt(), 0)
            val ground = kotlin.math.max(terrain?.elevation(t.centre) ?: 0.0, 0.0)
            val base = if (t.inHurricane) HURRICANE_BASE else kotlin.math.max(LOWEST_BASE, STORM_BASE * climate.stormBase)
            Entry(
                t, ground, base + 120.0 * h(0),
                key = Noise.hashInt(0x70A1, t.id.toInt(), (t.id ushr 32).toInt(), 0).toLong() and 0xffffffffL,
                variant = (h(1) * VARIANTS).toInt().coerceAtMost(VARIANTS - 1),
                spin = 0.45 + 0.35 * h(2),
                period1 = 9.0 + 6.0 * h(3), period2 = 12.0 + 7.0 * h(4), period3 = 25.0 + 15.0 * h(5),
                phase1 = 2.0 * PI * h(6), phase2 = 2.0 * PI * h(7), phase3 = 2.0 * PI * h(8),
                wall = 900.0 + 900.0 * h(9) + 4.0 * t.coreRadius,
                listedAt = time,
                kind = kindOverride ?: kindOf(t.centre, ground),
            ).also { e ->
                // Its lean carries on from the last listing's.
                before.firstOrNull { it.tornado.id == t.id }?.let { old -> e.heading.setTo(old.heading); e.headingAt = old.headingAt }
            }
        }
    }

    /** What a tornado at unit [d] picks up, by the ground there. */
    private fun kindOf(d: Vec3, ground: Double): DebrisKind {
        val terrain = body.terrain ?: return DebrisKind.SOIL
        return when (terrain.material(d, ground, 0.0)) {
            SurfaceMaterial.SNOW, SurfaceMaterial.ICE, SurfaceMaterial.FROST, SurfaceMaterial.NITROGEN_ICE -> DebrisKind.SNOW
            SurfaceMaterial.SAND, SurfaceMaterial.DARK_SAND, SurfaceMaterial.ORGANIC_SAND, SurfaceMaterial.SALT, SurfaceMaterial.RED_DUST -> DebrisKind.SAND
            else -> DebrisKind.SOIL
        }
    }

    // --- drawing ---------------------------------------------------------------

    private val foot = Vec3()
    private val up = Vec3()
    private val track = Vec3()
    private val east = Vec3()
    private val north = Vec3()
    private val side = Vec3()
    private val p0 = Vec3()
    private val p1 = Vec3()
    private val dir = Vec3()
    private val at = Vec3()
    private val upright = Quat()
    private val tilt = Quat()
    private val turn = Quat()
    private val q = Quat()
    private var picked = BooleanArray(8)
    private var distances = DoubleArray(8)

    /** The pieces' shapes, by variant and how many pieces, made once. */
    private val funnels = Array(VARIANTS) { v -> Array(PIECE_COUNTS.size) { c -> Array(PIECE_COUNTS[c]) { k -> FunnelPiece(v, k, PIECE_COUNTS[c]) } } }
    private val puffDetail = if (tier == QualityTier.LOW) 1 else 2
    private val puffs = Array(PUFF_VARIANTS) { CloudPuff(it, false, puffDetail) }
    private val flatPuffs = Array(PUFF_VARIANTS) { CloudPuff(it, true, puffDetail) }
    private val sky = com.rm.apogee.render.SkyColours.of(body.id)
    private val dusty by lazy { climate.stormCloud == CloudType.DUST }

    /**
     * Appends the tornadoes near body-fixed [camera], turned to where the planet is at
     * [bodyRotation], to [out], in items from [pool] (new ones without).
     */
    fun append(camera: Vec3, bodyRotation: Quat, time: Double, out: MutableList<RenderItem>, pool: RenderItemPool? = null) {
        debris.clear()
        val list = near
        val n = list.size
        if (n == 0) return
        if (distances.size < n) { distances = DoubleArray(n * 2); picked = BooleanArray(n * 2) }
        for (i in 0 until n) {
            footOf(list[i], time, foot)
            distances[i] = foot.distanceTo(camera)
            picked[i] = false
        }
        // The nearest few within reach.
        repeat(minOf(most, n)) {
            var best = -1
            for (i in 0 until n) if (!picked[i] && distances[i] < reach && (best < 0 || distances[i] < distances[best])) best = i
            if (best < 0) return
            picked[best] = true
            draw(list[best], distances[best], camera, bodyRotation, time, out, pool)
        }
    }

    /** Where [e]'s funnel meets the ground at [time], body-fixed, into [out]. */
    private fun footOf(e: Entry, time: Double, out: Vec3): Vec3 =
        out.setTo(e.tornado.centre).mulInPlace(body.radius + e.ground).addScaledInPlace(e.tornado.velocity, time - e.listedAt)

    private fun draw(e: Entry, distance: Double, camera: Vec3, bodyRotation: Quat, time: Double, out: MutableList<RenderItem>, pool: RenderItemPool?) {
        val t = e.tornado
        val f = ((t.age + time - e.listedAt) / t.life).coerceIn(0.0, 1.0)
        val fade = 1.0 - smooth(0.65 * reach, reach, distance)
        if (fade <= 0.01) return
        // It lowers from the cloud as it forms, ropes out thin and bent, then lifts away.
        val lowered = smooth(0.0, 0.12, f) * (1.0 - smooth(0.9, 1.0, f))
        val rope = smooth(0.7, 1.0, f)
        val alive = smooth(0.0, 0.04, f) * (1.0 - smooth(0.93, 1.0, f))
        footOf(e, time, foot)
        up.setTo(foot).normalizeInPlace()
        // Along its track, or any level way if it's standing still, eased so it doesn't swing
        // round as the wander turns it.
        east.setTo(up.z, 0.0, -up.x)
        if (east.length < 1e-6) east.setTo(1.0, 0.0, 0.0)
        east.normalizeInPlace()
        north.setTo(up).crossInPlace(east)
        track.setTo(t.velocity).addScaledInPlace(up, -(t.velocity dot up))
        if (track.length < 0.2) track.setTo(east)
        track.normalizeInPlace()
        if (e.headingAt.isNaN()) e.heading.setTo(track)
        else e.heading.addScaledInPlace(dir.setTo(track).subInPlace(e.heading), (kotlin.math.abs(time - e.headingAt) / HEADING_EASE).coerceAtMost(1.0))
        e.headingAt = time
        e.heading.addScaledInPlace(up, -(e.heading dot up))
        if (e.heading.length < 1e-6) e.heading.setTo(track)
        e.heading.normalizeInPlace()
        track.setTo(e.heading)
        side.setTo(up).crossInPlace(track)
        fromTo(UNIT_Y, up, upright)

        val core = t.coreRadius
        val whole = e.base + WALL_DIP
        val length = whole * lowered
        val spinAngle = t.turn * ((e.spin * time) % (2.0 * PI))
        val water = t.overWater

        // The funnel, in pieces bent along its axis.
        if (length > 5.0) {
            val count = when {
                distance > 30_000.0 -> 0
                distance > 12_000.0 -> 1
                tier == QualityTier.LOW -> 2
                else -> 3
            }
            val pieces = PIECE_COUNTS[count]
            // A waterspout's funnel is paler and narrower than its wind.
            val top = core * TOP_CORES * (1.0 - 0.5 * rope) * (if (water) SPOUT_WIDTH else 1.0)
            for (k in 0 until pieces) {
                axis(e, foot, whole, length, rope, k.toDouble() / pieces, time, p0)
                axis(e, foot, whole, length, rope, (k + 1.0) / pieces, time, p1)
                dir.setTo(p1).subInPlace(p0)
                val span = dir.length
                if (span < 1e-6) continue
                dir.mulInPlace(1.0 / span)
                fromTo(up, dir, tilt)
                Quat.fromAxisAngle(UNIT_Y, spinAngle, turn)
                val item = pool?.take() ?: RenderItemPool.blank()
                bodyRotation.rotate(p0, item.position)
                item.rotation.setTo(bodyRotation).mulInPlace(tilt).mulInPlace(upright).mulInPlace(turn)
                val scale = item.ownScale().setTo(top, span * 1.06, top)
                // Grey cloud, browned near the ground by what it lifts, or paled by spray.
                val c = item.ownColour()
                val low = (1.0 - (k + 0.5) / pieces).let { it * it } * smooth(0.06, 0.16, f)
                if (dusty) { c[0] = 0.62f; c[1] = 0.46f; c[2] = 0.32f } else if (water) { c[0] = 0.52f; c[1] = 0.55f; c[2] = 0.60f }
                else { c[0] = 0.40f; c[1] = 0.42f; c[2] = 0.46f }
                if (water) mix(c, 0.74f, 0.77f, 0.82f, 0.5 * low) else mix(c, 0.44f, 0.36f, 0.28f, 0.55 * low)
                tint(c)
                // Solid while it's whole, so the storm's big puffs behind it can't be sorted over it.
                val shown = fade * alive
                c[3] = if (shown > 0.99) 1f else shown.toFloat()
                out.add(item.refill(funnels[e.variant][count][k], c, StackCaps.BOTH, scale, FUNNEL_AMBIENT, false, RenderItem.cloudKey(e.key, k), orderedTransparency = true))
            }
        }

        // The wall cloud: a dark lowered disc under the storm, and a collar round the funnel's top.
        val wallAlpha = (WALL_ALPHA * fade * alive).toFloat()
        axis(e, foot, whole, whole, rope, 1.0, time, at)
        at.addScaledInPlace(up, WALL_ABOVE - WALL_DIP)
        puff(flatPuffs[e.variant], at, e.wall, WALL_DEPTH, spinAngle * 0.12, 0.30, wallAlpha, bodyRotation, RenderItem.cloudKey(e.key, SLOT_WALL), out, pool)
        axis(e, foot, whole, whole, rope, 1.0, time, at)
        at.addScaledInPlace(up, 10.0 - WALL_DIP)
        puff(flatPuffs[(e.variant + 1) % PUFF_VARIANTS], at, kotlin.math.max(0.45 * e.wall, 2.4 * core * TOP_CORES), COLLAR_DEPTH, spinAngle * 0.3, 0.26, wallAlpha,
            bodyRotation, RenderItem.cloudKey(e.key, SLOT_WALL + 1), out, pool)

        // Its foot: a ring of debris over land, spray over water, turning with it.
        val touch = smooth(0.08, 0.16, f) * (1.0 - smooth(0.85, 0.95, f))
        if (touch <= 0.01 || distance > RING_REACH) return
        val ring = if (distance > 10_000.0) 3 else if (tier == QualityTier.LOW) 5 else 7
        val strong = 0.55 + 0.6 * t.strength
        axis(e, foot, whole, length, rope, 0.0, time, p0)
        val alpha = ((if (water) SPRAY_ALPHA else DEBRIS_ALPHA) * touch * fade).toFloat()
        if (water) {
            // Spray round the very foot, and a ring of it carried round.
            val middleWide = core * 1.1 * strong
            val middleTall = core * 0.35 * strong + 15.0
            at.setTo(p0).addScaledInPlace(up, 0.3 * middleTall)
            groundPuff(true, e.kind, puffs, at, middleWide, middleTall, spinAngle * 0.9, alpha, e.variant + 2, bodyRotation, RenderItem.cloudKey(e.key, SLOT_FOOT), out, pool)
            // Carried round slower than the funnel turns, so it reads as turning rather than a blur.
            val ringSpin = t.turn * ((RING_SPIN * e.spin * time) % (2.0 * PI))
            for (j in 0 until ring) {
                val h = Noise.hash(0x70A3, e.key.toInt(), j, 0)
                val angle = ringSpin + 2.0 * PI * (j + 0.3 * h) / ring
                val r = core * (1.0 + 0.3 * h) * strong
                val wide = core * (0.6 + 0.2 * h) * strong
                val tall = core * (0.25 + 0.15 * h) * strong + 10.0
                at.setTo(p0).addScaledInPlace(east, cos(angle) * r).addScaledInPlace(north, sin(angle) * r).addScaledInPlace(up, 0.3 * tall)
                groundPuff(true, e.kind, puffs, at, wide, tall, angle + h, alpha, e.variant + j, bodyRotation, RenderItem.cloudKey(e.key, SLOT_RING + j), out, pool)
            }
            return
        }
        // Over land a low dirt collar tight round the foot, so the funnel still shows above it,
        // and streaks of dirt leaning into the turn, swirling round it faster than the ring of spray.
        val collarWide = core * 0.75 * strong
        val collarTall = core * 0.4 * strong + 10.0
        at.setTo(p0).addScaledInPlace(up, 0.25 * collarTall)
        groundPuff(false, e.kind, puffs, at, collarWide, collarTall, spinAngle * 0.9, alpha * 0.8f, e.variant + 2, bodyRotation, RenderItem.cloudKey(e.key, SLOT_FOOT), out, pool)
        val swirl = t.turn * ((SWIRL_SPIN * e.spin * time) % (2.0 * PI))
        for (j in 0 until ring) {
            val h = Noise.hash(0x70A3, e.key.toInt(), j, 0)
            val angle = swirl + 2.0 * PI * (j + 0.3 * h) / ring
            val r = core * (0.75 + 0.3 * h) * strong
            val along = core * (0.55 + 0.2 * h) * strong
            val tall = core * (0.2 + 0.15 * h) * strong + 8.0
            val across = core * (0.22 + 0.08 * h) * strong
            at.setTo(p0).addScaledInPlace(east, cos(angle) * r).addScaledInPlace(north, sin(angle) * r).addScaledInPlace(up, 0.35 * tall)
            swirlPuff(e.kind, at, angle, t.turn, along, tall, across, alpha, e.variant + j, bodyRotation, RenderItem.cloudKey(e.key, SLOT_RING + j), out, pool)
        }
        if (distance < DEBRIS_REACH) throwDebris(e, distance, length, whole, rope, time, touch)
    }

    private val tangent = Vec3()
    private val lean = Quat()

    /**
     * A streak of dirt at [at], [angle] round the foot: long along the way it turns, leaning up
     * into the turn ([sense] 1 anticlockwise), [along] by [tall] by [across] metres.
     */
    private fun swirlPuff(
        kind: DebrisKind, at: Vec3, angle: Double, sense: Double, along: Double, tall: Double, across: Double, alpha: Float, variant: Int,
        bodyRotation: Quat, key: Long, out: MutableList<RenderItem>, pool: RenderItemPool?,
    ) {
        val item = pool?.take() ?: RenderItemPool.blank()
        bodyRotation.rotate(at, item.position)
        // Turn its own x onto the tangent, then lean it about its own z.
        tangent.setTo(east).mulInPlace(-sin(angle)).addScaledInPlace(north, cos(angle)).mulInPlace(sense)
        upright.rotate(UNIT_X, dir)
        val yaw = kotlin.math.atan2(dir.setTo(dir).crossInPlace(tangent) dot up, upright.rotate(UNIT_X, p1) dot tangent)
        Quat.fromAxisAngle(UNIT_Y, yaw, turn)
        Quat.fromAxisAngle(UNIT_Z, SWIRL_LEAN, lean)
        item.rotation.setTo(bodyRotation).mulInPlace(upright).mulInPlace(turn).mulInPlace(lean)
        val c = item.ownColour()
        if (dusty) { c[0] = 0.66f; c[1] = 0.46f; c[2] = 0.30f }
        else when (kind) {
            DebrisKind.SOIL -> { c[0] = 0.38f; c[1] = 0.30f; c[2] = 0.23f }
            DebrisKind.SAND -> { c[0] = 0.64f; c[1] = 0.54f; c[2] = 0.39f }
            DebrisKind.SNOW -> { c[0] = 0.80f; c[1] = 0.83f; c[2] = 0.88f }
        }
        tint(c)
        c[3] = alpha
        out.add(item.refill(puffs[variant % PUFF_VARIANTS], c, StackCaps.BOTH, item.ownScale().setTo(along, tall, across), CLOUD_AMBIENT, true, key, orderedTransparency = true))
    }

    /**
     * The point [s] of the way up [e]'s funnel (0 its foot, 1 its top), body-fixed, into [out]: the
     * lower end trailing behind along its track and swinging slowly, a rope bending side to side.
     */
    private fun axis(e: Entry, foot: Vec3, whole: Double, length: Double, rope: Double, s: Double, time: Double, out: Vec3): Vec3 {
        val below = 1.0 - s
        val core = e.tornado.coreRadius
        val wobble = (WOBBLE_CORES * core + 0.04 * length * rope) * below
        val swing1 = sin(2.0 * PI * (time / e.period1) % (2.0 * PI) + e.phase1 + 2.0 * s)
        val swing2 = cos(2.0 * PI * (time / e.period2) % (2.0 * PI) + e.phase2 + 1.5 * s)
        val bend = sin(PI * s) * sin(2.0 * PI * (time / e.period3) % (2.0 * PI) + e.phase3)
        val along = -length * (LEAN + ROPE_LEAN * rope) * below + wobble * swing1
        val across = wobble * swing2 + ROPE_BEND * length * rope * bend
        return out.setTo(foot).addScaledInPlace(up, whole - length + length * s)
            .addScaledInPlace(track, along).addScaledInPlace(side, across)
    }

    /**
     * [e]'s debris into [debris]: pieces each on a loop of their own, spiralling up round the
     * funnel, then flung out past the dirt on a curving path and falling, some drawn back in,
     * worked out from [time] alone. Fewer and fainter far off; each tornado takes what's left of
     * the budget, less the further it is.
     */
    private fun throwDebris(e: Entry, distance: Double, length: Double, whole: Double, rope: Double, time: Double, touch: Double) {
        val t = e.tornado
        val shown = touch * (1.0 - smooth(0.6 * DEBRIS_REACH, DEBRIS_REACH, distance))
        if (shown <= 0.01) return
        val left = debris.capacity - debris.count
        val n = (left * DEBRIS_SHARE * (1.0 - 0.75 * smooth(1_500.0, DEBRIS_REACH, distance))).toInt()
        val core = t.coreRadius
        val strong = 0.55 + 0.6 * t.strength
        val seed = e.key.toInt()
        for (j in 0 until n) {
            fun h(k: Int) = Noise.hash(0x70B0 + k, seed, j, 0)
            val period = 7.0 + 9.0 * h(1)
            val u = (time / period + h(2)).let { it - kotlin.math.floor(it) }
            val top = kotlin.math.min((40.0 + 420.0 * h(4) * h(4)) * strong, 0.8 * whole)
            val r0 = core * (0.55 + 0.45 * h(5))
            val speed = (25.0 + 45.0 * t.strength) * (0.7 + 0.6 * h(7))
            val rise = 0.45 + 0.25 * h(3)
            val back = h(8) < 0.3
            val r: Double; val height: Double; val travelled: Double
            if (u < rise) {
                // Spiralling up round the funnel, widening as it climbs.
                val s = u / rise
                r = r0 * (1.0 + 0.4 * s)
                height = top * s * (2.0 - s)
                travelled = speed * u * period
            } else {
                // Flung out and falling, its turn dying away so the path curves; some drawn back in.
                val s = (u - rise) / (1.0 - rise)
                val out = core * (1.5 + 3.0 * h(13)) * strong
                r = r0 * 1.4 + out * (if (back) kotlin.math.sin(PI * s) * 0.7 else s * (2.0 - s))
                height = top * (1.0 - s * s) + 0.25 * top * 4.0 * s * (1.0 - s)
                travelled = speed * (rise + (u - rise) * (1.0 - 0.5 * (u - rise) / (1.0 - rise))) * period * 0.6
            }
            val angle = 2.0 * PI * h(6) + t.turn * travelled / (1.25 * r0)
            val a = smooth(0.0, 0.05, u) * (1.0 - smooth(0.93, 1.0, u)) * shown
            if (a <= 0.02) continue
            // Round the funnel's axis at that height, measured from the ground.
            val along = (height / kotlin.math.max(length, 1.0)).coerceIn(0.0, 1.0)
            axis(e, foot, whole, length, rope, along, time, at)
            at.addScaledInPlace(up, -(whole - length + length * along) + height)
                .addScaledInPlace(east, cos(angle) * r).addScaledInPlace(north, sin(angle) * r)
            // Boards and branches long and thin, clods stubby, each tumbling end over end.
            val c = h(12)
            val board = c >= 0.7
            val branch = c >= 0.4 && !board
            val long = if (board) 1.2 + 1.6 * h(9) else if (branch) 0.9 + 1.4 * h(9) else 0.4 + 0.6 * h(9)
            val wide = if (board) 0.22 * long else if (branch) 0.12 * long else 0.7 * long
            val spin = (1.5 + 3.5 * h(10)) * time + 6.3 * h(11)
            val fore = 0.3 + 0.7 * kotlin.math.abs(cos(spin))
            val turned = 0.6 * spin + 6.3 * h(14)
            when (e.kind) {
                DebrisKind.SOIL -> if (board) debris.add(at, long, wide, turned, fore, 0.24f, 0.20f, 0.16f, a.toFloat())
                    else if (branch) debris.add(at, long, wide, turned, fore, 0.07f, 0.06f, 0.05f, a.toFloat())
                    else debris.add(at, long, wide, turned, fore, 0.12f, 0.09f, 0.07f, a.toFloat())
                DebrisKind.SAND -> if (board) debris.add(at, long, wide, turned, fore, 0.24f, 0.20f, 0.16f, a.toFloat())
                    else if (branch) debris.add(at, long, wide, turned, fore, 0.09f, 0.08f, 0.06f, a.toFloat())
                    else debris.add(at, long, wide, turned, fore, 0.34f, 0.27f, 0.18f, a.toFloat())
                DebrisKind.SNOW -> if (board) debris.add(at, long, wide, turned, fore, 0.24f, 0.20f, 0.16f, a.toFloat())
                    else if (branch) debris.add(at, long, wide, turned, fore, 0.07f, 0.06f, 0.05f, a.toFloat())
                    else debris.add(at, long, wide, turned, fore, 0.72f, 0.76f, 0.82f, a.toFloat())
            }
        }
    }

    private fun groundPuff(
        water: Boolean, kind: DebrisKind, shapes: Array<CloudPuff>, at: Vec3, wide: Double, tall: Double, yaw: Double, alpha: Float, variant: Int,
        bodyRotation: Quat, key: Long, out: MutableList<RenderItem>, pool: RenderItemPool?,
    ) {
        val item = pool?.take() ?: RenderItemPool.blank()
        bodyRotation.rotate(at, item.position)
        Quat.fromAxisAngle(UNIT_Y, yaw, turn)
        item.rotation.setTo(bodyRotation).mulInPlace(upright).mulInPlace(turn)
        val c = item.ownColour()
        if (water) { c[0] = 0.86f; c[1] = 0.89f; c[2] = 0.93f }
        else if (dusty) { c[0] = 0.66f; c[1] = 0.46f; c[2] = 0.30f }
        else when (kind) {
            DebrisKind.SOIL -> { c[0] = 0.42f; c[1] = 0.34f; c[2] = 0.26f }
            DebrisKind.SAND -> { c[0] = 0.68f; c[1] = 0.58f; c[2] = 0.42f }
            DebrisKind.SNOW -> { c[0] = 0.82f; c[1] = 0.85f; c[2] = 0.90f }
        }
        tint(c)
        c[3] = alpha
        out.add(item.refill(shapes[variant % PUFF_VARIANTS], c, StackCaps.BOTH, item.ownScale().setTo(wide, tall, wide), CLOUD_AMBIENT, true, key, orderedTransparency = true))
    }

    private fun puff(
        shape: CloudPuff, at: Vec3, wide: Double, tall: Double, yaw: Double, shade: Double, alpha: Float,
        bodyRotation: Quat, key: Long, out: MutableList<RenderItem>, pool: RenderItemPool?,
    ) {
        if (alpha <= 0.01f) return
        val item = pool?.take() ?: RenderItemPool.blank()
        bodyRotation.rotate(at, item.position)
        Quat.fromAxisAngle(UNIT_Y, yaw, turn)
        item.rotation.setTo(bodyRotation).mulInPlace(upright).mulInPlace(turn)
        // A storm's dark blue-grey, as its base is drawn.
        val c = item.ownColour()
        if (dusty) { c[0] = (0.72 * shade * 1.6).toFloat(); c[1] = (0.48 * shade * 1.6).toFloat(); c[2] = (0.30 * shade * 1.6).toFloat() }
        else { c[0] = (0.60 * shade).toFloat(); c[1] = (0.63 * shade).toFloat(); c[2] = (0.72 * shade).toFloat() }
        tint(c)
        c[3] = alpha
        out.add(item.refill(shape, c, StackCaps.BOTH, item.ownScale().setTo(wide, tall, wide), CLOUD_AMBIENT, true, key, orderedTransparency = true))
    }

    private fun tint(c: FloatArray) {
        val t = sky.cloud
        c[0] *= t[0]; c[1] *= t[1]; c[2] *= t[2]
    }

    private fun mix(c: FloatArray, r: Float, g: Float, b: Float, by: Double) {
        val k = by.toFloat().coerceIn(0f, 1f)
        c[0] += (r - c[0]) * k; c[1] += (g - c[1]) * k; c[2] += (b - c[2]) * k
    }

    // --- sound -------------------------------------------------------------------

    /** How loud the nearest roar is, 0..1, after [heard]. */
    var loudness = 0.0
        private set

    /** How near the roar is, 0..1, after [heard]: 1 in its core. */
    var closeness = 0.0
        private set

    /** Which way it's heard from, body-fixed, unit, after [heard]. */
    val heardFrom = Vec3()

    /** How much debris is heard clattering round a land tornado's foot, 0..1, after [heard]. */
    var rattle = 0.0
        private set

    /**
     * Works out [loudness], [closeness] and [heardFrom] for ears at body-fixed [camera] at [time]:
     * the loudest roar, rising as you get near and with its strength, nothing past [HEARD_REACH].
     */
    fun heard(camera: Vec3, time: Double) {
        loudness = 0.0; closeness = 0.0; rattle = 0.0
        val list = near
        for (i in list.indices) {
            val e = list[i]
            val t = e.tornado
            val f = ((t.age + time - e.listedAt) / t.life).coerceIn(0.0, 1.0)
            val down = smooth(0.06, 0.16, f) * (1.0 - smooth(0.88, 1.0, f))
            if (down <= 0.0) continue
            footOf(e, time, foot)
            // From the nearest point on its column.
            up.setTo(foot).normalizeInPlace()
            val height = ((camera dot up) - foot.length).coerceIn(0.0, e.base)
            at.setTo(foot).addScaledInPlace(up, height)
            val d = at.distanceTo(camera)
            if (d >= HEARD_REACH) continue
            val close = 1.0 - smooth(1.5 * t.coreRadius, HEARD_NEAR, d)
            // Debris near the foot, low down, over land.
            if (!t.overWater) {
                val low = 1.0 - smooth(0.3 * RATTLE_HEIGHT, RATTLE_HEIGHT, height)
                val r = down * (0.4 + 0.6 * t.strength) * (1.0 - smooth(1.5 * t.coreRadius, 3.0 * t.coreRadius + RATTLE_REACH, d)) * low
                if (r > rattle) rattle = r
            }
            val loud = down * (0.45 + 0.55 * t.strength) * (1.0 - smooth(0.0, HEARD_REACH, d)).let { it * sqrt(it) } * (0.35 + 0.65 * close)
            if (loud > loudness) {
                loudness = loud
                closeness = close
                heardFrom.setTo(at).subInPlace(camera)
                if (heardFrom.length > 1e-6) heardFrom.normalizeInPlace()
            }
        }
    }

    private fun smooth(a: Double, b: Double, x: Double): Double {
        val t = ((x - a) / (b - a)).coerceIn(0.0, 1.0)
        return t * t * (3 - 2 * t)
    }

    /** The shortest turn taking unit [a] to unit [b], into [out], without making anything. */
    private fun fromTo(a: Vec3, b: Vec3, out: Quat): Quat {
        val d = a dot b
        if (d <= -1.0 + 1e-9) return out.setTo(1.0, 0.0, 0.0, 0.0)
        return out.setTo(a.y * b.z - a.z * b.y, a.z * b.x - a.x * b.z, a.x * b.y - a.y * b.x, 1.0 + d).normalizeInPlace()
    }

    companion object {
        private val UNIT_Y = Vec3(0.0, 1.0, 0.0)
        private val UNIT_X = Vec3(1.0, 0.0, 0.0)
        private val UNIT_Z = Vec3(0.0, 0.0, 1.0)

        /** How often the near ones are listed, in seconds, or when the camera has gone this far, in metres. */
        const val RELIST_SECONDS = 1.0
        const val RELIST_DISTANCE = 2_000.0

        /** How often the whole world's are listed for the map, in seconds of game time. */
        const val WORLD_RELIST_SECONDS = 5.0

        /** Above this, in metres, none are listed near the camera; they're too small to see. */
        const val MOST_ABOVE = 60_000.0

        /** How high a supercell's wall cloud hangs over the ground, in metres times the world's storm base, and the least. */
        const val STORM_BASE = 900.0
        const val LOWEST_BASE = 450.0

        /** A hurricane band's, under its low deck. */
        const val HURRICANE_BASE = 520.0

        /** The funnel reaches this far up into its wall cloud, in metres. */
        const val WALL_DIP = 60.0

        /** The wall cloud's middle over its base, and its depth and its collar's, in metres. */
        const val WALL_ABOVE = 150.0
        const val WALL_DEPTH = 220.0
        const val COLLAR_DEPTH = 150.0

        /** The funnel's top radius in core radii. */
        const val TOP_CORES = 1.5

        /** How far the foot trails, as a share of its length, more as it ropes out, and how far a rope bends. */
        const val LEAN = 0.12
        const val ROPE_LEAN = 0.25
        const val ROPE_BEND = 0.2

        /** How far the foot swings, in core radii. */
        const val WOBBLE_CORES = 0.35

        /** The funnel's light without the sun: more than a part's, as it's cloud. */
        const val FUNNEL_AMBIENT = 0.5f
        const val WALL_ALPHA = 0.95
        const val DEBRIS_ALPHA = 0.62
        const val SPRAY_ALPHA = 0.42

        /** How wide a waterspout's funnel is next to a tornado's of the same core. */
        const val SPOUT_WIDTH = 0.7
        const val CLOUD_AMBIENT = 0.42f

        /** How fast the debris ring is carried round, as a share of the funnel's turn. */
        const val RING_SPIN = 0.22

        /** How long its lean takes to follow a turn in its track, in seconds. */
        const val HEADING_EASE = 4.0

        /** Debris and spray are drawn out to here, in metres. */
        const val RING_REACH = 25_000.0

        /** Thrown bits are drawn out to here, in metres. */
        const val DEBRIS_REACH = 6_000.0

        /** How fast the dirt streaks swirl round the foot, as a share of the funnel's turn, and how far they lean, radians. */
        const val SWIRL_SPIN = 0.6
        const val SWIRL_LEAN = 0.45

        /** The share of the debris budget the nearest tornado takes: fewer, bigger pieces. */
        const val DEBRIS_SHARE = 0.4

        /** The roar is gone past [HEARD_REACH] metres and at its fullest inside [HEARD_NEAR]. */
        const val HEARD_REACH = 4_000.0
        const val HEARD_NEAR = 1_200.0

        /** Debris is heard out to this many metres past three core radii, and up to this high. */
        const val RATTLE_REACH = 600.0
        const val RATTLE_HEIGHT = 400.0

        const val VARIANTS = 3
        const val PUFF_VARIANTS = com.rm.apogee.render.CloudShapes.VARIANTS

        /** How many pieces a funnel is drawn in: far, middling, near on LOW, near otherwise. */
        val PIECE_COUNTS = intArrayOf(2, 3, 4, 6)

        // Item slots within a tornado: the funnel's pieces from 0.
        const val SLOT_WALL = 8
        const val SLOT_FOOT = 10
        const val SLOT_RING = 16
    }
}
