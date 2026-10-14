package com.rm.apogee.desktop

import com.rm.apogee.core.math.Quat
import com.rm.apogee.core.math.Vec3
import com.rm.apogee.core.part.ModelSpec
import com.rm.apogee.game.ShroudPanel
import org.junit.Assert.*
import org.junit.Test

class ShroudPanelTest {
    private val white = floatArrayOf(1f, 1f, 1f, 1f)
    private val shape = ModelSpec.Lathe(listOf(listOf(1.0, 0.0), listOf(1.0, 3.0)), segments = 6, sweep = 120.0)

    @Test fun fastMovingPanelsFirstAppearAtTheAttachedShellForEveryFrameRate() {
        for (speed in listOf(0.0, 750.0, 7500.0)) for (frameDt in listOf(1.0 / 120, 1.0 / 30, .1)) {
            for (side in 0..2) {
                val origin = Vec3(627_000.0, 1234.0, 5678.0)
                val rotation = Quat.fromAxisAngle(Vec3(1.0, 2.0, 3.0).normalized(), .7)
                val foot = Vec3(kotlin.math.cos(side * 2.0), 0.0, kotlin.math.sin(side * 2.0))
                val velocity = Vec3(speed, 8.0, 0.0)
                val pivot = rotation.rotate(foot).addInPlace(origin)
                val panel = ShroudPanel(shape, "terra", pivot.copy(), velocity.copy(), rotation,
                    Vec3.unitY(), 1.3, foot, 99)
                val first = panel.advance(frameDt, 9.81, white)
                assertTrue("new shell starts on the vehicle at $speed m/s, dt=$frameDt", first.position.distanceTo(origin) < 1e-8)
                assertEquals(0.0, panel.age, 0.0)
                assertTrue("no impulse from the preceding frame", panel.velocity.approxEquals(velocity, 1e-10))
                assertTrue("initial attitude is attached attitude", first.rotation.rotate(Vec3.unitY()).distanceTo(rotation.rotate(Vec3.unitY())) < 1e-9)
                val next = panel.advance(frameDt, 9.81, white)
                val gravity = pivot.normalized().mulInPlace(-9.81)
                val expected = pivot.copy().addScaledInPlace(velocity, frameDt).addScaledInPlace(gravity, frameDt * frameDt)
                val actualPivot = next.rotation.rotate(foot).addInPlace(next.position)
                assertTrue("subsequent motion includes just one frame", actualPivot.distanceTo(expected) < 1e-8)
                assertEquals(frameDt, panel.age, 1e-12)
            }
        }
    }

    @Test fun pausedCreationAndResumeDoNotReplayTheFrameBeforeStaging() {
        val panel = ShroudPanel(shape, "terra", Vec3(627_000.0, 0.0, 0.0), Vec3(750.0, 0.0, 0.0),
            Quat.identity(), Vec3.unitY(), 0.0, Vec3(), 1)
        val first = panel.advance(.1, 0.0, white)
        repeat(4) { assertTrue(panel.advance(0.0, 0.0, white).position.approxEquals(first.position, 0.0)) }
        val resumed = panel.advance(.02, 0.0, white)
        assertEquals(15.0, resumed.position.x - first.position.x, 1e-8)
    }
}
