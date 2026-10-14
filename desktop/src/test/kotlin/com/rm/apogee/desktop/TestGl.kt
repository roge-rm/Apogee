package com.rm.apogee.desktop

import org.junit.Assume.assumeTrue
import org.lwjgl.glfw.GLFW.*
import org.lwjgl.opengl.GL

/** One context per test process, as in the app; NVIDIA's threaded driver needs it kept alive. */
internal object TestGl {
    private val window: Long by lazy {
        assumeTrue("no display", System.getenv("DISPLAY") != null || System.getenv("WAYLAND_DISPLAY") != null)
        assumeTrue("GLFW won't start", glfwInit())
        glfwDefaultWindowHints()
        glfwWindowHint(GLFW_VISIBLE, GLFW_FALSE)
        glfwWindowHint(GLFW_CONTEXT_VERSION_MAJOR, 3)
        glfwWindowHint(GLFW_CONTEXT_VERSION_MINOR, 3)
        glfwWindowHint(GLFW_OPENGL_PROFILE, GLFW_OPENGL_CORE_PROFILE)
        glfwWindowHint(GLFW_OPENGL_FORWARD_COMPAT, GLFW_TRUE)
        glfwCreateWindow(256, 256, "Apogee GL tests", 0, 0).also { assumeTrue("no OpenGL 3.3 context", it != 0L) }
    }

    fun current() {
        glfwMakeContextCurrent(window)
        GL.createCapabilities()
    }
}
