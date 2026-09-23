package com.rm.apogee.render

import android.opengl.GLES30
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer

/**
 * Draws the frame's particles: shapes of six camera-relative vertices each,
 * position and colour, fanned into four triangles, blended over everything
 * and writing no depth.
 *
 * One streamed buffer, rewritten each frame. The index pattern never
 * changes, so it is built once, as long as the most shapes yet seen.
 */
class ParticleRenderer {

    private val program = ShaderProgram(Shaders.PARTICLE_VERTEX, Shaders.PARTICLE_FRAGMENT, "particles")
    private val vao = IntArray(1)
    private val buffers = IntArray(2)
    private var vertexCapacity = 0
    private var indexShapes = 0
    private var staging: FloatBuffer? = null

    init {
        GLES30.glGenVertexArrays(1, vao, 0)
        GLES30.glGenBuffers(2, buffers, 0)
        GLES30.glBindVertexArray(vao[0])
        GLES30.glBindBuffer(GLES30.GL_ARRAY_BUFFER, buffers[0])
        val stride = FLOATS * 4
        GLES30.glEnableVertexAttribArray(0)
        GLES30.glVertexAttribPointer(0, 3, GLES30.GL_FLOAT, false, stride, 0)
        GLES30.glEnableVertexAttribArray(1)
        GLES30.glVertexAttribPointer(1, 4, GLES30.GL_FLOAT, false, stride, 12)
        GLES30.glBindBuffer(GLES30.GL_ELEMENT_ARRAY_BUFFER, buffers[1])
        GLES30.glBindVertexArray(0)
    }

    fun draw(vertices: FloatArray, shapes: Int, viewProjection: FloatArray, fogDistance: Float, fogColor: FloatArray) {
        if (shapes <= 0) return
        val floats = shapes * 6 * FLOATS
        GLES30.glBindVertexArray(vao[0])

        val buffer = staging?.takeIf { it.capacity() >= floats }
            ?: ByteBuffer.allocateDirect(floats * 4 + 4096).order(ByteOrder.nativeOrder()).asFloatBuffer().also { staging = it }
        buffer.clear()
        buffer.put(vertices, 0, floats)
        buffer.position(0)
        GLES30.glBindBuffer(GLES30.GL_ARRAY_BUFFER, buffers[0])
        if (floats * 4 > vertexCapacity) {
            vertexCapacity = floats * 4 * 2
            GLES30.glBufferData(GLES30.GL_ARRAY_BUFFER, vertexCapacity, null, GLES30.GL_STREAM_DRAW)
        }
        GLES30.glBufferSubData(GLES30.GL_ARRAY_BUFFER, 0, floats * 4, buffer)

        if (shapes > indexShapes) {
            indexShapes = shapes * 2
            val indices = IntArray(indexShapes * 12)
            for (s in 0 until indexShapes) {
                val b = s * 6
                val o = s * 12
                for (t in 0 until 4) {
                    indices[o + t * 3] = b
                    indices[o + t * 3 + 1] = b + t + 1
                    indices[o + t * 3 + 2] = b + t + 2
                }
            }
            val ib = ByteBuffer.allocateDirect(indices.size * 4).order(ByteOrder.nativeOrder())
            ib.asIntBuffer().put(indices)
            ib.position(0)
            GLES30.glBindBuffer(GLES30.GL_ELEMENT_ARRAY_BUFFER, buffers[1])
            GLES30.glBufferData(GLES30.GL_ELEMENT_ARRAY_BUFFER, indices.size * 4, ib, GLES30.GL_STATIC_DRAW)
        }

        program.use()
        program.setMat4("uViewProjection", viewProjection)
        program.setFloat("uFogDistance", fogDistance)
        program.setVec3("uFogColor", fogColor[0], fogColor[1], fogColor[2])
        GLES30.glEnable(GLES30.GL_BLEND)
        GLES30.glBlendFunc(GLES30.GL_SRC_ALPHA, GLES30.GL_ONE_MINUS_SRC_ALPHA)
        GLES30.glDepthMask(false)
        // Both faces: a billboard's winding depends on which way it was built.
        GLES30.glDisable(GLES30.GL_CULL_FACE)
        GLES30.glDrawElements(GLES30.GL_TRIANGLES, shapes * 12, GLES30.GL_UNSIGNED_INT, 0)
        GLES30.glEnable(GLES30.GL_CULL_FACE)
        GLES30.glDepthMask(true)
        GLES30.glDisable(GLES30.GL_BLEND)
        GLES30.glBindVertexArray(0)
    }

    fun release() {
        program.release()
        GLES30.glDeleteBuffers(2, buffers, 0)
        GLES30.glDeleteVertexArrays(1, vao, 0)
    }

    private companion object {
        const val FLOATS = 7
    }
}
