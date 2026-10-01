package com.rm.apogee.core.weather

import com.rm.apogee.core.terrain.TerrainField
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * How lively a world's weather is. The host picks it; it's saved with the world and shared, since
 * the weather comes from this and the seed.
 */
@Serializable
enum class WeatherIntensity(
    val label: String,
    /** Scales every steady wind. */
    val wind: Double,
    /** How often storms form. Zero means never. */
    val storms: Double,
    /** Scales how strong thermals are and how many there are. */
    val thermals: Double,
    /** Scales turbulence and gusts. */
    val gusts: Double,
    /**
     * Scales the sea: wind waves and swell. Wind alone wasn't enough, since fetch holds waves
     * down near a coast and the swell didn't change.
     */
    val sea: Double,
) {
    /** Wind but no storms, and gentle thermals, for learning to fly. */
    @SerialName("calm") CALM("Calm", wind = 0.5, storms = 0.0, thermals = 0.5, gusts = 0.5, sea = 0.6),
    @SerialName("normal") NORMAL("Normal", wind = 1.0, storms = 1.0, thermals = 1.0, gusts = 1.0, sea = 1.0),
    @SerialName("wild") WILD("Wild", wind = 1.6, storms = 2.5, thermals = 1.4, gusts = 1.7, sea = 1.7),
}

/**
 * How much layer cloud a world has. The host picks it; it's saved with the world. Cover always
 * gathers in pockets: thin over most of the map, thick here and there.
 */
@Serializable
enum class CloudCover(
    val label: String,
    /** Added to the air's humidity before deciding whether it's cloudy. */
    val humidity: Double,
    /** How thick the pockets get, and how common they are. */
    val pockets: Double,
    /**
     * Where low and middle decks close into a blanket, over the part of a slow broad pattern above
     * this (0..1). Past 1 it never does. Lower means more overcast.
     */
    val blanketFrom: Double,
) {
    @SerialName("light") LIGHT("Light", humidity = -0.1, pockets = 0.6, blanketFrom = 2.0),
    @SerialName("normal") NORMAL("Normal", humidity = 0.0, pockets = 1.0, blanketFrom = 0.55),
    @SerialName("heavy") HEAVY("Heavy", humidity = 0.12, pockets = 1.5, blanketFrom = 0.33),
}

/**
 * Everything a world's weather is made from, apart from the planet. It all goes over the network
 * and into saves; the weather comes purely from this, the terrain, the time and the place.
 */
@Serializable
data class WeatherConfig(
    val seed: Int = TerrainField.DEFAULT_SEED,
    val intensity: WeatherIntensity = WeatherIntensity.NORMAL,
    val clouds: CloudCover = CloudCover.NORMAL,
)
