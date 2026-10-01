package com.rm.apogee.core.weather

import com.rm.apogee.core.math.Vec3

/** The kinds of cloud, each with its own height, water load and murk. */
enum class CloudType(
    /** Extra air loading at full density. Drag and lift scale by 1 + this. */
    val dragLoad: Double,
    /** How far you can see inside it at full density, in metres. */
    val visibility: Double,
) {
    /** Heaped fair-weather cloud on top of a thermal. */
    CUMULUS(dragLoad = 0.06, visibility = 60.0),

    /** A low grey deck, often over the sea and under lows. */
    STRATUS(dragLoad = 0.04, visibility = 110.0),

    /** A mid-level sheet. */
    ALTOSTRATUS(dragLoad = 0.03, visibility = 180.0),

    /** High, thin ice. Mostly there to look at. */
    CIRRUS(dragLoad = 0.01, visibility = 1_500.0),

    /** A storm tower: rain, hail, updraughts and the heaviest water load. */
    CUMULONIMBUS(dragLoad = 0.15, visibility = 30.0),

    /** The wall of a dust storm: grit thick enough to lose the sun in. */
    DUST(dragLoad = 0.02, visibility = 150.0),

    /** A deck all the way around the world that never breaks, like Caligo's acid cloud. */
    DECK(dragLoad = 0.02, visibility = 400.0),
}

/**
 * The air at one place and time.
 *
 * [wind] is the steady part (circulation, terrain, thermals, storm outflow) in the body's rotating
 * frame, vertical included. Turbulence is left out because it changes over a craft's length; it's
 * sampled per part through [Weather.turbulence] at [turbulence]'s strength.
 */
class AirSample {
    /** Body-fixed, in m/s, including vertical. */
    val wind = Vec3()

    /** The vertical part of [wind], in m/s: thermals, ridge lift and downdraughts. */
    var lift = 0.0

    /** How rough the air is, 0..1. */
    var turbulence = 0.0

    /** How much cloud is here, 0..1, and what kind. Null for clear air. */
    var cloudDensity = 0.0
    var cloudType: CloudType? = null

    /** Rain, 0..1. */
    var precipitation = 0.0

    /** How far you can see, in metres. */
    var visibility = CLEAR_VISIBILITY

    /** How close and how strong the nearest storm is, 0..1, for how dark the sky gets. */
    var storm = 0.0

    /** Extra air loading from cloud water, as a multiplier on density. 1 in clear air. */
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
        /** A clear day: out to the haze on the horizon. */
        const val CLEAR_VISIBILITY = 60_000.0
    }
}

/** One cloud to draw: its kind, how solid it is, and the lobes it's made of. */
class CloudShape(
    val type: CloudType,
    val amount: Double,
    /**
     * One of a deck's rough far sheets rather than its puffs. The two fade into each other round
     * [Weather.NEAR_DECK].
     */
    val far: Boolean = false,
) {
    val lobes = ArrayList<CloudLobe>(6)

    /**
     * Body-fixed velocity in m/s: a storm along its steering wind, zero for a deck's puffs. Drawing
     * carries the lobes on by this between listings so they don't jump.
     */
    val drift = com.rm.apogee.core.math.Vec3()

    /** How fast [amount] is changing, per second, until its lobes' [CloudLobe.changingUntil]. */
    var amountRate = 0.0

    /** Rain curtains from its base to the ground, seen from far away. */
    val rain = ArrayList<CloudLobe>(0)
}

/**
 * One rounded lobe of a cloud: an ellipsoid [horizontal] metres across its middle each way and
 * [vertical] up and down from [centre] (body-fixed), lit [shade] bright. Storm bases are dark and
 * tops are bright.
 */
class CloudLobe(
    val centre: Vec3, val horizontal: Double, val vertical: Double, val shade: Double,
    /** Spread thin and flat, like an anvil or a storm's base. */
    val flat: Boolean = false,
) {
    /**
     * Rates of change per second until [changingUntil], as a deck's puff grows, shrinks or rises.
     * Drawing carries it on by these between listings.
     */
    var horizontalRate = 0.0
    var verticalRate = 0.0
    var centreRate: Vec3? = null
    var changingUntil = Double.NEGATIVE_INFINITY
}
