package com.rm.apogee.core

import com.rm.apogee.core.part.StockParts
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Test
import java.security.MessageDigest

/** The common stand-ins for the JVM's own, held to what the JVM gives. */
class PlatformTest {

    private fun jdk(bytes: ByteArray) = MessageDigest.getInstance("SHA-256").digest(bytes)

    @Test
    fun `sha256 matches the JVM's at every length, across the block boundaries`() {
        val random = kotlin.random.Random(7)
        for (length in (0..200) + listOf(447, 448, 511, 512, 513, 4_096, 100_000)) {
            val bytes = random.nextBytes(length)
            assertArrayEquals("length $length", jdk(bytes), Sha256.digest(bytes))
        }
        assertEquals("e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855", hex(Sha256.digest(ByteArray(0))))
    }

    @Test
    fun `the hashes clients and servers compare come out as they did`() {
        // Worked the old way, straight from the JDK, from the same text.
        val text = resourceText("/parts/stock.json")!!.encodeToByteArray()
        assertEquals(jdk(text).joinToString("") { "%02x".format(it) }, hex(Sha256.digest(text)))
        assertEquals(16, StockParts.catalog.contentHash.length)
    }

    @Test
    fun `the resources are found`() {
        for (path in listOf("/parts/stock.json", "/parts/bases.json", "/parts/structures.json", "/career/tree.json")) {
            assertNotNull(path, resourceText(path))
        }
    }

    @Test
    fun `the conversions are the JVM's to the last bit`() {
        val random = kotlin.random.Random(3)
        repeat(10_000) {
            val x = (random.nextDouble() - 0.5) * 1_000.0
            assertEquals(java.lang.Math.toRadians(x), com.rm.apogee.core.math.Math.toRadians(x), 0.0)
            assertEquals(java.lang.Math.toDegrees(x), com.rm.apogee.core.math.Math.toDegrees(x), 0.0)
        }
    }

    @Test
    fun `an lru map throws out the least recently used`() {
        val map = lruMapOf<Int, String>(4, 3)
        map[1] = "a"; map[2] = "b"; map[3] = "c"
        map[1]
        map[4] = "d"
        assertEquals(setOf(1, 3, 4), map.keys)
    }
}
