package com.rm.apogee.server

import com.rm.apogee.core.craft.VesselId
import com.rm.apogee.core.part.StockParts
import com.rm.apogee.core.world.Command
import com.rm.apogee.core.world.World
import com.rm.apogee.net.GameClient
import com.rm.apogee.net.LoopbackTransportPair
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.yield
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
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
        // Defaults to one identity per name, which is what most tests want.
        // Tests about identity pass it explicitly.
        clientId: String = "install-$name",
        terrainGeneration: Int = com.rm.apogee.core.terrain.TerrainField.GENERATION,
        systemHash: String = com.rm.apogee.core.orbit.SolarSystem.DEFAULT_HASH,
    ): GameClient {
        val link = LoopbackTransportPair()
        server.accept(link.serverSide, scope)
        val client = GameClient(link.clientSide, name, catalogHash, clientId, terrainGeneration, systemHash)
        client.connect(scope)
        pumpUntil(server, "$name's handshake to resolve") {
            client.connected || client.rejectionReason != null
        }
        return client
    }

    /**
     * The bug this whole mechanism exists for. Two devices that have never had
     * a name set both arrive as the default "Pilot"; matching on the name
     * handed the second one the first one's rocket, and the two flew it
     * together without either realising.
     */
    /**
     * A crash that strips parts off: the client ends up seeing what the
     * world has - the craft it flies, smaller, and the pieces that came off
     * as craft of their own, with none left over that are gone.
     */
    @Test
    fun `a crash that breaks the craft up reaches the client`() = runTest {
        val world = World.default(catalog)
        val server = GameServer(world, ServerConfig())
        val client = joinClient(server, backgroundScope, "Pilot")
        pumpUntil(server, "a craft") { client.controlledVessel != null }
        val id = client.controlledVessel!!
        val rocket = world.vessel(VesselId(id))!!
        val partsBefore = rocket.design.parts.size
        val up = rocket.body.position.copy().normalizeInPlace()
        rocket.body.position.addScaledInPlace(up, 1.0)
        rocket.body.linearVelocity.addScaledInPlace(up, -60.0)
        rocket.wake()
        repeat(240) {
            server.stepOnce()
            repeat(SETTLE_YIELDS) { yield() }
        }
        // The last pieces may come off in the last ticks: let the client hear.
        pumpUntil(server, "the client to catch up") {
            client.vessel(id)?.design?.parts?.size == world.vessel(VesselId(id))?.design?.parts?.size &&
                world.vessels.map { it.id.raw }.toSet() == client.vessels.map { it.id }.toSet()
        }
        val survivor = world.vessel(VesselId(id))
        assertNotNull("the pod survives as the craft", survivor)
        assertTrue(survivor!!.design.parts.size < partsBefore)
        assertEquals(survivor.design.parts.size, client.vessel(id)?.design?.parts?.size)
        assertEquals(
            world.vessels.map { it.id.raw }.toSet(),
            client.vessels.map { it.id }.toSet(),
        )
        val kinds = client.partEvents.map { it.kind }.toSet()
        assertTrue("the client heard the blows: $kinds", com.rm.apogee.core.world.PartEventKind.IMPACT in kinds)
        assertTrue("and what they destroyed", com.rm.apogee.core.world.PartEventKind.DESTROYED in kinds)
        assertTrue(
            "and saw the pod was hurt",
            client.vessel(id)!!.latest!!.condition.isNotEmpty(),
        )
    }

    /** Pause and warp are the solo player's; the moment anyone else is on, time is everyone's. */
    @Test
    fun `pause and warp only while alone`() = runTest {
        val world = World.default(catalog)
        val server = GameServer(world, ServerConfig(allowWarp = true))
        val first = joinClient(server, backgroundScope, "Solo", clientId = "install-solo")
        pumpUntil(server, "a craft") { first.controlledVessel != null }
        first.send(Command.SetWarp(0.0))
        pumpUntil(server, "the pause") { server.requestedWarp == 0.0 }
        val paused = world.time
        repeat(10) { server.stepOnce(); repeat(SETTLE_YIELDS) { yield() } }
        assertEquals("paused, the clock stands still", paused, world.time, 0.0)

        first.send(Command.SetWarp(4.0))
        pumpUntil(server, "the warp") { server.requestedWarp == 4.0 }
        val before = world.time
        server.stepOnce()
        assertEquals("four steps a tick", before + 4.0 / 60.0, world.time, 1e-9)

        joinClient(server, backgroundScope, "Guest", clientId = "install-guest")
        repeat(3) { server.stepOnce(); repeat(SETTLE_YIELDS) { yield() } }
        assertFalse("not with someone else here", server.warpAllowed)
        assertEquals("and back to real time", 1.0, server.effectiveWarp(), 0.0)
        first.send(Command.SetWarp(4.0))
        repeat(3) { server.stepOnce(); repeat(SETTLE_YIELDS) { yield() } }
        assertEquals("a request now is refused", 1.0, server.requestedWarp, 0.0)
    }

    @Test
    fun `a dedicated server never pauses`() = runTest {
        val server = GameServer(World.default(catalog), ServerConfig())
        val client = joinClient(server, backgroundScope, "Solo")
        client.send(Command.SetWarp(0.0))
        repeat(5) { server.stepOnce(); repeat(SETTLE_YIELDS) { yield() } }
        assertEquals(1.0, server.effectiveWarp(), 0.0)
    }

    /** A player can take their own craft away, and nobody else's. */
    @Test
    fun `players remove their own craft only`() = runTest {
        val world = World.default(catalog)
        val server = GameServer(world, ServerConfig())
        val alice = joinClient(server, backgroundScope, "Alice", clientId = "install-alice")
        val bob = joinClient(server, backgroundScope, "Bob", clientId = "install-bob")
        pumpUntil(server, "craft for both") { alice.controlledVessel != null && bob.controlledVessel != null }
        val alices = alice.controlledVessel!!
        bob.send(Command.RemoveVessel(alices))
        repeat(5) { server.stepOnce(); repeat(SETTLE_YIELDS) { yield() } }
        assertNotNull("Bob cannot remove Alice's craft", world.vessel(VesselId(alices)))
        alice.send(Command.RemoveVessel(alices))
        pumpUntil(server, "Alice's craft to go") { world.vessel(VesselId(alices)) == null }
        pumpUntil(server, "Alice to be flying nothing") { alice.controlledVessel == null }
    }

    @Test
    fun `two players sharing a name get a craft each`() = runTest {
        val server = GameServer(World.default(catalog), ServerConfig())
        val first = joinClient(server, backgroundScope, "Pilot", clientId = "install-one")
        val second = joinClient(server, backgroundScope, "Pilot", clientId = "install-two")

        pumpUntil(server, "both to be given craft") {
            first.controlledVessel != null && second.controlledVessel != null
        }
        assertNotEquals(
            "both Pilots were handed the same craft",
            first.controlledVessel,
            second.controlledVessel,
        )
    }

    /** And the converse: the same install coming back gets its craft again. */
    @Test
    fun `the same install is given its craft back`() = runTest {
        val server = GameServer(World.default(catalog), ServerConfig())
        val first = joinClient(server, backgroundScope, "Pilot", clientId = "install-one")
        pumpUntil(server, "the first craft") { first.controlledVessel != null }
        val original = first.controlledVessel

        // Same device, different name this time: the label is cosmetic.
        val again = joinClient(server, backgroundScope, "Commander", clientId = "install-one")
        pumpUntil(server, "the craft to come back") { again.controlledVessel != null }

        assertEquals("a returning install lost its craft", original, again.controlledVessel)
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

    /**
     * Same parts, different ground: an older build joining a 0.3.0 server
     * would drive over hills the server says are not there.
     */
    @Test
    fun `a client on different terrain is refused`() = runTest {
        val server = GameServer.default(catalog)
        val client = joinClient(
            server, backgroundScope, "Pilot",
            terrainGeneration = com.rm.apogee.core.terrain.TerrainField.GENERATION - 1,
        )

        assertFalse("should not be connected", client.connected)
        assertTrue(
            "reason should name the terrain: ${client.rejectionReason}",
            client.rejectionReason?.contains("terrain", ignoreCase = true) == true,
        )
        assertEquals(0, server.playerCount)
    }

    /** Same parts and ground, different worlds: the planets would not be where the server has them. */
    @Test
    fun `a client with a different solar system is refused`() = runTest {
        val server = GameServer.default(catalog)
        val client = joinClient(server, backgroundScope, "Pilot", systemHash = "0123456789abcdef")

        assertFalse("should not be connected", client.connected)
        assertTrue(
            "reason should name the system: ${client.rejectionReason}",
            client.rejectionReason?.contains("solar system", ignoreCase = true) == true,
        )
        assertEquals(0, server.playerCount)
    }

    /**
     * A tree felled by one player is gone for everyone: for a player already
     * there, and for one who joins afterwards.
     */
    @Test
    fun `a felled tree is gone for every player`() = runTest {
        val server = GameServer.default(catalog)
        val there = joinClient(server, backgroundScope, "Alice")
        server.world.fell(123_456L)
        pumpUntil(server, "the fall to reach Alice") { 123_456L in there.felledScatter }

        val late = joinClient(server, backgroundScope, "Bob")
        pumpUntil(server, "the fall to reach Bob on joining") { 123_456L in late.felledScatter }
        assertTrue(late.felledRevision > 0)
    }

    /**
     * Moving parts are replicated: a player watching another's aircraft sees
     * its control surfaces where the server has them - the pilot's elevons,
     * not a guess at them.
     */
    @Test
    fun `another player sees the same control surface deflection`() = runTest {
        val server = GameServer.default(catalog)
        val watcher = joinClient(server, backgroundScope, "Watcher")
        val plane = server.world.spawnOnSurface(
            com.rm.apogee.core.craft.StockCraft.aeroplane(catalog),
            com.rm.apogee.core.world.World.launchSites.first(),
        )
        plane.control.pitch = 0.6
        plane.control.roll = -0.3
        val values = com.rm.apogee.core.world.VesselPose.Values()
        pumpUntil(server, "the watcher to receive the plane's pose") {
            val seen = watcher.vessel(plane.id.raw)?.latest ?: return@pumpUntil false
            val defs = seen.let { plane.defs }
            com.rm.apogee.core.world.VesselPose.decode(defs, seen.pose, values) &&
                values.deflection.any { kotlin.math.abs(it) > 0.1 }
        }
        var compared = 0
        for (i in plane.defs.indices) {
            if (plane.defs[i].module<com.rm.apogee.core.part.AeroSurface>()?.controllable != true) continue
            assertEquals(
                "surface $i",
                plane.surfaceDeflection[i], values.deflection[i],
                com.rm.apogee.core.world.VesselPose.RESOLUTION * 2,
            )
            compared++
        }
        assertTrue("expected surfaces to compare, got $compared", compared >= 3)
    }

    /**
     * Free Flight: joining a fresh flight clears away the craft flown last
     * time and starts on a new one - and leaves alone anything else the
     * player owns, which is a base left on purpose.
     */
    @Test
    fun `a fresh flight replaces the craft flown last time, and only that one`() = runTest {
        val world = com.rm.apogee.core.world.World.default(catalog)
        val first = GameServer(world)
        val client = joinClient(first, backgroundScope, "Pilot")
        pumpUntil(first, "the first flight's craft") { client.controlledVessel != null }
        val flown = client.controlledVessel!!
        // A base the player also owns, parked elsewhere.
        val base = world.spawnOnSurface(
            com.rm.apogee.core.craft.StockCraft.rover(catalog),
            com.rm.apogee.core.world.World.launchSites.first(), pad = 3,
        ).also { it.owner = client.clientId }

        val second = GameServer(world, ServerConfig(freshFlight = true))
        val again = joinClient(second, backgroundScope, "Pilot", clientId = client.clientId)
        pumpUntil(second, "the second flight's craft") { again.controlledVessel != null }

        assertNotEquals("a new craft, not the old one", flown, again.controlledVessel)
        assertNull("the last flight's craft is gone", world.vessel(VesselId(flown)))
        assertNotNull("the parked base is not", world.vessel(base.id))
    }

    @Test
    fun `two clients in one world see each other's craft`() = runTest {
        val server = GameServer.default(catalog)
        val alice = joinClient(server, backgroundScope, "Alice")
        val bob = joinClient(server, backgroundScope, "Bob")

        pumpUntil(server, "both clients to see both craft") {
            alice.vessels.count { it.owner != World.WORLD_OWNER } == 2 && bob.vessels.count { it.owner != World.WORLD_OWNER } == 2
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
            alice.vessels.count { it.owner != World.WORLD_OWNER } == 2 && bob.vessels.count { it.owner != World.WORLD_OWNER } == 2
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

    /** Out on EVA into another player's empty seat: aboard, and along for the ride. */
    @Test
    fun `a player who boards another's craft rides as a passenger`() = runTest {
        val world = World.default(catalog)
        val server = GameServer(world, ServerConfig())
        val alice = joinClient(server, backgroundScope, "Alice", clientId = "install-alice")
        val bob = joinClient(server, backgroundScope, "Bob", clientId = "install-bob")
        pumpUntil(server, "craft for both") { alice.controlledVessel != null && bob.controlledVessel != null }
        val alices = world.vessel(VesselId(alice.controlledVessel!!))!!
        val bobs = world.vessel(VesselId(bob.controlledVessel!!))!!
        // Alice steps out, leaving her pod empty.
        alice.send(Command.Eva(alices.id.raw, alices.crew.first { it.isNotEmpty() }.first()))
        pumpUntil(server, "Alice on EVA") { alice.controlledVessel != alices.id.raw }
        // Bob steps out, and up beside Alice's pod.
        val bobCrew = bobs.crew.first { it.isNotEmpty() }.first()
        bob.send(Command.Eva(bobs.id.raw, bobCrew))
        pumpUntil(server, "Bob on EVA") { bob.controlledVessel != bobs.id.raw }
        val suit = world.vessel(VesselId(bob.controlledVessel!!))!!
        val pod = alices.defs.indexOfFirst { it.id == "pod-halo" }
        suit.body.position.setTo(alices.partPositionWorld(pod)).addScaledInPlace(alices.body.position.normalized().cross(com.rm.apogee.core.math.Vec3.unitY()).normalizeInPlace(), 1.2)
        suit.body.linearVelocity.setTo(alices.body.linearVelocity)
        bob.send(Command.Board(suit.id.raw, alices.id.raw))
        pumpUntil(server, "Bob aboard Alice's craft") { bob.controlledVessel == alices.id.raw }
        assertEquals(alices.id.raw, world.crew.getValue(bobCrew).vessel)
        bob.send(Command.SetThrottle(alices.id.raw, 1.0))
        repeat(5) { server.stepOnce(); repeat(SETTLE_YIELDS) { yield() } }
        assertEquals("a passenger worked the throttle", 0.0, alices.control.throttle, 0.0)
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

    /**
     * The pilot's HUD shows what is left in the tanks, and its replica burns
     * from it - so the server says, rather than the client assuming full.
     */
    @Test
    fun `the pilot is told what is left in the tanks`() = runTest {
        val server = GameServer.default(catalog)
        val client = joinClient(server, backgroundScope, "Pilot")
        pumpUntil(server, "the client to know its craft") {
            client.controlledVessel?.let { client.vessel(it)?.fuel } != null
        }
        val id = client.controlledVessel!!
        val full = client.vessel(id)!!.fuel!!.sum()

        client.send(Command.Stage(id))
        client.send(Command.SetThrottle(id, 1.0))
        pumpUntil(server, "the burn to show in the tanks", maxTicks = 600) {
            (client.vessel(id)?.fuel?.sum() ?: full) < full * 0.97f
        }
        val server0 = server.world.vessel(VesselId(id))!!
        assertEquals(
            "the client's figures are the server's",
            server0.flatResources().sum().toDouble(), client.vessel(id)!!.fuel!!.sum().toDouble(), full * 0.02,
        )
    }

    /** The weather is computed on every device alike: all it needs is the config, sent in the welcome. */
    @Test
    fun `a client is told what the weather is made of`() = runTest {
        val wild = com.rm.apogee.core.weather.WeatherConfig(intensity = com.rm.apogee.core.weather.WeatherIntensity.WILD)
        val server = GameServer(World.default(catalog), ServerConfig(weatherIntensity = com.rm.apogee.core.weather.WeatherIntensity.WILD))
        val client = joinClient(server, backgroundScope, "Pilot")
        assertEquals(wild, client.weather)
        assertEquals(wild, server.world.weatherConfig)
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

    /**
     * A craft left on the pad from before a restart still has the pad. A new
     * player's starter used to go on pad 0 by a counter that restarted with
     * the server, straight into it.
     */
    @Test
    fun `a new player's craft goes beside one left on the pad, not into it`() = runTest {
        val server = GameServer.default(catalog)
        val parked = server.world.spawnOnSurface(
            com.rm.apogee.core.craft.StockCraft.starterRocket(catalog),
            com.rm.apogee.core.world.World.launchSites.first(),
        )
        val client = joinClient(server, backgroundScope, "Newcomer")
        pumpUntil(server, "the newcomer to be flying something") { client.controlledVessel != null }

        val mine = server.world.vessel(VesselId(client.controlledVessel!!))!!
        val separation = mine.body.position.distanceTo(parked.body.position)
        assertTrue("clear of the parked craft, were ${separation}m apart", separation > 20.0)
        assertTrue("and close by, were ${separation}m apart", separation < 100.0)
    }

    /**
     * The point of a persistent world, from the seat: log off in orbit, come
     * back, still be in orbit.
     */
    @Test
    fun `a returning player gets their own craft back`() = runTest {
        val server = GameServer.default(catalog)
        val alice = joinClient(server, backgroundScope, "Alice")
        pumpUntil(server, "Alice to be flying something") { alice.controlledVessel != null }

        val hers = VesselId(alice.controlledVessel!!)
        // Fly it somewhere distinctive, then leave.
        server.world.apply(Command.Stage(hers.raw))
        server.world.apply(Command.SetThrottle(hers.raw, 1.0))
        repeat(600) { server.stepOnce() }
        val altitude = server.world.vessel(hers)!!.body.position.length
        alice.close()
        pumpUntil(server, "Alice to be noticed gone") { server.playerCount == 0 }

        val backAgain = joinClient(server, backgroundScope, "Alice")
        pumpUntil(server, "Alice to be flying again") { backAgain.controlledVessel != null }

        assertEquals(
            "she should be given the same craft, not a fresh one on the pad",
            hers.raw,
            backAgain.controlledVessel,
        )
        // Near where she left it, not exactly: the world does not pause because
        // nobody is watching, so a craft under power keeps climbing while its
        // pilot reconnects. What must not happen is finding it back on the pad.
        val now = server.world.vessel(hers)!!.body.position.length
        assertEquals("she should rejoin near where she left off", altitude, now, 100.0)
        assertTrue(
            "and certainly not back on the launch pad",
            now > server.world.system.body("terra").radius + 100.0,
        )
        assertEquals("with no second craft spawned", 1, server.world.vessels.count { it.owner != World.WORLD_OWNER })
    }

    @Test
    fun `a different player gets their own craft`() = runTest {
        val server = GameServer.default(catalog)
        val alice = joinClient(server, backgroundScope, "Alice")
        val bob = joinClient(server, backgroundScope, "Bob")
        pumpUntil(server, "both to be flying") {
            alice.controlledVessel != null && bob.controlledVessel != null
        }

        assertTrue(
            "two players must not be handed the same craft",
            alice.controlledVessel != bob.controlledVessel,
        )
        assertEquals(2, server.world.vessels.count { it.owner != World.WORLD_OWNER })
    }

    @Test
    fun `a full server refuses with a reason`() = runTest {
        val server = GameServer(
            world = com.rm.apogee.core.world.World.default(catalog),
            config = ServerConfig(maxPlayers = 1),
        )
        val alice = joinClient(server, backgroundScope, "Alice")
        pumpUntil(server, "Alice to connect") { alice.connected }

        val bob = joinClient(server, backgroundScope, "Bob")

        assertFalse("Bob should not get in", bob.connected)
        assertNotNull("and should be told why", bob.rejectionReason)
        assertTrue(
            "the reason should say full, not something vague: ${bob.rejectionReason}",
            bob.rejectionReason!!.contains("full", ignoreCase = true),
        )
        assertEquals(1, server.playerCount)
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

    /**
     * Two players' craft docked: both are in the one craft, both are asked
     * who flies it, and what they choose is what the server lets through.
     * Undocked, each is back in their own.
     */
    @Test
    fun `two players dock, choose who flies, and undock back into their own craft`() = runTest {
        val server = GameServer.default(catalog)
        val alice = joinClient(server, backgroundScope, "Alice")
        val bob = joinClient(server, backgroundScope, "Bob")
        pumpUntil(server, "both flying") { alice.controlledVessel != null && bob.controlledVessel != null }
        val site = com.rm.apogee.core.world.World.launchSites.first().id
        val tug = com.rm.apogee.core.craft.StockCraft.portTug(catalog)
        val aliceOld = alice.controlledVessel; val bobOld = bob.controlledVessel
        alice.send(Command.SpawnCraft(tug, site))
        bob.send(Command.SpawnCraft(tug, site))
        pumpUntil(server, "both in tugs") {
            alice.controlledVessel != aliceOld && bob.controlledVessel != bobOld &&
                alice.controlledVessel != null && bob.controlledVessel != null
        }
        val a = server.world.vessel(VesselId(alice.controlledVessel!!))!!
        val b = server.world.vessel(VesselId(bob.controlledVessel!!))!!
        val ring = a.defs.indices.first { a.defs[it].id == "dock-port" }
        val whole = server.world.dockPorts(a, ring, b, ring)!!
        pumpUntil(server, "both in the docked craft") {
            alice.controlledVessel == whole.id.raw && bob.controlledVessel == whole.id.raw
        }
        pumpUntil(server, "both asked who flies") { alice.dockedWith != null && bob.dockedWith != null }
        assertEquals("Bob", alice.dockedWith!!.other)
        assertEquals("either, until they say", "", alice.dockedWith!!.pilot)

        // Bob hands it to Alice: his throttle is refused, hers goes through.
        bob.send(Command.SetDockPilot(whole.id.raw, alice.clientId))
        pumpUntil(server, "the choice to reach Alice") { alice.dockedWith?.pilot == alice.clientId }
        bob.send(Command.SetThrottle(whole.id.raw, 0.9))
        repeat(20) { server.stepOnce(); repeat(SETTLE_YIELDS) { yield() } }
        assertEquals("Bob is a passenger", 0.0, whole.control.throttle, 0.0)
        alice.send(Command.SetThrottle(whole.id.raw, 0.4))
        pumpUntil(server, "Alice's throttle") { whole.control.throttle == 0.4 }

        // Apart again: each back in their own.
        val joint = whole.defs.indices.first { whole.design.parts[it].dockedFrom != null }
        alice.send(Command.Undock(whole.id.raw, joint))
        pumpUntil(server, "each back in their own craft") {
            alice.controlledVessel != null && bob.controlledVessel != null && alice.controlledVessel != bob.controlledVessel
        }
        assertEquals(alice.clientId, server.world.vessel(VesselId(alice.controlledVessel!!))!!.owner)
        assertEquals(bob.clientId, server.world.vessel(VesselId(bob.controlledVessel!!))!!.owner)
    }
}
