package com.rm.apogee.net

import com.rm.apogee.core.craft.StockCraft
import com.rm.apogee.core.math.Quat
import com.rm.apogee.core.math.Vec3
import com.rm.apogee.core.part.StockParts
import com.rm.apogee.core.world.ClientMessage
import com.rm.apogee.core.world.Command
import com.rm.apogee.core.world.Protocol
import com.rm.apogee.core.world.ServerMessage
import com.rm.apogee.core.world.Snapshot
import com.rm.apogee.core.world.StructureUpdate
import com.rm.apogee.core.world.VesselKinematics
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class CodecTest {

    @Test
    fun `every client message round-trips`() {
        val messages = listOf(
            ClientMessage.Hello(Protocol.VERSION, "abc123", "Pilot", "install-1"),
            ClientMessage.Hello(Protocol.VERSION, "abc123", "Pilot", "install-1", terrainGeneration = 7, systemHash = "0f1e2d3c4b5a6978"),
            ClientMessage.CommandMessage(Command.SetThrottle(7, 0.75)),
            ClientMessage.CommandMessage(Command.Unlock("tanks")),
            ClientMessage.CommandMessage(Command.SetAttitude(7, 0.1, -0.2, 0.3)),
            ClientMessage.CommandMessage(Command.SetSas(7, true)),
            ClientMessage.CommandMessage(Command.Stage(7)),
            ClientMessage.CommandMessage(Command.Chat("hello")),
            ClientMessage.CommandMessage(Command.SetTarget(7, -1, "luna")),
            ClientMessage.CommandMessage(
                Command.PlanBurns(7, listOf(com.rm.apogee.core.world.PlannedBurn(1234.5, 846.9, -3.0, 1.5))),
            ),
            ClientMessage.CommandMessage(Command.SetAutopilot(7, autoBurn = true, autoLand = false)),
            ClientMessage.CommandMessage(Command.WarpTo(99_999.0)),
            ClientMessage.CommandMessage(Command.Deploy(7, true)),
            ClientMessage.CommandMessage(Command.SetIndustry(7, drilling = true, refining = false)),
            ClientMessage.CommandMessage(Command.Unload(7, true)),
            ClientMessage.CommandMessage(Command.Eva(7, 42)),
            ClientMessage.CommandMessage(Command.Board(8, -1)),
            ClientMessage.CommandMessage(Command.TransferCrew(7, 42, 3)),
            ClientMessage.CommandMessage(Command.Jump(8)),
            ClientMessage.CommandMessage(Command.Grab(8, true)),
            ClientMessage.CommandMessage(Command.PlantFlag(8)),
            ClientMessage.CommandMessage(Command.SetBallast(7, -1)),
            ClientMessage.CommandMessage(Command.HoldDepth(7, true)),
            ClientMessage.CommandMessage(
                Command.SpawnCraft(StockCraft.starterRocket(StockParts.catalog), "cape")
            ),
        )

        for (message in messages) {
            val decoded = Codec.decodeClientMessage(Codec.encode(message))
            assertEquals("round trip failed for ${message::class.simpleName}", message, decoded)
        }
    }

    @Test
    fun `every server message round-trips`() {
        val kinematics = VesselKinematics(
            vessel = 1,
            referenceBodyId = "terra",
            position = Vec3(1.0, 2.0, 3.0),
            rotation = Quat.fromAxisAngle(Vec3.unitY(), 0.4),
            velocity = Vec3(10.0, 20.0, 30.0),
            angularVelocity = Vec3(0.1, 0.0, -0.1),
            throttle = 0.5,
        )
        val messages = listOf(
            ServerMessage.Welcome(Protocol.VERSION, "abc123", "Test Server", 1),
            ServerMessage.Welcome(Protocol.VERSION, "abc123", "Test Server", 1, mode = com.rm.apogee.core.world.WorldSave.MODE_CAREER),
            ServerMessage.Career(
                com.rm.apogee.core.career.CareerState("install-1", insight = 42, nodes = listOf("tanks"), feats = mapOf("hop" to 0, "staging" to 2), visits = listOf("luna:orbit")),
                listOf(com.rm.apogee.core.career.WorldFirst("luna", "land", "install-1", "Pilot", 1234.5)),
            ),
            ServerMessage.Feat("Staging", "Gold", 35),
            ServerMessage.CareerRefused("Not unlocked yet: Vesper Vacuum Engine"),
            ServerMessage.Rejected("part catalogue mismatch"),
            ServerMessage.SnapshotMessage(Snapshot(42, 0.7, listOf(kinematics))),
            ServerMessage.StructureMessage(
                StructureUpdate(1, StockCraft.probe(StockParts.catalog), "Probe", 0, listOf(0))
            ),
            ServerMessage.StructureMessage(
                StructureUpdate(
                    1, StockCraft.probe(StockParts.catalog), "Probe", 0, listOf(0),
                    burns = listOf(com.rm.apogee.core.world.PlannedBurn(10.0, prograde = 5.0)),
                )
            ),
            ServerMessage.ChatMessage("Pilot", "hello"),
            ServerMessage.CraftSystems(
                7, charge = 12.5f, capacity = 30f, net = -0.015f, powered = true,
                signal = com.rm.apogee.core.world.Signal.RELAYED, relays = listOf(9, 11),
                controllable = true, needsSignal = true, deployed = true,
                drilling = true, refining = true, drillState = com.rm.apogee.core.world.DrillState.DIGGING,
                survey = 0.4f, ore = 0.55f, water = 0.9f,
                ballast = 0.6f, ballastMode = 1, holdingDepth = 120f, crush = 0.85f,
                seabed = 14.5f, findBearing = -32f, findRange = 640f,
            ),
            ServerMessage.Surveyed(listOf("luna", "terra")),
            ServerMessage.Roster(
                listOf(
                    com.rm.apogee.core.crew.CrewMember(1, "Ada Mercer", "abc", com.rm.apogee.core.crew.CrewStatus.ABOARD, vessel = 7),
                    com.rm.apogee.core.crew.CrewMember(2, "Bram Holm", "abc", com.rm.apogee.core.crew.CrewStatus.LOST, lostAt = 12.5, lostWhere = "Luna", lostHow = "a hard landing"),
                ),
            ),
        )

        for (message in messages) {
            val decoded = Codec.decodeServerMessage(Codec.encode(message))
            assertEquals("round trip failed for ${message::class.simpleName}", message, decoded)
        }
    }

    @Test
    fun `vectors survive the round trip at full double precision`() {
        // The whole point of a double-precision core is lost if the wire format narrows on the way
        // past.
        val position = Vec3(6_400_000.000000123, -1.0e-7, 12_345.678901234)
        val message = ServerMessage.SnapshotMessage(
            Snapshot(
                1, 0.0,
                listOf(
                    VesselKinematics(
                        1, "terra", position, Quat.identity(),
                        Vec3.zero(), Vec3.zero(), 0.0,
                    )
                ),
            )
        )

        val decoded = Codec.decodeServerMessage(Codec.encode(message))
        val result = (decoded as ServerMessage.SnapshotMessage).snapshot.vessels.first().position
        assertEquals(position.x, result.x, 0.0)
        assertEquals(position.y, result.y, 0.0)
        assertEquals(position.z, result.z, 0.0)
    }

    @Test
    fun `a craft design is compact enough to send`() {
        val message = ClientMessage.CommandMessage(
            Command.SpawnCraft(StockCraft.starterRocket(StockParts.catalog), "cape")
        )
        val size = Codec.encode(message).size
        // Not a hard budget, but a canary. Structure travels rarely, but if a 13-part rocket ever
        // costs tens of kilobytes something has gone wrong in the schema.
        assertTrue("craft design encoded to $size bytes", size < 8_000)
    }
}
