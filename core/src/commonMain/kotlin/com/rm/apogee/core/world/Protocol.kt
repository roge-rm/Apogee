package com.rm.apogee.core.world

import com.rm.apogee.core.craft.CraftDesign
import com.rm.apogee.core.math.SerialQuat
import com.rm.apogee.core.math.SerialVec3
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * Something a player asks the world to do.
 *
 * Commands go from client to server and get applied at a tick boundary, never in the middle of a
 * step. Applying them mid-step would make the result depend on where in the vessel loop the command
 * landed, which is exactly the kind of ordering problem that makes a replay stop matching.
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

    /** Another craft to steer by, or -1 to clear. */
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

    /**
     * Welds this craft to whatever it's resting against.
     *
     * With no target, the world picks the nearest craft that's actually touching. A player pushing
     * one module up against another shouldn't also have to point out which one, and on a touch
     * screen there's nothing sensible to tap.
     */
    @Serializable
    @SerialName("join")
    data class Join(val vessel: Long) : Command

    /**
     * Pins this craft to the ground where it rests, founding a base, or with [anchored] false, lets
     * it go again. Only a craft with a working foundation, at rest. The world checks.
     */
    @Serializable
    @SerialName("anchor")
    data class Anchor(val vessel: Long, val anchored: Boolean) : Command

    /**
     * Fill this craft from the base whose pad it stands on, or that it's docked to: propellant,
     * monopropellant and charge, as far as the base has them and has the power to pump. Or with
     * [active] false, stop.
     */
    @Serializable
    @SerialName("refuel")
    data class Refuel(val vessel: Long, val active: Boolean) : Command

    /**
     * Two players' craft are docked, and this says who flies the combined craft [vessel]. [pilot]
     * is a player's client id, or empty for either of them.
     */
    @Serializable
    @SerialName("setDockPilot")
    data class SetDockPilot(val vessel: Long, val pilot: String) : Command

    /** Let go at docking part [part] of [vessel]: undock a ring or clamp, or uncouple a hitch. */
    @Serializable
    @SerialName("undock")
    data class Undock(val vessel: Long, val part: Int) : Command

    /**
     * Fly a different craft.
     *
     * This is the partner to launching. In a world you leave things in, the craft you want is
     * usually not the one you're in. Only craft the player owns can be switched to, and the server
     * checks that.
     */
    @Serializable
    @SerialName("switchVessel")
    data class SwitchVessel(val vessel: Long) : Command

    @Serializable
    @SerialName("chat")
    data class Chat(val text: String) : Command

    /**
     * How fast the world runs, as a multiple of real time, with 0 to pause. It's only honoured by a
     * server that allows it, with nobody else on it, because one player can't stop or speed up
     * everyone else's world.
     */
    @Serializable
    @SerialName("setWarp")
    data class SetWarp(val rate: Double) : Command

    /**
     * As fast as the world allows until universe [time], then back to real time, for example to
     * reach a planned burn. Honoured wherever [SetWarp] is.
     */
    @Serializable
    @SerialName("warpTo")
    data class WarpTo(val time: Double) : Command

    /**
     * Takes one of the player's own craft out of the world for good, either from the craft list or
     * by retiring the one being flown.
     */
    @Serializable
    @SerialName("removeVessel")
    data class RemoveVessel(val vessel: Long) : Command

    /** In a career, spend insight on tech node [node]. */
    @Serializable
    @SerialName("unlock")
    data class Unlock(val node: String) : Command
}

/**
 * One vessel's motion at a tick.
 *
 * It's small and sent constantly (20 Hz for each vessel in range), which is why it only carries
 * what changes all the time. Structure travels separately as [StructureUpdate], because a craft's
 * part list changes a handful of times per flight, and repeating it 20 times a second would eat the
 * bandwidth for no reason.
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
) {
    // Compared by content, because an array compares by identity, and two snapshots of the same
    // craft are equal whether or not they share one.
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
 * A vessel's structure appearing, changing or going away.
 *
 * Sent on spawn, on decouple, and on destruction. [design] is null when the vessel is just gone.
 */
@Serializable
data class StructureUpdate(
    val vessel: Long,
    val design: CraftDesign? = null,
    val name: String = "",
    /** Which stage the craft is on, so a client joining sees the right state. */
    val currentStage: Int = 0,
    val activatedParts: List<Int> = emptyList(),
    /**
     * Who this craft belongs to, or blank for debris.
     *
     * It's sent so the client can tell which craft are the player's own, and offer to switch
     * between them without asking the server first.
     */
    val owner: String = "",
    /** The owner's display name, for labels and chat. Never matched on. */
    val ownerName: String = "",
    /**
     * Parts that have failed, like a collapsed leg or a torn chute.
     *
     * It's carried alongside [activatedParts] instead of removing them from it, because they really
     * are different states. A torn parachute is staged *and* useless, and mustn't look un-staged,
     * or the client would show it as still available to deploy.
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
        /**
         * What the world's weather is made from. That's all the client needs to work out the same
         * wind for its replica and the same sky to draw. Null for still air.
         */
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
     * Your craft docked with another player's, and [vessel] is both of them now. [other] is their
     * name, and [pilot] is who flies it, a client id, or empty for either of you. Sent again
     * whenever that changes.
     */
    @Serializable
    @SerialName("dockedWith")
    data class DockedWith(val vessel: Long, val other: String, val otherId: String, val pilot: String) : ServerMessage

    @Serializable
    @SerialName("structure")
    data class StructureMessage(val update: StructureUpdate) : ServerMessage

    /**
     * The craft this client is flying now.
     *
     * Control gets settled once at the handshake in [Welcome], but it moves afterwards, when you
     * launch a new craft or switch to one that's already parked. Without this the client would keep
     * sending commands naming a craft the server no longer links to it, and every one would be
     * refused.
     */
    @Serializable
    @SerialName("controlChanged")
    data class ControlChanged(val vessel: Long) : ServerMessage

    @Serializable
    @SerialName("chat")
    data class ChatMessage(val from: String, val text: String) : ServerMessage

    /**
     * Scatter that got knocked down. It's sent as it happens, and in full to anyone joining, so
     * every player's forest has the same gaps in it.
     */
    @Serializable
    @SerialName("scatterFelled")
    data class ScatterFelled(val ids: List<Long>) : ServerMessage

    /**
     * What's left in the tanks of the craft this client flies: for each part in order, one amount
     * per [com.rm.apogee.core.part.ResourceType].
     *
     * It's sent a few times a second, and only to the pilot, because nobody else's HUD shows
     * another craft's fuel. Without it the client's replica started every rebuild with full tanks,
     * so a craft resumed half empty read full and predicted thrust the server's craft didn't have
     * any more.
     */
    @Serializable
    @SerialName("fuel")
    data class FuelLevels(val vessel: Long, val amounts: List<Float>) : ServerMessage

    /**
     * The pilot's craft's power and link home, sent with its tanks: its charge and how much it
     * holds, in units; the net rate, in units a second; whether it has power; its [signal] and the
     * relays carrying it, nearest first; whether it can be flown at all; and whether its fold-outs
     * have been told to come out.
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
        /**
         * Someone on EVA: the craft with a free seat in reach (blank for none), whether a ladder is
         * in reach, and whether they're holding one.
         */
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
        /**
         * What the ground right below holds, 0..1, read by a scanner that's low enough. -1 when
         * there's no reading.
         */
        val ore: Float = -1f,
        val water: Float = -1f,
        /**
         * Its ballast, 0..1 full, or -1 with no tanks, what the tanks are doing, and the depth
         * being held in metres, or -1.
         */
        val ballast: Float = -1f,
        val ballastMode: Int = 0,
        val holdingDepth: Float = -1f,
        /** How close the sea is to crushing its weakest hollow part. 1 is its limit. */
        val crush: Float = 0f,
        /**
         * Its sonar's reading: the sea floor below in metres, or -1, and the nearest place that
         * hasn't been found yet, as a bearing (degrees) and range (metres), with range -1 for none.
         */
        val seabed: Float = -1f,
        val findBearing: Float = 0f,
        val findRange: Float = -1f,
        /**
         * Holding its height and heading: the height above the datum in metres, and the heading in
         * degrees north of east. Height below 0 means it isn't. And whether it's allowed to at all.
         */
        val cruiseHeight: Float = -1f,
        val cruiseHeading: Float = 0f,
        val mayCruise: Boolean = true,
        /** Its action groups' states, by group number: 0 left alone, 1 on, -1 off. */
        val groups: List<Int> = emptyList(),
        /**
         * Its winch, if it has one: what it would hook onto now ("ground", a craft's name, or blank
         * for nothing in reach), whether it's hooked, which way it's winding (1 in, -1 out, 0
         * holding), and whether the line is pulling.
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
    ) : ServerMessage

    /**
     * A player's crew (at home, aboard, and on the memorial), sent to them when they join and
     * whenever it changes.
     */
    @Serializable
    @SerialName("roster")
    data class Roster(val members: List<com.rm.apogee.core.crew.CrewMember>) : ServerMessage

    /**
     * A player's career, sent to them when they join and whenever it changes, plus the world
     * firsts, which belong to everyone.
     */
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

    /**
     * The bodies surveyed for ore and water: all of them, whenever the list grows, and when
     * joining.
     */
    @Serializable
    @SerialName("surveyed")
    data class Surveyed(val bodies: List<String>) : ServerMessage

    /**
     * What the pilot's craft can do with a base right now, sent with its tanks: be founded where it
     * rests, or let go if it already is, and be filled from the base it stands on or is docked to,
     * plus whether it is being filled, or why it last stopped.
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
        /**
         * Whether it has ore or water to empty into whatever it's docked to or standing on, and
         * whether it's doing so.
         */
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
     * Lightning struck [vessel], knocking out [partIndex] (or nothing, -1). Strikes that hit
     * nothing aren't sent, because every client works those out from the weather itself.
     */
    @Serializable
    @SerialName("lightning")
    data class Lightning(val strikeId: Long, val vessel: Long, val partIndex: Int) : ServerMessage

    /**
     * Something happened to a part that's worth seeing and hearing: a blow, a part destroyed or
     * torn off, or a tank going up. It goes by part id and position instead of just the index,
     * because by the time it arrives the craft it happened to might already be a different shape.
     * [amount] is the impact speed in m/s for an impact, and the propellant in kg for an explosion.
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
        /** The universe time it happened, which says which way the planet was turned. */
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
        /**
         * Stable and opaque, generated once per install and never typed by anyone. This is what
         * decides which craft belong to whom. [playerName] decides nothing.
         */
        val clientId: String,
        /**
         * [com.rm.apogee.core.terrain.TerrainField.GENERATION]. Two builds with different ground
         * can't share a world, because each would collide craft against its own idea of the
         * surface.
         */
        val terrainGeneration: Int = 0,
        /**
         * [com.rm.apogee.core.orbit.SolarSystem.contentHash]: the worlds, their orbits, air and
         * weather. Two builds with different worlds can't share a game.
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
     * Goes up whenever the network format changes in a way older builds can't read. It's checked
     * alongside the part catalogue hash during the handshake, because the two can drift apart
     * separately. A matching protocol with a mismatched catalogue is just as broken, and much
     * harder to track down from the symptoms.
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
    // 17: the wider system - Hello.systemHash; every world, tilted, round a real sun.
    // 18: career - Welcome.mode, Career, Feat, CareerRefused, Command.Unlock, feats watched per player.
    // 19: play comfort - SetFlaps, SetCruise, ToggleGroup, the winch, PlacedPart.group, sails and
    //     flaps in the pose, winch lines in the snapshot, the new CraftSystems fields.
    // 20: rotors and lighter than air - SetStationKeep, the keeper, lift and ballonet in
    //     CraftSystems.
    // 21: suit colours - Hello.stripe, StructureUpdate.stripe and visor.
    // 22: rotor speeds in the pose.
    const val VERSION = 22
}
