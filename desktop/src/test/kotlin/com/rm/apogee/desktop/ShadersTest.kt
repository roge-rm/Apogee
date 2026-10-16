package com.rm.apogee.desktop

import com.rm.apogee.render.ShaderProgram
import com.rm.apogee.render.Shaders
import org.junit.Test

/**
 * Every program the renderer makes compiles and links on desktop OpenGL 3.3, with the ES version
 * line rewritten. Skipped where there's no display to make a context on.
 */
class ShadersTest {
    @Test
    fun everyProgramBuildsOnGl33() {
        TestGl.current()
        println("on ${org.lwjgl.opengl.GL11C.glGetString(org.lwjgl.opengl.GL11C.GL_RENDERER)}")
        val programs = listOf(
            Triple(Shaders.VESSEL_VERTEX, Shaders.VESSEL_FRAGMENT, "vessel"),
            Triple(Shaders.VESSEL_VERTEX, Shaders.DEPTH_FRAGMENT, "vessel-depth"),
            Triple(Shaders.CLOUD_INSTANCED_VERTEX, Shaders.CLOUD_INSTANCED_FRAGMENT, "cloud"),
            Triple(Shaders.CLOUD_INSTANCED_VERTEX, Shaders.DEPTH_FRAGMENT, "vessel-depth-instanced"),
            Triple(Shaders.CLOUD_SHELL_VERTEX, Shaders.CLOUD_SHELL_FRAGMENT, "cloud-shell"),
            Triple(Shaders.HURRICANE_VEIL_VERTEX, Shaders.HURRICANE_VEIL_FRAGMENT, "hurricane-veil"),
            Triple(Shaders.LINE_VERTEX, Shaders.LINE_FRAGMENT, "line"),
            Triple(Shaders.PARTICLE_VERTEX, Shaders.PARTICLE_FRAGMENT, "particles"),
            Triple(Shaders.SCATTER_VERTEX, Shaders.SCATTER_FRAGMENT, "scatter"),
            Triple(Shaders.SCATTER_VERTEX, Shaders.DEPTH_FRAGMENT, "scatter-depth"),
            Triple(Shaders.SEA_VERTEX, Shaders.SEA_FRAGMENT, "sea"),
            Triple(Shaders.SKY_VERTEX, Shaders.SKY_FRAGMENT, "sky"),
            Triple(Shaders.TERRAIN_VERTEX, Shaders.TERRAIN_FRAGMENT, "terrain"),
            Triple(Shaders.TERRAIN_VERTEX_CALM, Shaders.TERRAIN_FRAGMENT_CALM, "terrain-calm"),
            Triple(Shaders.TERRAIN_VERTEX, Shaders.TERRAIN_FRAGMENT_LOW, "terrain-low"),
            Triple(Shaders.TERRAIN_VERTEX, Shaders.DEPTH_FRAGMENT, "terrain-depth"),
        )
        // ShaderProgram throws with the driver's log if one fails.
        for ((vertex, fragment, name) in programs) ShaderProgram(vertex, fragment, name).release()
    }
}
