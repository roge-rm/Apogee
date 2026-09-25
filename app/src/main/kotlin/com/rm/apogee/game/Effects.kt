package com.rm.apogee.game

import com.rm.apogee.core.world.PartEventKind
import com.rm.apogee.core.math.Quat
import com.rm.apogee.core.math.Vec3
import com.rm.apogee.core.math.quatFromTo
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

/** One lit engine this frame, in the attractor's frame. */
class EngineEmitter(
    /** Where the exhaust leaves the nozzle. */
    val nozzle: Vec3,
    /** Unit: the way the exhaust goes. */
    val out: Vec3,
    /** Nozzle radius, m. */
    val radius: Double,
    val kind: Exhaust,
    val throttle: Double,
    /** The craft's velocity. */
    val velocity: Vec3,
    /** A stable number for this engine, for its flicker. */
    val seed: Int,
    /** The bell's mouth, m: where the flame is drawn from. */
    val exitRadius: Double = radius * 1.25,
)

/**
 * What engines and weather leave in the air: flames, smoke, dust, spray,
 * contrails, rain and lightning.
 *
 * Particles live in the body's turning frame, where the wind is, and are
 * carried by the same [Weather] every device computes - so smoke drifts
 * downwind, rises in a thermal and swirls in a gust the same way for every
 * player watching. They are small faceted hexagons, flat-coloured, in keeping
 * with everything else. Flames are translucent faceted cones, glowing.
 */
class Effects(tier: QualityTier) {

    private val capacity = tier.particleBudget

    // Particle state, structure of arrays. Positions and velocities body-fixed.
    private val px = DoubleArray(capacity); private val py = DoubleArray(capacity); private val pz = DoubleArray(capacity)
    private val vx = DoubleArray(capacity); private val vy = DoubleArray(capacity); private val vz = DoubleArray(capacity)
    private val age = FloatArray(capacity); private val life = FloatArray(capacity)
    private val size0 = FloatArray(capacity); private val size1 = FloatArray(capacity)
    private val colour = FloatArray(capacity * 4)
    /** How quickly it takes up the wind: 1/s. */
    private val grip = FloatArray(capacity)
    /** Upward acceleration from heat, m/s^2, fading as it cools. */
    private val rise = FloatArray(capacity)
    private val streak = BooleanArray(capacity)
    /** Lights itself - fire, sparks - rather than being lit by the sun. */
    private val glows = BooleanArray(capacity)
    /** Falls and comes to rest on the ground, as fragments and sparks do. */
    private val falls = BooleanArray(capacity)
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

    /** Emission budget per engine per second is scaled to the device. */
    private val rateScale = (capacity / 2048.0).coerceIn(0.5, 3.0)

    private class Bolt(val points: List<Vec3>, val born: Double, val energy: Double)
    private val bolts = ArrayList<Bolt>()
    private var lastStrikeCheck = Double.NaN
    private val strikes = ArrayList<Strike>()

    /** Particles alive now. */
    val particleCount: Int get() = count

    /** The mean position of the particles, body-fixed, into [out]: for tests. */
    internal fun centroid(out: Vec3): Vec3 {
        out.setZero()
        if (count == 0) return out
        for (i in 0 until count) out.addInPlace(Vec3(px[i], py[i], pz[i]))
        return out.mulInPlace(1.0 / count)
    }

    /** Bolts seen since last drained: distance, m, and energy - for their thunder. */
    val thunder = ArrayList<DoubleArray>()

    /** How bright the sky is from lightning, 0..1, decaying. */
    var flash = 0f
        private set

    /**
     * Advances everything by [dt], at [time]: emits from [emitters], moves
     * the particles through the air, rains if [cameraAir] is raining, and
     * lights any strike near the camera.
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
    ) {
        if (dt <= 0.0) return
        gravity = body.surfaceGravity
        val terrain = body.terrain
        val atmosphere = body.atmosphere

        // The wind at two heights over the focus: the particles take the
        // one nearer theirs. A sample each per frame, not one per particle.
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
            if (cameraAir != null && cameraAir.precipitation > 0.02) rain(dt, cameraAir.precipitation, cameraBodyFixed, body)
        }
        advance(dt, body)
        if (weather != null) lightning(time, weather, cameraBodyFixed, body)
        flash = (flash * exp(-dt / 0.12).toFloat()).coerceAtLeast(0f)
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
        val ground = body.terrain?.let { t -> max(t.elevation(up), if (t.hasOcean) 0.0 else -1e9) } ?: 0.0
        val agl = altitude - ground

        when (e.kind) {
            Exhaust.ROCKET -> {
                // Smoke off the end of the flame: thick low down, thin high up.
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
                        spawn(
                            x = gx, y = gy, z = gz,
                            vx = dirX * speed + up.x * 2.0, vy = dirY * speed + up.y * 2.0, vz = dirZ * speed + up.z * 2.0,
                            life = 6.0 + 6.0 * rand(k + 13),
                            startSize = 2.0, endSize = 10.0 + 8.0 * rand(k + 17),
                            r = tone, g = tone * 0.95f, b = tone * 0.88f, a = 0.6f,
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
        for (k in 0 until n) {
            val tone = 0.6f + 0.2f * rand(k)
            spawn(
                x = at.x - up.x * agl + jitter(k, 1) * 3, y = at.y - up.y * agl + jitter(k, 2) * 3, z = at.z - up.z * agl + jitter(k, 3) * 3,
                vx = jitter(k, 4) * 6 + up.x, vy = jitter(k, 5) * 6 + up.y, vz = jitter(k, 6) * 6 + up.z,
                life = 3.0 + 3.0 * rand(k + 1), startSize = 1.0, endSize = 5.0,
                r = tone, g = tone * 0.93f, b = tone * 0.82f, a = 0.5f, grip = 1.0, rise = 0.3,
            )
        }
    }

    private fun rain(dt: Double, precipitation: Double, camera: Vec3, body: CelestialBody) {
        up.setTo(camera).normalizeInPlace()
        val n = poisson(precipitation * 1_800.0 * rateScale * dt, 991)
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

    // --- lightning ------------------------------------------------------------

    private fun lightning(time: Double, weather: Weather, camera: Vec3, body: CelestialBody) {
        val from = if (lastStrikeCheck.isNaN() || time - lastStrikeCheck > 2.0) time - 0.05 else lastStrikeCheck
        lastStrikeCheck = time
        strikes.clear()
        weather.strikes(scratch.setTo(camera).normalizeInPlace(), from, time, strikes)
        for (s in strikes) {
            val groundHeight = body.terrain?.let { max(it.elevation(s.direction), 0.0) } ?: 0.0
            val ground = Vec3().setTo(s.direction).mulInPlace(body.radius + groundHeight)
            val distance = ground.distanceTo(camera)
            if (distance > LIGHTNING_VISIBLE) continue
            bolts.add(Bolt(jagged(ground, s.direction, s.id), time, s.energy))
            thunder.add(doubleArrayOf(distance, s.energy))
            flash = max(flash, (s.energy * exp(-distance / 12_000.0)).toFloat())
        }
        bolts.removeAll { time - it.born > BOLT_SECONDS }
    }

    /** A bolt from the cloud base down to [ground], forking a little on the way. */
    private fun jagged(ground: Vec3, up: Vec3, id: Long): List<Vec3> {
        val points = ArrayList<Vec3>(14)
        val east = Vec3(up.z, 0.0, -up.x).let { if (it.lengthSq < 1e-12) Vec3(1.0, 0.0, 0.0) else it.normalizeInPlace() }
        val north = Vec3().setTo(up).crossInPlace(east)
        val seed = (id xor (id ushr 32)).toInt()
        var drift = 0.0; var driftN = 0.0
        for (k in 0..12) {
            val h = CLOUD_BASE * k / 12.0
            drift += (Noise.hash(seed, k, 1, 0) - 0.5) * 70.0
            driftN += (Noise.hash(seed, k, 2, 0) - 0.5) * 70.0
            points.add(Vec3().setTo(ground).addScaledInPlace(up, h).addScaledInPlace(east, if (k == 0) 0.0 else drift).addScaledInPlace(north, if (k == 0) 0.0 else driftN))
        }
        return points
    }

    // --- motion ---------------------------------------------------------------

    private fun advance(dt: Double, body: CelestialBody) {
        val dtf = dt.toFloat()
        var i = 0
        while (i < count) {
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
            if (falls[i]) {
                val r = sqrt(px[i] * px[i] + py[i] * py[i] + pz[i] * pz[i])
                val ux = px[i] / r; val uy = py[i] / r; val uz = pz[i] / r
                if (height > 0.15) {
                    val g = gravity * dt
                    vx[i] -= ux * g; vy[i] -= uy * g; vz[i] -= uz * g
                } else if (streak[i] && glows[i]) {
                    // A spark: out as it lands.
                    kill(i); continue
                } else {
                    // Landed: it stops going down, and skids to a halt.
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
            glows[i] = glows[last]; falls[i] = falls[last]
        }
        count--
    }

    private fun spawn(
        x: Double, y: Double, z: Double, vx: Double, vy: Double, vz: Double,
        life: Double, startSize: Double, endSize: Double,
        r: Float, g: Float, b: Float, a: Float, grip: Double, rise: Double, streak: Boolean = false,
        glow: Boolean = false, fall: Boolean = false,
    ) {
        // Full: the oldest-looking one makes way, rather than the new one
        // not appearing - fresh smoke matters more than old.
        val i = if (count < capacity) count++ else (spawnCounter++ % capacity)
        px[i] = x; py[i] = y; pz[i] = z
        this.vx[i] = vx; this.vy[i] = vy; this.vz[i] = vz
        age[i] = 0f; this.life[i] = life.toFloat()
        size0[i] = startSize.toFloat(); size1[i] = endSize.toFloat()
        colour[i * 4] = r; colour[i * 4 + 1] = g; colour[i * 4 + 2] = b; colour[i * 4 + 3] = a
        this.grip[i] = grip.toFloat(); this.rise[i] = rise.toFloat(); this.streak[i] = streak
        glows[i] = glow; falls[i] = fall
    }

    // --- flames -----------------------------------------------------------------

    /**
     * Each lit engine's flame, as glowing translucent cones into [out]: a
     * long pale outer plume and a short bright core, longer with throttle,
     * swelling into a broad faint bloom as the air thins, flickering.
     */
    fun flames(emitters: List<EngineEmitter>, body: CelestialBody, time: Double, out: MutableList<RenderItem>) {
        for (e in emitters) {
            if (e.throttle <= 0.01) continue
            val altitude = e.nozzle.length - body.radius
            val pressure = body.atmosphere?.pressureRatioAt(altitude) ?: 0.0
            val flicker = 0.9 + 0.1 * Noise.simplex(e.seed, time * 13.0, 0.0, 0.0)
            val orient = quatFromTo(Vec3(0.0, -1.0, 0.0), e.out)
            when (e.kind) {
                Exhaust.ROCKET -> {
                    // The plume only really opens up near space: cubed, so it
                    // is still a flame at 7 km (a third of the pressure left)
                    // and a broad bloom only in the last few percent of air.
                    val vacuum = (1.0 - pressure).let { it * it * it }
                    // Leaves the bell at the bell's own width and only spreads
                    // downstream of it - it used to start three bell-widths
                    // across in thin air, a slab hiding the engine (Dan).
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
     * Dust thrown up behind a wheel rolling at [speed] over dry ground at
     * [contact] (attractor frame). Nothing on grass, snow, rock or water -
     * the ground has to be loose and dry to give any.
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
     * What a craft's passage does to the air around it, drawn: a condensation
     * cone as it nears the speed of sound in damp low air, and at re-entry
     * speeds a glowing shock ahead of it with a trail of hot sparks. Sized
     * from the same airspeed and density the drag is.
     *
     * [centre] is the craft's middle and [airVelocity] its motion through the
     * air, both in the attractor's frame; [size] is its radius.
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
        /** How thick the craft is there, m: its widest body radius. */
        girth: Double = size * 0.3,
        /** Unit, from the nose back along the body: what the collar hugs. Null for along the flow. */
        back: Vec3? = null,
    ) {
        val atmosphere = body.atmosphere ?: return
        val altitude = centre.length - body.radius
        val density = atmosphere.densityAt(altitude)
        if (density <= 0.0) return
        val speed = airVelocity.length
        if (speed < 150.0) return
        val flow = airVelocity.copy().mulInPlace(-1.0 / speed)

        // Vapour: a shock cone of condensed cloud, strongest right at Mach 1,
        // only where there is water in the air to condense.
        val mach = speed / SPEED_OF_SOUND
        val damp = smoothstep(0.05, 0.35, density)
        val vapour = damp * (1.0 - smoothstep(0.0, 0.2, kotlin.math.abs(mach - 1.0))) * (0.85 + 0.15 * Noise.simplex(seed, time * 9.0, 0.0, 0.0))
        if (vapour > 0.02) {
            // A collar just behind the nose, flaring back past the shoulders:
            // where the air speeds round the craft and drops below its dew
            // point - the size of the body there, not of the whole craft.
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
        }

        // Re-entry glow: air heated to plasma. What decides it is how hot
        // the air stopped against the craft gets - the recovery temperature,
        // v²/2cp, the same the heat model uses - and a return from orbit
        // passes 2,500 K where a fast dive low down, at a kilometre a second,
        // never passes 800. Thick enough air to glow, but it hardly matters
        // how thick past that.
        val recovery = speed * speed / (2.0 * 1_005.0)
        val glow = smoothstep(GLOW_FROM, GLOW_FULL, recovery) * smoothstep(1e-6, 1e-4, density)
        if (glow > 0.02) {
            val flicker = 0.9 + 0.1 * Noise.simplex(seed + 3, time * 17.0, 0.0, 0.0)
            // Standing just off the nose, wrapping back round it.
            val ahead = nose.copy().addScaledInPlace(flow, girth * 0.4)
            out.add(
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

    // --- crashes -------------------------------------------------------------------

    /**
     * What a blow, a breakage or a blast looks like, at [at] (body-fixed).
     * [amount] is the impact speed for an impact and the propellant for an
     * explosion; [colour] is the part's own, for its fragments.
     */
    fun partEvent(kind: PartEventKind, at: Vec3, amount: Double, colour: FloatArray?, seed: Int, water: Boolean = false) {
        up.setTo(at).normalizeInPlace()
        when (kind) {
            // Docking: a little breath of gas as the latches go, more as they let go.
            PartEventKind.DOCKED, PartEventKind.HITCHED, PartEventKind.UNHITCHED -> Unit
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
                // Pieces of it, in its own colour, some scorched.
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
                // The fireball: bright, fast, slowing hard and rising.
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
     * A part on fire: flames licking up off it and smoke drifting away
     * downwind, from [at] (body-fixed), for a part [size] across.
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
     * A seam working near its limit: sparks and now and then a fleck of
     * metal off it, at [rate] a second. [at] is the seam (body-fixed),
     * [across] the way out of it, [radius] its reach; [velocity] is the
     * craft's (body-fixed), so what comes off leaves with it and falls
     * behind as the air takes it - in vacuum nothing takes it, and it keeps
     * going with the craft. [colour] is the part's own, for the flecks.
     */
    /**
     * A thruster block firing at [strength] (0..1): quick pale puffs from
     * [at] (body-fixed) along [out] (unit, the way the gas goes, body frame),
     * carried along at the craft's [velocity] over the ground. In vacuum
     * they shoot straight out and thin to nothing at once; in air they slow
     * and hang a moment.
     */
    fun rcsPuff(at: Vec3, out: Vec3, velocity: Vec3, strength: Double, inAir: Boolean, dt: Double, seed: Int) {
        if (strength < 0.02) return
        val n = poisson(RCS_PUFFS * strength * rateScale * dt, seed)
        for (k in 0 until n) {
            // A narrow cone about the nozzle's axis.
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

    fun strain(
        at: Vec3, velocity: Vec3, radius: Double, rate: Double, inAir: Boolean,
        colour: FloatArray?, dt: Double, seed: Int,
    ) {
        up.setTo(at).normalizeInPlace()
        val grip = if (inAir) 1.5 else 0.0
        // In bursts, as metal grinding on metal gives them: a handful at a
        // time, now and then, not a steady trickle.
        val n = poisson(rate / BURST * rateScale * dt, seed) * BURST
        for (k in 0 until n) {
            // Round the seam, flung outward.
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
                life = 2.0 + 2.0 * rand(k + 1), startSize = 0.12 + 0.1 * rand(k + 2), endSize = 0.1,
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

    /** Triple-buffered, so the renderer can still be reading one while the next is filled. */
    private val buffers = Array(3) { FloatArray(0) }
    private var bufferIndex = 0

    /**
     * The particles and bolts as camera-facing faceted hexagons (streaks for
     * rain, ribbons for bolts), camera-relative, into a vertex array of
     * [VERTEX_FLOATS] per vertex, six vertices per shape. Returns the array
     * and how many shapes are in it.
     */
    fun vertices(
        bodyRotation: Quat,
        cameraPos: Vec3,
        cameraRotation: Quat,
        lightScale: Float,
        time: Double,
        /** How much sun reaches the camera: at night smoke and rain are moonlit, not white. */
        daylight: Float = 1f,
        /** Lightning's light this frame. */
        flash: Float = 0f,
    ): Pair<FloatArray, Int> {
        val shapes = count + bolts.sumOf { it.points.size - 1 }
        val needed = shapes * 6 * VERTEX_FLOATS
        bufferIndex = (bufferIndex + 1) % buffers.size
        if (buffers[bufferIndex].size < needed) buffers[bufferIndex] = FloatArray(needed + 1024 * VERTEX_FLOATS)
        val v = buffers[bufferIndex]
        val right = cameraRotation.rotate(Vec3.unitX(), Vec3())
        val upAxis = cameraRotation.rotate(Vec3.unitY(), Vec3())
        val p = Vec3()
        var o = 0
        var written = 0
        // What lights a puff that does not glow: sun, or moon and twilight.
        // Rain is lit by the sky's full light by day, not dimmed by the cloud
        // it falls from, and smoke by what reaches under it.
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
            // Short-lived ones come in fast: a spark that took a fifth of a
            // second to appear would be gone before it was ever bright.
            val fadeIn = min(1f, age[i] / min(0.2f, life[i] * 0.15f))
            // Rain holds its strength until the end: faded as smoke is, a drop
            // had lost three quarters of it by the time it fell past the
            // camera, and a downpour was hard to see at all (Dan).
            val fadeOut = if (streak[i] && !glows[i]) {
                // And gone before it reaches the lens: a drop a hand's breadth
                // from the camera drew as a bar across the whole screen.
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
            if (streak[i]) {
                // A short streak along its travel, as seen: rain a long thin
                // line, a spark a glowing dash as long as it is quick.
                scratch.setTo(vx[i], vy[i], vz[i])
                bodyRotation.rotate(scratch, scratch)
                val length = if (glows[i]) (scratch.length * 0.05).coerceIn(0.15, 1.2) else 2.2
                val width = if (glows[i]) size * 0.22 else 0.04
                scratch.normalizeInPlace().mulInPlace(length)
                scratch2.setTo(p).crossInPlace(scratch).normalizeInPlace().mulInPlace(width.toDouble())
                o = quad(v, o, p, scratch, scratch2, r, g, b, alpha)
            } else {
                o = hexagon(v, o, p, right, upAxis, size.toDouble(), r, g, b, alpha)
            }
            written++
        }
        for (bolt in bolts) {
            val glow = (1.0 - (time - bolt.born) / BOLT_SECONDS).coerceIn(0.0, 1.0).toFloat()
            for (k in 0 until bolt.points.size - 1) {
                val a = bodyRotation.rotate(bolt.points[k], Vec3()).subInPlace(cameraPos)
                val b = bodyRotation.rotate(bolt.points[k + 1], Vec3()).subInPlace(cameraPos)
                scratch.setTo(b).subInPlace(a).mulInPlace(0.5)
                p.setTo(a).addInPlace(scratch)
                scratch2.setTo(p).crossInPlace(scratch).normalizeInPlace().mulInPlace(4.0 + 6.0 * bolt.energy)
                o = quad(v, o, p, scratch, scratch2, 0.95f, 0.95f, 1f, glow)
                written++
            }
        }
        return v to written
    }

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

    /** A thin quad centred on [c], half-length along [along], half-width along [across], as a degenerate hexagon. */
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

    // Only the look of the smoke depends on these, so plain randomness does:
    // nothing about it has to agree between devices beyond where it drifts.
    private val random = java.util.SplittableRandom(0x5A0E)

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
        /** Floats per vertex: position, colour. */
        const val VERTEX_FLOATS = 7

        /** Ambient at or above 1 means "glows": drawn at its own colour. */
        const val EMISSIVE = 1.0f

        /** A glowing particle's light: its own colour, day or night. */
        private val GLOWING = floatArrayOf(1f, 1f, 1f)

        const val CONTRAIL_ALTITUDE = 8_000.0

        /** Sparks off a straining seam come this many at a time. */
        private const val BURST = 5
        const val SPEED_OF_SOUND = 340.0

        /** Heat index 1: a strong glow. See [aero]. */
        /** Recovery temperature rise where the plasma begins to show, and where it is full, K. */
        const val GLOW_FROM = 1_400.0
        const val GLOW_FULL = 2_600.0

        /** A cone of condensed vapour, open at the back, around the craft. */
        val VAPOUR_CONE = ModelSpec.Lathe(
            listOf(listOf(0.15, 0.55), listOf(0.6, 0.25), listOf(0.92, -0.2), listOf(1.0, -0.55)),
            segments = 14,
        )

        /** The glowing cap of a re-entry shock, bulging forward. */
        val BOW_SHOCK = ModelSpec.Lathe(
            listOf(listOf(0.0, 0.6), listOf(0.55, 0.45), listOf(0.9, 0.1), listOf(1.05, -0.35), listOf(1.1, -0.8)),
            segments = 14,
        )
        /** Puffs a second from a thruster block firing flat out. */
        const val RCS_PUFFS = 45.0
        const val RAIN_RADIUS = 45.0
        const val RAIN_FALL = 9.0
        const val CLOUD_BASE = 1_200.0
        const val BOLT_SECONDS = 0.35
        const val LIGHTNING_VISIBLE = 40_000.0

        /**
         * The outer flame for [vacuum] (0 in thick air, 1 in none), in units
         * of the bell's mouth across and the flame's length along: from a
         * point far downstream back to exactly the mouth. In air a flame,
         * barely wider than the bell; as the air thins it fans out behind
         * the engine like a second, larger bell - never at the nozzle itself.
         * One of [PLUME_STEPS] shapes, so the renderer builds only a few.
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
