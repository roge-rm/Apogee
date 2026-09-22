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
 * Uploads the procedural primitives to the GPU.
 *
 * The geometry itself is built by [MeshShapes], which needs no GL context and
 * can therefore be tested; this is only the upload step.
 */
object MeshBuilder {

    fun box(halfExtentX: Float, halfExtentY: Float, halfExtentZ: Float): Mesh =
        MeshShapes.box(halfExtentX, halfExtentY, halfExtentZ).toMesh()

    fun cube(halfExtent: Float = 0.5f): Mesh = box(halfExtent, halfExtent, halfExtent)

    fun frustum(
        bottomRadius: Float,
        topRadius: Float,
        height: Float,
        segments: Int = 20,
        caps: Int = StackCaps.BOTH,
    ): Mesh = MeshShapes.frustum(bottomRadius, topRadius, height, segments, caps).toMesh()

    fun cylinder(
        radius: Float,
        height: Float,
        segments: Int = 20,
        caps: Int = StackCaps.BOTH,
    ): Mesh = MeshShapes.cylinder(radius, height, segments, caps).toMesh()

    fun sphere(radius: Float, rings: Int = 12, segments: Int = 20): Mesh =
        MeshShapes.sphere(radius, rings, segments).toMesh()

    private fun MeshData.toMesh() = Mesh(vertices, indices)
}
