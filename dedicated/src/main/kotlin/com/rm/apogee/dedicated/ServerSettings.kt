package com.rm.apogee.dedicated

import java.io.File

/**
 * Everything the standalone server reads when it starts.
 *
 * I use environment variables instead of a config file. The server is meant to live in a container,
 * where the environment is how settings arrive, and a file would need a volume of its own just to
 * set a port. Anything that really builds up over time (the world, the craft) lives in
 * [stateDirectory] and is written by the server, not edited by hand.
 */
class ServerSettings(
    val serverName: String,
    val port: Int,
    val stateDirectory: File,
    val controlSocket: File?,
    val autosaveSeconds: Int,
    val lanDiscovery: Boolean,
    val tickHz: Int,
    val snapshotHz: Int,
    val maxPlayers: Int,
) {
    val worldFile: File get() = File(stateDirectory, "world.json")

    fun describe(): String = buildString {
        appendLine("  name        $serverName")
        appendLine("  port        $port")
        appendLine("  state       ${stateDirectory.absolutePath}")
        appendLine("  control     ${controlSocket?.absolutePath ?: "(disabled)"}")
        appendLine("  autosave    ${if (autosaveSeconds > 0) "${autosaveSeconds}s" else "off"}")
        appendLine("  discovery   ${if (lanDiscovery) "on" else "off"}")
        appendLine("  tick rate   ${tickHz}Hz, snapshots ${snapshotHz}Hz")
        append("  max players ${if (maxPlayers > 0) maxPlayers.toString() else "unlimited"}")
    }

    companion object {
        fun fromEnvironment(env: Map<String, String> = System.getenv()): ServerSettings {
            fun text(key: String, fallback: String) = env[key]?.takeIf { it.isNotBlank() } ?: fallback
            fun number(key: String, fallback: Int) = env[key]?.toIntOrNull() ?: fallback
            fun flag(key: String, fallback: Boolean) = when (env[key]?.lowercase()) {
                null, "" -> fallback
                "0", "false", "no", "off" -> false
                else -> true
            }

            val stateDirectory = File(text("APOGEE_STATE_DIR", "./state"))
            val socketPath = text("APOGEE_CONTROL_SOCKET", "")

            return ServerSettings(
                serverName = text("APOGEE_SERVER_NAME", "Apogee Server"),
                port = number("APOGEE_PORT", DEFAULT_PORT),
                stateDirectory = stateDirectory,
                // Missing means no control channel at all, which is the right default for someone
                // running this from a terminal.
                controlSocket = socketPath.takeIf { it.isNotBlank() }?.let { File(it) },
                autosaveSeconds = number("APOGEE_AUTOSAVE_SECONDS", 60),
                lanDiscovery = flag("APOGEE_LAN_DISCOVERY", true),
                tickHz = number("APOGEE_TICK_HZ", 60),
                snapshotHz = number("APOGEE_SNAPSHOT_HZ", 20),
                maxPlayers = number("APOGEE_MAX_PLAYERS", 0),
            )
        }

        const val DEFAULT_PORT = 45_678
    }
}
