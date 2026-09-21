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

    /**
     * A cone frustum about the +Y axis. Covers cylinders too - a cylinder is
     * just a frustum whose radii match - so tanks, engine bells, nose cones and
     * pods are all one code path.
     */
    fun frustum(
        bottomRadius: Float,
        topRadius: Float,
        height: Float,
        segments: Int = 20,
    ): Mesh {
        val vertices = ArrayList<Float>()
        val indices = ArrayList<Int>()
        val halfHeight = height * 0.5f

        // Side normals tilt with the slope, so a cone shades like a cone rather
        // than like a cylinder someone squashed.
        val slope = (bottomRadius - topRadius) / height
        val normalScale = 1f / kotlin.math.sqrt(1f + slope * slope)

        // Each segment gets its own four vertices rather than sharing a seam,
        // so the side normals stay per-segment and the silhouette reads cleanly.
        for (i in 0 until segments) {
            val quad = vertices.size / Mesh.STRIDE_FLOATS

            for (step in 0..1) {
                val angle = (2.0 * Math.PI * (i + step) / segments).toFloat()
                val cos = kotlin.math.cos(angle)
                val sin = kotlin.math.sin(angle)
                val nx = cos * normalScale
                val nz = sin * normalScale
                val ny = slope * normalScale

                // Bottom rim, then top rim, at this angle.
                vertices.add(cos * bottomRadius)
                vertices.add(-halfHeight)
                vertices.add(sin * bottomRadius)
                vertices.add(nx); vertices.add(ny); vertices.add(nz)

                vertices.add(cos * topRadius)
                vertices.add(halfHeight)
                vertices.add(sin * topRadius)
                vertices.add(nx); vertices.add(ny); vertices.add(nz)
            }

            // quad+0 bottom-left, +1 top-left, +2 bottom-right, +3 top-right.
            indices.add(quad); indices.add(quad + 2); indices.add(quad + 3)
            indices.add(quad); indices.add(quad + 3); indices.add(quad + 1)
        }

        addCap(vertices, indices, topRadius, halfHeight, 1f, segments)
        addCap(vertices, indices, bottomRadius, -halfHeight, -1f, segments)

        return Mesh(vertices.toFloatArray(), indices.toIntArray())
    }

    fun cylinder(radius: Float, height: Float, segments: Int = 20): Mesh =
        frustum(radius, radius, height, segments)

    /** A UV sphere. Used for spherical tanks and, later, celestial bodies. */
    fun sphere(radius: Float, rings: Int = 12, segments: Int = 20): Mesh {
        val vertices = ArrayList<Float>()
        val indices = ArrayList<Int>()

        for (ring in 0..rings) {
            val phi = Math.PI * ring / rings
            val y = kotlin.math.cos(phi).toFloat()
            val ringRadius = kotlin.math.sin(phi).toFloat()
            for (segment in 0..segments) {
                val theta = 2.0 * Math.PI * segment / segments
                val x = (ringRadius * kotlin.math.cos(theta)).toFloat()
                val z = (ringRadius * kotlin.math.sin(theta)).toFloat()
                vertices.add(x * radius); vertices.add(y * radius); vertices.add(z * radius)
                vertices.add(x); vertices.add(y); vertices.add(z)
            }
        }

        val stride = segments + 1
        for (ring in 0 until rings) {
            for (segment in 0 until segments) {
                val a = ring * stride + segment
                val b = a + stride
                indices.add(a); indices.add(b); indices.add(a + 1)
                indices.add(a + 1); indices.add(b); indices.add(b + 1)
            }
        }
        return Mesh(vertices.toFloatArray(), indices.toIntArray())
    }

    /** A flat disc closing one end of a frustum. */
    private fun addCap(
        vertices: ArrayList<Float>,
        indices: ArrayList<Int>,
        radius: Float,
        y: Float,
        normalY: Float,
        segments: Int,
    ) {
        if (radius <= 0f) return
        val centre = vertices.size / Mesh.STRIDE_FLOATS
        vertices.add(0f); vertices.add(y); vertices.add(0f)
        vertices.add(0f); vertices.add(normalY); vertices.add(0f)

        for (i in 0..segments) {
            val angle = (2.0 * Math.PI * i / segments).toFloat()
            vertices.add(kotlin.math.cos(angle) * radius)
            vertices.add(y)
            vertices.add(kotlin.math.sin(angle) * radius)
            vertices.add(0f); vertices.add(normalY); vertices.add(0f)
        }
        for (i in 0 until segments) {
            val a = centre + 1 + i
            val b = centre + 2 + i
            // Wind the two caps oppositely so back-face culling keeps both.
            if (normalY > 0f) {
                indices.add(centre); indices.add(a); indices.add(b)
            } else {
                indices.add(centre); indices.add(b); indices.add(a)
            }
        }
    }

    private fun cross(a: FloatArray, b: FloatArray) = floatArrayOf(
        a[1] * b[2] - a[2] * b[1],
        a[2] * b[0] - a[0] * b[2],
        a[0] * b[1] - a[1] * b[0],
    )
}
