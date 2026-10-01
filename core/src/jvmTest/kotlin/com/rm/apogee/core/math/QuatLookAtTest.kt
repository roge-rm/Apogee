package com.rm.apogee.core.math

import org.junit.Assert.assertTrue
import org.junit.Test

class QuatLookAtTest {

    @Test
    fun `the rotation aims local -Z along the requested direction`() {
        val directions = listOf(
            Vec3(1.0, 0.0, 0.0),
            Vec3(0.0, 0.0, -1.0),
            Vec3(0.3, 0.8, -0.5),
            Vec3(-2.0, -1.0, 0.4),
        )
        for (direction in directions) {
            val rotation = quatLookAt(direction)
            val aimed = rotation.rotate(Vec3(0.0, 0.0, -1.0))
            assertTrue(
                "aiming at $direction produced $aimed",
                aimed.approxEquals(direction.normalized(), 1e-9),
            )
        }
    }

    @Test
    fun `looking straight up doesn't give a degenerate rotation`() {
        // Up is the default roll reference, which breaks a naive cross-product basis.
        val rotation = quatLookAt(Vec3.unitY())
        val aimed = rotation.rotate(Vec3(0.0, 0.0, -1.0))
        assertTrue("got $aimed", aimed.approxEquals(Vec3.unitY(), 1e-9))
        assertTrue(rotation.isFinite)
    }

    @Test
    fun `looking straight down doesn't give a degenerate rotation`() {
        val rotation = quatLookAt(Vec3(0.0, -1.0, 0.0))
        val aimed = rotation.rotate(Vec3(0.0, 0.0, -1.0))
        assertTrue("got $aimed", aimed.approxEquals(Vec3(0.0, -1.0, 0.0), 1e-9))
        assertTrue(rotation.isFinite)
    }
}
