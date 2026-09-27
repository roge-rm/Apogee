package com.rm.apogee.dedicated

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ServerSettingsTest {

    @Test
    fun `an empty environment gives a runnable server`() {
        val settings = ServerSettings.fromEnvironment(emptyMap())

        assertEquals(ServerSettings.DEFAULT_PORT, settings.port)
        assertEquals(60, settings.autosaveSeconds)
        assertTrue("discovery should be on by default", settings.lanDiscovery)
        assertNull(
            "no control socket unless asked for - a terminal user needs no admin channel",
            settings.controlSocket,
        )
        assertTrue(settings.serverName.isNotBlank())
    }

    @Test
    fun `the environment overrides every default`() {
        val settings = ServerSettings.fromEnvironment(
            mapOf(
                "APOGEE_SERVER_NAME" to "Orbital Test Range",
                "APOGEE_PORT" to "40000",
                "APOGEE_STATE_DIR" to "/data",
                "APOGEE_CONTROL_SOCKET" to "/run/apogee/control.sock",
                "APOGEE_AUTOSAVE_SECONDS" to "15",
                "APOGEE_LAN_DISCOVERY" to "0",
                "APOGEE_TICK_HZ" to "30",
                "APOGEE_SNAPSHOT_HZ" to "10",
                "APOGEE_MAX_PLAYERS" to "8",
            )
        )

        assertEquals("Orbital Test Range", settings.serverName)
        assertEquals(40_000, settings.port)
        assertEquals("/data", settings.stateDirectory.path)
        assertEquals("/data/world.json", settings.worldFile.path)
        assertEquals("/run/apogee/control.sock", settings.controlSocket?.path)
        assertEquals(15, settings.autosaveSeconds)
        assertFalse(settings.lanDiscovery)
        assertEquals(30, settings.tickHz)
        assertEquals(10, settings.snapshotHz)
        assertEquals(8, settings.maxPlayers)
    }

    @Test
    fun `flags accept the spellings people actually type`() {
        fun discovery(value: String) =
            ServerSettings.fromEnvironment(mapOf("APOGEE_LAN_DISCOVERY" to value)).lanDiscovery

        for (off in listOf("0", "false", "no", "off", "FALSE", "Off")) {
            assertFalse("\"$off\" should mean off", discovery(off))
        }
        for (on in listOf("1", "true", "yes", "on")) {
            assertTrue("\"$on\" should mean on", discovery(on))
        }
    }

    @Test
    fun `a blank value falls back instead of giving an empty name`() {
        val settings = ServerSettings.fromEnvironment(
            mapOf("APOGEE_SERVER_NAME" to "   ", "APOGEE_CONTROL_SOCKET" to "")
        )
        assertTrue(settings.serverName.isNotBlank())
        assertNull(settings.controlSocket)
    }

    @Test
    fun `a nonsense number falls back instead of crashing at startup`() {
        // An operator's typo in a compose file shouldn't be a stack trace.
        val settings = ServerSettings.fromEnvironment(mapOf("APOGEE_PORT" to "not-a-port"))
        assertEquals(ServerSettings.DEFAULT_PORT, settings.port)
    }

    @Test
    fun `settings describe themselves for the startup log`() {
        val text = ServerSettings.fromEnvironment(emptyMap()).describe()
        assertTrue(text.contains("port"))
        assertTrue(text.contains("autosave"))
        assertTrue(text.contains("state"))
    }
}
