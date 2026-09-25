package com.rm.apogee.render

import com.rm.apogee.core.math.Quat
import com.rm.apogee.core.math.Vec3
import com.rm.apogee.core.part.MeshSpec
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Test

class ItemMatcherTest {

    private val shape = MeshSpec.Cylinder(radius = 1.0, height = 2.0)

    private fun item(key: Long) = RenderItem(shape, Vec3(), Quat.identity(), floatArrayOf(1f, 1f, 1f, 1f), key = key)

    @Test
    fun `a flame lighting ahead of the craft does not shift its parts onto each other`() {
        val a0 = item(RenderItem.partKey(1, 11, 0)); val b0 = item(RenderItem.partKey(1, 22, 0))
        val cloud0 = item(0L)
        val a1 = item(a0.key); val b1 = item(b0.key); val cloud1 = item(0L)
        val flame = item(RenderItem.effectKey(31, 0))
        val matcher = ItemMatcher()
        // Last frame: a, b, cloud. This frame a flame comes first.
        matcher.match(listOf(flame, a1, b1, cloud1), listOf(a0, b0, cloud0))
        assertNull("new this frame: nothing to ease from", matcher.partners[0])
        assertSame(a0, matcher.partners[1])
        assertSame(b0, matcher.partners[2])
        assertSame("the unkeyed keep their own order", cloud0, matcher.partners[3])
    }

    @Test
    fun `a part gone this frame leaves the rest matched to themselves`() {
        val a0 = item(RenderItem.partKey(1, 11, 0)); val b0 = item(RenderItem.partKey(1, 22, 0)); val c0 = item(RenderItem.partKey(1, 33, 0))
        val matcher = ItemMatcher()
        val b1 = item(b0.key); val c1 = item(c0.key)
        matcher.match(listOf(b1, c1), listOf(a0, b0, c0))
        assertSame(b0, matcher.partners[0])
        assertSame(c0, matcher.partners[1])
    }
}
