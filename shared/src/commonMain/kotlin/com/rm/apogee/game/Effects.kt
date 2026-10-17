package com.rm.apogee.game

import com.rm.apogee.core.world.PartEventKind
import com.rm.apogee.core.math.Quat
import com.rm.apogee.core.math.Vec3
import com.rm.apogee.core.math.quatFromTo
import com.rm.apogee.core.math.quatLookAt
import com.rm.apogee.core.orbit.CelestialBody
import com.rm.apogee.core.part.Exhaust
import com.rm.apogee.core.part.ModelSpec
import com.rm.apogee.core.terrain.Noise
import com.rm.apogee.core.weather.AirSample
import com.rm.apogee.core.weather.Strike
import com.rm.apogee.core.weather.Weather
import com.rm.apogee.render.NightLight
import com.rm.apogee.render.QualityTier
import com.rm.apogee.render.RenderItem
import kotlin.math.exp
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt
import com.rm.apogee.core.math.Math

/** One lit engine this frame, in the attractor's frame. */
class EngineEmitter(
    /** Where the exhaust leaves the nozzle. */
    val nozzle: Vec3,
    /** Unit: the way the exhaust goes. */
    val out: Vec3,
    /** The nozzle radius, in metres. */
    val radius: Double,
    val kind: Exhaust,
    val throttle: Double,
    /** The craft's velocity. */
    val velocity: Vec3,
    /** A stable number for this engine, for its flicker. */
    val seed: Int,
    /** The bell's mouth in metres, where the flame is drawn from. */
    val exitRadius: Double = radius * 1.25,
)

/**
 * What engines and weather leave in the air: flames, smoke, dust, spray, contrails, rain and
 * lightning.
 *
 * Particles live in the body's turning frame and are carried by the same [Weather] every device
 * works out, so smoke drifts, rises and swirls the same for every player. They're small
 * flat-coloured faceted hexagons; flames are see-through faceted cones that glow.
 */
class Effects(tier: QualityTier) {

    private val capacity = tier.particleBudget

    // Particle state, as a structure of arrays. Positions and velocities are body-fixed.
    private val px = DoubleArray(capacity); private val py = DoubleArray(capacity); private val pz = DoubleArray(capacity)
    private val vx = DoubleArray(capacity); private val vy = DoubleArray(capacity); private val vz = DoubleArray(capacity)
    private val age = FloatArray(capacity); private val life = FloatArray(capacity)
    private val size0 = FloatArray(capacity); private val size1 = FloatArray(capacity)
    private val colour = FloatArray(capacity * 4)
    /** How quickly it takes up the wind, per second. */
    private val grip = FloatArray(capacity)
    /** Upward acceleration from heat, in m/s^2, fading as it cools. */
    private val rise = FloatArray(capacity)
    private val streak = BooleanArray(capacity)
    /** Lights itself (fire, sparks) instead of being lit by the sun. */
    private val glows = BooleanArray(capacity)
    /** Falls and comes to rest on the ground, the way fragments and sparks do. */
    private val falls = BooleanArray(capacity)
    /** Snow: a streak only as long as its speed makes it. */
    private val flakes = BooleanArray(capacity)
    /** Hail: falls fast and bounces off the ground once or twice. */
    private val pellets = BooleanArray(capacity)
    /** Ash flecks: drawn in a dark rim, never under ASH_SEEN across as seen. */
    private val motes = BooleanArray(capacity)
    /**
     * Born since the last step, so it's drawn where it was put at the frame's own time. Moving it
     * on in that step drew anything made this frame a frame ahead of its craft (20 m at 560 m/s).
     */
    private val fresh = BooleanArray(capacity)
    private var count = 0
    private var gravity = 9.81
    private var spawnCounter = 0

    private val scratch = Vec3()
    private val scratch2 = Vec3()
    private val bodyFixed = Vec3()
    private val relative = Vec3()
    private val windLow = Vec3()
    private val windHigh = Vec3()
    private val airLow = AirSample()
    private val airHigh = AirSample()
    private val up = Vec3()
    private var focusHeight = 0.0
    private var focusGround = 0.0

    /** The emission budget per engine per second is scaled to the device. */
    private val rateScale = (capacity / 2048.0).coerceIn(0.5, 3.0)

    /**
     * A bolt: its main channel from the ground up and its branches (each from where it leaves the
     * channel), when it struck, its core's half width in metres, its strokes' start times after
     * [born] (2 or 3), and how brightly it lights the scene at its strokes' peaks, 0..1.
     */
    private class Bolt(var shape: BoltShape, val born: Double, var width: Double, val id: Long, var light: Float) {
        val strokes: DoubleArray = boltStrokes(id)
        val seed = (id xor (id ushr 32)).toInt()

        /** How bright its channel is [t] seconds after it struck: each stroke flares and dies over a dim glow. */
        fun glow(t: Double): Double {
            var glow = 0.2 * (1.0 - t / BOLT_SECONDS).coerceAtLeast(0.0)
            for (k in strokes.indices) {
                val since = t - strokes[k]
                if (since >= 0.0) glow = max(glow, exp(-since / 0.04) * (1.0 - 0.15 * k))
            }
            return glow
        }
    }

    private class BoltShape(val main: List<Vec3>, val branches: List<List<Vec3>>)
    private val bolts = ArrayList<Bolt>()
    private var lastStrikeCheck = Double.NaN
    private val strikes = ArrayList<Strike>()
    /** Strikes drawn to a craft by [struck], so the ground strike isn't drawn again. */
    private val struckIds = ArrayDeque<Long>()

    /** Particles alive right now. */
    val particleCount: Int get() = count

    /** The average position of the particles, body-fixed, into [out], for tests. */
    internal fun centroid(out: Vec3): Vec3 {
        out.setZero()
        if (count == 0) return out
        for (i in 0 until count) out.addInPlace(Vec3(px[i], py[i], pz[i]))
        return out.mulInPlace(1.0 / count)
    }

    /** Bolts seen since the last drain: distance in metres, and energy, for their thunder. */
    val thunder = ArrayList<DoubleArray>()

    /** How bright the sky is from lightning, 0..1, fading. */
    var flash = 0f
        private set

    /** How hard it's hailing at the camera, 0..1, so rain draws thinner round the stones. */
    private var hailing = 0f

    /**
     * Moves everything on by [dt] at [time]: emits from [emitters], moves particles through the
     * air, rains if [cameraAir] is raining, and lights any strike near the camera.
     */
    fun step(
        dt: Double,
        time: Double,
        body: CelestialBody,
        bodyRotation: Quat,
        emitters: List<EngineEmitter>,
        weather: Weather?,
        cameraBodyFixed: Vec3,
        cameraAir: AirSample?,
        /** Unit, body-fixed: the way the camera looks, so blown sand keeps to the edges of the view. Null for none. */
        cameraForward: Vec3? = null,
    ) {
        if (dt <= 0.0) return
        gravity = body.surfaceGravity
        val terrain = body.terrain
        val atmosphere = body.atmosphere

        // The wind at two heights over the focus; each particle takes the nearer. One sample each
        // per frame, not per particle.
        up.setTo(cameraBodyFixed).normalizeInPlace()
        focusGround = terrain?.let { max(it.elevation(up), if (it.hasOcean) 0.0 else -1e9) } ?: 0.0
        focusHeight = cameraBodyFixed.length - body.radius
        if (weather != null) {
            weather.sample(scratch.setTo(up).mulInPlace(body.radius + focusGround + 20.0), time, airLow)
            weather.sample(scratch.setTo(up).mulInPlace(body.radius + focusGround + 800.0), time, airHigh)
            windLow.setTo(airLow.wind); windHigh.setTo(airHigh.wind)
        } else {
            windLow.setZero(); windHigh.setZero()
        }

        if (atmosphere != null) {
            for (e in emitters) emit(e, dt, body, bodyRotation)
            // Rain turns to snow in a blizzard.
            if (cameraAir != null && cameraAir.precipitation > 0.02 && cameraAir.snowfall < SNOW_NOT_RAIN) rain(dt, cameraAir.precipitation, cameraBodyFixed, body)
            if (cameraAir != null && cameraAir.snow > SNOW_FROM && cameraForward != null) snow(dt, cameraAir.snow, cameraBodyFixed, cameraForward)
            if (cameraAir != null && cameraAir.hail > HAIL_FROM && cameraForward != null) hail(dt, cameraAir.hail, cameraBodyFixed, cameraForward)
            if (cameraAir != null && cameraAir.ash > ASH_FROM && cameraForward != null) ash(dt, cameraAir.ash, cameraBodyFixed, cameraForward)
            if (cameraAir != null && cameraAir.dust > SAND_FROM && focusHeight - focusGround < SAND_ABOVE && cameraForward != null) sand(dt, cameraAir.dust, cameraBodyFixed, cameraForward)
        }
        seaSpray(dt, time, body)
        plumes(dt, body, cameraBodyFixed)
        seaVents(dt, body, cameraBodyFixed)
        groundVents(dt, body, cameraBodyFixed)
        advance(dt, body)
        if (weather != null) lightning(time, weather, cameraBodyFixed, body)
        flash = (flash * exp(-dt / 0.12).toFloat()).coerceAtLeast(0f)
        // Each stroke lights the scene again.
        for (bolt in bolts) if (bolt.light > 0f) flash = max(flash, bolt.light * bolt.glow(time - bolt.born).toFloat())
        hailing = if (cameraAir == null) 0f else ((cameraAir.hail - HAIL_FROM) / 0.3).coerceIn(0.0, 1.0).toFloat()
    }

    // --- plumes -----------------------------------------------------------------

    /**
     * The sea floor's chimneys near the camera, as body-fixed tops, and where and on what they were
     * last looked for.
     */
    private val ventTops = ArrayList<Vec3>()
    private val ventsLookedFrom = Vec3()
    private var ventsBody = ""

    /**
     * The sea floor's vents near the camera at work: dark water welling up and spreading from each
     * chimney, with a faint heat shimmer at the mouth. Only in the water, and only close, since the
     * murk hides the rest.
     */
    private fun seaVents(dt: Double, body: CelestialBody, camera: Vec3) {
        val scatter = body.terrain?.scatter
        if (scatter == null || body.ocean == null || camera.length - body.radius > 0.0) {
            ventTops.clear(); ventsBody = ""; return
        }
        if (ventsBody != body.id || ventsLookedFrom.distanceTo(camera) > VENT_LOOK_AGAIN) {
            ventTops.clear()
            ventsBody = body.id
            ventsLookedFrom.setTo(camera)
            scatter.forEachBlockNear(camera, VENT_REACH, scratch2) { block ->
                for (k in 0 until block.count) {
                    val kind = com.rm.apogee.core.terrain.ScatterKind.of(block.kinds[k].toInt())
                    if (kind != com.rm.apogee.core.terrain.ScatterKind.VENT) continue
                    val at = Vec3(block.x[k], block.y[k], block.z[k])
                    if (at.distanceTo(camera) > VENT_REACH) continue
                    // Its mouth, at the top of the chimney.
                    ventTops += at.addScaledInPlace(at.copy().normalizeInPlace(), kind.height * block.sizes[k])
                }
            }
        }
        for ((n, top) in ventTops.withIndex()) {
            up.setTo(top).normalizeInPlace()
            val puffs = poisson(VENT_PUFFS * rateScale * dt, 0x5E47 + n)
            for (k in 0 until puffs) {
                val rise = 0.6 + 0.5 * rand(k)
                spawn(
                    x = top.x + jitter(k, 1) * 0.3, y = top.y + jitter(k, 2) * 0.3, z = top.z + jitter(k, 3) * 0.3,
                    vx = up.x * rise + jitter(k, 4) * 0.08, vy = up.y * rise + jitter(k, 5) * 0.08, vz = up.z * rise + jitter(k, 6) * 0.08,
                    life = 10.0 + 6.0 * rand(k + 1), startSize = 0.6, endSize = 4.5,
                    r = 0.20f, g = 0.18f, b = 0.16f, a = 0.55f, grip = 0.0, rise = 0.0,
                )
            }
            val shimmer = poisson(VENT_SHIMMER * rateScale * dt, 0x5E48 + n)
            for (k in 0 until shimmer) {
                spawn(
                    x = top.x, y = top.y, z = top.z,
                    vx = up.x * 1.2, vy = up.y * 1.2, vz = up.z * 1.2,
                    life = 0.8 + 0.6 * rand(k), startSize = 0.5, endSize = 1.1,
                    r = 0.55f, g = 0.22f, b = 0.08f, a = 0.25f, grip = 0.0, rise = 0.0, glow = true,
                )
            }
        }
    }

    /** The geysers and fumaroles near the camera, as body-fixed mouths, and which each is. */
    private val groundMouths = ArrayList<Vec3>()
    private val groundGeysers = ArrayList<Boolean>()
    private val groundLookedFrom = Vec3()
    private var groundBody = ""

    /**
     * Geysers spraying and fumaroles fuming near the camera, on dry ground. A geyser throws a
     * narrow column of frost that falls back round it; a fumarole puffs pale gas.
     */
    private fun groundVents(dt: Double, body: CelestialBody, camera: Vec3) {
        val scatter = body.terrain?.scatter
        val height = camera.length - body.radius
        if (scatter == null || !body.terrain!!.barren || height > GROUND_VENT_REACH + body.terrain!!.maxElevation) {
            groundMouths.clear(); groundGeysers.clear(); groundBody = ""; return
        }
        if (groundBody != body.id || groundLookedFrom.distanceTo(camera) > GROUND_VENT_LOOK_AGAIN) {
            groundMouths.clear(); groundGeysers.clear()
            groundBody = body.id
            groundLookedFrom.setTo(camera)
            scatter.forEachBlockNear(camera, GROUND_VENT_REACH, scratch2) { block ->
                for (k in 0 until block.count) {
                    val kind = com.rm.apogee.core.terrain.ScatterKind.of(block.kinds[k].toInt())
                    val geyser = kind == com.rm.apogee.core.terrain.ScatterKind.GEYSER
                    if (!geyser && kind != com.rm.apogee.core.terrain.ScatterKind.FUMAROLE) continue
                    val at = Vec3(block.x[k], block.y[k], block.z[k])
                    if (at.distanceTo(camera) > GROUND_VENT_REACH) continue
                    groundMouths += at.addScaledInPlace(at.copy().normalizeInPlace(), kind.height * block.sizes[k])
                    groundGeysers += geyser
                }
            }
        }
        val g = body.surfaceGravity
        // Aversa's jets are dark with the dust they carry.
        val dark = body.id == "aversa"
        for ((n, mouth) in groundMouths.withIndex()) {
            up.setTo(mouth).normalizeInPlace()
            if (groundGeysers[n]) {
                val speed = kotlin.math.sqrt(2.0 * g * GEYSER_HEIGHT)
                val count = poisson(GEYSER_RATE * rateScale * dt, 0x6E75 + n)
                for (k in 0 until count) {
                    val v = speed * (0.7 + 0.3 * rand(k))
                    val shade = 0.85f + 0.15f * rand(k + 1)
                    val (r, gg, b) = if (dark) Triple(0.22f, 0.20f, 0.19f) else Triple(0.92f, 0.95f, 0.98f)
                    spawn(
                        x = mouth.x, y = mouth.y, z = mouth.z,
                        vx = up.x * v + jitter(k, 4) * 0.6, vy = up.y * v + jitter(k, 5) * 0.6, vz = up.z * v + jitter(k, 6) * 0.6,
                        life = 2.0 * v / g, startSize = 0.4, endSize = 1.6,
                        r = r * shade, g = gg * shade, b = b * shade, a = 0.6f, grip = 0.0, rise = 0.0, fall = true,
                    )
                }
            } else {
                val count = poisson(FUMAROLE_RATE * rateScale * dt, 0x6E76 + n)
                for (k in 0 until count) {
                    val rise = 0.5 + 0.4 * rand(k)
                    spawn(
                        x = mouth.x + jitter(k, 1) * 0.2, y = mouth.y + jitter(k, 2) * 0.2, z = mouth.z + jitter(k, 3) * 0.2,
                        vx = up.x * rise + jitter(k, 4) * 0.1, vy = up.y * rise + jitter(k, 5) * 0.1, vz = up.z * rise + jitter(k, 6) * 0.1,
                        life = 5.0 + 3.0 * rand(k + 1), startSize = 0.5, endSize = 3.5,
                        r = 0.88f, g = 0.86f, b = 0.74f, a = 0.35f, grip = 0.0, rise = 0.0,
                    )
                }
            }
        }
    }

    /**
     * A world's vents at work near the camera: Fornax's volcanoes throwing sulfur kilometres up,
     * Fons's geysers, Aversa's dark jets. Only for looking at: fountains of big particles arcing up
     * and back with no air to slow them.
     */
    private fun plumes(dt: Double, body: CelestialBody, camera: Vec3) {
        val vents = PLUMES[body.id] ?: return
        val terrain = body.terrain
        for ((n, vent) in vents.withIndex()) {
            val d = vent.direction
            val ground = terrain?.elevation(d) ?: 0.0
            val at = scratch2.setTo(d).mulInPlace(body.radius + ground)
            if (at.distanceTo(camera) > PLUME_REACH) continue
            val speed = kotlin.math.sqrt(2.0 * body.surfaceGravity * vent.height)
            val count = poisson(vent.rate * rateScale * dt, 0x9107 + n)
            // Across the vent's two sides, for the fountain's spread.
            val e = Vec3(d.z, 0.0, -d.x).let { if (it.lengthSq < 1e-12) it.setTo(1.0, 0.0, 0.0) else it.normalizeInPlace() }
            val nth = Vec3().setTo(d).crossInPlace(e)
            for (k in 0 until count) {
                val a = rand(k) * 2.0 * Math.PI
                val lean = vent.spread * kotlin.math.sqrt(rand(k + 1).toDouble())
                val v = speed * (0.75 + 0.25 * rand(k + 2))
                val vx = (d.x + (e.x * kotlin.math.cos(a) + nth.x * kotlin.math.sin(a)) * lean) * v
                val vy = (d.y + (e.y * kotlin.math.cos(a) + nth.y * kotlin.math.sin(a)) * lean) * v
                val vz = (d.z + (e.z * kotlin.math.cos(a) + nth.z * kotlin.math.sin(a)) * lean) * v
                val shade = 0.85f + 0.15f * rand(k + 3)
                spawn(
                    x = at.x, y = at.y, z = at.z, vx = vx, vy = vy, vz = vz,
                    life = 2.0 * v / body.surfaceGravity, startSize = vent.size, endSize = vent.size * 3.0,
                    r = vent.colour[0] * shade, g = vent.colour[1] * shade, b = vent.colour[2] * shade, a = 0.55f,
                    grip = 0.0, rise = 0.0, fall = true,
                )
            }
        }
    }

    private class Vent(latitude: Double, longitude: Double, val height: Double, val rate: Double, val size: Double, val spread: Double, val colour: FloatArray) {
        val direction: Vec3 = com.rm.apogee.core.orbit.SolarSystem.surfaceDirection(Math.toRadians(latitude), Math.toRadians(longitude))
    }

    // --- the sea ---------------------------------------------------------------

    /** Samples the sea at a body-fixed point, for spray and wakes. Null where there's no sea. */
    var sea: ((Vec3, Double, com.rm.apogee.core.sea.SeaSample) -> Unit)? = null
    private val seaSample = com.rm.apogee.core.sea.SeaSample()
    private val seaPoint = Vec3()

    /**
     * Spray torn off breaking crests by strong wind or storm round the camera. Tried at a handful
     * of points a frame and thrown up and downwind where the sea is breaking.
     */
    private fun seaSpray(dt: Double, time: Double, body: CelestialBody) {
        val sample = sea ?: return
        if (focusHeight - max(focusGround, 0.0) > SPRAY_HEIGHT) return
        val east = scratch.setTo(up.z, 0.0, -up.x).let { if (it.lengthSq < 1e-12) it.setTo(1.0, 0.0, 0.0) else it.normalizeInPlace() }
        val north = Vec3().setTo(up).crossInPlace(east)
        val tries = poisson(SPRAY_TRIES * rateScale * dt, 0x5E4)
        for (k in 0 until tries) {
            val a = rand(k) * 2.0 * Math.PI
            val r = SPRAY_REACH * kotlin.math.sqrt(rand(k + 1).toDouble())
            seaPoint.setTo(up).mulInPlace(body.radius)
                .addScaledInPlace(east, kotlin.math.cos(a) * r).addScaledInPlace(north, kotlin.math.sin(a) * r)
            sample(seaPoint, time, seaSample)
            if (seaSample.depth <= 0.0 || seaSample.breaking < 0.35) continue
            val blow = smoothstep(9.0, 18.0, seaSample.wind + seaSample.stormHeight * 1.5)
            if (blow <= 0.0) continue
            seaPoint.normalizeInPlace().mulInPlace(body.radius + seaSample.height)
            val n = (1 + 4 * blow).toInt()
            for (j in 0 until n) {
                val lift = 2.0 + 4.0 * rand(k * 7 + j)
                spawn(
                    x = seaPoint.x + jitter(j, 1), y = seaPoint.y + jitter(j, 2), z = seaPoint.z + jitter(j, 3),
                    vx = windLow.x * 0.8 + up.x * lift, vy = windLow.y * 0.8 + up.y * lift, vz = windLow.z * 0.8 + up.z * lift,
                    life = 1.2 + 1.8 * rand(k * 7 + j + 3), startSize = 0.4, endSize = 1.2 + 1.0 * blow,
                    r = 0.9f, g = 0.95f, b = 0.98f, a = 0.45f, grip = 0.8, rise = 0.0, fall = true,
                )
            }
        }
    }

    /**
     * A hull moving through the water from [stern] to [bow] (world), [beam] wide, at [velocity]
     * (world) over the ground. Foam spreads in a vee from its stern and quarters, and spray flies
     * from its bow when it drives into the sea.
     */
    fun wake(stern: Vec3, bow: Vec3, beam: Double, velocity: Vec3, body: CelestialBody, bodyRotation: Quat, time: Double, dt: Double, seed: Int) {
        val sample = sea ?: return
        bodyRotation.inverseRotate(stern, bodyFixed)
        sample(bodyFixed, time, seaSample)
        if (seaSample.depth <= 0.0) return
        // Only while it's in the water.
        if (kotlin.math.abs(bodyFixed.length - body.radius - seaSample.height) > beam + 1.5) return
        // Through the water, not over the ground: a boat carried by the waves' own motion makes no
        // wake.
        body.surfaceVelocityAt(stern, scratch)
        relative.setTo(velocity).subInPlace(scratch)
        bodyRotation.inverseRotate(relative, relative)
        relative.subInPlace(seaSample.velocity)
        val speed = relative.length
        // Foam in a big sea breaks up in moments.
        val lasting = 1.0 / (1.0 + seaSample.significantHeight / 1.5)
        if (speed < 0.8) return
        val forward = scratch2.setTo(relative).mulInPlace(1.0 / speed)
        val u = Vec3().setTo(bodyFixed).normalizeInPlace()
        val side = Vec3().setTo(forward).crossInPlace(u).normalizeInPlace()
        val surface = Vec3().setTo(u).mulInPlace(body.radius + seaSample.height)
        val n = poisson(WAKE_RATE * rateScale * kotlin.math.min(speed, 12.0) * dt, seed)
        for (k in 0 until n) {
            // Out to either quarter, spreading at the wake's angle.
            val sideways = if (k % 2 == 0) 1.0 else -1.0
            val spread = speed * 0.35
            spawn(
                x = surface.x + side.x * sideways * beam, y = surface.y + side.y * sideways * beam, z = surface.z + side.z * sideways * beam,
                vx = side.x * sideways * spread - forward.x * speed * 0.1,
                vy = side.y * sideways * spread - forward.y * speed * 0.1,
                vz = side.z * sideways * spread - forward.z * speed * 0.1,
                life = (7.0 + 6.0 * rand(k)) * lasting, startSize = 0.6, endSize = 3.0 + 0.2 * speed,
                r = 0.93f, g = 0.96f, b = 1f, a = 0.7f, grip = 0.0, rise = 0.0,
            )
        }
        // Bow spray, when it drives into the sea fast enough.
        if (speed < 3.0) return
        bodyRotation.inverseRotate(bow, seaPoint)
        sample(seaPoint, time, seaSample)
        val under = body.radius + seaSample.height - seaPoint.length
        if (under < -0.3) return
        val m = poisson(BOW_RATE * rateScale * (speed - 3.0) * dt, seed + 1)
        for (k in 0 until m) {
            val sideways = if (k % 2 == 0) 1.0 else -1.0
            val throwOut = 1.0 + 0.25 * speed * rand(k + 5)
            val lift = 1.5 + 0.3 * speed * rand(k + 6)
            spawn(
                x = seaPoint.x, y = seaPoint.y, z = seaPoint.z,
                vx = side.x * sideways * throwOut + u.x * lift + forward.x * speed * 0.3,
                vy = side.y * sideways * throwOut + u.y * lift + forward.y * speed * 0.3,
                vz = side.z * sideways * throwOut + u.z * lift + forward.z * speed * 0.3,
                life = 0.8 + 0.8 * rand(k + 7), startSize = 0.3, endSize = 1.2,
                r = 0.93f, g = 0.97f, b = 1f, a = 0.75f, grip = 0.3, rise = 0.0, fall = true,
            )
        }
    }

    // --- emission -------------------------------------------------------------

    private fun emit(e: EngineEmitter, dt: Double, body: CelestialBody, bodyRotation: Quat) {
        val throttle = e.throttle
        if (throttle <= 0.01) return
        // Into the body's frame: where, which way, and how fast over the ground.
        bodyRotation.inverseRotate(e.nozzle, bodyFixed)
        val altitude = bodyFixed.length - body.radius
        val density = body.atmosphere?.densityAt(altitude) ?: 0.0
        if (density <= 1e-4) return
        val thickness = (density / 1.225).coerceIn(0.0, 1.0)
        body.surfaceVelocityAt(e.nozzle, scratch)
        relative.setTo(e.velocity).subInPlace(scratch)
        bodyRotation.inverseRotate(relative, relative)
        bodyRotation.inverseRotate(e.out, scratch2)
        val out = scratch2
        up.setTo(bodyFixed).normalizeInPlace()
        val elevation = body.terrain?.elevation(up)
        overSea = elevation != null && body.terrain?.hasOcean == true && elevation < 0.0
        val ground = body.terrain?.let { t -> max(elevation!!, if (t.hasOcean) 0.0 else -1e9) } ?: 0.0
        val agl = altitude - ground

        when (e.kind) {
            Exhaust.ROCKET -> {
                // Smoke off the end of the flame, thick low down and thin high up.
                val n = poisson(60.0 * rateScale * throttle * thickness * dt, e.seed)
                val flame = e.radius * 12.0 * throttle
                for (k in 0 until n) {
                    val grey = 0.78f + 0.15f * rand(k)
                    spawn(
                        x = bodyFixed.x + out.x * flame, y = bodyFixed.y + out.y * flame, z = bodyFixed.z + out.z * flame,
                        vx = relative.x + out.x * 30.0 + jitter(k, 1) * 4.0,
                        vy = relative.y + out.y * 30.0 + jitter(k, 2) * 4.0,
                        vz = relative.z + out.z * 30.0 + jitter(k, 3) * 4.0,
                        life = (5.0 + 5.0 * rand(k + 7)) * (0.4 + 0.6 * thickness),
                        startSize = e.radius * 2.5, endSize = e.radius * (8.0 + 10.0 * thickness),
                        r = grey, g = grey, b = grey * 1.02f, a = 0.6f * thickness.toFloat() + 0.2f,
                        grip = 1.2, rise = 1.5,
                    )
                }
                // Near the ground the blast throws up a cloud of dust and steam.
                if (agl < 45.0) {
                    val strength = 1.0 - agl / 45.0
                    val m = poisson(70.0 * rateScale * throttle * strength * dt, e.seed + 1)
                    for (k in 0 until m) {
                        val a = rand(k + 3) * 2.0 * Math.PI
                        val east = scratch.setTo(up.z, 0.0, -up.x).let { if (it.lengthSq < 1e-12) it.setTo(1.0, 0.0, 0.0) else it.normalizeInPlace() }
                        val north = Vec3().setTo(up).crossInPlace(east)
                        val dirX = east.x * kotlin.math.cos(a) + north.x * kotlin.math.sin(a)
                        val dirY = east.y * kotlin.math.cos(a) + north.y * kotlin.math.sin(a)
                        val dirZ = east.z * kotlin.math.cos(a) + north.z * kotlin.math.sin(a)
                        val speed = 12.0 + 20.0 * rand(k + 5)
                        val gx = bodyFixed.x - up.x * agl; val gy = bodyFixed.y - up.y * agl; val gz = bodyFixed.z - up.z * agl
                        val tone = 0.62f + 0.2f * rand(k + 11)
                        // Over the sea, a white wall of spray and steam.
                        spawn(
                            x = gx, y = gy, z = gz,
                            vx = dirX * speed + up.x * 2.0, vy = dirY * speed + up.y * 2.0, vz = dirZ * speed + up.z * 2.0,
                            life = 6.0 + 6.0 * rand(k + 13),
                            startSize = 2.0, endSize = 10.0 + 8.0 * rand(k + 17),
                            r = if (overSea) 0.94f else tone, g = if (overSea) 0.96f else tone * 0.95f, b = if (overSea) 0.99f else tone * 0.88f, a = 0.6f,
                            grip = 0.6, rise = 0.6,
                        )
                    }
                }
            }
            Exhaust.JET -> {
                // A contrail, where the air is cold enough.
                if (altitude > CONTRAIL_ALTITUDE) {
                    val n = poisson(24.0 * rateScale * throttle * dt, e.seed)
                    for (k in 0 until n) {
                        spawn(
                            x = bodyFixed.x + out.x * e.radius * 4, y = bodyFixed.y + out.y * e.radius * 4, z = bodyFixed.z + out.z * e.radius * 4,
                            vx = relative.x * 0.2, vy = relative.y * 0.2, vz = relative.z * 0.2,
                            life = 25.0 + 10.0 * rand(k), startSize = e.radius * 1.5, endSize = e.radius * 7.0,
                            r = 0.97f, g = 0.98f, b = 1f, a = 0.55f, grip = 2.0, rise = 0.0,
                        )
                    }
                }
                if (agl < 12.0) dust(e, bodyFixed, up, agl, throttle * 0.5, dt)
            }
            Exhaust.PROP -> if (agl < 15.0) dust(e, bodyFixed, up, agl, throttle, dt)
            Exhaust.WATER -> {
                // Churned white water on the surface behind the motor.
                if (agl > 1.5) return
                val n = poisson(30.0 * rateScale * throttle * dt, e.seed)
                for (k in 0 until n) {
                    val sx = bodyFixed.x - up.x * agl; val sy = bodyFixed.y - up.y * agl; val sz = bodyFixed.z - up.z * agl
                    spawn(
                        x = sx + jitter(k, 1) * 0.4, y = sy + jitter(k, 2) * 0.4, z = sz + jitter(k, 3) * 0.4,
                        vx = out.x * 2.0, vy = out.y * 2.0, vz = out.z * 2.0,
                        life = 5.0 + 3.0 * rand(k), startSize = 0.4, endSize = 2.5,
                        r = 0.95f, g = 0.97f, b = 1f, a = 0.8f, grip = 0.0, rise = 0.0,
                    )
                }
            }
        }
    }

    private fun dust(e: EngineEmitter, at: Vec3, up: Vec3, agl: Double, strength: Double, dt: Double) {
        val n = poisson(20.0 * rateScale * strength * (1.0 - agl / 15.0).coerceIn(0.0, 1.0) * dt, e.seed + 2)
        // Over the sea it's spray, not dust.
        val wet = overSea
        for (k in 0 until n) {
            val tone = 0.6f + 0.2f * rand(k)
            spawn(
                x = at.x - up.x * agl + jitter(k, 1) * 3, y = at.y - up.y * agl + jitter(k, 2) * 3, z = at.z - up.z * agl + jitter(k, 3) * 3,
                vx = jitter(k, 4) * 6 + up.x, vy = jitter(k, 5) * 6 + up.y, vz = jitter(k, 6) * 6 + up.z,
                life = 3.0 + 3.0 * rand(k + 1), startSize = 1.0, endSize = 5.0,
                r = if (wet) 0.92f else tone, g = if (wet) 0.95f else tone * 0.93f, b = if (wet) 0.98f else tone * 0.82f,
                a = 0.5f, grip = 1.0, rise = 0.3, fall = wet,
            )
        }
    }

    /** Whether the emitter being worked on is over the sea. */
    private var overSea = false

    private fun rain(dt: Double, precipitation: Double, camera: Vec3, body: CelestialBody) {
        up.setTo(camera).normalizeInPlace()
        // Few enough that a downpour keeps under [RAIN_SHARE] of the budget, so new drops don't
        // push out smoke, spray or flame.
        val life = 70.0 / RAIN_FALL
        val rate = kotlin.math.min(1_800.0 * rateScale, capacity * RAIN_SHARE / life)
        val n = poisson(precipitation * rate * dt, 991)
        for (k in 0 until n) {
            val ox = jitter(k, 1) * RAIN_RADIUS; val oy = jitter(k, 2) * RAIN_RADIUS; val oz = jitter(k, 3) * RAIN_RADIUS
            spawn(
                x = camera.x + ox + up.x * 35.0, y = camera.y + oy + up.y * 35.0, z = camera.z + oz + up.z * 35.0,
                vx = windLow.x - up.x * RAIN_FALL, vy = windLow.y - up.y * RAIN_FALL, vz = windLow.z - up.z * RAIN_FALL,
                life = 70.0 / RAIN_FALL, startSize = 0.04, endSize = 0.04,
                r = 0.72f, g = 0.78f, b = 0.86f, a = 0.7f, grip = 0.0, rise = 0.0, streak = true,
            )
        }
    }

    /**
     * Sand blown past the camera in thick dust near the ground: a few thin streaks along the wind,
     * only round the edges of the view ([forward] the way it looks), start to end, so the middle stays clear.
     */
    private fun sand(dt: Double, dust: Double, camera: Vec3, forward: Vec3) {
        up.setTo(camera).normalizeInPlace()
        val speed = windLow.length
        if (speed < 4.0) return
        val n = poisson(SAND_RATE * rateScale * ((dust - SAND_FROM) / (1.0 - SAND_FROM)) * dt, 993)
        for (k in 0 until n) {
            // Somewhere round the camera, a few metres off, and upwind so it blows past.
            val ox = jitter(k, 1); val oy = jitter(k, 2); val oz = jitter(k, 3)
            scratch.setTo(ox, oy, oz).addScaledInPlace(up, -(ox * up.x + oy * up.y + oz * up.z))
            if (scratch.lengthSq < 1e-6) continue
            scratch.normalizeInPlace().mulInPlace(SAND_NEAR + (SAND_FAR - SAND_NEAR) * rand(k)).addScaledInPlace(windLow, -0.6)
            val lift = 3.0 * jitter(k, 4)
            scratch.addScaledInPlace(up, lift)
            // Kept to the edges: off the middle all along the way it blows.
            var clear = true
            for (step in 0..4) {
                sandEnd.setTo(scratch).addScaledInPlace(windLow, 1.1 * SAND_LIFE * step / 4.0)
                if (!offCentre(sandEnd, forward)) { clear = false; break }
            }
            if (!clear) continue
            scratch.addScaledInPlace(up, -lift)
            spawn(
                x = camera.x + scratch.x + up.x * lift, y = camera.y + scratch.y + up.y * lift, z = camera.z + scratch.z + up.z * lift,
                vx = windLow.x * 1.1 + up.x * jitter(k, 5), vy = windLow.y * 1.1 + up.y * jitter(k, 6), vz = windLow.z * 1.1 + up.z * jitter(k, 7),
                life = SAND_LIFE, startSize = 0.025, endSize = 0.025,
                r = 0.66f, g = 0.54f, b = 0.40f, a = 0.3f, grip = 0.0, rise = 0.0, streak = true,
            )
        }
    }

    private val sandEnd = Vec3()

    /**
     * Snow driven past the camera in a blizzard: flakes going with the wind, as streaks once it
     * blows hard, kept off the very middle of the view ([forward] the way it looks), and snow
     * drifting low over the ground with the camera near it.
     */
    private fun snow(dt: Double, snow: Double, camera: Vec3, forward: Vec3) {
        up.setTo(camera).normalizeInPlace()
        val speed = windLow.length
        val streak = speed > SNOW_STREAK
        val life = SNOW_LIFE
        val rate = kotlin.math.min(SNOW_RATE * rateScale, capacity * SNOW_SHARE / life)
        val n = poisson(rate * ((snow - SNOW_FROM) / (1.0 - SNOW_FROM)).coerceIn(0.0, 1.0) * dt, 995)
        val low = focusHeight - focusGround < SAND_ABOVE
        for (k in 0 until n) {
            val ox = jitter(k, 1); val oy = jitter(k, 2); val oz = jitter(k, 3)
            scratch.setTo(ox, oy, oz)
            if (scratch.lengthSq < 1e-6) continue
            scratch.normalizeInPlace().mulInPlace(SNOW_NEAR + (SNOW_FAR - SNOW_NEAR) * rand(k)).addScaledInPlace(windLow, -0.5 * life)
            // A share of it drifting along just over the ground, near the camera.
            val drifting = low && k % 3 == 0
            if (drifting) {
                val h = (focusHeight - focusGround).coerceAtLeast(0.0)
                scratch.addScaledInPlace(up, -(scratch dot up) - h + 0.2 + 1.2 * rand(k + 7))
            }
            val fall = if (drifting) 0.0 else SNOW_FALL
            // Off the very middle all along the way it goes.
            var clear = true
            for (step in 0..4) {
                sandEnd.setTo(scratch).addScaledInPlace(windLow, life * step / 4.0).addScaledInPlace(up, -fall * life * step / 4.0)
                if (!offCentre(sandEnd, forward, SNOW_CENTRE)) { clear = false; break }
            }
            if (!clear) continue
            spawn(
                x = camera.x + scratch.x, y = camera.y + scratch.y, z = camera.z + scratch.z,
                vx = windLow.x + up.x * (0.6 * jitter(k, 5) - fall), vy = windLow.y + up.y * (0.6 * jitter(k, 6) - fall), vz = windLow.z + up.z * (0.6 * jitter(k, 7) - fall),
                life = life, startSize = if (streak || drifting) 0.03 else 0.05, endSize = if (streak || drifting) 0.03 else 0.05,
                r = 0.93f, g = 0.95f, b = 0.98f, a = if (drifting) 0.45f else 0.75f, grip = 0.0, rise = 0.0, streak = streak || drifting, flake = true,
            )
        }
    }

    /**
     * Hailstones falling past the camera: small white pellets, fast, kept off the very middle of
     * the view ([forward] the way it looks), bouncing off the ground near it.
     */
    private fun hail(dt: Double, hail: Double, camera: Vec3, forward: Vec3) {
        up.setTo(camera).normalizeInPlace()
        val rate = kotlin.math.min(HAIL_RATE * rateScale, capacity * HAIL_SHARE / HAIL_LIFE)
        val n = poisson(rate * ((hail - HAIL_FROM) / (1.0 - HAIL_FROM)).coerceIn(0.0, 1.0) * dt, 997)
        // Starting high enough over the camera to be at full speed past it.
        val drop = HAIL_FALL * HAIL_LIFE * 0.4
        for (k in 0 until n) {
            val ox = jitter(k, 1); val oy = jitter(k, 2); val oz = jitter(k, 3)
            scratch.setTo(ox, oy, oz).addScaledInPlace(up, -(ox * up.x + oy * up.y + oz * up.z))
            if (scratch.lengthSq < 1e-6) continue
            scratch.normalizeInPlace().mulInPlace(HAIL_NEAR + (HAIL_FAR - HAIL_NEAR) * rand(k)).addScaledInPlace(up, drop * (0.5 + rand(k + 5)))
            scratch.addScaledInPlace(windLow, -0.4 * HAIL_LIFE * 0.4)
            var clear = true
            for (step in 0..4) {
                sandEnd.setTo(scratch).addScaledInPlace(windLow, 0.4 * HAIL_LIFE * step / 4.0).addScaledInPlace(up, -HAIL_FALL * HAIL_LIFE * step / 4.0)
                if (!offCentre(sandEnd, forward, SNOW_CENTRE)) { clear = false; break }
            }
            if (!clear) continue
            val fall = HAIL_FALL * (0.85 + 0.3 * rand(k + 9))
            // White to ice grey, 3-5.5 cm across as drawn.
            val size = 0.016 + 0.012 * rand(k + 11)
            val grey = 0.8f + 0.18f * rand(k + 13)
            spawn(
                x = camera.x + scratch.x, y = camera.y + scratch.y, z = camera.z + scratch.z,
                vx = windLow.x * 0.4 - up.x * fall, vy = windLow.y * 0.4 - up.y * fall, vz = windLow.z * 0.4 - up.z * fall,
                life = HAIL_LIFE, startSize = size, endSize = size,
                r = grey, g = grey + 0.01f, b = min(1f, grey + 0.04f), a = 0.95f, grip = 0.0, rise = 0.0, streak = true, flake = true, pellet = true,
            )
        }
    }

    /**
     * Ash drifting down past the camera: fine grey flecks going with the wind and settling slowly,
     * kept off the very middle of the view ([forward] the way it looks).
     */
    private fun ash(dt: Double, ash: Double, camera: Vec3, forward: Vec3) {
        up.setTo(camera).normalizeInPlace()
        val rate = kotlin.math.min(ASH_RATE * rateScale, capacity * ASH_SHARE / ASH_LIFE)
        val n = poisson(rate * ((ash - ASH_FROM) / (ASH_FULL - ASH_FROM)).coerceIn(0.0, 1.0) * dt, 999)
        for (k in 0 until n) {
            val ox = jitter(k, 1); val oy = jitter(k, 2); val oz = jitter(k, 3)
            scratch.setTo(ox, oy, oz)
            if (scratch.lengthSq < 1e-6) continue
            // Only in front of the camera, where they're seen.
            val behind = scratch dot forward
            if (behind < 0.0) scratch.addScaledInPlace(forward, -2.0 * behind)
            scratch.normalizeInPlace().mulInPlace(ASH_NEAR + (ASH_FAR - ASH_NEAR) * rand(k)).addScaledInPlace(windLow, -0.5 * ASH_LIFE)
            var clear = true
            for (step in 0..4) {
                sandEnd.setTo(scratch).addScaledInPlace(windLow, ASH_LIFE * step / 4.0).addScaledInPlace(up, -ASH_FALL * ASH_LIFE * step / 4.0)
                if (!offCentre(sandEnd, forward, ASH_CENTRE)) { clear = false; break }
            }
            if (!clear) continue
            // Pale, so they show against the dark haze.
            val size = 0.035 + 0.035 * rand(k + 11)
            val grey = 0.85f + 0.15f * rand(k + 13)
            spawn(
                x = camera.x + scratch.x, y = camera.y + scratch.y, z = camera.z + scratch.z,
                vx = windLow.x + up.x * (0.4 * jitter(k, 5) - ASH_FALL), vy = windLow.y + up.y * (0.4 * jitter(k, 6) - ASH_FALL),
                vz = windLow.z + up.z * (0.4 * jitter(k, 7) - ASH_FALL),
                life = ASH_LIFE, startSize = size, endSize = size,
                r = grey, g = grey * 0.97f, b = grey * 0.93f, a = 1f, grip = 0.0, rise = 0.0, mote = true,
            )
        }
    }

    /** Whether [offset] from the camera is outside the middle of the view looking along unit [forward]. */
    private fun offCentre(offset: Vec3, forward: Vec3, centre: Double = SAND_CENTRE): Boolean {
        val length = offset.length
        return length < 1e-6 || (offset dot forward) / length < centre
    }

    // --- lightning ------------------------------------------------------------

    private fun lightning(time: Double, weather: Weather, camera: Vec3, body: CelestialBody) {
        val from = if (lastStrikeCheck.isNaN() || time - lastStrikeCheck > 2.0) time - 0.05 else lastStrikeCheck
        lastStrikeCheck = time
        strikes.clear()
        weather.strikes(scratch.setTo(camera).normalizeInPlace(), from, time, strikes)
        for (s in strikes) {
            if (s.id in struckIds) continue
            val groundHeight = body.terrain?.let { max(it.elevation(s.direction), 0.0) } ?: 0.0
            val ground = Vec3().setTo(s.direction).mulInPlace(body.radius + groundHeight)
            val distance = ground.distanceTo(camera)
            if (distance > LIGHTNING_VISIBLE) continue
            val light = (s.energy * exp(-distance / 12_000.0)).toFloat()
            bolts.add(Bolt(jagged(ground, s.direction, s.id), time, 1.5 + 2.5 * s.energy, s.id, light))
            thunder.add(doubleArrayOf(distance, s.energy))
            flash = max(flash, light)
        }
        bolts.removeAll { time - it.born > BOLT_SECONDS }
    }

    /**
     * Strike [id] hitting a craft at body-fixed [at], [distance] metres from the camera, at [time]:
     * its bolt drawn down to the craft (the ground strike's, if already drawn, moved there), the
     * sky lit, and its thunder if it wasn't heard already. [own] for the craft being flown, whose
     * flash is full.
     */
    fun struck(at: Vec3, id: Long, distance: Double, time: Double, own: Boolean) {
        if (id in struckIds) return
        struckIds.addLast(id)
        while (struckIds.size > 16) struckIds.removeFirst()
        val up = Vec3().setTo(at).normalizeInPlace()
        val points = jagged(at, up, id, close = true)
        val light = if (own) 1f else exp(-distance / 12_000.0).toFloat()
        val drawn = bolts.firstOrNull { it.id == id }
        if (drawn != null) { drawn.shape = points; drawn.width = STRUCK_WIDTH; drawn.light = max(drawn.light, light) }
        else {
            if (distance > LIGHTNING_VISIBLE) return
            bolts.add(Bolt(points, time, STRUCK_WIDTH, id, light))
            thunder.add(doubleArrayOf(distance, 1.0))
        }
        flash = max(flash, light)
    }

    /**
     * A bolt from the cloud base down to [ground], all from [id] so every screen draws the same
     * one: [BOLT_MAIN] short kinks on a slow wander, 3-5 branches leaving the channel's upper
     * part downward and out and one or two more from its lower part, 8 kinks each. [close] packs the
     * kinks toward the ground, for a bolt seen from near where it lands.
     */
    private fun jagged(ground: Vec3, up: Vec3, id: Long, close: Boolean = false): BoltShape {
        val east = Vec3(up.z, 0.0, -up.x).let { if (it.lengthSq < 1e-12) Vec3(1.0, 0.0, 0.0) else it.normalizeInPlace() }
        val north = Vec3().setTo(up).crossInPlace(east)
        val seed = (id xor (id ushr 32)).toInt()
        var drift = 0.0; var driftN = 0.0
        var last = 0.0
        val main = ArrayList<Vec3>(BOLT_MAIN + 1)
        for (k in 0..BOLT_MAIN) {
            val u = k.toDouble() / BOLT_MAIN
            val h = CLOUD_BASE * (if (close) u * u * kotlin.math.sqrt(u) else u)
            val step = h - last
            last = h
            // A slow wander, plus a kink of its own at every point so it zigzags all the way.
            drift += (Noise.hash(seed, k, 1, 0) - 0.5) * 0.6 * step
            driftN += (Noise.hash(seed, k, 2, 0) - 0.5) * 0.6 * step
            val kink = if (k == 0 || k == BOLT_MAIN) 0.0 else 1.4 * step
            main.add(Vec3().setTo(ground).addScaledInPlace(up, h)
                .addScaledInPlace(east, if (k == 0) 0.0 else drift + (Noise.hash(seed, k, 10, 0) - 0.5) * kink)
                .addScaledInPlace(north, if (k == 0) 0.0 else driftN + (Noise.hash(seed, k, 10, 1) - 0.5) * kink))
        }
        val branches = ArrayList<List<Vec3>>(7)
        val count = 3 + (Noise.hash(seed, 0, 20, 0) * 3).toInt()
        val low = if (close) 2 else 1
        for (j in 0 until count + low) {
            // The last leave low and reach further: 70-240 m up, or 20-60 m packed close.
            val share = if (j < count) 0.2 + 0.65 * Noise.hash(seed, j, 21, 0)
                else (if (close) 0.18 else 0.06) + (if (close) 0.12 else 0.14) * Noise.hash(seed, j, 21, 0)
            val from = main[(main.size * share).toInt().coerceIn(1, main.size - 2)]
            val height = (from.distanceTo(ground)).coerceAtLeast(1.0)
            val step = height * ((if (j < count) 0.25 else 0.45) + 0.3 * Noise.hash(seed, j, 21, 1)) / BOLT_BRANCH
            val angle = Noise.hash(seed, j, 21, 2) * 2 * Math.PI
            val out = Vec3().setTo(east).mulInPlace(kotlin.math.cos(angle)).addScaledInPlace(north, kotlin.math.sin(angle))
            val branch = ArrayList<Vec3>(BOLT_BRANCH + 1)
            branch.add(from)
            val at = Vec3().setTo(from)
            for (k in 1..BOLT_BRANCH) {
                val side = (Noise.hash(seed, j * 16 + k, 22, 0) - 0.5) * 1.2
                val sideN = (Noise.hash(seed, j * 16 + k, 22, 1) - 0.5) * 1.2
                at.addScaledInPlace(out, step * 0.8).addScaledInPlace(up, -step * 0.6)
                    .addScaledInPlace(east, side * step).addScaledInPlace(north, sideN * step)
                branch.add(Vec3().setTo(at))
            }
            branches.add(branch)
        }
        return BoltShape(main, branches)
    }

    // --- motion ---------------------------------------------------------------

    private fun advance(dt: Double, body: CelestialBody) {
        val dtf = dt.toFloat()
        var i = 0
        while (i < count) {
            if (fresh[i]) { fresh[i] = false; i++; continue }
            age[i] += dtf
            if (age[i] >= life[i]) { kill(i); continue }
            // The wind at its height, between the two samples.
            val height = sqrt(px[i] * px[i] + py[i] * py[i] + pz[i] * pz[i]) - body.radius - focusGround
            val t = (height / 800.0).coerceIn(0.0, 1.0)
            val wx = windLow.x + (windHigh.x - windLow.x) * t
            val wy = windLow.y + (windHigh.y - windLow.y) * t
            val wz = windLow.z + (windHigh.z - windLow.z) * t
            val k = 1.0 - exp(-grip[i] * dt)
            vx[i] += (wx - vx[i]) * k; vy[i] += (wy - vy[i]) * k; vz[i] += (wz - vz[i]) * k
            if (rise[i] != 0f) {
                val cool = exp(-age[i] / 3.0)
                val r = sqrt(px[i] * px[i] + py[i] * py[i] + pz[i] * pz[i])
                val lift = rise[i] * cool * dt / r
                vx[i] += px[i] * lift; vy[i] += py[i] * lift; vz[i] += pz[i] * lift
            }
            if (pellets[i]) {
                val r = sqrt(px[i] * px[i] + py[i] * py[i] + pz[i] * pz[i])
                val ux = px[i] / r; val uy = py[i] / r; val uz = pz[i] / r
                val down = -(vx[i] * ux + vy[i] * uy + vz[i] * uz)
                if (height > 0.05 || down < 0.0) {
                    // Gravity, held to its falling speed by the air.
                    if (down < HAIL_FALL) { val g = gravity * dt; vx[i] -= ux * g; vy[i] -= uy * g; vz[i] -= uz * g }
                } else if (down > HAIL_REST) {
                    // Off the ground, a third as fast, slowed along it.
                    val back = down * (1.0 + HAIL_BOUNCE)
                    vx[i] += ux * back; vy[i] += uy * back; vz[i] += uz * back
                    vx[i] *= 0.6; vy[i] *= 0.6; vz[i] *= 0.6
                } else {
                    kill(i); continue
                }
            } else if (falls[i]) {
                val r = sqrt(px[i] * px[i] + py[i] * py[i] + pz[i] * pz[i])
                val ux = px[i] / r; val uy = py[i] / r; val uz = pz[i] / r
                if (height > 0.15) {
                    val g = gravity * dt
                    vx[i] -= ux * g; vy[i] -= uy * g; vz[i] -= uz * g
                } else if (streak[i] && glows[i]) {
                    // A spark goes out as it lands.
                    kill(i); continue
                } else {
                    // Landed, so it stops going down and skids to a halt.
                    val down = vx[i] * ux + vy[i] * uy + vz[i] * uz
                    if (down < 0.0) { vx[i] -= ux * down; vy[i] -= uy * down; vz[i] -= uz * down }
                    val stop = exp(-4.0 * dt)
                    vx[i] *= stop; vy[i] *= stop; vz[i] *= stop
                }
            }
            px[i] += vx[i] * dt; py[i] += vy[i] * dt; pz[i] += vz[i] * dt
            i++
        }
    }

    private fun kill(i: Int) {
        val last = count - 1
        if (i != last) {
            px[i] = px[last]; py[i] = py[last]; pz[i] = pz[last]
            vx[i] = vx[last]; vy[i] = vy[last]; vz[i] = vz[last]
            age[i] = age[last]; life[i] = life[last]
            size0[i] = size0[last]; size1[i] = size1[last]
            for (c in 0 until 4) colour[i * 4 + c] = colour[last * 4 + c]
            grip[i] = grip[last]; rise[i] = rise[last]; streak[i] = streak[last]
            glows[i] = glows[last]; falls[i] = falls[last]; flakes[i] = flakes[last]; pellets[i] = pellets[last]; motes[i] = motes[last]; fresh[i] = fresh[last]
        }
        count--
    }

    private fun spawn(
        x: Double, y: Double, z: Double, vx: Double, vy: Double, vz: Double,
        life: Double, startSize: Double, endSize: Double,
        r: Float, g: Float, b: Float, a: Float, grip: Double, rise: Double, streak: Boolean = false,
        glow: Boolean = false, fall: Boolean = false, flake: Boolean = false, pellet: Boolean = false, mote: Boolean = false,
    ) {
        // Full, so the oldest-looking one makes way rather than the new one not appearing. Fresh
        // smoke matters more than old.
        val i = if (count < capacity) count++ else (spawnCounter++ % capacity)
        px[i] = x; py[i] = y; pz[i] = z
        this.vx[i] = vx; this.vy[i] = vy; this.vz[i] = vz
        age[i] = 0f; this.life[i] = life.toFloat()
        size0[i] = startSize.toFloat(); size1[i] = endSize.toFloat()
        colour[i * 4] = r; colour[i * 4 + 1] = g; colour[i * 4 + 2] = b; colour[i * 4 + 3] = a
        this.grip[i] = grip.toFloat(); this.rise[i] = rise.toFloat(); this.streak[i] = streak
        glows[i] = glow; falls[i] = fall; flakes[i] = flake; pellets[i] = pellet; motes[i] = mote
        fresh[i] = true
    }

    // --- flames -----------------------------------------------------------------

    /**
     * Each lit engine's flame as glowing see-through cones into [out]: a long pale plume and a
     * short bright core, longer with throttle, blooming broad and faint as the air thins, and
     * flickering.
     */
    fun flames(emitters: List<EngineEmitter>, body: CelestialBody, time: Double, out: MutableList<RenderItem>) {
        for (e in emitters) {
            if (e.throttle <= 0.01) continue
            val altitude = e.nozzle.length - body.radius
            // Air thicker than Terra's sea level, as on Caligo, gives the same flame.
            val pressure = body.atmosphere?.pressureRatioAt(altitude)?.coerceIn(0.0, 1.0) ?: 0.0
            val flicker = 0.9 + 0.1 * Noise.simplex(e.seed, time * 13.0, 0.0, 0.0)
            val orient = quatFromTo(Vec3(0.0, -1.0, 0.0), e.out)
            when (e.kind) {
                Exhaust.ROCKET -> {
                    // The plume only really opens near space. Cubed, so it's still a flame at 7 km
                    // (a third of the pressure left) and only a broad bloom in the last few percent
                    // of air.
                    val vacuum = (1.0 - pressure).let { it * it * it }
                    // It leaves the bell at the bell's own width and only spreads downstream, so it
                    // never hides the engine.
                    val width = e.exitRadius
                    val length = e.radius * (22.0 + 14.0 * vacuum) * (0.35 + 0.65 * e.throttle) * flicker
                    out.add(RenderItem(rocketPlume(vacuum), e.nozzle.copy(), orient, floatArrayOf(1.0f, 0.55f, 0.2f, (0.65 - 0.35 * vacuum).toFloat()), scale = Vec3(width, length, width), ambient = EMISSIVE, key = RenderItem.effectKey(e.seed.toLong(), 0)))
                    out.add(RenderItem(ROCKET_CORE, e.nozzle.copy(), orient, floatArrayOf(1.0f, 0.95f, 0.8f, (0.92 - 0.35 * vacuum).toFloat()), scale = Vec3(e.radius * 0.85, length * 0.4, e.radius * 0.85), ambient = EMISSIVE, key = RenderItem.effectKey(e.seed.toLong(), 1)))
                }
                Exhaust.JET -> {
                    val length = e.radius * 3.0 * e.throttle * flicker
                    out.add(RenderItem(JET_PLUME, e.nozzle.copy(), orient, floatArrayOf(0.55f, 0.7f, 1.0f, 0.35f), scale = Vec3(e.radius * 0.8, length, e.radius * 0.8), ambient = EMISSIVE, key = RenderItem.effectKey(e.seed.toLong(), 2)))
                }
                else -> Unit
            }
        }
    }

    /**
     * Dust thrown up behind a wheel rolling at [speed] over dry ground at [contact] (attractor
     * frame). Nothing on grass, snow, rock or water: the ground has to be loose and dry.
     */
    fun wheelDust(contact: Vec3, speed: Double, body: CelestialBody, bodyRotation: Quat, dt: Double, seed: Int) {
        if (speed < 3.0 || body.atmosphere == null) return
        bodyRotation.inverseRotate(contact, bodyFixed)
        up.setTo(bodyFixed).normalizeInPlace()
        val terrain = body.terrain ?: return
        val elevation = terrain.elevation(up)
        if (terrain.hasOcean && elevation < 0.0) return
        val material = terrain.material(up, elevation, 0.0)
        val tone = when (material) {
            com.rm.apogee.core.terrain.SurfaceMaterial.SAND -> floatArrayOf(0.86f, 0.78f, 0.58f)
            com.rm.apogee.core.terrain.SurfaceMaterial.DIRT, com.rm.apogee.core.terrain.SurfaceMaterial.CLAY -> floatArrayOf(0.62f, 0.52f, 0.40f)
            com.rm.apogee.core.terrain.SurfaceMaterial.SCREE -> floatArrayOf(0.6f, 0.58f, 0.55f)
            com.rm.apogee.core.terrain.SurfaceMaterial.REGOLITH -> floatArrayOf(0.62f, 0.62f, 0.6f)
            com.rm.apogee.core.terrain.SurfaceMaterial.DARK_SAND -> floatArrayOf(0.32f, 0.30f, 0.28f)
            com.rm.apogee.core.terrain.SurfaceMaterial.EJECTA -> floatArrayOf(0.74f, 0.72f, 0.68f)
            com.rm.apogee.core.terrain.SurfaceMaterial.SALT -> floatArrayOf(0.86f, 0.84f, 0.78f)
            else -> return
        }
        val n = poisson(speed * 1.2 * rateScale * dt, seed)
        for (k in 0 until n) {
            val shade = 0.9f + 0.2f * rand(k)
            spawn(
                x = bodyFixed.x + jitter(k, 1) * 0.3, y = bodyFixed.y + jitter(k, 2) * 0.3, z = bodyFixed.z + jitter(k, 3) * 0.3,
                vx = up.x * 1.5 + jitter(k, 4), vy = up.y * 1.5 + jitter(k, 5), vz = up.z * 1.5 + jitter(k, 6),
                life = 2.5 + 2.5 * rand(k + 1), startSize = 0.5, endSize = 3.0 + 0.1 * speed,
                r = tone[0] * shade, g = tone[1] * shade, b = tone[2] * shade, a = 0.55f, grip = 1.5, rise = 0.2,
            )
        }
    }

    // --- the air made visible ------------------------------------------------------

    /**
     * A craft's passage through the air, drawn: a condensation cone near Mach 1 in damp low air,
     * and at re-entry speeds a glowing shock ahead with a trail of hot sparks. Sized from the same
     * airspeed and density the drag uses.
     *
     * [centre] is the craft's middle and [airVelocity] its motion through the air, both in the
     * attractor's frame. [size] is its radius.
     */
    fun aero(
        centre: Vec3,
        airVelocity: Vec3,
        size: Double,
        body: CelestialBody,
        bodyRotation: Quat,
        dt: Double,
        time: Double,
        seed: Int,
        out: MutableList<RenderItem>,
        /** The craft's leading point, where the air meets it first. */
        nose: Vec3 = centre,
        /** How thick the craft is there, in metres: its widest body radius. */
        girth: Double = size * 0.3,
        /** Unit, from the nose back along the body: what the collar hugs. Null for along the flow. */
        back: Vec3? = null,
        /** The craft's parts, for vapour and glow that follow its shape. Empty for one shock on the nose. */
        parts: List<AeroPart> = emptyList(),
    ) {
        val atmosphere = body.atmosphere ?: return
        val altitude = centre.length - body.radius
        val density = atmosphere.densityAt(altitude)
        if (density <= 0.0) return
        val speed = airVelocity.length
        if (speed < AIR_SHOWS_FROM) return
        val flow = airVelocity.copy().mulInPlace(-1.0 / speed)

        // Vapour: a condensation shock cone, strongest right at Mach 1, and only where there's
        // water in the air.
        val mach = speed / SPEED_OF_SOUND
        val damp = smoothstep(0.05, 0.35, density)
        val vapour = damp * (1.0 - smoothstep(0.0, 0.2, kotlin.math.abs(mach - 1.0))) * (0.85 + 0.15 * Noise.simplex(seed, time * 9.0, 0.0, 0.0))
        if (vapour > 0.02) {
            // A collar just behind the nose, flaring back past the shoulders where the air speeds
            // round and drops below its dew point. Sized to the body there, not the whole craft.
            val length = girth * 3.0
            val along = back ?: flow
            val collar = Vec3().setTo(nose).addScaledInPlace(along, 0.55 * length + girth * 0.6)
            out.add(
                RenderItem(
                    VAPOUR_CONE, collar, quatFromTo(Vec3(0.0, -1.0, 0.0), along),
                    floatArrayOf(0.95f, 0.97f, 1.0f, (0.45 * vapour).toFloat()),
                    caps = 0, scale = Vec3(girth * 2.2, length, girth * 2.2), ambient = 0.9f,
                    key = RenderItem.effectKey(-1L - seed, 3),
                ),
            )
            wingVapour(parts, flow, vapour, seed, out)
        }

        // Re-entry glow: air heated to plasma. It's decided by the recovery temperature, v²/2cp, as
        // in the heat model: an orbital return passes 2,500 K, and a fast dive at about Mach 3.5
        // starts it, pale and thin with few sparks. The air must be thick enough to glow, but past
        // that thickness hardly matters.
        val recovery = speed * speed / (2.0 * 1_005.0)
        val glow = smoothstep(GLOW_FROM, GLOW_FULL, recovery) * smoothstep(1e-6, 1e-4, density)
        if (glow > 0.02) {
            val flicker = 0.9 + 0.1 * Noise.simplex(seed + 3, time * 17.0, 0.0, 0.0)
            if (parts.isNotEmpty()) sheaths(parts, flow, glow, flicker, seed, out)
            // Standing just off the nose, wrapping back round it.
            val ahead = nose.copy().addScaledInPlace(flow, girth * 0.4)
            if (parts.isEmpty()) out.add(
                RenderItem(
                    BOW_SHOCK, ahead, quatFromTo(Vec3(0.0, 1.0, 0.0), flow.copy().mulInPlace(-1.0)),
                    floatArrayOf(1.0f, (0.45 + 0.35 * (1 - glow)).toFloat(), (0.2 * (1 - glow)).toFloat(), (0.75 * glow * flicker).toFloat()),
                    caps = 0, scale = Vec3(girth * 2.0, girth * 1.8, girth * 2.0), ambient = EMISSIVE,
                    key = RenderItem.effectKey(-1L - seed, 4),
                ),
            )
            // Sparks streaming off behind it.
            bodyRotation.inverseRotate(centre, bodyFixed)
            bodyRotation.inverseRotate(flow, scratch)
            val n = poisson(90.0 * rateScale * glow * dt, seed + 5)
            for (k in 0 until n) {
                spawn(
                    x = bodyFixed.x + jitter(k, 1) * size, y = bodyFixed.y + jitter(k, 2) * size, z = bodyFixed.z + jitter(k, 3) * size,
                    vx = scratch.x * speed * 0.15, vy = scratch.y * speed * 0.15, vz = scratch.z * speed * 0.15,
                    life = 0.6 + 0.6 * rand(k), startSize = size * 0.35, endSize = size * 0.1,
                    r = 1.0f, g = 0.55f + 0.3f * rand(k + 1), b = 0.25f, a = 0.9f, grip = 0.0, rise = 0.0,
                )
            }
        }
    }

    /**
     * A sheet of vapour over each wing and fin near Mach 1, where the air speeding over its top
     * drops below its dew point. It sits on the side the air leaves from and flares back past the
     * trailing edge.
     */
    private fun wingVapour(parts: List<AeroPart>, flow: Vec3, vapour: Double, seed: Int, out: MutableList<RenderItem>) {
        var k = 0
        for (part in parts) {
            if (!part.wing || k >= MAX_SHEATHS) continue
            // The top: the face the air moves away from.
            val top = part.z.copy().let { if ((it dot flow) < 0.0) it.negateInPlace() else it }
            val wide = part.longestAcross(flow)
            val span = part.extent(wide)
            val chord = part.extent(flow).coerceAtLeast(part.halves.y * 0.5)
            if (span < MIN_SHEATH) continue
            val at = part.centre.copy().addScaledInPlace(top, part.halves.z + chord * 0.15).addScaledInPlace(flow, chord * 0.3)
            out.add(
                RenderItem(
                    VAPOUR_CONE, at, quatLookAt(wide.cross(flow), flow.copy().mulInPlace(-1.0)),
                    floatArrayOf(0.95f, 0.97f, 1.0f, (0.4 * vapour).toFloat()),
                    caps = 0, scale = Vec3(span * 1.05, chord * 2.2, maxOf(chord * 0.3, MIN_SHEATH)), ambient = 0.9f,
                    key = RenderItem.effectKey(-1L - seed, 80 + k),
                ),
            )
            k++
        }
    }

    /**
     * The glow wrapping a craft's own shape: a see-through sheath on each part, as wide and as deep
     * as the part meets the air, so a wing edge-on glows along its leading edge and face-on across
     * its whole underside. Each stands just off the part's face and streams back along the flow,
     * longer and hotter as it heats. A part tucked in behind another glows less, the way its shield
     * covers it. [flow] is the way the air moves past the craft, unit.
     */
    private fun sheaths(parts: List<AeroPart>, flow: Vec3, glow: Double, flicker: Double, seed: Int, out: MutableList<RenderItem>) {
        val faces = ArrayList<Face>(parts.size)
        var tail = -Double.MAX_VALUE
        for (part in parts) {
            val depth = part.extent(flow)
            // Wide across its longest side as the air sees it, and narrow across the other.
            val wide = part.longestAcross(flow)
            val narrow = flow.cross(wide)
            val face = Vec3().setTo(part.centre).addScaledInPlace(flow, -depth)
            faces += Face(face, wide, part.extent(wide).coerceAtLeast(MIN_SHEATH), part.extent(narrow).coerceAtLeast(MIN_SHEATH))
            // Where the craft ends downstream, so a stream runs past the whole of it.
            tail = maxOf(tail, (part.centre dot flow) + depth)
        }
        // Upstream first, biggest first.
        val order = faces.sortedBy { (it.at dot flow) - it.wide * 0.01 }.take(MAX_SHEATHS)
        val lateral = Vec3()
        order.forEachIndexed { k, face ->
            // In the lee of a part further upstream that's big enough to cover it.
            var shade = 1.0
            for (j in 0 until k) {
                val other = order[j]
                lateral.setTo(face.at).subInPlace(other.at)
                val down = lateral dot flow
                if (down <= 0.0 || face.wide > other.wide * 1.1) continue
                lateral.addScaledInPlace(flow, -down)
                val across = (lateral dot other.across) / other.wide
                val thin = (lateral dot flow.cross(other.across)) / other.narrow
                if (across * across + thin * thin < 0.81) { shade = SHELTERED; break }
            }
            val heat = glow * shade
            if (heat < 0.03) return@forEachIndexed
            // Lathe +Y upstream and +X along the part's wide side.
            val turn = quatLookAt(face.across.cross(flow), flow.copy().mulInPlace(-1.0))
            val size = maxOf(face.wide, face.narrow)
            val at = face.at.copy().addScaledInPlace(flow, -face.narrow * 0.15)
            // The hot cap, short and bright, then the long orange stream behind.
            out.add(
                RenderItem(
                    SHEATH, at, turn,
                    floatArrayOf(1.0f, (0.75 + 0.2 * (1 - heat)).toFloat(), (0.45 * (1 - heat)).toFloat(), (0.5 * heat * flicker).toFloat()),
                    caps = 0, scale = Vec3(face.wide * 1.15, size * (0.6 + 0.6 * heat), face.narrow * 1.15), ambient = EMISSIVE,
                    key = RenderItem.effectKey(-1L - seed, 20 + 2 * k),
                ),
            )
            // Past the end of the craft behind it, and on for a few widths as it heats.
            val behind = (tail - (face.at dot flow)).coerceAtLeast(0.0)
            out.add(
                RenderItem(
                    SHEATH, at, turn,
                    floatArrayOf(1.0f, (0.4 + 0.25 * (1 - heat)).toFloat(), (0.12 * (1 - heat)).toFloat(), (0.3 * heat * flicker).toFloat()),
                    caps = 0, scale = Vec3(face.wide * 1.35, behind + size * (1.0 + 4.0 * heat), face.narrow * 1.35), ambient = EMISSIVE,
                    key = RenderItem.effectKey(-1L - seed, 21 + 2 * k),
                ),
            )
        }
    }

    /** A part's face to the air: where it is, its wide way across (unit) and its half sizes both ways. */
    private class Face(val at: Vec3, val across: Vec3, val wide: Double, val narrow: Double)

    // --- crashes -------------------------------------------------------------------

    /**
     * What a blow, breakage or blast looks like at body-fixed [at]. [amount] is impact speed for an
     * impact and propellant for an explosion. [colour] is the part's own, for its fragments.
     */
    fun partEvent(kind: PartEventKind, at: Vec3, amount: Double, colour: FloatArray?, seed: Int, water: Boolean = false) {
        up.setTo(at).normalizeInPlace()
        when (kind) {
            // Docking: a little breath of gas as the latches go, and more as they let go.
            PartEventKind.DOCKED, PartEventKind.HITCHED, PartEventKind.UNHITCHED,
            PartEventKind.HOOKED, PartEventKind.UNHOOKED, PartEventKind.SNAPPED -> Unit
            PartEventKind.UNDOCKED -> repeat(10) { k ->
                spawn(
                    x = at.x + jitter(k, 1) * 0.3, y = at.y + jitter(k, 2) * 0.3, z = at.z + jitter(k, 3) * 0.3,
                    vx = jitter(k, 4) * 3.0, vy = jitter(k, 5) * 3.0, vz = jitter(k, 6) * 3.0,
                    life = 0.5 + 0.3 * rand(k), startSize = 0.15, endSize = 1.2,
                    r = 0.93f, g = 0.95f, b = 1f, a = 0.45f, grip = 0.0, rise = 0.0,
                )
            }
            PartEventKind.IMPACT -> if (water) splash(at, amount) else {
                val strength = (amount / 30.0).coerceIn(0.1, 2.0)
                sparks(at, (6 + 14 * strength).toInt(), 6.0 + 10.0 * strength, 0.12)
                // A burst of whatever the ground is made of.
                repeat((3 + 8 * strength).toInt()) { k ->
                    val s = 1.0 + 3.0 * strength
                    spawn(
                        x = at.x + jitter(k, 1), y = at.y + jitter(k, 2), z = at.z + jitter(k, 3),
                        vx = jitter(k, 4) * s + up.x * s, vy = jitter(k, 5) * s + up.y * s, vz = jitter(k, 6) * s + up.z * s,
                        life = 2.0 + 2.0 * rand(k), startSize = 0.8 + strength, endSize = 2.5 + 2.0 * strength,
                        r = 0.52f, g = 0.47f, b = 0.40f, a = 0.55f, grip = 1.2, rise = 0.3,
                    )
                }
            }
            PartEventKind.DESTROYED -> {
                val c = colour ?: floatArrayOf(0.6f, 0.6f, 0.62f, 1f)
                // Pieces of it, in its own colour, some of them scorched.
                repeat(14) { k ->
                    val burnt = rand(k) < 0.35f
                    val tone = if (burnt) 0.25f else 0.8f + 0.2f * rand(k + 1)
                    val s = 6.0 + 14.0 * rand(k + 2)
                    spawn(
                        x = at.x, y = at.y, z = at.z,
                        vx = jitter(k, 1) * s + up.x * s * 0.6, vy = jitter(k, 2) * s + up.y * s * 0.6, vz = jitter(k, 3) * s + up.z * s * 0.6,
                        life = 6.0 + 4.0 * rand(k + 3), startSize = 0.25 + 0.35 * rand(k + 4), endSize = 0.2,
                        r = c[0] * tone, g = c[1] * tone, b = c[2] * tone, a = 1f, grip = 0.05, rise = 0.0, fall = true,
                    )
                }
                sparks(at, 12, 10.0, 0.1)
                smoke(at, 6, 1.5, 0.5)
            }
            PartEventKind.DETACHED -> sparks(at, 10, 7.0, 0.1)
            PartEventKind.EXPLOSION -> {
                val scale = sqrt(amount.coerceAtLeast(20.0) / 2_000.0).coerceIn(0.15, 3.0)
                // The fireball: bright and fast, slowing hard and rising.
                repeat((50 * scale).toInt().coerceIn(12, 150)) { k ->
                    val s = (6.0 + 22.0 * rand(k)) * scale
                    val hot = rand(k + 1)
                    spawn(
                        x = at.x, y = at.y, z = at.z,
                        vx = jitter(k, 1) * s, vy = jitter(k, 2) * s, vz = jitter(k, 3) * s,
                        life = 0.9 + 1.2 * rand(k + 2), startSize = 2.0 * scale + 1.0, endSize = 7.0 * scale + 2.0,
                        r = 1f, g = 0.55f + 0.4f * hot, b = 0.15f + 0.45f * hot * hot, a = 0.9f, grip = 2.5, rise = 3.0, glow = true,
                    )
                }
                // Then the smoke it leaves, dark and climbing.
                smoke(at, (30 * scale).toInt().coerceIn(8, 80), 5.0 * scale, 2.5 * scale)
                // And burning debris thrown wide.
                sparks(at, (30 * scale).toInt().coerceIn(10, 80), 25.0 * scale, 0.35)
                flash = maxOf(flash, (0.35 * scale).toFloat().coerceAtMost(0.8f))
            }
        }
    }

    /**
     * A part on fire: flames licking up and smoke drifting downwind from body-fixed [at], for a
     * part [size] across.
     */
    fun burn(at: Vec3, size: Double, dt: Double, seed: Int) {
        up.setTo(at).normalizeInPlace()
        val flames = poisson(14.0 * rateScale * dt, seed)
        for (k in 0 until flames) {
            spawn(
                x = at.x + jitter(k, 1) * size * 0.4, y = at.y + jitter(k, 2) * size * 0.4, z = at.z + jitter(k, 3) * size * 0.4,
                vx = up.x * 2.0, vy = up.y * 2.0, vz = up.z * 2.0,
                life = 0.5 + 0.5 * rand(k), startSize = size * 0.5, endSize = size * 0.15,
                r = 1f, g = 0.45f + 0.35f * rand(k + 1), b = 0.1f, a = 0.85f, grip = 1.0, rise = 2.0, glow = true,
            )
        }
        val puffs = poisson(5.0 * rateScale * dt, seed + 1)
        for (k in 0 until puffs) {
            spawn(
                x = at.x, y = at.y, z = at.z,
                vx = up.x * 1.5, vy = up.y * 1.5, vz = up.z * 1.5,
                life = 6.0 + 4.0 * rand(k), startSize = size * 0.6, endSize = size * 3.0,
                r = 0.16f, g = 0.15f, b = 0.14f, a = 0.5f, grip = 0.8, rise = 1.2,
            )
        }
    }

    /**
     * A thruster block firing at [strength] (0..1): quick pale puffs from body-fixed [at] along
     * [out] (unit, the gas's direction), carried at the craft's [velocity] over the ground. In
     * vacuum they shoot straight out and vanish; in air they slow and hang a moment.
     */
    fun rcsPuff(at: Vec3, out: Vec3, velocity: Vec3, strength: Double, inAir: Boolean, dt: Double, seed: Int) {
        if (strength < 0.02) return
        val n = poisson(RCS_PUFFS * strength * rateScale * dt, seed)
        for (k in 0 until n) {
            // A narrow cone around the nozzle's axis.
            scratch.setTo(out).addInPlace(Vec3(jitter(k, 1), jitter(k, 2), jitter(k, 3)).mulInPlace(0.18)).normalizeInPlace()
            val speed = if (inAir) 10.0 + 8.0 * rand(k) else 24.0 + 16.0 * rand(k)
            spawn(
                x = at.x + out.x * 0.2, y = at.y + out.y * 0.2, z = at.z + out.z * 0.2,
                vx = velocity.x + scratch.x * speed, vy = velocity.y + scratch.y * speed, vz = velocity.z + scratch.z * speed,
                life = (if (inAir) 0.45 else 0.3) + 0.2 * rand(k + 5),
                startSize = 0.12, endSize = if (inAir) 1.3 else 2.2,
                r = 0.93f, g = 0.95f, b = 1f, a = (0.35 + 0.3 * strength).toFloat(),
                grip = if (inAir) 4.0 else 0.0, rise = 0.0,
            )
        }
    }

    /**
     * A seam working near its limit: sparks, and now and then a fleck of metal, at [rate] a second.
     * [at] is the seam (body-fixed), [radius] its reach, and [velocity] the craft's (body-fixed),
     * so what comes off leaves with it. [colour] is the part's own, for the flecks.
     */
    fun strain(
        at: Vec3, velocity: Vec3, radius: Double, rate: Double, inAir: Boolean,
        colour: FloatArray?, dt: Double, seed: Int,
    ) {
        up.setTo(at).normalizeInPlace()
        // Carried along with the craft for their short life. Dragged to a stop by the air, at 1
        // km/s they fell far behind at once and seemed to come from nowhere near the seam.
        val grip = 0.0
        // In bursts, as metal grinding on metal gives them: a handful now and then, not a trickle.
        val n = poisson(rate / BURST * rateScale * dt, seed) * BURST
        for (k in 0 until n) {
            // Around the seam, flung outward.
            scratch.setTo(jitter(k, 1), jitter(k, 2), jitter(k, 3))
            if (scratch.lengthSq < 1e-6) scratch.setTo(up)
            scratch.normalizeInPlace()
            val s = 3.0 + 6.0 * rand(k)
            spawn(
                x = at.x + scratch.x * radius, y = at.y + scratch.y * radius, z = at.z + scratch.z * radius,
                vx = velocity.x + scratch.x * s, vy = velocity.y + scratch.y * s, vz = velocity.z + scratch.z * s,
                life = 0.25 + 0.45 * rand(k + 1), startSize = 0.14 + 0.08 * rand(k + 2), endSize = 0.06,
                r = 1f, g = 0.62f + 0.3f * rand(k + 3), b = 0.18f + 0.25f * rand(k + 4), a = 1f,
                grip = grip, rise = 0.0, streak = true, glow = true, fall = inAir,
            )
        }
        val flecks = poisson(rate * 0.08 * rateScale * dt, seed + 1)
        val c = colour ?: floatArrayOf(0.62f, 0.62f, 0.64f, 1f)
        for (k in 0 until flecks) {
            scratch.setTo(jitter(k, 1), jitter(k, 2), jitter(k, 3))
            if (scratch.lengthSq < 1e-6) scratch.setTo(up)
            scratch.normalizeInPlace()
            val s = 1.5 + 3.0 * rand(k)
            spawn(
                x = at.x + scratch.x * radius, y = at.y + scratch.y * radius, z = at.z + scratch.z * radius,
                vx = velocity.x + scratch.x * s, vy = velocity.y + scratch.y * s, vz = velocity.z + scratch.z * s,
                life = 0.6 + 0.6 * rand(k + 1), startSize = 0.12 + 0.1 * rand(k + 2), endSize = 0.1,
                r = c[0] * 0.85f, g = c[1] * 0.85f, b = c[2] * 0.85f, a = 1f,
                grip = grip * 0.3, rise = 0.0, fall = inAir,
            )
        }
    }

    /** Water thrown up where something went into it at [speed]: a white column and falling spray. */
    private fun splash(at: Vec3, speed: Double) {
        val strength = (speed / 30.0).coerceIn(0.2, 4.0)
        repeat((10 + 20 * strength).toInt().coerceAtMost(120)) { k ->
            val s = (3.0 + 9.0 * rand(k)) * strength
            spawn(
                x = at.x, y = at.y, z = at.z,
                vx = jitter(k, 1) * s * 0.4 + up.x * s, vy = jitter(k, 2) * s * 0.4 + up.y * s, vz = jitter(k, 3) * s * 0.4 + up.z * s,
                life = 1.5 + 1.5 * rand(k + 1), startSize = 0.6 * strength, endSize = 1.8 * strength,
                r = 0.93f, g = 0.96f, b = 1f, a = 0.8f, grip = 0.3, rise = 0.0, fall = true,
            )
        }
    }

    private fun sparks(at: Vec3, n: Int, speed: Double, size: Double) {
        repeat(n) { k ->
            val s = speed * (0.4 + 0.6 * rand(k))
            spawn(
                x = at.x, y = at.y, z = at.z,
                vx = jitter(k, 1) * s + up.x * s * 0.5, vy = jitter(k, 2) * s + up.y * s * 0.5, vz = jitter(k, 3) * s + up.z * s * 0.5,
                life = 0.4 + 0.8 * rand(k + 1), startSize = size, endSize = size * 0.5,
                r = 1f, g = 0.7f + 0.25f * rand(k + 2), b = 0.3f, a = 1f, grip = 0.1, rise = 0.0, glow = true, fall = true,
            )
        }
    }

    private fun smoke(at: Vec3, n: Int, size: Double, rising: Double) {
        repeat(n) { k ->
            val s = 2.0 + 4.0 * rand(k)
            spawn(
                x = at.x + jitter(k, 1) * size, y = at.y + jitter(k, 2) * size, z = at.z + jitter(k, 3) * size,
                vx = jitter(k, 4) * s + up.x * s, vy = jitter(k, 5) * s + up.y * s, vz = jitter(k, 6) * s + up.z * s,
                life = 6.0 + 5.0 * rand(k + 1), startSize = size, endSize = size * 3.5,
                r = 0.13f, g = 0.12f, b = 0.11f, a = 0.6f, grip = 0.7, rise = rising,
            )
        }
    }

    private fun smoothstep(a: Double, b: Double, x: Double): Double {
        val t = ((x - a) / (b - a)).coerceIn(0.0, 1.0)
        return t * t * (3 - 2 * t)
    }

    // --- drawing ------------------------------------------------------------------

    private val smokeLight = FloatArray(3)
    private val rainLight = FloatArray(3)

    /** Triple-buffered, so the renderer can read one while the next is filled. */
    private val buffers = Array(3) { FloatArray(0) }
    private var bufferIndex = 0

    /**
     * The particles and bolts as camera-facing faceted hexagons (streaks for rain, ribbons for
     * bolts), camera-relative, into a vertex array of [VERTEX_FLOATS] per vertex, six vertices per
     * shape. Returns the array and the shape count.
     */
    fun vertices(
        bodyRotation: Quat,
        cameraPos: Vec3,
        cameraRotation: Quat,
        lightScale: Float,
        time: Double,
        /** How much sun reaches the camera. At night smoke and rain are moonlit, not white. */
        daylight: Float = 1f,
        /** Lightning's light this frame. */
        flash: Float = 0f,
        /** What tornadoes near by throw this frame. */
        debris: TornadoDebris? = null,
    ): Pair<FloatArray, Int> {
        val thrown = debris?.count ?: 0
        val shapes = count + pelletCount() + thrown + bolts.sumOf { (it.shape.main.size - 1) * 2 + it.shape.branches.sumOf { b -> (b.size - 1) * 2 } + BOLT_FINER * 2 }
        val needed = shapes * 6 * VERTEX_FLOATS
        bufferIndex = (bufferIndex + 1) % buffers.size
        if (buffers[bufferIndex].size < needed) buffers[bufferIndex] = FloatArray(needed + 1024 * VERTEX_FLOATS)
        val v = buffers[bufferIndex]
        val right = cameraRotation.rotate(Vec3.unitX(), Vec3())
        val upAxis = cameraRotation.rotate(Vec3.unitY(), Vec3())
        val p = Vec3()
        var o = 0
        var written = 0
        // What lights a puff that doesn't glow: sun, or moon and twilight. By day rain gets the
        // sky's full light, not dimmed by its cloud, and smoke what reaches under it.
        NightLight.flatLight(daylight, 0.55f + 0.45f * lightScale, lightScale, smokeLight)
        NightLight.flatLight(daylight, 1f, lightScale, rainLight)
        for (c in 0 until 3) {
            smokeLight[c] += NightLight.FLASH[c] * flash
            rainLight[c] += NightLight.FLASH[c] * flash
        }
        for (i in 0 until count) {
            p.setTo(px[i], py[i], pz[i])
            bodyRotation.rotate(p, p).subInPlace(cameraPos)
            val u = age[i] / life[i]
            val size = size0[i] + (size1[i] - size0[i]) * u
            // Short-lived ones come in fast, or a spark would be gone before it was ever bright.
            val fadeIn = min(1f, age[i] / min(0.2f, life[i] * 0.15f))
            // Rain holds its strength to the end; faded like smoke, a downpour was hard to see.
            val fadeOut = if (streak[i] && !glows[i]) {
                // And gone before it reaches the lens, or a drop near the camera draws as a bar
                // across the screen.
                val near = ((p.length - 2.0) / 3.0).coerceIn(0.0, 1.0).toFloat()
                (1f - ((u - 0.8f) / 0.2f).coerceIn(0f, 1f)) * near
            } else (1f - u) * (1f - u)
            val alpha = colour[i * 4 + 3] * fadeIn * fadeOut
            val light = when {
                glows[i] -> GLOWING
                streak[i] -> rainLight
                else -> smokeLight
            }
            val r = colour[i * 4] * light[0]; val g = colour[i * 4 + 1] * light[1]; val b = colour[i * 4 + 2] * light[2]
            if (pellets[i]) {
                // A hailstone: a round stone however fast it falls, never under HAIL_SEEN across
                // as seen, in a bright rim.
                val stone = max(size.toDouble(), p.length * HAIL_SEEN)
                // Ice catches what light there is: brighter than rain, its rim brighter still.
                o = hexagon(v, o, p, right, upAxis, stone * 1.3, min(1f, r * 2f), min(1f, g * 2f), min(1f, b * 2f), alpha * 0.85f)
                o = hexagon(v, o, p, right, upAxis, stone, min(1f, r * 1.4f), min(1f, g * 1.4f), min(1f, b * 1.4f), alpha)
                written++
            } else if (motes[i]) {
                // An ash fleck: pale grey in a dark rim so it shows against the haze, held to its
                // strength until its last quarter.
                val fleck = max(size.toDouble(), p.length * ASH_SEEN)
                val a = colour[i * 4 + 3] * fadeIn * (1f - ((u - 0.75f) / 0.25f).coerceIn(0f, 1f))
                o = hexagon(v, o, p, right, upAxis, fleck * 1.5, r * 0.25f, g * 0.25f, b * 0.25f, a * 0.8f)
                o = hexagon(v, o, p, right, upAxis, fleck, min(1f, r * 1.3f), min(1f, g * 1.3f), min(1f, b * 1.3f), a)
                written++
            } else if (streak[i]) {
                // A short streak along its travel as seen: rain a long thin line, a spark a glowing
                // dash as long as it's quick.
                scratch.setTo(vx[i], vy[i], vz[i])
                bodyRotation.rotate(scratch, scratch)
                val length = when {
                    glows[i] -> (scratch.length * 0.05).coerceIn(0.15, 1.2)
                    flakes[i] -> (scratch.length * FLAKE_SMEAR).coerceIn(0.05, 0.4)
                    else -> 2.2
                }
                // Rain thins in hail, at most a hair as seen, so the stones stand out.
                val width = if (glows[i]) size * 0.22 else if (flakes[i]) size.toDouble()
                    else if (hailing > 0f) min(0.04 * (1.0 - 0.5 * hailing), p.length * 0.0015 + 0.04 * (1f - hailing)) else 0.04
                scratch.normalizeInPlace().mulInPlace(length)
                scratch2.setTo(p).crossInPlace(scratch).normalizeInPlace().mulInPlace(width.toDouble())
                o = quad(v, o, p, scratch, scratch2, r, g, b, alpha)
            } else {
                o = hexagon(v, o, p, right, upAxis, size.toDouble(), r, g, b, alpha)
            }
            written++
        }
        // Tornado debris: dark boards, branches and clods, each a thin quad turned and foreshortened
        // as it tumbles, never under TornadoDebris.SEEN long as seen.
        if (debris != null) for (i in 0 until thrown) {
            p.setTo(debris.x[i], debris.y[i], debris.z[i])
            bodyRotation.rotate(p, p).subInPlace(cameraPos)
            val d = p.length
            val long = max(debris.length[i].toDouble(), d * TornadoDebris.SEEN) * debris.fore[i]
            val wide = max(debris.width[i].toDouble(), d * TornadoDebris.SEEN_WIDE)
            val ca = kotlin.math.cos(debris.angle[i].toDouble()); val sa = kotlin.math.sin(debris.angle[i].toDouble())
            scratch.setTo(right).mulInPlace(ca * max(long, wide)).addScaledInPlace(upAxis, sa * max(long, wide))
            scratch2.setTo(right).mulInPlace(-sa * wide).addScaledInPlace(upAxis, ca * wide)
            val c = debris.colour
            o = quad(v, o, p, scratch, scratch2, c[i * 4] * smokeLight[0], c[i * 4 + 1] * smokeLight[1], c[i * 4 + 2] * smokeLight[2], c[i * 4 + 3])
            written++
        }
        if (bolts.isNotEmpty()) {
            bodyRotation.inverseRotate(cameraPos, boltCamera)
            bodyRotation.inverseRotate(cameraRotation.rotate(boltLook.setTo(0.0, 0.0, -1.0), boltLook), boltLook)
        }
        for (bolt in bolts) {
            val t = time - bolt.born
            // Each stroke flares and dies in a few hundredths of a second over a dim channel;
            // the branches are lit by the first stroke, faintly by the second, then gone.
            val glow = bolt.glow(t)
            var forks = 0.0
            for (k in 0 until min(2, bolt.strokes.size)) {
                val since = t - bolt.strokes[k]
                if (since >= 0.0) forks = max(forks, exp(-since / 0.035) * (if (k == 0) 1.0 else 0.35))
            }
            if (glow < 0.01) continue
            // Long segments seen close are split into finer kinks, the main channel first.
            var finer = BOLT_FINER
            finer -= refine(bolt.shape.main, bolt.seed, 0, finer, boltMain)
            if (forks >= 0.01) for ((j, branch) in bolt.shape.branches.withIndex()) finer -= refine(branch, bolt.seed, j + 1, finer, boltForks[j])
            // A soft blue glow under a thin white core.
            o = boltLine(v, o, boltMain, bodyRotation, cameraPos, bolt.width * 3.5, BOLT_GLOW_ANGLE, 0.6f, 0.65f, 1f, (0.25 * glow).toFloat(), false, 1f)
            o = boltLine(v, o, boltMain, bodyRotation, cameraPos, bolt.width, BOLT_CORE_ANGLE, 1f, 1f, 1f, glow.toFloat(), false)
            written += (boltMain.size - 1) * 2
            if (forks < 0.01) continue
            for (j in bolt.shape.branches.indices) {
                val branch = boltForks[j]
                o = boltLine(v, o, branch, bodyRotation, cameraPos, bolt.width * 3.0, BOLT_GLOW_ANGLE, 0.65f, 0.7f, 1f, (0.25 * forks).toFloat(), true, 1f)
                o = boltLine(v, o, branch, bodyRotation, cameraPos, bolt.width * 0.6, BOLT_CORE_ANGLE, 0.95f, 0.95f, 1f, forks.toFloat(), true)
                written += (branch.size - 1) * 2
            }
        }
        return v to written
    }

    /** Hailstones and ash flecks alive, each drawn twice (rim and middle). */
    private fun pelletCount(): Int {
        var n = 0
        for (i in 0 until count) if (pellets[i] || motes[i]) n++
        return n
    }

    /** The camera, body-fixed, for splitting bolt segments by how close they are. */
    private val boltCamera = Vec3()
    /** And the way it looks, body-fixed. */
    private val boltLook = Vec3()
    private val boltMain = ArrayList<Vec3>()
    private val boltForks = Array(7) { ArrayList<Vec3>() }
    private val boltPool = ArrayList<Vec3>()
    private var boltPoolUsed = 0
    private val boltLevels = IntArray(BOLT_MAIN + 1)
    private val boltMids = Array(BOLT_LEVELS + 1) { Vec3() }

    private fun pooled(): Vec3 {
        if (boltPoolUsed == boltPool.size) boltPool.add(Vec3())
        return boltPool[boltPoolUsed++]
    }

    /**
     * [points] into [out] with each segment in view that looks longer than [BOLT_SPLIT] radians
     * from the camera split in two, and again, up to [BOLT_LEVELS] times, each new point kinked off the
     * line by up to about half its piece. The kinks come from [seed], [line] and where they fall,
     * so a closer look only adds to the shape. At most [budget] new points; returns how many.
     */
    private fun refine(points: List<Vec3>, seed: Int, line: Int, budget: Int, out: ArrayList<Vec3>): Int {
        if (line == 0) boltPoolUsed = 0
        out.clear()
        val n = points.size - 1
        var total = 0
        for (k in 0 until n) {
            val a = points[k]; val b = points[k + 1]
            scratch.setTo(a).addInPlace(b).mulInPlace(0.5).subInPlace(boltCamera)
            val distance = max(scratch.length, 1.0)
            val angle = a.distanceTo(b) / distance
            // Off the screen: more than about 55 degrees off the view, by its nearest end.
            val off = kotlin.math.acos(((scratch dot boltLook) / distance).coerceIn(-1.0, 1.0)) - angle * 0.5 > 0.95
            var level = 0
            while (!off && level < BOLT_LEVELS && angle / (1 shl level) > BOLT_SPLIT) level++
            boltLevels[k] = level
            total += (1 shl level) - 1
        }
        // Over budget: take a level off the most split until it fits.
        while (total > budget) {
            var most = 0
            for (k in 1 until n) if (boltLevels[k] > boltLevels[most]) most = k
            if (boltLevels[most] == 0) break
            total -= 1 shl (boltLevels[most] - 1)
            boltLevels[most]--
        }
        for (k in 0 until n) {
            out.add(points[k])
            if (boltLevels[k] > 0) split(points[k], points[k + 1], seed, line * 64 + k, 1, 0, boltLevels[k], out)
        }
        out.add(points[n])
        return total
    }

    /** The points strictly between [a] and [b], in order, split [levels] deep. */
    private fun split(a: Vec3, b: Vec3, seed: Int, key: Int, level: Int, path: Int, levels: Int, out: ArrayList<Vec3>) {
        val m = boltMids[level]
        val length = a.distanceTo(b)
        val h = key * 32 + (1 shl level) + path
        m.setTo(Noise.hash(seed, h, 40, 0) - 0.5, Noise.hash(seed, h, 40, 1) - 0.5, Noise.hash(seed, h, 40, 2) - 0.5)
        scratch2.setTo(b).subInPlace(a)
        // Across the piece only, so it stays as long as it was.
        m.addScaledInPlace(scratch2, -(m dot scratch2) / max(scratch2.lengthSq, 1e-9))
        m.mulInPlace(0.9 * length).addInPlace(a).addScaledInPlace(scratch2, 0.5)
        if (level < levels) split(a, m, seed, key, level + 1, path * 2, levels, out)
        out.add(pooled().setTo(m))
        if (level < levels) split(m, b, seed, key, level + 1, path * 2 + 1, levels, out)
    }

    /**
     * [points], body-fixed, as camera-facing ribbons [width] metres each side, a touch longer than
     * each segment so the kinks join. Within [BOLT_NEAR] metres no thinner than [angle] radians
     * as seen and [nearer] times again as strong, so a bolt close by keeps its core and glow. [fades] thins its strength to nothing at
     * the far end.
     */
    private fun boltLine(
        v: FloatArray, start: Int, points: List<Vec3>, bodyRotation: Quat, cameraPos: Vec3,
        width: Double, angle: Double, r: Float, g: Float, b: Float, a: Float, fades: Boolean, nearer: Float = 0f,
    ): Int {
        var o = start
        val from = boltA; val to = boltB; val mid = boltMid
        bodyRotation.rotate(points[0], from).subInPlace(cameraPos)
        val n = points.size - 1
        for (k in 0 until n) {
            bodyRotation.rotate(points[k + 1], to).subInPlace(cameraPos)
            scratch.setTo(to).subInPlace(from).mulInPlace(0.5)
            mid.setTo(from).addInPlace(scratch)
            val distance = mid.length
            val near = 1.0 - smoothstep(BOLT_NEAR * 0.4, BOLT_NEAR, distance)
            scratch2.setTo(mid).crossInPlace(scratch).normalizeInPlace().mulInPlace(max(width, distance * angle * near))
            scratch.mulInPlace(1.08)
            val strength = (if (fades) a * (1f - k.toFloat() / n) else a) * (1f + nearer * near.toFloat())
            o = quad(v, o, mid, scratch, scratch2, r, g, b, strength)
            from.setTo(to)
        }
        return o
    }

    private val boltA = Vec3()
    private val boltB = Vec3()
    private val boltMid = Vec3()

    private fun hexagon(v: FloatArray, start: Int, c: Vec3, right: Vec3, up: Vec3, size: Double, r: Float, g: Float, b: Float, a: Float): Int {
        var o = start
        for (k in 0 until 6) {
            val angle = k * Math.PI / 3.0
            val cs = kotlin.math.cos(angle) * size; val sn = kotlin.math.sin(angle) * size
            v[o++] = (c.x + right.x * cs + up.x * sn).toFloat()
            v[o++] = (c.y + right.y * cs + up.y * sn).toFloat()
            v[o++] = (c.z + right.z * cs + up.z * sn).toFloat()
            // Facets: each corner a touch lighter or darker.
            val facet = 0.92f + 0.08f * ((k % 3) - 1)
            v[o++] = r * facet; v[o++] = g * facet; v[o++] = b * facet; v[o++] = a
        }
        return o
    }

    /**
     * A thin quad centred on [c], half-length along [along] and half-width along [across], as a
     * degenerate hexagon.
     */
    private fun quad(v: FloatArray, start: Int, c: Vec3, along: Vec3, across: Vec3, r: Float, g: Float, b: Float, a: Float): Int {
        var o = start
        val corners = arrayOf(1.0 to 1.0, 1.0 to -1.0, 0.0 to -1.0, -1.0 to -1.0, -1.0 to 1.0, 0.0 to 1.0)
        for ((s, t) in corners) {
            v[o++] = (c.x + along.x * s + across.x * t).toFloat()
            v[o++] = (c.y + along.y * s + across.y * t).toFloat()
            v[o++] = (c.z + along.z * s + across.z * t).toFloat()
            v[o++] = r; v[o++] = g; v[o++] = b; v[o++] = a
        }
        return o
    }

    // --- noise --------------------------------------------------------------------

    // Only the smoke's look depends on these, so plain randomness is fine. Nothing but its drift
    // has to agree between devices.
    private val random = com.rm.apogee.platform.SplittableRandom(0x5A0E)

    @Suppress("UNUSED_PARAMETER")
    private fun rand(k: Int): Float = random.nextDouble().toFloat()

    @Suppress("UNUSED_PARAMETER")
    private fun jitter(k: Int, axis: Int): Double = random.nextDouble() * 2.0 - 1.0

    /** How many to emit this frame for an expected [mean], carrying the fraction over. */
    private val carry = HashMap<Int, Double>()

    private fun poisson(mean: Double, key: Int): Int {
        val total = mean + (carry[key] ?: 0.0)
        val n = total.toInt()
        carry[key] = total - n
        return n
    }

    companion object {
        /** Out to here a vent is seen at work, in metres. */
        private const val PLUME_REACH = 150_000.0

        /**
         * A sea-floor vent's reach in metres (past what the murk shows), and how far the camera
         * goes before they're looked for again.
         */
        private const val VENT_REACH = 150.0

        /**
         * Geysers and fumaroles: how near they're seen, how far the camera moves before looking
         * again, how high a geyser throws, and puffs a second from each.
         */
        private const val GROUND_VENT_REACH = 300.0
        private const val GROUND_VENT_LOOK_AGAIN = 50.0
        private const val GEYSER_HEIGHT = 25.0
        private const val GEYSER_RATE = 14.0
        private const val FUMAROLE_RATE = 2.0
        private const val VENT_LOOK_AGAIN = 25.0

        /** A sea-floor vent's smoke and shimmer, a second each. */
        private const val VENT_PUFFS = 3.0
        private const val VENT_SHIMMER = 6.0

        private val SULFUR = floatArrayOf(0.88f, 0.82f, 0.55f)
        private val VAPOUR = floatArrayOf(0.95f, 0.97f, 1.0f)
        private val SOOT = floatArrayOf(0.26f, 0.22f, 0.22f)

        /**
         * Each world's vents: where, how high and how often they throw, how big and spread, and
         * what colour.
         */
        private val PLUMES: Map<String, List<Vent>> = mapOf(
            "fornax" to listOf(
                Vent(-12.0, 50.0, 12_000.0, 1.5, 300.0, 0.45, SULFUR),
                Vent(25.0, -140.0, 9_000.0, 1.2, 260.0, 0.4, SULFUR),
                Vent(-40.0, 170.0, 10_000.0, 1.2, 280.0, 0.45, SULFUR),
            ),
            "fons" to listOf(
                Vent(-80.0, 20.0, 2_000.0, 0.3, 60.0, 0.12, VAPOUR),
                Vent(-83.0, 60.0, 2_500.0, 0.3, 60.0, 0.12, VAPOUR),
                Vent(-86.0, 150.0, 2_000.0, 0.3, 60.0, 0.12, VAPOUR),
                Vent(-82.0, -100.0, 1_800.0, 0.3, 60.0, 0.12, VAPOUR),
            ),
            "aversa" to listOf(
                Vent(-55.0, 40.0, 3_000.0, 0.8, 80.0, 0.08, SOOT),
                Vent(-60.0, 10.0, 2_500.0, 0.8, 80.0, 0.08, SOOT),
            ),
        )

        /** The most of the particle budget rain can take, in a downpour. */
        const val RAIN_SHARE = 0.5

        /**
         * Spray: how high over the sea the camera still sees it and how far round, in metres, and
         * tries per second.
         */
        const val SPRAY_HEIGHT = 400.0
        const val SPRAY_REACH = 90.0
        const val SPRAY_TRIES = 40.0

        /** Wake foam per second per m/s of speed, and bow spray per m/s past a brisk pace. */
        const val WAKE_RATE = 3.0
        const val BOW_RATE = 8.0

        /** Floats per vertex: position and colour. */
        const val VERTEX_FLOATS = 7

        /** An ambient at or above 1 means "glows", drawn at its own colour. */
        const val EMISSIVE = 1.0f

        /** A glowing particle's light: its own colour, day or night. */
        private val GLOWING = floatArrayOf(1f, 1f, 1f)

        const val CONTRAIL_ALTITUDE = 8_000.0

        /** Sparks off a straining seam come this many at a time. */
        private const val BURST = 5
        const val SPEED_OF_SOUND = 340.0

        /**
         * The recovery temperature rise where the plasma starts to show, and where it's full, in K.
         */
        const val GLOW_FROM = 700.0
        const val GLOW_FULL = 2_600.0

        /** A cone of condensed vapour, open at the back, around the craft. */
        val VAPOUR_CONE = ModelSpec.Lathe(
            listOf(listOf(0.15, 0.55), listOf(0.6, 0.25), listOf(0.92, -0.2), listOf(1.0, -0.55)),
            segments = 14,
        )

        /**
         * A sheath of hot air round a part's face, +Y upstream: a round cap just ahead of it, flaring
         * past its edge and streaming back to a point.
         */
        val SHEATH = ModelSpec.Lathe(
            listOf(listOf(0.0, 0.25), listOf(0.6, 0.15), listOf(0.95, -0.1), listOf(1.0, -0.35), listOf(0.75, -0.6), listOf(0.35, -0.85), listOf(0.0, -1.0)),
            segments = 14,
        )

        /** At most this many parts get a sheath, and a sheltered one glows this much. */
        const val MAX_SHEATHS = 24

        /** Airspeed in m/s below which the air shows nothing round a craft. */
        const val AIR_SHOWS_FROM = 150.0

        /** Whether the air at [speed] m/s shows round a craft's parts, as vapour near Mach 1 or as glow. */
        fun showsShape(speed: Double): Boolean =
            speed > AIR_SHOWS_FROM && (kotlin.math.abs(speed / SPEED_OF_SOUND - 1.0) < 0.25 || speed * speed / (2.0 * 1_005.0) > GLOW_FROM)
        const val SHELTERED = 0.3

        /** The thinnest a sheath gets, in metres, so a fin edge-on still shows a line of glow. */
        const val MIN_SHEATH = 0.15

        /** The glowing cap of a re-entry shock, bulging forward. */
        val BOW_SHOCK = ModelSpec.Lathe(
            listOf(listOf(0.0, 0.6), listOf(0.55, 0.45), listOf(0.9, 0.1), listOf(1.05, -0.35), listOf(1.1, -0.8)),
            segments = 14,
        )
        /** Puffs per second from a thruster block firing flat out. */
        const val RCS_PUFFS = 45.0
        const val RAIN_RADIUS = 45.0
        /** Sand streaks from this much dust, with the camera under this many metres over the ground, at most this many a second, this far off in metres. */
        const val SAND_FROM = 0.3
        const val SAND_ABOVE = 60.0
        const val SAND_RATE = 15.0
        const val SAND_LIFE = 0.8
        /** Cosine of the angle off the view's middle inside which no sand is drawn (about 40 degrees). */
        const val SAND_CENTRE = 0.77
        const val SAND_NEAR = 6.0
        const val SAND_FAR = 25.0
        const val RAIN_FALL = 9.0
        /** Snow round the camera from this much, at most this many flakes a second and this share of the budget, this far off in metres. */
        const val SNOW_FROM = 0.05
        const val SNOW_RATE = 500.0
        const val SNOW_SHARE = 0.3
        const val SNOW_LIFE = 1.2
        const val SNOW_NEAR = 2.0
        const val SNOW_FAR = 22.0
        const val SNOW_FALL = 1.2
        /** Flakes draw as streaks in wind past this, m/s. */
        const val SNOW_STREAK = 9.0
        /** Cosine of the angle off the view's middle inside which no snow is drawn (about 18 degrees): snow fills more of the view than sand. */
        const val SNOW_CENTRE = 0.95
        /** No rain drawn with this much snow falling. */
        const val SNOW_NOT_RAIN = 0.2
        /** A flake's streak is its speed times this long, metres per m/s: blur over a sixtieth of a second. */
        const val FLAKE_SMEAR = 0.015
        /** Ash round the camera from this much, at most this many flecks a second and this share of the budget, this far off in metres, falling this fast in m/s. */
        const val ASH_FROM = 0.05
        const val ASH_RATE = 1_400.0
        const val ASH_SHARE = 0.35
        /** Ash at which the flecks are as thick as they get. */
        const val ASH_FULL = 0.35
        const val ASH_LIFE = 2.0
        const val ASH_NEAR = 1.0
        const val ASH_FAR = 6.0
        /** Kept a little wider of the middle than snow, as they wander more on the way. */
        const val ASH_CENTRE = 0.92
        /** Least size of a fleck as seen [rad], so the far ones aren't lost under a pixel. */
        const val ASH_SEEN = 0.004
        const val ASH_FALL = 0.8
        /** Hail round the camera from this much, at most this many stones a second and this share of the budget, this far off in metres. */
        const val HAIL_FROM = 0.05
        const val HAIL_RATE = 600.0
        const val HAIL_SHARE = 0.3
        const val HAIL_LIFE = 1.4
        const val HAIL_NEAR = 2.0
        const val HAIL_FAR = 18.0
        /** How fast it falls, m/s, how much of that it bounces back up with, and under what it stops. */
        const val HAIL_FALL = 16.0
        const val HAIL_BOUNCE = 0.3
        const val HAIL_REST = 1.5
        /** A hailstone is drawn at least this many radians across each side, so it reads as a stone from 18 m. */
        const val HAIL_SEEN = 0.0045
        const val CLOUD_BASE = 1_200.0
        const val BOLT_SECONDS = 0.45
        /** Segments in a bolt's main channel and in each branch. */
        const val BOLT_MAIN = 48
        const val BOLT_BRANCH = 8
        /** A bolt segment longer than this as seen, in radians, is split, at most this many times over and into this many new points a bolt. */
        const val BOLT_SPLIT = 0.012
        const val BOLT_LEVELS = 4
        const val BOLT_FINER = 160
        /** Within this many metres a bolt's core and glow are at least this wide each side as seen, in radians. */
        const val BOLT_NEAR = 2_500.0
        const val BOLT_CORE_ANGLE = 0.0012
        const val BOLT_GLOW_ANGLE = 0.007

        /** Strike [id]'s strokes, seconds after it: the first at once, then 1 or 2 more 0.07-0.15 s apart. */
        private fun boltStrokes(id: Long): DoubleArray {
            val seed = (id xor (id ushr 32)).toInt()
            val n = if (Noise.hash(seed, 0, 30, 0) < 0.5) 2 else 3
            val out = DoubleArray(n)
            for (k in 1 until n) out[k] = out[k - 1] + 0.07 + 0.08 * Noise.hash(seed, k, 30, 1)
            return out
        }
        /** A bolt into a craft has a core this wide each side, in metres: it's seen close. */
        const val STRUCK_WIDTH = 0.12
        const val LIGHTNING_VISIBLE = 40_000.0

        /**
         * The outer flame for [vacuum] (0 thick air, 1 none), in units of the bell's mouth across
         * and the flame's length along, from far downstream back to exactly the mouth. In air it's
         * barely wider than the bell; as the air thins it fans out behind like a second, larger
         * bell, never at the nozzle. One of [PLUME_STEPS] shapes, so the renderer only builds a
         * few.
         */
        fun rocketPlume(vacuum: Double): ModelSpec.Lathe =
            PLUME_SHAPES[(vacuum.coerceIn(0.0, 1.0) * (PLUME_STEPS - 1) + 0.5).toInt()]

        private const val PLUME_STEPS = 8
        private val PLUME_ALONG = doubleArrayOf(-1.0, -0.9, -0.75, -0.55, -0.35, -0.18, -0.07, 0.0)
        private val PLUME_AIR = doubleArrayOf(0.08, 0.3, 0.55, 0.85, 1.02, 1.08, 1.04, 1.0)
        private val PLUME_VACUUM = doubleArrayOf(0.0, 2.2, 2.9, 2.8, 2.3, 1.7, 1.25, 1.0)
        private val PLUME_SHAPES = List(PLUME_STEPS) { step ->
            val v = step / (PLUME_STEPS - 1.0)
            ModelSpec.Lathe(
                PLUME_ALONG.indices.map { i -> listOf(PLUME_AIR[i] + (PLUME_VACUUM[i] - PLUME_AIR[i]) * v, PLUME_ALONG[i]) },
                segments = 12,
            )
        }
        val ROCKET_CORE = ModelSpec.Lathe(
            listOf(listOf(0.0, -1.0), listOf(0.7, -0.6), listOf(1.0, -0.2), listOf(0.9, 0.0)),
            segments = 10,
        )
        val JET_PLUME = ModelSpec.Lathe(
            listOf(listOf(0.0, -1.0), listOf(0.6, -0.5), listOf(0.9, -0.1), listOf(0.85, 0.0)),
            segments = 10,
        )
    }
}

/**
 * A part as the air meets it, in the attractor frame: its middle, its own X, Y and Z (unit) and half
 * its size along each. A [round] part is a cylinder about Y, with [halves] x as its radius. A [wing]
 * is a flat plate: span along X, chord along Y and thin along Z.
 */
class AeroPart(val centre: Vec3, val x: Vec3, val y: Vec3, val z: Vec3, val halves: Vec3, val round: Boolean, val wing: Boolean = false) {
    /** Half its size along [direction], unit. */
    fun extent(direction: Vec3): Double {
        val alongY = kotlin.math.abs(y dot direction)
        if (round) return halves.y * alongY + halves.x * kotlin.math.sqrt((1.0 - alongY * alongY).coerceAtLeast(0.0))
        return halves.x * kotlin.math.abs(x dot direction) + halves.y * alongY + halves.z * kotlin.math.abs(z dot direction)
    }

    /** Its longest side as seen down [flow], unit and square to it. */
    fun longestAcross(flow: Vec3): Vec3 {
        var best = Vec3(); var most = -1.0
        for ((axis, half) in listOf(x to halves.x, y to halves.y, z to halves.z)) {
            val side = axis.copy().addScaledInPlace(flow, -(axis dot flow))
            val seen = side.length
            if (seen < 0.1) continue
            if (half * seen > most) { most = half * seen; best = side.mulInPlace(1.0 / seen) }
        }
        return best
    }
}
