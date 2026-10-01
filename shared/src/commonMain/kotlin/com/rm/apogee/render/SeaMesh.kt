package com.rm.apogee.render

import com.rm.apogee.render.gl.GLES30
import com.rm.apogee.render.gl.GlData

/** The sea's surface on the GPU, replaced whole each time a new one is built. */
class SeaMesh {

    private val vao = IntArray(1)
    private val buffers = IntArray(2)
    private var vertexCapacity = 0
    private var indexCount = 0
    private var layout = -1
    private var vertexBuffer: GlData? = null

    /** The surface on the GPU now. */
    var surface: SeaSurface? = null
        private set

    init {
        GLES30.glGenVertexArrays(1, vao, 0)
        GLES30.glGenBuffers(2, buffers, 0)
        GLES30.glBindVertexArray(vao[0])
        GLES30.glBindBuffer(GLES30.GL_ARRAY_BUFFER, buffers[0])
        val stride = SeaSurface.STRIDE * Float.SIZE_BYTES
        GLES30.glEnableVertexAttribArray(0)
        GLES30.glVertexAttribPointer(0, 3, GLES30.GL_FLOAT, false, stride, 0)
        GLES30.glEnableVertexAttribArray(1)
        GLES30.glVertexAttribPointer(1, 3, GLES30.GL_FLOAT, false, stride, 3 * Float.SIZE_BYTES)
        GLES30.glEnableVertexAttribArray(2)
        GLES30.glVertexAttribPointer(2, 4, GLES30.GL_FLOAT, false, stride, 6 * Float.SIZE_BYTES)
        GLES30.glEnableVertexAttribArray(3)
        GLES30.glVertexAttribPointer(3, 1, GLES30.GL_FLOAT, false, stride, 10 * Float.SIZE_BYTES)
        GLES30.glBindBuffer(GLES30.GL_ELEMENT_ARRAY_BUFFER, buffers[1])
        GLES30.glBindVertexArray(0)
    }

    /** Takes [next] onto the GPU, if it isn't the one already there. */
    fun take(next: SeaSurface) {
        if (next === surface) return
        GLES30.glBindVertexArray(vao[0])
        val bytes = next.vertexCount * SeaSurface.STRIDE * Float.SIZE_BYTES
        val buffer = vertexBuffer?.takeIf { it.capacity >= bytes }
            ?: GlData(bytes).also { vertexBuffer = it }
        buffer.putFloats(next.vertices, next.vertexCount * SeaSurface.STRIDE)
        GLES30.glBindBuffer(GLES30.GL_ARRAY_BUFFER, buffers[0])
        if (bytes > vertexCapacity) {
            GLES30.glBufferData(GLES30.GL_ARRAY_BUFFER, bytes, buffer, GLES30.GL_DYNAMIC_DRAW)
            vertexCapacity = bytes
        } else {
            GLES30.glBufferSubData(GLES30.GL_ARRAY_BUFFER, 0, bytes, buffer)
        }
        if (next.layout != layout) {
            val indexBytes = next.indices.size * 4
            val indexBuffer = GlData(indexBytes)
            indexBuffer.putInts(next.indices)
            GLES30.glBindBuffer(GLES30.GL_ELEMENT_ARRAY_BUFFER, buffers[1])
            GLES30.glBufferData(GLES30.GL_ELEMENT_ARRAY_BUFFER, indexBytes, indexBuffer, GLES30.GL_STATIC_DRAW)
            indexCount = next.indices.size
            layout = next.layout
        }
        GLES30.glBindVertexArray(0)
        // The last one's vertices are done with: they're on the GPU no longer, and nothing else
        // reads them. They go back to be built into again.
        surface?.let { old -> old.giveBack?.invoke(old.vertices) }
        surface = next
    }

    fun draw() {
        if (surface == null || indexCount == 0) return
        GLES30.glBindVertexArray(vao[0])
        GLES30.glDrawElements(GLES30.GL_TRIANGLES, indexCount, GLES30.GL_UNSIGNED_INT, 0)
        GLES30.glBindVertexArray(0)
    }

    fun release() {
        GLES30.glDeleteBuffers(2, buffers, 0)
        GLES30.glDeleteVertexArrays(1, vao, 0)
        surface = null
    }
}
