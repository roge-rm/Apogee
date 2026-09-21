package com.rm.apogee.render

import android.app.ActivityManager
import android.content.Context
import android.opengl.GLES30
import android.os.Build
import android.util.Log

/**
 * How much the renderer is allowed to attempt on this device.
 *
 * This exists because minSdk is 27. Holding the floor that low is a deliberate
 * reach decision, but it admits 2017-era hardware that cannot run a continuous
 * 6-DOF physics sandbox at full detail, so the low end is a real, tested
 * configuration rather than a hope. [detect] picks a starting tier; the player
 * can override it in Settings, and M3's acceptance pass includes forcing [LOW].
 */
enum class QualityTier {
    LOW,
    MEDIUM,
    HIGH;

    /** Hard cap on parts per vessel before the builder refuses more. */
    val maxPartsPerVessel: Int
        get() = when (this) {
            LOW -> 60
            MEDIUM -> 150
            HIGH -> 400
        }

    /** Depth of the planet surface quadtree; each level doubles linear detail. */
    val terrainLodDepth: Int
        get() = when (this) {
            LOW -> 6
            MEDIUM -> 9
            HIGH -> 12
        }

    /** Simultaneous particles across all effects. */
    val particleBudget: Int
        get() = when (this) {
            LOW -> 256
            MEDIUM -> 2_048
            HIGH -> 8_192
        }

    val shadowsEnabled: Boolean get() = this == HIGH

    companion object {
        private const val TAG = "ApogeeQuality"

        /**
         * Chooses a tier from RAM, core count and the GL renderer string.
         *
         * Must be called on the GL thread - [GLES30.glGetString] needs a
         * current context.
         */
        fun detect(context: Context): QualityTier {
            val activityManager =
                context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
            val memoryInfo = ActivityManager.MemoryInfo()
            activityManager.getMemoryInfo(memoryInfo)
            val totalRamGb = memoryInfo.totalMem / (1024.0 * 1024.0 * 1024.0)
            val cores = Runtime.getRuntime().availableProcessors()
            val renderer = runCatching { GLES30.glGetString(GLES30.GL_RENDERER) }.getOrNull() ?: ""

            val tier = when {
                totalRamGb >= 6.0 && cores >= 8 && Build.VERSION.SDK_INT >= 31 -> HIGH
                totalRamGb >= 4.0 && cores >= 6 -> MEDIUM
                else -> LOW
            }

            Log.i(
                TAG,
                "quality=$tier ram=${"%.1f".format(totalRamGb)}GB cores=$cores " +
                    "sdk=${Build.VERSION.SDK_INT} renderer='$renderer'",
            )
            return tier
        }
    }
}
