package com.rm.apogee.core.career

import kotlinx.serialization.Serializable

/**
 * What one craft has done since it last left the ground: what the career's
 * feats are read from. Saved with the craft, so a flight interrupted by a
 * restart still counts. Distances in metres, masses in kilograms; -1 for
 * "not yet" where a number stands for a moment.
 */
@Serializable
class FlightLog(
    /** What it weighed when it left, kg, and how many parts it had at the last look. */
    var launchMass: Double = 0.0,
    var parts: Int = 0,
    /** A stage fired since the last look: whether it dropped anything is seen by the part count. */
    var stagePending: Boolean = false,
    var stagesDropped: Int = 0,
    /** Highest above the ground, m. */
    var peak: Double = 0.0,
    /** Out of Terra's air, and in orbit round it, this flight. */
    var aboveAir: Boolean = false,
    var orbitedHome: Boolean = false,
    /** Worlds it has landed on this flight, crew and all. */
    var landedOn: MutableList<String> = ArrayList(),
    /** Flown, driven and sailed, m. */
    var flown: Double = 0.0,
    var driven: Double = 0.0,
    var sailed: Double = 0.0,
    /** Standing on a runway at the last look; and whether this flight began from one. */
    var onRunway: Boolean = false,
    var fromRunway: Boolean = false,
    /** Resting: whatever it did on landing has been reckoned. Airborne: off the ground since it last rested. */
    var resting: Boolean = true,
    var airborne: Boolean = false,
    /** Its orbit's high point on entering an atmosphere, m, or -1; and whether it has used an engine since. */
    var airApoapsis: Double = -1.0,
    var airUnbound: Boolean = false,
    var airThrust: Boolean = false,
    /** The world it is passing close by, its energy about that world's parent on the way in, and any thrust since. */
    var flybyBody: String = "",
    var flybyEnergy: Double = 0.0,
    var flybyThrust: Boolean = false,
    /**
     * Propellant aboard at the last look - standing on another world, the
     * least it has had there - and whether it has been topped up there since.
     */
    var propellant: Double = 0.0,
    var refuelledAway: Boolean = false,
    /** Universe time at the last look. */
    var lookedAt: Double = -1.0,
    /** When it came down on the water out of a flight, universe time, or -1. */
    var wetAt: Double = -1.0,
    /** Under the sea since it was last on the surface: how deep it has been, m, and whether it has set down on the floor down there. */
    var deepest: Double = 0.0,
    var seafloor: Boolean = false,
) {
    /** A new flight: everything it did last time forgotten, [mass] its weight now. */
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
        fromRunway = onRunway
        airApoapsis = -1.0
        airThrust = false
        flybyBody = ""
        refuelledAway = false
    }
}
