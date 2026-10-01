package com.rm.apogee.render

import org.junit.Assert.assertEquals
import org.junit.Test

class AutoResolutionTest {
    /** Runs [auto] for [seconds] on a device that draws at [fps] for the scale it's at. */
    private fun run(auto: AutoResolution, clock: LongArray, frames: LongArray, seconds: Double, fps: (Double) -> Double) {
        val tick = 100_000_000L
        repeat((seconds * 10).toInt()) {
            clock[0] += tick
            frames[0] += (fps(auto.scale) * tick / 1e9).toLong().coerceAtLeast(0)
            auto.update(clock[0], frames[0])
        }
    }

    // A mid-range phone with the Flat Top: 26 fps at full resolution, 41 at two thirds, 51 at half.
    private val phone: (Double) -> Double = { scale -> (26.0 / (0.42 + 0.58 * scale * scale)).coerceAtMost(60.0) }

    @Test
    fun `a phone that can't hold forty at full resolution steps down until it can, and stays there`() {
        val auto = AutoResolution()
        val clock = longArrayOf(1_000_000_000L)
        val frames = longArrayOf(0L)
        auto.reset(clock[0])
        run(auto, clock, frames, 60.0, phone)
        val settled = auto.scale
        assert(phone(settled) >= AutoResolution.DOWN_FPS) { "settled at $settled, ${phone(settled)} fps" }
        assert(settled < 1.0) { "never stepped down" }
        run(auto, clock, frames, 120.0, phone)
        assertEquals("it wandered", settled, auto.scale, 1e-9)
    }

    @Test
    fun `a fast device stays at full resolution`() {
        val auto = AutoResolution()
        val clock = longArrayOf(1_000_000_000L)
        val frames = longArrayOf(0L)
        auto.reset(clock[0])
        run(auto, clock, frames, 60.0) { 60.0 }
        assertEquals(1.0, auto.scale, 1e-9)
    }

    @Test
    fun `once the heavy part is over it comes back up to full`() {
        val auto = AutoResolution()
        val clock = longArrayOf(1_000_000_000L)
        val frames = longArrayOf(0L)
        auto.reset(clock[0])
        run(auto, clock, frames, 60.0, phone)
        assert(auto.scale < 1.0)
        run(auto, clock, frames, 60.0) { 60.0 }
        assertEquals(1.0, auto.scale, 1e-9)
    }
}
