package com.rm.apogee.core.scenario

import com.rm.apogee.core.craft.StockCraft
import com.rm.apogee.core.craft.Vessel
import com.rm.apogee.core.math.Vec3
import com.rm.apogee.core.orbit.CelestialBody
import com.rm.apogee.core.orbit.Orbit
import com.rm.apogee.core.part.Engine
import com.rm.apogee.core.part.ResourceType
import com.rm.apogee.core.part.StockParts
import com.rm.apogee.core.world.AttitudeController
import com.rm.apogee.core.world.World
import kotlin.math.PI
import kotlin.math.sqrt

/**
 * Flies the stock rocket from the pad to orbit, headless, in well under a second. It's the fast
 * check for physics work. The autopilot only drives [Vessel.control], the same path the player's
 * thumb drives, so a flight that works here can really be flown.
 */
class AscentScenario(
    private val targetApoapsisAltitude: Double = 100_000.0,
    private val turnEndAltitude: Double = 45_000.0,
    /** The weather to fly through. Null for still air. */
    private val weather: com.rm.apogee.core.weather.WeatherConfig? = null,
    /** What flies: the stock rocket unless another one is given. */
    private val design: (com.rm.apogee.core.part.PartCatalog) -> com.rm.apogee.core.craft.CraftDesign = { StockCraft.starterRocket(it) },
    /** The universe time to lift off at (a launch window), or null for the start. */
    private val launchAt: Double? = null,
    /** Called every tick before the autopilot steers, for jettisoning a fairing, say. */
    private val onTick: (World, Vessel) -> Unit = { _, _ -> },
) {
    enum class Phase { LIFTOFF, GRAVITY_TURN, COAST, CIRCULARISE, DONE, FAILED }

    class Telemetry(
        val time: Double,
        val phase: Phase,
        val altitude: Double,
        val surfaceSpeed: Double,
        val orbitalSpeed: Double,
        val apoapsisAltitude: Double,
        val periapsisAltitude: Double,
        val mass: Double,
        val propellant: Double,
        val stage: Int,
    ) {
        override fun toString(): String =
            "t=%6.1fs %-13s alt=%8.0fm vs=%6.0f vo=%6.0f ap=%9.0f pe=%10.0f m=%7.0fkg fuel=%6.1f st=%d"
                .format(
                    time, phase, altitude, surfaceSpeed, orbitalSpeed,
                    apoapsisAltitude, periapsisAltitude, mass, propellant, stage,
                )
    }

    class Result(
        val reachedOrbit: Boolean,
        val finalOrbit: Orbit,
        val apoapsisAltitude: Double,
        val periapsisAltitude: Double,
        val flightTime: Double,
        val propellantRemaining: Double,
        val log: List<Telemetry>,
        val failure: String? = null,
        /** The hardest any joint was loaded, as a share of its strength, and which part it was under. */
        val peakStress: Double = 0.0,
        val peakStressPart: String = "",
        /** Parts lost on the way. */
        val partsLost: Int = 0,
        /** The world and the craft as the flight left them, to fly on from. */
        val world: World? = null,
        val vessel: Vessel? = null,
    )

    fun fly(maxSeconds: Double = 2_400.0, logEvery: Double = 15.0): Result {
        val catalog = StockParts.catalog
        val world = World.default(catalog)
        world.weatherConfig = weather
        var peakStress = 0.0
        var peakStressPart = ""
        var partsLost = 0
        launchAt?.let { world.skipTo(it) }
        val vessel = world.spawnOnSurface(
            design(catalog),
            World.launchSites.first(),
        )
        val attractor = world.attractorFor(vessel)
        val autopilot = AttitudeController()

        val targetApoapsis = attractor.radius + targetApoapsisAltitude
        val log = ArrayList<Telemetry>()

        var phase = Phase.LIFTOFF
        var nextLogAt = world.time
        var failure: String? = null

        // Light the first stage.
        world.stage(vessel)
        vessel.control.throttle = 1.0
        vessel.control.sasEnabled = true

        val up = Vec3()
        val east = Vec3()
        val desired = Vec3()
        val dt = DT

        val liftoff = world.time
        while (world.time - liftoff < maxSeconds && phase != Phase.DONE && phase != Phase.FAILED) {
            val altitude = attractor.altitudeOf(vessel.body.position)
            val orbit = world.orbitOf(vessel)

            if (altitude < -100.0) {
                failure = "flew into the ground at t=${"%.1f".format(world.time - liftoff)}s"
                phase = Phase.FAILED
                break
            }

            basisAt(vessel, up, east)

            when (phase) {
                Phase.LIFTOFF -> {
                    desired.setTo(up)
                    if (altitude > 500.0) phase = Phase.GRAVITY_TURN
                }

                Phase.GRAVITY_TURN -> {
                    // Pitch over on a square-root profile: fast early, tapering as the air thins.
                    val progress = ((altitude - 500.0) / (turnEndAltitude - 500.0))
                        .coerceIn(0.0, 1.0)
                    val pitchFromVertical = (PI / 2.0) * sqrt(progress)
                    headingAt(pitchFromVertical, up, east, desired)

                    if (orbit.apoapsis >= targetApoapsis) {
                        vessel.control.throttle = 0.0
                        phase = Phase.COAST
                    }
                }

                Phase.COAST -> {
                    // Hold horizontal, waiting for apoapsis.
                    headingAt(PI / 2.0, up, east, desired)

                    // Start half a burn early so the burn straddles apoapsis. Otherwise the second
                    // half raises apoapsis and leaves the orbit elliptical.
                    val halfBurn = halfBurnSeconds(vessel, orbit, attractor)
                    if (orbit.timeToApoapsis <= halfBurn || orbit.apoapsis < targetApoapsis * 0.98) {
                        phase = Phase.CIRCULARISE
                    }
                }

                Phase.CIRCULARISE -> {
                    // Burn along the horizon. Prograde still has a vertical part here, which would
                    // push apoapsis up.
                    horizontalProgrgarde(vessel, up, desired)

                    val circularSpeed = sqrt(attractor.gravitationalParameter /
                        vessel.body.position.length)
                    val shortfall = circularSpeed - vessel.body.linearVelocity.length

                    // Taper so the last few m/s don't overshoot.
                    vessel.control.throttle = (shortfall / THROTTLE_TAPER_MARGIN)
                        .coerceIn(0.0, 1.0)

                    val periapsisAltitude = orbit.periapsis - attractor.radius
                    if (orbit.eccentricity < CIRCULAR_ENOUGH &&
                        periapsisAltitude > attractor.atmosphereHeight
                    ) {
                        vessel.control.throttle = 0.0
                        phase = Phase.DONE
                    }
                }

                else -> Unit
            }

            onTick(world, vessel)
            autopilot.steer(vessel, desired)

            // Stage as soon as the running stage is dry.
            if (vessel.propellantAvailableToActiveEngines(ResourceType.PROPELLANT) <= 1e-6 &&
                vessel.stagesRemaining > 0
            ) {
                world.stage(vessel)
            }

            if (phase == Phase.CIRCULARISE &&
                vessel.propellantAvailableToActiveEngines(ResourceType.PROPELLANT) <= 1e-6 &&
                vessel.stagesRemaining == 0
            ) {
                failure = "ran out of propellant before circularising"
                phase = Phase.FAILED
            }

            world.step(dt)
            if (vessel.stress > peakStress) {
                peakStress = vessel.stress
                peakStressPart = vessel.defs.getOrNull(vessel.worstJoint)?.id ?: ""
            }
            // Its own parts only. The spent stage is meant to break up in the sea.
            partsLost += world.drainEvents().count {
                (it is com.rm.apogee.core.world.WorldEvent.PartDetached && it.id == vessel.id) ||
                    (it is com.rm.apogee.core.world.WorldEvent.PartDestroyed && it.id == vessel.id)
            }

            if (world.time >= nextLogAt) {
                log.add(sample(world, vessel, attractor, phase))
                nextLogAt += logEvery
            }
        }

        if (phase != Phase.DONE && failure == null) {
            failure = "did not reach orbit within ${maxSeconds.toInt()}s (ended in $phase)"
        }

        val finalOrbit = world.orbitOf(vessel)
        log.add(sample(world, vessel, attractor, phase))

        return Result(
            reachedOrbit = phase == Phase.DONE,
            finalOrbit = finalOrbit,
            apoapsisAltitude = finalOrbit.apoapsis - attractor.radius,
            periapsisAltitude = finalOrbit.periapsis - attractor.radius,
            flightTime = world.time,
            propellantRemaining = vessel.amountOf(ResourceType.PROPELLANT),
            log = log,
            failure = failure,
            peakStress = peakStress,
            peakStressPart = peakStressPart,
            partsLost = partsLost,
            world = world,
            vessel = vessel,
        )
    }

    /** Half the circularisation burn's length, from its delta-v and the current thrust. */
    private fun halfBurnSeconds(
        vessel: Vessel,
        orbit: Orbit,
        attractor: CelestialBody,
    ): Double {
        val speedAtApoapsis = if (orbit.isBound) {
            // Vis-viva at apoapsis.
            sqrt(
                attractor.gravitationalParameter *
                    (2.0 / orbit.apoapsis - 1.0 / orbit.semiMajorAxis)
            )
        } else {
            vessel.body.linearVelocity.length
        }
        val circularAtApoapsis = sqrt(attractor.gravitationalParameter / orbit.apoapsis)
        val deltaV = (circularAtApoapsis - speedAtApoapsis).coerceAtLeast(0.0)

        var thrust = 0.0
        for (index in vessel.activeEngines()) {
            thrust += vessel.defs[index].module<Engine>()?.thrustVacuum ?: 0.0
        }
        if (thrust <= 0.0) return MIN_BURN_LEAD_SECONDS

        val acceleration = thrust / vessel.body.mass
        return (deltaV / acceleration / 2.0).coerceAtLeast(MIN_BURN_LEAD_SECONDS)
    }

    /** Velocity with its vertical part removed, normalised. */
    private fun horizontalProgrgarde(vessel: Vessel, up: Vec3, out: Vec3) {
        out.setTo(vessel.body.linearVelocity)
        val vertical = out dot up
        out.addScaledInPlace(up, -vertical)
        if (out.lengthSq < 1e-9) out.setTo(vessel.body.linearVelocity)
        out.normalizeInPlace()
    }

    /** Local up and the eastward direction at the vessel's position. */
    private fun basisAt(vessel: Vessel, up: Vec3, east: Vec3) {
        up.setTo(vessel.body.position).normalizeInPlace()
        // North is the world +Y axis, and east is north x up.
        east.setTo(Vec3.unitY()).crossInPlace(up)
        if (east.lengthSq < 1e-12) east.setTo(Vec3.unitZ()) else east.normalizeInPlace()
    }

    /** A heading [pitchFromVertical] radians away from straight up, toward the east. */
    private fun headingAt(pitchFromVertical: Double, up: Vec3, east: Vec3, out: Vec3) {
        val c = kotlin.math.cos(pitchFromVertical)
        val s = kotlin.math.sin(pitchFromVertical)
        out.setTo(
            up.x * c + east.x * s,
            up.y * c + east.y * s,
            up.z * c + east.z * s,
        ).normalizeInPlace()
    }

    private fun sample(
        world: World,
        vessel: Vessel,
        attractor: CelestialBody,
        phase: Phase,
    ): Telemetry {
        val orbit = world.orbitOf(vessel)
        val surfaceVelocity = attractor.surfaceVelocityAt(vessel.body.position)
        return Telemetry(
            time = world.time,
            phase = phase,
            altitude = attractor.altitudeOf(vessel.body.position),
            surfaceSpeed = (vessel.body.linearVelocity - surfaceVelocity).length,
            orbitalSpeed = vessel.body.linearVelocity.length,
            apoapsisAltitude = orbit.apoapsis - attractor.radius,
            periapsisAltitude = orbit.periapsis - attractor.radius,
            mass = vessel.body.mass,
            propellant = vessel.amountOf(ResourceType.PROPELLANT),
            stage = vessel.currentStage,
        )
    }

    companion object {
        const val DT = 1.0 / 60.0

        /** The floor on the circularisation lead time, in seconds. */
        const val MIN_BURN_LEAD_SECONDS = 2.0

        /** The velocity shortfall, in m/s, over which the throttle is wide open. */
        const val THROTTLE_TAPER_MARGIN = 40.0

        /** The eccentricity at or below which the orbit counts as circular. */
        const val CIRCULAR_ENOUGH = 0.005
    }
}

/** You can run this directly for physics work. It prints the whole flight. */
fun main() {
    val result = AscentScenario().fly()
    result.log.forEach(::println)
    println()
    println("reached orbit : ${result.reachedOrbit}")
    println("apoapsis      : ${"%.0f".format(result.apoapsisAltitude)} m")
    println("periapsis     : ${"%.0f".format(result.periapsisAltitude)} m")
    println("flight time   : ${"%.1f".format(result.flightTime)} s")
    println("fuel left     : ${"%.1f".format(result.propellantRemaining)} units")
    result.failure?.let { println("FAILURE       : $it") }
}
