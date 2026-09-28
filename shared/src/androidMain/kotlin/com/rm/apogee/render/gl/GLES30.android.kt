package com.rm.apogee.render.gl

import android.opengl.GLES30 as Gl

actual object GLES30 : GlConstants {
    actual fun glActiveTexture(texture: Int) = Gl.glActiveTexture(texture)
    actual fun glAttachShader(program: Int, shader: Int) = Gl.glAttachShader(program, shader)
    actual fun glBindBuffer(target: Int, buffer: Int) = Gl.glBindBuffer(target, buffer)
    actual fun glBindFramebuffer(target: Int, framebuffer: Int) = Gl.glBindFramebuffer(target, framebuffer)
    actual fun glBindRenderbuffer(target: Int, renderbuffer: Int) = Gl.glBindRenderbuffer(target, renderbuffer)
    actual fun glBindTexture(target: Int, texture: Int) = Gl.glBindTexture(target, texture)
    actual fun glBindVertexArray(array: Int) = Gl.glBindVertexArray(array)
    actual fun glBlendFunc(sfactor: Int, dfactor: Int) = Gl.glBlendFunc(sfactor, dfactor)
    actual fun glBufferData(target: Int, size: Int, data: GlData?, usage: Int) = Gl.glBufferData(target, size, data?.buffer, usage)
    actual fun glBufferSubData(target: Int, offset: Int, size: Int, data: GlData) = Gl.glBufferSubData(target, offset, size, data.buffer)
    actual fun glClear(mask: Int) = Gl.glClear(mask)
    actual fun glClearColor(red: Float, green: Float, blue: Float, alpha: Float) = Gl.glClearColor(red, green, blue, alpha)
    actual fun glCompileShader(shader: Int) = Gl.glCompileShader(shader)
    actual fun glCreateProgram(): Int = Gl.glCreateProgram()
    actual fun glCreateShader(type: Int): Int = Gl.glCreateShader(type)
    actual fun glCullFace(mode: Int) = Gl.glCullFace(mode)
    actual fun glDeleteBuffers(n: Int, buffers: IntArray, offset: Int) = Gl.glDeleteBuffers(n, buffers, offset)
    actual fun glDeleteFramebuffers(n: Int, framebuffers: IntArray, offset: Int) = Gl.glDeleteFramebuffers(n, framebuffers, offset)
    actual fun glDeleteProgram(program: Int) = Gl.glDeleteProgram(program)
    actual fun glDeleteShader(shader: Int) = Gl.glDeleteShader(shader)
    actual fun glDeleteTextures(n: Int, textures: IntArray, offset: Int) = Gl.glDeleteTextures(n, textures, offset)
    actual fun glDeleteVertexArrays(n: Int, arrays: IntArray, offset: Int) = Gl.glDeleteVertexArrays(n, arrays, offset)
    actual fun glDepthMask(flag: Boolean) = Gl.glDepthMask(flag)
    actual fun glDisable(cap: Int) = Gl.glDisable(cap)
    actual fun glDisableVertexAttribArray(index: Int) = Gl.glDisableVertexAttribArray(index)
    actual fun glDrawArrays(mode: Int, first: Int, count: Int) = Gl.glDrawArrays(mode, first, count)
    actual fun glDrawBuffers(n: Int, bufs: IntArray, offset: Int) = Gl.glDrawBuffers(n, bufs, offset)
    actual fun glDrawElements(mode: Int, count: Int, type: Int, offset: Int) = Gl.glDrawElements(mode, count, type, offset)
    actual fun glDrawElementsInstanced(mode: Int, count: Int, type: Int, offset: Int, instanceCount: Int) = Gl.glDrawElementsInstanced(mode, count, type, offset, instanceCount)
    actual fun glEnable(cap: Int) = Gl.glEnable(cap)
    actual fun glEnableVertexAttribArray(index: Int) = Gl.glEnableVertexAttribArray(index)
    actual fun glFinish() = Gl.glFinish()
    actual fun glFramebufferRenderbuffer(target: Int, attachment: Int, renderbuffertarget: Int, renderbuffer: Int) = Gl.glFramebufferRenderbuffer(target, attachment, renderbuffertarget, renderbuffer)
    actual fun glFramebufferTexture2D(target: Int, attachment: Int, textarget: Int, texture: Int, level: Int) = Gl.glFramebufferTexture2D(target, attachment, textarget, texture, level)
    actual fun glGenBuffers(n: Int, buffers: IntArray, offset: Int) = Gl.glGenBuffers(n, buffers, offset)
    actual fun glGenFramebuffers(n: Int, framebuffers: IntArray, offset: Int) = Gl.glGenFramebuffers(n, framebuffers, offset)
    actual fun glGenRenderbuffers(n: Int, renderbuffers: IntArray, offset: Int) = Gl.glGenRenderbuffers(n, renderbuffers, offset)
    actual fun glGenTextures(n: Int, textures: IntArray, offset: Int) = Gl.glGenTextures(n, textures, offset)
    actual fun glGenVertexArrays(n: Int, arrays: IntArray, offset: Int) = Gl.glGenVertexArrays(n, arrays, offset)
    actual fun glGetProgramInfoLog(program: Int): String = Gl.glGetProgramInfoLog(program)
    actual fun glGetProgramiv(program: Int, pname: Int, params: IntArray, offset: Int) = Gl.glGetProgramiv(program, pname, params, offset)
    actual fun glGetShaderInfoLog(shader: Int): String = Gl.glGetShaderInfoLog(shader)
    actual fun glGetShaderiv(shader: Int, pname: Int, params: IntArray, offset: Int) = Gl.glGetShaderiv(shader, pname, params, offset)
    actual fun glGetString(name: Int): String? = Gl.glGetString(name)
    actual fun glGetUniformLocation(program: Int, name: String): Int = Gl.glGetUniformLocation(program, name)
    actual fun glLinkProgram(program: Int) = Gl.glLinkProgram(program)
    actual fun glPixelStorei(pname: Int, param: Int) = Gl.glPixelStorei(pname, param)
    actual fun glPolygonOffset(factor: Float, units: Float) = Gl.glPolygonOffset(factor, units)
    actual fun glReadBuffer(mode: Int) = Gl.glReadBuffer(mode)
    actual fun glReadPixels(x: Int, y: Int, width: Int, height: Int, format: Int, type: Int, pixels: GlData) = Gl.glReadPixels(x, y, width, height, format, type, pixels.buffer)
    actual fun glRenderbufferStorage(target: Int, internalformat: Int, width: Int, height: Int) = Gl.glRenderbufferStorage(target, internalformat, width, height)
    actual fun glShaderSource(shader: Int, string: String) = Gl.glShaderSource(shader, string)
    actual fun glTexImage2D(target: Int, level: Int, internalformat: Int, width: Int, height: Int, border: Int, format: Int, type: Int, pixels: GlData?) = Gl.glTexImage2D(target, level, internalformat, width, height, border, format, type, pixels?.buffer)
    actual fun glTexParameteri(target: Int, pname: Int, param: Int) = Gl.glTexParameteri(target, pname, param)
    actual fun glUniform1f(location: Int, x: Float) = Gl.glUniform1f(location, x)
    actual fun glUniform1i(location: Int, x: Int) = Gl.glUniform1i(location, x)
    actual fun glUniform3f(location: Int, x: Float, y: Float, z: Float) = Gl.glUniform3f(location, x, y, z)
    actual fun glUniform4f(location: Int, x: Float, y: Float, z: Float, w: Float) = Gl.glUniform4f(location, x, y, z, w)
    actual fun glUniform4fv(location: Int, count: Int, v: FloatArray, offset: Int) = Gl.glUniform4fv(location, count, v, offset)
    actual fun glUniformMatrix3fv(location: Int, count: Int, transpose: Boolean, value: FloatArray, offset: Int) = Gl.glUniformMatrix3fv(location, count, transpose, value, offset)
    actual fun glUniformMatrix4fv(location: Int, count: Int, transpose: Boolean, value: FloatArray, offset: Int) = Gl.glUniformMatrix4fv(location, count, transpose, value, offset)
    actual fun glUseProgram(program: Int) = Gl.glUseProgram(program)
    actual fun glVertexAttribDivisor(index: Int, divisor: Int) = Gl.glVertexAttribDivisor(index, divisor)
    actual fun glVertexAttribPointer(index: Int, size: Int, type: Int, normalized: Boolean, stride: Int, offset: Int) = Gl.glVertexAttribPointer(index, size, type, normalized, stride, offset)
    actual fun glViewport(x: Int, y: Int, width: Int, height: Int) = Gl.glViewport(x, y, width, height)
}

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
