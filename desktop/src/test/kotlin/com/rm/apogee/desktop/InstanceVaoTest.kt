package com.rm.apogee.desktop

import com.rm.apogee.core.math.Quat
import com.rm.apogee.core.math.Vec3
import com.rm.apogee.render.*
import org.junit.Assert.*
import org.junit.Test
import org.lwjgl.BufferUtils
import org.lwjgl.opengl.GL11C.*

class InstanceVaoTest {
    @Test fun retainedInstanceStateMatchesLegacyWhenBuffersOffsetsAndOrdinaryDrawsAlternate() {
        TestGl.current()
        val bus = FrameBus()
        val renderer = GlRenderer({ QualityTier.LOW }, bus)
        renderer.onSurfaceCreated(); renderer.onSurfaceChanged(256, 256)
        try {
            fun render(legacy: Boolean, step: Int): ByteArray {
                Mesh.debugLegacyInstancing = legacy
                val shape = CloudPuff(0, false, 1)
                val items = (0..7).map { i ->
                    // The same mesh uses the solid and cloud instance buffers, plus single draws.
                    val alpha = if (i < 3) 1f else .65f
                    RenderItem(shape, Vec3((i % 4 - 1.5) * 1.4, (i / 4 - .5) * 1.4, -7.0 - (i + step) % 3),
                        Quat.identity(), floatArrayOf(.2f + i * .08f, .6f, .3f, alpha),
                        caps = if (i == 7) 0 else StackCaps.BOTH, scale = Vec3(.6, .6, .6),
                        wrap = i >= 3, key = RenderItem.cloudKey(42, i), orderedTransparency = true)
                }.let { if (step % 2 == 0) it else it.reversed() }
                bus.clear()
                bus.publish(RenderFrame(step.toLong(), System.nanoTime(), Vec3(), Quat.identity(), Math.toRadians(55.0), items))
                renderer.onDrawFrame()
                val pixels = BufferUtils.createByteBuffer(256 * 256 * 4)
                glReadPixels(0, 0, 256, 256, GL_RGBA, GL_UNSIGNED_BYTE, pixels)
                return ByteArray(pixels.capacity()).also { pixels.get(it) }
            }
            // Reusing and replacing both instance buffer offsets must not retain stale pointers.
            for (step in 0..3) {
                val legacy = render(true, step)
                val retained = render(false, step)
                assertArrayEquals("mixed ordinary/instanced draws, step $step", legacy, retained)
            }
            assertEquals(GL_NO_ERROR, glGetError())
        } finally { Mesh.debugLegacyInstancing = false; renderer.release(); bus.clear() }
    }
}
