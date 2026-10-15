package com.rm.apogee.desktop

import com.rm.apogee.core.craft.StockCraft
import com.rm.apogee.core.part.StockParts
import com.rm.apogee.core.weather.CloudCover
import com.rm.apogee.core.weather.WeatherIntensity
import com.rm.apogee.core.world.World
import com.rm.apogee.game.GameSession
import com.rm.apogee.render.FrameBus
import com.rm.apogee.render.GlRenderer
import com.rm.apogee.render.PartModels
import com.rm.apogee.render.QualityTier
import com.rm.apogee.render.RenderFrame
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test

/** The Starter I on the Cape pad: the bases on other worlds are left out of the frame, the flown craft never. */
class CraftCullingTest {
    @Test fun otherWorldBasesAreLeftOutFromTheCapePad() {
        TestGl.current()
        val frameBus = FrameBus()
        val renderer = GlRenderer({ QualityTier.LOW }, frameBus)
        renderer.onSurfaceCreated()
        renderer.onSurfaceChanged(400, 640)
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val world = World.default(StockParts.catalog).also { it.ensureStructures() }
        val session = GameSession.hostLocal(
            frameBus = frameBus, perfHints = null, playerName = "Cull", clientId = "cull-client",
            design = StockCraft.starterRocket(StockParts.catalog), scope = scope, world = world,
            freshFlight = true, weather = WeatherIntensity.NORMAL, clouds = CloudCover.NORMAL,
        )
        session.attachTerrain(renderer.terrainSource, QualityTier.LOW)
        session.start(scope)
        try {
            fun far(frame: RenderFrame) = frame.items.count { it.position.distanceTo(frame.cameraPosition) > FAR }
            fun drawUntil(seconds: Double, done: (RenderFrame) -> Boolean): RenderFrame? {
                val end = System.nanoTime() + (seconds * 1e9).toLong()
                while (System.nanoTime() < end) {
                    renderer.onDrawFrame()
                    frameBus.latest()?.latest?.takeIf(done)?.let { return it }
                    Thread.sleep(30)
                }
                return null
            }
            // Drawing everything, the other worlds' bases are in the frame.
            session.debugNoCraftCulling = true
            val all = drawUntil(90.0) { far(it) > 100 }
            assertNotNull("other worlds' bases never drawn", all)
            // Counted now: once drawn past, a frame's items and list are filled again.
            val allCount = all!!.items.size

            session.debugNoCraftCulling = false
            val published = frameBus.latest()?.latest
            val culled = drawUntil(10.0) { it !== published && far(it) == 0 }
            assertNotNull("other worlds' bases still drawn", culled)
            culled!!
            // The flown craft is still there, round where the view is centred.
            val focus = culled.shadowFocus!!
            val own = culled.items.count { it.position.distanceTo(focus) < 30.0 + PartModels.boundingRadius(it.shape) }
            assertTrue("flown craft missing", own >= 15)
            assertTrue("${culled.items.size} items of $allCount", culled.items.size < allCount)
        } finally {
            session.stop()
            scope.cancel()
            renderer.release()
        }
    }

    private companion object {
        /** Further than anything on Terra in view from the pad, in metres. */
        const val FAR = 2_000_000.0
    }
}
