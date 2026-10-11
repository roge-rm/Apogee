package com.rm.apogee.game

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** The controls fade after five minutes without being touched, and come back straight away. */
class HudFadeTest {
    @Test
    fun `idle only once five minutes have passed without a wake`() {
        val fade = HudFade()
        val start = 1_000_000_000_000L
        fade.wake(start)
        assertFalse(fade.idle(start + 299_000_000_000L))
        assertTrue(fade.idle(start + 301_000_000_000L))
    }

    @Test
    fun `a touch brings it straight back and starts the wait again`() {
        val fade = HudFade()
        val start = 1_000_000_000_000L
        fade.wake(start)
        assertTrue(fade.idle(start + 400_000_000_000L))
        fade.wake(start + 400_000_000_000L)
        assertFalse(fade.idle(start + 400_000_000_001L))
        assertFalse(fade.idle(start + 690_000_000_000L))
        assertTrue(fade.idle(start + 701_000_000_000L))
    }

    @Test
    fun `the hud's touch wakes its fade`() {
        val hud = HudState()
        hud.fade.wake(System.nanoTime() - HudFade.IDLE_NANOS - 1_000_000_000L)
        assertTrue(hud.fade.idle(System.nanoTime()))
        hud.touched()
        assertFalse(hud.fade.idle(System.nanoTime()))
    }
}
