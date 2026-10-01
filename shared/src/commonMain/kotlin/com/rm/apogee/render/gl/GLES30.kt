package com.rm.apogee.render.gl

/**
 * The OpenGL ES 3.0 the renderer uses, named as in `android.opengl.GLES30`. On Android it's that;
 * in a browser it's WebGL2. Where Android takes a `java.nio` buffer, these take a [GlData].
 */
expect object GLES30 : GlConstants {
    fun glActiveTexture(texture: Int)
    fun glAttachShader(program: Int, shader: Int)
    fun glBindBuffer(target: Int, buffer: Int)
    fun glBindFramebuffer(target: Int, framebuffer: Int)
    fun glBindRenderbuffer(target: Int, renderbuffer: Int)
    fun glBindTexture(target: Int, texture: Int)
    fun glBindVertexArray(array: Int)
    fun glBlendFunc(sfactor: Int, dfactor: Int)
    fun glBufferData(target: Int, size: Int, data: GlData?, usage: Int)
    fun glBufferSubData(target: Int, offset: Int, size: Int, data: GlData)
    fun glClear(mask: Int)
    fun glClearColor(red: Float, green: Float, blue: Float, alpha: Float)
    fun glCompileShader(shader: Int)
    fun glCreateProgram(): Int
    fun glCreateShader(type: Int): Int
    fun glCullFace(mode: Int)
    fun glDeleteBuffers(n: Int, buffers: IntArray, offset: Int)
    fun glDeleteFramebuffers(n: Int, framebuffers: IntArray, offset: Int)
    fun glDeleteProgram(program: Int)
    fun glDeleteShader(shader: Int)
    fun glDeleteTextures(n: Int, textures: IntArray, offset: Int)
    fun glDeleteVertexArrays(n: Int, arrays: IntArray, offset: Int)
    fun glDepthMask(flag: Boolean)
    fun glDisable(cap: Int)
    fun glDisableVertexAttribArray(index: Int)
    fun glDrawArrays(mode: Int, first: Int, count: Int)
    fun glDrawBuffers(n: Int, bufs: IntArray, offset: Int)
    fun glDrawElements(mode: Int, count: Int, type: Int, offset: Int)
    fun glDrawElementsInstanced(mode: Int, count: Int, type: Int, offset: Int, instanceCount: Int)
    fun glEnable(cap: Int)
    fun glEnableVertexAttribArray(index: Int)
    fun glFinish()
    fun glFramebufferRenderbuffer(target: Int, attachment: Int, renderbuffertarget: Int, renderbuffer: Int)
    fun glFramebufferTexture2D(target: Int, attachment: Int, textarget: Int, texture: Int, level: Int)
    fun glGenBuffers(n: Int, buffers: IntArray, offset: Int)
    fun glGenFramebuffers(n: Int, framebuffers: IntArray, offset: Int)
    fun glGenRenderbuffers(n: Int, renderbuffers: IntArray, offset: Int)
    fun glGenTextures(n: Int, textures: IntArray, offset: Int)
    fun glGenVertexArrays(n: Int, arrays: IntArray, offset: Int)
    fun glGetProgramInfoLog(program: Int): String
    fun glGetProgramiv(program: Int, pname: Int, params: IntArray, offset: Int)
    fun glGetShaderInfoLog(shader: Int): String
    fun glGetShaderiv(shader: Int, pname: Int, params: IntArray, offset: Int)
    fun glGetString(name: Int): String?
    fun glGetUniformLocation(program: Int, name: String): Int
    fun glLinkProgram(program: Int)
    fun glPixelStorei(pname: Int, param: Int)
    fun glPolygonOffset(factor: Float, units: Float)
    fun glReadBuffer(mode: Int)
    fun glReadPixels(x: Int, y: Int, width: Int, height: Int, format: Int, type: Int, pixels: GlData)
    fun glRenderbufferStorage(target: Int, internalformat: Int, width: Int, height: Int)
    fun glShaderSource(shader: Int, string: String)
    fun glTexImage2D(target: Int, level: Int, internalformat: Int, width: Int, height: Int, border: Int, format: Int, type: Int, pixels: GlData?)
    fun glTexParameteri(target: Int, pname: Int, param: Int)
    fun glUniform1f(location: Int, x: Float)
    fun glUniform1i(location: Int, x: Int)
    fun glUniform3f(location: Int, x: Float, y: Float, z: Float)
    fun glUniform4f(location: Int, x: Float, y: Float, z: Float, w: Float)
    fun glUniform4fv(location: Int, count: Int, v: FloatArray, offset: Int)
    fun glUniformMatrix3fv(location: Int, count: Int, transpose: Boolean, value: FloatArray, offset: Int)
    fun glUniformMatrix4fv(location: Int, count: Int, transpose: Boolean, value: FloatArray, offset: Int)
    fun glUseProgram(program: Int)
    fun glVertexAttribDivisor(index: Int, divisor: Int)
    fun glVertexAttribPointer(index: Int, size: Int, type: Int, normalized: Boolean, stride: Int, offset: Int)
    fun glViewport(x: Int, y: Int, width: Int, height: Int)
}

/** The constants [GLES30] uses. Their values are the same in OpenGL ES and in WebGL2. */
interface GlConstants {
    val GL_ARRAY_BUFFER: Int get() = 0x8892
    val GL_BACK: Int get() = 0x0405
    val GL_BLEND: Int get() = 0x0BE2
    val GL_CLAMP_TO_EDGE: Int get() = 0x812F
    val GL_COLOR_ATTACHMENT0: Int get() = 0x8CE0
    val GL_COLOR_BUFFER_BIT: Int get() = 0x4000
    val GL_COMPARE_REF_TO_TEXTURE: Int get() = 0x884E
    val GL_COMPILE_STATUS: Int get() = 0x8B81
    val GL_CULL_FACE: Int get() = 0x0B44
    val GL_DEPTH_ATTACHMENT: Int get() = 0x8D00
    val GL_DEPTH_BUFFER_BIT: Int get() = 0x0100
    val GL_DEPTH_COMPONENT: Int get() = 0x1902
    val GL_DEPTH_COMPONENT24: Int get() = 0x81A6
    val GL_DEPTH_TEST: Int get() = 0x0B71
    val GL_DYNAMIC_DRAW: Int get() = 0x88E8
    val GL_ELEMENT_ARRAY_BUFFER: Int get() = 0x8893
    val GL_FLOAT: Int get() = 0x1406
    val GL_FRAGMENT_SHADER: Int get() = 0x8B30
    val GL_FRAMEBUFFER: Int get() = 0x8D40
    val GL_LEQUAL: Int get() = 0x0203
    val GL_LINEAR: Int get() = 0x2601
    val GL_LINE_STRIP: Int get() = 0x0003
    val GL_LINK_STATUS: Int get() = 0x8B82
    val GL_NONE: Int get() = 0
    val GL_ONE_MINUS_SRC_ALPHA: Int get() = 0x0303
    val GL_POLYGON_OFFSET_FILL: Int get() = 0x8037
    val GL_RENDERBUFFER: Int get() = 0x8D41
    val GL_RENDERER: Int get() = 0x1F01
    val GL_RG: Int get() = 0x8227
    val GL_RG8: Int get() = 0x822B
    val GL_RGBA: Int get() = 0x1908
    val GL_RGBA8: Int get() = 0x8058
    val GL_SRC_ALPHA: Int get() = 0x0302
    val GL_STATIC_DRAW: Int get() = 0x88E4
    val GL_STREAM_DRAW: Int get() = 0x88E0
    val GL_TEXTURE0: Int get() = 0x84C0
    val GL_TEXTURE1: Int get() = 0x84C1
    val GL_TEXTURE2: Int get() = 0x84C2
    val GL_TEXTURE3: Int get() = 0x84C3
    val GL_TEXTURE_2D: Int get() = 0x0DE1
    val GL_TEXTURE_COMPARE_FUNC: Int get() = 0x884D
    val GL_TEXTURE_COMPARE_MODE: Int get() = 0x884C
    val GL_TEXTURE_MAG_FILTER: Int get() = 0x2800
    val GL_TEXTURE_MIN_FILTER: Int get() = 0x2801
    val GL_TEXTURE_WRAP_S: Int get() = 0x2802
    val GL_TEXTURE_WRAP_T: Int get() = 0x2803
    val GL_TRIANGLES: Int get() = 0x0004
    val GL_UNPACK_ALIGNMENT: Int get() = 0x0CF5
    val GL_UNSIGNED_BYTE: Int get() = 0x1401
    val GL_UNSIGNED_INT: Int get() = 0x1405
    val GL_UNSIGNED_SHORT: Int get() = 0x1403
    val GL_VERTEX_SHADER: Int get() = 0x8B31
}

/** Bytes for OpenGL: a direct buffer on Android, a typed array in a browser. Fills write from the start. */
expect class GlData(bytes: Int) {
    /** Room, in bytes. */
    val capacity: Int

    /** Fills it from the start with [count] floats from [src]. */
    fun putFloats(src: FloatArray, count: Int = src.size)

    /** Fills it from the start with [count] ints from [src]. */
    fun putInts(src: IntArray, count: Int = src.size)

    /** Fills it from the start with [count] shorts from [src]. */
    fun putShorts(src: ShortArray, count: Int = src.size)

    /** Fills it from the start with [count] bytes from [src]. */
    fun putBytes(src: ByteArray, count: Int = src.size)

    /** Copies its first [count] bytes into [dst]. */
    fun getBytes(dst: ByteArray, count: Int = dst.size)
}
