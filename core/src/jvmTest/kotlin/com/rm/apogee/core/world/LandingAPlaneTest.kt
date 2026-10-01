package com.rm.apogee.core.world

import com.rm.apogee.core.craft.StockCraft
import com.rm.apogee.core.craft.Vessel
import com.rm.apogee.core.math.Quat
import com.rm.apogee.core.math.Vec3
import com.rm.apogee.core.math.quatFromTo
import com.rm.apogee.core.part.StockParts
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Landing an aeroplane: power off over the runway, SAS holding a gentle nose-up attitude, then
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
        /** Ticks in the last twenty seconds, parked, with no wheel down. */
        val hopTicks: Int,
        /** Nose up and bank at rest, in degrees. */
        val nose: Double = 0.0,
        val bank: Double = 0.0,
    )

    private fun land(
        approachSpeed: Double = 45.0,
        height: Double = 40.0,
        noseUpDegrees: Double = 6.0,
        weather: com.rm.apogee.core.weather.WeatherIntensity? = null,
        design: com.rm.apogee.core.craft.CraftDesign = StockCraft.aeroplane(catalog),
    ): Outcome {
        val world = World.default(catalog)
        world.weatherConfig = weather?.let { com.rm.apogee.core.weather.WeatherConfig(intensity = it) }
        val terra = world.system.body("terra")!!

        // Over the west end of the runway, which runs east 400 m south of the pads.
        val pad = com.rm.apogee.core.orbit.SolarSystem.capeDirection(260.0, -400.0)
        val up = pad.copy()
        val position = Vec3().setTo(up).mulInPlace(terra.surfaceRadiusInBodyFrame(up) + height)
        val surface = terra.surfaceVelocityAt(position, Vec3())
        val east = surface.copy().normalizeInPlace()
        val right = east.copy().crossInPlace(up).normalizeInPlace()

        // Sky side up, nose east, then pitched up about the wing.
        val rotation = quatFromTo(Vec3(0.0, 0.0, 1.0), up)
        val nose = rotation.rotate(Vec3(0.0, 1.0, 0.0))
        rotation.setTo(quatFromTo(nose, east) * rotation)
        rotation.setTo(Quat.fromAxisAngle(right, Math.toRadians(noseUpDegrees)) * rotation)
        check((rotation.rotate(Vec3(0.0, 1.0, 0.0)) dot up) > 0.0) { "pitched the wrong way" }

        val velocity = surface.copy().addScaledInPlace(east, approachSpeed)
        val plane = world.spawnAt(design, "terra", position, velocity, rotation)
        plane.control.sasEnabled = true

        var touchdownSpeed = -1.0
        var touchdownTime = -1.0
        // Integrated over the ground, since inertial positions include the runway's 175 m/s.
        var rollout = 0.0
        // Where it was five seconds before the end. Stopped is judged by distance since then,
        // since a parked aircraft rocks on its gear in a wind.
        var settling: Vec3? = null
        var hopTicks = 0
        var t = 0.0
        while (t < 90.0) {
            if (settling == null && t >= 85.0) settling = bodyFixed(world, plane)
            world.step(dt)
            t += dt
            if (touchdownSpeed < 0.0 && plane.touchingGround) {
                touchdownSpeed = groundSpeed(world, plane)
                touchdownTime = t
            }
            // Brakes once all the wheels are down. Braking on first contact with one main wheel
            // ground-loops it.
            if (touchdownTime >= 0.0 && t > touchdownTime + 2.0) plane.control.brakes = true
            if (touchdownSpeed >= 0.0) rollout += groundSpeed(world, plane) * dt
            if (t > 70.0 && !plane.touchingGround) hopTicks++
        }
        val upNow = plane.body.position.copy().normalizeInPlace()
        // How far off the centreline it came to rest.
        val restAt = world.attractorFor(plane).toBodyFixed(
            plane.body.position, world.attractorFor(plane).rotationAt(world.time),
        ).normalizeInPlace()
        val along = Vec3(0.0, 1.0, 0.0).crossInPlace(pad).normalizeInPlace()
        val across = pad.copy().crossInPlace(along)
        val offCentre = kotlin.math.abs(restAt.copy().subInPlace(pad) dot across) * 600_000.0
        val deck = plane.body.orientation.rotate(Vec3(0.0, 0.0, 1.0))
        return Outcome(
            touchdownSpeed = touchdownSpeed,
            rolloutMetres = rollout,
            finalSpeed = bodyFixed(world, plane).distanceTo(settling!!) / 5.0,
            broken = plane.broken.count { it },
            tilt = Math.toDegrees(kotlin.math.acos((deck dot upNow).coerceIn(-1.0, 1.0))),
            onRunway = world.attractorFor(plane).altitudeOf(plane.body.position) < 1_000.0,
            offCentre = offCentre,
            hopTicks = hopTicks,
            nose = Math.toDegrees(kotlin.math.asin((plane.body.orientation.rotate(Vec3(0.0, 1.0, 0.0)) dot upNow).coerceIn(-1.0, 1.0))),
            bank = Math.toDegrees(kotlin.math.asin((plane.body.orientation.rotate(Vec3(1.0, 0.0, 0.0)) dot upNow).coerceIn(-1.0, 1.0))),
        )
    }

    private fun bodyFixed(world: World, vessel: Vessel): Vec3 {
        val terra = world.attractorFor(vessel)
        return terra.toBodyFixed(vessel.body.position, terra.rotationAt(world.time))
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

    /** The same approach in the Cape's weather: down whole and stopped, maybe blown off centre. */
    @Test
    fun `it lands in the weather`() {
        for (intensity in listOf(com.rm.apogee.core.weather.WeatherIntensity.NORMAL, com.rm.apogee.core.weather.WeatherIntensity.WILD)) {
            val o = land(weather = intensity)
            println("$intensity: touchdown %.1f m/s, rollout %.0f m, final %.2f m/s, broken %d, tilt %.1f, %.0f m off centre, hops %d"
                .format(o.touchdownSpeed, o.rolloutMetres, o.finalSpeed, o.broken, o.tilt, o.offCentre, o.hopTicks))
            assertTrue("$intensity: never touched down", o.touchdownSpeed >= 0.0)
            assertTrue("$intensity: ${o.broken} parts broke", o.broken == 0)
            assertTrue("$intensity: still rolling at ${o.finalSpeed} m/s", o.finalSpeed < 0.5)
            // Parked on the grass in the wind, it just sits.
            assertEquals("$intensity: parked, it left the ground", 0, o.hopTicks)
            assertTrue("$intensity: tipped ${o.tilt} degrees", o.tilt < 10.0)
            // Nobody corrects for the sea crosswind, so it may drift beside the tarmac.
            assertTrue("$intensity: %.0f m off the centreline".format(o.offCentre), o.offCentre < 120.0)
        }
    }

    @Test
    fun `the Petrel lands slow and stops short`() {
        val o = land(approachSpeed = 38.0, height = 25.0, noseUpDegrees = 5.0, design = StockCraft.petrel(catalog))
        assertTrue("never touched down", o.touchdownSpeed >= 0.0)
        assertTrue("${o.broken} parts broke", o.broken == 0)
        // Half the Sparrow's landing speed, on brakes alone. On a ship into the wind there's
        // 20 m/s less, and the wires take the rest.
        assertTrue("touched down at ${o.touchdownSpeed} m/s", o.touchdownSpeed < 37.0)
        assertTrue("rolled ${o.rolloutMetres} m", o.rolloutMetres < 220.0)
        assertTrue("sat back on its tail, nose up ${o.nose} degrees", o.nose < 10.0)
    }
}
