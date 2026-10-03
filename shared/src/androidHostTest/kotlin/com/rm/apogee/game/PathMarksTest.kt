package com.rm.apogee.game

import com.rm.apogee.core.math.Vec3
import com.rm.apogee.core.orbit.Orbit
import com.rm.apogee.core.orbit.SolarSystem
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.PI
import kotlin.math.sqrt

/** The marks on the map's path: what's marked, where, and when. */
class PathMarksTest {
    private val system = SolarSystem.defaultSystem()
    private val terra = system.body("terra")
    private val luna = system.body("luna")
    private val planner = PathPlanner(system)
    private val marks = PathMarks(system)

    private fun plan(position: Vec3, velocity: Vec3, time: Double = 1_000.0, targetBody: String = "", target: Orbit? = null) =
        planner.work(
            PathPlanner.Ask(
                "terra", position, velocity, time, emptyList(), mass = 0.0, dragArea = 0.0, targetBody = targetBody,
                targetPosition = target?.position, targetVelocity = target?.velocity,
            ),
        )

    private fun kinds(list: List<PathMark>) = list.map { it.kind }

    @Test
    fun `a hop marks its high point, the air and coming down, in that order`() {
        val r = terra.radius + 90_000.0
        val p = plan(Vec3(r, 0.0, 0.0), Vec3(0.0, 400.0, 1_500.0))
        val got = marks.of(p, "terra")
        val ap = got.single { it.kind == MarkKind.AP }
        val air = got.single { it.kind == MarkKind.AIR }
        val land = got.single { it.kind == MarkKind.LAND }
        assertTrue(ap.time < air.time && air.time < land.time)
        assertEquals(terra.radius + terra.atmosphereHeight, air.at.length, 5.0)
        assertEquals(terra.atmosphereHeight, air.height, 1e-6)
    }

    @Test
    fun `an orbit above the air marks its high and low points and nothing else`() {
        val o = Orbit.circular(terra.radius + 100_000.0, terra.gravitationalParameter)
        val v = o.velocity.copy().mulInPlace(1.05)
        val got = marks.of(plan(o.position.copy(), v), "terra")
        assertEquals(setOf(MarkKind.AP, MarkKind.PE), kinds(got).toSet())
        assertEquals(100_000.0, got.single { it.kind == MarkKind.PE }.height, 10.0)
    }

    @Test
    fun `a transfer to Luna marks going in and its low point there`() {
        val t0 = 1_000.0
        val r1 = terra.radius + 100_000.0
        val r2 = luna.orbit!!.semiMajorAxis + 600_000.0
        val mu = terra.gravitationalParameter
        val a = 0.5 * (r1 + r2)
        val flight = PI * sqrt(a * a * a / mu)
        val arrival = luna.orbit!!.stateAt(t0 + flight).position.normalized()
        val position = arrival.copy().negateInPlace().mulInPlace(r1)
        val velocity = luna.orbit!!.angularMomentum.normalized().cross(position).normalizeInPlace().mulInPlace(sqrt(mu * (2.0 / r1 - 1.0 / a)))
        val got = marks.of(plan(position, velocity, t0, targetBody = "luna"), "terra")
        val enter = got.single { it.kind == MarkKind.ENTER }
        assertEquals("luna", enter.bodyId)
        // Drawn where Luna will be: at the edge of its reach.
        val there = system.positionOf("luna", enter.time).subInPlace(system.positionOf("terra", enter.time))
        assertEquals(luna.sphereOfInfluence, enter.at.distanceTo(there), luna.sphereOfInfluence * 0.01)
        val low = got.single { it.kind == MarkKind.PE && it.bodyId == "luna" }
        assertTrue(low.time > enter.time && low.height > 0.0)
        // In Luna's own plane: no nodes. Met, so no near pass either; its low point says it.
        assertTrue(kinds(got).none { it == MarkKind.AN || it == MarkKind.DN || it == MarkKind.NEAR })
    }

    @Test
    fun `a craft in another plane is crossed twice and passed near`() {
        val mu = terra.gravitationalParameter
        val mine = Orbit.circular(terra.radius + 100_000.0, mu, epoch = 1_000.0)
        val theirs = Orbit.circular(terra.radius + 120_000.0, mu, epoch = 1_000.0, inclination = 0.3)
        val got = marks.of(plan(mine.position.copy(), mine.velocity.copy(), target = theirs), "terra")
        val an = got.single { it.kind == MarkKind.AN }
        val dn = got.single { it.kind == MarkKind.DN }
        assertEquals(0.3, an.extra, 1e-6)
        assertEquals(mine.period / 2.0, kotlin.math.abs(an.time - dn.time), 1.0)
        // On their plane there.
        val normal = theirs.angularMomentum.normalized()
        assertEquals(0.0, an.at dot normal, 1.0)
        val near = got.single { it.kind == MarkKind.NEAR }
        assertTrue(near.ghost != null && near.extra >= 20_000.0 - 1.0)
    }

    @Test
    fun `an orbit with no plan marks the air when it dips in, and where it comes down`() {
        val r = terra.radius + 100_000.0
        val o = Orbit(Vec3(r, 0.0, 0.0), Vec3(0.0, 0.0, 2_000.0), terra.gravitationalParameter)
        val got = marks.ofOrbit(o, "terra", 0.0, hit = Vec3(terra.radius, 0.0, 0.0), hitTime = 900.0)
        assertEquals(listOf(MarkKind.LAND, MarkKind.AIR), kinds(got))
    }
}
