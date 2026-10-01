package com.rm.apogee.render

import com.rm.apogee.render.gl.GLES30

/**
 * How much the renderer may try on this device. minSdk 27 lets in old hardware, so [LOW] is a
 * real, tested setup. Detection picks a starting tier; the player can change it in Settings.
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
     * Terrain chunks kept on the GPU, about 15 KB each. The working set on the ground is 100 to
     * 300; the rest holds ground you've just left, so turning round doesn't rebuild it.
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
