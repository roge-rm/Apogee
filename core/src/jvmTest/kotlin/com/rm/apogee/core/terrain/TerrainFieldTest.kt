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
 * What the rest of the game assumes about the height function: a level pad, the mesh and collider
 * on the same surface, and the same ground wherever it's worked out.
 */
class TerrainFieldTest {

    private val radius = 600_000.0
    private val home = Vec3(1.0, 0.0, 0.0)

    private fun field() = TerrainField(bodyRadius = radius, homeDirection = home)

    /** A unit direction [metres] away from [from], along some tangent. */
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

    /** Every copy of the field has to agree exactly about where the ground is. */
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

    /** Uneven ground under a rocket topples it or keeps the contact solver from settling. */
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

    /** The pad is a blend toward one height. A blend that doesn't close leaves a cliff at its edge. */
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
     * Hills go on after the shaping curve, so they could dig land below the waterline and leave
     * ponds. The fade that stops it only works while it's wider than the hills are tall.
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

            // The surface is never below the datum. The renderer clamps its water to it.
            assertTrue(f.surfaceRadius(d) >= radius)
        }
        val oceanFraction = ocean.toDouble() / (land + ocean)
        assertTrue(
            "ocean fraction should be recognisably a planet, was $oceanFraction",
            oceanFraction in 0.45..0.75,
        )
    }

    /**
     * The mesh comes from [TerrainField.surfaceRadius] and the collider asks
     * [com.rm.apogee.core.orbit.CelestialBody]. They have to agree.
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
