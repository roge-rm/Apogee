package com.rm.apogee.render

import com.rm.apogee.render.gl.GLES30
import com.rm.apogee.render.gl.GlData

/** An index buffer uploaded once and shared by every terrain chunk, which all have the same topology. */
class SharedIndexBuffer(indices: ShortArray) {
    val id: Int
    val count: Int = indices.size

    init {
        val ids = IntArray(1)
        GLES30.glGenBuffers(1, ids, 0)
        id = ids[0]
        val buffer = GlData(indices.size * 2)
        buffer.putShorts(indices)
        GLES30.glBindBuffer(GLES30.GL_ELEMENT_ARRAY_BUFFER, id)
        GLES30.glBufferData(GLES30.GL_ELEMENT_ARRAY_BUFFER, indices.size * 2, buffer, GLES30.GL_STATIC_DRAW)
        GLES30.glBindBuffer(GLES30.GL_ELEMENT_ARRAY_BUFFER, 0)
    }

    fun release() = GLES30.glDeleteBuffers(1, intArrayOf(id), 0)
}

/**
 * A terrain mesh on the GPU: position, normal, colour (from [TerrainPalette]) and wetness.
 *
 * @param shared the index buffer to draw with. Null for the globe, which has its own.
 */
class TerrainMesh(private val shared: SharedIndexBuffer? = null) {

    private val vao = IntArray(1)
    private val buffers = IntArray(2)
    private var indexCount = shared?.count ?: 0
    private var vertexCapacity = 0
    private var indexCapacity = 0
    private var hasVertices = false

    init {
        GLES30.glGenVertexArrays(1, vao, 0)
        GLES30.glGenBuffers(2, buffers, 0)

        GLES30.glBindVertexArray(vao[0])
        GLES30.glBindBuffer(GLES30.GL_ARRAY_BUFFER, buffers[0])

        val stride = TerrainChunk.STRIDE_FLOATS * Float.SIZE_BYTES
        GLES30.glEnableVertexAttribArray(ATTR_POSITION)
        GLES30.glVertexAttribPointer(ATTR_POSITION, 3, GLES30.GL_FLOAT, false, stride, 0)
        GLES30.glEnableVertexAttribArray(ATTR_NORMAL)
        GLES30.glVertexAttribPointer(ATTR_NORMAL, 3, GLES30.GL_FLOAT, false, stride, 3 * Float.SIZE_BYTES)
        GLES30.glEnableVertexAttribArray(ATTR_COLOUR)
        GLES30.glVertexAttribPointer(ATTR_COLOUR, 3, GLES30.GL_FLOAT, false, stride, 6 * Float.SIZE_BYTES)
        GLES30.glEnableVertexAttribArray(ATTR_WET)
        GLES30.glVertexAttribPointer(ATTR_WET, 1, GLES30.GL_FLOAT, false, stride, 9 * Float.SIZE_BYTES)

        GLES30.glBindBuffer(GLES30.GL_ELEMENT_ARRAY_BUFFER, shared?.id ?: buffers[1])
        GLES30.glBindVertexArray(0)
    }

    /** Replaces the vertices, and the indices too for a mesh with its own. */
    fun upload(vertices: FloatArray, indices: IntArray? = null) {
        GLES30.glBindVertexArray(vao[0])

        val vertexBytes = vertices.size * Float.SIZE_BYTES
        val vertexBuffer = staging(vertexBytes)
        vertexBuffer.putFloats(vertices)
        GLES30.glBindBuffer(GLES30.GL_ARRAY_BUFFER, buffers[0])
        if (vertexBytes > vertexCapacity) {
            GLES30.glBufferData(GLES30.GL_ARRAY_BUFFER, vertexBytes, vertexBuffer, GLES30.GL_STATIC_DRAW)
            vertexCapacity = vertexBytes
        } else {
            GLES30.glBufferSubData(GLES30.GL_ARRAY_BUFFER, 0, vertexBytes, vertexBuffer)
        }

        if (indices != null && shared == null) {
            indexCount = indices.size
            val indexBytes = indices.size * 4
            val indexBuffer = GlData(indexBytes).apply { putInts(indices) }
            GLES30.glBindBuffer(GLES30.GL_ELEMENT_ARRAY_BUFFER, buffers[1])
            if (indexBytes > indexCapacity) {
                GLES30.glBufferData(GLES30.GL_ELEMENT_ARRAY_BUFFER, indexBytes, indexBuffer, GLES30.GL_STATIC_DRAW)
                indexCapacity = indexBytes
            } else {
                GLES30.glBufferSubData(GLES30.GL_ELEMENT_ARRAY_BUFFER, 0, indexBytes, indexBuffer)
            }
        }
        hasVertices = true
        GLES30.glBindVertexArray(0)
    }

    val isReady: Boolean get() = hasVertices && indexCount > 0


    fun draw() {
        if (!isReady) return
        GLES30.glBindVertexArray(vao[0])
        // The globe's own indices are 32-bit; chunks share a 16-bit list.
        val type = if (shared == null) GLES30.GL_UNSIGNED_INT else GLES30.GL_UNSIGNED_SHORT
        GLES30.glDrawElements(GLES30.GL_TRIANGLES, indexCount, type, 0)
        GLES30.glBindVertexArray(0)
    }

    /** Draws only the quarters in [mask] of a chunk. See [TerrainChunk.indices]. */
    fun drawQuadrants(mask: Int) {
        if (mask == DrawEntry.ALL_QUADRANTS) return draw()
        if (!isReady) return
        val perQuarter = TerrainChunk.quadrantIndexCount
        GLES30.glBindVertexArray(vao[0])
        for (quarter in 0 until 4) {
            if (mask and (1 shl quarter) == 0) continue
            // The offset in bytes, two per unsigned short.
            GLES30.glDrawElements(GLES30.GL_TRIANGLES, perQuarter, GLES30.GL_UNSIGNED_SHORT, quarter * perQuarter * 2)
        }
        GLES30.glBindVertexArray(0)
    }

    fun release() {
        GLES30.glDeleteBuffers(2, buffers, 0)
        GLES30.glDeleteVertexArrays(1, vao, 0)
    }

    private companion object {
        /** One staging buffer for every upload, grown as needed, so chunks don't churn native memory. GL thread only. */
        private var stagingBuffer: GlData? = null

        fun staging(bytes: Int): GlData {
            val current = stagingBuffer
            if (current != null && current.capacity >= bytes) return current
            return GlData(bytes).also { stagingBuffer = it }
        }

        const val ATTR_POSITION = 0
        const val ATTR_NORMAL = 1
        const val ATTR_COLOUR = 2
        const val ATTR_WET = 3
    }
}
