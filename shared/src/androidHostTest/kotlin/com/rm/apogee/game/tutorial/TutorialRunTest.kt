package com.rm.apogee.game.tutorial

import com.rm.apogee.core.craft.StockCraft
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TutorialRunTest {
    private val tutorial = Tutorial(
        "test", Topic.OTHER, "Test",
        TutorialStart.OnSite({ StockCraft.sounder(it) }, "cape"),
        listOf(
            Step("Throttle up") { v, _ -> v.throttle > 0.5 },
            Step("Hold it", holdFor = 2.0) { v, _ -> v.agl > 10.0 },
            Step("Stage") { v, r -> v.stage != r.first?.stage },
        ),
    )

    @Test
    fun `a step ticks, waits, then the next one comes`() {
        val run = TutorialRun(tutorial)
        assertFalse(run.tick(TutorialView(), 0.0))
        assertEquals(1, run.line.number)
        assertTrue(run.tick(TutorialView(throttle = 1.0), 1.0))
        assertTrue(run.line.ticked)
        assertFalse("too soon to move on", run.tick(TutorialView(throttle = 1.0), 1.5))
        assertEquals(1, run.line.number)
        assertTrue(run.tick(TutorialView(throttle = 1.0), 1.0 + TutorialRun.TICK_SECONDS))
        assertEquals(2, run.line.number)
        assertFalse(run.line.ticked)
    }

    @Test
    fun `only the step you're on counts, so later ones can't be done early`() {
        val run = TutorialRun(tutorial)
        run.tick(TutorialView(stage = 0), 0.0)
        run.tick(TutorialView(stage = 3, agl = 50.0), 1.0)
        assertEquals("Throttle up", run.line.text)
        assertFalse(run.line.ticked)
    }

    @Test
    fun `a held step has to hold the whole time, and starts over if it slips`() {
        val run = TutorialRun(tutorial)
        run.tick(TutorialView(throttle = 1.0), 0.0)
        run.tick(TutorialView(throttle = 1.0), 2.0)
        assertEquals(2, run.line.number)
        run.tick(TutorialView(agl = 20.0), 3.0)
        run.tick(TutorialView(agl = 5.0), 4.0)
        run.tick(TutorialView(agl = 20.0), 4.5)
        assertFalse("slipped, so the hold started again", run.tick(TutorialView(agl = 20.0), 6.0))
        assertTrue(run.tick(TutorialView(agl = 20.0), 6.6))
    }

    @Test
    fun `it finishes after the last step`() {
        val run = TutorialRun(tutorial)
        var t = 0.0
        fun look(v: TutorialView) { run.tick(v, t); t += 1.5 }
        look(TutorialView(stage = 0))
        look(TutorialView(throttle = 1.0)); look(TutorialView(throttle = 1.0))
        repeat(4) { look(TutorialView(agl = 20.0)) }
        look(TutorialView(stage = 1)); look(TutorialView(stage = 1))
        assertTrue(run.finished)
        assertTrue(run.line.finished)
        assertEquals("Test", run.line.text)
    }
}
