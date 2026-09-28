package com.rm.apogee.core.career

import com.rm.apogee.core.craft.StockCraft
import com.rm.apogee.core.part.StockParts
import com.rm.apogee.core.world.Aloft
import com.rm.apogee.core.world.Command
import com.rm.apogee.core.world.World
import org.junit.Assert.assertTrue
import org.junit.Test

/** The feats of rotors and gas, flown. */
class SkiesFeatsTest {

    private val catalog = StockParts.catalog

    private fun careerWorld(): World = World.default(catalog).also { it.program = Program() }

    private fun earned(world: World): Map<String, Int> = world.program!!.careerOf("p1").feats

    @Test
    fun `a quad hovered by hand, with no keeper, for half a minute is a Hover`() {
        val world = careerWorld()
        val quad = Aloft.spawn(world, StockCraft.quad(catalog), 40.0)
        world.program!!.launched(quad)
        world.apply(Command.SetSas(quad.id.raw, true))
        val held = Aloft.altitude(world, quad)
        var t = 0.0
        // A steady hand on the collective, and stability assist keeping it level.
        while (t < 60.0 && Feat.HOVER.id !in earned(world)) {
            val throttle = 0.68 + 0.05 * (held - Aloft.altitude(world, quad)) - 0.2 * Aloft.climb(world, quad)
            world.apply(Command.SetThrottle(quad.id.raw, throttle.coerceIn(0.0, 1.0)))
            Aloft.run(world, 0.1)
            t += 0.1
        }
        assertTrue("no Hover: ${earned(world)}", Feat.HOVER.id in earned(world))
    }

    @Test
    fun `a balloon let go at the Cape rises a kilometre on gas alone, which is Up and Away`() {
        val world = careerWorld()
        val balloon = world.spawnFor(Command.SpawnCraft(StockCraft.skylark(catalog), "cape"), "p1")
        var t = 0.0
        while (t < 600.0 && Feat.UP_AND_AWAY.id !in earned(world)) { Aloft.run(world, 1.0); t += 1.0 }
        assertTrue("no Up and Away at ${Aloft.altitude(world, balloon)} m: ${earned(world)}", Feat.UP_AND_AWAY.id in earned(world))
    }
}
