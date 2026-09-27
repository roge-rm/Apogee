package com.rm.apogee.render

import com.rm.apogee.core.weather.Climate

/**
 * The colours a world's air is drawn in: its day sky, the band around a low sun, the haze over
 * distance, and how much of a sky it makes at all. Terra's are exactly the ones the shaders always
 * had.
 */
class SkyColours(
    val zenith: FloatArray,
    val horizon: FloatArray,
    val sunset: FloatArray,
    val haze: FloatArray,
    /** The thin glow along the horizon. */
    val rim: FloatArray,
    /** Tints clouds and fog. */
    val cloud: FloatArray,
    /** 0..1, how much sky the air makes. A thin air's sky is black with a faint rim. */
    val depth: Float,
    /** The sky a sea reflects. */
    val seaSky: FloatArray,
) {
    companion object {
        val TERRA = SkyColours(
            zenith = floatArrayOf(0.09f, 0.22f, 0.52f),
            horizon = floatArrayOf(0.55f, 0.68f, 0.86f),
            sunset = floatArrayOf(0.85f, 0.42f, 0.18f),
            haze = floatArrayOf(0.52f, 0.66f, 0.85f),
            rim = floatArrayOf(0.30f, 0.45f, 0.70f),
            cloud = floatArrayOf(1f, 1f, 1f),
            depth = 1f,
            seaSky = floatArrayOf(0.28f, 0.48f, 0.80f),
        )

        private val made = HashMap<String, SkyColours>()

        /** World [bodyId]'s sky. Terra's where it has no climate of its own. */
        fun of(bodyId: String): SkyColours = synchronized(made) {
            made.getOrPut(bodyId) {
                val c = Climate.of(bodyId)
                if (c == null || c == Climate.TERRA) TERRA else {
                    val haze = rgb(c.hazeColour)
                    SkyColours(
                        zenith = rgb(c.skyZenith),
                        horizon = rgb(c.skyHorizon),
                        sunset = rgb(c.sunset),
                        haze = haze,
                        rim = floatArrayOf(haze[0] * 0.6f, haze[1] * 0.6f, haze[2] * 0.6f),
                        cloud = rgb(c.cloudTint),
                        depth = c.skyDepth.toFloat(),
                        seaSky = rgb(c.skyHorizon).let { h -> floatArrayOf(h[0] * 0.6f, h[1] * 0.6f, h[2] * 0.6f) },
                    )
                }
            }
        }

        private fun rgb(c: Int) = floatArrayOf(((c shr 16) and 0xFF) / 255f, ((c shr 8) and 0xFF) / 255f, (c and 0xFF) / 255f)
    }
}
