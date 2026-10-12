package com.rm.apogee.game

import com.rm.apogee.net.ServerBeacon
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test

class DiscoveredServerTest {
    private fun beacon(address: String, id: String = "abc", port: Int = 45678) =
        ServerBeacon("Pilot's Game", port, 1, 1, "hash", hostId = id).also { it.address = address }

    @Test
    fun oneGameOnTwoNetworksIsOneEntry() {
        assertEquals(DiscoveredServer(beacon("127.0.0.1"), 0).key, DiscoveredServer(beacon("10.0.0.5"), 0).key)
    }

    @Test
    fun twoGamesOfTheSameNameStayApart() {
        assertNotEquals(DiscoveredServer(beacon("10.0.0.5", "abc"), 0).key, DiscoveredServer(beacon("10.0.0.6", "def"), 0).key)
    }

    @Test
    fun keepsTheAddressOthersCanReach() {
        val lan = DiscoveredServer.heard(null, beacon("10.0.0.5"), 1)
        assertEquals("10.0.0.5", DiscoveredServer.heard(lan, beacon("127.0.0.1"), 2).beacon.address)
        val loop = DiscoveredServer.heard(null, beacon("127.0.0.1"), 1)
        assertEquals("10.0.0.5", DiscoveredServer.heard(loop, beacon("10.0.0.5"), 2).beacon.address)
    }

    @Test
    fun anOlderHostIsKnownByItsAddress() {
        assertNotEquals(DiscoveredServer(beacon("10.0.0.5", ""), 0).key, DiscoveredServer(beacon("10.0.0.6", ""), 0).key)
    }
}
