package com.rm.apogee.core.part

import com.rm.apogee.core.math.SerialVec3
import com.rm.apogee.core.math.Vec3
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * A behaviour on a part. Parts are bags of modules, not subclasses, so boats, planes and rovers are
 * catalogue data: a hull is a part with [Buoyancy], a wing one with [AeroSurface].
 */
@Serializable
sealed interface PartModule

/**
 * Thrust along [thrustDirection] while throttled, burning [propellant]. Thrust and Isp vary with
 * ambient pressure, which is why a vacuum upper stage makes a poor first stage.
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
     * Part-local direction of the force on the craft, opposite the exhaust. +Y by default, so a
     * stack's nose is +Y.
     */
    val thrustDirection: SerialVec3 = Vec3(0.0, 1.0, 0.0),
    /** Lowest throttle, as a fraction. Solid motors would set 1.0. */
    val minThrottle: Double = 0.0,
    /**
     * For a water propeller like an outboard: where the prop is, part-local. It only pushes while
     * that point is under water, in proportion to depth.
     */
    val waterProp: SerialVec3? = null,
    /** What comes out the back, for drawing. Null lets [exhaustKind] decide. */
    val exhaust: Exhaust? = null,
    /** Alternator charge at full output, in units a second. */
    val alternator: Double = 1.0,
) : PartModule {
    /**
     * What it leaves behind: water for a water prop, a jet for anything with no vacuum thrust
     * (unless it says prop), else a rocket.
     */
    val exhaustKind: Exhaust
        get() = exhaust ?: when {
            waterProp != null -> Exhaust.WATER
            thrustVacuum <= 0.0 -> Exhaust.JET
            else -> Exhaust.ROCKET
        }
}

/** Exhaust kinds, for plumes and smoke. */
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

/** Makes a craft controllable, with attitude control that uses no reaction mass. */
@Serializable
@SerialName("command")
data class Command(
    val crewCapacity: Int = 0,
    /** Reaction wheel torque in N·m about each axis. */
    val reactionTorque: Double = 0.0,
    /** Whether this pod alone can fly the craft. */
    val providesControl: Boolean = true,
    /** Charge used just by being on, units a second, for instruments and radio. */
    val idleDraw: Double = 0.02,
) : PartModule

/** Splits the craft when staged. The tree above it becomes a separate vessel. */
@Serializable
@SerialName("decoupler")
data class Decoupler(
    /** Impulse pushing the halves apart, in N·s. */
    val ejectionImpulse: Double = 2_500.0,
    /**
     * Stays on its own craft and only lets go of what it holds, like a flatbed release clamp
     * setting its load level beside the truck (right-hand side). The load is cargo, so it keeps its
     * owner.
     */
    val stays: Boolean = false,
    /**
     * Pushes what it releases out sideways from the craft's axis, like a fairing shell falling
     * open.
     */
    val radial: Boolean = false,
    /**
     * Passes propellant while joined: what it holds (a side booster) feeds the craft and drains
     * first, so it's empty when dropped.
     */
    val feeds: Boolean = false,
) : PartModule

/** A wing, fin or control surface. */
@Serializable
@SerialName("aeroSurface")
data class AeroSurface(
    val liftCoefficient: Double,
    /** Reference area, in m². */
    val area: Double,
    /**
     * Whether it moves with control input. False for stabiliser fins, which would otherwise give
     * ascent guidance controls it wasn't tuned for.
     */
    val controllable: Boolean = true,
    val controlAuthority: Double = 1.0,
    /**
     * How far it moves, in degrees. Force follows sin(d)cos(d) of this, since control surfaces move
     * about twenty degrees, not ninety.
     */
    val maxDeflection: Double = 20.0,
    /**
     * Normal force in N at which it breaks, lift and control together. Zero means from its size, as
     * for every stock surface. See [loadLimit].
     */
    val maxLoad: Double = 0.0,
    /**
     * Extra lift coefficient with flaps down, or 0 with none. Lets a plane fly slower and land
     * shorter.
     */
    val flapLift: Double = 0.0,
    /** Extra drag coefficient with flaps down. */
    val flapDrag: Double = 0.0,
) : PartModule {
    /**
     * Breaking load in N. By default its own lift at [DESIGN_LOAD], a strong gust at speed, well
     * past normal stock flying.
     */
    val loadLimit: Double get() = if (maxLoad > 0.0) maxLoad else area * liftCoefficient * DESIGN_LOAD

    companion object {
        /** Lift loading a surface is built for, in Pa. */
        const val DESIGN_LOAD = 12_000.0
    }
}

/**
 * A rudder, keel or hydrofoil. The same flat-plate physics as [AeroSurface] with water's density,
 * for the submerged part only. Water is 800 times denser than air, so a small keel stops a boat
 * sliding sideways.
 *
 * The plate lies in the part's X-Y plane, span along X, chord along Y, like a fin. [controllable]
 * ones follow the stick by the same rule as aircraft surfaces, so the direction depends on where
 * it's mounted.
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
    /** Half its depth in metres, either side of its centre. */
    val halfDepth: Double = 0.5,
) : PartModule

/**
 * A rotor lifting along [liftDirection] (+Y by default). Throttle is collective. Lift needs air:
 * none in vacuum, weak in Rubra's thin air, more in thick air but not proportionally, since the
 * engine's power is limited.
 *
 * Steering depends on the craft:
 * - Several rotors (drone, platform) vary speed by position to tilt the craft, and yaw by
 *   trading speed between the two spin directions.
 * - A single main rotor with [cyclic] tilts its lift with the stick, and a [tail] rotor counters
 *   its torque and yaws.
 *
 * No staging needed; it turns whenever the throttle's up.
 */
@Serializable
@SerialName("rotor")
data class Rotor(
    /** Newtons of lift at full collective at Terra sea level. */
    val lift: Double,
    /** Rotor diameter in metres. */
    val diameter: Double,
    /** Spin direction seen from above, 1 or -1. Even numbers of rotors alternate. */
    val spin: Int = 1,
    /** How far the stick tilts its lift, in degrees. Zero for none. */
    val cyclic: Double = 0.0,
    /** Reaction torque in N·m per N of lift. A tail rotor or paired rotors cancel it. */
    val torque: Double = 0.0,
    /** A tail rotor: holds heading against the main rotor's torque, and yaws. */
    val tail: Boolean = false,
    /** Lift direction, part-local. */
    val liftDirection: SerialVec3 = Vec3(0.0, 1.0, 0.0),
    /** What drives it: an engine burning propellant, or a motor on charge. */
    val propellant: ResourceType = ResourceType.PROPELLANT,
    /** N·s of lift per kg of propellant, or per unit of charge. */
    val efficiency: Double = 30_000.0,
) : PartModule

/**
 * A gas cell, floating in air as a hull floats in water: it lifts the weight of air displaced less
 * the gas's weight, so lift falls with height and a craft settles where they match. A ballonet
 * takes in air to sink and lets it out to rise, pumping on charge.
 */
@Serializable
@SerialName("liftGas")
data class LiftGas(
    /** Gas volume in m³. */
    val volume: Double,
    /** Ballonet fill or empty rate, as a share a second. */
    val trimRate: Double = 0.05,
    /** Charge drawn while pumping air in, units a second. */
    val draw: Double = 0.3,
) : PartModule

/**
 * The keeper core: a flight computer that holds a craft where it was in the air or on water, using
 * whatever it has (rotors, gas and fans, props, or downward engines).
 */
@Serializable
@SerialName("stationKeeper")
data class StationKeeper(
    /** Charge drawn while holding, units a second. */
    val draw: Double = 0.1,
) : PartModule

/**
 * A sail on a mast. It trims itself each tick to the best angle for the apparent wind, eased out as
 * the wind goes aft; lift across the wind and drag along it drive and heel the boat, and keel and
 * hull do the rest. Closer than about forty degrees to the wind it just flaps.
 *
 * Throttle is the sheet: 0 furled, otherwise that share of sail set, like reefing. The mast is the
 * part's +Y and, centred, the chord runs aft along -Z.
 */
@Serializable
@SerialName("sail")
data class Sail(
    /** Sail area set full, in m². */
    val area: Double,
    /** Height of the sail's centre of effort, in metres from the part's centre along +Y. */
    val effortHeight: Double = 0.0,
) : PartModule

/**
 * A winch: a drum of line with a hook. Hooked to another craft, the ground, a tree or a rock, it
 * winds in and pulls them together, up to [pull]: for getting a rover out of a ditch or towing.
 *
 * The line leaves along the part's +Y (it mounts by its base, like a hitch), [faceOffset] from its
 * centre. Past its pull the drum slips; a hard enough yank snaps the line.
 */
@Serializable
@SerialName("winch")
data class Winch(
    /** Line length in metres: the furthest it can hook. */
    val reach: Double = 30.0,
    /** Most pull in N. */
    val pull: Double = 25_000.0,
    /** Wind-in or pay-out speed, m/s. */
    val reelSpeed: Double = 0.8,
    /** Charge a second while it winds in. */
    val draw: Double = 0.5,
    /** Metres along the part's +Y from its centre to where the line comes out. */
    val faceOffset: Double = 0.2,
    /**
     * Leads its line any direction, like a ship's towing winch over a stern roller, not just ahead
     * like a rover's.
     */
    val anyWay: Boolean = false,
) : PartModule

/**
 * A tailhook: an arm under a plane's tail that lowers with the gear and catches deck wires. [tip]
 * is the hook, part-local.
 */
@Serializable
@SerialName("tailhook")
data class Tailhook(val tip: SerialVec3 = Vec3(0.0, -1.0, -0.9)) : PartModule

/**
 * Arresting wires across a deck: along the part's X at each of [wires] along Y, [span] metres wide.
 * A lowered hook crossing one stops the plane in [runout] metres, never pulling more than [mostG].
 */
@Serializable
@SerialName("arrestingGear")
data class ArrestingGear(
    val wires: List<Double> = listOf(-4.0, 0.0, 4.0),
    val span: Double = 18.0,
    val runout: Double = 60.0,
    val mostG: Double = 4.0,
) : PartModule

/**
 * A catapult: a track along the part's +Y, [stroke] metres long. A craft sitting still at the near
 * end at full throttle with brakes off is launched to [endSpeed] m/s.
 */
@Serializable
@SerialName("catapult")
data class Catapult(
    val stroke: Double = 70.0,
    val endSpeed: Double = 60.0,
    /** Track width in metres, for what counts as on it. */
    val width: Double = 3.0,
) : PartModule

/**
 * A skid: a foot that doesn't fold, roll or give, like a helicopter's. Stands on decks like a
 * wheel.
 */
@Serializable
@SerialName("skid")
data object Skid : PartModule

/**
 * A place to make a line fast: a bitt, bollard or towing eye. A winch hooks one in reach first, so
 * tows attach where they should.
 */
@Serializable
@SerialName("towPoint")
data object TowPoint : PartModule

/** A drag device. Nothing until deployed, then it dominates. */
@Serializable
@SerialName("parachute")
data class Parachute(
    val deployedDragCoefficient: Double = 500.0,
    /** Safe deployment speed in m/s. Faster and it tears away. */
    val maxDeploymentSpeed: Double = 300.0,
) : PartModule {
    /**
     * Staging arms a chute; it opens itself as a small drogue once slow enough and in thick enough
     * air, for a fast, steady fall. Near the ground the main opens. State lives in the part's
     * deploy value: 0 packed, up to [DROGUE_FULL] the drogue filling, up to 1 the main, below 0 cut
     * away after landing.
     */
    companion object {
        /** Deploy value with the drogue full and the main not out. */
        const val DROGUE_FULL = 0.5

        /** Drogue drag as a share of the full canopy's. */
        const val DROGUE_SHARE = 0.06

        /** Height above ground or sea where the main opens, in metres. */
        const val MAIN_HEIGHT = 200.0

        /** Share of full canopy drag at [deploy]. */
        fun dragShare(deploy: Double): Double = when {
            deploy <= 0.0 -> 0.0
            deploy <= DROGUE_FULL -> DROGUE_SHARE * (deploy / DROGUE_FULL).let { it * it }
            else -> DROGUE_SHARE + (1.0 - DROGUE_SHARE) * ((deploy - DROGUE_FULL) / (1.0 - DROGUE_FULL)).let { it * it }
        }

        /** Opens at this share of [maxDeploymentSpeed] or slower. */
        const val OPEN_SHARE = 0.95

        /** Minimum air density in kg/m³ to fill the canopy. */
        const val OPEN_DENSITY = 0.04

        /** Fill time in seconds. */
        const val INFLATE_SECONDS = 1.5

        /** Most an opening chute pulls, in g of the craft. */
        const val MOST_PULL_G = 6.0

        /** Down and this slow or slower in m/s, it's cut away so wind can't drag the craft. */
        const val CUT_SPEED = 3.0

        /** Or down this many seconds, however fast it's dragged. */
        const val CUT_AFTER = 1.5
    }
}

/** Displaces [displacedVolume] m³ under water. */
@Serializable
@SerialName("buoyancy")
data class Buoyancy(
    val displacedVolume: Double,
    /**
     * Open to the sky, like a skiff. A wave over the gunwale, or heeling it under, pours water in,
     * and enough sinks it. A decked hull takes none until holed.
     */
    val open: Boolean = false,
) : PartModule {
    companion object {
        /** Share of a hull box that holds water. */
        const val INTERIOR = 0.85

        /** Water in kg part [def] holds when full at [density] kg/m³, or 0 if it isn't a hull. */
        fun capacity(def: PartDef, density: Double = 1_025.0): Double {
            if (def.module<Buoyancy>() == null) return 0.0
            val box = def.mesh as? MeshSpec.Box ?: return 0.0
            return box.width * box.height * box.depth * INTERIOR * density
        }
    }
}

/**
 * A landing leg: a contact point on a spring. A rigid contact stops a craft in one tick, in a
 * single impulse; a spring spreads it over its travel. Deploys when its stage fires. A stowed leg's
 * foot touches nothing, so landing gear-up is a crash.
 */
@Serializable
@SerialName("landingLeg")
data class LandingLeg(
    /** Compression before bottoming out, in metres. */
    val suspensionTravel: Double = 0.4,
    /**
     * Spring rate in N/m. Should hold the landed weight at about half travel: too soft bottoms out,
     * too stiff does nothing.
     */
    val springRate: Double = 90_000.0,
    /** Damping in N per m/s, so the craft doesn't bounce. */
    val damping: Double = 12_000.0,
    /** Seconds from stowed to fully deployed. */
    val deployTime: Double = 1.5,
    /**
     * Fold hinge and axis in part space. Stowed, the leg is turned [stowedAngle] degrees about
     * [foldAxis] through [hinge], and its feet meet the ground wherever that puts them.
     */
    val hinge: SerialVec3 = Vec3(0.0, 0.0, 0.0),
    val foldAxis: SerialVec3 = Vec3(0.0, 0.0, 1.0),
    /** Zero for a leg that doesn't fold. */
    val stowedAngle: Double = 0.0,
) : PartModule

/**
 * Attitude and translation thrusters, for small moves like nudging a landed module into its
 * neighbour. Thrust acts at each block's position, so a symmetric set cancels its torque and a lone
 * block spins the craft. Placement matters, and nothing balances it for you.
 */
@Serializable
@SerialName("rcs")
data class Rcs(
    /** Newtons per block along the commanded axis. */
    val thrust: Double = 1_000.0,
    /** Isp in seconds. Thirsty, and that's fine. */
    val isp: Double = 240.0,
    val propellant: ResourceType = ResourceType.MONOPROPELLANT,
) : PartModule

/**
 * A driven, sprung, rolling contact: a [LandingLeg] that rolls and can be driven. Friction along
 * the rolling direction drops to [rollingResistance] while grip across stays full; otherwise ground
 * friction would hold a rover still and the motor would have to overpower it.
 */
@Serializable
@SerialName("wheel")
data class Wheel(
    /** Metres. Sets the axle height. */
    val radius: Double = 0.35,
    /** Drive force per wheel at full throttle from a standstill, in N. */
    val motorForce: Double = 0.0,
    /**
     * Ground speed in m/s where drive force falls linearly to zero, giving a top speed. Without it
     * a rover keeps accelerating.
     */
    val topSpeed: Double = 22.0,
    /** Whether steering turns this wheel. */
    val steerable: Boolean = false,
    /** Most steering angle, in degrees. */
    val steeringRange: Double = 30.0,
    /**
     * Friction coefficient along the rolling direction. Not zero, or a parked rover creeps down any
     * slope.
     */
    val rollingResistance: Double = 0.06,
    /**
     * Friction coefficient along the rolling direction with brakes on. Less than locked-wheel
     * friction, since harder braking pitches a rover with a high centre of mass over its front
     * wheels. Still holds on a slope of atan(brakeFriction), about nineteen degrees by default.
     */
    val brakeFriction: Double = 0.35,
    val suspensionTravel: Double = 0.25,
    val springRate: Double = 60_000.0,
    val damping: Double = 6_000.0,
) : PartModule

/**
 * Feet that pin a base to the ground. A craft with a working one, resting on the ground, can be
 * anchored: levelled with each foot reaching down up to [travel], on slopes up to [maxSlope], then
 * immovable. See `World.anchor`.
 */
@Serializable
@SerialName("foundation")
data class Foundation(
    /** How far its feet reach down to level it, in metres. */
    val travel: Double = 1.0,
    /** Steepest ground it levels on, in degrees. */
    val maxSlope: Double = 10.0,
) : PartModule

/**
 * Somewhere to launch from. A craft can be set down on it and takes propellant from the base's
 * stores. See `World.spawnOnPad`.
 */
@Serializable
@SerialName("launchPad")
data class LaunchPad(
    /** Charge a launch takes from the base, in units. */
    val launchCharge: Double = 50.0,
) : PartModule

/**
 * Moves propellant and charge between a base and a craft docked to it or on its pad, while the base
 * has power.
 */
@Serializable
@SerialName("pump")
data class Pump(
    /** Units a second of whatever it moves. */
    val rate: Double = 20.0,
    /** Charge drawn while pumping, units a second. */
    val draw: Double = 2.0,
) : PartModule

/** A light, lit at night while there's power. */
@Serializable
@SerialName("lamp")
data class Lamp(
    /** Charge drawn while lit, units a second. */
    val draw: Double = 0.2,
    /**
     * Reach of its pool of light on the ground, in metres. 0 for a lamp that's seen but lights
     * nothing.
     */
    val reach: Double = 0.0,
    /**
     * How far ahead (along +Z, level) its pool of light is centred, in metres, for an aimed
     * floodlight. 0 for all round.
     */
    val aim: Double = 0.0,
) : PartModule

/**
 * A fairing base: a closed shell of [radius] standing [height] above the part's top face round
 * whatever rides on it. Inside is out of the air: no drag or heating. Staged, it opens in two
 * halves ([shellPart], [shellMass] kg each) and the ring stays.
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
    /** Units a second at 1 AU, facing the star. */
    val chargeRate: Double,
    /**
     * Folds out when staged or told to, and back in. Makes nothing folded. Out, it tracks the sun
     * about one axis.
     */
    val deployable: Boolean = false,
    /**
     * A fixed panel's face, in part space; charge depends on how squarely it faces the sun. Null
     * for one that tracks.
     */
    val normal: com.rm.apogee.core.math.SerialVec3? = null,
    /** Torn off if deployed in air pushing harder than this, in Pa. */
    val maxPressure: Double = 3_000.0,
) : PartModule

/**
 * Turns monopropellant into charge: [rate] units a second, burning [monoPerCharge] mono each. Runs
 * itself, on below a quarter full and off at nine tenths.
 */
@Serializable
@SerialName("fuelCell")
data class FuelCell(
    val rate: Double = 1.5,
    val monoPerCharge: Double = 0.01,
) : PartModule

/**
 * Makes [rate] charge a second regardless of light, from isotope heat, for where the sun is too
 * faint.
 */
@Serializable
@SerialName("generator")
data class Generator(val rate: Double) : PartModule

/** Living room for [capacity] people off duty, like a base habitat. Seats, not controls. */
@Serializable
@SerialName("habitat")
data class Habitat(val capacity: Int) : PartModule

/**
 * Walking for a crew member outside. On the ground it drives them toward [speed] m/s within the
 * ground's grip, keeps them upright with a stiff, damped [stand] torque in N·m per radian, and can
 * [jump] them at that many m/s. Off the ground it does nothing; the jetpack flies them.
 */
@Serializable
@SerialName("walker")
data class Walker(
    val speed: Double = 1.6,
    val jump: Double = 3.0,
    val stand: Double = 800.0,
    /** Limb swing each way at full stride, in degrees. Visual only. */
    val swing: Double = 28.0,
    /** Stride length in metres. */
    val stride: Double = 1.4,
) : PartModule

/**
 * A ladder: [length] metres of rungs along the part's Y, centred. Crew nearby can hold on and
 * climb.
 */
@Serializable
@SerialName("ladder")
data class Ladder(val length: Double = 2.4) : PartModule

/**
 * Digs [rate] units a second each of ore and water, scaled by the ground's richness (see
 * `Deposits`). Works switched on, landed, still and powered, with its [head] (part space, deployed)
 * within [reach] metres of the ground. Draws [draw] charge a second.
 */
@Serializable
@SerialName("drill")
data class Drill(
    val rate: Double = 1.0,
    val draw: Double = 2.0,
    val head: com.rm.apogee.core.math.SerialVec3 = com.rm.apogee.core.math.Vec3(0.0, -1.2, 0.0),
    val reach: Double = 1.5,
) : PartModule

/** One [Converter] process: [inputRate] of [input] a second in, [outputs] a second out. */
@Serializable
data class Recipe(
    val input: ResourceType,
    val inputRate: Double,
    val outputs: Map<ResourceType, Double>,
)

/**
 * Makes propellant from what's dug up. Runs each of [recipes] as far as input, room and charge
 * allow, drawing [draw] a second.
 */
@Serializable
@SerialName("converter")
data class Converter(
    val recipes: List<Recipe>,
    val draw: Double = 4.0,
) : PartModule

/**
 * Surveys the ground. From a low orbit (apoapsis under [surveyAltitude] of the body's radius) it
 * maps a whole body's ore and water in half an orbit. Near the ground it reads what's below. Draws
 * [draw] a second.
 */
@Serializable
@SerialName("scanner")
data class Scanner(
    val draw: Double = 0.1,
    val surveyAltitude: Double = 0.45,
) : PartModule

/**
 * Talks home: [range] metres to a ground station or another antenna, the shorter range deciding. A
 * [relay] passes others' signals on. [deployable] ones fold out first. Draws [draw] a second while
 * linked.
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
 * A ballast tank: room for [volume] m³ of sea water, flooded to dive and blown to surface. The
 * water is carried as weight in the part, like a swamped hull's. Flooding needs the tank under
 * water; blowing takes [draw] charge a second. Moves [rate] m³ a second either way.
 */
@Serializable
@SerialName("ballast")
data class Ballast(
    val volume: Double,
    val rate: Double = 0.08,
    val draw: Double = 0.6,
) : PartModule

/**
 * Sonar: what's below and ahead in the water, out to [range] metres. Gives the depth under the
 * craft and the nearest unfound named place in the sea. Draws [draw] charge a second.
 */
@Serializable
@SerialName("sonar")
data class Sonar(
    val range: Double = 2_000.0,
    val draw: Double = 0.02,
) : PartModule

/** Stores electric charge. Separate from [Tank] only for a clearer UI. */
@Serializable
@SerialName("battery")
data class Battery(
    val capacity: Double,
) : PartModule

/**
 * Ablates to take re-entry heat. It heats normally up to [charTemperature], then while ablator
 * lasts gets no hotter: further heat burns ablator at [energyPerUnit] joules a unit. Pointed into
 * the flow, it shades what's behind it.
 */
@Serializable
@SerialName("heatShield")
data class HeatShield(
    val energyPerUnit: Double = 3.0e6,
    val charTemperature: Double = 1_400.0,
) : PartModule

/** What a [DockingPort] is, and so what it joins. */
@Serializable
enum class DockKind {
    /** A docking ring. Joins a ring of the same size. */
    @SerialName("port") PORT,
    /** A tow ball on a rover. Joins a coupling. */
    @SerialName("hitchBall") HITCH_BALL,
    /** A trailer drawbar coupling. Joins a ball. */
    @SerialName("hitchCoupling") HITCH_COUPLING,
    /** A mooring clamp on a hull's side. Joins another clamp. */
    @SerialName("clamp") CLAMP,
}

/**
 * Where two craft join and can part again. The face is at [faceOffset] along the part's +Y, facing
 * +Y. Brought close, lined up and slow to a matching partner, it captures, draws in and straightens
 * it. After [latchSeconds] face to face it latches: a ring or clamp makes one rigid craft; a hitch
 * makes a joint they turn about. Either can be undone.
 *
 * Capture is forgiving (a metre, fifteen degrees, a metre a second) so it works on a phone; the
 * magnets do the rest.
 */
@Serializable
@SerialName("dockingPort")
data class DockingPort(
    val kind: DockKind = DockKind.PORT,
    /** Size class. Only equal sizes join. */
    val size: Int = 1,
    /** Metres along +Y from the part's centre to its face. */
    val faceOffset: Double = 0.15,
    /** Capture distance between faces, in metres. */
    val captureRange: Double = 1.0,
    /** Capture misalignment, in degrees. */
    val captureAngle: Double = 15.0,
    /** Capture closing speed, in m/s. */
    val captureSpeed: Double = 1.0,
    /** Most magnet pull in N, and turn in N·m. */
    val pull: Double = 3_000.0,
    val turn: Double = 1_500.0,
    /** Seconds face to face before it latches. */
    val latchSeconds: Double = 0.5,
    /**
     * Latch distance between faces in metres; the latch squares the rest. Bigger for base
     * connectors, since a module on its feet is held by ground friction the magnets can't beat
     * close in.
     */
    val latchRange: Double = 0.06,
    /** Latch misalignment in degrees; the latch squares the rest. */
    val latchAngle: Double = 2.5,
    /** Push to each side on undocking, in N·s. */
    val undockImpulse: Double = 400.0,
) : PartModule {
    /** Whether this can join [other] at all. */
    fun matesWith(other: DockingPort): Boolean = size == other.size && when (kind) {
        DockKind.PORT -> other.kind == DockKind.PORT
        DockKind.CLAMP -> other.kind == DockKind.CLAMP
        DockKind.HITCH_BALL -> other.kind == DockKind.HITCH_COUPLING
        DockKind.HITCH_COUPLING -> other.kind == DockKind.HITCH_BALL
    }

    /** A hitch turns about its joint; a ring or clamp is rigid. */
    val rigid: Boolean get() = kind == DockKind.PORT || kind == DockKind.CLAMP
}
