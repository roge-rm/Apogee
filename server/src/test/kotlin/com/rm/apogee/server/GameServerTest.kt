package com.rm.apogee.server

import com.rm.apogee.core.part.StockParts
import com.rm.apogee.core.world.Command
import com.rm.apogee.net.GameClient
import com.rm.apogee.net.LoopbackTransportPair
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.yield
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class GameServerTest {

    private val catalog = StockParts.catalog

    /**
     * Lets both sides' coroutines make progress without real time passing.
     *
     * Clients are launched into runTest's `backgroundScope`, which it cancels
     * on completion - a client collecting from a transport never finishes on
     * its own, so launching into the test scope itself would hang every test.
     */
    private suspend fun settle(times: Int = 40) = repeat(times) { yield() }

    private suspend fun joinClient(
        server: GameServer,
        scope: CoroutineScope,
        name: String,
        catalogHash: String = catalog.contentHash,
    ): GameClient {
        val link = LoopbackTransportPair()
        server.accept(link.serverSide, scope)
        val client = GameClient(link.clientSide, name, catalogHash)
        client.connect(scope)
        settle()
        server.stepOnce()
        settle()
        return client
    }

    @Test
    fun `a client completes the handshake and is given a craft`() = runTest {
        val server = GameServer.default(catalog)
        val client = joinClient(server, backgroundScope, "Pilot")

        assertTrue("handshake should have completed", client.connected)
        assertNull(client.rejectionReason)
        assertNotNull("client should be given a vessel to fly", client.controlledVessel)
        assertEquals(1, server.playerCount)
    }

    @Test
    fun `a client with a different part catalogue is refused`() = runTest {
        val server = GameServer.default(catalog)
        val client = joinClient(server, backgroundScope, "Pilot", catalogHash = "not-the-same")

        assertFalse("should not be connected", client.connected)
        assertNotNull("should say why", client.rejectionReason)
        assertTrue(
            "reason should name the catalogue: ${client.rejectionReason}",
            client.rejectionReason!!.contains("catalogue", ignoreCase = true),
        )
        assertEquals(0, server.playerCount)
    }

    @Test
    fun `two clients in one world see each other's craft`() = runTest {
        val server = GameServer.default(catalog)

        val alice = joinClient(server, backgroundScope, "Alice")
        val bob = joinClient(server, backgroundScope, "Bob")

        // Let the join broadcasts and a snapshot reach both sides.
        repeat(5) { server.stepOnce(); settle() }

        assertEquals(2, server.playerCount)
        assertEquals("Alice should see both craft", 2, alice.vessels.size)
        assertEquals("Bob should see both craft", 2, bob.vessels.size)

        val bobsVessel = bob.controlledVessel!!
        assertNotNull(
            "Alice should know the structure of Bob's craft",
            alice.vessel(bobsVessel)?.design,
        )
    }

    @Test
    fun `one client's throttle moves its craft for the other to see`() = runTest {
        val server = GameServer.default(catalog)
        val alice = joinClient(server, backgroundScope, "Alice")
        val bob = joinClient(server, backgroundScope, "Bob")
        repeat(3) { server.stepOnce(); settle() }

        val aliceVessel = alice.controlledVessel!!
        val startAltitude = server.world.vessel(
            com.rm.apogee.core.craft.VesselId(aliceVessel)
        )!!.body.position.length

        alice.send(Command.Stage(aliceVessel))
        alice.send(Command.SetThrottle(aliceVessel, 1.0))
        settle()

        repeat(200) { server.stepOnce() }
        settle()

        val endAltitude = server.world.vessel(
            com.rm.apogee.core.craft.VesselId(aliceVessel)
        )!!.body.position.length
        assertTrue("Alice's craft should have climbed", endAltitude > startAltitude + 5.0)

        // Bob receives it without ever having asked.
        val seenByBob = bob.vessel(aliceVessel)?.latest
        assertNotNull("Bob should have motion for Alice's craft", seenByBob)
    }

    @Test
    fun `a client cannot fly a craft it does not own`() = runTest {
        val server = GameServer.default(catalog)
        val alice = joinClient(server, backgroundScope, "Alice")
        val bob = joinClient(server, backgroundScope, "Bob")
        repeat(3) { server.stepOnce(); settle() }

        val alicesVessel = com.rm.apogee.core.craft.VesselId(alice.controlledVessel!!)

        // Bob tries to throttle up Alice's rocket. In a persistent shared world
        // this is the difference between a sandbox and a free-for-all.
        bob.send(Command.SetThrottle(alicesVessel.raw, 1.0))
        settle()
        server.stepOnce()
        settle()

        assertEquals(
            "an unauthorised command must be discarded",
            0.0,
            server.world.vessel(alicesVessel)!!.control.throttle,
            0.0,
        )
    }

    @Test
    fun `staging is reflected back to the client`() = runTest {
        val server = GameServer.default(catalog)
        val client = joinClient(server, backgroundScope, "Pilot")
        repeat(3) { server.stepOnce(); settle() }

        val vesselId = client.controlledVessel!!
        assertEquals(0, client.vessel(vesselId)!!.currentStage)

        client.send(Command.Stage(vesselId))
        settle()
        server.stepOnce()
        settle()

        // Igniting an engine changes no part list, so this only arrives if the
        // server broadcasts structure on a plain stage as well as on a split.
        assertEquals(
            "client's stage counter should have advanced",
            1,
            client.vessel(vesselId)!!.currentStage,
        )
        assertTrue(
            "the lit engine should be marked activated",
            client.vessel(vesselId)!!.activatedParts.isNotEmpty(),
        )
    }

    @Test
    fun `chat reaches every connected client`() = runTest {
        val server = GameServer.default(catalog)
        val alice = joinClient(server, backgroundScope, "Alice")
        val bob = joinClient(server, backgroundScope, "Bob")
        repeat(3) { server.stepOnce(); settle() }

        alice.send(Command.Chat("hello from the pad"))
        settle()
        server.stepOnce()
        settle()

        assertTrue(
            "Bob should have received it, got ${bob.chatHistory()}",
            bob.chatHistory().any { it.contains("hello from the pad") },
        )
    }
}
