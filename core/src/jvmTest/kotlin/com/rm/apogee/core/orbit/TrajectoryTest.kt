package com.rm.apogee.core.orbit

import com.rm.apogee.core.craft.StockCraft
import com.rm.apogee.core.math.Quat
import com.rm.apogee.core.math.Vec3
import com.rm.apogee.core.part.StockParts
import com.rm.apogee.core.world.World
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.PI
import kotlin.math.sqrt

class TrajectoryTest {
    private val system = SolarSystem.defaultSystem()
    private val terra = system.body("terra")
    private val luna = system.body("luna")

    /**
     * A craft in a 100 km orbit round Terra in Luna's plane, just given a Hohmann transfer to meet
     * Luna, its high point [wide] metres past Luna's orbit. Also returns the transfer time.
     */
    private fun transfer(t0: Double, wide: Double = 0.0): Triple<Vec3, Vec3, Double> {
        val r1 = terra.radius + 100_000.0
        val r2 = luna.orbit!!.semiMajorAxis + wide
        val mu = terra.gravitationalParameter
        val a = 0.5 * (r1 + r2)
        val flight = PI * sqrt(a * a * a / mu)
        val normal = luna.orbit!!.angularMomentum.normalized()
        val arrival = luna.orbit!!.stateAt(t0 + flight).position.normalized()
        val position = arrival.copy().negateInPlace().mulInPlace(r1)
        val along = normal.cross(position).normalizeInPlace()
        val velocity = along.mulInPlace(sqrt(mu * (2.0 / r1 - 1.0 / a)))
        return Triple(position, velocity, flight)
    }

    @Test
    fun `a Hohmann transfer to Luna meets it and carries on around it`() {
        val t0 = 1_000.0
        val (p, v, flight) = transfer(t0)
        val path = Trajectory.predict(system, "terra", p, v, t0)
        val first = path.segments.first()
        assertEquals(Trajectory.Ending.ENCOUNTER, first.ending)
        assertEquals("luna", first.nextBodyId)
        // Into Luna's reach well before the high point: the reach is a fifth of the orbit's size
        // and the craft is slow up there.
        assertTrue("met at ${first.end - t0} s of $flight", first.end - t0 in (0.6 * flight)..flight)
        val second = path.segments[1]
        assertEquals("luna", second.bodyId)
        assertTrue("entered Luna's pull outside its reach", second.orbit.position.length <= luna.sphereOfInfluence + 1.0)
    }

    @Test
    fun `aimed a little wide of Luna it passes it at a height`() {
        val t0 = 1_000.0
        val (p, v, _) = transfer(t0, wide = 600_000.0)
        val path = Trajectory.predict(system, "terra", p, v, t0)
        val about = path.about("luna")
        assertTrue("never reached Luna", about != null)
        val height = about!!.orbit.periapsis - luna.radius
        assertTrue("passed Luna at $height m", height in 50_000.0..2_000_000.0)
    }

    @Test
    fun `an escape from Luna is predicted when the world flies it`() {
        val world = World.default(StockParts.catalog)
        val r = luna.radius + 50_000.0
        val out = Vec3(0.3, 0.1, 1.0).normalizeInPlace()
        val position = Vec3(1.0, 0.0, 0.0).mulInPlace(r)
        val velocity = out.cross(position).normalizeInPlace().mulInPlace(luna.escapeVelocityAt(r) * 1.3)
        val path = Trajectory.predict(system, "luna", position, velocity, world.time)
        val first = path.segments.first()
        assertEquals(Trajectory.Ending.ESCAPE, first.ending)
        assertEquals("terra", first.nextBodyId)

        val probe = world.spawnAt(StockCraft.probe(StockParts.catalog), "luna", position, velocity, Quat.identity())
        var t = 0.0
        while (probe.referenceBodyId == "luna" && t < 100_000.0) { world.advanceOnRails(5.0); t += 5.0 }
        assertEquals("terra", probe.referenceBodyId)
        assertEquals(first.end - first.start, t, 5.0)
    }

    @Test
    fun `a low orbit around Terra goes round and nothing ends it`() {
        val r = terra.radius + 100_000.0
        val path = Trajectory.predict(system, "terra", Vec3(r, 0.0, 0.0), Vec3(0.0, 0.0, terra.circularVelocityAt(r)), 0.0)
        assertEquals(1, path.segments.size)
        val only = path.segments.first()
        assertEquals(Trajectory.Ending.NONE, only.ending)
        assertEquals(only.orbit.period, only.end - only.start, 1e-6)
    }

    @Test
    fun `a craft falling toward Luna comes down at its datum`() {
        val r = luna.radius + 20_000.0
        val path = Trajectory.predict(system, "luna", Vec3(r, 0.0, 0.0), Vec3(-50.0, 0.0, 300.0), 0.0)
        val first = path.segments.first()
        assertEquals(Trajectory.Ending.IMPACT, first.ending)
        assertEquals(luna.radius, first.stateAt(first.end).position.length, 5.0)
    }

    @Test
    fun `closest approach finds the point it passes through`() {
        val r = terra.radius + 100_000.0
        val path = Trajectory.predict(system, "terra", Vec3(r, 0.0, 0.0), Vec3(0.0, 0.0, terra.circularVelocityAt(r)), 0.0)
        val segment = path.segments.first()
        val when_ = segment.start + 0.37 * (segment.end - segment.start)
        val mark = segment.stateAt(when_).position
        val approach = Trajectory.closestApproach(segment) { _, out -> out.setTo(mark) }
        assertTrue("missed by ${approach.distance} m", approach.distance < 1.0)
        assertEquals(when_, approach.time, 0.5)
    }

    @Test
    fun `a craft arriving on a hyperbola knows when its low point comes`() {
        val mu = luna.gravitationalParameter
        val r = 1_500_000.0
        // Falling inward, faster than escape, off to one side.
        val position = Vec3(r, 0.0, 0.0)
        val velocity = Vec3(-900.0, 0.0, 250.0)
        val orbit = Orbit(position, velocity, mu, 0.0)
        assertTrue(!orbit.isBound)
        val t = orbit.timeToPeriapsis
        assertTrue("no time to periapsis", t.isFinite() && t > 0.0)
        assertEquals(orbit.periapsis, orbit.propagate(t).position.length, 5.0)
        // And once it's past it, there's no more.
        assertTrue(Orbit(orbit.propagate(t + 100.0).position, orbit.propagate(t + 100.0).velocity, mu).timeToPeriapsis.isInfinite())
    }
}
