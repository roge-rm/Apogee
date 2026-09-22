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
    /** Player this belongs to, or blank for debris. */
    val owner: String = "",
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
    /**
     * Resource levels per part, in part order, each entry ordered by
     * [ResourceType].
     *
     * Stored positionally rather than as named maps because it is written for
     * every craft on every autosave; the ordering is pinned by a test so a
     * reordered enum cannot silently reinterpret everyone's fuel.
     */
    val resources: List<List<Double>> = emptyList(),
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
) {
    companion object {
        /**
         * Bumped when the shape changes incompatibly.
         *
         * An operator who upgrades a server should be told their world cannot
         * be read, not have it quietly half-loaded.
         */
        const val FORMAT_VERSION = 1

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
