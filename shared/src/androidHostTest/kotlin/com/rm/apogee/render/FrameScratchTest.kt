package com.rm.apogee.render

import com.rm.apogee.core.math.Quat
import com.rm.apogee.core.math.Vec3
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.random.Random

/** Scratch objects reused in the frame build never end up in a drawn item, and give the same results. */
class FrameScratchTest {
    @Test fun cloudItemsKeepNothingTheCallerReuses() {
        val sky = CloudTransitions()
        val cloud = CloudTransitions.Cloud().apply {
            shape = CloudPuff(1, false, 2); key = RenderItem.cloudKey(1, 1); colour = floatArrayOf(.5f, .5f, .5f, .8f)
            alpha = .8f; scale = grown.setTo(10.0, 20.0, 30.0); position.setTo(1.0, 2.0, 3.0)
            rotation.setTo(Quat.fromAxisAngle(Vec3.unitY(), 0.3)); wrap = true; orderedTransparency = true
        }
        val revision = Any()
        sky.append(listOf(cloud), 1, revision, 0L, ArrayList())
        // Past the fade in, so it's drawn as listed.
        val first = ArrayList<RenderItem>().also { sky.append(listOf(cloud), 1, revision, 1_000_000_000L, it) }.single()
        assertEquals(.8f, first.color[3], 0f)
        assertSame(cloud.colour, first.color)
        cloud.position.setTo(9.0, 9.0, 9.0); cloud.rotation.setIdentity(); cloud.grown.setTo(1.0, 1.0, 1.0)
        sky.append(listOf(cloud), 1, revision, 1_100_000_000L, ArrayList())
        assertEquals(1.0, first.position.x, 0.0)
        assertEquals(20.0, first.scale!!.y, 0.0)
        assertTrue(first.rotation.y != 0.0)
    }

    @Test fun flapSignMatchesTurningTheParts() {
        val random = Random(7)
        val up = Vec3(0.2, 0.9, -0.3).normalizeInPlace()
        repeat(500) {
            val turn = Quat.fromAxisAngle(Vec3(random.nextDouble(-1.0, 1.0), random.nextDouble(-1.0, 1.0), random.nextDouble(-1.0, 1.0)).normalizeInPlace(),
                random.nextDouble(-Math.PI, Math.PI))
            val anim = PartAnim()
            PartModels.alignFlap(turn, up, anim)
            val expected = if ((turn.rotate(Vec3(0.0, 0.0, 1.0)) dot up) >= 0.0) 1.0 else -1.0
            assertEquals(expected, anim.flapSign, 0.0)
        }
    }
}
