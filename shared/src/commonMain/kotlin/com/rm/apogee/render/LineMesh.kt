package com.rm.apogee.render

import com.rm.apogee.render.gl.GLES30
import com.rm.apogee.render.gl.GlData

/**
 * A polyline in GPU memory, uploaded again as it changes.
 *
 * It's separate from [Mesh] because the two have nothing in common at the GL level. This one has
 * one attribute instead of two, streams instead of sitting still, and draws as a line strip instead
 * of indexed triangles. An orbit is worked out again every frame as the craft moves, so the buffer
 * is `GL_DYNAMIC_DRAW` and only grows when a longer path arrives.
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
            // Grow, and reallocate instead of orphaning. A partial update into a smaller buffer is
            // undefined, not just wrong.
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
