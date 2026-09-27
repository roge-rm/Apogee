package com.rm.apogee.core.world

import com.rm.apogee.core.craft.CraftBuilder
import com.rm.apogee.core.craft.StockCraft
import com.rm.apogee.core.craft.SymmetryMode
import com.rm.apogee.core.part.AttachNodeKind
import com.rm.apogee.core.part.Engine
import com.rm.apogee.core.part.StockParts
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Action groups: one button switches a set of parts on and off together. */
class GroupsTest {

    private val catalog = StockParts.catalog
    private val dt = 1.0 / 60.0

    @Test
    fun `a group set in the builder takes a part's symmetry partners with it`() {
        val builder = CraftBuilder(catalog)
        builder.placeRoot("pod-halo")
        val tank = builder.attach("tank-cask4", builder.openNodes().first { it.partIndex == 0 && it.node.id == "bottom" }).first()
        builder.symmetry = SymmetryMode.QUAD
        val lamps = builder.attach("wing-kite", builder.openNodes().first { it.partIndex == tank && it.kind == AttachNodeKind.SURFACE })
        assertEquals(4, lamps.size)
        assertTrue(builder.setGroup(lamps.first(), 2))
        assertTrue(lamps.all { builder.design.parts[it].group == 2 })
        // A tank isn't something a group can switch.
        assertFalse(builder.setGroup(tank, 1))
        builder.undo()
        assertTrue(lamps.all { builder.design.parts[it].group == 0 })
    }

    @Test
    fun `switched off, a group's engine stops, and the others burn on, and back on it lights`() {
        val base = StockCraft.starterRocket(catalog)
        val engines = base.parts.indices.filter { catalog[base.parts[it].partId]?.module<Engine>() != null }
        assertTrue(engines.size >= 2)
        val lower = engines.maxByOrNull { -base.parts[it].position.y }!!
        val upper = engines.first { it != lower }
        val design = base.copy(parts = base.parts.mapIndexed { i, p -> if (i == lower) p.copy(group = 1) else p })
        val world = World.default(catalog)
        val rocket = world.spawnOnSurface(design, World.launchSites.first { it.id == "cape" })
        world.apply(Command.Stage(rocket.id.raw))
        world.apply(Command.SetThrottle(rocket.id.raw, 1.0))
        repeat(30) { world.step(dt) }
        assertTrue("lit: ${rocket.engineOutput[lower]}", rocket.engineOutput[lower] > 0.5)
        // Left alone, it runs. The first press switches it on (it already is), the second off.
        world.apply(Command.ToggleGroup(rocket.id.raw, 1))
        world.apply(Command.ToggleGroup(rocket.id.raw, 1))
        repeat(10) { world.step(dt) }
        assertEquals(0.0, rocket.engineOutput[lower], 1e-9)
        // The upper stage's engine, in no group and not staged, is untouched either way.
        assertEquals(0.0, rocket.engineOutput[upper], 1e-9)
        world.apply(Command.ToggleGroup(rocket.id.raw, 1))
        repeat(10) { world.step(dt) }
        assertTrue(rocket.engineOutput[lower] > 0.5)
    }

    @Test
    fun `groups and their state survive a save`() {
        val base = StockCraft.starterRocket(catalog)
        val engine = base.parts.indices.first { catalog[base.parts[it].partId]?.module<Engine>() != null }
        val design = base.copy(parts = base.parts.mapIndexed { i, p -> if (i == engine) p.copy(group = 3) else p })
        val world = World.default(catalog)
        val rocket = world.spawnOnSurface(design, World.launchSites.first { it.id == "cape" })
        world.apply(Command.ToggleGroup(rocket.id.raw, 3))
        world.apply(Command.ToggleGroup(rocket.id.raw, 3))
        val restored = World.default(catalog)
        restored.restore(world.save())
        val again = restored.vessels.single()
        assertEquals(3, again.design.parts[engine].group)
        assertEquals(-1, again.groupStates[3])
    }
}
