package com.rm.apogee.core.weather

import com.rm.apogee.core.math.Vec3

/**
 * The kinds of cloud, each with its own place in the sky, its own weight of
 * water to push through and its own murk.
 */
enum class CloudType(
    /** Extra air loading at full density: drag and lift scale by 1 + this. */
    val dragLoad: Double,
    /** How far you can see inside it at full density, metres. */
    val visibility: Double,
) {
    /** Heaped fair-weather cloud on top of a thermal. */
    CUMULUS(dragLoad = 0.06, visibility = 60.0),

    /** Low grey deck, often over the sea and under lows. */
    STRATUS(dragLoad = 0.04, visibility = 110.0),

    /** Mid-level sheet. */
    ALTOSTRATUS(dragLoad = 0.03, visibility = 180.0),

    /** High, thin ice. Mostly to look at. */
    CIRRUS(dragLoad = 0.01, visibility = 1_500.0),

    /** A storm tower: rain, hail, updraughts and the heaviest water load. */
    CUMULONIMBUS(dragLoad = 0.15, visibility = 30.0),

    /** A dust storm's wall: grit, not water, thick enough to lose the sun in. */
    DUST(dragLoad = 0.02, visibility = 150.0),

    /** A deck round the whole world that never breaks, as Caligo's acid cloud. */
    DECK(dragLoad = 0.02, visibility = 400.0),
}

/**
 * The air at one place and time.
 *
 * [wind] is the steady part - circulation, terrain, thermals, a storm's
 * outflow - in the body's own rotating frame, including its vertical motion.
 * Turbulence is left out on purpose: it varies over a craft's length, and is
 * sampled part by part through [Weather.turbulence] at [turbulence]'s
 * strength.
 */
class AirSample {
    /** Body-fixed, m/s, vertical included. */
    val wind = Vec3()

    /** The vertical part of [wind], m/s: thermals, ridge lift, downdraughts. */
    var lift = 0.0

    /** How rough the air is, 0..1. */
    var turbulence = 0.0

    /** How much cloud is here, 0..1, and what kind; null for clear air. */
    var cloudDensity = 0.0
    var cloudType: CloudType? = null

    /** Rain, 0..1. */
    var precipitation = 0.0

    /** How far can be seen, metres. */
    var visibility = CLEAR_VISIBILITY

    /** How near and how strong the nearest storm is, 0..1: for the sky's darkness. */
    var storm = 0.0

    /** Extra air loading from cloud water, as a factor on density: 1 in clear air. */
    val loading: Double get() = 1.0 + cloudDensity * (cloudType?.dragLoad ?: 0.0)

    fun clear() {
        wind.setZero()
        lift = 0.0
        turbulence = 0.0
        cloudDensity = 0.0
        cloudType = null
        precipitation = 0.0
        visibility = CLEAR_VISIBILITY
        storm = 0.0
    }

    fun setTo(other: AirSample): AirSample {
        wind.setTo(other.wind)
        lift = other.lift
        turbulence = other.turbulence
        cloudDensity = other.cloudDensity
        cloudType = other.cloudType
        precipitation = other.precipitation
        visibility = other.visibility
        storm = other.storm
        return this
    }

    companion object {
        /** A clear day: out to the horizon haze. */
        const val CLEAR_VISIBILITY = 60_000.0
    }
}

/** One cloud to draw: its kind, how solid it is, and the lobes it is made of. */
class CloudShape(val type: CloudType, val amount: Double) {
    val lobes = ArrayList<CloudLobe>(6)

    /** Rain falling out of it, as curtains from its base to the ground: what shows from afar. */
    val rain = ArrayList<CloudLobe>(0)
}

/**
 * One rounded lobe of a cloud: an ellipsoid [horizontal] metres across its
 * middle each way and [vertical] up and down from [centre] (body-fixed), lit
 * [shade] bright - storm bases dark, tops bright.
 */
class CloudLobe(
    val centre: Vec3, val horizontal: Double, val vertical: Double, val shade: Double,
    /** Spread thin and flat, as an anvil or a storm's base is. */
    val flat: Boolean = false,
)
