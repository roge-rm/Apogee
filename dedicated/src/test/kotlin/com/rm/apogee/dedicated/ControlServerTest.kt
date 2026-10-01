package com.rm.apogee.dedicated

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonPrimitive
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.BufferedReader
import java.net.StandardProtocolFamily
import java.net.UnixDomainSocketAddress
import java.nio.channels.Channels
import java.nio.channels.SocketChannel

/**
 * Drives the control channel over a real Unix domain socket, since the protocol is the contract
 * with the web admin.
 */
class ControlServerTest {

    @get:Rule
    val folder = TemporaryFolder()

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private lateinit var control: ControlServer
    private lateinit var socketFile: java.io.File

    private val broadcasts = mutableListOf<String>()
    private val kicked = mutableListOf<String>()
    private var stopRequested = false
    private var saveCount = 0

    private val handler = object : ControlServer.ControlHandler {
        override fun status(): Map<String, JsonElement> = mapOf(
            "running" to JsonPrimitive(true),
            "serverName" to JsonPrimitive("Test"),
            "players" to JsonPrimitive(2),
        )

        override fun players(): List<Map<String, JsonElement>> = listOf(
            mapOf("name" to JsonPrimitive("Alice"), "altitude" to JsonPrimitive(1234.5)),
            mapOf("name" to JsonPrimitive("Bob"), "altitude" to JsonPrimitive(0.0)),
        )

        override fun logLines(count: Int): List<String> =
            (1..count).map { "line $it" }.take(3)

        override fun saveNow(): Map<String, JsonElement> {
            saveCount++
            return mapOf("saved" to JsonPrimitive(true))
        }

        override fun broadcast(text: String) {
            broadcasts.add(text)
        }

        override fun kick(playerName: String): Boolean {
            if (playerName != "Alice") return false
            kicked.add(playerName)
            return true
        }

        override fun requestStop() {
            stopRequested = true
        }
    }

    @Before
    fun start() {
        socketFile = java.io.File(folder.newFolder("run"), "control.sock")
        control = ControlServer(socketFile, handler, LogRing(capacity = 10))
        control.start(scope)
        // start() binds before launching the accept loop, so the socket exists now.
    }

    @After
    fun stop() {
        control.stop()
        scope.cancel()
    }

    /** Sends one request line and returns the parsed reply. */
    private fun call(vararg args: String): JsonObject {
        val channel = SocketChannel.open(StandardProtocolFamily.UNIX)
        channel.connect(UnixDomainSocketAddress.of(socketFile.toPath()))
        channel.use {
            val writer = Channels.newWriter(it, Charsets.UTF_8)
            writer.write(args.joinToString("\t") + "\n")
            writer.flush()
            val reader = BufferedReader(Channels.newReader(it, Charsets.UTF_8))
            val line = reader.readLine() ?: error("no reply")
            return Json.parseToJsonElement(line) as JsonObject
        }
    }

    @Test
    fun `status answers with the server's own numbers`() {
        val reply = call("status")
        assertTrue(reply["ok"]!!.jsonPrimitive.boolean)
        assertEquals("Test", reply["serverName"]!!.jsonPrimitive.content)
        assertEquals(2, reply["players"]!!.jsonPrimitive.content.toInt())
    }

    @Test
    fun `players comes back as a list`() {
        val reply = call("players")
        val players = reply["players"]!!.jsonArray
        assertEquals(2, players.size)
        assertEquals(
            "Alice",
            (players[0] as JsonObject)["name"]!!.jsonPrimitive.content,
        )
    }

    @Test
    fun `log takes a count`() {
        val reply = call("log", "3")
        assertEquals(3, reply["lines"]!!.jsonArray.size)
    }

    @Test
    fun `save is carried out`() {
        assertEquals(0, saveCount)
        val reply = call("save")
        assertTrue(reply["ok"]!!.jsonPrimitive.boolean)
        assertEquals(1, saveCount)
    }

    @Test
    fun `chat carries text through to the server`() {
        call("chat", "restarting in five minutes")
        assertEquals(listOf("restarting in five minutes"), broadcasts)
    }

    @Test
    fun `an argument may contain a tab`() {
        // A tab in a chat line is escaped so it stays one argument.
        call("chat", "before\\tafter")
        assertEquals(listOf("before\tafter"), broadcasts)
    }

    @Test
    fun `kicking an unknown player is refused with a reason`() {
        val refused = call("kick", "Nobody")
        assertFalse(refused["ok"]!!.jsonPrimitive.boolean)
        assertTrue(refused["error"]!!.jsonPrimitive.content.contains("Nobody"))
        assertTrue(kicked.isEmpty())

        val accepted = call("kick", "Alice")
        assertTrue(accepted["ok"]!!.jsonPrimitive.boolean)
        assertEquals(listOf("Alice"), kicked)
    }

    @Test
    fun `stop is requested, not performed, by the channel`() {
        assertFalse(stopRequested)
        val reply = call("stop")
        assertTrue(reply["ok"]!!.jsonPrimitive.boolean)
        assertTrue(stopRequested)
    }

    @Test
    fun `an unknown command is refused without closing the channel`() {
        val refused = call("sudo-make-me-a-sandwich")
        assertFalse(refused["ok"]!!.jsonPrimitive.boolean)
        assertTrue(refused["error"]!!.jsonPrimitive.content.contains("unknown command"))

        // And the channel still works afterwards.
        assertTrue(call("status")["ok"]!!.jsonPrimitive.boolean)
    }

    @Test
    fun `an empty request is refused`() {
        assertFalse(call("")["ok"]!!.jsonPrimitive.boolean)
    }

    @Test
    fun `chat with nothing to say is refused`() {
        val reply = call("chat", "")
        assertFalse(reply["ok"]!!.jsonPrimitive.boolean)
        assertTrue(broadcasts.isEmpty())
    }
}
