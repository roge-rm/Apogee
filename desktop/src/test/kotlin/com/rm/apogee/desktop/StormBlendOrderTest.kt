package com.rm.apogee.desktop

import com.rm.apogee.core.math.Quat
import com.rm.apogee.core.math.Vec3
import com.rm.apogee.render.*
import org.junit.Assert.*
import org.junit.Test
import org.lwjgl.BufferUtils
import org.lwjgl.opengl.GL11C.*

class StormBlendOrderTest {
    @Test fun overlappingStormPuffsMatchStrictBackToFrontInsteadOfShapeGrouping() {
        TestGl.current()
        val bus = FrameBus(); val renderer = GlRenderer({ QualityTier.LOW }, bus)
        renderer.onSurfaceCreated(); renderer.onSurfaceChanged(256, 256)
        try {
            fun render(ordered: Boolean, reference: Boolean): ByteArray {
                val items = (0..2).map { i ->
                    val shade = floatArrayOf(.15f, .85f, .3f)[i]
                    RenderItem(CloudPuff(if (i == 1) 1 else 0, false, 2), Vec3(0.0, 0.0, -3000.0 + i * 140),
                        Quat.identity(), floatArrayOf(shade, shade, shade, .8f), caps = if (reference) 0 else StackCaps.BOTH,
                        scale = Vec3(750.0, 750.0, 750.0), ambient = .42f,
                        key = RenderItem.cloudKey(1, i), orderedTransparency = ordered)
                }
                bus.clear()
                bus.publish(RenderFrame(1, System.nanoTime(), Vec3(), Quat.identity(), Math.toRadians(55.0), items))
                renderer.onDrawFrame()
                val data = BufferUtils.createByteBuffer(256 * 256 * 4)
                glReadPixels(0, 0, 256, 256, GL_RGBA, GL_UNSIGNED_BYTE, data)
                return ByteArray(data.capacity()).also { data.get(it) }
            }
            val reference = render(true, true)
            val actual = render(true, false)
            val grouped = render(false, false)
            var oldMismatch = 0; var pixels = 0
            for (y in 90..166) for (x in 90..166) {
                val i = (y * 256 + x) * 4
                val expected = reference[i].toInt() and 255
                assertTrue("storm depth order at $x,$y", kotlin.math.abs(expected - (actual[i].toInt() and 255)) <= 1)
                if (kotlin.math.abs(expected - (grouped[i].toInt() and 255)) > 5) oldMismatch++
                pixels++
            }
            assertTrue("old shape regrouping visibly changes storm: $oldMismatch/$pixels", oldMismatch > 100)
            println("Old storm batching changes $oldMismatch pixels; ordered instancing matches the strict reference")
            assertEquals(GL_NO_ERROR, glGetError())
        } finally { renderer.release(); bus.clear() }
    }
}
