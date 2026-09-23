package com.rm.apogee.core.world

import com.rm.apogee.core.craft.CraftDesign
import com.rm.apogee.core.craft.PlacedPart
import com.rm.apogee.core.craft.Stage
import com.rm.apogee.core.craft.StockCraft
import com.rm.apogee.core.craft.Vessel
import com.rm.apogee.core.math.Quat
import com.rm.apogee.core.math.Vec3
import com.rm.apogee.core.math.quatFromTo
import com.rm.apogee.core.part.StockParts
import com.rm.apogee.core.terrain.Noise
import com.rm.apogee.core.weather.CloudType
import com.rm.apogee.core.weather.Strike
import com.rm.apogee.core.weather.Weather
import com.rm.apogee.core.weather.WeatherConfig
import com.rm.apogee.core.weather.WeatherIntensity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.cos
import kotlin.math.sin

/** What the weather does to craft: carries them, loads them, breaks them. */
class WeatherPhysicsTest {

    private val catalog = StockParts.catalog
    private val dt = 1.0 / 60.0

    private fun world(intensity: WeatherIntensity? = WeatherIntensity.NORMAL): World =
        World.default(catalog).also { w -> w.weatherConfig = intensity?.let { WeatherConfig(intensity = it) } }

    private fun horizontal(v: Vec3, up: Vec3) = v.copy().addScaledInPlace(up, -(v dot up))

    private fun groundVelocity(world: World, vessel: Vessel): Vec3 =
        vessel.body.linearVelocity.copy().subInPlace(world.attractorFor(vessel).surfaceVelocityAt(vessel.body.position, Vec3()))

    /** A pod under a chute, hung [height] above the Cape at rest over the ground. */
    private fun podUnderChute(world: World, height: Double): Vessel {
        val design = CraftDesign(
            "Drifter",
            listOf(PlacedPart("pod-halo", Vec3.zero()), PlacedPart("chute-canopy", Vec3(0.0, 1.2, 0.0), parentIndex = 0)),
            listOf(Stage(listOf(1))),
            catalog.contentHash,
        )
        val terra = world.system.body("terra")
        val up = Vec3(1.0, 0.0, 0.0)
        val position = up.copy().mulInPlace(terra.radius + terra.terrain!!.elevation(up).coerceAtLeast(0.0) + height)
        return world.spawnAt(design, "terra", position, terra.surfaceVelocityAt(position, Vec3()), quatFromTo(Vec3.unitY(), up))
    }

    /** A chute goes where the wind goes: its drift over the ground is the wind's. */
    @Test
    fun `a parachute drifts with the wind`() {
        val world = world()
        val pod = podUnderChute(world, 2_500.0)
        world.stage(pod)
        repeat((90.0 / dt).toInt()) { world.step(dt) }
        val up = pod.body.position.copy().normalizeInPlace()
        val rotation = world.attractorFor(pod).rotationAt(world.time)
        val wind = horizontal(rotation.rotate(pod.air.wind, Vec3()), up)
        val drift = horizontal(groundVelocity(world, pod), up)
        assertTrue("need some wind to test: ${wind.length}", wind.length > 1.0)
        assertTrue(
            "drifting at $drift in a wind of $wind",
            drift.distanceTo(wind) < maxOf(1.0, 0.3 * wind.length),
        )
    }

    /** Pulled hard at speed, a surface past what it was built for fails. */
    @Test
    fun `a hard pull at speed overstresses a surface`() {
        val world = world(null)
        val design = StockCraft.sparrow(catalog)
        val terra = world.system.body("terra")
        val up = Vec3(1.0, 0.0, 0.0)
        val position = up.copy().mulInPlace(terra.radius + 1_500.0)
        // Nose east, belly down: the design's forward is +Y and its up is +Z.
        val rotation = quatFromTo(Vec3.unitZ(), up)
        val nose = rotation.rotate(Vec3.unitY(), Vec3())
        val velocity = terra.surfaceVelocityAt(position, Vec3()).addScaledInPlace(nose, 260.0)
        val jet = world.spawnAt(design, "terra", position, velocity, rotation)
        world.apply(Command.SetAttitude(jet.id.raw, 1.0, 0.0, 0.0))
        var failed = false
        repeat((6.0 / dt).toInt()) {
            world.step(dt)
            if (world.drainEvents().any { it is WorldEvent.PartFailed && it.reason.contains("under load") }) failed = true
        }
        assertTrue("a full pull at 260 m/s should break something", failed)
    }

    /** Normal weather at the Cape: the jet still takes off and climbs, whole. */
    @Test
    fun `the Sparrow flies in normal weather without breaking`() {
        val world = world()
        val jet = world.spawnOnSurface(StockCraft.sparrow(catalog), World.launchSiteFor(StockCraft.sparrow(catalog), catalog))
        world.apply(Command.Stage(jet.id.raw))
        world.apply(Command.SetThrottle(jet.id.raw, 1.0))
        world.apply(Command.SetSas(jet.id.raw, true))
        val attractor = world.attractorFor(jet)
        fun height(): Double {
            val bf = attractor.toBodyFixed(jet.body.position, attractor.rotationAt(world.time))
            return attractor.heightAboveTerrain(jet.body.position, bf)
        }
        fun noseUp(): Double {
            val nose = jet.body.orientation.rotate(Vec3.unitY(), Vec3())
            return Math.toDegrees(kotlin.math.asin((nose dot jet.body.position.copy().normalizeInPlace()).coerceIn(-1.0, 1.0)))
        }
        var phase = 0
        repeat((40.0 / dt).toInt()) {
            val speed = groundVelocity(world, jet).length
            if (phase == 0 && speed > 50.0) phase = 1
            if (phase == 1) {
                world.apply(Command.SetAttitude(jet.id.raw, ((12.0 - noseUp()) / 6.0).coerceIn(-1.0, 1.0), 0.0, 0.0))
                if (height() > 10.0) { world.apply(Command.SetAttitude(jet.id.raw, 0.0, 0.0, 0.0)); phase = 2 }
            }
            world.step(dt)
        }
        assertTrue("never got off the ground", phase == 2)
        assertTrue("climbing: ${height()} m", height() > 100.0)
        assertTrue("it broke: ${jet.defs.indices.filter { jet.isBroken(it) }}", jet.defs.indices.none { jet.isBroken(it) })
    }

    /** A storm doesn't care whether anyone is flying: a parked craft under a strike takes it. */
    @Test
    fun `lightning strikes a craft parked under a storm`() {
        val world = world(WeatherIntensity.WILD)
        val terra = world.system.body("terra")
        val weather: Weather = world.weatherFor(terra)!!
        val strikes = ArrayList<Strike>()
        var strike: Strike? = null
        for (i in 0 until 2_000) {
            val lat = (Noise.hash(3, i, 0, 0) - 0.5) * 1.2
            val lon = Noise.hash(3, i, 1, 0) * 2 * Math.PI
            val dir = Vec3(cos(lat) * cos(lon), sin(lat), cos(lat) * sin(lon))
            strikes.clear()
            weather.strikes(dir, 500.0, 3_000.0, strikes)
            strike = strikes.firstOrNull { terra.terrain!!.elevation(it.direction) > 5.0 }
            if (strike != null) break
        }
        val target = strike ?: throw AssertionError("no strike over land found in a wild sky")

        // Parked there, from the start of time, until it is asleep.
        val ground = target.direction.copy().mulInPlace(terra.radius + terra.terrain!!.elevation(target.direction) + 1.5)
        val pod = world.spawnAt(
            StockCraft.probe(catalog), "terra", ground, terra.surfaceVelocityAt(ground, Vec3()),
            quatFromTo(Vec3.unitY(), target.direction),
        )
        repeat((30.0 / dt).toInt()) { world.step(dt) }
        assertTrue("it should have parked", pod.dormant)
        world.drainEvents()

        world.syncClock(target.time - 3.0)
        val hits = ArrayList<WorldEvent.LightningHit>()
        repeat((6.0 / dt).toInt()) {
            world.step(dt)
            hits.addAll(world.drainEvents().filterIsInstance<WorldEvent.LightningHit>())
        }
        assertTrue("the strike should have hit it", hits.any { it.id == pod.id && it.strikeId == target.id })
        assertTrue("and woken it", !pod.dormant || hits.isNotEmpty())
    }

    /** A boat left alone in a wind drops anchor rather than drift off for ever. */
    @Test
    fun `a boat left alone in the wind drops anchor`() {
        val world = world()
        val design = StockCraft.skiff(catalog)
        val boat = world.spawnOnSurface(design, World.launchSiteFor(design, catalog))
        repeat((60.0 / dt).toInt()) { world.step(dt) }
        assertTrue("wind at the harbour: ${boat.air.wind.length}", boat.air.wind.length > 0.5 || boat.dormant)
        assertTrue("it never settled", boat.dormant)
    }

    /** Cloud water thickens the air: a storm tower is heavy going. */
    @Test
    fun `cloud loads the air`() {
        val world = world()
        val pod = podUnderChute(world, 1_000.0)
        pod.body.linearVelocity.addScaledInPlace(pod.body.position.copy().normalizeInPlace(), 50.0)
        val forces = Forces()
        val attractor = world.attractorFor(pod)
        val clear = forces.dynamicPressure(pod, attractor)
        pod.air.cloudDensity = 1.0
        pod.air.cloudType = CloudType.CUMULONIMBUS
        val cloudy = forces.dynamicPressure(pod, attractor)
        assertEquals(1.0 + CloudType.CUMULONIMBUS.dragLoad, cloudy / clear, 1e-9)
    }
}
