package com.rm.apogee.ui.components

import com.rm.apogee.core.math.Quat
import com.rm.apogee.core.math.Vec3
import com.rm.apogee.game.FlightTelemetry
import com.rm.apogee.ui.theme.ApogeeColors
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** The flight strip shows the few numbers that matter for what the craft is doing. */
class FlightStripTest {
    private fun telemetry(
        altitude: Double = 0.0,
        agl: Double = 0.0,
        srf: Double = 0.0,
        inOrbit: Boolean = false,
        inAir: Boolean = false,
        wind: Double = 0.0,
        windFrom: Double = 0.0,
        q: Double = 0.0,
        target: String? = null,
        moonWindow: Double = Double.NaN,
        destroyed: String? = null,
    ) = FlightTelemetry(
        altitude = altitude, heightAboveGround = agl, surfaceSpeed = srf, orbitalSpeed = 2_200.0,
        apoapsisAltitude = 120_000.0, periapsisAltitude = 95_000.0, timeToApoapsis = 600.0,
        throttle = 0.0, stage = 0, inOrbit = inOrbit, craftName = "Test", dynamicPressure = q,
        rotation = Quat.identity(), up = Vec3.unitY(), prograde = null,
        inAir = inAir, windSpeed = wind, windFrom = windFrom, lunaWindow = moonWindow, moonName = if (moonWindow.isNaN()) "" else "Luna",
        targetName = target, targetDistance = 1_500.0, destroyed = destroyed,
    )

    private fun labels(t: FlightTelemetry) = stripFields(t).map { it.label }

    @Test
    fun `on the ground it's speed and heading, and the moon's window`() {
        assertEquals(listOf("SRF", "HDG"), labels(telemetry()))
        assertEquals(listOf("SRF", "HDG", "LUNA"), labels(telemetry(moonWindow = 900.0)))
    }

    @Test
    fun `low in the air it's height over the ground, climb and airspeed, and a strong wind`() {
        assertEquals(listOf("AGL", "VS", "AIR"), labels(telemetry(altitude = 1_500.0, agl = 800.0, srf = 90.0, inAir = true)))
        assertEquals(listOf("ALT", "VS", "AIR"), labels(telemetry(altitude = 6_000.0, agl = 5_000.0, srf = 200.0, inAir = true)))
        assertEquals(listOf("ALT", "VS", "AIR", "WIND"), labels(telemetry(altitude = 6_000.0, agl = 5_000.0, srf = 200.0, inAir = true, wind = 22.0)))
        // Low over an airless world, so speed over the ground.
        assertEquals(listOf("AGL", "VS", "SRF"), labels(telemetry(altitude = 900.0, agl = 900.0, srf = 40.0)))
    }

    @Test
    fun `under sail the wind always shows, with the way it blows`() {
        val afloat = stripFields(telemetry(srf = 1.5, inAir = true, wind = 7.0, windFrom = 0.0), sailing = true)
        assertEquals(listOf("SRF", "WIND", "HDG"), afloat.map { it.label })
        // From dead ahead, it blows toward you.
        assertEquals("7 \u2193", afloat[1].value)
        // From the left, it blows to the right.
        assertEquals("7 \u2192", stripFields(telemetry(srf = 1.5, inAir = true, wind = 7.0, windFrom = 270.0), sailing = true)[1].value)
        // Not sailing, a light wind doesn't show.
        assertEquals(listOf("SRF", "HDG"), labels(telemetry(srf = 1.5, inAir = true, wind = 7.0)))
    }

    @Test
    fun `out of the air it's the orbit`() {
        assertEquals(listOf("AP", "PE", "T-AP", "ORB"), labels(telemetry(altitude = 100_000.0, agl = 100_000.0, srf = 2_000.0, inOrbit = true)))
    }

    @Test
    fun `a target takes the last place, and a dangerous load of air comes first`() {
        val orbit = labels(telemetry(altitude = 100_000.0, agl = 100_000.0, srf = 2_000.0, inOrbit = true, target = "Station"))
        assertEquals(listOf("AP", "PE", "T-AP", "DST"), orbit)
        val climbing = stripFields(telemetry(altitude = 9_000.0, agl = 8_000.0, srf = 400.0, inAir = true, q = 40_000.0))
        assertEquals("Q", climbing.first().label)
        assertEquals(ApogeeColors.Danger, climbing.first().colour)
    }

    @Test
    fun `nothing for a craft that's gone`() {
        assertTrue(stripFields(telemetry(destroyed = "Crashed")).isEmpty())
    }
}
