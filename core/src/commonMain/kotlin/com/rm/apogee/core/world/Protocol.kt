package com.rm.apogee.core.world

import com.rm.apogee.core.craft.CraftDesign
import com.rm.apogee.core.math.SerialQuat
import com.rm.apogee.core.math.SerialVec3
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * Something a player asks the world to do. Applied at a tick boundary, never mid-step, so the
 * result doesn't depend on where in the vessel loop it landed.
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

    /** What stability assist holds: a navball marker, or the attitude when you let go. */
    @Serializable
    @SerialName("setSasMode")
    data class SetSasMode(val vessel: Long, val mode: SasMode) : Command

    /** What the navball measures against. */
    @Serializable
    @SerialName("setNavFrame")
    data class SetNavFrame(val vessel: Long, val frame: NavFrame) : Command

    /** [vessel]'s light switch. */
    @Serializable
    @SerialName("setLights")
    data class SetLights(val vessel: Long, val mode: com.rm.apogee.core.part.LightMode) : Command

    /** Another craft to steer by, or -1 to clear. [body] is a world, or a place (see [Wonders.TARGET_PREFIX]). */
    @Serializable
    @SerialName("setTarget")
    data class SetTarget(val vessel: Long, val target: Long, val body: String = "") : Command

    /** Replaces [vessel]'s planned burns with [burns]. See [PlannedBurn]. Empty clears them. */
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

    /** Folds [vessel]'s sun wings and dishes out, or away. */
    @Serializable
    @SerialName("deploy")
    data class Deploy(val vessel: Long, val deployed: Boolean) : Command

    /** Puts [vessel]'s landing gear and legs down, or folds them up. */
    @Serializable
    @SerialName("setGear")
    data class SetGear(val vessel: Long, val down: Boolean) : Command

    /** Crew member [crew] climbs out of [vessel] on EVA. */
    @Serializable
    @SerialName("eva")
    data class Eva(val vessel: Long, val crew: Long) : Command

    /**
     * Someone on EVA in suit [vessel] climbs into a free seat of [target], or with -1, the nearest
     * one in reach.
     */
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

    /** Someone on EVA grabs the nearest ladder, or lets go. */
    @Serializable
    @SerialName("grab")
    data class Grab(val vessel: Long, val on: Boolean) : Command

    /** Someone standing on the ground plants a flag. */
    @Serializable
    @SerialName("plantFlag")
    data class PlantFlag(val vessel: Long) : Command

    /** Someone in the water, or at the top of a ladder, climbs out onto a deck in reach. */
    @Serializable
    @SerialName("climbOut")
    data class ClimbOut(val vessel: Long) : Command

    /** The crew roll their capsized craft back upright. See `World.canRight`. */
    @Serializable
    @SerialName("rightCraft")
    data class RightCraft(val vessel: Long) : Command

    /**
     * Floods [vessel]'s ballast tanks (1), blows them (-1), or stops them (0), and lets go of any
     * depth being held.
     */
    @Serializable
    @SerialName("setBallast")
    data class SetBallast(val vessel: Long, val mode: Int) : Command

    /** Holds [vessel] at its current depth using its ballast, or stops. */
    @Serializable
    @SerialName("holdDepth")
    data class HoldDepth(val vessel: Long, val on: Boolean) : Command

    /**
     * Holds [vessel] still where it is now with its keeper core, over the ground and at its height,
     * or stops.
     */
    @Serializable
    @SerialName("stationKeep")
    data class SetStationKeep(val vessel: Long, val on: Boolean) : Command

    /** Switches [vessel]'s drills and its converters (a base's refinery) on or off. */
    @Serializable
    @SerialName("setIndustry")
    data class SetIndustry(val vessel: Long, val drilling: Boolean, val refining: Boolean) : Command

    /** Starts or stops [vessel] emptying its ore and water into the base or craft it's docked to or standing on. */
    @Serializable
    @SerialName("unload")
    data class Unload(val vessel: Long, val active: Boolean) : Command

    /** Drive the wheels backwards (or forwards again). */
    @Serializable
    @SerialName("setReverse")
    data class SetReverse(val vessel: Long, val engaged: Boolean) : Command

    /** Hook the winch onto whatever's in reach in front of it: a craft first, then the ground. */
    @Serializable
    @SerialName("hook")
    data class Hook(val vessel: Long) : Command

    /** Wind the winch in (1), let it out (-1), or hold it (0). */
    @Serializable
    @SerialName("reel")
    data class Reel(val vessel: Long, val mode: Int) : Command

    /** Let go of the winch line, and wind it back onto the drum. */
    @Serializable
    @SerialName("releaseLine")
    data class ReleaseLine(val vessel: Long) : Command

    /**
     * Hold the aircraft's height and heading as they are now, or let go. A career has to have
     * unlocked Cruise Control.
     */
    @Serializable
    @SerialName("cruise")
    data class SetCruise(val vessel: Long, val on: Boolean) : Command

    /** Switch action group [group] (1 to 3) on, or off if it's on. */
    @Serializable
    @SerialName("toggleGroup")
    data class ToggleGroup(val vessel: Long, val group: Int) : Command

    /** Flaps down, or back up. */
    @Serializable
    @SerialName("flaps")
    data class SetFlaps(val vessel: Long, val down: Boolean) : Command

    @Serializable
    @SerialName("stage")
    data class Stage(val vessel: Long) : Command

    @Serializable
    @SerialName("spawnCraft")
    data class SpawnCraft(val design: CraftDesign, val siteId: String) : Command

    /** Welds this craft to the nearest craft it's touching. */
    @Serializable
    @SerialName("join")
    data class Join(val vessel: Long) : Command

    /**
     * Pins this craft to the ground where it rests, founding a base, or with [anchored] false, lets
     * it go. The world checks it has a working foundation and is at rest.
     */
    @Serializable
    @SerialName("anchor")
    data class Anchor(val vessel: Long, val anchored: Boolean) : Command

    /**
     * Fill this craft's propellant, monopropellant and charge from the base it stands on or is
     * docked to, as far as the base has them and the power to pump. [active] false stops.
     */
    @Serializable
    @SerialName("refuel")
    data class Refuel(val vessel: Long, val active: Boolean) : Command

    /** Who flies two players' docked craft [vessel]: a client id, or empty for either. */
    @Serializable
    @SerialName("setDockPilot")
    data class SetDockPilot(val vessel: Long, val pilot: String) : Command

    /** Let go at docking part [part] of [vessel]: undock a ring or clamp, or uncouple a hitch. */
    @Serializable
    @SerialName("undock")
    data class Undock(val vessel: Long, val part: Int) : Command

    /** Fly a different craft. Only your own; the server checks. */
    @Serializable
    @SerialName("switchVessel")
    data class SwitchVessel(val vessel: Long) : Command

    @Serializable
    @SerialName("chat")
    data class Chat(val text: String) : Command

    /**
     * How fast the world runs, times real time, with 0 to pause. Only honoured by a server that
     * allows it with nobody else on it.
     */
    @Serializable
    @SerialName("setWarp")
    data class SetWarp(val rate: Double) : Command

    /** As fast as allowed until universe [time], then real time. Honoured wherever [SetWarp] is. */
    @Serializable
    @SerialName("warpTo")
    data class WarpTo(val time: Double) : Command

    /** Takes one of the player's own craft out of the world for good. */
    @Serializable
    @SerialName("removeVessel")
    data class RemoveVessel(val vessel: Long) : Command

    /** In a career, spend insight on tech node [node]. */
    @Serializable
    @SerialName("unlock")
    data class Unlock(val node: String) : Command
}

/**
 * One vessel's motion at a tick, sent at 20 Hz for each vessel in range, so it only carries what
 * changes all the time. Structure goes separately as [StructureUpdate].
 */
@Serializable
data class VesselKinematics(
    val vessel: Long,
    val referenceBodyId: String,
    val position: SerialVec3,
    val rotation: SerialQuat,
    val velocity: SerialVec3,
    val angularVelocity: SerialVec3,
    /** A fraction, for the plume. */
    val throttle: Double = 0.0,
    /** The craft's moving parts, packed by [VesselPose]. */
    val pose: ByteArray = ByteArray(0),
    /**
     * How hurt, hot and dented its parts are, packed by [VesselCondition]. Empty when it's whole
     * and cool.
     */
    val condition: ByteArray = ByteArray(0),
    /**
     * Asleep on the server: parked on the ground, or riding the sea. The client's replica then
     * skips its physics, since it can't tell a ship asleep on the water from how she moves.
     */
    val asleep: Boolean = false,
    /**
     * Where in the design its centre of mass is now, with fuel burnt, so parts are drawn round
     * [position] where they really are. Null where it isn't known.
     */
    val centreOfMass: SerialVec3? = null,
    /** Its lamps lit now, by part index. Null for none. */
    val lit: List<Int>? = null,
) {
    // By content, since arrays compare by identity.
    override fun equals(other: Any?): Boolean =
        other is VesselKinematics && vessel == other.vessel && referenceBodyId == other.referenceBodyId &&
            position == other.position && rotation == other.rotation && velocity == other.velocity &&
            angularVelocity == other.angularVelocity && throttle == other.throttle && asleep == other.asleep && lit == other.lit &&
            pose.contentEquals(other.pose) && condition.contentEquals(other.condition)

    override fun hashCode(): Int =
        ((vessel.hashCode() * 31 + position.hashCode()) * 31 + rotation.hashCode()) * 31 + pose.contentHashCode()
}

@Serializable
data class Snapshot(
    val tick: Long,
    val time: Double,
    val vessels: List<VesselKinematics>,
    /** How fast the world is running, times real time. 0 while paused. */
    val warp: Double = 1.0,
    /** What the player asked for, which the world might be holding below. */
    val warpRequested: Double = 1.0,
    /** Whether this player can pause or warp: a solo world with nobody else on it. */
    val warpAllowed: Boolean = false,
    /** Tow hitches coupled up. */
    val hitches: List<SavedLink> = emptyList(),
    /** Winch lines out, for drawing. */
    val lines: List<SavedLine> = emptyList(),
)

/**
 * A vessel's structure appearing, changing or going away: sent on spawn, decouple and destruction.
 * [design] is null when the vessel is gone.
 */
@Serializable
data class StructureUpdate(
    val vessel: Long,
    val design: CraftDesign? = null,
    val name: String = "",
    /** Which stage the craft is on, so a client joining sees the right state. */
    val currentStage: Int = 0,
    val activatedParts: List<Int> = emptyList(),
    /** Who this craft belongs to, or blank for debris. */
    val owner: String = "",
    /** The owner's display name, for labels and chat. Never matched on. */
    val ownerName: String = "",
    /**
     * Parts that have failed, like a collapsed leg or a torn chute. Kept apart from
     * [activatedParts]: a torn chute is staged and useless, and mustn't look ready to deploy.
     */
    val brokenParts: List<Int> = emptyList(),
    /** Founded: pinned to the ground and immovable. See [World.anchor]. */
    val anchored: Boolean = false,
    /** Burns planned for it, soonest first. */
    val burns: List<PlannedBurn> = emptyList(),
    /** Who sits in each part, by crew id, in part order. Empty with nobody aboard. */
    val crew: List<List<Long>> = emptyList(),
    /**
     * For someone out in a suit, its colours: the stripe their player picked and their own visor,
     * as [com.rm.apogee.core.crew.Crew] numbers them. -1 for anything else.
     */
    val stripe: Int = -1,
    val visor: Int = -1,
)

/** Server to client. */
@Serializable
sealed interface ServerMessage {
    @Serializable
    @SerialName("welcome")
    data class Welcome(
        val protocolVersion: Int,
        val catalogHash: String,
        val serverName: String,
        /** The vessel this client controls, or -1 if there isn't one yet. */
        val controlledVessel: Long = -1,
        /** What the weather is made from, so clients get the same wind and sky. Null for still air. */
        val weather: com.rm.apogee.core.weather.WeatherConfig? = null,
        /** [WorldSave.MODE_CAREER] or [WorldSave.MODE_SANDBOX]. */
        val mode: String = WorldSave.MODE_SANDBOX,
    ) : ServerMessage

    @Serializable
    @SerialName("rejected")
    data class Rejected(val reason: String) : ServerMessage

    @Serializable
    @SerialName("snapshot")
    data class SnapshotMessage(val snapshot: Snapshot) : ServerMessage

    /**
     * Your craft docked with another player's into [vessel]. [other] is their name, and [pilot] who
     * flies it, a client id, or empty for either. Sent again whenever that changes.
     */
    @Serializable
    @SerialName("dockedWith")
    data class DockedWith(val vessel: Long, val other: String, val otherId: String, val pilot: String) : ServerMessage

    @Serializable
    @SerialName("structure")
    data class StructureMessage(val update: StructureUpdate) : ServerMessage

    /** The craft this client flies now, after a launch or a switch. [Welcome] sets the first. */
    @Serializable
    @SerialName("controlChanged")
    data class ControlChanged(val vessel: Long) : ServerMessage

    @Serializable
    @SerialName("chat")
    data class ChatMessage(val from: String, val text: String) : ServerMessage

    /** Scatter that got knocked down: as it happens, and in full to anyone joining. */
    @Serializable
    @SerialName("scatterFelled")
    data class ScatterFelled(val ids: List<Long>) : ServerMessage

    /**
     * What's left in the pilot's tanks: for each part in order, one amount per
     * [com.rm.apogee.core.part.ResourceType]. Sent a few times a second, only to the pilot, so the
     * replica doesn't rebuild with full tanks.
     */
    @Serializable
    @SerialName("fuel")
    data class FuelLevels(val vessel: Long, val amounts: List<Float>) : ServerMessage

    /**
     * The pilot's craft's systems, sent with its tanks: charge and capacity in units, net rate in
     * units a second, [signal] and its relays nearest first, and whether it can be flown.
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
        /** Why it can't be flown ("NO CREW", "NO POWER", "NO SIGNAL"), or blank. */
        val blocked: String = "",
        val needsSignal: Boolean,
        val deployed: Boolean,
        /** The gear switch, and whether it has gear that folds. */
        val gear: Boolean = true,
        val gearFolds: Boolean = false,
        /** On EVA: the craft with a free seat in reach (or blank), and a ladder in reach or held. */
        val boardable: String = "",
        val canGrab: Boolean = false,
        val onLadder: Boolean = false,
        /** Aboard another player's craft, along for the ride and not flying it. */
        val passenger: Boolean = false,
        /** Whether its drills and converters are switched on, and what its drills are doing. */
        val drilling: Boolean = false,
        val refining: Boolean = false,
        val drillState: DrillState = DrillState.OFF,
        /** Its survey of the body it orbits, 0..1, or -1 with no scanner aboard. */
        val survey: Float = -1f,
        /** What the ground right below holds, 0..1, from a scanner low enough, or -1. */
        val ore: Float = -1f,
        val water: Float = -1f,
        /** Ballast 0..1 full (-1 with no tanks), what the tanks are doing, and depth held (m) or -1. */
        val ballast: Float = -1f,
        val ballastMode: Int = 0,
        val holdingDepth: Float = -1f,
        /** How close the sea is to crushing its weakest hollow part. 1 is its limit. */
        val crush: Float = 0f,
        /**
         * Sonar: sea floor below in metres or -1, and the nearest place not yet found as a bearing
         * (degrees) and range (metres, -1 for none).
         */
        val seabed: Float = -1f,
        val findBearing: Float = 0f,
        val findRange: Float = -1f,
        /**
         * Cruise: height above the datum in metres (below 0 when off), heading in degrees north of
         * east, and whether it's allowed.
         */
        val cruiseHeight: Float = -1f,
        val cruiseHeading: Float = 0f,
        val mayCruise: Boolean = true,
        /** Its action groups' states, by group number: 0 left alone, 1 on, -1 off. */
        val groups: List<Int> = emptyList(),
        /** Its light switch, and whether it has any lamps to switch. */
        val lights: com.rm.apogee.core.part.LightMode = com.rm.apogee.core.part.LightMode.OFF,
        val lamps: Boolean = false,
        /**
         * Winch: what it would hook now ("ground", a craft's name, or blank), whether hooked, which
         * way it's winding (1 in, -1 out, 0 holding), and whether the line is pulling.
         */
        val hasWinch: Boolean = false,
        val canHook: String = "",
        val hooked: Boolean = false,
        val reel: Int = 0,
        val taut: Boolean = false,
        /** Whether it has a working keeper core, and whether it's holding station with it. */
        val hasKeeper: Boolean = false,
        val keeping: Boolean = false,
        /** The body-fixed place it's holding, so a client's replica holds the same one. */
        val keepPoint: com.rm.apogee.core.math.SerialVec3 = com.rm.apogee.core.math.Vec3(),
        /** How much air its gas cells' ballonets hold, 0..1. */
        val ballonet: Float = 0f,
        /** What its gas cells lift as a share of its weight, or below 0 with none. */
        val lift: Float = -1f,
        /** Capsized, and small enough and still enough for its crew to roll it back upright. */
        val canRight: Boolean = false,
        /** The craft whose deck its wheels, legs or feet are on, or -1. */
        val standingOn: Long = -1L,
        /** The craft standing on its deck, or asleep there. */
        val riders: List<Long> = emptyList(),
        /** For someone in the water: the craft they could climb out onto, or empty. */
        val climbOnto: String = "",
        /** Someone in the sea, swimming or down on the bottom of it. */
        val swimming: Boolean = false,
        /** How cold someone in the water has got, 0..1. At 1 it's killed them. */
        val chill: Float = 0f,
        /** Why nobody can go outside right now, like too deep for a suit, or empty. */
        val evaBlocked: String = "",
    ) : ServerMessage

    /** A player's crew (home, aboard, memorial), sent on joining and whenever it changes. */
    @Serializable
    @SerialName("roster")
    data class Roster(val members: List<com.rm.apogee.core.crew.CrewMember>) : ServerMessage

    /** A player's career, sent on joining and whenever it changes, plus everyone's world firsts. */
    @Serializable
    @SerialName("career")
    data class Career(
        val state: com.rm.apogee.core.career.CareerState,
        val firsts: List<com.rm.apogee.core.career.WorldFirst> = emptyList(),
    ) : ServerMessage

    /** The named places under the sea this player has found, by id. The rest stay hidden. */
    @Serializable
    @SerialName("wonders-found")
    data class WondersFound(val ids: List<String>) : ServerMessage

    /** A feat or a visit just credited to this player. [grade] is blank for an ungraded one. */
    @Serializable
    @SerialName("feat")
    data class Feat(val title: String, val grade: String, val insight: Int) : ServerMessage

    /** A launch or an unlock the career wouldn't allow, and why. */
    @Serializable
    @SerialName("careerRefused")
    data class CareerRefused(val reason: String) : ServerMessage

    /** Every body surveyed for ore and water, sent on joining and whenever the list grows. */
    @Serializable
    @SerialName("surveyed")
    data class Surveyed(val bodies: List<String>) : ServerMessage

    /**
     * What the pilot's craft can do with a base, sent with its tanks: be founded or let go, be
     * filled, whether it's filling, and why it last stopped.
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
        /** Whether it has ore or water to empty into what it's docked to or standing on, and is. */
        val canUnload: Boolean = false,
        val unloading: Boolean = false,
    ) : ServerMessage

    /** A founded base near the pilot, or the one they're flying, as its card shows it. */
    @Serializable
    @SerialName("base")
    data class BaseStatus(
        val vessel: Long,
        val name: String,
        val powered: Boolean,
        val charge: Float,
        val chargeCapacity: Float,
        /** Charge coming in minus going out, per second. */
        val net: Float,
        val propellant: Float,
        val propellantCapacity: Float,
        val monopropellant: Float,
        val monopropellantCapacity: Float,
        /** The launch pads it has. */
        val pads: Int,
        /** How far away it is, in metres. */
        val distance: Float,
        val ore: Float = 0f,
        val oreCapacity: Float = 0f,
        val water: Float = 0f,
        val waterCapacity: Float = 0f,
        /** Whether it has a refinery and whether that's switched on, and its drills, if it has any. */
        val hasRefinery: Boolean = false,
        val refining: Boolean = false,
        val drilling: Boolean = false,
    ) : ServerMessage

    /**
     * Lightning struck [vessel], knocking out [partIndex] (or -1). Strikes that hit nothing aren't
     * sent; clients work those out from the weather.
     */
    @Serializable
    @SerialName("lightning")
    data class Lightning(val strikeId: Long, val vessel: Long, val partIndex: Int) : ServerMessage

    /**
     * Something worth seeing and hearing happened to a part. Sent by part id and position, since the
     * craft may have changed shape by the time it arrives. [amount] is impact speed in m/s, or
     * propellant in kg for an explosion.
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
        /** Universe time it happened, for which way the planet was turned. */
        val time: Double = 0.0,
    ) : ServerMessage
}

@Serializable
enum class PartEventKind {
    @SerialName("impact") IMPACT,
    @SerialName("destroyed") DESTROYED,
    @SerialName("detached") DETACHED,
    @SerialName("explosion") EXPLOSION,
    /** Two craft latched ring to ring or clamped. [ServerMessage.PartEvent.vessel] is what they became. */
    @SerialName("docked") DOCKED,
    @SerialName("undocked") UNDOCKED,
    @SerialName("hitched") HITCHED,
    @SerialName("unhitched") UNHITCHED,
    /** A winch hooked on, let go, or snapped. */
    @SerialName("hooked") HOOKED,
    @SerialName("unhooked") UNHOOKED,
    @SerialName("snapped") SNAPPED,
}

/** Client to server. */
@Serializable
sealed interface ClientMessage {
    @Serializable
    @SerialName("hello")
    data class Hello(
        val protocolVersion: Int,
        val catalogHash: String,
        /** Cosmetic: what to show next to this player's craft and in chat. */
        val playerName: String,
        /** Generated once per install. Decides which craft are whose; [playerName] doesn't. */
        val clientId: String,
        /**
         * [com.rm.apogee.core.terrain.TerrainField.GENERATION]. Builds with different ground can't
         * share a world.
         */
        val terrainGeneration: Int = 0,
        /**
         * [com.rm.apogee.core.orbit.SolarSystem.contentHash]. Builds with different worlds can't
         * share a game.
         */
        val systemHash: String = "",
        /** The stripe this player's crew wear, or -1 to leave it to the server. See [com.rm.apogee.core.crew.Crew.stripeFor]. */
        val stripe: Int = -1,
    ) : ClientMessage

    @Serializable
    @SerialName("command")
    data class CommandMessage(val command: Command) : ClientMessage
}

object Protocol {
    /**
     * Goes up whenever the network format changes in a way older builds can't read. Checked in the
     * handshake alongside the part catalogue hash, which can drift on its own.
     */
    // 3: CraftDesign.orientation, Command.SetBrakes.
    // 4: ServerMessage.ScatterFelled, Hello.terrainGeneration.
    // 5: VesselKinematics.pose.
    // 6: ServerMessage.FuelLevels, CraftDesign.manualStaging.
    // 7: Welcome.weather, ServerMessage.Lightning.
    // 8: VesselKinematics.condition, ServerMessage.PartEvent, the ablator,
    //    engine output in the pose, time warp, removing craft.
    // 9: docking: Undock, SetDockPilot, DockedWith, Snapshot.hitches.
    // 10: PlacedPart.turn.
    // 11: the sea: flooding in VesselCondition, open hulls.
    // 12: bases: Anchor, Refuel, StructureUpdate.anchored, Service, BaseStatus, launching from base pads.
    // 13: navigation: PlanBurns, SetAutopilot, WarpTo, targeting bodies.
    // 14: craft systems: Deploy, CraftSystems.
    // 15: resources: SetIndustry, Unload, Surveyed, CraftSystems industry and readings, BaseStatus stores.
    // 16: crew: Roster, seats in StructureUpdate, EVA and boarding commands.
    // 17: the wider system: Hello.systemHash; every world, tilted, round a real sun.
    // 18: career: Welcome.mode, Career, Feat, CareerRefused, Command.Unlock, feats watched per player.
    // 19: play comfort: SetFlaps, SetCruise, ToggleGroup, the winch, PlacedPart.group, sails and
    //     flaps in the pose, winch lines in the snapshot, the new CraftSystems fields.
    // 20: rotors and lighter than air: SetStationKeep, the keeper, lift and ballonet in
    //     CraftSystems.
    // 21: suit colours: Hello.stripe, StructureUpdate.stripe and visor.
    // 22: rotor speeds in the pose.
    // 23: PlacedPart.shroud; new parts (side chutes and decouplers, 3.75 m, the structural kit).
    // 24: righting a capsized craft: RightCraft, CraftSystems.canRight, standingOn and riders.
    // 25: crew in the water: ClimbOut, CraftSystems.climbOnto, swimming, chill and evaBlocked.
    // 26: VesselKinematics.asleep.
    // 27: VesselKinematics.centreOfMass.
    // 28: places as targets, SetTarget's body "place:" and an id.
    // 29: the light switch: SetLights, CraftSystems.lights and lamps, VesselKinematics.lit.
    // 30: the gear switch: SetGear, CraftSystems.gear and gearFolds; legs no longer stage.
    const val VERSION = 30
}
