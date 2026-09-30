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
import com.rm.apogee.core.weather.Climate

/**
 * A craft's power, tick by tick: what its panels, alternators and fuel cells make, what its pods,
 * reaction wheels, assists, lamps and antennas use, and whether it has any left. With none it goes
 * dark. See `World.step` for what that takes away.
 */
class Power(private val system: SolarSystem) {

    private val scratchHere = Vec3()
    private val scratchRel = Vec3()
    private val scratchSun = Vec3()
    private val scratchFace = Vec3()
    private val scratchSunDir = Vec3()

    /**
     * How much of the sun reaches [position] (relative to [attractor]'s centre) at [time]. It's 1
     * in the sun, and 0 in the shadow of the body or its parent or moons, which is a cylinder
     * behind each one along the sun's direction.
     */
    fun sunlight(attractor: CelestialBody, position: Vec3, time: Double): Double {
        val sun = system.sunDirection(attractor.id, position, time, scratchSunDir)
        scratchHere.setTo(system.positionOf(attractor.id, time)).addInPlace(position)
        for (body in shadowers(attractor)) {
            // The star doesn't cast a shadow of its own.
            if (body.parentId == null) continue
            scratchRel.setTo(system.positionOf(body.id, time)).negateInPlace().addInPlace(scratchHere)
            val along = scratchRel dot sun
            if (along >= 0.0) continue
            scratchRel.addScaledInPlace(sun, -along)
            if (scratchRel.length < body.radius) return 0.0
        }
        return 1.0
    }

    /**
     * How much of the sunlight the air lets through to [vessel], 0..1. All of it on an airless
     * world and in Terra's sky, a tenth under Caligo's deck or Aurantia's haze, and very little
     * inside a dust storm.
     */
    fun skyShade(vessel: Vessel, attractor: CelestialBody): Double {
        if (attractor.atmosphere == null) return 1.0
        val climate = Climate.of(attractor.id) ?: return 1.0
        var through = climate.sunThrough(vessel.body.position.length - attractor.radius)
        val air = vessel.air
        if (climate.stormShade > 0.0 && air.cloudType == climate.stormCloud) through *= 1.0 - climate.stormShade * air.cloudDensity
        return through
    }

    private val shadowerLists = HashMap<String, List<CelestialBody>>()

    /**
     * The bodies whose shadow can fall on a craft in [attractor]'s pull: it, its planet and its
     * moons.
     */
    private fun shadowers(attractor: CelestialBody): List<CelestialBody> = shadowerLists.getOrPut(attractor.id) {
        buildList {
            add(attractor)
            attractor.parentId?.let { system.body(it) }?.takeIf { it.parentId != null }?.let { add(it) }
            addAll(system.childrenOf(attractor.id))
        }
    }

    /**
     * One step of [dt] for awake [vessel] around [attractor] at [time]: the charge it made and
     * used, its fuel cells switched on or off, and [Vessel.powered] and [Vessel.powerNet] brought
     * up to date. On [rails] nothing is steering, so only what runs by itself draws power.
     */
    fun step(vessel: Vessel, attractor: CelestialBody, time: Double, dt: Double, rails: Boolean = false) {
        val capacity = vessel.capacityOf(ResourceType.ELECTRIC_CHARGE)
        // As bright as the distance from the star leaves it, so faint out among the giants.
        val lit = sunlight(attractor, vessel.body.position, time) * system.sunStrength(attractor.id, vessel.body.position, time) *
            skyShade(vessel, attractor) * seaShade(attractor, vessel.body.position)
        val sun = system.sunDirection(attractor.id, vessel.body.position, time, scratchSunDir)
        // The sun in the craft's own axes, for which way each panel faces.
        vessel.body.orientation.inverseRotate(sun, scratchSun)
        // Dark at night, and under the sea deep enough that the daylight is gone.
        val night = (scratchFace.setTo(vessel.body.position).normalizeInPlace() dot sun) < World.LAMP_DUSK ||
            (attractor.ocean != null && attractor.altitudeOf(vessel.body.position) < -World.LAMP_DEPTH)
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
                is com.rm.apogee.core.part.Generator -> made += module.rate
                is Command -> used += module.idleDraw
                is Antenna -> if (!module.deployable || deployed(vessel, i)) used += module.draw
                is Lamp -> if (vessel.running(i, night)) used += module.draw
                is FuelCell -> { cells += module.rate; cellMono += module.rate * module.monoPerCharge; anyCell = i }
                is com.rm.apogee.core.part.Scanner -> used += module.draw
                is com.rm.apogee.core.part.Sonar -> used += module.draw
                else -> Unit
            }
        }
        // Its drills and converters, as they ran this step.
        used += vessel.industryDraw
        used += vessel.winchDraw
        if (!rails) {
            val control = vessel.control
            // On the ground or the water, the wheels and the assist are steering against what the
            // ground and the water mostly hold anyway.
            if (!vessel.touchingGround && !vessel.afloat) {
                used += vessel.wheelWork * WHEEL_DRAW
                if (control.sasEnabled && vessel.powered) used += SAS_DRAW
            }
            if (control.autoBurn || control.autoLand) used += AUTOPILOT_DRAW
        }
        // Fuel cells come on when running low and go off when it's well back up, and only while
        // there's monopropellant.
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

    /**
     * How squarely panel [i] faces the sun, 0..1. A fold-out one counts when it's out and turned
     * toward it, and a fixed one by its face.
     */
    private fun facing(vessel: Vessel, i: Int, panel: SolarPanel): Double {
        if (panel.deployable) return if (deployed(vessel, i)) 1.0 else 0.0
        val normal = panel.normal ?: return 1.0
        vessel.design.parts[i].rotation.rotate(normal, scratchFace)
        return (scratchFace.normalizeInPlace() dot scratchSun).coerceAtLeast(0.0)
    }

    companion object {
        /**
         * Charge per second for each N·m of reaction wheel torque used. A pod's wheels flat out use
         * 0.1 a second.
         */
        const val WHEEL_DRAW = 2.0e-5
        /** Stability assist holding and an autopilot flying, in units a second. */
        const val SAS_DRAW = 0.01
        const val AUTOPILOT_DRAW = 0.05
        /** Fuel cells cut in under this share of charge, and out above this. */
        const val CELLS_ON = 0.25
        const val CELLS_OFF = 0.9
        /** Under this share of charge the HUD warns you. */
        const val LOW = 0.2

        /**
         * Charge left, or being made faster than it's used, or no battery at all. A craft with
         * nothing to run flat, like a buggy's open seat, runs straight off what it has.
         */
        fun poweredNow(vessel: Vessel, capacity: Double, net: Double): Boolean =
            capacity <= 0.0 || net > 0.0 || vessel.amountOf(ResourceType.ELECTRIC_CHARGE) > 0.0

        /**
         * How much daylight reaches [position] under the sea of [attractor], 0..1. All of it in the
         * air, and it fades by e every [LIGHT_FADE] metres down, so a panel on a base fifty metres
         * down makes a twelfth of what it would in the sun.
         */
        fun seaShade(attractor: CelestialBody, position: Vec3): Double {
            if (attractor.ocean == null) return 1.0
            val down = -attractor.altitudeOf(position)
            return if (down <= 0.0) 1.0 else kotlin.math.exp(-down / LIGHT_FADE)
        }

        /** The depth, in metres, over which daylight in the sea fades by e. */
        const val LIGHT_FADE = 20.0

        /** Whether fold-out part [i] of [vessel] is all the way out. */
        fun deployed(vessel: Vessel, i: Int): Boolean = vessel.legDeploy.getOrElse(i) { 0.0 } >= 0.99
    }
}
