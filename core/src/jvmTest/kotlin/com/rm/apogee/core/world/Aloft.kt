package com.rm.apogee.core.world

import com.rm.apogee.core.craft.CraftDesign
import com.rm.apogee.core.craft.Vessel
import com.rm.apogee.core.math.Vec3
import com.rm.apogee.core.math.quatFromTo
import com.rm.apogee.core.orbit.SolarSystem

/** Helpers for the tests of things that fly without wings: putting them up and watching them. */
object Aloft {
    const val DT = 1.0 / 60.0

    /** [design] [height] metres over the sea west of the Cape on [bodyId], level and still, crewed. */
    fun spawn(world: World, design: CraftDesign, height: Double, bodyId: String = "terra"): Vessel {
        val body = world.system.body(bodyId)!!
        val up = if (bodyId == "terra") SolarSystem.capeDirection(-3_000.0, 500.0) else Vec3(0.0, 0.0, 1.0)
        val ground = if (bodyId == "terra") 0.0 else (body.terrain?.elevation(up) ?: 0.0).coerceAtLeast(0.0)
        val position = Vec3().setTo(up).mulInPlace(body.radius + ground + height)
        val velocity = body.surfaceVelocityAt(position, Vec3())
        val vessel = world.spawnAt(design, bodyId, position, velocity, quatFromTo(design.orientation.up, up))
        world.assignOwner(vessel, "p1")
        world.seatCrew(vessel)
        return vessel
    }

    /** [vessel]'s rotors already turning at the speed [throttle] holds them at, as if it's been flying. */
    fun spinUp(vessel: Vessel, throttle: Double) {
        vessel.fitPose()
        for (i in vessel.defs.indices) {
            if (vessel.defs[i].module<com.rm.apogee.core.part.Rotor>() != null) vessel.spool[i] = kotlin.math.sqrt(throttle)
        }
    }

    fun run(world: World, seconds: Double, each: () -> Unit = {}) {
        var t = 0.0
        while (t < seconds) { world.step(DT); t += DT; each() }
    }

    fun altitude(world: World, vessel: Vessel): Double = world.attractorFor(vessel).altitudeOf(vessel.body.position)

    /** Its climb over the ground, in m/s. */
    fun climb(world: World, vessel: Vessel): Double {
        val body = world.attractorFor(vessel)
        val ground = vessel.body.linearVelocity.copy().subInPlace(body.surfaceVelocityAt(vessel.body.position, Vec3()))
        return ground dot vessel.body.position.normalized()
    }

    /** How far [vessel] is from where it was when [fixed] was taken, body-fixed. */
    fun moved(world: World, vessel: Vessel, fixed: Vec3): Double {
        val body = world.attractorFor(vessel)
        return body.toBodyFixed(vessel.body.position, body.rotationAt(world.time)).distanceTo(fixed)
    }

    fun fixed(world: World, vessel: Vessel): Vec3 {
        val body = world.attractorFor(vessel)
        return body.toBodyFixed(vessel.body.position, body.rotationAt(world.time))
    }

    /** Degrees its up is off the sky's. */
    fun tilt(vessel: Vessel): Double {
        val up = vessel.body.orientation.rotate(vessel.design.orientation.up)
        return Math.toDegrees(kotlin.math.acos((up dot vessel.body.position.normalized()).coerceIn(-1.0, 1.0)))
    }
}
