package com.rm.apogee.render

import com.rm.apogee.render.gl.GLES30

/**
 * How much the renderer is allowed to try on this device.
 *
 * This exists because minSdk is 27. Keeping the floor that low is a deliberate choice about reach,
 * but it lets in 2017-era hardware that can't run a continuous 6-DOF physics sandbox at full
 * detail, so the low end is a real, tested setup and not just a hope. Detection picks a starting
 * tier. The player can override it in Settings, and M3's acceptance pass includes forcing [LOW].
 */
enum class QualityTier {
    LOW,
    MEDIUM,
    HIGH;

    /** A hard cap on parts per vessel before the builder refuses any more. */
    val maxPartsPerVessel: Int
        get() = when (this) {
            LOW -> 60
            MEDIUM -> 150
            HIGH -> 400
        }

    /**
     * Terrain chunks kept on the GPU. Each one is about fifteen kilobytes of vertices. The working
     * set on the ground is one to three hundred, and the rest of the budget is ground you've
     * recently driven over, kept so turning round doesn't rebuild it. How fine the ground is drawn
     * is up to the terrain builder, per tier.
     */
    val terrainChunkBudget: Int
        get() = when (this) {
            LOW -> 220
            MEDIUM -> 320
            HIGH -> 450
        }

    /** Particles at once, across all effects. */
    val particleBudget: Int
        get() = when (this) {
            LOW -> 256
            MEDIUM -> 2_048
            HIGH -> 8_192
        }

    val shadowsEnabled: Boolean get() = this == HIGH

    companion object
}
