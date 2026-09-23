package com.rm.apogee.render

import android.opengl.GLES30
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * An index buffer uploaded once and bound by many meshes.
 *
 * Every terrain chunk has the same topology - only its vertices differ - so
 * there is one copy of the triangle list on the GPU rather than several
 * hundred identical ones.
 */
class SharedIndexBuffer(indices: ShortArray) {
    val id: Int
    val count: Int = indices.size

    init {
        val ids = IntArray(1)
        GLES30.glGenBuffers(1, ids, 0)
        id = ids[0]
        val buffer = ByteBuffer.allocateDirect(indices.size * 2).order(ByteOrder.nativeOrder())
        buffer.asShortBuffer().put(indices)
        buffer.position(0)
        GLES30.glBindBuffer(GLES30.GL_ELEMENT_ARRAY_BUFFER, id)
        GLES30.glBufferData(GLES30.GL_ELEMENT_ARRAY_BUFFER, indices.size * 2, buffer, GLES30.GL_STATIC_DRAW)
        GLES30.glBindBuffer(GLES30.GL_ELEMENT_ARRAY_BUFFER, 0)
    }

    fun release() = GLES30.glDeleteBuffers(1, intArrayOf(id), 0)
}

/**
 * A terrain mesh on the GPU: position, normal, colour and wetness.
 *
 * Colour arrives already decided, from [TerrainPalette] on the CPU, so the
 * shader only lights it. Wetness marks water, which the shader gives a glint.
 *
 * @param shared the index buffer to draw with. Null for a mesh with its own
 *   triangle list - the globe.
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
    fun upload(vertices: FloatArray, indices: ShortArray? = null) {
        GLES30.glBindVertexArray(vao[0])

        val vertexBytes = vertices.size * Float.SIZE_BYTES
        val vertexBuffer = ByteBuffer.allocateDirect(vertexBytes)
            .order(ByteOrder.nativeOrder())
            .apply { asFloatBuffer().put(vertices); position(0) }
        GLES30.glBindBuffer(GLES30.GL_ARRAY_BUFFER, buffers[0])
        if (vertexBytes > vertexCapacity) {
            GLES30.glBufferData(GLES30.GL_ARRAY_BUFFER, vertexBytes, vertexBuffer, GLES30.GL_STATIC_DRAW)
            vertexCapacity = vertexBytes
        } else {
            GLES30.glBufferSubData(GLES30.GL_ARRAY_BUFFER, 0, vertexBytes, vertexBuffer)
        }

        if (indices != null && shared == null) {
            indexCount = indices.size
            val indexBytes = indices.size * 2
            val indexBuffer = ByteBuffer.allocateDirect(indexBytes)
                .order(ByteOrder.nativeOrder())
                .apply { asShortBuffer().put(indices); position(0) }
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
        GLES30.glDrawElements(GLES30.GL_TRIANGLES, indexCount, GLES30.GL_UNSIGNED_SHORT, 0)
        GLES30.glBindVertexArray(0)
    }

    fun release() {
        GLES30.glDeleteBuffers(2, buffers, 0)
        GLES30.glDeleteVertexArrays(1, vao, 0)
    }

    private companion object {
        const val ATTR_POSITION = 0
        const val ATTR_NORMAL = 1
        const val ATTR_COLOUR = 2
        const val ATTR_WET = 3
    }
}
