package com.rm.apogee.server

import com.rm.apogee.core.part.StockParts
import com.rm.apogee.core.world.Command
import com.rm.apogee.net.GameClient
import com.rm.apogee.net.LanDiscovery
import com.rm.apogee.net.TcpTransport
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import java.util.concurrent.ConcurrentHashMap

/**
 * Connects to a running host as a real client and reports what it sees.
 *
 * `./gradlew :server:netProbe --args="<host> <port>"`, or with no arguments it
 * browses the network first.
 *
 * Exists to close the loop that no unit test can: a game hosted from an actual
 * phone, joined from an actual second process, over an actual socket. The
 * integration tests prove the protocol and the framing; this proves the thing
 * the player will do.
 */
fun main(args: Array<String>) = runBlocking {
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    val catalog = StockParts.catalog

    try {
        val (host, port) = when {
            args.size >= 2 -> args[0] to args[1].toInt()
            else -> browse(scope) ?: run {
                println("No games found on this network.")
                return@runBlocking
            }
        }

        println("Connecting to $host:$port as \"Probe\"...")
        val transport = TcpTransport.connect(host, port).getOrElse {
            println("FAILED to connect: ${it.message}")
            return@runBlocking
        }

        val client = GameClient(transport, "Probe", catalog.contentHash, "probe-install")
        client.connect(scope)

        repeat(60) {
            if (client.connected || client.rejectionReason != null) return@repeat
            delay(100)
        }

        if (client.rejectionReason != null) {
            println("REJECTED: ${client.rejectionReason}")
            return@runBlocking
        }
        if (!client.connected) {
            println("FAILED: no welcome within 6s")
            return@runBlocking
        }

        println("Connected to \"${client.serverName}\"")
        println("Controlling vessel ${client.controlledVessel}")

        // Let a few snapshots land so the world settles.
        delay(1_500)
        println("Vessels visible: ${client.vessels.size}")
        for (vessel in client.vessels.sortedBy { it.id }) {
            val state = vessel.latest
            val marker = if (vessel.id == client.controlledVessel) " <- mine" else ""
            println(
                "  #%d %-14s %2d parts  alt %,.0f m%s".format(
                    vessel.id,
                    vessel.name.take(14),
                    vessel.design.parts.size,
                    (state?.position?.length ?: 0.0) - 600_000.0,
                    marker,
                )
            )
        }

        // Fly it, so the host sees something happen.
        val mine = client.controlledVessel
        if (mine != null) {
            println("Staging and throttling up...")
            client.send(Command.Stage(mine))
            client.send(Command.SetThrottle(mine, 1.0))
            client.send(Command.SetSas(mine, true))
            client.send(Command.Chat("Probe reporting in"))

            val start = client.vessel(mine)?.latest?.position?.length ?: 0.0
            delay(8_000)
            val end = client.vessel(mine)?.latest?.position?.length ?: 0.0
            println("Climbed %.1f m in 8s".format(end - start))
        }

        println("Chat seen: ${client.chatHistory()}")
        client.close()
    } finally {
        scope.cancel()
    }
}

/** Browses for a few seconds and returns the first game found. */
private suspend fun browse(scope: CoroutineScope): Pair<String, Int>? {
    println("Browsing for games...")
    val found = ConcurrentHashMap<String, Pair<String, Int>>()
    val job = LanDiscovery.listen(scope) { beacon ->
        val key = "${beacon.address}:${beacon.port}"
        if (found.putIfAbsent(key, beacon.address to beacon.port) == null) {
            println("  found \"${beacon.serverName}\" at $key (${beacon.players} playing)")
        }
    }
    delay(4_000)
    job.cancel()
    return found.values.firstOrNull()
}
