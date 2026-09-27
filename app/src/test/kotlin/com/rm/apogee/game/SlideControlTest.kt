package com.rm.apogee.game

import com.rm.apogee.core.math.Quat
import com.rm.apogee.core.math.Vec3
import com.rm.apogee.core.math.quatLookAt
import org.junit.Assert.assertEquals
import org.junit.Test

class SlideControlTest {

    // Standing on the planet's +X, spinning about +Y: up is +X, north is +Y, and east (the way the
    // ground turns, Y x X) is -Z.
    private val up = Vec3(1.0, 0.0, 0.0)
    private val north = Vec3(0.0, 1.0, 0.0)
    private val east = Vec3(0.0, 0.0, -1.0)

    /** A camera looking north and a little down, with the planet's up as its up. */
    private val lookingNorth = quatLookAt(Vec3().setTo(north).addScaledInPlace(up, -0.3), up)

    private fun assertNear(expected: Vec3, actual: Vec3) {
        assertEquals(expected.x, actual.x, 1e-6); assertEquals(expected.y, actual.y, 1e-6); assertEquals(expected.z, actual.z, 1e-6)
    }

    @Test
    fun `stick up slides away from the camera along the ground, and right to its right`() {
        val identity = Quat.identity()
        assertNear(north, SlideControl.command(lookingNorth, identity, up, 0.0, 1.0, 0.0, Vec3()))
        assertNear(east, SlideControl.command(lookingNorth, identity, up, 1.0, 0.0, 0.0, Vec3()))
        assertNear(up, SlideControl.command(lookingNorth, identity, up, 0.0, 0.0, 1.0, Vec3()))
    }

    @Test
    fun `the answer is in the craft's own axes`() {
        // A craft turned a quarter about the planet's up, so its own +Z now points north.
        val turned = Quat.fromAxisAngle(up, -Math.PI / 2, Quat())
        val local = SlideControl.command(lookingNorth, turned, up, 0.0, 1.0, 0.0, Vec3())
        assertNear(north, turned.rotate(local, Vec3()))
    }

    @Test
    fun `looking straight down, up the screen is still somewhere on the ground`() {
        val down = quatLookAt(Vec3().setTo(up).mulInPlace(-1.0), north)
        val slide = SlideControl.command(down, Quat.identity(), up, 0.0, 1.0, 0.0, Vec3())
        assertEquals(1.0, slide.length, 1e-6)
        assertEquals(0.0, slide dot up, 1e-6)
    }

    @Test
    fun `a diagonal is a direction, not extra thrust`() {
        val slide = SlideControl.command(lookingNorth, Quat.identity(), up, 1.0, 1.0, 1.0, Vec3())
        assertEquals(1.0, slide.length, 1e-6)
    }
}
