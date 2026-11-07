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

    @Test fun `dialogs keep builder keys out and release them on closing`() {
        val session = BuilderSession(FrameBus(), StockParts.catalog, CraftStore(folder.newFolder("modal")))
        session.edit(StockCraft.sounder(StockParts.catalog))
        session.selectPart("decoupler-ring")
        val before = session.builder.design
        session.keysBlocked = true
        for (key in BuilderKey.entries) assertFalse("$key", session.applyKey(key))
        assertEquals(before, session.builder.design)
        assertEquals("decoupler-ring", session.held?.partId)
        assertTrue(session.savedCraft.isEmpty())
        session.keysBlocked = false
        assertTrue(session.applyKey(BuilderKey.CANCEL))
        assertNull(session.held)
    }

    @Test fun `a ring stack end wins over nearby waist nodes but sides remain usable`() {
        val catalog = StockParts.catalog
        val bus = FrameBus()
        val session = BuilderSession(bus, catalog, CraftStore(folder.newFolder("snap")))
        session.builder.placeRoot("decoupler-ring")
        session.setViewSize(1000f, 800f)
        session.camera.distance = 18.0
        session.camera.pitch = 0.1
        BuilderSession::class.java.getDeclaredMethod("publishFrame").apply { isAccessible = true }.invoke(session)
        val frame = bus.latest()!!.latest
        val picker = MapPicking(frame.cameraPosition, frame.cameraRotation, 1000f, 800f)
        val bottom = catalog["decoupler-ring"]!!.attachNodes.first { it.id == "bottom" }
        val at = picker.project(bottom.position)!!
        session.selectPart("tank-cask2")
        session.tap(at.first, at.second - 3f, 1000f, 800f)
        assertEquals("bottom", session.builder.design.parts.last().parentNodeId)
        session.undo()
        val side = catalog["decoupler-ring"]!!.surfaceNodes.first { it.position.x > 0.0 }
        val beside = picker.project(side.position)!!
        session.selectPart("tank-cask2")
        session.tap(beside.first, beside.second, 1000f, 800f)
        assertEquals(side.id, session.builder.design.parts.last().parentNodeId)
    }

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
