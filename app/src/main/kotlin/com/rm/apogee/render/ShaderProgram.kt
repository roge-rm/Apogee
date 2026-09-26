package com.rm.apogee.render

import android.opengl.GLES30
import android.util.Log

/**
 * A compiled and linked GLES program, with uniform locations cached on first
 * lookup.
 *
 * Deliberately conservative GLSL: `#version 300 es` only, no compute, no
 * storage buffers, no `gl_FragDepth` tricks. minSdk 27 means Adreno 5xx and
 * Mali-T8xx drivers are in the test matrix, and those are exactly the drivers
 * that quietly mis-compile anything clever.
 */
class ShaderProgram(vertexSource: String, fragmentSource: String, private val name: String) {

    val handle: Int
    private val uniformLocations = HashMap<String, Int>()

    init {
        val vs = compile(GLES30.GL_VERTEX_SHADER, vertexSource, "$name.vert")
        val fs = compile(GLES30.GL_FRAGMENT_SHADER, fragmentSource, "$name.frag")
        handle = GLES30.glCreateProgram()
        GLES30.glAttachShader(handle, vs)
        GLES30.glAttachShader(handle, fs)
        GLES30.glLinkProgram(handle)

        val status = IntArray(1)
        GLES30.glGetProgramiv(handle, GLES30.GL_LINK_STATUS, status, 0)
        if (status[0] == 0) {
            val log = GLES30.glGetProgramInfoLog(handle)
            GLES30.glDeleteProgram(handle)
            error("Failed to link program '$name': $log")
        }

        // Shaders are reference-counted by the program; drop our references.
        GLES30.glDeleteShader(vs)
        GLES30.glDeleteShader(fs)
    }

    fun use() = GLES30.glUseProgram(handle)

    fun uniform(uniformName: String): Int = uniformLocations.getOrPut(uniformName) {
        val location = GLES30.glGetUniformLocation(handle, uniformName)
        if (location < 0) {
            // Not fatal: a uniform the compiler proved unused is legitimately absent.
            Log.w(TAG, "uniform '$uniformName' not found in program '$name' (optimised out?)")
        }
        location
    }

    fun setMat4(uniformName: String, matrix: FloatArray) =
        GLES30.glUniformMatrix4fv(uniform(uniformName), 1, false, matrix, 0)

    fun setMat3(uniformName: String, matrix: FloatArray) =
        GLES30.glUniformMatrix3fv(uniform(uniformName), 1, false, matrix, 0)

    fun setVec3(uniformName: String, x: Float, y: Float, z: Float) =
        GLES30.glUniform3f(uniform(uniformName), x, y, z)

    fun setVec4(uniformName: String, v: FloatArray) =
        GLES30.glUniform4f(uniform(uniformName), v[0], v[1], v[2], v[3])

    /** The first [count] vec4s of [v] into a uniform array. */
    fun setVec4Array(uniformName: String, v: FloatArray, count: Int) =
        GLES30.glUniform4fv(uniform(uniformName), count, v, 0)

    fun setFloat(uniformName: String, value: Float) =
        GLES30.glUniform1f(uniform(uniformName), value)

    /** For a sampler: which texture unit it reads. */
    fun setInt(uniformName: String, value: Int) =
        GLES30.glUniform1i(uniform(uniformName), value)

    fun release() {
        GLES30.glDeleteProgram(handle)
    }

    private fun compile(type: Int, source: String, label: String): Int {
        val shader = GLES30.glCreateShader(type)
        GLES30.glShaderSource(shader, source)
        GLES30.glCompileShader(shader)

        val status = IntArray(1)
        GLES30.glGetShaderiv(shader, GLES30.GL_COMPILE_STATUS, status, 0)
        if (status[0] == 0) {
            val log = GLES30.glGetShaderInfoLog(shader)
            GLES30.glDeleteShader(shader)
            error("Failed to compile $label: $log")
        }
        return shader
    }

    private companion object {
        const val TAG = "ApogeeShader"
    }
}
