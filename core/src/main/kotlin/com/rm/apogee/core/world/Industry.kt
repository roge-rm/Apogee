package com.rm.apogee.core.world

import com.rm.apogee.core.craft.Vessel
import com.rm.apogee.core.math.Quat
import com.rm.apogee.core.math.Vec3
import com.rm.apogee.core.orbit.CelestialBody
import com.rm.apogee.core.part.Converter
import com.rm.apogee.core.part.Drill
import com.rm.apogee.core.part.ResourceType
import com.rm.apogee.core.terrain.Deposits

/** What a craft's drills are doing, for its pilot. */
@kotlinx.serialization.Serializable
enum class DrillState {
    /** Switched off, or there are none. */
    OFF,
    DIGGING,
    /** Its bit is still swinging down. */
    EXTENDING,
    /** No drill head close enough to the ground. */
    NO_GROUND,
    /** Not still enough to dig. */
    MOVING,
    /** Nowhere to put what it digs. */
    FULL,
    /** Nothing here to dig. */
    BARREN,
    NO_POWER,
}

/**
 * Drills and converters, a step at a time: what a craft digs and refines in [step]'s time, and the
 * charge it takes. Both the tick (for craft being flown) and the power ledger (for parked craft and
 * bases nobody is near) share this, so a base mines and refines whether anyone is watching or not.
 */
class Industry {

    private val rotation = Quat.identity()
    private val tip = Vec3()
    private val offset = Vec3()
    private val fixed = Vec3()

    /**
     * One step of [dt] for [vessel] around [attractor] at [time]. It digs if it's [still] and has
     * [charge], refines if it has charge, puts the charge that took per second in
     * [Vessel.industryDraw], and returns it.
     */
    fun step(vessel: Vessel, attractor: CelestialBody, time: Double, dt: Double, still: Boolean, charge: Boolean): Double {
        var draw = 0.0
        var state = DrillState.OFF
        run {
            for (i in vessel.defs.indices) {
                val drill = vessel.defs[i].module<Drill>() ?: continue
                if (vessel.isBroken(i) || !vessel.running(i, vessel.control.drilling)) continue
                val here = dig(vessel, i, drill, attractor, time, dt, still, charge)
                if (here == DrillState.DIGGING) draw += drill.draw
                // The best of what its drills are doing is what the pilot hears about.
                if (state == DrillState.OFF || here.ordinal < state.ordinal) state = here
            }
        }
        vessel.drillState = state
        if (charge) {
            for (i in vessel.defs.indices) {
                val converter = vessel.defs[i].module<Converter>() ?: continue
                if (vessel.isBroken(i) || !vessel.running(i, vessel.control.refining)) continue
                if (convert(vessel, i, converter, dt)) draw += converter.draw
            }
        }
        vessel.industryDraw = draw
        return draw
    }

    private fun dig(vessel: Vessel, i: Int, drill: Drill, attractor: CelestialBody, time: Double, dt: Double, still: Boolean, charge: Boolean): DrillState {
        val terrain = attractor.terrain ?: return DrillState.NO_GROUND
        if (!still) return DrillState.MOVING
        if (!Power.deployed(vessel, i)) return DrillState.EXTENDING
        // The head, drawn out: part space, into the world.
        vessel.design.parts[i].rotation.rotate(drill.head, offset)
        vessel.body.orientation.rotate(offset, offset)
        vessel.partPositionWorld(i, tip).addInPlace(offset)
        attractor.rotationAt(time, rotation)
        attractor.toBodyFixed(tip, rotation, fixed)
        val gap = fixed.length - attractor.surfaceRadiusInBodyFrame(fixed.normalized())
        if (gap > drill.reach) return DrillState.NO_GROUND
        if (!charge) return DrillState.NO_POWER
        // What's down there, only worked out again once it has moved.
        val site = vessel.drillSite
        if (site.x.isNaN() || site.distanceTo(fixed) > SITE_MOVE) {
            site.setTo(fixed)
            vessel.drillOre = Deposits.richness(terrain, fixed, ResourceType.ORE)
            vessel.drillWater = Deposits.richness(terrain, fixed, ResourceType.WATER)
        }
        if (vessel.drillOre <= 0.0 && vessel.drillWater <= 0.0) return DrillState.BARREN
        val ore = vessel.putIntoGroupOf(i, ResourceType.ORE, drill.rate * vessel.drillOre * dt)
        val water = vessel.putIntoGroupOf(i, ResourceType.WATER, drill.rate * vessel.drillWater * dt)
        if (ore + water <= 0.0) return DrillState.FULL
        vessel.industryMoved = true
        return DrillState.DIGGING
    }

    /**
     * One step of converter [i]: each recipe as far as input and room allow. Returns whether it
     * ran.
     */
    private fun convert(vessel: Vessel, i: Int, converter: Converter, dt: Double): Boolean {
        var ran = false
        for (recipe in converter.recipes) {
            val want = recipe.inputRate * dt
            var share = (vessel.amountInGroupOf(i, recipe.input) / want).coerceAtMost(1.0)
            for ((type, rate) in recipe.outputs) {
                if (rate > 0.0) share = minOf(share, vessel.roomInGroupOf(i, type) / (rate * dt))
            }
            if (share <= 1e-6) continue
            vessel.drainFromGroupOf(i, recipe.input, want * share)
            for ((type, rate) in recipe.outputs) vessel.putIntoGroupOf(i, type, rate * dt * share)
            ran = true
        }
        if (ran) vessel.industryMoved = true
        return ran
    }

    companion object {
        /** How far a drill head can move, in metres, before the ground under it gets read again. */
        const val SITE_MOVE = 2.0
        /** The speed over the ground, in m/s, under which a landed craft is still enough to dig. */
        const val STILL = 0.3
    }
}
