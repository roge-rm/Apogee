package com.rm.apogee.core.craft

import com.rm.apogee.core.math.Quat
import com.rm.apogee.core.math.Vec3
import com.rm.apogee.core.part.Engine
import com.rm.apogee.core.part.PartDef
import com.rm.apogee.core.part.ResourceType
import com.rm.apogee.core.part.Tank
import com.rm.apogee.core.physics.RigidBody
import com.rm.apogee.core.math.Math
import kotlin.jvm.JvmInline

@JvmInline
value class VesselId(val raw: Long) {
    override fun toString(): String = "vessel#$raw"
}

/** What the pilot is asking for this tick. */
class ControlState {
    /** 0..1. */
    var throttle: Double = 0.0
        set(value) { field = value.coerceIn(0.0, 1.0) }

    /** -1..1 each. */
    var pitch: Double = 0.0
        set(value) { field = value.coerceIn(-1.0, 1.0) }
    var yaw: Double = 0.0
        set(value) { field = value.coerceIn(-1.0, 1.0) }
    var roll: Double = 0.0
        set(value) { field = value.coerceIn(-1.0, 1.0) }

    /**
     * Translation, -1..1 on each craft-local axis: right, up, forward.
     *
     * This is kept separate from [pitch]/[yaw]/[roll] instead of using a mode flag, because the
     * simulation has no business knowing which of them your thumb is driving right now. The UI
     * decides that, and both sets arrive here.
     */
    var translateX: Double = 0.0
        set(value) { field = value.coerceIn(-1.0, 1.0) }
    var translateY: Double = 0.0
        set(value) { field = value.coerceIn(-1.0, 1.0) }
    var translateZ: Double = 0.0
        set(value) { field = value.coerceIn(-1.0, 1.0) }

    /** Whether the thrusters are switched on at all. */
    var rcsEnabled: Boolean = false

    /**
     * Stability assist. In the air with the stick centred, it holds the attitude you let go of the
     * stick at. On the ground, or with nothing to hold, it damps rotation. See
     * [com.rm.apogee.core.world.StabilityAssist].
     */
    var sasEnabled: Boolean = false

    /** What stability assist holds the nose on, when it's on. */
    var sasMode: com.rm.apogee.core.world.SasMode = com.rm.apogee.core.world.SasMode.HOLD

    /** What the navball's markers, and so the held directions, are measured against. */
    var navFrame: com.rm.apogee.core.world.NavFrame = com.rm.apogee.core.world.NavFrame.AUTO

    /** Another craft to steer by, or -1 for none. */
    var target: Long = -1L

    /**
     * A body to steer by, like a moon or a planet, by id, or blank for none. A craft target wins.
     */
    var targetBody: String = ""

    /**
     * Flying the next planned burn by itself: turning, throttling and cutting. See
     * `World.autoBurn`.
     */
    var autoBurn: Boolean = false

    /** Landing by itself: braking, and settling gently onto its legs. See `World.autoLand`. */
    var autoLand: Boolean = false

    /**
     * Holding an aircraft's height and heading: [cruiseHeight] above the datum in metres, and
     * [cruiseHeading] in degrees north of east. See `World`'s cruise.
     */
    var cruise: Boolean = false
    var cruiseHeight: Double = 0.0
    var cruiseHeading: Double = 0.0
    /** The nose over the flight path it has found it needs to fly level, in degrees. */
    var cruiseTrim: Double = 2.0

    /**
     * Holding station with a keeper core: the body-fixed place it holds (metres from the planet's
     * centre, turning with it), and the collective it has found it needs to hover, 0..1.
     */
    var keeping: Boolean = false
    val keepPoint: Vec3 = Vec3()
    var keepTrim: Double = 0.0

    /** Why the autopilot last gave up, or blank if there's no reason to show. */
    var autopilotNote: String = ""

    /**
     * Wheel brakes. It's a mode rather than a held button, like the parking brake it mostly is. A
     * rover left on a slope has to stay put with nobody holding anything, and rolling resistance
     * alone lets it creep off any slope steeper than about three degrees.
     */
    var brakes: Boolean = false

    /** Wheels driven backwards, so the throttle runs them in reverse. */
    var reverse: Boolean = false

    /** Flaps down, on every wing that has them. */
    var flaps: Boolean = false

    /** Fold-out sun wings and dishes, out or folded away. */
    var deployed: Boolean = false

    /** Whether its drills are switched on. See `World`'s industry. */
    var drilling: Boolean = false

    /** Whether its converters (a craft's, or a base's refinery) are switched on. */
    var refining: Boolean = false

    /** Its ballast tanks: flooding (1), blowing (-1), or neither (0). */
    var ballast: Int = 0

    /**
     * Whether it's holding a depth with the ballast, and which depth, in metres below the surface.
     */
    var holdDepth: Boolean = false
    var holdDepthAt: Double = 0.0

    /**
     * What stability assist is asking for, -1..1 on each axis.
     * [com.rm.apogee.core.world.StabilityAssist] writes this every tick, and a player never does.
     * It's kept apart from [pitch]/[yaw]/[roll] so we can still tell whether the stick is centred,
     * which is what decides whether the assist is allowed to act at all.
     */
    var assistPitch: Double = 0.0
    var assistYaw: Double = 0.0
    var assistRoll: Double = 0.0

    /** Whether the player is touching the attitude controls. */
    val hasAttitudeInput: Boolean get() = pitch != 0.0 || yaw != 0.0 || roll != 0.0

    /**
     * The stick is a request to the station keeper, which tips the craft for it, instead of a turn
     * of its own. The keeper sets this every tick it's doing that.
     */
    var stickTilts: Boolean = false

    /** Whether the stick is steering the craft itself, over stability assist. */
    val stickOverrides: Boolean get() = hasAttitudeInput && !stickTilts

    /**
     * The attitude command the craft acts on: the player's while they're steering, otherwise the
     * assist's. Every control surface, gimbal and reaction wheel reads these, so a hold uses the
     * same authority your thumb does instead of a second, hidden set of controls.
     */
    val commandPitch: Double get() = if (stickOverrides) pitch else assistPitch
    val commandYaw: Double get() = if (stickOverrides) yaw else assistYaw
    val commandRoll: Double get() = if (stickOverrides && !(assistLevelling && roll == 0.0)) roll else assistRoll

    /**
     * Stability assist keeping a boat level while you steer it. The roll stays with the assist
     * while you turn, unless you roll it yourself. The assist writes this every tick.
     */
    var assistLevelling: Boolean = false

    fun reset() {
        throttle = 0.0; pitch = 0.0; yaw = 0.0; roll = 0.0
        translateX = 0.0; translateY = 0.0; translateZ = 0.0
        assistPitch = 0.0; assistYaw = 0.0; assistRoll = 0.0
    }
}

/**
 * A craft as it exists in the world: the blueprint, plus everything about it that changes.
 *
 * Resources are tracked **per part** instead of as one pool for the whole craft. That costs a
 * little bookkeeping and gets the thing that actually matters: as a lower stage drains, the craft's
 * centre of mass climbs and the handling changes with it. A single pooled number would drain "from
 * everywhere at once" and the craft would fly like its mass never moved.
 */
class Vessel(
    val id: VesselId,
    design: CraftDesign,
    defs: List<PartDef>,
    /** Which celestial body this vessel's position is measured from. */
    var referenceBodyId: String,
) {
    var design: CraftDesign = design
        private set

    /**
     * Stability assist's memory: the attitude being held, whether there is one, and the integral of
     * the error. It isn't saved. A reloaded craft just takes its hold from wherever it is on the
     * first tick.
     */
    val assistHeld = com.rm.apogee.core.math.Quat.identity()
    var assistHolding: Boolean = false
    val assistIntegral = Vec3()

    // --- moving parts ---------------------------------------------------------
    //
    // For each part, alongside [defs]: how each moving part is posed. The world works it out every
    // tick and sends it to every client, so a player flying alongside sees the same elevon, the
    // same steered wheel and the same leg half out that the pilot does.

    /** Control surfaces: deflection, -1..1 of their travel. */
    var surfaceDeflection = DoubleArray(design.parts.size)
        private set

    /** Steerable wheels: steering angle, in radians. */
    var wheelSteer = DoubleArray(design.parts.size)
        private set

    /** Wheels: how far the suspension is compressed, in metres. */
    var wheelCompression = DoubleArray(design.parts.size)
        private set

    /**
     * How far each part has sunk into soft ground, in metres. It's kept from tick to tick, because
     * ground gives way over time rather than all at once. See GroundContact.
     */
    var sunk = DoubleArray(design.parts.size)
        private set

    /** Landing legs: deploy progress, from 0 stowed to 1 deployed. */
    var legDeploy = DoubleArray(design.parts.size)
        private set

    /** Gimballed engines: how far the nozzle is swung, -1..1 of its range, in pitch and yaw. */
    var gimbalPitch = DoubleArray(design.parts.size)
        private set
    var gimbalYaw = DoubleArray(design.parts.size)
        private set

    /**
     * Engines: what each one is actually putting out this tick, 0..1 of its full thrust. That's the
     * throttle times whatever propellant reached it. It's zero for one that has run dry however far
     * the throttle is open, and it's what the flame and the sound follow.
     */
    var engineOutput = DoubleArray(design.parts.size)

    /**
     * Rotors and propellers: how fast each is actually turning, 0..1 of its full speed. It spools
     * toward what the throttle asks over a moment (longer the bigger it is), and a rotor lifts
     * with the square of it, so the blades drawn at this speed show what it's lifting. It carries
     * across staging and docking, and is saved.
     */
    var spool = DoubleArray(design.parts.size)

    /**
     * Rotors with cyclic: which way each one's lift points this tick, in the craft's own axes, for
     * drawing its disc tilted the way it's pulling. Three values a part, zero for any other part.
     */
    var rotorTilt = DoubleArray(design.parts.size * 3)
        private set

    /** How far each wing's flaps are down, 0..1. They take a couple of seconds to run out. */
    var flapPosition = DoubleArray(design.parts.size)

    /**
     * Each sail's angle, in radians around its mast (the part's +Y) from the part's own -Z, and how full
     * it is, 0..1: furled or flapping is 0, and drawing hard is 1. The forces set these, and
     * everyone draws them.
     */
    var sailAngle = DoubleArray(design.parts.size)
    var sailFill = DoubleArray(design.parts.size)
        private set

    /**
     * Thruster blocks: which way each one is pushing the craft this tick, and how hard. There are
     * three values per part, in the craft's own axes, with a length of 0..1 of its thrust. It
     * covers sliding and turning together, and it's zero for a block at rest and for every other
     * part. The puffs and the sound follow this.
     */
    var rcsFiring = DoubleArray(design.parts.size * 3)
        private set

    /** New pose arrays for a changed structure, keeping each leg's deploy. */
    private fun resetPose(deploy: DoubleArray, spun: DoubleArray = DoubleArray(deploy.size)) {
        surfaceDeflection = DoubleArray(deploy.size)
        wheelSteer = DoubleArray(deploy.size)
        wheelCompression = DoubleArray(deploy.size)
        sunk = DoubleArray(deploy.size)
        gimbalPitch = DoubleArray(deploy.size)
        gimbalYaw = DoubleArray(deploy.size)
        engineOutput = DoubleArray(deploy.size)
        spool = spun
        rotorTilt = DoubleArray(deploy.size * 3)
        rcsFiring = DoubleArray(deploy.size * 3)
        flapPosition = DoubleArray(deploy.size)
        sailAngle = DoubleArray(deploy.size)
        sailFill = DoubleArray(deploy.size)
        legDeploy = deploy
    }

    /**
     * Sets a leg's deploy progress, or a chute's (below 0 for cut away). Used when restoring a
     * save, mirroring the server, or when the chute fills.
     */
    fun setLegDeploy(index: Int, progress: Double) {
        if (index in legDeploy.indices) legDeploy[index] = progress.coerceIn(-1.0, 1.0)
    }

    /** Resizes the pose arrays after the structure changes. */
    fun fitPose() {
        val n = defs.size
        if (surfaceDeflection.size == n) return
        surfaceDeflection = DoubleArray(n)
        wheelSteer = DoubleArray(n)
        wheelCompression = DoubleArray(n)
        sunk = DoubleArray(n)
        gimbalPitch = DoubleArray(n)
        gimbalYaw = DoubleArray(n)
        engineOutput = DoubleArray(n)
        spool = DoubleArray(n)
        rotorTilt = DoubleArray(n * 3)
        rcsFiring = DoubleArray(n * 3)
        flapPosition = DoubleArray(n)
        sailAngle = DoubleArray(n)
        sailFill = DoubleArray(n)
        // Staged legs start down. A staged chute starts packed, and opens by itself when it's safe
        // to.
        legDeploy = DoubleArray(n) {
            if (it < activated.size && isWorking(it) && defs[it].module<com.rm.apogee.core.part.Parachute>() == null) 1.0 else 0.0
        }
    }

    /** Whether any part of this craft touched the ground last tick. */
    var touchingGround: Boolean = false

    /**
     * How long it has been down, in seconds. It counts while it touches, and only resets once it
     * has been off the ground for half a second, so a craft being dragged or bouncing along still
     * counts as down. This is what cuts a chute away on landing.
     */
    var groundedSeconds: Double = 0.0
        private set
    private var liftedSeconds = 0.0

    /** Moves [groundedSeconds] on by [dt], depending on whether it touched this tick. */
    fun countGrounded(touching: Boolean, dt: Double) {
        if (touching) {
            groundedSeconds += dt
            liftedSeconds = 0.0
        } else {
            liftedSeconds += dt
            if (liftedSeconds > 0.5) groundedSeconds = 0.0
        }
    }

    /**
     * How many contact points it had on the ground last tick. Sharing the craft's weight between
     * them gives the load under each, which decides how far a foot or wheel sinks into soft ground.
     */
    var groundContacts: Int = 0

    /** Looked-up part definitions, alongside `design.parts`. */
    var defs: List<PartDef> = defs
        private set

    val body = RigidBody()
    val control = ControlState()

    var name: String = design.name

    /**
     * Who this craft belongs to, or blank for debris and abandoned craft.
     *
     * It's an id that stays the same for each install, not a session and not a display name. Not a
     * session, because sessions end every time someone closes the app, and the whole point of a
     * persistent world is that the craft is still there when they come back. Not a display name,
     * because names aren't unique or stable. Two devices that never set one both show up as the
     * default and end up sharing a craft, which is exactly what happened the first time two clients
     * met on a server.
     */
    var owner: String = ""

    /**
     * What to call the owner on screen. It's cosmetic, and never used to decide what belongs to
     * whom. See [owner].
     */
    var ownerName: String = ""

    /**
     * Resource amounts for each part, indexed `[partIndex][ResourceType.ordinal]`. It's a flat
     * array instead of a map because this gets read for every engine every tick.
     */
    private var resources: Array<DoubleArray> =
        Array(design.parts.size) { DoubleArray(RESOURCE_COUNT) }

    /** Parts whose stage has fired: engines lit, parachutes out, gear down. */
    var activated: BooleanArray = BooleanArray(design.parts.size)
        private set

    /**
     * How whole each part is, 0..1. Damage from impacts, heat and strain wears it down. At zero the
     * part is destroyed and whatever hung from it comes away (see `World.failParts`).
     */
    var health: DoubleArray = DoubleArray(design.parts.size) { 1.0 }
        private set

    /**
     * How each part is dented, in its own axes: the direction it was hit from, scaled by how badly.
     * Three floats per part, for drawing.
     */
    var crumple: FloatArray = FloatArray(design.parts.size * 3)
        private set

    /**
     * Each part's temperature, in K. It starts at a mild day's temperature, and the air, the sun,
     * engines and re-entry move it from there. See [com.rm.apogee.core.world.Heat].
     */
    var temperature: DoubleArray = DoubleArray(design.parts.size) { AMBIENT_TEMPERATURE }
        private set

    /**
     * Who sits in each part, by crew id: a pod's crew, a habitat's residents, or an astronaut in
     * their suit. They get carried with the parts through every change of structure, just like fuel
     * and damage. See `World`'s crew for who they are.
     */
    var crew: Array<LongArray> = Array(design.parts.size) { NO_CREW }

    /** How many are aboard. */
    val crewAboard: Int get() = crew.sumOf { it.size }

    /** Someone on foot: the grip their feet found this tick, in N·s, for walking to use. See `World`'s walking. */
    var walkGrip: Double = 0.0

    /** Where someone walking is in their stride, in radians. This is what swings their legs. */
    var walkPhase: Double = 0.0

    /**
     * Which side of its body's ring plane it was on last time, or NaN if it hasn't been checked
     * yet. See `World`'s rings.
     */
    var ringSide: Double = Double.NaN

    /** Walking or jumping this tick, so it shouldn't be held still like a parked craft. */
    var walking: Boolean = false

    /** On foot or on a ladder. The stick walks or climbs instead of turning them head over heels. */
    var onFeet: Boolean = false

    /** The world's tick this was last moved on in, for anything that measures against it mid-tick. */
    var movedTick: Long = -1L

    /** Someone in the water, off their feet: the stick swims them, and DIVE and RISE take them down and up. */
    var swimming: Boolean = false

    /**
     * How cold someone in the water has got, 0..1. It climbs while they're in it, faster the colder
     * the sea, and falls again once they're out. At 1 the cold has killed them.
     */
    var chill: Double = 0.0

    /** The ladder being held (its craft's id and the part), or -1 for none. */
    var ladderVessel: Long = -1L
    var ladderPart: Int = -1

    /** How far along the ladder they're holding they are, in metres from its middle, or NaN before the first tick on it. */
    var ladderAlong: Double = Double.NaN

    /** Whether anyone is aboard a working part. */
    fun hasCrew(): Boolean = crew.indices.any { crew[it].isNotEmpty() && !broken[it] }

    /** Takes up to [units] of [type] from part [index] only, and returns what it got. */
    fun takeFromPart(index: Int, type: ResourceType, units: Double): Double {
        val row = resources[index]
        val taken = minOf(units, row[type.ordinal]).coerceAtLeast(0.0)
        row[type.ordinal] -= taken
        return taken
    }

    /**
     * This tick's thrust and air forces on each part, in world axes, in N, with three values per
     * part. [com.rm.apogee.core.world.Stress] works out the joints from this. It's cleared at the
     * start of every tick.
     */
    var partForce: DoubleArray = DoubleArray(design.parts.size * 3)
        private set

    fun recordForce(index: Int, force: Vec3) {
        partForce[index * 3] += force.x
        partForce[index * 3 + 1] += force.y
        partForce[index * 3 + 2] += force.z
    }

    fun clearForces() = partForce.fill(0.0)

    /**
     * How close the joint above each part is to its limit, as load over strength, where 1 is the
     * limit. For the HUD, the sound and the camera.
     */
    var jointLoad: FloatArray = FloatArray(design.parts.size)
        private set

    /**
     * Each wing and fin's air load this tick as a share of what it's built for, from the drag pass.
     * The stress pass hands this on to [jointLoad] without judging it itself.
     */
    var surfaceLoad: FloatArray = FloatArray(design.parts.size)
        private set

    /**
     * Which parts had their centres under water last tick, to tell a part hitting the sea apart
     * from one that's already in it. Null until it's first checked.
     */
    var wet: BooleanArray? = null

    /**
     * Whether any of it was held up by the water last tick, either afloat or at least partly in the
     * sea.
     */
    var buoyed: Boolean = false

    /**
     * Whether all of it, or near enough, was under the water last tick, meaning it's diving rather
     * than riding on the sea.
     */
    var submerged: Boolean = false

    /**
     * How close the sea is to crushing it: the worst of its hollow parts' water pressure compared
     * to what that part is built for, as of last tick. Over 1 and it's giving way. 0 out of the
     * water.
     */
    var crushShare: Double = 0.0

    /**
     * Which faces of each part's volume cells meet the water, and for which shape. Hydrostatics
     * works it out, and again whenever [design] changes.
     */
    var faceExposure: Array<ByteArray>? = null
    var faceExposureFor: CraftDesign? = null

    /**
     * Water that has got into each part, in kg, like an open hull a wave has come over, or a holed
     * one. It's carried as weight, so a boat full of water sits lower and past a point sinks.
     */
    var flooded: DoubleArray = DoubleArray(design.parts.size)

    /**
     * Air let into its gas cells' ballonets, 0..1: none lifts fully, and full takes away the most
     * lift it can. See [com.rm.apogee.core.part.LiftGas].
     */
    var ballonet: Double = 0.0
        set(value) { field = value.coerceIn(0.0, 1.0) }

    /**
     * What holding a height on gas has learned it needs beyond what it works out, in m/s², for
     * whatever pushes it that isn't in the sum.
     */
    var heightIntegral: Double = 0.0

    /** What its gas cells lifted last tick, in newtons, for the HUD and for founding aloft. */
    var gasLift: Double = 0.0

    /**
     * Which way each rotor turns, by part: 1, -1, or 0 for a part that isn't a rotor. With an even
     * number of lifting rotors they alternate round the craft, so a pair or a ring cancels each
     * other's twist, however the builder's symmetry placed them. Otherwise each turns its own way.
     */
    val rotorSpin: IntArray
        get() {
            val cached = rotorSpinCache
            if (cached != null && rotorSpinFor === defs) return cached
            val spins = rotorSpins(design, defs)
            rotorSpinCache = spins
            rotorSpinFor = defs
            return spins
        }
    private var rotorSpinCache: IntArray? = null
    private var rotorSpinFor: List<com.rm.apogee.core.part.PartDef>? = null

    /** The worst of [jointLoad], and which part's joint it is (-1 for none). */
    var stress: Double = 0.0
    var worstJoint: Int = -1

    /** The hottest part, as a share of what it can stand, and which one it is (-1 for none). */
    var hottest: Double = 0.0
    var hottestPart: Int = -1

    /**
     * Takes [amount] of health from [index], with a dent toward [from] (body axes, any length) if
     * given. Returns true if that destroyed it.
     */
    fun damage(index: Int, amount: Double, from: Vec3? = null): Boolean {
        if (index !in health.indices || amount <= 0.0 || health[index] <= 0.0) return false
        health[index] = (health[index] - amount).coerceAtLeast(0.0)
        if (from != null && from.lengthSq > 1e-12) {
            // Into the part's own axes, so the dent turns with it.
            val local = design.parts[index].rotation.inverseRotate(from.normalized())
            val depth = amount.coerceAtMost(1.0).toFloat()
            crumple[index * 3] = (crumple[index * 3] + local.x.toFloat() * depth).coerceIn(-1f, 1f)
            crumple[index * 3 + 1] = (crumple[index * 3 + 1] + local.y.toFloat() * depth).coerceIn(-1f, 1f)
            crumple[index * 3 + 2] = (crumple[index * 3 + 2] + local.z.toFloat() * depth).coerceIn(-1f, 1f)
        }
        return health[index] <= 0.0
    }

    /**
     * Takes on the state of [source]'s parts [indices] (fuel, what has fired, what has failed, how
     * damaged, how deployed) as this craft's parts in the same order. This is for a piece that has
     * just come off another craft. Built fresh, it would otherwise start with full tanks and no
     * damage.
     */
    fun inheritParts(source: Vessel, indices: List<Int>) {
        fitPose()
        indices.forEachIndexed { newIndex, oldIndex ->
            source.resources[oldIndex].copyInto(resources[newIndex])
            activated[newIndex] = source.activated[oldIndex]
            broken[newIndex] = source.broken[oldIndex]
            health[newIndex] = source.health.getOrElse(oldIndex) { 1.0 }
            temperature[newIndex] = source.temperature.getOrElse(oldIndex) { AMBIENT_TEMPERATURE }
            crew[newIndex] = source.crew.getOrElse(oldIndex) { NO_CREW }
            for (k in 0..2) crumple[newIndex * 3 + k] = source.crumple.getOrElse(oldIndex * 3 + k) { 0f }
            setLegDeploy(newIndex, source.legDeploy.getOrElse(oldIndex) { 0.0 })
        }
        recomputeMass(shiftBodyPosition = false)
    }

    /** Restores the condition from a save. */
    fun restoreCondition(savedHealth: List<Double>, savedCrumple: List<Float>, savedTemperature: List<Double> = emptyList()) {
        for (i in health.indices) health[i] = savedHealth.getOrElse(i) { 1.0 }.coerceIn(0.0, 1.0)
        for (i in crumple.indices) crumple[i] = savedCrumple.getOrElse(i) { 0f }
        for (i in temperature.indices) temperature[i] = savedTemperature.getOrElse(i) { AMBIENT_TEMPERATURE }
    }

    /**
     * Parts that have failed but are still attached.
     *
     * A collapsed landing leg and a torn parachute are both like this. The geometry is still there
     * and still has mass, but the module stops working. It's kept separate from [activated] because
     * a broken part mustn't just look un-staged. A torn chute can't be redeployed by staging it
     * again.
     */
    var broken: BooleanArray = BooleanArray(design.parts.size)
        private set

    /** The next stage to fire. It equals `design.stages.size` when staging is used up. */
    /**
     * The air it's in this tick: wind, cloud, rain and how rough. The world samples it before
     * applying forces. It isn't saved, because the weather depends only on where and when.
     */
    val air = com.rm.apogee.core.weather.AirSample()

    /** When [air] was worked out, in world time, and where, body-fixed. NaN until it has been. */
    var airSampledAt = Double.NaN
    val airSampledWhere = Vec3()

    /** Burns planned for this craft, soonest first. See [com.rm.apogee.core.world.PlannedBurn]. */
    val plannedBurns = ArrayList<com.rm.apogee.core.world.PlannedBurn>()

    /**
     * The next burn, in world axes, fixed from when its window opens (see `Burns.WINDOW`). NaN
     * until then.
     */
    val burnVector = Vec3(Double.NaN, 0.0, 0.0)

    /**
     * What has been given toward it since then, by the engines or anything except gravity, in m/s,
     * world axes.
     */
    val burnApplied = Vec3()

    /**
     * The auto-land has had its first tick, and set itself up: where it comes down, and the trims
     * it starts from. Cleared whenever it's off.
     */
    var landStarted: Boolean = false

    /**
     * Its crew are rolling it back upright: when they started, in world time (NaN when they
     * aren't), and the pose it goes from and to. Not saved. A save caught halfway has it lying
     * wherever it had got to, and they can start again.
     */
    var rightingStart: Double = Double.NaN
    val rightingFrom = Quat()
    val rightingTo = Quat()

    /**
     * The craft its wheels, legs or feet are on, as of its last tick, or null on the ground or off
     * it. Not saved: the next tick finds it again.
     */
    var standingOn: Vessel? = null

    /**
     * How long its hull is at the waterline, in metres, and the parts it was worked out for. See
     * `Hydrostatics.hullLength`.
     */
    var hullLength: Double = 0.0
    var hullLengthOf: List<PartDef>? = null

    /** The auto-land has started braking. See `World.autoLand`. */
    var landBraking: Boolean = false

    /**
     * A plane landing itself: its speed when it was asked to, which it keeps most of on the way
     * down so there's enough left to flare with. 0 until the landing's first tick.
     */
    var landSpeed: Double = 0.0

    /**
     * The sink, in m/s, a rotorcraft or airship landing itself is working toward now. It builds up
     * gently from nothing, since asking for it all at once dropped a drone's throttle so low it had
     * nothing left to steer with.
     */
    var landSink: Double = 0.0

    /** How long, in seconds, a craft landing itself has been standing on the ground. */
    var landDownFor: Double = 0.0

    /**
     * Whether a craft landing itself has picked where it's coming down, into its keep point. A
     * rocket picks when it starts braking, and anything flying on the air as it starts.
     */
    var landSpotChosen: Boolean = false

    /** Whether that spot is somewhere other than where it was, so it has to get over it first. */
    var landSpotMoved: Boolean = false

    /**
     * Where a plane landing itself touches down, body-fixed, at the ground, and the level way it
     * lands along from there, body-fixed and unit length, when it has a runway or a clear strip
     * to land on ([landStripSet]). [landFinal] is how far out, in metres, it joins the strip's line.
     */
    val landStripAt = com.rm.apogee.core.math.Vec3()
    val landStripAlong = com.rm.apogee.core.math.Vec3()
    var landStripSet: Boolean = false
    var landFinal: Double = 0.0

    /** The heading a plane landing itself is turning to, degrees north of east, or NaN before it has one. */
    var landHeading: Double = Double.NaN

    /** A plane landing itself going round again, out alongside its strip's line, on its right (1) or left (-1). */
    var landOutbound: Boolean = false
    var landOutSide: Double = 1.0

    /** The angle, in degrees, a plane landing itself has learned the wind blows it off its nose by. */
    var landCrab: Double = 0.0

    /** The stick a helicopter landing itself has learned to hold against a steady push, pitch and roll. */
    var landStickPitch: Double = 0.0
    var landStickRoll: Double = 0.0

    /** Which way a plane landing itself is turning, once it has a long way round to go: 1 left, -1 right, 0 neither. */
    var landTurn: Double = 0.0

    /**
     * How long the next burn takes at full throttle, in seconds, as last worked out. 0 if there's
     * none.
     */
    var burnDuration: Double = 0.0

    /**
     * Which parts ride inside a closed fairing, out of the air. See
     * [com.rm.apogee.core.craft.Fairings]. It gets worked out again when the structure changes or a
     * fairing opens.
     */
    fun enclosed(): BooleanArray {
        var open = 0L
        for (i in defs.indices) if (activated[i] && defs[i].module<com.rm.apogee.core.part.Fairing>() != null) open = open * 31 + i + 1
        if (design !== enclosedDesign || open != enclosedOpen) {
            enclosedCache = Fairings.enclosed(design, defs) { activated.getOrElse(it) { false } }
            enclosedDesign = design
            enclosedOpen = open
        }
        return enclosedCache
    }

    private var enclosedCache = BooleanArray(0)
    private var enclosedDesign: CraftDesign? = null
    private var enclosedOpen = -1L

    /** Forgets any burn in progress, either because there's a new plan or the last one is done. */
    fun resetBurn() {
        burnVector.setTo(Double.NaN, 0.0, 0.0)
        burnApplied.setZero()
    }

    var currentStage: Int = 0
        private set

    /**
     * Which fuel group each part belongs to. Parts share a group when they're connected without a
     * decoupler in between.
     *
     * This is the crossfeed rule, and it's what makes staging mean anything. Without it an engine
     * draws from every tank on the craft, so a first stage quietly burns the upper stage's
     * propellant and separating gets you nothing except lost mass, which is exactly what the first
     * headless ascent did.
     *
     * It's declared above `init` on purpose. Kotlin runs property initialisers and init blocks in
     * the order they're declared, so a field declared further down would have its `IntArray(0)`
     * initialiser overwrite whatever init worked out.
     */
    private var fuelGroups: IntArray = IntArray(0)

    /** Each part's feed tier: see [FuelGroups.tiers]. Higher is drunk first. */
    private var fuelTiers: IntArray = IntArray(0)

    /** Centre of mass in design space, kept so [body]'s position can follow it. */
    private val centerOfMassLocal = Vec3()

    private val scratch = Vec3()
    private val scratchB = Vec3()

    init {
        computeFuelGroups()
        fillTanks()
        recomputeMass(shiftBodyPosition = false)
    }

    val partCount: Int get() = design.parts.size

    val stagesRemaining: Int get() = (design.stages.size - currentStage).coerceAtLeast(0)

    // --- resources ----------------------------------------------------------

    fun fillTanks() {
        for (i in defs.indices) {
            for (module in defs[i].modules) {
                if (module is Tank && module.resource.startsFull) resources[i][module.resource.ordinal] = module.capacity
                // A battery holds charge the same way a tank holds propellant.
                if (module is com.rm.apogee.core.part.Battery) resources[i][ResourceType.ELECTRIC_CHARGE.ordinal] = module.capacity
            }
        }
    }

    fun amountOf(type: ResourceType): Double {
        var total = 0.0
        for (i in resources.indices) total += resources[i][type.ordinal]
        return total
    }

    fun capacityOf(type: ResourceType): Double {
        var total = 0.0
        for (i in defs.indices) total += capacityInPart(i, type)
        return total
    }

    /** What part [index] can hold of [type]: its tanks, plus its batteries for charge. */
    fun capacityInPart(index: Int, type: ResourceType): Double {
        var total = 0.0
        for (module in defs[index].modules) {
            if (module is Tank && module.resource == type) total += module.capacity
            if (module is com.rm.apogee.core.part.Battery && type == ResourceType.ELECTRIC_CHARGE) total += module.capacity
        }
        return total
    }

    // --- a base's power -------------------------------------------------------

    /**
     * When its power was last worked out up to, in universe seconds. NaN before it ever has been.
     * See `World.settlePower`.
     */
    var powerSettledAt: Double = Double.NaN

    /** Whether it has charge to run on. A base with none is dark: no lamps, no pumping, no launching. */
    var powered: Boolean = true

    /**
     * Charge coming in minus going out, in units a second, as last worked out. This is what a
     * base's card shows.
     */
    var powerNet: Double = 0.0

    /** Whether its fuel cells are running. See [com.rm.apogee.core.part.FuelCell]. */
    var fuelCellsOn: Boolean = false

    /**
     * Reaction wheel torque used last tick, in N·m for all axes together, which is what the wheels
     * drew power for.
     */
    var wheelWork: Double = 0.0

    /** Its link home, as last worked out. See [com.rm.apogee.core.world.Comms]. */
    var signal: com.rm.apogee.core.world.Signal = com.rm.apogee.core.world.Signal.NONE

    /**
     * The craft its signal passes through on the way home, by id, nearest first. Empty when it's
     * direct or there's none.
     */
    var signalPath: List<Long> = emptyList()

    /** What it has done since it last left the ground, for a career's feats. Null outside a career. */
    var log: com.rm.apogee.core.career.FlightLog? = null

    /** World time when [signal] was last worked out, or NaN for never. */
    var signalAt: Double = Double.NaN

    /** What its drills are doing, as last worked out. */
    var drillState: com.rm.apogee.core.world.DrillState = com.rm.apogee.core.world.DrillState.OFF

    /**
     * The charge its drills and converters used last, in units a second. It's part of what
     * [powerNet] counts.
     */
    var industryDraw: Double = 0.0

    /** Charge a second its winch used this tick, winding in. */
    var winchDraw: Double = 0.0

    /** Its drills or converters have moved mass since its mass was last worked out. */
    var industryMoved: Boolean = false

    /**
     * Where its drill last bit, body-fixed, and how rich the ground there was in ore and in water.
     * This is worked out again once it has moved.
     */
    val drillSite = Vec3(Double.NaN, 0.0, 0.0)
    var drillOre: Double = 0.0
    var drillWater: Double = 0.0

    /** Seconds spent so far surveying [surveyBody] from a suitable orbit. */
    var surveyProgress: Double = 0.0
    var surveyBody: String = ""

    /** How much more of [type] the parts sharing part [partIndex]'s plumbing have room for. */
    fun roomInGroupOf(partIndex: Int, type: ResourceType): Double {
        val group = fuelGroups[partIndex]
        var total = 0.0
        for (i in resources.indices) if (fuelGroups[i] == group) total += (capacityInPart(i, type) - resources[i][type.ordinal]).coerceAtLeast(0.0)
        return total
    }

    /**
     * Puts up to [amount] of [type] into the parts sharing part [partIndex]'s plumbing, and returns
     * how much went in.
     */
    fun putIntoGroupOf(partIndex: Int, type: ResourceType, amount: Double): Double {
        val group = fuelGroups[partIndex]
        return putInto(resources.indices.filter { fuelGroups[it] == group }, type, amount)
    }

    /** How much of [type] parts [parts] hold between them. */
    fun amountIn(parts: Collection<Int>, type: ResourceType): Double = parts.sumOf { resources[it][type.ordinal] }

    /** How much more of [type] parts [parts] have room for. */
    fun roomIn(parts: Collection<Int>, type: ResourceType): Double =
        parts.sumOf { (capacityInPart(it, type) - resources[it][type.ordinal]).coerceAtLeast(0.0) }

    /**
     * Takes up to [amount] of [type] out of [parts], in their order, and returns how much came out.
     */
    fun takeFrom(parts: Collection<Int>, type: ResourceType, amount: Double): Double {
        var left = amount
        for (i in parts) {
            if (left <= 0.0) break
            val take = minOf(left, resources[i][type.ordinal])
            resources[i][type.ordinal] -= take
            left -= take
        }
        return amount - left
    }

    /**
     * Puts up to [amount] of [type] into [parts] as far as they'll hold, and returns how much went
     * in.
     */
    fun putInto(parts: Collection<Int>, type: ResourceType, amount: Double): Double {
        var left = amount
        for (i in parts) {
            if (left <= 0.0) break
            val room = capacityInPart(i, type) - resources[i][type.ordinal]
            if (room <= 0.0) continue
            val put = minOf(room, left)
            resources[i][type.ordinal] += put
            left -= put
        }
        return amount - left
    }

    /**
     * Takes [amount] of charge from wherever it's held. Returns false, and takes nothing, if there
     * isn't that much.
     */
    fun drawCharge(amount: Double): Boolean {
        val slot = ResourceType.ELECTRIC_CHARGE.ordinal
        if (amountOf(ResourceType.ELECTRIC_CHARGE) < amount) return false
        var left = amount
        for (i in resources.indices) {
            val take = minOf(left, resources[i][slot])
            resources[i][slot] -= take
            left -= take
            if (left <= 0.0) break
        }
        return true
    }

    /**
     * Puts [amount] of charge into its batteries as far as they'll hold, and returns how much went
     * in.
     */
    fun storeCharge(amount: Double): Double {
        val slot = ResourceType.ELECTRIC_CHARGE.ordinal
        var left = amount
        for (i in resources.indices) {
            if (left <= 0.0) break
            val room = capacityInPart(i, ResourceType.ELECTRIC_CHARGE) - resources[i][slot]
            if (room <= 0.0) continue
            val put = minOf(room, left)
            resources[i][slot] += put
            left -= put
        }
        return amount - left
    }

    fun amountInPart(partIndex: Int, type: ResourceType): Double =
        resources[partIndex][type.ordinal]

    /** The total of [type] that [partIndex] can reach through crossfeed. */
    fun amountInGroupOf(partIndex: Int, type: ResourceType): Double {
        val group = fuelGroups[partIndex]
        var total = 0.0
        for (i in resources.indices) {
            if (fuelGroups[i] == group) total += resources[i][type.ordinal]
        }
        return total
    }

    /**
     * Draws up to [amount] units of [type] from the tanks [partIndex] can reach, spread across them
     * in proportion. Returns how much was available.
     *
     * Drawing proportionally within a group is still a simplification, since a real craft has a
     * draw order. But it keeps the centre of mass sliding smoothly instead of lurching as tanks
     * empty one at a time.
     */
    fun drainFromGroupOf(partIndex: Int, type: ResourceType, amount: Double): Double {
        if (amount <= 0.0) return 0.0
        val available = amountInGroupOf(partIndex, type)
        if (available <= 0.0) return 0.0

        val taken = minOf(amount, available)
        val slot = type.ordinal
        val group = fuelGroups[partIndex]
        // Outermost first: a feeder's tanks are emptied before the next tier in is touched, and
        // within a tier they're drawn down evenly.
        var left = taken
        var tier = Int.MAX_VALUE
        while (left > 1e-12) {
            var top = -1
            for (i in resources.indices) {
                if (fuelGroups[i] == group && fuelTiers[i] < tier && resources[i][slot] > 0.0) top = maxOf(top, fuelTiers[i])
            }
            if (top < 0) break
            var inTier = 0.0
            for (i in resources.indices) if (fuelGroups[i] == group && fuelTiers[i] == top) inTier += resources[i][slot]
            val fromTier = minOf(left, inTier)
            val fraction = fromTier / inTier
            for (i in resources.indices) {
                if (fuelGroups[i] == group && fuelTiers[i] == top) resources[i][slot] -= resources[i][slot] * fraction
            }
            left -= fromTier
            tier = top
        }
        return taken
    }

    /** Propellant that any engine that's lit right now can reach. */
    fun propellantAvailableToActiveEngines(type: ResourceType): Double {
        val counted = HashSet<Int>()
        var total = 0.0
        for (engineIndex in activeEngines()) {
            val group = fuelGroups[engineIndex]
            if (!counted.add(group)) continue
            total += amountInGroupOf(engineIndex, type)
        }
        return total
    }

    /**
     * Rebuilds the crossfeed groups.
     *
     * It hands off to [FuelGroups] so the builder's delta-v analysis and the live simulation use
     * literally the same rule. Two copies of it would drift apart, and the builder would start
     * predicting flights the simulation can't fly.
     *
     * It happens rarely enough to be free, since only a spawn or a separation changes structure, so
     * this never ends up in the per-tick path.
     */
    private fun computeFuelGroups() {
        fuelGroups = FuelGroups.compute(design, defs)
        fuelTiers = FuelGroups.tiers(design, defs)
    }

    /**
     * Current resource levels, part by part, for saving.
     *
     * They're copied rather than handed out directly, because the engine loop writes the live
     * arrays every tick, and handing them out would let a save in progress see a half-drained
     * state.
     */
    fun resourceSnapshot(): List<DoubleArray> = resources.map { it.copyOf() }

    /**
     * Every part's levels in one flat list, part by part, for sending over the network. See
     * [restoreFlatResources].
     */
    fun flatResources(): List<Float> {
        val out = ArrayList<Float>(resources.size * RESOURCE_COUNT)
        for (row in resources) for (value in row) out.add(value.toFloat())
        return out
    }

    /**
     * Restores levels from [flatResources]. Returns false, and changes nothing, if they don't fit
     * this craft.
     */
    fun restoreFlatResources(flat: List<Float>): Boolean {
        if (flat.size != resources.size * RESOURCE_COUNT) return false
        var k = 0
        for (row in resources) for (slot in row.indices) row[slot] = flat[k++].toDouble()
        recomputeMass(shiftBodyPosition = false)
        return true
    }

    /** Restores levels taken from [resourceSnapshot]. */
    fun restoreResources(saved: List<DoubleArray>) {
        for (i in resources.indices) {
            val row = saved.getOrNull(i) ?: continue
            row.copyInto(resources[i], endIndex = minOf(row.size, resources[i].size))
        }
        recomputeMass(shiftBodyPosition = false)
    }

    /**
     * The same parts, described again. This is for changes that don't move anything or add or
     * remove anything, like a docking ring letting go of its partner, or a craft getting its own
     * name back.
     */
    fun redesign(newDesign: CraftDesign) {
        require(newDesign.parts.size == design.parts.size) { "redesign keeps the same parts" }
        design = newDesign
    }

    /** Parts that have fired, and parts that have failed, by index. */
    fun activatedIndices(): List<Int> = activated.indices.filter { activated[it] }
    fun brokenIndices(): List<Int> = broken.indices.filter { broken[it] }

    /** Restores which parts are live and which have failed, after loading. */
    fun restoreStaging(
        stage: Int,
        activatedParts: List<Int>,
        brokenParts: List<Int> = emptyList(),
    ) {
        currentStage = stage.coerceIn(0, design.stages.size)
        activated.fill(false)
        for (index in activatedParts) {
            if (index in activated.indices) activated[index] = true
        }
        // Damage survives a reload. Without this, a craft that limped down on a collapsed leg would
        // stand back up repaired the next time the server started, which is the kind of thing a
        // persistent world mustn't do.
        broken.fill(false)
        for (index in brokenParts) {
            if (index in broken.indices) broken[index] = true
        }
    }

    // --- mass ---------------------------------------------------------------

    /** The current mass of one part, including whatever it's carrying. */
    fun massOfPart(index: Int): Double {
        var mass = defs[index].dryMass + flooded.getOrElse(index) { 0.0 }
        // A fairing's shell, while it's still on.
        defs[index].module<com.rm.apogee.core.part.Fairing>()?.let { if (!activated[index]) mass += 2.0 * it.shellMass }
        val amounts = resources[index]
        for (type in ResourceType.entries) {
            mass += amounts[type.ordinal] * type.densityPerUnit
        }
        return mass
    }

    /**
     * Rebuilds mass, centre of mass and inertia from the current resource levels.
     *
     * When the centre of mass moves in design space, [body]'s world position gets shifted to make
     * up for it, so the *parts* stay where they were instead of the craft seeming to jump as its
     * tanks drain.
     */
    fun recomputeMass(shiftBodyPosition: Boolean = true) {
        val masses = DoubleArray(partCount) { massOfPart(it) }
        val properties = MassProperties.compute(design, defs, masses)

        if (shiftBodyPosition) {
            scratch.setTo(properties.centerOfMass).subInPlace(centerOfMassLocal)
            if (scratch.lengthSq > 0.0) {
                body.orientation.rotate(scratch, scratchB)
                body.position.addInPlace(scratchB)
                // Asleep, or as a founded base, it's posed from where its centre of mass sleeps.
                // That has to move with it, or the next tick puts the old centre back where it was
                // and the whole craft jumps by the shift.
                if (dormant) {
                    sleepOrientation.rotate(scratch, scratchB)
                    sleepPosition.addInPlace(scratchB)
                }
            }
        }

        centerOfMassLocal.setTo(properties.centerOfMass)
        recomputeContactRadius()
        body.mass = properties.mass
        body.setInertia(properties.inertia)
    }

    /**
     * [recomputeMass], but only once what it's carrying has changed its mass by more than [share]
     * of it. Adding the parts up is cheap, and working out the inertia and the centre again is
     * not. A boat burning its tank for minutes had it done every step for grams.
     */
    fun recomputeMassIfChanged(share: Double = MASS_CHANGE_SHARE) {
        var total = 0.0
        for (i in 0 until partCount) total += massOfPart(i)
        if (kotlin.math.abs(total - body.mass) > share * body.mass) recomputeMass()
    }

    /** Part [index]'s mass as it is now, dry plus what's in it, in kg. */
    fun partMass(index: Int): Double = massOfPart(index)

    /** Centre of mass in design space. */
    fun centerOfMass(out: Vec3 = Vec3()): Vec3 = out.setTo(centerOfMassLocal)

    // --- geometry -----------------------------------------------------------

    /**
     * The offset from the centre of mass, in world axes, of a point given in part [index]'s own
     * local space.
     */
    fun partPointOffsetWorld(index: Int, local: Vec3, out: Vec3 = Vec3()): Vec3 {
        val placed = design.parts[index]
        placed.rotation.rotate(local, out)
        out.addInPlace(placed.position).subInPlace(centerOfMassLocal)
        return body.orientation.rotate(out, out)
    }

    /** The offset of part [index] from the centre of mass, in world axes. */
    fun partOffsetWorld(index: Int, out: Vec3 = Vec3()): Vec3 {
        out.setTo(design.parts[index].position).subInPlace(centerOfMassLocal)
        return body.orientation.rotate(out, out)
    }

    /**
     * The world position of one of part [index]'s hull contact points.
     *
     * @param pointIndex index into the part definition's `contactPoints`.
     */
    /**
     * The distance from the centre of mass to the furthest contact point, in metres.
     *
     * It's only used to limit how fast the ends of the craft sweep when it's rotating, so the
     * contact solver knows how finely to split up a tick. It's cached because it only changes when
     * the structure or the centre of mass does, and it's wanted every tick.
     */
    var contactRadius: Double = 0.0
        private set

    private fun recomputeContactRadius() {
        var furthest = 0.0
        val point = Vec3()
        for (index in defs.indices) {
            val placed = design.parts[index]
            for (local in defs[index].contactPoints) {
                placed.rotation.rotate(local, point)
                point.addInPlace(placed.position).subInPlace(centerOfMassLocal)
                if (point.lengthSq > furthest) furthest = point.lengthSq
            }
        }
        contactRadius = kotlin.math.sqrt(furthest)
    }

    /**
     * Contact point [pointIndex] of part [index] in its own part space, as it's posed now. A
     * landing leg's feet move as it deploys, and the ground meets them wherever they are: folded
     * against the hull, swinging down, or out on their springs.
     */
    fun posedContactPoint(index: Int, pointIndex: Int, out: Vec3): Vec3 {
        out.setTo(defs[index].contactPoints[pointIndex])
        val leg = defs[index].module<com.rm.apogee.core.part.LandingLeg>() ?: return out
        if (leg.stowedAngle == 0.0) return out
        val deploy = legDeploy.getOrElse(index) { 1.0 }
        if (deploy >= 1.0) return out
        val angle = Math.toRadians(leg.stowedAngle) * (1.0 - deploy)
        com.rm.apogee.core.math.Quat.fromAxisAngle(leg.foldAxis.normalized(), angle, poseFold)
        out.subInPlace(leg.hinge)
        poseFold.rotate(out, out)
        return out.addInPlace(leg.hinge)
    }

    private val poseFold = com.rm.apogee.core.math.Quat.identity()
    private val posePoint = Vec3()

    fun contactPointWorld(index: Int, pointIndex: Int, out: Vec3 = Vec3()): Vec3 {
        val local = posedContactPoint(index, pointIndex, posePoint)
        val placed = design.parts[index]
        // Part-local to design space (the part may be rotated on the craft).
        placed.rotation.rotate(local, out)
        out.addInPlace(placed.position).subInPlace(centerOfMassLocal)
        // Design space to world.
        body.orientation.rotate(out, out)
        return out.addInPlace(body.position)
    }

    /** The offset of a contact point from the centre of mass, in world axes. */
    fun contactOffsetWorld(index: Int, pointIndex: Int, out: Vec3 = Vec3()): Vec3 {
        val local = posedContactPoint(index, pointIndex, posePoint)
        val placed = design.parts[index]
        placed.rotation.rotate(local, out)
        out.addInPlace(placed.position).subInPlace(centerOfMassLocal)
        return body.orientation.rotate(out, out)
    }

    /**
     * The reverse of [contactPointWorld]: a world point in part [index]'s own local frame, written
     * into [out].
     *
     * It lives here instead of in the caller because it needs the centre of mass, which is this
     * class's business. A collider asking for it directly would be reaching through the vessel to
     * rebuild a transform the vessel already knows how to undo.
     */
    fun worldToPartLocal(index: Int, worldPoint: Vec3, out: Vec3 = Vec3()): Vec3 {
        val placed = design.parts[index]
        out.setTo(worldPoint).subInPlace(body.position)
        body.orientation.inverseRotate(out, out)
        out.addInPlace(centerOfMassLocal).subInPlace(placed.position)
        return placed.rotation.inverseRotate(out, out)
    }

    /**
     * A world point in this craft's *design* space.
     *
     * It's different from [worldToPartLocal], which goes one step further into a single part's own
     * frame. This is the frame `CraftDesign` positions are written in, which is what merging two
     * craft needs.
     */
    fun worldToDesign(worldPoint: Vec3, out: Vec3 = Vec3()): Vec3 {
        out.setTo(worldPoint).subInPlace(body.position)
        body.orientation.inverseRotate(out, out)
        return out.addInPlace(centerOfMassLocal)
    }

    /** The world position of part [index], in the reference body's frame. */
    fun partPositionWorld(index: Int, out: Vec3 = Vec3()): Vec3 =
        partOffsetWorld(index, out).addInPlace(body.position)

    /** The craft's nose direction (+Y in design space) in world axes. */
    fun forward(out: Vec3 = Vec3()): Vec3 = body.orientation.rotate(Vec3.unitY(), out)

    // --- staging ------------------------------------------------------------

    fun isActivated(index: Int): Boolean = activated[index]

    /** Whether part [index] is working, meaning it's staged and hasn't failed since. */
    fun isWorking(index: Int): Boolean = activated[index] && !broken[index] && groupState(index) >= 0

    /**
     * Each action group's state, by group number (1 to 3): 0 left alone, so its parts do what the
     * craft's own controls say, 1 switched on, and -1 switched off.
     */
    var groupStates = IntArray(GROUPS + 1)

    /** The state of the action group part [index] is in, or 0 if it's in none. */
    fun groupState(index: Int): Int {
        val group = design.parts.getOrNull(index)?.group ?: 0
        return if (group in 1..GROUPS) groupStates[group] else 0
    }

    /**
     * Whether part [index] runs, given that it would by the craft's own controls when [normal]: a
     * group switched on runs it anyway, and one switched off stops it.
     */
    fun running(index: Int, normal: Boolean): Boolean = when (groupState(index)) {
        1 -> true
        -1 -> false
        else -> normal
    }

    fun isBroken(index: Int): Boolean = broken[index]

    /** Records a part failure. Returns false if it had already failed. */
    fun breakPart(index: Int): Boolean {
        if (index !in broken.indices || broken[index]) return false
        broken[index] = true
        return true
    }

    /**
     * Fires the next stage and marks its parts active.
     *
     * It returns the indices it activated, so the caller can deal with the ones that do more than
     * change this vessel. A decoupler has to split the craft, and only
     * [com.rm.apogee.core.world.World] can create the second vessel.
     */
    fun activateNextStage(): List<Int> {
        if (currentStage >= design.stages.size) return emptyList()
        val stage = design.stages[currentStage]
        currentStage++
        for (index in stage.activatedParts) {
            if (index in activated.indices) activated[index] = true
        }
        return stage.activatedParts
    }

    /** Engines that are lit and still have propellant to burn. */
    fun activeEngines(): List<Int> {
        val result = ArrayList<Int>(4)
        for (i in defs.indices) {
            if (!activated[i] || groupState(i) < 0) continue
            if (defs[i].module<Engine>() != null) result.add(i)
        }
        return result
    }

    /**
     * Replaces this vessel's structure and keeps its motion.
     *
     * Decoupling uses this: the vessel that's left keeps flying with fewer parts. Resource levels
     * carry over for the parts that survive.
     */
    fun replaceStructure(
        newDesign: CraftDesign,
        newDefs: List<PartDef>,
        keptIndices: List<Int>,
    ) {
        val newResources = Array(newDesign.parts.size) { DoubleArray(RESOURCE_COUNT) }
        val newActivated = BooleanArray(newDesign.parts.size)
        val newBroken = BooleanArray(newDesign.parts.size)
        val newDeploy = DoubleArray(newDesign.parts.size)
        val newSpool = DoubleArray(newDesign.parts.size)
        val newHealth = DoubleArray(newDesign.parts.size) { 1.0 }
        val newCrumple = FloatArray(newDesign.parts.size * 3)
        val newTemperature = DoubleArray(newDesign.parts.size) { AMBIENT_TEMPERATURE }
        val newFlooded = DoubleArray(keptIndices.size)
        val newCrew = Array(newDesign.parts.size) { NO_CREW }
        keptIndices.forEachIndexed { newIndex, oldIndex ->
            newTemperature[newIndex] = temperature.getOrElse(oldIndex) { AMBIENT_TEMPERATURE }
            newCrew[newIndex] = crew.getOrElse(oldIndex) { NO_CREW }
            newFlooded[newIndex] = flooded.getOrElse(oldIndex) { 0.0 }
            resources[oldIndex].copyInto(newResources[newIndex])
            newActivated[newIndex] = activated[oldIndex]
            newBroken[newIndex] = broken[oldIndex]
            newDeploy[newIndex] = legDeploy.getOrElse(oldIndex) { 0.0 }
            newSpool[newIndex] = spool.getOrElse(oldIndex) { 0.0 }
            newHealth[newIndex] = health.getOrElse(oldIndex) { 1.0 }
            for (k in 0..2) newCrumple[newIndex * 3 + k] = crumple.getOrElse(oldIndex * 3 + k) { 0f }
        }

        design = newDesign
        defs = newDefs
        resources = newResources
        flooded = newFlooded
        activated = newActivated
        broken = newBroken
        health = newHealth
        crumple = newCrumple
        temperature = newTemperature
        crew = newCrew
        resetStress(newDesign.parts.size)
        resetPose(newDeploy, newSpool)
        name = newDesign.name
        computeFuelGroups()
        recomputeMass()
    }

    // --- dormancy -----------------------------------------------------------

    /**
     * Whether this craft has been put on rails against the ground.
     *
     * A world that people leave things in is mostly made of things nobody is looking at, and a base
     * resting on a pad costs exactly as much to simulate as one being flown. Dormant craft aren't
     * stepped at all: no forces, no integration, no terrain sampling.
     *
     * Dormant never means *gone*. The craft keeps its position, still takes part in collisions, and
     * wakes up the moment anything touches it. Otherwise a returning player would fly straight
     * through their own base.
     */
    var dormant: Boolean = false
        private set

    /**
     * Where it sleeps, in the body's own rotating frame.
     *
     * Freezing the inertial state would be wrong. A craft at rest on the ground is travelling at a
     * hundred and seventy-five metres a second in the inertial frame, and holding *that* still
     * would leave the planet to rotate out from under it. What actually stays the same is its
     * position on the ground, so that's what gets stored, and the inertial state is rebuilt from
     * the body's rotation each tick.
     */
    private val sleepPosition = Vec3()
    private val sleepOrientation = Quat.identity()

    /** Ticks spent within the stillness thresholds, so it doesn't flicker. */
    private var settledTicks: Int = 0

    /** The pose this craft had last tick, in the body's rotating frame. */
    private val lastRestPosition = Vec3()
    private val lastRestOrientation = Quat.identity()
    private var hasRestPose = false
    private val restScratch = Vec3()

    /**
     * How far this craft has actually moved across the ground since the last call, in metres,
     * counting rotation at the rim.
     *
     * This measures distance moved rather than speed, and that difference is the whole point. A
     * craft balanced on sprung legs finishes every tick holding the impulse that cancelled that
     * tick's gravity, 0.163 m/s, because contacts get resolved after gravity and before the next
     * one. Ask it whether it's *moving* and it says yes, forever. Ask whether it has *moved* and it
     * says no to five decimal places, which is the truth.
     *
     * It returns a large number the first time, so nothing anchors on its first tick of contact.
     */
    fun groundMovementSince(position: Vec3, orientation: Quat): Double {
        if (!hasRestPose) {
            lastRestPosition.setTo(position)
            lastRestOrientation.setTo(orientation)
            hasRestPose = true
            return Double.MAX_VALUE
        }

        restScratch.setTo(position).subInPlace(lastRestPosition)
        val linear = restScratch.length

        val dot = kotlin.math.abs(lastRestOrientation dot orientation).coerceAtMost(1.0)
        val turned = 2.0 * kotlin.math.acos(dot)

        lastRestPosition.setTo(position)
        lastRestOrientation.setTo(orientation)
        return linear + turned * contactRadius
    }

    /** Forgets the tracked pose, so a craft that has been moved starts fresh. */
    fun forgetRestPose() {
        hasRestPose = false
    }

    /**
     * Puts the craft to sleep at its current pose, in the rotating frame described by
     * [bodyRotation].
     */
    fun sleep(bodyRotation: Quat) {
        if (dormant) return
        bodyRotation.inverseRotate(body.position, sleepPosition)
        sleepOrientation.setTo(bodyRotation.conjugate().times(body.orientation))
        dormant = true
        settledTicks = 0
    }

    /**
     * Takes how it lies asleep again from how it's turned now, where it is. For a craft set down at
     * its balance afloat after it went to sleep.
     */
    fun retakeTurn(bodyRotation: Quat) {
        sleepOrientation.setTo(bodyRotation.conjugate().times(body.orientation))
    }

    /**
     * Pinned to the ground, as a founded base. It's asleep for good and rides the planet round like
     * any sleeping craft, but touching it never wakes it, and its body is
     * [com.rm.apogee.core.physics.RigidBody.fixed], so nothing that hits it moves it. Its parts
     * still take the blow.
     */
    var anchored: Boolean = false
        private set

    /** Anchors it where it is now, in the rotating frame [bodyRotation] describes. */
    fun anchor(bodyRotation: Quat) {
        if (dormant && !anchored) wake()
        anchored = false
        dormant = false
        sleep(bodyRotation)
        body.linearVelocity.setZero()
        body.angularVelocity.setZero()
        anchored = true
        body.fixed = true
    }

    /**
     * Takes its pose again where it is now, still anchored. This is for after its structure
     * changed, like a module joining or a part breaking off, and its centre of mass moved with it.
     */
    fun reanchor(bodyRotation: Quat) {
        if (!anchored) return
        anchored = false
        dormant = false
        body.fixed = false
        anchor(bodyRotation)
    }

    /** Lets go of the ground, so it's an ordinary craft again, awake. */
    fun unanchor() {
        if (!anchored) return
        anchored = false
        body.fixed = false
        wake()
    }

    /** Returns true if this call is what woke it. It never wakes one that's [anchored]. */
    fun wake(): Boolean {
        if (anchored) return false
        if (!dormant) {
            settledTicks = 0
            return false
        }
        dormant = false
        afloat = false
        ridingOn = null
        settledTicks = 0
        hasRestPose = false
        return true
    }

    /**
     * Rebuilds the inertial pose of a sleeping craft from the body's current rotation. That's four
     * rotations, compared with a full force and contact pass.
     */
    fun followRotation(bodyRotation: Quat, surfaceVelocity: Vec3, spin: Vec3) {
        bodyRotation.rotate(sleepPosition, body.position)
        // setTo then mulInPlace, not `a * b`, because the operator allocates and this runs for
        // every sleeping craft every tick. A world full of parked bases is exactly where an
        // allocation per object per tick hurts the most, and that's the whole reason dormancy
        // exists.
        body.orientation.setTo(bodyRotation).mulInPlace(sleepOrientation)
        body.linearVelocity.setTo(surfaceVelocity)
        body.angularVelocity.setTo(spin)
    }

    /**
     * Asleep afloat: riding the sea instead of pinned to the ground. The water under a moored boat
     * rises and falls with the tide and the waves, and the boat goes with it. It sits [draft]
     * metres from the surface to its centre, tilted the way the water was ([sleepNormal],
     * body-fixed) when it settled.
     */
    var afloat = false

    /**
     * Asleep on another craft's deck: which craft, and where it sits on it, in that craft's own
     * frame, so it goes wherever the deck goes. Null when it isn't. Not saved: loaded, it wakes
     * where it was and settles on the deck again.
     */
    var ridingOn: VesselId? = null
    private val rideOffset = Vec3()
    private val rideTurn = Quat()

    /** Goes to sleep on [deck], as it sits on it now. */
    fun ride(deck: Vessel) {
        if (dormant) return
        rideOffset.setTo(body.position).subInPlace(deck.body.position)
        deck.body.orientation.inverseRotate(rideOffset, rideOffset)
        rideTurn.setTo(deck.body.orientation.conjugate().times(body.orientation))
        dormant = true
        ridingOn = deck.id
        settledTicks = 0
    }

    /** Posed where it sleeps on [deck], moving with it. */
    fun followRide(deck: Vessel) {
        deck.body.orientation.rotate(rideOffset, body.position).addInPlace(deck.body.position)
        body.orientation.setTo(deck.body.orientation).mulInPlace(rideTurn).normalizeInPlace()
        deck.body.velocityAtOffset(rideScratch.setTo(body.position).subInPlace(deck.body.position), body.linearVelocity)
        body.angularVelocity.setTo(deck.body.angularVelocity)
    }

    private val rideScratch = Vec3()
    var draft = 0.0
    val sleepNormal = Vec3()

    /** Where it went to sleep, body-fixed, unit length. */
    fun sleepDirection(out: Vec3): Vec3 = out.setTo(sleepPosition).normalizeInPlace()

    /**
     * The pose of a craft asleep afloat: straight out along where it went to sleep, [radius] from
     * the centre, tipped by [tilt] (body-fixed) from how it lay, and all turned by [bodyRotation].
     */
    fun followSea(bodyRotation: Quat, radius: Double, tilt: Quat, velocity: Vec3, spin: Vec3) {
        seaScratch.setTo(sleepPosition).normalizeInPlace().mulInPlace(radius)
        bodyRotation.rotate(seaScratch, body.position)
        body.orientation.setTo(bodyRotation).mulInPlace(tilt).mulInPlace(sleepOrientation)
        body.linearVelocity.setTo(velocity)
        body.angularVelocity.setTo(spin)
    }

    private val seaScratch = Vec3()

    /**
     * Counts still ticks in a row and reports when it has been still long enough to sleep. This
     * stops a craft rocking gently on its gear from flickering in and out of dormancy.
     */
    fun noteStillness(still: Boolean, requiredTicks: Int): Boolean {
        if (!still) {
            settledTicks = 0
            return false
        }
        settledTicks++
        return settledTicks >= requiredTicks
    }

    /**
     * Takes on [other]'s parts as well as its own, becoming [newDesign].
     *
     * This is the partner to [replaceStructure], which can only ever describe *part* of one craft.
     * Its index map says where each surviving part came from, and there's no way in it to say "from
     * the other vessel". Merging needs both sources, so it gets its own path instead of a cleverer
     * index map.
     *
     * [newDesign] has to be this craft's parts in their existing order followed by [other]'s in
     * theirs, which is what [com.rm.apogee.core.world.World]'s merge builds. The per-part state is
     * carried across by position.
     */
    fun absorb(newDesign: CraftDesign, newDefs: List<PartDef>, other: Vessel) {
        val own = design.parts.size
        require(newDesign.parts.size == own + other.design.parts.size) {
            "merged design must be this craft's parts followed by the other's"
        }

        val newResources = Array(newDesign.parts.size) { DoubleArray(RESOURCE_COUNT) }
        val newActivated = BooleanArray(newDesign.parts.size)
        val newBroken = BooleanArray(newDesign.parts.size)

        val newDeploy = DoubleArray(newDesign.parts.size)
        val newSpool = DoubleArray(newDesign.parts.size)
        val newHealth = DoubleArray(newDesign.parts.size) { 1.0 }
        val newCrumple = FloatArray(newDesign.parts.size * 3)
        val newTemperature = DoubleArray(newDesign.parts.size)
        temperature.copyInto(newTemperature, 0, 0, own)
        other.temperature.copyInto(newTemperature, own)
        val newCrew = Array(newDesign.parts.size) { if (it < own) crew.getOrElse(it) { NO_CREW } else other.crew.getOrElse(it - own) { NO_CREW } }
        val newFlooded = DoubleArray(newDesign.parts.size)
        for (i in 0 until own) newFlooded[i] = flooded.getOrElse(i) { 0.0 }
        for (j in other.design.parts.indices) newFlooded[own + j] = other.flooded.getOrElse(j) { 0.0 }
        for (i in 0 until own) {
            resources[i].copyInto(newResources[i])
            newActivated[i] = activated[i]
            newBroken[i] = broken[i]
            newDeploy[i] = legDeploy.getOrElse(i) { 0.0 }
            newSpool[i] = spool.getOrElse(i) { 0.0 }
            newHealth[i] = health[i]
            crumple.copyInto(newCrumple, i * 3, i * 3, i * 3 + 3)
        }
        for (j in other.design.parts.indices) {
            other.resources[j].copyInto(newResources[own + j])
            newActivated[own + j] = other.activated[j]
            newBroken[own + j] = other.broken[j]
            newDeploy[own + j] = other.legDeploy.getOrElse(j) { 0.0 }
            newSpool[own + j] = other.spool.getOrElse(j) { 0.0 }
            newHealth[own + j] = other.health[j]
            other.crumple.copyInto(newCrumple, (own + j) * 3, j * 3, j * 3 + 3)
        }

        design = newDesign
        defs = newDefs
        resources = newResources
        flooded = newFlooded
        activated = newActivated
        broken = newBroken
        health = newHealth
        crumple = newCrumple
        temperature = newTemperature
        crew = newCrew
        resetStress(newDesign.parts.size)
        resetPose(newDeploy, newSpool)
        computeFuelGroups()
        recomputeMass()
    }

    private fun resetStress(parts: Int) {
        wet = null
        partForce = DoubleArray(parts * 3)
        jointLoad = FloatArray(parts)
        surfaceLoad = FloatArray(parts)
        stress = 0.0
        worstJoint = -1
    }

    override fun toString(): String = "Vessel($id '$name', ${partCount}p, ${body.mass.toInt()}kg)"

    companion object {
        /** How much a craft's mass can drift from its last full working-out before it's done again. */
        const val MASS_CHANGE_SHARE = 0.001

        /** Which way each rotor of [design] turns: see [rotorSpin]. The builder's stats use it too. */
        fun rotorSpins(design: CraftDesign, defs: List<com.rm.apogee.core.part.PartDef>): IntArray {
            val spins = IntArray(defs.size)
            val lifting = defs.indices.filter { defs[it].module<com.rm.apogee.core.part.Rotor>()?.tail == false }
            for (i in defs.indices) spins[i] = defs[i].module<com.rm.apogee.core.part.Rotor>()?.spin?.let { if (it < 0) -1 else 1 } ?: 0
            if (lifting.size >= 2 && lifting.size % 2 == 0) {
                // Round the craft's up axis, from the middle of the rotors.
                val up = design.orientation.up
                val middle = Vec3()
                for (i in lifting) middle.addInPlace(design.parts[i].position)
                middle.mulInPlace(1.0 / lifting.size)
                val across = (if (kotlin.math.abs(up.x) < 0.9) Vec3.unitX() else Vec3.unitY()).cross(up).normalizeInPlace()
                val other = up.cross(across)
                val order = lifting.sortedBy { i ->
                    val r = Vec3().setTo(design.parts[i].position).subInPlace(middle)
                    kotlin.math.atan2(r dot other, r dot across)
                }
                order.forEachIndexed { k, i -> spins[i] = if (k % 2 == 0) 1 else -1 }
            }
            return spins
        }

        /** How many action groups a craft can have. */
        const val GROUPS = 3

        private val RESOURCE_COUNT = ResourceType.entries.size

        /** A part nobody is in. */
        val NO_CREW = LongArray(0)

        /** Where every part's temperature starts, in K. */
        const val AMBIENT_TEMPERATURE = 288.0
    }
}
