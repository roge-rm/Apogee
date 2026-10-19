package com.rm.apogee

import androidx.core.content.FileProvider
import com.rm.apogee.audio.AudioEngine
import com.rm.apogee.audio.Buses
import com.rm.apogee.audio.SharedParams
import com.rm.apogee.core.FileFolder
import com.rm.apogee.core.career.Program
import com.rm.apogee.core.craft.VesselId
import com.rm.apogee.server.GameServer
import com.rm.apogee.core.craft.CraftStore
import com.rm.apogee.core.craft.StockCraft
import com.rm.apogee.core.part.ResourceType
import com.rm.apogee.core.part.StockParts
import com.rm.apogee.core.world.*
import com.rm.apogee.net.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.first
import java.io.File
import java.net.ServerSocket

/** Runs against the installed Android target, including its optimised serializers and JNI calls. */
class OptimizedAppChecks(private val context: android.content.Context) {
    private val directoryDelegate = lazy { File(context.cacheDir, "shared/r8-validation-${System.nanoTime()}").apply { mkdirs() } }
    private val directory by directoryDelegate
    private val catalog = StockParts.catalog

    fun perfOnly() { check(context.packageName == "com.rm.apogee.perf") }
    fun cleanup() { if (directoryDelegate.isInitialized()) directory.deleteRecursively() }

    fun readsAnUnoptimizedWorldFixture() {
        val file = File(context.getExternalFilesDir(null), "r8-input-world.json")
        val saved = WorldStore.decode(file.readText())
        val world = World.default(catalog)
        val problems = world.restore(saved)
        assertTrue(problems.toString(), problems.isEmpty())
        val vessel = world.vessels.single()
        assertEquals(StockCraft.sparkler(catalog).name, vessel.name)
        assertTrue(vessel.amountOf(ResourceType.SOLID_FUEL) > 0.0)
    }

    fun stagedSolidsKeepFuelAndStateAcrossSaveAndBackupRecovery() {
        val world = World.default(catalog)
        val vessel = world.spawnOnSurface(StockCraft.sparkler(catalog), World.launchSites.first())
        vessel.owner = "r8-player"
        val before = vessel.amountOf(ResourceType.SOLID_FUEL)
        world.apply(Command.Stage(vessel.id.raw))
        repeat(120) { world.step(1.0 / 60.0) }
        assertTrue("solid motor lit", vessel.burning)
        val fuel = vessel.amountOf(ResourceType.SOLID_FUEL)
        assertTrue("solid fuel consumed", fuel < before)
        val store = WorldStore(FileFolder(directory), "world.json")
        store.save(world.save()).getOrThrow()
        val exported = File(directory, "world.json").readText()
        File(context.getExternalFilesDir(null), "r8-output-world.json").writeText(exported)
        val restored = World.default(catalog)
        val problems = restored.restore(WorldStore.decode(exported))
        assertTrue(problems.toString(), problems.isEmpty())
        assertEquals(restored.time, store.load().getOrThrow().universeTime, 0.0)
        val back = restored.vessels.first()
        assertEquals("r8-player", back.owner)
        assertEquals(vessel.currentStage, back.currentStage)
        assertEquals(fuel, back.amountOf(ResourceType.SOLID_FUEL), 0.0)
        assertTrue("burning survives load", back.burning)
        assertTrue(vessel.body.position.approxEquals(back.body.position, 1e-8))
        val savedAt = world.time
        repeat(30) { world.step(1.0 / 60.0) }
        store.save(world.save()).getOrThrow()
        File(directory, "world.json").writeText("broken")
        val fallback = store.loadWithFallback()!!
        assertNotNull("backup warning", fallback.second)
        assertEquals(savedAt, fallback.first.universeTime, 0.0)
    }

    fun careerUnlocksAndLegacyBoosterGrantSurviveSave() {
        val world = World.default(catalog).also { it.program = Program() }
        val program = world.program!!
        val initial = program.careerOf("r8-player").insight
        assertNull(world.unlock("r8-player", "tanks"))
        assertTrue(program.careerOf("r8-player").has("tanks"))
        assertTrue(program.careerOf("r8-player").insight < initial)
        val store = WorldStore(FileFolder(directory), "career.json")
        store.save(world.save()).getOrThrow()
        File(context.getExternalFilesDir(null), "r8-output-career.json").writeText(File(directory, "career.json").readText())
        val restored = World.default(catalog)
        assertTrue(restored.restore(store.load().getOrThrow()).isEmpty())
        assertTrue(restored.program!!.careerOf("r8-player").has("tanks"))
        assertNull(restored.program!!.refusal("r8-player", StockCraft.sounder(catalog), "cape", catalog))
        val state = program.careerOf("r8-player").copy(nodes = listOf("pad-2"))
        val migrated = Program().also { it.restore(listOf(state), emptyList()) }
        assertTrue(migrated.careerOf("r8-player").has("boosters"))
    }

    fun sharedCraftImportExportAndMalformedFiles() {
        val store = CraftStore(FileFolder(directory))
        for (design in listOf(StockCraft.sounder(catalog), StockCraft.sparkler(catalog), StockCraft.wayfarer(catalog))) {
            val encoded = store.encode(design)
            File(context.getExternalFilesDir(null), "r8-output-craft.json").writeText(encoded)
            val imported = store.decode(encoded, catalog).getOrThrow()
            assertEquals(design.name, imported.name)
            assertEquals(design.parts, imported.parts)
            assertEquals(design.stages, imported.stages)
            assertTrue(imported.validate(catalog).toString(), imported.validate(catalog).isEmpty())
            val saved = store.save(imported).getOrThrow()
            assertEquals(imported.parts, store.load(saved.fileName).getOrThrow().parts)
            val uri = FileProvider.getUriForFile(context, "${context.packageName}.files", File(directory, saved.fileName))
            val shared = context.contentResolver.openInputStream(uri)!!.bufferedReader().use { it.readText() }
            assertEquals(imported.parts, store.decode(shared, catalog).getOrThrow().parts)
        }
        assertEquals(3, store.list().size)
        assertTrue(store.decode("broken", catalog).isFailure)
    }

    fun protobufCommandsAndRealTcpHandshake() = runBlocking {
        val messages = listOf(
            ClientMessage.Hello(Protocol.VERSION, "test-catalog", "Pilot", "r8-player"),
            ClientMessage.CommandMessage(Command.Stage(7)),
            ClientMessage.CommandMessage(Command.Unlock("tanks")),
            ClientMessage.CommandMessage(Command.SetThrottle(7, 0.75)),
        )
        File(context.getExternalFilesDir(null), "r8-output-client.bin").writeBytes(Codec.encode(messages.first()))
        File(context.getExternalFilesDir(null), "r8-output-command.bin").writeBytes(Codec.encode(messages.last()))
        for (message in messages) assertEquals(message, Codec.decodeClientMessage(Codec.encode(message)))
        val welcome = ServerMessage.Welcome(Protocol.VERSION, "test-catalog", "R8 local server",
            weather = com.rm.apogee.core.weather.WeatherConfig(intensity = com.rm.apogee.core.weather.WeatherIntensity.EXTREME))
        File(context.getExternalFilesDir(null), "r8-output-server.bin").writeBytes(Codec.encode(welcome))
        assertEquals(welcome, Codec.decodeServerMessage(Codec.encode(welcome)))
        withTimeout(15_000) {
            ServerSocket(0).use { listener ->
                val accepted = async(Dispatchers.IO) {
                    val peer = TcpTransport.wrap(listener.accept())
                    var received: ClientMessage? = null
                    try {
                        peer.incoming.collect { packet ->
                            received = Codec.decodeClientMessage(packet.bytes)
                            peer.send(Packet(Channel.CONTROL, Codec.encode(welcome)))
                            peer.close()
                        }
                    } finally { peer.close() }
                    received
                }
                val client = TcpTransport.connect("127.0.0.1", listener.localPort).getOrThrow()
                try {
                    client.send(Packet(Channel.CONTROL, Codec.encode(messages.first())))
                    assertEquals(welcome, Codec.decodeServerMessage(client.incoming.first().bytes))
                    assertEquals(messages.first(), accepted.await())
                } finally { client.close() }
            }
        }
    }

    fun twoGameClientsExchangeStructuresAndCommands() = runBlocking {
        val net = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        val server = GameServer.default(catalog)
        val listener = TcpListener(0) { server.accept(it, net) }
        val listening = listener.start(net)
        val clients = ArrayList<GameClient>()
        suspend fun pump(condition: () -> Boolean) {
            withTimeout(30_000) {
                while (!condition()) { server.stepOnce(); delay(5) }
            }
        }
        try {
            for (name in listOf("Alice", "Bob")) {
                val transport = TcpTransport.connect("127.0.0.1", listener.boundPort).getOrThrow()
                val client = GameClient(transport, name, catalog.contentHash, "r8-$name")
                clients.add(client)
                client.connect(net)
            }
            val alice = clients[0]; val bob = clients[1]
            pump {
                alice.vessels.count { it.owner != World.WORLD_OWNER } == 2 &&
                    bob.vessels.count { it.owner != World.WORLD_OWNER } == 2
            }
            assertEquals(2, server.playerCount)
            val vessel = alice.controlledVessel!!
            assertNotNull(bob.vessel(vessel)?.design)
            alice.send(Command.Stage(vessel))
            alice.send(Command.SetThrottle(vessel, 1.0))
            pump { server.world.vessel(VesselId(vessel))?.control?.throttle == 1.0 }
            assertTrue(server.world.vessel(VesselId(vessel))!!.burning)
        } finally {
            clients.forEach { it.close() }
            listener.stop(); listening.cancel(); net.cancel()
        }
    }

    fun nativeAudioStartsPublishesAndStops() {
        AudioEngine.start(16)
        try {
            assertTrue("native audio output opened", AudioEngine.running)
            AudioEngine.busGains(FloatArray(Buses.COUNT))
            AudioEngine.room(0.2f)
            AudioEngine.scene(0, IntArray(0), IntArray(0), IntArray(0), FloatArray(0))
            AudioEngine.event(0, 0, 123, 0f, FloatArray(SharedParams.COUNT))
            assertTrue(AudioEngine.activeVoices >= 0)
            AudioEngine.pause(true)
            AudioEngine.pause(false)
        } finally { AudioEngine.stop() }
        assertFalse(AudioEngine.running)
    }
    private fun assertTrue(value: Boolean) { check(value) }
    private fun assertTrue(message: String, value: Boolean) { check(value) { message } }
    private fun assertFalse(value: Boolean) { check(!value) }
    private fun assertNull(value: Any?) { check(value == null) { "$value" } }
    private fun assertNotNull(value: Any?) { check(value != null) }
    private fun assertNotNull(message: String, value: Any?) { check(value != null) { message } }
    private fun assertEquals(expected: Any?, actual: Any?) { check(expected == actual) { "Expected $expected, got $actual" } }
    private fun assertEquals(expected: Double, actual: Double, delta: Double) {
        check(kotlin.math.abs(expected - actual) <= delta) { "Expected $expected, got $actual" }
    }

}
