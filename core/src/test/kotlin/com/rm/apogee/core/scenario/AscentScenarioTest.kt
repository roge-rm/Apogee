package com.rm.apogee.core.scenario

import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The vertical slice, as a test.
 *
 * If this fails, something in thrust, drag, mass, staging, crossfeed, attitude
 * control or the integrator has regressed - and the telemetry it prints on
 * failure usually says which. It is deliberately an end-to-end assertion rather
 * than a tight numeric one: exact numbers will move as the model improves, but
 * "the stock rocket makes a stable orbit" must not.
 */
class AscentScenarioTest {

    @Test
    fun `the stock rocket reaches a stable parking orbit`() {
        val result = AscentScenario().fly()

        if (!result.reachedOrbit) {
            // Surfacing the flight log here is the point - a bare assertion
            // failure would say nothing about where the ascent went wrong.
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
        assertTrue(
            "should reach orbit with margin, ${result.propellantRemaining} units left",
            result.propellantRemaining > 5.0,
        )
    }

    @Test
    fun `ascent is reproducible`() {
        // Same inputs, same flight. Not because we rely on cross-device
        // determinism - we do not - but because a simulation whose own results
        // wander cannot be tested or replayed at all.
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
