@file:OptIn(ExperimentalWasmJsInterop::class, UnsafeWasmMemoryApi::class)

package com.rm.apogee.render.gl

import kotlin.js.ExperimentalWasmJsInterop
import kotlin.wasm.unsafe.Pointer
import kotlin.wasm.unsafe.UnsafeWasmMemoryApi
import kotlin.wasm.unsafe.withScopedMemoryAllocator

/*
 * GLES30 as WebGL2. WebGL objects are kept in [objects] and the renderer gets the index; 0 is
 * "none", as in OpenGL. Uniform locations have their own table.
 *
 * Crossing into JavaScript is costly, so arrays are written into this module's memory and WebGL
 * gets a view of those bytes.
 */

/** The page's WebGL2 context, set by the page before anything is drawn. */
var webGl: JsAny? = null

private fun gl(): JsAny = webGl ?: error("no WebGL2 context")

private val objects = ArrayList<JsAny?>().apply { add(null) }
private val freeSlots = ArrayList<Int>()
private val uniforms = ArrayList<JsAny?>().apply { add(null) }

private fun keep(o: JsAny?): Int {
    if (o == null) return 0
    if (freeSlots.isNotEmpty()) { val i = freeSlots.removeAt(freeSlots.size - 1); objects[i] = o; return i }
    objects.add(o); return objects.size - 1
}
private fun obj(i: Int): JsAny? = if (i <= 0 || i >= objects.size) null else objects[i]
private fun forget(i: Int) { if (i > 0 && i < objects.size && objects[i] != null) { objects[i] = null; freeSlots.add(i) } }

// The bridges, one per call.
private fun jsCall0(g: JsAny, name: String): Unit = js("g[name]()")
private fun jsCallI(g: JsAny, name: String, a: Int): Unit = js("g[name](a)")
private fun jsCallII(g: JsAny, name: String, a: Int, b: Int): Unit = js("g[name](a, b)")
private fun jsCallIII(g: JsAny, name: String, a: Int, b: Int, c: Int): Unit = js("g[name](a, b, c)")
private fun jsCallIIII(g: JsAny, name: String, a: Int, b: Int, c: Int, d: Int): Unit = js("g[name](a, b, c, d)")
private fun jsCreate(g: JsAny, name: String): JsAny? = js("g[name]()")
private fun jsCreateI(g: JsAny, name: String, a: Int): JsAny? = js("g[name](a)")
private fun jsWithObject(g: JsAny, name: String, o: JsAny?): Unit = js("g[name](o)")
private fun jsBindObject(g: JsAny, name: String, target: Int, o: JsAny?): Unit = js("g[name](target, o)")
private fun jsTwoObjects(g: JsAny, name: String, a: JsAny?, b: JsAny?): Unit = js("g[name](a, b)")
private fun jsBufferDataSize(g: JsAny, target: Int, size: Int, usage: Int): Unit = js("g.bufferData(target, size, usage)")
private fun jsBufferData(g: JsAny, target: Int, at: Int, size: Int, usage: Int): Unit = js("g.bufferData(target, new Uint8Array(wasmExports.memory.buffer, at, size), usage)")
private fun jsBufferSubData(g: JsAny, target: Int, offset: Int, at: Int, size: Int): Unit = js("g.bufferSubData(target, offset, new Uint8Array(wasmExports.memory.buffer, at, size))")
private fun jsClearColor(g: JsAny, r: Double, gr: Double, b: Double, a: Double): Unit = js("g.clearColor(r, gr, b, a)")
private fun jsDepthMask(g: JsAny, flag: Boolean): Unit = js("g.depthMask(flag)")
private fun jsDrawBuffers1(g: JsAny, a: Int): Unit = js("g.drawBuffers([a])")
private fun jsDrawElementsInstanced(g: JsAny, mode: Int, count: Int, type: Int, offset: Int, n: Int): Unit = js("g.drawElementsInstanced(mode, count, type, offset, n)")
private fun jsFramebufferRenderbuffer(g: JsAny, t: Int, a: Int, rt: Int, rb: JsAny?): Unit = js("g.framebufferRenderbuffer(t, a, rt, rb)")
private fun jsFramebufferTexture2D(g: JsAny, t: Int, a: Int, tt: Int, tex: JsAny?, level: Int): Unit = js("g.framebufferTexture2D(t, a, tt, tex, level)")
private fun jsProgramInfoLog(g: JsAny, p: JsAny?): String = js("g.getProgramInfoLog(p) || ''")
private fun jsShaderInfoLog(g: JsAny, s: JsAny?): String = js("g.getShaderInfoLog(s) || ''")
private fun jsProgramParameter(g: JsAny, p: JsAny?, pname: Int): Int = js("Number(g.getProgramParameter(p, pname))")
private fun jsShaderParameter(g: JsAny, s: JsAny?, pname: Int): Int = js("Number(g.getShaderParameter(s, pname))")
private fun jsGetString(g: JsAny, pname: Int): String? = js("(function(){ var d = g.getExtension('WEBGL_debug_renderer_info'); var v = g.getParameter(d && pname == 0x1F01 ? d.UNMASKED_RENDERER_WEBGL : pname); return v == null ? null : String(v); })()")
private fun jsUniformLocation(g: JsAny, p: JsAny?, name: String): JsAny? = js("g.getUniformLocation(p, name)")
private fun jsPolygonOffset(g: JsAny, f: Double, u: Double): Unit = js("g.polygonOffset(f, u)")
private fun jsReadPixels(g: JsAny, x: Int, y: Int, w: Int, h: Int, f: Int, t: Int, at: Int, size: Int): Unit = js("g.readPixels(x, y, w, h, f, t, new Uint8Array(wasmExports.memory.buffer, at, size))")
private fun jsShaderSource(g: JsAny, s: JsAny?, src: String): Unit = js("g.shaderSource(s, src)")
private fun jsTexImage2D(g: JsAny, t: Int, level: Int, internal: Int, w: Int, h: Int, border: Int, f: Int, type: Int): Unit = js("g.texImage2D(t, level, internal, w, h, border, f, type, null)")
private fun jsTexImage2DFrom(g: JsAny, t: Int, level: Int, internal: Int, w: Int, h: Int, border: Int, f: Int, type: Int, at: Int, size: Int): Unit = js("g.texImage2D(t, level, internal, w, h, border, f, type, new Uint8Array(wasmExports.memory.buffer, at, size))")
private fun jsUniform1f(g: JsAny, l: JsAny?, x: Double): Unit = js("g.uniform1f(l, x)")
private fun jsUniform1i(g: JsAny, l: JsAny?, x: Int): Unit = js("g.uniform1i(l, x)")
private fun jsUniform3f(g: JsAny, l: JsAny?, x: Double, y: Double, z: Double): Unit = js("g.uniform3f(l, x, y, z)")
private fun jsUniform4f(g: JsAny, l: JsAny?, x: Double, y: Double, z: Double, w: Double): Unit = js("g.uniform4f(l, x, y, z, w)")
private fun jsUniform4fv(g: JsAny, l: JsAny?, at: Int, count: Int): Unit = js("g.uniform4fv(l, new Float32Array(wasmExports.memory.buffer, at, count))")
private fun jsUniformMatrix(g: JsAny, name: String, l: JsAny?, transpose: Boolean, at: Int, count: Int): Unit = js("g[name](l, transpose, new Float32Array(wasmExports.memory.buffer, at, count))")
private fun jsVertexAttribPointer(g: JsAny, i: Int, size: Int, type: Int, norm: Boolean, stride: Int, offset: Int): Unit = js("g.vertexAttribPointer(i, size, type, norm, stride, offset)")

/** [count] floats of [src] from [offset], put in this module's memory for [use] to hand over. */
private inline fun withFloats(src: FloatArray, offset: Int, count: Int, use: (at: Int) -> Unit) {
    withScopedMemoryAllocator { memory ->
        val at = memory.allocate(count * 4)
        for (k in 0 until count) (at + k * 4).storeInt(src[offset + k].toRawBits())
        use(at.address.toInt())
    }
}

actual object GLES30 : GlConstants {
    actual fun glActiveTexture(texture: Int) = jsCallI(gl(), "activeTexture", texture)
    actual fun glAttachShader(program: Int, shader: Int) = jsTwoObjects(gl(), "attachShader", obj(program), obj(shader))
    actual fun glBindBuffer(target: Int, buffer: Int) = jsBindObject(gl(), "bindBuffer", target, obj(buffer))
    actual fun glBindFramebuffer(target: Int, framebuffer: Int) = jsBindObject(gl(), "bindFramebuffer", target, obj(framebuffer))
    actual fun glBindRenderbuffer(target: Int, renderbuffer: Int) = jsBindObject(gl(), "bindRenderbuffer", target, obj(renderbuffer))
    actual fun glBindTexture(target: Int, texture: Int) = jsBindObject(gl(), "bindTexture", target, obj(texture))
    actual fun glBindVertexArray(array: Int) = jsWithObject(gl(), "bindVertexArray", obj(array))
    actual fun glBlendFunc(sfactor: Int, dfactor: Int) = jsCallII(gl(), "blendFunc", sfactor, dfactor)
    actual fun glBufferData(target: Int, size: Int, data: GlData?, usage: Int) {
        if (data == null) jsBufferDataSize(gl(), target, size, usage)
        else data.inMemory(size) { at -> jsBufferData(gl(), target, at, size, usage) }
    }
    actual fun glBufferSubData(target: Int, offset: Int, size: Int, data: GlData) =
        data.inMemory(size) { at -> jsBufferSubData(gl(), target, offset, at, size) }
    actual fun glClear(mask: Int) = jsCallI(gl(), "clear", mask)
    actual fun glClearColor(red: Float, green: Float, blue: Float, alpha: Float) = jsClearColor(gl(), red.toDouble(), green.toDouble(), blue.toDouble(), alpha.toDouble())
    actual fun glCompileShader(shader: Int) = jsWithObject(gl(), "compileShader", obj(shader))
    actual fun glCreateProgram(): Int = keep(jsCreate(gl(), "createProgram"))
    actual fun glCreateShader(type: Int): Int = keep(jsCreateI(gl(), "createShader", type))
    actual fun glCullFace(mode: Int) = jsCallI(gl(), "cullFace", mode)
    actual fun glDeleteBuffers(n: Int, buffers: IntArray, offset: Int) { for (k in 0 until n) { val i = buffers[offset + k]; jsWithObject(gl(), "deleteBuffer", obj(i)); forget(i) } }
    actual fun glDeleteFramebuffers(n: Int, framebuffers: IntArray, offset: Int) { for (k in 0 until n) { val i = framebuffers[offset + k]; jsWithObject(gl(), "deleteFramebuffer", obj(i)); forget(i) } }
    actual fun glDeleteProgram(program: Int) { jsWithObject(gl(), "deleteProgram", obj(program)); forget(program) }
    actual fun glDeleteShader(shader: Int) { jsWithObject(gl(), "deleteShader", obj(shader)); forget(shader) }
    actual fun glDeleteTextures(n: Int, textures: IntArray, offset: Int) { for (k in 0 until n) { val i = textures[offset + k]; jsWithObject(gl(), "deleteTexture", obj(i)); forget(i) } }
    actual fun glDeleteVertexArrays(n: Int, arrays: IntArray, offset: Int) { for (k in 0 until n) { val i = arrays[offset + k]; jsWithObject(gl(), "deleteVertexArray", obj(i)); forget(i) } }
    actual fun glDepthMask(flag: Boolean) = jsDepthMask(gl(), flag)
    actual fun glDisable(cap: Int) = jsCallI(gl(), "disable", cap)
    actual fun glDisableVertexAttribArray(index: Int) = jsCallI(gl(), "disableVertexAttribArray", index)
    actual fun glDrawArrays(mode: Int, first: Int, count: Int) = jsCallIII(gl(), "drawArrays", mode, first, count)
    actual fun glDrawBuffers(n: Int, bufs: IntArray, offset: Int) = jsDrawBuffers1(gl(), bufs[offset])
    actual fun glDrawElements(mode: Int, count: Int, type: Int, offset: Int) = jsCallIIII(gl(), "drawElements", mode, count, type, offset)
    actual fun glDrawElementsInstanced(mode: Int, count: Int, type: Int, offset: Int, instanceCount: Int) = jsDrawElementsInstanced(gl(), mode, count, type, offset, instanceCount)
    actual fun glEnable(cap: Int) = jsCallI(gl(), "enable", cap)
    actual fun glEnableVertexAttribArray(index: Int) = jsCallI(gl(), "enableVertexAttribArray", index)
    actual fun glFinish() = jsCall0(gl(), "finish")
    actual fun glFramebufferRenderbuffer(target: Int, attachment: Int, renderbuffertarget: Int, renderbuffer: Int) = jsFramebufferRenderbuffer(gl(), target, attachment, renderbuffertarget, obj(renderbuffer))
    actual fun glFramebufferTexture2D(target: Int, attachment: Int, textarget: Int, texture: Int, level: Int) = jsFramebufferTexture2D(gl(), target, attachment, textarget, obj(texture), level)
    actual fun glGenBuffers(n: Int, buffers: IntArray, offset: Int) { for (k in 0 until n) buffers[offset + k] = keep(jsCreate(gl(), "createBuffer")) }
    actual fun glGenFramebuffers(n: Int, framebuffers: IntArray, offset: Int) { for (k in 0 until n) framebuffers[offset + k] = keep(jsCreate(gl(), "createFramebuffer")) }
    actual fun glGenRenderbuffers(n: Int, renderbuffers: IntArray, offset: Int) { for (k in 0 until n) renderbuffers[offset + k] = keep(jsCreate(gl(), "createRenderbuffer")) }
    actual fun glGenTextures(n: Int, textures: IntArray, offset: Int) { for (k in 0 until n) textures[offset + k] = keep(jsCreate(gl(), "createTexture")) }
    actual fun glGenVertexArrays(n: Int, arrays: IntArray, offset: Int) { for (k in 0 until n) arrays[offset + k] = keep(jsCreate(gl(), "createVertexArray")) }
    actual fun glGetProgramInfoLog(program: Int): String = jsProgramInfoLog(gl(), obj(program))
    actual fun glGetProgramiv(program: Int, pname: Int, params: IntArray, offset: Int) { params[offset] = jsProgramParameter(gl(), obj(program), pname) }
    actual fun glGetShaderInfoLog(shader: Int): String = jsShaderInfoLog(gl(), obj(shader))
    actual fun glGetShaderiv(shader: Int, pname: Int, params: IntArray, offset: Int) { params[offset] = jsShaderParameter(gl(), obj(shader), pname) }
    actual fun glGetString(name: Int): String? = jsGetString(gl(), name)
    actual fun glGetUniformLocation(program: Int, name: String): Int { val l = jsUniformLocation(gl(), obj(program), name) ?: return -1; uniforms.add(l); return uniforms.size - 1 }
    actual fun glLinkProgram(program: Int) = jsWithObject(gl(), "linkProgram", obj(program))
    actual fun glPixelStorei(pname: Int, param: Int) = jsCallII(gl(), "pixelStorei", pname, param)
    actual fun glPolygonOffset(factor: Float, units: Float) = jsPolygonOffset(gl(), factor.toDouble(), units.toDouble())
    actual fun glReadBuffer(mode: Int) = jsCallI(gl(), "readBuffer", mode)
    actual fun glReadPixels(x: Int, y: Int, width: Int, height: Int, format: Int, type: Int, pixels: GlData) =
        pixels.fromMemory(width * height * 4) { at, size -> jsReadPixels(gl(), x, y, width, height, format, type, at, size) }
    actual fun glRenderbufferStorage(target: Int, internalformat: Int, width: Int, height: Int) = jsCallIIII(gl(), "renderbufferStorage", target, internalformat, width, height)
    actual fun glShaderSource(shader: Int, string: String) = jsShaderSource(gl(), obj(shader), string)
    actual fun glTexImage2D(target: Int, level: Int, internalformat: Int, width: Int, height: Int, border: Int, format: Int, type: Int, pixels: GlData?) {
        if (pixels == null) jsTexImage2D(gl(), target, level, internalformat, width, height, border, format, type)
        else pixels.inMemory(pixels.capacity) { at -> jsTexImage2DFrom(gl(), target, level, internalformat, width, height, border, format, type, at, pixels.capacity) }
    }
    actual fun glTexParameteri(target: Int, pname: Int, param: Int) = jsCallIII(gl(), "texParameteri", target, pname, param)
    actual fun glUniform1f(location: Int, x: Float) = jsUniform1f(gl(), loc(location), x.toDouble())
    actual fun glUniform1i(location: Int, x: Int) = jsUniform1i(gl(), loc(location), x)
    actual fun glUniform3f(location: Int, x: Float, y: Float, z: Float) = jsUniform3f(gl(), loc(location), x.toDouble(), y.toDouble(), z.toDouble())
    actual fun glUniform4f(location: Int, x: Float, y: Float, z: Float, w: Float) = jsUniform4f(gl(), loc(location), x.toDouble(), y.toDouble(), z.toDouble(), w.toDouble())
    actual fun glUniform4fv(location: Int, count: Int, v: FloatArray, offset: Int) =
        withFloats(v, offset, count * 4) { at -> jsUniform4fv(gl(), loc(location), at, count * 4) }
    actual fun glUniformMatrix3fv(location: Int, count: Int, transpose: Boolean, value: FloatArray, offset: Int) =
        withFloats(value, offset, count * 9) { at -> jsUniformMatrix(gl(), "uniformMatrix3fv", loc(location), transpose, at, count * 9) }
    actual fun glUniformMatrix4fv(location: Int, count: Int, transpose: Boolean, value: FloatArray, offset: Int) =
        withFloats(value, offset, count * 16) { at -> jsUniformMatrix(gl(), "uniformMatrix4fv", loc(location), transpose, at, count * 16) }
    actual fun glUseProgram(program: Int) = jsWithObject(gl(), "useProgram", obj(program))
    actual fun glVertexAttribDivisor(index: Int, divisor: Int) = jsCallII(gl(), "vertexAttribDivisor", index, divisor)
    actual fun glVertexAttribPointer(index: Int, size: Int, type: Int, normalized: Boolean, stride: Int, offset: Int) = jsVertexAttribPointer(gl(), index, size, type, normalized, stride, offset)
    actual fun glViewport(x: Int, y: Int, width: Int, height: Int) = jsCallIIII(gl(), "viewport", x, y, width, height)

    private fun loc(i: Int): JsAny? = if (i <= 0 || i >= uniforms.size) null else uniforms[i]
}

/**
 * Bytes for the GPU, as little-endian words in an IntArray (fast to write in WebAssembly), copied
 * into the module's memory when a call needs them ([inMemory]).
 */
actual class GlData actual constructor(bytes: Int) {
    private val words = IntArray((bytes + 3) / 4)

    actual val capacity: Int = bytes

    actual fun putFloats(src: FloatArray, count: Int) {
        for (k in 0 until count) words[k] = src[k].toRawBits()
    }

    actual fun putInts(src: IntArray, count: Int) {
        src.copyInto(words, 0, 0, count)
    }

    actual fun putShorts(src: ShortArray, count: Int) {
        var k = 0
        while (k + 1 < count) {
            words[k shr 1] = (src[k].toInt() and 0xFFFF) or (src[k + 1].toInt() shl 16)
            k += 2
        }
        if (k < count) words[k shr 1] = (words[k shr 1] and 0xFFFF.inv()) or (src[k].toInt() and 0xFFFF)
    }

    actual fun putBytes(src: ByteArray, count: Int) {
        for (k in 0 until count) {
            val shift = (k and 3) * 8
            val w = k shr 2
            words[w] = (words[w] and (0xFF shl shift).inv()) or ((src[k].toInt() and 0xFF) shl shift)
        }
    }

    actual fun getBytes(dst: ByteArray, count: Int) {
        for (k in 0 until count) dst[k] = (words[k shr 2] ushr ((k and 3) * 8)).toByte()
    }

    /** The first [size] bytes, in this module's memory, at the address [use] is given. */
    internal inline fun inMemory(size: Int, use: (at: Int) -> Unit) {
        withScopedMemoryAllocator { memory ->
            val at = memory.allocate(size.coerceAtLeast(4))
            val n = minOf((size + 3) / 4, words.size)
            for (k in 0 until n) (at + k * 4).storeInt(words[k])
            use(at.address.toInt())
        }
    }

    /** Room for [size] bytes in the module's memory for [fill] to write, then kept here. */
    internal inline fun fromMemory(size: Int, fill: (at: Int, size: Int) -> Unit) {
        withScopedMemoryAllocator { memory ->
            val bytes = minOf(size, capacity)
            val at = memory.allocate(bytes.coerceAtLeast(4))
            fill(at.address.toInt(), bytes)
            for (k in 0 until (bytes + 3) / 4) words[k] = (at + k * 4).loadInt()
        }
    }
}
