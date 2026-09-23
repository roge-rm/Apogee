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
import com.rm.apogee.core.part.AeroSurface
import com.rm.apogee.core.part.Decoupler
import com.rm.apogee.core.part.LandingLeg
import com.rm.apogee.core.part.PartCatalog
import com.rm.apogee.core.physics.ContactReport
import com.rm.apogee.core.physics.CraftContact
import com.rm.apogee.core.physics.GroundContact
import com.rm.apogee.core.terrain.TerrainField
import com.rm.apogee.core.weather.Strike
import com.rm.apogee.core.weather.Weather
import com.rm.apogee.core.weather.WeatherConfig

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

    /** A tree or shrub knocked down, for good. */
    data class ScatterFelled(val scatterId: Long) : WorldEvent
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

    /**
     * Lightning struck a craft - [partIndex] is what it knocked out, or -1
     * if it came through unharmed. Strikes that hit nothing need no event:
     * every client works them out from the weather for itself.
     */
    data class LightningHit(
        val id: VesselId,
        val strikeId: Long,
        val partIndex: Int,
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

    /**
     * Sets the clock of a replica: a client's prediction world has to agree
     * with the server's about what time it is, because the time is where the
     * planet has turned to, and so where the ground is under every craft.
     * Never used on an authoritative world, whose clock only advances by
     * stepping.
     */
    fun syncClock(time: Double) {
        this.time = time
    }

    var tick: Long = 0
        private set

    /**
     * What the weather is made from: the world's seed and how lively the
     * host wants it - or null for still air. A game's world is given its
     * config by the server that owns it, and a replica takes the server's;
     * a bare world is still, so a test of how a leg takes a landing is not
     * also a test of which way the wind happened to blow.
     */
    var weatherConfig: WeatherConfig? = null
        set(value) {
            if (field == value) return
            field = value
            weathers.clear()
        }

    private val weathers = HashMap<String, Weather>()

    /** Pairs of craft that have just separated, by [pairKey], and until when they ignore each other. */
    private val justSeparated = HashMap<Long, Double>()

    private fun pairKey(a: Long, b: Long): Long = if (a < b) (a shl 32) or b else (b shl 32) or a

    /** The weather over [body], or null for an airless one or a still world. */
    fun weatherFor(body: CelestialBody): Weather? {
        val config = weatherConfig ?: return null
        if (body.atmosphere == null) return null
        return weathers.getOrPut(body.id) { Weather(body, config) }
    }

    private val weatherRotation = Quat.identity()
    private val weatherPoint = Vec3()
    private var lightningCheckedTo = Double.NaN
    private val strikesFound = ArrayList<Strike>()
    private val strikesSeen = HashSet<Long>()

    private val vesselsById = LinkedHashMap<VesselId, Vessel>()
    private var nextVesselId = 1L

    private val forces = Forces()
    private val stabilityAssist = StabilityAssist()
    private val hydrostatics = Hydrostatics()
    private val scatterContacts = com.rm.apogee.core.physics.ScatterContact()

    /**
     * Scatter knocked down - trees, shrubs, cacti - by id. World state: saved,
     * sent to every client, and never regrown. Everything else about scatter
     * is decided by the terrain and needs no remembering.
     */
    val felledScatter: MutableSet<Long> = java.util.concurrent.ConcurrentHashMap.newKeySet()

    /**
     * The craft each player last flew, by owner id. Saved with the world, so
     * starting a fresh flight can clear away the one from the last session -
     * and only that one: anything else a player owns is a base they left on
     * purpose.
     */
    val lastFlown: MutableMap<String, Long> = java.util.concurrent.ConcurrentHashMap()
    private val contacts = GroundContact()

    private val pendingEvents = ArrayList<WorldEvent>()

    /** The time the tick in progress ends at: [time] + dt, while stepping. */
    private var tickEnd = 0.0

    /** Knocks down scatter [id] for good, and tells everyone. Felling it twice does nothing. */
    fun fell(id: Long) {
        if (felledScatter.add(id)) pendingEvents.add(WorldEvent.ScatterFelled(id))
    }

    /**
     * Craft to remove once the step finishes.
     *
     * Destroying in place would mutate the map being iterated. Reused rather
     * than allocated per step, and empty on almost every one.
     */
    private val pendingDestruction = ArrayList<Pair<VesselId, String>>()

    private val craftContacts = CraftContact().also { contacts ->
        contacts.ignorePair = { a, b -> justSeparated.containsKey(pairKey(a, b)) }
    }

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

    /**
     * Stands [vessel] on the ground directly below it, as posed now: lifted
     * or lowered until its lowest contact just touches, moving with the
     * surface, not turning. For putting a craft down after changing its
     * shape - deploying its legs in place, say - without dropping it or
     * burying it.
     */
    fun setDown(vessel: Vessel) {
        val attractor = system.body(vessel.referenceBodyId)
        val up = Vec3().setTo(vessel.body.position).normalizeInPlace()
        attractor.rotationAt(time, scratchRotation)
        val bodyFixed = attractor.toBodyFixed(up, scratchRotation)
        val ground = attractor.surfaceRadiusInBodyFrame(bodyFixed)
        vessel.body.position.setTo(up).mulInPlace(ground + lowestExtentAlong(vessel, up))
        attractor.surfaceVelocityAt(vessel.body.position, vessel.body.linearVelocity)
        vessel.body.angularVelocity.setTo(Vec3.zero())
    }

    /**
     * Sets every craft that was resting on the ground back onto it, after a
     * load onto a different terrain.
     *
     * The saved position was on the old ground, which may now be metres above
     * or below it: a parked rover would wake up buried, and be flung out, or
     * hovering, and drop. "Resting" is judged by motion, since the old ground
     * is gone - barely moving relative to the surface, and low enough that
     * the surface is what it could be resting on. Craft in flight and in orbit
     * are left exactly where they were, and so is anything afloat.
     *
     * The craft keeps its orientation and is lifted or lowered so its lowest
     * contact point just touches the new ground, moving with the surface. If
     * it was parked on a slope it settles onto the new one by itself.
     *
     * @return how many craft were moved.
     */
    private fun reseatOnNewTerrain(): Int {
        var moved = 0
        val up = Vec3()
        val bodyFixed = Vec3()
        val surface = Vec3()
        val rotation = Quat.identity()
        for (vessel in vesselsById.values) {
            val attractor = system.body(vessel.referenceBodyId)
            val terrain = attractor.terrain ?: continue
            attractor.surfaceVelocityAt(vessel.body.position, surface)
            val relative = Vec3().setTo(vessel.body.linearVelocity).subInPlace(surface).length
            if (relative > RESEAT_MAX_SPEED) continue
            val altitude = attractor.altitudeOf(vessel.body.position)
            if (altitude > terrain.maxElevation + RESEAT_MAX_HEIGHT) continue

            up.setTo(vessel.body.position).normalizeInPlace()
            attractor.rotationAt(time, rotation)
            attractor.toBodyFixed(up, rotation, bodyFixed)
            if (terrain.isOcean(bodyFixed)) continue
            val ground = attractor.surfaceRadiusInBodyFrame(bodyFixed)

            vessel.body.position.setTo(up).mulInPlace(ground + lowestExtentAlong(vessel, up) + 0.05)
            attractor.surfaceVelocityAt(vessel.body.position, vessel.body.linearVelocity)
            vessel.body.angularVelocity.setTo(Vec3.zero())
            moved++
        }
        return moved
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

            is Command.SetBrakes ->
                waken(command.vessel)?.control?.brakes = command.engaged

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

        // Every decoupler in the stage, not just the first: four radial
        // boosters are four decouplers firing together. Each split renumbers
        // the craft that keeps flying, so the rest are followed through it.
        var pending = activated.filter { index -> vessel.defs.getOrNull(index)?.module<Decoupler>() != null }
        while (pending.isNotEmpty()) {
            val kept = splitAt(vessel, pending.first()) ?: break
            pending = pending.drop(1).mapNotNull { old -> kept.indexOf(old).takeIf { it >= 0 } }
        }
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

    private fun splitAt(vessel: Vessel, decouplerIndex: Int): List<Int>? {
        val separating = vessel.design.subtreeOf(decouplerIndex).toSet()
        val remaining = vessel.design.parts.indices.filter { it !in separating }
        if (separating.isEmpty() || remaining.isEmpty()) return null

        val ejection = vessel.defs[decouplerIndex].module<Decoupler>()?.ejectionImpulse ?: 0.0

        // Both halves need their world motion preserved, so capture it before
        // either design is rebuilt underneath them.
        val position = vessel.body.position.copy()
        val orientation = vessel.body.orientation.copy()
        val linearVelocity = vessel.body.linearVelocity.copy()
        val angularVelocity = vessel.body.angularVelocity.copy()

        val discarded = buildSubDesign(vessel.design, separating.sorted())
        val kept = buildSubDesign(vessel.design, remaining, firedStages = vessel.currentStage)

        // Resolve BOTH halves' definitions before either design is replaced.
        // replaceStructure swaps vessel.defs for the kept subset, so indexing
        // it afterwards with original indices reads the wrong parts - or walks
        // off the end, which is how this was found.
        val originalDefs = vessel.defs
        val keptDefs = kept.indices.map { originalDefs[it] }
        val discardedDefs = discarded.indices.map { originalDefs[it] }

        // The half that keeps flying, at the same place in its sequence.
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
        // The halves overlap as they part - an engine bell inside the ring
        // it sat on - and meet again if the craft is turning or the spent
        // half is braked harder by the air than the live one. Neither is a
        // collision: they are still coming apart.
        justSeparated[pairKey(vessel.id.raw, debris.id.raw)] = time + SEPARATION_GRACE
        pendingEvents.add(WorldEvent.VesselStructureChanged(vessel.id))
        pendingEvents.add(WorldEvent.VesselSpawned(debris.id))
        return kept.indices
    }

    private class SubDesign(val design: CraftDesign, val indices: List<Int>)

    /**
     * Rebuilds a design from a subset of parts, remapping parent indices.
     *
     * Stages left with nothing to fire are dropped - except the first
     * [firedStages], which have fired already and are kept, empty, as
     * placeholders. Dropping those renumbered every stage after them: the
     * chute a player knew as stage 2 became stage 1 the moment the stage
     * below it fell away, and the craft's place in its own sequence had to
     * be moved back to match, or it skipped a stage.
     */
    private fun buildSubDesign(source: CraftDesign, keep: List<Int>, firedStages: Int = 0): SubDesign {
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

        val stages = source.stages.map { stage ->
            com.rm.apogee.core.craft.Stage(
                stage.activatedParts.mapNotNull { remap[it] }
            )
        }.filterIndexed { index, stage -> index < firedStages || stage.activatedParts.isNotEmpty() }

        // A copy, so the orientation comes too: a plane dropping a tank was
        // left thinking it stood on its tail.
        return SubDesign(source.copy(parts = parts, stages = stages), keep)
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
        tickEnd = time + dt
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
                // At the end of the tick, which is the time this tick's
                // positions are reported at - integrated craft move to it,
                // and a sleeping one has to be where the ground is then.
                attractor.rotationAt(tickEnd, scratchRotation)
                attractor.surfaceVelocityAt(body.position, scratchSurfaceVelocity)
                attractor.angularVelocity(scratchSpin)
                vessel.followRotation(scratchRotation, scratchSurfaceVelocity, scratchSpin)
                // The ground's velocity where it now is, not where it was a
                // tick ago: a client recognises a sleeping craft by its
                // moving with the surface exactly.
                attractor.surfaceVelocityAt(body.position, body.linearVelocity)
                continue
            }

            body.clearAccumulators()

            // Before any force, because the elevons deflect inside the drag
            // pass and the gimbal inside thrust: all of them act on what
            // stability assist asks for this tick.
            stabilityAssist.update(vessel, dt)
            updatePose(vessel, dt)

            forces.applyGravity(vessel, attractor)
            forces.applyThrust(vessel, attractor, dt, time)
            // The air it is flying through, once per craft per tick at its
            // centre of mass; gusts over its length come in the drag pass.
            val weather = weatherFor(attractor)
            attractor.rotationAt(time, weatherRotation)
            if (weather != null) {
                attractor.toBodyFixed(body.position, weatherRotation, weatherPoint)
                weather.sample(weatherPoint, time, vessel.air)
            } else {
                vessel.air.clear()
            }
            forces.applyDrag(vessel, attractor, weather, weatherRotation, time)
            for (i in 0 until forces.tornCount) {
                val index = forces.tornParachutes[i]
                pendingEvents.add(
                    WorldEvent.PartFailed(
                        vessel.id, index, "${vessel.defs[index].title} tore away",
                    )
                )
            }
            for (i in 0 until forces.overstressedCount) {
                val index = forces.overstressed[i]
                pendingEvents.add(
                    WorldEvent.PartFailed(
                        vessel.id, index, "${vessel.defs[index].title} failed under load",
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
            // Boulders and trunks, into the same report, so a craft wrecked on a
            // rock is judged the way one wrecked on the ground is.
            scatterContacts.resolve(vessel, attractor, time, contacts.report, felledScatter) { fell(it) }
            val report = contacts.report
            vessel.touchingGround = report.hadContact
            vessel.groundContacts = report.contactCount

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
        if (justSeparated.isNotEmpty()) justSeparated.values.removeAll { it < time }
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

        if (tick % LIGHTNING_CHECK_TICKS == 0L) strikeLightning(tickEnd)

        tick++
        time += dt
    }

    /**
     * Lightning since the last look, up to [until]: every strike near any
     * craft - flying or parked, since a storm does not care which - that
     * lands within [LIGHTNING_REACH] of one below the cloud hits it. The
     * highest part takes it, and may be knocked out. Every part of it is a
     * function of the weather and the strike, so a replica agrees.
     */
    private fun strikeLightning(until: Double) {
        val from = if (lightningCheckedTo.isNaN()) until - LIGHTNING_CHECK_TICKS / 60.0 else lightningCheckedTo
        lightningCheckedTo = until
        if (until <= from) return
        strikesSeen.clear()
        for (vessel in vesselsById.values.toList()) {
            val attractor = attractorFor(vessel)
            val weather = weatherFor(attractor) ?: continue
            attractor.rotationAt(until, weatherRotation)
            attractor.toBodyFixed(vessel.body.position, weatherRotation, weatherPoint)
            val height = weatherPoint.length - attractor.radius
            if (height > LIGHTNING_CEILING) continue
            weatherPoint.normalizeInPlace()
            strikesFound.clear()
            weather.strikes(weatherPoint, from, until, strikesFound)
            for (strike in strikesFound) {
                val horizontal = strike.direction.distanceTo(weatherPoint) * attractor.radius
                if (horizontal > LIGHTNING_REACH + vessel.contactRadius) continue
                // One craft per strike: the first found, which is stable.
                if (!strikesSeen.add(strike.id)) continue
                hitByLightning(vessel, attractor, strike)
            }
        }
    }

    private fun hitByLightning(vessel: Vessel, attractor: CelestialBody, strike: Strike) {
        vessel.wake()
        // The highest part, against the local vertical.
        val up = Vec3().setTo(vessel.body.position).normalizeInPlace()
        var highest = -1
        var best = Double.NEGATIVE_INFINITY
        val offset = Vec3()
        for (i in vessel.defs.indices) {
            if (vessel.isBroken(i)) continue
            vessel.partOffsetWorld(i, offset)
            val h = offset dot up
            if (h > best) { best = h; highest = i }
        }
        val roll = com.rm.apogee.core.terrain.Noise.hash(weatherConfig?.seed ?: 0, (strike.id ushr 32).toInt(), strike.id.toInt(), 7)
        val damaged = highest >= 0 && roll < 0.35 + 0.5 * strike.energy && vessel.breakPart(highest)
        pendingEvents.add(WorldEvent.LightningHit(vessel.id, strike.id, if (damaged) highest else -1))
        if (damaged) {
            pendingEvents.add(
                WorldEvent.PartFailed(vessel.id, highest, "${vessel.defs[highest].title} was struck by lightning"),
            )
        }
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
        // Not while a leg is still swinging: asleep, it would stop half out.
        if (legsMoving(vessel)) {
            vessel.noteStillness(false, SLEEP_SETTLE_TICKS)
            return
        }
        val still = report.anchored || floatingStill(vessel)
        if (vessel.noteStillness(still, SLEEP_SETTLE_TICKS)) {
            // The pose was just integrated to the end of the tick, so it is
            // pinned to the ground as the ground is then.
            attractorFor(vessel).rotationAt(tickEnd, scratchRotation)
            vessel.sleep(scratchRotation)
        }
    }

    /**
     * Afloat, clear of the bottom, engine off, and going nowhere relative to
     * the water.
     *
     * The other way to be at rest. Ground contact is what normally decides,
     * and a floating craft has none, so without this a boat moored at sea was
     * simulated every tick for ever - which in a world of bases people leave
     * and come back to is exactly the craft that should cost nothing.
     *
     * A velocity threshold here, not an anchor, because nothing is holding a
     * floating craft - it is still because the water has damped it. Valid
     * only while the sea is calm: with waves (M9) a floating craft is never
     * at rest, and a dormant one has to ride the surface instead.
     *
     * In a wind a boat left alone never stops: it drifts, steadily, at the
     * pace the wind on its topsides and the water on its hull agree on. Held
     * to the still-water threshold it would never sleep, and in a world
     * people leave boats in it would drift off across the sea while nobody
     * was there. So a boat left alone - engine off, hands off - drops
     * anchor: drifting no faster than [ANCHOR_DRIFT], it is allowed to
     * sleep, and asleep it stays where it is.
     */
    private fun floatingStill(vessel: Vessel): Boolean {
        if (vessel.touchingGround || hydrostatics.submergedVolume <= 0.0) return false
        val control = vessel.control
        if (control.throttle > 0.0) return false
        val handsOff = control.pitch == 0.0 && control.yaw == 0.0 && control.roll == 0.0
        val limit = if (handsOff && vessel.air.wind.length > 0.5) ANCHOR_DRIFT else FLOATING_REST_SPEED
        val attractor = attractorFor(vessel)
        attractor.surfaceVelocityAt(vessel.body.position, scratchSurfaceVelocity)
        scratchRelativeVelocity.setTo(vessel.body.linearVelocity).subInPlace(scratchSurfaceVelocity)
        if (scratchRelativeVelocity.length > limit) return false
        attractor.angularVelocity(scratchSpin)
        scratchSpin.subInPlace(vessel.body.angularVelocity)
        return scratchSpin.length * vessel.contactRadius <= limit
    }

    /**
     * Poses the craft's moving parts for this tick: control surfaces to the
     * stick (and stability assist), steerable wheels to the steering, legs
     * towards deployed once staged. The forces and the contacts read these,
     * and snapshots carry them, so what moves a craft and what everyone sees
     * of it are the same numbers.
     */
    private fun updatePose(vessel: Vessel, dt: Double) {
        vessel.fitPose()
        val yaw = vessel.control.yaw
        val forward = vessel.design.orientation.forward
        vessel.centerOfMass(scratch)
        for (i in vessel.defs.indices) {
            val def = vessel.defs[i]
            if (def.module<AeroSurface>()?.controllable == true ||
                def.module<com.rm.apogee.core.part.HydroSurface>()?.controllable == true
            ) {
                vessel.surfaceDeflection[i] = forces.controlDeflection(vessel, i)
            }
            val engine = def.module<com.rm.apogee.core.part.Engine>()
            if (engine != null && engine.gimbalRange > 0.0) {
                // As Forces.gimballedDirection swings the thrust.
                val burning = vessel.isWorking(i) && vessel.control.throttle > 0.0
                vessel.gimbalPitch[i] = if (burning) -vessel.control.commandPitch.coerceIn(-1.0, 1.0) else 0.0
                vessel.gimbalYaw[i] = if (burning) -vessel.control.commandYaw.coerceIn(-1.0, 1.0) else 0.0
            }
            val wheel = def.module<com.rm.apogee.core.part.Wheel>()
            if (wheel != null) {
                vessel.wheelSteer[i] = if (!wheel.steerable || yaw == 0.0) 0.0 else {
                    // Front wheels into the corner, rear wheels away from it -
                    // which end is judged from the centre of mass along the
                    // craft's forward. See GroundContact.driveWheel.
                    val ahead = (vessel.design.parts[i].position.x - scratch.x) * forward.x +
                        (vessel.design.parts[i].position.y - scratch.y) * forward.y +
                        (vessel.design.parts[i].position.z - scratch.z) * forward.z
                    val end = if (ahead >= 0.0) 1.0 else -1.0
                    Math.toRadians(wheel.steeringRange * yaw) * end
                }
            }
            val leg = def.module<LandingLeg>()
            if (leg != null) {
                val target = if (vessel.isWorking(i)) 1.0 else 0.0
                val step = dt / leg.deployTime.coerceAtLeast(1e-3)
                val now = vessel.legDeploy[i]
                vessel.legDeploy[i] = if (now < target) minOf(target, now + step) else maxOf(target, now - step)
            }
        }
    }

    private fun legsMoving(vessel: Vessel): Boolean {
        for (i in vessel.defs.indices) {
            if (vessel.defs[i].module<LandingLeg>() == null) continue
            val target = if (vessel.isWorking(i)) 1.0 else 0.0
            if (vessel.legDeploy.getOrElse(i) { target } != target) return true
        }
        return false
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
                pose = VesselPose.encode(vessel),
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
        felledScatter = felledScatter.sorted(),
        terrainGeneration = TerrainField.GENERATION,
        lastFlown = lastFlown.toMap(),
        weather = weatherConfig,
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
                brakes = vessel.control.brakes,
                resources = vessel.resourceSnapshot().map { it.toList() },
                legDeploy = vessel.legDeploy.toList(),
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
        val terrainChanged = save.terrainGeneration != TerrainField.GENERATION
        felledScatter.clear()
        // Scatter ids name places on one generation's ground; on another they
        // would fell some unrelated tree, so a new terrain grows back whole.
        if (!terrainChanged) felledScatter.addAll(save.felledScatter)
        lastFlown.clear()
        lastFlown.putAll(save.lastFlown)
        save.weather?.let { weatherConfig = it }

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
            vessel.control.brakes = saved.brakes
            vessel.fitPose()
            saved.legDeploy.forEachIndexed { i, progress -> vessel.setLegDeploy(i, progress) }
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

        if (terrainChanged) {
            val moved = reseatOnNewTerrain()
            problems.add(
                "The terrain changed (generation ${save.terrainGeneration} to " +
                    "${TerrainField.GENERATION}): set $moved landed craft back on the ground" +
                    if (save.felledScatter.isNotEmpty()) "; felled trees have regrown" else ""
            )
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
        val vessel = spawnAtSite(command.design, site)
        vessel.owner = owner
        return vessel
    }

    /**
     * Puts a craft on the nearest clear pad at [site]: the site itself if
     * nothing is standing there, otherwise the next pad out, alternating
     * either side, forty metres apart.
     *
     * Clear means clear of the whole craft, not its centre: a pad counts as
     * taken while any craft is nearer than the two craft's radii plus a
     * margin, so a wide aeroplane does not have a rocket put through its wing.
     */
    fun spawnAtSite(design: CraftDesign, site: LaunchSite): Vessel =
        spawnOnSurface(design, site, pad = nextFreePad(site, radiusOf(design)))

    /** How far [design]'s furthest contact point reaches from its centre of mass. */
    private fun radiusOf(design: CraftDesign): Double {
        val probe = Vessel(
            id = VesselId(-1),
            design = design,
            defs = design.parts.map { catalog.require(it.partId) },
            referenceBodyId = SolarSystem.HOMEWORLD_ID,
        )
        probe.recomputeMass(shiftBodyPosition = false)
        return probe.contactRadius
    }

    /**
     * The first pad at [site] with nothing standing on it.
     *
     * A persistent world accumulates craft at the launch complex, and dropping
     * a new one into a pad that is already occupied would spawn it inside
     * somebody's base - which, now that craft are solid, is an explosion
     * rather than a curiosity.
     */
    private fun nextFreePad(site: LaunchSite, radius: Double): Int {
        val spot = Vec3()
        var best = 0
        var bestClearance = Double.NEGATIVE_INFINITY
        for (pad in 0 until MAX_PADS) {
            surfaceNormalAt(site, pad, scratchBodyFixedUp)
            attractorFor(site).rotationAt(time, scratchRotation)
            scratchRotation.rotate(scratchBodyFixedUp, spot)
            spot.mulInPlace(attractorFor(site).surfaceRadiusInBodyFrame(scratchBodyFixedUp) + radius)

            // The tightest gap to anything already here, beyond the margin.
            var clearance = Double.POSITIVE_INFINITY
            for (other in vesselsById.values) {
                if (other.referenceBodyId != site.bodyId) continue
                val gap = scratch.setTo(other.body.position).subInPlace(spot).length -
                    other.contactRadius - radius - PAD_MARGIN_METRES
                if (gap < clearance) clearance = gap
            }
            if (clearance >= 0.0) return pad
            if (clearance > bestClearance) {
                bestClearance = clearance
                best = pad
            }
        }
        // Every pad taken: the one with the most room.
        return best
    }

    private fun attractorFor(site: LaunchSite): CelestialBody = system.body(site.bodyId)

    /** Finds a craft belonging to [owner], so a returning player gets it back. */
    fun vesselOwnedBy(owner: String): Vessel? =
        if (owner.isBlank()) null
        // Exact, not case-insensitive: this is an opaque id now, not a name
        // someone typed, so folding case can only ever create a false match.
        else vesselsById.values.firstOrNull { it.owner == owner }

    /**
     * Puts a fresh copy of craft [id] back on its launch site - the site its
     * design would launch from, on the nearest clear pad - fuelled and
     * unstaged, with the same name and owner. The craft as it was is gone:
     * a rover stuck in a ravine, a lander on its side. Returns the new craft,
     * or null if there was no such craft.
     */
    fun resetToSite(id: VesselId): Vessel? {
        val old = vesselsById[id] ?: return null
        val design = old.design
        val name = old.name
        val owner = old.owner
        val ownerName = old.ownerName
        destroy(id, "reset to its launch site")
        val fresh = spawnAtSite(design, launchSiteFor(design, catalog))
        fresh.name = name
        fresh.owner = owner
        fresh.ownerName = ownerName
        lastFlown.entries.filter { it.value == id.raw }.forEach { lastFlown[it.key] = fresh.id.raw }
        return fresh
    }

    fun destroy(id: VesselId, reason: String) {
        if (vesselsById.remove(id) != null) {
            pendingEvents.add(WorldEvent.VesselDestroyed(id, reason))
        }
    }

    companion object {
        /** Metres between adjacent launch pads at a site. */
        private const val PAD_SPACING_METRES = 40.0

        /** Gap, metres, kept between a new craft and anything already standing near its pad. */
        private const val PAD_MARGIN_METRES = 5.0

        /** How many pads to look through before giving up and reusing one. */
        private const val MAX_PADS = 64

        /** Faster than this relative to the ground, m/s, a craft is flying, not parked. */
        private const val RESEAT_MAX_SPEED = 2.0

        /** Metres above the highest ground beyond which nothing is resting on it. */
        private const val RESEAT_MAX_HEIGHT = 500.0

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

        /** Seconds two halves of a staged craft pass through each other while they part. */
        private const val SEPARATION_GRACE = 1.5

        /** Fastest drift, m/s, at which a boat left alone in a wind drops anchor. */
        private const val ANCHOR_DRIFT = 1.5

        /** Lightning is looked for once a second. */
        private const val LIGHTNING_CHECK_TICKS = 60L

        /** How close to a craft a strike has to land to hit it, m. */
        private const val LIGHTNING_REACH = 60.0

        /** Above this a craft is in or over the cloud, not under the bolt, m. */
        private const val LIGHTNING_CEILING = 3_000.0

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

        /**
         * Metres per second, of the hull or of its extremities turning, below
         * which a floating craft counts as moored. Above the few millimetres a
         * second the heave damping leaves behind after settling, well below
         * any drift anyone could see.
         */
        private const val FLOATING_REST_SPEED = 0.02


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
            // For testing: straight onto the Moon without flying there. On
            // the mare north-east of Luna's prime meridian, a kilometre and a
            // half below the datum, where the ground under the whole row of
            // pads grades less than one in a hundred.
            LaunchSite(
                id = "luna-mare",
                displayName = "Luna Mare (test)",
                bodyId = "luna",
                latitude = 0.131822,
                longitude = 0.131733,
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
