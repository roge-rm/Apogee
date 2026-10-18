package com.rm.apogee.core.craft

import com.rm.apogee.core.part.CurveSteps
import com.rm.apogee.core.part.Engine
import com.rm.apogee.core.part.ResourceType
import com.rm.apogee.core.part.StockParts
import com.rm.apogee.core.part.Tank
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.ln

/** Flints on side clamps round a liquid core: how they're staged and what the stats make of them. */
class SolidBoosterTest {

    private val catalog = StockParts.catalog
    private val G0 = 9.80665
    private val flint = catalog.require("srb-flint")
    private val solid = flint.module<Engine>()!!
    private val ember = catalog.require("engine-ember").module<Engine>()!!

    /** Two Cask-4s and an Ember, with a Flint on a plain side clamp on each side of the top tank. */
    private fun sparkler(): CraftDesign {
        val builder = CraftBuilder(catalog)
        check(builder.placeRoot("tank-cask4"))
        fun on(parent: Int, node: String, part: String): Int {
            val target = builder.openNodes().first { it.partIndex == parent && it.node.id == node }
            return builder.attach(part, target).first()
        }
        val lower = on(0, "bottom", "tank-cask4")
        on(lower, "bottom", "engine-ember")
        builder.symmetry = SymmetryMode.MIRROR
        val clamps = builder.attach("decoupler-side", builder.openNodes().first { it.partIndex == 0 && it.node.id == "surface-0" })
        check(clamps.size == 2)
        builder.symmetry = SymmetryMode.NONE
        for (clamp in clamps) on(clamp, "mount", "srb-flint")
        builder.restage()
        return builder.design
    }

    private fun indices(design: CraftDesign, id: String) = design.parts.indices.filter { design.parts[it].partId == id }

    @Test
    fun `the Flints light with the core engine, and the clamps let go in the next stage`() {
        val design = sparkler()
        val flints = indices(design, "srb-flint")
        val clamps = indices(design, "decoupler-side")
        val engine = indices(design, "engine-ember").single()
        assertEquals(2, flints.size)
        assertEquals(2, clamps.size)
        val first = design.stages[0].activatedParts.toSet()
        assertEquals(flints.toSet() + engine, first)
        assertEquals(clamps.toSet(), design.stages[1].activatedParts.toSet())
    }

    @Test
    fun `the core keeps the fuel the Flints' burn time leaves it`() {
        val design = sparkler()
        val stats = CraftStats.analyze(design, catalog).stages
        val boost = stats[0]
        val after = stats[1]
        val solidFlow = solid.thrustVacuum / (solid.ispVacuum * G0)
        val coreFlow = ember.thrustVacuum / (ember.ispVacuum * G0)
        val solidMass = flint.module<Tank>()!!.capacity * ResourceType.SOLID_FUEL.densityPerUnit
        // Along its thrust curve.
        val burnout = CurveSteps.burn(solid, solidMass).seconds

        assertTrue(boost.solid)
        assertFalse(after.solid)
        assertEquals("burns till the Flints are out", burnout, boost.burnTime, burnout * 1e-4)
        assertEquals("burns both Flints and some of the core", 2 * solidMass + coreFlow * burnout, boost.propellantMass, 0.1)

        val tank = catalog.require("tank-cask4").module<Tank>()!!.capacity
        val coreLeft = 2 * tank - coreFlow * burnout / ResourceType.PROPELLANT.densityPerUnit
        val fuel = after.fuel.single { it.type == ResourceType.PROPELLANT }
        assertEquals("the core's left after the boost", coreLeft, fuel.amount, 0.05)
        assertEquals("then burns it dry", coreLeft / (coreFlow / ResourceType.PROPELLANT.densityPerUnit), after.burnTime, 1e-3)

        // The boost's delta-v, stepped along the Flints' curve with the core beside them.
        var mass = boost.startMass
        var left = solidMass
        var deltaV = 0.0
        val dt = 1e-3
        while (left > 0.0) {
            val flow = solidFlow * solid.thrustFactor(left / solidMass)
            val step = minOf(dt, left / flow)
            deltaV += (2 * solid.thrustVacuum * flow / solidFlow + ember.thrustVacuum) / mass * step
            left -= flow * step
            mass -= (2 * flow + coreFlow) * step
        }
        assertEquals(deltaV, boost.deltaVVacuum, deltaV * 1e-3)
        // Dropping the spent Flints and clamps.
        val dropped = 2 * (flint.dryMass + catalog.require("decoupler-side").dryMass)
        assertEquals(boost.endMass - dropped, after.startMass, 1e-6)
        assertEquals(ember.thrustVacuum / coreFlow * ln(after.startMass / after.endMass), after.deltaVVacuum, 1e-6)
        assertTrue("delta-v ${boost.deltaVVacuum} + ${after.deltaVVacuum}", boost.deltaVVacuum in 1000.0..3000.0 && after.deltaVVacuum in 500.0..3000.0)
    }

    @Test
    fun `with only a chute staged next, the core burns dry on the boost's card`() {
        val builder = CraftBuilder(catalog).also { it.load(sparkler()) }
        val chute = builder.attach("chute-side", builder.openNodes().first { it.partIndex == 1 && it.node.id.startsWith("surface") }).first()
        val design = builder.design
        val staged = design.copy(stages = listOf(
            design.stages[0],
            Stage(listOf(chute)),
            Stage(indices(design, "decoupler-side")),
        ))
        val stats = CraftStats.analyze(staged, catalog).stages
        val solidMass = flint.module<Tank>()!!.capacity * ResourceType.SOLID_FUEL.densityPerUnit
        val coreMass = 2 * catalog.require("tank-cask4").module<Tank>()!!.capacity * ResourceType.PROPELLANT.densityPerUnit
        assertEquals("both Flints and all the core", 2 * solidMass + coreMass, stats[0].propellantMass, 0.1)
        assertEquals("nothing on the chute's card", 0.0, stats[1].deltaVVacuum, 0.0)
        assertEquals("nor the clamps'", 0.0, stats[2].deltaVVacuum, 0.0)
    }

    @Test
    fun `with the Flints added it goes further than the core alone`() {
        val design = sparkler()
        val core = design.copy(parts = design.parts, stages = listOf(Stage(indices(design, "engine-ember"))))
        val alone = CraftStats.analyze(core, catalog).stages.sumOf { it.deltaVVacuum }
        val boosted = CraftStats.analyze(design, catalog).stages.sumOf { it.deltaVVacuum }
        assertTrue("$boosted against $alone", boosted > alone)
    }

    @Test
    fun `in flight, a Flint part burnt is a shorter boost`() {
        val design = sparkler()
        val vessel = Vessel(VesselId(1), design, design.parts.map { catalog.require(it.partId) }, "terra")
        vessel.restoreStaging(1, design.stages[0].activatedParts)
        val full = CraftStats.analyzeLive(vessel)
        for (index in indices(design, "srb-flint")) vessel.drainFromPart(index, ResourceType.SOLID_FUEL, 140.0)
        val half = CraftStats.analyzeLive(vessel)
        assertTrue(half[0].solid)
        val solidMass = flint.module<Tank>()!!.capacity * ResourceType.SOLID_FUEL.densityPerUnit
        assertEquals(CurveSteps.burn(solid, solidMass, from = 0.5).seconds, half[0].burnTime, 1e-3)
        assertTrue(half[0].burnTime < full[0].burnTime)
    }
}
