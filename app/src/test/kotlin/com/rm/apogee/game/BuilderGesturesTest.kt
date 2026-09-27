package com.rm.apogee.game

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class BuilderGesturesTest {

    private class Log(val canLift: Boolean = true) : BuilderGestures.Listener {
        val events = ArrayList<String>()
        var orbited = 0f
        var panned = 0f
        var zoomed = 1f
        override fun tap(x: Float, y: Float) { events += "tap" }
        override fun doubleTap(x: Float, y: Float) { events += "double" }
        override fun longPress(x: Float, y: Float): Boolean { events += "long"; return canLift }
        override fun carry(x: Float, y: Float) { events += "carry" }
        override fun drop(x: Float, y: Float) { events += "drop" }
        override fun orbit(dx: Float, dy: Float) { orbited += kotlin.math.abs(dx) + kotlin.math.abs(dy) }
        override fun pan(dx: Float, dy: Float) { panned += kotlin.math.abs(dx) + kotlin.math.abs(dy) }
        override fun zoom(factor: Float) { zoomed *= factor }
    }

    @Test
    fun `a quick touch is a tap, and two close together are a double tap`() {
        val log = Log(); val g = BuilderGestures(log)
        g.down(100f, 100f, 0); g.up(103f, 101f, 90)
        g.down(110f, 104f, 200); g.up(110f, 104f, 280)
        assertEquals(listOf("tap", "double"), log.events)
        // A third one is a tap again, not another double.
        g.down(110f, 104f, 400); g.up(110f, 104f, 450)
        assertEquals("tap", log.events.last())
    }

    @Test
    fun `a drag turns the view and isn't a tap`() {
        val log = Log(); val g = BuilderGestures(log)
        g.down(100f, 100f, 0); g.move(160f, 100f); g.up(160f, 100f, 150)
        assertTrue(log.orbited > 50f)
        assertTrue(log.events.isEmpty())
    }

    @Test
    fun `held still it picks up, then carries, then drops`() {
        val log = Log(); val g = BuilderGestures(log)
        g.down(100f, 100f, 0)
        g.tick(200); assertTrue("not yet", log.events.isEmpty())
        g.tick(500)
        g.move(150f, 180f); g.move(200f, 260f)
        g.up(200f, 260f, 900)
        assertEquals(listOf("long", "carry", "carry", "drop"), log.events)
        assertEquals("carrying does not turn the view", 0f, log.orbited)
    }

    @Test
    fun `held still over nothing, the finger can still turn the view`() {
        val log = Log(canLift = false); val g = BuilderGestures(log)
        g.down(100f, 100f, 0); g.tick(500); g.tick(900)
        g.move(180f, 100f); g.up(180f, 100f, 1200)
        assertEquals(listOf("long"), log.events)
        assertTrue(log.orbited > 50f)
    }

    @Test
    fun `two fingers pan and zoom, and the one left behind does nothing`() {
        val log = Log(); val g = BuilderGestures(log)
        g.down(100f, 100f, 0)
        g.secondDown(100f, 100f, 200f, 100f)
        g.move(120f, 140f, 260f, 140f)
        assertTrue(log.panned > 30f)
        assertTrue("spread 100 -> 140", log.zoomed > 1.3f)
        g.secondUp()
        g.move(400f, 400f)
        g.up(400f, 400f, 300)
        assertEquals(0f, log.orbited)
        assertTrue(log.events.isEmpty())
    }
}
