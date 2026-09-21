package com.rm.apogee.render

import android.opengl.GLES30
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * An indexed triangle mesh living in GPU memory.
 *
 * Vertex layout is interleaved position(3) + normal(3), both float, matching
 * the attribute locations declared in [Shaders.VESSEL_VERTEX].
 *
 * Part meshes are procedural for now - a part definition names a shape and
 * dimensions rather than a model file - so every mesh in the game is built by
 * [MeshBuilder] at load time. That keeps the whole asset pipeline out of the
 * early milestones without painting us into a corner: the part schema's mesh
 * field is a tagged spec, so a `gltf:` variant can be added later without
 * touching any existing part.
 */
class Mesh(vertices: FloatArray, indices: IntArray) {

    private val vao = IntArray(1)
    private val buffers = IntArray(2)
    val indexCount = indices.size

    init {
        GLES30.glGenVertexArrays(1, vao, 0)
        GLES30.glGenBuffers(2, buffers, 0)

        GLES30.glBindVertexArray(vao[0])

        val vertexBytes = ByteBuffer
            .allocateDirect(vertices.size * Float.SIZE_BYTES)
            .order(ByteOrder.nativeOrder())
            .apply { asFloatBuffer().put(vertices); position(0) }

        GLES30.glBindBuffer(GLES30.GL_ARRAY_BUFFER, buffers[0])
        GLES30.glBufferData(
            GLES30.GL_ARRAY_BUFFER,
            vertices.size * Float.SIZE_BYTES,
            vertexBytes,
            GLES30.GL_STATIC_DRAW,
        )

        val indexBytes = ByteBuffer
            .allocateDirect(indices.size * Int.SIZE_BYTES)
            .order(ByteOrder.nativeOrder())
            .apply { asIntBuffer().put(indices); position(0) }

        GLES30.glBindBuffer(GLES30.GL_ELEMENT_ARRAY_BUFFER, buffers[1])
        GLES30.glBufferData(
            GLES30.GL_ELEMENT_ARRAY_BUFFER,
            indices.size * Int.SIZE_BYTES,
            indexBytes,
            GLES30.GL_STATIC_DRAW,
        )

        val stride = STRIDE_FLOATS * Float.SIZE_BYTES
        GLES30.glEnableVertexAttribArray(ATTR_POSITION)
        GLES30.glVertexAttribPointer(ATTR_POSITION, 3, GLES30.GL_FLOAT, false, stride, 0)
        GLES30.glEnableVertexAttribArray(ATTR_NORMAL)
        GLES30.glVertexAttribPointer(
            ATTR_NORMAL, 3, GLES30.GL_FLOAT, false, stride, 3 * Float.SIZE_BYTES,
        )

        GLES30.glBindVertexArray(0)
    }

    fun draw() {
        GLES30.glBindVertexArray(vao[0])
        GLES30.glDrawElements(GLES30.GL_TRIANGLES, indexCount, GLES30.GL_UNSIGNED_INT, 0)
        GLES30.glBindVertexArray(0)
    }

    fun release() {
        GLES30.glDeleteBuffers(2, buffers, 0)
        GLES30.glDeleteVertexArrays(1, vao, 0)
    }

    companion object {
        const val ATTR_POSITION = 0
        const val ATTR_NORMAL = 1
        const val STRIDE_FLOATS = 6
    }
}

/**
 * Builds the procedural primitives part definitions refer to.
 *
 * Units are metres, and shapes are centred on the origin with +Y as the axis of
 * revolution - a rocket's "up". That convention is what lets a stack of parts
 * be positioned purely by their attachment nodes.
 */
object MeshBuilder {

    fun box(halfExtentX: Float, halfExtentY: Float, halfExtentZ: Float): Mesh {
        val vertices = ArrayList<Float>(6 * 4 * Mesh.STRIDE_FLOATS)
        val indices = ArrayList<Int>(36)

        // Each face gets its own four vertices so the normals stay flat rather
        // than being averaged across the edges into a rounded-looking cube.
        val faces = arrayOf(
            // normal, then the four corners counter-clockwise when seen from outside
            floatArrayOf(0f, 0f, 1f), floatArrayOf(0f, 0f, -1f),
            floatArrayOf(1f, 0f, 0f), floatArrayOf(-1f, 0f, 0f),
            floatArrayOf(0f, 1f, 0f), floatArrayOf(0f, -1f, 0f),
        )

        for (normal in faces) {
            val base = vertices.size / Mesh.STRIDE_FLOATS
            // Build an orthonormal basis for the face from its normal.
            val up = if (kotlin.math.abs(normal[1]) > 0.9f) {
                floatArrayOf(0f, 0f, 1f)
            } else {
                floatArrayOf(0f, 1f, 0f)
            }
            val right = cross(up, normal)
            val realUp = cross(normal, right)

            for ((su, sv) in listOf(-1f to -1f, 1f to -1f, 1f to 1f, -1f to 1f)) {
                val px = (normal[0] + right[0] * su + realUp[0] * sv) * halfExtentX
                val py = (normal[1] + right[1] * su + realUp[1] * sv) * halfExtentY
                val pz = (normal[2] + right[2] * su + realUp[2] * sv) * halfExtentZ
                vertices.add(px); vertices.add(py); vertices.add(pz)
                vertices.add(normal[0]); vertices.add(normal[1]); vertices.add(normal[2])
            }
            indices.add(base); indices.add(base + 1); indices.add(base + 2)
            indices.add(base); indices.add(base + 2); indices.add(base + 3)
        }

        return Mesh(vertices.toFloatArray(), indices.toIntArray())
    }

    fun cube(halfExtent: Float = 0.5f): Mesh = box(halfExtent, halfExtent, halfExtent)

    private fun cross(a: FloatArray, b: FloatArray) = floatArrayOf(
        a[1] * b[2] - a[2] * b[1],
        a[2] * b[0] - a[0] * b[2],
        a[0] * b[1] - a[1] * b[0],
    )
}
