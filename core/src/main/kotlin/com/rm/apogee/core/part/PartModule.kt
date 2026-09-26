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
    /**
     * For a propeller that works in water - an outboard motor: where its
     * propeller is, part-local. The engine pushes only while that point is
     * under water, and in proportion to how far under it is.
     */
    val waterProp: SerialVec3? = null,
    /** What comes out of the back, for drawing; null lets [exhaustKind] decide. */
    val exhaust: Exhaust? = null,
) : PartModule {
    /**
     * What it leaves behind it: a water propeller churns water, anything
     * with no vacuum thrust breathes air - a jet unless it says it is a
     * propeller - and the rest are rockets.
     */
    val exhaustKind: Exhaust
        get() = exhaust ?: when {
            waterProp != null -> Exhaust.WATER
            thrustVacuum <= 0.0 -> Exhaust.JET
            else -> Exhaust.ROCKET
        }
}

/** The kinds of exhaust an engine leaves, for plumes and smoke. */
@Serializable
enum class Exhaust {
    /** Flame and smoke, widening with altitude into a vacuum bloom. */
    @SerialName("rocket") ROCKET,
    /** A faint hot core, and contrails high up. */
    @SerialName("jet") JET,
    /** No flame: prop wash, dust near the ground. */
    @SerialName("prop") PROP,
    /** Churned water behind it. */
    @SerialName("water") WATER,
}

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
    /**
     * Stays on the craft it is mounted to, letting go only of what it holds:
     * a release clamp on a flatbed, setting its load down on the ground
     * beside the truck - its right-hand side - level and just clear. What it
     * lets go of is cargo, not a spent stage: it stays the owner's.
     */
    val stays: Boolean = false,
) : PartModule

/** A wing, fin or control surface. */
@Serializable
@SerialName("aeroSurface")
data class AeroSurface(
    val liftCoefficient: Double,
    /** Reference area, m². */
    val area: Double,
    /**
     * Whether it deflects with control input.
     *
     * A stabiliser is not a control surface. Fins that hold a rocket straight
     * want this false: making them steerable as well hands the ascent a
     * second, much stronger set of controls that its guidance was never
     * written for, and the stock rocket stopped reaching orbit.
     */
    val controllable: Boolean = true,
    val controlAuthority: Double = 1.0,
    /**
     * How far it deflects, degrees.
     *
     * The force follows sin(d)cos(d) of this, not the surface's full
     * broadside force - a control surface moves through twenty degrees or so,
     * not ninety.
     */
    val maxDeflection: Double = 20.0,
    /**
     * The normal force, N, it fails beyond - lift and control together. Zero
     * means "work it out from its size", which is how every stock surface
     * gets one: see [loadLimit].
     */
    val maxLoad: Double = 0.0,
) : PartModule {
    /**
     * What it takes to break it, N. By default what its own lift would be
     * at [DESIGN_LOAD] - a strong gust at speed, or a hard pull in rough air,
     * well past anything the stock craft do flying normally.
     */
    val loadLimit: Double get() = if (maxLoad > 0.0) maxLoad else area * liftCoefficient * DESIGN_LOAD

    companion object {
        /** Pascals of lift-equivalent loading a surface is built for. */
        const val DESIGN_LOAD = 12_000.0
    }
}

/**
 * A rudder, keel or hydrofoil: a flying surface for water.
 *
 * The same flat-plate physics as [AeroSurface], with water's density, and only
 * for as much of it as is under the surface. Water is eight hundred times
 * denser than air, so a keel a fraction of a sail's size is what stops a boat
 * sliding sideways, and a rudder the size of a door steers a ship.
 *
 * The plate lies in the part's X-Y plane - span along X, chord along Y - as a
 * fin's does. [controllable] surfaces deflect with the stick by the same rule
 * as aircraft surfaces: which way follows from where it is bolted, so a
 * rudder under the stern yaws the boat.
 */
@Serializable
@SerialName("hydroSurface")
data class HydroSurface(
    val liftCoefficient: Double = 1.2,
    /** Reference area, m². */
    val area: Double,
    val controllable: Boolean = false,
    val controlAuthority: Double = 1.0,
    val maxDeflection: Double = 30.0,
    /** Half its depth, metres: how far either side of its centre it reaches. */
    val halfDepth: Double = 0.5,
) : PartModule

/** Drag device. Inert until deployed, then dominant. */
@Serializable
@SerialName("parachute")
data class Parachute(
    val deployedDragCoefficient: Double = 500.0,
    /** Safe deployment speed, m/s. Above this it tears away. */
    val maxDeploymentSpeed: Double = 300.0,
) : PartModule {
    /**
     * Staging a chute arms it; it opens itself once it is safe - slow enough
     * not to shred and in air thick enough to fill it - as a small drogue: a
     * fast, steady fall, a couple of minutes from high up. Near the ground it
     * opens fully, for a gentle last stretch. Its state rides in the part's
     * deploy value: 0 packed, up to [DROGUE_FULL] the drogue filling, up to
     * 1 the main, below 0 cut away after landing.
     */
    companion object {
        /** Deploy value with the drogue full and the main not yet out. */
        const val DROGUE_FULL = 0.5

        /** The drogue's drag, as a share of the full canopy's. */
        const val DROGUE_SHARE = 0.06

        /** Height over the ground or sea at which the main opens, m. */
        const val MAIN_HEIGHT = 200.0

        /** How much of the full canopy's drag a chute at [deploy] gives. */
        fun dragShare(deploy: Double): Double = when {
            deploy <= 0.0 -> 0.0
            deploy <= DROGUE_FULL -> DROGUE_SHARE * (deploy / DROGUE_FULL).let { it * it }
            else -> DROGUE_SHARE + (1.0 - DROGUE_SHARE) * ((deploy - DROGUE_FULL) / (1.0 - DROGUE_FULL)).let { it * it }
        }

        /** Opens at this share of [maxDeploymentSpeed] or slower. */
        const val OPEN_SHARE = 0.95

        /** Air at least this dense, kg/m³: high enough up, a canopy has nothing to fill it. */
        const val OPEN_DENSITY = 0.04

        /** How long it takes to fill, s. */
        const val INFLATE_SECONDS = 1.5

        /** The most an opening chute pulls, in g of the craft it carries. */
        const val MOST_PULL_G = 6.0

        /** Down, and this slow or slower, m/s: cut away, so the wind cannot drag the craft along the ground. */
        const val CUT_SPEED = 3.0

        /** Or down this long, s, however fast it is being dragged. */
        const val CUT_AFTER = 1.5
    }
}

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
    /**
     * Open to the sky, as a skiff or a punt is: a wave coming over its top
     * edge - the gunwale - or a heel that puts it under pours in, and enough
     * water in it sinks it. A decked hull ships none until it is holed.
     */
    val open: Boolean = false,
) : PartModule {
    companion object {
        /** Share of a hull box that is room for water. */
        const val INTERIOR = 0.85

        /** How much water, kg, part [def] holds full - [density] kg/m³ - or 0 if it is no hull. */
        fun capacity(def: PartDef, density: Double = 1_025.0): Double {
            if (def.module<Buoyancy>() == null) return 0.0
            val box = def.mesh as? MeshSpec.Box ?: return 0.0
            return box.width * box.height * box.depth * INTERIOR * density
        }
    }
}

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
    /** Seconds from stowed to fully deployed. */
    val deployTime: Double = 1.5,
    /**
     * Where it folds, in part space, and about which axis. Stowed, the leg
     * is turned [stowedAngle] degrees about [foldAxis] through [hinge] from
     * its deployed pose - and its feet are wherever that puts them, for the
     * ground to meet as it meets anything else.
     */
    val hinge: SerialVec3 = Vec3(0.0, 0.0, 0.0),
    val foldAxis: SerialVec3 = Vec3(0.0, 0.0, 1.0),
    /** Zero: a leg that does not fold, whose feet are always where they are. */
    val stowedAngle: Double = 0.0,
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

/**
 * A driven, sprung, rolling contact.
 *
 * Mechanically a [LandingLeg] with two additions, and that is the point: a
 * rover is a craft whose feet turn. The suspension is the leg's, unchanged.
 * What makes it a wheel is that it rolls - friction along its rolling axis is
 * reduced to [rollingResistance] while grip *across* that axis stays full -
 * and that it can be driven.
 *
 * Without the anisotropic friction there is no such thing as a wheel here:
 * the ground friction that stops a landed craft sliding is exactly what would
 * stop a rover being pushed, and the motor would have to out-muscle it rather
 * than roll past it.
 */
@Serializable
@SerialName("wheel")
data class Wheel(
    /** Metres. Sets how far the axle rides above the ground. */
    val radius: Double = 0.35,
    /** Newtons of tractive effort at full throttle, per wheel, from rest. */
    val motorForce: Double = 0.0,
    /**
     * Ground speed at which the motor runs out of pull, m/s.
     *
     * Tractive effort falls off linearly to nothing here, which is roughly
     * what a real motor does and is what gives a rover a top speed at all.
     * Without it the drive force is constant and a rover simply keeps
     * accelerating - it reached 180 km/h on flat ground, which is a rocket
     * sled rather than a vehicle.
     */
    val topSpeed: Double = 22.0,
    /** Whether the steering input turns this wheel. */
    val steerable: Boolean = false,
    /** Maximum steering deflection, degrees. */
    val steeringRange: Double = 30.0,
    /**
     * Friction coefficient along the rolling direction.
     *
     * Small but not zero: a free wheel still costs something to push, and at
     * zero a parked rover slides down the gentlest slope for ever.
     */
    val rollingResistance: Double = 0.06,
    /**
     * Friction coefficient along the rolling direction with the brakes on.
     *
     * Not the full ground friction a locked wheel would give. Braking at a
     * whole 0.6 g threw the stock rover over its own front wheels - its
     * centre of mass sits higher than half its wheelbase, which is exactly
     * the condition for a nose-over - and real brakes are modulated for the
     * same reason. Still enough to hold a parked craft on a slope of
     * atan(brakeFriction): about nineteen degrees at the default.
     */
    val brakeFriction: Double = 0.35,
    val suspensionTravel: Double = 0.25,
    val springRate: Double = 60_000.0,
    val damping: Double = 6_000.0,
) : PartModule

/**
 * Feet that pin a base to the ground. A craft with one working, resting on
 * the ground, can be anchored: levelled on its feet - each reaching down as
 * far as [travel] to meet ground that is not flat, up to [maxSlope] - and
 * from then immovable. See `World.anchor`.
 */
@Serializable
@SerialName("foundation")
data class Foundation(
    /** How far its feet reach down to level it, m. */
    val travel: Double = 1.0,
    /** The steepest ground it will level on, degrees. */
    val maxSlope: Double = 10.0,
) : PartModule

/**
 * Makes the part a place to launch from: a craft can be set down on it, and
 * takes its propellant from the base's stores. See `World.spawnOnPad`.
 */
@Serializable
@SerialName("launchPad")
data class LaunchPad(
    /** Charge a launch takes from the base, units. */
    val launchCharge: Double = 50.0,
) : PartModule

/**
 * Moves propellant and charge between a base and a craft docked to it or
 * standing on its pad, while the base has power.
 */
@Serializable
@SerialName("pump")
data class Pump(
    /** Units a second, of whatever it moves. */
    val rate: Double = 20.0,
    /** Charge it draws while pumping, units a second. */
    val draw: Double = 2.0,
) : PartModule

/** A light: lit at night while there is power for it. */
@Serializable
@SerialName("lamp")
data class Lamp(
    /** Charge it draws while lit, units a second. */
    val draw: Double = 0.2,
    /** How far its pool of light reaches on the ground, m; 0 for a lamp seen but lighting nothing. */
    val reach: Double = 0.0,
    /**
     * How far out in front of it - along its +Z, level - its pool is
     * centred, m: a floodlight is aimed at what it lights; 0 for a lamp that
     * lights all round it.
     */
    val aim: Double = 0.0,
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

/**
 * Takes the heat of re-entry on itself by burning away its ablator. It
 * heats like anything else until it reaches [charTemperature]; from there,
 * while there is ablator left, it goes no hotter - whatever would heat it
 * further chars ablator away instead, at [energyPerUnit] joules a unit. So
 * it glows as hot as it can, sheds what it can, and spends ablator only on
 * the rest. Faced into the airflow, it shades what is behind it as well.
 */
@Serializable
@SerialName("heatShield")
data class HeatShield(
    val energyPerUnit: Double = 3.0e6,
    val charTemperature: Double = 1_400.0,
) : PartModule

/** What a [DockingPort] is, and so what it mates with. */
@Serializable
enum class DockKind {
    /** A docking ring: mates with another ring of the same size. */
    @SerialName("port") PORT,
    /** A tow ball, on the back of a rover: mates with a coupling. */
    @SerialName("hitchBall") HITCH_BALL,
    /** A tow coupling, on a trailer's drawbar: mates with a ball. */
    @SerialName("hitchCoupling") HITCH_COUPLING,
    /** A mooring clamp on a hull's side: mates with another clamp. */
    @SerialName("clamp") CLAMP,
}

/**
 * A place where two craft come together and can come apart again.
 *
 * Its face is at [faceOffset] along the part's own +Y, facing +Y. Brought
 * face to face with a partner it can mate with - close, lined up and slow -
 * it captures it and draws it in, straightening it up, and after
 * [latchSeconds] of being face to face it latches: a docking ring or clamp
 * into one rigid craft, a hitch into a joint the two turn about. Either can
 * be undone later, which is what sets them apart from a weld.
 *
 * Capture is forgiving on purpose - within a metre, fifteen degrees and a
 * metre a second - because a phone is no place for a pixel hunt: the magnets
 * do the last of the work.
 */
@Serializable
@SerialName("dockingPort")
data class DockingPort(
    val kind: DockKind = DockKind.PORT,
    /** Size class: only equal sizes mate. */
    val size: Int = 1,
    /** Metres along the part's +Y from its centre to its face. */
    val faceOffset: Double = 0.15,
    /** How close the faces must be to capture, m. */
    val captureRange: Double = 1.0,
    /** How far off facing each other they may be, degrees. */
    val captureAngle: Double = 15.0,
    /** How slowly they must be coming together, m/s. */
    val captureSpeed: Double = 1.0,
    /** The most the magnets pull with, N, and turn with, N·m. */
    val pull: Double = 3_000.0,
    val turn: Double = 1_500.0,
    /** Seconds face to face before it latches. */
    val latchSeconds: Double = 0.5,
    /**
     * How close the faces must be to latch, m; the latch squares up the
     * rest. More for a base's connectors: a module standing on its feet is
     * held by the ground's friction, which the magnets outpull at arm's
     * length but not a hand's breadth away.
     */
    val latchRange: Double = 0.06,
    /** How far off square the faces may be to latch, degrees; the latch squares up the rest. */
    val latchAngle: Double = 2.5,
    /** Push given to each side on undocking, N·s. */
    val undockImpulse: Double = 400.0,
) : PartModule {
    /** Whether this can mate with [other] at all. */
    fun matesWith(other: DockingPort): Boolean = size == other.size && when (kind) {
        DockKind.PORT -> other.kind == DockKind.PORT
        DockKind.CLAMP -> other.kind == DockKind.CLAMP
        DockKind.HITCH_BALL -> other.kind == DockKind.HITCH_COUPLING
        DockKind.HITCH_COUPLING -> other.kind == DockKind.HITCH_BALL
    }

    /** A hitch turns about its joint; a ring or clamp holds rigid. */
    val rigid: Boolean get() = kind == DockKind.PORT || kind == DockKind.CLAMP
}
