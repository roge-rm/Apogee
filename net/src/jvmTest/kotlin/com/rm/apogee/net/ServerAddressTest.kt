package com.rm.apogee.net

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ServerAddressTest {

    private fun parse(text: String) = ServerAddress.parse(text, DEFAULT)

    @Test
    fun `bare address takes the default port`() {
        assertEquals(ServerAddress("10.0.0.233", DEFAULT), parse("10.0.0.233"))
    }

    @Test
    fun `explicit port wins`() {
        assertEquals(ServerAddress("10.0.0.233", 45_000), parse("10.0.0.233:45000"))
    }

    @Test
    fun `hostnames work`() {
        assertEquals(ServerAddress("apogee.example.com", DEFAULT), parse("apogee.example.com"))
    }

    /** Someone typing on a phone gets a space from autocorrect sooner or later. */
    @Test
    fun `surrounding whitespace is ignored`() {
        assertEquals(ServerAddress("10.0.0.233", DEFAULT), parse("  10.0.0.233 "))
    }

    @Test
    fun `bracketed ipv6 keeps its colons`() {
        assertEquals(ServerAddress("fd00::1", DEFAULT), parse("[fd00::1]"))
        assertEquals(ServerAddress("fd00::1", 45_000), parse("[fd00::1]:45000"))
    }

    /** A VPN might well hand out a v6 address, and nobody brackets one by hand. */
    @Test
    fun `bare ipv6 is recognised by its colons`() {
        assertEquals(ServerAddress("fd00::1", DEFAULT), parse("fd00::1"))
    }

    @Test
    fun `incomplete input isn't an address`() {
        assertNull(parse(""))
        assertNull(parse("   "))
        assertNull(parse("10.0.0.233:"))
        assertNull(parse("10.0.0.233:notaport"))
        assertNull(parse("10.0.0.233:0"))
        assertNull(parse("10.0.0.233:70000"))
        assertNull(parse("[fd00::1"))
        assertNull(parse("http://10.0.0.233"))
    }

    @Test
    fun `label hides the usual port and shows an unusual one`() {
        assertEquals("10.0.0.233", ServerAddress("10.0.0.233", DEFAULT).label(DEFAULT))
        assertEquals("10.0.0.233:45000", ServerAddress("10.0.0.233", 45_000).label(DEFAULT))
    }

    private companion object {
        const val DEFAULT = 45_678
    }
}
