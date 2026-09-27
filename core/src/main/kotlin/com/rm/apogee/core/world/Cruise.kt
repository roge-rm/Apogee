package com.rm.apogee.core.world

import com.rm.apogee.core.craft.Vessel
import com.rm.apogee.core.math.Quat
import com.rm.apogee.core.math.Vec3
import com.rm.apogee.core.math.quatFromTo
import com.rm.apogee.core.orbit.CelestialBody
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Altitude and heading hold for an aircraft: it keeps the height above the datum and the heading it
 * was given, and leaves the throttle to the pilot.
 *
 * It flies the way a pilot does, through stability assist. Toward the heading it banks into the
 * turn, as much as the turn needs up to [MAX_BANK]. For the height it holds the nose a few degrees
 * above the way it's going, more to climb and less to sink, in proportion to how far off it is.
 * What it works out is the attitude for stability assist to hold, so the same elevons, rudder and
 * wheels do the flying that your thumb would.
 */
internal class Cruise {
    private val up = Vec3()
    private val east = Vec3()
    private val north = Vec3()
    private val velocity = Vec3()
    private val flat = Vec3()
    private val nose = Vec3()
    private val level = Vec3()
    private val wing = Vec3()
    private val deck = Vec3()
    private val turnNose = Quat()
    private val turnDeck = Quat()

    /** Degrees [vessel]'s nose is above the way it's travelling. */
    fun attack(vessel: Vessel, attractor: CelestialBody): Double {
        frame(vessel, attractor)
        vessel.body.orientation.rotate(vessel.design.orientation.forward, nose)
        return Math.toDegrees(kotlin.math.asin((nose dot up).coerceIn(-1.0, 1.0))) -
            Math.toDegrees(atan2(velocity dot up, flat.length))
    }

    /** Degrees north of east [vessel] is travelling over the ground. */
    fun track(vessel: Vessel, attractor: CelestialBody): Double {
        frame(vessel, attractor)
        return Math.toDegrees(atan2(flat dot north, flat dot east))
    }

    /**
     * Sets [vessel]'s held attitude to fly its cruise height and heading, for a tick of [dt]
     * seconds.
     */
    fun fly(vessel: Vessel, attractor: CelestialBody, dt: Double) {
        frame(vessel, attractor)
        val control = vessel.control
        val speed = flat.length
        val climb = velocity dot up
        val track = Math.toDegrees(atan2(flat dot north, flat dot east))
        val path = Math.toDegrees(atan2(climb, speed))
        var turn = control.cruiseHeading - track
        while (turn > 180.0) turn -= 360.0
        while (turn < -180.0) turn += 360.0
        val bank = (turn * TURN_BANK).coerceIn(-MAX_BANK, MAX_BANK)
        val height = attractor.altitudeOf(vessel.body.position)
        val climbWanted = ((control.cruiseHeight - height) / HEIGHT_PACE).coerceIn(-MAX_CLIMB, MAX_CLIMB)
        // The trim creeps toward whatever holds it level, the way a pilot trims out a steady pull,
        // so it settles on the height instead of a little under it.
        control.cruiseTrim = (control.cruiseTrim + (climbWanted - climb) * TRIM_RATE * dt).coerceIn(-MAX_ATTACK, MAX_ATTACK)
        val attack = (control.cruiseTrim + (climbWanted - climb) * CLIMB_GAIN).coerceIn(-MAX_ATTACK, MAX_ATTACK)
        hold(vessel, path + attack, track + (turn * 0.3).coerceIn(-5.0, 5.0), bank)
    }

    /**
     * Holds the nose [noseUp] degrees above the horizon, on [heading] degrees north of east, banked
     * [bank] degrees, positive into a turn to the left.
     */
    private fun hold(vessel: Vessel, noseUp: Double, heading: Double, bank: Double) {
        val t = Math.toRadians(noseUp)
        val h = Math.toRadians(heading)
        nose.setTo(east).mulInPlace(cos(h) * cos(t)).addScaledInPlace(north, sin(h) * cos(t)).addScaledInPlace(up, sin(t))
        // Wings level: the craft's up in the vertical plane through the nose, then banked about it.
        level.setTo(up).addScaledInPlace(nose, -(up dot nose)).normalizeInPlace()
        wing.setTo(nose).crossInPlace(level)
        val b = Math.toRadians(bank)
        deck.setTo(level).mulInPlace(cos(b)).addScaledInPlace(wing, sin(b))
        val orientation = vessel.design.orientation
        quatFromTo(orientation.forward, nose, turnNose)
        val deckNow = turnNose.rotate(orientation.up)
        quatFromTo(deckNow, deck, turnDeck)
        vessel.assistHeld.setTo(turnDeck).mulInPlace(turnNose)
        vessel.assistHolding = true
    }

    /** Up, east and north where [vessel] is, and its velocity over the ground, all and flattened. */
    private fun frame(vessel: Vessel, attractor: CelestialBody) {
        up.setTo(vessel.body.position).normalizeInPlace()
        // East is the way the ground turns under it. North is up x east.
        attractor.surfaceVelocityAt(vessel.body.position, east)
        if (east.lengthSq < 1e-9) east.setTo(0.0, 1.0, 0.0).crossInPlace(up)
        east.addScaledInPlace(up, -(east dot up)).normalizeInPlace()
        north.setTo(up).crossInPlace(east)
        attractor.surfaceVelocityAt(vessel.body.position, velocity)
        velocity.negateInPlace().addInPlace(vessel.body.linearVelocity)
        flat.setTo(velocity).addScaledInPlace(up, -(velocity dot up))
        if (flat.lengthSq < 1e-6) {
            vessel.body.orientation.rotate(vessel.design.orientation.forward, flat)
            flat.addScaledInPlace(up, -(flat dot up))
        }
        val length = sqrt(flat.lengthSq)
        if (length < 1e-9) flat.setTo(east)
    }

    companion object {
        /** Bank, in degrees, for each degree of turn still to go, and the most it banks. */
        const val TURN_BANK = 1.5
        const val MAX_BANK = 25.0

        /** Metres off the held height for each m/s of climb or sink it asks for, and the most. */
        const val HEIGHT_PACE = 10.0
        const val MAX_CLIMB = 10.0

        /**
         * Degrees of nose over the flight path per m/s of climb wanted, degrees a second the trim
         * moves per m/s, and the most either goes.
         */
        const val CLIMB_GAIN = 0.3
        const val TRIM_RATE = 0.05
        const val MAX_ATTACK = 8.0

        /** The lowest it takes over at, in metres above the ground. */
        const val LOWEST = 100.0
    }
}
