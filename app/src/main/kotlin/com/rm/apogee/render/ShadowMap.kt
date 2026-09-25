package com.rm.apogee.render

import android.opengl.GLES30

/**
 * A depth texture drawn from the light's side, and the framebuffer that
 * draws it: whatever is nearest the light at each texel is what casts there.
 *
 * Set for comparison, so the shaders read it through `sampler2DShadow` and
 * the GPU does the test - and with linear filtering it blends the four
 * nearest results, which softens the edge by a texel for nothing.
 *
 * GL thread only; dies with the context.
 */
class ShadowMap(val size: Int) {
    private val names = IntArray(1)
    private val fbo = IntArray(1)
    val texture: Int get() = names[0]

    init {
        GLES30.glGenTextures(1, names, 0)
        GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, names[0])
        GLES30.glTexImage2D(
            GLES30.GL_TEXTURE_2D, 0, GLES30.GL_DEPTH_COMPONENT24, size, size, 0,
            GLES30.GL_DEPTH_COMPONENT, GLES30.GL_UNSIGNED_INT, null,
        )
        GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_MIN_FILTER, GLES30.GL_LINEAR)
        GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_MAG_FILTER, GLES30.GL_LINEAR)
        GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_WRAP_S, GLES30.GL_CLAMP_TO_EDGE)
        GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_WRAP_T, GLES30.GL_CLAMP_TO_EDGE)
        GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_COMPARE_MODE, GLES30.GL_COMPARE_REF_TO_TEXTURE)
        GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_COMPARE_FUNC, GLES30.GL_LEQUAL)
        GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, 0)

        GLES30.glGenFramebuffers(1, fbo, 0)
        GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, fbo[0])
        GLES30.glFramebufferTexture2D(GLES30.GL_FRAMEBUFFER, GLES30.GL_DEPTH_ATTACHMENT, GLES30.GL_TEXTURE_2D, names[0], 0)
        GLES30.glDrawBuffers(1, intArrayOf(GLES30.GL_NONE), 0)
        GLES30.glReadBuffer(GLES30.GL_NONE)
        GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, 0)
    }

    /**
     * Starts drawing into it: cleared to far, the craft's thin single-sided
     * fins and wings drawn both ways, and pushed back a touch so a surface
     * does not shade itself - by [slope] times its steepness as the light
     * sees it, plus [units] of the depth buffer's least step.
     */
    fun begin(slope: Float = 2f, units: Float = 4f) {
        GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, fbo[0])
        GLES30.glViewport(0, 0, size, size)
        GLES30.glClear(GLES30.GL_DEPTH_BUFFER_BIT)
        GLES30.glDisable(GLES30.GL_CULL_FACE)
        GLES30.glEnable(GLES30.GL_POLYGON_OFFSET_FILL)
        GLES30.glPolygonOffset(slope, units)
    }

    /** Back to the screen, [width] by [height]. */
    fun end(width: Int, height: Int) {
        GLES30.glDisable(GLES30.GL_POLYGON_OFFSET_FILL)
        GLES30.glEnable(GLES30.GL_CULL_FACE)
        GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, 0)
        GLES30.glViewport(0, 0, width, height)
    }

    fun release() {
        GLES30.glDeleteFramebuffers(1, fbo, 0)
        GLES30.glDeleteTextures(1, names, 0)
    }
}
