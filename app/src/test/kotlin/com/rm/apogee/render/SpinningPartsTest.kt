package com.rm.apogee.render

import com.rm.apogee.core.part.ModelSpec
import com.rm.apogee.core.part.StockParts
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * A propeller turns on its own shaft, wherever on its part it is: the
 * outboard's, turned about the part's origin instead, went round in a
 * circle a metre across - out of the water and back - as it spun.
 */
class SpinningPartsTest {

    @Test
    fun `a propeller stays where it is as it spins`() {
        val catalog = StockParts.catalog
        var checked = 0
        for (id in listOf("motor-outboard", "engine-prop")) {
            val def = catalog.require(id)
            fun propAt(spin: Double): com.rm.apogee.core.math.Vec3 {
                val out = ArrayList<PartModels.Leaf>()
                PartModels.expand(def, StackCaps.BOTH, PartAnim(spin = spin), out)
                return out.single { it.shape is ModelSpec.Prop }.position
            }
            val rest = propAt(0.0)
            for (k in 1..7) {
                val moved = propAt(k * 0.9).distanceTo(rest)
                assertTrue("$id's propeller moved $moved m as it spun", moved < 1e-9)
                checked++
            }
        }
        assertTrue(checked > 0)
    }
}
