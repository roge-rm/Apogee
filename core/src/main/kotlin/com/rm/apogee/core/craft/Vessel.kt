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
    val commandRoll: Double get() = if (hasAttitudeInput) roll else assistRoll

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

    /** Whether anything of this craft touched the ground last tick. */
    var touchingGround: Boolean = false

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
                if (module is Tank) resources[i][module.resource.ordinal] = module.capacity
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
        for (i in defs.indices) {
            for (module in defs[i].modules) {
                if (module is Tank && module.resource == type) total += module.capacity
            }
        }
        return total
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

    /** Restores levels taken from [resourceSnapshot]. */
    fun restoreResources(saved: List<DoubleArray>) {
        for (i in resources.indices) {
            val row = saved.getOrNull(i) ?: continue
            row.copyInto(resources[i], endIndex = minOf(row.size, resources[i].size))
        }
        recomputeMass(shiftBodyPosition = false)
    }

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
        var mass = defs[index].dryMass
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
            }
        }

        centerOfMassLocal.setTo(properties.centerOfMass)
        recomputeContactRadius()
        body.mass = properties.mass
        body.setInertia(properties.inertia)
    }

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

    fun contactPointWorld(index: Int, pointIndex: Int, out: Vec3 = Vec3()): Vec3 {
        val local = defs[index].contactPoints[pointIndex]
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
        val local = defs[index].contactPoints[pointIndex]
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
        keptIndices.forEachIndexed { newIndex, oldIndex ->
            resources[oldIndex].copyInto(newResources[newIndex])
            newActivated[newIndex] = activated[oldIndex]
            newBroken[newIndex] = broken[oldIndex]
        }

        design = newDesign
        defs = newDefs
        resources = newResources
        activated = newActivated
        broken = newBroken
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

    /** Returns true if this call is what woke it. */
    fun wake(): Boolean {
        if (!dormant) {
            settledTicks = 0
            return false
        }
        dormant = false
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

        for (i in 0 until own) {
            resources[i].copyInto(newResources[i])
            newActivated[i] = activated[i]
            newBroken[i] = broken[i]
        }
        for (j in other.design.parts.indices) {
            other.resources[j].copyInto(newResources[own + j])
            newActivated[own + j] = other.activated[j]
            newBroken[own + j] = other.broken[j]
        }

        design = newDesign
        defs = newDefs
        resources = newResources
        activated = newActivated
        broken = newBroken
        computeFuelGroups()
        recomputeMass()
    }

    override fun toString(): String = "Vessel($id '$name', ${partCount}p, ${body.mass.toInt()}kg)"

    companion object {
        private val RESOURCE_COUNT = ResourceType.entries.size
    }
}
