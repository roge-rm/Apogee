package com.rm.apogee.core.world

import com.rm.apogee.core.craft.Vessel
import com.rm.apogee.core.math.Vec3
import com.rm.apogee.core.orbit.CelestialBody
import com.rm.apogee.core.orbit.SolarSystem
import com.rm.apogee.core.part.Antenna
import com.rm.apogee.core.part.Command
import com.rm.apogee.core.part.Engine
import com.rm.apogee.core.part.FuelCell
import com.rm.apogee.core.part.Lamp
import com.rm.apogee.core.part.ResourceType
import com.rm.apogee.core.part.SolarPanel

/**
 * A craft's power, tick by tick: what its panels, alternators and fuel
 * cells make, what its pods, reaction wheels, assists, lamps and antennas
 * use, and whether it has any left. With none it is dark: see
 * `World.step` for what that takes away.
 */
class Power(private val system: SolarSystem) {

    private val scratchHere = Vec3()
    private val scratchRel = Vec3()
    private val scratchSun = Vec3()
    private val scratchFace = Vec3()

    /**
     * How much of the sun reaches [position] - relative to [attractor]'s
     * centre - at [time]: 1 in it, 0 in the shadow of the body or of its
     * parent or moons, a cylinder behind each along the sun's direction.
     */
    fun sunlight(attractor: CelestialBody, position: Vec3, time: Double): Double {
        val sun = LaunchTime.SUN_DIRECTION
        scratchHere.setTo(system.positionOf(attractor.id, time)).addInPlace(position)
        for (body in shadowers(attractor)) {
            scratchRel.setTo(system.positionOf(body.id, time)).negateInPlace().addInPlace(scratchHere)
            val along = scratchRel dot sun
            if (along >= 0.0) continue
            scratchRel.addScaledInPlace(sun, -along)
            if (scratchRel.length < body.radius) return 0.0
        }
        return 1.0
    }

    private val shadowerLists = HashMap<String, List<CelestialBody>>()

    /** The bodies whose shadow can fall on a craft in [attractor]'s pull: it, its planet, its moons. */
    private fun shadowers(attractor: CelestialBody): List<CelestialBody> = shadowerLists.getOrPut(attractor.id) {
        buildList {
            add(attractor)
            attractor.parentId?.let { system.body(it) }?.takeIf { it.parentId != null }?.let { add(it) }
            addAll(system.childrenOf(attractor.id))
        }
    }

    /**
     * One step of [dt] for awake [vessel] about [attractor] at [time]: its
     * charge made and used, its fuel cells switched, and [Vessel.powered]
     * and [Vessel.powerNet] brought up to date. On [rails] nothing is
     * steering, so only what runs by itself draws.
     */
    fun step(vessel: Vessel, attractor: CelestialBody, time: Double, dt: Double, rails: Boolean = false) {
        val capacity = vessel.capacityOf(ResourceType.ELECTRIC_CHARGE)
        val lit = sunlight(attractor, vessel.body.position, time)
        // The sun in the craft's own axes, for which way each panel faces.
        vessel.body.orientation.inverseRotate(LaunchTime.SUN_DIRECTION, scratchSun)
        val night = (scratchFace.setTo(vessel.body.position).normalizeInPlace() dot LaunchTime.SUN_DIRECTION) < World.LAMP_DUSK
        var made = 0.0
        var used = 0.0
        var cells = 0.0
        var cellMono = 0.0
        var anyCell = -1
        for (i in vessel.defs.indices) {
            if (vessel.isBroken(i)) continue
            for (module in vessel.defs[i].modules) when (module) {
                is SolarPanel -> if (lit > 0.0) made += module.chargeRate * lit * facing(vessel, i, module)
                is Engine -> made += module.alternator * vessel.engineOutput.getOrElse(i) { 0.0 }
                is Command -> used += module.idleDraw
                is Antenna -> if (!module.deployable || deployed(vessel, i)) used += module.draw
                is Lamp -> if (night) used += module.draw
                is FuelCell -> { cells += module.rate; cellMono += module.rate * module.monoPerCharge; anyCell = i }
                is com.rm.apogee.core.part.Scanner -> used += module.draw
                else -> Unit
            }
        }
        // Its drills and converters, as they ran this step.
        used += vessel.industryDraw
        if (!rails) {
            val control = vessel.control
            // On the ground or the water the wheels and the assist are
            // steering what the ground and the water mostly hold anyway.
            if (!vessel.touchingGround && !vessel.afloat) {
                used += vessel.wheelWork * WHEEL_DRAW
                if (control.sasEnabled && vessel.powered) used += SAS_DRAW
            }
            if (control.autoBurn || control.autoLand) used += AUTOPILOT_DRAW
        }
        // Fuel cells: on when running low, off when well up again, and only while there is monopropellant.
        if (anyCell >= 0 && capacity > 0.0) {
            val share = vessel.amountOf(ResourceType.ELECTRIC_CHARGE) / capacity
            if (!vessel.fuelCellsOn && share < CELLS_ON) vessel.fuelCellsOn = true
            else if (vessel.fuelCellsOn && share > CELLS_OFF) vessel.fuelCellsOn = false
            if (vessel.fuelCellsOn) {
                val want = cellMono * dt
                val got = vessel.drainFromGroupOf(anyCell, ResourceType.MONOPROPELLANT, want)
                if (got <= 0.0) vessel.fuelCellsOn = false
                else made += cells * (got / want)
            }
        }
        val net = made - used
        if (net >= 0.0) vessel.storeCharge(net * dt)
        else if (!vessel.drawCharge(-net * dt)) vessel.drawCharge(vessel.amountOf(ResourceType.ELECTRIC_CHARGE))
        vessel.powerNet = net
        vessel.powered = poweredNow(vessel, capacity, net)
    }

    /** How squarely panel [i] meets the sun, 0..1: a fold-out one out and turned to it, a fixed one by its face. */
    private fun facing(vessel: Vessel, i: Int, panel: SolarPanel): Double {
        if (panel.deployable) return if (deployed(vessel, i)) 1.0 else 0.0
        val normal = panel.normal ?: return 1.0
        vessel.design.parts[i].rotation.rotate(normal, scratchFace)
        return (scratchFace.normalizeInPlace() dot scratchSun).coerceAtLeast(0.0)
    }

    companion object {
        /** Charge a second for each N·m of reaction-wheel torque worked: a pod's wheels flat out, 0.1 a second. */
        const val WHEEL_DRAW = 2.0e-5
        /** Stability assist holding, and an autopilot flying, units a second. */
        const val SAS_DRAW = 0.01
        const val AUTOPILOT_DRAW = 0.05
        /** Fuel cells cut in under this share of charge, and out over this. */
        const val CELLS_ON = 0.25
        const val CELLS_OFF = 0.9
        /** Under this share of charge the HUD warns. */
        const val LOW = 0.2

        /**
         * Charge left, or being made faster than used - or no battery at all:
         * a craft with nothing to run flat, a buggy's open seat, runs straight
         * off what it has.
         */
        fun poweredNow(vessel: Vessel, capacity: Double, net: Double): Boolean =
            capacity <= 0.0 || net > 0.0 || vessel.amountOf(ResourceType.ELECTRIC_CHARGE) > 0.0

        /** Whether fold-out part [i] of [vessel] is all the way out. */
        fun deployed(vessel: Vessel, i: Int): Boolean = vessel.legDeploy.getOrElse(i) { 0.0 } >= 0.99
    }
}
