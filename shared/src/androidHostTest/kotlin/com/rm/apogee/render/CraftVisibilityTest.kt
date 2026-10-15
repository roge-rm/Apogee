package com.rm.apogee.render

import com.rm.apogee.core.math.Quat
import com.rm.apogee.core.math.Vec3
import org.junit.Assert.*
import org.junit.Test
import kotlin.math.PI

class CraftVisibilityTest {
    private val visibility = CraftVisibility()
    private val fov = 55.0 * PI / 180.0
    // Terra's size, the camera 20 m up looking north along the ground (-z is forward).
    private val ground = 600_000.0 - 10_000.0
    private val camera = Vec3(0.0, 600_020.0, 0.0)
    private val level = Quat.identity()

    private fun seen(at: Vec3, radius: Double, rotation: Quat = level, from: Vec3 = camera, height: Int = 640, aspect: Double = 0.625, ground: Double = this.ground) =
        visibility.visible(at, radius, from, rotation, fov, aspect, height, ground)

    @Test fun `a base on another world is left out`() {
        // Luna is about 12 million metres off, straight ahead.
        assertFalse(seen(Vec3(0.0, 600_000.0, -12_000_000.0), 60.0))
        // On a 4K screen too.
        assertFalse(seen(Vec3(0.0, 600_000.0, -12_000_000.0), 60.0, height = 2160, aspect = 1.78))
    }

    @Test fun `a tiny far craft is left out but a near one is kept`() {
        assertFalse(seen(Vec3(0.0, 600_020.0, -200_000.0), 10.0))
        assertTrue(seen(Vec3(0.0, 600_020.0, -2_000.0), 10.0))
    }

    @Test fun `a craft behind the horizon is left out`() {
        // A third of the way round Terra, higher than the camera so it isn't too small.
        val camera = Vec3(0.0, 700_000.0, 0.0)
        val behind = Vec3(0.0, -400_000.0, -500_000.0)
        val look = com.rm.apogee.core.math.quatFromTo(Vec3(0.0, 0.0, -1.0), Vec3().setTo(behind).subInPlace(camera).normalizeInPlace())
        assertFalse(seen(behind, 20_000.0, look, camera, ground = 600_000.0))
        // Far enough out to rise over the horizon, it shows.
        assertTrue(seen(Vec3(0.0, 0.0, -1_500_000.0), 20_000.0, look, camera, ground = 600_000.0))
        // Without the horizon test it would be kept.
        assertTrue(seen(behind, 20_000.0, look, camera, ground = 0.0))
    }

    @Test fun `a craft just off the screen is kept, one behind the camera is left out`() {
        // The Fire's half-height screen spans about 31 degrees from the middle to a corner.
        val side = Math.toRadians(45.0)
        val just = Vec3(kotlin.math.sin(side) * 500.0, 600_020.0, -kotlin.math.cos(side) * 500.0)
        assertTrue(seen(just, 5.0))
        assertFalse(seen(Vec3(0.0, 600_020.0, 500.0), 5.0))
        // Turned to face it, the one behind is kept.
        assertTrue(seen(Vec3(0.0, 600_020.0, 500.0), 5.0, Quat.fromAxisAngle(Vec3.unitY(), PI)))
    }

    @Test fun `the camera inside a craft keeps it`() {
        assertTrue(seen(Vec3(0.0, 600_020.0, 10.0), 30.0))
    }

    @Test fun `lamps that light the ground in view keep a craft`() {
        // Behind and to the side, its body out of view, but its lamps' reach in it.
        val at = Vec3(70.0, 600_020.0, 40.0)
        assertFalse(seen(at, 10.0))
        assertTrue(seen(at, 10.0 + 60.0))
    }
}
