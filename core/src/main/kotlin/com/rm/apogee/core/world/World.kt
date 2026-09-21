package com.rm.apogee.core.world

import com.rm.apogee.core.craft.CraftDesign
import com.rm.apogee.core.craft.PlacedPart
import com.rm.apogee.core.craft.Vessel
import com.rm.apogee.core.craft.VesselId
import com.rm.apogee.core.math.Quat
import com.rm.apogee.core.math.Vec3
import com.rm.apogee.core.math.quatFromTo
import com.rm.apogee.core.orbit.CelestialBody
import com.rm.apogee.core.orbit.Orbit
import com.rm.apogee.core.orbit.SolarSystem
import com.rm.apogee.core.part.Decoupler
import com.rm.apogee.core.part.PartCatalog
import com.rm.apogee.core.physics.GroundContact

/** Where a craft can be put on the ground. */
data class LaunchSite(
    val id: String,
    val displayName: String,
    val bodyId: String,
    /** Radians. */
    val latitude: Double,
    val longitude: Double,
)

/** Something worth telling the presentation layer about. */
sealed interface WorldEvent {
    data class VesselSpawned(val id: VesselId) : WorldEvent
    data class VesselStructureChanged(val id: VesselId) : WorldEvent
    data class VesselDestroyed(val id: VesselId, val reason: String) : WorldEvent
    data class Staged(val id: VesselId, val stage: Int) : WorldEvent
    data class Touchdown(val id: VesselId, val impactSpeed: Double) : WorldEvent
}

/**
 * The simulation. One authoritative instance lives on the server; each client
 * runs a second one to predict its own craft.
 *
 * Everything here is deterministic given the same starting state, the same
 * commands and the same tick sequence: iteration order is fixed, the timestep
 * is fixed, and nothing reads a wall clock. That is not because we intend
 * lockstep - devices disagree about floating point - but because a simulation
 * that is reproducible on one machine can be tested, replayed and reasoned
 * about, and one that is not cannot.
 */
class World(
    val system: SolarSystem,
    val catalog: PartCatalog,
) {
    /** Seconds since the universe began. */
    var time: Double = 0.0
        private set

    var tick: Long = 0
        private set

    private val vesselsById = LinkedHashMap<VesselId, Vessel>()
    private var nextVesselId = 1L

    private val forces = Forces()
    private val contacts = GroundContact()

    private val pendingEvents = ArrayList<WorldEvent>()
    private val scratch = Vec3()
    private val scratchUp = Vec3()

    val vessels: Collection<Vessel> get() = vesselsById.values

    fun vessel(id: VesselId): Vessel? = vesselsById[id]

    /** Drains and returns events raised since the last call. */
    fun drainEvents(): List<WorldEvent> {
        if (pendingEvents.isEmpty()) return emptyList()
        val copy = ArrayList<WorldEvent>(pendingEvents)
        pendingEvents.clear()
        return copy
    }

    // --- spawning -----------------------------------------------------------

    /**
     * Places a craft on the ground at [site], resting on its lowest part and
     * moving with the surface.
     *
     * Matching the surface velocity matters more than it looks: a craft spawned
     * at rest in the planet's *inertial* frame is actually moving at a couple
     * of hundred metres per second relative to the ground it is standing on,
     * and would be dragged off the pad the instant friction applied.
     */
    fun spawnOnSurface(design: CraftDesign, site: LaunchSite): Vessel {
        val problems = design.validate(catalog)
        require(problems.isEmpty()) {
            "Cannot spawn '${design.name}': ${problems.joinToString("; ")}"
        }

        val attractor = system.body(site.bodyId)
        val vessel = Vessel(
            id = VesselId(nextVesselId++),
            design = design,
            defs = design.parts.map { catalog.require(it.partId) },
            referenceBodyId = site.bodyId,
        )

        val up = surfaceNormalAt(site, scratchUp)
        // Nose (+Y in design space) points straight up.
        quatFromTo(Vec3.unitY(), up, vessel.body.orientation)

        // Lift the craft until its lowest part just touches the ground.
        val clearance = lowestExtentAlong(vessel, up)
        vessel.body.position
            .setTo(up)
            .mulInPlace(attractor.radius + clearance)
        // Rebuild now that orientation and position are set.
        vessel.recomputeMass(shiftBodyPosition = false)
        vessel.body.position.setTo(up).mulInPlace(attractor.radius + lowestExtentAlong(vessel, up))

        attractor.surfaceVelocityAt(vessel.body.position, vessel.body.linearVelocity)

        vesselsById[vessel.id] = vessel
        pendingEvents.add(WorldEvent.VesselSpawned(vessel.id))
        return vessel
    }

    /** Places a craft directly into orbit. Used by tests and by debug tooling. */
    fun spawnInOrbit(design: CraftDesign, bodyId: String, orbit: Orbit): Vessel {
        val problems = design.validate(catalog)
        require(problems.isEmpty()) {
            "Cannot spawn '${design.name}': ${problems.joinToString("; ")}"
        }
        val vessel = Vessel(
            id = VesselId(nextVesselId++),
            design = design,
            defs = design.parts.map { catalog.require(it.partId) },
            referenceBodyId = bodyId,
        )
        vessel.body.position.setTo(orbit.position)
        vessel.body.linearVelocity.setTo(orbit.velocity)
        quatFromTo(Vec3.unitY(), orbit.position.normalized(), vessel.body.orientation)
        vesselsById[vessel.id] = vessel
        pendingEvents.add(WorldEvent.VesselSpawned(vessel.id))
        return vessel
    }

    private fun surfaceNormalAt(site: LaunchSite, out: Vec3): Vec3 {
        val cosLat = kotlin.math.cos(site.latitude)
        return out.setTo(
            cosLat * kotlin.math.cos(site.longitude),
            kotlin.math.sin(site.latitude),
            cosLat * kotlin.math.sin(site.longitude),
        ).normalizeInPlace()
    }

    /**
     * How far the craft's centre of mass must sit above the surface.
     *
     * Uses exactly the same hull contact points the collision resolver does, so
     * a craft spawns already satisfying the contact constraint. Computing
     * clearance one way and collision another leaves every craft either
     * hovering or spawning inside the ground.
     */
    private fun lowestExtentAlong(vessel: Vessel, up: Vec3): Double {
        var deepest = 0.0
        val offset = Vec3()
        for (i in vessel.defs.indices) {
            for (p in vessel.defs[i].contactPoints.indices) {
                vessel.contactOffsetWorld(i, p, offset)
                val along = offset dot up
                if (along < deepest) deepest = along
            }
        }
        return -deepest
    }

    // --- commands -----------------------------------------------------------

    fun apply(command: Command) {
        when (command) {
            is Command.SetThrottle ->
                vesselsById[VesselId(command.vessel)]?.control?.throttle = command.throttle

            is Command.SetAttitude -> vesselsById[VesselId(command.vessel)]?.control?.let {
                it.pitch = command.pitch
                it.yaw = command.yaw
                it.roll = command.roll
            }

            is Command.SetSas ->
                vesselsById[VesselId(command.vessel)]?.control?.sasEnabled = command.enabled

            is Command.Stage -> vesselsById[VesselId(command.vessel)]?.let { stage(it) }

            is Command.SpawnCraft -> {
                val site = launchSites.firstOrNull { it.id == command.siteId }
                    ?: launchSites.first()
                spawnOnSurface(command.design, site)
            }

            is Command.Chat -> Unit // handled above the world
        }
    }

    /**
     * Fires the next stage, and splits the craft at any decoupler in it.
     *
     * The part tree below the decoupler becomes a new vessel that keeps the
     * motion it had, plus a separation impulse. Splitting a body is the whole
     * of staging here - there are no joints to release, because parts are
     * welded.
     */
    fun stage(vessel: Vessel) {
        val activated = vessel.activateNextStage()
        if (activated.isEmpty()) return
        pendingEvents.add(WorldEvent.Staged(vessel.id, vessel.currentStage))

        val decouplerIndex = activated.firstOrNull { index ->
            vessel.defs.getOrNull(index)?.module<Decoupler>() != null
        } ?: return

        splitAt(vessel, decouplerIndex)
    }

    /**
     * Separates the subtree below [decouplerIndex] into its own vessel.
     *
     * The decoupler itself stays with the discarded half, which matches the
     * genre's convention and means the surviving craft does not keep carrying
     * dead mass.
     */
    private fun splitAt(vessel: Vessel, decouplerIndex: Int) {
        val separating = vessel.design.subtreeOf(decouplerIndex).toSet()
        val remaining = vessel.design.parts.indices.filter { it !in separating }
        if (separating.isEmpty() || remaining.isEmpty()) return

        val ejection = vessel.defs[decouplerIndex].module<Decoupler>()?.ejectionImpulse ?: 0.0

        // Both halves need their world motion preserved, so capture it before
        // either design is rebuilt underneath them.
        val position = vessel.body.position.copy()
        val orientation = vessel.body.orientation.copy()
        val linearVelocity = vessel.body.linearVelocity.copy()
        val angularVelocity = vessel.body.angularVelocity.copy()

        val discarded = buildSubDesign(vessel.design, separating.sorted())
        val kept = buildSubDesign(vessel.design, remaining)

        // Resolve BOTH halves' definitions before either design is replaced.
        // replaceStructure swaps vessel.defs for the kept subset, so indexing
        // it afterwards with original indices reads the wrong parts - or walks
        // off the end, which is how this was found.
        val originalDefs = vessel.defs
        val keptDefs = kept.indices.map { originalDefs[it] }
        val discardedDefs = discarded.indices.map { originalDefs[it] }

        // The half that keeps flying.
        vessel.replaceStructure(kept.design, keptDefs, kept.indices)

        // The half that falls away, as a new vessel with the same motion.
        val debris = Vessel(
            id = VesselId(nextVesselId++),
            design = discarded.design,
            defs = discardedDefs,
            referenceBodyId = vessel.referenceBodyId,
        )
        debris.body.orientation.setTo(orientation)
        debris.body.linearVelocity.setTo(linearVelocity)
        debris.body.angularVelocity.setTo(angularVelocity)
        debris.body.position.setTo(position)
        debris.recomputeMass(shiftBodyPosition = false)

        // Push the halves apart along the craft's long axis.
        scratch.setTo(Vec3.unitY())
        orientation.rotate(scratch, scratch)
        val separation = scratch.copy()

        if (ejection > 0.0) {
            scratch.setTo(separation).mulInPlace(ejection)
            vessel.body.applyImpulse(scratch)
            scratch.setTo(separation).mulInPlace(-ejection)
            debris.body.applyImpulse(scratch)
        }
        // Nudge them apart geometrically too, so the contact-free frame after
        // separation does not start with the two halves interpenetrating.
        scratch.setTo(separation).mulInPlace(-SEPARATION_CLEARANCE)
        debris.body.position.addInPlace(scratch)

        vesselsById[debris.id] = debris
        pendingEvents.add(WorldEvent.VesselStructureChanged(vessel.id))
        pendingEvents.add(WorldEvent.VesselSpawned(debris.id))
    }

    private class SubDesign(val design: CraftDesign, val indices: List<Int>)

    /** Rebuilds a design from a subset of parts, remapping parent indices. */
    private fun buildSubDesign(source: CraftDesign, keep: List<Int>): SubDesign {
        val remap = HashMap<Int, Int>(keep.size)
        keep.forEachIndexed { newIndex, oldIndex -> remap[oldIndex] = newIndex }

        val parts = keep.map { oldIndex ->
            val part = source.parts[oldIndex]
            val newParent = remap[part.parentIndex] ?: -1
            PlacedPart(
                partId = part.partId,
                position = part.position.copy(),
                rotation = part.rotation.copy(),
                parentIndex = newParent,
                parentNodeId = part.parentNodeId,
                ownNodeId = part.ownNodeId,
                symmetryGroup = part.symmetryGroup,
            )
        }

        // Stages that referenced discarded parts are pruned, not renumbered
        // away - the surviving craft keeps its remaining staging sequence.
        val stages = source.stages.map { stage ->
            com.rm.apogee.core.craft.Stage(
                stage.activatedParts.mapNotNull { remap[it] }
            )
        }.filter { it.activatedParts.isNotEmpty() }

        return SubDesign(
            CraftDesign(source.name, parts, stages, source.catalogHash),
            keep,
        )
    }

    // --- the step -----------------------------------------------------------

    /**
     * Advances one fixed timestep.
     *
     * Vessels are iterated in insertion order, which is stable because ids are
     * assigned monotonically and the map preserves it. Iteration order affects
     * nothing today - vessels do not yet interact - but fixing it now means it
     * cannot silently start mattering later.
     */
    fun step(dt: Double) {
        for (vessel in vesselsById.values) {
            val attractor = attractorFor(vessel)
            val body = vessel.body

            body.clearAccumulators()

            forces.applyGravity(vessel, attractor)
            forces.applyThrust(vessel, attractor, dt)
            forces.applyDrag(vessel, attractor)
            forces.applyReactionWheels(vessel)

            body.integrate(dt)

            // Mass changes as propellant burns, and with it the centre of mass.
            if (vessel.control.throttle > 0.0) vessel.recomputeMass()

            val report = contacts.resolve(vessel, attractor, dt)
            if (report.hadContact && report.worstImpactSpeed > TOUCHDOWN_REPORT_SPEED) {
                pendingEvents.add(WorldEvent.Touchdown(vessel.id, report.worstImpactSpeed))
            }
        }

        tick++
        time += dt
    }

    fun attractorFor(vessel: Vessel): CelestialBody = system.body(vessel.referenceBodyId)

    /** The vessel's current two-body trajectory about its attractor. */
    fun orbitOf(vessel: Vessel): Orbit {
        val attractor = attractorFor(vessel)
        return Orbit(
            position = vessel.body.position.copy(),
            velocity = vessel.body.linearVelocity.copy(),
            mu = attractor.gravitationalParameter,
            epoch = time,
        )
    }

    fun snapshot(): Snapshot = Snapshot(
        tick = tick,
        time = time,
        vessels = vesselsById.values.map { vessel ->
            VesselKinematics(
                vessel = vessel.id.raw,
                referenceBodyId = vessel.referenceBodyId,
                position = vessel.body.position.copy(),
                rotation = vessel.body.orientation.copy(),
                velocity = vessel.body.linearVelocity.copy(),
                angularVelocity = vessel.body.angularVelocity.copy(),
                throttle = vessel.control.throttle,
            )
        },
    )

    fun structureUpdateFor(vessel: Vessel) = StructureUpdate(
        vessel = vessel.id.raw,
        design = vessel.design,
        name = vessel.name,
        currentStage = vessel.currentStage,
        activatedParts = vessel.activated.withIndex().filter { it.value }.map { it.index },
    )

    fun destroy(id: VesselId, reason: String) {
        if (vesselsById.remove(id) != null) {
            pendingEvents.add(WorldEvent.VesselDestroyed(id, reason))
        }
    }

    companion object {
        /** Metres the two halves of a separation are pushed apart immediately. */
        private const val SEPARATION_CLEARANCE = 0.5

        /** Impacts gentler than this are not worth an event. */
        private const val TOUCHDOWN_REPORT_SPEED = 0.5

        val launchSites = listOf(
            LaunchSite(
                id = "cape",
                displayName = "Cape Launch Complex",
                bodyId = SolarSystem.HOMEWORLD_ID,
                latitude = 0.0,
                longitude = 0.0,
            ),
        )

        fun default(catalog: PartCatalog) = World(SolarSystem.defaultSystem(), catalog)
    }
}
