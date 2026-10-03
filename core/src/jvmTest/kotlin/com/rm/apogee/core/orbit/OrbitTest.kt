package com.rm.apogee.core.orbit

import com.rm.apogee.core.math.Vec3
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.sqrt

class OrbitTest {

    /** A homeworld-scale attractor, so the numbers are the ones the game uses. */
    private val mu = 3.5316e12
    private val bodyRadius = 600_000.0

    @Test
    fun `a circular orbit has matching apoapsis and periapsis`() {
        val r = bodyRadius + 100_000.0
        val orbit = Orbit.circular(r, mu)

        assertEquals("eccentricity", 0.0, orbit.eccentricity, 1e-12)
        assertEquals("periapsis", r, orbit.periapsis, 1e-6)
        assertEquals("apoapsis", r, orbit.apoapsis, 1e-6)
        assertTrue(orbit.isBound)
    }

    @Test
    fun `propagating a full period returns to the starting state`() {
        val r = bodyRadius + 100_000.0
        val orbit = Orbit.circular(r, mu)

        val after = orbit.propagate(orbit.period)

        assertTrue(
            "position drifted: ${after.position} vs ${orbit.position}",
            after.position.approxEquals(orbit.position, 1e-3),
        )
        assertTrue(
            "velocity drifted: ${after.velocity} vs ${orbit.velocity}",
            after.velocity.approxEquals(orbit.velocity, 1e-6),
        )
    }

    @Test
    fun `propagating forward then back is the identity`() {
        // A clearly elliptical, inclined orbit, not a symmetric special case.
        val orbit = Orbit(
            position = Vec3(800_000.0, 120_000.0, -50_000.0),
            velocity = Vec3(300.0, 180.0, 1900.0),
            mu = mu,
        )

        val dt = 1234.5
        val forward = orbit.propagate(dt)
        val back = Orbit(forward.position, forward.velocity, mu).propagate(-dt)

        assertTrue(
            "round trip lost the position: $back vs ${orbit.position}",
            back.position.approxEquals(orbit.position, 1e-3),
        )
        assertTrue(back.velocity.approxEquals(orbit.velocity, 1e-6))
    }

    @Test
    fun `energy and angular momentum are conserved over many propagations`() {
        var state = StateVector(
            Vec3(750_000.0, 0.0, 30_000.0),
            Vec3(150.0, 400.0, 2100.0),
        )
        val initial = Orbit(state.position, state.velocity, mu)

        // Step over and over instead of in one jump, so any drift per call adds up.
        repeat(200) {
            val orbit = Orbit(state.position, state.velocity, mu)
            state = orbit.propagate(60.0)
        }

        val final = Orbit(state.position, state.velocity, mu)
        assertEquals(
            "specific energy drifted",
            initial.specificEnergy,
            final.specificEnergy,
            abs(initial.specificEnergy) * 1e-9,
        )
        assertEquals(
            "angular momentum drifted",
            initial.angularMomentum.length,
            final.angularMomentum.length,
            initial.angularMomentum.length * 1e-9,
        )
    }

    @Test
    fun `period obeys Kepler's third law`() {
        val a = bodyRadius + 250_000.0
        val orbit = Orbit.circular(a, mu)
        val expected = 2.0 * PI * sqrt(a * a * a / mu)
        assertEquals(expected, orbit.period, expected * 1e-12)
    }

    @Test
    fun `an elliptical orbit reaches its computed apoapsis at half a period`() {
        // Periapsis at 100km altitude, apoapsis higher: burn prograde from circular.
        val rp = bodyRadius + 100_000.0
        val ra = bodyRadius + 500_000.0
        val a = (rp + ra) / 2.0
        // Vis-viva at periapsis.
        val vp = sqrt(mu * (2.0 / rp - 1.0 / a))

        val orbit = Orbit(Vec3(rp, 0.0, 0.0), Vec3(0.0, 0.0, -vp), mu)

        assertEquals("periapsis", rp, orbit.periapsis, 1.0)
        assertEquals("apoapsis", ra, orbit.apoapsis, 1.0)

        val atApoapsis = orbit.propagate(orbit.period / 2.0)
        assertEquals(
            "should be at apoapsis half a period later",
            ra,
            atApoapsis.position.length,
            1.0,
        )
    }

    @Test
    fun `a hyperbolic trajectory is unbound and keeps receding`() {
        val r = bodyRadius + 100_000.0
        val escapeSpeed = sqrt(2.0 * mu / r)
        // Comfortably above escape.
        val orbit = Orbit(Vec3(r, 0.0, 0.0), Vec3(0.0, 0.0, -escapeSpeed * 1.3), mu)

        assertFalse("should not be bound", orbit.isBound)
        assertTrue("eccentricity should exceed 1, was ${orbit.eccentricity}", orbit.eccentricity > 1.0)
        assertTrue(orbit.apoapsis.isInfinite())
        assertTrue(orbit.period.isInfinite())

        val near = orbit.propagate(600.0).position.length
        val far = orbit.propagate(6_000.0).position.length
        assertTrue("should be receding: $near then $far", far > near && near > r)
    }

    @Test
    fun `near-parabolic trajectories propagate without a discontinuity`() {
        // Straddle escape velocity, where the classical form switches equations.
        val r = bodyRadius + 100_000.0
        val escapeSpeed = sqrt(2.0 * mu / r)

        val justBelow = Orbit(Vec3(r, 0.0, 0.0), Vec3(0.0, 0.0, -escapeSpeed * 0.9999), mu)
        val justAbove = Orbit(Vec3(r, 0.0, 0.0), Vec3(0.0, 0.0, -escapeSpeed * 1.0001), mu)

        val a = justBelow.propagate(300.0).position
        val b = justAbove.propagate(300.0).position

        assertTrue("both must be finite: $a / $b", a.isFinite && b.isFinite)
        // A 0.02% velocity difference mustn't give a big jump in position.
        assertTrue(
            "discontinuity across escape velocity: $a vs $b",
            a.distanceTo(b) < 1_000.0,
        )
    }

    @Test
    fun `inclination is measured against the reference plane`() {
        val r = bodyRadius + 100_000.0
        val speed = sqrt(mu / r)

        val equatorial = Orbit(Vec3(r, 0.0, 0.0), Vec3(0.0, 0.0, -speed), mu)
        assertEquals("equatorial", 0.0, Math.toDegrees(equatorial.inclination), 1e-9)

        val polar = Orbit(Vec3(r, 0.0, 0.0), Vec3(0.0, speed, 0.0), mu)
        assertEquals("polar", 90.0, Math.toDegrees(polar.inclination), 1e-9)
    }

    @Test
    fun `sampling a bound orbit produces a closed path`() {
        val orbit = Orbit.circular(bodyRadius + 100_000.0, mu)
        val points = orbit.sample(64)

        assertEquals(64, points.size)
        assertTrue(
            "first and last sample should coincide on a closed orbit",
            points.first().approxEquals(points.last(), 1e-3),
        )
        // Every sample sits on the circle.
        val expectedRadius = bodyRadius + 100_000.0
        points.forEach { assertEquals(expectedRadius, it.length, 1e-3) }
    }

    @Test
    fun `the time to a direction is the time to get there`() {
        // An ellipse caught on its way up, and a hyperbola on its way in.
        val ellipse = Orbit(Vec3(bodyRadius + 90_000.0, 0.0, 0.0), Vec3(0.0, 300.0, 2_500.0), mu)
        assertEquals(ellipse.timeToApoapsis, ellipse.timeToDirection(ellipse.eccentricityVector.copy().negateInPlace()), 0.01)
        assertEquals(ellipse.timeToPeriapsis, ellipse.timeToDirection(ellipse.eccentricityVector), 0.01)
        val hyperbola = Orbit(Vec3(4.0e6, 0.0, 0.0), Vec3(-2_000.0, 0.0, 600.0), mu)
        assertEquals(hyperbola.timeToPeriapsis, hyperbola.timeToDirection(hyperbola.eccentricityVector), 0.01)
        // Any direction: it's over it then.
        val way = Vec3(-0.3, 0.2, 1.0).normalizeInPlace()
        val t = ellipse.timeToDirection(way)
        val there = ellipse.propagate(t).position
        val inPlane = way.copy().addScaledInPlace(ellipse.angularMomentum.normalized(), -(way dot ellipse.angularMomentum.normalized())).normalizeInPlace()
        assertTrue("off by ${there.normalized().distanceTo(inPlane)}", there.normalized().distanceTo(inPlane) < 1e-6)
    }

    @Test
    fun `the nodes are where it crosses the other plane, up and then down`() {
        val orbit = Orbit.circular(bodyRadius + 100_000.0, mu, inclination = 0.4).let { Orbit(it.propagate(500.0).position, it.propagate(500.0).velocity, mu) }
        val (up, down) = orbit.nodesWith(Vec3.unitY())!!
        assertEquals(orbit.period / 2.0, abs(up - down), 0.01)
        for ((t, rising) in listOf(up to true, down to false)) {
            val s = orbit.propagate(t)
            assertEquals(0.0, s.position.y, 1.0)
            assertEquals(rising, s.velocity.y > 0.0)
        }
        assertEquals(0.4, orbit.relativeInclination(Vec3.unitY()), 1e-9)
        assertTrue(Orbit.circular(bodyRadius + 100_000.0, mu).nodesWith(Vec3.unitY()) == null)
    }

    @Test
    fun `coming down through a height is timed to it, and an orbit above it never is`() {
        val orbit = Orbit(Vec3(bodyRadius + 90_000.0, 0.0, 0.0), Vec3(0.0, 0.0, 2_100.0), mu)
        val air = bodyRadius + 70_000.0
        val t = orbit.timeToRadiusDown(air)
        val s = orbit.propagate(t)
        assertEquals(air, s.position.length, 1.0)
        assertTrue((s.position dot s.velocity) < 0.0)
        assertTrue(Orbit.circular(bodyRadius + 100_000.0, mu).timeToRadiusDown(air).isNaN())
    }
}
