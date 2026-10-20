package com.rm.apogee.desktop

import com.rm.apogee.core.math.Vec3
import com.rm.apogee.core.terrain.SurfaceMaterial
import com.rm.apogee.core.terrain.Terrain
import com.rm.apogee.core.terrain.TerrainTileCache
import com.rm.apogee.render.*
import org.junit.Assert.*
import org.junit.Test
import org.lwjgl.BufferUtils
import org.lwjgl.opengl.GL11C.*

class TerrainSkirtTest {
    @Test fun gapCoversKeepTheirGroundLightingRightUpToTheEdge() {
        TestGl.current()
        val field = object : Terrain {
            override val bodyRadius = 600_000.0
            override val maxElevation = 0.0
            override val generation = 0
            override val hasOcean = false
            override val tiles = TerrainTileCache(this)
            override fun elevation(direction: Vec3) = 0.0
            override fun material(direction: Vec3, elevation: Double, slope: Double) = SurfaceMaterial.GRASS
        }
        val chunk = TerrainChunk.build(field, ChunkKey(0, 8, 128, 128))
        val indices = SharedIndexBuffer(TerrainChunk.indices)
        val mesh = TerrainMesh(indices)
        // Read the flag used to select ground lighting. Intermediate values let the upper lip
        // receive vertical-wall lighting, which makes dark dotted lines at distant patch edges.
        val shader = ShaderProgram(Shaders.TERRAIN_VERTEX_CALM, """
            #version 300 es
            precision highp float;
            flat in float vSkirt;
            out vec4 fragColor;
            void main() { fragColor = vec4(vSkirt, vSkirt, vSkirt, 1.0); }
        """.trimIndent(), "skirt-lighting")
        try {
            mesh.upload(chunk.vertices!!, coarse = chunk.coarseVertices)
            val vertices = chunk.vertices!!
            val topology = TerrainChunk.indices
            var covers = 0
            for (i in topology.indices step 3) {
                val corners = (0..2).map { topology[i + it].toInt() and 65535 }
                if (corners.none { vertices[it * TerrainChunk.STRIDE_FLOATS + 9] >= 3.5f }) continue
                covers++
                assertTrue("gap cover must select ground lighting for the whole triangle",
                    vertices[corners.last() * TerrainChunk.STRIDE_FLOATS + 9] >= 3.5f)
            }
            assertEquals(4 * TerrainChunk.CELLS * 4, covers)
            shader.use()
            shader.setMat4("uModel", com.rm.apogee.core.math.Mat4().m)
            // Side view: Y across, radial X up, so the hanging strips cover a visible band.
            shader.setMat4("uViewProjection", floatArrayOf(
                0f, .004f, 0f, 0f,
                .0004f, 0f, 0f, 0f,
                0f, 0f, .0001f, 0f,
                0f, .5f, 0f, 1f,
            ))
            shader.setFloat("uMorph", 0f)
            glViewport(0, 0, 256, 256)
            glDisable(GL_DEPTH_TEST); glDisable(GL_CULL_FACE); glDisable(GL_BLEND)
            glClearColor(0f, 0f, 0f, 1f); glClear(GL_COLOR_BUFFER_BIT)
            mesh.draw()
            val pixels = BufferUtils.createByteBuffer(256 * 256 * 4)
            glReadPixels(0, 0, 256, 256, GL_RGBA, GL_UNSIGNED_BYTE, pixels)
            var lit = 0
            for (i in 0 until 256 * 256) {
                val value = pixels[i * 4].toInt() and 255
                assertTrue("gap cover has mixed wall/ground lighting at pixel $i: $value", value == 0 || value == 255)
                if (value == 255) lit++
            }
            assertTrue("visible gap-cover pixels: $lit", lit > 1000)
            assertEquals(GL_NO_ERROR, glGetError())
        } finally {
            mesh.release(); indices.release(); shader.release()
            glEnable(GL_DEPTH_TEST); glEnable(GL_CULL_FACE)
        }
    }
}
