package com.rm.apogee.core.craft

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

    /** Stability assist: damp rotation toward zero. */
    var sasEnabled: Boolean = false

    fun reset() {
        throttle = 0.0; pitch = 0.0; yaw = 0.0; roll = 0.0
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

    /** Resolved definitions, parallel to `design.parts`. */
    var defs: List<PartDef> = defs
        private set

    val body = RigidBody()
    val control = ControlState()

    var name: String = design.name

    /**
     * Player this craft belongs to, or blank for debris and abandoned craft.
     *
     * A name rather than a session: sessions end every time someone closes the
     * app, and the whole point of a persistent world is that the craft is still
     * there when they come back.
     */
    var owner: String = ""

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

    override fun toString(): String = "Vessel($id '$name', ${partCount}p, ${body.mass.toInt()}kg)"

    companion object {
        private val RESOURCE_COUNT = ResourceType.entries.size
    }
}
