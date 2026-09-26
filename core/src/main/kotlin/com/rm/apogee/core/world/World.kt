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
@kotlinx.serialization.Serializable
data class LaunchSite(
    val id: String,
    val displayName: String,
    val bodyId: String,
    /** Radians. */
    val latitude: Double,
    val longitude: Double,
) {
    /** A pad on a founded base, rather than one of the fixed sites. */
    val onBase: Boolean get() = id.startsWith(BASE_SITE_PREFIX)

    companion object {
        /** Base pads' site ids: this, the base's vessel id, a colon and the pad's part index. */
        const val BASE_SITE_PREFIX = "base:"
    }
}

/** Something worth telling the presentation layer about. */
sealed interface WorldEvent {
    data class VesselSpawned(val id: VesselId) : WorldEvent
    data class VesselStructureChanged(val id: VesselId) : WorldEvent
    data class VesselDestroyed(val id: VesselId, val reason: String) : WorldEvent

    /** [absorbed] docked on to [keeper] and is now part of it. */
    data class Docked(val keeper: VesselId, val absorbed: VesselId, val position: Vec3, val bodyId: String) : WorldEvent

    /** [spawned] undocked from [from] and is a craft of its own again. */
    data class Undocked(val from: VesselId, val spawned: VesselId, val position: Vec3, val bodyId: String) : WorldEvent

    /** A tow hitch coupled ([coupled]) or let go. */
    data class Hitched(val a: VesselId, val b: VesselId, val coupled: Boolean, val position: Vec3, val bodyId: String) : WorldEvent

    /** A tree or shrub knocked down, for good. */
    data class ScatterFelled(val scatterId: Long) : WorldEvent

    /** Refuelling [id] from a base stopped: [reason] - full, the base dry or dark, or nothing to fill it from. */
    data class RefuelStopped(val id: VesselId, val reason: String) : WorldEvent
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
     * A part struck hard enough to hurt it: [damage] of its health taken, at
     * [position] in its attractor's frame. For sound and effects.
     */
    data class Impact(
        val id: VesselId,
        val partIndex: Int,
        val partId: String,
        val speed: Double,
        val damage: Double,
        val position: Vec3,
        val bodyId: String,
        /** Into the sea rather than onto something solid. */
        val water: Boolean = false,
    ) : WorldEvent

    /** A part destroyed outright - struck, burnt, blown up - at [position] in its attractor's frame. */
    data class PartDestroyed(
        val id: VesselId,
        val partIndex: Int,
        val partId: String,
        val cause: String,
        val position: Vec3,
        val bodyId: String,
    ) : WorldEvent

    /**
     * A part torn off whole - a joint that let go, a wing snapped - taking
     * whatever hangs from it along, as debris of its own. At [position] in
     * its attractor's frame.
     */
    data class PartDetached(
        val id: VesselId,
        val partIndex: Int,
        val partId: String,
        val cause: String,
        val position: Vec3,
        val bodyId: String,
    ) : WorldEvent

    /** A tank going up with [energy] kg of propellant, at [position] round body [bodyId]. */
    data class Explosion(val bodyId: String, val position: Vec3, val energy: Double) : WorldEvent

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
            bindSeas()
        }

    /**
     * Gives each body's ocean its sea: its moon's tides always, and waves
     * from this world's weather when it has one. Every world binds its own -
     * the server's, each client's replica - and they agree, being the same
     * function of the same config.
     */
    private fun bindSeas() {
        for (body in system.bodies.values) {
            val ocean = body.ocean ?: continue
            val moon = system.bodies.values.firstOrNull { it.parentId == body.id && it.orbit != null }
            ocean.sea = com.rm.apogee.core.sea.Sea(body, moon, weatherFor(body), weatherConfig?.seed ?: 0)
        }
    }

    init {
        bindSeas()
    }

    private val weathers = HashMap<String, Weather>()

    private val navDirections = NavDirections()
    private val holdScratch = Vec3()

    /**
     * Where stability assist should hold the nose, in inertial axes, for a
     * craft holding a navball marker - computed exactly as the navball
     * draws it. Null for plain attitude hold, or a marker with nothing to
     * point at (no motion, no target).
     */
    private fun holdDirection(vessel: Vessel, attractor: CelestialBody): Vec3? {
        val control = vessel.control
        if (!control.sasEnabled || control.sasMode == SasMode.HOLD) return null
        val target = vessel(VesselId(control.target))?.takeIf { it.referenceBodyId == vessel.referenceBodyId }
        Navigation.compute(
            vessel.body.position, vessel.body.linearVelocity, attractor, control.navFrame,
            target?.body?.position, target?.body?.linearVelocity, navDirections,
        )
        return if (navDirections.forMode(control.sasMode, holdScratch)) holdScratch else null
    }

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
    private val stress = Stress()
    private val heat = Heat()
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

    /** Craft with a part damaged to nothing this tick, to break up at its end. */
    private val pendingBreakUps = LinkedHashSet<VesselId>()

    /** Why a craft in [pendingBreakUps] is breaking up, when it is not a blow. */
    private val breakUpCause = HashMap<VesselId, String>()

    /** Joints that let go this tick, by craft: the part below each. */
    private val pendingDetach = HashMap<VesselId, MutableSet<Int>>()
    private val impactNormal = Vec3()

    /**
     * Small crash fragments, and when they go: bits of fins and rings would
     * otherwise litter every crash site for good. Anything with controls on
     * it, or heavy enough to be wreckage worth finding, is never here.
     */
    private val fragmentExpiry = HashMap<VesselId, Double>()

    /** Tanks that went up this tick, to damage what is near them. */
    private class Blast(val bodyId: String, val centre: Vec3, val energy: Double, val source: VesselId)
    private val pendingBlasts = ArrayList<Blast>()

    private val craftContacts = CraftContact().also { contacts ->
        contacts.ignorePair = { a, b -> docking.capturing(a, b) || linked(a, b) }
        // Just parted: still solid to each other, only gently so.
        contacts.gentlePair = { a, b -> justSeparated.containsKey(pairKey(a, b)) }
        // A stage let go under power can push the one above for seconds:
        // gentle for as long as they touch, not only the first second and a half.
        contacts.gentleTouching = { a, b ->
            val key = pairKey(a, b)
            val until = justSeparated[key]
            if (until != null && until < time + GENTLE_HOLD) justSeparated[key] = time + GENTLE_HOLD
        }
    }

    /** Docking parts drawing each other in, and latching. */
    private val docking = com.rm.apogee.core.physics.Docking()

    /** A tow hitch coupled: two craft turning about one point. */
    class Link(val a: VesselId, val partA: Int, val b: VesselId, val partB: Int)

    /** Hitches coupled now. */
    private val links = ArrayList<Link>()

    /** The links, for drawing and for tests. */
    val hitches: List<Link> get() = links

    private fun linked(a: Long, b: Long) = links.any { (it.a.raw == a && it.b.raw == b) || (it.a.raw == b && it.b.raw == a) }

    /** Whether craft [a] and [b] are drawing each other in to dock. */
    fun capturing(a: Long, b: Long) = docking.capturing(a, b)

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
        // At sea, on the water as it is now - the tide may be metres up or down.
        val sea = attractor.ocean?.let { attractor.radius + it.surfaceHeight(scratchBodyFixedUp, time) }
        val groundRadius = kotlin.math.max(attractor.solidRadiusInBodyFrame(scratchBodyFixedUp), sea ?: 0.0)
            .let { if (sea == null) attractor.surfaceRadiusInBodyFrame(scratchBodyFixedUp) else it }

        // Lift the craft until its lowest part just touches the ground.
        val clearance = lowestExtentAlong(vessel, up)
        vessel.body.position
            .setTo(up)
            .mulInPlace(groundRadius + clearance)
        // Rebuild now that orientation and position are set.
        vessel.recomputeMass(shiftBodyPosition = false)
        vessel.body.position.setTo(up).mulInPlace(groundRadius + lowestExtentAlong(vessel, up))

        attractor.surfaceVelocityAt(vessel.body.position, vessel.body.linearVelocity)
        if (sea != null) floatAtDraft(vessel, attractor, up, sea)

        vesselsById[vessel.id] = vessel
        pendingEvents.add(WorldEvent.VesselSpawned(vessel.id))
        return vessel
    }

    /**
     * A boat set down afloat: settled at the draft its weight gives it on
     * the water it is put in, moving as that water is. Stood on its lowest
     * point instead - an outboard's leg - it was dropped the best part of a
     * metre, and in a seaway landed still on a moving slope and slammed
     * over: a skiff launched into the harbour pitched up fifty degrees and
     * swamped where it was put (Dan). Nothing floating, or water too
     * shallow for it, and it stays as stood on the bottom.
     */
    private fun floatAtDraft(vessel: Vessel, attractor: CelestialBody, up: Vec3, seaRadius: Double) {
        val ocean = attractor.ocean ?: return
        var area = 0.0
        var bottom = Double.MAX_VALUE
        var deepest = 0.0
        val offset = Vec3()
        for (i in vessel.defs.indices) {
            if (vessel.defs[i].module<com.rm.apogee.core.part.Buoyancy>() == null) continue
            val box = vessel.defs[i].mesh as? com.rm.apogee.core.part.MeshSpec.Box ?: continue
            area += box.width * box.height
            vessel.partPointOffsetWorld(i, Vec3(0.0, 0.0, -0.5 * box.depth), offset)
            bottom = minOf(bottom, offset dot up)
            deepest = maxOf(deepest, box.depth)
        }
        if (area <= 0.0) return
        val draft = (vessel.body.mass / (ocean.density * area)).coerceAtMost(0.9 * deepest)
        val radius = seaRadius - draft - bottom
        // Only if that is not below where it already stands clear of the bottom.
        if (radius > vessel.body.position.length) return
        if (radius - lowestExtentAlong(vessel, up) < attractor.solidRadiusInBodyFrame(scratchBodyFixedUp)) return
        vessel.body.position.setTo(up).mulInPlace(radius)
        attractor.surfaceVelocityAt(vessel.body.position, vessel.body.linearVelocity)
        ocean.sample(scratchBodyFixedUp, time, seaRide)
        attractor.rotationAt(time, scratchRotation)
        vessel.body.linearVelocity.addInPlace(scratchRotation.rotate(seaRide.velocity, offset))
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

        // Facing east, along the runway, if it goes anywhere along the
        // ground: a plane, and a rover built standing up. Left to the turn
        // that stood it upright, a rover's heading depended on where on the
        // planet it stood - at the old Cape it happened to face east, at the
        // new one straight off the side of the pad into the sea. A rocket
        // or a lander has no front to face with, and keeps the turn.
        if (orientation == CraftOrientation.VERTICAL &&
            vessel.defs.none { it.module<com.rm.apogee.core.part.Wheel>() != null }
        ) return

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

            is Command.SetSasMode -> waken(command.vessel)?.let {
                it.control.sasMode = command.mode
                // A new mode is a new hold: start it from where the craft is.
                it.assistHolding = false
            }

            is Command.SetNavFrame -> waken(command.vessel)?.control?.navFrame = command.frame

            is Command.SetTarget -> waken(command.vessel)?.control?.target =
                if (command.target == command.vessel) -1L else command.target

            is Command.SetBrakes ->
                waken(command.vessel)?.control?.brakes = command.engaged
            is Command.SetReverse ->
                waken(command.vessel)?.control?.reverse = command.engaged

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
            is Command.Anchor -> vesselsById[VesselId(command.vessel)]?.let { if (command.anchored) anchor(it) else unanchor(it) }
            is Command.Refuel -> if (command.active) refuelling.add(VesselId(command.vessel)) else refuelling.remove(VesselId(command.vessel))
            is Command.Undock -> waken(command.vessel)?.let { undock(it, command.part) }
            is Command.SetDockPilot -> Unit // the server's: who may fly what

            // Handled by the server, which owns the notion of who is flying
            // what. Reaching the world means nobody was listening.
            is Command.SwitchVessel -> waken(command.vessel)

            is Command.Chat -> Unit // handled above the world
            is Command.SetWarp -> Unit // the server's clock, not the world's
            is Command.RemoveVessel -> destroy(VesselId(command.vessel), "removed")
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
            // The Cape's own buildings are nobody's to weld to.
            if (other.owner == WORLD_OWNER || vessel.owner == WORLD_OWNER) continue
            // Two halves just parted, or one still pushing the other.
            if (justSeparated.containsKey(pairKey(vessel.id.raw, other.id.raw))) continue

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
    fun join(keeper: Vessel, absorbed: Vessel, dock: DockJoin? = null): Vessel? {
        if (keeper.id == absorbed.id) return null
        if (keeper.referenceBodyId != absorbed.referenceBodyId) return null
        // A base takes in what joins it, never the other way: it stays where
        // it is founded, and what came is fixed to it where it stands.
        if (absorbed.anchored && !keeper.anchored) {
            return join(absorbed, keeper, dock?.let { DockJoin(it.absorbedPart, it.keeperPart) })
        }

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

        val merged = mergeDesigns(keeper, absorbed, dock) ?: return null
        val mergedDefs = merged.parts.map { catalog.require(it.partId) }
        keeper.absorb(merged, mergedDefs, absorbed)

        keeper.body.position.setTo(combinedCentre)
        keeper.body.linearVelocity.setTo(velocity)
        val inverseInertiaWorld = com.rm.apogee.core.math.Mat3()
            .setRotated(keeper.body.inverseInertiaLocal, keeper.body.orientation)
        inverseInertiaWorld.transform(angularMomentum, keeper.body.angularVelocity)

        vesselsById.remove(absorbed.id)
        docking.forget(absorbed.id.raw)
        docking.forget(keeper.id.raw)
        links.removeAll { it.a == absorbed.id || it.b == absorbed.id }
        pendingEvents.add(WorldEvent.VesselDestroyed(absorbed.id, if (dock != null) "docked to ${keeper.name}" else "joined to ${keeper.name}"))
        pendingEvents.add(WorldEvent.VesselStructureChanged(keeper.id))
        reseat(keeper)
        return keeper
    }

    // --- anchoring ------------------------------------------------------------

    /**
     * Founds [vessel] where it rests: pinned to the ground from now on, never
     * woken, immovable to anything that strikes it - though its parts still
     * break. Only a craft with a working [com.rm.apogee.core.part.Foundation],
     * on the ground and still. False, and nothing changed, otherwise.
     */
    fun anchor(vessel: Vessel): Boolean {
        if (vessel.anchored) return true
        if (!canAnchor(vessel)) return false
        val attractor = attractorFor(vessel)
        attractor.rotationAt(tickEnd, anchorRotation)
        if (!level(vessel, attractor)) return false
        vessel.anchor(anchorRotation)
        vessel.powerSettledAt = tickEnd
        attractor.surfaceVelocityAt(vessel.body.position, vessel.body.linearVelocity)
        attractor.angularVelocity(vessel.body.angularVelocity)
        pendingEvents.add(WorldEvent.VesselStructureChanged(vessel.id))
        return true
    }

    /** Whether [vessel] could be founded where it is now: see [anchor]. */
    fun canAnchor(vessel: Vessel): Boolean {
        if (vessel.anchored) return false
        val footed = vessel.defs.indices.any { vessel.defs[it].module<com.rm.apogee.core.part.Foundation>() != null && !vessel.isBroken(it) }
        if (!footed) return false
        if (vessel.dormant) { if (vessel.afloat) return false }
        else {
            if (!vessel.touchingGround) return false
            attractorFor(vessel).surfaceVelocityAt(vessel.body.position, scratch)
            if (scratch.subInPlace(vessel.body.linearVelocity).length >= ANCHOR_MAX_SPEED) return false
        }
        // Standing on its feet: a foundation still on the flatbed that
        // brought it is not on the ground, whatever the truck is.
        return lowestFoot(vessel) <= FOOT_ON_GROUND
    }

    /** How far the lowest foot of any working foundation of [vessel] stands above the ground, m. */
    private fun lowestFoot(vessel: Vessel): Double {
        val attractor = attractorFor(vessel)
        attractor.rotationAt(tickEnd, anchorRotation)
        var lowest = Double.MAX_VALUE
        val point = Vec3()
        val direction = Vec3()
        for (i in vessel.defs.indices) {
            val def = vessel.defs[i]
            if (def.module<com.rm.apogee.core.part.Foundation>() == null || vessel.isBroken(i)) continue
            val bottom = def.contactPoints.minOfOrNull { it.y } ?: continue
            for (p in def.contactPoints.indices) {
                if (def.contactPoints[p].y > bottom + 1e-6) continue
                vessel.contactPointWorld(i, p, point)
                attractor.toBodyFixed(point, anchorRotation, direction).normalizeInPlace()
                lowest = minOf(lowest, point.length - attractor.solidRadiusInBodyFrame(direction))
            }
        }
        return lowest
    }

    /**
     * Stands [vessel] level on its foundations' feet before it is founded:
     * turned upright about its centre - no further than the steepest ground
     * its foundations will take - and set down on its lowest-standing foot,
     * the rest reaching down to the ground within their travel. False, and
     * the craft left as it was, where the ground is too steep or too uneven.
     */
    private fun level(vessel: Vessel, attractor: CelestialBody): Boolean {
        val body = vessel.body
        var maxSlope = Double.MAX_VALUE
        var travel = Double.MAX_VALUE
        for (i in vessel.defs.indices) {
            val f = vessel.defs[i].module<com.rm.apogee.core.part.Foundation>() ?: continue
            if (vessel.isBroken(i)) continue
            maxSlope = minOf(maxSlope, f.maxSlope)
            travel = minOf(travel, f.travel)
        }
        if (maxSlope == Double.MAX_VALUE) return false

        val up = Vec3().setTo(body.position).normalizeInPlace()
        val craftUp = body.orientation.rotate(vessel.design.orientation.up, Vec3())
        val tilt = Math.toDegrees(kotlin.math.acos((craftUp dot up).coerceIn(-1.0, 1.0)))
        if (tilt > maxSlope) return false
        val oldOrientation = body.orientation.copy()
        val oldPosition = body.position.copy()
        body.orientation.setTo(com.rm.apogee.core.math.quatFromTo(craftUp, up) * body.orientation).normalizeInPlace()

        // Each foot: the lowest points of each foundation, and the ground under it.
        var lowest = Double.MAX_VALUE
        var highest = -Double.MAX_VALUE
        val point = Vec3()
        val direction = Vec3()
        for (i in vessel.defs.indices) {
            val def = vessel.defs[i]
            if (def.module<com.rm.apogee.core.part.Foundation>() == null || vessel.isBroken(i)) continue
            val bottom = def.contactPoints.minOfOrNull { it.y } ?: continue
            for (p in def.contactPoints.indices) {
                if (def.contactPoints[p].y > bottom + 1e-6) continue
                vessel.contactPointWorld(i, p, point)
                attractor.toBodyFixed(point, anchorRotation, direction).normalizeInPlace()
                val clearance = point.length - attractor.solidRadiusInBodyFrame(direction)
                lowest = minOf(lowest, clearance)
                highest = maxOf(highest, clearance)
            }
        }
        if (lowest == Double.MAX_VALUE || highest - lowest > travel) {
            body.orientation.setTo(oldOrientation)
            body.position.setTo(oldPosition)
            return false
        }
        // Down (or up) until the foot that stands highest meets the ground.
        body.position.addScaledInPlace(up, -lowest)
        return true
    }

    // --- refuelling ----------------------------------------------------------------

    /** Craft being filled from a base, by id. */
    private val refuelling = LinkedHashSet<VesselId>()

    /** Whether [vessel] is being filled from a base now. */
    fun isRefuelling(vessel: VesselId): Boolean = vessel in refuelling

    /** Where a craft is filled from: the [base], the parts of it that give, and the [craft] parts that take. */
    class Service(val base: Vessel, val from: List<Int>, val craft: Vessel, val into: List<Int>)

    /**
     * What [craft] could be filled from now: the base whose pad deck it
     * stands on, or - docked to a base, and so one craft with it - the rest
     * of that base. Null if neither.
     */
    fun serviceFor(craft: Vessel): Service? {
        if (craft.anchored) {
            // Docked: what came is what hangs from each ring that docked on.
            val into = LinkedHashSet<Int>()
            for (i in craft.design.parts.indices) {
                if (craft.design.parts[i].dockedFrom != null) into.addAll(craft.design.subtreeOf(i))
            }
            if (into.isEmpty()) return null
            return Service(craft, craft.defs.indices.filter { it !in into }, craft, into.toList())
        }
        val up = Vec3()
        val offset = Vec3()
        for (base in vesselsById.values) {
            if (!base.anchored || base.referenceBodyId != craft.referenceBodyId) continue
            scratch.setTo(base.body.position).subInPlace(craft.body.position)
            val reach = base.contactRadius + craft.contactRadius
            if (scratch.lengthSq > reach * reach) continue
            for (i in base.defs.indices) {
                val pad = base.defs[i]
                if (pad.module<com.rm.apogee.core.part.LaunchPad>() == null || base.isBroken(i)) continue
                val centre = base.partPositionWorld(i, Vec3())
                base.design.parts[i].rotation.rotate(Vec3.unitY(), up)
                base.body.orientation.rotate(up, up)
                val top = pad.boundsHalfExtents.y
                offset.setTo(craft.body.position).subInPlace(centre)
                val height = offset dot up
                offset.addScaledInPlace(up, -height)
                // Over the deck, and standing on it: its lowest point near the top.
                if (offset.length > pad.boundsHalfExtents.x) continue
                if (height - lowestExtentAlong(craft, up) - top > PAD_SERVICE_HEIGHT) continue
                return Service(base, base.defs.indices.toList(), craft, craft.defs.indices.toList())
            }
        }
        return null
    }

    /** Whether [craft] could be filled from a base just now: standing on or docked to one, with room for something it has. */
    fun canRefuel(craft: Vessel): Boolean {
        val service = serviceFor(craft) ?: return false
        val endless = service.base.owner == WORLD_OWNER
        return REFUEL_TYPES.any { type ->
            service.craft.roomIn(service.into, type) > 1e-6 && (endless || service.base.amountIn(service.from, type) > 1e-6)
        }
    }

    private fun stepRefuelling(dt: Double) {
        if (refuelling.isEmpty()) return
        val iterator = refuelling.iterator()
        while (iterator.hasNext()) {
            val id = iterator.next()
            val craft = vesselsById[id]
            val service = craft?.let { serviceFor(it) }
            val stop = when {
                craft == null -> null
                service == null -> "nothing to fill from here"
                else -> pump(service, dt)
            }
            if (craft == null || stop != null) {
                iterator.remove()
                if (craft != null) pendingEvents.add(WorldEvent.RefuelStopped(id, stop!!))
            }
        }
    }

    /** One tick's pumping for [service]; why it stopped, or null while it goes on. */
    private fun pump(service: Service, dt: Double): String? {
        val base = service.base
        var rate = 0.0
        var draw = 0.0
        for (i in service.from) {
            val pump = base.defs[i].module<com.rm.apogee.core.part.Pump>() ?: continue
            if (base.isBroken(i)) continue
            if (pump.rate > rate) { rate = pump.rate; draw = pump.draw }
        }
        if (rate <= 0.0) return "this base has no pump"
        // The world's own - the Cape, Luna's test base - never run dry or dark.
        val endless = base.owner == WORLD_OWNER
        if (!endless && (!base.powered || !base.drawCharge(draw * dt))) return "the base has no power"
        var moved = 0.0
        for (type in REFUEL_TYPES) {
            val want = minOf(rate * dt, service.craft.roomIn(service.into, type), if (endless) Double.MAX_VALUE else base.amountIn(service.from, type))
            if (want <= 1e-9) continue
            val out = if (endless) want else base.takeFrom(service.from, type, want)
            moved += service.craft.putInto(service.into, type, out)
        }
        if (moved <= 1e-9) {
            // Full of everything the base has to give, or the base out of what the craft still wants.
            val wanting = REFUEL_TYPES.any { service.craft.roomIn(service.into, it) > 1e-9 && base.amountIn(service.from, it) > 1e-9 }
            val holds = REFUEL_TYPES.any { base.amountIn(service.from, it) > 1e-9 }
            return if (!wanting && holds) "full" else "the base has nothing more to give"
        }
        base.recomputeMass()
        if (service.craft !== base) service.craft.recomputeMass()
        return null
    }

    // --- the Cape's own buildings --------------------------------------------------

    /**
     * Puts up any of the Cape's buildings that are missing: the launch
     * complex, the airfield and the harbour, each a founded base of the
     * world's. A new world, and one saved before they existed, both get
     * them; one that has them keeps the ones it has.
     */
    fun ensureStructures() {
        if (SolarSystem.HOMEWORLD_ID !in system.bodies) return
        capeBuilt = true
        for (complex in com.rm.apogee.core.craft.StockStructures.complexes) {
            if (structureOf(complex) != null) continue
            raiseStructure(complex)
        }
        if (lunaBase() == null) raiseLunaBase()
    }

    // --- Luna's test base ------------------------------------------------------------

    /**
     * A pad base on the Luna Mare test site, the world's: somewhere on Luna
     * to launch from and refuel at while testing, without flying there. Its
     * stores never run dry nor its power out, as the Cape's do not. For
     * testing - a career would take it away.
     */
    fun lunaBase(): Vessel? = vesselsById.values.firstOrNull { it.owner == WORLD_OWNER && it.name == LUNA_BASE_NAME && it.anchored }

    private fun raiseLunaBase(): Vessel? {
        val site = launchSites.firstOrNull { it.id == LUNA_TEST_SITE } ?: return null
        if (site.bodyId !in system.bodies) return null
        val base = spawnOnSurface(com.rm.apogee.core.craft.StockCraft.padBase(catalog), site, pad = LUNA_BASE_PAD)
        base.owner = WORLD_OWNER
        base.ownerName = ""
        base.name = LUNA_BASE_NAME
        val attractor = attractorFor(base)
        attractor.rotationAt(tickEnd, anchorRotation)
        level(base, attractor)
        base.anchor(anchorRotation)
        return base
    }

    /** Whether this world has the Cape's buildings to look after: see [ensureStructures]. */
    private var capeBuilt = false

    /** Since when nothing awake has been near each complex, by name: see [repairStructures]. */
    private val quietSince = HashMap<String, Double>()

    /**
     * Rebuilds any of the Cape's buildings that have been broken - parts
     * lost or hurt - once nothing awake has come within [REPAIR_REACH] of
     * them for [REPAIR_QUIET] seconds, or at once when [now]: wreckage
     * cleared, the complex put back as it was built. Nobody watches it
     * happen, and the start of the game is never left in ruins.
     */
    fun repairStructures(now: Boolean = false) {
        if (!capeBuilt) return
        val body = system.body(SolarSystem.HOMEWORLD_ID)
        body.rotationAt(time, scratchRotation)
        val here = Vec3()
        for (complex in com.rm.apogee.core.craft.StockStructures.complexes) {
            val site = SolarSystem.capeDirection(complex.east, complex.north, body.radius).mulInPlace(body.radius)
            // Anything awake close by keeps it as it is, broken or not.
            val busy = vesselsById.values.any { v ->
                !v.dormant && v.owner != WORLD_OWNER && v.referenceBodyId == body.id &&
                    body.toBodyFixed(v.body.position, scratchRotation, here).distanceTo(site) < REPAIR_REACH
            }
            if (busy) { quietSince.remove(complex.name); continue }
            val since = quietSince.getOrPut(complex.name) { time }
            val standing = structureOf(complex)
            if (standing != null && intact(standing, complex)) continue
            if (!now && time - since < REPAIR_QUIET) continue
            // What fell off it, lying about: every craft of nothing but buildings, loose, near it.
            val wreckage = vesselsById.values.filter { v ->
                !v.anchored && v.referenceBodyId == body.id &&
                    v.defs.all { it.category == com.rm.apogee.core.part.PartCategory.STRUCTURE } &&
                    body.toBodyFixed(v.body.position, scratchRotation, here).distanceTo(site) < REPAIR_REACH
            }
            standing?.let { destroy(it.id, "rebuilt") }
            for (piece in wreckage) destroy(piece.id, "cleared away")
            raiseStructure(complex)
        }
        repairLunaBase(now)
    }

    /** Luna's test base put back if broken - once nothing awake is near it, or at once when [now]. */
    private fun repairLunaBase(now: Boolean) {
        val site = launchSites.firstOrNull { it.id == LUNA_TEST_SITE } ?: return
        if (site.bodyId !in system.bodies) return
        val body = system.body(site.bodyId)
        body.rotationAt(time, scratchRotation)
        val at = SolarSystem.surfaceDirection(site.latitude, site.longitude).mulInPlace(body.radius)
        val here = Vec3()
        val busy = vesselsById.values.any { v ->
            !v.dormant && v.owner != WORLD_OWNER && v.referenceBodyId == body.id &&
                body.toBodyFixed(v.body.position, scratchRotation, here).distanceTo(at) < REPAIR_REACH
        }
        if (busy) { quietSince.remove(LUNA_BASE_NAME); return }
        val since = quietSince.getOrPut(LUNA_BASE_NAME) { time }
        val standing = lunaBase()
        val whole = standing != null && standing.defs.size == com.rm.apogee.core.craft.StockCraft.padBase(catalog).parts.size &&
            standing.broken.none { it } && standing.health.all { it >= 1.0 }
        if (whole) return
        if (!now && time - since < REPAIR_QUIET) return
        standing?.let { destroy(it.id, "rebuilt") }
        raiseLunaBase()
    }

    /** Whether [standing] is all of [complex], and whole. */
    private fun intact(standing: Vessel, complex: com.rm.apogee.core.craft.StockStructures.Complex): Boolean =
        standing.defs.size == complex.placements.size && standing.broken.none { it } && standing.health.all { it >= 1.0 }

    /** The world's standing copy of [complex], if it has one. */
    fun structureOf(complex: com.rm.apogee.core.craft.StockStructures.Complex): Vessel? =
        vesselsById.values.firstOrNull { it.owner == WORLD_OWNER && it.name == complex.name && it.anchored }

    /** [complex] built where it belongs, founded and the world's. */
    private fun raiseStructure(complex: com.rm.apogee.core.craft.StockStructures.Complex): Vessel {
        val design = com.rm.apogee.core.craft.StockStructures.design(complex, catalog)
        val body = system.body(SolarSystem.HOMEWORLD_ID)
        val up = SolarSystem.capeDirection(complex.east, complex.north, body.radius)
        val ground = body.radius + (body.terrain?.elevation(up) ?: 0.0)
        // Its axes on the ground there: +X east, +Y up, +Z south.
        val east = Vec3(0.0, 1.0, 0.0).crossInPlace(up).normalizeInPlace()
        val standing = com.rm.apogee.core.math.quatFromTo(Vec3.unitY(), up)
        val xNow = standing.rotate(Vec3.unitX(), Vec3())
        val heading = if ((xNow dot east) < -0.999999) Quat.fromAxisAngle(up, Math.PI) else com.rm.apogee.core.math.quatFromTo(xNow, east)
        val local = (heading * standing).normalizeInPlace()

        body.rotationAt(time, scratchRotation)
        val rotation = (scratchRotation * local).normalizeInPlace()
        val origin = scratchRotation.rotate(Vec3().setTo(up).mulInPlace(ground), Vec3())
        val probe = spawnAt(design, body.id, origin, Vec3(), rotation)
        // Its centre where its centre is: the design's origin on the ground.
        probe.body.position.setTo(origin).addInPlace(rotation.rotate(probe.centerOfMass(Vec3()), Vec3()))
        body.surfaceVelocityAt(probe.body.position, probe.body.linearVelocity)
        probe.owner = WORLD_OWNER
        probe.ownerName = ""
        probe.name = complex.name
        pin(probe)
        return probe
    }

    // --- launching from bases -----------------------------------------------------

    /** Whether a player [owner] may launch from [base]: their own, or one of the world's. */
    fun mayLaunchFrom(base: Vessel, owner: String): Boolean = base.owner == owner || base.owner == WORLD_OWNER

    /** The pads [owner] may launch from on founded bases, as launch sites. */
    fun baseSites(owner: String): List<LaunchSite> {
        val sites = ArrayList<LaunchSite>()
        val direction = Vec3()
        for (base in vesselsById.values) {
            if (!base.anchored || !mayLaunchFrom(base, owner)) continue
            val pads = base.defs.indices.filter { base.defs[it].module<com.rm.apogee.core.part.LaunchPad>() != null && !base.isBroken(it) }
            val attractor = attractorFor(base)
            attractor.rotationAt(time, scratchRotation)
            pads.forEachIndexed { k, pad ->
                attractor.toBodyFixed(base.partPositionWorld(pad), scratchRotation, direction).normalizeInPlace()
                sites.add(
                    LaunchSite(
                        id = "${LaunchSite.BASE_SITE_PREFIX}${base.id.raw}:$pad",
                        displayName = if (pads.size > 1) "${base.name}, pad ${k + 1}" else base.name,
                        bodyId = base.referenceBodyId,
                        latitude = kotlin.math.asin(direction.y.coerceIn(-1.0, 1.0)),
                        longitude = kotlin.math.atan2(direction.z, direction.x),
                    )
                )
            }
        }
        return sites
    }

    /**
     * A craft set upright on the deck of [base]'s pad [pad], its tanks filled
     * from the base's stores as far as they go - if the base has the power
     * to pump; dark, it is put there empty.
     */
    fun spawnOnBasePad(design: CraftDesign, base: Vessel, pad: Int): Vessel {
        val site = baseSites(base.owner).firstOrNull { it.id == "${LaunchSite.BASE_SITE_PREFIX}${base.id.raw}:$pad" }
            ?: error("no pad $pad on ${base.name}")
        val craft = spawnOnSurface(design, site)
        // On the deck, not on the ground under it.
        val up = Vec3().setTo(craft.body.position).normalizeInPlace()
        val top = Vec3().setTo(base.partPositionWorld(pad)).addScaledInPlace(up, base.defs[pad].boundsHalfExtents.y)
        craft.body.position.setTo(up).mulInPlace(top.length + lowestExtentAlong(craft, up) + 0.02)
        attractorFor(craft).surfaceVelocityAt(craft.body.position, craft.body.linearVelocity)

        // What it carries, it carries from the base.
        settlePower(base)
        val all = craft.defs.indices.toList()
        // The world's own bases supply it whole, as the Cape does.
        if (base.owner == WORLD_OWNER) return craft
        val launch = base.defs[pad].module<com.rm.apogee.core.part.LaunchPad>()!!.launchCharge
        val pumping = base.powered && base.drawCharge(launch)
        for (type in listOf(com.rm.apogee.core.part.ResourceType.PROPELLANT, com.rm.apogee.core.part.ResourceType.MONOPROPELLANT)) {
            val wanted = craft.amountIn(all, type)
            craft.takeFrom(all, type, wanted)
            if (!pumping) continue
            val given = base.takeFrom(base.defs.indices.toList(), type, wanted)
            craft.putInto(all, type, given)
        }
        base.recomputeMass()
        craft.recomputeMass()
        return craft
    }

    // --- power --------------------------------------------------------------------

    /**
     * Brings a founded base's power up to [until]: its panels' charge in, by
     * how high the sun has stood over it; its core's upkeep and, after dusk,
     * its lamps' out - over the time since it was last worked out, in steps
     * of a minute or, across a long absence, as many as [POWER_MAX_STEPS].
     * Nobody need be near: a base keeps its ledger, not its ticks.
     */
    fun settlePower(vessel: Vessel, until: Double = time) {
        if (!vessel.anchored) return
        val from = vessel.powerSettledAt
        vessel.powerSettledAt = until
        if (from.isNaN() || until <= from) return
        val attractor = attractorFor(vessel)
        var solar = 0.0
        var upkeep = 0.0
        var lamps = 0.0
        for (i in vessel.defs.indices) {
            if (vessel.isBroken(i)) continue
            for (module in vessel.defs[i].modules) when (module) {
                is com.rm.apogee.core.part.SolarPanel -> solar += module.chargeRate
                is com.rm.apogee.core.part.Command -> upkeep += BASE_UPKEEP
                is com.rm.apogee.core.part.Lamp -> lamps += module.draw
                else -> Unit
            }
        }
        val site = vessel.sleepDirection(powerSite)
        val step = maxOf(POWER_STEP, (until - from) / POWER_MAX_STEPS)
        var t = from
        var net = 0.0
        while (t < until) {
            val h = minOf(step, until - t)
            val sun = sunHeight(attractor, site, t + 0.5 * h)
            net = solar * maxOf(0.0, sun) - upkeep - if (sun < LAMP_DUSK) lamps else 0.0
            if (net >= 0.0) vessel.storeCharge(net * h)
            else if (!vessel.drawCharge(-net * h)) vessel.drawCharge(vessel.amountOf(com.rm.apogee.core.part.ResourceType.ELECTRIC_CHARGE))
            t += h
        }
        vessel.powerNet = net
        vessel.powered = vessel.amountOf(com.rm.apogee.core.part.ResourceType.ELECTRIC_CHARGE) > 0.0 || net > 0.0
    }

    /** How high the sun stands over body-fixed unit [site] at [at]: the sine of its elevation, below 0 at night. */
    fun sunHeight(attractor: CelestialBody, site: Vec3, at: Double): Double {
        attractor.rotationAt(at, powerRotation)
        powerRotation.rotate(site, powerUp)
        return powerUp dot LaunchTime.SUN_DIRECTION
    }

    private val powerSite = Vec3()
    private val powerUp = Vec3()
    private val powerRotation = Quat.identity()

    /** Lets a founded [vessel] go: a craft like any other again, awake. */
    fun unanchor(vessel: Vessel): Boolean {
        if (!vessel.anchored) return false
        vessel.unanchor()
        pendingEvents.add(WorldEvent.VesselStructureChanged(vessel.id))
        return true
    }

    /** An anchored craft's pose taken afresh after its structure - and so its centre of mass - changed. */
    private fun reseat(vessel: Vessel) {
        if (!vessel.anchored) return
        attractorFor(vessel).rotationAt(tickEnd, anchorRotation)
        vessel.reanchor(anchorRotation)
    }

    private val anchorRotation = Quat.identity()

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
    private fun mergeDesigns(keeper: Vessel, absorbed: Vessel, dock: DockJoin? = null): CraftDesign? {
        val offset = keeper.design.parts.size
        val parts = ArrayList<PlacedPart>(offset + absorbed.design.parts.size)
        parts.addAll(keeper.design.parts)

        // The part of the keeper nearest the absorbed craft becomes the parent
        // of its root, so the tree stays connected and staging still has
        // something to walk. Docked, its ring is the joint instead: the
        // absorbed craft is re-rooted at its own ring and hung from the
        // keeper's, so undocking is cutting that one joint.
        val anchor = dock?.keeperPart ?: nearestPartTo(keeper, absorbed.body.position)
        val absorbedDesign = if (dock != null) absorbed.design.rerootedAt(dock.absorbedPart) else absorbed.design

        val worldPoint = Vec3()
        for ((index, placed) in absorbedDesign.parts.withIndex()) {
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
                    parentNodeId = if (placed.parentIndex < 0) null else placed.parentNodeId,
                    ownNodeId = if (placed.parentIndex < 0) null else placed.ownNodeId,
                    symmetryGroup = -1,
                    dockedTo = if (placed.dockedTo >= 0) placed.dockedTo + offset else -1,
                )
            )
        }
        if (dock != null) {
            // Each ring knows its partner; the one that came knows what it came as.
            parts[dock.keeperPart] = parts[dock.keeperPart].copy(dockedTo = offset + dock.absorbedPart, dockedFrom = null)
            parts[offset + dock.absorbedPart] = parts[offset + dock.absorbedPart].copy(
                dockedTo = dock.keeperPart,
                dockedFrom = com.rm.apogee.core.craft.DockedOrigin(
                    name = absorbed.name, owner = absorbed.owner, ownerName = absorbed.ownerName,
                    orientation = absorbed.design.orientation, currentStage = absorbed.currentStage,
                    throttle = absorbed.control.throttle,
                ),
            )
        }

        val stages = keeper.design.stages +
            absorbed.design.stages.map { stage ->
                com.rm.apogee.core.craft.Stage(stage.activatedParts.map { it + offset })
            }

        // The keeper's design, extended: built lying down, it stays lying down.
        return keeper.design.copy(parts = parts, stages = stages)
    }

    /** Everyone with a claim on [vessel]: its owner, and the owner of each craft docked into it. */
    fun ownersOf(vessel: Vessel): Set<String> =
        (listOf(vessel.owner) + vessel.design.parts.mapNotNull { it.dockedFrom?.owner }).filter { it.isNotBlank() }.toSet()

    /** Lets [a] and [b] touch only gently for [seconds]: two halves just parted, as a replica first sees them. */
    fun graceBetween(a: VesselId, b: VesselId, seconds: Double) {
        justSeparated[pairKey(a.raw, b.raw)] = time + seconds
    }

    /** A docking join: which part of each craft is the ring they latched by. */
    class DockJoin(val keeperPart: Int, val absorbedPart: Int)

    /**
     * Docking, each tick after craft have moved and met: the magnets pull,
     * captures latch into one craft or a coupled hitch, and coupled hitches
     * hold their two craft together.
     */
    private fun stepDocking(dt: Double) {
        docking.step(
            vesselsById.values, dt,
            ignore = { a, b -> justSeparated.containsKey(pairKey(a, b)) || linked(a, b) },
            occupied = { v, i -> links.any { (it.a == v.id && it.partA == i) || (it.b == v.id && it.partB == i) } },
        )
        for (capture in ArrayList(docking.latching)) {
            val a = capture.a; val b = capture.b
            if (a.vessel.id !in vesselsById || b.vessel.id !in vesselsById) continue
            if (a.port.rigid) dockPorts(a.vessel, a.index, b.vessel, b.index)
            else couple(a.vessel, a.index, b.vessel, b.index)
        }
        solveLinks(dt)
    }

    /**
     * Latches two rings: the lighter craft docks on to the heavier, set
     * square on its ring - the last centimetres and degrees the magnets
     * left - and the two become one craft.
     */
    fun dockPorts(a: Vessel, partA: Int, b: Vessel, partB: Int): Vessel? {
        // The heavier keeps - or a base, whatever it weighs.
        val (keeper, keeperPart, absorbed, absorbedPart) =
            if (a.anchored || (!b.anchored && a.body.mass >= b.body.mass)) Quad(a, partA, b, partB) else Quad(b, partB, a, partA)
        val kp = keeper.defs[keeperPart].module<com.rm.apogee.core.part.DockingPort>() ?: return null
        val ap = absorbed.defs[absorbedPart].module<com.rm.apogee.core.part.DockingPort>() ?: return null
        // Square the absorbed craft up on the keeper's ring.
        val keeperRef = com.rm.apogee.core.physics.PortRef(keeper, keeperPart, kp).update()
        val absorbedRef = com.rm.apogee.core.physics.PortRef(absorbed, absorbedPart, ap).update()
        val turn = com.rm.apogee.core.math.quatFromTo(absorbedRef.axis, Vec3().setTo(keeperRef.axis).mulInPlace(-1.0))
        absorbed.body.orientation.setTo(turn * absorbed.body.orientation).normalizeInPlace()
        absorbedRef.update()
        absorbed.body.position.addInPlace(Vec3().setTo(keeperRef.face).subInPlace(absorbedRef.face))
        val at = keeperRef.face.copy()
        // Nobody's craft taken in hand by somebody's: it is theirs now.
        if (keeper.owner.isBlank() && absorbed.owner.isNotBlank()) {
            keeper.owner = absorbed.owner
            keeper.ownerName = absorbed.ownerName
        }
        val joined = join(keeper, absorbed, DockJoin(keeperPart, absorbedPart)) ?: return null
        pendingEvents.add(WorldEvent.Docked(keeper.id, absorbed.id, at, keeper.referenceBodyId))
        return joined
    }

    private data class Quad(val a: Vessel, val ia: Int, val b: Vessel, val ib: Int)

    /** Couples a hitch: the two craft stay two, turning about the one point. */
    private fun couple(a: Vessel, partA: Int, b: Vessel, partB: Int) {
        docking.forget(a.id.raw); docking.forget(b.id.raw)
        links.add(Link(a.id, partA, b.id, partB))
        val ref = com.rm.apogee.core.physics.PortRef(a, partA, a.defs[partA].module<com.rm.apogee.core.part.DockingPort>()!!).update()
        pendingEvents.add(WorldEvent.Hitched(a.id, b.id, coupled = true, ref.face.copy(), a.referenceBodyId))
    }

    /**
     * Lets go of whatever docking part [part] of [vessel] holds: a ring or
     * clamp undocks - the craft that docked on is given back its own name,
     * owner and staging, and the two are pushed gently apart - and a hitch
     * uncouples. False if it held nothing.
     */
    fun undock(vessel: Vessel, part: Int): Boolean {
        if (part !in vessel.defs.indices) return false
        links.firstOrNull { (it.a == vessel.id && it.partA == part) || (it.b == vessel.id && it.partB == part) }?.let { link ->
            links.remove(link)
            vesselsById[link.a]?.wake(); vesselsById[link.b]?.wake()
            justSeparated[pairKey(link.a.raw, link.b.raw)] = time + UNDOCK_GRACE
            pendingEvents.add(WorldEvent.Hitched(link.a, link.b, coupled = false, vessel.partPositionWorld(part), vessel.referenceBodyId))
            return true
        }
        val partner = vessel.design.parts[part].dockedTo
        if (partner !in vessel.design.parts.indices) return false
        // The ring that came, hung from the one it came to.
        val child = if (vessel.design.parts[part].parentIndex == partner) part else partner
        val parent = vessel.design.parts[child].parentIndex
        val origin = vessel.design.parts[child].dockedFrom
        val port = vessel.defs[child].module<com.rm.apogee.core.part.DockingPort>()
        val ref = port?.let { com.rm.apogee.core.physics.PortRef(vessel, child, it).update() }
        // Clear the rings' hold on each other, then cut between them.
        val parts = vessel.design.parts.toMutableList()
        parts[child] = parts[child].copy(dockedTo = -1, dockedFrom = null)
        if (parent in parts.indices) parts[parent] = parts[parent].copy(dockedTo = -1, dockedFrom = null)
        vessel.redesign(vessel.design.copy(parts = parts))
        val spawned = splitOff(vessel, child, ref?.axis, port?.undockImpulse ?: 0.0) ?: return false
        if (origin != null) {
            spawned.name = origin.name
            spawned.owner = origin.owner
            spawned.ownerName = origin.ownerName
            spawned.redesign(spawned.design.copy(name = origin.name, orientation = origin.orientation))
            spawned.control.throttle = origin.throttle
        }
        justSeparated[pairKey(vessel.id.raw, spawned.id.raw)] = time + UNDOCK_GRACE
        vessel.wake(); spawned.wake()
        pendingEvents.add(WorldEvent.Undocked(vessel.id, spawned.id, ref?.face?.copy() ?: vessel.body.position.copy(), vessel.referenceBodyId))
        return true
    }

    /**
     * Splits the subtree under [root] off [vessel] as a craft of its own,
     * pushed away along [along] (world, the way the root's part faces - so
     * backward for the piece) with [impulse] N·s each way. The new craft.
     */
    private fun splitOff(vessel: Vessel, root: Int, along: Vec3?, impulse: Double): Vessel? {
        val separating = vessel.design.subtreeOf(root).toSet()
        val remaining = vessel.design.parts.indices.filter { it !in separating }
        if (separating.isEmpty() || remaining.isEmpty()) return null
        val position = vessel.body.position.copy()
        val orientation = vessel.body.orientation.copy()
        val angularVelocity = vessel.body.angularVelocity.copy()
        val stage = vessel.currentStage
        val off = buildSubDesign(vessel.design, separating.sorted(), firedStages = 0)
        val kept = buildSubDesign(vessel.design, remaining, firedStages = stage)
        val originalDefs = vessel.defs
        val centre = pieceCentre(vessel, off.indices, Vec3()).copy()
        val pointVelocity = vessel.body.velocityAtOffset(centre, Vec3())
        val piece = Vessel(
            id = VesselId(nextVesselId++),
            design = off.design,
            defs = off.indices.map { originalDefs[it] },
            referenceBodyId = vessel.referenceBodyId,
        )
        piece.inheritParts(vessel, off.indices)
        piece.control.throttle = vessel.control.throttle
        piece.body.orientation.setTo(orientation)
        piece.body.linearVelocity.setTo(pointVelocity)
        piece.body.angularVelocity.setTo(angularVelocity)
        piece.body.position.setTo(position).addInPlace(centre)
        piece.recomputeMass(shiftBodyPosition = false)
        // Its place in its own staging: past every stage all of whose parts have fired.
        piece.restoreStaging(
            piece.design.stages.indexOfFirst { st -> st.activatedParts.any { !piece.isActivated(it) } }
                .let { if (it < 0) piece.design.stages.size else it },
            piece.activatedIndices(), piece.brokenIndices(),
        )
        vessel.replaceStructure(kept.design, kept.indices.map { originalDefs[it] }, kept.indices)
        reseat(vessel)
        if (along != null && impulse > 0.0) {
            val push = Vec3().setTo(along).normalizeInPlace()
            // The ring faces from the piece toward the craft it was on.
            vessel.body.applyImpulse(Vec3().setTo(push).mulInPlace(impulse))
            piece.body.applyImpulse(Vec3().setTo(push).mulInPlace(-impulse))
        }
        vesselsById[piece.id] = piece
        pendingEvents.add(WorldEvent.VesselStructureChanged(vessel.id))
        pendingEvents.add(WorldEvent.VesselSpawned(piece.id))
        return piece
    }

    /**
     * Holds each coupled hitch together: a point joint, solved as impulses
     * on the two craft - the relative velocity of the two hitch points along
     * each axis taken out, plus a share of any gap - so the towed craft
     * follows and turns freely about the ball. Too hard a yank, and it
     * breaks.
     */
    private fun solveLinks(dt: Double) {
        if (links.isEmpty()) return
        val broken = ArrayList<Link>()
        for (link in links) {
            val a = vesselsById[link.a]; val b = vesselsById[link.b]
            if (a == null || b == null || link.partA !in a.defs.indices || link.partB !in b.defs.indices) { broken.add(link); continue }
            // Both awake, or both asleep: a craft towed never sleeps while its tug moves.
            if (!a.dormant || !b.dormant) { if (a.dormant) a.wake(); if (b.dormant) b.wake() }
            if (a.dormant && b.dormant) continue
            val pa = com.rm.apogee.core.physics.PortRef(a, link.partA, a.defs[link.partA].module<com.rm.apogee.core.part.DockingPort>()!!).update()
            val pb = com.rm.apogee.core.physics.PortRef(b, link.partB, b.defs[link.partB].module<com.rm.apogee.core.part.DockingPort>()!!).update()
            val gap = Vec3().setTo(pb.face).subInPlace(pa.face)
            var total = 0.0
            val axis = Vec3(); val va = Vec3(); val vb = Vec3(); val ra = Vec3(); val rb = Vec3()
            // Every pass aims at the same closing speed - a share of the gap
            // a tick - or the later passes undo the first's correction and a
            // gap, once opened, never closes.
            for (iteration in 0 until LINK_ITERATIONS) for (k in 0 until 3) {
                axis.setTo(if (k == 0) 1.0 else 0.0, if (k == 1) 1.0 else 0.0, if (k == 2) 1.0 else 0.0)
                a.body.velocityAtOffset(pa.offset, va); b.body.velocityAtOffset(pb.offset, vb)
                val rel = (vb dot axis) - (va dot axis)
                ra.setTo(pa.offset).crossInPlace(axis); rb.setTo(pb.offset).crossInPlace(axis)
                val inverse = a.body.inverseMass + b.body.inverseMass +
                    a.body.inverseInertiaAbout(ra.normalizedOrZero()) * ra.lengthSq + b.body.inverseInertiaAbout(rb.normalizedOrZero()) * rb.lengthSq
                if (inverse <= 0.0) continue
                val bias = LINK_STIFFNESS * (gap dot axis) / dt
                val j = -(rel + bias) / inverse
                b.body.applyImpulseAtOffset(Vec3().setTo(axis).mulInPlace(j), pb.offset)
                a.body.applyImpulseAtOffset(Vec3().setTo(axis).mulInPlace(-j), pa.offset)
                total += kotlin.math.abs(j)
            }
            val lighter = minOf(a.body.mass, b.body.mass)
            if (total / dt > LINK_BREAK_G * 9.81 * lighter) broken.add(link)
        }
        for (link in broken) {
            links.remove(link)
            val a = vesselsById[link.a]
            if (a != null && link.partA in a.defs.indices) {
                pendingEvents.add(WorldEvent.Hitched(link.a, link.b, coupled = false, a.partPositionWorld(link.partA), a.referenceBodyId))
            }
        }
    }

    private fun Vec3.normalizedOrZero(): Vec3 {
        val l = length
        return if (l < 1e-12) Vec3() else Vec3().setTo(this).mulInPlace(1.0 / l)
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
        val clamp = vessel.defs[decouplerIndex].module<Decoupler>()?.stays == true
        val separating = vessel.design.subtreeOf(decouplerIndex).toSet().let { if (clamp) it - decouplerIndex else it }
        val remaining = vessel.design.parts.indices.filter { it !in separating }
        if (separating.isEmpty() || remaining.isEmpty()) return null

        val ejection = vessel.defs[decouplerIndex].module<Decoupler>()?.ejectionImpulse ?: 0.0

        // Both halves need their world motion preserved, so capture it before
        // either design is rebuilt underneath them.
        val position = vessel.body.position.copy()
        val orientation = vessel.body.orientation.copy()
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

        // The half that falls away, as a new vessel: its own centre of mass
        // where that was, moving as that point of the craft was, and with
        // its parts as they were - what is left in its tanks, what it had
        // fired, how hurt and hot. Worked out from the whole craft, so before
        // the kept half is rebuilt.
        //
        // It was placed at the whole craft's centre instead - metres up the
        // stack, inside the stage it had just let go of - and built fresh,
        // tanks full. The halves then met again the moment the grace after
        // separating ran out, and stuck.
        val centre = pieceCentre(vessel, discarded.indices, Vec3()).copy()
        val pointVelocity = vessel.body.velocityAtOffset(centre, Vec3())
        val debris = Vessel(
            id = VesselId(nextVesselId++),
            design = discarded.design,
            defs = discardedDefs,
            referenceBodyId = vessel.referenceBodyId,
        )
        debris.inheritParts(vessel, discarded.indices)
        // Let go while burning, it goes on burning: the control module it
        // answered to is gone, but nothing ever told its engine to stop, so
        // it runs on at the throttle it had until its tanks are dry (Dan).
        debris.control.throttle = vessel.control.throttle
        debris.body.orientation.setTo(orientation)
        debris.body.linearVelocity.setTo(pointVelocity)
        debris.body.angularVelocity.setTo(angularVelocity)
        debris.body.position.setTo(position).addInPlace(centre)
        debris.recomputeMass(shiftBodyPosition = false)
        // A load set down, not a stage thrown away: still the owner's, and
        // named for what it is.
        if (clamp) {
            debris.owner = vessel.owner
            debris.ownerName = vessel.ownerName
            debris.name = discardedDefs.firstOrNull { it.hasModule<com.rm.apogee.core.part.Command>() }?.title
                ?: discardedDefs.maxByOrNull { it.dryMass }?.title ?: debris.name
        }

        // The half that keeps flying, at the same place in its sequence.
        vessel.replaceStructure(kept.design, keptDefs, kept.indices)
        reseat(vessel)
        if (clamp) {
            // Lifted off and set down beside the truck, not pushed away.
            setDownBeside(vessel, debris)
            vesselsById[debris.id] = debris
            justSeparated[pairKey(vessel.id.raw, debris.id.raw)] = time + SEPARATION_GRACE
            pendingEvents.add(WorldEvent.VesselStructureChanged(vessel.id))
            pendingEvents.add(WorldEvent.VesselSpawned(debris.id))
            return kept.indices
        }

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

    /**
     * [load] lifted off [truck] and set on the ground to its right: clear of
     * it by [SET_DOWN_CLEARANCE], stood upright the way its own design
     * stands, resting on its lowest part and moving with the ground.
     */
    private fun setDownBeside(truck: Vessel, load: Vessel) {
        val attractor = attractorFor(truck)
        val up = Vec3().setTo(truck.body.position).normalizeInPlace()
        val right = truck.body.orientation.rotate(Vec3(1.0, 0.0, 0.0), Vec3())
        right.addScaledInPlace(up, -(right dot up)).normalizeInPlace()
        // Upright, keeping its heading.
        val loadUp = load.body.orientation.rotate(load.design.orientation.up, Vec3())
        load.body.orientation.setTo(com.rm.apogee.core.math.quatFromTo(loadUp, up) * load.body.orientation).normalizeInPlace()
        // Out to the side by both their half-widths and a little more.
        val out = truck.contactRadius.coerceAtMost(halfWidth(truck, right)) + halfWidth(load, right) + SET_DOWN_CLEARANCE
        val centre = Vec3().setTo(truck.body.position).addScaledInPlace(right, out)
        attractor.rotationAt(time, scratchRotation)
        val direction = attractor.toBodyFixed(centre, scratchRotation, Vec3()).normalizeInPlace()
        val ground = attractor.solidRadiusInBodyFrame(direction)
        val along = Vec3().setTo(centre).normalizeInPlace()
        load.body.position.setTo(along).mulInPlace(ground + lowestExtentAlong(load, along) + 0.02)
        attractor.surfaceVelocityAt(load.body.position, load.body.linearVelocity)
        attractor.angularVelocity(load.body.angularVelocity)
    }

    /** How far [vessel] reaches from its centre along [direction], m: the furthest of its contact points. */
    private fun halfWidth(vessel: Vessel, direction: Vec3): Double {
        var most = 0.0
        val offset = Vec3()
        for (i in vessel.defs.indices) for (p in vessel.defs[i].contactPoints.indices) {
            vessel.contactOffsetWorld(i, p, offset)
            most = maxOf(most, offset dot direction)
        }
        return most
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
                dockedTo = if (part.dockedTo >= 0) remap[part.dockedTo] ?: -1 else -1,
                dockedFrom = part.dockedFrom.takeIf { part.dockedTo >= 0 && remap.containsKey(part.dockedTo) },
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
                if (vessel.afloat && !vessel.anchored) followSea(vessel, attractor, waves = true) else followGround(vessel, attractor)
                continue
            }

            body.clearAccumulators()
            vessel.clearForces()

            // Before any force, because the elevons deflect inside the drag
            // pass and the gimbal inside thrust: all of them act on what
            // stability assist asks for this tick.
            stabilityAssist.update(vessel, dt, holdDirection(vessel, attractor))
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
            forces.applyDrag(vessel, attractor, weather, weatherRotation, time, dt)
            for (i in 0 until forces.tornCount) {
                val index = forces.tornParachutes[i]
                pendingEvents.add(
                    WorldEvent.PartFailed(
                        vessel.id, index, "${vessel.defs[index].title} tore away",
                    )
                )
            }
            for (i in 0 until forces.overstressedCount) {
                detach(vessel, forces.overstressed[i], "snapped off under load")
            }
            hydrostatics.apply(vessel, attractor, time, dt)
            if (hydrostatics.splashCount > 0) {
                for (i in 0 until hydrostatics.splashCount) {
                    impactNormal.setTo(vessel.body.position).normalizeInPlace()
                    impact(vessel, hydrostatics.splashParts[i], hydrostatics.splashSpeeds[i], impactNormal, water = true)
                }
                pendingBreakUps.add(vessel.id)
            }
            forces.applyReactionWheels(vessel)
            forces.applyRcs(vessel, dt)
            stress.update(vessel, dt)
            for (i in 0 until stress.snappedCount) detach(vessel, stress.snapped[i], "tore off under load")
            heat.update(vessel, attractor, dt)
            vessel.hottest = heat.hottest
            vessel.hottestPart = heat.hottestPart
            if (heat.burntCount > 0) {
                pendingBreakUps.add(vessel.id)
                breakUpCause[vessel.id] = "burnt up"
            }

            // Integration and contact are subdivided together when the craft
            // is moving fast near the ground. Forces are not recomputed per
            // substep - they change far more slowly than the geometry does,
            // and recomputing thrust and drag eight times a tick would cost
            // more than the problem is worth.
            val substeps = contactSubsteps(vessel, attractor, dt)
            val h = dt / substeps
            for (substep in 0 until substeps) {
                body.integrate(h)
                // Against the ground where it is now the craft has moved on -
                // the end of this substep, not its start. The ground turns
                // with the planet, 175 m/s at the equator: a substep behind,
                // it stood 2.9 m back along the turn, which on flat ground
                // changes nothing and on a steep slope is metres up or down.
                // A pod landed on a mountainside came to rest two metres
                // inside it.
                contacts.resolve(vessel, attractor, h, time + (substep + 1) * h, substep > 0)
            }
            // Boulders and trunks, into the same report, so a craft wrecked on a
            // rock is judged the way one wrecked on the ground is.
            scatterContacts.resolve(vessel, attractor, time + dt, contacts.report, felledScatter) { fell(it) }
            val report = contacts.report
            vessel.touchingGround = report.hadContact
            vessel.groundContacts = report.contactCount
            vessel.countGrounded(report.hadContact, dt)

            // Mass changes as propellant burns, and with it the centre of mass.
            if (vessel.control.throttle > 0.0) vessel.recomputeMass()
            if (report.hadContact && report.worstImpactSpeed > TOUCHDOWN_REPORT_SPEED) {
                pendingEvents.add(WorldEvent.Touchdown(vessel.id, report.worstImpactSpeed))
            }
            if (report.impactCount > 0) {
                for (i in 0 until report.impactCount) {
                    impactNormal.setTo(report.impactNormals[i * 3], report.impactNormals[i * 3 + 1], report.impactNormals[i * 3 + 2])
                    impact(vessel, report.impactParts[i], report.impactSpeeds[i], impactNormal)
                }
                pendingBreakUps.add(vessel.id)
            }

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
            val struck = vesselsById[VesselId(impacts.vessels[i])] ?: continue
            impactNormal.setTo(impacts.normals[i * 3], impacts.normals[i * 3 + 1], impacts.normals[i * 3 + 2])
            impact(struck, impacts.parts[i], impacts.speeds[i], impactNormal)
            pendingBreakUps.add(struck.id)
        }
        stepDocking(dt)
        stepRefuelling(dt)
        resolveExplosions()
        if (pendingBreakUps.isNotEmpty() || pendingDetach.isNotEmpty()) {
            val ids = LinkedHashSet(pendingBreakUps).apply { addAll(pendingDetach.keys) }
            for (id in ids) vesselsById[id]?.let {
                breakUp(it, cause = breakUpCause[id] ?: "struck", detached = pendingDetach[id] ?: emptySet())
            }
            pendingBreakUps.clear()
            pendingDetach.clear()
            breakUpCause.clear()
        }

        if (fragmentExpiry.isNotEmpty() && tick % FRAGMENT_CHECK_TICKS == 0L) {
            val iterator = fragmentExpiry.entries.iterator()
            while (iterator.hasNext()) {
                val (id, at) = iterator.next()
                if (id !in vesselsById) iterator.remove()
                else if (at < time) {
                    iterator.remove()
                    pendingDestruction.add(id to "cleared away")
                }
            }
        }

        if (pendingDestruction.isNotEmpty()) {
            for ((id, reason) in pendingDestruction) destroy(id, reason)
            pendingDestruction.clear()
        }

        if (tick % LIGHTNING_CHECK_TICKS == 0L) strikeLightning(tickEnd)
        if (tick % REPAIR_CHECK_TICKS == 0L) repairStructures()
        if (tick % POWER_CHECK_TICKS == 0L) {
            for (vessel in vesselsById.values) if (vessel.anchored) settlePower(vessel, tickEnd)
        }

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
        // Nor while it is being drawn in to dock: held still short of the
        // latch by the ground's friction, asleep it would stay there.
        if (legsMoving(vessel) || docking.capturing(vessel.id.raw)) {
            vessel.noteStillness(false, SLEEP_SETTLE_TICKS)
            return
        }
        val still = report.anchored || floatingStill(vessel)
        if (vessel.noteStillness(still, SLEEP_SETTLE_TICKS)) {
            // The pose was just integrated to the end of the tick, so it is
            // pinned to the ground as the ground is then.
            val attractor = attractorFor(vessel)
            attractor.rotationAt(tickEnd, scratchRotation)
            vessel.sleep(scratchRotation)
            // Afloat, it rides the sea from here: note how it lies in it.
            val ocean = attractor.ocean
            if (!report.anchored && ocean != null && !vessel.touchingGround) {
                attractor.toBodyFixed(vessel.body.position, scratchRotation, scratchSeaPoint)
                ocean.sample(scratchSeaPoint, tickEnd, seaRide, spacing = riderSpacing(vessel))
                vessel.afloat = true
                vessel.draft = scratchSeaPoint.length - attractor.radius - seaRide.height
                vessel.sleepNormal.setTo(seaRide.normal)
            }
        }
    }

    private val seaRide = com.rm.apogee.core.sea.SeaSample()
    private val scratchSeaPoint = Vec3()
    private val scratchSeaUp = Vec3()
    private val scratchTilt = Quat()

    /** Waves shorter than a craft's own length don't move it as a whole: a rider feels only the longer ones. */
    private fun riderSpacing(vessel: Vessel): Double = kotlin.math.max(1.0, vessel.contactRadius)

    /**
     * A craft asleep afloat, riding the sea: up and down with the tide and
     * the waves, tipped with them, where it was moored. [waves] off for time
     * warped on rails, when only the tide is followed.
     */
    private fun followSea(vessel: Vessel, attractor: CelestialBody, waves: Boolean) {
        val ocean = attractor.ocean ?: return followGround(vessel, attractor)
        attractor.rotationAt(tickEnd, scratchRotation)
        vessel.sleepDirection(scratchSeaUp)
        ocean.sample(scratchSeaUp, tickEnd, seaRide, spacing = if (waves) riderSpacing(vessel) else 1.0e9)
        val height = if (waves) seaRide.height else seaRide.tide
        com.rm.apogee.core.math.quatFromTo(vessel.sleepNormal, if (waves) seaRide.normal else scratchSeaUp, scratchTilt)
        scratchRotation.rotate(scratchSeaUp, scratchSeaPoint).mulInPlace(attractor.radius + height + vessel.draft)
        attractor.surfaceVelocityAt(scratchSeaPoint, scratchSurfaceVelocity)
        if (waves) scratchSurfaceVelocity.addInPlace(scratchRotation.rotate(seaRide.velocity, scratchSeaPoint))
        attractor.angularVelocity(scratchSpin)
        vessel.followSea(scratchRotation, attractor.radius + height + vessel.draft, scratchTilt, scratchSurfaceVelocity, scratchSpin)
        // A sea got up that is big for it: what happens to it now - riding
        // it out, shipping water, going over - is for the physics to say.
        if (waves && !hurried && seaRide.significantHeight > tooRough(vessel)) vessel.wake()
    }

    /**
     * Time asked to go faster than physics can follow. A boat left alone in
     * a rough sea may then drop anchor and ride it asleep after all, as in
     * a calm one - otherwise any storm anywhere would hold the whole world
     * to physics warp until it blew over.
     */
    var hurried: Boolean = false

    /**
     * Significant wave height, m, past which the sea is rough for [vessel]:
     * too big, for its size, to be ridden asleep like a cork.
     */
    private fun tooRough(vessel: Vessel): Double = kotlin.math.max(ROUGH_SEA, ROUGH_PER_METRE * vessel.contactRadius)

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
        val attractor = attractorFor(vessel)
        // In a seaway a boat is never at rest - it goes up and down with the
        // waves - so hands off and drifting slowly is still enough: asleep,
        // it rides them anyway.
        val seaway = hydrostatics.seaHeight > SEAWAY_HS
        if (seaway && !handsOff) return false
        if (!hurried && hydrostatics.seaHeight > tooRough(vessel)) return false
        val limit = if (seaway || (handsOff && vessel.air.wind.length > 0.5)) ANCHOR_DRIFT else FLOATING_REST_SPEED
        attractor.surfaceVelocityAt(vessel.body.position, scratchSurfaceVelocity)
        scratchRelativeVelocity.setTo(vessel.body.linearVelocity).subInPlace(scratchSurfaceVelocity)
        if (seaway) {
            // Only its drift across the water counts, not its heaving.
            scratchSeaUp.setTo(vessel.body.position).normalizeInPlace()
            scratchRelativeVelocity.addScaledInPlace(scratchSeaUp, -(scratchRelativeVelocity dot scratchSeaUp))
            return scratchRelativeVelocity.length <= limit
        }
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
     * A part struck at [speed] (m/s, into the surface) along [push] (world
     * axes, the way the blow drives it).
     *
     * The blow travels through the craft in order. The part that touched
     * takes it first: below its crash tolerance nothing; above, damage
     * rising steeply - ((v - tol) / 2tol)^1.5, so three times its tolerance
     * finishes it. A part that survives stops the blow there, and its
     * neighbours only feel a jolt. A part that is crushed soaks up energy as
     * it goes - its structure's mass times its tolerance squared, for
     * propellant does not crumple - and what is left carries on, slower,
     * with the rest of the craft into the next part along the line of the
     * blow. So
     * a nose cone or an engine bell is a crumple zone: a crash can strip the
     * front off a craft and leave the pod behind it whole.
     *
     * A leg is built for this and folds instead, as it always has.
     */
    fun impact(vessel: Vessel, partIndex: Int, speed: Double, push: Vec3, water: Boolean = false) {
        var part = partIndex
        var v = speed
        val local = vessel.body.orientation.inverseRotate(push)
        val visited = HashSet<Int>()
        // What is still moving behind the blow: the crushed parts stop.
        var moving = vessel.body.mass
        while (part >= 0 && visited.add(part)) {
            val def = vessel.defs[part]
            val tolerance = def.crashTolerance
            if (v <= tolerance) return
            val blow = Math.pow((v - tolerance) / (2.0 * tolerance), 1.5)
            if (def.module<LandingLeg>() != null && vessel.breakPart(part)) {
                pendingEvents.add(WorldEvent.PartFailed(vessel.id, part, "${def.title} collapsed"))
            }
            val health = vessel.health[part]
            vessel.partOffsetWorld(part, scratch)
            pendingEvents.add(
                WorldEvent.Impact(
                    vessel.id, part, def.id, v, kotlin.math.min(blow, health),
                    Vec3().setTo(vessel.body.position).addInPlace(scratch), vessel.referenceBodyId, water,
                ),
            )
            if (blow < health) {
                // It held: the blow stops here, and the parts round it are jolted.
                vessel.damage(part, blow, local)
                shock(vessel, part, blow * SHOCK_SHARE, 1)
                return
            }
            // Crushed. It takes what it could, and passes the rest on.
            vessel.damage(part, health, local)
            val absorbed = CRUSH_ENERGY * def.dryMass * tolerance * tolerance * health
            moving = (moving - vessel.partMass(part)).coerceAtLeast(1.0)
            val remaining = v * v - 2.0 * absorbed / moving
            if (remaining <= 0.0) return
            v = kotlin.math.sqrt(remaining)
            part = nextAlong(vessel, part, local, visited)
        }
    }

    /**
     * The neighbour of [part] - its parent or a child - that lies furthest
     * along [push] (design axes): where a blow driving it that way goes next.
     */
    private fun nextAlong(vessel: Vessel, part: Int, push: Vec3, visited: Set<Int>): Int {
        val parts = vessel.design.parts
        val here = parts[part].position
        var best = -1
        var bestAlong = 0.05
        fun consider(n: Int) {
            if (n < 0 || n in visited || vessel.health[n] <= 0.0) return
            val p = parts[n].position
            val along = (p.x - here.x) * push.x + (p.y - here.y) * push.y + (p.z - here.z) * push.z
            if (along > bestAlong) { bestAlong = along; best = n }
        }
        consider(parts[part].parentIndex)
        for (i in parts.indices) if (parts[i].parentIndex == part) consider(i)
        return best
    }

    private fun shock(vessel: Vessel, from: Int, amount: Double, depth: Int) {
        if (depth <= 0 || amount < 0.01) return
        val parts = vessel.design.parts
        val parent = parts[from].parentIndex
        if (parent >= 0) {
            vessel.damage(parent, amount)
            shock(vessel, parent, amount * SHOCK_SHARE, depth - 1)
        }
        for (i in parts.indices) {
            if (parts[i].parentIndex != from) continue
            vessel.damage(i, amount)
            shock(vessel, i, amount * SHOCK_SHARE, depth - 1)
        }
    }

    /**
     * Breaks [vessel] up if any of its parts is damaged to nothing: those
     * parts are gone, and the craft falls into however many pieces are left
     * holding together. The piece with the controls on it - or the heaviest,
     * if none has - stays this vessel, so the player is still flying what is
     * left; the rest become debris, each carrying on at its own point's
     * velocity. A tank that goes with propellant in it explodes.
     *
     * If nothing is left at all, the craft is destroyed.
     */
    fun breakUp(vessel: Vessel, cause: String = "struck", detached: Set<Int> = emptySet()) {
        val destroyed = vessel.defs.indices.filter { vessel.health[it] <= 0.0 }.toSet()
        if (destroyed.isEmpty() && detached.isEmpty()) return
        failParts(vessel, destroyed, detached - destroyed, cause)
    }

    /**
     * Part [index] of [vessel] tears away at the end of the tick, with
     * whatever hangs from it. A root has no joint above it, so it tears
     * away from everything below instead; a craft of one part has nothing
     * to tear from.
     */
    private fun detach(vessel: Vessel, index: Int, cause: String) {
        val parts = vessel.design.parts
        val cuts = if (parts[index].parentIndex >= 0) listOf(index)
            else parts.indices.filter { parts[it].parentIndex == index }
        if (cuts.isEmpty()) return
        val set = pendingDetach.getOrPut(vessel.id) { HashSet() }
        if (!set.addAll(cuts)) return
        vessel.partOffsetWorld(index, scratch)
        val at = Vec3().setTo(vessel.body.position).addInPlace(scratch)
        pendingEvents.add(WorldEvent.PartDetached(vessel.id, index, vessel.defs[index].id, "${vessel.defs[index].title} $cause", at, vessel.referenceBodyId))
        vessel.wake()
    }

    /**
     * Removes [destroyed] parts from [vessel] and cuts the joints above
     * [detached] parts, then splits what remains into its connected pieces.
     */
    fun failParts(vessel: Vessel, destroyed: Set<Int>, detached: Set<Int>, cause: String) {
        val design = vessel.design
        val count = design.parts.size
        // Blasts and events first, while the indices still mean something.
        for (index in destroyed) {
            vessel.partOffsetWorld(index, scratch)
            val at = Vec3().setTo(vessel.body.position).addInPlace(scratch)
            pendingEvents.add(WorldEvent.PartDestroyed(vessel.id, index, vessel.defs[index].id, cause, at, vessel.referenceBodyId))
            val fuel = com.rm.apogee.core.part.ResourceType.entries.filter { it.explosive }.sumOf {
                vessel.amountInPart(index, it) * it.densityPerUnit
            }
            if (fuel > MIN_EXPLOSIVE_KG) pendingBlasts.add(Blast(vessel.referenceBodyId, at, fuel, vessel.id))
        }

        // Connected pieces: parent links, minus destroyed parts and cut joints.
        val piece = IntArray(count) { -1 }
        var pieces = 0
        fun joined(child: Int): Boolean {
            val parent = design.parts[child].parentIndex
            return parent >= 0 && parent !in destroyed && child !in detached
        }
        for (start in 0 until count) {
            if (start in destroyed || piece[start] >= 0) continue
            // Up to the top of its piece, then down through everything joined.
            var top = start
            while (joined(top)) top = design.parts[top].parentIndex
            val id = pieces++
            val stack = ArrayDeque<Int>()
            stack.add(top)
            while (stack.isNotEmpty()) {
                val p = stack.removeLast()
                if (piece[p] >= 0) continue
                piece[p] = id
                for (c in 0 until count) {
                    if (design.parts[c].parentIndex == p && c !in destroyed && c !in detached) stack.add(c)
                }
            }
        }
        if (pieces == 0) {
            pendingDestruction.add(vessel.id to "${vessel.name} was destroyed")
            return
        }
        val members = Array(pieces) { k -> (0 until count).filter { piece[it] == k } }

        // Which piece the craft carries on as: the one with the controls,
        // else the heaviest.
        fun mass(indices: List<Int>) = indices.sumOf { vessel.partMass(it) }
        fun controls(indices: List<Int>) = indices.any { vessel.defs[it].module<com.rm.apogee.core.part.Command>() != null }
        // A craft that had controls and has none left is lost with them:
        // what is left is wreckage, not the craft. Carried on as its
        // heaviest piece, a player went on flying a heat shield rolling
        // across the ground, its parts counted as the craft's (Dan).
        val lostWithControls = controls(vessel.design.parts.indices.toList()) && members.none { controls(it) }
        val keep = if (lostWithControls) -1 else members.indices.maxWith(
            compareBy<Int>({ k -> if (controls(members[k])) 1 else 0 })
                .thenBy { k -> mass(members[k]) },
        )

        val position = vessel.body.position.copy()
        val orientation = vessel.body.orientation.copy()
        val angularVelocity = vessel.body.angularVelocity.copy()
        val originalDefs = vessel.defs
        val originalDesign = vessel.design
        val offset = Vec3()
        val pointVelocity = Vec3()

        // The debris first, each from the original, before the craft itself
        // is rebuilt underneath it.
        val spawned = ArrayList<VesselId>()
        for (k in members.indices) {
            if (k == keep) continue
            val sub = buildSubDesign(originalDesign, members[k])
            val debris = Vessel(
                id = VesselId(nextVesselId++),
                design = sub.design,
                defs = sub.indices.map { originalDefs[it] },
                referenceBodyId = vessel.referenceBodyId,
            )
            debris.inheritParts(vessel, sub.indices)
            // A piece broken off with a lit engine keeps it lit, as a stage does.
            debris.control.throttle = vessel.control.throttle
            if (members[k].none { originalDefs[it].module<com.rm.apogee.core.part.Command>() != null }) {
                debris.name = "${vessel.name} debris"
            }
            // Where its own centre was, moving as that point of the craft was.
            val centre = pieceCentre(vessel, members[k], offset)
            vessel.body.velocityAtOffset(centre, pointVelocity)
            debris.body.orientation.setTo(orientation)
            debris.body.position.setTo(position).addInPlace(centre)
            debris.recomputeMass(shiftBodyPosition = false)
            debris.body.linearVelocity.setTo(pointVelocity)
            debris.body.angularVelocity.setTo(angularVelocity)
            vesselsById[debris.id] = debris
            pendingEvents.add(WorldEvent.VesselSpawned(debris.id))
            spawned.add(debris.id)
            if (debris.body.mass < WRECKAGE_KG && debris.defs.none { it.module<com.rm.apogee.core.part.Command>() != null }) {
                fragmentExpiry[debris.id] = time + FRAGMENT_LIFETIME
            }
        }
        if (lostWithControls) {
            for (a in spawned.indices) for (b in a + 1 until spawned.size) {
                justSeparated[pairKey(spawned[a].raw, spawned[b].raw)] = time + SEPARATION_GRACE
            }
            pendingDestruction.add(vessel.id to "${vessel.name} was destroyed")
            return
        }
        // The pieces start out touching where they were joined: that is a
        // break, not a fresh collision between them.
        spawned.add(vessel.id)
        for (a in spawned.indices) for (b in a + 1 until spawned.size) {
            justSeparated[pairKey(spawned[a].raw, spawned[b].raw)] = time + SEPARATION_GRACE
        }

        val kept = buildSubDesign(originalDesign, members[keep], firedStages = vessel.currentStage)
        val keptCentre = pieceCentre(vessel, members[keep], offset).copy()
        vessel.body.velocityAtOffset(keptCentre, pointVelocity)
        vessel.replaceStructure(kept.design, kept.indices.map { originalDefs[it] }, kept.indices)
        reseat(vessel)
        vessel.body.linearVelocity.setTo(pointVelocity)
        pendingEvents.add(WorldEvent.VesselStructureChanged(vessel.id))
    }

    /** The mass-weighted centre of parts [indices], as an offset from the craft's body position, world axes. */
    private fun pieceCentre(vessel: Vessel, indices: List<Int>, out: Vec3): Vec3 {
        out.setZero()
        var total = 0.0
        val part = Vec3()
        for (i in indices) {
            val m = vessel.partMass(i)
            vessel.partOffsetWorld(i, part)
            out.addScaledInPlace(part, m)
            total += m
        }
        return if (total > 0.0) out.mulInPlace(1.0 / total) else out
    }

    /**
     * Tanks that went up this tick: everything within reach, on any craft,
     * is damaged by how close it was and pushed away from the blast.
     */
    private fun resolveExplosions() {
        if (pendingBlasts.isEmpty()) return
        val blasts = pendingBlasts.toList()
        pendingBlasts.clear()
        for (blast in blasts) {
            // Energy by the propellant, capped: a big tank is a big bang, not
            // the end of the world.
            val energy = blast.energy.coerceAtMost(MAX_BLAST_KG)
            val radius = BLAST_RADIUS_PER_KG * kotlin.math.sqrt(energy)
            pendingEvents.add(WorldEvent.Explosion(blast.bodyId, blast.centre.copy(), energy))
            val offset = Vec3()
            for (other in vesselsById.values.toList()) {
                if (other.referenceBodyId != blast.bodyId) continue
                if (other.body.position.distanceTo(blast.centre) > radius + other.contactRadius) continue
                other.wake()
                var hit = false
                for (i in other.defs.indices) {
                    other.partOffsetWorld(i, offset)
                    offset.addInPlace(other.body.position).subInPlace(blast.centre)
                    val d = offset.length
                    if (d > radius) continue
                    // Falling off steeply: the shock wave spends itself fast.
                    val strength = 1.0 - d / radius
                    val ratio = BLAST_TOUGHNESS / other.defs[i].crashTolerance
                    val toughness = (ratio * ratio).coerceIn(0.25, 1.5)
                    val push = if (d > 1e-6) offset.copy().mulInPlace(1.0 / d) else Vec3(0.0, 1.0, 0.0)
                    other.damage(i, BLAST_DAMAGE * strength * strength * strength * toughness, other.body.orientation.inverseRotate(push))
                    hit = true
                }
                if (hit) {
                    // A shove away from the blast, by the energy and how near.
                    val away = other.body.position.copy().subInPlace(blast.centre)
                    val d = away.length.coerceAtLeast(1.0)
                    val impulse = (BLAST_IMPULSE_PER_KG * energy * (1.0 - (d / (radius + other.contactRadius)).coerceIn(0.0, 1.0)))
                        .coerceAtMost(BLAST_MAX_KICK * other.body.mass)
                    other.body.applyImpulse(away.mulInPlace(impulse / d))
                    pendingBreakUps.add(other.id)
                }
            }
        }
    }

    fun attractorFor(vessel: Vessel): CelestialBody = system.body(vessel.referenceBodyId)

    /**
     * A sleeping craft, carried round with the ground to where it is at the
     * end of the tick - the time the tick's positions are reported at.
     */
    private fun followGround(vessel: Vessel, attractor: CelestialBody) {
        val body = vessel.body
        attractor.rotationAt(tickEnd, scratchRotation)
        attractor.surfaceVelocityAt(body.position, scratchSurfaceVelocity)
        attractor.angularVelocity(scratchSpin)
        vessel.followRotation(scratchRotation, scratchSurfaceVelocity, scratchSpin)
        // The ground's velocity where it now is, not where it was a tick
        // ago: a client recognises a sleeping craft by its moving with the
        // surface exactly.
        attractor.surfaceVelocityAt(body.position, body.linearVelocity)
    }

    // --- time warp --------------------------------------------------------------

    /**
     * The fastest the world may run just now, as a multiple of real time:
     * the lowest any awake craft allows (see [warpLimit]). A sleeping craft
     * rides the ground at any rate and limits nothing.
     */
    fun maxWarp(): Double {
        var limit = WARP_RATES.last()
        for (vessel in vesselsById.values) {
            if (vessel.dormant) continue
            limit = minOf(limit, warpLimit(vessel))
        }
        return limit
    }

    /**
     * How fast one craft lets time go. Up to [PHYSICS_WARP] the world simply
     * steps more often, and anything goes. Beyond that craft move on rails,
     * along their orbits exactly, which is only honest for a craft that
     * nothing but gravity is acting on: out of the air, well clear of the
     * ground and not burning. Faster still needs more room - each rate
     * wants a greater height, by the body's size - since a
     * tick then covers so much of an orbit that an atmosphere or a mountain
     * could slip by between two looks, or a burn the pilot meant to make.
     */
    fun warpLimit(vessel: Vessel): Double {
        if (vessel.control.throttle > 0.0 && vessel.activeEngines().isNotEmpty()) return PHYSICS_WARP
        val attractor = attractorFor(vessel)
        val position = vessel.body.position
        attractor.toBodyFixed(position, attractor.rotationAt(time), scratchWarp)
        val floor: Double
        if (attractor.atmosphere != null) {
            floor = attractor.atmosphereHeight
            if (attractor.altitudeOf(position) <= floor) return PHYSICS_WARP
        } else {
            floor = RAILS_CLEARANCE
            if (attractor.heightAboveTerrain(position, scratchWarp) <= RAILS_CLEARANCE) return PHYSICS_WARP
        }
        val above = (attractor.altitudeOf(position) - floor) / attractor.radius
        var allowed = PHYSICS_WARP
        for (k in RAILS_RATES.indices) {
            if (above >= RAILS_HEIGHTS[k]) allowed = RAILS_RATES[k]
        }
        return allowed
    }

    /**
     * Moves the world on by [seconds] on rails: every awake craft along its
     * orbit, turning as it was, every sleeping one with the ground. In
     * slices of at most [RAILS_STEP], looking again after each: the moment
     * any craft stops allowing it - it has reached the air, or come down
     * toward a moon - it stops, and says how far it got.
     */
    fun advanceOnRails(seconds: Double): Double {
        var done = 0.0
        while (seconds - done > 1e-9) {
            if (maxWarp() <= PHYSICS_WARP) break
            val h = minOf(RAILS_STEP, seconds - done)
            tickEnd = time + h
            for (vessel in vesselsById.values) {
                val attractor = attractorFor(vessel)
                if (vessel.dormant) {
                    if (vessel.afloat) followSea(vessel, attractor, waves = false) else followGround(vessel, attractor)
                    continue
                }
                val body = vessel.body
                val next = Orbit(body.position, body.linearVelocity, attractor.gravitationalParameter).propagate(h)
                body.position.setTo(next.position)
                body.linearVelocity.setTo(next.velocity)
                // Still turning as it was.
                val spin = body.angularVelocity.length
                if (spin > 1e-9) {
                    scratchWarp.setTo(body.angularVelocity).mulInPlace(1.0 / spin)
                    body.orientation.setTo(Quat.fromAxisAngle(scratchWarp, spin * h) * body.orientation).normalizeInPlace()
                }
            }
            time += h
            done += h
        }
        if (done > 0.0) tick++
        return done
    }

    private val scratchWarp = Vec3()

    /**
     * Moves the clock straight on to [until], for a launch at a chosen time
     * of day. A parked craft stays on its ground; one in a clear orbit goes
     * round it; anything else - in the air, or just set down and not yet
     * settled - is carried round with the planet, over the same ground and
     * moving as it was, as if time had been paused for it. Lightning is not
     * looked for over the gap.
     */
    fun skipTo(until: Double) {
        val seconds = until - time
        if (seconds <= 0.0) return
        val turn = Quat()
        for (vessel in vesselsById.values) {
            val attractor = attractorFor(vessel)
            if (vessel.dormant) continue
            val body = vessel.body
            if (warpLimit(vessel) > PHYSICS_WARP) {
                val next = Orbit(body.position, body.linearVelocity, attractor.gravitationalParameter).propagate(seconds)
                body.position.setTo(next.position)
                body.linearVelocity.setTo(next.velocity)
            } else {
                // The planet's turn over the gap, applied to the craft whole.
                attractor.rotationAt(until, turn)
                turn.setTo(turn * attractor.rotationAt(time).conjugate())
                turn.rotate(body.position, body.position)
                turn.rotate(body.linearVelocity, body.linearVelocity)
                turn.rotate(body.angularVelocity, body.angularVelocity)
                body.orientation.setTo(turn * body.orientation).normalizeInPlace()
            }
        }
        time = until
        tickEnd = until
        for (vessel in vesselsById.values) {
            if (vessel.dormant) {
                if (vessel.afloat) followSea(vessel, attractorFor(vessel), waves = true) else followGround(vessel, attractorFor(vessel))
            }
        }
        lightningCheckedTo = Double.NaN
        tick++
    }

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
        hitches = links.map { SavedLink(it.a.raw, it.partA, it.b.raw, it.partB) },
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
                condition = VesselCondition.encode(vessel),
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
        anchored = vessel.anchored,
    )

    /**
     * Pins [vessel] where it is now, as the server says it is founded: for a
     * client's replica, which takes the server's word rather than asking
     * whether it could be.
     */
    fun pin(vessel: Vessel) {
        if (vessel.anchored) return
        attractorFor(vessel).rotationAt(time, anchorRotation)
        vessel.anchor(anchorRotation)
    }

    // --- persistence ---------------------------------------------------------

    /**
     * Captures the whole world for saving.
     *
     * Takes a copy of everything it touches: an autosave runs on the same
     * thread as the tick in this design, but the moment it does not, handing
     * out live vectors would let a save observe a craft halfway through a step.
     */
    fun save(): WorldSave {
        // Bases' power brought up to now, so what is saved is what they have.
        for (vessel in vesselsById.values) if (vessel.anchored) settlePower(vessel)
        return saveNow()
    }

    private fun saveNow(): WorldSave = WorldSave(
        links = links.map { SavedLink(it.a.raw, it.partA, it.b.raw, it.partB) },
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
                sasMode = vessel.control.sasMode,
                navFrame = vessel.control.navFrame,
                target = vessel.control.target,
                brakes = vessel.control.brakes,
                resources = vessel.resourceSnapshot().map { it.toList() },
                legDeploy = vessel.legDeploy.toList(),
                health = vessel.health.toList(),
                flooded = if (vessel.flooded.any { it > 0.0 }) vessel.flooded.toList() else emptyList(),
                crumple = vessel.crumple.toList(),
                temperature = vessel.temperature.toList(),
                anchored = vessel.anchored,
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
        // Founded bases, pinned again once everything is in place - after
        // any setting down on changed ground.
        val founded = ArrayList<Vessel>()
        val terrainChanged = save.terrainGeneration != TerrainField.GENERATION
        felledScatter.clear()
        // Scatter ids name places on one generation's ground; on another they
        // would fell some unrelated tree, so a new terrain grows back whole.
        if (!terrainChanged) felledScatter.addAll(save.felledScatter)
        lastFlown.clear()
        lastFlown.putAll(save.lastFlown)
        save.weather?.let { weatherConfig = it }
        links.clear()
        for (l in save.links) links.add(Link(VesselId(l.vesselA), l.partA, VesselId(l.vesselB), l.partB))

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
        tickEnd = time
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
            vessel.control.sasMode = saved.sasMode
            vessel.control.navFrame = saved.navFrame
            vessel.control.target = saved.target
            vessel.control.brakes = saved.brakes
            vessel.fitPose()
            saved.legDeploy.forEachIndexed { i, progress -> vessel.setLegDeploy(i, progress) }
            if (saved.resources.isNotEmpty()) {
                vessel.restoreResources(saved.resources.map { it.toDoubleArray() })
            }
            vessel.restoreCondition(saved.health, saved.crumple, saved.temperature)
            saved.flooded.forEachIndexed { i, kg -> if (i < vessel.flooded.size) vessel.flooded[i] = kg }
            vessel.recomputeMass(shiftBodyPosition = false)
            if (saved.anchored) founded.add(vessel)

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
        for (vessel in founded) {
            attractorFor(vessel).rotationAt(time, anchorRotation)
            vessel.anchor(anchorRotation)
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
        if (command.siteId.startsWith(LaunchSite.BASE_SITE_PREFIX)) {
            val (base, pad) = command.siteId.removePrefix(LaunchSite.BASE_SITE_PREFIX).split(":").let {
                vesselsById[VesselId(it.getOrNull(0)?.toLongOrNull() ?: -1)] to (it.getOrNull(1)?.toIntOrNull() ?: -1)
            }
            if (base != null && base.anchored && mayLaunchFrom(base, owner) && base.defs.getOrNull(pad)?.module<com.rm.apogee.core.part.LaunchPad>() != null) {
                return spawnOnBasePad(command.design, base, pad).also { it.owner = owner }
            }
        }
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
                // A founded base can be hundreds of metres across with its
                // pads in the middle of it: what matters is its buildings,
                // one by one, not the reach of the whole.
                val near = if (other.anchored && gap < 0.0) partsGap(other, spot, radius) else gap
                if (near < clearance) clearance = near
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

    /** The tightest gap from a craft of [radius] at [spot] to any solid part of [vessel], beyond the margin. */
    private fun partsGap(vessel: Vessel, spot: Vec3, radius: Double): Double {
        var least = Double.POSITIVE_INFINITY
        val at = Vec3()
        for (i in vessel.defs.indices) {
            val def = vessel.defs[i]
            if (!def.solid) continue
            val gap = vessel.partPositionWorld(i, at).subInPlace(spot).length - def.boundsHalfExtents.length - radius - PAD_MARGIN_METRES
            if (gap < least) least = gap
        }
        return least
    }

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
        /** After undocking, seconds before the two may capture again. */
        const val UNDOCK_GRACE = 6.0

        /** A hitch: solver passes a tick, share of the gap closed a tick, and the yank that breaks it, g on the lighter craft. */
        private const val LINK_ITERATIONS = 4
        private const val LINK_STIFFNESS = 0.25
        private const val LINK_BREAK_G = 8.0

        /** What time warp offers, as multiples of real time. */
        val WARP_RATES = doubleArrayOf(1.0, 2.0, 4.0, 10.0, 50.0, 100.0, 1_000.0, 10_000.0)

        /** Up to this the world steps faster; past it, craft go on rails. */
        const val PHYSICS_WARP = 4.0

        /** The rails rates, and the height above the air (or [RAILS_CLEARANCE]) each needs, in the body's radii. */
        private val RAILS_RATES = doubleArrayOf(10.0, 50.0, 100.0, 1_000.0, 10_000.0)
        private val RAILS_HEIGHTS = doubleArrayOf(0.0, 0.1, 0.2, 0.4, 0.8)

        /** Over an airless body, how far above the ground rails warp can start, m. */
        private const val RAILS_CLEARANCE = 5_000.0

        /** The longest slice of time moved on rails before looking again, s. */
        private const val RAILS_STEP = 5.0

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

        /** Luna's test base: its name, the site it stands by, and which of the site's pads it takes. */
        const val LUNA_BASE_NAME = "Luna Test Base"
        const val LUNA_TEST_SITE = "luna-mare"
        const val LUNA_BASE_PAD = 6

        /** Whose the Cape's own buildings are: nobody's, and everybody's to launch from. */
        const val WORLD_OWNER = "world"

        /** What a base's pump moves into a craft. */
        private val REFUEL_TYPES = listOf(
            com.rm.apogee.core.part.ResourceType.PROPELLANT,
            com.rm.apogee.core.part.ResourceType.MONOPROPELLANT,
            com.rm.apogee.core.part.ResourceType.ELECTRIC_CHARGE,
        )

        /** How far above a pad deck's top a craft's lowest point may be and still be standing on it, m. */
        const val PAD_SERVICE_HEIGHT = 0.8

        /** How often the Cape's buildings are looked at for repair, ticks. */
        const val REPAIR_CHECK_TICKS = 60L

        /** Nothing awake may be within this of a complex, m, for [REPAIR_QUIET] s, for it to be rebuilt. */
        const val REPAIR_REACH = 2_000.0
        const val REPAIR_QUIET = 60.0

        /** How often founded bases' power is brought up to date, ticks. */
        const val POWER_CHECK_TICKS = 60L

        /** A base's power ledger is worked out in steps of this, s - or coarser, to [POWER_MAX_STEPS] of them. */
        const val POWER_STEP = 60.0
        const val POWER_MAX_STEPS = 20_000

        /** What a base's command part takes to keep it running, charge a second. */
        const val BASE_UPKEEP = 0.05

        /** Lamps come on when the sun is lower than this, the sine of its elevation: dusk. */
        const val LAMP_DUSK = 0.05

        /** How far clear of the truck a release clamp sets its load down, m. */
        const val SET_DOWN_CLEARANCE = 0.3

        /** How near the ground a foundation's lowest foot must be for the craft to be founded, m. */
        const val FOOT_ON_GROUND = 0.3

        /** The fastest over the ground a craft may be going and still be founded, m/s. */
        const val ANCHOR_MAX_SPEED = 0.3

        /** Metres the two halves of a separation are pushed apart immediately. */
        private const val SEPARATION_CLEARANCE = 0.5

        /** Impacts gentler than this are not worth an event. */
        private const val TOUCHDOWN_REPORT_SPEED = 0.5

        /** Seconds two halves of a staged craft touch only gently while they part. */
        private const val SEPARATION_GRACE = 1.5

        /** How long a gentle pair stays gentle after it last touched, s. */
        private const val GENTLE_HOLD = 0.3

        /** Fastest drift, m/s, at which a boat left alone in a wind drops anchor. */
        private const val ANCHOR_DRIFT = 1.5

        /** Share of a blow a part that holds passes on to its neighbours as a jolt. */
        private const val SHOCK_SHARE = 0.2

        /**
         * Energy a part soaks up being crushed, J, per kg per (m/s of crash
         * tolerance) squared, of its dry mass: a half-tonne tank of
         * tolerance 6 takes 2.7 MJ, the Ember's 1.5 t bell 11 MJ - enough to
         * stop the Starter I from 40 m/s tail first, not from 60.
         */
        private const val CRUSH_ENERGY = 150.0

        /** Crash debris lighter than this, with no controls, is cleared away, kg. */
        private const val WRECKAGE_KG = 200.0
        /** How long a fragment lies there first, s. */
        private const val FRAGMENT_LIFETIME = 120.0
        private const val FRAGMENT_CHECK_TICKS = 60L

        /** Propellant below this, kg, burns rather than explodes. */
        private const val MIN_EXPLOSIVE_KG = 20.0
        private const val MAX_BLAST_KG = 20_000.0
        /** Blast radius, m, per square root of kg of propellant: 1 t is about 24 m. */
        private const val BLAST_RADIUS_PER_KG = 0.75
        /**
         * Damage at the heart of a blast, to a part of [BLAST_TOUGHNESS]
         * crash tolerance; by the square of it tougher parts take less,
         * flimsier more - a pod rides out the blast that takes the tank
         * beside it.
         */
        private const val BLAST_DAMAGE = 1.2
        private const val BLAST_TOUGHNESS = 8.0
        /** N*s of push per kg of propellant, at the centre. */
        private const val BLAST_IMPULSE_PER_KG = 6.0
        /** The most a blast can change a craft's speed, m/s: it throws fins, not bullets. */
        private const val BLAST_MAX_KICK = 25.0

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

        /** Significant wave height, m, above which the sea is a seaway: floating craft heave with it. */
        private const val SEAWAY_HS = 0.3

        /**
         * How rough a sea, m of significant height per m of a craft's
         * contact radius, it may sleep through, past [ROUGH_SEA]: a Trawler
         * a little more than that; in a storm's sea, none.
         */
        private const val ROUGH_PER_METRE = 0.4

        /**
         * Significant wave height, m, that any boat may sleep through: an
         * ordinary day's sea off an open coast. Below it, boats moored at a
         * harbour cost nothing, as a base's should; above it is weather.
         */
        private const val ROUGH_SEA = 3.0


        val launchSites = listOf(
            // The pads: a row of them east and west of the launch tower, on
            // the coast. See SolarSystem.PAD_LATITUDE.
            LaunchSite(
                id = "cape",
                displayName = "Cape Launch Complex",
                bodyId = SolarSystem.HOMEWORLD_ID,
                latitude = SolarSystem.PAD_LATITUDE,
                longitude = SolarSystem.PAD_LONGITUDE,
            ),
            // The airfield: at the runway's west end, facing down it, east,
            // toward the bay - the next one along it forty metres on.
            capeSite("airfield", "Cape Airfield", 340.0, -400.0),
            // The harbour's berth, off its jetty in the broad bay beside the
            // Cape: dredged deep, open to the sea only up a winding inlet.
            capeSite("harbour", "Cape Harbour", 2_700.0, 330.0),
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
            // Built lying down, with wings: a plane, for the runway.
            val flies = design.orientation == com.rm.apogee.core.craft.CraftOrientation.HORIZONTAL && design.parts.any {
                catalog[it.partId]?.hasModule<com.rm.apogee.core.part.AeroSurface>() == true
            }
            return launchSites.first { it.id == if (floats) "harbour" else if (flies) "airfield" else "cape" }
        }

        /** A site at the Cape, [east] and [north] metres from the pad. */
        private fun capeSite(id: String, name: String, east: Double, north: Double): LaunchSite {
            val d = SolarSystem.capeDirection(east, north)
            return LaunchSite(id, name, SolarSystem.HOMEWORLD_ID, SolarSystem.latitudeOf(d), SolarSystem.longitudeOf(d))
        }

        fun default(catalog: PartCatalog) = World(SolarSystem.defaultSystem(), catalog)
    }
}
