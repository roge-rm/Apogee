package com.rm.apogee.render.gl

import org.lwjgl.opengl.GL11C
import org.lwjgl.opengl.GL13C
import org.lwjgl.opengl.GL15C
import org.lwjgl.opengl.GL20C
import org.lwjgl.opengl.GL30C
import org.lwjgl.opengl.GL31C
import org.lwjgl.opengl.GL33C
import org.lwjgl.system.MemoryUtil

/**
 * OpenGL ES 3.0 on a desktop's OpenGL 3.3 core context, through LWJGL. The calls are the same;
 * sizes go by the byte counts given, as on Android, so buffers are passed as addresses.
 */
actual object GLES30 : GlConstants {
    /**
     * The framebuffer the world draws to when it asks for the screen (0), set by the desktop's
     * off-screen view. Only used from the GL thread.
     */
    var screenFramebuffer = 0

    actual fun glActiveTexture(texture: Int) = GL13C.glActiveTexture(texture)
    actual fun glAttachShader(program: Int, shader: Int) = GL20C.glAttachShader(program, shader)
    actual fun glBindBuffer(target: Int, buffer: Int) = GL15C.glBindBuffer(target, buffer)
    actual fun glBindFramebuffer(target: Int, framebuffer: Int) =
        GL30C.glBindFramebuffer(target, if (framebuffer == 0) screenFramebuffer else framebuffer)
    actual fun glBindRenderbuffer(target: Int, renderbuffer: Int) = GL30C.glBindRenderbuffer(target, renderbuffer)
    actual fun glBindTexture(target: Int, texture: Int) = GL11C.glBindTexture(target, texture)
    actual fun glBindVertexArray(array: Int) = GL30C.glBindVertexArray(array)
    actual fun glBlendFunc(sfactor: Int, dfactor: Int) = GL11C.glBlendFunc(sfactor, dfactor)
    actual fun glBufferData(target: Int, size: Int, data: GlData?, usage: Int) =
        GL15C.nglBufferData(target, size.toLong(), data?.let { MemoryUtil.memAddress(it.buffer) } ?: 0L, usage)
    actual fun glBufferSubData(target: Int, offset: Int, size: Int, data: GlData) =
        GL15C.nglBufferSubData(target, offset.toLong(), size.toLong(), MemoryUtil.memAddress(data.buffer))
    actual fun glClear(mask: Int) = GL11C.glClear(mask)
    actual fun glClearColor(red: Float, green: Float, blue: Float, alpha: Float) = GL11C.glClearColor(red, green, blue, alpha)
    actual fun glCompileShader(shader: Int) = GL20C.glCompileShader(shader)
    actual fun glCreateProgram(): Int = GL20C.glCreateProgram()
    actual fun glCreateShader(type: Int): Int = GL20C.glCreateShader(type)
    actual fun glCullFace(mode: Int) = GL11C.glCullFace(mode)
    actual fun glDeleteBuffers(n: Int, buffers: IntArray, offset: Int) = GL15C.glDeleteBuffers(buffers.copyOfRange(offset, offset + n))
    actual fun glDeleteFramebuffers(n: Int, framebuffers: IntArray, offset: Int) = GL30C.glDeleteFramebuffers(framebuffers.copyOfRange(offset, offset + n))
    actual fun glDeleteRenderbuffers(n: Int, renderbuffers: IntArray, offset: Int) = GL30C.glDeleteRenderbuffers(renderbuffers.copyOfRange(offset, offset + n))
    actual fun glDeleteProgram(program: Int) = GL20C.glDeleteProgram(program)
    actual fun glDeleteShader(shader: Int) = GL20C.glDeleteShader(shader)
    actual fun glDeleteTextures(n: Int, textures: IntArray, offset: Int) = GL11C.glDeleteTextures(textures.copyOfRange(offset, offset + n))
    actual fun glDeleteVertexArrays(n: Int, arrays: IntArray, offset: Int) = GL30C.glDeleteVertexArrays(arrays.copyOfRange(offset, offset + n))
    actual fun glDepthMask(flag: Boolean) = GL11C.glDepthMask(flag)
    actual fun glDisable(cap: Int) = GL11C.glDisable(cap)
    actual fun glDisableVertexAttribArray(index: Int) = GL20C.glDisableVertexAttribArray(index)
    actual fun glDrawArrays(mode: Int, first: Int, count: Int) = GL11C.glDrawArrays(mode, first, count)
    actual fun glDrawBuffers(n: Int, bufs: IntArray, offset: Int) = GL20C.glDrawBuffers(bufs.copyOfRange(offset, offset + n))
    actual fun glDrawElements(mode: Int, count: Int, type: Int, offset: Int) = GL11C.glDrawElements(mode, count, type, offset.toLong())
    actual fun glDrawElementsInstanced(mode: Int, count: Int, type: Int, offset: Int, instanceCount: Int) =
        GL31C.glDrawElementsInstanced(mode, count, type, offset.toLong(), instanceCount)
    actual fun glEnable(cap: Int) = GL11C.glEnable(cap)
    actual fun glEnableVertexAttribArray(index: Int) = GL20C.glEnableVertexAttribArray(index)
    actual fun glFinish() = GL11C.glFinish()
    actual fun glFramebufferRenderbuffer(target: Int, attachment: Int, renderbuffertarget: Int, renderbuffer: Int) =
        GL30C.glFramebufferRenderbuffer(target, attachment, renderbuffertarget, renderbuffer)
    actual fun glFramebufferTexture2D(target: Int, attachment: Int, textarget: Int, texture: Int, level: Int) =
        GL30C.glFramebufferTexture2D(target, attachment, textarget, texture, level)
    actual fun glGenBuffers(n: Int, buffers: IntArray, offset: Int) { for (i in 0 until n) buffers[offset + i] = GL15C.glGenBuffers() }
    actual fun glGenFramebuffers(n: Int, framebuffers: IntArray, offset: Int) { for (i in 0 until n) framebuffers[offset + i] = GL30C.glGenFramebuffers() }
    actual fun glGenRenderbuffers(n: Int, renderbuffers: IntArray, offset: Int) { for (i in 0 until n) renderbuffers[offset + i] = GL30C.glGenRenderbuffers() }
    actual fun glGenTextures(n: Int, textures: IntArray, offset: Int) { for (i in 0 until n) textures[offset + i] = GL11C.glGenTextures() }
    actual fun glGenVertexArrays(n: Int, arrays: IntArray, offset: Int) { for (i in 0 until n) arrays[offset + i] = GL30C.glGenVertexArrays() }
    actual fun glGetProgramInfoLog(program: Int): String = GL20C.glGetProgramInfoLog(program)
    actual fun glGetProgramiv(program: Int, pname: Int, params: IntArray, offset: Int) { params[offset] = GL20C.glGetProgrami(program, pname) }
    actual fun glGetShaderInfoLog(shader: Int): String = GL20C.glGetShaderInfoLog(shader)
    actual fun glGetShaderiv(shader: Int, pname: Int, params: IntArray, offset: Int) { params[offset] = GL20C.glGetShaderi(shader, pname) }
    actual fun glGetString(name: Int): String? = GL11C.glGetString(name)
    actual fun glGetUniformLocation(program: Int, name: String): Int = GL20C.glGetUniformLocation(program, name)
    actual fun glLinkProgram(program: Int) = GL20C.glLinkProgram(program)
    actual fun glPixelStorei(pname: Int, param: Int) = GL11C.glPixelStorei(pname, param)
    actual fun glPolygonOffset(factor: Float, units: Float) = GL11C.glPolygonOffset(factor, units)
    actual fun glReadBuffer(mode: Int) = GL11C.glReadBuffer(mode)
    actual fun glReadPixels(x: Int, y: Int, width: Int, height: Int, format: Int, type: Int, pixels: GlData) =
        GL11C.nglReadPixels(x, y, width, height, format, type, MemoryUtil.memAddress(pixels.buffer))
    actual fun glRenderbufferStorage(target: Int, internalformat: Int, width: Int, height: Int) =
        GL30C.glRenderbufferStorage(target, internalformat, width, height)
    actual fun glShaderSource(shader: Int, string: String) = GL20C.glShaderSource(shader, desktopShader(string))
    actual fun glTexImage2D(target: Int, level: Int, internalformat: Int, width: Int, height: Int, border: Int, format: Int, type: Int, pixels: GlData?) =
        GL11C.nglTexImage2D(target, level, internalformat, width, height, border, format, type, pixels?.let { MemoryUtil.memAddress(it.buffer) } ?: 0L)
    actual fun glTexParameteri(target: Int, pname: Int, param: Int) = GL11C.glTexParameteri(target, pname, param)
    actual fun glUniform1f(location: Int, x: Float) = GL20C.glUniform1f(location, x)
    actual fun glUniform1i(location: Int, x: Int) = GL20C.glUniform1i(location, x)
    actual fun glUniform3f(location: Int, x: Float, y: Float, z: Float) = GL20C.glUniform3f(location, x, y, z)
    actual fun glUniform4f(location: Int, x: Float, y: Float, z: Float, w: Float) = GL20C.glUniform4f(location, x, y, z, w)
    actual fun glUniform4fv(location: Int, count: Int, v: FloatArray, offset: Int) = GL20C.glUniform4fv(location, slice(v, offset, count * 4))
    actual fun glUniformMatrix3fv(location: Int, count: Int, transpose: Boolean, value: FloatArray, offset: Int) =
        GL20C.glUniformMatrix3fv(location, transpose, slice(value, offset, count * 9))
    actual fun glUniformMatrix4fv(location: Int, count: Int, transpose: Boolean, value: FloatArray, offset: Int) =
        GL20C.glUniformMatrix4fv(location, transpose, slice(value, offset, count * 16))
    actual fun glUseProgram(program: Int) = GL20C.glUseProgram(program)
    actual fun glVertexAttribDivisor(index: Int, divisor: Int) = GL33C.glVertexAttribDivisor(index, divisor)
    actual fun glVertexAttribPointer(index: Int, size: Int, type: Int, normalized: Boolean, stride: Int, offset: Int) =
        GL20C.glVertexAttribPointer(index, size, type, normalized, stride, offset.toLong())
    actual fun glViewport(x: Int, y: Int, width: Int, height: Int) = GL11C.glViewport(x, y, width, height)

    /** The [count] floats of [array] from [offset], without a copy when that's all of it. */
    private fun slice(array: FloatArray, offset: Int, count: Int): FloatArray =
        if (offset == 0 && array.size == count) array else array.copyOfRange(offset, offset + count)
}

/**
 * An ES 3.0 shader as desktop GL 3.3 core reads it: the same language once its version line says
 * so. Precision lines stay; desktop GLSL accepts and ignores them.
 */
internal fun desktopShader(source: String): String =
    source.replaceFirst("#version 300 es", "#version 330 core")

actual class GlData actual constructor(bytes: Int) {
    val buffer: java.nio.ByteBuffer = java.nio.ByteBuffer.allocateDirect(bytes).order(java.nio.ByteOrder.nativeOrder())

    actual val capacity: Int get() = buffer.capacity()

    actual fun putFloats(src: FloatArray, count: Int) {
        buffer.clear(); buffer.asFloatBuffer().put(src, 0, count); buffer.position(0)
    }

    actual fun putInts(src: IntArray, count: Int) {
        buffer.clear(); buffer.asIntBuffer().put(src, 0, count); buffer.position(0)
    }

    actual fun putShorts(src: ShortArray, count: Int) {
        buffer.clear(); buffer.asShortBuffer().put(src, 0, count); buffer.position(0)
    }

    actual fun putBytes(src: ByteArray, count: Int) {
        buffer.clear(); buffer.put(src, 0, count); buffer.position(0)
    }

    actual fun getBytes(dst: ByteArray, count: Int) {
        buffer.rewind(); buffer.get(dst, 0, count); buffer.rewind()
    }
}
