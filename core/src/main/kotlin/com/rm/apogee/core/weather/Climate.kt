package com.rm.apogee.core.weather

/**
 * What kind of weather a world has: the shape of its winds, what its storms are made of, what falls
 * from them, what hangs in its air, and the colours all of that is drawn in.
 *
 * [Weather] reads every number it used to keep as a constant from here. Terra's are exactly those
 * constants, so its air is what it always was. Each other world with air gets its own, and a world
 * with none has no climate at all.
 */
data class Climate(
    // --- circulation -------------------------------------------------------------
    /** How the winds are laid out by latitude. */
    val circulation: Circulation = Circulation.THREE_CELL,
    /** Scales the circulation's winds: the bands, and the flow around highs and lows. */
    val windScale: Double = 1.0,
    /** For [Circulation.BANDED]: how many jets there are from pole to pole. */
    val bands: Int = 0,
    /**
     * For [Circulation.BANDED]: the equatorial jet's speed in m/s. Negative runs against the spin.
     */
    val bandSpeed: Double = 0.0,
    /** The peak jet stream speed in m/s, and its height in metres. */
    val jet: Double = 22.0,
    val jetHeight: Double = 10_000.0,
    /**
     * The wind the whole upper air carries around the world, faster than the world turns, in m/s,
     * reached at [superRotationHeight]. Caligo's clouds lap it in days while its ground air barely
     * moves.
     */
    val superRotation: Double = 0.0,
    val superRotationHeight: Double = 1.0,
    /**
     * Where the weather starts to fade with height, and above which the air is still, in metres.
     */
    val fadeFrom: Double = 18_000.0,
    val ceiling: Double = 30_000.0,
    /** False for a giant, which has no ground under the air to slow the wind. */
    val ground: Boolean = true,

    // --- what forms in it --------------------------------------------------------
    /** Scales how many thermals there are and how hard they lift. */
    val thermals: Double = 1.0,
    /** Whether a thermal's top condenses into cumulus. Not in dry air, where it only lifts dust. */
    val cumulus: Boolean = true,
    /** Stratus, altostratus and cirrus decks. */
    val layers: Boolean = true,
    /** Scales how often storms form. Zero means never. */
    val storms: Double = 1.0,
    /** What a storm is: a tower of cloud, or a wall of dust. */
    val stormCloud: CloudType = CloudType.CUMULONIMBUS,
    /** Scales how tall storms stand, and how far above the ground their base is. */
    val stormHeight: Double = 1.0,
    val stormBase: Double = 1.0,
    /** What falls from a storm. */
    val precipitation: Precipitation = Precipitation.RAIN,
    /** Whether storms strike the ground. */
    val lightning: Boolean = true,

    // --- the permanent deck ------------------------------------------------------
    /**
     * A cloud layer all the way around the world that never breaks, in metres above datum. None if
     * top <= base.
     */
    val deckBase: Double = 0.0,
    val deckTop: Double = 0.0,
    val deckDensity: Double = 0.0,
    /** Flashes inside the deck, for drawing. Caligo's lightning never reaches the ground. */
    val deckLightning: Boolean = false,

    // --- haze and light ----------------------------------------------------------
    /** How far you can see in clear air near the ground, in metres. */
    val haze: Double = AirSample.CLEAR_VISIBILITY,
    /**
     * The share of the sunlight that never reaches the ground, 0..1, fading off to nothing at
     * [gloomTop].
     */
    val gloom: Double = 0.0,
    val gloomTop: Double = 1.0,
    /**
     * The share of the sunlight a storm's cloud takes at full density, 0..1. Dust darkens panels.
     */
    val stormShade: Double = 0.0,

    // --- colours, for drawing (0xRRGGBB, as the sky shader mixes them) ---------
    /** The day sky overhead and at the horizon, and the band low around a rising or setting sun. */
    val skyZenith: Int = 0x173885,
    val skyHorizon: Int = 0x8CADDB,
    val sunset: Int = 0xD96B2E,
    /** The colour of the air over distance. */
    val hazeColour: Int = 0x85A8D9,
    /** Tints every cloud. White leaves them as they are. */
    val cloudTint: Int = 0xFFFFFF,
    val rainColour: Int = 0xB8C4D0,
    /** How much of a sky the air makes, 0..1. A thin air's sky is black with a glow at the rim. */
    val skyDepth: Double = 1.0,
    /**
     * The height of the cloud or haze that hides the ground from above, in metres, or 0 where the
     * ground can be seen. From orbit Caligo is a blank cream ball.
     */
    val veil: Double = 0.0,
) {
    /** Everything in it, numbers by their bits, for telling two builds' worlds apart. */
    fun fingerprint(): String = buildString {
        fun d(x: Double) { append(java.lang.Long.toHexString(x.toRawBits())).append(',') }
        append(circulation.name).append(',').append(bands).append(',')
        for (x in doubleArrayOf(
            windScale, bandSpeed, jet, jetHeight, superRotation, superRotationHeight, fadeFrom, ceiling,
            thermals, storms, stormHeight, stormBase, deckBase, deckTop, deckDensity, haze, gloom, gloomTop, stormShade,
        )) d(x)
        append(ground).append(cumulus).append(layers).append(stormCloud.name).append(precipitation.name).append(lightning)
    }

    /** Whether there's a permanent deck. */
    val hasDeck: Boolean get() = deckTop > deckBase

    /**
     * How much of the sun reaches [altitude] through the gloom, 0..1. It's 1 above it and on a
     * clear world.
     */
    fun sunThrough(altitude: Double): Double =
        if (gloom <= 0.0) 1.0 else 1.0 - gloom * (1.0 - smooth(0.0, gloomTop, altitude))

    enum class Circulation {
        /** Terra's: trade winds, westerlies and polar easterlies. */
        THREE_CELL,
        /** A giant's: alternating jets from pole to pole. */
        BANDED,
        /** Too thin to move anything worth mentioning. */
        STILL,
    }

    enum class Precipitation { RAIN, METHANE, NONE }

    companion object {
        /** Terra's own, which are the numbers the weather has always used. */
        val TERRA = Climate()

        /** The climate of the world [bodyId], or null where there's no weather at all. */
        fun of(bodyId: String): Climate? = BY_BODY[bodyId]

        private val CALIGO = Climate(
            // The ground air creeps, and high up the whole sky races around the world.
            windScale = 0.12, jet = 0.0,
            superRotation = 110.0, superRotationHeight = 50_000.0,
            fadeFrom = 75_000.0, ceiling = 90_000.0,
            thermals = 0.0, cumulus = false, layers = false, storms = 0.0,
            precipitation = Precipitation.NONE, lightning = false,
            // The acid deck, from the clear hot air 48 km up to 70.
            deckBase = 48_000.0, deckTop = 70_000.0, deckDensity = 0.8, deckLightning = true, veil = 70_000.0,
            haze = 12_000.0, gloom = 0.9, gloomTop = 70_000.0,
            skyZenith = 0x66471F, skyHorizon = 0xB38547, sunset = 0x8C4D1F,
            cloudTint = 0xE8D8A8, hazeColour = 0x9E7542, rainColour = 0xD8C890,
        )

        private val RUBRA = Climate(
            // Thin air: slow winds, dry thermals whirling dust up, and dust storms.
            windScale = 0.7, jet = 12.0, jetHeight = 20_000.0,
            fadeFrom = 30_000.0, ceiling = 45_000.0,
            thermals = 1.3, cumulus = false, layers = false,
            storms = 0.6, stormCloud = CloudType.DUST, stormHeight = 0.6, stormBase = 0.0,
            precipitation = Precipitation.NONE, lightning = false,
            haze = 30_000.0, stormShade = 0.85,
            // Butterscotch by day, blue around the setting sun.
            skyZenith = 0x735133, skyHorizon = 0xC7996B, sunset = 0x5980CC,
            cloudTint = 0xC89060, hazeColour = 0xB88F66, rainColour = 0xB07850,
        )

        private val AURANTIA = Climate(
            // Thick, cold and slow, with methane storms and an orange murk.
            windScale = 0.3, jet = 8.0, jetHeight = 25_000.0,
            fadeFrom = 60_000.0, ceiling = 80_000.0,
            thermals = 0.3, cumulus = true, layers = false,
            storms = 0.5, stormHeight = 1.2,
            precipitation = Precipitation.METHANE, lightning = false,
            haze = 8_000.0, gloom = 0.9, gloomTop = 80_000.0, veil = 80_000.0,
            skyZenith = 0x4D2E0F, skyHorizon = 0x9E6B2E, sunset = 0x663814,
            cloudTint = 0xD8B070, hazeColour = 0x94662E, rainColour = 0xA88040,
        )

        private fun giant(bands: Int, speed: Double, zenith: Int, horizon: Int) = Climate(
            circulation = Circulation.BANDED, bands = bands, bandSpeed = speed, jet = 0.0,
            fadeFrom = 150_000.0, ceiling = 250_000.0, ground = false,
            thermals = 0.0, cumulus = false, layers = false, storms = 0.0,
            precipitation = Precipitation.NONE, lightning = false,
            haze = 20_000.0,
            skyZenith = zenith, skyHorizon = horizon, sunset = horizon,
            hazeColour = horizon,
        )

        /** Air too thin to have weather: just drag, and a tint to the sky. */
        private fun thin(haze: Int) = Climate(
            circulation = Circulation.STILL, windScale = 0.0, jet = 0.0,
            thermals = 0.0, cumulus = false, layers = false, storms = 0.0,
            precipitation = Precipitation.NONE, lightning = false,
            skyZenith = 0x000000, skyHorizon = haze, sunset = haze, hazeColour = haze,
            skyDepth = 0.12,
        )

        private val BY_BODY: Map<String, Climate> = mapOf(
            "terra" to TERRA,
            "caligo" to CALIGO,
            "rubra" to RUBRA,
            "aurantia" to AURANTIA,
            "magna" to giant(14, 120.0, 0x4A3A2A, 0xA08868),
            "aurea" to giant(12, 250.0, 0x5A4A28, 0xB8A070),
            "obliqua" to giant(6, -100.0, 0x2A5A68, 0x80B8C8),
            "caerula" to giant(6, -400.0, 0x0F1E5A, 0x3A5AB0),
            "aversa" to thin(0x5A6A80),
            "ultima" to thin(0x4A6AA0),
        )
    }
}
