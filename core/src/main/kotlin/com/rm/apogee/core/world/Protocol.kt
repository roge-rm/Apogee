package com.rm.apogee.core.world

import com.rm.apogee.core.craft.CraftDesign
import com.rm.apogee.core.math.SerialQuat
import com.rm.apogee.core.math.SerialVec3
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * Something a player asks the world to do.
 *
 * Commands travel client -> server and are applied at a tick boundary, never
 * mid-step. Applying them mid-step would make the result depend on where in the
 * vessel iteration the command landed, which is exactly the kind of ordering
 * dependence that makes a replay stop matching.
 */
@Serializable
sealed interface Command {
    @Serializable
    @SerialName("setThrottle")
    data class SetThrottle(val vessel: Long, val throttle: Double) : Command

    @Serializable
    @SerialName("setAttitude")
    data class SetAttitude(
        val vessel: Long,
        val pitch: Double,
        val yaw: Double,
        val roll: Double,
    ) : Command

    /** Translation, in craft-local axes: right, up, forward. */
    @Serializable
    @SerialName("setTranslation")
    data class SetTranslation(
        val vessel: Long,
        val x: Double,
        val y: Double,
        val z: Double,
    ) : Command

    @Serializable
    @SerialName("setRcs")
    data class SetRcs(val vessel: Long, val enabled: Boolean) : Command

    @Serializable
    @SerialName("setSas")
    data class SetSas(val vessel: Long, val enabled: Boolean) : Command

    /** What stability assist holds: a navball marker, or the attitude at release. */
    @Serializable
    @SerialName("setSasMode")
    data class SetSasMode(val vessel: Long, val mode: SasMode) : Command

    /** What the navball measures against. */
    @Serializable
    @SerialName("setNavFrame")
    data class SetNavFrame(val vessel: Long, val frame: NavFrame) : Command

    /** Another craft to steer by, or -1 to clear. */
    @Serializable
    @SerialName("setTarget")
    data class SetTarget(val vessel: Long, val target: Long) : Command

    @Serializable
    @SerialName("setBrakes")
    data class SetBrakes(val vessel: Long, val engaged: Boolean) : Command

    @Serializable
    @SerialName("stage")
    data class Stage(val vessel: Long) : Command

    @Serializable
    @SerialName("spawnCraft")
    data class SpawnCraft(val design: CraftDesign, val siteId: String) : Command

    /**
     * Welds this craft to whatever it is resting against.
     *
     * No target: the world picks the nearest craft actually in contact. A
     * player pushing one module up against another should not also have to
     * identify it, and on a touch screen there is nothing sensible to tap.
     */
    @Serializable
    @SerialName("join")
    data class Join(val vessel: Long) : Command

    /**
     * Fly a different craft.
     *
     * The counterpart to launching: a world you leave things in is one where
     * the craft you want is usually not the one you are in. Only craft the
     * player owns are switchable, which the server checks.
     */
    @Serializable
    @SerialName("switchVessel")
    data class SwitchVessel(val vessel: Long) : Command

    @Serializable
    @SerialName("chat")
    data class Chat(val text: String) : Command
}

/**
 * One vessel's motion at a tick.
 *
 * Small and sent constantly - 20 Hz per vessel in range - which is why it
 * carries only what changes continuously. Structure travels separately as
 * [StructureUpdate], because a craft's part list changes a handful of times per
 * flight and repeating it 20 times a second would dominate the bandwidth for no
 * reason.
 */
@Serializable
data class VesselKinematics(
    val vessel: Long,
    val referenceBodyId: String,
    val position: SerialVec3,
    val rotation: SerialQuat,
    val velocity: SerialVec3,
    val angularVelocity: SerialVec3,
    /** Fraction, for the plume. */
    val throttle: Double = 0.0,
    /** The craft's moving parts, packed by [VesselPose]. */
    val pose: ByteArray = ByteArray(0),
) {
    // By content: an array compares by identity, and two snapshots of the
    // same craft are equal whether or not they share one.
    override fun equals(other: Any?): Boolean =
        other is VesselKinematics && vessel == other.vessel && referenceBodyId == other.referenceBodyId &&
            position == other.position && rotation == other.rotation && velocity == other.velocity &&
            angularVelocity == other.angularVelocity && throttle == other.throttle &&
            pose.contentEquals(other.pose)

    override fun hashCode(): Int =
        ((vessel.hashCode() * 31 + position.hashCode()) * 31 + rotation.hashCode()) * 31 + pose.contentHashCode()
}

@Serializable
data class Snapshot(
    val tick: Long,
    val time: Double,
    val vessels: List<VesselKinematics>,
)

/**
 * A vessel's structure appearing, changing or going away.
 *
 * Sent on spawn, on decouple, and on destruction. [design] is null when the
 * vessel is simply gone.
 */
@Serializable
data class StructureUpdate(
    val vessel: Long,
    val design: CraftDesign? = null,
    val name: String = "",
    /** Which stage the craft is on, so a joining client sees the right state. */
    val currentStage: Int = 0,
    val activatedParts: List<Int> = emptyList(),
    /**
     * Who this craft belongs to, or blank for debris.
     *
     * Sent so the client can tell which craft are the player's own, and offer
     * to switch between them without asking the server first.
     */
    val owner: String = "",
    /** The owner's display name, for labels and chat. Never matched on. */
    val ownerName: String = "",
    /**
     * Parts that have failed: a collapsed leg, a torn chute.
     *
     * Carried alongside [activatedParts] rather than by removing them from
     * it, because the two are genuinely different states - a torn parachute
     * is staged *and* useless, and must not look un-staged or the client
     * would show it as still available to deploy.
     */
    val brokenParts: List<Int> = emptyList(),
)

/** Server -> client. */
@Serializable
sealed interface ServerMessage {
    @Serializable
    @SerialName("welcome")
    data class Welcome(
        val protocolVersion: Int,
        val catalogHash: String,
        val serverName: String,
        /** The vessel this client controls, or -1 if none yet. */
        val controlledVessel: Long = -1,
        /**
         * What the world's weather is made from - all the client needs to
         * compute the same wind for its replica and the same sky to draw.
         * Null for still air.
         */
        val weather: com.rm.apogee.core.weather.WeatherConfig? = null,
    ) : ServerMessage

    @Serializable
    @SerialName("rejected")
    data class Rejected(val reason: String) : ServerMessage

    @Serializable
    @SerialName("snapshot")
    data class SnapshotMessage(val snapshot: Snapshot) : ServerMessage

    @Serializable
    @SerialName("structure")
    data class StructureMessage(val update: StructureUpdate) : ServerMessage

    /**
     * The craft this client is now flying.
     *
     * Control is settled once at the handshake in [Welcome], but it moves
     * afterwards - launching a new craft, or switching to one already parked.
     * Without this the client would go on sending commands naming a craft the
     * server no longer associates with it, and every one would be refused.
     */
    @Serializable
    @SerialName("controlChanged")
    data class ControlChanged(val vessel: Long) : ServerMessage

    @Serializable
    @SerialName("chat")
    data class ChatMessage(val from: String, val text: String) : ServerMessage

    /**
     * Scatter knocked down. Sent as it happens, and in full to anyone joining,
     * so every player's forest has the same gaps in it.
     */
    @Serializable
    @SerialName("scatterFelled")
    data class ScatterFelled(val ids: List<Long>) : ServerMessage

    /**
     * What is left in the tanks of the craft this client flies: for each
     * part in order, one amount per [com.rm.apogee.core.part.ResourceType].
     *
     * Sent a few times a second, and only to the pilot - nobody else's HUD
     * shows another craft's fuel. Without it the client's replica started
     * every rebuild with full tanks, so a craft resumed half empty read full
     * and predicted thrust the server's craft no longer had.
     */
    @Serializable
    @SerialName("fuel")
    data class FuelLevels(val vessel: Long, val amounts: List<Float>) : ServerMessage

    /**
     * Lightning struck [vessel], knocking out [partIndex] (or nothing, -1).
     * Strikes that hit nothing are not sent: every client works those out
     * from the weather itself.
     */
    @Serializable
    @SerialName("lightning")
    data class Lightning(val strikeId: Long, val vessel: Long, val partIndex: Int) : ServerMessage
}

/** Client -> server. */
@Serializable
sealed interface ClientMessage {
    @Serializable
    @SerialName("hello")
    data class Hello(
        val protocolVersion: Int,
        val catalogHash: String,
        /** Cosmetic: what to show beside this player's craft and in chat. */
        val playerName: String,
        /**
         * Stable, opaque, generated once per install and never typed by
         * anyone. This is what decides which craft are whose; [playerName]
         * decides nothing.
         */
        val clientId: String,
        /**
         * [com.rm.apogee.core.terrain.TerrainField.GENERATION]. Two builds
         * with different ground cannot share a world: each would collide
         * craft with its own idea of the surface.
         */
        val terrainGeneration: Int = 0,
    ) : ClientMessage

    @Serializable
    @SerialName("command")
    data class CommandMessage(val command: Command) : ClientMessage
}

object Protocol {
    /**
     * Bumped whenever the wire format changes incompatibly. Checked alongside
     * the part-catalogue hash during the handshake, because the two can drift
     * independently - a matching protocol with a mismatched catalogue is just
     * as broken, and much harder to diagnose from the symptoms.
     */
    // 3: CraftDesign.orientation, Command.SetBrakes.
    // 4: ServerMessage.ScatterFelled, Hello.terrainGeneration.
    // 5: VesselKinematics.pose.
    // 6: ServerMessage.FuelLevels, CraftDesign.manualStaging.
    // 7: Welcome.weather, ServerMessage.Lightning.
    const val VERSION = 7
}
