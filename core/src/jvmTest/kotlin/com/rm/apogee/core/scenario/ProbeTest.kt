package com.rm.apogee.core.scenario

import com.rm.apogee.core.craft.StockCraft
import com.rm.apogee.core.part.ResourceType
import com.rm.apogee.core.world.Power
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** The Mote Probe: to orbit on its lifter, then its wings out and charging. */
class ProbeTest {
    private val dt = 1.0 / 60.0

    @Test
    fun `the Mote Probe makes orbit, and its wings charge it there`() {
        val ascent = AscentScenario(design = { StockCraft.moteProbe(it) }).fly()
        if (!ascent.reachedOrbit) println(ascent.log.joinToString("\n"))
        assertTrue("never made orbit: ${ascent.failure}", ascent.reachedOrbit)
        assertEquals("parts lost on the way", 0, ascent.partsLost)
        val world = ascent.world!!
        val probe = ascent.vessel!!
        assertTrue("flat on reaching orbit", probe.powered)

        // The wings are the last stage.
        while (probe.currentStage < probe.design.stages.size) world.stage(probe)
        repeat((6.0 / dt).toInt()) { world.step(dt) }
        val wings = probe.defs.indices.filter { probe.defs[it].id == "wing-kite" }
        assertEquals(2, wings.size)
        assertTrue("not out: ${wings.map { probe.legDeploy[it] }}", wings.all { Power.deployed(probe, it) })

        // Once round an orbit, it ends up with more than it started with, or full.
        probe.drawCharge(probe.amountOf(ResourceType.ELECTRIC_CHARGE) / 2)
        val had = probe.amountOf(ResourceType.ELECTRIC_CHARGE)
        var t = 0.0
        while (t < 2_200.0) { t += world.advanceOnRails(50.0).also { if (it <= 0.0) repeat(60) { world.step(dt) } }.coerceAtLeast(1.0) }
        assertTrue("charge ${probe.amountOf(ResourceType.ELECTRIC_CHARGE)} from $had", probe.amountOf(ResourceType.ELECTRIC_CHARGE) > had)
    }
}
