package com.rm.apogee.render

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Test
import kotlin.random.Random

class LongMapTest {
    @Test fun putGetAndReplace() {
        val map = LongMap<String>()
        assertNull(map.put(5L, "a"))
        assertNull(map.put(-7L, "b"))
        assertEquals("a", map[5L])
        assertEquals("b", map[-7L])
        assertNull(map[6L])
        assertEquals("a", map.put(5L, "c"))
        assertEquals("c", map[5L])
        assertEquals(2, map.size)
    }

    @Test fun zeroAndExtremeKeys() {
        val map = LongMap<String>()
        map[0L] = "zero"; map[Long.MIN_VALUE] = "min"; map[Long.MAX_VALUE] = "max"
        assertEquals("zero", map[0L])
        assertEquals("min", map[Long.MIN_VALUE])
        assertEquals("max", map[Long.MAX_VALUE])
        assertEquals(3, map.size)
        assertEquals("zero", map.remove(0L))
        assertFalse(0L in map)
        assertEquals(2, map.size)
    }

    @Test fun growsPastItsStartingRoom() {
        val map = LongMap<Long>(2)
        for (k in 1L..5000L) map[k * 1_000_003L] = k
        assertEquals(5000, map.size)
        for (k in 1L..5000L) assertEquals(k, map[k * 1_000_003L])
        assertNull(map[3L])
    }

    @Test fun collidingKeysSurviveRemovalInTheirRun() {
        // Keys that differ only above the bits the hash mixes down still land in runs; check every
        // removal order against a plain map.
        val random = Random(42)
        val map = LongMap<Long>(8)
        val reference = HashMap<Long, Long>()
        val keys = LongArray(400) { (it.toLong() shl 40) or (it.toLong() and 3) }
        repeat(20_000) {
            val key = keys[random.nextInt(keys.size)]
            when (random.nextInt(3)) {
                0, 1 -> assertEquals(reference.put(key, it.toLong()), map.put(key, it.toLong()))
                else -> assertEquals(reference.remove(key), map.remove(key))
            }
            assertEquals(reference.size, map.size)
        }
        for (key in keys) assertEquals(reference[key], map[key])
    }

    @Test fun removeMissingAndClear() {
        val map = LongMap<String>()
        assertNull(map.remove(9L))
        for (k in 1L..100L) map[k] = k.toString()
        map.clear()
        assertEquals(0, map.size)
        for (k in 1L..100L) assertNull(map[k])
        map[50L] = "again"
        assertEquals("again", map[50L])
    }
}
