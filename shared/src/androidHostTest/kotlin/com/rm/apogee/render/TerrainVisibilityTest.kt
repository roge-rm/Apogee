package com.rm.apogee.render

import com.rm.apogee.core.math.Mat4
import com.rm.apogee.core.math.Quat
import com.rm.apogee.core.math.Vec3
import kotlin.math.PI
import kotlin.math.tan
import kotlin.random.Random
import org.junit.Assert.*
import org.junit.Test

class TerrainVisibilityTest {
    @Test fun `spheres crossing a side or containing the camera remain visible`() {
        val v = TerrainVisibility()
        val q = Quat.identity()
        assertTrue(v.visible(Vec3(11.0, 0.0, -10.0), 1.0, q, 1.0, 1.0))
        assertTrue(v.visible(Vec3(0.0, 11.0, -10.0), 1.0, q, 1.0, 1.0))
        assertTrue(v.visible(Vec3(0.0, 0.0, 0.5), 10.0, q, 1.0, 1.0))
        assertFalse(v.visible(Vec3(12.0, 0.0, -10.0), 1.0, q, 1.0, 1.0))
        assertFalse(v.visible(Vec3(0.0, 0.0, 10.0), 1.0, q, 1.0, 1.0))
    }

    @Test fun `rejecting a sphere never removes a sample inside the projection`() {
        val random = Random(713)
        val v = TerrainVisibility()
        for (aspect in listOf(0.625, 1.0, 2.0)) for (fov in listOf(0.5, 1.0, 2.0)) {
            val matrix = Mat4().also { it.setPerspective(fov, aspect, 0.1, 100_000.0) }.m
            val rotation = Quat.fromAxisAngle(Vec3(0.3, 1.0, 0.2).normalizeInPlace(), PI * 0.7)
            repeat(150) {
                val centre = Vec3(random.nextDouble(-200.0, 200.0), random.nextDouble(-200.0, 200.0), random.nextDouble(-200.0, 200.0))
                val radius = random.nextDouble(0.1, 100.0)
                val world = rotation.rotate(centre, Vec3())
                if (!v.visible(world, radius, rotation, tan(fov * 0.5), aspect)) repeat(100) {
                    val offset = Vec3(random.nextDouble(-1.0, 1.0), random.nextDouble(-1.0, 1.0), random.nextDouble(-1.0, 1.0))
                    if (offset.lengthSq <= 1.0) {
                        val p = centre.copy().addScaledInPlace(offset, radius)
                        val w = -p.z
                        val x = matrix[0] * p.x; val y = matrix[5] * p.y
                        assertFalse("culled sphere includes an on-screen point", w > 0.0 && x in -w..w && y in -w..w)
                    }
                }
            }
        }
    }
}
