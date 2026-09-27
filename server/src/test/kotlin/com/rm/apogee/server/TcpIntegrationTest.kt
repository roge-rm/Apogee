package com.rm.apogee.server

import com.rm.apogee.core.craft.VesselId
import com.rm.apogee.core.part.StockParts
import com.rm.apogee.core.world.Command
import com.rm.apogee.core.world.World
import com.rm.apogee.net.Channel
import com.rm.apogee.net.Codec
import com.rm.apogee.net.GameClient
import com.rm.apogee.net.Packet
import com.rm.apogee.net.TcpListener
import com.rm.apogee.net.TcpTransport
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Two clients, one server, over real TCP sockets on the loopback interface.
 *
 * This is different from [GameServerTest], which runs everything over an in-process queue pair.
 * That proves the protocol, and this proves the *transport*: framing across a stream that might
 * split or join writes, a real accept loop, and real disconnects. A protocol that works in memory
 * and fails on a socket is what normally happens when you don't write this test.
 *
 * It uses real time instead of a test dispatcher, because sockets do.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class TcpIntegrationTest {

    private val catalog = StockParts.catalog

    /**
     * Runs a test against a real listener on a real port.
     *
     * Clients are launched into [net], on purpose *not* into the `runBlocking` scope, and it gets
     * cancelled in `finally`.
     *
     * A client collecting from a socket never finishes on its own, and `runBlocking` doesn't return
     * until every coroutine started in its scope has finished. Launching clients there hangs the
     * entire test run forever, and `withTimeout` doesn't rescue it, because the timeout cancels its
     * own block, not children started outside it. The first version of this file did exactly that
     * and wedged the build.
     */
    private fun harness(
        block: suspend (server: GameServer, net: CoroutineScope, port: Int) -> Unit,
    ) = runBlocking {
        val net = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        val server = GameServer.default(catalog)
        // Port 0, so the OS picks, and a stuck previous run can't wedge this one.
        val listener = TcpListener(0) { transport -> server.accept(transport, net) }
        val job = listener.start(net)
        try {
            withTimeout(30_000) { block(server, net, listener.boundPort) }
        } finally {
            listener.stop()
            job.cancel()
            net.cancel()
        }
    }

    /** Steps the server until [condition] holds, or fails. */
    private suspend fun pumpUntil(server: GameServer, what: String, condition: () -> Boolean) {
        repeat(600) {
            if (condition()) return
            server.stepOnce()
            delay(5)
        }
        if (!condition()) throw AssertionError("timed out waiting for: $what")
    }

    private suspend fun join(port: Int, scope: CoroutineScope, name: String): GameClient {
        val transport = TcpTransport.connect("127.0.0.1", port).getOrThrow()
        val client = GameClient(transport, name, catalog.contentHash, "install-$name")
        client.connect(scope)
        return client
    }

    @Test
    fun `a client connects over a socket and is given a craft`() = harness { server, net, port ->
        val client = join(port, net, "Pilot")
        pumpUntil(server, "the handshake to complete") { client.connected }

        assertTrue(client.connected)
        assertNotNull("should be given a craft", client.controlledVessel)
        assertEquals(1, server.playerCount)
    }

    @Test
    fun `two clients over sockets see each other's craft`() = harness { server, net, port ->
        val alice = join(port, net, "Alice")
        val bob = join(port, net, "Bob")

        pumpUntil(server, "both clients to see both craft") {
            alice.vessels.count { it.owner != World.WORLD_OWNER } == 2 && bob.vessels.count { it.owner != World.WORLD_OWNER } == 2
        }

        assertEquals(2, server.playerCount)
        assertNotNull(
            "Alice should have Bob's structure",
            alice.vessel(bob.controlledVessel!!)?.design,
        )
        assertNotNull(
            "Bob should have Alice's structure",
            bob.vessel(alice.controlledVessel!!)?.design,
        )
    }

    @Test
    fun `a craft flown by one client moves for the other`() = harness { server, net, port ->
        val alice = join(port, net, "Alice")
        val bob = join(port, net, "Bob")
        pumpUntil(server, "both clients to see both craft") {
            alice.vessels.count { it.owner != World.WORLD_OWNER } == 2 && bob.vessels.count { it.owner != World.WORLD_OWNER } == 2
        }

        val aliceVessel = alice.controlledVessel!!
        alice.send(Command.Stage(aliceVessel))
        alice.send(Command.SetThrottle(aliceVessel, 1.0))
        pumpUntil(server, "the throttle command to arrive over the wire") {
            server.world.vessel(VesselId(aliceVessel))?.control?.throttle == 1.0
        }

        val start = server.world.vessel(VesselId(aliceVessel))!!.body.position.length
        repeat(600) { server.stepOnce() }

        // Bob has to see the movement, not just the server.
        pumpUntil(server, "Bob to see Alice's craft climb") {
            val seen = bob.vessel(aliceVessel)?.latest ?: return@pumpUntil false
            seen.position.length > start + 50.0
        }
    }

    /**
     * The framing check.
     *
     * TCP is a stream, so a write isn't a read. A protocol that assumes otherwise passes every
     * in-process test and then loses its first craft spawn on a real network, where a
     * multi-kilobyte message gets split across segments.
     *
     * The stock rocket isn't big enough to prove this (protobuf encodes all thirteen parts in well
     * under a kilobyte), so this sends a craft that's oversized on purpose and can't fit in one
     * segment.
     */
    @Test
    fun `a large craft survives being split across segments`() = harness { server, net, port ->
        val client = join(port, net, "Pilot")
        pumpUntil(server, "the handshake to complete") { client.connected }

        val big = longStack(partCount = 120)
        val encoded = Codec.encode(
            com.rm.apogee.core.world.ClientMessage.CommandMessage(
                Command.SpawnCraft(big, "cape")
            )
        ).size
        assertTrue(
            "this craft should not fit in one TCP segment, was $encoded bytes",
            encoded > 4_000,
        )

        val before = server.world.vessels.size
        client.send(Command.SpawnCraft(big, "cape"))
        pumpUntil(server, "the oversized craft to arrive whole") {
            server.world.vessels.size > before
        }

        val spawned = server.world.vessels.last()
        assertEquals(
            "every part must survive the crossing",
            big.parts.size,
            spawned.partCount,
        )
        assertEquals(
            "and in the same order",
            big.parts.map { it.partId },
            spawned.design.parts.map { it.partId },
        )
    }

    /** A pod on top of a very long stack of tanks. It's only useful for its size. */
    private fun longStack(partCount: Int): com.rm.apogee.core.craft.CraftDesign {
        val builder = com.rm.apogee.core.craft.CraftBuilder(catalog)
        builder.placeRoot("pod-halo")
        var attachTo = builder.openNodes().first { it.partIndex == 0 && it.node.id == "bottom" }
        repeat(partCount - 1) {
            val added = builder.attach("tank-cask2", attachTo).firstOrNull() ?: return@repeat
            attachTo = builder.openNodes()
                .first { it.partIndex == added && it.node.id == "bottom" }
        }
        return builder.design
    }

    @Test
    fun `a mismatched catalogue is refused over a socket too`() = harness { server, net, port ->
        val transport = TcpTransport.connect("127.0.0.1", port).getOrThrow()
        val client = GameClient(transport, "Pilot", "wrong-hash", "install-pilot")
        client.connect(net)

        pumpUntil(server, "the rejection") { client.rejectionReason != null }
        assertTrue(client.rejectionReason!!.contains("catalogue", ignoreCase = true))
        assertEquals(0, server.playerCount)
    }

    @Test
    fun `a garbage frame length doesn't take the server down`() = harness { server, net, port ->
        // A well-behaved client first, so there's something to survive for.
        val good = join(port, net, "Good")
        pumpUntil(server, "the good client to connect") { good.connected }

        // Then a socket that sends a frame claiming to be enormous.
        val hostile = java.net.Socket("127.0.0.1", port)
        hostile.getOutputStream().apply {
            write(Channel.CONTROL.ordinal)
            // 512 MB: past the frame cap, and past what anyone should allocate.
            write(byteArrayOf(0x20, 0x00, 0x00, 0x00))
            flush()
        }
        delay(200)
        repeat(20) { server.stepOnce(); delay(5) }

        assertTrue("the good client should still be connected", good.connected)
        assertEquals("and still the only player", 1, server.playerCount)
        hostile.close()
    }

    @Test
    fun `a disconnecting client is dropped without disturbing the others`() =
        harness { server, net, port ->
            val alice = join(port, net, "Alice")
            val bob = join(port, net, "Bob")
            pumpUntil(server, "both to connect") { server.playerCount == 2 }

            bob.close()
            pumpUntil(server, "Bob to be noticed gone") { server.playerCount == 1 }

            // Alice keeps flying.
            val aliceVessel = alice.controlledVessel!!
            alice.send(Command.SetThrottle(aliceVessel, 0.5))
            pumpUntil(server, "Alice's command to still be obeyed") {
                server.world.vessel(VesselId(aliceVessel))?.control?.throttle == 0.5
            }
        }
}
