package com.rm.apogee.game

import com.rm.apogee.core.craft.CraftDesign
import com.rm.apogee.core.craft.StockCraft
import com.rm.apogee.core.craft.Vessel
import com.rm.apogee.core.craft.VesselId
import com.rm.apogee.core.part.StockParts
import com.rm.apogee.core.world.Forces
import org.junit.Test
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue

/** FLAMEOUT for a lit jet, ROTOR LOW for a fuel rotor, choked by ash, with the throttle up. */
class FlameoutTest {
    private val catalog = StockParts.catalog
    private val forces = Forces()

    private fun craft(design: CraftDesign, ash: Double, throttle: Double): Vessel {
        val vessel = Vessel(id = VesselId(1), design = design, defs = design.parts.map { catalog.require(it.partId) }, referenceBodyId = "terra")
        while (vessel.activeEngines().isEmpty() && vessel.currentStage < design.stages.size) vessel.activateNextStage()
        vessel.air.ash = ash
        vessel.control.throttle = throttle
        return vessel
    }

    @Test
    fun `a jet in thick ash with throttle up flames out, not at zero throttle or in clean air`() {
        val jet = StockCraft.sparrow(catalog)
        assertTrue(GameSession.flamedOut(craft(jet, 0.7, 1.0), forces))
        assertFalse(GameSession.flamedOut(craft(jet, 0.7, 0.0), forces))
        assertFalse(GameSession.flamedOut(craft(jet, 0.0, 1.0), forces))
        assertFalse(GameSession.flamedOut(craft(jet, 0.05, 1.0), forces))
        assertFalse(GameSession.rotorLow(craft(jet, 0.7, 1.0), forces))
    }

    @Test
    fun `a helicopter chokes in thick ash with throttle up, not in thin ash or at zero throttle`() {
        val heli = StockCraft.hummingbird(catalog)
        assertTrue(GameSession.rotorLow(craft(heli, 0.9, 0.7), forces))
        assertFalse(GameSession.flamedOut(craft(heli, 0.9, 0.7), forces))
        assertFalse(GameSession.rotorLow(craft(heli, 0.9, 0.0), forces))
        assertFalse(GameSession.rotorLow(craft(heli, 0.0, 0.7), forces))
        assertFalse(GameSession.rotorLow(craft(heli, 0.2, 0.7), forces))
        assertEquals(1.0, GameSession.rotorAshSpin(0.0, forces), 1e-9)
        assertTrue(GameSession.rotorAshSpin(0.9, forces) in 0.65..0.7)
    }
}
