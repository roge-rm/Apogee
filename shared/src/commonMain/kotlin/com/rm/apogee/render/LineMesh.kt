package com.rm.apogee.render

import com.rm.apogee.render.gl.GLES30
import com.rm.apogee.render.gl.GlData

/**
 * A polyline in GPU memory, uploaded again as it changes. One attribute, drawn as a line strip.
 * Orbits change every frame, so the buffer is `GL_DYNAMIC_DRAW` and only grows when it must.
 */
class LineMesh {

    private val vao = IntArray(1)
    private val vbo = IntArray(1)
    private var capacityFloats = 0
    private var vertexCount = 0

    init {
        GLES30.glGenVertexArrays(1, vao, 0)
        GLES30.glGenBuffers(1, vbo, 0)

        GLES30.glBindVertexArray(vao[0])
        GLES30.glBindBuffer(GLES30.GL_ARRAY_BUFFER, vbo[0])
        GLES30.glEnableVertexAttribArray(0)
        GLES30.glVertexAttribPointer(0, 3, GLES30.GL_FLOAT, false, 3 * Float.SIZE_BYTES, 0)
        GLES30.glBindVertexArray(0)
    }

    /** Replaces the polyline. [points] is x,y,z triples. */
    fun upload(points: FloatArray) {
        vertexCount = points.size / 3
        if (vertexCount == 0) return

        GLES30.glBindVertexArray(vao[0])
        GLES30.glBindBuffer(GLES30.GL_ARRAY_BUFFER, vbo[0])

        val buffer = GlData(points.size * Float.SIZE_BYTES).apply { putFloats(points) }

        if (points.size > capacityFloats) {
            // Reallocate to grow. A partial update past the end of a buffer is undefined.
            GLES30.glBufferData(
                GLES30.GL_ARRAY_BUFFER,
                points.size * Float.SIZE_BYTES,
                buffer,
                GLES30.GL_DYNAMIC_DRAW,
            )
            capacityFloats = points.size
        } else {
            GLES30.glBufferSubData(
                GLES30.GL_ARRAY_BUFFER,
                0,
                points.size * Float.SIZE_BYTES,
                buffer,
            )
        }
        GLES30.glBindVertexArray(0)
    }

    fun draw() {
        if (vertexCount < 2) return
        GLES30.glBindVertexArray(vao[0])
        GLES30.glDrawArrays(GLES30.GL_LINE_STRIP, 0, vertexCount)
        GLES30.glBindVertexArray(0)
    }

    fun release() {
        GLES30.glDeleteBuffers(1, vbo, 0)
        GLES30.glDeleteVertexArrays(1, vao, 0)
    }
}
