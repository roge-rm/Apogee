package com.rm.apogee.desktop

import com.rm.apogee.core.math.Quat
import com.rm.apogee.core.math.Vec3
import com.rm.apogee.core.part.MeshSpec
import com.rm.apogee.render.ItemMatcher
import com.rm.apogee.render.RenderItem
import org.junit.Assert.*
import org.junit.Test

class DetachedPanelInterpolationTest {
    private val colour = floatArrayOf(1f, 1f, 1f, 1f)
    private fun item(key: Long, position: Vec3, rotation: Quat = Quat.identity(), reference: Long = 0) =
        RenderItem(MeshSpec.Sphere(1.0), position, rotation, colour, key = key, spawnReference = reference)

    @Test fun newPanelSharesItsSourcePartsInterpolationAtFlightSpeed() {
        for (speed in listOf(750.0, 7500.0)) for (dt in listOf(1.0 / 120, 1.0 / 30, .1)) {
            val before = item(1, Vec3(627_000.0, 0.0, 0.0))
            val now = item(1, before.position.copy().addInPlace(Vec3(speed * dt, 0.0, 0.0)))
            val offset = Vec3(0.0, 3.0, 1.0)
            val panel = item(2, now.position.copy().addInPlace(offset), reference = 1)
            val matcher = ItemMatcher()
            matcher.match(listOf(now, panel), listOf(before))
            val prior = matcher.partners[1]!!
            for (alpha in listOf(0.0, .25, .5, 1.0)) {
                val shownPart = before.position.copy().mulInPlace(1 - alpha).addScaledInPlace(now.position, alpha)
                val shownPanel = prior.position.copy().mulInPlace(1 - alpha).addScaledInPlace(panel.position, alpha)
                assertTrue("panel stays at its source at $speed m/s, dt=$dt, alpha=$alpha", shownPanel.subInPlace(shownPart).distanceTo(offset) < 1e-8)
            }
            // After birth, the panel matches its own preceding frame instead of following the rocket.
            val moving = item(2, panel.position.copy().addInPlace(Vec3(1.0, 0.0, 0.0)))
            matcher.match(listOf(now, moving), listOf(now, panel))
            assertSame(panel, matcher.partners[1])
        }
    }

    @Test fun aTurningSourcesPreviousPoseReconstructsTheAttachedCover() {
        val a = Quat.fromAxisAngle(Vec3.unitZ(), .3)
        val b = Quat.fromAxisAngle(Vec3.unitZ(), .5)
        val before = item(1, Vec3(100.0, 200.0, 300.0), a)
        val now = item(1, Vec3(120.0, 210.0, 305.0), b)
        val local = Vec3(2.0, 3.0, 4.0)
        val panel = item(2, b.rotate(local).addInPlace(now.position), b, 1)
        val matcher = ItemMatcher()
        matcher.match(listOf(now, panel), listOf(before))
        val previous = matcher.partners[1]!!
        assertTrue(previous.position.distanceTo(a.rotate(local).addInPlace(before.position)) < 1e-10)
        assertTrue(previous.rotation.rotate(Vec3.unitY()).distanceTo(a.rotate(Vec3.unitY())) < 1e-10)
        matcher.match(listOf(panel), emptyList())
        assertNull("missing source uses the normal new-item path", matcher.partners[0])
    }
}
