package com.rm.apogee.core.craft

import com.rm.apogee.core.math.Quat
import com.rm.apogee.core.math.Vec3
import com.rm.apogee.core.part.Engine
import com.rm.apogee.core.part.PartDef
import com.rm.apogee.core.part.ResourceType
import com.rm.apogee.core.part.Tank
import com.rm.apogee.core.physics.RigidBody

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
     * Separate from [pitch]/[yaw]/[roll] rather than a mode flag, because the
     * simulation has no business knowing which one the player's thumb is
     * currently driving. The UI decides that; both sets arrive here.
     */
    var translateX: Double = 0.0
        set(value) { field = value.coerceIn(-1.0, 1.0) }
    var translateY: Double = 0.0
        set(value) { field = value.coerceIn(-1.0, 1.0) }
    var translateZ: Double = 0.0
        set(value) { field = value.coerceIn(-1.0, 1.0) }

    /** Whether the thrusters are enabled at all. */
    var rcsEnabled: Boolean = false

    /**
     * Stability assist. In the air with the stick centred it holds the
     * attitude the stick was released at; on the ground, or with nothing to
     * hold, it damps rotation. See [com.rm.apogee.core.world.StabilityAssist].
     */
    var sasEnabled: Boolean = false

    /** What stability assist holds the nose on, when it is on. */
    var sasMode: com.rm.apogee.core.world.SasMode = com.rm.apogee.core.world.SasMode.HOLD

    /** What the navball's markers - and so the held directions - are measured against. */
    var navFrame: com.rm.apogee.core.world.NavFrame = com.rm.apogee.core.world.NavFrame.AUTO

    /** Another craft to steer by, or -1 for none. */
    var target: Long = -1L

    /** A body to steer by - a moon, a planet - by id, or blank for none. A craft target wins. */
    var targetBody: String = ""

    /** Flying the next planned burn by itself: turning, throttling, cutting. See `World.autoBurn`. */
    var autoBurn: Boolean = false

    /** Setting itself down by itself: braking, and down gently onto its legs. See `World.autoLand`. */
    var autoLand: Boolean = false

    /** Why the autopilot last gave up, blank for no reason to tell. */
    var autopilotNote: String = ""

    /**
     * Wheel brakes. A mode rather than a held button, like the parking brake
     * it mostly is: a rover left on a slope has to stay there with nobody
     * holding anything, and rolling resistance alone lets it creep off any
     * slope steeper than about three degrees.
     */
    var brakes: Boolean = false

    /** Wheels driven backwards: the throttle runs them in reverse. */
    var reverse: Boolean = false

    /** Fold-out sun wings and dishes: out, or folded away. */
    var deployed: Boolean = false

    /** Its drills switched on: see `World`'s industry. */
    var drilling: Boolean = false

    /** Its converters - a craft's, a base's refinery - switched on. */
    var refining: Boolean = false

    /**
     * What stability assist is asking for, -1..1 on each axis, written each
     * tick by [com.rm.apogee.core.world.StabilityAssist] and never by a
     * player. Kept apart from [pitch]/[yaw]/[roll] so that "is the stick
     * centred" stays answerable - which is the question that decides whether
     * the assist may act at all.
     */
    var assistPitch: Double = 0.0
    var assistYaw: Double = 0.0
    var assistRoll: Double = 0.0

    /** Whether the player is touching the attitude controls. */
    val hasAttitudeInput: Boolean get() = pitch != 0.0 || yaw != 0.0 || roll != 0.0

    /**
     * The attitude command the craft acts on: the player's while they are
     * steering, otherwise the assist's. Every control surface, gimbal and
     * reaction wheel reads these, so a hold uses the same authority a thumb
     * does rather than a second, invisible set of controls.
     */
    val commandPitch: Double get() = if (hasAttitudeInput) pitch else assistPitch
    val commandYaw: Double get() = if (hasAttitudeInput) yaw else assistYaw
    val commandRoll: Double get() = if (hasAttitudeInput && !(assistLevelling && roll == 0.0)) roll else assistRoll

    /**
     * Stability assist keeping a boat level while it is steered: the roll
     * stays the assist's while the player turns, unless they roll it
     * themselves. Written each tick by the assist.
     */
    var assistLevelling: Boolean = false

    fun reset() {
        throttle = 0.0; pitch = 0.0; yaw = 0.0; roll = 0.0
        translateX = 0.0; translateY = 0.0; translateZ = 0.0
        assistPitch = 0.0; assistYaw = 0.0; assistRoll = 0.0
    }
}

/**
 * A craft as it exists in the world: the blueprint, plus everything about it
 * that changes.
 *
 * Resources are tracked **per part** rather than as one vessel-wide pool. That
 * costs a little bookkeeping and buys the thing that actually matters: as a
 * lower stage drains, the craft's centre of mass climbs, and the handling
 * changes with it. A single pooled number would drain "from everywhere at
 * once" and the craft would fly like its mass never moved.
 */
class Vessel(
    val id: VesselId,
    design: CraftDesign,
    defs: List<PartDef>,
    /** Which celestial body this vessel's position is expressed relative to. */
    var referenceBodyId: String,
) {
    var design: CraftDesign = design
        private set

    /**
     * Stability assist's memory: the attitude being held, whether there is
     * one, and the integral of the error. Not saved - a reloaded craft simply
     * takes its hold from wherever it is on the first tick.
     */
    val assistHeld = com.rm.apogee.core.math.Quat.identity()
    var assistHolding: Boolean = false
    val assistIntegral = Vec3()

    // --- moving parts ---------------------------------------------------------
    //
    // Per part, parallel to [defs]: how each moving part is posed. Worked out
    // every tick by the world and sent to every client, so a player flying
    // alongside sees the same elevon, the same steered wheel and the same leg
    // half out that the pilot does.

    /** Control surfaces: deflection, -1..1 of their travel. */
    var surfaceDeflection = DoubleArray(design.parts.size)
        private set

    /** Steerable wheels: steering angle, radians. */
    var wheelSteer = DoubleArray(design.parts.size)
        private set

    /** Wheels: how far the suspension is compressed, metres. */
    var wheelCompression = DoubleArray(design.parts.size)
        private set

    /**
     * How far each part has sunk into soft ground, metres - kept from tick
     * to tick, since ground gives over time rather than all at once. See
     * GroundContact.
     */
    var sunk = DoubleArray(design.parts.size)
        private set

    /** Landing legs: deploy progress, 0 stowed to 1 deployed. */
    var legDeploy = DoubleArray(design.parts.size)
        private set

    /** Gimballed engines: how far the nozzle is swung, -1..1 of its range, about pitch and yaw. */
    var gimbalPitch = DoubleArray(design.parts.size)
        private set
    var gimbalYaw = DoubleArray(design.parts.size)
        private set

    /**
     * Engines: what each is actually putting out this tick, 0..1 of its full
     * thrust - throttle times whatever propellant reached it. Zero for one
     * that has run dry, however far the throttle is open: what the flame and
     * the sound follow.
     */
    var engineOutput = DoubleArray(design.parts.size)
        private set

    /**
     * Thruster blocks: which way each is pushing the craft this tick, and
     * how hard - three values per part, in the craft's own axes, the length
     * 0..1 of its thrust. Sliding and turning together; zero for a block at
     * rest and for every other part. What the puffs and the sound follow.
     */
    var rcsFiring = DoubleArray(design.parts.size * 3)
        private set

    /** New pose arrays for a changed structure, keeping each leg's deploy. */
    private fun resetPose(deploy: DoubleArray) {
        surfaceDeflection = DoubleArray(deploy.size)
        wheelSteer = DoubleArray(deploy.size)
        wheelCompression = DoubleArray(deploy.size)
        sunk = DoubleArray(deploy.size)
        gimbalPitch = DoubleArray(deploy.size)
        gimbalYaw = DoubleArray(deploy.size)
        engineOutput = DoubleArray(deploy.size)
        rcsFiring = DoubleArray(deploy.size * 3)
        legDeploy = deploy
    }

    /**
     * Sets a leg's deploy progress, or a chute's (below 0 for cut away):
     * restoring a save, mirroring the server, or the chute filling.
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
        rcsFiring = DoubleArray(n * 3)
        // Staged legs start down; a staged chute starts packed, and opens
        // itself when it is safe to.
        legDeploy = DoubleArray(n) {
            if (it < activated.size && isWorking(it) && defs[it].module<com.rm.apogee.core.part.Parachute>() == null) 1.0 else 0.0
        }
    }

    /** Whether anything of this craft touched the ground last tick. */
    var touchingGround: Boolean = false

    /**
     * How long it has been down, s: counting while it touches, and let go
     * only once it has been off the ground half a second - a craft dragged
     * or bouncing along is still down. What cuts a chute away on landing.
     */
    var groundedSeconds: Double = 0.0
        private set
    private var liftedSeconds = 0.0

    /** Counts [groundedSeconds] on by [dt], from whether it touched this tick. */
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
     * How many contact points it had on the ground last tick. Sharing the
     * craft's weight among them gives the load under each, which is what
     * decides how far a foot or a wheel sinks into soft ground.
     */
    var groundContacts: Int = 0

    /** Resolved definitions, parallel to `design.parts`. */
    var defs: List<PartDef> = defs
        private set

    val body = RigidBody()
    val control = ControlState()

    var name: String = design.name

    /**
     * Who this craft belongs to, or blank for debris and abandoned craft.
     *
     * An opaque per-install id, not a session and not a display name. Not a
     * session because sessions end every time someone closes the app, and the
     * whole point of a persistent world is that the craft is still there when
     * they come back. Not a display name because names are neither unique nor
     * stable: two devices that never set one both arrive as the default and
     * end up sharing a craft, which is exactly what happened the first time
     * two clients met on a server.
     */
    var owner: String = ""

    /**
     * What to call the owner on screen. Cosmetic, and never used to decide
     * what belongs to whom - see [owner].
     */
    var ownerName: String = ""

    /**
     * Per-part resource amounts, indexed `[partIndex][ResourceType.ordinal]`.
     * A flat array rather than a map: this is read for every engine every tick.
     */
    private var resources: Array<DoubleArray> =
        Array(design.parts.size) { DoubleArray(RESOURCE_COUNT) }

    /** Parts whose stage has fired: engines lit, parachutes out, gear down. */
    var activated: BooleanArray = BooleanArray(design.parts.size)
        private set

    /**
     * How whole each part is, 0..1. Damage from impacts, heat and strain
     * wears it down; at zero the part is destroyed and whatever hung from it
     * comes away (see `World.failParts`).
     */
    var health: DoubleArray = DoubleArray(design.parts.size) { 1.0 }
        private set

    /**
     * How each part is dented, in its own axes: the direction it was struck
     * from, scaled by how badly - three floats a part. For drawing.
     */
    var crumple: FloatArray = FloatArray(design.parts.size * 3)
        private set

    /**
     * Each part's temperature, K. Starts at a mild day's; the air, the sun,
     * engines and re-entry move it from there - see
     * [com.rm.apogee.core.world.Heat].
     */
    var temperature: DoubleArray = DoubleArray(design.parts.size) { AMBIENT_TEMPERATURE }
        private set

    /** Takes up to [units] of [type] from part [index] alone; returns what it got. */
    fun takeFromPart(index: Int, type: ResourceType, units: Double): Double {
        val row = resources[index]
        val taken = minOf(units, row[type.ordinal]).coerceAtLeast(0.0)
        row[type.ordinal] -= taken
        return taken
    }

    /**
     * This tick's thrust and air forces on each part, world axes, N - three
     * a part. What [com.rm.apogee.core.world.Stress] works the joints out
     * from; cleared at the start of every tick.
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
     * How near its limit the joint above each part is, as load over
     * strength: 1 is the limit. For the HUD, the sound and the camera.
     */
    var jointLoad: FloatArray = FloatArray(design.parts.size)
        private set

    /**
     * Each wing and fin's air load this tick as a share of what it is built
     * for, from the drag pass - handed on to [jointLoad] by the stress pass,
     * which does not judge them itself.
     */
    var surfaceLoad: FloatArray = FloatArray(design.parts.size)
        private set

    /**
     * Which parts had their centres under water last tick, for telling a
     * part hitting the sea from one already in it; null until first looked.
     */
    var wet: BooleanArray? = null

    /** Whether any of it was held up by the water last tick: afloat, or at least partly in the sea. */
    var buoyed: Boolean = false

    /**
     * Which faces of each part's volume cells meet the water, and for which
     * shape - worked out by Hydrostatics, again whenever [design] changes.
     */
    var faceExposure: Array<ByteArray>? = null
    var faceExposureFor: CraftDesign? = null

    /**
     * Water shipped into each part, kg: an open hull that a wave has come
     * over, or a holed one. Carried as weight - a boat full of water sits
     * lower, and past a point sinks.
     */
    var flooded: DoubleArray = DoubleArray(design.parts.size)

    /** The worst of [jointLoad], and which part's joint it is (-1 for none). */
    var stress: Double = 0.0
    var worstJoint: Int = -1

    /** The hottest part, as a share of what it can stand, and which (-1 for none). */
    var hottest: Double = 0.0
    var hottestPart: Int = -1

    /**
     * Takes [amount] of health from [index]; a dent toward [from] (body
     * axes, any length) if given. Returns true if that finished it.
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
     * Takes on the state of [source]'s parts [indices] - fuel, what has
     * fired, what has failed, how damaged, how deployed - as this craft's
     * parts in the same order. For a piece that has just come off another:
     * built fresh, it would otherwise start with full tanks and no damage.
     */
    fun inheritParts(source: Vessel, indices: List<Int>) {
        fitPose()
        indices.forEachIndexed { newIndex, oldIndex ->
            source.resources[oldIndex].copyInto(resources[newIndex])
            activated[newIndex] = source.activated[oldIndex]
            broken[newIndex] = source.broken[oldIndex]
            health[newIndex] = source.health.getOrElse(oldIndex) { 1.0 }
            temperature[newIndex] = source.temperature.getOrElse(oldIndex) { AMBIENT_TEMPERATURE }
            for (k in 0..2) crumple[newIndex * 3 + k] = source.crumple.getOrElse(oldIndex * 3 + k) { 0f }
            setLegDeploy(newIndex, source.legDeploy.getOrElse(oldIndex) { 0.0 })
        }
        recomputeMass(shiftBodyPosition = false)
    }

    /** Restores condition from a save. */
    fun restoreCondition(savedHealth: List<Double>, savedCrumple: List<Float>, savedTemperature: List<Double> = emptyList()) {
        for (i in health.indices) health[i] = savedHealth.getOrElse(i) { 1.0 }.coerceIn(0.0, 1.0)
        for (i in crumple.indices) crumple[i] = savedCrumple.getOrElse(i) { 0f }
        for (i in temperature.indices) temperature[i] = savedTemperature.getOrElse(i) { AMBIENT_TEMPERATURE }
    }

    /**
     * Parts that have failed but are still attached.
     *
     * A collapsed landing leg and a torn parachute are both this: the geometry
     * is still there and still has mass, but the module stops working. Kept
     * separate from [activated] because a broken part must not simply look
     * un-staged - a torn chute cannot be redeployed by staging again.
     */
    var broken: BooleanArray = BooleanArray(design.parts.size)
        private set

    /** Next stage to fire. Equals `design.stages.size` when staging is spent. */
    /**
     * The air it is in this tick: wind, cloud, rain, how rough. Sampled by
     * the world before forces are applied; not saved, since the weather is a
     * function of where and when.
     */
    val air = com.rm.apogee.core.weather.AirSample()

    /** Burns planned for this craft, soonest first: see [com.rm.apogee.core.world.PlannedBurn]. */
    val plannedBurns = ArrayList<com.rm.apogee.core.world.PlannedBurn>()

    /**
     * The next burn, in the world's axes, fixed from when its window opens
     * (see `Burns.WINDOW`); NaN until then.
     */
    val burnVector = Vec3(Double.NaN, 0.0, 0.0)

    /** What has been given toward it since - by the engines, or anything but gravity - m/s, world axes. */
    val burnApplied = Vec3()

    /** The auto-land has begun braking: see `World.autoLand`. */
    var landBraking: Boolean = false

    /** How long the next burn takes at full throttle, s, as last worked out; 0 with none. */
    var burnDuration: Double = 0.0

    /**
     * Which parts ride inside a closed fairing, out of the air: see
     * [com.rm.apogee.core.craft.Fairings]. Worked out again when the
     * structure changes or a fairing opens.
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

    /** Forgets any burn in progress: a new plan, or the last one done. */
    fun resetBurn() {
        burnVector.setTo(Double.NaN, 0.0, 0.0)
        burnApplied.setZero()
    }

    var currentStage: Int = 0
        private set

    /**
     * Which fuel group each part belongs to. Parts share a group when they are
     * connected without a decoupler in between.
     *
     * This is the crossfeed rule, and it is what makes staging mean anything.
     * Without it an engine draws from every tank on the craft, so a first stage
     * quietly burns the upper stage's propellant and separating buys nothing
     * but lost mass - which is exactly what the first headless ascent did.
     *
     * Declared above `init` deliberately: Kotlin runs property initialisers and
     * init blocks in declaration order, so a field declared further down would
     * have its `IntArray(0)` initialiser overwrite whatever init computed.
     */
    private var fuelGroups: IntArray = IntArray(0)

    /** Centre of mass in design-space, kept so [body]'s position can track it. */
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
                // A battery holds charge as a tank holds propellant.
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

    /** What part [index] can hold of [type]: its tanks, and its batteries for charge. */
    fun capacityInPart(index: Int, type: ResourceType): Double {
        var total = 0.0
        for (module in defs[index].modules) {
            if (module is Tank && module.resource == type) total += module.capacity
            if (module is com.rm.apogee.core.part.Battery && type == ResourceType.ELECTRIC_CHARGE) total += module.capacity
        }
        return total
    }

    // --- a base's power -------------------------------------------------------

    /** When its power was last worked out to, universe seconds; NaN before it ever has been. See `World.settlePower`. */
    var powerSettledAt: Double = Double.NaN

    /** Whether it has charge to run on: a base with none is dark - no lamps, no pumping, no launching. */
    var powered: Boolean = true

    /** Charge coming in less going out, units a second, as last worked out: what a base's card shows. */
    var powerNet: Double = 0.0

    /** Its fuel cells running: see [com.rm.apogee.core.part.FuelCell]. */
    var fuelCellsOn: Boolean = false

    /** Reaction-wheel torque used last tick, N·m all axes together: what the wheels drew for. */
    var wheelWork: Double = 0.0

    /** Its link home, as last worked out: see [com.rm.apogee.core.world.Comms]. */
    var signal: com.rm.apogee.core.world.Signal = com.rm.apogee.core.world.Signal.NONE

    /** The craft its signal passes through on the way home, by id, nearest first; empty when direct or none. */
    var signalPath: List<Long> = emptyList()

    /** World time [signal] was last worked out, NaN for never. */
    var signalAt: Double = Double.NaN

    /** What its drills are doing, as last worked out. */
    var drillState: com.rm.apogee.core.world.DrillState = com.rm.apogee.core.world.DrillState.OFF

    /** Charge its drills and converters used last, units a second: part of what [powerNet] counts. */
    var industryDraw: Double = 0.0

    /** Its drills or converters have moved mass since its mass was last worked out. */
    var industryMoved: Boolean = false

    /**
     * Where its drill last bit, body-fixed, and how rich the ground there
     * was in ore and in water: worked out again once it has moved.
     */
    val drillSite = Vec3(Double.NaN, 0.0, 0.0)
    var drillOre: Double = 0.0
    var drillWater: Double = 0.0

    /** Seconds spent so far surveying [surveyBody] from a qualifying orbit. */
    var surveyProgress: Double = 0.0
    var surveyBody: String = ""

    /** How much of [type] more the parts sharing part [partIndex]'s plumbing have room for. */
    fun roomInGroupOf(partIndex: Int, type: ResourceType): Double {
        val group = fuelGroups[partIndex]
        var total = 0.0
        for (i in resources.indices) if (fuelGroups[i] == group) total += (capacityInPart(i, type) - resources[i][type.ordinal]).coerceAtLeast(0.0)
        return total
    }

    /** Puts up to [amount] of [type] into the parts sharing part [partIndex]'s plumbing; how much went in. */
    fun putIntoGroupOf(partIndex: Int, type: ResourceType, amount: Double): Double {
        val group = fuelGroups[partIndex]
        return putInto(resources.indices.filter { fuelGroups[it] == group }, type, amount)
    }

    /** How much of [type] parts [parts] hold between them. */
    fun amountIn(parts: Collection<Int>, type: ResourceType): Double = parts.sumOf { resources[it][type.ordinal] }

    /** How much more of [type] parts [parts] have room for. */
    fun roomIn(parts: Collection<Int>, type: ResourceType): Double =
        parts.sumOf { (capacityInPart(it, type) - resources[it][type.ordinal]).coerceAtLeast(0.0) }

    /** Takes up to [amount] of [type] out of [parts], in their order; how much came out. */
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

    /** Puts up to [amount] of [type] into [parts], as far as they hold; how much went in. */
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

    /** Takes [amount] of charge from wherever it is held; false, and nothing taken, if there is not that much. */
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

    /** Puts [amount] of charge into its batteries, as far as they hold; how much went in. */
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

    /** Total of [type] reachable from [partIndex] through crossfeed. */
    fun amountInGroupOf(partIndex: Int, type: ResourceType): Double {
        val group = fuelGroups[partIndex]
        var total = 0.0
        for (i in resources.indices) {
            if (fuelGroups[i] == group) total += resources[i][type.ordinal]
        }
        return total
    }

    /**
     * Draws up to [amount] units of [type] from the tanks [partIndex] can
     * reach, proportionally across them. Returns how much was available.
     *
     * Proportional draw within a group is still a simplification - a real craft
     * has a draw order - but it keeps the centre of mass sliding smoothly
     * rather than lurching as individual tanks empty one at a time.
     */
    fun drainFromGroupOf(partIndex: Int, type: ResourceType, amount: Double): Double {
        if (amount <= 0.0) return 0.0
        val available = amountInGroupOf(partIndex, type)
        if (available <= 0.0) return 0.0

        val taken = minOf(amount, available)
        val fraction = taken / available
        val slot = type.ordinal
        val group = fuelGroups[partIndex]
        for (i in resources.indices) {
            if (fuelGroups[i] == group) resources[i][slot] -= resources[i][slot] * fraction
        }
        return taken
    }

    /** Propellant reachable by any engine that is currently lit. */
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
     * Delegates to [FuelGroups] so the builder's delta-v analysis and the live
     * simulation apply literally the same rule - two copies of it would drift,
     * and the builder would start predicting flights the simulation cannot fly.
     *
     * Rare enough to be free: only a spawn or a separation changes structure,
     * so this never lands in the per-tick path.
     */
    private fun computeFuelGroups() {
        fuelGroups = FuelGroups.compute(design, defs)
    }

    /**
     * Current resource levels, part by part, for saving.
     *
     * Copied rather than exposed: the live arrays are written every tick by the
     * engine loop, and handing them out would let a save in progress observe a
     * half-drained state.
     */
    fun resourceSnapshot(): List<DoubleArray> = resources.map { it.copyOf() }

    /** Every part's levels in one flat list, part by part, for the wire. See [restoreFlatResources]. */
    fun flatResources(): List<Float> {
        val out = ArrayList<Float>(resources.size * RESOURCE_COUNT)
        for (row in resources) for (value in row) out.add(value.toFloat())
        return out
    }

    /** Restores levels from [flatResources]; false, and nothing changed, if they do not fit this craft. */
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
     * The same parts, re-described: for changes that move nothing and add or
     * take away nothing - a docking ring's hold on its partner let go, a
     * craft given back its own name.
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
        // Damage survives a reload. Without this a craft that limped down on
        // a collapsed leg stands back up repaired the next time the server
        // starts, which is the sort of thing a persistent world must not do.
        broken.fill(false)
        for (index in brokenParts) {
            if (index in broken.indices) broken[index] = true
        }
    }

    // --- mass ---------------------------------------------------------------

    /** Current mass of one part, including whatever it is carrying. */
    fun massOfPart(index: Int): Double {
        var mass = defs[index].dryMass + flooded.getOrElse(index) { 0.0 }
        // A fairing's shell, while it is still on.
        defs[index].module<com.rm.apogee.core.part.Fairing>()?.let { if (!activated[index]) mass += 2.0 * it.shellMass }
        val amounts = resources[index]
        for (type in ResourceType.entries) {
            mass += amounts[type.ordinal] * type.densityPerUnit
        }
        return mass
    }

    /**
     * Rebuilds mass, centre of mass and inertia from current resource levels.
     *
     * When the centre of mass moves in design space, [body]'s world position is
     * shifted to compensate, so the *parts* stay where they were rather than
     * the craft appearing to jump as its tanks drain.
     */
    fun recomputeMass(shiftBodyPosition: Boolean = true) {
        val masses = DoubleArray(partCount) { massOfPart(it) }
        val properties = MassProperties.compute(design, defs, masses)

        if (shiftBodyPosition) {
            scratch.setTo(properties.centerOfMass).subInPlace(centerOfMassLocal)
            if (scratch.lengthSq > 0.0) {
                body.orientation.rotate(scratch, scratchB)
                body.position.addInPlace(scratchB)
                // Asleep, or a founded base, it is posed from where its
                // centre of mass sleeps: that moves with it, or the next
                // tick puts the old centre back where it was and the whole
                // craft jumps by the shift.
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

    /** Part [index]'s mass as it stands, dry plus what is in it, kg. */
    fun partMass(index: Int): Double = massOfPart(index)

    /** Centre of mass in design space. */
    fun centerOfMass(out: Vec3 = Vec3()): Vec3 = out.setTo(centerOfMassLocal)

    // --- geometry -----------------------------------------------------------

    /**
     * Offset from the centre of mass, in world axes, of a point given in part
     * [index]'s own local space.
     */
    fun partPointOffsetWorld(index: Int, local: Vec3, out: Vec3 = Vec3()): Vec3 {
        val placed = design.parts[index]
        placed.rotation.rotate(local, out)
        out.addInPlace(placed.position).subInPlace(centerOfMassLocal)
        return body.orientation.rotate(out, out)
    }

    /** Offset of part [index] from the centre of mass, in world axes. */
    fun partOffsetWorld(index: Int, out: Vec3 = Vec3()): Vec3 {
        out.setTo(design.parts[index].position).subInPlace(centerOfMassLocal)
        return body.orientation.rotate(out, out)
    }

    /**
     * World position of one of part [index]'s hull contact points.
     *
     * @param pointIndex index into the part definition's `contactPoints`.
     */
    /**
     * Distance from the centre of mass to the furthest contact point, metres.
     *
     * Only used to bound how fast the craft's extremities sweep when it is
     * rotating, so the contact solver knows how finely to subdivide a tick.
     * Cached because it changes only when the structure or the centre of mass
     * does, and is wanted every tick.
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
     * Contact point [pointIndex] of part [index] in its own part space, as
     * posed now: a landing leg's feet move with its deploy, and the ground
     * meets them wherever they are - folded against the hull, swinging down,
     * or out on their springs.
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
        // Part-local -> design space (the part may be rotated on the craft).
        placed.rotation.rotate(local, out)
        out.addInPlace(placed.position).subInPlace(centerOfMassLocal)
        // Design space -> world.
        body.orientation.rotate(out, out)
        return out.addInPlace(body.position)
    }

    /** Offset of a contact point from the centre of mass, in world axes. */
    fun contactOffsetWorld(index: Int, pointIndex: Int, out: Vec3 = Vec3()): Vec3 {
        val local = posedContactPoint(index, pointIndex, posePoint)
        val placed = design.parts[index]
        placed.rotation.rotate(local, out)
        out.addInPlace(placed.position).subInPlace(centerOfMassLocal)
        return body.orientation.rotate(out, out)
    }

    /**
     * The inverse of [contactPointWorld]: a world point in part [index]'s own
     * local frame, written into [out].
     *
     * Here rather than in the caller because it needs the centre of mass,
     * which is this class's business - a collider asking for it directly would
     * be reaching through the vessel to reassemble a transform the vessel
     * already knows how to undo.
     */
    fun worldToPartLocal(index: Int, worldPoint: Vec3, out: Vec3 = Vec3()): Vec3 {
        val placed = design.parts[index]
        out.setTo(worldPoint).subInPlace(body.position)
        body.orientation.inverseRotate(out, out)
        out.addInPlace(centerOfMassLocal).subInPlace(placed.position)
        return placed.rotation.inverseRotate(out, out)
    }

    /**
     * A world point expressed in this craft's *design* space.
     *
     * Distinct from [worldToPartLocal], which goes one step further into a
     * single part's own frame. This is the frame `CraftDesign` positions are
     * written in, which is what merging two craft needs.
     */
    fun worldToDesign(worldPoint: Vec3, out: Vec3 = Vec3()): Vec3 {
        out.setTo(worldPoint).subInPlace(body.position)
        body.orientation.inverseRotate(out, out)
        return out.addInPlace(centerOfMassLocal)
    }

    /** World position of part [index], in the reference body's frame. */
    fun partPositionWorld(index: Int, out: Vec3 = Vec3()): Vec3 =
        partOffsetWorld(index, out).addInPlace(body.position)

    /** The craft's nose direction ( +Y in design space ) in world axes. */
    fun forward(out: Vec3 = Vec3()): Vec3 = body.orientation.rotate(Vec3.unitY(), out)

    // --- staging ------------------------------------------------------------

    fun isActivated(index: Int): Boolean = activated[index]

    /** Whether part [index] is working: staged, and not since failed. */
    fun isWorking(index: Int): Boolean = activated[index] && !broken[index]

    fun isBroken(index: Int): Boolean = broken[index]

    /** Records a part failure. Returns false if it had already failed. */
    fun breakPart(index: Int): Boolean {
        if (index !in broken.indices || broken[index]) return false
        broken[index] = true
        return true
    }

    /**
     * Fires the next stage, marking its parts active.
     *
     * Returns the indices activated, so the caller can act on the ones with
     * side effects beyond this vessel - a decoupler has to split the craft, and
     * only [com.rm.apogee.core.world.World] can create the second vessel.
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
            if (!activated[i]) continue
            if (defs[i].module<Engine>() != null) result.add(i)
        }
        return result
    }

    /**
     * Replaces this vessel's structure, keeping its motion.
     *
     * Used by decoupling: the vessel that remains keeps flying, with fewer
     * parts. Resource levels are carried over for the parts that survive.
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
        val newHealth = DoubleArray(newDesign.parts.size) { 1.0 }
        val newCrumple = FloatArray(newDesign.parts.size * 3)
        val newTemperature = DoubleArray(newDesign.parts.size) { AMBIENT_TEMPERATURE }
        val newFlooded = DoubleArray(keptIndices.size)
        keptIndices.forEachIndexed { newIndex, oldIndex ->
            newTemperature[newIndex] = temperature.getOrElse(oldIndex) { AMBIENT_TEMPERATURE }
            newFlooded[newIndex] = flooded.getOrElse(oldIndex) { 0.0 }
            resources[oldIndex].copyInto(newResources[newIndex])
            newActivated[newIndex] = activated[oldIndex]
            newBroken[newIndex] = broken[oldIndex]
            newDeploy[newIndex] = legDeploy.getOrElse(oldIndex) { 0.0 }
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
        resetStress(newDesign.parts.size)
        resetPose(newDeploy)
        name = newDesign.name
        computeFuelGroups()
        recomputeMass()
    }

    // --- dormancy -----------------------------------------------------------

    /**
     * Whether this craft has been put on rails against the ground.
     *
     * A world that people leave things in is mostly made of things nobody is
     * looking at, and a base resting on a pad costs exactly as much to
     * simulate as one being flown. Dormant craft are not stepped at all: no
     * forces, no integration, no terrain sampling.
     *
     * Dormant is never *absent*. The craft keeps its position, keeps taking
     * part in collision, and wakes the moment anything touches it - otherwise
     * a returning player would fly straight through their own base.
     */
    var dormant: Boolean = false
        private set

    /**
     * Where it sleeps, in the body's own rotating frame.
     *
     * Freezing the inertial state would be wrong: a craft at rest on the
     * ground is travelling at a hundred and seventy-five metres a second in
     * the inertial frame, and holding *that* still would leave the planet to
     * rotate out from under it. What is actually constant is its position on
     * the ground, so that is what is stored, and the inertial state is
     * rebuilt from the body's rotation each tick.
     */
    private val sleepPosition = Vec3()
    private val sleepOrientation = Quat.identity()

    /** Ticks spent within the stillness thresholds, for hysteresis. */
    private var settledTicks: Int = 0

    /** The pose this craft held last tick, in the body's rotating frame. */
    private val lastRestPosition = Vec3()
    private val lastRestOrientation = Quat.identity()
    private var hasRestPose = false
    private val restScratch = Vec3()

    /**
     * How far this craft has actually moved across the ground since the last
     * call, metres, counting rotation at the rim.
     *
     * Displacement rather than velocity, and that distinction is the whole
     * point. A craft in equilibrium on sprung legs finishes every tick holding
     * the impulse that cancelled that tick's gravity - 0.163 m/s - because
     * contacts resolve after gravity and before the next one. Ask it whether
     * it is *moving* and it says yes, for ever. Ask whether it has *moved* and
     * it says no, to five decimal places, which is the truth.
     *
     * Returns a large number the first time, so nothing anchors on its first
     * tick of contact.
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
     * Puts the craft to sleep at its current pose, expressed in the rotating
     * frame described by [bodyRotation].
     */
    fun sleep(bodyRotation: Quat) {
        if (dormant) return
        bodyRotation.inverseRotate(body.position, sleepPosition)
        sleepOrientation.setTo(bodyRotation.conjugate().times(body.orientation))
        dormant = true
        settledTicks = 0
    }

    /**
     * Pinned to the ground: a founded base. Asleep for good - it rides the
     * planet round as any sleeping craft does - but touching it never wakes
     * it, and its body is [com.rm.apogee.core.physics.RigidBody.fixed], so
     * nothing that strikes it moves it. Its parts still take the blow.
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
     * Its pose taken afresh where it now is, still anchored: after its
     * structure changed - a module joined, a part broken off - and its
     * centre of mass with it.
     */
    fun reanchor(bodyRotation: Quat) {
        if (!anchored) return
        anchored = false
        dormant = false
        body.fixed = false
        anchor(bodyRotation)
    }

    /** Lets go of the ground: an ordinary craft again, awake. */
    fun unanchor() {
        if (!anchored) return
        anchored = false
        body.fixed = false
        wake()
    }

    /** Returns true if this call is what woke it. Never wakes one [anchored]. */
    fun wake(): Boolean {
        if (anchored) return false
        if (!dormant) {
            settledTicks = 0
            return false
        }
        dormant = false
        afloat = false
        settledTicks = 0
        hasRestPose = false
        return true
    }

    /**
     * Rebuilds the inertial pose of a sleeping craft from the body's current
     * rotation. Four rotations, against a full force-and-contact pass.
     */
    fun followRotation(bodyRotation: Quat, surfaceVelocity: Vec3, spin: Vec3) {
        bodyRotation.rotate(sleepPosition, body.position)
        // setTo then mulInPlace, not `a * b`: the operator allocates, and this
        // runs for every sleeping craft every tick. A world full of parked
        // bases is exactly where an allocation per object per tick is least
        // affordable, which is the whole reason dormancy exists.
        body.orientation.setTo(bodyRotation).mulInPlace(sleepOrientation)
        body.linearVelocity.setTo(surfaceVelocity)
        body.angularVelocity.setTo(spin)
    }

    /**
     * Asleep afloat: riding the sea rather than pinned to the ground. The
     * water under a moored boat rises and falls with the tide and the waves,
     * and it goes with it - [draft] metres from the surface to its centre,
     * tilted as the water was ([sleepNormal], body-fixed) when it settled.
     */
    var afloat = false
    var draft = 0.0
    val sleepNormal = Vec3()

    /** Where it went to sleep, body-fixed, unit. */
    fun sleepDirection(out: Vec3): Vec3 = out.setTo(sleepPosition).normalizeInPlace()

    /**
     * The pose of a craft asleep afloat: straight out along where it went to
     * sleep, [radius] from the centre, tipped by [tilt] (body-fixed) from how
     * it lay - all turned by [bodyRotation].
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
     * Counts consecutive still ticks and reports when it has been still long
     * enough to sleep. Hysteresis, so a craft rocking gently on its gear does
     * not flicker in and out of dormancy.
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
     * The counterpart to [replaceStructure], which can only ever express a
     * *subset* of one craft: its index map says where each surviving part came
     * from, and there is nowhere in it to say "from the other vessel". Merging
     * needs both sources, so it gets its own path rather than a more clever
     * index map.
     *
     * [newDesign] must be this craft's parts in their existing order followed
     * by [other]'s in theirs, which is what [com.rm.apogee.core.world.World]'s
     * merge builds - the per-part state is carried across positionally.
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
        val newHealth = DoubleArray(newDesign.parts.size) { 1.0 }
        val newCrumple = FloatArray(newDesign.parts.size * 3)
        val newTemperature = DoubleArray(newDesign.parts.size)
        temperature.copyInto(newTemperature, 0, 0, own)
        other.temperature.copyInto(newTemperature, own)
        val newFlooded = DoubleArray(newDesign.parts.size)
        for (i in 0 until own) newFlooded[i] = flooded.getOrElse(i) { 0.0 }
        for (j in other.design.parts.indices) newFlooded[own + j] = other.flooded.getOrElse(j) { 0.0 }
        for (i in 0 until own) {
            resources[i].copyInto(newResources[i])
            newActivated[i] = activated[i]
            newBroken[i] = broken[i]
            newDeploy[i] = legDeploy.getOrElse(i) { 0.0 }
            newHealth[i] = health[i]
            crumple.copyInto(newCrumple, i * 3, i * 3, i * 3 + 3)
        }
        for (j in other.design.parts.indices) {
            other.resources[j].copyInto(newResources[own + j])
            newActivated[own + j] = other.activated[j]
            newBroken[own + j] = other.broken[j]
            newDeploy[own + j] = other.legDeploy.getOrElse(j) { 0.0 }
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
        resetStress(newDesign.parts.size)
        resetPose(newDeploy)
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
        private val RESOURCE_COUNT = ResourceType.entries.size

        /** Where every part's temperature starts, K. */
        const val AMBIENT_TEMPERATURE = 288.0
    }
}
