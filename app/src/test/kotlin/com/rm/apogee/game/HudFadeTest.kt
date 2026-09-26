package com.rm.apogee.game

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** The controls fade after a few seconds untouched, and come back at once. */
class HudFadeTest {
    @Test
    fun `idle only once four seconds have passed without a wake`() {
        val fade = HudFade()
        val start = 1_000_000_000_000L
        fade.wake(start)
        assertFalse(fade.idle(start + 3_900_000_000L))
        assertTrue(fade.idle(start + 4_100_000_000L))
    }

    @Test
    fun `a touch brings it straight back, and starts the wait again`() {
        val fade = HudFade()
        val start = 1_000_000_000_000L
        fade.wake(start)
        assertTrue(fade.idle(start + 10_000_000_000L))
        fade.wake(start + 10_000_000_000L)
        assertFalse(fade.idle(start + 10_000_000_001L))
        assertFalse(fade.idle(start + 13_000_000_000L))
        assertTrue(fade.idle(start + 14_500_000_000L))
    }

    @Test
    fun `the hud's touch wakes its fade`() {
        val hud = HudState()
        hud.fade.wake(0L)
        assertTrue(hud.fade.idle(System.nanoTime()))
        hud.touched()
        assertFalse(hud.fade.idle(System.nanoTime()))
    }
}
