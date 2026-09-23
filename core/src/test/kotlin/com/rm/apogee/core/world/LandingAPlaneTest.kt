package com.rm.apogee.core.world

import com.rm.apogee.core.craft.StockCraft
import com.rm.apogee.core.craft.Vessel
import com.rm.apogee.core.math.Quat
import com.rm.apogee.core.math.Vec3
import com.rm.apogee.core.math.quatFromTo
import com.rm.apogee.core.part.StockParts
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Bringing an aeroplane back down: on approach over the runway, power off,
 * SAS holding a gentle nose-up attitude so it settles rather than dives - then
 * brakes once the wheels are on.
 */
class LandingAPlaneTest {

    private val catalog = StockParts.catalog
    private val dt = 1.0 / 60.0

    class Outcome(
        val touchdownSpeed: Double,
        val rolloutMetres: Double,
        val finalSpeed: Double,
        val broken: Int,
        val tilt: Double,
        val onRunway: Boolean,
        val offCentre: Double,
    )

    private fun land(
        approachSpeed: Double = 95.0,
        height: Double = 40.0,
        noseUpDegrees: Double = 6.0,
        weather: com.rm.apogee.core.weather.WeatherIntensity? = null,
    ): Outcome {
        val world = World.default(catalog)
        world.weatherConfig = weather?.let { com.rm.apogee.core.weather.WeatherConfig(intensity = it) }
        val terra = world.system.body("terra")!!

        // Over the pad end of the runway, which runs east from the Cape.
        val up = Vec3(1.0, 0.0, 0.0)
        val position = Vec3().setTo(up).mulInPlace(terra.surfaceRadiusInBodyFrame(up) + height)
        val surface = terra.surfaceVelocityAt(position, Vec3())
        val east = surface.copy().normalizeInPlace()
        val right = east.copy().crossInPlace(up).normalizeInPlace()

        // Sky-side up, nose east, then pitched up about the wing.
        val rotation = quatFromTo(Vec3(0.0, 0.0, 1.0), up)
        val nose = rotation.rotate(Vec3(0.0, 1.0, 0.0))
        rotation.setTo(quatFromTo(nose, east) * rotation)
        rotation.setTo(Quat.fromAxisAngle(right, Math.toRadians(noseUpDegrees)) * rotation)
        check((rotation.rotate(Vec3(0.0, 1.0, 0.0)) dot up) > 0.0) { "pitched the wrong way" }

        val velocity = surface.copy().addScaledInPlace(east, approachSpeed)
        val plane = world.spawnAt(StockCraft.aeroplane(catalog), "terra", position, velocity, rotation)
        plane.control.sasEnabled = true

        var touchdownSpeed = -1.0
        var touchdownTime = -1.0
        // Along the ground, integrated: positions are inertial, and the
        // runway itself moves 175 m/s, so a straight-line difference between
        // two of them measures the planet's spin.
        var rollout = 0.0
        var t = 0.0
        while (t < 90.0) {
            world.step(dt)
            t += dt
            if (touchdownSpeed < 0.0 && plane.touchingGround) {
                touchdownSpeed = groundSpeed(world, plane)
                touchdownTime = t
            }
            // Brakes once the wheels are all down, as a pilot would. Braking
            // on first contact - one main wheel, briefly, before the rest -
            // yanked the nose seventeen degrees round and the aircraft
            // ground-looped off the runway.
            if (touchdownTime >= 0.0 && t > touchdownTime + 2.0) plane.control.brakes = true
            if (touchdownSpeed >= 0.0) rollout += groundSpeed(world, plane) * dt
        }
        val upNow = plane.body.position.copy().normalizeInPlace()
        // How far off the runway's centreline it came to rest. The runway
        // runs east from the Cape, which sits at +X, so east is -Z and the
        // centreline is the equator.
        val restAt = world.attractorFor(plane).toBodyFixed(
            plane.body.position, world.attractorFor(plane).rotationAt(world.time),
        ).normalizeInPlace()
        val offCentre = kotlin.math.abs(restAt.y) * 600_000.0
        val deck = plane.body.orientation.rotate(Vec3(0.0, 0.0, 1.0))
        return Outcome(
            touchdownSpeed = touchdownSpeed,
            rolloutMetres = rollout,
            finalSpeed = groundSpeed(world, plane),
            broken = plane.broken.count { it },
            tilt = Math.toDegrees(kotlin.math.acos((deck dot upNow).coerceIn(-1.0, 1.0))),
            onRunway = world.attractorFor(plane).altitudeOf(plane.body.position) < 1_000.0,
            offCentre = offCentre,
        )
    }

    private fun groundSpeed(world: World, vessel: Vessel): Double {
        val surface = world.attractorFor(vessel).surfaceVelocityAt(vessel.body.position, Vec3())
        return vessel.body.linearVelocity.copy().subInPlace(surface).length
    }

    @Test
    fun `it lands on its gear and brakes to a stop`() {
        val o = land()
        println("touchdown %.1f m/s, rollout %.0f m, final %.2f m/s, broken %d, tilt %.1f, %.0f m off centre"
            .format(o.touchdownSpeed, o.rolloutMetres, o.finalSpeed, o.broken, o.tilt, o.offCentre))
        assertTrue("never touched down", o.touchdownSpeed >= 0.0)
        assertTrue("${o.broken} parts broke on landing", o.broken == 0)
        assertTrue("still rolling at ${o.finalSpeed} m/s", o.finalSpeed < 0.5)
        assertTrue("rolled ${o.rolloutMetres} m - off the end of the runway", o.rolloutMetres < 2_000.0)
        assertTrue("ended up tipped ${o.tilt} degrees", o.tilt < 10.0)
        assertTrue("ran off the runway, %.0f m off the centreline".format(o.offCentre), o.offCentre < 40.0)
    }

    /**
     * The same approach through the Cape's weather: whatever wind and gusts
     * are there, it touches down on its gear, stays whole and stops on the
     * runway - blown a little way off the centreline, perhaps.
     */
    @Test
    fun `it lands in the weather`() {
        for (intensity in listOf(com.rm.apogee.core.weather.WeatherIntensity.NORMAL, com.rm.apogee.core.weather.WeatherIntensity.WILD)) {
            val o = land(weather = intensity)
            println("$intensity: touchdown %.1f m/s, rollout %.0f m, final %.2f m/s, broken %d, tilt %.1f, %.0f m off centre"
                .format(o.touchdownSpeed, o.rolloutMetres, o.finalSpeed, o.broken, o.tilt, o.offCentre))
            assertTrue("$intensity: never touched down", o.touchdownSpeed >= 0.0)
            assertTrue("$intensity: ${o.broken} parts broke", o.broken == 0)
            assertTrue("$intensity: still rolling at ${o.finalSpeed} m/s", o.finalSpeed < 0.5)
            assertTrue("$intensity: tipped ${o.tilt} degrees", o.tilt < 10.0)
            assertTrue("$intensity: %.0f m off the centreline".format(o.offCentre), o.offCentre < 80.0)
        }
    }
}
