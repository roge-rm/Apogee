package com.rm.apogee.game

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Surf rides the waves: white along a breaking crest, not a fixed speckle over the whole shallows. */
class SurfFoamTest {

    @Test
    fun `a breaking crest is solid white, and the trough ahead of the next is water`() {
        assertEquals(1.0, SurfFoam.amount(1.0, 0.5, 0.2, 0.0), 1e-9)
        assertEquals(0.0, SurfFoam.amount(1.0, -0.5, 0.3, 0.0), 1e-9)
    }

    @Test
    fun `behind a crest the foam thins as the water falls away`() {
        val trail = SurfFoam.amount(1.0, -0.1, -0.4, 0.0)
        assertTrue("trail $trail", trail in 0.1..0.6)
    }

    @Test
    fun `no breaking, no foam, but the swash at the waterline is white`() {
        assertEquals(0.0, SurfFoam.amount(0.0, 0.5, 0.2, 0.0), 1e-9)
        assertEquals(0.8, SurfFoam.amount(0.0, 0.0, 0.0, 0.8), 1e-9)
    }
}
