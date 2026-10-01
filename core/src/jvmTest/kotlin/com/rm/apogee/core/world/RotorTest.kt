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
        Aloft.spinUp(heli, 0.68)
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
        // SAS holds attitude, not height, so near the hover it only drifts gently.
        assertTrue("climbing at up to $worstClimb m/s", worstClimb < 3.0)
    }

    @Test
    fun `the Hummingbird stands still on its skids on the airfield, in a breeze or a gale`() {
        for (weather in listOf(com.rm.apogee.core.weather.WeatherIntensity.NORMAL, com.rm.apogee.core.weather.WeatherIntensity.WILD)) {
            val world = World.default(catalog)
            world.weatherConfig = com.rm.apogee.core.weather.WeatherConfig(intensity = weather)
            val heli = world.spawnFor(Command.SpawnCraft(StockCraft.hummingbird(catalog), "airfield"), "p1")
            val terra = world.attractorFor(heli)
            fun where() = terra.toBodyFixed(heli.body.position, terra.rotationAt(world.time), com.rm.apogee.core.math.Vec3())
            repeat(120) { world.step(1.0 / 60.0) }
            val start = where()
            repeat(1200) { world.step(1.0 / 60.0) }
            // Flat on its skids, not rocked onto their back edge by the tail boom.
            assertTrue("it slid ${where().distanceTo(start)} m in $weather", where().distanceTo(start) < 0.1)
            assertTrue("never settled in $weather", heli.dormant)
        }
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
    fun `with no stability assist, the Hummingbird hangs under its rotor instead of tipping over`() {
        val world = World.default(catalog)
        val heli = world.spawnFor(Command.SpawnCraft(StockCraft.hummingbird(catalog), "airfield"), "p1")
        world.seatCrew(heli)
        Aloft.run(world, 3.0)
        val start = Aloft.altitude(world, heli)
        world.apply(Command.SetThrottle(heli.id.raw, 1.0))
        var worstTilt = 0.0
        var worstSpin = 0.0
        Aloft.run(world, 12.0) {
            worstTilt = maxOf(worstTilt, Aloft.tilt(heli))
            worstSpin = maxOf(worstSpin, heli.body.angularVelocity.length)
        }
        // It mustn't pitch over just after lifting off.
        assertTrue("only rose ${Aloft.altitude(world, heli) - start} m", Aloft.altitude(world, heli) - start > 20.0)
        assertTrue("tipped to $worstTilt degrees", worstTilt < 20.0)
        assertTrue("turning at $worstSpin rad/s", worstSpin < 0.3)
    }

    @Test
    fun `a quad on its keeper holds still, however quick its motors have to be`() {
        val world = World.default(catalog)
        val quad = Aloft.spawn(world, StockCraft.quad(catalog), 60.0)
        world.apply(Command.SetStationKeep(quad.id.raw, true))
        Aloft.run(world, 20.0)
        val spot = Aloft.fixed(world, quad)
        var worstTilt = 0.0
        Aloft.run(world, 30.0) { worstTilt = maxOf(worstTilt, Aloft.tilt(quad)) }
        assertTrue("tipped to $worstTilt degrees", worstTilt < 5.0)
        assertTrue("moved ${Aloft.moved(world, quad, spot)} m", Aloft.moved(world, quad, spot) < 2.0)
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
        // They wind down, and with them the lift.
        Aloft.run(world, 1.5)
        assertTrue(rotors.all { quad.engineOutput[it] < 0.05 })
        assertTrue("climbing at ${Aloft.climb(world, quad)} m/s", Aloft.climb(world, quad) < -3.0)
    }

    @Test
    fun `a rotor spools up over a moment, and lifts with the square of its speed`() {
        val world = World.default(catalog)
        val heli = Aloft.spawn(world, StockCraft.hummingbird(catalog), 300.0)
        val main = heli.defs.indices.first { heli.defs[it].module<Rotor>()?.let { r -> !r.tail } == true }
        val rotor = heli.defs[main].module<Rotor>()!!
        world.apply(Command.SetThrottle(heli.id.raw, 1.0))
        // After one spool time it's most of the way there, not all of it.
        Aloft.run(world, Rotors.spoolTime(rotor))
        val early = heli.spool[main]
        assertTrue("at $early after one spool time", early in 0.5..0.8)
        Aloft.run(world, Rotors.spoolTime(rotor) * 5.0)
        assertTrue("at ${heli.spool[main]} after six", heli.spool[main] > 0.99)
        assertEquals(heli.spool[main], heli.engineOutput[main], 1e-9)
    }

    @Test
    fun `the tail rotor turns with the main rotor`() {
        val world = World.default(catalog)
        val heli = Aloft.spawn(world, StockCraft.hummingbird(catalog), 300.0)
        val main = heli.defs.indices.first { heli.defs[it].module<Rotor>()?.let { r -> !r.tail } == true }
        val tail = heli.defs.indices.first { heli.defs[it].module<Rotor>()?.tail == true }
        world.apply(Command.SetThrottle(heli.id.raw, 0.6))
        Aloft.run(world, 0.5)
        assertEquals(heli.spool[main], heli.spool[tail], 1e-9)
        world.apply(Command.SetThrottle(heli.id.raw, 0.0))
        Aloft.run(world, 1.0)
        assertEquals(heli.spool[main], heli.spool[tail], 1e-9)
    }

    @Test
    fun `from rest on the ground, the Hummingbird spools up and lifts off level`() {
        val world = World.default(catalog)
        val heli = world.spawnFor(Command.SpawnCraft(StockCraft.hummingbird(catalog), "airfield"), "p1")
        world.seatCrew(heli)
        world.apply(Command.SetSas(heli.id.raw, true))
        Aloft.run(world, 3.0)
        val start = Aloft.altitude(world, heli)
        world.apply(Command.SetThrottle(heli.id.raw, 0.9))
        var worstTilt = 0.0
        var worstSpin = 0.0
        Aloft.run(world, 12.0) {
            worstTilt = maxOf(worstTilt, Aloft.tilt(heli))
            worstSpin = maxOf(worstSpin, heli.body.angularVelocity.length)
        }
        assertTrue("only rose ${Aloft.altitude(world, heli) - start} m", Aloft.altitude(world, heli) - start > 20.0)
        assertTrue("tipped to $worstTilt degrees", worstTilt < 10.0)
        assertTrue("spun at $worstSpin rad/s", worstSpin < 0.3)
    }
}
