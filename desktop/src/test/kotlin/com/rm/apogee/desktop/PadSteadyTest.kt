package com.rm.apogee.desktop

import com.rm.apogee.core.craft.StockCraft
import com.rm.apogee.core.math.Vec3
import com.rm.apogee.core.part.StockParts
import com.rm.apogee.core.weather.CloudCover
import com.rm.apogee.core.weather.WeatherIntensity
import com.rm.apogee.core.world.World
import com.rm.apogee.game.GameSession
import com.rm.apogee.render.FrameBus
import com.rm.apogee.render.GlRenderer
import com.rm.apogee.render.QualityTier
import com.rm.apogee.render.RenderFrame
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test

/**
 * The Starter I sitting on the Cape pad in a wild storm: it's drawn in the same place on the ground
 * every frame. A snapshot landing mid-frame used to pair the craft's old state with the new
 * snapshot's time, and the pad slid a few metres under it and eased back.
 */
class PadSteadyTest {
    @Test fun rocketOnThePadStaysPutInAStorm() {
        TestGl.current()
        val frameBus = FrameBus()
        val renderer = GlRenderer({ QualityTier.LOW }, frameBus)
        renderer.onSurfaceCreated()
        renderer.onSurfaceChanged(400, 640)
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val world = World.default(StockParts.catalog).also { it.ensureStructures() }
        val session = GameSession.hostLocal(
            frameBus = frameBus, perfHints = null, playerName = "Pad", clientId = "pad-client",
            design = StockCraft.starterRocket(StockParts.catalog), scope = scope, world = world,
            freshFlight = true, weather = WeatherIntensity.WILD, clouds = CloudCover.HEAVY,
        )
        session.attachTerrain(renderer.terrainSource, QualityTier.LOW)
        session.start(scope)
        try {
            var last: RenderFrame? = null
            var settled: Vec3? = null
            var previous: Vec3? = null
            var frames = 0
            var worstStep = 0.0
            var worstDrift = 0.0
            val start = System.nanoTime()
            while (System.nanoTime() - start < RUN_NANOS) {
                renderer.onDrawFrame()
                val frame = frameBus.latest()?.latest
                if (frame != null && frame !== last && frame.world != null && frame.shadowFocus != null) {
                    last = frame
                    // Where the craft is drawn, on the turning ground.
                    val ground = frame.world!!.bodyRotation.inverseRotate(frame.shadowFocus!!)
                    // The first seconds settle the replica onto the pad.
                    if (System.nanoTime() - start > SETTLE_NANOS) {
                        if (settled == null) settled = ground
                        previous?.let { worstStep = maxOf(worstStep, it.distanceTo(ground)) }
                        worstDrift = maxOf(worstDrift, settled.distanceTo(ground))
                        frames++
                    }
                    previous = ground
                }
            }
            println("PAD frames=$frames worstStep=$worstStep worstDrift=$worstDrift")
            assertTrue("only $frames frames", frames > 300)
            assertTrue("moved ${"%.2f".format(worstStep)} m in a frame", worstStep < 0.05)
            assertTrue("wandered ${"%.2f".format(worstDrift)} m", worstDrift < 0.2)
        } finally {
            session.stop()
            scope.cancel()
            renderer.release()
        }
    }

    private companion object {
        const val SETTLE_NANOS = 5_000_000_000L
        const val RUN_NANOS = 45_000_000_000L
    }
}
