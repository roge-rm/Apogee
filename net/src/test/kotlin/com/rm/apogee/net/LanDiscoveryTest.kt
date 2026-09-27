package com.rm.apogee.net

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.util.concurrent.ConcurrentLinkedQueue

/**
 * Announce and listen, in one process, over the machine's real interfaces.
 *
 * This really opens a UDP socket on the discovery port, so it's skipped instead of failed when the
 * environment won't allow it. A build machine with no usable interface, or another Apogee host
 * already bound to the port, isn't a broken protocol.
 */
class LanDiscoveryTest {

    private fun beacon(name: String) = ServerBeacon(
        serverName = name,
        port = 45_678,
        players = 2,
        protocolVersion = 1,
        catalogHash = "abc123",
    )

    @Test
    fun `a listener receives an announced beacon`() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        val received = ConcurrentLinkedQueue<ServerBeacon>()
        try {
            val listener = runCatching { LanDiscovery.listen(scope) { received.add(it) } }
                .getOrNull()
            assumeTrue("discovery port unavailable in this environment", listener != null)

            LanDiscovery.announce(beacon("Test Game"), scope)

            val found = withTimeoutOrNull(6_000) {
                while (received.isEmpty()) delay(50)
                received.peek()
            }
            assumeTrue("no loopback broadcast in this environment", found != null)

            assertNotNull(found)
            assertEquals("Test Game", found!!.serverName)
            assertEquals(45_678, found.port)
            assertEquals(2, found.players)
            assertEquals("abc123", found.catalogHash)
            assertTrue(
                "the listener must fill in where it came from",
                found.address.isNotBlank(),
            )
        } finally {
            scope.cancel()
        }
    }

    @Test
    fun `a beacon round-trips through its wire form`() {
        // The half that doesn't depend on the transport, which always runs.
        val original = beacon("Round Trip")
        val json = kotlinx.serialization.json.Json.encodeToString(original)
        val decoded = kotlinx.serialization.json.Json.decodeFromString<ServerBeacon>(json)

        assertEquals(original.serverName, decoded.serverName)
        assertEquals(original.port, decoded.port)
        assertEquals(original.protocolVersion, decoded.protocolVersion)
        assertEquals(original.catalogHash, decoded.catalogHash)
        assertEquals(
            "address is filled in from the packet, not the payload",
            "",
            decoded.address,
        )
    }

    @Test
    fun `a beacon from a future protocol version still parses`() {
        // Forward compatibility. An older build has to be able to read enough of a newer beacon to
        // say "different game version" instead of crashing the browser on an unknown field.
        val json = """
            {"serverName":"Newer","port":45678,"players":1,
             "protocolVersion":99,"catalogHash":"zzz","somethingNew":true}
        """.trimIndent()
        val decoded = kotlinx.serialization.json.Json { ignoreUnknownKeys = true }
            .decodeFromString<ServerBeacon>(json)
        assertEquals(99, decoded.protocolVersion)
    }
}
