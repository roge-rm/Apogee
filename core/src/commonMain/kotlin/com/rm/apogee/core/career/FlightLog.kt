package com.rm.apogee.core.career

import kotlinx.serialization.Serializable

/**
 * Everything one craft has done since it last left the ground. The career's feats are read from
 * this. It's saved with the craft, so a flight that gets interrupted by a restart still counts.
 * Distances are in metres and masses in kilograms, and -1 means "not yet" where a number stands for
 * a moment in time.
 */
@Serializable
class FlightLog(
    /** What it weighed when it left, in kg, and how many parts it had at the last look. */
    var launchMass: Double = 0.0,
    var parts: Int = 0,
    /**
     * A stage fired since the last look. Whether it dropped anything shows up in the part count.
     */
    var stagePending: Boolean = false,
    var stagesDropped: Int = 0,
    /** The highest it has been above the ground, in metres. */
    var peak: Double = 0.0,
    /** Whether it got out of Terra's air, and into orbit around it, on this flight. */
    var aboveAir: Boolean = false,
    var orbitedHome: Boolean = false,
    /** Worlds it has landed on this flight with its crew. */
    var landedOn: MutableList<String> = ArrayList(),
    /** How far it has flown, driven and sailed, in metres. */
    var flown: Double = 0.0,
    var driven: Double = 0.0,
    var sailed: Double = 0.0,
    /** How far it has sailed on the wind alone since an engine last ran, in metres. */
    var underSail: Double = 0.0,
    /**
     * Hovering by hand: the body-fixed spot it's holding over, when it started (-1 for not), and
     * the furthest it has strayed from it, in metres.
     */
    var hoverSpot: com.rm.apogee.core.math.SerialVec3 = com.rm.apogee.core.math.Vec3(),
    var hoverSince: Double = -1.0,
    var hoverWorst: Double = 0.0,
    /**
     * Floating on gas: the height it started rising from with nothing running (NaN for not), and
     * how far it has travelled aloft on gas, in metres.
     */
    var floatFrom: Double = Double.NaN,
    var floated: Double = 0.0,
    /**
     * Whether it was standing on a runway at the last look, and whether this flight started from
     * one.
     */
    var onRunway: Boolean = false,
    var fromRunway: Boolean = false,
    /**
     * Resting means whatever it did on landing has been counted. Airborne means it has been off the
     * ground since it last rested.
     */
    var resting: Boolean = true,
    var airborne: Boolean = false,
    /**
     * The high point of its orbit when it entered an atmosphere, in metres, or -1, and whether it
     * has used an engine since.
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
     * The propellant aboard at the last look (while standing on another world, the lowest it has
     * had there), and whether it has been topped up there since.
     */
    var propellant: Double = 0.0,
    var refuelledAway: Boolean = false,
    /** Universe time at the last look. */
    var lookedAt: Double = -1.0,
    /** When it came down on the water at the end of a flight, in universe time, or -1. */
    var wetAt: Double = -1.0,
    /**
     * Its time under the sea since it was last at the surface: the deepest it has been, in metres,
     * and whether it has settled on the floor down there.
     */
    var deepest: Double = 0.0,
    var seafloor: Boolean = false,
) {
    /**
     * Starts a new flight. Everything from last time is forgotten, and [mass] is what it weighs
     * now.
     */
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
