package com.rm.apogee.render

import com.rm.apogee.render.gl.GLES30
import com.rm.apogee.render.gl.GlData

/**
 * A polyline in GPU memory as a ribbon of quads, one per segment, that the vertex shader turns to
 * face the screen at a set width. Each vertex is its own end, the segment's other end, and which
 * side it's on. Orbits change every frame, so the buffer is `GL_DYNAMIC_DRAW` and only grows when
 * it must.
 */
class LineMesh {

    private val vao = IntArray(1)
    private val vbo = IntArray(1)
    private var capacityFloats = 0
    private var vertexCount = 0
    private var scratch = FloatArray(0)

    init {
        GLES30.glGenVertexArrays(1, vao, 0)
        GLES30.glGenBuffers(1, vbo, 0)

        GLES30.glBindVertexArray(vao[0])
        GLES30.glBindBuffer(GLES30.GL_ARRAY_BUFFER, vbo[0])
        val stride = FLOATS * Float.SIZE_BYTES
        GLES30.glEnableVertexAttribArray(0)
        GLES30.glVertexAttribPointer(0, 3, GLES30.GL_FLOAT, false, stride, 0)
        GLES30.glEnableVertexAttribArray(1)
        GLES30.glVertexAttribPointer(1, 3, GLES30.GL_FLOAT, false, stride, 3 * Float.SIZE_BYTES)
        GLES30.glEnableVertexAttribArray(2)
        GLES30.glVertexAttribPointer(2, 1, GLES30.GL_FLOAT, false, stride, 6 * Float.SIZE_BYTES)
        GLES30.glBindVertexArray(0)
    }

    /**
     * Replaces the ribbon with the segments of [points] (x,y,z triples, [count] points) whose
     * index passes [keep] (segment k joins point k and k + 1).
     */
    fun upload(points: FloatArray, count: Int, keep: (Int) -> Boolean) {
        var segments = 0
        for (k in 0 until count - 1) if (keep(k)) segments++
        vertexCount = segments * 6
        if (vertexCount == 0) return
        val floats = vertexCount * FLOATS
        if (scratch.size < floats) scratch = FloatArray(floats)
        var o = 0
        for (k in 0 until count - 1) {
            if (!keep(k)) continue
            val a = k * 3
            val b = a + 3
            // Two triangles: A-, A+, B+ and A-, B+, B-. Seen from B, A is the other end, so B's
            // side is flipped to stay on the same edge.
            for ((end, side) in QUAD) {
                val here = if (end == 0) a else b
                val there = if (end == 0) b else a
                scratch[o] = points[here]; scratch[o + 1] = points[here + 1]; scratch[o + 2] = points[here + 2]
                scratch[o + 3] = points[there]; scratch[o + 4] = points[there + 1]; scratch[o + 5] = points[there + 2]
                scratch[o + 6] = if (end == 0) side else -side
                o += FLOATS
            }
        }

        GLES30.glBindVertexArray(vao[0])
        GLES30.glBindBuffer(GLES30.GL_ARRAY_BUFFER, vbo[0])
        val buffer = GlData(floats * Float.SIZE_BYTES).apply { putFloats(scratch, floats) }
        if (floats > capacityFloats) {
            // Reallocate to grow. A partial update past the end of a buffer is undefined.
            GLES30.glBufferData(GLES30.GL_ARRAY_BUFFER, floats * Float.SIZE_BYTES, buffer, GLES30.GL_DYNAMIC_DRAW)
            capacityFloats = floats
        } else {
            GLES30.glBufferSubData(GLES30.GL_ARRAY_BUFFER, 0, floats * Float.SIZE_BYTES, buffer)
        }
        GLES30.glBindVertexArray(0)
    }

    fun draw() {
        if (vertexCount < 3) return
        GLES30.glBindVertexArray(vao[0])
        GLES30.glDrawArrays(GLES30.GL_TRIANGLES, 0, vertexCount)
        GLES30.glBindVertexArray(0)
    }

    fun release() {
        GLES30.glDeleteBuffers(1, vbo, 0)
        GLES30.glDeleteVertexArrays(1, vao, 0)
    }

    private companion object {
        /** Position, the other end, and side. */
        const val FLOATS = 7

        /** The quad's six corners: which end (0 = A, 1 = B), and side. */
        val QUAD = listOf(0 to -1f, 0 to 1f, 1 to 1f, 0 to -1f, 1 to 1f, 1 to -1f)
    }
}
