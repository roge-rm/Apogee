package com.rm.apogee.core.world

import com.rm.apogee.core.craft.CraftDesign
import com.rm.apogee.core.craft.StockCraft
import com.rm.apogee.core.math.Quat
import com.rm.apogee.core.math.Vec3
import com.rm.apogee.core.math.quatFromTo
import com.rm.apogee.core.orbit.SystemData
import com.rm.apogee.core.part.StockParts
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.acos
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * A long, heavy stack that turns slowly on its wheels comes round to a held direction 75 degrees
 * away, stays on it without swinging about it, and holds it once it lights.
 */
class SasHeavyStackTest {
    private val catalog = StockParts.catalog
    private val dt = 1.0 / 60.0

    /** [design] cut down to the parts [keep], with its first [stagesDropped] stages gone. */
    private fun upperOf(design: CraftDesign, keep: List<Int>, stagesDropped: Int, name: String): CraftDesign {
        val remap = keep.withIndex().associate { (n, old) -> old to n }
        val parts = keep.map { old ->
            val p = design.parts[old]
            p.copy(parentIndex = if (p.parentIndex < 0) -1 else remap.getValue(p.parentIndex))
        }
        val stages = design.stages.drop(stagesDropped).map { s -> s.copy(activatedParts = s.activatedParts.mapNotNull { remap[it] }) }
        return design.copy(name = name, parts = parts, stages = stages)
    }

    /** The Wayfarer's crossing stage and lander, as the climb leaves them. */
    private fun wayfarerCrossing(): CraftDesign {
        val w = StockCraft.wayfarer(catalog)
        val firstDrop = w.parts.indexOfFirst { it.partId == "decoupler-broad" }
        return upperOf(w, w.parts.indices.filter { it < firstDrop }, 3, "Wayfarer Crossing")
    }

    /** The Moonshot without its first stage. */
    private fun moonshotCruiser(): CraftDesign {
        val m = StockCraft.moonshot(catalog)
        val drop = setOf("decoupler-broad", "tank-broad8", "engine-forge")
        return upperOf(m, m.parts.indices.filter { m.parts[it].partId !in drop }, 1, "Rubra Cruiser")
    }

    private class Run(val settledAt: Double, val swing: Double, val burning: Double)

    /**
     * Puts [design] in a parking orbit pointing [away] degrees off prograde, stages it [stages]
     * times and holds prograde with SAS on its wheels alone for [seconds], as auto burn turns
     * before lighting. Then it burns for [burn] seconds. Reports when it settled within [within]
     * degrees for good, the most it was off in the second half of the turn, and while burning.
     */
    private fun fly(design: CraftDesign, stages: Int, away: Double = 75.0, seconds: Double = 300.0, burn: Double = 60.0, within: Double = 2.0): Run {
        val world = World.default(catalog)
        val terra = world.system.body("terra")
        val r = terra.radius + 150_000.0
        val north = SystemData.ECLIPTIC_NORTH.normalized()
        val out = Vec3(1.0, 0.0, 0.0).let { it.subInPlace(north * (it dot north)).normalizeInPlace() }
        val along = north.cross(out).normalizeInPlace()
        val craft = world.spawnAt(design, "terra", out * r, along * sqrt(terra.gravitationalParameter / r), Quat.identity())
        // Turned to point [away] off prograde, toward the outward side.
        val a = Math.toRadians(away)
        val nose = along * cos(a) + out * sin(a)
        craft.body.orientation.setTo(quatFromTo(craft.forward(), nose))
        craft.control.pitch = 0.0; craft.control.yaw = 0.0; craft.control.roll = 0.0
        repeat(stages) { world.stage(craft) }
        craft.control.sasEnabled = true
        craft.control.sasMode = SasMode.PROGRADE
        craft.control.throttle = 0.0

        val forward = Vec3()
        fun off(): Double {
            craft.forward(forward)
            val prograde = craft.body.linearVelocity.normalized()
            return Math.toDegrees(acos((forward dot prograde).coerceIn(-1.0, 1.0)))
        }
        var t = 0.0
        var settledAt = Double.NaN
        var swing = 0.0
        while (t < seconds) {
            world.step(dt); t += dt
            val off = off()
            if (off > within) settledAt = Double.NaN
            else if (settledAt.isNaN()) settledAt = t
            // The last half, well after it should have come round.
            if (t > seconds / 2) swing = maxOf(swing, off)
        }
        craft.control.throttle = 1.0
        var burning = 0.0
        t = 0.0
        while (t < burn) {
            world.step(dt); t += dt
            burning = maxOf(burning, off())
        }
        println("${design.name}: settled within $within degrees at ${"%.1f".format(settledAt)} s, ${"%.2f".format(swing)} degrees off at most after ${seconds / 2} s, ${"%.2f".format(burning)} burning")
        return Run(settledAt, swing, burning)
    }

    @Test
    fun `the Wayfarer's crossing stage comes round to a burn without swinging`() {
        // The Shroud opens, then the crossing stage lights.
        val run = fly(wayfarerCrossing(), 2)
        assertTrue("never settled", !run.settledAt.isNaN())
        assertTrue("took ${run.settledAt} s to settle", run.settledAt < 60.0)
        assertTrue("swung ${run.swing} degrees off once settled", run.swing < 0.5)
        assertTrue("swung ${run.burning} degrees off burning", run.burning < 2.0)
    }

    @Test
    fun `the Moonshot's upper stage comes round to a burn without swinging`() {
        val run = fly(moonshotCruiser(), 2)
        assertTrue("never settled", !run.settledAt.isNaN())
        assertTrue("took ${run.settledAt} s to settle", run.settledAt < 60.0)
        assertTrue("swung ${run.swing} degrees off once settled", run.swing < 0.5)
        assertTrue("swung ${run.burning} degrees off burning", run.burning < 2.0)
    }
}
