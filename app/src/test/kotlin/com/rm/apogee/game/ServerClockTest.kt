package com.rm.apogee.game

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.random.Random

class ServerClockTest {

    @Test
    fun `jittery arrivals give a steady present`() {
        val clock = ServerClock()
        assertNull(clock.now(0.0))
        val random = Random(4)
        // The server 1000 s ahead of the local clock; 20 snapshots a second,
        // each arriving 2 to 40 ms after it was sent.
        var worst = 0.0
        for (k in 0 until 400) {
            val sent = 1000.0 + k * 0.05
            val arrived = sent - 1000.0 + 0.002 + 0.038 * random.nextDouble()
            clock.sample(sent, arrived)
            if (k > 20) {
                // What it says the server's time is, a moment after: no more than
                // the least delay behind the truth, and never jumping.
                val error = (arrived + 1000.0) - clock.now(arrived)!!
                worst = maxOf(worst, kotlin.math.abs(error - 0.0))
            }
        }
        assertTrue("within a few ms of the least-delayed arrival: $worst", worst < 0.045)
        // Steadiness: successive estimates of the same instant barely move.
        val a = clock.now(50.0)!!
        clock.sample(1000.0 + 20.0, 20.0 + 0.04) // a late one
        assertEquals(a, clock.now(50.0)!!, 0.0025)
    }

    @Test
    fun `a server falling behind is followed`() {
        val clock = ServerClock()
        // Its clock runs 2% slow against ours.
        for (k in 0 until 400) clock.sample(k * 0.05 * 0.98, k * 0.05 + 0.005)
        val local = 399 * 0.05
        assertEquals(399 * 0.05 * 0.98, clock.now(local)!!, 0.03)
    }

    /**
     * A quicker arrival steps the estimate at once, but the present drawn at
     * it moves smoothly: at orbital speed a millisecond is a metre.
     */
    @Test
    fun `a step in the estimate is spread out, not jumped`() {
        val clock = ServerClock()
        clock.sample(100.0, 0.05)
        var last = clock.now(0.05)!!
        var local = 0.05
        // A snapshot 20 ms quicker than thought: the estimate moves 10 ms.
        clock.sample(100.05, 0.08)
        var worst = 0.0
        repeat(60) {
            local += 1.0 / 60.0
            val now = clock.now(local)!!
            worst = maxOf(worst, kotlin.math.abs((now - last) - 1.0 / 60.0))
            last = now
        }
        assertTrue("no frame's present steps more than the slew allows: $worst", worst <= ServerClock.SLEW / 60.0 + 1e-9)
        // And it has caught up within the second.
        assertEquals(100.05 - 0.08 + local, clock.now(local)!!, 0.011)
    }
}
