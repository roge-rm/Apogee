package com.rm.apogee.core.part

import com.rm.apogee.core.math.SerialVec3
import com.rm.apogee.core.math.Vec3
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * A behaviour bolted onto a part.
 *
 * A part is a bag of modules instead of a subclass of Part, so the same tank body can be given an
 * engine, or a command pod given reaction wheels, purely in data. That's what makes boats, planes
 * and rovers additions to the *catalogue* instead of additions to the engine. A hull is a part with
 * a [Buoyancy] module, a wing is a part with an [AeroSurface] module, and neither needs a new code
 * path in how the simulation is built.
 */
@Serializable
sealed interface PartModule

/**
 * Produces thrust along [thrustDirection] while throttled up, using [propellant].
 *
 * Thrust and specific impulse both change with ambient pressure, and that's not a detail. It's why
 * a vacuum-tuned upper stage makes a bad first stage, and it's the whole reason staging is
 * interesting.
 */
@Serializable
@SerialName("engine")
data class Engine(
    /** Newtons in vacuum. */
    val thrustVacuum: Double,
    /** Newtons at 1 atmosphere. */
    val thrustSeaLevel: Double,
    /** Specific impulse in seconds, in vacuum. */
    val ispVacuum: Double,
    /** Specific impulse in seconds, at 1 atmosphere. */
    val ispSeaLevel: Double,
    /** The most the gimbal can swing, in degrees. Zero means fixed. */
    val gimbalRange: Double = 0.0,
    val propellant: ResourceType = ResourceType.PROPELLANT,
    /**
     * The direction, in part-local space, of the force on the craft, which is opposite the exhaust.
     * +Y by default, so a stack points its nose along +Y.
     */
    val thrustDirection: SerialVec3 = Vec3(0.0, 1.0, 0.0),
    /** The lowest throttle, as a fraction. Solid motors would set this to 1.0. */
    val minThrottle: Double = 0.0,
    /**
     * For a propeller that works in water, like an outboard motor: where its propeller is,
     * part-local. The engine only pushes while that point is under water, and in proportion to how
     * far under it is.
     */
    val waterProp: SerialVec3? = null,
    /** What comes out of the back, for drawing. Null lets [exhaustKind] decide. */
    val exhaust: Exhaust? = null,
    /** The charge its alternator makes at full output, in units a second. */
    val alternator: Double = 1.0,
) : PartModule {
    /**
     * What it leaves behind it. A water propeller churns water, anything with no vacuum thrust
     * breathes air (a jet, unless it says it's a propeller), and the rest are rockets.
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
    /** Flame and smoke, widening with height into a vacuum bloom. */
    @SerialName("rocket") ROCKET,
    /** A faint hot core, and contrails high up. */
    @SerialName("jet") JET,
    /** No flame: prop wash, and dust near the ground. */
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
 * Makes a craft controllable, and gives attitude control without using up reaction mass.
 */
@Serializable
@SerialName("command")
data class Command(
    val crewCapacity: Int = 0,
    /** Reaction wheel authority, in N·m, around each control axis. */
    val reactionTorque: Double = 0.0,
    /** Whether this pod alone is enough to fly the craft. */
    val providesControl: Boolean = true,
    /** The charge it uses just by being on, in units a second, for its instruments and radio. */
    val idleDraw: Double = 0.02,
) : PartModule

/**
 * Splits the craft in two when staged. The part of the tree above it becomes a separate vessel.
 */
@Serializable
@SerialName("decoupler")
data class Decoupler(
    /** The impulse that pushes the halves apart, in N·s. */
    val ejectionImpulse: Double = 2_500.0,
    /**
     * Stays on the craft it's mounted to and only lets go of what it holds. For example a release
     * clamp on a flatbed, which sets its load down on the ground beside the truck (on its
     * right-hand side), level and just clear. What it lets go of is cargo, not a spent stage, so it
     * stays with its owner.
     */
    val stays: Boolean = false,
    /**
     * Pushes what it lets go of out sideways, away from the craft's axis, instead of back along it,
     * like a fairing's shell falling open.
     */
    val radial: Boolean = false,
) : PartModule

/** A wing, fin or control surface. */
@Serializable
@SerialName("aeroSurface")
data class AeroSurface(
    val liftCoefficient: Double,
    /** Reference area, in m². */
    val area: Double,
    /**
     * Whether it moves with control input.
     *
     * A stabiliser isn't a control surface. Fins that hold a rocket straight want this false.
     * Making them steerable too hands the ascent a second, much stronger set of controls that its
     * guidance was never written for, and the stock rocket stopped reaching orbit.
     */
    val controllable: Boolean = true,
    val controlAuthority: Double = 1.0,
    /**
     * How far it moves, in degrees.
     *
     * The force follows sin(d)cos(d) of this, not the surface's full broadside force, because a
     * control surface moves through twenty degrees or so, not ninety.
     */
    val maxDeflection: Double = 20.0,
    /**
     * The normal force, in N, beyond which it breaks, counting lift and control together. Zero
     * means "work it out from its size", which is how every stock surface gets one. See
     * [loadLimit].
     */
    val maxLoad: Double = 0.0,
) : PartModule {
    /**
     * What it takes to break it, in N. By default that's what its own lift would be at
     * [DESIGN_LOAD], which is a strong gust at speed or a hard pull in rough air, well past
     * anything the stock craft do in normal flying.
     */
    val loadLimit: Double get() = if (maxLoad > 0.0) maxLoad else area * liftCoefficient * DESIGN_LOAD

    companion object {
        /** The loading, in pascals of lift, a surface is built for. */
        const val DESIGN_LOAD = 12_000.0
    }
}

/**
 * A rudder, keel or hydrofoil: a flying surface for water.
 *
 * It uses the same flat-plate physics as [AeroSurface], with water's density, and only for as much
 * of it as is under the surface. Water is eight hundred times denser than air, so a keel a fraction
 * of a sail's size is what stops a boat sliding sideways, and a rudder the size of a door steers a
 * ship.
 *
 * The plate lies in the part's X-Y plane, span along X and chord along Y, the same as a fin's.
 * [controllable] surfaces move with the stick by the same rule as aircraft surfaces. Which way
 * depends on where it's bolted, so a rudder under the stern yaws the boat.
 */
@Serializable
@SerialName("hydroSurface")
data class HydroSurface(
    val liftCoefficient: Double = 1.2,
    /** Reference area, in m². */
    val area: Double,
    val controllable: Boolean = false,
    val controlAuthority: Double = 1.0,
    val maxDeflection: Double = 30.0,
    /** Half its depth, in metres: how far it reaches either side of its centre. */
    val halfDepth: Double = 0.5,
) : PartModule

/** A drag device. It does nothing until deployed, and then it dominates. */
@Serializable
@SerialName("parachute")
data class Parachute(
    val deployedDragCoefficient: Double = 500.0,
    /** The safe deployment speed, in m/s. Above this it tears away. */
    val maxDeploymentSpeed: Double = 300.0,
) : PartModule {
    /**
     * Staging a chute arms it, and it opens by itself once it's safe (slow enough not to shred and
     * in air thick enough to fill it) as a small drogue. That gives a fast, steady fall, a couple
     * of minutes from high up. Near the ground it opens fully for a gentle last stretch. Its state
     * lives in the part's deploy value: 0 is packed, up to [DROGUE_FULL] is the drogue filling, up
     * to 1 is the main, and below 0 means cut away after landing.
     */
    companion object {
        /** The deploy value with the drogue full and the main not out yet. */
        const val DROGUE_FULL = 0.5

        /** The drogue's drag, as a share of the full canopy's. */
        const val DROGUE_SHARE = 0.06

        /** The height above the ground or sea at which the main opens, in metres. */
        const val MAIN_HEIGHT = 200.0

        /** How much of the full canopy's drag a chute at [deploy] gives. */
        fun dragShare(deploy: Double): Double = when {
            deploy <= 0.0 -> 0.0
            deploy <= DROGUE_FULL -> DROGUE_SHARE * (deploy / DROGUE_FULL).let { it * it }
            else -> DROGUE_SHARE + (1.0 - DROGUE_SHARE) * ((deploy - DROGUE_FULL) / (1.0 - DROGUE_FULL)).let { it * it }
        }

        /** Opens at this share of [maxDeploymentSpeed] or slower. */
        const val OPEN_SHARE = 0.95

        /** Air at least this dense, in kg/m³. High enough up, a canopy has nothing to fill it. */
        const val OPEN_DENSITY = 0.04

        /** How long it takes to fill, in seconds. */
        const val INFLATE_SECONDS = 1.5

        /** The most an opening chute pulls, in g of the craft it's carrying. */
        const val MOST_PULL_G = 6.0

        /**
         * Down, and this slow or slower in m/s, and it gets cut away so the wind can't drag the
         * craft along the ground.
         */
        const val CUT_SPEED = 3.0

        /** Or down this long in seconds, however fast it's being dragged. */
        const val CUT_AFTER = 1.5
    }
}

/**
 * Displaces [displacedVolume] m³ when under water.
 *
 * The rocket slice didn't use it, but it was included on purpose. Boats and submarines were part of
 * the plan from the start, and having the module in the schema early meant buoyancy could be added
 * later as a new force source instead of a change to the part model everything else already depends
 * on.
 */
@Serializable
@SerialName("buoyancy")
data class Buoyancy(
    val displacedVolume: Double,
    /**
     * Open to the sky, like a skiff or a punt. A wave coming over its top edge (the gunwale), or
     * heeling far enough to put that edge under, pours water in, and enough water sinks it. A
     * decked hull takes on none until it's holed.
     */
    val open: Boolean = false,
) : PartModule {
    companion object {
        /** The share of a hull box that's room for water. */
        const val INTERIOR = 0.85

        /**
         * How much water, in kg, part [def] holds when full at [density] kg/m³, or 0 if it isn't a
         * hull.
         */
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
 * Gear is the difference between arriving and crashing, and the thing that does it is the
 * suspension, not the strength. A rigid contact stops a descending craft in one tick, so the whole
 * arrival lands in a single impulse and the only question is whether that was more than something's
 * crash tolerance. A spring spreads the same momentum over its travel, which is what a real leg is
 * for.
 *
 * It deploys when its stage fires, like everything else. A stowed leg does nothing (its foot
 * doesn't touch anything), so leaving the gear up is a way to land badly, not a harmless mistake.
 */
@Serializable
@SerialName("landingLeg")
data class LandingLeg(
    /** How far the leg compresses before it bottoms out, in metres. */
    val suspensionTravel: Double = 0.4,
    /**
     * Spring rate, in N/m.
     *
     * It should hold the craft's landed weight at about half travel. Too soft and the leg is always
     * bottomed out and acts rigid anyway. Too stiff and it might as well not be there.
     */
    val springRate: Double = 90_000.0,
    /** Damping, in N per m/s. Without it the craft bounces on its own springs. */
    val damping: Double = 12_000.0,
    /** Seconds from stowed to fully deployed. */
    val deployTime: Double = 1.5,
    /**
     * Where it folds, in part space, and around which axis. When stowed, the leg is turned
     * [stowedAngle] degrees around [foldAxis] through [hinge] from its deployed pose, and its feet
     * are wherever that puts them, for the ground to meet just like it meets anything else.
     */
    val hinge: SerialVec3 = Vec3(0.0, 0.0, 0.0),
    val foldAxis: SerialVec3 = Vec3(0.0, 0.0, 1.0),
    /** Zero means a leg that doesn't fold, so its feet are always where they are. */
    val stowedAngle: Double = 0.0,
) : PartModule

/**
 * Attitude and translation thrusters.
 *
 * The difference from [Engine] is what it's *for*. An engine changes where you're going, and a
 * thruster block changes where you are. Nudging a landed module a couple of metres so it touches
 * the one next to it is what makes a base buildable, and a main engine can't do that because it
 * points one way and delivers tonnes.
 *
 * Thrust is applied at each block's own position, like everything else on a craft, so a symmetric
 * set cancels out its own torque and a lone block off to one side spins the craft. That's the
 * behaviour worth having: where you put them matters, and the game tells you so instead of quietly
 * balancing it for you.
 */
@Serializable
@SerialName("rcs")
data class Rcs(
    /** Newtons per block, along whichever axis is being commanded. */
    val thrust: Double = 1_000.0,
    /** Specific impulse in seconds. Thrusters are thirsty and that's fine. */
    val isp: Double = 240.0,
    val propellant: ResourceType = ResourceType.MONOPROPELLANT,
) : PartModule

/**
 * A driven, sprung, rolling contact.
 *
 * Mechanically it's a [LandingLeg] with two additions, and that's the point: a rover is a craft
 * whose feet turn. The suspension is the leg's, unchanged. What makes it a wheel is that it rolls
 * (friction along its rolling direction drops to [rollingResistance] while grip *across* it stays
 * full) and that it can be driven.
 *
 * Without the friction being different in each direction there'd be no such thing as a wheel here.
 * The ground friction that stops a landed craft sliding is exactly what would stop a rover being
 * pushed, and the motor would have to overpower it instead of rolling past it.
 */
@Serializable
@SerialName("wheel")
data class Wheel(
    /** In metres. Sets how high the axle rides above the ground. */
    val radius: Double = 0.35,
    /** Newtons of drive force at full throttle, per wheel, from a standstill. */
    val motorForce: Double = 0.0,
    /**
     * The ground speed at which the motor runs out of pull, in m/s.
     *
     * The drive force falls off in a straight line to nothing here, which is roughly what a real
     * motor does, and it's what gives a rover a top speed at all. Without it the drive force stays
     * constant and a rover just keeps speeding up. It got to 180 km/h on flat ground, which is a
     * rocket sled, not a vehicle.
     */
    val topSpeed: Double = 22.0,
    /** Whether the steering input turns this wheel. */
    val steerable: Boolean = false,
    /** The most it can steer, in degrees. */
    val steeringRange: Double = 30.0,
    /**
     * The friction coefficient along the rolling direction.
     *
     * Small but not zero. A free wheel still costs something to push, and at zero a parked rover
     * slides down the gentlest slope forever.
     */
    val rollingResistance: Double = 0.06,
    /**
     * The friction coefficient along the rolling direction with the brakes on.
     *
     * This isn't the full ground friction a locked wheel would give. Braking at a full 0.6 g threw
     * the stock rover over its own front wheels, because its centre of mass sits higher than half
     * its wheelbase, which is exactly what makes a vehicle nose over. Real brakes are eased on for
     * the same reason. It's still enough to hold a parked craft on a slope of atan(brakeFriction),
     * about nineteen degrees at the default.
     */
    val brakeFriction: Double = 0.35,
    val suspensionTravel: Double = 0.25,
    val springRate: Double = 60_000.0,
    val damping: Double = 6_000.0,
) : PartModule

/**
 * Feet that pin a base to the ground. A craft with one working, resting on the ground, can be
 * anchored. It gets levelled on its feet, each reaching down as far as [travel] to meet uneven
 * ground, on slopes up to [maxSlope], and from then on it can't be moved. See `World.anchor`.
 */
@Serializable
@SerialName("foundation")
data class Foundation(
    /** How far its feet reach down to level it, in metres. */
    val travel: Double = 1.0,
    /** The steepest ground it will level on, in degrees. */
    val maxSlope: Double = 10.0,
) : PartModule

/**
 * Makes the part somewhere to launch from. A craft can be set down on it, and takes its propellant
 * from the base's stores. See `World.spawnOnPad`.
 */
@Serializable
@SerialName("launchPad")
data class LaunchPad(
    /** The charge a launch takes from the base, in units. */
    val launchCharge: Double = 50.0,
) : PartModule

/**
 * Moves propellant and charge between a base and a craft docked to it or standing on its pad, while
 * the base has power.
 */
@Serializable
@SerialName("pump")
data class Pump(
    /** Units a second of whatever it moves. */
    val rate: Double = 20.0,
    /** The charge it draws while pumping, in units a second. */
    val draw: Double = 2.0,
) : PartModule

/** A light, lit at night while there's power for it. */
@Serializable
@SerialName("lamp")
data class Lamp(
    /** The charge it draws while lit, in units a second. */
    val draw: Double = 0.2,
    /**
     * How far its pool of light reaches on the ground, in metres. 0 for a lamp you can see but that
     * lights nothing.
     */
    val reach: Double = 0.0,
    /**
     * How far out in front of it (along its +Z, level) its pool of light is centred, in metres. A
     * floodlight is aimed at what it lights. 0 for a lamp that lights all around it.
     */
    val aim: Double = 0.0,
) : PartModule

/**
 * A fairing's base: a closed shell of [radius] standing [height] above the part's top face, around
 * whatever rides on it. Inside it's out of the air, with no drag and no heating. When staged it
 * opens in two halves ([shellPart], [shellMass] kg each), and the ring stays on the craft.
 */
@Serializable
@SerialName("fairing")
data class Fairing(
    val height: Double = 7.5,
    val radius: Double = 1.5,
    val shellPart: String = "fairing-shroud",
    val shellMass: Double = 350.0,
    val ejectionImpulse: Double = 900.0,
) : PartModule

/** Makes electric charge from sunlight. */
@Serializable
@SerialName("solarPanel")
data class SolarPanel(
    /** Units per second at 1 AU, facing the star. */
    val chargeRate: Double,
    /**
     * Folds out when staged or told to, and back in again. It makes nothing while folded. When it's
     * out it turns itself toward the sun around one axis, so it faces the sun whenever it's lit at
     * all.
     */
    val deployable: Boolean = false,
    /**
     * A fixed panel's face, in part space. Charge depends on how squarely it faces the sun. Null
     * for one that tracks the sun.
     */
    val normal: com.rm.apogee.core.math.SerialVec3? = null,
    /** If it's folded out in air pushing harder than this, in Pa, it gets torn off. */
    val maxPressure: Double = 3_000.0,
) : PartModule

/**
 * Turns monopropellant into charge: [rate] units a second, burning [monoPerCharge] units of
 * monopropellant for each one. It runs itself, switching on below a quarter full and off again at
 * nine tenths.
 */
@Serializable
@SerialName("fuelCell")
data class FuelCell(
    val rate: Double = 1.5,
    val monoPerCharge: Double = 0.01,
) : PartModule

/**
 * Makes [rate] units of charge a second whatever the light, from an isotope's slow heat, for places
 * where the sun is too faint.
 */
@Serializable
@SerialName("generator")
data class Generator(val rate: Double) : PartModule

/** Room for [capacity] people to live off duty, like a base's habitat. Seats, not controls. */
@Serializable
@SerialName("habitat")
data class Habitat(val capacity: Int) : PartModule

/**
 * Walking: a crew member out of their craft. On the ground it drives them toward [speed] m/s as far
 * as the ground's grip allows, keeps them upright with a stiff, damped [stand] torque in N·m per
 * radian, and can [jump] them up at that many m/s. Off the ground it does nothing, because the
 * jetpack flies them there.
 */
@Serializable
@SerialName("walker")
data class Walker(
    val speed: Double = 1.6,
    val jump: Double = 3.0,
    val stand: Double = 800.0,
    /** How far legs and arms swing each way at a full stride, in degrees. It's drawn, not felt. */
    val swing: Double = 28.0,
    /** A stride, in metres: how far a step carries them. */
    val stride: Double = 1.4,
) : PartModule

/**
 * A ladder: [length] metres of rungs along the part's own Y, centred on it. A crew member near it
 * can hold on and climb.
 */
@Serializable
@SerialName("ladder")
data class Ladder(val length: Double = 2.4) : PartModule

/**
 * Digs the ground: [rate] units a second of ore and of water, each scaled by how rich the ground is
 * in it (see `Deposits`). It works while switched on, landed, still and powered, with its [head]
 * (part space, drawn out) within [reach] metres of the ground. It uses [draw] charge a second while
 * it digs.
 */
@Serializable
@SerialName("drill")
data class Drill(
    val rate: Double = 1.0,
    val draw: Double = 2.0,
    val head: com.rm.apogee.core.math.SerialVec3 = com.rm.apogee.core.math.Vec3(0.0, -1.2, 0.0),
    val reach: Double = 1.5,
) : PartModule

/**
 * One of a [Converter]'s processes: [inputRate] of [input] a second in, and [outputs] a second out.
 */
@Serializable
data class Recipe(
    val input: ResourceType,
    val inputRate: Double,
    val outputs: Map<ResourceType, Double>,
)

/**
 * Makes propellant out of what gets dug up. It runs each of its [recipes] as far as there's input,
 * room and charge, using [draw] a second while it runs.
 */
@Serializable
@SerialName("converter")
data class Converter(
    val recipes: List<Recipe>,
    val draw: Double = 4.0,
) : PartModule

/**
 * Surveys the ground. From a low, steep orbit (an apoapsis under [surveyAltitude] of its body's
 * radius) it maps a whole body's ore and water in half an orbit. Near the ground it reads what's
 * below. It uses [draw] a second.
 */
@Serializable
@SerialName("scanner")
data class Scanner(
    val draw: Double = 0.1,
    val surveyAltitude: Double = 0.45,
) : PartModule

/**
 * Talks home: [range] metres to a ground station or another antenna, with the shorter of the two
 * ranges deciding. A [relay] passes on what others send through it. [deployable] ones fold out
 * first. It uses [draw] a second while it has a link.
 */
@Serializable
@SerialName("antenna")
data class Antenna(
    val range: Double,
    val relay: Boolean = false,
    val deployable: Boolean = false,
    val draw: Double = 0.02,
) : PartModule

/**
 * A ballast tank: room for [volume] m³ of sea water, let in to go down and blown out to come up.
 * The water is carried as weight in the part while it's in, the same way a swamped hull carries
 * what it has taken on, so a craft sinks as it floods and rises as it blows, and that's all there
 * is to it. Flooding needs the tank under water. Blowing it out takes charge, [draw] units a
 * second, and it moves [rate] m³ a second either way.
 */
@Serializable
@SerialName("ballast")
data class Ballast(
    val volume: Double,
    val rate: Double = 0.08,
    val draw: Double = 0.6,
) : PartModule

/**
 * Sound bounced off the sea floor: what lies below and ahead of a craft in the water, out to
 * [range] metres. It gives the depth under it and the nearest named place in the sea that hasn't
 * been found yet. It draws [draw] charge a second.
 */
@Serializable
@SerialName("sonar")
data class Sonar(
    val range: Double = 2_000.0,
    val draw: Double = 0.02,
) : PartModule

/** Stores electric charge. It's only separate from [Tank] to make the UI clearer. */
@Serializable
@SerialName("battery")
data class Battery(
    val capacity: Double,
) : PartModule

/**
 * Takes the heat of re-entry on itself by burning away its ablator. It heats up like anything else
 * until it reaches [charTemperature]. From then on, while there's ablator left, it gets no hotter.
 * Whatever would heat it further burns ablator away instead, at [energyPerUnit] joules a unit. So
 * it glows as hot as it can, sheds what it can, and only spends ablator on the rest. Pointed into
 * the airflow, it also shades whatever is behind it.
 */
@Serializable
@SerialName("heatShield")
data class HeatShield(
    val energyPerUnit: Double = 3.0e6,
    val charTemperature: Double = 1_400.0,
) : PartModule

/** What a [DockingPort] is, and so what it joins with. */
@Serializable
enum class DockKind {
    /** A docking ring. It joins with another ring of the same size. */
    @SerialName("port") PORT,
    /** A tow ball on the back of a rover. It joins with a coupling. */
    @SerialName("hitchBall") HITCH_BALL,
    /** A tow coupling on a trailer's drawbar. It joins with a ball. */
    @SerialName("hitchCoupling") HITCH_COUPLING,
    /** A mooring clamp on a hull's side. It joins with another clamp. */
    @SerialName("clamp") CLAMP,
}

/**
 * A place where two craft come together and can come apart again.
 *
 * Its face is at [faceOffset] along the part's own +Y, facing +Y. Brought face to face with a
 * partner it can join with, close, lined up and slow, it captures it and draws it in, straightening
 * it up. After [latchSeconds] of being face to face it latches: a docking ring or clamp makes one
 * rigid craft, and a hitch makes a joint the two turn around. Either can be undone later, which is
 * what makes them different from a weld.
 *
 * Capture is forgiving on purpose, within a metre, fifteen degrees and a metre a second, because a
 * phone is no place for hunting pixels. The magnets do the last of the work.
 */
@Serializable
@SerialName("dockingPort")
data class DockingPort(
    val kind: DockKind = DockKind.PORT,
    /** Size class. Only equal sizes join. */
    val size: Int = 1,
    /** Metres along the part's +Y from its centre to its face. */
    val faceOffset: Double = 0.15,
    /** How close the faces have to be to capture, in metres. */
    val captureRange: Double = 1.0,
    /** How far off facing each other they can be, in degrees. */
    val captureAngle: Double = 15.0,
    /** How slowly they have to be coming together, in m/s. */
    val captureSpeed: Double = 1.0,
    /** The most the magnets pull with, in N, and turn with, in N·m. */
    val pull: Double = 3_000.0,
    val turn: Double = 1_500.0,
    /** Seconds face to face before it latches. */
    val latchSeconds: Double = 0.5,
    /**
     * How close the faces have to be to latch, in metres. The latch squares up the rest. It's more
     * for a base's connectors, because a module standing on its feet is held by the ground's
     * friction, which the magnets can beat at arm's length but not from a hand's width away.
     */
    val latchRange: Double = 0.06,
    /** How far off square the faces can be to latch, in degrees. The latch squares up the rest. */
    val latchAngle: Double = 2.5,
    /** The push given to each side when undocking, in N·s. */
    val undockImpulse: Double = 400.0,
) : PartModule {
    /** Whether this can join with [other] at all. */
    fun matesWith(other: DockingPort): Boolean = size == other.size && when (kind) {
        DockKind.PORT -> other.kind == DockKind.PORT
        DockKind.CLAMP -> other.kind == DockKind.CLAMP
        DockKind.HITCH_BALL -> other.kind == DockKind.HITCH_COUPLING
        DockKind.HITCH_COUPLING -> other.kind == DockKind.HITCH_BALL
    }

    /** A hitch turns around its joint, and a ring or clamp holds rigid. */
    val rigid: Boolean get() = kind == DockKind.PORT || kind == DockKind.CLAMP
}
