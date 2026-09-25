package com.rm.apogee.render

import android.opengl.GLES30
import com.rm.apogee.core.math.Mat4
import com.rm.apogee.core.math.Quat
import com.rm.apogee.core.math.Vec3
import com.rm.apogee.core.terrain.ScatterKind
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Draws scatter, instanced: one mesh per kind, one instance buffer per block.
 *
 * GL thread only. Every GL name in here dies with the context, so the whole
 * thing is rebuilt in [GlRenderer.onSurfaceCreated].
 */
class ScatterRenderer {

    private val program = ShaderProgram(Shaders.SCATTER_VERTEX, Shaders.SCATTER_FRAGMENT, "scatter")

    /** The same trees and rocks, swaying the same, drawn as depth only: into a shadow map. */
    private val depthProgram = ShaderProgram(Shaders.SCATTER_VERTEX, Shaders.DEPTH_FRAGMENT, "scatter-depth")

    /** Sets the lit program's shadow uniforms before a frame's draw. */
    var shadows: ((ShaderProgram) -> Unit)? = null
    private val kindVao = IntArray(ScatterDraw.KINDS)
    private val kindVbo = IntArray(ScatterDraw.KINDS)
    private val kindIbo = IntArray(ScatterDraw.KINDS)
    private val kindIndexCount = IntArray(ScatterDraw.KINDS)

    /** Block key to its instance buffer, and the revision uploaded into it. */
    private val blockBuffers = HashMap<Long, Pair<Int, Int>>()
    private val lastDrawn = HashMap<Long, Long>()
    private var frame = 0L

    private val model = Mat4()
    private val centre = Vec3()

    init {
        GLES30.glGenVertexArrays(ScatterDraw.KINDS, kindVao, 0)
        GLES30.glGenBuffers(ScatterDraw.KINDS, kindVbo, 0)
        GLES30.glGenBuffers(ScatterDraw.KINDS, kindIbo, 0)
        for (kind in ScatterKind.entries) {
            val data = ScatterMeshes.build(kind)
            val k = kind.ordinal
            kindIndexCount[k] = data.indices.size
            GLES30.glBindVertexArray(kindVao[k])
            GLES30.glBindBuffer(GLES30.GL_ARRAY_BUFFER, kindVbo[k])
            val vb = ByteBuffer.allocateDirect(data.vertices.size * 4).order(ByteOrder.nativeOrder())
            vb.asFloatBuffer().put(data.vertices); vb.position(0)
            GLES30.glBufferData(GLES30.GL_ARRAY_BUFFER, data.vertices.size * 4, vb, GLES30.GL_STATIC_DRAW)
            val stride = ScatterMeshes.STRIDE_FLOATS * 4
            GLES30.glEnableVertexAttribArray(0)
            GLES30.glVertexAttribPointer(0, 3, GLES30.GL_FLOAT, false, stride, 0)
            GLES30.glEnableVertexAttribArray(1)
            GLES30.glVertexAttribPointer(1, 3, GLES30.GL_FLOAT, false, stride, 12)
            GLES30.glEnableVertexAttribArray(2)
            GLES30.glVertexAttribPointer(2, 3, GLES30.GL_FLOAT, false, stride, 24)
            GLES30.glBindBuffer(GLES30.GL_ELEMENT_ARRAY_BUFFER, kindIbo[k])
            val ib = ByteBuffer.allocateDirect(data.indices.size * 2).order(ByteOrder.nativeOrder())
            ib.asShortBuffer().put(data.indices); ib.position(0)
            GLES30.glBufferData(GLES30.GL_ELEMENT_ARRAY_BUFFER, data.indices.size * 2, ib, GLES30.GL_STATIC_DRAW)
            GLES30.glEnableVertexAttribArray(3)
            GLES30.glVertexAttribDivisor(3, 1)
            GLES30.glEnableVertexAttribArray(4)
            GLES30.glVertexAttribDivisor(4, 1)
        }
        GLES30.glBindVertexArray(0)
    }

    fun draw(
        list: List<ScatterDraw>,
        bodyRotation: Quat,
        cameraPos: Vec3,
        cameraForward: Vec3,
        viewProjection: FloatArray,
        sun: Vec3,
        atmosphereFactor: Float,
        hazeDistance: Float,
        world: WorldView,
        /** How much sun reaches the camera, for the dark side. */
        daylight: Float = 1f,
        /** Weather fog's colour, dimmed for the time of day. */
        fogColor: FloatArray = world.fogColor,
    ) {
        if (list.isEmpty()) return
        frame++
        program.use()
        program.setMat4("uViewProjection", viewProjection)
        program.setVec3("uSunDirection", sun.x.toFloat(), sun.y.toFloat(), sun.z.toFloat())
        program.setFloat("uAtmosphereFactor", atmosphereFactor)
        program.setFloat("uHazeDistance", hazeDistance)
        program.setFloat("uLightScale", world.lightScale)
        program.setFloat("uFlash", world.flash)
        program.setFloat("uDaylight", daylight)
        program.setFloat("uFogDistance", world.fogDistance.toFloat())
        program.setVec3("uFogColor", fogColor[0], fogColor[1], fogColor[2])
        program.setFloat("uTime", (world.time % 10_000.0).toFloat())
        shadows?.invoke(program)
        val wind = world.surfaceWind
        // Faceted meshes built by hand: drawn both sides rather than trusting
        // every triangle's winding.
        GLES30.glDisable(GLES30.GL_CULL_FACE)

        var uploads = 0
        for (block in list) {
            if (block.offsets.isEmpty()) continue
            var buffer = blockBuffers[block.key]
            if (buffer == null || buffer.second != block.revision) {
                if (uploads >= MAX_UPLOADS_PER_FRAME) continue
                val data = block.instances ?: continue
                val id = buffer?.first ?: IntArray(1).also { GLES30.glGenBuffers(1, it, 0) }[0]
                GLES30.glBindBuffer(GLES30.GL_ARRAY_BUFFER, id)
                val bb = ByteBuffer.allocateDirect(maxOf(data.size, 1) * 4).order(ByteOrder.nativeOrder())
                bb.asFloatBuffer().put(data); bb.position(0)
                GLES30.glBufferData(GLES30.GL_ARRAY_BUFFER, data.size * 4, bb, GLES30.GL_STATIC_DRAW)
                buffer = id to block.revision
                blockBuffers[block.key] = buffer
                block.instances = null
                uploads++
            }

            bodyRotation.rotate(block.centre, centre)
            centre.subInPlace(cameraPos)
            if ((centre dot cameraForward) < -block.boundingRadius) continue
            centre.addInPlace(cameraPos)
            model.setFromTrs(centre, bodyRotation, cameraPos)
            program.setMat4("uModel", model.m)
            program.setMat3("uBasis", block.basis)
            // The wind across this block, in its own east and north.
            val b = block.basis
            program.setVec3(
                "uWind",
                (wind.x * b[0] + wind.y * b[1] + wind.z * b[2]).toFloat(),
                0f,
                (wind.x * b[6] + wind.y * b[7] + wind.z * b[8]).toFloat(),
            )
            lastDrawn[block.key] = frame

            val stride = ScatterDraw.INSTANCE_FLOATS * 4
            for (k in 0 until ScatterDraw.KINDS) {
                val count = block.counts[k]
                if (count == 0) continue
                GLES30.glBindVertexArray(kindVao[k])
                GLES30.glBindBuffer(GLES30.GL_ARRAY_BUFFER, buffer.first)
                val base = block.offsets[k] * stride
                GLES30.glVertexAttribPointer(3, 4, GLES30.GL_FLOAT, false, stride, base)
                GLES30.glVertexAttribPointer(4, 1, GLES30.GL_FLOAT, false, stride, base + 16)
                GLES30.glDrawElementsInstanced(
                    GLES30.GL_TRIANGLES, kindIndexCount[k], GLES30.GL_UNSIGNED_SHORT, 0, count,
                )
            }
        }
        GLES30.glBindVertexArray(0)
        GLES30.glEnable(GLES30.GL_CULL_FACE)
        evict(list)
    }

    /**
     * Draws what is already uploaded within [reach] of [focus] (absolute)
     * into a shadow map with [viewProjection]: casters only, nothing new
     * uploaded - a block not yet on the GPU casts from the next frame.
     */
    fun drawDepth(
        list: List<ScatterDraw>,
        bodyRotation: Quat,
        cameraPos: Vec3,
        viewProjection: FloatArray,
        world: WorldView,
        focus: Vec3,
        reach: Double,
    ) {
        if (list.isEmpty()) return
        depthProgram.use()
        depthProgram.setMat4("uViewProjection", viewProjection)
        depthProgram.setFloat("uTime", (world.time % 10_000.0).toFloat())
        val wind = world.surfaceWind
        for (block in list) {
            if (block.offsets.isEmpty()) continue
            val buffer = blockBuffers[block.key] ?: continue
            if (buffer.second != block.revision) continue
            bodyRotation.rotate(block.centre, centre)
            if (centre.distanceTo(focus) > reach + block.boundingRadius) continue
            model.setFromTrs(centre, bodyRotation, cameraPos)
            depthProgram.setMat4("uModel", model.m)
            depthProgram.setMat3("uBasis", block.basis)
            val b = block.basis
            depthProgram.setVec3(
                "uWind",
                (wind.x * b[0] + wind.y * b[1] + wind.z * b[2]).toFloat(),
                0f,
                (wind.x * b[6] + wind.y * b[7] + wind.z * b[8]).toFloat(),
            )
            val stride = ScatterDraw.INSTANCE_FLOATS * 4
            for (k in 0 until ScatterDraw.KINDS) {
                val count = block.counts[k]
                if (count == 0) continue
                GLES30.glBindVertexArray(kindVao[k])
                GLES30.glBindBuffer(GLES30.GL_ARRAY_BUFFER, buffer.first)
                val base = block.offsets[k] * stride
                GLES30.glVertexAttribPointer(3, 4, GLES30.GL_FLOAT, false, stride, base)
                GLES30.glVertexAttribPointer(4, 1, GLES30.GL_FLOAT, false, stride, base + 16)
                GLES30.glDrawElementsInstanced(
                    GLES30.GL_TRIANGLES, kindIndexCount[k], GLES30.GL_UNSIGNED_SHORT, 0, count,
                )
            }
        }
        GLES30.glBindVertexArray(0)
    }

    private fun evict(current: List<ScatterDraw>) {
        if (blockBuffers.size <= MAX_BLOCKS) return
        val inUse = current.mapTo(HashSet()) { it.key }
        val victims = blockBuffers.keys.filter { it !in inUse }.sortedBy { lastDrawn[it] ?: 0L }
        for (key in victims.take(blockBuffers.size - MAX_BLOCKS)) {
            blockBuffers.remove(key)?.let { GLES30.glDeleteBuffers(1, intArrayOf(it.first), 0) }
            lastDrawn.remove(key)
        }
    }

    fun release() {
        program.release()
        depthProgram.release()
        GLES30.glDeleteVertexArrays(ScatterDraw.KINDS, kindVao, 0)
        GLES30.glDeleteBuffers(ScatterDraw.KINDS, kindVbo, 0)
        GLES30.glDeleteBuffers(ScatterDraw.KINDS, kindIbo, 0)
        for ((id, _) in blockBuffers.values) GLES30.glDeleteBuffers(1, intArrayOf(id), 0)
        blockBuffers.clear()
    }

    private companion object {
        const val MAX_UPLOADS_PER_FRAME = 16
        const val MAX_BLOCKS = 400
    }
}
