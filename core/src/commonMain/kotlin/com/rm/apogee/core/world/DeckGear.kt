package com.rm.apogee.core.world

import com.rm.apogee.core.craft.Vessel
import com.rm.apogee.core.craft.VesselId
import com.rm.apogee.core.math.Vec3
import com.rm.apogee.core.part.ArrestingGear
import com.rm.apogee.core.part.Catapult
import com.rm.apogee.core.part.Tailhook
import kotlin.math.abs

/**
 * A carrier's deck gear: arresting wires that catch a plane's tailhook and stop it short, and
 * catapults that throw a plane off the deck at flying speed.
 *
 * Neither is a rope or a piston. A caught plane is slowed evenly against the deck it landed on, the
 * way the wire paying out under the deck does it, and a launched one is pushed evenly along the
 * track. Each pushes the deck back as hard, so a light platform feels it.
 */
internal class DeckGear {
    /** A plane caught on a wire of [deck]'s part [part], being slowed at [decel] m/s². */
    private class Arrest(val plane: VesselId, val deck: VesselId, val part: Int, val decel: Double)

    /** A plane being thrown along [deck]'s catapult [part] at [accel] m/s², up to [endSpeed]. */
    private class Launch(val plane: VesselId, val deck: VesselId, val part: Int, val accel: Double, val endSpeed: Double)

    private val arrests = ArrayList<Arrest>()
    private val launches = ArrayList<Launch>()

    /** How long each craft has sat on a catapult ready to go, in seconds. */
    private val ready = HashMap<VesselId, Double>()

    private val tip = Vec3()
    private val local = Vec3()
    private val offset = Vec3()
    private val velocity = Vec3()
    private val under = Vec3()
    private val axis = Vec3()
    private val impulse = Vec3()

    /** Whether [plane] is caught on a wire right now. */
    fun caught(plane: VesselId): Boolean = arrests.any { it.plane == plane }

    /** Whether [plane] is being thrown off a catapult right now. */
    fun launching(plane: VesselId): Boolean = launches.any { it.plane == plane }

    fun step(vessels: Map<VesselId, Vessel>, dt: Double) {
        catchHooks(vessels, dt)
        slowCaught(vessels, dt)
        armCatapults(vessels, dt)
        throwLaunched(vessels, dt)
    }

    // --- the wires -------------------------------------------------------------------------

    /**
     * A lowered hook crossing a wire, over it and moving across it, catches it. The hook is lowered
     * with the gear.
     */
    private fun catchHooks(vessels: Map<VesselId, Vessel>, dt: Double) {
        for (plane in vessels.values) {
            if (plane.dormant || !plane.control.deployed || caught(plane.id)) continue
            for (h in plane.defs.indices) {
                val hook = plane.defs[h].module<Tailhook>() ?: continue
                if (plane.isBroken(h)) continue
                plane.partPointOffsetWorld(h, local.setTo(hook.tip), tip).addInPlace(plane.body.position)
                for (deck in vessels.values) {
                    if (deck === plane || deck.referenceBodyId != plane.referenceBodyId) continue
                    if (deck.body.position.distanceTo(tip) > deck.contactRadius + REACH) continue
                    for (g in deck.defs.indices) {
                        val gear = deck.defs[g].module<ArrestingGear>() ?: continue
                        if (deck.isBroken(g)) continue
                        deck.worldToPartLocal(g, tip, local)
                        if (abs(local.x) > gear.span / 2.0 || local.z < LOWEST || local.z > HIGHEST) continue
                        relativeVelocity(plane, deck, tip, velocity)
                        // Into the wires' own frame, to see which way it's going across them.
                        deck.body.orientation.inverseRotate(velocity, axis)
                        deck.design.parts[g].rotation.inverseRotate(axis, axis)
                        if (abs(axis.y) < LEAST_CATCH_SPEED) continue
                        val before = local.y - axis.y * dt
                        if (gear.wires.none { (before - it) * (local.y - it) <= 0.0 }) continue
                        val speed = flat(velocity, deck).length
                        val decel = minOf(speed * speed / (2.0 * gear.runout), gear.mostG * STANDARD_GRAVITY)
                        arrests.add(Arrest(plane.id, deck.id, g, decel))
                        plane.control.autopilotNote = "Caught a wire"
                        if (!deck.anchored) deck.wake()
                        // One catch a tick is plenty. Anyone else is looked for next tick.
                        return
                    }
                }
            }
        }
    }

    /** Slows each caught plane against its deck, and lets it go once it's stopped. */
    private fun slowCaught(vessels: Map<VesselId, Vessel>, dt: Double) {
        val iterator = arrests.iterator()
        while (iterator.hasNext()) {
            val arrest = iterator.next()
            val plane = vessels[arrest.plane]
            val deck = vessels[arrest.deck]
            if (plane == null || deck == null || deck.isBroken(arrest.part)) { iterator.remove(); continue }
            relativeVelocity(plane, deck, plane.body.position, velocity)
            val across = flat(velocity, deck)
            val speed = across.length
            if (speed < STOPPED || plane.body.position.distanceTo(deck.partPositionWorld(arrest.part)) > LET_GO) {
                // Stopped: held on the brakes from here.
                plane.control.brakes = true
                plane.control.throttle = 0.0
                plane.control.autopilotNote = ""
                iterator.remove()
                continue
            }
            // Never more than stops it this tick.
            val most = minOf(arrest.decel * dt, speed)
            impulse.setTo(across).mulInPlace(-plane.body.mass * most / speed)
            push(plane, deck, impulse)
        }
    }

    // --- the catapults ---------------------------------------------------------------------

    /**
     * A craft on a catapult's near end, at full throttle with its brakes off, is ready, and held
     * there by the holdback against its own push. Held for [HOLD] seconds, it goes. Waiting for it to
     * sit still at full power, it never did: it was already rolling.
     */
    private fun armCatapults(vessels: Map<VesselId, Vessel>, dt: Double) {
        for (craft in vessels.values) {
            val deck = craft.standingOn
            if (deck == null || launching(craft.id) || craft.control.throttle < FULL || craft.control.brakes) { ready.remove(craft.id); continue }
            var on = -1
            for (c in deck.defs.indices) {
                val catapult = deck.defs[c].module<Catapult>() ?: continue
                if (deck.isBroken(c)) continue
                deck.worldToPartLocal(c, craft.body.position, local)
                if (abs(local.x) > catapult.width / 2.0) continue
                if (local.y < -catapult.stroke / 2.0 - START || local.y > -catapult.stroke / 2.0 + START) continue
                on = c
                break
            }
            relativeVelocity(craft, deck, craft.body.position, velocity)
            val across = flat(velocity, deck)
            if (on < 0 || (ready[craft.id] == null && across.length > SITTING)) { ready.remove(craft.id); continue }
            // The holdback: whatever it's gained across the deck, taken off again.
            impulse.setTo(across).mulInPlace(-craft.body.mass)
            push(craft, deck, impulse)
            val held = (ready[craft.id] ?: 0.0) + dt
            if (held < HOLD) { ready[craft.id] = held; continue }
            ready.remove(craft.id)
            val catapult = deck.defs[on].module<Catapult>()!!
            launches.add(Launch(craft.id, deck.id, on, catapult.endSpeed * catapult.endSpeed / (2.0 * catapult.stroke), catapult.endSpeed))
            craft.control.autopilotNote = "Catapult"
            // Hands off, a plane thrown off the bow with nothing holding it noses into the sea.
            // So the shot turns on its SAS, holding the way it's pointing, if it wasn't on already.
            if (!craft.control.sasEnabled) {
                craft.control.sasEnabled = true
                craft.control.sasMode = SasMode.HOLD
                craft.assistHolding = false
            }
            if (!deck.anchored) deck.wake()
        }
    }

    /** Pushes each launched craft along its track until it's at speed, off the end, or off the deck. */
    private fun throwLaunched(vessels: Map<VesselId, Vessel>, dt: Double) {
        val iterator = launches.iterator()
        while (iterator.hasNext()) {
            val launch = iterator.next()
            val craft = vessels[launch.plane]
            val deck = vessels[launch.deck]
            if (craft == null || deck == null || deck.isBroken(launch.part)) { iterator.remove(); continue }
            val catapult = deck.defs[launch.part].module<Catapult>()!!
            deck.worldToPartLocal(launch.part, craft.body.position, local)
            // Along the track, in the world.
            deck.design.parts[launch.part].rotation.rotate(Vec3.unitY(), axis)
            deck.body.orientation.rotate(axis, axis)
            relativeVelocity(craft, deck, craft.body.position, velocity)
            val along = velocity dot axis
            if (local.y > catapult.stroke / 2.0 || along >= launch.endSpeed || craft.standingOn !== deck) {
                craft.control.autopilotNote = ""
                iterator.remove()
                continue
            }
            impulse.setTo(axis).mulInPlace(craft.body.mass * minOf(launch.accel * dt, launch.endSpeed - along))
            push(craft, deck, impulse)
        }
    }

    // --- helpers ---------------------------------------------------------------------------

    /** [plane]'s velocity at [point] against [deck]'s there, into [out]. */
    private fun relativeVelocity(plane: Vessel, deck: Vessel, point: Vec3, out: Vec3): Vec3 {
        plane.body.velocityAtOffset(offset.setTo(point).subInPlace(plane.body.position), out)
        deck.body.velocityAtOffset(offset.setTo(point).subInPlace(deck.body.position), under)
        return out.subInPlace(under)
    }

    /** [v] with the part along [deck]'s up taken out, in place. */
    private fun flat(v: Vec3, deck: Vessel): Vec3 {
        deck.body.orientation.rotate(deck.design.orientation.up, under)
        return v.addScaledInPlace(under, -(v dot under))
    }

    /** [impulse] on [craft] at its middle, and the same back on [deck] where the craft is. */
    private fun push(craft: Vessel, deck: Vessel, impulse: Vec3) {
        craft.body.applyImpulseAtOffset(impulse, Vec3.zero())
        if (deck.body.inverseMass > 0.0) {
            offset.setTo(craft.body.position).subInPlace(deck.body.position)
            deck.body.applyImpulseAtOffset(impulse.mulInPlace(-1.0), offset)
        }
    }

    private companion object {
        const val STANDARD_GRAVITY = 9.81

        /** How far past a deck's own reach a hook is looked for, in metres. */
        const val REACH = 5.0

        /** How far below and above the wires a hook catches them, in metres. */
        const val LOWEST = -0.6
        const val HIGHEST = 0.8

        /** The slowest a hook can cross a wire and catch it, in m/s. */
        const val LEAST_CATCH_SPEED = 3.0

        /** Slower than this across the deck, in m/s, a caught plane is let go, held on its brakes. */
        const val STOPPED = 0.5

        /** Further than this from the wires, in metres, a caught plane is let go whatever it's doing. */
        const val LET_GO = 150.0

        /** The throttle, as a share, that counts as full on a catapult. */
        const val FULL = 0.95

        /** How far from the near end of a catapult, in metres, a craft counts as on it. */
        const val START = 8.0

        /** How still a craft has to sit on a catapult, in m/s, and for how long, in seconds, before it goes. */
        const val SITTING = 1.0
        const val HOLD = 1.5
    }
}
