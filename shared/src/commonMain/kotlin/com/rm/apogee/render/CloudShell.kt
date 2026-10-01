package com.rm.apogee.render

import com.rm.apogee.render.gl.GLES30
import com.rm.apogee.render.gl.GlData
import com.rm.apogee.core.math.Vec3
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

/**
 * A planet's cloud seen from far off: one thin veil a little above the ground, white where decks
 * are thick, faint where they thin and clear between. Opacity is per vertex; puffs would look like
 * plates laid on the planet from this far.
 *
 * Vertices are in body radii, like the globe's: x, y, z, then r, g, b, a.
 */
class CloudShell(val vertices: FloatArray, val indices: IntArray, val revision: Int) {

    companion object {
        const val STRIDE = 7

        /** Latitude divisions. Longitude gets twice as many. */
        const val RINGS = 64

        /** The most the veil lifts, as a share of the body's radius. */
        private const val LIFT = 0.012

        /**
         * The veil for a body of [radius] m at [height] m up. [cover] gives the cover at each unit
         * body-fixed direction (0..1) and [shade] its shade (1 is white, lower for storm grey).
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
                    // Clear below a fifth and nearly white where thick, so sea and land show
                    // between.
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
                // Winding doesn't matter: it's drawn from both sides and the globe hides the far
                // half.
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
        val vertexBuffer = GlData(shell.vertices.size * Float.SIZE_BYTES)
        vertexBuffer.putFloats(shell.vertices)
        GLES30.glBindBuffer(GLES30.GL_ARRAY_BUFFER, buffers[0])
        GLES30.glBufferData(GLES30.GL_ARRAY_BUFFER, shell.vertices.size * Float.SIZE_BYTES, vertexBuffer, GLES30.GL_DYNAMIC_DRAW)
        val indexBuffer = GlData(shell.indices.size * Int.SIZE_BYTES)
        indexBuffer.putInts(shell.indices)
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
