package com.rm.apogee.render

import android.opengl.GLES30
import com.rm.apogee.core.math.Vec3
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

/**
 * A planet's cloud as seen from far off: a thin veil over the whole globe, a
 * little above the ground, white where the decks are thick, faint where
 * they thin, and clear between - what cloud cover looks like from orbit.
 * One sheet, the opacity at each vertex; not a scatter of puffs, which from
 * a distance read as plates laid on the planet.
 *
 * Vertices in body radii, as the globe's: x, y, z, then r, g, b, a.
 */
class CloudShell(val vertices: FloatArray, val indices: IntArray, val revision: Int) {

    companion object {
        const val STRIDE = 7

        /** Latitude divisions; longitude gets twice as many. */
        const val RINGS = 64

        /** How high the veil floats, as a share of the body's radius, at most: over the mountains, under nothing. */
        private const val LIFT = 0.012

        /**
         * The veil for a body of [radius] m at [height] m up, the cover at
         * each vertex from [cover] (0..1, given a unit body-fixed direction)
         * and its shade from [shade] (1 white, lower for a storm's grey).
         */
        fun build(
            radius: Double,
            height: Double,
            revision: Int,
            cover: (Vec3) -> Double,
            shade: (Vec3) -> Double,
        ): CloudShell {
            val rings = RINGS
            val segments = rings * 2
            val lift = 1.0 + minOf(height / radius, LIFT)
            val vertices = FloatArray((rings + 1) * (segments + 1) * STRIDE)
            val indices = IntArray(rings * segments * 6)
            val direction = Vec3()
            var v = 0
            for (ring in 0..rings) {
                val phi = PI * ring / rings
                val y = cos(phi)
                val across = sin(phi)
                for (segment in 0..segments) {
                    val theta = 2.0 * PI * segment / segments
                    direction.setTo(across * cos(theta), y, across * sin(theta))
                    val amount = cover(direction)
                    // Nothing below a fifth - a clear sky - and near white
                    // where it is thick: the sea and land show between.
                    val t = ((amount - 0.2) / 0.75).coerceIn(0.0, 1.0)
                    val alpha = t * t * (3.0 - 2.0 * t) * 0.85
                    val grey = shade(direction).coerceIn(0.4, 1.0)
                    vertices[v] = (direction.x * lift).toFloat()
                    vertices[v + 1] = (direction.y * lift).toFloat()
                    vertices[v + 2] = (direction.z * lift).toFloat()
                    vertices[v + 3] = (0.96 * grey).toFloat()
                    vertices[v + 4] = (0.97 * grey).toFloat()
                    vertices[v + 5] = (1.0 * grey).toFloat()
                    vertices[v + 6] = alpha.toFloat()
                    v += STRIDE
                }
            }
            var k = 0
            for (ring in 0 until rings) for (segment in 0 until segments) {
                val a = ring * (segments + 1) + segment
                val b = a + segments + 1
                // Either way round: it is drawn from both sides, and the globe
                // in front hides the far half.
                indices[k++] = a; indices[k++] = a + 1; indices[k++] = b
                indices[k++] = a + 1; indices[k++] = b + 1; indices[k++] = b
            }
            return CloudShell(vertices, indices, revision)
        }
    }
}

/** A [CloudShell] on the GPU. */
class CloudShellMesh {
    private val vao = IntArray(1)
    private val buffers = IntArray(2)
    private var indexCount = 0
    var revision = -1
        private set

    init {
        GLES30.glGenVertexArrays(1, vao, 0)
        GLES30.glGenBuffers(2, buffers, 0)
        GLES30.glBindVertexArray(vao[0])
        GLES30.glBindBuffer(GLES30.GL_ARRAY_BUFFER, buffers[0])
        val stride = CloudShell.STRIDE * Float.SIZE_BYTES
        GLES30.glEnableVertexAttribArray(0)
        GLES30.glVertexAttribPointer(0, 3, GLES30.GL_FLOAT, false, stride, 0)
        GLES30.glEnableVertexAttribArray(1)
        GLES30.glVertexAttribPointer(1, 4, GLES30.GL_FLOAT, false, stride, 3 * Float.SIZE_BYTES)
        GLES30.glBindBuffer(GLES30.GL_ELEMENT_ARRAY_BUFFER, buffers[1])
        GLES30.glBindVertexArray(0)
    }

    fun upload(shell: CloudShell) {
        GLES30.glBindVertexArray(vao[0])
        val vertexBuffer = ByteBuffer.allocateDirect(shell.vertices.size * Float.SIZE_BYTES).order(ByteOrder.nativeOrder())
        vertexBuffer.asFloatBuffer().put(shell.vertices)
        vertexBuffer.position(0)
        GLES30.glBindBuffer(GLES30.GL_ARRAY_BUFFER, buffers[0])
        GLES30.glBufferData(GLES30.GL_ARRAY_BUFFER, shell.vertices.size * Float.SIZE_BYTES, vertexBuffer, GLES30.GL_DYNAMIC_DRAW)
        val indexBuffer = ByteBuffer.allocateDirect(shell.indices.size * Int.SIZE_BYTES).order(ByteOrder.nativeOrder())
        indexBuffer.asIntBuffer().put(shell.indices)
        indexBuffer.position(0)
        GLES30.glBindBuffer(GLES30.GL_ELEMENT_ARRAY_BUFFER, buffers[1])
        GLES30.glBufferData(GLES30.GL_ELEMENT_ARRAY_BUFFER, shell.indices.size * Int.SIZE_BYTES, indexBuffer, GLES30.GL_STATIC_DRAW)
        GLES30.glBindVertexArray(0)
        indexCount = shell.indices.size
        revision = shell.revision
    }

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
}
