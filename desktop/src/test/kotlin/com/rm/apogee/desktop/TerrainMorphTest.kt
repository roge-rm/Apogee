package com.rm.apogee.desktop

import com.rm.apogee.core.math.Vec3
import com.rm.apogee.core.terrain.SurfaceMaterial
import com.rm.apogee.core.terrain.Terrain
import com.rm.apogee.core.terrain.TerrainTileCache
import com.rm.apogee.render.ChunkKey
import com.rm.apogee.render.ShaderProgram
import com.rm.apogee.render.Shaders
import com.rm.apogee.render.SharedIndexBuffer
import com.rm.apogee.render.TerrainChunk
import com.rm.apogee.render.TerrainMesh
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.lwjgl.BufferUtils
import org.lwjgl.opengl.GL11C.*
import kotlin.math.sin

class TerrainMorphTest {
    @Test
    fun collapsedChildDrawsTheSameDepthAsItsParent() = compareDepth(ocean = false)

    @Test
    fun collapsedFarWaterDrawsTheSameDepthAsItsParent() = compareDepth(ocean = true)

    private fun compareDepth(ocean: Boolean) {
        TestGl.current()
        val field = object : Terrain {
            override val bodyRadius = 600_000.0
            override val maxElevation = 50.0
            override val generation = 0
            override val hasOcean = ocean
            override val tiles = TerrainTileCache(this)
            override fun elevation(direction: Vec3) = (if (ocean) -500.0 else 0.0) +
                50.0 * sin(direction.y * bodyRadius * 0.03) * sin(direction.z * bodyRadius * 0.03)
            override fun material(direction: Vec3, elevation: Double, slope: Double) = SurfaceMaterial.GRASS
        }
        val parent = TerrainChunk.build(field, ChunkKey(0, 10, 512, 512))
        val child = TerrainChunk.build(field, parent.key.child(0, 0))
        val indices = SharedIndexBuffer(TerrainChunk.indices)
        val coarse = TerrainMesh(indices)
        val fine = TerrainMesh(indices)
        val shader = ShaderProgram(Shaders.TERRAIN_VERTEX, Shaders.DEPTH_FRAGMENT, "morph-depth")
        try {
            coarse.upload(parent.vertices!!, coarse = parent.coarseVertices)
            fine.upload(child.vertices!!, coarse = child.coarseVertices)
            shader.use()
            // Look down +X, with Y and Z across the screen, centred on the child.
            val projection = floatArrayOf(
                0f, 0f, -0.001f, 0f,
                0.004f, 0f, 0f, 0f,
                0f, 0.004f, 0f, 0f,
                0f, 0f, 0f, 1f,
            )
            shader.setMat4("uViewProjection", projection)
            shader.setFloat("uSeaReach", 0f)
            shader.setFloat("uTide", 0f)
            shader.setVec3("uBodyCentre", -child.centre.x.toFloat() - 600f, -child.centre.y.toFloat(), -child.centre.z.toFloat())
            glViewport(0, 0, 256, 256)
            glEnable(GL_DEPTH_TEST)
            glDisable(GL_CULL_FACE)
            fun render(mesh: TerrainMesh, morph: Float, translation: Vec3, mask: Int, active: ShaderProgram = shader): FloatArray {
                active.use()
                glClear(GL_DEPTH_BUFFER_BIT or GL_COLOR_BUFFER_BIT)
                active.setMat4("uModel", floatArrayOf(
                    1f, 0f, 0f, 0f, 0f, 1f, 0f, 0f, 0f, 0f, 1f, 0f,
                    translation.x.toFloat() - 600f, translation.y.toFloat(), translation.z.toFloat(), 1f,
                ))
                active.setFloat("uMorph", morph)
                mesh.drawQuadrants(mask)
                return FloatArray(256 * 256).also { glReadPixels(0, 0, 256, 256, GL_DEPTH_COMPONENT, GL_FLOAT, it) }
            }
            val before = render(coarse, 0f, parent.centre.copy().subInPlace(child.centre), 1)
            val collapsed = render(fine, 1f, Vec3(), 15)
            val detailed = render(fine, 0f, Vec3(), 15)
            var checked = 0
            var relief = 0f
            for (y in 24 until 232) for (x in 24 until 232) {
                val i = y * 256 + x
                if (before[i] == 1f || collapsed[i] == 1f) continue
                checked++
                assertEquals("pixel $x,$y", before[i], collapsed[i], 0.00001f)
                relief = maxOf(relief, kotlin.math.abs(detailed[i] - collapsed[i]))
            }
            assertTrue("covered $checked pixels", checked > 30_000)
            if (!ocean) assertTrue("fine mesh has distinct relief", relief > 0.001f)
            run {
                val colour = ShaderProgram(Shaders.TERRAIN_VERTEX, Shaders.TERRAIN_FRAGMENT, "morph-colour")
                try {
                    colour.use()
                    colour.setMat4("uViewProjection", projection)
                    colour.setFloat("uSeaReach", 0f)
                    colour.setFloat("uTide", 0f)
                    colour.setVec3("uBodyCentre", -child.centre.x.toFloat() - 600f, -child.centre.y.toFloat(), -child.centre.z.toFloat())
                    colour.setVec3("uSunDirection", 1f, 0f, 0f)
                    colour.setFloat("uSkyBody", 1f)
                    fun colours(): ByteArray {
                        val buffer = BufferUtils.createByteBuffer(256 * 256 * 4)
                        glReadPixels(0, 0, 256, 256, GL_RGBA, GL_UNSIGNED_BYTE, buffer)
                        return ByteArray(buffer.capacity()).also { buffer.get(it) }
                    }
                    render(coarse, 0f, parent.centre.copy().subInPlace(child.centre), 1, colour)
                    val oldColour = colours()
                    render(fine, 1f, Vec3(), 15, colour)
                    val newColour = colours()
                    if (ocean) {
                        // A crack-hiding strip has the same water colour and lighting as its top.
                        val skirtVertices = child.vertices!!.copyOf()
                        for (v in 0 until TerrainChunk.VERTEX_COUNT) {
                            val o = v * TerrainChunk.STRIDE_FLOATS + 9
                            if (skirtVertices[o] < 3.5f) skirtVertices[o] += 4f
                        }
                        val strip = TerrainMesh(indices)
                        try {
                            strip.upload(skirtVertices, coarse = child.coarseVertices)
                            for (night in listOf(false, true)) {
                                colour.setFloat("uSkyBody", if (night) 0f else 1f)
                                colour.setFloat("uDaylight", if (night) 0f else 1f)
                                colour.setFloat("uLightScale", 1f)
                                colour.setVec3("uSunDirection", if (night) -1f else 1f, 0f, 0f)
                                colour.setVec3("uHaze", 0.15f, 0.3f, 0.5f)
                                colour.setVec3("uSeaSky", 0.05f, 0.1f, 0.25f)
                                render(fine, 0f, Vec3(), 15, colour)
                                val top = colours()
                                render(strip, 0f, Vec3(), 15, colour)
                                val seam = colours()
                                for (y in 24 until 232) for (x in 24 until 232) for (channel in 0..2) {
                                    val i = (y * 256 + x) * 4 + channel
                                    assertTrue("water seam lighting $x,$y channel $channel: ${top[i].toInt() and 255}/${seam[i].toInt() and 255}",
                                        kotlin.math.abs((top[i].toInt() and 255) - (seam[i].toInt() and 255)) <= 1)
                                }
                            }
                        } finally { strip.release() }
                    }
                    for (y in 24 until 232) for (x in 24 until 232) {
                        val pixel = y * 256 + x
                        if (before[pixel] == 1f || collapsed[pixel] == 1f) continue
                        for (channel in 0..2) {
                            val i = pixel * 4 + channel
                            assertTrue("colour at $x,$y channel $channel: ${oldColour[i].toInt() and 255} / ${newColour[i].toInt() and 255}",
                                kotlin.math.abs((oldColour[i].toInt() and 255) - (newColour[i].toInt() and 255)) <= 1)
                        }
                    }
                } finally { colour.release() }
            }
            assertEquals("GL error", GL_NO_ERROR, glGetError())
        } finally {
            coarse.release(); fine.release(); indices.release(); shader.release()
        }
    }
}
