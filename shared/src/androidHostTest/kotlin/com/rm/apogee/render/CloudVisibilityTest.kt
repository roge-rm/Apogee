package com.rm.apogee.render

import com.rm.apogee.core.math.Quat
import com.rm.apogee.core.math.Vec3
import org.junit.Assert.*
import org.junit.Test

class CloudVisibilityTest {
    private val visibility = CloudVisibility()
    private fun puff(x: Double, z: Double, size: Double = 1.0) = RenderItem(
        CloudPuff(0), Vec3(x, 0.0, z), Quat.identity(), floatArrayOf(1f, 1f, 1f, 0.5f), scale = Vec3(size, size, size),
    )
    private fun visible(now: RenderItem, old: RenderItem? = null) = visibility.visible(now, old, Vec3(), Quat.identity(), 1.0, 1.0)

    @Test fun `puffs outside camera are rejected but edge intersections and containing clouds survive`() {
        assertFalse(visible(puff(0.0, 10.0)))
        assertFalse(visible(puff(15.0, -10.0)))
        assertTrue(visible(puff(11.0, -10.0)))
        assertTrue(visible(puff(0.0, 0.5, 10.0)))
        assertTrue(visible(puff(0.0, -10.0)))
    }

    @Test fun `interpolation retains crossing clouds and rejects only common outside planes`() {
        assertTrue(visible(puff(20.0, -10.0), puff(0.0, -10.0)))
        assertTrue(visible(puff(20.0, -10.0), puff(-20.0, -10.0)))
        assertFalse(visible(puff(20.0, -10.0), puff(25.0, -10.0)))
        assertTrue(visible(puff(20.0, -10.0), puff(20.0, -10.0, 15.0)))
    }

    @Test fun `camera orientation and aspect change the frustum without dropping other items`() {
        val sideways = Quat.fromAxisAngle(Vec3.unitY(), kotlin.math.PI / 2)
        assertTrue(visibility.visible(puff(-10.0, 0.0), null, Vec3(), sideways, 1.0, 1.0))
        assertTrue(visibility.visible(puff(15.0, -10.0), null, Vec3(), Quat.identity(), 1.0, 2.0))
        val solid = RenderItem(com.rm.apogee.core.part.MeshSpec.Sphere(1.0), Vec3(0.0, 0.0, 10.0), Quat.identity(), floatArrayOf(1f, 1f, 1f, 1f))
        assertTrue(visible(solid))
    }
}
