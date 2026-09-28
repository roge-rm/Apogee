package com.rm.apogee.core.orbit

import com.rm.apogee.core.math.Vec3
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class LaunchWindowsTest {

    private val system = SolarSystem.defaultSystem()
    private val terra = system.body("terra")
    private val luna = system.body("luna")
    private val cape = SolarSystem.surfaceDirection(SolarSystem.PAD_LATITUDE, SolarSystem.PAD_LONGITUDE)

    /**
     * Degrees between Luna's orbital plane and the plane of a launch due east from the Cape at
     * [time].
     */
    private fun planeError(time: Double): Double {
        val p = terra.rotationAt(time).rotate(cape, Vec3())
        val east = Vec3(0.0, 1.0, 0.0).crossInPlace(p).normalizeInPlace()
        val launched = p.cross(east).normalizeInPlace()
        val moon = luna.orbit!!.angularMomentum.normalized()
        return Math.toDegrees(kotlin.math.acos((launched dot moon).coerceIn(-1.0, 1.0)))
    }

    @Test
    fun `Luna's orbit is tilted by the Cape's latitude`() {
        assertTrue(kotlin.math.abs(luna.orbit!!.inclination - SolarSystem.PAD_LATITUDE) < 1e-9)
    }

    @Test
    fun `a launch due east in the window goes into Luna's plane, and out of it doesn't`() {
        for (from in listOf(0.0, 5_000.0, 123_456.0)) {
            val window = LaunchWindows.next(terra, cape, luna, from)
            assertNotNull(window)
            assertTrue("the window is ahead: $window from $from", window!! >= from && window < from + terra.rotationPeriod)
            assertTrue("in the window: ${planeError(window)} degrees out", planeError(window) < 0.1)
            assertTrue("half a day off: ${planeError(window + terra.rotationPeriod / 2)} degrees out", planeError(window + terra.rotationPeriod / 2) > 10.0)
            // Once a day.
            val again = LaunchWindows.next(terra, cape, luna, window + 60.0)!!
            assertTrue("a day later: ${again - window}", kotlin.math.abs(again - window - terra.rotationPeriod) < 60.0)
        }
    }
}
