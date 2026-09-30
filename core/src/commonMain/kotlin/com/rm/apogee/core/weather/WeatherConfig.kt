package com.rm.apogee.core.weather

import com.rm.apogee.core.terrain.TerrainField
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * How lively a world's weather is. It's the host's choice, saved with the world and shared by
 * everyone in it. Weather comes from this and the seed, so two players with different settings
 * would be flying through different skies.
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
     * Scales the sea: the waves the wind raises, and the swell. The wind alone did too little.
     * Off a coast the waves are held down by how little open water the wind has crossed, and the
     * ocean's swell didn't change at all, so a wild sea near the Cape was hardly bigger than a
     * normal one.
     */
    val sea: Double,
) {
    /** Wind but no storms, and gentle thermals, for learning to fly. */
    @SerialName("calm") CALM("Calm", wind = 0.5, storms = 0.0, thermals = 0.5, gusts = 0.5, sea = 0.6),
    @SerialName("normal") NORMAL("Normal", wind = 1.0, storms = 1.0, thermals = 1.0, gusts = 1.0, sea = 1.0),
    @SerialName("wild") WILD("Wild", wind = 1.6, storms = 2.5, thermals = 1.4, gusts = 1.7, sea = 1.7),
}

/**
 * How much layer cloud a world has. This is the host's choice too, and saved with the world.
 * Whatever the setting, cover gathers in pockets: thin and see-through over most of the map, and
 * thick here and there.
 */
@Serializable
enum class CloudCover(
    val label: String,
    /** Added to the air's humidity before deciding whether it's cloudy. */
    val humidity: Double,
    /** How thick the pockets get, and how common they are. */
    val pockets: Double,
    /**
     * Where the low and middle decks close up into a blanket, over the part of a slow, broad
     * pattern above this (0..1). Past 1 it never happens, and you only get scattered cloud. Lower
     * means more of the map is overcast, with breaks drifting through it.
     */
    val blanketFrom: Double,
) {
    @SerialName("light") LIGHT("Light", humidity = -0.1, pockets = 0.6, blanketFrom = 2.0),
    @SerialName("normal") NORMAL("Normal", humidity = 0.0, pockets = 1.0, blanketFrom = 0.55),
    @SerialName("heavy") HEAVY("Heavy", humidity = 0.12, pockets = 1.5, blanketFrom = 0.33),
}

/**
 * Everything a world's weather is made from, apart from the planet itself.
 *
 * All of it goes over the network and into saves. Nothing else about the air does, because the
 * weather comes purely from this, the terrain, the time and the place.
 */
@Serializable
data class WeatherConfig(
    val seed: Int = TerrainField.DEFAULT_SEED,
    val intensity: WeatherIntensity = WeatherIntensity.NORMAL,
    val clouds: CloudCover = CloudCover.NORMAL,
)
