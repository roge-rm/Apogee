package com.rm.apogee.core.world

import com.rm.apogee.core.craft.CraftDesign
import com.rm.apogee.core.math.SerialQuat
import com.rm.apogee.core.math.SerialVec3
import com.rm.apogee.core.part.ResourceType
import kotlinx.serialization.Serializable

/** One craft, as it is written to disk. */
@Serializable
class VesselSave(
    val id: Long,
    val name: String,
    /**
     * Who this belongs to, or blank for debris and abandoned craft.
     *
     * An opaque client id since format 2. In format 1 it held a display name,
     * which is not an identity - see the migration in [World.restore].
     */
    val owner: String = "",
    /** The owner's display name. Cosmetic; never matched on. */
    val ownerName: String = "",
    val design: CraftDesign,
    val referenceBodyId: String,
    val position: SerialVec3,
    val rotation: SerialQuat,
    val velocity: SerialVec3,
    val angularVelocity: SerialVec3,
    val currentStage: Int = 0,
    val activatedParts: List<Int> = emptyList(),
    /** Parts that have failed. Damage persists across a restart. */
    val brokenParts: List<Int> = emptyList(),
    /**
     * Throttle setting, 0..1.
     *
     * Saved, unlike pitch/yaw/roll. A throttle is a lever the pilot *set* and
     * left: a craft under power when the server stopped is still under power
     * when it comes back, and reloading at zero throttle would silently
     * strand a burn. Attitude input is the opposite - it is a stick being
     * held, and resuming a held stick across a restart would have the craft
     * quietly rotating with nobody touching it.
     */
    val throttle: Double = 0.0,
    /** Stability assist. A mode, like the throttle, so it persists. */
    val sasEnabled: Boolean = false,
    val sasMode: SasMode = SasMode.HOLD,
    val navFrame: NavFrame = NavFrame.AUTO,
    val target: Long = -1L,
    /** A body targeted instead of a craft, by id; blank for none. */
    val targetBody: String = "",
    /** Burns planned for it, soonest first. */
    val burns: List<PlannedBurn> = emptyList(),
    /** Wheel brakes. A parked rover has to still be parked when it is reloaded. */
    val brakes: Boolean = false,
    /** Sun wings and dishes told to fold out. */
    val deployed: Boolean = false,
    /** Fuel cells running: they cut out again only well charged. */
    val fuelCellsOn: Boolean = false,
    /** Drills and converters switched on: a base mines and refines while nobody is there. */
    val drilling: Boolean = false,
    val refining: Boolean = false,
    /** Ballast flooding or blowing, and a depth held. */
    val ballast: Int = 0,
    val holdDepth: Boolean = false,
    val holdDepthAt: Double = 0.0,
    /** Who sits in each part, by crew id, in part order. */
    val crew: List<List<Long>> = emptyList(),
    /** A survey under way: of which body, and how many seconds of it are done. */
    val surveyBody: String = "",
    val surveyProgress: Double = 0.0,
    /**
     * Landing-leg deploy progress per part, in part order. Empty in saves
     * from before legs deployed over time: legs then start deployed if they
     * were staged, which is what they were.
     */
    val legDeploy: List<Double> = emptyList(),
    /**
     * Resource levels per part, in part order, each entry ordered by
     * [ResourceType].
     *
     * Stored positionally rather than as named maps because it is written for
     * every craft on every autosave; the ordering is pinned by a test so a
     * reordered enum cannot silently reinterpret everyone's fuel.
     */
    val resources: List<List<Double>> = emptyList(),
    /**
     * How hurt, dented and hot each part is, in part order - dents three to
     * a part. Empty in saves from before damage: whole, straight and at a
     * mild day's temperature.
     */
    val health: List<Double> = emptyList(),
    /** Water shipped into each part, kg; empty for a dry craft. */
    val flooded: List<Double> = emptyList(),
    val crumple: List<Float> = emptyList(),
    val temperature: List<Double> = emptyList(),
    /** Founded: pinned to the ground where it stands. See [World.anchor]. */
    val anchored: Boolean = false,
    /** What it has done this flight, for a career; null outside one. */
    val log: com.rm.apogee.core.career.FlightLog? = null,
)

/**
 * A whole world, as it is written to disk.
 *
 * The format is JSON from the same `@Serializable` types the game already
 * uses, so a save file is something an operator can read, diff and hand-edit
 * when a craft gets stuck somewhere it should not be.
 */
@Serializable
class WorldSave(
    val formatVersion: Int = FORMAT_VERSION,
    /**
     * The catalogue this world was built with.
     *
     * Loading a world whose parts no longer exist would silently drop craft, so
     * a mismatch is reported rather than worked around.
     */
    val catalogHash: String,
    val universeTime: Double,
    val nextVesselId: Long,
    val vessels: List<VesselSave> = emptyList(),
    /** Trees and shrubs knocked down. A felled tree stays felled across a restart. */
    val felledScatter: List<Long> = emptyList(),
    /** Bodies surveyed for ore and water, by id. */
    val surveyed: List<String> = emptyList(),
    /** Everyone who has flown: at home, aboard, or on the memorial. */
    val crew: List<com.rm.apogee.core.crew.CrewMember> = emptyList(),
    /**
     * Whether craft carry their crew in this save. False in saves from before
     * there were crew: every seat is filled on loading, or every craft would
     * be an empty pod nobody could fly.
     */
    val crewSeated: Boolean = false,
    /**
     * Which terrain this world's craft are standing on:
     * [com.rm.apogee.core.terrain.TerrainField.GENERATION] when it was saved.
     *
     * Absent from saves older than 0.3.0, which were all generation 1. A craft
     * parked on one generation's ground is buried or floating on the next, so
     * [World.restore] sets it back down when the two differ.
     */
    val terrainGeneration: Int = 1,
    /** Owner id to the vessel id they last flew; see [World.lastFlown]. */
    val lastFlown: Map<String, Long> = emptyMap(),
    /** Owner id to the sea's named places they have found; see [World.wondersFound]. */
    val wondersFound: Map<String, List<String>> = emptyMap(),
    /**
     * What the weather is made from. Absent in saves from before there was
     * any: the server then gives the world its own default.
     */
    val weather: com.rm.apogee.core.weather.WeatherConfig? = null,
    /** Craft towing others: each hitch coupled to its ball. */
    val links: List<SavedLink> = emptyList(),
    /** [MODE_CAREER] or [MODE_SANDBOX]: absent, a sandbox, as every world was before careers. */
    val mode: String = MODE_SANDBOX,
    /** Every player's career, in a career world. */
    val careers: List<com.rm.apogee.core.career.CareerState> = emptyList(),
    /** Who got where first. */
    val firsts: List<com.rm.apogee.core.career.WorldFirst> = emptyList(),
) {
    companion object {
        /**
         * Bumped when the shape changes incompatibly.
         *
         * An operator who upgrades a server should be told their world cannot
         * be read, not have it quietly half-loaded.
         */
        const val FORMAT_VERSION = 2

        const val MODE_SANDBOX = "sandbox"
        const val MODE_CAREER = "career"

        /** Number of resource slots each part records. Pinned by a test. */
        val RESOURCE_SLOTS = ResourceType.entries.size
    }
}

/** Convenience for building an empty save of a fresh world. */
fun emptyWorldSave(catalogHash: String) = WorldSave(
    catalogHash = catalogHash,
    universeTime = 0.0,
    nextVesselId = 1L,
    vessels = emptyList(),
)

/** A tow hitch coupled up: part [partA] of craft [vesselA] to part [partB] of [vesselB]. */
@Serializable
data class SavedLink(val vesselA: Long, val partA: Int, val vesselB: Long, val partB: Int)
