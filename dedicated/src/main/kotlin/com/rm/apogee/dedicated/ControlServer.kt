package com.rm.apogee.dedicated

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonObject
import java.io.BufferedReader
import java.io.IOException
import java.net.StandardProtocolFamily
import java.net.UnixDomainSocketAddress
import java.nio.channels.Channels
import java.nio.channels.ServerSocketChannel
import java.nio.channels.SocketChannel
import java.nio.file.Files

/**
 * The admin control channel: a Unix domain socket that takes one line in and gives one line of JSON
 * out.
 *
 * I chose a Unix socket instead of an HTTP port on purpose. The admin channel can stop the server,
 * kick players and rewrite the world. Putting it on the network would mean it needs logging in, and
 * the only thing between an open port and a stranger would be a password someone left at the
 * default. A socket in a directory has filesystem permissions instead, and the web admin reaches it
 * by sharing a volume instead of by being trusted.
 *
 * Requests are tab-separated so a person can drive it with `socat` while debugging. Replies are
 * JSON because a program reads them.
 */
class ControlServer(
    private val socketFile: java.io.File,
    private val handler: ControlHandler,
    private val log: LogRing,
) {
    /** What the control channel is allowed to ask the server to do. */
    interface ControlHandler {
        fun status(): Map<String, JsonElement>
        fun players(): List<Map<String, JsonElement>>
        fun logLines(count: Int): List<String>
        fun saveNow(): Map<String, JsonElement>
        fun broadcast(text: String)
        fun kick(playerName: String): Boolean
        fun requestStop()
    }

    private var channel: ServerSocketChannel? = null

    fun start(scope: CoroutineScope): Job {
        // A socket file left behind by a crash would make bind fail, and nothing else can properly
        // own this path.
        Files.deleteIfExists(socketFile.toPath())
        socketFile.parentFile?.mkdirs()

        val address = UnixDomainSocketAddress.of(socketFile.toPath())
        val server = ServerSocketChannel.open(StandardProtocolFamily.UNIX)
        server.bind(address)
        channel = server
        log.info("Control socket listening on ${socketFile.absolutePath}")

        return scope.launch(Dispatchers.IO) {
            try {
                while (isActive) {
                    val client = server.accept()
                    // Serve inline. Requests are a single short line and the admin page makes them
                    // one at a time, so a thread per connection would be machinery for no traffic.
                    runCatching { serve(client) }
                    runCatching { client.close() }
                }
            } catch (_: IOException) {
                // Closed while blocked in accept(), which is how stop() works.
            }
        }
    }

    fun stop() {
        runCatching { channel?.close() }
        channel = null
        runCatching { Files.deleteIfExists(socketFile.toPath()) }
    }

    private fun serve(client: SocketChannel) {
        val reader = BufferedReader(Channels.newReader(client, Charsets.UTF_8))
        val writer = Channels.newWriter(client, Charsets.UTF_8)

        val line = reader.readLine() ?: return
        val reply = runCatching { dispatch(line) }.getOrElse { failure ->
            // A bad request mustn't take the channel down. The admin page will show the message and
            // carry on.
            error(failure.message ?: failure::class.simpleName ?: "unknown error")
        }
        writer.write(reply)
        writer.write("\n")
        writer.flush()
    }

    private fun dispatch(line: String): String {
        val parts = line.split('\t').map { unescape(it) }
        return when (val command = parts.firstOrNull()?.lowercase()) {
            "status" -> ok(handler.status())

            "players" -> JSON.encodeToString(
                JsonElement.serializer(),
                buildJsonObject {
                    put("ok", JsonPrimitive(true))
                    put(
                        "players",
                        kotlinx.serialization.json.JsonArray(
                            handler.players().map { player ->
                                buildJsonObject { player.forEach { (k, v) -> put(k, v) } }
                            }
                        ),
                    )
                },
            )

            "log" -> {
                val count = parts.getOrNull(1)?.toIntOrNull() ?: 100
                JSON.encodeToString(
                    JsonElement.serializer(),
                    buildJsonObject {
                        put("ok", JsonPrimitive(true))
                        put(
                            "lines",
                            kotlinx.serialization.json.JsonArray(
                                handler.logLines(count).map { JsonPrimitive(it) }
                            ),
                        )
                    },
                )
            }

            "save" -> ok(handler.saveNow())

            "chat" -> {
                val text = parts.getOrNull(1).orEmpty()
                if (text.isBlank()) error("chat needs something to say")
                else {
                    handler.broadcast(text)
                    ok(mapOf("sent" to JsonPrimitive(true)))
                }
            }

            "kick" -> {
                val who = parts.getOrNull(1).orEmpty()
                when {
                    who.isBlank() -> error("kick needs a player name")
                    handler.kick(who) -> ok(mapOf("kicked" to JsonPrimitive(who)))
                    else -> error("no player called \"$who\"")
                }
            }

            "stop" -> {
                handler.requestStop()
                ok(mapOf("stopping" to JsonPrimitive(true)))
            }

            null, "" -> error("empty request")
            else -> error("unknown command \"$command\"")
        }
    }

    private fun ok(fields: Map<String, JsonElement>): String = JSON.encodeToString(
        JsonElement.serializer(),
        buildJsonObject {
            put("ok", JsonPrimitive(true))
            fields.forEach { (key, value) -> put(key, value) }
        },
    )

    private fun error(message: String): String = JSON.encodeToString(
        JsonElement.serializer(),
        buildJsonObject {
            put("ok", JsonPrimitive(false))
            put("error", JsonPrimitive(message))
        },
    )

    /** Undoes the escaping the client applies, so arguments can contain tabs. */
    private fun unescape(value: String): String = buildString(value.length) {
        var index = 0
        while (index < value.length) {
            val character = value[index]
            if (character == '\\' && index + 1 < value.length) {
                index++
                when (value[index]) {
                    't' -> append('\t')
                    'n' -> append('\n')
                    '\\' -> append('\\')
                    else -> append(value[index])
                }
            } else {
                append(character)
            }
            index++
        }
    }

    private companion object {
        val JSON = Json { encodeDefaults = true }
    }
}
