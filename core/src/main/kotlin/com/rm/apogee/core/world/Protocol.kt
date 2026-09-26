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
    data class SetTarget(val vessel: Long, val target: Long, val body: String = "") : Command

    /** Replaces [vessel]'s planned burns with [burns]: see [PlannedBurn]. Empty clears them. */
    @Serializable
    @SerialName("planBurns")
    data class PlanBurns(val vessel: Long, val burns: List<PlannedBurn>) : Command

    /** Turns the autopilots on or off: flying the next burn, and landing. */
    @Serializable
    @SerialName("setAutopilot")
    data class SetAutopilot(val vessel: Long, val autoBurn: Boolean, val autoLand: Boolean) : Command

    @Serializable
    @SerialName("setBrakes")
    data class SetBrakes(val vessel: Long, val engaged: Boolean) : Command

    /** Folds out (or away) [vessel]'s sun wings and dishes. */
    @Serializable
    @SerialName("deploy")
    data class Deploy(val vessel: Long, val deployed: Boolean) : Command

    /** Crew member [crew] climbs out of [vessel] on EVA. */
    @Serializable
    @SerialName("eva")
    data class Eva(val vessel: Long, val crew: Long) : Command

    /** Someone on EVA in suit [vessel] climbs into a free seat of [target] - or, -1, the nearest in reach. */
    @Serializable
    @SerialName("board")
    data class Board(val vessel: Long, val target: Long = -1L) : Command

    /** Moves crew member [crew] to a free seat in [part] of the same craft. */
    @Serializable
    @SerialName("transferCrew")
    data class TransferCrew(val vessel: Long, val crew: Long, val part: Int) : Command

    /** Someone on their feet jumps. */
    @Serializable
    @SerialName("jump")
    data class Jump(val vessel: Long) : Command

    /** Someone on EVA takes hold of the nearest ladder, or lets go. */
    @Serializable
    @SerialName("grab")
    data class Grab(val vessel: Long, val on: Boolean) : Command

    /** Someone standing on the ground plants a flag. */
    @Serializable
    @SerialName("plantFlag")
    data class PlantFlag(val vessel: Long) : Command

    /** Switches [vessel]'s drills and its converters - a base's refinery - on or off. */
    @Serializable
    @SerialName("setIndustry")
    data class SetIndustry(val vessel: Long, val drilling: Boolean, val refining: Boolean) : Command

    /** Starts or stops [vessel] emptying its ore and water into the base, or craft, it is docked to or stands on. */
    @Serializable
    @SerialName("unload")
    data class Unload(val vessel: Long, val active: Boolean) : Command

    /** Drive the wheels backwards (or forwards again). */
    @Serializable
    @SerialName("setReverse")
    data class SetReverse(val vessel: Long, val engaged: Boolean) : Command

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
     * Pins this craft to the ground where it rests - a base founded - or,
     * [anchored] false, lets it go again. Only a craft with a working
     * foundation, at rest; the world checks.
     */
    @Serializable
    @SerialName("anchor")
    data class Anchor(val vessel: Long, val anchored: Boolean) : Command

    /**
     * Fill this craft from the base it stands on the pad of, or is docked to
     * - propellant, monopropellant and charge, as far as the base has them
     * and has the power to pump - or, [active] false, stop.
     */
    @Serializable
    @SerialName("refuel")
    data class Refuel(val vessel: Long, val active: Boolean) : Command

    /**
     * Two players' craft docked: who flies the combined craft [vessel] -
     * [pilot] is a player's client id, or empty for either of them.
     */
    @Serializable
    @SerialName("setDockPilot")
    data class SetDockPilot(val vessel: Long, val pilot: String) : Command

    /** Let go at docking part [part] of [vessel]: undock a ring or clamp, uncouple a hitch. */
    @Serializable
    @SerialName("undock")
    data class Undock(val vessel: Long, val part: Int) : Command

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

    /**
     * How fast the world runs: a multiple of real time, 0 to pause. Only
     * honoured by a server that allows it, with nobody else on it - one
     * player cannot stop or speed up everyone else's world.
     */
    @Serializable
    @SerialName("setWarp")
    data class SetWarp(val rate: Double) : Command

    /**
     * As fast as the world allows until universe [time], then back to real
     * time: to a planned burn, say. Honoured where [SetWarp] is.
     */
    @Serializable
    @SerialName("warpTo")
    data class WarpTo(val time: Double) : Command

    /**
     * Takes one of the player's own craft out of the world for good - from
     * the craft list, or retiring the one being flown.
     */
    @Serializable
    @SerialName("removeVessel")
    data class RemoveVessel(val vessel: Long) : Command
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
    /** How hurt, hot and dented its parts are, packed by [VesselCondition]; empty when whole and cool. */
    val condition: ByteArray = ByteArray(0),
) {
    // By content: an array compares by identity, and two snapshots of the
    // same craft are equal whether or not they share one.
    override fun equals(other: Any?): Boolean =
        other is VesselKinematics && vessel == other.vessel && referenceBodyId == other.referenceBodyId &&
            position == other.position && rotation == other.rotation && velocity == other.velocity &&
            angularVelocity == other.angularVelocity && throttle == other.throttle &&
            pose.contentEquals(other.pose) && condition.contentEquals(other.condition)

    override fun hashCode(): Int =
        ((vessel.hashCode() * 31 + position.hashCode()) * 31 + rotation.hashCode()) * 31 + pose.contentHashCode()
}

@Serializable
data class Snapshot(
    val tick: Long,
    val time: Double,
    val vessels: List<VesselKinematics>,
    /** How fast the world is running, times real time; 0 while paused. */
    val warp: Double = 1.0,
    /** What the player asked for, which the world may be holding below. */
    val warpRequested: Double = 1.0,
    /** Whether this player may pause or warp: a solo world with nobody else on it. */
    val warpAllowed: Boolean = false,
    /** Tow hitches coupled up. */
    val hitches: List<SavedLink> = emptyList(),
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
    /** Founded: pinned to the ground, immovable. See [World.anchor]. */
    val anchored: Boolean = false,
    /** Burns planned for it, soonest first. */
    val burns: List<PlannedBurn> = emptyList(),
    /** Who sits in each part, by crew id, in part order; empty with nobody aboard. */
    val crew: List<List<Long>> = emptyList(),
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

    /**
     * Your craft docked with another player's, and [vessel] is both now:
     * [other] is their name, [pilot] who flies it - a client id, or empty for
     * either of you. Sent again whenever that changes.
     */
    @Serializable
    @SerialName("dockedWith")
    data class DockedWith(val vessel: Long, val other: String, val otherId: String, val pilot: String) : ServerMessage

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
     * The pilot's craft's power and link home, sent with its tanks: charge
     * and what it holds, units; the net rate, units a second; whether it has
     * power; its [signal] and the relays that carries it through, nearest
     * first; whether it can be flown at all; and its fold-outs told out.
     */
    @Serializable
    @SerialName("systems")
    data class CraftSystems(
        val vessel: Long,
        val charge: Float,
        val capacity: Float,
        val net: Float,
        val powered: Boolean,
        val signal: Signal,
        val relays: List<Long>,
        val controllable: Boolean,
        /** Why it cannot be flown - "NO CREW", "NO POWER", "NO SIGNAL" - or blank. */
        val blocked: String = "",
        val needsSignal: Boolean,
        val deployed: Boolean,
        /** Someone on EVA: the craft with a free seat in reach (blank for none), a ladder in reach, and holding one. */
        val boardable: String = "",
        val canGrab: Boolean = false,
        val onLadder: Boolean = false,
        /** Aboard another player's craft: along for the ride, not flying it. */
        val passenger: Boolean = false,
        /** Its drills and converters switched on, and what its drills are doing. */
        val drilling: Boolean = false,
        val refining: Boolean = false,
        val drillState: DrillState = DrillState.OFF,
        /** Its survey of the body it orbits, 0..1, or -1 with no scanner aboard. */
        val survey: Float = -1f,
        /** What the ground right below holds, 0..1, read by a scanner low enough; -1 when there is no reading. */
        val ore: Float = -1f,
        val water: Float = -1f,
    ) : ServerMessage

    /** A player's crew - at home, aboard, and on the memorial - sent to them on joining and whenever it changes. */
    @Serializable
    @SerialName("roster")
    data class Roster(val members: List<com.rm.apogee.core.crew.CrewMember>) : ServerMessage

    /** The bodies surveyed for ore and water: all of them, whenever the list grows, and on joining. */
    @Serializable
    @SerialName("surveyed")
    data class Surveyed(val bodies: List<String>) : ServerMessage

    /**
     * What the pilot's craft can do with a base just now, sent with its
     * tanks: be founded where it rests, or let go if it is; be filled from
     * the base it stands on or is docked to - and whether it is, or why it
     * last stopped.
     */
    @Serializable
    @SerialName("service")
    data class Service(
        val vessel: Long,
        val canFound: Boolean,
        val founded: Boolean,
        val canRefuel: Boolean,
        val refuelling: Boolean,
        val stopped: String = "",
        /** Whether it has ore or water to empty into what it is docked to or stands on, and is doing so. */
        val canUnload: Boolean = false,
        val unloading: Boolean = false,
    ) : ServerMessage

    /** A founded base near the pilot - or the one they are flying - as its card shows it. */
    @Serializable
    @SerialName("base")
    data class BaseStatus(
        val vessel: Long,
        val name: String,
        val powered: Boolean,
        val charge: Float,
        val chargeCapacity: Float,
        /** Charge coming in less going out, a second. */
        val net: Float,
        val propellant: Float,
        val propellantCapacity: Float,
        val monopropellant: Float,
        val monopropellantCapacity: Float,
        /** Launch pads it has. */
        val pads: Int,
        /** How far off it is, m. */
        val distance: Float,
        val ore: Float = 0f,
        val oreCapacity: Float = 0f,
        val water: Float = 0f,
        val waterCapacity: Float = 0f,
        /** Whether it has a refinery, and whether that is switched on; and its drills, if it has any. */
        val hasRefinery: Boolean = false,
        val refining: Boolean = false,
        val drilling: Boolean = false,
    ) : ServerMessage

    /**
     * Lightning struck [vessel], knocking out [partIndex] (or nothing, -1).
     * Strikes that hit nothing are not sent: every client works those out
     * from the weather itself.
     */
    @Serializable
    @SerialName("lightning")
    data class Lightning(val strikeId: Long, val vessel: Long, val partIndex: Int) : ServerMessage

    /**
     * Something happened to a part that is worth seeing and hearing: a blow,
     * a part destroyed or torn off, a tank going up. By part id and
     * position rather than index alone, because by the time it arrives the
     * craft it happened to may already be a different shape. [amount] is the
     * impact speed, m/s, for an impact, and the propellant, kg, for an
     * explosion.
     */
    @Serializable
    @SerialName("partEvent")
    data class PartEvent(
        val kind: PartEventKind,
        val vessel: Long,
        val partId: String,
        val bodyId: String,
        val position: SerialVec3,
        val amount: Double = 0.0,
        val cause: String = "",
        /** Universe time it happened: which way the planet was turned. */
        val time: Double = 0.0,
    ) : ServerMessage
}

@Serializable
enum class PartEventKind {
    @SerialName("impact") IMPACT,
    @SerialName("destroyed") DESTROYED,
    @SerialName("detached") DETACHED,
    @SerialName("explosion") EXPLOSION,
    /** Two craft latched ring to ring or clamped; [ServerMessage.PartEvent.vessel] is what they became. */
    @SerialName("docked") DOCKED,
    @SerialName("undocked") UNDOCKED,
    @SerialName("hitched") HITCHED,
    @SerialName("unhitched") UNHITCHED,
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
    // 8: VesselKinematics.condition, ServerMessage.PartEvent, the ablator,
    //    engine output in the pose, time warp, removing craft.
    // 9: docking - Undock, SetDockPilot, DockedWith, Snapshot.hitches.
    // 10: PlacedPart.turn.
    // 11: the sea - flooding in VesselCondition, open hulls.
    // 12: bases - Anchor, Refuel, StructureUpdate.anchored, Service, BaseStatus, launching from base pads.
    // 13: navigation - PlanBurns, SetAutopilot, WarpTo, targeting bodies.
    // 14: craft systems - Deploy, CraftSystems.
    // 15: resources - SetIndustry, Unload, Surveyed, CraftSystems industry and readings, BaseStatus stores.
    // 16: crew - Roster, seats in StructureUpdate, EVA and boarding commands.
    const val VERSION = 16
}
