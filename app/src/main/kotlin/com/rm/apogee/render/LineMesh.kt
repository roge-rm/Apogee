package com.rm.apogee.render

import android.opengl.GLES30
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * A polyline in GPU memory, re-uploaded as it changes.
 *
 * Separate from [Mesh] because the two have nothing in common at the GL level:
 * this has one attribute rather than two, streams rather than sits still, and
 * draws as a line strip rather than indexed triangles. An orbit is recomputed
 * every frame as the craft moves, so the buffer is `GL_DYNAMIC_DRAW` and grown
 * only when a longer path arrives.
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

        val buffer = ByteBuffer
            .allocateDirect(points.size * Float.SIZE_BYTES)
            .order(ByteOrder.nativeOrder())
            .apply { asFloatBuffer().put(points); position(0) }

        if (points.size > capacityFloats) {
            // Grow, and reallocate rather than orphan - a partial update into a
            // smaller buffer is undefined, not merely wrong.
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
