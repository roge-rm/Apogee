package com.rm.apogee.desktop

import com.rm.apogee.core.craft.StockCraft
import com.rm.apogee.core.craft.VesselId
import com.rm.apogee.core.part.StockParts
import com.rm.apogee.core.world.World
import com.rm.apogee.game.GameSession
import com.rm.apogee.render.FrameBus
import com.rm.apogee.render.TerrainSource
import com.rm.apogee.render.QualityTier
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test

class AssemblyReturnTest {
    @Test fun returningRemovesTheFlownVehicleAndKeepsItsLaunchDesign() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val catalog = StockParts.catalog
        val world = World.default(catalog)
        val design = StockCraft.sparkler(catalog)
        val bus = FrameBus()
        val session = GameSession.hostLocal(
            bus, null, "Builder", "assembly-test", design = design, scope = scope,
            world = world, freshFlight = false,
        )
        session.attachTerrain(TerrainSource(), QualityTier.LOW)
        session.start(scope)
        try {
            withTimeout(10_000) {
                while (session.controlledCraft == null || session.assemblyDesign != design) delay(20)
            }
            val id = session.controlledCraft!!
            for (quality in listOf(QualityTier.MEDIUM, QualityTier.LOW)) {
                session.requestQuality(quality)
                // Let a frame already in progress finish, then draw one with the new detail.
                repeat(2) {
                    val previous = bus.latest()?.latest
                    withTimeout(10_000) {
                        while (bus.latest()?.latest === previous || bus.latest()?.latest?.world == null) delay(20)
                    }
                }
                assertEquals(id, session.controlledCraft)
            }
            session.stage()
            delay(250)
            assertEquals(design, session.takeToAssembly())
            assertNull(world.vessel(VesselId(id)))
            assertNull(session.controlledCraft)
        } finally {
            session.stop()
            scope.cancel()
        }
    }
}
