package com.rm.apogee.core.world

import com.rm.apogee.core.craft.CraftBuilder
import com.rm.apogee.core.craft.CraftDesign
import com.rm.apogee.core.craft.CraftStats
import com.rm.apogee.core.craft.SymmetryMode
import com.rm.apogee.core.part.CurveSteps
import com.rm.apogee.core.part.Engine
import com.rm.apogee.core.part.ResourceType
import com.rm.apogee.core.part.Tank
import com.rm.apogee.core.part.StockParts
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.acos

/**
 * Two solids on side clamps round a liquid core go straight up on SAS, along their thrust curves,
 * and push the core on to their burnout once it runs dry.
 */
class TorchAscentTest {
    private val catalog = StockParts.catalog
    private val dt = 1.0 / 60.0

    /** A Halo pod on [stack] and [engine], with a [booster] on a side clamp either side of the last tank. */
    private fun design(stack: List<String>, engine: String, booster: String): CraftDesign {
        val builder = CraftBuilder(catalog)
        check(builder.placeRoot("pod-halo"))
        fun on(parent: Int, node: String, part: String): Int {
            val target = builder.openNodes().first { it.partIndex == parent && it.node.id == node }
            return builder.attach(part, target).first()
        }
        var tank = 0
        for (part in stack) tank = on(tank, "bottom", part)
        on(tank, "bottom", engine)
        builder.symmetry = SymmetryMode.MIRROR
        val clamps = builder.attach("decoupler-side", builder.openNodes().first { it.partIndex == tank && it.node.id == "surface-0" })
        check(clamps.size == 2)
        builder.symmetry = SymmetryMode.NONE
        for (clamp in clamps) on(clamp, "mount", booster)
        builder.restage()
        return builder.design.copy(name = "Two ${catalog.require(booster).title}s")
    }

    /**
     * Flown straight up on SAS at full throttle. The core runs dry before the solids do, and from
     * then the clamps carry it until the solids burn out.
     */
    private fun fly(design: CraftDesign, booster: String, minAltitude: Double) {
        assertTrue("won't validate: ${design.validate(catalog)}", design.validate(catalog).isEmpty())
        val stats = CraftStats.analyze(design, catalog)
        println("${design.name}: ${stats.totalMass.toInt()} kg, TWR ${"%.2f".format(stats.liftoffTwr)}, boost ${"%.1f".format(stats.stages[0].burnTime)} s")
        assertTrue(stats.stages[0].solid)

        val world = World.default(catalog)
        val craft = world.spawnOnSurface(design, World.launchSites.first { it.id == "cape" })
        val terra = world.system.body("terra")
        val solids = design.parts.indices.filter { design.parts[it].partId == booster }
        val clamps = design.parts.indices.filter { design.parts[it].partId == "decoupler-side" }
        val def = catalog.require(booster)
        val fullKg = def.module<Tank>()!!.capacity * ResourceType.SOLID_FUEL.densityPerUnit
        world.stage(craft)
        world.apply(Command.SetSas(craft.id.raw, true))
        world.apply(Command.SetThrottle(craft.id.raw, 1.0))
        val forward = com.rm.apogee.core.math.Vec3()
        var worst = 0.0
        var strain = 0.0
        var clampStrain = 0.0
        var t = 0.0
        var coreOut = -1.0
        var coreAltitude = 0.0
        var left = emptyList<Double>()
        while (t < 120.0 && craft.amountOf(ResourceType.SOLID_FUEL) > 0.0) {
            world.step(dt); t += dt
            craft.forward(forward)
            val up = craft.body.position.normalized()
            val off = Math.toDegrees(acos((forward dot up).coerceIn(-1.0, 1.0)))
            worst = maxOf(worst, off)
            strain = maxOf(strain, craft.stress)
            if (craft.design.parts.size == design.parts.size) {
                for (c in clamps) clampStrain = maxOf(clampStrain, craft.jointLoad[c].toDouble())
                for (s in solids) clampStrain = maxOf(clampStrain, craft.jointLoad[s].toDouble())
            }
            if (coreOut < 0.0 && craft.amountOf(ResourceType.PROPELLANT) <= 0.0) {
                coreOut = t
                coreAltitude = terra.altitudeOf(craft.body.position)
                left = solids.map { craft.amountInPart(it, ResourceType.SOLID_FUEL) * ResourceType.SOLID_FUEL.densityPerUnit }
            }
        }
        val altitude = terra.altitudeOf(craft.body.position)
        println("${design.name}: core out at ${"%.1f".format(coreOut)} s, ${coreAltitude.toInt()} m, solids out at ${"%.1f".format(t)} s, ${altitude.toInt()} m, " +
            "${"%.2f".format(worst)} degrees off at most, joints at ${"%.2f".format(strain)} of their strength, clamps at ${"%.3f".format(clampStrain)}")
        assertTrue("the core ran dry first", coreOut in 0.0..t)
        assertEquals("still in one piece", design.parts.size, craft.design.parts.size)
        assertFalse("broke on the way up", craft.broken.any { it })
        assertTrue("swung $worst degrees off straight up", worst < 3.0)
        assertTrue("joints at $strain of their strength", strain < 0.7)
        assertTrue("only $altitude m up", altitude > minAltitude)
        // In the air the thrust is less but the flow the same, so the fuel follows the curve.
        val expected = fullKg - CurveSteps.burn(def.module<Engine>()!!, fullKg, limit = coreOut).burntKg
        for (kg in left) assertEquals("burnt along the curve", expected, kg, fullKg * 0.01)
        assertEquals("burnt to empty along the curve", CurveSteps.burn(def.module<Engine>()!!, fullKg).seconds, t, 0.1)
    }

    @Test
    fun `two Torches push a Cask-4 core on past its burnout`() =
        fly(design(listOf("tank-cask4"), "engine-ember", "srb-torch"), "srb-torch", 10_000.0)

    @Test
    fun `two Pyres push a Broad Cask-8 core on past its burnout`() =
        fly(design(listOf("adapter-taper", "tank-broad8"), "engine-forge", "srb-pyre"), "srb-pyre", 10_000.0)
}
