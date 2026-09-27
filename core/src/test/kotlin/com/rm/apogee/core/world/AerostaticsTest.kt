package com.rm.apogee.core.world

import com.rm.apogee.core.craft.StockCraft
import com.rm.apogee.core.part.StockParts
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Gas cells: lift from the air pushed aside, a ceiling that holds itself, and the ballonets. */
class AerostaticsTest {

    private val catalog = StockParts.catalog

    @Test
    fun `a balloon lifts less the higher it goes, more on Aurantia, and nothing on Luna`() {
        val world = World.default(catalog)
        val balloon = Aloft.spawn(world, StockCraft.skylark(catalog), 100.0)
        val terra = world.system.body("terra")!!
        val weight = balloon.body.mass * 9.81
        assertTrue(Aerostatics.liftAt(balloon, terra, 0.0) > weight)
        assertTrue(Aerostatics.liftAt(balloon, terra, 1_000.0) > weight)
        assertTrue(Aerostatics.liftAt(balloon, terra, 6_000.0) < weight)
        // In Aurantia's thick cold air it lifts over three times the mass it does on Terra.
        val aurantia = world.system.body("aurantia")!!
        val gAurantia = aurantia.gravitationalParameter / (aurantia.radius * aurantia.radius)
        assertTrue(Aerostatics.liftAt(balloon, aurantia, 0.0) / gAurantia > 3.0 * Aerostatics.liftAt(balloon, terra, 0.0) / 9.81)
        assertEquals(0.0, Aerostatics.liftAt(balloon, world.system.body("luna")!!, 0.0), 1e-9)
    }

    @Test
    fun `let go, a light balloon rises, and full ballonets bring it down`() {
        val world = World.default(catalog)
        val balloon = Aloft.spawn(world, StockCraft.skylark(catalog), 100.0)
        Aloft.run(world, 30.0)
        assertTrue("climbing at ${Aloft.climb(world, balloon)} m/s", Aloft.climb(world, balloon) > 1.0)
        world.apply(Command.SetBallast(balloon.id.raw, 1))
        Aloft.run(world, 60.0)
        world.apply(Command.SetBallast(balloon.id.raw, 0))
        assertTrue("ballonet ${balloon.ballonet}", balloon.ballonet > 0.9)
        Aloft.run(world, 60.0)
        assertTrue("climbing at ${Aloft.climb(world, balloon)} m/s with full ballonets", Aloft.climb(world, balloon) < 0.0)
    }

    @Test
    fun `HOLD holds a height with the ballonets`() {
        val world = World.default(catalog)
        val balloon = Aloft.spawn(world, StockCraft.skylark(catalog), 800.0)
        Aloft.run(world, 20.0)
        world.apply(Command.HoldDepth(balloon.id.raw, true))
        val held = Aloft.altitude(world, balloon)
        Aloft.run(world, 180.0)
        var worst = 0.0
        Aloft.run(world, 120.0) { worst = maxOf(worst, kotlin.math.abs(Aloft.altitude(world, balloon) - held)) }
        assertTrue("strayed $worst m from $held", worst < 40.0)
    }
}
