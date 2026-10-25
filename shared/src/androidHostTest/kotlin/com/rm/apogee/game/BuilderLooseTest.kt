package com.rm.apogee.game

import com.rm.apogee.core.craft.CraftStats
import com.rm.apogee.core.craft.CraftStore
import com.rm.apogee.core.craft.StockCraft
import com.rm.apogee.core.math.Vec3
import com.rm.apogee.core.part.StockParts
import com.rm.apogee.render.FrameBus
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/** Loose pieces in the assembly building: kept on the floor, and nothing launches or saves while they lie there. */
class BuilderLooseTest {
    @get:Rule val folder = TemporaryFolder()

    @Test fun `loose pieces stop launch and save, count for nothing and survive leaving`() {
        val store = CraftStore(folder.newFolder("craft"))
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val catalog = StockParts.catalog
        fun open() = BuilderSession(FrameBus(), catalog, store).also { it.start(scope) }
        try {
            val first = open()
            first.edit(StockCraft.sounder(catalog))
            assertNotNull(first.designForLaunch())
            val lower = first.builder.design.parts.indices.last()
            assertTrue(first.builder.park(lower, Vec3(4.0, 0.0, 0.0)))
            first.undo(); first.redo()
            assertTrue(first.hasLoose)
            assertNull(first.designForLaunch())
            assertEquals(BuilderSession.LOOSE_NOTE, first.launchRefusal())
            first.save()
            assertEquals(BuilderSession.LOOSE_NOTE, first.statusMessage)
            assertTrue(first.savedCraft.isEmpty())
            assertEquals(CraftStats.analyze(first.builder.design, catalog).totalMass, first.stats.totalMass, 1e-9)
            val design = first.builder.design
            val loose = first.builder.loose
            first.stop()

            val second = open()
            assertEquals(design, second.builder.design)
            assertEquals(loose, second.builder.loose)
            second.builder.removeLoose(0)
            assertFalse(second.hasLoose)
            assertNull(second.launchRefusal())
            second.stop()
        } finally { scope.cancel() }
    }

    @Test fun `undo and redo end a carry first`() {
        val store = CraftStore(folder.newFolder("craft"))
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val catalog = StockParts.catalog
        try {
            val session = BuilderSession(FrameBus(), catalog, store).also { it.start(scope) }
            session.edit(StockCraft.sounder(catalog))
            session.builder.park(session.builder.design.parts.indices.last(), Vec3(4.0, 0.0, 0.0))
            for (key in listOf(BuilderKey.UNDO, BuilderKey.REDO)) {
                session.beginCarry("decoupler-ring")
                assertNotNull(session.carryPartId)
                assertTrue(session.applyKey(key))
                assertNull("$key ends the carry", session.carryPartId)
                val after = session.builder.design
                val loose = session.builder.loose
                session.endCarry()
                assertEquals("letting go afterwards does nothing", after, session.builder.design)
                assertEquals(loose, session.builder.loose)
            }
            session.stop()
        } finally { scope.cancel() }
    }
}
