package com.rm.apogee.render

import com.rm.apogee.core.math.Vec3
import org.junit.Assert.*
import org.junit.Test

class TsunamiFloodTest {
    @Test fun `levels use the supplied grid width on both detail sizes`() {
        for (side in listOf(128, 256, 512)) {
            val texels = ByteArray(2 * side * side)
            TsunamiFlood.put(texels, side + 3, 3.7)
            TsunamiFlood.put(texels, side * side - 1, 6.6)
            val flood = TsunamiFlood(Vec3.unitY(), Vec3.unitX(), Vec3.unitZ(), 100.0, texels, side)
            assertEquals(3.7, flood.level(3, 1), 0.0005)
            assertEquals(6.6, flood.level(side - 1, side - 1), 0.0005)
            assertEquals(0.0, flood.level(4, 1), 0.0)
        }
    }

    @Test fun `patch bounds include wet neighbours and exclude dry regions`() {
        for (side in listOf(128, 256, 512)) {
            val texels = ByteArray(2 * side * side)
            TsunamiFlood.put(texels, (side / 2) * side + side / 2, 3.7)
            val flood = TsunamiFlood(Vec3.unitY(), Vec3.unitX(), Vec3.unitZ(), 100.0, texels, side)
            fun at(x: Double, y: Double) = Vec3(x / 600_000.0, 1.0, y / 600_000.0)
            assertTrue(flood.touches(at(50.0, 50.0), 0.0, 600_000.0))
            assertTrue(flood.touches(at(150.0, 50.0), 0.0, 600_000.0))
            assertTrue(flood.touches(at(1_000.0, 50.0), 1_000.0, 600_000.0))
            assertFalse(flood.touches(at(2_000.0, 2_000.0), 100.0, 600_000.0))
            assertFalse(flood.touches(at(100_000.0, 0.0), 100.0, 600_000.0))
            assertFalse(flood.touches(at(-100_000.0, 0.0), 100.0, 600_000.0))
            assertTrue(flood.touches(at(0.0, 0.0), 100_000.0, 600_000.0))
        }
    }

}
