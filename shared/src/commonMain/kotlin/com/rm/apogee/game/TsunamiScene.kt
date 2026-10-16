package com.rm.apogee.game

import com.rm.apogee.core.math.Vec3
import com.rm.apogee.core.orbit.CelestialBody
import com.rm.apogee.core.weather.LiveTsunami
import com.rm.apogee.core.weather.Weather
import com.rm.apogee.core.weather.WeatherConfig
import com.rm.apogee.render.QualityTier
import com.rm.apogee.render.TsunamiField
import kotlinx.coroutines.launch
import kotlin.concurrent.Volatile
import kotlin.math.sqrt

/**
 * Tsunamis for drawing, hearing and the map: the sea they move round the camera for the flat water
 * past the waves ([field]), a roar as a bore or a flood comes in near the camera, and each live one
 * on the map with its front.
 *
 * Worked out off the frame thread about once a second while one is near the camera, every few
 * seconds for the map while it's open. With none near, nothing is done.
 */
class TsunamiScene(
    private val body: CelestialBody,
    private val config: WeatherConfig,
    private val tier: QualityTier,
    private val scope: kotlinx.coroutines.CoroutineScope,
) {
    /** Off where the setting has no rare events or the world has no sea. */
    val active = config.intensity.rare > 0.0 && body.ocean != null && body.terrain?.hasOcean == true

    /** Its own, for working on a worker; a weather's caches aren't thread safe. */
    private val weather by lazy { Weather(body, config) }

    /** A live tsunami for the map, with its front as runs of unit, body-fixed points over the sea. */
    class MapEntry(val tsunami: LiveTsunami, val front: List<List<Vec3>>)

    /** Every live tsunami on the world, as last listed for the map. Empty until the map asks. */
    @Volatile var all: List<MapEntry> = emptyList()
        private set

    /** The sea round the camera as last worked out, or null with none near. */
    @Volatile var field: TsunamiField? = null
        private set

    /** How long the last field took to work out, in ms. */
    @Volatile var lastFieldMillis = 0.0
        private set

    /** The roar as last worked out: how loud, how near, and from where, body-fixed. */
    private class Roar(val loudness: Double, val closeness: Double, val from: Vec3)
    @Volatile private var roar: Roar? = null

    @Volatile private var listing = false
    private var listedAt = Double.NEGATIVE_INFINITY
    private val listedFrom = Vec3()
    private var allAt = Double.NEGATIVE_INFINITY
    private var allWanted = false

    /** How far round the camera the far sea shows a tsunami, in metres. */
    val reach: Double get() = when (tier) {
        QualityTier.LOW -> 36_000.0
        QualityTier.MEDIUM -> 48_000.0
        QualityTier.HIGH -> 64_000.0
    }

    /** Works it out again when due, for a camera at body-fixed [camera]. */
    fun update(camera: Vec3, time: Double) {
        if (!active || listing) return
        val nearDue = kotlin.math.abs(time - listedAt) >= RELIST_SECONDS || camera.distanceTo(listedFrom) >= RELIST_DISTANCE
        val allDue = allWanted && kotlin.math.abs(time - allAt) >= WORLD_RELIST_SECONDS
        if (!nearDue && !allDue) return
        if (nearDue) { listedAt = time; listedFrom.setTo(camera) }
        if (allDue) { allAt = time; allWanted = false }
        val at = camera.copy()
        val high = camera.length - body.radius > MOST_ABOVE
        listing = true
        scope.launch(kotlinx.coroutines.Dispatchers.Default) {
            try {
                if (nearDue) {
                    if (high) { field = null; roar = null; spray = null } else near(at, time)
                }
                if (allDue) all = listAll(time)
            } finally {
                listing = false
            }
        }
    }

    /** The tsunamis on the whole world for the map, as last listed, and a fresh listing when due. */
    fun forMap(camera: Vec3, time: Double): List<MapEntry> {
        allWanted = true
        update(camera, time)
        return all
    }

    private val sample = Vec3()

    /** The field and the roar for a camera at body-fixed [camera]. */
    private fun near(camera: Vec3, time: Double) {
        val up = camera.copy().normalizeInPlace()
        val live = ArrayList<LiveTsunami>()
        weather.tsunamis(up, time, reach * 1.5, live)
        if (live.isEmpty()) { field = null; roar = null; spray = null; return }
        val started = com.rm.apogee.platform.System.nanoTime()
        val terrain = body.terrain ?: return
        val east = Vec3(up.z, 0.0, -up.x)
        if (east.length < 1e-6) east.setTo(1.0, 0.0, 0.0)
        east.normalizeInPlace()
        val north = Vec3().setTo(up).crossInPlace(east)
        val side = TsunamiField.SIDE
        val spacing = 2.0 * reach / (side - 1)
        val values = FloatArray(TsunamiField.VEC4S * 4)
        val tau = FloatArray(TsunamiField.VEC4S * 4)
        // Its crest is drawn for one: the one passing here, or else the biggest.
        val main = live.firstOrNull { !it.arrivesHere.isNaN() && time >= it.arrivesHere && time < it.arrivesHere + Weather.TSUNAMI_TRAIN }
            ?: live.maxBy { it.strength }
        val phase = DoubleArray(2)
        var any = false
        for (j in 0 until side) for (i in 0 until side) {
            val x = (i - (side - 1) / 2) * spacing; val y = (j - (side - 1) / 2) * spacing
            sample.setTo(up).addScaledInPlace(east, x / body.radius).addScaledInPlace(north, y / body.radius).normalizeInPlace()
            val bed = terrain.elevation(sample)
            // Over land, the sea as if it ran on over it, so the water along the coast reads smoothly.
            val h = weather.tsunamiHeight(sample, time, kotlin.math.min(bed, -SHALLOW), blur = spacing)
            if (kotlin.math.abs(h) > 0.05) any = true
            val arrives = weather.tsunamiPhase(sample, time, main.id, phase)
            values[j * side + i] = TsunamiField.pack(h, if (arrives) phase[1] else 0.0)
            tau[j * side + i] = (if (arrives) phase[0] else Weather.TSUNAMI_NO_CREST).toFloat()
        }
        fillNever(tau)
        val shore = weather.tsunamiHeight(up, time, kotlin.math.min(terrain.elevation(up), -SHALLOW))
        val g = body.gravitationalParameter / (body.radius * body.radius)
        field = if (any) TsunamiField(
            up, east, north, spacing, values, shore, tau, time, main.period,
            Weather.TSUNAMI_COAST_CAP * main.strength, 2.0 / (Weather.TSUNAMI_SHOAL_SLOPE * sqrt(g)),
        ) else null
        roar = roarAt(camera, up, east, north, time)
        spray = sprayAt(camera, up, east, north, time)
        lastFieldMillis = (com.rm.apogee.platform.System.nanoTime() - started) / 1e6
    }

    /**
     * Points [tau] has no arrival for (far inland, or cut off) take their neighbours' times, so the
     * shader reading between points never mixes a time with none.
     */
    private fun fillNever(tau: FloatArray) {
        val side = TsunamiField.SIDE
        val none = Weather.TSUNAMI_NO_CREST.toFloat()
        val known = BooleanArray(side * side) { tau[it] != none }
        val next = known.copyOf()
        repeat(side) {
            var left = false
            for (k in 0 until side * side) {
                if (known[k]) continue
                val i = k % side; val j = k / side
                var sum = 0f; var n = 0
                if (i > 0 && known[k - 1]) { sum += tau[k - 1]; n++ }
                if (i < side - 1 && known[k + 1]) { sum += tau[k + 1]; n++ }
                if (j > 0 && known[k - side]) { sum += tau[k - side]; n++ }
                if (j < side - 1 && known[k + side]) { sum += tau[k + side]; n++ }
                if (n > 0) { tau[k] = sum / n; next[k] = true } else left = true
            }
            next.copyInto(known)
            if (!left) return
        }
    }

    /**
     * The loudest bore or flood near body-fixed [camera] (over unit [up]): sampled on rings out to
     * [HEARD_REACH], each source falling off with its distance.
     */
    private fun roarAt(camera: Vec3, up: Vec3, east: Vec3, north: Vec3, time: Double): Roar? {
        val terrain = body.terrain ?: return null
        var best = 0.0
        var bestClose = 0.0
        val from = Vec3()
        for (ring in RINGS.indices) {
            val r = RINGS[ring]
            val count = if (r == 0.0) 1 else BEARINGS
            for (k in 0 until count) {
                val a = 2.0 * kotlin.math.PI * (k + 0.5 * (ring and 1)) / BEARINGS
                sample.setTo(up).addScaledInPlace(east, r * kotlin.math.cos(a) / body.radius)
                    .addScaledInPlace(north, r * kotlin.math.sin(a) / body.radius).normalizeInPlace()
                val bed = terrain.elevation(sample)
                // Blurred, so the bore isn't missed between the rings.
                val h = weather.tsunamiHeight(sample, time, bed, blur = HEARD_BLUR)
                val source = weather.tsunamiFoam() * (if (bed <= 0.0) smooth(0.3, 3.0, h) else smooth(0.2, 2.0, h - bed))
                if (source <= 0.0) continue
                sample.mulInPlace(body.radius + kotlin.math.max(bed, h))
                val d = sample.distanceTo(camera)
                if (d >= HEARD_REACH) continue
                val loud = source * (1.0 - smooth(HEARD_NEAR, HEARD_REACH, d)).let { it * sqrt(it) }
                if (loud > best) {
                    best = loud
                    bestClose = source * (1.0 - smooth(0.0, CLOSE_REACH, d))
                    from.setTo(sample)
                }
            }
        }
        return if (best > 0.0) Roar(best, bestClose, from) else null
    }

    /** Every live tsunami on the world with its front. */
    private fun listAll(time: Double): List<MapEntry> {
        val live = ArrayList<LiveTsunami>()
        weather.tsunamis(Vec3.unitY(), time, Double.MAX_VALUE, live)
        val terrain = body.terrain
        return live.map { t ->
            val runs = ArrayList<List<Vec3>>()
            val angle = t.frontRadius / body.radius
            if (terrain != null && angle > 1e-4 && angle < kotlin.math.PI - 1e-3) {
                val o = t.origin
                val e = Vec3(o.z, 0.0, -o.x).let { if (it.length < 1e-6) it.setTo(1.0, 0.0, 0.0) else it }.normalizeInPlace()
                val n = Vec3().setTo(o).crossInPlace(e)
                var run = ArrayList<Vec3>()
                val c = kotlin.math.cos(angle); val s = kotlin.math.sin(angle)
                for (k in 0..FRONT_POINTS) {
                    val b = 2.0 * kotlin.math.PI * k / FRONT_POINTS
                    val p = Vec3().setTo(o).mulInPlace(c).addScaledInPlace(e, s * kotlin.math.cos(b)).addScaledInPlace(n, s * kotlin.math.sin(b)).normalizeInPlace()
                    // Only over the sea, where it runs.
                    if (terrain.elevation(p) < 0.0) run.add(p)
                    else {
                        if (run.size > 1) runs.add(run)
                        run = ArrayList()
                    }
                }
                if (run.size > 1) runs.add(run)
            }
            MapEntry(t, runs)
        }
    }

    // --- spray ---------------------------------------------------------------

    /**
     * Where the bore's front was found round the camera: per bearing slot, a body-fixed point on
     * the water, which way and how fast the front runs (m/s, body-fixed), and how strong it is, as
     * of [time].
     */
    private class Spray(val time: Double, val count: Int, val slot: IntArray, val at: DoubleArray, val velocity: DoubleArray, val strength: DoubleArray)
    @Volatile private var spray: Spray? = null

    /** Bearings searched for the front, and how far out, by tier. */
    private val sprayBearings = when (tier) { QualityTier.LOW -> 12; QualityTier.MEDIUM -> 18; QualityTier.HIGH -> 24 }
    val sprayReach: Double get() = when (tier) { QualityTier.LOW -> 1_500.0; QualityTier.MEDIUM -> 2_500.0; QualityTier.HIGH -> 3_000.0 }

    private val sprayFlow = Vec3()

    /** The bore's front along each bearing from body-fixed [camera] (over unit [up]), nearest first. */
    private fun sprayAt(camera: Vec3, up: Vec3, east: Vec3, north: Vec3, time: Double): Spray? {
        val terrain = body.terrain ?: return null
        val n = sprayBearings
        val slot = IntArray(n); val at = DoubleArray(3 * n); val velocity = DoubleArray(3 * n); val strength = DoubleArray(n)
        var count = 0
        for (k in 0 until n) {
            val a = 2.0 * kotlin.math.PI * k / n
            val c = kotlin.math.cos(a); val sn = kotlin.math.sin(a)
            var r = 0.0
            while (r <= sprayReach) {
                sample.setTo(up).addScaledInPlace(east, r * c / body.radius).addScaledInPlace(north, r * sn / body.radius).normalizeInPlace()
                val bed = terrain.elevation(sample)
                val h = weather.tsunamiHeight(sample, time, bed, sprayFlow)
                val foam = weather.tsunamiFoam()
                val water = h - bed
                if (foam >= 0.99 && water > 0.3 && sprayFlow.length > 0.5) {
                    // The front runs with the flood: shallow-water speed at sea, slower over land.
                    val speed = if (bed <= 0.0) sqrt(G * kotlin.math.max(-bed + kotlin.math.max(h, 0.0), 1.0)).coerceAtMost(SPRAY_FASTEST)
                        else SPRAY_LAND_SPEED
                    sprayFlow.normalizeInPlace().mulInPlace(speed)
                    val level = body.radius + kotlin.math.max(bed, h)
                    at[3 * count] = sample.x * level; at[3 * count + 1] = sample.y * level; at[3 * count + 2] = sample.z * level
                    velocity[3 * count] = sprayFlow.x; velocity[3 * count + 1] = sprayFlow.y; velocity[3 * count + 2] = sprayFlow.z
                    strength[count] = smooth(0.5, 3.0, kotlin.math.abs(h))
                    slot[count] = k
                    count++
                    break
                }
                r += SPRAY_STEP
            }
        }
        return if (count == 0) null else Spray(time, count, slot, at, velocity, strength)
    }

    private val puffs = Array(com.rm.apogee.render.CloudShapes.VARIANTS) { com.rm.apogee.render.CloudPuff(it, false, 1) }
    private val sky = com.rm.apogee.render.SkyColours.of(body.id)
    private val sprayAtFrame = Vec3()
    private val sprayUp = Vec3()
    private val upright = com.rm.apogee.core.math.Quat()
    private val turn = com.rm.apogee.core.math.Quat()

    /**
     * Spray and mist along the bore's front near body-fixed [camera], carried on along the front
     * since it was found, turned to where the planet is at [bodyRotation], to [out], in items from
     * [pool] (new ones without). Two puffs per bearing at most, none past [sprayReach].
     */
    fun append(camera: Vec3, bodyRotation: com.rm.apogee.core.math.Quat, time: Double, out: MutableList<com.rm.apogee.render.RenderItem>, pool: com.rm.apogee.render.RenderItemPool? = null) {
        val sp = spray ?: return
        val age = time - sp.time
        if (age < -1.0 || age > SPRAY_HOLD) return
        val fade = 1.0 - smooth(SPRAY_HOLD * 0.6, SPRAY_HOLD, age)
        for (i in 0 until sp.count) {
            sprayAtFrame.setTo(sp.at[3 * i], sp.at[3 * i + 1], sp.at[3 * i + 2])
            sprayAtFrame.x += sp.velocity[3 * i] * age; sprayAtFrame.y += sp.velocity[3 * i + 1] * age; sprayAtFrame.z += sp.velocity[3 * i + 2] * age
            val d = sprayAtFrame.distanceTo(camera)
            if (d > sprayReach) continue
            val alpha = (SPRAY_ALPHA * sp.strength[i] * fade * (1.0 - smooth(sprayReach * 0.7, sprayReach, d))).toFloat()
            if (alpha < 0.02f) continue
            sprayUp.setTo(sprayAtFrame).normalizeInPlace()
            fromTo(UNIT_Y, sprayUp, upright)
            val k = sp.slot[i]
            val pieces = if (tier == QualityTier.LOW || d > sprayReach * 0.5) 1 else 2
            for (j in 0 until pieces) {
                val wide = SPRAY_WIDE * (1.0 - 0.35 * j)
                val tall = SPRAY_TALL * (1.0 + 0.6 * j)
                val item = pool?.take() ?: com.rm.apogee.render.RenderItemPool.blank()
                val lift = tall * (0.3 + 0.4 * j)
                bodyRotation.rotate(sprayUp.mulInPlace(lift).addInPlace(sprayAtFrame), item.position)
                sprayUp.setTo(sprayAtFrame).normalizeInPlace()
                com.rm.apogee.core.math.Quat.fromAxisAngle(UNIT_Y, 1.7 * k + 0.9 * j, turn)
                item.rotation.setTo(bodyRotation).mulInPlace(upright).mulInPlace(turn)
                val c = item.ownColour()
                c[0] = 0.90f; c[1] = 0.93f; c[2] = 0.95f
                val t = sky.cloud
                c[0] *= t[0]; c[1] *= t[1]; c[2] *= t[2]
                c[3] = alpha * (1f - 0.35f * j)
                out.add(item.refill(puffs[(k + j) % puffs.size], c, com.rm.apogee.render.StackCaps.BOTH, item.ownScale().setTo(wide, tall, wide),
                    SPRAY_AMBIENT, true, com.rm.apogee.render.RenderItem.cloudKey(SPRAY_KEY, 2 * k + j), orderedTransparency = true))
            }
        }
    }

    /** The shortest turn taking unit [a] to unit [b], into [out], without making anything. */
    private fun fromTo(a: Vec3, b: Vec3, out: com.rm.apogee.core.math.Quat): com.rm.apogee.core.math.Quat {
        val d = a dot b
        if (d <= -1.0 + 1e-9) return out.setTo(1.0, 0.0, 0.0, 0.0)
        return out.setTo(a.y * b.z - a.z * b.y, a.z * b.x - a.x * b.z, a.x * b.y - a.y * b.x, 1.0 + d).normalizeInPlace()
    }

    // --- hearing ---------------------------------------------------------------

    /** How loud the roar is, 0..1, after [heard]. */
    var loudness = 0.0
        private set

    /** How close the bore is, 0..1, after [heard]. */
    var closeness = 0.0
        private set

    /** Which way it's heard from, body-fixed, unit, after [heard]. */
    val heardFrom = Vec3()

    private var heardAt = Double.NaN

    /**
     * Works out [loudness], [closeness] and [heardFrom] for ears at body-fixed [camera] at [time],
     * eased toward the last working out so it swells and fades rather than steps.
     */
    fun heard(camera: Vec3, time: Double) {
        val r = roar
        val step = if (heardAt.isNaN()) 1.0 else (kotlin.math.abs(time - heardAt) / HEARD_EASE).coerceAtMost(1.0)
        heardAt = time
        loudness += ((r?.loudness ?: 0.0) - loudness) * step
        closeness += ((r?.closeness ?: 0.0) - closeness) * step
        if (loudness < 1e-3) loudness = 0.0
        if (r != null) {
            heardFrom.setTo(r.from).subInPlace(camera)
            if (heardFrom.length > 1e-6) heardFrom.normalizeInPlace()
        }
    }

    private fun smooth(a: Double, b: Double, x: Double): Double {
        val t = ((x - a) / (b - a)).coerceIn(0.0, 1.0)
        return t * t * (3 - 2 * t)
    }

    companion object {
        /** Worked out again this often, in seconds, or after the camera moves this far, in metres. */
        const val RELIST_SECONDS = 1.0
        const val RELIST_DISTANCE = 2_000.0

        /** The map's listing, in seconds. */
        const val WORLD_RELIST_SECONDS = 5.0

        /** None worked out with the camera higher than this, in metres. */
        const val MOST_ABOVE = 100_000.0

        /** Land counts as water this deep, in metres, for the sea run on over it. */
        const val SHALLOW = 2.0

        /** Heard fully within [HEARD_NEAR] metres, gone by [HEARD_REACH], up close inside [CLOSE_REACH]. */
        const val HEARD_NEAR = 400.0
        const val HEARD_REACH = 4_000.0
        const val CLOSE_REACH = 1_200.0

        /** How far the bore's foam is widened when listening for it, in metres. */
        const val HEARD_BLUR = 500.0

        /** Seconds the roar takes to follow a new working out. */
        const val HEARD_EASE = 1.5

        /** Where the roar is listened for: rings out from the camera, in metres, and bearings on each. */
        val RINGS = doubleArrayOf(0.0, 250.0, 600.0, 1_100.0, 1_800.0, 2_700.0, 3_800.0)
        const val BEARINGS = 12

        /** Spray: steps the front is searched for in, metres; the fastest it's carried, m/s; held this long, s. */
        const val SPRAY_STEP = 40.0
        const val SPRAY_FASTEST = 20.0
        const val SPRAY_HOLD = 3.0

        /** How fast a flood runs in over land, m/s, as the model has it. */
        const val SPRAY_LAND_SPEED = 6.0

        /** A spray puff's size in metres, how see-through it is at most, and how it takes the sky's light. */
        const val SPRAY_WIDE = 28.0
        const val SPRAY_TALL = 6.0
        const val SPRAY_ALPHA = 0.7
        const val SPRAY_AMBIENT = 0.8f

        /** Keys for the spray's items, apart from the clouds'. */
        const val SPRAY_KEY = 0x75A1L

        private const val G = 9.81
        private val UNIT_Y = Vec3(0.0, 1.0, 0.0)

        /** Points round a front on the map. */
        const val FRONT_POINTS = 120
    }
}
