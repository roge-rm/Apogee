package com.rm.apogee.game

import com.rm.apogee.core.craft.StockCraft
import com.rm.apogee.core.math.Quat
import com.rm.apogee.core.math.Vec3
import com.rm.apogee.core.math.quatLookAt
import com.rm.apogee.core.part.StockParts
import com.rm.apogee.core.weather.AirSample
import com.rm.apogee.core.world.VesselKinematics
import com.rm.apogee.core.world.World
import com.rm.apogee.net.ClientVessel
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * The wind arrow is read against the screen, as the windsock in view is -
 * not against the craft's nose, which the camera may be looking anywhere
 * but along (Dan: the windsock and the reading did not match).
 */
class WindReadingTest {

    private val catalog = StockParts.catalog
    private val terra = World.default(catalog).system.body("terra")!!

    /** A craft with +Y up, nose to +X; a wind from +Z blowing to -Z. */
    private fun from(view: Quat): Double {
        val at = Vec3(0.0, terra.radius + 10.0, 0.0)
        val vessel = ClientVessel(1, StockCraft.probe(catalog), "probe")
        vessel.observe(
            VesselKinematics(
                1, "terra", at, Quat.fromAxisAngle(Vec3.unitZ(), -Math.PI / 2), terra.surfaceVelocityAt(at), Vec3(),
            ),
        )
        val air = AirSample().apply { wind.setTo(0.0, 0.0, -10.0) }
        return FlightTelemetry.from(
            vessel, terra, 0.0, at, air = air, bodyRotation = Quat.identity(), viewRotation = view,
        ).windFrom
    }

    @Test
    fun `looking into the wind, it comes from straight ahead`() {
        assertEquals(0.0, from(quatLookAt(Vec3(0.0, 0.0, 1.0), Vec3.unitY())), 1.0)
    }

    @Test
    fun `looking along the nose with the wind on the right, it comes from the right`() {
        // Looking along +X with +Y up, +Z is to the right.
        assertEquals(90.0, from(quatLookAt(Vec3(1.0, 0.0, 0.0), Vec3.unitY())), 1.0)
    }

    @Test
    fun `looking downwind, it comes from behind`() {
        assertEquals(180.0, kotlin.math.abs(from(quatLookAt(Vec3(0.0, 0.0, -1.0), Vec3.unitY()))), 1.0)
    }
}
