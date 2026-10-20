package com.rm.apogee.desktop

import com.rm.apogee.core.craft.StockCraft
import com.rm.apogee.core.math.Quat
import com.rm.apogee.core.math.Vec3
import com.rm.apogee.core.part.StockParts
import com.rm.apogee.core.world.World
import com.rm.apogee.game.*
import com.rm.apogee.render.FrameBus
import com.rm.apogee.render.QualityTier
import com.rm.apogee.render.TerrainSource
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test
import kotlin.math.sqrt

class MapSelectionTest {
    @Test fun selectionIsSeparateFromTargetingBurnsAndFocus() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val world = World.default(StockParts.catalog)
        val body = world.system.body("terra")
        val radius = body.radius + 300_000.0
        val craft = world.spawnAt(StockCraft.starterRocket(), body.id, Vec3(radius, 0.0, 0.0),
            Vec3(0.0, sqrt(body.gravitationalParameter / radius), 0.0), Quat.identity()).apply { owner = "map-test" }
        val other = world.spawnAt(StockCraft.starterRocket(), body.id, Vec3(radius + 1000.0, 0.0, 0.0),
            Vec3(0.0, sqrt(body.gravitationalParameter / radius), 0.0), Quat.identity()).apply { owner = "map-test" }
        val session = GameSession.hostLocal(FrameBus(), null, "Mapper", "map-test", scope = scope,
            world = world, resumeVessel = craft.id.raw)
        session.attachTerrain(TerrainSource(), QualityTier.LOW)
        session.mapMode = true
        session.start(scope)
        suspend fun await(condition: () -> Boolean) = withTimeout(15_000) { while (!condition()) delay(25) }
        try {
            await { session.mapLabels(1000f, 800f).any { it.selectionKey == "world:terra" } }
            session.selectMapObject("world:terra")
            assertEquals("Terra", session.mapSelection.selected!!.name)
            assertEquals(listOf(MapAction.FOCUS), session.mapSelection.actions)
            assertEquals("", session.localTargetBody)
            session.actOnMapSelection(MapAction.FOCUS)
            assertEquals(GameSession.MapFocus(GameSession.FocusKind.WORLD, "terra"), session.mapFocus)
            await { session.mapLabels(1000f, 800f).any { it.selectionKey == "craft:${other.id.raw}" || it.icon == GameSession.IconKind.OTHER } }
            val craftLabel = session.mapLabels(1000f, 800f).first { it.selectionKey == "craft:${other.id.raw}" }
            session.mapTap(craftLabel.x, craftLabel.y, 1000f, 800f)
            assertTrue("overlapping craft offer a choice", session.mapSelection.choices.size >= 2)
            session.selectMapObject("craft:${other.id.raw}")
            assertTrue(MapAction.FLY in session.mapSelection.actions)
            session.actOnMapSelection(MapAction.FOCUS)
            assertTrue(MapAction.TARGET in session.mapSelection.actions)
            assertEquals(GameSession.FocusKind.VESSEL, session.mapFocus.kind)
            session.actOnMapSelection(MapAction.TARGET)
            assertFalse(MapAction.TARGET in session.mapSelection.actions)
            assertTrue(MapAction.CLEAR_TARGET in session.mapSelection.actions)
            session.actOnMapSelection(MapAction.CLEAR_TARGET)
            assertTrue(MapAction.TARGET in session.mapSelection.actions)

            // Return to the whole orbit, then pick a future path event without creating a burn.
            session.selectMapObject("world:terra")
            session.actOnMapSelection(MapAction.FOCUS)
            await { session.mapLabels(1000f, 800f).any { it.mark != null && it.inSeconds > 120 } }
            val mark = session.mapLabels(1000f, 800f).first { it.mark != null && it.inSeconds > 120 && it.selectionKey.isNotEmpty() }
            session.selectMapObject(mark.selectionKey)
            assertNull(session.burnReadout)
            assertTrue(MapAction.WARP in session.mapSelection.actions)
            await { MapAction.ADD_BURN in session.mapSelection.actions }
            session.actOnMapSelection(MapAction.ADD_BURN)
            await { session.burnReadout != null }
            assertNull(session.mapSelection.selected)
            await { session.mapLabels(1000f, 800f).any { it.mark?.kind == MarkKind.BURN } }
            session.selectMapObject("mark:BURN:terra")
            session.actOnMapSelection(MapAction.DELETE_BURN)
            await { session.burnReadout == null }

            val label = session.mapLabels(1000f, 800f).first { it.selectionKey == "world:terra" }
            session.mapTap(label.x, label.y, 1000f, 800f)
            assertTrue(session.mapSelection.selected?.bodyId == "terra" || session.mapSelection.choices.any { it.bodyId == "terra" })
            session.dismissMapSelection()
            assertFalse(session.mapTap(-10_000f, -10_000f, 1000f, 800f))
            assertNull(session.mapSelection.selected)
            session.selectMapObject("world:terra")
            session.mapMode = false
            session.mapMode = true
            assertNull(session.mapSelection.selected)
        } finally { session.stop(); scope.cancel() }
    }
}
