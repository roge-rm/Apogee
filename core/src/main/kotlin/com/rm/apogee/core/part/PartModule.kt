package com.rm.apogee.core.part

import com.rm.apogee.core.math.SerialVec3
import com.rm.apogee.core.math.Vec3
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * A behaviour bolted onto a part.
 *
 * A part is a bag of modules rather than a subclass of Part, so the same tank
 * body can be given an engine, or a command pod given reaction wheels, purely
 * in data. This is what makes the eventual boats, planes and rovers additions
 * to the *catalogue* rather than additions to the engine: a hull is a part with
 * a [Buoyancy] module, a wing is a part with an [AeroSurface] module, and
 * neither needs a new code path in the simulation's structure.
 */
@Serializable
sealed interface PartModule

/**
 * Produces thrust along [thrustDirection] while throttled up, consuming
 * [propellant].
 *
 * Thrust and specific impulse both vary with ambient pressure, which is not a
 * detail - it is why a vacuum-optimised upper stage is a bad first stage, and
 * the whole reason staging is interesting.
 */
@Serializable
@SerialName("engine")
data class Engine(
    /** Newtons in vacuum. */
    val thrustVacuum: Double,
    /** Newtons at 1 atmosphere. */
    val thrustSeaLevel: Double,
    /** Specific impulse in seconds, vacuum. */
    val ispVacuum: Double,
    /** Specific impulse in seconds, at 1 atmosphere. */
    val ispSeaLevel: Double,
    /** Maximum gimbal deflection, degrees. Zero means fixed. */
    val gimbalRange: Double = 0.0,
    val propellant: ResourceType = ResourceType.PROPELLANT,
    /**
     * Direction, in part-local space, of the force applied to the craft -
     * i.e. opposite the exhaust. +Y by default, so a stack points its nose
     * along +Y.
     */
    val thrustDirection: SerialVec3 = Vec3(0.0, 1.0, 0.0),
    /** Minimum throttle as a fraction; solid motors would set this to 1.0. */
    val minThrottle: Double = 0.0,
) : PartModule

/** Stores [capacity] units of [resource]. */
@Serializable
@SerialName("tank")
data class Tank(
    val resource: ResourceType,
    val capacity: Double,
) : PartModule

/**
 * Makes a craft controllable, and supplies attitude authority without
 * expending reaction mass.
 */
@Serializable
@SerialName("command")
data class Command(
    val crewCapacity: Int = 0,
    /** Reaction-wheel authority, N·m, applied about each control axis. */
    val reactionTorque: Double = 0.0,
    /** Whether this pod alone is enough to fly the craft. */
    val providesControl: Boolean = true,
) : PartModule

/**
 * Splits the craft in two when staged. The part tree above it becomes a
 * separate vessel.
 */
@Serializable
@SerialName("decoupler")
data class Decoupler(
    /** Impulse applied to push the halves apart, N·s. */
    val ejectionImpulse: Double = 2_500.0,
) : PartModule

/** A wing, fin or control surface. */
@Serializable
@SerialName("aeroSurface")
data class AeroSurface(
    val liftCoefficient: Double,
    /** Reference area, m². */
    val area: Double,
    /** Whether it deflects with control input. */
    val controllable: Boolean = true,
    val controlAuthority: Double = 1.0,
) : PartModule

/** Drag device. Inert until deployed, then dominant. */
@Serializable
@SerialName("parachute")
data class Parachute(
    val deployedDragCoefficient: Double = 500.0,
    /** Safe deployment speed, m/s. Above this it tears away. */
    val maxDeploymentSpeed: Double = 300.0,
) : PartModule

/**
 * Displaces [displacedVolume] m³ when submerged.
 *
 * Not used by the rocket slice, and included deliberately: boats and
 * submarines were part of the brief from the start, and carrying the module in
 * the schema now means the buoyancy force is a force-source addition later
 * rather than a change to the part model everything else already depends on.
 */
@Serializable
@SerialName("buoyancy")
data class Buoyancy(
    val displacedVolume: Double,
) : PartModule

/**
 * A landing leg: a contact point on a spring.
 *
 * Gear is the difference between arriving and crashing, and the mechanism is
 * the suspension rather than the strength. A rigid contact stops a descending
 * craft in one tick, so the whole arrival appears in a single impulse and the
 * only question is whether that exceeded something's crash tolerance. A spring
 * spreads the same momentum over the travel, which is what a real leg is for.
 *
 * Deploys when its stage fires, like everything else. A stowed leg is inert -
 * its foot does not touch anything - so gear left up is a way to land badly
 * rather than a no-op.
 */
@Serializable
@SerialName("landingLeg")
data class LandingLeg(
    /** How far the leg compresses before it bottoms out, m. */
    val suspensionTravel: Double = 0.4,
    /**
     * Spring rate, N/m.
     *
     * Wants to hold the craft's landed weight at roughly half travel: too soft
     * and the leg is permanently bottomed out and behaves rigidly anyway, too
     * stiff and it may as well not be there.
     */
    val springRate: Double = 90_000.0,
    /** Damping, N per m/s. Without it the craft pogos off its own springs. */
    val damping: Double = 12_000.0,
) : PartModule

/**
 * Attitude and translation thrusters.
 *
 * The difference from [Engine] is what it is *for*: an engine changes where
 * you are going, a thruster block changes where you are. Nudging a landed
 * module a couple of metres so it touches the one beside it is the operation
 * that makes a base buildable, and a main engine cannot do it - it points one
 * way and delivers tonnes.
 *
 * Thrust is applied at each block's own offset, like everything else on a
 * craft, so a symmetric set cancels its own torque and a lone block off to one
 * side spins the craft. That is the behaviour worth having: where you put them
 * matters, and the game says so rather than quietly balancing it for you.
 */
@Serializable
@SerialName("rcs")
data class Rcs(
    /** Newtons, per block, along whichever axis is commanded. */
    val thrust: Double = 1_000.0,
    /** Specific impulse in seconds. Thrusters are thirsty and that is fine. */
    val isp: Double = 240.0,
    val propellant: ResourceType = ResourceType.MONOPROPELLANT,
) : PartModule

/** Generates electric charge from sunlight. */
@Serializable
@SerialName("solarPanel")
data class SolarPanel(
    /** Units per second at 1 AU, facing the star. */
    val chargeRate: Double,
) : PartModule

/** Stores electric charge. Distinct from [Tank] only for clarity in the UI. */
@Serializable
@SerialName("battery")
data class Battery(
    val capacity: Double,
) : PartModule
