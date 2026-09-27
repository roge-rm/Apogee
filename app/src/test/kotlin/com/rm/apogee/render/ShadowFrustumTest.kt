package com.rm.apogee.render

import com.rm.apogee.core.math.Vec3
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ShadowFrustumTest {

    private val sun = Vec3(0.62, 0.45, 0.64).normalizeInPlace()
    // A craft on the ground, far from the origin like a real one is.
    private val craft = Vec3(3.1e5, 5.2e5, 1.9e5)

    @Test
    fun `the centre falls in the middle of the map and depth runs from the light`() {
        val f = ShadowFrustum()
        val camera = craft.copy().addInPlace(Vec3(20.0, 5.0, -12.0))
        f.update(sun, craft, camera, 100.0, 300.0, 2048)
        val centre = f.project(craft.copy().subInPlace(camera))
        assertEquals(0.5, centre[0], f.texelSize / 200.0 * 2)
        assertEquals(0.5, centre[1], f.texelSize / 200.0 * 2)
        assertEquals(0.5, centre[2], 1e-6)
        // Something between it and the light is nearer the light, so it has a smaller depth.
        val above = craft.copy().addScaledInPlace(sun, 50.0).subInPlace(camera)
        assertTrue(f.project(above)[2] < centre[2])
        // And it lands on the same texel as the point it shades.
        val below = f.project(above)
        assertEquals(centre[0], below[0], 1e-6)
        assertEquals(centre[1], below[1], 1e-6)
    }

    @Test
    fun `moving the craft less than a texel moves nothing on the ground`() {
        val f = ShadowFrustum()
        val ground = craft.copy().addInPlace(Vec3(7.0, 0.0, 3.0))
        val camera = craft.copy().addInPlace(Vec3(30.0, 10.0, 0.0))
        f.update(sun, craft, camera, 100.0, 300.0, 2048)
        val before = f.project(ground.copy().subInPlace(camera))
        // Nudged by a fraction of a texel, and the camera with it.
        val nudge = Vec3(0.01, -0.02, 0.015)
        f.update(sun, craft.copy().addInPlace(nudge), camera.copy().addInPlace(nudge), 100.0, 300.0, 2048)
        val after = f.project(ground.copy().subInPlace(camera.copy().addInPlace(nudge)))
        // Either it didn't move, or it moved by one whole texel (the grid ticked over). Never a
        // fraction.
        for (k in 0..1) {
            val texels = (after[k] - before[k]) * 2048
            val whole = Math.round(texels).toDouble()
            assertEquals("texel $k moved $texels", whole, texels, 0.02)
        }
    }
}
