package com.rm.apogee.core.career

import kotlinx.serialization.Serializable

/**
 * What one craft has done since it last left the ground, which career feats are read from. Saved
 * with the craft, so a restart doesn't lose a flight. Metres and kilograms; -1 means "not yet" for
 * a time.
 */
@Serializable
class FlightLog(
    /** Mass at launch in kg, and part count at the last look. */
    var launchMass: Double = 0.0,
    var parts: Int = 0,
    /** A stage fired since the last look. Whether it dropped anything shows in the part count. */
    var stagePending: Boolean = false,
    var stagesDropped: Int = 0,
    /** The highest it has been above the ground, in metres. */
    var peak: Double = 0.0,
    /** Whether it left Terra's air, and orbited it, this flight. */
    var aboveAir: Boolean = false,
    var orbitedHome: Boolean = false,
    /** Worlds it has landed on this flight with crew. */
    var landedOn: MutableList<String> = ArrayList(),
    /** Distance flown, driven and sailed, in metres. */
    var flown: Double = 0.0,
    var driven: Double = 0.0,
    var sailed: Double = 0.0,
    /** Distance sailed on wind alone since an engine last ran, in metres. */
    var underSail: Double = 0.0,
    /** Distance towed on its winch line, in metres. */
    var towed: Double = 0.0,
    /**
     * Hovering by hand: the body-fixed spot held, when it started (-1 for not), and the furthest it
     * strayed, in metres.
     */
    var hoverSpot: com.rm.apogee.core.math.SerialVec3 = com.rm.apogee.core.math.Vec3(),
    var hoverSince: Double = -1.0,
    var hoverWorst: Double = 0.0,
    /**
     * Floating on gas: the height it started rising from with nothing running (NaN for not), and
     * the distance travelled aloft, in metres.
     */
    var floatFrom: Double = Double.NaN,
    var floated: Double = 0.0,
    /** Whether it was on a runway at the last look, and whether this flight started from one. */
    var onRunway: Boolean = false,
    var fromRunway: Boolean = false,
    /** Resting: its landing has been counted. Airborne: off the ground since it last rested. */
    var resting: Boolean = true,
    var airborne: Boolean = false,
    /**
     * Apoapsis in metres when it entered an atmosphere (or -1), and whether it has used an engine
     * since.
     */
    var airApoapsis: Double = -1.0,
    var airUnbound: Boolean = false,
    var airThrust: Boolean = false,
    /**
     * The world it's passing close to, its energy relative to that world's parent on the way in,
     * and any thrust since.
     */
    var flybyBody: String = "",
    var flybyEnergy: Double = 0.0,
    var flybyThrust: Boolean = false,
    /**
     * Propellant at the last look (on another world, the lowest it's had there), and whether it's
     * been topped up there since.
     */
    var propellant: Double = 0.0,
    var refuelledAway: Boolean = false,
    /** Universe time at the last look. */
    var lookedAt: Double = -1.0,
    /** When it came down on water at the end of a flight, universe time, or -1. */
    var wetAt: Double = -1.0,
    /**
     * Since it was last at the surface: the deepest it has been, in metres, and whether it settled
     * on the sea floor.
     */
    var deepest: Double = 0.0,
    var seafloor: Boolean = false,
) {
    /** Starts a new flight, forgetting the last. [mass] is its mass now. */
    fun relaunch(mass: Double) {
        launchMass = mass
        stagesDropped = 0
        peak = 0.0
        aboveAir = false
        orbitedHome = false
        landedOn.clear()
        flown = 0.0
        driven = 0.0
        sailed = 0.0
        underSail = 0.0
        fromRunway = onRunway
        airApoapsis = -1.0
        airThrust = false
        flybyBody = ""
        refuelledAway = false
    }
}
