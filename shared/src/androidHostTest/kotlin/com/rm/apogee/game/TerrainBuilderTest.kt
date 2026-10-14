package com.rm.apogee.game

import com.rm.apogee.core.math.Vec3
import com.rm.apogee.core.orbit.CelestialBody
import com.rm.apogee.core.terrain.SurfaceMaterial
import com.rm.apogee.core.terrain.Terrain
import com.rm.apogee.core.terrain.TerrainTileCache
import com.rm.apogee.render.ChunkKey
import com.rm.apogee.render.QualityTier
import com.rm.apogee.render.TerrainChunk
import com.rm.apogee.render.TerrainSource
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import org.junit.Assert.assertTrue
import org.junit.Test

class TerrainBuilderTest {
    @Test
    fun `waiting for refinements still reaches full ground detail and finishes loading`() {
        val field = object : Terrain {
            override val bodyRadius = 1_000.0
            override val maxElevation = 0.0
            override val generation = 0
            override val hasOcean = false
            override val tiles = TerrainTileCache(this)
            override fun elevation(direction: Vec3) = 0.0
            override fun material(direction: Vec3, elevation: Double, slope: Double) = SurfaceMaterial.GRASS
        }
        val body = CelestialBody("test", "Ground", 10_000.0, field.bodyRadius, terrain = field)
        val source = TerrainSource()
        val builder = TerrainBuilder(source, QualityTier.LOW)
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val frames = HashMap<ChunkKey, Int>()
        var waited = false
        try {
            val deadline = System.currentTimeMillis() + 10_000
            while (!builder.patchReady && System.currentTimeMillis() < deadline) {
                builder.collect()
                builder.followCraft(body, Vec3(body.radius + 2.0, 0.0, 0.0), 2.0, scope)
                while (true) {
                    val chunk = source.nextToUpload() ?: break
                    source.markUploaded(chunk.key)
                }
                // Stand in for frames from the GPU, leaving each refinement unsettled briefly.
                for (entry in source.drawList()) {
                    val age = (frames[entry.key] ?: 0) + 1
                    frames[entry.key] = age
                    if (age >= 3) source.markSettled(entry.key) else waited = true
                }
                Thread.sleep(5)
            }
            assertTrue("a refinement was held", waited)
            assertTrue("loading finished", builder.patchReady)
            val finest = TerrainChunk.finestLevel(field.tiles.tilesPerFace)
            assertTrue("full detail under the craft", source.drawList().any { it.key.face == 0 && it.key.level == finest })
        } finally {
            builder.stop()
            scope.cancel()
        }
    }
}
