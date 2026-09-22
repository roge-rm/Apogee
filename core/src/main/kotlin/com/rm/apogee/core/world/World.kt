package com.rm.apogee.core.world

import com.rm.apogee.core.craft.CraftDesign
import com.rm.apogee.core.craft.CraftOrientation
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
import com.rm.apogee.core.part.LandingLeg
import com.rm.apogee.core.part.PartCatalog
import com.rm.apogee.core.physics.ContactReport
import com.rm.apogee.core.physics.CraftContact
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

    /**
     * A part failed but the craft is still flying: a leg collapsed, a chute
     * tore away. Distinct from [VesselDestroyed], which ends the craft.
     */
    data class PartFailed(
        val id: VesselId,
        val partIndex: Int,
        val reason: String,
    ) : WorldEvent
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
    private val stabilityAssist = StabilityAssist()
    private val hydrostatics = Hydrostatics()
    private val contacts = GroundContact()

    private val pendingEvents = ArrayList<WorldEvent>()

    /**
     * Craft to remove once the step finishes.
     *
     * Destroying in place would mutate the map being iterated. Reused rather
     * than allocated per step, and empty on almost every one.
     */
    private val pendingDestruction = ArrayList<Pair<VesselId, String>>()

    private val craftContacts = CraftContact()

    /**
     * Vessels in step order, reused so the craft-vs-craft pass can index them
     * without allocating a list every tick.
     */
    private val stepOrder = ArrayList<Vessel>()

    private val scratchSurfaceVelocity = Vec3()
    private val scratchRelativeVelocity = Vec3()
    private val scratchSpin = Vec3()
    private val scratch = Vec3()
    private val scratchUp = Vec3()
    private val scratchBodyFixedUp = Vec3()
    private val scratchRotation = Quat.identity()

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
    fun spawnOnSurface(design: CraftDesign, site: LaunchSite, pad: Int = 0): Vessel {
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

        // The site is a point on a turning planet: its body-fixed normal is
        // fixed, its inertial direction is not.
        surfaceNormalAt(site, pad, scratchBodyFixedUp)
        attractor.rotationAt(time, scratchRotation)
        scratchRotation.rotate(scratchBodyFixedUp, scratchUp)
        val up = scratchUp
        standUpright(vessel, attractor, up)

        // On the ground, not at sea level: the pad may be most of a kilometre
        // above the datum, and spawning at the datum would drop the craft
        // inside a hill.
        val groundRadius = attractor.surfaceRadiusInBodyFrame(scratchBodyFixedUp)

        // Lift the craft until its lowest part just touches the ground.
        val clearance = lowestExtentAlong(vessel, up)
        vessel.body.position
            .setTo(up)
            .mulInPlace(groundRadius + clearance)
        // Rebuild now that orientation and position are set.
        vessel.recomputeMass(shiftBodyPosition = false)
        vessel.body.position.setTo(up).mulInPlace(groundRadius + lowestExtentAlong(vessel, up))

        attractor.surfaceVelocityAt(vessel.body.position, vessel.body.linearVelocity)

        vesselsById[vessel.id] = vessel
        pendingEvents.add(WorldEvent.VesselSpawned(vessel.id))
        return vessel
    }

    /**
     * Places a craft at an explicit state.
     *
     * Used by client-side prediction, which needs to seed a local replica from
     * an authoritative snapshot rather than from a launch site or an orbit.
     */
    fun spawnAt(
        design: CraftDesign,
        bodyId: String,
        position: Vec3,
        velocity: Vec3,
        rotation: Quat,
        angularVelocity: Vec3 = Vec3.zero(),
    ): Vessel {
        val vessel = Vessel(
            id = VesselId(nextVesselId++),
            design = design,
            defs = design.parts.map { catalog.require(it.partId) },
            referenceBodyId = bodyId,
        )
        vessel.body.position.setTo(position)
        vessel.body.linearVelocity.setTo(velocity)
        vessel.body.orientation.setTo(rotation)
        vessel.body.angularVelocity.setTo(angularVelocity)
        vessel.recomputeMass(shiftBodyPosition = false)
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

    /**
     * Turns a craft so the design's [CraftOrientation.up] points at the sky.
     *
     * A vertical craft keeps exactly the attitude it always had - nose up,
     * roll wherever the shortest turn leaves it - so nothing that was already
     * flying changes under it. A horizontal one is also given a heading: nose
     * east, along the way the ground is already carrying it, which is the
     * cheap direction to take off in for the same reason it is the cheap
     * direction to launch in.
     */
    private fun standUpright(vessel: Vessel, attractor: CelestialBody, up: Vec3) {
        val orientation = vessel.design.orientation
        val rotation = vessel.body.orientation
        quatFromTo(orientation.up, up, rotation)
        if (orientation == CraftOrientation.VERTICAL) return

        // East is the way the surface moves. At a pole it does not move, and
        // any heading is as good as another.
        val east = attractor.surfaceVelocityAt(up, Vec3())
        east.addScaledInPlace(up, -(east dot up))
        if (east.lengthSq < 1e-12) return
        east.normalizeInPlace()

        // The first turn left the nose somewhere level; swing it round the
        // vertical until it faces east.
        val nose = rotation.rotate(orientation.forward, Vec3())
        val heading = if ((nose dot east) < -0.999999) {
            Quat.fromAxisAngle(up, Math.PI)
        } else {
            quatFromTo(nose, east)
        }
        rotation.setTo(heading * rotation)
    }

    /**
     * Surface normal at a launch site's [pad].
     *
     * Pads are spread along the local east-west line. Without this every player
     * who joins spawns at exactly the same point, inside everyone already
     * there, and the contact solver throws them apart at violent speed - which
     * is a spectacular but unhelpful way to start a game.
     */
    private fun surfaceNormalAt(site: LaunchSite, pad: Int, out: Vec3): Vec3 {
        val attractor = system.body(site.bodyId)
        // Alternate either side of the site so the first few pads stay close to
        // the middle rather than marching off in one direction.
        val slot = if (pad % 2 == 0) pad / 2 else -(pad + 1) / 2
        val longitude = site.longitude + slot * PAD_SPACING_METRES / attractor.radius

        val cosLat = kotlin.math.cos(site.latitude)
        return out.setTo(
            cosLat * kotlin.math.cos(longitude),
            kotlin.math.sin(site.latitude),
            cosLat * kotlin.math.sin(longitude),
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
            // Each of these wakes its target first: a command is somebody
            // paying attention to that craft, which is exactly the signal
            // dormancy is waiting for.
            is Command.SetThrottle ->
                waken(command.vessel)?.control?.throttle = command.throttle

            is Command.SetAttitude -> waken(command.vessel)?.control?.let {
                it.pitch = command.pitch
                it.yaw = command.yaw
                it.roll = command.roll
            }

            is Command.SetSas ->
                waken(command.vessel)?.control?.sasEnabled = command.enabled

            is Command.SetTranslation -> waken(command.vessel)?.control?.let {
                it.translateX = command.x
                it.translateY = command.y
                it.translateZ = command.z
            }

            is Command.SetRcs ->
                waken(command.vessel)?.control?.rcsEnabled = command.enabled

            is Command.Stage -> waken(command.vessel)?.let { stage(it) }

            // Handled by the server, which has to decide who owns and flies
            // the result. Reaching it here means nobody claimed it.
            is Command.SpawnCraft -> spawnFor(command, owner = "")

            is Command.Join -> waken(command.vessel)?.let { joinToNeighbour(it) }

            // Handled by the server, which owns the notion of who is flying
            // what. Reaching the world means nobody was listening.
            is Command.SwitchVessel -> waken(command.vessel)

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
    /**
     * Welds [vessel] to the nearest craft it is touching.
     *
     * This is the inverse of [splitAt], and is how a base gets built: modules
     * are landed, pushed into place, and tied together into one structure.
     * Doing it as a merge of part trees rather than as a new kind of link
     * means everything downstream - mass, inertia, fuel crossfeed, collision,
     * saving - keeps working without knowing that bases exist.
     *
     * Welded where they stand rather than snapped onto attach nodes. A base is
     * assembled by manoeuvring things into position; snapping would teleport a
     * module the player has just spent a minute placing.
     *
     * @return the merged vessel, or null if there was nothing to join to.
     */
    fun joinToNeighbour(vessel: Vessel): Vessel? {
        val partner = nearestJoinable(vessel) ?: return null
        return join(vessel, partner)
    }

    /** The closest craft in contact with [vessel] and near enough to rest. */
    private fun nearestJoinable(vessel: Vessel): Vessel? {
        var best: Vessel? = null
        var bestDistance = Double.MAX_VALUE
        for (other in vesselsById.values) {
            if (other.id == vessel.id) continue
            if (other.referenceBodyId != vessel.referenceBodyId) continue

            scratch.setTo(vessel.body.position).subInPlace(other.body.position)
            val distance = scratch.length
            if (distance > vessel.contactRadius + other.contactRadius) continue

            // Only things it is resting against. Welding to something you are
            // flying past is how a docking mechanic becomes a grappling hook.
            scratch.setTo(vessel.body.linearVelocity).subInPlace(other.body.linearVelocity)
            if (scratch.length > JOIN_MAX_CLOSING_SPEED) continue

            if (distance < bestDistance) {
                bestDistance = distance
                best = other
            }
        }
        return best
    }

    /**
     * Merges [absorbed] into [keeper], preserving momentum, and removes it.
     *
     * The transform is the only fiddly part: [absorbed]'s parts are expressed
     * in its own design space, and have to be re-expressed in [keeper]'s so
     * that every part ends up exactly where it already is in the world.
     */
    fun join(keeper: Vessel, absorbed: Vessel): Vessel? {
        if (keeper.id == absorbed.id) return null
        if (keeper.referenceBodyId != absorbed.referenceBodyId) return null

        val massKeeper = keeper.body.mass
        val massAbsorbed = absorbed.body.mass
        val total = massKeeper + massAbsorbed
        if (total <= 0.0) return null

        // Momentum, captured before either structure is touched.
        val centreKeeper = keeper.body.position.copy()
        val centreAbsorbed = absorbed.body.position.copy()
        val combinedCentre = Vec3(
            (centreKeeper.x * massKeeper + centreAbsorbed.x * massAbsorbed) / total,
            (centreKeeper.y * massKeeper + centreAbsorbed.y * massAbsorbed) / total,
            (centreKeeper.z * massKeeper + centreAbsorbed.z * massAbsorbed) / total,
        )
        val velocity = Vec3(
            (keeper.body.linearVelocity.x * massKeeper +
                absorbed.body.linearVelocity.x * massAbsorbed) / total,
            (keeper.body.linearVelocity.y * massKeeper +
                absorbed.body.linearVelocity.y * massAbsorbed) / total,
            (keeper.body.linearVelocity.z * massKeeper +
                absorbed.body.linearVelocity.z * massAbsorbed) / total,
        )
        val angularMomentum = angularMomentumAbout(keeper, combinedCentre, velocity)
            .addInPlace(angularMomentumAbout(absorbed, combinedCentre, velocity))

        val merged = mergeDesigns(keeper, absorbed) ?: return null
        val mergedDefs = merged.parts.map { catalog.require(it.partId) }
        keeper.absorb(merged, mergedDefs, absorbed)

        keeper.body.position.setTo(combinedCentre)
        keeper.body.linearVelocity.setTo(velocity)
        val inverseInertiaWorld = com.rm.apogee.core.math.Mat3()
            .setRotated(keeper.body.inverseInertiaLocal, keeper.body.orientation)
        inverseInertiaWorld.transform(angularMomentum, keeper.body.angularVelocity)

        vesselsById.remove(absorbed.id)
        pendingEvents.add(WorldEvent.VesselDestroyed(absorbed.id, "joined to ${keeper.name}"))
        pendingEvents.add(WorldEvent.VesselStructureChanged(keeper.id))
        return keeper
    }

    /** Angular momentum of [vessel] about [centre], for a body moving at [velocity]. */
    private fun angularMomentumAbout(vessel: Vessel, centre: Vec3, velocity: Vec3): Vec3 {
        val inertiaWorld = com.rm.apogee.core.math.Mat3()
            .setRotated(vessel.body.inertiaLocal, vessel.body.orientation)
        val spin = inertiaWorld.transform(vessel.body.angularVelocity, Vec3())
        // Plus the orbital term: the craft's own centre swinging about the
        // combined one. Dropping this quietly loses the rotation you get from
        // welding two things that were drifting past each other.
        val lever = Vec3().setTo(vessel.body.position).subInPlace(centre)
        val relative = Vec3().setTo(vessel.body.linearVelocity).subInPlace(velocity)
        return spin.addInPlace(lever.crossInPlace(relative).mulInPlace(vessel.body.mass))
    }

    /**
     * [absorbed]'s parts, re-expressed in [keeper]'s design space so that each
     * lands exactly where it already is in the world.
     */
    private fun mergeDesigns(keeper: Vessel, absorbed: Vessel): CraftDesign? {
        val offset = keeper.design.parts.size
        val parts = ArrayList<PlacedPart>(offset + absorbed.design.parts.size)
        parts.addAll(keeper.design.parts)

        // The part of the keeper nearest the absorbed craft becomes the parent
        // of its root, so the tree stays connected and staging still has
        // something to walk.
        val anchor = nearestPartTo(keeper, absorbed.body.position)

        val worldPoint = Vec3()
        for ((index, placed) in absorbed.design.parts.withIndex()) {
            absorbed.partPositionWorld(index, worldPoint)
            val local = keeper.worldToDesign(worldPoint, Vec3())
            val rotation = keeper.body.orientation.conjugate()
                .times(absorbed.body.orientation)
                .times(placed.rotation)
            parts.add(
                placed.copy(
                    position = local,
                    rotation = rotation,
                    parentIndex = if (placed.parentIndex < 0) anchor
                    else placed.parentIndex + offset,
                    // A weld, not a node attachment; the transform is what is
                    // authoritative and there is no node pair to name.
                    parentNodeId = null,
                    ownNodeId = null,
                    symmetryGroup = -1,
                )
            )
        }

        val stages = keeper.design.stages +
            absorbed.design.stages.map { stage ->
                com.rm.apogee.core.craft.Stage(stage.activatedParts.map { it + offset })
            }

        return CraftDesign(
            name = keeper.design.name,
            parts = parts,
            stages = stages,
            catalogHash = keeper.design.catalogHash,
        )
    }

    private fun nearestPartTo(vessel: Vessel, worldPoint: Vec3): Int {
        var best = 0
        var bestDistance = Double.MAX_VALUE
        val position = Vec3()
        for (index in vessel.design.parts.indices) {
            vessel.partPositionWorld(index, position)
            val distance = position.subInPlace(worldPoint).lengthSq
            if (distance < bestDistance) {
                bestDistance = distance
                best = index
            }
        }
        return best
    }

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

            // Dormant craft ride the planet's rotation and are not simulated.
            //
            // This is the same idea as putting an orbit on rails, for the
            // other place a craft spends most of its life: parked. A world
            // people leave bases in is mostly made of things nobody is
            // looking at, and a base on a pad otherwise costs exactly what
            // one being flown does.
            if (vessel.dormant) {
                attractor.rotationAt(time, scratchRotation)
                attractor.surfaceVelocityAt(body.position, scratchSurfaceVelocity)
                attractor.angularVelocity(scratchSpin)
                vessel.followRotation(scratchRotation, scratchSurfaceVelocity, scratchSpin)
                continue
            }

            body.clearAccumulators()

            // Before any force, because the elevons deflect inside the drag
            // pass and the gimbal inside thrust: all of them act on what
            // stability assist asks for this tick.
            stabilityAssist.update(vessel, dt)

            forces.applyGravity(vessel, attractor)
            forces.applyThrust(vessel, attractor, dt)
            forces.applyDrag(vessel, attractor)
            for (i in 0 until forces.tornCount) {
                val index = forces.tornParachutes[i]
                pendingEvents.add(
                    WorldEvent.PartFailed(
                        vessel.id, index, "${vessel.defs[index].title} tore away",
                    )
                )
            }
            hydrostatics.apply(vessel, attractor, time, dt)
            forces.applyReactionWheels(vessel)
            forces.applyRcs(vessel, dt)

            // Integration and contact are subdivided together when the craft
            // is moving fast near the ground. Forces are not recomputed per
            // substep - they change far more slowly than the geometry does,
            // and recomputing thrust and drag eight times a tick would cost
            // more than the problem is worth.
            val substeps = contactSubsteps(vessel, attractor, dt)
            val h = dt / substeps
            for (substep in 0 until substeps) {
                body.integrate(h)
                contacts.resolve(vessel, attractor, h, time + substep * h, substep > 0)
            }
            val report = contacts.report
            vessel.touchingGround = report.hadContact

            // Mass changes as propellant burns, and with it the centre of mass.
            if (vessel.control.throttle > 0.0) vessel.recomputeMass()
            if (report.hadContact && report.worstImpactSpeed > TOUCHDOWN_REPORT_SPEED) {
                pendingEvents.add(WorldEvent.Touchdown(vessel.id, report.worstImpactSpeed))
            }
            if (report.failureCount > 0) applyImpactDamage(vessel, report)

            considerSleeping(vessel, report)
        }

        // Craft against craft, once everything has moved.
        //
        // After the per-vessel pass rather than inside it, because a pair
        // needs both halves in their new positions before it means anything.
        // The cost is that a craft-craft contact is resolved against terrain
        // contacts from the same tick rather than interleaved with them, which
        // at a sixtieth of a second is not something anyone can see.
        stepOrder.clear()
        stepOrder.addAll(vesselsById.values)
        val impacts = craftContacts.resolve(stepOrder, dt)
        // Anything that was touched is awake again, whether or not it was
        // hurt. A sleeping base that stayed asleep while something landed on
        // it would be a wall, not an object.
        for (i in 0 until craftContacts.touchedCount) {
            vesselsById[VesselId(craftContacts.touched[i])]?.wake()
        }
        for (i in 0 until impacts.count) {
            applyCollisionDamage(VesselId(impacts.vessels[i]), impacts.parts[i], impacts.speeds[i])
        }

        if (pendingDestruction.isNotEmpty()) {
            for ((id, reason) in pendingDestruction) destroy(id, reason)
            pendingDestruction.clear()
        }

        tick++
        time += dt
    }

    /**
     * Puts a craft to sleep once friction has been holding it still a while.
     *
     * The condition is [ContactReport.anchored], not a velocity threshold.
     * The contact resolver already answers the hard question - is friction
     * winning? - and a craft it has anchored is exactly, not approximately,
     * stationary on the ground. Comparing velocities here instead meant
     * picking a number above a resting craft's jitter and below a real slide,
     * and the first attempt at that number was below the jitter's ninetieth
     * percentile, so nothing ever slept.
     *
     * The delay is hysteresis: a lander rocking onto its gear can be anchored
     * for a tick or two on the way to settling.
     */
    private fun considerSleeping(vessel: Vessel, report: ContactReport) {
        if (vessel.noteStillness(report.anchored, SLEEP_SETTLE_TICKS)) {
            attractorFor(vessel).rotationAt(time, scratchRotation)
            vessel.sleep(scratchRotation)
        }
    }

    /** Wakes [id] if it is asleep, so a command always reaches a live craft. */
    private fun waken(id: Long): Vessel? = vesselsById[VesselId(id)]?.also { it.wake() }

    /**
     * A part of one craft struck another hard enough to fail.
     *
     * Same rule as hitting the ground: gear gives way and the craft lives,
     * anything else and the craft does not. Collisions between craft are how
     * a base gets damaged by something landing badly on it, so the two paths
     * deliberately agree - it would be strange for a tank to survive a
     * thirty-metre-a-second arrival onto a station and not onto a hillside.
     */
    private fun applyCollisionDamage(id: VesselId, partIndex: Int, speed: Double) {
        val vessel = vesselsById[id] ?: return
        if (partIndex !in vessel.defs.indices) return
        val def = vessel.defs[partIndex]
        if (def.module<LandingLeg>() != null) {
            if (vessel.breakPart(partIndex)) {
                pendingEvents.add(
                    WorldEvent.PartFailed(id, partIndex, "${def.title} collapsed")
                )
            }
            return
        }
        if (pendingDestruction.none { it.first == id }) {
            pendingDestruction.add(
                id to "${def.title} was struck at ${speed.toInt()} m/s"
            )
        }
    }

    /**
     * How finely to subdivide this tick's integration and contact test.
     *
     * A tick is a sixtieth of a second, and a craft descending at thirty-five
     * metres a second covers well over half a metre in one. Anything smaller
     * than that - a landing leg protruding below an engine bell, a wheel, a
     * ridge in the terrain - can be stepped straight over, so the first thing
     * the solver ever sees is several parts already buried. That is how a
     * lander's legs came to be skipped while the engine above them was
     * recorded as the part that hit.
     *
     * Only paid for near the ground: above the highest ground the body can
     * produce there is nothing to hit, and orbital speeds would otherwise
     * demand the maximum subdivision on every tick of every flight.
     */
    private fun contactSubsteps(vessel: Vessel, attractor: CelestialBody, dt: Double): Int {
        val body = vessel.body
        val ceiling = (attractor.terrain?.maxElevation ?: 0.0) + SUBSTEP_CEILING_METRES
        if (attractor.altitudeOf(body.position) > ceiling) return 1

        // And then against the ground actually underneath, for the same
        // reason GroundContact does: the ceiling only rules out craft above
        // the tallest mountain on the body, which is no help to a rocket
        // climbing through clear air five kilometres up.
        attractor.rotationAt(time, scratchRotation)
        attractor.toBodyFixed(body.position, scratchRotation, scratchBodyFixedUp)
        val groundBelow = attractor.solidRadiusInBodyFrame(scratchBodyFixedUp)
        if (body.position.length - vessel.contactRadius >
            groundBelow + vessel.contactRadius + SUBSTEP_PROXIMITY_MARGIN
        ) {
            return 1
        }

        attractor.surfaceVelocityAt(body.position, scratchSurfaceVelocity)
        scratchRelativeVelocity.setTo(body.linearVelocity).subInPlace(scratchSurfaceVelocity)
        // The extremities of a rotating craft sweep faster than its centre.
        val sweep = scratchRelativeVelocity.length +
            body.angularVelocity.length * vessel.contactRadius
        val distance = sweep * dt
        if (distance <= MAX_SUBSTEP_DISTANCE) return 1
        return kotlin.math.ceil(distance / MAX_SUBSTEP_DISTANCE).toInt()
            .coerceAtMost(MAX_CONTACT_SUBSTEPS)
    }

    /**
     * Turns "this part hit harder than it can take" into a consequence.
     *
     * A leg collapses and the craft keeps existing, now resting on whatever is
     * underneath it - which is usually the next thing to fail, and is the
     * right outcome: gear absorbs one bad landing, not every landing. Anything
     * else failing is the end of the craft, because a tank or an engine
     * meeting the ground above its tolerance is not a survivable event.
     */
    private fun applyImpactDamage(vessel: Vessel, report: ContactReport) {
        var fatalPart = -1
        for (i in 0 until report.failureCount) {
            val index = report.failedParts[i]
            val def = vessel.defs[index]
            if (def.module<LandingLeg>() != null) {
                if (vessel.breakPart(index)) {
                    pendingEvents.add(
                        WorldEvent.PartFailed(vessel.id, index, "${def.title} collapsed")
                    )
                }
            } else if (fatalPart < 0) {
                fatalPart = index
            }
        }
        if (fatalPart >= 0) {
            val speed = report.worstImpactSpeed
            pendingDestruction.add(
                vessel.id to
                    "${vessel.defs[fatalPart].title} hit the surface at ${speed.toInt()} m/s"
            )
        }
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
        owner = vessel.owner,
        ownerName = vessel.ownerName,
        brokenParts = vessel.broken.withIndex().filter { it.value }.map { it.index },
    )

    // --- persistence ---------------------------------------------------------

    /**
     * Captures the whole world for saving.
     *
     * Takes a copy of everything it touches: an autosave runs on the same
     * thread as the tick in this design, but the moment it does not, handing
     * out live vectors would let a save observe a craft halfway through a step.
     */
    fun save(): WorldSave = WorldSave(
        catalogHash = catalog.contentHash,
        universeTime = time,
        nextVesselId = nextVesselId,
        vessels = vesselsById.values.map { vessel ->
            VesselSave(
                id = vessel.id.raw,
                name = vessel.name,
                owner = vessel.owner,
                ownerName = vessel.ownerName,
                design = vessel.design,
                referenceBodyId = vessel.referenceBodyId,
                position = vessel.body.position.copy(),
                rotation = vessel.body.orientation.copy(),
                velocity = vessel.body.linearVelocity.copy(),
                angularVelocity = vessel.body.angularVelocity.copy(),
                currentStage = vessel.currentStage,
                activatedParts = vessel.activated
                    .withIndex().filter { it.value }.map { it.index },
                brokenParts = vessel.broken
                    .withIndex().filter { it.value }.map { it.index },
                throttle = vessel.control.throttle,
                sasEnabled = vessel.control.sasEnabled,
                resources = vessel.resourceSnapshot().map { it.toList() },
            )
        },
    )

    /**
     * Replaces this world's contents with a saved one.
     *
     * @return the problems found. A craft referring to parts this build no
     *   longer has is skipped and reported rather than dropped silently - an
     *   operator who changed the catalogue needs to know which craft they lost.
     */
    fun restore(save: WorldSave): List<String> {
        val problems = ArrayList<String>()

        if (!SaveMigration.canRead(save.formatVersion)) {
            return listOf(
                "Save is format ${save.formatVersion}, this build reads " +
                    "${WorldSave.FORMAT_VERSION}"
            )
        }
        // A differing catalogue is expected rather than alarming - parts get
        // tuned between every build. What matters is whether each craft can
        // still be assembled, which is settled per craft below.
        val catalogueChanged = save.catalogHash != catalog.contentHash

        vesselsById.clear()
        pendingEvents.clear()
        time = save.universeTime
        nextVesselId = save.nextVesselId

        for (saved in save.vessels) {
            val migration = SaveMigration.migrate(saved.design, catalog)
            val design = migration.design
            if (design == null) {
                problems.add(
                    "Lost '${saved.name}' (#${saved.id}): " +
                        migration.notes.joinToString("; ")
                )
                continue
            }
            if (migration.notes.isNotEmpty()) {
                problems.add(
                    "Carried '${saved.name}' (#${saved.id}) forward: " +
                        migration.notes.joinToString("; ")
                )
            }

            val invalid = design.validate(catalog)
            if (invalid.isNotEmpty()) {
                problems.add("Skipped '${saved.name}' (#${saved.id}): ${invalid.first()}")
                continue
            }

            val vessel = Vessel(
                id = VesselId(saved.id),
                design = design,
                defs = design.parts.map { catalog.require(it.partId) },
                referenceBodyId = saved.referenceBodyId,
            )
            vessel.name = saved.name
            if (save.formatVersion >= 2) {
                vessel.owner = saved.owner
                vessel.ownerName = saved.ownerName
            } else {
                // Format 1 stored a display name where the id now goes. There
                // is no way to work out which install that was, and guessing
                // would hand someone else's base to whoever types the same
                // name. The craft keeps its label and becomes unowned, which
                // is the honest outcome: it is still there, still yours to
                // fly, and claimable rather than locked to a name.
                vessel.owner = ""
                vessel.ownerName = saved.owner
                if (saved.owner.isNotBlank()) {
                    problems.add(
                        "'${saved.name}' (#${saved.id}) was owned by name " +
                            "'${saved.owner}'; it is now unclaimed"
                    )
                }
            }
            vessel.body.position.setTo(saved.position)
            vessel.body.orientation.setTo(saved.rotation)
            vessel.body.linearVelocity.setTo(saved.velocity)
            vessel.body.angularVelocity.setTo(saved.angularVelocity)
            vessel.restoreStaging(saved.currentStage, saved.activatedParts, saved.brokenParts)
            vessel.control.throttle = saved.throttle
            vessel.control.sasEnabled = saved.sasEnabled
            if (saved.resources.isNotEmpty()) {
                vessel.restoreResources(saved.resources.map { it.toDoubleArray() })
            }
            vessel.recomputeMass(shiftBodyPosition = false)

            vesselsById[vessel.id] = vessel
            // Everyone connected needs to be told these exist.
            pendingEvents.add(WorldEvent.VesselSpawned(vessel.id))

            // A save written by an older build could contain an id at or past
            // the counter; handing it out again would collide.
            if (saved.id >= nextVesselId) nextVesselId = saved.id + 1
        }

        // Only worth saying once, and only when something actually suffered.
        if (catalogueChanged && problems.isNotEmpty()) {
            problems.add(
                0,
                "This world was written with a different part catalogue " +
                    "(${save.catalogHash} vs ${catalog.contentHash})",
            )
        }
        return problems
    }

    /**
     * Spawns the craft a [Command.SpawnCraft] asks for, on a free pad.
     *
     * Returns the vessel, because whoever asked for it almost certainly wants
     * to fly it - which is the difference between launching a module and
     * merely adding one to the scenery.
     */
    fun spawnFor(command: Command.SpawnCraft, owner: String): Vessel {
        val site = launchSites.firstOrNull { it.id == command.siteId } ?: launchSites.first()
        val vessel = spawnOnSurface(command.design, site, pad = nextFreePad(site))
        vessel.owner = owner
        return vessel
    }

    /**
     * The first pad at [site] with nothing standing on it.
     *
     * A persistent world accumulates craft at the launch complex, and dropping
     * a new one into a pad that is already occupied would spawn it inside
     * somebody's base - which, now that craft are solid, is an explosion
     * rather than a curiosity.
     */
    private fun nextFreePad(site: LaunchSite): Int {
        val occupied = Vec3()
        for (pad in 0 until MAX_PADS) {
            surfaceNormalAt(site, pad, scratchBodyFixedUp)
            attractorFor(site).rotationAt(time, scratchRotation)
            scratchRotation.rotate(scratchBodyFixedUp, occupied)
            val radius = attractorFor(site).surfaceRadiusInBodyFrame(scratchBodyFixedUp)
            occupied.mulInPlace(radius)

            val clear = vesselsById.values.none { other ->
                scratch.setTo(other.body.position).subInPlace(occupied)
                scratch.length < PAD_SPACING_METRES * 0.5
            }
            if (clear) return pad
        }
        return 0
    }

    private fun attractorFor(site: LaunchSite): CelestialBody = system.body(site.bodyId)

    /** Finds a craft belonging to [owner], so a returning player gets it back. */
    fun vesselOwnedBy(owner: String): Vessel? =
        if (owner.isBlank()) null
        // Exact, not case-insensitive: this is an opaque id now, not a name
        // someone typed, so folding case can only ever create a false match.
        else vesselsById.values.firstOrNull { it.owner == owner }

    fun destroy(id: VesselId, reason: String) {
        if (vesselsById.remove(id) != null) {
            pendingEvents.add(WorldEvent.VesselDestroyed(id, reason))
        }
    }

    companion object {
        /** Metres between adjacent launch pads at a site. */
        private const val PAD_SPACING_METRES = 40.0

        /** How many pads to look through before giving up and reusing one. */
        private const val MAX_PADS = 64

        /**
         * Above this closing speed a weld is a collision, not an assembly.
         *
         * Public because the client uses it to decide whether to offer the
         * action at all; a button that appears when the server would refuse is
         * worse than no button.
         */
        const val JOIN_MAX_CLOSING_SPEED = 2.0

        /** Metres the two halves of a separation are pushed apart immediately. */
        private const val SEPARATION_CLEARANCE = 0.5

        /** Impacts gentler than this are not worth an event. */
        private const val TOUCHDOWN_REPORT_SPEED = 0.5

        /**
         * Furthest a contact point may sweep in one substep, metres.
         *
         * Smaller than the smallest thing that has to be noticed - a landing
         * leg's protrusion below the engine it protects.
         */
        private const val MAX_SUBSTEP_DISTANCE = 0.15

        /** Above eight, a tick costs more than the accuracy is worth. */
        private const val MAX_CONTACT_SUBSTEPS = 8

        /** Metres above the highest possible ground to stop subdividing. */
        private const val SUBSTEP_CEILING_METRES = 200.0

        /**
         * Metres above the ground *directly beneath* to stop subdividing.
         *
         * Wider than the contact resolver's own margin, because a craft this
         * close is about to need the fine steps and the test runs a tick
         * before they matter.
         */
        private const val SUBSTEP_PROXIMITY_MARGIN = 120.0

        /**
         * How long it must stay that still first.
         *
         * Two seconds. Hysteresis, so a lander rocking on its gear settles
         * once rather than flickering in and out of dormancy - and long
         * enough that a craft still creeping down a slope is not caught
         * mid-slide and frozen there.
         */
        private const val SLEEP_SETTLE_TICKS = 120


        val launchSites = listOf(
            LaunchSite(
                id = "cape",
                displayName = "Cape Launch Complex",
                bodyId = SolarSystem.HOMEWORLD_ID,
                latitude = 0.0,
                longitude = 0.0,
            ),
            // Offshore, north-east of the Cape where its continent first
            // meets the sea, 87 km away. Thirty-two metres of water, and
            // nothing shallower than seven within a kilometre and a half.
            LaunchSite(
                id = "harbour",
                displayName = "North-East Harbour",
                bodyId = SolarSystem.HOMEWORLD_ID,
                latitude = 0.102236,
                longitude = 0.102236,
            ),
        )

        /**
         * Where a design should be launched from: the sea for anything built
         * around a hull, the pad for everything else. A chooser is the right
         * answer once there are more than two; with two, the design already
         * says which it wants.
         */
        fun launchSiteFor(design: CraftDesign, catalog: PartCatalog): LaunchSite {
            val floats = design.parts.any {
                catalog[it.partId]?.hasModule<com.rm.apogee.core.part.Buoyancy>() == true
            }
            return launchSites.first { it.id == if (floats) "harbour" else "cape" }
        }

        fun default(catalog: PartCatalog) = World(SolarSystem.defaultSystem(), catalog)
    }
}
