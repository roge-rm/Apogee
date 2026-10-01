package com.rm.apogee.render

import com.rm.apogee.render.gl.GLES30
import com.rm.apogee.render.gl.GlData

/**
 * An indexed triangle mesh in GPU memory. Vertices are position(3) + normal(3) floats,
 * interleaved, matching the attribute locations in [Shaders.VESSEL_VERTEX].
 *
 * Every mesh is procedural, built by [MeshBuilder] at load time. The part schema's mesh field is a
 * tagged spec, so a `gltf:` variant could be added later.
 */
class Mesh(vertices: FloatArray, indices: IntArray) {

    private val vao = IntArray(1)
    private val buffers = IntArray(2)
    val indexCount = indices.size

    init {
        GLES30.glGenVertexArrays(1, vao, 0)
        GLES30.glGenBuffers(2, buffers, 0)

        GLES30.glBindVertexArray(vao[0])

        val vertexBytes = GlData(vertices.size * Float.SIZE_BYTES).apply { putFloats(vertices) }

        GLES30.glBindBuffer(GLES30.GL_ARRAY_BUFFER, buffers[0])
        GLES30.glBufferData(
            GLES30.GL_ARRAY_BUFFER,
            vertices.size * Float.SIZE_BYTES,
            vertexBytes,
            GLES30.GL_STATIC_DRAW,
        )

        val indexBytes = GlData(indices.size * Int.SIZE_BYTES).apply { putInts(indices) }

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

    /**
     * Draws [count] copies in one call, from instance [first] of [instances], a buffer of
     * [INSTANCE_FLOATS] floats each: model matrix, 1/scale^2, colour and ambient. See
     * [Shaders.CLOUD_INSTANCED_VERTEX]. The instance attributes are switched off again afterwards.
     */
    fun drawInstanced(instances: Int, first: Int, count: Int) {
        GLES30.glBindVertexArray(vao[0])
        GLES30.glBindBuffer(GLES30.GL_ARRAY_BUFFER, instances)
        val stride = INSTANCE_FLOATS * Float.SIZE_BYTES
        val base = first * stride
        // A divisor stays with the attribute and is ignored while it's off, so set it once.
        if (!divisorsSet) {
            for (location in ATTR_MODEL until ATTR_MODEL + INSTANCE_ATTRIBUTES) GLES30.glVertexAttribDivisor(location, 1)
            divisorsSet = true
        }
        for (column in 0 until INSTANCE_ATTRIBUTES) {
            GLES30.glEnableVertexAttribArray(ATTR_MODEL + column)
            GLES30.glVertexAttribPointer(ATTR_MODEL + column, 4, GLES30.GL_FLOAT, false, stride, base + column * 4 * Float.SIZE_BYTES)
        }
        GLES30.glDrawElementsInstanced(GLES30.GL_TRIANGLES, indexCount, GLES30.GL_UNSIGNED_INT, 0, count)
        for (location in ATTR_MODEL until ATTR_MODEL + INSTANCE_ATTRIBUTES) GLES30.glDisableVertexAttribArray(location)
        GLES30.glBindVertexArray(0)
    }

    private var divisorsSet = false

    fun release() {
        GLES30.glDeleteBuffers(2, buffers, 0)
        GLES30.glDeleteVertexArrays(1, vao, 0)
    }

    companion object {
        const val ATTR_POSITION = 0
        const val ATTR_NORMAL = 1
        const val STRIDE_FLOATS = 6

        /**
         * Instance attributes, six vec4s from [ATTR_MODEL]: a model matrix (four columns),
         * 1/scale^2 with the ambient as its fourth, and colour.
         */
        const val ATTR_MODEL = 2
        const val INSTANCE_ATTRIBUTES = 6
        const val INSTANCE_FLOATS = 24
    }
}

/** Uploads the primitives built by [MeshShapes], which needs no GL context and can be tested. */
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
