package com.rm.apogee.desktop

import com.rm.apogee.core.math.Mat4
import com.rm.apogee.core.math.Quat
import com.rm.apogee.core.math.Vec3
import com.rm.apogee.core.part.MeshSpec
import com.rm.apogee.render.*
import org.junit.Assert.*
import org.junit.Test
import org.lwjgl.BufferUtils
import org.lwjgl.opengl.GL11C.*
import org.lwjgl.opengl.GL30C.*

class CloudDepthTest {
    private fun frame(camera: Vec3, items: List<RenderItem> = emptyList()) = RenderFrame(
        0, 0, camera, Quat.identity(), Math.toRadians(55.0), emptyList(),
        world = WorldView(600_000.0, 70_000.0, 5600.0, Vec3(0.0, 0.0, 1.0),
            Vec3(1.0, 0.0, 0.0), camera.length - 600_000.0, Quat.identity(), 6000.0), farItems = items)

    @Test fun nearPlaneRetainsNearbyObjectsAndSurfaceViews() {
        val camera = Vec3(0.0, 0.0, 3_000_000.0)
        val far = 1e8
        assertTrue(FarProjection.nearPlane(frame(camera), camera, far) > 200_000.0)
        val nearby = RenderItem(MeshSpec.Sphere(100.0), Vec3(0.0, 0.0, 2_999_000.0), Quat.identity(), floatArrayOf(1f, 1f, 1f, 1f))
        assertEquals(100.0, FarProjection.nearPlane(frame(camera, listOf(nearby)), camera, far), 0.0)
        val surface = Vec3(0.0, 0.0, 600_020.0)
        assertEquals(100.0, FarProjection.nearPlane(frame(surface), surface, far), 0.0)
    }

    @Test fun distantCloudsStayVisibleAgainstTheGlobeOn24BitDepth() {
        TestGl.current()
        val fbo = glGenFramebuffers(); val colour = glGenRenderbuffers(); val depth = glGenRenderbuffers()
        glBindFramebuffer(GL_FRAMEBUFFER, fbo)
        glBindRenderbuffer(GL_RENDERBUFFER, colour); glRenderbufferStorage(GL_RENDERBUFFER, GL_RGBA8, 256, 256)
        glFramebufferRenderbuffer(GL_FRAMEBUFFER, GL_COLOR_ATTACHMENT0, GL_RENDERBUFFER, colour)
        glBindRenderbuffer(GL_RENDERBUFFER, depth); glRenderbufferStorage(GL_RENDERBUFFER, GL_DEPTH_COMPONENT24, 256, 256)
        glFramebufferRenderbuffer(GL_FRAMEBUFFER, GL_DEPTH_ATTACHMENT, GL_RENDERBUFFER, depth)
        assertEquals(GL_FRAMEBUFFER_COMPLETE, glCheckFramebufferStatus(GL_FRAMEBUFFER))
        val ground = CloudShellMesh(); val cloud = CloudShellMesh()
        ground.upload(CloudShell.build(600_000.0, 0.0, 1, { 1.0 }, { 1.0 }))
        cloud.upload(CloudShell.build(600_000.0, 8000.0, 1, { 1.0 }, { 1.0 }))
        val solid = ShaderProgram(Shaders.CLOUD_SHELL_VERTEX, """
            #version 300 es
            precision highp float;
            out vec4 fragColor;
            void main() { fragColor = vec4(0.04, 0.12, 0.25, 1.0); }
        """.trimIndent(), "cloud-depth-ground")
        val veil = ShaderProgram(Shaders.CLOUD_SHELL_VERTEX, Shaders.CLOUD_SHELL_FRAGMENT, "cloud-depth-veil")
        try {
            glViewport(0, 0, 256, 256); glEnable(GL_CULL_FACE); glCullFace(GL_BACK); glFrontFace(GL_CCW)
            glDepthFunc(GL_LESS); glClearColor(.04f, .12f, .25f, 1f)
            fun render(distance: Double, angle: Double, useDepth: Boolean, oldProjection: Boolean = false): ByteArray {
                val camera = Vec3(0.0, 0.0, distance)
                val near = if (oldProjection) 100.0 else FarProjection.nearPlane(frame(camera), camera, 1e8)
                val projection = Mat4().setPerspective(Math.toRadians(55.0), 1.0, near, 1e8)
                val q = Quat(0.0, kotlin.math.sin(angle * .5), 0.0, kotlin.math.cos(angle * .5))
                val model = Mat4().setFromTrs(Vec3.zero(), q, camera, 600_000.0)
                glDepthMask(true); glEnable(GL_DEPTH_TEST); glDisable(GL_BLEND)
                glClear(GL_COLOR_BUFFER_BIT or GL_DEPTH_BUFFER_BIT)
                solid.use(); solid.setMat4("uViewProjection", projection.m); solid.setMat4("uModel", model.m); ground.draw()
                veil.use(); veil.setMat4("uViewProjection", projection.m); veil.setMat4("uModel", model.m)
                veil.setVec3("uSunDirection", 0f, 0f, 1f); veil.setFloat("uNoiseScale", 15f); veil.setFloat("uDrift", 0f)
                if (!useDepth) glDisable(GL_DEPTH_TEST)
                glDepthMask(false); glEnable(GL_BLEND); glBlendFunc(GL_SRC_ALPHA, GL_ONE_MINUS_SRC_ALPHA); cloud.draw()
                glDisable(GL_BLEND); glDepthMask(true)
                val bytes = BufferUtils.createByteBuffer(256 * 256 * 4)
                glReadPixels(0, 0, 256, 256, GL_RGBA, GL_UNSIGNED_BYTE, bytes)
                return ByteArray(bytes.capacity()).also { bytes.get(it) }
            }
            var oldMissing = 0
            for (distance in listOf(3_000_000.0, 6_000_000.0, 12_000_000.0)) for (angle in listOf(0.0, .07, .15)) {
                val expected = render(distance, angle, false)
                val actual = render(distance, angle, true)
                val old = render(distance, angle, true, true)
                var checked = 0
                for (y in 0 until 256) for (x in 0 until 256) {
                    val i = (y * 256 + x) * 4
                    // Only cloud well inside the disc; the far half and limb are not the reference.
                    if ((expected[i].toInt() and 255) < 50) continue
                    val r2 = (x - 128) * (x - 128) + (y - 128) * (y - 128)
                    val radius = 600_000.0 / distance * 246.0
                    if (r2 > radius * radius * .65) continue
                    checked++
                    assertTrue("cloud lost against globe at $distance/$angle pixel $x,$y",
                        kotlin.math.abs((expected[i].toInt() and 255) - (actual[i].toInt() and 255)) <= 1)
                    if ((expected[i].toInt() and 255) - (old[i].toInt() and 255) > 8) oldMissing++
                }
                assertTrue("cloud pixels checked at $distance", checked > 30)
            }
            println("Old far projection lost $oldMissing cloud pixels; corrected projection matches the visible-cloud reference")
            assertTrue("old projection reproduces missing cloud pixels: $oldMissing", oldMissing > 100)
            assertEquals(GL_NO_ERROR, glGetError())
        } finally {
            solid.release(); veil.release(); ground.release(); cloud.release()
            glDepthMask(true); glEnable(GL_DEPTH_TEST); glDisable(GL_BLEND)
            glBindFramebuffer(GL_FRAMEBUFFER, 0); glDeleteFramebuffers(fbo); glDeleteRenderbuffers(colour); glDeleteRenderbuffers(depth)
        }
    }
}
