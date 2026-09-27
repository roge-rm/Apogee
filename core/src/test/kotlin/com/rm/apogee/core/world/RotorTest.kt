package com.rm.apogee.core.world

import com.rm.apogee.core.craft.StockCraft
import com.rm.apogee.core.part.Rotor
import com.rm.apogee.core.part.StockParts
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Rotors: helicopters and drones hover, steer, and need air. */
class RotorTest {

    private val catalog = StockParts.catalog

    @Test
    fun `the Hummingbird hovers level on stability assist, its tail holding the heading`() {
        val world = World.default(catalog)
        val heli = Aloft.spawn(world, StockCraft.hummingbird(catalog), 80.0)
        world.apply(Command.SetSas(heli.id.raw, true))
        world.apply(Command.SetThrottle(heli.id.raw, 0.68))
        var worstTilt = 0.0
        var worstSpin = 0.0
        var worstClimb = 0.0
        Aloft.run(world, 30.0) {
            worstTilt = maxOf(worstTilt, Aloft.tilt(heli))
            worstSpin = maxOf(worstSpin, heli.body.angularVelocity.length)
            worstClimb = maxOf(worstClimb, kotlin.math.abs(Aloft.climb(world, heli)))
        }
        assertTrue("tipped to $worstTilt degrees", worstTilt < 6.0)
        assertTrue("spun at $worstSpin rad/s", worstSpin < 0.1)
        // Stability assist holds its attitude, not its height, so near the hover it only drifts
        // gently up or down.
        assertTrue("climbing at up to $worstClimb m/s", worstClimb < 3.0)
    }

    @Test
    fun `a quad's rotors turn opposite ways round the frame, so their twists cancel`() {
        val world = World.default(catalog)
        val quad = Aloft.spawn(world, StockCraft.quad(catalog), 50.0)
        val spins = quad.defs.indices.filter { quad.defs[it].module<Rotor>() != null }.map { quad.rotorSpin[it] }
        assertEquals(4, spins.size)
        assertEquals(0, spins.sum())
        assertTrue(spins.zipWithNext().all { (a, b) -> a != b } || spins.sorted() == listOf(-1, -1, 1, 1))
    }

    @Test
    fun `no air, no lift`() {
        val world = World.default(catalog)
        val quad = Aloft.spawn(world, StockCraft.quad(catalog), 50.0, bodyId = "luna")
        world.apply(Command.SetThrottle(quad.id.raw, 1.0))
        Aloft.run(world, 3.0)
        assertTrue("climbing at ${Aloft.climb(world, quad)} m/s", Aloft.climb(world, quad) < -1.0)
    }

    @Test
    fun `a group switched off stops its rotors`() {
        val base = StockCraft.quad(catalog)
        val design = base.copy(parts = base.parts.map { if (catalog[it.partId]?.module<Rotor>() != null) it.copy(group = 1) else it })
        val world = World.default(catalog)
        val quad = Aloft.spawn(world, design, 50.0)
        world.apply(Command.SetThrottle(quad.id.raw, 0.8))
        Aloft.run(world, 1.0)
        val rotors = quad.defs.indices.filter { quad.defs[it].module<Rotor>() != null }
        assertTrue(rotors.all { quad.engineOutput[it] > 0.3 })
        world.apply(Command.ToggleGroup(quad.id.raw, 1))
        world.apply(Command.ToggleGroup(quad.id.raw, 1))
        Aloft.run(world, 0.5)
        assertTrue(rotors.all { quad.engineOutput[it] == 0.0 })
    }
}
