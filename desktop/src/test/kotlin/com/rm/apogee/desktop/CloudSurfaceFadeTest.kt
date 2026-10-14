package com.rm.apogee.desktop

import com.rm.apogee.render.ShaderProgram
import com.rm.apogee.render.Shaders
import org.junit.Assert.*
import org.junit.Test
import org.lwjgl.BufferUtils
import org.lwjgl.opengl.GL11C.*
import org.lwjgl.opengl.GL30C.*

class CloudSurfaceFadeTest {
    @Test fun cloudSurfaceThinsBeforeTheCameraClipsThroughIt() {
        TestGl.current()
        val fbo = glGenFramebuffers(); val colour = glGenRenderbuffers(); val vao = glGenVertexArrays()
        glBindFramebuffer(GL_FRAMEBUFFER, fbo)
        glBindRenderbuffer(GL_RENDERBUFFER, colour); glRenderbufferStorage(GL_RENDERBUFFER, GL_RGBA8, 32, 32)
        glFramebufferRenderbuffer(GL_FRAMEBUFFER, GL_COLOR_ATTACHMENT0, GL_RENDERBUFFER, colour)
        assertEquals(GL_FRAMEBUFFER_COMPLETE, glCheckFramebufferStatus(GL_FRAMEBUFFER))
        glBindVertexArray(vao); glViewport(0, 0, 32, 32)
        glDisable(GL_DEPTH_TEST); glDisable(GL_BLEND); glDisable(GL_CULL_FACE)
        val vertex = """
            #version 300 es
            uniform float uDistance;
            flat out vec3 vNormal;
            out float vDistance;
            out vec3 vToCamera;
            out vec3 vRound;
            out float vHeight;
            out vec3 vAxis;
            flat out vec4 vColor;
            flat out float vAmbient;
            void main() {
                vec2 p = vec2((gl_VertexID << 1) & 2, gl_VertexID & 2);
                gl_Position = vec4(p * 2.0 - 1.0, 0.0, 1.0);
                vNormal = vec3(0, 0, 1);
                vDistance = uDistance;
                vToCamera = vec3(0, 0, uDistance);
                vRound = vec3(1, 0, 0);
                vHeight = 0.0;
                vAxis = vec3(0, 1, 0);
                vColor = vec4(1, 1, 1, 0.8);
                vAmbient = 1.0;
            }
        """.trimIndent()
        try {
            for ((name, fragment) in listOf("single" to Shaders.VESSEL_FRAGMENT, "instanced" to Shaders.CLOUD_INSTANCED_FRAGMENT)) {
                val shader = ShaderProgram(vertex, fragment, "cloud-entry-$name")
                try {
                    shader.use(); shader.setVec4("uColor", floatArrayOf(1f, 1f, 1f, .8f)); shader.setFloat("uAmbient", 1f)
                    shader.setFloat("uHazeDistance", 1e9f); shader.setFloat("uFogDistance", 1e9f)
                    shader.setVec3("uLightDirection", 0f, 0f, 1f)
                    fun opacity(distance: Float, cloud: Boolean = true): Float {
                        shader.setFloat("uDistance", distance); shader.setFloat("uWrap", if (cloud) 1f else 0f)
                        glDrawArrays(GL_TRIANGLES, 0, 3)
                        val pixel = BufferUtils.createByteBuffer(4)
                        glReadPixels(16, 16, 1, 1, GL_RGBA, GL_UNSIGNED_BYTE, pixel)
                        return (pixel.get(3).toInt() and 255) / 255f
                    }
                    var previous = 0f
                    for (distance in listOf(.1f, 25f, 50f, 100f, 150f, 200f, 250f, 500f)) {
                        val a = opacity(distance)
                        assertTrue("$name continuous opacity at $distance: $a", a >= previous - .005f)
                        if (distance <= 25f) assertTrue("$name cloud gone before near-plane clipping", a < .03f)
                        if (distance >= 250f) assertEquals(.8f, a, .005f)
                        previous = a
                    }
                    assertEquals("$name ordinary surfaces keep opacity", .8f, opacity(1f, false), .005f)
                } finally { shader.release() }
            }
            assertEquals(GL_NO_ERROR, glGetError())
        } finally {
            glBindVertexArray(0); glDeleteVertexArrays(vao)
            glBindFramebuffer(GL_FRAMEBUFFER, 0); glDeleteFramebuffers(fbo); glDeleteRenderbuffers(colour)
            glEnable(GL_DEPTH_TEST); glEnable(GL_CULL_FACE)
        }
    }
}
