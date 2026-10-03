package com.rm.apogee.core.world

import com.rm.apogee.core.craft.CraftDesign
import com.rm.apogee.core.math.SerialQuat
import com.rm.apogee.core.math.SerialVec3
import com.rm.apogee.core.part.ResourceType
import kotlinx.serialization.Serializable

/** One craft, as it's written to disk. */
@Serializable
class VesselSave(
    val id: Long,
    val name: String,
    /**
     * Who this belongs to, or blank for debris and abandoned craft. A client id since format 2; a
     * display name in format 1 (see the migration in [World.restore]).
     */
    val owner: String = "",
    /** The owner's display name. Cosmetic, and never matched on. */
    val ownerName: String = "",
    val design: CraftDesign,
    val referenceBodyId: String,
    val position: SerialVec3,
    val rotation: SerialQuat,
    val velocity: SerialVec3,
    val angularVelocity: SerialVec3,
    val currentStage: Int = 0,
    val activatedParts: List<Int> = emptyList(),
    /** Parts that have failed. Damage survives a restart. */
    val brokenParts: List<Int> = emptyList(),
    /**
     * The throttle setting, 0..1. Saved because it's a lever left set; pitch, yaw and roll are a
     * held stick, so they aren't.
     */
    val throttle: Double = 0.0,
    /** Stability assist. A mode, so it's kept. */
    val sasEnabled: Boolean = false,
    val sasMode: SasMode = SasMode.HOLD,
    val navFrame: NavFrame = NavFrame.AUTO,
    val target: Long = -1L,
    /** A body targeted instead of a craft, by id. Blank for none. */
    val targetBody: String = "",
    /** Burns planned for it, soonest first. */
    val burns: List<PlannedBurn> = emptyList(),
    /** Wheel brakes, so a parked rover stays parked. */
    val brakes: Boolean = false,
    /** The light switch. */
    val lights: com.rm.apogee.core.part.LightMode = com.rm.apogee.core.part.LightMode.OFF,
    /** Sun wings and dishes told to fold out. */
    val deployed: Boolean = false,
    /** Fuel cells running. They cut out once it's well charged. */
    val fuelCellsOn: Boolean = false,
    /** Drills and converters switched on, so a base mines and refines while nobody's there. */
    val drilling: Boolean = false,
    /** Its action groups' states, by group number, or empty for all left alone. */
    val groups: List<Int> = emptyList(),
    val refining: Boolean = false,
    /** Ballast flooding or blowing, and a depth being held. */
    val ballast: Int = 0,
    val holdDepth: Boolean = false,
    val holdDepthAt: Double = 0.0,
    /**
     * Holding station with its keeper core: the body-fixed place held and the throttle it found it
     * needs. Then how much air its ballonets hold, 0..1.
     */
    val keeping: Boolean = false,
    val keepPoint: com.rm.apogee.core.math.SerialVec3 = com.rm.apogee.core.math.Vec3(),
    val keepTrim: Double = 0.0,
    val ballonet: Double = 0.0,
    /** Who sits in each part, by crew id, in part order. */
    val crew: List<List<Long>> = emptyList(),
    /** A survey in progress: which body, and how many seconds of it are done. */
    val surveyBody: String = "",
    val surveyProgress: Double = 0.0,
    /** Landing leg deploy progress per part, in part order. Empty in older saves; staged legs load out. */
    val legDeploy: List<Double> = emptyList(),
    /**
     * How fast each rotor and propeller was turning, 0..1, in part order, so a hovering craft loads
     * hovering. Empty in older saves, which start at rest.
     */
    val spool: List<Double> = emptyList(),
    /**
     * Resource levels per part, in part order, each ordered by [ResourceType]. By position to keep
     * autosaves small; a test pins the enum order.
     */
    val resources: List<List<Double>> = emptyList(),
    /**
     * How hurt, dented and hot each part is, in part order, three dent values per part. Empty in
     * older saves: whole, straight and at a mild day's temperature.
     */
    val health: List<Double> = emptyList(),
    /** Water that has got into each part, in kg. Empty for a dry craft. */
    val flooded: List<Double> = emptyList(),
    val crumple: List<Float> = emptyList(),
    val temperature: List<Double> = emptyList(),
    /** Founded: pinned to the ground where it stands. See [World.anchor]. */
    val anchored: Boolean = false,
    /** Founded afloat, so it rides the sea. */
    val afloat: Boolean = false,
    /** What it has done this flight, for a career. Null outside one. */
    val log: com.rm.apogee.core.career.FlightLog? = null,
)

/** A whole world, as it's written to disk: JSON, so an operator can read and edit it by hand. */
@Serializable
class WorldSave(
    val formatVersion: Int = FORMAT_VERSION,
    /** The catalogue this world was built with. A mismatch is reported, not worked around. */
    val catalogHash: String,
    val universeTime: Double,
    val nextVesselId: Long,
    val vessels: List<VesselSave> = emptyList(),
    /** Trees and shrubs knocked down. They stay down across a restart. */
    val felledScatter: List<Long> = emptyList(),
    /** Bodies surveyed for ore and water, by id. */
    val surveyed: List<String> = emptyList(),
    /** Everyone who has flown: at home, aboard, or on the memorial. */
    val crew: List<com.rm.apogee.core.crew.CrewMember> = emptyList(),
    /** Whether craft carry their crew. False in older saves, and every seat is filled on loading. */
    val crewSeated: Boolean = false,
    /**
     * [com.rm.apogee.core.terrain.TerrainField.GENERATION] when it was saved; missing (1) before
     * 0.3.0. [World.restore] sets craft back down on the ground when it differs.
     */
    val terrainGeneration: Int = 1,
    /** Owner id to the vessel id they last flew. See [World.lastFlown]. */
    val lastFlown: Map<String, Long> = emptyMap(),
    /** Owner id to the stripe their crew wear. See [World.stripes]. */
    val stripes: Map<String, Int> = emptyMap(),
    /** Owner id to the named places under the sea they've found. See [World.wondersFound]. */
    val wondersFound: Map<String, List<String>> = emptyMap(),
    /** What the weather is made from. Missing in older saves, and the server picks its default. */
    val weather: com.rm.apogee.core.weather.WeatherConfig? = null,
    /** Craft towing others: each hitch coupled to its ball. */
    val links: List<SavedLink> = emptyList(),
    /** Winch lines out. */
    val lines: List<SavedLine> = emptyList(),
    /** [MODE_CAREER] or [MODE_SANDBOX]. Missing means a sandbox. */
    val mode: String = MODE_SANDBOX,
    /** Every player's career, in a career world. */
    val careers: List<com.rm.apogee.core.career.CareerState> = emptyList(),
    /** Who got where first. */
    val firsts: List<com.rm.apogee.core.career.WorldFirst> = emptyList(),
) {
    companion object {
        /** Goes up when the shape changes in a way older builds can't read. */
        const val FORMAT_VERSION = 2

        const val MODE_SANDBOX = "sandbox"
        const val MODE_CAREER = "career"

        /** The number of resource slots each part records. Pinned by a test. */
        val RESOURCE_SLOTS = ResourceType.entries.size
    }
}

/** A shortcut for building an empty save of a fresh world. */
fun emptyWorldSave(catalogHash: String) = WorldSave(
    catalogHash = catalogHash,
    universeTime = 0.0,
    nextVesselId = 1L,
    vessels = emptyList(),
)

/** A tow hitch coupled up: part [partA] of craft [vesselA] to part [partB] of [vesselB]. */
@Serializable
data class SavedLink(val vesselA: Long, val partA: Int, val vesselB: Long, val partB: Int)

/**
 * A winch line from winch [partA] on [vesselA] to [hook] on part [partB] of [vesselB] (in that
 * part's axes), or with [vesselB] -1, to body-fixed [ground] on [bodyId]. [length] is line out in
 * metres; [reel] is 1 in, -1 out, 0 holding; [taut] is whether it's pulling.
 */
@Serializable
data class SavedLine(
    val vesselA: Long,
    val partA: Int,
    val vesselB: Long,
    val partB: Int,
    val hook: com.rm.apogee.core.math.SerialVec3,
    val ground: com.rm.apogee.core.math.SerialVec3,
    val bodyId: String,
    val length: Double,
    val reel: Int = 0,
    val taut: Boolean = false,
)
