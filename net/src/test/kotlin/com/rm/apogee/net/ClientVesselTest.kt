package com.rm.apogee.net

import com.rm.apogee.core.craft.StockCraft
import com.rm.apogee.core.part.StockParts
import com.rm.apogee.core.math.Quat
import com.rm.apogee.core.math.Vec3
import com.rm.apogee.core.world.VesselKinematics
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ClientVesselTest {

    private fun seen(x: Double) = VesselKinematics(
        vessel = 1, referenceBodyId = "terra",
        position = Vec3(x, 0.0, 0.0), rotation = Quat(),
        velocity = Vec3(), angularVelocity = Vec3(),
    )

    private fun vessel() = ClientVessel(1, StockCraft.probe(StockParts.catalog), "probe")

    /**
     * Under warp the frame is drawn a snapshot and a half behind the newest:
     * between the two before it, not held at the older of the last two.
     */
    @Test
    fun `a time behind the newest two finds the pair around it`() {
        val v = vessel()
        for (i in 0..5) v.observe(seen(i.toDouble()), time = i * 0.2)
        val (a, b) = v.around(0.65)!!
        assertEquals(0.6, a!!.time, 1e-9)
        assertEquals(0.8, b.time, 1e-9)
    }

    @Test
    fun `past the newest it is the newest, before the oldest kept there is no older`() {
        val v = vessel()
        for (i in 0..9) v.observe(seen(i.toDouble()), time = i * 0.2)
        assertEquals(1.8, v.around(5.0)!!.second.time, 1e-9)
        val (a, b) = v.around(0.1)!!
        assertNull(a)
        assertEquals("only the last few are kept", 1.0, b.time, 1e-9)
    }
}
