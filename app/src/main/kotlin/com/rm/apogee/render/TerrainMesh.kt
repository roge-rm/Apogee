package com.rm.apogee.render

import android.opengl.GLES30
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * A terrain mesh on the GPU: position, normal, elevation and slope.
 *
 * Separate from [Mesh] because of those extra attributes. Height and slope
 * are carried through to the fragment shader so the surface can be coloured
 * by what it is rather than by noise - which is the whole reason the shader
 * no longer generates terrain of its own.
 */
class TerrainMesh {

    private val vao = IntArray(1)
    private val buffers = IntArray(2)
    private var indexCount = 0
    private var vertexCapacity = 0
    private var indexCapacity = 0

    init {
        GLES30.glGenVertexArrays(1, vao, 0)
        GLES30.glGenBuffers(2, buffers, 0)

        GLES30.glBindVertexArray(vao[0])
        GLES30.glBindBuffer(GLES30.GL_ARRAY_BUFFER, buffers[0])

        val stride = PlanetMesh.STRIDE_FLOATS * Float.SIZE_BYTES
        GLES30.glEnableVertexAttribArray(ATTR_POSITION)
        GLES30.glVertexAttribPointer(ATTR_POSITION, 3, GLES30.GL_FLOAT, false, stride, 0)
        GLES30.glEnableVertexAttribArray(ATTR_NORMAL)
        GLES30.glVertexAttribPointer(
            ATTR_NORMAL, 3, GLES30.GL_FLOAT, false, stride, 3 * Float.SIZE_BYTES,
        )
        GLES30.glEnableVertexAttribArray(ATTR_ELEVATION)
        GLES30.glVertexAttribPointer(
            ATTR_ELEVATION, 1, GLES30.GL_FLOAT, false, stride, 6 * Float.SIZE_BYTES,
        )
        GLES30.glEnableVertexAttribArray(ATTR_SLOPE)
        GLES30.glVertexAttribPointer(
            ATTR_SLOPE, 1, GLES30.GL_FLOAT, false, stride, 7 * Float.SIZE_BYTES,
        )

        GLES30.glBindBuffer(GLES30.GL_ELEMENT_ARRAY_BUFFER, buffers[1])
        GLES30.glBindVertexArray(0)
    }

    /** Replaces the geometry. Grows the buffers only when it has to. */
    fun upload(data: PlanetMesh.Data) {
        indexCount = data.indices.size
        if (indexCount == 0) return

        GLES30.glBindVertexArray(vao[0])

        val vertexBytes = data.vertices.size * Float.SIZE_BYTES
        val vertexBuffer = ByteBuffer.allocateDirect(vertexBytes)
            .order(ByteOrder.nativeOrder())
            .apply { asFloatBuffer().put(data.vertices); position(0) }

        GLES30.glBindBuffer(GLES30.GL_ARRAY_BUFFER, buffers[0])
        if (vertexBytes > vertexCapacity) {
            GLES30.glBufferData(
                GLES30.GL_ARRAY_BUFFER, vertexBytes, vertexBuffer, GLES30.GL_STATIC_DRAW,
            )
            vertexCapacity = vertexBytes
        } else {
            GLES30.glBufferSubData(GLES30.GL_ARRAY_BUFFER, 0, vertexBytes, vertexBuffer)
        }

        val indexBytes = data.indices.size * Int.SIZE_BYTES
        val indexBuffer = ByteBuffer.allocateDirect(indexBytes)
            .order(ByteOrder.nativeOrder())
            .apply { asIntBuffer().put(data.indices); position(0) }

        GLES30.glBindBuffer(GLES30.GL_ELEMENT_ARRAY_BUFFER, buffers[1])
        if (indexBytes > indexCapacity) {
            GLES30.glBufferData(
                GLES30.GL_ELEMENT_ARRAY_BUFFER, indexBytes, indexBuffer, GLES30.GL_STATIC_DRAW,
            )
            indexCapacity = indexBytes
        } else {
            GLES30.glBufferSubData(GLES30.GL_ELEMENT_ARRAY_BUFFER, 0, indexBytes, indexBuffer)
        }

        GLES30.glBindVertexArray(0)
    }

    val isReady: Boolean get() = indexCount > 0

    fun draw() {
        if (indexCount == 0) return
        GLES30.glBindVertexArray(vao[0])
        GLES30.glDrawElements(GLES30.GL_TRIANGLES, indexCount, GLES30.GL_UNSIGNED_INT, 0)
        GLES30.glBindVertexArray(0)
    }

    fun release() {
        GLES30.glDeleteBuffers(2, buffers, 0)
        GLES30.glDeleteVertexArrays(1, vao, 0)
    }

    private companion object {
        const val ATTR_POSITION = 0
        const val ATTR_NORMAL = 1
        const val ATTR_ELEVATION = 2
        const val ATTR_SLOPE = 3
    }
}
