package com.rm.apogee.core.weather

import com.rm.apogee.core.terrain.TerrainField
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * How lively a world's weather is. The host's choice, saved with the world
 * and shared by everyone in it - weather is a function of this and the seed,
 * so two players with different settings would be flying through different
 * skies.
 */
@Serializable
enum class WeatherIntensity(
    val label: String,
    /** Scales every steady wind. */
    val wind: Double,
    /** How often storms form; zero means never. */
    val storms: Double,
    /** Scales thermal strength and how many there are. */
    val thermals: Double,
    /** Scales turbulence and gusts. */
    val gusts: Double,
) {
    /** Wind but no storms, gentle thermals: for learning to fly. */
    @SerialName("calm") CALM("Calm", wind = 0.5, storms = 0.0, thermals = 0.5, gusts = 0.5),
    @SerialName("normal") NORMAL("Normal", wind = 1.0, storms = 1.0, thermals = 1.0, gusts = 1.0),
    @SerialName("wild") WILD("Wild", wind = 1.6, storms = 2.5, thermals = 1.4, gusts = 1.7),
}

/**
 * How much layer cloud a world has. Also the host's, and saved with the world.
 * Whatever the setting, cover gathers in pockets: thin and see-through over
 * most of the map, thick here and there.
 */
@Serializable
enum class CloudCover(
    val label: String,
    /** Added to the air's humidity before it is judged cloudy or not. */
    val humidity: Double,
    /** How thick the pockets get, and how common. */
    val pockets: Double,
    /**
     * Where the low and middle decks close into a blanket: over the part of
     * a slow, broad pattern above this (0..1). Past 1, never - scattered
     * cloud only; lower, more of the map overcast, with breaks drifting
     * through it.
     */
    val blanketFrom: Double,
) {
    @SerialName("light") LIGHT("Light", humidity = -0.1, pockets = 0.6, blanketFrom = 2.0),
    @SerialName("normal") NORMAL("Normal", humidity = 0.0, pockets = 1.0, blanketFrom = 0.55),
    @SerialName("heavy") HEAVY("Heavy", humidity = 0.12, pockets = 1.5, blanketFrom = 0.33),
}

/**
 * Everything a world's weather is made from, besides the planet itself.
 *
 * The whole of it crosses the wire and goes into saves - nothing else about
 * the air does, because the weather is a pure function of this, the terrain,
 * the time and the place.
 */
@Serializable
data class WeatherConfig(
    val seed: Int = TerrainField.DEFAULT_SEED,
    val intensity: WeatherIntensity = WeatherIntensity.NORMAL,
    val clouds: CloudCover = CloudCover.NORMAL,
)
