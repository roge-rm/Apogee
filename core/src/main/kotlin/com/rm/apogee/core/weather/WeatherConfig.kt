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
)
