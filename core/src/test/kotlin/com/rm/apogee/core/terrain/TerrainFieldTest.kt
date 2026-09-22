package com.rm.apogee.core.terrain

import com.rm.apogee.core.math.Vec3
import com.rm.apogee.core.orbit.SolarSystem
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Properties of the height function that the game depends on.
 *
 * These are not "does it look nice" tests. Each one pins a property that
 * something else assumes: that a craft can be placed on the pad, that the
 * renderer's mesh is sampling the same surface the collider is, and that two
 * machines in a multiplayer session agree about where the ground is.
 */
class TerrainFieldTest {

    private val radius = 600_000.0
    private val home = Vec3(1.0, 0.0, 0.0)

    private fun field() = TerrainField(bodyRadius = radius, homeDirection = home)

    /** A unit direction [metres] away from [from], along an arbitrary tangent. */
    private fun offset(from: Vec3, metres: Double, bearing: Double): Vec3 {
        val up = from.normalized()
        val east = (if (abs(up.y) < 0.9) Vec3.unitY() else Vec3.unitX())
            .cross(up).normalizeInPlace()
        val north = up.cross(east).normalizeInPlace()
        val step = metres / radius
        return Vec3(
            up.x + (east.x * cos(bearing) + north.x * sin(bearing)) * step,
            up.y + (east.y * cos(bearing) + north.y * sin(bearing)) * step,
            up.z + (east.z * cos(bearing) + north.z * sin(bearing)) * step,
        ).normalizeInPlace()
    }

    /**
     * Multiplayer rests on this. The server and every client evaluate the
     * field independently and must agree to the metre, or craft sink into
     * ground that is not there on the other machine.
     */
    @Test
    fun `two fields with the same seed describe the same planet`() {
        val a = field()
        val b = field()
        repeat(2_000) { i ->
            val y = 1.0 - (i / 1_999.0) * 2.0
            val r = sqrt(1.0 - y * y)
            val theta = PI * (3.0 - sqrt(5.0)) * i
            val d = Vec3(cos(theta) * r, y, sin(theta) * r)
            assertEquals(a.elevation(d), b.elevation(d), 0.0)
        }
    }

    /**
     * A rocket stands on the pad. If the ground under its footprint is not
     * level it topples, or the contact solver fights itself forever trying
     * to settle it.
     */
    @Test
    fun `the launch pad is level`() {
        val f = field()
        val centre = f.elevation(home)
        for (metres in listOf(5.0, 25.0, 100.0, 190.0)) {
            repeat(16) { i ->
                val there = f.elevation(offset(home, metres, 2.0 * PI * i / 16))
                assertEquals(
                    "ground $metres m from the pad should be level with it",
                    centre, there, 0.05,
                )
            }
        }
        val normal = f.surfaceNormal(home)
        assertTrue(
            "the pad's surface normal should be radial, was ${normal dot home}",
            (normal dot home.normalized()) > 0.9999,
        )
    }

    /**
     * The pad is levelled by blending the real field toward one height. A
     * blend that does not close leaves a cliff at its edge - invisible in a
     * screenshot, and something a rover drives off.
     */
    @Test
    fun `there is no step where the levelled pad rejoins the terrain`() {
        val f = field()
        val step = 5.0
        for (bearing in 0 until 8) {
            var previous = f.elevation(home)
            var metres = step
            while (metres <= 3_000.0) {
                val here = f.elevation(offset(home, metres, 2.0 * PI * bearing / 8))
                assertTrue(
                    "a ${abs(here - previous)} m step at $metres m out, bearing $bearing",
                    abs(here - previous) < 4.0,
                )
                previous = here
                metres += step
            }
        }
    }

    /**
     * Hills are added in metres after the shaping curve, which risks
     * subtracting a patch of land back below the waterline and speckling
     * coastlines with ponds. The fade that prevents it is only correct while
     * it is wider than the hills are tall.
     */
    @Test
    fun `hills never dig land back below the waterline`() {
        val f = field()
        var land = 0
        var ocean = 0
        val samples = 30_000
        for (i in 0 until samples) {
            val y = 1.0 - (i / (samples - 1.0)) * 2.0
            val r = sqrt(1.0 - y * y)
            val theta = PI * (3.0 - sqrt(5.0)) * i
            val d = Vec3(cos(theta) * r, y, sin(theta) * r)
            val e = f.elevation(d)
            if (e < 0.0) ocean++ else land++

            // Whatever the height, the surface a craft rests on is never
            // below the datum - the renderer clamps its water to exactly
            // this and would otherwise draw sea over dry ground.
            assertTrue(f.surfaceRadius(d) >= radius)
        }
        val oceanFraction = ocean.toDouble() / (land + ocean)
        assertTrue(
            "ocean fraction should be recognisably a planet, was $oceanFraction",
            oceanFraction in 0.45..0.75,
        )
    }

    /**
     * The renderer builds its mesh from [TerrainField.surfaceRadius] and the
     * collider asks [com.rm.apogee.core.orbit.CelestialBody] for the same
     * thing. If those two ever disagree, a craft lands on a mountain that is
     * drawn somewhere else.
     */
    @Test
    fun `the body reports the same surface the field does`() {
        val terra = SolarSystem.defaultSystem().body("terra")
        val f = terra.terrain!!
        repeat(500) { i ->
            val theta = PI * (3.0 - sqrt(5.0)) * i
            val y = 1.0 - (i / 499.0) * 2.0
            val r = sqrt(1.0 - y * y)
            val d = Vec3(cos(theta) * r, y, sin(theta) * r)
            assertEquals(
                f.surfaceRadius(d),
                terra.surfaceRadiusInBodyFrame(d),
                0.0,
            )
        }
    }
}
