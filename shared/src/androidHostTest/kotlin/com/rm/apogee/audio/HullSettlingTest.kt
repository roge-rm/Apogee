package com.rm.apogee.audio

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.exp

class HullSettlingTest {

    @Test
    fun climbingWorksTheHullAndSpaceLetsItSettle() {
        val hull = HullSettling()
        var t = 0.0
        // A climb: the air thins from sea level to almost nothing over 100 s.
        while (t < 100.0) {
            hull.update(exp(-t / 25.0), t)
            t += 0.05
        }
        val climbing = hull.level
        // Then two minutes in space, with the pressure flat.
        while (t < 220.0) {
            hull.update(0.0, t)
            t += 0.05
        }
        assertTrue("working on the way up: $climbing", climbing > 0.1)
        assertTrue("settled in space: ${hull.level}", hull.level < 0.1 * climbing)
    }

    @Test
    fun sittingStillOrPausedIsQuiet() {
        val hull = HullSettling()
        for (i in 0..200) hull.update(1.0, i * 0.05)
        assertEquals(0.0, hull.level, 1e-9)
        // Paused, so time stands still and nothing changes.
        repeat(50) { hull.update(0.5, 10.0) }
        assertEquals(0.0, hull.level, 1e-9)
    }
}
