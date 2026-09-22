package com.rm.apogee.server

import com.rm.apogee.core.craft.VesselId
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
     * Steps the server until [condition] holds.
     *
     * Counting `yield()`s is not a synchronisation primitive, and an earlier
     * version of these tests did exactly that: a fixed number of yields that
     * passed happily until unrelated work changed how many coroutines were in
     * flight, at which point two of them began failing for no reason connected
     * to what they were testing. Waiting on the actual condition is both faster
     * and stable.
     */
    private suspend fun pumpUntil(
        server: GameServer,
        what: String,
        maxTicks: Int = 400,
        condition: () -> Boolean,
    ) {
        repeat(maxTicks) {
            if (condition()) return
            server.stepOnce()
            repeat(SETTLE_YIELDS) { yield() }
        }
        if (!condition()) throw AssertionError("timed out waiting for: $what")
    }

    /**
     * Connects a client and waits for the handshake to resolve either way.
     *
     * Clients are launched into runTest's `backgroundScope`, which it cancels
     * on completion - a client collecting from a transport never finishes on
     * its own, so launching into the test scope itself would hang every test.
     */
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
        pumpUntil(server, "$name's handshake to resolve") {
            client.connected || client.rejectionReason != null
        }
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

        pumpUntil(server, "both clients to see both craft") {
            alice.vessels.size == 2 && bob.vessels.size == 2
        }

        assertEquals(2, server.playerCount)
        assertNotNull(
            "Alice should know the structure of Bob's craft",
            alice.vessel(bob.controlledVessel!!)?.design,
        )
        assertNotNull(
            "and Bob should know Alice's",
            bob.vessel(alice.controlledVessel!!)?.design,
        )
    }

    @Test
    fun `one client's throttle moves its craft for the other to see`() = runTest {
        val server = GameServer.default(catalog)
        val alice = joinClient(server, backgroundScope, "Alice")
        val bob = joinClient(server, backgroundScope, "Bob")
        pumpUntil(server, "both clients to see both craft") {
            alice.vessels.size == 2 && bob.vessels.size == 2
        }

        val aliceVessel = alice.controlledVessel!!
        val startAltitude = server.world.vessel(VesselId(aliceVessel))!!.body.position.length

        alice.send(Command.Stage(aliceVessel))
        alice.send(Command.SetThrottle(aliceVessel, 1.0))
        pumpUntil(server, "the server to apply Alice's throttle") {
            server.world.vessel(VesselId(aliceVessel))?.control?.throttle == 1.0
        }

        repeat(400) { server.stepOnce() }
        repeat(SETTLE_YIELDS) { yield() }

        val endAltitude = server.world.vessel(VesselId(aliceVessel))!!.body.position.length
        assertTrue(
            "Alice's craft should have climbed ($startAltitude -> $endAltitude)",
            endAltitude > startAltitude + 5.0,
        )

        // Bob receives it without ever having asked.
        assertNotNull(
            "Bob should have motion for Alice's craft",
            bob.vessel(aliceVessel)?.latest,
        )
    }

    @Test
    fun `a client cannot fly a craft it does not own`() = runTest {
        val server = GameServer.default(catalog)
        val alice = joinClient(server, backgroundScope, "Alice")
        val bob = joinClient(server, backgroundScope, "Bob")
        pumpUntil(server, "both clients to be flying something") {
            alice.controlledVessel != null && bob.controlledVessel != null
        }

        val alicesVessel = VesselId(alice.controlledVessel!!)

        // Bob tries to throttle up Alice's rocket. In a persistent shared world
        // this is the difference between a sandbox and a free-for-all.
        bob.send(Command.SetThrottle(alicesVessel.raw, 1.0))
        repeat(20) { server.stepOnce(); repeat(SETTLE_YIELDS) { yield() } }

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
        // Waiting on controlledVessel alone is not enough: that arrives in the
        // welcome, while the craft's *structure* is a separate message on a
        // separate channel. Dereferencing the vessel before it lands is a
        // one-in-many-runs null, which is exactly the kind of flake that gets
        // blamed on the test rather than on the assumption.
        pumpUntil(server, "the client to know its craft's structure") {
            client.controlledVessel?.let { client.vessel(it) != null } == true
        }

        val vesselId = client.controlledVessel!!
        assertEquals(0, client.vessel(vesselId)!!.currentStage)

        client.send(Command.Stage(vesselId))
        // Igniting an engine changes no part list, so this only arrives if the
        // server broadcasts structure on a plain stage as well as on a split.
        pumpUntil(server, "the stage change to reach the client") {
            client.vessel(vesselId)!!.currentStage == 1
        }

        assertTrue(
            "the lit engine should be marked activated",
            client.vessel(vesselId)!!.activatedParts.isNotEmpty(),
        )
    }

    @Test
    fun `two players spawn on separate pads`() = runTest {
        val server = GameServer.default(catalog)
        val alice = joinClient(server, backgroundScope, "Alice")
        val bob = joinClient(server, backgroundScope, "Bob")
        pumpUntil(server, "both to be flying something") {
            alice.controlledVessel != null && bob.controlledVessel != null
        }

        val one = server.world.vessel(VesselId(alice.controlledVessel!!))!!
        val two = server.world.vessel(VesselId(bob.controlledVessel!!))!!
        val separation = one.body.position.distanceTo(two.body.position)

        // Spawning both at the same point drops one craft inside the other and
        // the contact solver flings them apart, which is a memorable but
        // unhelpful way to begin a game.
        assertTrue(
            "craft should spawn clear of each other, were ${separation}m apart",
            separation > 20.0,
        )
        assertTrue(
            "but still at the same launch site, were ${separation}m apart",
            separation < 200.0,
        )
    }

    @Test
    fun `chat reaches every connected client`() = runTest {
        val server = GameServer.default(catalog)
        val alice = joinClient(server, backgroundScope, "Alice")
        val bob = joinClient(server, backgroundScope, "Bob")
        pumpUntil(server, "both clients to be flying something") {
            alice.controlledVessel != null && bob.controlledVessel != null
        }

        alice.send(Command.Chat("hello from the pad"))
        pumpUntil(server, "the chat line to reach Bob") {
            bob.chatHistory().any { it.contains("hello from the pad") }
        }
    }

    private companion object {
        /** Yields per pumped tick, to let both sides' coroutines drain. */
        const val SETTLE_YIELDS = 8
    }
}
