package com.rm.apogee.net

import com.rm.apogee.core.world.ClientMessage
import com.rm.apogee.core.world.Command
import com.rm.apogee.core.world.Protocol
import com.rm.apogee.core.world.ServerMessage
import com.rm.apogee.core.weather.WeatherConfig
import com.rm.apogee.core.weather.WeatherIntensity
import org.junit.Assert.assertEquals
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File

/** The optimised Android encoder's real bytes, read by unoptimised JVM code. */
class R8WireInteropTest {
    @Test fun optimizedAndroidMessagesDecodeOnTheJvm() {
        val path = System.getenv("R8_VALIDATION_DIR")
        assumeTrue("set R8_VALIDATION_DIR to the pulled Android exports", path != null)
        val directory = File(path!!)
        assertEquals(ClientMessage.Hello(Protocol.VERSION, "test-catalog", "Pilot", "r8-player"),
            Codec.decodeClientMessage(File(directory, "r8-output-client.bin").readBytes()))
        assertEquals(ClientMessage.CommandMessage(Command.SetThrottle(7, 0.75)),
            Codec.decodeClientMessage(File(directory, "r8-output-command.bin").readBytes()))
        assertEquals(ServerMessage.Welcome(Protocol.VERSION, "test-catalog", "R8 local server",
            weather = WeatherConfig(intensity = WeatherIntensity.EXTREME)),
            Codec.decodeServerMessage(File(directory, "r8-output-server.bin").readBytes()))
    }
}
