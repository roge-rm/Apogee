package com.rm.apogee.core.scenario

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The stock rocket to orbit, end to end. A failure means thrust, drag, mass, staging, crossfeed,
 * attitude or the integrator went backwards; the telemetry it prints usually says which. The
 * bounds are loose on purpose.
 */
class AscentScenarioTest {

    @Test
    fun `the stock rocket reaches a stable parking orbit`() {
        val result = AscentScenario().fly()

        if (!result.reachedOrbit) {
            // The log shows where the ascent went wrong.
            println(result.log.joinToString("\n"))
        }

        assertNull("ascent failed: ${result.failure}", result.failure)
        assertTrue("should have reached orbit", result.reachedOrbit)

        assertTrue(
            "periapsis ${result.periapsisAltitude}m must clear the 70km atmosphere",
            result.periapsisAltitude > 70_000.0,
        )
        assertTrue(
            "apoapsis ${result.apoapsisAltitude}m should be near the 100km target",
            result.apoapsisAltitude in 90_000.0..130_000.0,
        )
        assertTrue(
            "orbit should be near-circular, eccentricity was ${result.finalOrbit.eccentricity}",
            result.finalOrbit.eccentricity < 0.02,
        )
        println("propellant left ${result.propellantRemaining}")
        assertTrue(
            "should reach orbit with margin, ${result.propellantRemaining} units left",
            result.propellantRemaining > 5.0,
        )
        println("peak joint load ${"%.2f".format(result.peakStress)} under ${result.peakStressPart}")
        assertEquals("nothing should come off on the way up", 0, result.partsLost)
        assertTrue(
            "the stock rocket's joints should stay well clear of fatigue, peak ${result.peakStress} under ${result.peakStressPart}",
            result.peakStress < 0.5,
        )
    }

    /** The stock rocket makes orbit through the weather. */
    @Test
    fun `the stock rocket reaches orbit through the weather`() {
        for (intensity in listOf(com.rm.apogee.core.weather.WeatherIntensity.NORMAL, com.rm.apogee.core.weather.WeatherIntensity.WILD)) {
            val result = AscentScenario(weather = com.rm.apogee.core.weather.WeatherConfig(intensity = intensity)).fly()
            if (!result.reachedOrbit) println(result.log.joinToString("\n"))
            assertNull("ascent in $intensity weather failed: ${result.failure}", result.failure)
            assertTrue(
                "periapsis ${result.periapsisAltitude}m in $intensity weather",
                result.periapsisAltitude > 70_000.0,
            )
        }
    }

    @Test
    fun `ascent is reproducible`() {
        // The same inputs give the same flight, or nothing can be tested or replayed. Cross-device
        // determinism isn't needed.
        val first = AscentScenario().fly()
        val second = AscentScenario().fly()

        assertTrue(first.reachedOrbit && second.reachedOrbit)
        assertTrue(
            "two identical flights diverged: ${first.apoapsisAltitude} vs ${second.apoapsisAltitude}",
            kotlin.math.abs(first.apoapsisAltitude - second.apoapsisAltitude) < 1e-6,
        )
        assertTrue(
            kotlin.math.abs(first.flightTime - second.flightTime) < 1e-9,
        )
    }
}
