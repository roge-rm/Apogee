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
import com.rm.apogee.core.weather.Climate
import com.rm.apogee.core.weather.Strike
import com.rm.apogee.core.weather.Weather
import com.rm.apogee.core.weather.WeatherConfig
import com.rm.apogee.core.concurrentSetOf
import com.rm.apogee.core.math.Math
import kotlin.concurrent.Volatile

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
    /** A pad on a founded base, instead of one of the fixed sites. */
    val onBase: Boolean get() = id.startsWith(BASE_SITE_PREFIX)

    companion object {
        /** Site ids for base pads: this, the base's vessel id, a colon and the pad's part index. */
        const val BASE_SITE_PREFIX = "base:"
    }
}

/** Something worth telling the presentation layer about. */
sealed interface WorldEvent {
    data class VesselSpawned(val id: VesselId) : WorldEvent
    data class VesselStructureChanged(val id: VesselId) : WorldEvent
    data class VesselDestroyed(val id: VesselId, val reason: String) : WorldEvent

    /** [absorbed] docked onto [keeper] and is now part of it. */
    data class Docked(val keeper: VesselId, val absorbed: VesselId, val position: Vec3, val bodyId: String) : WorldEvent

    /** [spawned] undocked from [from] and is a craft of its own again. */
    data class Undocked(val from: VesselId, val spawned: VesselId, val position: Vec3, val bodyId: String) : WorldEvent

    /**
     * A winch on [a] hooked onto [b] (null for the ground), or let go ([hooked] false), and whether it
     * [snapped] doing it.
     */
    data class Winched(val a: VesselId, val b: VesselId?, val hooked: Boolean, val snapped: Boolean = false) : WorldEvent

    /** A tow hitch coupled ([coupled]) or let go. */
    data class Hitched(val a: VesselId, val b: VesselId, val coupled: Boolean, val position: Vec3, val bodyId: String) : WorldEvent

    /** A tree or shrub knocked down, for good. */
    data class ScatterFelled(val scatterId: Long) : WorldEvent

    /**
     * Refuelling [id] from a base stopped because of [reason]: it's full, the base is dry or dark,
     * or there's nothing to fill it from.
     */
    data class RefuelStopped(val id: VesselId, val reason: String) : WorldEvent
    data class Staged(val id: VesselId, val stage: Int) : WorldEvent
    data class Touchdown(val id: VesselId, val impactSpeed: Double) : WorldEvent
    /**
     * [bodyId] has been surveyed by [id]'s scanner, so its ore and water are on everyone's map now.
     */
    data class Surveyed(val id: VesselId, val bodyId: String) : WorldEvent

    /** One of [owner]'s crew died: [how]. */
    data class CrewLost(val crewId: Long, val name: String, val owner: String, val how: String) : WorldEvent

    /** A craft passed out of one body's pull into another's. */
    data class BodyChanged(val id: VesselId, val from: String, val to: String) : WorldEvent

    /**
     * [owner]'s career got credited with a feat ([grade] blank for an ungraded one) or a visit,
     * worth [insight], earned by craft [vessel].
     */
    data class FeatEarned(val owner: String, val title: String, val grade: String, val insight: Int, val vessel: VesselId) : WorldEvent

    /**
     * A part failed but the craft is still flying, like a leg collapsing or a chute tearing away.
     * It's different from [VesselDestroyed], which ends the craft.
     */
    data class PartFailed(
        val id: VesselId,
        val partIndex: Int,
        val reason: String,
    ) : WorldEvent

    /**
     * A part hit hard enough to hurt it: [damage] of its health taken, at [position] in its
     * attractor's frame. For sound and effects.
     */
    data class Impact(
        val id: VesselId,
        val partIndex: Int,
        val partId: String,
        val speed: Double,
        val damage: Double,
        val position: Vec3,
        val bodyId: String,
        /** Into the sea instead of onto something solid. */
        val water: Boolean = false,
    ) : WorldEvent

    /** A part destroyed outright (hit, burnt or blown up) at [position] in its attractor's frame. */
    data class PartDestroyed(
        val id: VesselId,
        val partIndex: Int,
        val partId: String,
        val cause: String,
        val position: Vec3,
        val bodyId: String,
    ) : WorldEvent

    /**
     * A part torn off whole, like a joint that let go or a wing that snapped, taking whatever hangs
     * from it along as debris of its own. At [position] in its attractor's frame.
     */
    data class PartDetached(
        val id: VesselId,
        val partIndex: Int,
        val partId: String,
        val cause: String,
        val position: Vec3,
        val bodyId: String,
    ) : WorldEvent

    /** A tank going up with [energy] kg of propellant, at [position] around body [bodyId]. */
    data class Explosion(val bodyId: String, val position: Vec3, val energy: Double) : WorldEvent

    /**
     * Lightning struck a craft. [partIndex] is what it knocked out, or -1 if it came through
     * unharmed. Strikes that hit nothing need no event, because every client works them out from
     * the weather for itself.
     */
    data class LightningHit(
        val id: VesselId,
        val strikeId: Long,
        val partIndex: Int,
    ) : WorldEvent
}

/**
 * The simulation. One authoritative copy lives on the server, and each client runs a second one to
 * predict its own craft.
 *
 * Everything here gives the same result from the same starting state, the same commands and the
 * same tick sequence. The iteration order is fixed, the timestep is fixed, and nothing reads a wall
 * clock. That isn't because we're aiming for lockstep, since devices disagree about floating point.
 * It's because a simulation that repeats on one machine can be tested, replayed and reasoned about,
 * and one that doesn't can't.
 */
class World(
    val system: SolarSystem,
    val catalog: PartCatalog,
) {
    /** Seconds since the universe began. */
    var time: Double = 0.0
        private set

    /**
     * Sets the clock of a replica. A client's prediction world has to agree with the server's about
     * what time it is, because the time is where the planet has turned to, and so where the ground
     * is under every craft. It's never used on an authoritative world, whose clock only moves
     * forward by stepping.
     */
    fun syncClock(time: Double) {
        this.time = time
    }

    var tick: Long = 0
        private set

    /**
     * The careers in this world, or null for a sandbox, where everything is unlocked and nothing is
     * watched. See [com.rm.apogee.core.career.Program].
     */
    var program: com.rm.apogee.core.career.Program? = null

    /** A world event from outside this class, like the career's feats. */
    internal fun raise(event: WorldEvent) {
        pendingEvents.add(event)
    }

    /** Events the career has already seen, from the start of [pendingEvents]. */
    private var careerSeen = 0

    /**
     * The career watching what happened since it last looked, and every craft too if [look] is set.
     */
    private fun watchCareer(look: Boolean) {
        if (look) lookForWonders()
        val program = program ?: run { careerSeen = pendingEvents.size; return }
        val fresh = if (careerSeen < pendingEvents.size) pendingEvents.subList(careerSeen, pendingEvents.size).toList() else emptyList()
        program.observe(this, fresh, look)
        careerSeen = pendingEvents.size
    }

    /**
     * [owner] spends insight on tech node [node]. Returns null if it worked, or the reason it
     * didn't.
     */
    fun unlock(owner: String, node: String): String? {
        val program = program ?: return "Not a career"
        return program.unlock(owner, node)
    }

    /**
     * What the weather is made from: the world's seed and how lively the host wants it, or null for
     * still air. A game's world gets its config from the server that owns it, and a replica takes
     * the server's. A bare world is still, so a test of how a leg takes a landing isn't also a test
     * of which way the wind happened to blow.
     */
    var weatherConfig: WeatherConfig? = null
        set(value) {
            if (field == value) return
            field = value
            weathers.clear()
            bindSeas()
        }

    /**
     * A steady wind over the ground everywhere there's air, in place of the weather: x is the part
     * blowing toward the east and y toward the north, in m/s. Null for the weather as usual. It's
     * for tests and tuning, where a sail or a windsock needs a wind that doesn't gust or turn.
     */
    var steadyWind: Vec3? = null
    private val scratchEast = Vec3()
    private val scratchNorth = Vec3()

    /**
     * Gives each body's ocean its sea: always its moon's tides, and waves from this world's weather
     * when it has any. Every world does its own (the server's, and each client's replica), and they
     * agree because they're the same function of the same config.
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
     * Where stability assist should hold the nose, in inertial axes, for a craft holding a navball
     * marker, worked out exactly the way the navball draws it. Null for plain attitude hold, or for
     * a marker with nothing to point at (no motion, no target).
     */
    private fun holdDirection(vessel: Vessel, attractor: CelestialBody): Vec3? {
        val control = vessel.control
        if (!control.sasEnabled || control.sasMode == SasMode.HOLD) return null
        val target = vessel(VesselId(control.target))?.takeIf { it.referenceBodyId == vessel.referenceBodyId }
        val body = if (target == null) targetBodyFor(vessel, attractor, time, targetPosition, targetVelocity) else false
        Navigation.compute(
            vessel.body.position, vessel.body.linearVelocity, attractor, control.navFrame,
            target?.body?.position ?: targetPosition.takeIf { body }, target?.body?.linearVelocity ?: targetVelocity.takeIf { body },
            navDirections,
        )
        navDirections.hasBurn = control.sasMode == SasMode.BURN && burnRemaining(vessel, attractor, navDirections.burn) > Burns.DONE
        if (navDirections.hasBurn) navDirections.burn.normalizeInPlace()
        return if (navDirections.forMode(control.sasMode, holdScratch)) holdScratch else null
    }

    private val targetPosition = Vec3()
    private val targetVelocity = Vec3()

    /**
     * Where [vessel]'s target body is relative to [attractor] at [at], into [position] and
     * [velocity], if it has one other than the body it's in. False if not.
     */
    fun targetBodyFor(vessel: Vessel, attractor: CelestialBody, at: Double, position: Vec3, velocity: Vec3): Boolean {
        val id = vessel.control.targetBody
        if (id.isEmpty() || id == attractor.id || id !in system.bodies) return false
        position.setTo(system.positionOf(id, at)).subInPlace(system.positionOf(attractor.id, at))
        velocity.setTo(system.velocityOf(id, at)).subInPlace(system.velocityOf(attractor.id, at))
        return true
    }

    // --- planned burns ------------------------------------------------------------

    /**
     * Whether [vessel] can fly itself, doing burns and landings. Everyone can for now. A career
     * will make it something to earn.
     */
    /** Whether [vessel] can hold its height and heading: in a career, once its owner has Cruise Control. */
    fun mayCruise(vessel: Vessel): Boolean {
        val program = program ?: return true
        return vessel.owner.isBlank() || program.allows(vessel.owner, com.rm.apogee.core.career.TechTree.CRUISE)
    }

    private val cruise = Cruise()
    private val rotors = Rotors()
    private val aerostatics = Aerostatics()
    private val keeper = StationKeeping()
    private val keeperSteered = HashSet<VesselId>()

    /** Whether [vessel] carries a working keeper core. */
    fun hasKeeper(vessel: Vessel): Boolean =
        vessel.defs.indices.any { vessel.defs[it].module<com.rm.apogee.core.part.StationKeeper>() != null && !vessel.isBroken(it) }

    /**
     * Turns [vessel]'s keeper core on, to hold it where it is now, or off. It needs a working
     * keeper core, something to hold the craft with, and to be off the ground or afloat.
     */
    private fun setStationKeep(vessel: Vessel, on: Boolean) {
        val control = vessel.control
        if (!on) {
            if (control.keeping) control.ballast = 0
            control.keeping = false
            return
        }
        val attractor = attractorFor(vessel)
        val means = keeper.means(vessel, attractor, vessel.buoyed)
        val reason = when {
            !hasKeeper(vessel) -> "No keeper core"
            !vessel.powered -> "No power"
            means == null -> "Nothing to hold it with"
            vessel.touchingGround && !vessel.buoyed -> "Lift off first"
            else -> ""
        }
        control.autopilotNote = reason
        if (reason.isNotEmpty()) return
        control.keeping = true
        control.cruise = false
        control.autoLand = false
        control.autoBurn = false
        control.holdDepth = false
        control.sasEnabled = true
        control.sasMode = SasMode.HOLD
        attractor.toBodyFixed(vessel.body.position, attractor.rotationAt(time), control.keepPoint)
        control.keepTrim = control.throttle
        vessel.heightIntegral = 0.0
        vessel.wake()
    }

    /** One tick of the keeper core holding [vessel] where it was asked to. */
    private fun flyKeeper(vessel: Vessel, attractor: CelestialBody, dt: Double) {
        val control = vessel.control
        val means = keeper.means(vessel, attractor, vessel.buoyed)
        val keeperPart = vessel.defs.indices.firstOrNull { vessel.defs[it].module<com.rm.apogee.core.part.StationKeeper>() != null && !vessel.isBroken(it) }
        val draw = keeperPart?.let { vessel.defs[it].module<com.rm.apogee.core.part.StationKeeper>()!!.draw } ?: 0.0
        val reason = when {
            keeperPart == null -> "No keeper core"
            means == null -> "Nothing to hold it with"
            !vessel.powered || !vessel.drawCharge(draw * dt) -> "No power"
            else -> ""
        }
        if (reason.isNotEmpty()) {
            control.keeping = false
            control.ballast = 0
            control.autopilotNote = reason
            return
        }
        // Let go of the stick, and it holds wherever it is then, at the height it's at.
        val steered = control.hasAttitudeInput
        if (steered) keeperSteered.add(vessel.id)
        else if (keeperSteered.remove(vessel.id)) {
            attractor.toBodyFixed(vessel.body.position, attractor.rotationAt(time), control.keepPoint)
        }
        keeper.fly(vessel, attractor, attractor.rotationAt(time, scratchKeeperRotation), means!!, steered, dt)
    }

    private val scratchKeeperRotation = com.rm.apogee.core.math.Quat()

    /** Whether [vessel] has wings to cruise on. */
    private fun winged(vessel: Vessel): Boolean = vessel.defs.any { it.module<AeroSurface>() != null }

    /**
     * Turns [vessel]'s height and heading hold on at its height and heading now, or off. It only
     * takes over a craft with wings, in the air, at least [Cruise.LOWEST] up.
     */
    private fun setCruise(vessel: Vessel, on: Boolean) {
        val control = vessel.control
        if (!on) { control.cruise = false; return }
        val attractor = attractorFor(vessel)
        val reason = when {
            !mayCruise(vessel) -> "Cruise Control not available"
            !winged(vessel) -> "No wings to cruise on"
            vessel.touchingGround || attractor.atmosphere == null || groundClearance(vessel, attractor) < Cruise.LOWEST -> "Too low to cruise"
            else -> ""
        }
        control.autopilotNote = reason
        if (reason.isNotEmpty()) return
        control.cruise = true
        control.sasEnabled = true
        control.sasMode = SasMode.HOLD
        control.cruiseHeight = attractor.altitudeOf(vessel.body.position)
        control.cruiseHeading = cruise.track(vessel, attractor)
        control.cruiseTrim = cruise.attack(vessel, attractor).coerceIn(-Cruise.MAX_ATTACK, Cruise.MAX_ATTACK)
        vessel.wake()
    }

    /** How high [vessel] is above the ground under it, or the sea, in metres. */
    private fun groundClearance(vessel: Vessel, attractor: CelestialBody): Double {
        val altitude = attractor.altitudeOf(vessel.body.position)
        val terrain = attractor.terrain ?: return altitude
        val up = attractor.toBodyFixed(vessel.body.position, attractor.rotationAt(time)).normalizeInPlace()
        return altitude - maxOf(terrain.elevation(up), 0.0)
    }

    /** Whether the stick was being held last tick, per cruising craft: letting go picks up the new heading and height. */
    private val cruiseSteered = HashSet<VesselId>()

    /**
     * Flies [vessel]'s height and heading hold for this tick, before stability assist. Steered by
     * hand it lets the stick fly, and when the stick is let go it holds the heading and height it
     * has then.
     */
    private fun flyCruise(vessel: Vessel, attractor: CelestialBody, dt: Double) {
        val control = vessel.control
        if (vessel.touchingGround || !vessel.powered || attractor.atmosphere == null) {
            control.cruise = false
            control.autopilotNote = if (!vessel.powered) "No power" else ""
            return
        }
        if (control.hasAttitudeInput) { cruiseSteered.add(vessel.id); return }
        if (cruiseSteered.remove(vessel.id)) {
            control.cruiseHeight = attractor.altitudeOf(vessel.body.position)
            control.cruiseHeading = cruise.track(vessel, attractor)
        }
        control.sasEnabled = true
        cruise.fly(vessel, attractor, dt)
    }

    /**
     * Switches [vessel]'s action group [group] on, or off if it's on. Switched on, its engines light
     * even if they haven't been staged.
     */
    private fun toggleGroup(vessel: Vessel, group: Int) {
        if (group !in 1..Vessel.GROUPS) return
        if (vessel.design.parts.none { it.group == group }) return
        val on = vessel.groupStates[group] != 1
        vessel.groupStates[group] = if (on) 1 else -1
        if (on) {
            for (i in vessel.design.parts.indices) {
                if (vessel.design.parts[i].group == group && vessel.defs[i].module<com.rm.apogee.core.part.Engine>() != null) vessel.activated[i] = true
            }
        }
        vessel.wake()
    }

    fun mayAutopilot(vessel: Vessel): Boolean {
        val program = program ?: return true
        return vessel.owner.isBlank() || program.allows(vessel.owner, com.rm.apogee.core.career.TechTree.AUTOPILOT)
    }

    /**
     * What's left of [vessel]'s next burn, in world axes, into [out], and how much in m/s. 0 with
     * none planned. Before its window opens, it's the whole burn, from the orbit as it is now.
     */
    fun burnRemaining(vessel: Vessel, attractor: CelestialBody = attractorFor(vessel), out: Vec3 = Vec3()): Double {
        val burn = vessel.plannedBurns.firstOrNull() ?: return 0.0.also { out.setZero() }
        if (vessel.burnVector.x.isNaN()) Burns.vectorOf(burn, orbitAbout(vessel, attractor), out)
        else out.setTo(vessel.burnVector).subInPlace(vessel.burnApplied)
        return out.length
    }

    private fun orbitAbout(vessel: Vessel, attractor: CelestialBody) =
        Orbit(vessel.body.position.copy(), vessel.body.linearVelocity.copy(), attractor.gravitationalParameter, time)

    /** Before the tick: what the craft's velocity was, to measure what it gives toward a burn. */
    private val burnBefore = Vec3()

    /**
     * Opens [vessel]'s next burn window when it's due, counts what this tick gave toward it (its
     * change of velocity, minus what gravity gave), and crosses it off once there's nothing left of
     * it.
     */
    private fun trackBurn(vessel: Vessel, attractor: CelestialBody, dt: Double) {
        val burn = vessel.plannedBurns.firstOrNull() ?: return
        val start = Burns.startOf(burn, vessel.burnDuration)
        if (vessel.burnVector.x.isNaN()) {
            if (tickEnd < start - Burns.WINDOW) return
            // Its direction is fixed in space from here on. The orbit's own axes turn as the craft
            // burns, and a burn chasing them spirals.
            Burns.vectorOf(burn, orbitAbout(vessel, attractor), vessel.burnVector)
            vessel.burnApplied.setZero()
            vessel.burnDuration = Burns.duration(vessel, burn.deltaV)
            return
        }
        attractor.gravityAt(vessel.body.position, scratchBurn).mulInPlace(dt)
        vessel.burnApplied.addInPlace(vessel.body.linearVelocity).subInPlace(burnBefore).subInPlace(scratchBurn)
        scratchBurn.setTo(vessel.burnVector).subInPlace(vessel.burnApplied)
        // Nothing left, or past it, so it's done.
        if (scratchBurn.length < Burns.DONE || (scratchBurn dot vessel.burnVector) < 0.0) {
            vessel.plannedBurns.removeAt(0)
            vessel.resetBurn()
            vessel.burnDuration = vessel.plannedBurns.firstOrNull()?.let { Burns.duration(vessel, it.deltaV) } ?: 0.0
            if (vessel.control.autoBurn) {
                vessel.control.throttle = 0.0
                vessel.control.autoBurn = false
            }
            pendingEvents.add(WorldEvent.VesselStructureChanged(vessel.id))
        }
    }

    private val scratchBurn = Vec3()

    /**
     * Flies [vessel]'s next burn: turns onto it, waits for its time, goes to full throttle, eases
     * off as it runs out, and cuts. It gives up, saying why, when there's nothing to burn with.
     */
    private fun autoBurn(vessel: Vessel, attractor: CelestialBody) {
        val control = vessel.control
        val burn = vessel.plannedBurns.firstOrNull()
        if (burn == null || !mayAutopilot(vessel)) {
            control.autoBurn = false
            control.throttle = 0.0
            return
        }
        control.sasEnabled = true
        control.rcsEnabled = true
        control.sasMode = SasMode.BURN
        val left = burnRemaining(vessel, attractor, scratchAuto)
        val start = Burns.startOf(burn, vessel.burnDuration)
        if (vessel.burnVector.x.isNaN() || time < start || left < Burns.DONE) { control.throttle = 0.0; return }
        var thrust = litThrust(vessel)
        // If the stage burns out part way through, move on to the next one, if it has an engine.
        if (thrust <= 0.0 && nextStageLights(vessel)) {
            stage(vessel)
            thrust = litThrust(vessel)
        }
        if (thrust <= 0.0) {
            control.throttle = 0.0
            control.autoBurn = false
            control.autopilotNote = "No engine to burn with"
            return
        }
        val facing = vessel.forward(scratchFacing) dot scratchAuto.mulInPlace(1.0 / left)
        val burning = control.throttle > 0.0
        val aligned = facing > (if (burning) ALIGNED_BURNING else ALIGNED_START)
        if (!aligned) { control.throttle = 0.0; return }
        // Full, then easing off over the last half second's worth.
        val most = thrust / vessel.body.mass
        control.throttle = (left / (most * AUTO_TAPER)).coerceIn(AUTO_LEAST_THROTTLE, 1.0)
    }

    // --- auto-land ------------------------------------------------------------------

    /**
     * Lands [vessel] by itself. The engine stays off with the nose into the fall until stopping
     * would take all the height that's left, then it brakes. It comes down at a pace that slows as
     * the ground gets closer, down to a metre a second, taking off the sideways speed on the way,
     * gets the legs out near the ground, and cuts once it's standing on them. Returns the way to
     * hold the nose, or null to leave it to stability assist. It gives up, saying why, if there's
     * too little engine to land on.
     */
    private fun autoLand(vessel: Vessel, attractor: CelestialBody, dt: Double): Vec3? {
        val control = vessel.control
        if (!mayAutopilot(vessel)) { control.autoLand = false; return null }
        if (!vessel.landStarted) {
            // Set up on its first tick, which is where it's switched on for the craft here and for
            // a player's own copy of it alike: where a rotorcraft or an airship comes down
            // (straight under it now), the trims it starts from, and nothing else flying it.
            vessel.landStarted = true
            vessel.landBraking = false
            vessel.landSpeed = 0.0
            vessel.landDownFor = 0.0
            vessel.landSink = 0.0
            attractor.toBodyFixed(vessel.body.position, attractor.rotationAt(time), control.keepPoint)
            control.keepTrim = control.throttle
            control.cruise = false
            control.keeping = false
        }
        // Anything that flies on the air lands the way it flies, not on its engines.
        airLanding(vessel, attractor, dt)?.let { outcome ->
            if (outcome == Landing.Outcome.LANDED) {
                control.autoLand = false
                control.autopilotNote = "Landed"
            }
            return null
        }
        val body = vessel.body
        control.sasEnabled = true
        control.rcsEnabled = true
        control.sasMode = SasMode.HOLD
        attractor.surfaceVelocityAt(body.position, landVelocity).negateInPlace().addInPlace(body.linearVelocity)
        landUp.setTo(body.position).normalizeInPlace()
        val vertical = landVelocity dot landUp
        landSide.setTo(landVelocity).addScaledInPlace(landUp, -vertical)
        if (vessel.touchingGround) {
            control.throttle = 0.0
            if (landVelocity.length < LANDED_SPEED) {
                control.autoLand = false
                control.autopilotNote = "Landed"
            }
            return null
        }
        val g = attractor.gravityAt(body.position, landScratch).length
        val thrust = litThrustHere(vessel, attractor)
        val most = thrust / body.mass
        if (thrust <= 0.0 || most < LAND_LEAST_TWR * g) {
            control.throttle = 0.0
            control.autoLand = false
            control.autopilotNote = if (thrust <= 0.0) "No engine to land with" else "Too little thrust to land here"
            return null
        }
        val height = clearance(vessel, attractor)
        if (height < LEGS_OUT) lowerLegs(vessel)
        val speed = landVelocity.length
        // Coasting down with nothing lit and the nose into the fall, until stopping from here would
        // take all the height there is.
        if (!vessel.landBraking) {
            val stopping = speed * speed / (2.0 * (LAND_BRAKE_SHARE * most - g).coerceAtLeast(0.1))
            if (vertical > 0.0 || height > stopping * LAND_MARGIN + LAND_FLARE) {
                control.throttle = 0.0
                return if (speed > 1.0) landDirection.setTo(landVelocity).negateInPlace().normalizeInPlace()
                    else landDirection.setTo(landUp)
            }
            vessel.landBraking = true
        }
        // Still going fast over the ground, coming down from orbit: full thrust against the way
        // it's going, with the nose never under the horizon, until the sideways speed is nearly
        // gone.
        if (landSide.length > LAND_SIDE_KILLED) {
            landDirection.setTo(landVelocity).negateInPlace().normalizeInPlace()
            val below = landDirection dot landUp
            if (below < LAND_LEAST_RISE) landDirection.addScaledInPlace(landUp, LAND_LEAST_RISE - below).normalizeInPlace()
            val facing = vessel.forward(scratchFacing) dot landDirection
            control.throttle = if (facing > LAND_ALIGNED) 1.0 else 0.0
            return landDirection
        }
        // Down no faster than it could still stop from. It falls freely while that's faster than
        // it's going, so no propellant gets spent holding a pace high up, and it eases to a metre a
        // second at the ground with no sideways drift.
        val canStop = kotlin.math.sqrt(2.0 * (LAND_BRAKE_SHARE * most - g).coerceAtLeast(0.5) * height.coerceAtLeast(0.0)) * LAND_CURVE
        val want = -maxOf(LAND_TOUCHDOWN, minOf(canStop, LAND_TOUCHDOWN + height * LAND_PACE))
        val lift = (g + LAND_GAIN * (want - vertical)).coerceAtLeast(0.0)
        landDirection.setTo(landUp).mulInPlace(lift)
        landScratch.setTo(landSide).mulInPlace(-LAND_SIDE_GAIN)
        val sideways = landScratch.length
        if (sideways > LAND_SIDE_SHARE * most) landScratch.mulInPlace(LAND_SIDE_SHARE * most / sideways)
        landDirection.addInPlace(landScratch)
        val need = landDirection.length
        if (need < 1e-6) { control.throttle = 0.0; return landDirection.setTo(landUp) }
        landDirection.mulInPlace(1.0 / need)
        // Never leaning far over. A craft tipped well off upright near the ground can't be brought
        // back in time, and maybe not at all. Leaning no further than this, it pushes up no harder
        // than the descent needs, because taking off drift is never worth climbing for.
        var push = need
        val lean = kotlin.math.acos((landDirection dot landUp).coerceIn(-1.0, 1.0))
        if (lean > LAND_MOST_LEAN) {
            landScratch.setTo(landDirection).addScaledInPlace(landUp, -(landDirection dot landUp)).normalizeInPlace()
            landDirection.setTo(landUp).mulInPlace(kotlin.math.cos(LAND_MOST_LEAN)).addScaledInPlace(landScratch, kotlin.math.sin(LAND_MOST_LEAN))
            push = lift / kotlin.math.cos(LAND_MOST_LEAN)
        }
        val facing = vessel.forward(scratchFacing) dot landDirection
        control.throttle = if (facing > LAND_ALIGNED) (push / most).coerceIn(0.0, 1.0) else 0.0
        return landDirection
    }

    /**
     * One tick of landing [vessel] the way a plane, a rotorcraft or an airship comes down, if it's
     * one of those and there's air to do it in. Null for anything else, which lands on its engines.
     */
    private fun airLanding(vessel: Vessel, attractor: CelestialBody, dt: Double): Landing.Outcome? {
        if (attractor.atmosphere == null) return null
        val kind = com.rm.apogee.core.craft.CraftKind.of(vessel.design, catalog)
        return when (kind) {
            com.rm.apogee.core.craft.CraftKind.PLANE ->
                landing.plane(vessel, attractor, airClearance(vessel, attractor), dt) { lowerLegs(vessel) }
            com.rm.apogee.core.craft.CraftKind.ROTORCRAFT, com.rm.apogee.core.craft.CraftKind.AIRSHIP -> {
                val means = keeper.means(vessel, attractor, false)?.takeIf { it != StationKeeping.Means.WATER } ?: return null
                landing.hover(vessel, attractor, attractor.rotationAt(time, scratchKeeperRotation), means, airClearance(vessel, attractor), dt) { lowerLegs(vessel) }
            }
            else -> null
        }
    }

    /** How far [vessel]'s lowest part is over the ground, or the sea where that's higher. */
    private fun airClearance(vessel: Vessel, attractor: CelestialBody): Double {
        val overGround = clearance(vessel, attractor)
        if (attractor.ocean == null) return overGround
        attractor.toBodyFixed(vessel.body.position, attractor.rotationAt(time), landScratch).normalizeInPlace()
        val underCentre = attractor.heightAboveTerrain(vessel.body.position, landScratch) - overGround
        return minOf(overGround, attractor.altitudeOf(vessel.body.position) - underCentre)
    }

    private val landing = Landing(keeper)

    /** Roughly how far the lowest part of [vessel] is above the ground under it, in metres. */
    private fun clearance(vessel: Vessel, attractor: CelestialBody): Double {
        attractor.toBodyFixed(vessel.body.position, attractor.rotationAt(time), landScratch).normalizeInPlace()
        val centre = attractor.heightAboveTerrain(vessel.body.position, landScratch)
        var below = 0.0
        for (i in vessel.defs.indices) {
            vessel.partOffsetWorld(i, landOffset)
            below = maxOf(below, -(landOffset dot landUp) + vessel.defs[i].boundsHalfExtents.length)
        }
        return centre - below
    }

    /**
     * Fires the next stage if it's nothing but legs and gear still up, so it has something to stand
     * on.
     */
    private fun lowerLegs(vessel: Vessel) {
        val next = vessel.design.stages.getOrNull(vessel.currentStage) ?: return
        if (next.activatedParts.isEmpty()) return
        val gear = next.activatedParts.all { i ->
            val def = vessel.defs.getOrNull(i) ?: return@all false
            def.module<com.rm.apogee.core.part.LandingLeg>() != null || def.module<com.rm.apogee.core.part.Wheel>() != null
        }
        if (gear) stage(vessel)
    }

    /**
     * The thrust the lit engines can give where the craft is, in N: in the air there, or in vacuum.
     */
    private fun litThrustHere(vessel: Vessel, attractor: CelestialBody): Double {
        val pressure = attractor.atmosphere?.pressureRatioAt(attractor.altitudeOf(vessel.body.position)) ?: 0.0
        var total = 0.0
        for (i in vessel.activeEngines()) {
            val engine = vessel.defs[i].module<com.rm.apogee.core.part.Engine>() ?: continue
            if (vessel.isBroken(i) || vessel.amountInGroupOf(i, engine.propellant) <= 0.0) continue
            total += engine.thrustVacuum + (engine.thrustSeaLevel - engine.thrustVacuum) * pressure.coerceIn(0.0, 1.0)
        }
        return total
    }

    private val landVelocity = Vec3()
    private val landUp = Vec3()
    private val landSide = Vec3()
    private val landDirection = Vec3()
    private val landScratch = Vec3()
    private val landOffset = Vec3()

    /**
     * Opens [vessel]'s fairing at part [index]. Its shell's two halves become craft of their own
     * where the shell stood, pushed out sideways from the craft's axis, one each way. The base
     * stays on, lighter by them.
     */
    private fun openFairing(vessel: Vessel, index: Int) {
        val fairing = vessel.defs[index].module<com.rm.apogee.core.part.Fairing>() ?: return
        val shell = catalog[fairing.shellPart] ?: return
        val placed = vessel.design.parts[index]
        val axis = vessel.body.orientation.rotate(placed.rotation.rotate(Vec3.unitY()))
        val offset = vessel.partOffsetWorld(index, Vec3())
        val rise = vessel.defs[index].boundsHalfExtents.y + fairing.height / 2
        for (side in listOf(1.0, -1.0)) {
            val turn = if (side > 0) Quat.identity() else Quat.fromAxisAngle(Vec3.unitY(), Math.PI)
            val rotation = (vessel.body.orientation * placed.rotation * turn).normalizeInPlace()
            val out = rotation.rotate(Vec3.unitX())
            // The half's own middle: out from the axis by half the shell's radius, and up by half
            // its height.
            val at = Vec3().setTo(offset).addScaledInPlace(axis, rise).addScaledInPlace(out, fairing.radius / 2)
            val half = Vessel(
                id = VesselId(nextVesselId++),
                design = CraftDesign(name = shell.title, parts = listOf(PlacedPart(shell.id, Vec3.zero())), catalogHash = catalog.contentHash),
                defs = listOf(shell),
                referenceBodyId = vessel.referenceBodyId,
            )
            half.recomputeMass(shiftBodyPosition = false)
            half.body.position.setTo(vessel.body.position).addInPlace(at)
            half.body.orientation.setTo(rotation)
            vessel.body.velocityAtOffset(at, half.body.linearVelocity)
            half.body.linearVelocity.addScaledInPlace(out, fairing.ejectionImpulse / shell.dryMass)
            half.body.angularVelocity.setTo(vessel.body.angularVelocity)
            vesselsById[half.id] = half
            justSeparated[pairKey(vessel.id.raw, half.id.raw)] = time + SEPARATION_GRACE
            pendingEvents.add(WorldEvent.VesselSpawned(half.id))
        }
        vessel.recomputeMass()
        pendingEvents.add(WorldEvent.VesselStructureChanged(vessel.id))
    }

    /** Whether [vessel]'s next stage lights an engine worth staging to, to keep a burn going. */
    private fun nextStageLights(vessel: Vessel): Boolean {
        val next = vessel.design.stages.getOrNull(vessel.currentStage) ?: return false
        return next.activatedParts.any { vessel.defs.getOrNull(it)?.module<com.rm.apogee.core.part.Engine>() != null }
    }

    /**
     * The thrust the craft's lit engines can give now, in vacuum, in N, counting those with
     * propellant.
     */
    fun litThrust(vessel: Vessel): Double {
        var total = 0.0
        for (i in vessel.activeEngines()) {
            val engine = vessel.defs[i].module<com.rm.apogee.core.part.Engine>() ?: continue
            if (vessel.isBroken(i) || vessel.amountInGroupOf(i, engine.propellant) <= 0.0) continue
            total += engine.thrustVacuum
        }
        return total
    }

    private val power = Power(system)
    private val industry = Industry()
    private val scratchIndustry = Vec3()

    /** Bodies surveyed for ore and water, by id. They're on everyone's map. */
    val surveyed: MutableSet<String> = LinkedHashSet()

    /**
     * [dt] more of [vessel]'s survey of [attractor]. It counts while it has a working scanner and
     * power, in a low, steep orbit clear of the ground. It's done in half an orbit, and lost the
     * moment it leaves one.
     */
    private fun survey(vessel: Vessel, attractor: CelestialBody, dt: Double) {
        if (attractor.id in surveyed || attractor.terrain == null) return
        var reach = 0.0
        for (i in vessel.defs.indices) {
            if (vessel.isBroken(i)) continue
            vessel.defs[i].module<com.rm.apogee.core.part.Scanner>()?.let { reach = maxOf(reach, it.surveyAltitude) }
        }
        val orbit = if (reach > 0.0) orbitOf(vessel) else null
        val floor = attractor.radius + maxOf(attractor.atmosphere?.height ?: 0.0, attractor.terrain.maxElevation)
        val qualifies = orbit != null && orbit.isBound && orbit.periapsis > floor &&
            orbit.apoapsis < attractor.radius * (1.0 + reach) &&
            kotlin.math.abs(orbit.angularMomentum.normalized().y) < SURVEY_TILT
        if (!qualifies || vessel.surveyBody != attractor.id) {
            vessel.surveyProgress = 0.0
            vessel.surveyBody = if (qualifies) attractor.id else ""
            if (!qualifies) return
        }
        // When it's out of power it waits, because the orbit it has is just as good when the sun
        // comes back.
        if (!vessel.powered) return
        vessel.surveyProgress += dt
        if (vessel.surveyProgress >= 0.5 * orbit!!.period) {
            surveyed.add(attractor.id)
            vessel.surveyProgress = 0.0
            pendingEvents.add(WorldEvent.Surveyed(vessel.id, attractor.id))
        }
    }

    /**
     * How far [vessel] has got through surveying the body it orbits, 0..1. It's 1 once that body is
     * surveyed.
     */
    fun surveyShare(vessel: Vessel): Double {
        val attractor = attractorFor(vessel)
        if (attractor.id in surveyed) return 1.0
        if (vessel.surveyBody != attractor.id) return 0.0
        return (vessel.surveyProgress / (0.5 * orbitOf(vessel).period)).coerceIn(0.0, 1.0)
    }

    /** How much of the sun reaches [vessel] where it is now. 0 in a shadow. */
    fun sunlight(vessel: Vessel): Double = power.sunlight(attractorFor(vessel), vessel.body.position, time)

    /** Fold-out panels out in air too thick for them get torn off. */
    private fun tearWings(vessel: Vessel, attractor: CelestialBody) {
        val atmosphere = attractor.atmosphere ?: return
        var q = -1.0
        for (i in vessel.defs.indices) {
            val panel = vessel.defs[i].module<com.rm.apogee.core.part.SolarPanel>() ?: continue
            if (!panel.deployable || vessel.isBroken(i) || vessel.legDeploy.getOrElse(i) { 0.0 } <= 0.05) continue
            if (q < 0.0) {
                val density = atmosphere.densityAt(attractor.altitudeOf(vessel.body.position))
                attractor.surfaceVelocityAt(vessel.body.position, scratchAuto).negateInPlace().addInPlace(vessel.body.linearVelocity)
                q = 0.5 * density * scratchAuto.lengthSq
            }
            if (q > panel.maxPressure && vessel.breakPart(i)) {
                pendingEvents.add(WorldEvent.PartFailed(vessel.id, i, "${vessel.defs[i].title} torn off by the air"))
            }
        }
    }

    private val scratchAuto = Vec3()
    private val scratchFacing = Vec3()

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

    init {
        forces.waterAt = { vessel, attractor, p, t -> hydrostatics.keptHeight(vessel, attractor, p, t) }
    }

    private val scatterContacts = com.rm.apogee.core.physics.ScatterContact()

    /**
     * Scatter knocked down (trees, shrubs, cacti), by id. It's world state, so it's saved, sent to
     * every client, and never grows back. Everything else about scatter is decided by the terrain
     * and doesn't need remembering.
     */
    val felledScatter: MutableSet<Long> = concurrentSetOf()

    /**
     * The craft each player last flew, by owner id. It's saved with the world, so starting a fresh
     * flight can clear away the one from the last session, and only that one. Anything else a
     * player owns is a base they left on purpose.
     */
    val lastFlown: MutableMap<String, Long> = com.rm.apogee.core.concurrentMapOf()

    /**
     * The stripe each player picked for their crew's suits, by owner id. Kept with the world, so a
     * player's crew still wear it while they're away.
     */
    val stripes: MutableMap<String, Int> = com.rm.apogee.core.concurrentMapOf()

    /**
     * The named places under the sea each player has found, by owner. It works the same in a career
     * and in free play, giving you somewhere to go that stays hidden until you reach it. A career
     * also pays for each one and keeps the world firsts.
     */
    val wondersFound: MutableMap<String, MutableSet<String>> = com.rm.apogee.core.concurrentMapOf()

    /** Goes up whenever anyone finds one, so a server knows to tell them. */
    @Volatile var wondersRevision = 0
        private set

    /** What [owner] has found of the named places under the sea, by id. */
    fun wondersFoundBy(owner: String): Set<String> = wondersFound[owner]?.toSet().orEmpty()

    /**
     * Checks every craft under the sea for the named places it has reached, meaning close by and
     * nearly as deep. The first time for its owner, it's a find. In a career that pays, and in free
     * play it gets a banner of its own.
     */
    private fun lookForWonders() {
        for (vessel in vesselsById.values) {
            if (vessel.anchored || vessel.owner.isBlank() || vessel.owner == WORLD_OWNER) continue
            val attractor = attractorFor(vessel)
            if (attractor.ocean == null) continue
            val terrain = attractor.terrain ?: continue
            val depth = depthOf(vessel)
            if (depth <= WONDER_LOOK_DEPTH) continue
            attractor.rotationAt(time, scratchRotation)
            val here = attractor.toBodyFixed(vessel.body.position, scratchRotation, scratchWonder).normalizeInPlace()
            for (wonder in SeaWonders.all) {
                if (wonder.bodyId != attractor.id) continue
                if (here.distanceTo(wonder.direction) * attractor.radius > SeaWonders.REACH) continue
                if (depth < -terrain.elevation(wonder.direction) * SeaWonders.DEPTH_SHARE) continue
                if (!wondersFound.getOrPut(vessel.owner) { concurrentSetOf() }.add(wonder.id)) continue
                wondersRevision++
                val program = program
                if (program != null) program.found(this, vessel, wonder)
                else raise(WorldEvent.FeatEarned(vessel.owner, wonder.name, com.rm.apogee.core.career.Program.FOUND, 0, vessel.id))
            }
        }
    }

    private val scratchWonder = Vec3()
    private val contacts = GroundContact()

    private val pendingEvents = ArrayList<WorldEvent>()

    /** The time the tick in progress ends at: [time] + dt, while stepping. */
    private var tickEnd = 0.0

    /** Knocks down scatter [id] for good and tells everyone. Felling it twice does nothing. */
    fun fell(id: Long) {
        if (felledScatter.add(id)) pendingEvents.add(WorldEvent.ScatterFelled(id))
    }

    /**
     * Craft to remove once the step finishes.
     *
     * Destroying them in place would change the map while it's being walked through. It's reused
     * instead of allocated every step, and it's empty on almost every one.
     */
    private val pendingDestruction = ArrayList<Pair<VesselId, String>>()

    /** Craft with a part damaged to nothing this tick, to break up at the end of it. */
    private val pendingBreakUps = LinkedHashSet<VesselId>()

    /** Why a craft in [pendingBreakUps] is breaking up, when it isn't from a blow. */
    private val breakUpCause = HashMap<VesselId, String>()

    /** Joints that let go this tick, by craft: the part below each one. */
    private val pendingDetach = HashMap<VesselId, MutableSet<Int>>()
    private val impactNormal = Vec3()

    /**
     * Small crash fragments, and when they go. Bits of fins and rings would otherwise litter every
     * crash site forever. Anything with controls on it, or heavy enough to be wreckage worth
     * finding, never goes here.
     */
    private val fragmentExpiry = HashMap<VesselId, Double>()

    /** Tanks that went up this tick, to damage whatever is near them. */
    private class Blast(val bodyId: String, val centre: Vec3, val energy: Double, val source: VesselId)
    private val pendingBlasts = ArrayList<Blast>()

    private val craftContacts = CraftContact().also { contacts ->
        contacts.ignorePair = { a, b -> docking.capturing(a, b) || linked(a, b) }
        // Just parted, so still solid to each other, but only gently.
        contacts.gentlePair = { a, b -> justSeparated.containsKey(pairKey(a, b)) }
        // A stage let go under power can push the one above for seconds, so they stay gentle for as
        // long as they touch, not just the first second and a half.
        contacts.gentleTouching = { a, b ->
            val key = pairKey(a, b)
            val until = justSeparated[key]
            if (until != null && until < time + GENTLE_HOLD) justSeparated[key] = time + GENTLE_HOLD
        }
    }

    /** Docking parts drawing each other in, and latching. */
    private val docking = com.rm.apogee.core.physics.Docking()

    /** A tow hitch coupled: two craft turning around one point. */
    class Link(val a: VesselId, val partA: Int, val b: VesselId, val partB: Int)

    /** Hitches coupled right now. */
    private val links = ArrayList<Link>()

    /** The links, for drawing and for tests. */
    val hitches: List<Link> get() = links

    /**
     * A winch line out: from winch [partA] on craft [a] to a hook on part [partB] of craft [b] at
     * [hook] (in that part's own axes), or with [b] null, to the ground at body-fixed [ground] on
     * [bodyId]. [length] is how much line is out, in metres.
     */
    class Line(
        val a: VesselId,
        val partA: Int,
        val b: VesselId?,
        val partB: Int,
        val hook: Vec3,
        val ground: Vec3,
        val bodyId: String,
        var length: Double,
    ) {
        /** Winding in (1), letting out (-1), or holding (0). */
        var reel = 0
        /** Whether it's pulling right now. */
        var taut = false
    }

    /** Winch lines out right now. */
    private val lines = ArrayList<Line>()

    /** The lines, for drawing and for tests. */
    val winchLines: List<Line> get() = lines

    /** [vessel]'s line out, if it has one. */
    fun lineOf(vessel: Vessel): Line? = lines.firstOrNull { it.a == vessel.id }

    private fun linked(a: Long, b: Long) = links.any { (it.a.raw == a && it.b.raw == b) || (it.a.raw == b && it.b.raw == a) }

    /**
     * The craft [vessel]'s wheels, legs or feet might be standing on this tick: those within reach
     * round the same body, leaving out what it's docking or coupled with and what it has only just
     * come apart from. Nobody drives over a person, so people on foot aren't decks.
     */
    private fun decksNear(vessel: Vessel): List<Vessel> {
        decksScratch.clear()
        if (vessel.defs.none { com.rm.apogee.core.physics.isFoot(it) }) return decksScratch
        val id = vessel.id.raw
        for (other in vesselsById.values) {
            if (other === vessel || other.referenceBodyId != vessel.referenceBodyId) continue
            val reach = vessel.contactRadius + other.contactRadius
            if (other.body.position.distanceTo(vessel.body.position) > reach) continue
            if (walking.walkerOf(other) != null) continue
            val otherId = other.id.raw
            if (docking.capturing(id, otherId) || linked(id, otherId) || justSeparated.containsKey(pairKey(id, otherId))) continue
            decksScratch.add(other)
        }
        return decksScratch
    }
    private val decksScratch = ArrayList<Vessel>()

    /**
     * The craft [vessel] is parked on, a plane on a carrier or a buggy on a barge, or null. It's
     * whatever's straight below its centre, within its own reach. Just loaded, nothing is standing on
     * anything yet, and the Petrel on the Flat Top's deck was listed as afloat.
     */
    fun deckUnder(vessel: Vessel): Vessel? {
        vessel.standingOn?.let { return it }
        vessel.ridingOn?.let { id -> vesselsById[id]?.let { return it } }
        if (vessel.defs.none { com.rm.apogee.core.physics.isFoot(it) }) return null
        scratchDeckUp.setTo(vessel.body.position).normalizeInPlace()
        for (other in vesselsById.values) {
            if (other === vessel || other.referenceBodyId != vessel.referenceBodyId) continue
            if (other.body.position.distanceTo(vessel.body.position) > vessel.contactRadius + other.contactRadius) continue
            // Down from its centre a step at a time, as far as it reaches.
            var down = DECK_PROBE_STEP
            while (down <= vessel.contactRadius + DECK_PROBE_STEP) {
                scratchDeckPoint.setTo(vessel.body.position).addScaledInPlace(scratchDeckUp, -down)
                for (i in other.defs.indices) {
                    if (!other.isBroken(i) && deckProbe.inside(scratchDeckPoint, other, i)) return other
                }
                down += DECK_PROBE_STEP
            }
        }
        return null
    }

    private val deckProbe = com.rm.apogee.core.physics.PartVolume()
    private val scratchDeckUp = Vec3()
    private val scratchDeckPoint = Vec3()

    /** Whether craft [a] and [b] are drawing each other in to dock. */
    fun capturing(a: Long, b: Long) = docking.capturing(a, b)

    /**
     * Vessels in step order, reused so the craft-against-craft pass can index them without
     * allocating a list every tick.
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

    /** Empties and returns the events raised since the last call. */
    fun drainEvents(): List<WorldEvent> {
        if (pendingEvents.isEmpty()) return emptyList()
        watchCareer(look = false)
        val copy = ArrayList<WorldEvent>(pendingEvents)
        pendingEvents.clear()
        careerSeen = 0
        return copy
    }

    // --- spawning -----------------------------------------------------------

    /**
     * Places a craft on the ground at [site], resting on its lowest part and moving with the
     * surface.
     *
     * Matching the surface velocity matters more than it looks. A craft spawned at rest in the
     * planet's *inertial* frame is really moving at a couple of hundred metres per second relative
     * to the ground it's standing on, and would get dragged off the pad the moment friction kicked
     * in.
     */
    fun spawnOnSurface(
        design: CraftDesign,
        site: LaunchSite,
        pad: Int = 0,
        /** A player's launch: it stands on its landing legs if they reach the ground. See [standOnLegs]. */
        legsOut: Boolean = false,
    ): Vessel {
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

        // The site is a point on a turning planet. Its body-fixed normal stays put, but its
        // inertial direction doesn't.
        surfaceNormalAt(site, pad, scratchBodyFixedUp)
        attractor.rotationAt(time, scratchRotation)
        scratchRotation.rotate(scratchBodyFixedUp, scratchUp)
        val up = scratchUp
        standUpright(vessel, attractor, up)

        // On the ground, not at sea level. The pad might be most of a kilometre above the datum,
        // and spawning at the datum would drop the craft inside a hill.
        //
        // At sea, it goes on the water as it is right now, since the tide might be metres up or
        // down.
        val sea = attractor.ocean?.let { attractor.radius + it.surfaceHeight(scratchBodyFixedUp, time) }
        val groundRadius = kotlin.math.max(attractor.solidRadiusInBodyFrame(scratchBodyFixedUp), sea ?: 0.0)
            .let { if (sea == null) attractor.surfaceRadiusInBodyFrame(scratchBodyFixedUp) else it }

        // On legs on land, and on a base's pad, even one afloat: a deck is something to stand on.
        if (legsOut && (sea == null || groundRadius > sea || site.onBase)) standOnLegs(vessel, up)

        // Lift the craft until its lowest part just touches the ground.
        val clearance = lowestExtentAlong(vessel, up)
        vessel.body.position
            .setTo(up)
            .mulInPlace(groundRadius + clearance)
        // Rebuild now that the orientation and position are set.
        vessel.recomputeMass(shiftBodyPosition = false)
        vessel.body.position.setTo(up).mulInPlace(groundRadius + lowestExtentAlong(vessel, up))

        attractor.surfaceVelocityAt(vessel.body.position, vessel.body.linearVelocity)
        if (sea != null) floatAtDraft(vessel, attractor, up, sea)

        vesselsById[vessel.id] = vessel
        seatCrew(vessel)
        pendingEvents.add(WorldEvent.VesselSpawned(vessel.id))
        return vessel
    }

    /**
     * A boat set down afloat, settled at the draft its weight gives it on the water it's put in,
     * and moving with that water. Stood on its lowest point instead (an outboard's leg), it got
     * dropped most of a metre, and in a swell it landed still on a moving slope and slammed over. A
     * skiff launched into the harbour pitched up fifty degrees and swamped where it was put. If
     * nothing floats, or the water's too shallow for it, it stays standing on the bottom.
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
        // Only if that isn't below where it already stands clear of the bottom.
        if (radius > vessel.body.position.length) return
        if (radius - lowestExtentAlong(vessel, up) < attractor.solidRadiusInBodyFrame(scratchBodyFixedUp)) return
        vessel.body.position.setTo(up).mulInPlace(radius)
        attractor.surfaceVelocityAt(vessel.body.position, vessel.body.linearVelocity)
        ocean.sample(scratchBodyFixedUp, time, seaRide)
        attractor.rotationAt(time, scratchRotation)
        vessel.body.linearVelocity.addInPlace(scratchRotation.rotate(seaRide.velocity, offset))
    }

    /**
     * Places a craft at a given state.
     *
     * Client-side prediction uses this. It needs to start a local replica from an authoritative
     * snapshot instead of from a launch site or an orbit.
     */
    fun spawnAt(
        design: CraftDesign,
        bodyId: String,
        position: Vec3,
        velocity: Vec3,
        rotation: Quat,
        angularVelocity: Vec3 = Vec3.zero(),
        /** Fill its seats with crew, except for someone climbing out, or a flag. */
        seat: Boolean = true,
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
        if (seat) seatCrew(vessel)
        pendingEvents.add(WorldEvent.VesselSpawned(vessel.id))
        return vessel
    }

    /** Places a craft straight into orbit. Used by tests and debug tools. */
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
        seatCrew(vessel)
        pendingEvents.add(WorldEvent.VesselSpawned(vessel.id))
        return vessel
    }

    /**
     * Turns a craft so the design's [CraftOrientation.up] points at the sky.
     *
     * A vertical craft keeps exactly the attitude it always had, nose up with its roll wherever the
     * shortest turn leaves it, so nothing that was already flying changes under it. A horizontal
     * one also gets a heading: nose east, along the way the ground is already carrying it. That's
     * the cheap direction to take off in, for the same reason it's the cheap direction to launch
     * in.
     */
    private fun standUpright(vessel: Vessel, attractor: CelestialBody, up: Vec3) {
        val orientation = vessel.design.orientation
        val rotation = vessel.body.orientation
        quatFromTo(orientation.up, up, rotation)

        // Facing east, along the runway, if it goes anywhere along the ground, like a plane or a
        // rover built standing up. Left to the turn that stood it upright, a rover's heading
        // depended on where on the planet it stood. At the old Cape it happened to face east, and
        // at the new one it faced straight off the side of the pad into the sea. A rocket or a
        // lander has no front to face, so it keeps the turn.
        if (orientation == CraftOrientation.VERTICAL &&
            vessel.defs.none { it.module<com.rm.apogee.core.part.Wheel>() != null }
        ) return

        // East is the way the surface moves. At a pole it doesn't move, so any heading is as good
        // as another.
        val east = attractor.surfaceVelocityAt(up, Vec3())
        east.addScaledInPlace(up, -(east dot up))
        if (east.lengthSq < 1e-12) return
        east.normalizeInPlace()

        // The first turn left the nose somewhere level, so swing it around the vertical until it
        // faces east.
        val nose = rotation.rotate(orientation.forward, Vec3())
        val heading = if ((nose dot east) < -0.999999) {
            Quat.fromAxisAngle(up, Math.PI)
        } else {
            quatFromTo(nose, east)
        }
        rotation.setTo(heading * rotation)
    }

    /**
     * The surface normal at a launch site's [pad].
     *
     * Pads are spread along the local east-west line. Without this every player who joins spawns at
     * exactly the same point, inside everyone already there, and the contact solver throws them
     * apart at violent speed, which is a spectacular but unhelpful way to start a game.
     */
    private fun surfaceNormalAt(site: LaunchSite, pad: Int, out: Vec3): Vec3 {
        val attractor = system.body(site.bodyId)
        // Alternate on either side of the site so the first few pads stay near the middle instead
        // of marching off in one direction.
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
     * How far the craft's centre of mass has to sit above the surface.
     *
     * It uses exactly the same hull contact points the collision resolver does, so a craft spawns
     * already meeting the contact constraint. Working out clearance one way and collision another
     * leaves every craft either hovering or spawning inside the ground.
     */
    /**
     * A craft a player launches stands on its landing legs if they're what it would stand on:
     * out and working, as if it had landed, instead of balanced on its engine bell with them folded
     * against it. Only the legs whose feet reach the bottom go out, so a rocket with a lander high
     * up its stack keeps that lander's folded. Their stage still fires in its turn, and changes
     * nothing.
     */
    private fun standOnLegs(vessel: Vessel, up: Vec3) {
        val legs = vessel.defs.indices.filter { vessel.defs[it].hasModule<LandingLeg>() }
        if (legs.isEmpty()) return
        val folded = lowestExtentAlong(vessel, up)
        for (i in legs) vessel.setLegDeploy(i, 1.0)
        val out = lowestExtentAlong(vessel, up)
        val offset = Vec3()
        for (i in legs) {
            var reach = Double.MAX_VALUE
            for (p in vessel.defs[i].contactPoints.indices) reach = minOf(reach, vessel.contactOffsetWorld(i, p, offset) dot up)
            val feetDown = out > folded + LEGS_REACH && -reach > out - LEGS_REACH
            if (feetDown) vessel.activated[i] = true else vessel.setLegDeploy(i, 0.0)
        }
    }

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
     * Stands [vessel] on the ground directly below it, as it's posed now. It's lifted or lowered
     * until its lowest contact just touches, moving with the surface and not turning. This is for
     * putting a craft down after changing its shape, like deploying its legs in place, without
     * dropping it or burying it.
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
     * Sets every craft that was resting on the ground back onto it, after loading onto different
     * terrain.
     *
     * The saved position was on the old ground, which might now be metres above or below it. A
     * parked rover would wake up buried and get flung out, or hovering and drop. Whether it's
     * resting is judged by motion, since the old ground is gone: barely moving relative to the
     * surface, and low enough that the surface is what it could be resting on. Craft in flight and
     * in orbit are left exactly where they were, and so is anything afloat.
     *
     * The craft keeps its orientation and gets lifted or lowered so its lowest contact point just
     * touches the new ground, moving with the surface. If it was parked on a slope, it settles onto
     * the new one by itself.
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
            // Each of these wakes its target first. A command means somebody is paying attention to
            // that craft, which is exactly the signal dormancy is waiting for.
            is Command.SetThrottle ->
                heard(command.vessel)?.control?.throttle = command.throttle

            is Command.SetAttitude -> heard(command.vessel)?.control?.let {
                it.pitch = command.pitch
                it.yaw = command.yaw
                it.roll = command.roll
            }

            is Command.SetSas ->
                heard(command.vessel)?.control?.sasEnabled = command.enabled

            is Command.SetSasMode -> heard(command.vessel)?.let {
                it.control.sasMode = command.mode
                // A new mode is a new hold, so start it from where the craft is.
                it.assistHolding = false
            }

            is Command.SetNavFrame -> waken(command.vessel)?.control?.navFrame = command.frame

            is Command.SetTarget -> waken(command.vessel)?.control?.let {
                it.target = if (command.target == command.vessel) -1L else command.target
                it.targetBody = if (command.body in system.bodies) command.body else ""
            }

            is Command.PlanBurns -> heard(command.vessel)?.let { vessel ->
                vessel.plannedBurns.clear()
                vessel.plannedBurns.addAll(command.burns.filter { it.time.isFinite() && it.deltaV.isFinite() }.sortedBy { it.time }.take(Burns.MOST))
                vessel.resetBurn()
                vessel.burnDuration = if (vessel.plannedBurns.isEmpty()) 0.0 else Burns.duration(vessel, vessel.plannedBurns.first().deltaV)
                pendingEvents.add(WorldEvent.VesselStructureChanged(vessel.id))
            }

            is Command.SetAutopilot -> heard(command.vessel)?.let { vessel ->
                val allowed = mayAutopilot(vessel)
                vessel.control.autoBurn = command.autoBurn && allowed
                vessel.control.autoLand = command.autoLand && allowed
                vessel.landBraking = false
                vessel.landStarted = false
                vessel.control.autopilotNote = if ((command.autoBurn || command.autoLand) && !allowed) "Autopilot not available" else ""
                if (!command.autoBurn && !command.autoLand) vessel.control.throttle = 0.0
            }

            is Command.SetBrakes ->
                heard(command.vessel)?.control?.brakes = command.engaged
            is Command.SetReverse ->
                heard(command.vessel)?.control?.reverse = command.engaged
            is Command.SetFlaps ->
                heard(command.vessel)?.control?.flaps = command.down
            is Command.SetCruise -> heard(command.vessel)?.let { setCruise(it, command.on) }
            is Command.ToggleGroup -> heard(command.vessel)?.let { toggleGroup(it, command.group) }
            is Command.Hook -> heard(command.vessel)?.let { hook(it) }
            is Command.Reel -> heard(command.vessel)?.let { vessel ->
                lineOf(vessel)?.reel = command.mode.coerceIn(-1, 1)
                vessel.wake()
            }
            is Command.ReleaseLine -> heard(command.vessel)?.let { releaseLine(it) }
            is Command.Deploy ->
                heard(command.vessel)?.control?.deployed = command.deployed
            is Command.SetBallast -> heard(command.vessel)?.let {
                it.wake()
                it.control.ballast = command.mode.coerceIn(-1, 1)
                it.control.holdDepth = false
                it.control.keeping = false
            }

            is Command.HoldDepth -> heard(command.vessel)?.let {
                it.wake()
                it.control.holdDepth = command.on
                it.control.ballast = 0
                it.control.keeping = false
                // Aloft it holds a height instead, kept as a depth below the datum.
                if (command.on) it.control.holdDepthAt = if (gasCraft(it)) -attractorFor(it).altitudeOf(it.body.position) else depthOf(it)
                it.heightIntegral = 0.0
            }

            is Command.SetStationKeep -> heard(command.vessel)?.let { setStationKeep(it, command.on) }

            is Command.SetIndustry -> heard(command.vessel)?.let {
                settlePower(it)
                it.control.drilling = command.drilling
                it.control.refining = command.refining
                if (!command.drilling) it.drillState = DrillState.OFF
                // A founded base never steps its pose, so its drills go straight down, or up.
                if (it.anchored) for (i in it.defs.indices) {
                    if (it.defs[i].module<com.rm.apogee.core.part.Drill>() != null) it.setLegDeploy(i, if (unfolded(it, i)) 1.0 else 0.0)
                }
            }
            is Command.Eva -> eva(command.vessel, command.crew)
            is Command.Board -> boardCraft(command.vessel, command.target)
            is Command.TransferCrew -> transferCrew(command.vessel, command.crew, command.part)
            is Command.Jump -> heard(command.vessel)?.let { jump(it) }
            is Command.Grab -> heard(command.vessel)?.let { grab(it, command.on) }
            is Command.PlantFlag -> heard(command.vessel)?.let { plantFlag(it) }
            is Command.ClimbOut -> heard(command.vessel)?.let { climbOut(it) }
            is Command.RightCraft -> heard(command.vessel)?.let { startRighting(it) }
            is Command.Unload -> if (command.active) unloading.add(VesselId(command.vessel)) else unloading.remove(VesselId(command.vessel))

            is Command.SetTranslation -> heard(command.vessel)?.control?.let {
                it.translateX = command.x
                it.translateY = command.y
                it.translateZ = command.z
            }

            is Command.SetRcs ->
                heard(command.vessel)?.control?.rcsEnabled = command.enabled

            is Command.Stage -> heard(command.vessel)?.let { stage(it) }

            // The server handles this, because it has to decide who owns and flies the result.
            // Getting here means nobody claimed it.
            is Command.SpawnCraft -> spawnFor(command, owner = "")

            is Command.Join -> waken(command.vessel)?.let { joinToNeighbour(it) }
            is Command.Anchor -> vesselsById[VesselId(command.vessel)]?.let { if (command.anchored) anchor(it) else unanchor(it) }
            is Command.Refuel -> if (command.active) refuelling.add(VesselId(command.vessel)) else refuelling.remove(VesselId(command.vessel))
            is Command.Undock -> heard(command.vessel)?.let { undock(it, command.part) }
            is Command.SetDockPilot -> Unit // the server's job: who can fly what

            // The server handles this, because it owns the idea of who's flying what. Getting to
            // the world means nobody was listening.
            is Command.SwitchVessel -> waken(command.vessel)

            is Command.Chat -> Unit // handled above the world
            is Command.SetWarp -> Unit // the server's clock, not the world's
            is Command.WarpTo -> Unit
            is Command.RemoveVessel -> destroy(VesselId(command.vessel), REMOVED_REASON)
            // Whose insight it is, is the server's call. See [unlock].
            is Command.Unlock -> Unit
        }
    }

    /**
     * Fires the next stage, and splits the craft at any decoupler in it.
     *
     * The part of the tree below the decoupler becomes a new vessel that keeps the motion it had,
     * plus a separation push. Splitting a body is all there is to staging here. There are no joints
     * to release, because parts are welded.
     */
    fun stage(vessel: Vessel) {
        val activated = vessel.activateNextStage()
        if (activated.isEmpty()) return
        pendingEvents.add(WorldEvent.Staged(vessel.id, vessel.currentStage))
        // Fairings open: the shell comes off in halves and the ring stays.
        for (index in activated) if (vessel.defs.getOrNull(index)?.module<com.rm.apogee.core.part.Fairing>() != null) openFairing(vessel, index)

        // Every decoupler in the stage, not just the first. Four radial boosters are four
        // decouplers firing together. Each split renumbers the craft that keeps flying, so the rest
        // are followed through it.
        var pending = activated.filter { index -> vessel.defs.getOrNull(index)?.module<Decoupler>() != null }
        while (pending.isNotEmpty()) {
            val kept = splitAt(vessel, pending.first()) ?: break
            pending = pending.drop(1).mapNotNull { old -> kept.indexOf(old).takeIf { it >= 0 } }
        }
    }

    /**
     * Separates the part of the tree below [decouplerIndex] into its own vessel.
     *
     * The decoupler itself stays with the half that's thrown away, which matches the usual
     * convention and means the craft that's left doesn't keep carrying dead weight.
     */
    /**
     * Welds [vessel] to the nearest craft it's touching.
     *
     * This is the reverse of [splitAt], and it's how a base gets built. Modules are landed, pushed
     * into place, and tied together into one structure. Doing it as a merge of part trees instead
     * of a new kind of link means everything downstream (mass, inertia, fuel crossfeed, collision,
     * saving) keeps working without knowing bases exist.
     *
     * They're welded where they stand instead of snapped onto attach nodes. A base is put together
     * by moving things into position, and snapping would teleport a module you've just spent a
     * minute placing.
     *
     * @return the merged vessel, or null if there was nothing to join to.
     */
    fun joinToNeighbour(vessel: Vessel): Vessel? {
        val partner = nearestJoinable(vessel) ?: return null
        return join(vessel, partner)
    }

    /** The closest craft touching [vessel] and near enough to rest against. */
    private fun nearestJoinable(vessel: Vessel): Vessel? {
        var best: Vessel? = null
        var bestDistance = Double.MAX_VALUE
        for (other in vesselsById.values) {
            if (other.id == vessel.id) continue
            if (other.referenceBodyId != vessel.referenceBodyId) continue
            // The Cape's own buildings aren't anyone's to weld to.
            if (other.owner == WORLD_OWNER || vessel.owner == WORLD_OWNER) continue
            // Two halves that just parted, or one still pushing the other.
            if (justSeparated.containsKey(pairKey(vessel.id.raw, other.id.raw))) continue
            // A deck and what's standing on it, a plane on a carrier or a buggy on a barge.
            if (onDeckOf(vessel, other) || onDeckOf(other, vessel)) continue

            scratch.setTo(vessel.body.position).subInPlace(other.body.position)
            val distance = scratch.length
            if (distance > vessel.contactRadius + other.contactRadius) continue

            // Only things it's resting against. Welding to something you're flying past is how a
            // docking mechanic turns into a grappling hook.
            scratch.setTo(vessel.body.linearVelocity).subInPlace(other.body.linearVelocity)
            if (scratch.length > JOIN_MAX_CLOSING_SPEED) continue

            if (distance < bestDistance) {
                bestDistance = distance
                best = other
            }
        }
        return best
    }

    /** Whether [rider] is standing on [deck], or asleep on it. */
    private fun onDeckOf(rider: Vessel, deck: Vessel): Boolean =
        rider.standingOn === deck || rider.ridingOn == deck.id

    /**
     * Merges [absorbed] into [keeper], keeping momentum, and removes it.
     *
     * The transform is the only fiddly bit. [absorbed]'s parts are described in its own design
     * space, and they have to be described again in [keeper]'s so every part ends up exactly where
     * it already is in the world.
     */
    fun join(keeper: Vessel, absorbed: Vessel, dock: DockJoin? = null): Vessel? {
        if (keeper.id == absorbed.id) return null
        if (keeper.referenceBodyId != absorbed.referenceBodyId) return null
        // A base takes in whatever joins it, never the other way round. It stays where it's
        // founded, and what came is fixed to it where it stands.
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
     * Founds [vessel] where it rests. From then on it's pinned to the ground, never woken, and
     * can't be moved by anything that hits it, though its parts still break. Only a craft with a
     * working [com.rm.apogee.core.part.Foundation], on the ground and still. Otherwise it returns
     * false and nothing changes.
     */
    fun anchor(vessel: Vessel): Boolean {
        if (vessel.anchored) return true
        if (!canAnchor(vessel)) return false
        val attractor = attractorFor(vessel)
        attractor.rotationAt(tickEnd, anchorRotation)
        // Standing on the ground, it's set level on its feet first. Afloat or aloft, it's pinned
        // as it floats.
        if (!floatingFoundable(vessel) && !level(vessel, attractor)) return false
        // Not a base down on the sea floor, though, which is in the water too.
        val onSea = !vessel.touchingGround && !vessel.submerged && (if (vessel.dormant) vessel.afloat else vessel.buoyed)
        vessel.anchor(anchorRotation)
        // Founded on the sea, it rides it: up and down with the swell, and tipped with it.
        if (onSea) settleAfloat(vessel, attractor)
        vessel.powerSettledAt = tickEnd
        attractor.surfaceVelocityAt(vessel.body.position, vessel.body.linearVelocity)
        attractor.angularVelocity(vessel.body.angularVelocity)
        pendingEvents.add(WorldEvent.VesselStructureChanged(vessel.id))
        program?.founded(this, vessel)
        return true
    }

    /**
     * Whether [vessel] floats where it is on its own and is still enough to be founded there: afloat
     * on the sea, or aloft, with its gas cells lifting its weight or its keeper core holding it.
     */
    private fun floatingFoundable(vessel: Vessel): Boolean {
        // Nor hanging in the water under it. Founded there it rode the sea at a depth nothing held
        // it at.
        if (vessel.touchingGround || vessel.submerged) return false
        val afloat = if (vessel.dormant) vessel.afloat else vessel.buoyed
        val aloft = !afloat && !vessel.dormant && (liftShare(vessel) >= FLOATS_ALONE || vessel.control.keeping)
        if (!afloat && !aloft) return false
        if (vessel.dormant) return true
        attractorFor(vessel).surfaceVelocityAt(vessel.body.position, scratch).subInPlace(vessel.body.linearVelocity)
        if (!afloat) return scratch.length < FLOAT_FOUND_SPEED
        // Afloat, only its drift across the sea counts, not its heaving and swaying with the waves.
        val up = vessel.body.position.normalized()
        scratch.addScaledInPlace(up, -(scratch dot up))
        return scratch.length < FLOAT_FOUND_DRIFT
    }

    /** Whether [vessel] could be founded where it is now. See [anchor]. */
    fun canAnchor(vessel: Vessel): Boolean {
        if (vessel.anchored) return false
        val footed = vessel.defs.indices.any { vessel.defs[it].module<com.rm.apogee.core.part.Foundation>() != null && !vessel.isBroken(it) }
        if (!footed) return false
        // A platform floating on its own, on the sea or in the sky.
        if (floatingFoundable(vessel)) return true
        if (vessel.dormant) { if (vessel.afloat) return false }
        else {
            // Under water a base weighs little more than the sea it's in, and it settles onto the
            // floor so lightly its feet only touch it now and then. There, its feet being down on the
            // floor, still, is enough, and the FOUND button doesn't blink.
            if (!vessel.touchingGround && !vessel.submerged) return false
            attractorFor(vessel).surfaceVelocityAt(vessel.body.position, scratch)
            if (scratch.subInPlace(vessel.body.linearVelocity).length >= ANCHOR_MAX_SPEED) return false
        }
        // Standing on its feet. A foundation still on the flatbed that brought it isn't on the
        // ground, whatever the truck is doing.
        return lowestFoot(vessel) <= FOOT_ON_GROUND
    }

    /**
     * How far the lowest foot of any working foundation of [vessel] is above the ground, in metres.
     */
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
     * Stands [vessel] level on its foundations' feet before it's founded. It's turned upright
     * around its centre (no further than the steepest ground its foundations can take) and set down
     * on its lowest foot, with the rest reaching down to the ground within their travel. Returns
     * false, leaving the craft as it was, where the ground is too steep or too uneven.
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
        // Down (or up) until the highest foot meets the ground.
        body.position.addScaledInPlace(up, -lowest)
        return true
    }

    // --- refuelling ----------------------------------------------------------------

    /** Craft being filled from a base, by id. */
    private val refuelling = LinkedHashSet<VesselId>()

    /** Whether [vessel] is being filled from a base right now. */
    fun isRefuelling(vessel: VesselId): Boolean = vessel in refuelling

    /** Craft emptying their ore and water into a base, or the craft docked to them, by id. */
    private val unloading = LinkedHashSet<VesselId>()

    /** Whether [vessel] is unloading right now. */
    fun isUnloading(vessel: VesselId): Boolean = vessel in unloading

    /** Whether [craft] has ore or water to unload, and somewhere with room for it. */
    fun canUnload(craft: Vessel): Boolean {
        val service = serviceFor(craft) ?: return false
        return UNLOAD_TYPES.any { service.craft.amountIn(service.into, it) > 1e-6 && service.base.roomIn(service.from, it) > 1e-6 }
    }

    /**
     * Where a craft gets filled from: the [base], the parts of it that give, and the [craft] parts
     * that take.
     */
    class Service(val base: Vessel, val from: List<Int>, val craft: Vessel, val into: List<Int>)

    /**
     * What [craft] could be filled from right now: the base whose pad deck it stands on, or if it's
     * docked to a base (and so one craft with it), the rest of that base. Null if neither.
     */
    fun serviceFor(craft: Vessel): Service? {
        // Docked: what came is what hangs from each ring that docked on.
        val docked = LinkedHashSet<Int>()
        for (i in craft.design.parts.indices) {
            if (craft.design.parts[i].dockedFrom != null) docked.addAll(craft.design.subtreeOf(i))
        }
        if (craft.anchored) {
            if (docked.isEmpty()) return null
            return Service(craft, craft.defs.indices.filter { it !in docked }, craft, docked.toList())
        }
        // Two craft docked in flight. The one being flown is the one that stayed, and it gets
        // filled from, or emptied into, whatever docked onto it.
        if (docked.isNotEmpty()) return Service(craft, docked.toList(), craft, craft.defs.indices.filter { it !in docked })
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
                // Over the deck and standing on it, with its lowest point near the top.
                if (offset.length > pad.boundsHalfExtents.x) continue
                if (height - lowestExtentAlong(craft, up) - top > PAD_SERVICE_HEIGHT) continue
                return Service(base, base.defs.indices.toList(), craft, craft.defs.indices.toList())
            }
        }
        return null
    }

    /**
     * Whether [craft] could be filled from a base right now: standing on or docked to one, with
     * room for something it has.
     */
    fun canRefuel(craft: Vessel): Boolean {
        val service = serviceFor(craft) ?: return false
        val endless = service.base.owner == WORLD_OWNER
        return REFUEL_TYPES.any { type ->
            room(service, type) > 1e-6 && (endless || service.base.amountIn(service.from, type) > 1e-6)
        }
    }

    /**
     * Room for [type] in what [service] fills. There's none for charge within a trickle of full,
     * because a craft sitting there draws on it as fast as it's topped up, and it would never
     * finish.
     */
    private fun room(service: Service, type: com.rm.apogee.core.part.ResourceType): Double {
        val room = service.craft.roomIn(service.into, type)
        return if (type == com.rm.apogee.core.part.ResourceType.ELECTRIC_CHARGE && room < CHARGE_TOPPED) 0.0 else room
    }

    private fun stepUnloading(dt: Double) {
        if (unloading.isEmpty()) return
        val iterator = unloading.iterator()
        while (iterator.hasNext()) {
            val id = iterator.next()
            val craft = vesselsById[id]
            val service = craft?.let { serviceFor(it) }
            val stop = when {
                craft == null -> null
                service == null -> "nothing to unload into here"
                else -> pump(service, dt, unload = true)
            }
            if (craft == null || stop != null) {
                iterator.remove()
                if (craft != null) pendingEvents.add(WorldEvent.RefuelStopped(id, stop!!))
            }
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

    /**
     * One tick of pumping for [service], either into the craft or, when [unload]ing, its ore and
     * water out of it. Returns why it stopped, or null while it's still going.
     */
    private fun pump(service: Service, dt: Double, unload: Boolean = false): String? {
        val base = service.base
        var rate = 0.0
        var draw = 0.0
        for (i in service.from) {
            val pump = base.defs[i].module<com.rm.apogee.core.part.Pump>() ?: continue
            if (base.isBroken(i)) continue
            if (pump.rate > rate) { rate = pump.rate; draw = pump.draw }
        }
        // Two craft docked: their own plumbing, through the rings.
        if (rate <= 0.0 && service.base === service.craft && !service.base.anchored) rate = DOCKED_TRANSFER
        if (rate <= 0.0) return "this base has no pump"
        if (unload) return unloadStep(service, rate, draw, dt)
        // The world's own bases, like the Cape and Luna's test base, never run dry or dark.
        val endless = base.owner == WORLD_OWNER
        if (!endless && (!base.powered || !base.drawCharge(draw * dt))) return "the base has no power"
        var moved = 0.0
        for (type in REFUEL_TYPES) {
            val want = minOf(rate * dt, room(service, type), if (endless) Double.MAX_VALUE else base.amountIn(service.from, type))
            if (want <= 1e-9) continue
            val out = if (endless) want else base.takeFrom(service.from, type, want)
            moved += service.craft.putInto(service.into, type, out)
        }
        if (moved <= 1e-9) {
            // Full of everything the base has to give, or the base is out of what the craft still
            // wants.
            val wanting = REFUEL_TYPES.any { room(service, it) > 1e-9 && base.amountIn(service.from, it) > 1e-9 }
            val holds = REFUEL_TYPES.any { base.amountIn(service.from, it) > 1e-9 }
            return if (!wanting && holds) "full" else "the base has nothing more to give"
        }
        base.recomputeMass()
        if (service.craft !== base) service.craft.recomputeMass()
        return null
    }

    /** One tick of [service]'s craft emptying its ore and water into the base. See [pump]. */
    private fun unloadStep(service: Service, rate: Double, draw: Double, dt: Double): String? {
        val base = service.base
        if (draw > 0.0 && (!base.powered || !base.drawCharge(draw * dt))) return "the base has no power"
        var moved = 0.0
        for (type in UNLOAD_TYPES) {
            val want = minOf(rate * dt, service.craft.amountIn(service.into, type), base.roomIn(service.from, type))
            if (want <= 1e-9) continue
            val out = service.craft.takeFrom(service.into, type, want)
            moved += base.putInto(service.from, type, out)
        }
        if (moved <= 1e-9) {
            val holding = UNLOAD_TYPES.any { service.craft.amountIn(service.into, it) > 1e-9 }
            return if (holding) "no room for it here" else "empty"
        }
        base.recomputeMass()
        if (service.craft !== base) service.craft.recomputeMass()
        return null
    }

    // --- the Cape's own buildings --------------------------------------------------

    /**
     * Puts up any of the Cape's buildings that are missing: the launch complex, the airfield and
     * the harbour, each a founded base belonging to the world. A new world and one saved before
     * they existed both get them, and one that has them keeps the ones it has.
     */
    fun ensureStructures() {
        if (SolarSystem.HOMEWORLD_ID !in system.bodies) return
        capeBuilt = true
        for (complex in com.rm.apogee.core.craft.StockStructures.complexes) {
            if (structureOf(complex) != null) continue
            raiseStructure(complex)
        }
        // Luna's, from before it had a name of its own.
        vesselsById.values.firstOrNull { it.owner == WORLD_OWNER && it.name == WorldBases.OLD_LUNA_NAME }
            ?.let { it.name = WorldBases.all.first { b -> b.bodyId == "luna" }.name }
        // What lies on the sea floor to be found: an arch and wrecks.
        for (wonder in SeaWonders.all) if (wonder.landmark.isNotEmpty() && landmark(wonder) == null) raiseLandmark(wonder)
        if (program == null) {
            for (base in WorldBases.all) if (worldBase(base.bodyId) == null) raiseWorldBase(base)
        } else {
            // A career builds its own, so the world's aren't there to find.
            for (standing in worldBases()) destroy(standing.id, "not in a career")
        }
    }

    // --- the world's bases, in free play ------------------------------------------------

    /**
     * The world's base on [bodyId], if it has one standing: a pad base next to that world's test
     * site, somewhere to launch from and refuel at in free play without flying there. Its stores
     * never run dry and its power never runs out, the same as the Cape's. See [WorldBases].
     */
    fun worldBase(bodyId: String): Vessel? {
        val name = WorldBases.all.firstOrNull { it.bodyId == bodyId }?.name ?: return null
        return vesselsById.values.firstOrNull { it.owner == WORLD_OWNER && it.name == name && it.anchored }
    }

    /** What stands at [wonder] to be found, if it's there. */
    fun landmark(wonder: SeaWonders.Wonder): Vessel? =
        vesselsById.values.firstOrNull { it.owner == WORLD_OWNER && it.name == wonder.landmark && it.anchored }

    /**
     * Puts [wonder]'s landmark on the sea floor there: the Great Arch standing, or a wreck lying
     * the way it came to rest (a rocket on its side, a trawler heeled over), pinned where it lies
     * and belonging to the world.
     */
    private fun raiseLandmark(wonder: SeaWonders.Wonder): Vessel? {
        if (wonder.bodyId !in system.bodies) return null
        val body = system.body(wonder.bodyId)
        val terrain = body.terrain ?: return null
        val (design, tip) = when (wonder.landmark) {
            SeaWonders.GREAT_ARCH -> CraftDesign("Rock Arch", listOf(com.rm.apogee.core.craft.PlacedPart("rock-arch", Vec3.zero())), catalogHash = catalog.contentHash) to 0.0
            SeaWonders.LOST_SOUNDER -> com.rm.apogee.core.craft.StockCraft.sounder(catalog) to Math.toRadians(84.0)
            SeaWonders.CANYON_WRECK -> com.rm.apogee.core.craft.StockCraft.trawler(catalog) to Math.toRadians(22.0)
            SeaWonders.PLAIN_ROCKET -> com.rm.apogee.core.craft.StockCraft.starterRocket(catalog) to Math.toRadians(88.0)
            else -> return null
        }
        val up = wonder.direction.normalized()
        val ground = body.radius + terrain.elevation(up)
        val east = Vec3(0.0, 1.0, 0.0).crossInPlace(up).normalizeInPlace()
        val standing = com.rm.apogee.core.math.quatFromTo(design.orientation.up, up)
        val local = (Quat.fromAxisAngle(east, tip) * standing).normalizeInPlace()
        body.rotationAt(time, scratchRotation)
        val rotation = (scratchRotation * local).normalizeInPlace()
        val upNow = scratchRotation.rotate(up, Vec3())
        val wreck = spawnAt(design, body.id, Vec3().setTo(upNow).mulInPlace(ground), Vec3(), rotation)
        // Resting on the floor, not in it, as low as it goes on the ground.
        wreck.body.position.setTo(upNow).mulInPlace(ground + lowestExtentAlong(wreck, upNow))
        body.surfaceVelocityAt(wreck.body.position, wreck.body.linearVelocity)
        assignOwner(wreck, WORLD_OWNER)
        wreck.ownerName = ""
        wreck.name = wonder.landmark
        pin(wreck)
        return wreck
    }

    /** Every one of the world's bases that's standing. */
    fun worldBases(): List<Vessel> = vesselsById.values.filter { it.owner == WORLD_OWNER && WorldBases.named(it.name) != null }

    /** Luna's. See [worldBase]. */
    fun lunaBase(): Vessel? = worldBase("luna")

    private fun raiseWorldBase(spec: WorldBases.Base): Vessel? {
        if (spec.bodyId !in system.bodies) return null
        val site = launchSites.firstOrNull { it.id == spec.siteId } ?: return null
        val base = spawnOnSurface(com.rm.apogee.core.craft.StockCraft.padBase(catalog), site, pad = WorldBases.PAD)
        assignOwner(base, WORLD_OWNER)
        base.ownerName = ""
        base.name = spec.name
        val attractor = attractorFor(base)
        attractor.rotationAt(tickEnd, anchorRotation)
        level(base, attractor)
        base.anchor(anchorRotation)
        return base
    }

    /** Whether this world has the Cape's buildings to look after. See [ensureStructures]. */
    private var capeBuilt = false

    /** Since when nothing awake has been near each complex, by name. See [repairStructures]. */
    private val quietSince = HashMap<String, Double>()

    /**
     * Rebuilds any of the Cape's buildings that have been broken (parts lost or hurt) once nothing
     * awake has come within [REPAIR_REACH] of them for [REPAIR_QUIET] seconds, or straight away
     * when [now] is set. The wreckage is cleared and the complex is put back the way it was built.
     * Nobody sees it happen, and the start of the game is never left in ruins.
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
            // Whatever fell off it and is lying around: every loose craft made of nothing but
            // buildings near it.
            val wreckage = vesselsById.values.filter { v ->
                !v.anchored && v.referenceBodyId == body.id &&
                    v.defs.all { it.category == com.rm.apogee.core.part.PartCategory.STRUCTURE } &&
                    body.toBodyFixed(v.body.position, scratchRotation, here).distanceTo(site) < REPAIR_REACH
            }
            standing?.let { destroy(it.id, "rebuilt") }
            for (piece in wreckage) destroy(piece.id, "cleared away")
            raiseStructure(complex)
        }
        if (program == null) for (base in WorldBases.all) repairWorldBase(base, now)
    }

    /**
     * The world's base on a world, put back if it's broken, once nothing awake is near it, or
     * straight away when [now] is set.
     */
    private fun repairWorldBase(spec: WorldBases.Base, now: Boolean) {
        val site = launchSites.firstOrNull { it.id == spec.siteId } ?: return
        if (site.bodyId !in system.bodies) return
        val body = system.body(site.bodyId)
        body.rotationAt(time, scratchRotation)
        val at = SolarSystem.surfaceDirection(site.latitude, site.longitude).mulInPlace(body.radius)
        val here = Vec3()
        val busy = vesselsById.values.any { v ->
            !v.dormant && v.owner != WORLD_OWNER && v.referenceBodyId == body.id &&
                body.toBodyFixed(v.body.position, scratchRotation, here).distanceTo(at) < REPAIR_REACH
        }
        if (busy) { quietSince.remove(spec.name); return }
        val since = quietSince.getOrPut(spec.name) { time }
        val standing = worldBase(spec.bodyId)
        val whole = standing != null && standing.defs.size == com.rm.apogee.core.craft.StockCraft.padBase(catalog).parts.size &&
            standing.broken.none { it } && standing.health.all { it >= 1.0 }
        if (whole) return
        if (!now && time - since < REPAIR_QUIET) return
        standing?.let { destroy(it.id, "rebuilt") }
        raiseWorldBase(spec)
    }

    /**
     * Whether [standing] is all of [complex], whole, and the way it's designed now. A world saved
     * before a building was moved or turned gets the new layout, the same as a broken one would.
     */
    private fun intact(standing: Vessel, complex: com.rm.apogee.core.craft.StockStructures.Complex): Boolean {
        if (standing.broken.any { it } || standing.health.any { it < 1.0 }) return false
        val canon = canonicalStructures.getOrPut(complex.name) { com.rm.apogee.core.craft.StockStructures.design(complex, catalog) }.parts
        val parts = standing.design.parts
        return parts.size == canon.size && canon.indices.all { i ->
            parts[i].partId == canon[i].partId && parts[i].position.distanceTo(canon[i].position) < 0.01 &&
                kotlin.math.abs(parts[i].rotation dot canon[i].rotation) > 0.99999
        }
    }

    /** Each complex as designed, by name. This is what [intact] holds a standing one to. */
    private val canonicalStructures = HashMap<String, com.rm.apogee.core.craft.CraftDesign>()

    /** The world's standing copy of [complex], if it has one. */
    fun structureOf(complex: com.rm.apogee.core.craft.StockStructures.Complex): Vessel? =
        vesselsById.values.firstOrNull { it.owner == WORLD_OWNER && it.name == complex.name && it.anchored }

    /** [complex] built where it belongs, founded and belonging to the world. */
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
        // Its centre where its centre is, with the design's origin on the ground.
        probe.body.position.setTo(origin).addInPlace(rotation.rotate(probe.centerOfMass(Vec3()), Vec3()))
        body.surfaceVelocityAt(probe.body.position, probe.body.linearVelocity)
        assignOwner(probe, WORLD_OWNER)
        probe.ownerName = ""
        probe.name = complex.name
        pin(probe)
        return probe
    }

    // --- launching from bases -----------------------------------------------------

    /**
     * A player's own, plus the world's too in free play. A career's players only have their own.
     */
    fun mayLaunchFrom(base: Vessel, owner: String): Boolean = base.owner == owner || (base.owner == WORLD_OWNER && program == null)

    /** The pads [owner] can launch from on founded bases, as launch sites. */
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
     * A craft set upright on the deck of [base]'s pad [pad], with its tanks filled from the base's
     * stores as far as they go, if the base has the power to pump. If the base is dark, it's put
     * there empty.
     */
    fun spawnOnBasePad(design: CraftDesign, base: Vessel, pad: Int, legsOut: Boolean = false): Vessel {
        val site = baseSites(base.owner).firstOrNull { it.id == "${LaunchSite.BASE_SITE_PREFIX}${base.id.raw}:$pad" }
            ?: error("no pad $pad on ${base.name}")
        val craft = spawnOnSurface(design, site, legsOut = legsOut)
        // On the deck, not on the ground under it.
        val up = Vec3().setTo(craft.body.position).normalizeInPlace()
        val top = Vec3().setTo(base.partPositionWorld(pad)).addScaledInPlace(up, base.defs[pad].boundsHalfExtents.y)
        craft.body.position.setTo(up).mulInPlace(top.length + lowestExtentAlong(craft, up) + 0.02)
        // Moving with the deck: the ground's speed, or on a base afloat, the swell's too.
        if (base.afloat) base.body.velocityAtOffset(Vec3().setTo(craft.body.position).subInPlace(base.body.position), craft.body.linearVelocity)
        else attractorFor(craft).surfaceVelocityAt(craft.body.position, craft.body.linearVelocity)

        // Whatever it carries, it carries from the base.
        settlePower(base)
        val all = craft.defs.indices.toList()
        // The world's own bases supply it in full, the same as the Cape does.
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

    /** What [vessel]'s fuel cells make over [h] seconds while it's parked, in charge a second. */
    private fun fuelCells(vessel: Vessel, anyCell: Int, cells: Double, cellMono: Double, h: Double): Double {
        val capacity = vessel.capacityOf(com.rm.apogee.core.part.ResourceType.ELECTRIC_CHARGE)
        if (capacity <= 0.0) return 0.0
        val share = vessel.amountOf(com.rm.apogee.core.part.ResourceType.ELECTRIC_CHARGE) / capacity
        if (!vessel.fuelCellsOn && share < Power.CELLS_ON) vessel.fuelCellsOn = true
        else if (vessel.fuelCellsOn && share > Power.CELLS_OFF) vessel.fuelCellsOn = false
        if (!vessel.fuelCellsOn) return 0.0
        val want = cellMono * h
        val got = vessel.drainFromGroupOf(anyCell, com.rm.apogee.core.part.ResourceType.MONOPROPELLANT, want)
        if (got <= 0.0) { vessel.fuelCellsOn = false; return 0.0 }
        return cells * (got / want)
    }

    /**
     * Brings a founded base's power up to [until]. Its panels' charge comes in, depending on how
     * high the sun has stood over it, and its core's upkeep and its lamps after dusk go out. It's
     * worked out over the time since it was last done, in steps of a minute, or across a long
     * absence up to [POWER_MAX_STEPS] of them. Nobody needs to be near, because a base keeps a
     * ledger, not ticks.
     */
    fun settlePower(vessel: Vessel, until: Double = time) {
        val from = vessel.powerSettledAt
        vessel.powerSettledAt = until
        if (from.isNaN() || until <= from) return
        val attractor = attractorFor(vessel)
        var solar = 0.0
        var upkeep = 0.0
        var lamps = 0.0
        var cells = 0.0
        var cellMono = 0.0
        var anyCell = -1
        for (i in vessel.defs.indices) {
            if (vessel.isBroken(i)) continue
            for (module in vessel.defs[i].modules) when (module) {
                // Folded, a wing makes nothing, and a fixed panel on the ground meets the sun at an
                // angle.
                is com.rm.apogee.core.part.SolarPanel -> solar += module.chargeRate * when {
                    module.deployable -> if (Power.deployed(vessel, i)) 1.0 else 0.0
                    module.normal != null && !vessel.anchored -> 0.5
                    else -> 1.0
                }
                is com.rm.apogee.core.part.Command -> upkeep += if (vessel.anchored) BASE_UPKEEP else module.idleDraw
                is com.rm.apogee.core.part.Antenna -> if (!module.deployable || Power.deployed(vessel, i)) upkeep += module.draw
                is com.rm.apogee.core.part.Scanner -> upkeep += module.draw
                is com.rm.apogee.core.part.Generator -> upkeep -= module.rate
                is com.rm.apogee.core.part.Lamp -> lamps += module.draw
                is com.rm.apogee.core.part.FuelCell -> { cells += module.rate; cellMono += module.rate * module.monoPerCharge; anyCell = i }
                else -> Unit
            }
        }
        val site = vessel.sleepDirection(powerSite)
        // What the air lets through at ground level. A tenth under Caligo's deck. And what the sea
        // does, for a base on the floor of it.
        val gloom = (if (attractor.atmosphere == null) 1.0
            else Climate.of(attractor.id)?.sunThrough(vessel.body.position.length - attractor.radius) ?: 1.0) *
            Power.seaShade(attractor, vessel.body.position)
        // Down where the daylight's gone, its lamps are on all day.
        val deep = attractor.ocean != null && attractor.altitudeOf(vessel.body.position) < -LAMP_DEPTH
        val step = maxOf(POWER_STEP, (until - from) / POWER_MAX_STEPS)
        var t = from
        var net = 0.0
        while (t < until) {
            val h = minOf(step, until - t)
            // Faint among the giants, the same as panels anywhere.
            val sun = sunHeight(attractor, site, t + 0.5 * h) * system.sunStrength(attractor.id, vessel.body.position, t + 0.5 * h)
            net = solar * gloom * maxOf(0.0, sun) - upkeep - if (sun < LAMP_DUSK || deep) lamps else 0.0
            // Its fuel cells, the same as a craft's: on when it's running low, off when it's well
            // back up, and only while there's monopropellant to burn.
            if (anyCell >= 0) net += fuelCells(vessel, anyCell, cells, cellMono, h)
            // Drills and converters, within whatever charge there is. It's parked on the ground
            // where it stands now, so it's posed as it is now.
            if (vessel.control.drilling || vessel.control.refining) {
                val charged = vessel.amountOf(com.rm.apogee.core.part.ResourceType.ELECTRIC_CHARGE) > 0.0 || net > 0.0
                net -= industry.step(vessel, attractor, time, h, still = !vessel.afloat, charge = charged)
            }
            if (net >= 0.0) vessel.storeCharge(net * h)
            else if (!vessel.drawCharge(-net * h)) vessel.drawCharge(vessel.amountOf(com.rm.apogee.core.part.ResourceType.ELECTRIC_CHARGE))
            t += h
        }
        vessel.powerNet = net
        vessel.powered = if (vessel.anchored) vessel.amountOf(com.rm.apogee.core.part.ResourceType.ELECTRIC_CHARGE) > 0.0 || net > 0.0
        else Power.poweredNow(vessel, vessel.capacityOf(com.rm.apogee.core.part.ResourceType.ELECTRIC_CHARGE), net)
    }

    /** How high the sun stands over body-fixed unit [site] at [at]: the sine of its elevation, below 0 at night. */
    fun sunHeight(attractor: CelestialBody, site: Vec3, at: Double): Double {
        attractor.rotationAt(at, powerRotation)
        powerRotation.rotate(site, powerUp)
        return powerUp dot system.sunDirection(attractor.id, powerSun.setTo(powerUp).mulInPlace(attractor.radius), at, powerSun)
    }

    /** The direction toward the star from [vessel], inertial and unit length, right now. */
    fun sunDirection(vessel: Vessel, out: Vec3 = Vec3()): Vec3 = system.sunDirection(vessel.referenceBodyId, vessel.body.position, time, out)

    private val powerSite = Vec3()
    private val powerUp = Vec3()
    private val powerSun = Vec3()
    private val powerRotation = Quat.identity()

    /** Lets a founded [vessel] go, so it's a craft like any other again, awake. */
    fun unanchor(vessel: Vessel): Boolean {
        if (!vessel.anchored) return false
        vessel.unanchor()
        pendingEvents.add(WorldEvent.VesselStructureChanged(vessel.id))
        return true
    }

    /** Takes an anchored craft's pose again after its structure, and so its centre of mass, changed. */
    private fun reseat(vessel: Vessel) {
        if (!vessel.anchored) return
        attractorFor(vessel).rotationAt(tickEnd, anchorRotation)
        vessel.reanchor(anchorRotation)
    }

    private val anchorRotation = Quat.identity()

    /** The angular momentum of [vessel] around [centre], for a body moving at [velocity]. */
    private fun angularMomentumAbout(vessel: Vessel, centre: Vec3, velocity: Vec3): Vec3 {
        val inertiaWorld = com.rm.apogee.core.math.Mat3()
            .setRotated(vessel.body.inertiaLocal, vessel.body.orientation)
        val spin = inertiaWorld.transform(vessel.body.angularVelocity, Vec3())
        // Plus the orbital term: the craft's own centre swinging around the combined one. Dropping
        // this quietly loses the rotation you get from welding two things that were drifting past
        // each other.
        val lever = Vec3().setTo(vessel.body.position).subInPlace(centre)
        val relative = Vec3().setTo(vessel.body.linearVelocity).subInPlace(velocity)
        return spin.addInPlace(lever.crossInPlace(relative).mulInPlace(vessel.body.mass))
    }

    /**
     * [absorbed]'s parts, described again in [keeper]'s design space so each one lands exactly
     * where it already is in the world.
     */
    private fun mergeDesigns(keeper: Vessel, absorbed: Vessel, dock: DockJoin? = null): CraftDesign? {
        val offset = keeper.design.parts.size
        val parts = ArrayList<PlacedPart>(offset + absorbed.design.parts.size)
        parts.addAll(keeper.design.parts)

        // The part of the keeper nearest the absorbed craft becomes the parent of its root, so the
        // tree stays connected and staging still has something to walk through. When docked, its
        // ring is the joint instead. The absorbed craft is re-rooted at its own ring and hung from
        // the keeper's, so undocking just cuts that one joint.
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
                    // A weld, not a node attachment. The transform is what counts, and there's no
                    // node pair to name.
                    parentNodeId = if (placed.parentIndex < 0) null else placed.parentNodeId,
                    ownNodeId = if (placed.parentIndex < 0) null else placed.ownNodeId,
                    symmetryGroup = -1,
                    dockedTo = if (placed.dockedTo >= 0) placed.dockedTo + offset else -1,
                )
            )
        }
        if (dock != null) {
            // Each ring knows its partner, and the one that came knows what it came as.
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

        // The keeper's design, extended. If it was built lying down, it stays lying down.
        return keeper.design.copy(parts = parts, stages = stages)
    }

    /** Everyone with a claim on [vessel]: its owner, and the owner of each craft docked into it. */
    fun ownersOf(vessel: Vessel): Set<String> =
        (listOf(vessel.owner) + vessel.design.parts.mapNotNull { it.dockedFrom?.owner }).filter { it.isNotBlank() }.toSet()

    /**
     * Lets [a] and [b] only touch gently for [seconds], as two halves that just parted, when a
     * replica first sees them.
     */
    fun graceBetween(a: VesselId, b: VesselId, seconds: Double) {
        justSeparated[pairKey(a.raw, b.raw)] = time + seconds
    }

    /** A docking join: which part of each craft is the ring they latched by. */
    class DockJoin(val keeperPart: Int, val absorbedPart: Int)

    /**
     * Docking, each tick after craft have moved and met. The magnets pull, captures latch into one
     * craft or a coupled hitch, and coupled hitches hold their two craft together.
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
        solveLines(dt)
        deckGear.step(vesselsById, dt)
    }

    /** Arresting wires and catapults on decks. */
    private val deckGear = DeckGear()

    /**
     * Latches two rings. The lighter craft docks onto the heavier, set square on its ring (the last
     * centimetres and degrees the magnets left), and the two become one craft.
     */
    fun dockPorts(a: Vessel, partA: Int, b: Vessel, partB: Int): Vessel? {
        // The heavier one keeps it, or a base does, whatever it weighs.
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
        // A craft belonging to nobody, taken in hand by somebody's, is theirs now.
        if (keeper.owner.isBlank() && absorbed.owner.isNotBlank()) {
            keeper.owner = absorbed.owner
            keeper.ownerName = absorbed.ownerName
        }
        val joined = join(keeper, absorbed, DockJoin(keeperPart, absorbedPart)) ?: return null
        pendingEvents.add(WorldEvent.Docked(keeper.id, absorbed.id, at, keeper.referenceBodyId))
        return joined
    }

    private data class Quad(val a: Vessel, val ia: Int, val b: Vessel, val ib: Int)

    /** Couples a hitch. The two craft stay separate, turning around the one point. */
    private fun couple(a: Vessel, partA: Int, b: Vessel, partB: Int) {
        docking.forget(a.id.raw); docking.forget(b.id.raw)
        links.add(Link(a.id, partA, b.id, partB))
        val ref = com.rm.apogee.core.physics.PortRef(a, partA, a.defs[partA].module<com.rm.apogee.core.part.DockingPort>()!!).update()
        pendingEvents.add(WorldEvent.Hitched(a.id, b.id, coupled = true, ref.face.copy(), a.referenceBodyId))
    }

    /**
     * Lets go of whatever docking part [part] of [vessel] holds. A ring or clamp undocks, so the
     * craft that docked on gets its own name, owner and staging back, and the two are pushed gently
     * apart. A hitch uncouples. False if it wasn't holding anything.
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
     * Splits the part of the tree under [root] off [vessel] as a craft of its own, pushed away
     * along [along] (in world space, the way the root's part faces, so backward for the piece) with
     * [impulse] N·s each way. Returns the new craft.
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
        // Its place in its own staging: past every stage whose parts have all fired.
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
     * Holds each coupled hitch together. It's a point joint, solved as impulses on the two craft:
     * the relative velocity of the two hitch points along each axis is taken out, plus a share of
     * any gap, so the towed craft follows along and turns freely around the ball. Yank it too hard
     * and it breaks.
     */
    private fun solveLinks(dt: Double) {
        if (links.isEmpty()) return
        val broken = ArrayList<Link>()
        for (link in links) {
            val a = vesselsById[link.a]; val b = vesselsById[link.b]
            if (a == null || b == null || link.partA !in a.defs.indices || link.partB !in b.defs.indices) { broken.add(link); continue }
            // Both awake, or both asleep. A towed craft never sleeps while its tug is moving.
            if (!a.dormant || !b.dormant) { if (a.dormant) a.wake(); if (b.dormant) b.wake() }
            if (a.dormant && b.dormant) continue
            val pa = com.rm.apogee.core.physics.PortRef(a, link.partA, a.defs[link.partA].module<com.rm.apogee.core.part.DockingPort>()!!).update()
            val pb = com.rm.apogee.core.physics.PortRef(b, link.partB, b.defs[link.partB].module<com.rm.apogee.core.part.DockingPort>()!!).update()
            val gap = Vec3().setTo(pb.face).subInPlace(pa.face)
            var total = 0.0
            val axis = Vec3(); val va = Vec3(); val vb = Vec3(); val ra = Vec3(); val rb = Vec3()
            // Every pass aims at the same closing speed, a share of the gap per tick. Otherwise the
            // later passes undo the first one's correction, and a gap, once opened, never closes.
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

    // --- winches ------------------------------------------------------------------------------

    /** [vessel]'s first whole winch, and its index. */
    private fun winchOf(vessel: Vessel): Pair<Int, com.rm.apogee.core.part.Winch>? {
        for (i in vessel.defs.indices) {
            if (vessel.isBroken(i)) continue
            val winch = vessel.defs[i].module<com.rm.apogee.core.part.Winch>() ?: continue
            return i to winch
        }
        return null
    }

    /** Where a winch's line comes out, as an offset from its craft's centre, world axes, into [out]. */
    private fun winchFace(vessel: Vessel, index: Int, winch: com.rm.apogee.core.part.Winch, out: Vec3): Vec3 =
        vessel.partPointOffsetWorld(index, Vec3(0.0, winch.faceOffset, 0.0), out)

    /**
     * What [vessel]'s winch could hook onto if asked: its nearest other craft's part in reach in front
     * of it (within [HOOK_CONE] of straight ahead), or failing that, the ground in front of it. Null
     * with no winch, a line already out, or nothing in reach.
     */
    class HookTarget(val craft: Vessel?, val part: Int, val hook: Vec3, val ground: Vec3, val distance: Double)

    fun hookTarget(vessel: Vessel): HookTarget? {
        val (index, winch) = winchOf(vessel) ?: return null
        if (lineOf(vessel) != null) return null
        val attractor = attractorFor(vessel)
        val offset = winchFace(vessel, index, winch, Vec3())
        val face = Vec3().setTo(offset).addInPlace(vessel.body.position)
        val ahead = vessel.design.parts[index].rotation.rotate(Vec3.unitY())
        vessel.body.orientation.rotate(ahead, ahead)
        // Another craft's part: the nearest tow point in reach, or failing one, the nearest part.
        var best: HookTarget? = null
        var bestFast = false
        val point = Vec3(); val toward = Vec3()
        for (other in vesselsById.values) {
            if (other === vessel || other.referenceBodyId != vessel.referenceBodyId) continue
            if (other.body.position.distanceTo(face) > winch.reach + other.contactRadius) continue
            for (j in other.defs.indices) {
                if (other.isBroken(j)) continue
                other.partPointOffsetWorld(j, Vec3(), point).addInPlace(other.body.position)
                toward.setTo(point).subInPlace(face)
                val d = toward.length
                if (d > winch.reach || d < 1e-3) continue
                if (!winch.anyWay && (toward dot ahead) / d < HOOK_CONE) continue
                val fast = other.defs[j].hasModule<com.rm.apogee.core.part.TowPoint>()
                if (best == null || (fast && !bestFast) || (fast == bestFast && d < best.distance)) {
                    best = HookTarget(other, j, Vec3(), Vec3(), d)
                    bestFast = fast
                }
            }
        }
        if (best != null) return best
        // The ground, a little way ahead: along the line flattened onto the ground, where the
        // terrain is there.
        if (attractor.terrain == null) return null
        val up = Vec3().setTo(vessel.body.position).normalizeInPlace()
        val flat = Vec3().setTo(ahead).addScaledInPlace(up, -(ahead dot up))
        if (flat.length < 0.2) flat.setTo(ahead) else flat.normalizeInPlace()
        val rotation = attractor.rotationAt(time)
        point.setTo(face).addScaledInPlace(flat, minOf(winch.reach, GROUND_HOOK_DISTANCE))
        val direction = attractor.toBodyFixed(point, rotation).normalizeInPlace()
        val ground = Vec3().setTo(direction).mulInPlace(attractor.surfaceRadiusInBodyFrame(direction))
        val distance = rotation.rotate(ground).distanceTo(face)
        return if (distance <= winch.reach) HookTarget(null, -1, Vec3(), ground, distance) else null
    }

    /** Hooks [vessel]'s winch onto what [hookTarget] finds. False if there's nothing. */
    fun hook(vessel: Vessel): Boolean {
        val (index, _) = winchOf(vessel) ?: return false
        val target = hookTarget(vessel) ?: return false
        lines.add(Line(vessel.id, index, target.craft?.id, target.part, target.hook, target.ground, vessel.referenceBodyId, target.distance + LINE_SLACK))
        vessel.wake()
        target.craft?.wake()
        pendingEvents.add(WorldEvent.Winched(vessel.id, target.craft?.id, hooked = true))
        return true
    }

    /** Lets go of [vessel]'s line. False if it had none. */
    fun releaseLine(vessel: Vessel): Boolean {
        val line = lineOf(vessel) ?: return false
        lines.remove(line)
        pendingEvents.add(WorldEvent.Winched(vessel.id, line.b, hooked = false))
        return true
    }

    /**
     * Each winch line, as a rope: it pulls only when it's taut, along the line, and never pushes.
     * Winding in shortens it, while there's charge. It pulls no harder than its winch can, and past
     * that the drum slips and lets line out instead. A sudden yank well past that, with the two
     * flying apart, snaps it.
     */
    private fun solveLines(dt: Double) {
        for (vessel in vesselsById.values) vessel.winchDraw = 0.0
        if (lines.isEmpty()) return
        val gone = ArrayList<Pair<Line, Boolean>>()
        val offA = Vec3(); val offB = Vec3(); val pa = Vec3(); val pb = Vec3()
        val va = Vec3(); val vb = Vec3(); val n = Vec3(); val r = Vec3(); val impulse = Vec3()
        for (line in lines) {
            val a = vesselsById[line.a]
            val winch = a?.takeIf { line.partA in it.defs.indices && !it.isBroken(line.partA) }
                ?.defs?.get(line.partA)?.module<com.rm.apogee.core.part.Winch>()
            if (a == null || winch == null) { gone.add(line to false); continue }
            val b = line.b?.let { vesselsById[it] }
            if (line.b != null && (b == null || line.partB !in b.defs.indices)) { gone.add(line to false); continue }
            if (b == null && a.referenceBodyId != line.bodyId) { gone.add(line to false); continue }
            val attractor = attractorFor(a)
            winchFace(a, line.partA, winch, offA)
            pa.setTo(offA).addInPlace(a.body.position)
            if (b != null) {
                b.partPointOffsetWorld(line.partB, line.hook, offB)
                pb.setTo(offB).addInPlace(b.body.position)
            } else {
                attractor.rotationAt(time).rotate(line.ground, pb)
            }
            // Winding in, while there's charge, or letting out.
            if (line.reel > 0 && a.powered) {
                line.length = maxOf(LINE_SHORTEST, line.length - winch.reelSpeed * dt)
                a.winchDraw += winch.draw
            } else if (line.reel < 0) {
                line.length = minOf(winch.reach, line.length + winch.reelSpeed * dt)
            }
            n.setTo(pb).subInPlace(pa)
            val distance = n.length
            if (distance <= line.length || distance < 1e-6) { line.taut = false; continue }
            n.mulInPlace(1.0 / distance)
            line.taut = true
            if (a.dormant) a.wake()
            if (b != null && b.dormant) b.wake()
            a.body.velocityAtOffset(offA, va)
            if (b != null) b.body.velocityAtOffset(offB, vb) else attractor.surfaceVelocityAt(pb, vb)
            // Coming apart along the line, positive.
            val apart = (vb dot n) - (va dot n)
            r.setTo(offA).crossInPlace(n)
            var inverse = a.body.inverseMass + a.body.inverseInertiaAbout(r.normalizedOrZero()) * r.lengthSq
            if (b != null) {
                r.setTo(offB).crossInPlace(n)
                inverse += b.body.inverseMass + b.body.inverseInertiaAbout(r.normalizedOrZero()) * r.lengthSq
            }
            if (inverse <= 0.0) continue
            val bias = LINE_STIFFNESS * (distance - line.length) / dt
            var j = (apart + bias) / inverse
            if (j <= 0.0) continue
            val most = winch.pull * dt
            if (j > most) {
                // A yank, with the two flying apart, snaps it. Otherwise the drum slips.
                if (j > LINE_SNAP * most && apart > LINE_SNAP_SPEED) { gone.add(line to true); continue }
                j = most
                line.length = maxOf(line.length, distance - LINE_SLIP)
            }
            impulse.setTo(n).mulInPlace(j)
            a.body.applyImpulseAtOffset(impulse, offA)
            if (b != null) b.body.applyImpulseAtOffset(impulse.negateInPlace(), offB)
        }
        for ((line, snapped) in gone) {
            lines.remove(line)
            pendingEvents.add(WorldEvent.Winched(line.a, line.b, hooked = false, snapped = snapped))
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

        // Both halves need their motion in the world kept, so capture it before either design is
        // rebuilt underneath them.
        val position = vessel.body.position.copy()
        val orientation = vessel.body.orientation.copy()
        val angularVelocity = vessel.body.angularVelocity.copy()

        // The part hanging under an engine keeps the shell round it as its stage drops away.
        val shrouds = com.rm.apogee.core.craft.Shrouds.of(vessel.design, catalog)
        val discarded = buildSubDesign(vessel.design, separating.sorted(), keepShrouds = shrouds)
        val kept = buildSubDesign(vessel.design, remaining, firedStages = vessel.currentStage)

        // Look up BOTH halves' definitions before either design is replaced. replaceStructure swaps
        // vessel.defs for the kept subset, so indexing it afterwards with the original indices
        // reads the wrong parts, or runs off the end, which is how this was found.
        val originalDefs = vessel.defs
        val keptDefs = kept.indices.map { originalDefs[it] }
        val discardedDefs = discarded.indices.map { originalDefs[it] }

        // The half that falls away becomes a new vessel, with its own centre of mass where that
        // was, moving the way that point of the craft was moving, and with its parts as they were:
        // what's left in its tanks, what it had fired, and how hurt and hot it is. It's worked out
        // from the whole craft, so before the kept half is rebuilt.
        //
        // It used to be placed at the whole craft's centre instead, metres up the stack inside the
        // stage it had just let go of, and built fresh with full tanks. The halves then met again
        // the moment the grace period after separating ran out, and stuck together.
        val centre = pieceCentre(vessel, discarded.indices, Vec3()).copy()
        val pointVelocity = vessel.body.velocityAtOffset(centre, Vec3())
        val debris = Vessel(
            id = VesselId(nextVesselId++),
            design = discarded.design,
            defs = discardedDefs,
            referenceBodyId = vessel.referenceBodyId,
        )
        debris.inheritParts(vessel, discarded.indices)
        // Let go while burning, it keeps burning. The control module it answered to is gone, but
        // nothing ever told its engine to stop, so it runs on at the throttle it had until its
        // tanks are dry.
        debris.control.throttle = vessel.control.throttle
        debris.body.orientation.setTo(orientation)
        debris.body.linearVelocity.setTo(pointVelocity)
        debris.body.angularVelocity.setTo(angularVelocity)
        debris.body.position.setTo(position).addInPlace(centre)
        debris.recomputeMass(shiftBodyPosition = false)
        // A load set down, not a stage thrown away, so it still belongs to its owner and is named
        // for what it is.
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
            // Lifted off and set down next to the truck, not pushed away.
            setDownBeside(vessel, debris)
            vesselsById[debris.id] = debris
            justSeparated[pairKey(vessel.id.raw, debris.id.raw)] = time + SEPARATION_GRACE
            pendingEvents.add(WorldEvent.VesselStructureChanged(vessel.id))
            pendingEvents.add(WorldEvent.VesselSpawned(debris.id))
            return kept.indices
        }

        // Push the halves apart along the craft's long axis, or for a shell falling open, out
        // sideways from it.
        scratch.setTo(Vec3.unitY())
        orientation.rotate(scratch, scratch)
        val separation = scratch.copy()
        if (originalDefs[decouplerIndex].module<Decoupler>()?.radial == true) {
            val out = Vec3().setTo(centre).addScaledInPlace(separation, -(centre dot separation))
            if (out.lengthSq > 1e-9) separation.setTo(out).normalizeInPlace().negateInPlace()
        }

        if (ejection > 0.0) {
            scratch.setTo(separation).mulInPlace(ejection)
            vessel.body.applyImpulse(scratch)
            scratch.setTo(separation).mulInPlace(-ejection)
            debris.body.applyImpulse(scratch)
        }
        // Nudge them apart in space too, so the contact-free frame after separation doesn't start
        // with the two halves inside each other.
        scratch.setTo(separation).mulInPlace(-SEPARATION_CLEARANCE)
        debris.body.position.addInPlace(scratch)

        vesselsById[debris.id] = debris
        // The halves overlap as they part (an engine bell inside the ring it sat on), and they meet
        // again if the craft is turning or the spent half gets braked harder by the air than the
        // live one. Neither of those is a collision, because they're still coming apart.
        justSeparated[pairKey(vessel.id.raw, debris.id.raw)] = time + SEPARATION_GRACE
        pendingEvents.add(WorldEvent.VesselStructureChanged(vessel.id))
        pendingEvents.add(WorldEvent.VesselSpawned(debris.id))
        return kept.indices
    }

    /**
     * [load] lifted off [truck] and put on the ground to its right, clear of it by
     * [SET_DOWN_CLEARANCE], standing upright the way its own design stands, resting on its lowest
     * part and moving with the ground.
     */
    private fun setDownBeside(truck: Vessel, load: Vessel) {
        val attractor = attractorFor(truck)
        val up = Vec3().setTo(truck.body.position).normalizeInPlace()
        val right = truck.body.orientation.rotate(Vec3(1.0, 0.0, 0.0), Vec3())
        right.addScaledInPlace(up, -(right dot up)).normalizeInPlace()
        // Upright, keeping its heading.
        val loadUp = load.body.orientation.rotate(load.design.orientation.up, Vec3())
        load.body.orientation.setTo(com.rm.apogee.core.math.quatFromTo(loadUp, up) * load.body.orientation).normalizeInPlace()
        // Out to the side by both their half-widths and a bit more.
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

    /**
     * How far [vessel] reaches from its centre along [direction], in metres: the furthest of its
     * contact points.
     */
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
     * Rebuilds a design from some of its parts, remapping the parent indices.
     *
     * Stages left with nothing to fire get dropped, except the first [firedStages], which have
     * already fired and are kept empty as placeholders. Dropping those renumbered every stage after
     * them. The chute a player knew as stage 2 became stage 1 the moment the stage below it fell
     * away, and the craft's place in its own sequence had to be moved back to match, or it skipped
     * a stage.
     */
    private fun buildSubDesign(
        source: CraftDesign, keep: List<Int>, firedStages: Int = 0,
        keepShrouds: Array<com.rm.apogee.core.craft.Shroud?>? = null,
    ): SubDesign {
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
                shroud = keepShrouds?.getOrNull(oldIndex) ?: part.shroud,
            )
        }

        val stages = source.stages.map { stage ->
            com.rm.apogee.core.craft.Stage(
                stage.activatedParts.mapNotNull { remap[it] }
            )
        }.filterIndexed { index, stage -> index < firedStages || stage.activatedParts.isNotEmpty() }

        // A copy, so the orientation comes too. A plane dropping a tank was left thinking it stood
        // on its tail.
        return SubDesign(source.copy(parts = parts, stages = stages), keep)
    }

    // --- the step -----------------------------------------------------------

    /**
     * Moves forward one fixed timestep.
     *
     * Vessels are gone through in the order they were added, which stays stable because ids only
     * ever go up and the map keeps that order. The order doesn't affect anything today, since
     * vessels don't interact yet, but fixing it now means it can't quietly start mattering later.
     */
    fun step(dt: Double) {
        tickEnd = time + dt
        // The first tick after a world's made or restored, and then once a second.
        if (calmCheckedAt < 0L || tick - calmCheckedAt >= CALM_BASES_EVERY) {
            calmCheckedAt = tick
            calmBases()
        }
        for (vessel in vesselsById.values) {
            val attractor = attractorFor(vessel)
            val body = vessel.body
            // Moved on to the end of this tick from here, so anything measured against it by a
            // craft still at the start of the tick knows it's a tick ahead.
            vessel.movedTick = tick

            // Dormant craft ride the planet's rotation and aren't simulated.
            //
            // This is the same idea as putting an orbit on rails, for the other place a craft
            // spends most of its life: parked. A world people leave bases in is mostly made of
            // things nobody is looking at, and otherwise a base on a pad costs exactly as much as
            // one being flown.
            if (vessel.dormant) {
                // Asleep on a deck, it's posed from the deck once everything has moved.
                if (vessel.ridingOn != null) continue
                if (vessel.afloat) followSea(vessel, attractor, waves = true, dt = dt) else followGround(vessel, attractor)
                // Parked in air that crushes, like Caligo's floor, or deeper in the sea than it's
                // built for, it gets crushed all the same.
                val air = attractor.atmosphere
                val deep = attractor.ocean != null && attractor.altitudeOf(vessel.body.position) < -WATER_PARKED_SAFE
                if (!isDebris(vessel) && (deep || air != null && air.pressureAt(attractor.altitudeOf(vessel.body.position)) > CRUSH_FLOOR)) {
                    hostile(vessel, attractor, dt)
                    if (vessel.id in pendingBreakUps) vessel.wake()
                }
                continue
            }

            body.clearAccumulators()
            vessel.clearForces()

            // Just woken, off the ground's ledger or off rails, so bring its power up to now before
            // anything draws on it.
            if (!vessel.powerSettledAt.isNaN() && time - vessel.powerSettledAt > 1.0) settlePower(vessel, time)
            // Dark, so there's nothing to hold or fly with.
            if (!vessel.powered && (vessel.control.autoBurn || vessel.control.autoLand)) {
                vessel.control.autoBurn = false
                vessel.control.autoLand = false
                vessel.control.throttle = 0.0
                vessel.control.autopilotNote = "No power"
            }

            // Before any force, because the elevons move inside the drag pass and the gimbal inside
            // thrust, and all of them act on what stability assist asks for this tick.
            if (vessel.control.autoBurn) autoBurn(vessel, attractor)
            if (!vessel.control.autoLand) vessel.landStarted = false
            val landing = if (vessel.control.autoLand) autoLand(vessel, attractor, dt) else null
            val landingItself = vessel.control.autoLand
            if (vessel.control.cruise && !landingItself) flyCruise(vessel, attractor, dt)
            vessel.control.stickTilts = false
            if (vessel.control.keeping && !landingItself) flyKeeper(vessel, attractor, dt)
            val cruising = (vessel.control.cruise || vessel.control.keeping || landingItself) && landing == null
            if (vessel.powered) stabilityAssist.update(vessel, dt, landing ?: if (cruising) null else holdDirection(vessel, attractor))
            else stabilityAssist.idle(vessel)
            updatePose(vessel, dt)
            // Someone on foot: kept upright, with the stick walking instead of tipping them over.
            val walker = walking.walkerOf(vessel)
            val hold = if (walker != null && vessel.ladderVessel >= 0) heldLadder(vessel, dt) else null
            val water = if (walker != null && hold == null) waterFor(vessel, attractor) else null
            if (walker != null) walking.stand(vessel, walker, attractor, hold, water)
            else { vessel.onFeet = false; vessel.walking = false; vessel.swimming = false }

            forces.applyGravity(vessel, attractor)
            forces.applyThrust(vessel, attractor, dt, time)
            // The air it's flying through, sampled once per craft per tick at its centre of mass.
            // Gusts over its length come in the drag pass.
            val weather = weatherFor(attractor)
            attractor.rotationAt(time, weatherRotation)
            val steady = steadyWind
            if (steady != null && attractor.atmosphere != null) {
                vessel.air.clear()
                vessel.airSampledAt = Double.NaN
                attractor.toBodyFixed(body.position, weatherRotation, weatherPoint).normalizeInPlace()
                // East and north at the craft, as the planet's spin about +Y defines them.
                scratchEast.setTo(0.0, 1.0, 0.0).crossInPlace(weatherPoint).normalizeInPlace()
                scratchNorth.setTo(weatherPoint).crossInPlace(scratchEast)
                vessel.air.wind.setTo(scratchEast).mulInPlace(steady.x).addScaledInPlace(scratchNorth, steady.y)
            } else if (weather != null) {
                attractor.toBodyFixed(body.position, weatherRotation, weatherPoint)
                // Kept for a few hundredths of a second, or a few metres. The air doesn't change
                // faster than that, and working it out (the ground's lie, the circulation, every
                // storm near by) four times a tick was more than a phone at 4x could spare.
                val age = time - vessel.airSampledAt
                if (vessel.airSampledAt.isNaN() || age < 0.0 || age >= AIR_KEEP ||
                    vessel.airSampledWhere.distanceTo(weatherPoint) > AIR_MOVE) {
                    weather.sample(weatherPoint, time, vessel.air)
                    vessel.airSampledAt = time
                    vessel.airSampledWhere.setTo(weatherPoint)
                }
            } else {
                vessel.air.clear()
                vessel.airSampledAt = Double.NaN
            }
            forces.applyDrag(vessel, attractor, weather, weatherRotation, time, dt)
            // Rotors and gas cells, after the drag pass, which is where the air at the craft is
            // sampled for this tick.
            rotors.apply(vessel, attractor, weatherRotation, time, dt)
            aerostatics.apply(vessel, attractor)
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
            forces.applyReactionWheels(vessel, attractor)
            forces.applyRcs(vessel, dt)
            stress.update(vessel, dt)
            for (i in 0 until stress.snappedCount) detach(vessel, stress.snapped[i], "tore off under load")
            heat.update(vessel, attractor, dt, system.sunStrength(attractor.id, body.position, time))
            vessel.hottest = heat.hottest
            vessel.hottestPart = heat.hottestPart
            if (heat.burntCount > 0) {
                pendingBreakUps.add(vessel.id)
                breakUpCause[vessel.id] = "burnt up"
            }
            hostile(vessel, attractor, dt)
            ballast(vessel, attractor, dt)

            // Integration and contact get split into smaller steps together when the craft is
            // moving fast near the ground. Forces aren't recalculated per substep, because they
            // change far more slowly than the geometry does, and working out thrust and drag eight
            // times a tick would cost more than the problem is worth.
            burnBefore.setTo(body.linearVelocity)
            val substeps = contactSubsteps(vessel, attractor, dt)
            val h = dt / substeps
            val decks = decksNear(vessel)
            for (substep in 0 until substeps) {
                body.integrate(h)
                // Test against the ground where it is now that the craft has moved on, which is the
                // end of this substep, not its start. The ground turns with the planet at 175 m/s
                // at the equator. A substep behind, it stood 2.9 m back along the turn, which
                // changes nothing on flat ground but is metres up or down on a steep slope. A pod
                // landed on a mountainside came to rest two metres inside it.
                contacts.resolve(vessel, attractor, h, time + (substep + 1) * h, substep > 0)
                // A deck stepped before this craft is already at the end of the tick, and one
                // stepped after it is still at the start. This craft is (substep + 1) * h in.
                contacts.resolveOnCraft(vessel, attractor, decks, h, tick, dt, (substep + 1) * h)
            }
            vessel.standingOn = contacts.deckUnder
            // A deck asleep doesn't move, so it's woken to take the weight.
            contacts.deckUnder?.wake()
            // Boulders and trunks go into the same report, so a craft wrecked on a rock is judged
            // the same way as one wrecked on the ground.
            scatterContacts.resolve(vessel, attractor, time + dt, contacts.report, felledScatter) { fell(it) }
            val report = contacts.report
            vessel.touchingGround = report.hadContact
            if (!vessel.rightingStart.isNaN()) stepRighting(vessel, attractor)
            crossRings(vessel, attractor)
            // Molten ground: whatever touches it is gone.
            if (report.lavaPart >= 0 && vessel.damage(report.lavaPart, 1.0)) {
                pendingBreakUps.add(vessel.id)
                breakUpCause[vessel.id] = "lost in the lava"
            }
            if (walker != null) {
                walking.move(vessel, walker, attractor, hold, dt, water)
                walking.swing(vessel, walker, attractor, hold, dt)
                cold(vessel, attractor, water, dt)
            }
            vessel.groundContacts = report.contactCount
            vessel.countGrounded(report.hadContact, dt)

            // Mass changes as propellant burns, and the centre of mass moves with it.
            if (vessel.control.throttle > 0.0) vessel.recomputeMassIfChanged()
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

            if (vessel.plannedBurns.isNotEmpty()) trackBurn(vessel, attractor, dt)
            if (!isDebris(vessel)) {
                attractor.surfaceVelocityAt(body.position, scratchIndustry).subInPlace(body.linearVelocity)
                val still = vessel.touchingGround && scratchIndustry.length < Industry.STILL
                industry.step(vessel, attractor, tickEnd, dt, still, vessel.powered)
                power.step(vessel, attractor, tickEnd, dt)
                vessel.powerSettledAt = tickEnd
                tearWings(vessel, attractor)
                if (!vessel.touchingGround) survey(vessel, attractor, dt)
            }
            considerSleeping(vessel, report)
            crossInfluence(vessel, tickEnd)
        }

        // Craft asleep on decks, carried to wherever their decks went this tick. A deck that's gone
        // leaves them awake.
        for (vessel in vesselsById.values) {
            val deckId = vessel.ridingOn ?: continue
            val deck = vesselsById[deckId]
            if (deck == null || deck.referenceBodyId != vessel.referenceBodyId) vessel.wake() else vessel.followRide(deck)
        }

        // Craft against craft, once everything has moved.
        //
        // This happens after the per-vessel pass instead of inside it, because a pair needs both
        // halves in their new positions before it means anything. The cost is that a craft-to-craft
        // contact is resolved against terrain contacts from the same tick instead of interleaved
        // with them, which at a sixtieth of a second isn't something anyone can see.
        stepOrder.clear()
        stepOrder.addAll(vesselsById.values)
        if (justSeparated.isNotEmpty()) justSeparated.values.removeAll { it < time }
        val impacts = craftContacts.resolve(stepOrder, dt)
        // Anything that got touched is awake again, whether it was hurt or not. A sleeping base
        // that stayed asleep while something landed on it would be a wall, not an object.
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
        stepUnloading(dt)
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
        if (tick % CREW_CHECK_TICKS == 0L) reconcileCrew()
        if (tick % SIGNAL_CHECK_TICKS == 0L) {
            // Whatever the drills and converters moved, weighed again.
            for (vessel in vesselsById.values) if (vessel.industryMoved) { vessel.industryMoved = false; vessel.recomputeMass() }
            for (vessel in vesselsById.values) {
                if (!vessel.dormant && !isDebris(vessel) && Comms.needsSignal(vessel)) refreshSignal(vessel, tickEnd)
            }
        }
        if (tick % POWER_CHECK_TICKS == 0L) {
            // Founded and parked craft keep a ledger instead of stepping.
            for (vessel in vesselsById.values) if (vessel.anchored || (vessel.dormant && !isDebris(vessel))) settlePower(vessel, tickEnd)
        }

        watchCareer(look = tick % com.rm.apogee.core.career.Program.LOOK_TICKS == 0L)
        tick++
        time += dt
    }

    /**
     * Lightning since the last look, up to [until]. Every strike near any craft (flying or parked,
     * since a storm doesn't care which) that lands within [LIGHTNING_REACH] of one below the cloud
     * hits it. The highest part takes the hit, and might be knocked out. All of it comes from the
     * weather and the strike, so a replica agrees.
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
                // One craft per strike: the first one found, which is stable.
                if (!strikesSeen.add(strike.id)) continue
                hitByLightning(vessel, attractor, strike)
            }
        }
    }

    private fun hitByLightning(vessel: Vessel, attractor: CelestialBody, strike: Strike) {
        vessel.wake()
        // The highest part, measured against the local vertical.
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
     * Puts a craft to sleep once friction has been holding it still for a while.
     *
     * The condition is [ContactReport.anchored], not a speed threshold. The contact resolver
     * already answers the hard question, which is whether friction is winning, and a craft it has
     * anchored is exactly, not roughly, still on the ground. Comparing speeds here instead meant
     * picking a number above a resting craft's jitter and below a real slide, and the first try at
     * that number was below the jitter's ninetieth percentile, so nothing ever slept.
     *
     * The delay stops flickering. A lander rocking onto its gear can be anchored for a tick or two
     * on the way to settling.
     */
    private fun considerSleeping(vessel: Vessel, report: ContactReport) {
        // Not while a leg is still swinging, because asleep it would stop half out.
        //
        // Not while it's being drawn in to dock either. Held short of the latch by the ground's
        // friction, asleep it would stay there.
        //
        // And not while its ballast is working, or holding it at a depth. Asleep, its tanks stop,
        // and one resting on the sea floor with its tanks blown stayed there.
        //
        // Or while a winch line on it is winding or pulling.
        //
        // Or while the auto-land is flying it. A balloon hanging still in the air on its way down
        // went to sleep there, and the landing stopped with it.
        //
        // Or while its crew are rolling it upright, which holds it still as they do.
        if (legsMoving(vessel) || docking.capturing(vessel.id.raw) || vessel.control.ballast != 0 || vessel.control.holdDepth || vessel.control.autoLand ||
            !vessel.rightingStart.isNaN() ||
            lines.any { (it.a == vessel.id || it.b == vessel.id) && (it.reel != 0 || it.taut) }
        ) {
            vessel.noteStillness(false, SLEEP_SETTLE_TICKS)
            return
        }
        // Standing on another craft's deck, it sleeps as a rider on it once it's still on the deck,
        // however the deck's moving.
        val deck = vessel.standingOn
        if (deck != null) {
            deck.body.velocityAtOffset(scratchRideFrom.setTo(vessel.body.position).subInPlace(deck.body.position), scratchRideVelocity)
            scratchRideVelocity.subInPlace(vessel.body.linearVelocity)
            scratchRideSpin.setTo(vessel.body.angularVelocity).subInPlace(deck.body.angularVelocity)
            val riding = vessel.control.throttle == 0.0 && !vessel.walking &&
                scratchRideVelocity.length < RIDE_STILL && scratchRideSpin.length < RIDE_SPIN
            if (vessel.noteStillness(riding, SLEEP_SETTLE_TICKS)) vessel.ride(deck)
            return
        }
        val still = report.anchored || floatingStill(vessel) || aloftStill(vessel)
        if (vessel.noteStillness(still, SLEEP_SETTLE_TICKS)) {
            // The pose was just integrated to the end of the tick, so it gets pinned to the ground
            // as the ground is then.
            val attractor = attractorFor(vessel)
            attractor.rotationAt(tickEnd, scratchRotation)
            vessel.sleep(scratchRotation)
            // Afloat, it rides the sea from here on, so note how it lies in it.
            val ocean = attractor.ocean
            if (!report.anchored && ocean != null && !vessel.touchingGround && vessel.buoyed) settleAfloat(vessel, attractor)
        }
    }

    /**
     * Notes how [vessel] lies in the sea where it floats now: afloat, its centre [Vessel.draft]
     * metres from the surface, tilted the way the water is, so asleep (or founded) it rides the sea
     * from here on.
     */
    private fun settleAfloat(vessel: Vessel, attractor: CelestialBody) {
        val ocean = attractor.ocean ?: return
        attractor.rotationAt(tickEnd, scratchRotation)
        attractor.toBodyFixed(vessel.body.position, scratchRotation, scratchSeaPoint)
        ocean.sample(scratchSeaPoint, tickEnd, seaRide, spacing = riderSpacing(vessel))
        vessel.afloat = true
        vessel.draft = scratchSeaPoint.length - attractor.radius - seaRide.height
        vessel.sleepNormal.setTo(seaRide.normal)
        // Not as it lay this moment, which in a swell can be the far end of a roll, but at its
        // balance in the water. See FloatingBalance.
        scratchRotation.rotate(seaRide.normal, scratchSeaUp)
        if (balance.solve(vessel, scratchSeaUp, ocean.density, vessel.draft)) {
            vessel.body.orientation.setTo(balance.orientation)
            vessel.retakeTurn(scratchRotation)
            vessel.draft = balance.height
        }
    }

    private val balance = FloatingBalance()

    private val seaRide = com.rm.apogee.core.sea.SeaSample()
    private val scratchRide = com.rm.apogee.core.math.Quat()
    private val scratchRideTurn = com.rm.apogee.core.math.Quat()
    private val scratchRideFrom = Vec3()
    private val scratchRideVelocity = Vec3()
    private val scratchRideSpin = Vec3()
    private val scratchSeaPoint = Vec3()
    private val scratchSeaUp = Vec3()
    private val scratchTilt = Quat()

    /**
     * Waves shorter than a craft's own length don't move it as a whole, so a rider only feels the
     * longer ones.
     */
    private fun riderSpacing(vessel: Vessel): Double = kotlin.math.max(1.0, vessel.contactRadius)

    /**
     * A craft asleep afloat, riding the sea: up and down with the tide and the waves, tipped with
     * them, where it was moored. [waves] is off for time warped on rails, when only the tide is
     * followed.
     */
    private fun followSea(vessel: Vessel, attractor: CelestialBody, waves: Boolean, dt: Double = 0.0) {
        val ocean = attractor.ocean ?: return followGround(vessel, attractor)
        // A founded base afloat carries whatever stands on its deck, and for that its deck has to
        // move with the speeds it really has, worked out from how it moved and turned this tick.
        // The sea's own velocity isn't that. The base stays moored where it was founded and only
        // heaves and tilts, while the water under it goes to and fro, and a buggy braked on its
        // deck slid about by the difference, a metre a second in a wild sea.
        val carrying = vessel.anchored && dt > 0.0
        if (carrying) { scratchRide.setTo(vessel.body.orientation); scratchRideFrom.setTo(vessel.body.position) }
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
        if (carrying) {
            vessel.body.linearVelocity.setTo(vessel.body.position).subInPlace(scratchRideFrom).mulInPlace(1.0 / dt)
            // Except where it was put somewhere new this tick, just founded or just loaded, which
            // isn't a speed. Then it's the ground's.
            attractor.surfaceVelocityAt(vessel.body.position, scratchRideFrom)
            if (vessel.body.linearVelocity.distanceTo(scratchRideFrom) > RIDE_MOST) vessel.body.linearVelocity.setTo(scratchRideFrom)
            // conj(before) then after: the turn this tick, as an angle about an axis.
            scratchRideTurn.setTo(vessel.body.orientation).mulInPlace(scratchRide.conjugateInPlace())
            if (scratchRideTurn.w < 0.0) scratchRideTurn.setTo(-scratchRideTurn.x, -scratchRideTurn.y, -scratchRideTurn.z, -scratchRideTurn.w)
            val sine = kotlin.math.sqrt(scratchRideTurn.x * scratchRideTurn.x + scratchRideTurn.y * scratchRideTurn.y + scratchRideTurn.z * scratchRideTurn.z)
            if (sine > 1e-12) {
                val angle = 2.0 * kotlin.math.atan2(sine, scratchRideTurn.w)
                vessel.body.angularVelocity.setTo(scratchRideTurn.x, scratchRideTurn.y, scratchRideTurn.z).mulInPlace(angle / (sine * dt))
            }
        }
        // The sea has got up big for it, so what happens to it now (riding it out, taking on water,
        // going over) is for the physics to decide.
        if (waves && !hurried && !vessel.anchored && seaRide.significantHeight > tooRough(vessel)) vessel.wake()
    }

    /**
     * Time was asked to go faster than physics can follow. A boat left alone in a rough sea can
     * then drop anchor and ride it asleep after all, the same as in a calm one. Otherwise any storm
     * anywhere would hold the whole world to physics warp until it blew over.
     */
    var hurried: Boolean = false

    /**
     * The significant wave height, in metres, past which the sea counts as rough for [vessel],
     * meaning too big for its size to be ridden asleep like a cork.
     */
    private fun tooRough(vessel: Vessel): Double = kotlin.math.max(ROUGH_SEA, ROUGH_PER_METRE * vessel.contactRadius)

    /**
     * Afloat, clear of the bottom, engine off, and going nowhere relative to the water.
     *
     * This is the other way to be at rest. Ground contact normally decides, and a floating craft
     * has none, so without this a boat moored at sea got simulated every tick forever. In a world
     * of bases people leave and come back to, that's exactly the craft that should cost nothing.
     *
     * This uses a speed threshold, not an anchor, because nothing is holding a floating craft. It's
     * still because the water has damped it. It's only valid while the sea is calm. With waves (M9)
     * a floating craft is never at rest, and a dormant one has to ride the surface instead.
     *
     * In a wind, a boat left alone never stops. It drifts, steadily, at whatever pace the wind on
     * its topsides and the water on its hull agree on. Held to the still-water threshold it would
     * never sleep, and in a world people leave boats in, it would drift off across the sea while
     * nobody was there. So a boat left alone (engine off, hands off) drops anchor. Drifting no
     * faster than [ANCHOR_DRIFT], it's allowed to sleep, and while asleep it stays where it is.
     */
    /**
     * The sea's current where [vessel] floats, in the world's frame, into [out]. Zero out of the
     * water, or on a world without currents.
     */
    fun currentAt(vessel: Vessel, attractor: CelestialBody = attractorFor(vessel), out: Vec3 = Vec3()): Vec3 {
        val sea = attractor.ocean?.sea ?: return out.setZero()
        val rotation = attractor.rotationAt(time, scratchCurrentRotation)
        attractor.toBodyFixed(vessel.body.position, rotation, scratchCurrentPoint)
        val below = (-attractor.altitudeOf(vessel.body.position)).coerceAtLeast(0.0)
        sea.current(scratchCurrentPoint, below, out)
        return rotation.rotate(out, out)
    }

    private val scratchCurrent = Vec3()
    private val scratchCurrentPoint = Vec3()
    private val scratchCurrentRotation = com.rm.apogee.core.math.Quat()

    /**
     * Up in the air and going nowhere: held by its keeper core with hands off the stick, or floating
     * in balance on its gas cells with nothing running. Then it may sleep aloft, and asleep it rides
     * the planet round at its height, like a sleeping craft on the ground, so a platform left in the
     * sky is where it was when you come back.
     */
    private fun aloftStill(vessel: Vessel): Boolean {
        if (vessel.touchingGround || vessel.buoyed) return false
        val control = vessel.control
        if (control.hasAttitudeInput) return false
        val keeping = control.keeping
        // Held by its keeper, only once it's back where it's meant to be.
        if (keeping) {
            val attractor = attractorFor(vessel)
            attractor.rotationAt(time, scratchCurrentRotation).rotate(control.keepPoint, scratchCurrent).subInPlace(vessel.body.position)
            if (scratchCurrent.length > KEEP_SETTLED) return false
        }
        val share = liftShare(vessel)
        val balanced = share in (1.0 - BALANCED)..(1.0 + BALANCED) && control.throttle == 0.0
        if (!keeping && !balanced) return false
        val attractor = attractorFor(vessel)
        attractor.surfaceVelocityAt(vessel.body.position, scratchSurfaceVelocity)
        scratchRelativeVelocity.setTo(vessel.body.linearVelocity).subInPlace(scratchSurfaceVelocity)
        // Kept over its spot, it's still over the ground. Left to float, it's still in the air.
        if (!keeping) scratchRelativeVelocity.subInPlace(attractor.rotationAt(time, scratchCurrentRotation).rotate(vessel.air.wind, scratchCurrent))
        if (scratchRelativeVelocity.length > ANCHOR_DRIFT) return false
        attractor.angularVelocity(scratchSpin)
        return vessel.body.angularVelocity.distanceTo(scratchSpin) < ALOFT_TURN
    }

    private fun floatingStill(vessel: Vessel): Boolean {
        if (vessel.touchingGround || hydrostatics.submergedVolume <= 0.0) return false
        // Someone in the water never dozes off there, because the cold is getting to them.
        if (walking.walkerOf(vessel) != null) return false
        val control = vessel.control
        if (control.throttle > 0.0) return false
        val handsOff = control.pitch == 0.0 && control.yaw == 0.0 && control.roll == 0.0
        val attractor = attractorFor(vessel)
        // In a swell a boat is never at rest, because it goes up and down with the waves. So hands
        // off and drifting slowly is enough, and asleep it rides the waves anyway. Not for a craft
        // wholly under water, though, which doesn't heave with the waves. A Base Core let go of on
        // the sea floor was rising straight up, drifting hardly at all, and fell asleep ten metres
        // down, where it stayed.
        val seaway = hydrostatics.seaHeight > SEAWAY_HS && !vessel.submerged
        if (seaway && !handsOff) return false
        if (!hurried && hydrostatics.seaHeight > tooRough(vessel)) return false
        val limit = if (seaway || (handsOff && vessel.air.wind.length > 0.5)) ANCHOR_DRIFT else FLOATING_REST_SPEED
        attractor.surfaceVelocityAt(vessel.body.position, scratchSurfaceVelocity)
        scratchRelativeVelocity.setTo(vessel.body.linearVelocity).subInPlace(scratchSurfaceVelocity)
        // Across the water, not the ground: a boat carried along by a current is still in it.
        scratchRelativeVelocity.subInPlace(currentAt(vessel, attractor, scratchCurrent))
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
     * Poses the craft's moving parts for this tick: control surfaces to the stick (and stability
     * assist), steerable wheels to the steering, and legs toward deployed once staged. The forces
     * and the contacts read these, and snapshots carry them, so what moves a craft and what
     * everyone sees of it are the same numbers.
     */
    private fun updatePose(vessel: Vessel, dt: Double) {
        vessel.fitPose()
        val yaw = vessel.control.yaw
        val forward = vessel.design.orientation.forward
        vessel.centerOfMass(scratch)
        // Less lock the faster it goes, like a car's steering is geared. Hard over at speed, the
        // Trundler rolled at eleven metres a second.
        var lock = Double.NaN
        for (i in vessel.defs.indices) {
            val def = vessel.defs[i]
            if (def.module<AeroSurface>()?.controllable == true ||
                def.module<com.rm.apogee.core.part.HydroSurface>()?.controllable == true
            ) {
                vessel.surfaceDeflection[i] = forces.controlDeflection(vessel, i)
            }
            if ((def.module<AeroSurface>()?.flapLift ?: 0.0) > 0.0) {
                val target = if (vessel.running(i, vessel.control.flaps) && !vessel.isBroken(i)) 1.0 else 0.0
                val now = vessel.flapPosition[i]
                val step = dt / FLAP_TIME
                vessel.flapPosition[i] = if (now < target) minOf(target, now + step) else maxOf(target, now - step)
            }
            val engine = def.module<com.rm.apogee.core.part.Engine>()
            if (engine != null && engine.gimbalRange > 0.0) {
                // The same way Forces.gimballedDirection swings the thrust.
                val burning = vessel.isWorking(i) && vessel.control.throttle > 0.0
                vessel.gimbalPitch[i] = if (burning) -vessel.control.commandPitch.coerceIn(-1.0, 1.0) else 0.0
                vessel.gimbalYaw[i] = if (burning) -vessel.control.commandYaw.coerceIn(-1.0, 1.0) else 0.0
            }
            val wheel = def.module<com.rm.apogee.core.part.Wheel>()
            if (wheel != null) {
                vessel.wheelSteer[i] = if (!wheel.steerable || yaw == 0.0) 0.0 else {
                    // Front wheels into the corner, rear wheels away from it. Which end is which is
                    // judged from the centre of mass along the craft's forward. See
                    // GroundContact.driveWheel.
                    val ahead = (vessel.design.parts[i].position.x - scratch.x) * forward.x +
                        (vessel.design.parts[i].position.y - scratch.y) * forward.y +
                        (vessel.design.parts[i].position.z - scratch.z) * forward.z
                    val end = if (ahead >= 0.0) 1.0 else -1.0
                    if (lock.isNaN()) {
                        val ground = attractorFor(vessel).surfaceVelocityAt(vessel.body.position, scratchSteer)
                            .subInPlace(vessel.body.linearVelocity).length
                        lock = (FULL_LOCK_SPEED / ground.coerceAtLeast(1e-3)).coerceAtMost(1.0)
                    }
                    Math.toRadians(wheel.steeringRange * yaw * lock) * end
                }
            }
            val leg = def.module<LandingLeg>()
            val unfolds = leg == null && foldsOut(def)
            if (leg != null || unfolds) {
                val target = if (if (unfolds) unfolded(vessel, i) else vessel.isWorking(i)) 1.0 else 0.0
                val step = dt / (leg?.deployTime ?: UNFOLD_TIME).coerceAtLeast(1e-3)
                val now = vessel.legDeploy[i]
                vessel.legDeploy[i] = if (now < target) minOf(target, now + step) else maxOf(target, now - step)
            }
        }
    }

    private fun foldsOut(def: com.rm.apogee.core.part.PartDef) = VesselPose.foldsOut(def)

    /**
     * Whether fold-out part [i] should be out: a drill while drilling, or a wing or dish told to be
     * or staged, and none of them broken.
     */
    private fun unfolded(vessel: Vessel, i: Int): Boolean {
        if (vessel.isBroken(i)) return false
        if (vessel.defs[i].module<com.rm.apogee.core.part.Drill>() != null) return vessel.running(i, vessel.control.drilling)
        return vessel.running(i, vessel.control.deployed || (i < vessel.activated.size && vessel.activated[i]))
    }

    private fun legsMoving(vessel: Vessel): Boolean {
        for (i in vessel.defs.indices) {
            val def = vessel.defs[i]
            val target = when {
                def.module<LandingLeg>() != null -> if (vessel.isWorking(i)) 1.0 else 0.0
                foldsOut(def) -> if (unfolded(vessel, i)) 1.0 else 0.0
                else -> continue
            }
            if (vessel.legDeploy.getOrElse(i) { target } != target) return true
        }
        return false
    }

    /** Wakes [id] if it's asleep, so a command always reaches a live craft. */
    private fun waken(id: Long): Vessel? = vesselsById[VesselId(id)]?.also { it.wake() }

    /**
     * [id], woken, if what gets sent to it is heard and acted on. That's always true for a craft
     * with somebody aboard, and for a probe only with power and a link home. Null for one that's
     * out of touch, which carries on the way it was left.
     */
    private fun heard(id: Long): Vessel? {
        val vessel = vesselsById[VesselId(id)] ?: return null
        if (Comms.needsSignal(vessel)) {
            if (vessel.signalAt.isNaN() || time - vessel.signalAt > SIGNAL_STALE) refreshSignal(vessel, time)
            if (!controllable(vessel)) return null
        }
        return vessel.also { it.wake() }
    }

    /**
     * Whether [vessel] can be flown right now: someone's aboard, or it's a probe core with power
     * and a link home. Nobody flies an empty pod.
     */
    fun controllable(vessel: Vessel): Boolean =
        !Comms.needsSignal(vessel) || (Comms.hasProbeCore(vessel) && vessel.powered && vessel.signal != Signal.NONE)

    /** Why [vessel] can't be flown ("NO CREW", "NO POWER", "NO SIGNAL"), or blank when it can. */
    fun whyNotControllable(vessel: Vessel): String = when {
        controllable(vessel) -> ""
        !Comms.hasProbeCore(vessel) -> "NO CREW"
        !vessel.powered -> "NO POWER"
        else -> "NO SIGNAL"
    }

    private val comms = Comms(system)

    /** [vessel]'s power and link home, for its pilot's HUD. */
    fun systemsOf(vessel: Vessel): ServerMessage.CraftSystems {
        val charge = com.rm.apogee.core.part.ResourceType.ELECTRIC_CHARGE
        return ServerMessage.CraftSystems(
            vessel = vessel.id.raw,
            charge = vessel.amountOf(charge).toFloat(),
            capacity = vessel.capacityOf(charge).toFloat(),
            net = vessel.powerNet.toFloat(),
            powered = vessel.powered,
            signal = vessel.signal,
            relays = vessel.signalPath,
            controllable = controllable(vessel),
            blocked = whyNotControllable(vessel),
            needsSignal = Comms.needsSignal(vessel),
            deployed = vessel.control.deployed,
            boardable = if (walking.walkerOf(vessel) != null) seatInReach(vessel)?.first?.name.orEmpty() else "",
            climbOnto = if (walking.walkerOf(vessel) != null) climbSpot(vessel)?.first?.name.orEmpty() else "",
            swimming = inSea(vessel),
            chill = vessel.chill.toFloat(),
            evaBlocked = evaBlocked(vessel),
            canGrab = walking.walkerOf(vessel) != null && !onLadder(vessel) && ladderInReach(vessel) != null,
            onLadder = onLadder(vessel),
            drilling = vessel.control.drilling,
            refining = vessel.control.refining,
            drillState = vessel.drillState,
            survey = if (hasScanner(vessel)) surveyShare(vessel).toFloat() else -1f,
            ore = reading(vessel, com.rm.apogee.core.part.ResourceType.ORE),
            water = reading(vessel, com.rm.apogee.core.part.ResourceType.WATER),
            // Someone in the water dives and rises with the same buttons. A founded base has no use
            // for them, its tanks or not.
            ballast = if (inSea(vessel)) 0f else if (vessel.anchored) -1f else ballastShare(vessel).toFloat(),
            ballastMode = vessel.control.ballast,
            // Aloft on gas, it's a height it holds, kept as a depth below the datum.
            holdingDepth = if (!vessel.control.holdDepth) -1f
                else if (gasCraft(vessel)) (-vessel.control.holdDepthAt).toFloat() else vessel.control.holdDepthAt.toFloat(),
            crush = vessel.crushShare.toFloat(),
            cruiseHeight = if (vessel.control.cruise) vessel.control.cruiseHeight.toFloat() else -1f,
            cruiseHeading = vessel.control.cruiseHeading.toFloat(),
            mayCruise = mayCruise(vessel),
            groups = if (vessel.groupStates.any { it != 0 }) vessel.groupStates.toList() else emptyList(),
            hasWinch = winchOf(vessel) != null,
            canHook = hookTarget(vessel)?.let { it.craft?.name ?: "ground" }.orEmpty(),
            hooked = lineOf(vessel) != null,
            reel = lineOf(vessel)?.reel ?: 0,
            taut = lineOf(vessel)?.taut == true,
            hasKeeper = hasKeeper(vessel),
            keeping = vessel.control.keeping,
            keepPoint = vessel.control.keepPoint.copy(),
            ballonet = vessel.ballonet.toFloat(),
            lift = liftShare(vessel).toFloat(),
            canRight = canRight(vessel),
            standingOn = vessel.standingOn?.id?.raw ?: -1L,
            riders = vesselsById.values.filter { onDeckOf(it, vessel) }.map { it.id.raw },
        ).let { sonar(vessel, it) }
    }

    /**
     * Whether [vessel]'s crew can roll it back upright: it's lying past [RIGHT_FROM] off upright,
     * afloat or on the ground, nearly still, crewed, and no heavier than [RIGHT_MOST_MASS].
     *
     * Every small boat floats as happily upside down as the right way up. The sea's buoyancy doesn't
     * care which way up the hull is, and a Jet Boat idling side on to a wild sea was rolled past
     * seventy degrees by a breaking crest and settled keel up. On a small craft that's the end of
     * it without this. People right dinghies and jet skis, and push a buggy back onto its wheels.
     */
    fun canRight(vessel: Vessel): Boolean {
        if (!vessel.rightingStart.isNaN() || vessel.anchored || isDebris(vessel) || walking.walkerOf(vessel) != null) return false
        if (!vessel.hasCrew() || vessel.body.mass > RIGHT_MOST_MASS) return false
        if (!vessel.dormant && !vessel.buoyed && !vessel.touchingGround) return false
        val up = vessel.body.position.normalized()
        val deck = vessel.body.orientation.rotate(vessel.design.orientation.up, Vec3())
        if ((deck dot up) > kotlin.math.cos(RIGHT_FROM)) return false
        val attractor = attractorFor(vessel)
        attractor.surfaceVelocityAt(vessel.body.position, scratchCrew).subInPlace(vessel.body.linearVelocity)
        return scratchCrew.length < RIGHT_STILL
    }

    /**
     * Sets [vessel]'s crew rolling it upright, about its own length the way a boat goes over, with
     * whatever tilt is left along its length taken out as well, keeping its heading.
     */
    private fun startRighting(vessel: Vessel) {
        if (!canRight(vessel)) return
        val up = vessel.body.position.normalized()
        val ahead = vessel.body.orientation.rotate(vessel.design.orientation.forward, Vec3())
        val deck = vessel.body.orientation.rotate(vessel.design.orientation.up, Vec3())
        val turn = Quat()
        // The roll: the deck and the up, both seen end on along the craft.
        val deckAcross = deck.copy().addScaledInPlace(ahead, -(deck dot ahead))
        val upAcross = up.copy().addScaledInPlace(ahead, -(up dot ahead))
        if (deckAcross.length > 1e-6 && upAcross.length > 1e-6) {
            val roll = kotlin.math.atan2(deckAcross.cross(upAcross) dot ahead, deckAcross dot upAcross)
            Quat.fromAxisAngle(ahead.normalizeInPlace(), roll, turn)
        }
        vessel.rightingTo.setTo(turn).mulInPlace(vessel.body.orientation)
        // Then the pitch, now that what's left is small and has only the one way to go.
        vessel.rightingTo.rotate(vessel.design.orientation.up, deck)
        vessel.rightingTo.setTo(quatFromTo(deck, up) * vessel.rightingTo).normalizeInPlace()
        vessel.rightingFrom.setTo(vessel.body.orientation)
        vessel.rightingStart = time
    }

    /**
     * One tick of the crew rolling [vessel] upright, after it has moved. The roll is theirs, not
     * the sea's or the ground's, so it's posed, and the tick's turning is dropped. Afloat, the
     * water still carries it. On the ground it's kept standing on its lowest point as it turns,
     * or turning it about its middle would drive half of it into the ground.
     */
    private fun stepRighting(vessel: Vessel, attractor: CelestialBody) {
        val share = ((time - vessel.rightingStart) / RIGHT_TIME).coerceIn(0.0, 1.0)
        // Slow to start and to finish, the way people heave something over.
        val eased = share * share * (3.0 - 2.0 * share)
        Quat.slerp(vessel.rightingFrom, vessel.rightingTo, eased, vessel.body.orientation)
        vessel.body.orientation.normalizeInPlace()
        vessel.body.angularVelocity.setTo(Vec3.zero())
        if (vessel.touchingGround && !vessel.buoyed) setDown(vessel)
        if (share >= 1.0) vessel.rightingStart = Double.NaN
    }

    /**
     * [systems] with what [vessel]'s sonar hears when it's powered and under the sea: the floor
     * below it, and the nearest named place under the sea its owner hasn't found yet, within the
     * sonar's range. It gives how far away it is, and which way from the nose in degrees, with
     * right positive.
     */
    private fun sonar(vessel: Vessel, systems: ServerMessage.CraftSystems): ServerMessage.CraftSystems {
        if (!vessel.powered) return systems
        var range = 0.0
        for (i in vessel.defs.indices) {
            if (vessel.isBroken(i)) continue
            vessel.defs[i].module<com.rm.apogee.core.part.Sonar>()?.let { range = maxOf(range, it.range) }
        }
        if (range <= 0.0 || depthOf(vessel) <= 0.0) return systems
        val attractor = attractorFor(vessel)
        val terrain = attractor.terrain ?: return systems
        attractor.rotationAt(time, scratchRotation)
        val here = attractor.toBodyFixed(vessel.body.position, scratchRotation, Vec3()).normalizeInPlace()
        val floor = attractor.altitudeOf(vessel.body.position) - terrain.elevation(here)
        val found = wondersFound[vessel.owner]
        var best: SeaWonders.Wonder? = null
        var bestRange = range
        for (wonder in SeaWonders.all) {
            if (wonder.bodyId != attractor.id || found?.contains(wonder.id) == true) continue
            val far = here.distanceTo(wonder.direction) * attractor.radius
            if (far < bestRange) { best = wonder; bestRange = far }
        }
        val nearest = best ?: return systems.copy(seabed = floor.toFloat())
        // Which way it lies compared with which way the nose points, both along the ground, in the
        // body's turning frame.
        val nose = attractor.toBodyFixed(vessel.forward(), scratchRotation, Vec3())
        val relative = Navigation.heading(here, nearest.direction.copy().subInPlace(here)) - Navigation.heading(here, nose)
        return systems.copy(
            seabed = floor.toFloat(),
            findBearing = (((relative % 360.0) + 540.0) % 360.0 - 180.0).toFloat(),
            findRange = bestRange.toFloat(),
        )
    }

    private fun hasScanner(vessel: Vessel) =
        vessel.defs.indices.any { !vessel.isBroken(it) && vessel.defs[it].hasModule<com.rm.apogee.core.part.Scanner>() }

    /**
     * What the ground right under [vessel] holds of [type], by its scanner, when it's low enough
     * and powered. -1 for no reading.
     */
    private fun reading(vessel: Vessel, type: com.rm.apogee.core.part.ResourceType): Float {
        if (!vessel.powered || !hasScanner(vessel)) return -1f
        val attractor = attractorFor(vessel)
        val terrain = attractor.terrain ?: return -1f
        if (attractor.altitudeOf(vessel.body.position) > READING_HEIGHT) return -1f
        attractor.rotationAt(time, anchorRotation)
        val below = attractor.toBodyFixed(vessel.body.position, anchorRotation, Vec3())
        return com.rm.apogee.core.terrain.Deposits.richness(terrain, below, type).toFloat()
    }

    /** Where the ground stations are at [at], in the system's frame. */
    fun groundStationPositions(at: Double = time): List<Vec3> = comms.stationPositions(at)

    private fun refreshSignal(vessel: Vessel, at: Double) {
        comms.update(vessel, vesselsById.values, at, ::isDebris)
        vessel.signalAt = at
    }

    /**
     * A part of one craft hit another hard enough to fail.
     *
     * It's the same rule as hitting the ground: gear gives way and the craft lives, and anything
     * else and the craft doesn't. Collisions between craft are how a base gets damaged by something
     * landing badly on it, so the two paths agree on purpose. It would be strange for a tank to
     * survive a thirty-metre-a-second arrival onto a station but not onto a hillside.
     */

    /**
     * How finely to split up this tick's integration and contact test.
     *
     * A tick is a sixtieth of a second, and a craft coming down at thirty-five metres a second
     * covers well over half a metre in one. Anything smaller than that, like a landing leg sticking
     * out below an engine bell, a wheel, or a ridge in the terrain, can get stepped straight over,
     * so the first thing the solver ever sees is several parts already buried. That's how a
     * lander's legs got skipped while the engine above them was recorded as the part that hit.
     *
     * It's only paid for near the ground. Above the highest ground the body can have there's
     * nothing to hit, and orbital speeds would otherwise demand the most splitting on every tick of
     * every flight.
     */
    private fun contactSubsteps(vessel: Vessel, attractor: CelestialBody, dt: Double): Int {
        val body = vessel.body
        val ceiling = (attractor.terrain?.maxElevation ?: 0.0) + SUBSTEP_CEILING_METRES
        if (attractor.altitudeOf(body.position) > ceiling) return 1

        // Then against the ground actually underneath, for the same reason GroundContact does it.
        // The ceiling only rules out craft above the tallest mountain on the body, which doesn't
        // help a rocket climbing through clear air five kilometres up.
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
        // The ends of a rotating craft sweep faster than its centre.
        val sweep = scratchRelativeVelocity.length +
            body.angularVelocity.length * vessel.contactRadius
        val distance = sweep * dt
        if (distance <= MAX_SUBSTEP_DISTANCE) return 1
        return kotlin.math.ceil(distance / MAX_SUBSTEP_DISTANCE).toInt()
            .coerceAtMost(MAX_CONTACT_SUBSTEPS)
    }

    /**
     * A part hit at [speed] (m/s, into the surface) along [push] (world axes, the way the blow
     * drives it).
     *
     * The blow travels through the craft in order. The part that touched takes it first. Below its
     * crash tolerance nothing happens, and above it the damage rises steeply, ((v - tol) /
     * 2tol)^1.5, so three times its tolerance finishes it. A part that survives stops the blow
     * there, and its neighbours only feel a jolt. A part that's crushed soaks up energy as it goes
     * (its structure's mass times its tolerance squared, because propellant doesn't crumple), and
     * whatever's left carries on, slower, with the rest of the craft into the next part along the
     * line of the blow. So a nose cone or an engine bell acts as a crumple zone. A crash can strip
     * the front off a craft and leave the pod behind it whole.
     *
     * A leg is built for this and folds instead, as it always has.
     */
    fun impact(vessel: Vessel, partIndex: Int, speed: Double, push: Vec3, water: Boolean = false) {
        var part = partIndex
        var v = speed
        val local = vessel.body.orientation.inverseRotate(push)
        val visited = HashSet<Int>()
        // What's still moving behind the blow. The crushed parts stop.
        var moving = vessel.body.mass
        while (part >= 0 && visited.add(part)) {
            val def = vessel.defs[part]
            // Someone going into the water feet first takes a lot more than hitting the ground. A
            // jump off a ship's deck is nothing, and eleven metres is about the most anyone lives
            // through.
            val person = def.module<com.rm.apogee.core.part.Walker>() != null
            val tolerance = if (water && person) WATER_ENTRY else def.crashTolerance
            if (v <= tolerance) return
            // A person isn't a tank. Past what their suit can take, it's fatal.
            val blow = if (def.module<com.rm.apogee.core.part.Walker>() != null) Double.MAX_VALUE
                else Math.pow((v - tolerance) / (2.0 * tolerance), 1.5)
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
                // It held, so the blow stops here, and the parts around it get jolted.
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
     * The neighbour of [part] (its parent or a child) that lies furthest along [push] (design
     * axes), which is where a blow driving it that way goes next.
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
     * Breaks [vessel] up if any of its parts is damaged to nothing. Those parts are gone, and the
     * craft falls into however many pieces are left holding together. The piece with the controls
     * on it (or the heaviest, if none has them) stays as this vessel, so the player is still flying
     * what's left. The rest become debris, each carrying on at its own point's velocity. A tank
     * that goes with propellant in it explodes.
     *
     * If nothing's left at all, the craft is destroyed.
     */
    fun breakUp(vessel: Vessel, cause: String = "struck", detached: Set<Int> = emptySet()) {
        val destroyed = vessel.defs.indices.filter { vessel.health[it] <= 0.0 }.toSet()
        if (destroyed.isEmpty() && detached.isEmpty()) return
        failParts(vessel, destroyed, detached - destroyed, cause)
    }

    /**
     * Part [index] of [vessel] tears away at the end of the tick, with whatever hangs from it. A
     * root has no joint above it, so it tears away from everything below it instead. A craft of one
     * part has nothing to tear away from.
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
     * Removes [destroyed] parts from [vessel] and cuts the joints above [detached] parts, then
     * splits what's left into its connected pieces.
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

        // Which piece the craft carries on as: the one with the controls, otherwise the heaviest.
        fun mass(indices: List<Int>) = indices.sumOf { vessel.partMass(it) }
        fun controls(indices: List<Int>) = indices.any { vessel.defs[it].module<com.rm.apogee.core.part.Command>() != null }
        // A craft that had controls and has none left is lost along with them, and what's left is
        // wreckage, not the craft. When it carried on as its heaviest piece, a player went on
        // flying a heat shield rolling across the ground, with its parts counted as the craft's.
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

        // The debris first, each piece from the original, before the craft itself gets rebuilt
        // underneath it.
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
            // A piece broken off with a lit engine keeps it lit, the same as a stage does.
            debris.control.throttle = vessel.control.throttle
            if (members[k].none { originalDefs[it].module<com.rm.apogee.core.part.Command>() != null }) {
                debris.name = "${vessel.name} debris"
            }
            // Where its own centre was, moving the way that point of the craft was moving.
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
        // The pieces start out touching where they were joined, which is a break, not a fresh
        // collision between them.
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

    /**
     * The mass-weighted centre of parts [indices], as an offset from the craft's body position, in
     * world axes.
     */
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
     * Tanks that went up this tick. Everything within reach, on any craft, gets damaged by how
     * close it was and pushed away from the blast.
     */
    private fun resolveExplosions() {
        if (pendingBlasts.isEmpty()) return
        val blasts = pendingBlasts.toList()
        pendingBlasts.clear()
        for (blast in blasts) {
            // Energy from the propellant, capped, so a big tank is a big bang, not the end of the
            // world.
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
                    // Falling off steeply, because the shock wave spends itself fast.
                    val strength = 1.0 - d / radius
                    val ratio = BLAST_TOUGHNESS / other.defs[i].crashTolerance
                    val toughness = (ratio * ratio).coerceIn(0.25, 1.5)
                    val push = if (d > 1e-6) offset.copy().mulInPlace(1.0 / d) else Vec3(0.0, 1.0, 0.0)
                    other.damage(i, BLAST_DAMAGE * strength * strength * strength * toughness, other.body.orientation.inverseRotate(push))
                    hit = true
                }
                if (hit) {
                    // A shove away from the blast, depending on the energy and how close it was.
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
     * A sleeping craft, carried round with the ground to where it is at the end of the tick, which
     * is the time the tick's positions are reported at.
     */
    private fun followGround(vessel: Vessel, attractor: CelestialBody) {
        val body = vessel.body
        attractor.rotationAt(tickEnd, scratchRotation)
        attractor.surfaceVelocityAt(body.position, scratchSurfaceVelocity)
        attractor.angularVelocity(scratchSpin)
        vessel.followRotation(scratchRotation, scratchSurfaceVelocity, scratchSpin)
        // The ground's velocity where it is now, not where it was a tick ago. A client recognises a
        // sleeping craft by it moving with the surface exactly.
        attractor.surfaceVelocityAt(body.position, body.linearVelocity)
    }

    // --- spheres of influence ---------------------------------------------------

    /**
     * Hands [vessel] over to whichever body's pull governs it at [at]: a moon's once it's close
     * enough, and its planet's again once it's clear. It's measured from the new body's centre,
     * with the same place and motion, so nothing jumps. That's the patched-conic picture every
     * orbit is drawn in, where one body pulls at a time. True if it changed.
     */
    private fun crossInfluence(vessel: Vessel, at: Double): Boolean {
        if (vessel.dormant || vessel.anchored) return false
        val attractor = attractorFor(vessel)
        val next = system.governing(attractor, vessel.body.position, at)
        if (next === attractor) return false
        system.rebase(vessel.body.position, vessel.body.linearVelocity, attractor.id, next.id, at)
        vessel.referenceBodyId = next.id
        vessel.air.clear()
        vessel.airSampledAt = Double.NaN
        vessel.ringSide = Double.NaN
        pendingEvents.add(WorldEvent.BodyChanged(vessel.id, attractor.id, next.id))
        program?.crossed(this, vessel, attractor.id, next.id, at)
        return true
    }

    /**
     * Carries an awake [vessel] [h] seconds along its orbit on rails, into another body's pull and
     * on around that one if it crosses over. The crossing is found to within a millisecond by
     * halving the slice where it goes over.
     */
    private fun coast(vessel: Vessel, h: Double) {
        val body = vessel.body
        val start = time
        var attractor = attractorFor(vessel)
        var orbit = Orbit(body.position, body.linearVelocity, attractor.gravitationalParameter)
        var from = 0.0
        var end = orbit.propagate(h)
        val crossed = system.governing(attractor, end.position, start + h)
        if (crossed !== attractor) {
            var lo = 0.0
            var hi = h
            while (hi - lo > 1e-3) {
                val mid = 0.5 * (lo + hi)
                if (system.governing(attractor, orbit.propagate(mid).position, start + mid) === attractor) lo = mid else hi = mid
            }
            val at = orbit.propagate(hi)
            body.position.setTo(at.position)
            body.linearVelocity.setTo(at.velocity)
            val next = system.governing(attractor, body.position, start + hi)
            system.rebase(body.position, body.linearVelocity, attractor.id, next.id, start + hi)
            vessel.referenceBodyId = next.id
            vessel.ringSide = Double.NaN
            pendingEvents.add(WorldEvent.BodyChanged(vessel.id, attractor.id, next.id))
            program?.crossed(this, vessel, attractor.id, next.id, start + hi)
            attractor = next
            from = hi
            orbit = Orbit(body.position, body.linearVelocity, attractor.gravitationalParameter)
            end = orbit.propagate(h - from)
        }
        body.position.setTo(end.position)
        body.linearVelocity.setTo(end.velocity)
    }

    // --- time warp --------------------------------------------------------------

    /**
     * The fastest the world can run right now, as a multiple of real time: the lowest any awake
     * craft allows (see [warpLimit]). A sleeping craft rides the ground at any rate and doesn't
     * limit anything.
     */
    fun maxWarp(): Double {
        var limit = WARP_RATES.last()
        for (vessel in vesselsById.values) {
            // Debris doesn't hold time back. Otherwise a spent stage still falling through the air
            // would keep every warp at physics speed until it hit the ground. See [advanceOnRails].
            if (vessel.dormant || isDebris(vessel)) continue
            limit = minOf(limit, warpLimit(vessel))
        }
        return limit
    }

    /** Nothing to fly it with: a spent stage, a fairing's shell, or a piece broken off. */
    fun isDebris(vessel: Vessel): Boolean =
        !vessel.anchored && vessel.defs.indices.none { vessel.defs[it].module<com.rm.apogee.core.part.Command>() != null && !vessel.isBroken(it) }

    /**
     * How fast one craft lets time go. Up to [PHYSICS_WARP] the world just steps more often, and
     * anything goes. Beyond that craft move on rails, exactly along their orbits, which is only
     * honest for a craft that nothing but gravity is acting on: out of the air, well clear of the
     * ground, and not burning. Going faster still needs more room. Each rate needs a greater
     * height, depending on the body's size, because a tick then covers so much of an orbit that an
     * atmosphere or a mountain could slip by between two looks, or a burn the pilot meant to make.
     */
    fun warpLimit(vessel: Vessel): Double {
        if (vessel.control.throttle > 0.0 && vessel.activeEngines().isNotEmpty()) return PHYSICS_WARP
        // Landing by itself, so never on rails, which would skip past where it has to brake.
        if (vessel.control.autoLand) return PHYSICS_WARP
        // Its autopilot's burn is coming up, so real time for the turn onto it.
        if (vessel.control.autoBurn) vessel.plannedBurns.firstOrNull()?.let {
            if (time >= Burns.startOf(it, vessel.burnDuration) - Burns.WINDOW) return 1.0
        }
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
     * Moves the world on by [seconds] on rails: every awake craft along its orbit, turning as it
     * was, and every sleeping one with the ground. It goes in slices of at most [RAILS_STEP],
     * checking again after each. The moment any craft stops allowing it (it has reached the air, or
     * come down toward a moon), it stops, and says how far it got.
     */
    fun advanceOnRails(seconds: Double): Double {
        var done = 0.0
        // Never past where a warp was asked to stop.
        val seconds = if (warpUntil.isNaN()) seconds else minOf(seconds, (warpUntil - time).coerceAtLeast(0.0))
        while (seconds - done > 1e-9) {
            val allowed = maxWarp()
            if (allowed <= PHYSICS_WARP) break
            // Longer slices only as far out as the fastest warps are allowed. On a conic, far from
            // anything, a long slice is as exact as a short one.
            val h = minOf(RAILS_STEP * maxOf(1.0, allowed / RAILS_SLICE_WARP), seconds - done)
            tickEnd = time + h
            for (vessel in vesselsById.values) {
                val attractor = attractorFor(vessel)
                if (vessel.dormant) {
                    if (vessel.afloat) followSea(vessel, attractor, waves = false) else followGround(vessel, attractor)
                    continue
                }
                // Debris in the air or near the ground can't go on rails, because it would fly
                // through both, and it doesn't hold anything back. It's lost on the way down.
                if (isDebris(vessel) && warpLimit(vessel) <= PHYSICS_WARP) {
                    pendingDestruction.add(vessel.id to "lost on the way down")
                    continue
                }
                val body = vessel.body
                coast(vessel, h)
                crossRings(vessel, attractorFor(vessel))
                if (!isDebris(vessel)) {
                    power.step(vessel, attractorFor(vessel), time + h, h, rails = true)
                    vessel.powerSettledAt = time + h
                    survey(vessel, attractorFor(vessel), h)
                }
                // Still turning as it was.
                val spin = body.angularVelocity.length
                if (spin > 1e-9) {
                    scratchWarp.setTo(body.angularVelocity).mulInPlace(1.0 / spin)
                    body.orientation.setTo(Quat.fromAxisAngle(scratchWarp, spin * h) * body.orientation).normalizeInPlace()
                }
            }
            time += h
            done += h
            if (pendingDestruction.isNotEmpty()) {
                for ((id, reason) in pendingDestruction) destroy(id, reason)
                pendingDestruction.clear()
            }
        }
        if (done > 0.0) {
            watchCareer(look = true)
            tick++
        }
        return done
    }

    private val scratchWarp = Vec3()

    /** The universe time a warp was asked to stop at (see [Command.WarpTo]), or NaN for none. */
    var warpUntil: Double = Double.NaN

    /**
     * Moves the clock straight on to [until], for a launch at a chosen time of day. A parked craft
     * stays on its ground, and one in a clear orbit goes round it. Anything else, in the air or
     * just set down and not settled yet, is carried round with the planet, over the same ground and
     * moving as it was, as if time had been paused for it. Lightning isn't checked for over the
     * gap.
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
                // The planet's turn over the gap, applied to the whole craft.
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
        watchCareer(look = true)
        tick++
    }

    /** The vessel's current two-body trajectory around its attractor. */
    fun orbitOf(vessel: Vessel): Orbit {
        val attractor = attractorFor(vessel)
        return Orbit(
            position = vessel.body.position.copy(),
            velocity = vessel.body.linearVelocity.copy(),
            mu = attractor.gravitationalParameter,
            epoch = time,
        )
    }

    private fun savedLines(): List<SavedLine> = lines.map {
        SavedLine(it.a.raw, it.partA, it.b?.raw ?: -1L, it.partB, it.hook.copy(), it.ground.copy(), it.bodyId, it.length, it.reel, it.taut)
    }

    fun snapshot(): Snapshot = Snapshot(
        tick = tick,
        time = time,
        hitches = links.map { SavedLink(it.a.raw, it.partA, it.b.raw, it.partB) },
        lines = savedLines(),
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
        burns = vessel.plannedBurns.toList(),
        crew = if (vessel.crewAboard > 0) vessel.crew.map { it.toList() } else emptyList(),
        stripe = suitWearer(vessel)?.let { com.rm.apogee.core.crew.Crew.stripeFor(vessel.owner, stripes[vessel.owner]) } ?: -1,
        visor = suitWearer(vessel)?.let { com.rm.apogee.core.crew.Crew.visorOf(it) } ?: -1,
    )

    /** Who's in [vessel], if it's someone out in a suit. */
    private fun suitWearer(vessel: Vessel): com.rm.apogee.core.crew.CrewMember? {
        if (vessel.design.parts.singleOrNull()?.partId != SUIT_PART) return null
        return vessel.crew.firstOrNull()?.firstOrNull()?.let { crew[it] }
    }

    /**
     * Tells each sea where the founded bases on it are, so the currents leave the water round them
     * calm. Bases don't move, so now and then is plenty. It goes by the anchored craft this world
     * holds, which on a client's replica are the bases near the craft being flown, so the replica's
     * water agrees with the server's where it matters.
     */
    private var calmCheckedAt = -1L

    private fun calmBases() {
        val direction = Vec3()
        val byBody = HashMap<String, MutableList<Pair<Vec3, Double>>>()
        for (vessel in vesselsById.values) {
            if (!vessel.anchored) continue
            val attractor = attractorFor(vessel)
            if (attractor.ocean?.sea == null) continue
            attractor.toBodyFixed(vessel.body.position, attractor.rotationAt(time, scratchRotation), direction).normalizeInPlace()
            byBody.getOrPut(attractor.id) { ArrayList() }.add(direction.copy() to com.rm.apogee.core.sea.Sea.CALM_BASE)
        }
        for (body in system.bodies.values) {
            val sea = body.ocean?.sea ?: continue
            val wanted = byBody[body.id] ?: emptyList()
            if (sea.calmBases.size != wanted.size || sea.calmBases.zip(wanted).any { (a, b) -> a.first.distanceTo(b.first) > 1e-9 }) {
                sea.calmBases = wanted
            }
        }
    }

    /**
     * Pins [vessel] where it is now, because the server says it's founded. This is for a client's
     * replica, which takes the server's word instead of asking whether it could be.
     */
    fun pin(vessel: Vessel) {
        if (vessel.anchored) return
        val attractor = attractorFor(vessel)
        attractor.rotationAt(time, anchorRotation)
        vessel.anchor(anchorRotation)
        // A base afloat rides the same sea here as on the server, since the sea is worked out the
        // same everywhere.
        if (floatsOnSea(vessel, attractor)) settleAfloat(vessel, attractor)
    }

    /** Whether [vessel] sits in the sea, with hulls or pontoons, near enough the surface to float. */
    private fun floatsOnSea(vessel: Vessel, attractor: CelestialBody): Boolean {
        if (attractor.ocean == null || vessel.defs.none { it.hasModule<com.rm.apogee.core.part.Buoyancy>() }) return false
        return kotlin.math.abs(depthOf(vessel)) < vessel.contactRadius
    }

    // --- persistence ---------------------------------------------------------

    /**
     * Captures the whole world for saving.
     *
     * It takes a copy of everything it touches. An autosave runs on the same thread as the tick in
     * this design, but the moment it doesn't, handing out live vectors would let a save see a craft
     * halfway through a step.
     */
    fun save(): WorldSave {
        // Bases' and parked craft's power brought up to now, so what gets saved is what they have.
        for (vessel in vesselsById.values) if (vessel.anchored || (vessel.dormant && !isDebris(vessel))) settlePower(vessel)
        return saveNow()
    }

    private fun saveNow(): WorldSave = WorldSave(
        links = links.map { SavedLink(it.a.raw, it.partA, it.b.raw, it.partB) },
        lines = savedLines(),
        catalogHash = catalog.contentHash,
        felledScatter = felledScatter.sorted(),
        surveyed = surveyed.sorted(),
        crew = crew.values.toList(),
        crewSeated = true,
        terrainGeneration = TerrainField.GENERATION,
        lastFlown = lastFlown.toMap(),
        stripes = stripes.toMap(),
        wondersFound = wondersFound.mapValues { it.value.sorted() },
        weather = weatherConfig,
        mode = if (program != null) WorldSave.MODE_CAREER else WorldSave.MODE_SANDBOX,
        careers = program?.all?.toList() ?: emptyList(),
        firsts = program?.firsts?.toList() ?: emptyList(),
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
                targetBody = vessel.control.targetBody,
                burns = vessel.plannedBurns.toList(),
                brakes = vessel.control.brakes,
                deployed = vessel.control.deployed,
                fuelCellsOn = vessel.fuelCellsOn,
                drilling = vessel.control.drilling,
                refining = vessel.control.refining,
                groups = if (vessel.groupStates.any { it != 0 }) vessel.groupStates.toList() else emptyList(),
                ballast = vessel.control.ballast,
                holdDepth = vessel.control.holdDepth,
                holdDepthAt = vessel.control.holdDepthAt,
                keeping = vessel.control.keeping,
                keepPoint = vessel.control.keepPoint.copy(),
                keepTrim = vessel.control.keepTrim,
                ballonet = vessel.ballonet,
                surveyBody = vessel.surveyBody,
                surveyProgress = vessel.surveyProgress,
                crew = if (vessel.crewAboard > 0) vessel.crew.map { it.toList() } else emptyList(),
                resources = vessel.resourceSnapshot().map { it.toList() },
                legDeploy = vessel.legDeploy.toList(),
                spool = if (vessel.spool.any { it > 0.0 }) vessel.spool.toList() else emptyList(),
                health = vessel.health.toList(),
                flooded = if (vessel.flooded.any { it > 0.0 }) vessel.flooded.toList() else emptyList(),
                crumple = vessel.crumple.toList(),
                temperature = vessel.temperature.toList(),
                anchored = vessel.anchored,
                afloat = vessel.anchored && vessel.afloat,
                log = vessel.log,
            )
        },
    )

    /**
     * Replaces this world's contents with a saved one.
     *
     * @return the problems found. A craft that refers to parts this build doesn't have any more
     *     gets skipped and reported instead of quietly dropped. An operator who changed the
     *     catalogue needs to know which craft they lost.
     */
    fun restore(save: WorldSave): List<String> {
        val problems = ArrayList<String>()
        // Its bases tell the seas where to stay calm on the next tick.
        calmCheckedAt = -1L
        // Founded bases, pinned again once everything is in place, after any setting down on
        // changed ground.
        val founded = ArrayList<Vessel>()
        val foundedAfloat = HashSet<VesselId>()
        val terrainChanged = save.terrainGeneration != TerrainField.GENERATION
        felledScatter.clear()
        // Scatter ids name places on one generation's ground. On another they'd knock down some
        // unrelated tree, so new terrain grows back whole.
        if (!terrainChanged) felledScatter.addAll(save.felledScatter)
        surveyed.clear()
        surveyed.addAll(save.surveyed.filter { it in system.bodies })
        crew.clear()
        for (member in save.crew) crew[member.id] = member
        crewRevision++
        nextCrewId = (save.crew.maxOfOrNull { it.id } ?: 0L) + 1L
        lastFlown.clear()
        lastFlown.putAll(save.lastFlown)
        stripes.clear()
        stripes.putAll(save.stripes)
        wondersFound.clear()
        for ((owner, ids) in save.wondersFound) wondersFound[owner] = com.rm.apogee.core.concurrentSetOf<String>().also { it.addAll(ids) }
        save.weather?.let { weatherConfig = it }
        program = if (save.mode == WorldSave.MODE_CAREER) {
            com.rm.apogee.core.career.Program().also { it.restore(save.careers, save.firsts) }
        } else {
            null
        }
        links.clear()
        for (l in save.links) links.add(Link(VesselId(l.vesselA), l.partA, VesselId(l.vesselB), l.partB))
        lines.clear()
        for (l in save.lines) {
            lines.add(Line(VesselId(l.vesselA), l.partA, if (l.vesselB < 0) null else VesselId(l.vesselB), l.partB, l.hook.copy(), l.ground.copy(), l.bodyId, l.length).also {
                it.reel = l.reel; it.taut = l.taut
            })
        }

        if (!SaveMigration.canRead(save.formatVersion)) {
            return listOf(
                "Save is format ${save.formatVersion}, this build reads " +
                    "${WorldSave.FORMAT_VERSION}"
            )
        }
        // A different catalogue is expected, not alarming, because parts get tuned between every
        // build. What matters is whether each craft can still be put together, which gets settled
        // per craft below.
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
                // Format 1 stored a display name where the id now goes. There's no way to work out
                // which install that was, and guessing would hand someone else's base to whoever
                // types the same name. The craft keeps its label and becomes unowned, which is the
                // honest outcome. It's still there, still yours to fly, and claimable instead of
                // locked to a name.
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
            vessel.control.targetBody = saved.targetBody.takeIf { it in system.bodies } ?: ""
            vessel.plannedBurns.addAll(saved.burns)
            vessel.burnDuration = if (saved.burns.isEmpty()) 0.0 else Burns.duration(vessel, saved.burns.first().deltaV)
            vessel.control.brakes = saved.brakes
            vessel.control.deployed = saved.deployed
            vessel.fuelCellsOn = saved.fuelCellsOn
            vessel.control.drilling = saved.drilling
            saved.groups.forEachIndexed { k, state -> if (k < vessel.groupStates.size) vessel.groupStates[k] = state }
            vessel.control.refining = saved.refining
            vessel.control.ballast = saved.ballast
            vessel.control.holdDepth = saved.holdDepth
            vessel.control.holdDepthAt = saved.holdDepthAt
            vessel.control.keeping = saved.keeping
            vessel.control.keepPoint.setTo(saved.keepPoint)
            vessel.control.keepTrim = saved.keepTrim
            vessel.ballonet = saved.ballonet
            vessel.log = saved.log
            vessel.surveyBody = saved.surveyBody
            vessel.surveyProgress = saved.surveyProgress
            saved.crew.forEachIndexed { i, seat -> if (i < vessel.crew.size) vessel.crew[i] = seat.toLongArray() }
            vessel.fitPose()
            saved.legDeploy.forEachIndexed { i, progress -> vessel.setLegDeploy(i, progress) }
            saved.spool.forEachIndexed { i, speed -> if (i < vessel.spool.size) vessel.spool[i] = speed.coerceIn(0.0, 1.0) }
            if (saved.resources.isNotEmpty()) {
                vessel.restoreResources(saved.resources.map { it.toDoubleArray() })
            }
            vessel.restoreCondition(saved.health, saved.crumple, saved.temperature)
            saved.flooded.forEachIndexed { i, kg -> if (i < vessel.flooded.size) vessel.flooded[i] = kg }
            vessel.recomputeMass(shiftBodyPosition = false)
            if (saved.anchored) founded.add(vessel)
            if (saved.anchored && saved.afloat) foundedAfloat.add(vessel.id)

            vesselsById[vessel.id] = vessel
            // Everyone connected needs to be told these exist.
            pendingEvents.add(WorldEvent.VesselSpawned(vessel.id))

            // A save written by an older build could have an id at or past the counter, and handing
            // it out again would collide.
            if (saved.id >= nextVesselId) nextVesselId = saved.id + 1
        }

        // From before there were crew, so fill everyone's seats and nothing is an empty pod.
        if (!save.crewSeated) for (vessel in vesselsById.values) seatCrew(vessel)

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
            // A base founded afloat rides the sea again.
            if (vessel.id in foundedAfloat) settleAfloat(vessel, attractorFor(vessel))
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
     * It returns the vessel, because whoever asked for it almost certainly wants to fly it, which
     * is the difference between launching a module and just adding one to the scenery.
     */
    fun spawnFor(command: Command.SpawnCraft, owner: String): Vessel {
        if (command.siteId.startsWith(LaunchSite.BASE_SITE_PREFIX)) {
            val (base, pad) = command.siteId.removePrefix(LaunchSite.BASE_SITE_PREFIX).split(":").let {
                vesselsById[VesselId(it.getOrNull(0)?.toLongOrNull() ?: -1)] to (it.getOrNull(1)?.toIntOrNull() ?: -1)
            }
            if (base != null && base.anchored && mayLaunchFrom(base, owner) && base.defs.getOrNull(pad)?.module<com.rm.apogee.core.part.LaunchPad>() != null) {
                return spawnOnBasePad(command.design, base, pad, legsOut = true).also { assignOwner(it, owner) }
            }
        }
        val site = launchSites.firstOrNull { it.id == command.siteId } ?: launchSites.first()
        val vessel = spawnAtSite(command.design, site, legsOut = true)
        assignOwner(vessel, owner)
        return vessel
    }

    /**
     * Puts a craft on the nearest clear pad at [site]. That's the site itself if nothing's standing
     * there, otherwise the next pad out, alternating either side, forty metres apart.
     *
     * Clear means clear of the whole craft, not just its centre. A pad counts as taken while any
     * craft is closer than the two craft's radii plus a margin, so a wide aeroplane doesn't get a
     * rocket put through its wing.
     */
    fun spawnAtSite(design: CraftDesign, site: LaunchSite, legsOut: Boolean = false): Vessel =
        spawnOnSurface(design, site, pad = nextFreePad(site, radiusOf(design)), legsOut = legsOut)

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
     * A persistent world builds up craft at the launch complex, and dropping a new one onto a pad
     * that's already taken would spawn it inside somebody's base, which now that craft are solid is
     * an explosion instead of a curiosity.
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
                // A founded base can be hundreds of metres across with its pads in the middle of
                // it, so what matters is its buildings, one by one, not the reach of the whole
                // thing.
                val near = if (other.anchored && gap < 0.0) partsGap(other, spot, radius) else gap
                if (near < clearance) clearance = near
            }
            if (clearance >= 0.0) return pad
            if (clearance > bestClearance) {
                bestClearance = clearance
                best = pad
            }
        }
        // Every pad is taken, so pick the one with the most room.
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
        // Exact, not case-insensitive. This is an opaque id now, not a name someone typed, so
        // ignoring case could only ever create a false match.
        else vesselsById.values.firstOrNull { it.owner == owner }

    /**
     * Puts a fresh copy of craft [id] back on its launch site (the site its design would launch
     * from, on the nearest clear pad), fuelled and unstaged, with the same name and owner. The
     * craft as it was is gone, like a rover stuck in a ravine or a lander on its side. Returns the
     * new craft, or null if there was no such craft.
     */
    fun resetToSite(id: VesselId): Vessel? {
        val old = vesselsById[id] ?: return null
        val design = old.design
        val name = old.name
        val owner = old.owner
        val ownerName = old.ownerName
        destroy(id, RESET_REASON)
        val fresh = spawnAtSite(design, launchSiteFor(design, catalog), legsOut = true)
        fresh.name = name
        assignOwner(fresh, owner)
        fresh.ownerName = ownerName
        lastFlown.entries.filter { it.value == id.raw }.forEach { lastFlown[it.key] = fresh.id.raw }
        return fresh
    }

    fun destroy(id: VesselId, reason: String) {
        val vessel = vesselsById.remove(id) ?: return
        // Taken away by its owner, or reset: home safe if it's somewhere they could walk away from.
        // Otherwise, they went with it.
        val home = reason == RESET_REASON || (reason == REMOVED_REASON && recoverable(vessel))
        for (seat in vessel.crew) for (member in seat) if (home) releaseCrew(member) else loseCrew(member, vessel, reason)
        pendingEvents.add(WorldEvent.VesselDestroyed(id, reason))
    }

    // --- crew -------------------------------------------------------------------------

    /** Everyone who has ever flown, by id: at home, aboard, or remembered. */
    val crew = LinkedHashMap<Long, com.rm.apogee.core.crew.CrewMember>()
    private var nextCrewId = 1L

    /** Goes up whenever anyone's crew changes (who's where, who was lost), so rosters only get sent when they change. */
    var crewRevision = 0L
        private set

    private fun setCrew(member: com.rm.apogee.core.crew.CrewMember) {
        crew[member.id] = member
        crewRevision++
    }

    /** Gives crew member [id] visor [visor] (-1 for one of their own). */
    fun setVisor(id: Long, visor: Int) {
        val member = crew[id] ?: return
        setCrew(member.copy(visor = visor.coerceIn(-1, com.rm.apogee.core.crew.Crew.VISORS - 1)))
    }

    /** [owner]'s crew, in the order they joined. */
    fun crewOf(owner: String): List<com.rm.apogee.core.crew.CrewMember> = crew.values.filter { it.owner == owner }

    /** A new recruit for [owner]. They're free, until a career says otherwise. */
    fun recruit(owner: String): com.rm.apogee.core.crew.CrewMember {
        val id = nextCrewId++
        val member = com.rm.apogee.core.crew.CrewMember(id, com.rm.apogee.core.crew.Crew.nameFor(id), owner)
        setCrew(member)
        return member
    }

    /**
     * Fills [vessel]'s empty seats from its owner's crew at home, recruiting as many more as it
     * takes. The world's own buildings don't seat anybody.
     */
    fun seatCrew(vessel: Vessel) {
        if (vessel.owner == WORLD_OWNER) return
        for (i in vessel.defs.indices) {
            val seats = com.rm.apogee.core.crew.Crew.seatsIn(vessel.defs[i])
            val have = vessel.crew[i]
            if (have.size >= seats) continue
            val boarded = (0 until seats - have.size).map { board(vessel.owner, vessel) }.filter { it >= 0L }
            vessel.crew[i] = have + boarded.toLongArray()
        }
    }

    /**
     * Someone of [owner]'s at home, put into [vessel]. They're recruited if need be (in a career,
     * only while there's room), or it's -1.
     */
    private fun board(owner: String, vessel: Vessel): Long {
        val member = crew.values.firstOrNull { it.owner == owner && it.status == com.rm.apogee.core.crew.CrewStatus.AVAILABLE }
            ?: run {
                val cap = program?.crewCap(owner) ?: Int.MAX_VALUE
                if (crew.values.count { it.owner == owner && it.status != com.rm.apogee.core.crew.CrewStatus.LOST } >= cap) return -1L
                recruit(owner)
            }
        setCrew(member.copy(status = com.rm.apogee.core.crew.CrewStatus.ABOARD, vessel = vessel.id.raw))
        return member.id
    }

    /**
     * Gives [vessel] to [owner], with its seats filled from their crew instead of whoever it
     * spawned with.
     */
    fun assignOwner(vessel: Vessel, owner: String) {
        if (vessel.log == null) program?.launched(vessel)
        if (vessel.owner == owner && vessel.crewAboard > 0) return
        for (i in vessel.crew.indices) {
            for (member in vessel.crew[i]) releaseCrew(member)
            vessel.crew[i] = Vessel.NO_CREW
        }
        vessel.owner = owner
        seatCrew(vessel)
    }

    private val walking = Walking()
    private val scratchSteer = Vec3()

    /**
     * What the place itself does to [vessel]: air heavy enough to crush a part (Caligo's, or a gas
     * giant's deep down), and the star's own heat close in. Parts give way as they get hurt, and
     * the craft breaks up the way it would from any blow.
     */
    private fun hostile(vessel: Vessel, attractor: CelestialBody, dt: Double) {
        val position = vessel.body.position
        // The star.
        if (attractor.id == SolarSystem.STAR_ID) {
            val nearness = attractor.radius * SOL_REACH / position.length
            if (nearness > 1.0) {
                var lost = false
                for (i in vessel.defs.indices) if (vessel.damage(i, SOL_BURN * nearness * nearness * dt)) lost = true
                if (lost) { pendingBreakUps.add(vessel.id); breakUpCause[vessel.id] = "burnt up by the sun" }
            }
            return
        }
        underSea(vessel, attractor, dt)
        val air = attractor.atmosphere ?: return
        val pressure = air.pressureAt(attractor.altitudeOf(position))
        if (pressure <= CRUSH_FLOOR) return
        val inside = vessel.enclosed()
        var lost = false
        for (i in vessel.defs.indices) {
            // Inside a closed shell, only the shell feels it.
            if (inside.getOrElse(i) { false }) continue
            val over = pressure / vessel.defs[i].maxPressure - 1.0
            if (over > 0.0 && vessel.damage(i, CRUSH_RATE * over * dt)) lost = true
        }
        if (lost) {
            pendingBreakUps.add(vessel.id)
            breakUpCause[vessel.id] = if (air.deep) "crushed in the deep" else "crushed by the air"
        }
    }

    /**
     * The sea's weight on [vessel]'s hollow parts, each at its own depth: the air on the surface
     * above it plus the water in between. A part past what it's built for gives way, slowly when
     * it's just over and in seconds at twice its rating. [Vessel.crushShare] is how close the worst
     * of them is, so the HUD can warn about it. Solid parts only get squeezed, and a closed shell
     * keeps it off whatever is inside.
     */
    private fun underSea(vessel: Vessel, attractor: CelestialBody, dt: Double) {
        vessel.crushShare = 0.0
        // The world's own craft (a wreck on the floor) already went down.
        if (vessel.owner == WORLD_OWNER) return
        val ocean = attractor.ocean ?: return
        if (attractor.altitudeOf(vessel.body.position) > SEA_CHECK_HEIGHT) return
        attractor.rotationAt(time, scratchRotation)
        attractor.toBodyFixed(vessel.body.position, scratchRotation, scratchSea)
        // From the waves the water pass keeps for it, when they're fresh: working the sea out afresh
        // here, every step, was an eighth of a boat's.
        val surface = hydrostatics.keptHeight(vessel, attractor, scratchSea, time).takeIf { !it.isNaN() }
            ?: ocean.surfaceHeight(scratchSea, time)
        val onTop = attractor.atmosphere?.pressureAt(surface) ?: 0.0
        val g = attractor.gravitationalParameter / (attractor.radius * attractor.radius)
        var inside: BooleanArray? = null
        var lost = false
        var worst = 0.0
        for (i in vessel.defs.indices) {
            val def = vessel.defs[i]
            if (!def.isHollow || vessel.isBroken(i)) continue
            val depth = surface - attractor.altitudeOf(vessel.partPositionWorld(i, scratchPart))
            if (depth <= 0.0) continue
            if ((inside ?: vessel.enclosed().also { inside = it }).getOrElse(i) { false }) continue
            val share = (onTop + ocean.density * g * depth) / def.maxPressure
            worst = maxOf(worst, share)
            if (share > 1.0 && vessel.damage(i, WATER_CRUSH_RATE * (share - 1.0) * dt)) lost = true
        }
        vessel.crushShare = worst
        if (lost) {
            pendingBreakUps.add(vessel.id)
            breakUpCause[vessel.id] = "crushed by the sea"
        }
    }

    private val scratchSea = Vec3()
    private val scratchPart = Vec3()

    /**
     * How far [vessel]'s centre is below the sea's surface above it, in metres. It's 0 or less out
     * of the water, or with no sea.
     */
    fun depthOf(vessel: Vessel): Double {
        val attractor = attractorFor(vessel)
        val ocean = attractor.ocean ?: return 0.0
        attractor.rotationAt(time, scratchRotation)
        attractor.toBodyFixed(vessel.body.position, scratchRotation, scratchSea)
        return ocean.surfaceHeight(scratchSea, time) - attractor.altitudeOf(vessel.body.position)
    }

    /**
     * [vessel]'s ballast tanks letting the sea in or blowing it out. While holding a depth, the
     * tanks are worked to hold it: if it's sinking faster than it should toward the depth, a little
     * gets blown, and if it's rising, a little gets let in. I aim for a gentle climb or sink toward
     * the depth instead of the depth itself, and leave it alone within a small band, so it doesn't
     * hunt.
     */
    private fun ballast(vessel: Vessel, attractor: CelestialBody, dt: Double) {
        val control = vessel.control
        if (control.ballast == 0 && !control.holdDepth) return
        // Someone swimming dives and rises by swimming, and holds whatever depth they got to.
        if (walking.walkerOf(vessel) != null) {
            if (control.ballast != 0) control.holdDepthAt = maxOf(0.0, depthOf(vessel))
            return
        }
        // Up in the air, the same buttons work the gas cells' ballonets.
        if (gasCraft(vessel)) { ballonets(vessel, attractor, dt); return }
        val ocean = attractor.ocean
        var mode = control.ballast
        if (control.holdDepth) {
            val depth = depthOf(vessel)
            attractor.surfaceVelocityAt(vessel.body.position, scratchSea)
            val climb = scratchSea.subInPlace(vessel.body.linearVelocity).mulInPlace(-1.0) dot vessel.body.position.normalized()
            val wanted = ((depth - control.holdDepthAt) * HOLD_DEPTH_GAIN).coerceIn(-HOLD_DEPTH_SPEED, HOLD_DEPTH_SPEED)
            mode = when {
                climb < wanted - HOLD_DEPTH_BAND -> -1
                climb > wanted + HOLD_DEPTH_BAND -> 1
                else -> 0
            }
            if (mode == 0) return
        }
        var changed = false
        for (i in vessel.defs.indices) {
            val tank = vessel.defs[i].module<com.rm.apogee.core.part.Ballast>() ?: continue
            if (vessel.isBroken(i) || ocean == null) continue
            val full = tank.volume * ocean.density
            val step = tank.rate * ocean.density * dt
            val had = vessel.flooded[i]
            if (mode > 0) {
                // Only the sea it's in can come in, through vents in its underside. While any of it
                // is under, it floods.
                attractor.rotationAt(time, scratchRotation)
                attractor.toBodyFixed(vessel.partPositionWorld(i, scratchPart), scratchRotation, scratchSea)
                val reach = vessel.defs[i].boundsHalfExtents.let { maxOf(it.x, it.y, it.z) }
                if (ocean.surfaceHeight(scratchSea, time) < attractor.altitudeOf(scratchPart) - reach) continue
                vessel.flooded[i] = minOf(full, had + step)
            } else if (had > 0.0 && vessel.drawCharge(tank.draw * dt)) {
                vessel.flooded[i] = maxOf(0.0, had - step)
            }
            if (vessel.flooded[i] != had) changed = true
        }
        if (changed && ++ballastSinceMass >= BALLAST_MASS_EVERY) { vessel.recomputeMass(); ballastSinceMass = 0 }
    }

    private var ballastSinceMass = 0

    /** What [vessel]'s gas cells lift as a share of its weight, or -1 with none. */
    fun liftShare(vessel: Vessel): Double {
        if (vessel.defs.none { it.module<com.rm.apogee.core.part.LiftGas>() != null }) return -1.0
        val weight = vessel.body.mass * attractorFor(vessel).gravityAt(vessel.body.position, scratchLift).length
        return if (weight > 0.0) vessel.gasLift / weight else 0.0
    }

    private val scratchLift = Vec3()

    /** Whether [vessel] floats on gas cells and isn't in the water, so its ballast is air. */
    private fun gasCraft(vessel: Vessel): Boolean =
        !vessel.buoyed && vessel.defs.indices.any { vessel.defs[it].module<com.rm.apogee.core.part.LiftGas>() != null && !vessel.isBroken(it) }

    /**
     * Fills [vessel]'s ballonets with air to sink, or lets it out to rise, or holds a height with
     * them, the way a submarine's tanks hold a depth. Pumping air in takes charge; letting it out
     * doesn't.
     */
    private fun ballonets(vessel: Vessel, attractor: CelestialBody, dt: Double) {
        val control = vessel.control
        var mode = control.ballast
        if (control.holdDepth) {
            // The fill that gives the lift for the climb it should have, like the keeper core.
            val below = -attractor.altitudeOf(vessel.body.position) - control.holdDepthAt
            attractor.surfaceVelocityAt(vessel.body.position, scratchSea)
            val climb = scratchSea.subInPlace(vessel.body.linearVelocity).mulInPlace(-1.0) dot vessel.body.position.normalized()
            val g = attractor.gravityAt(vessel.body.position, scratchLift).length
            val trim = Aerostatics.trimFor(vessel, below, climb, g, dt)
            mode = when {
                trim > vessel.ballonet + Aerostatics.TRIM_NEAR -> 1
                trim < vessel.ballonet - Aerostatics.TRIM_NEAR -> -1
                else -> 0
            }
            if (mode == 0) return
        }
        var rate = 0.0
        var draw = 0.0
        for (i in vessel.defs.indices) {
            val gas = vessel.defs[i].module<com.rm.apogee.core.part.LiftGas>() ?: continue
            if (vessel.isBroken(i)) continue
            rate = maxOf(rate, gas.trimRate)
            draw += gas.draw
        }
        if (mode > 0) { if (vessel.drawCharge(draw * dt)) vessel.ballonet += rate * dt }
        else if (mode < 0) vessel.ballonet -= rate * dt
    }

    /** How full [vessel]'s ballast tanks are, 0..1, or -1 if it has none. */
    fun ballastShare(vessel: Vessel): Double {
        val ocean = attractorFor(vessel).ocean
        var room = 0.0
        var held = 0.0
        for (i in vessel.defs.indices) {
            val tank = vessel.defs[i].module<com.rm.apogee.core.part.Ballast>() ?: continue
            room += tank.volume * (ocean?.density ?: 1_025.0)
            held += vessel.flooded[i]
        }
        if (room > 0.0) return (held / room).coerceIn(0.0, 1.0)
        // Aloft on gas cells, how full the ballonets are.
        return if (gasCraft(vessel)) vessel.ballonet else -1.0
    }

    /**
     * A craft that crossed [attractor]'s rings since it was last looked at, going through them
     * instead of along with them, gets torn apart. Rings are gravel on circular orbits, so a craft
     * on a circular orbit of its own, in their plane, can ride among them.
     */
    private fun crossRings(vessel: Vessel, attractor: CelestialBody) {
        val rings = attractor.rings
        val position = vessel.body.position
        val side = if (rings == null) Double.NaN else position dot attractor.spinAxis
        val before = vessel.ringSide
        vessel.ringSide = side
        if (rings == null || before.isNaN() || before * side > 0.0) return
        scratchRing.setTo(position).addScaledInPlace(attractor.spinAxis, -side)
        val r = scratchRing.length
        if (r < rings.inner || r > rings.outer) return
        // The gravel here: round the planet at this radius, the way it spins.
        val speed = kotlin.math.sqrt(attractor.gravitationalParameter / r)
        scratchRing.normalizeInPlace()
        attractor.spinAxis.cross(scratchRing).normalizeInPlace().mulInPlace(speed).let { gravel ->
            if (gravel.subInPlace(vessel.body.linearVelocity).length > RING_SPEED) {
                pendingDestruction.add(vessel.id to "torn apart in the rings")
            }
        }
    }

    private val scratchRing = Vec3()

    /**
     * The ladder [vessel] is holding, as it's held now. It lets go if the ladder has gone, broken,
     * or drifted out of reach.
     */
    private fun heldLadder(vessel: Vessel, dt: Double): Walking.LadderHold? {
        val craft = vesselsById[VesselId(vessel.ladderVessel)]
        // The ladder's craft may have been moved on to the end of this tick already, and the one
        // holding it is still at the start. Everything on Terra goes round with it at a couple of
        // hundred metres a second, so a tick apart they were three metres apart, and let go.
        val lead = if (craft != null && craft.movedTick == tick) dt else 0.0
        val hold = craft?.takeIf { it.referenceBodyId == vessel.referenceBodyId }?.let { walking.ladderOf(it, vessel.ladderPart, lead) }
        scratchLadderAt.setTo(vessel.body.position).addScaledInPlace(vessel.body.linearVelocity, lead)
        if (hold == null || walking.distanceTo(hold, scratchLadderAt) > LADDER_SLIP) {
            vessel.ladderVessel = -1L
            vessel.ladderPart = -1
            return null
        }
        return hold
    }

    private val scratchLadderAt = Vec3()

    /** Whether [vessel] is someone on a ladder. */
    fun onLadder(vessel: Vessel): Boolean = vessel.ladderVessel >= 0

    /** The design for someone out of their craft: [name] in a suit. */
    private fun suitDesign(name: String) = CraftDesign(
        name = name, parts = listOf(com.rm.apogee.core.craft.PlacedPart(SUIT_PART, Vec3.zero())),
        stages = emptyList(), manualStaging = true, catalogHash = catalog.contentHash,
    )

    /**
     * Crew member [crewId] climbs out of craft [vesselId] into a suit of their own, a craft of one,
     * beside it. That's on the ground next to it if it's landed, or beside their part if not, and
     * moving as it moves. It returns null, and nobody moves, if they aren't aboard or it's going
     * too fast to step off.
     */
    /** Why the last EVA asked for didn't happen, when it's worth saying, or empty. */
    var evaRefusal: String = ""
        private set

    /** Crew who came in out of the sea cold, as how cold and when, so they're still cold going straight back out. */
    private val chilled = HashMap<Long, Pair<Double, Double>>()

    /** How far under the sea's surface [point] is, in metres, or 0 or less out of it. */
    private fun depthAt(attractor: CelestialBody, point: Vec3): Double {
        val ocean = attractor.ocean ?: return 0.0
        attractor.rotationAt(time, scratchRotation)
        attractor.toBodyFixed(point, scratchRotation, scratchSea)
        return ocean.surfaceHeight(scratchSea, time) - attractor.altitudeOf(point)
    }

    /** Whether [vessel] is someone in the sea, swimming or down on the bottom of it. */
    fun inSea(vessel: Vessel): Boolean =
        walking.walkerOf(vessel) != null && (vessel.swimming || (vessel.onFeet && vessel.standingOn == null && depthOf(vessel) > SUIT_HALF_HEIGHT))

    /** Why nobody can go outside from [vessel] now, or empty. Only the sea's depth is checked here. */
    private fun evaBlocked(vessel: Vessel): String {
        if (walking.walkerOf(vessel) != null) return ""
        val part = vessel.crew.indexOfFirst { it.isNotEmpty() }
        if (part < 0) return ""
        val attractor = attractorFor(vessel)
        return if (depthAt(attractor, vessel.partPositionWorld(part, scratchCrew)) > suitDeepest(attractor)) TOO_DEEP else ""
    }

    /** The deepest, in metres, a suit can go in [attractor]'s sea before it starts to give. */
    fun suitDeepest(attractor: CelestialBody): Double {
        val ocean = attractor.ocean ?: return Double.MAX_VALUE
        val suit = catalog[SUIT_PART] ?: return Double.MAX_VALUE
        val g = attractor.gravitationalParameter / (attractor.radius * attractor.radius)
        val air = attractor.atmosphere?.pressureAt(0.0) ?: 0.0
        return (suit.maxPressure - air) / (ocean.density * g)
    }

    fun eva(vesselId: Long, crewId: Long): Vessel? {
        val craft = vesselsById[VesselId(vesselId)] ?: return null
        val part = craft.crew.indexOfFirst { crewId in it }
        val member = crew[crewId]
        if (part < 0 || member == null) return null
        val attractor = attractorFor(craft)
        // Nobody steps out into air that would crush or cook them.
        attractor.atmosphere?.let { air ->
            val altitude = attractor.altitudeOf(craft.body.position)
            if (air.pressureAt(altitude) > EVA_PRESSURE || air.temperatureAt(altitude) > EVA_HOT) return null
        }
        val up = craft.body.position.normalized()
        attractor.surfaceVelocityAt(craft.body.position, scratchCrew).subInPlace(craft.body.linearVelocity)
        val landed = craft.touchingGround || craft.dormant || craft.anchored
        if (landed && scratchCrew.length > EVA_LANDED_SPEED) return null
        // A boat asleep on the water isn't on the ground. Stepped out beside it, they were put a
        // craft's length off, in the sea, as if walking away from it on land. Over the side they
        // go, beside where they were sitting.
        val onGround = landed && !(craft.dormant && craft.afloat && !craft.touchingGround)
        // Out from the craft's axis, level.
        val partAt = craft.partPositionWorld(part, Vec3())
        val out = partAt.copy().subInPlace(craft.body.position)
        out.addScaledInPlace(up, -(out dot up))
        if (out.length < 0.1) out.setTo(up.cross(if (kotlin.math.abs(up.y) < 0.9) Vec3.unitY() else Vec3.unitX()))
        out.normalizeInPlace()
        // Under water, out through the hatch at the depth it's at, not up at the surface, and not at
        // all deeper than a suit can take.
        val under = depthAt(attractor, partAt) > SUIT_HALF_HEIGHT
        if (under && depthAt(attractor, partAt) > suitDeepest(attractor)) {
            evaRefusal = "$TOO_DEEP to go outside: a suit's good to ${suitDeepest(attractor).toInt()} m"
            return null
        }
        val position = if (onGround && !under) {
            val foot = craft.body.position.copy().addScaledInPlace(out, craft.contactRadius + SUIT_CLEARANCE)
            attractor.rotationAt(time, scratchRotation)
            val fixed = attractor.toBodyFixed(foot, scratchRotation, Vec3()).normalizeInPlace()
            foot.normalizeInPlace().mulInPlace(attractor.surfaceRadiusInBodyFrame(fixed) + SUIT_HALF_HEIGHT + 0.05)
        } else {
            val reach = craft.defs[part].boundsHalfExtents.let { maxOf(it.x, it.y, it.z) } + SUIT_CLEARANCE
            partAt.copy().addScaledInPlace(out, reach)
        }
        val velocity = craft.body.velocityAtOffset(position.copy().subInPlace(craft.body.position))
        // Upright, facing away from the craft they came out of, so walking forward takes them away
        // from it.
        val rotation = quatFromTo(Vec3.unitY(), up)
        val facing = rotation.rotate(Walking.FACING, Vec3())
        val turn = quatFromTo(facing.addScaledInPlace(up, -(facing dot up)).normalizeInPlace(), out)
        val suit = spawnAt(suitDesign(member.name), craft.referenceBodyId, position, velocity, (turn * rotation).normalizeInPlace(), seat = false)
        suit.owner = member.owner
        program?.launched(suit)
        suit.ownerName = craft.ownerName
        suit.control.rcsEnabled = true
        // Treading water where they came out, and as cold as they were when they climbed in, less
        // what they've warmed up since.
        if (under) suit.control.holdDepthAt = depthAt(attractor, position)
        chilled.remove(crewId)?.let { (chill, at) -> suit.chill = maxOf(0.0, chill - (time - at) / WARM_ABOARD) }
        evaRefusal = ""
        craft.crew[part] = craft.crew[part].filter { it != crewId }.toLongArray()
        suit.crew[0] = longArrayOf(crewId)
        setCrew(member.copy(vessel = suit.id.raw))
        pendingEvents.add(WorldEvent.VesselStructureChanged(craft.id))
        pendingEvents.add(WorldEvent.VesselStructureChanged(suit.id))
        return suit
    }

    /**
     * Someone in suit [suitId] climbs into a free seat of craft [targetId] (or, with -1, the
     * nearest craft that has one in reach), and the suit is gone. It can be anyone's craft. Aboard
     * someone else's, they ride along. Null if there's no free seat in reach.
     */
    fun boardCraft(suitId: Long, targetId: Long = -1L): Vessel? {
        val suit = vesselsById[VesselId(suitId)] ?: return null
        if (walking.walkerOf(suit) == null) return null
        val crewId = suit.crew.getOrNull(0)?.firstOrNull() ?: return null
        val member = crew[crewId] ?: return null
        val seat = seatInReach(suit, targetId) ?: return null
        val (target, part) = seat
        if (suit.chill > 0.0) chilled[crewId] = suit.chill to time
        target.crew[part] = target.crew[part] + crewId
        suit.crew[0] = Vessel.NO_CREW
        setCrew(member.copy(vessel = target.id.raw))
        program?.boarded(this, suit, target)
        destroy(suit.id, BOARDED_REASON)
        target.wake()
        pendingEvents.add(WorldEvent.VesselStructureChanged(target.id))
        return target
    }

    /**
     * The nearest free seat to suit [suit] within reach, as a craft and part. Only in [targetId] if
     * it isn't -1.
     */
    fun seatInReach(suit: Vessel, targetId: Long = -1L): Pair<Vessel, Int>? {
        // From the water at the surface there's no climbing straight into a seat. They get out onto
        // something first, up a ladder or onto a deck low enough to climb onto. Under water, at a
        // base's or a submarine's hatch, they swim straight in.
        if (suit.swimming && depthOf(suit) < HATCH_DEPTH) return null
        var best: Pair<Vessel, Int>? = null
        var bestGap = BOARD_REACH
        for (craft in vesselsById.values) {
            if (craft === suit || craft.referenceBodyId != suit.referenceBodyId) continue
            if (targetId >= 0 && craft.id.raw != targetId) continue
            if (walking.walkerOf(craft) != null) continue
            // Far off, so no part of it is near.
            if (craft.body.position.distanceTo(suit.body.position) > craft.contactRadius + BOARD_REACH + SUIT_HALF_HEIGHT) continue
            for (i in craft.defs.indices) {
                if (craft.isBroken(i)) continue
                if (com.rm.apogee.core.crew.Crew.seatsIn(craft.defs[i]) <= craft.crew[i].size) continue
                val size = craft.defs[i].boundsHalfExtents.let { maxOf(it.x, it.y, it.z) }
                val gap = craft.partPositionWorld(i, scratchCrew).distanceTo(suit.body.position) - size - SUIT_HALF_HEIGHT
                if (gap < bestGap) { bestGap = gap; best = craft to i }
            }
        }
        return best
    }

    /** Moves crew member [crewId] within craft [vesselId] to a free seat in [part]. */
    fun transferCrew(vesselId: Long, crewId: Long, part: Int): Boolean {
        val craft = vesselsById[VesselId(vesselId)] ?: return false
        val from = craft.crew.indexOfFirst { crewId in it }
        if (from < 0 || part !in craft.defs.indices || part == from || craft.isBroken(part)) return false
        if (com.rm.apogee.core.crew.Crew.seatsIn(craft.defs[part]) <= craft.crew[part].size) return false
        craft.crew[from] = craft.crew[from].filter { it != crewId }.toLongArray()
        craft.crew[part] = craft.crew[part] + crewId
        crewRevision++
        pendingEvents.add(WorldEvent.VesselStructureChanged(craft.id))
        return true
    }

    /**
     * Where someone out of their craft is in the sea, or null if they aren't in it: on land, on a
     * deck, or with their middle clear of the water.
     */
    private fun waterFor(vessel: Vessel, attractor: CelestialBody): Walking.Water? {
        val ocean = attractor.ocean ?: return null
        if (vessel.standingOn != null) return null
        attractor.rotationAt(time, scratchRotation)
        attractor.toBodyFixed(vessel.body.position, scratchRotation, scratchSea).normalizeInPlace()
        val floor = attractor.solidRadiusInBodyFrame(scratchSea)
        val surface = attractor.radius + ocean.surfaceHeight(scratchSea, time)
        // The ground is above the sea here, so it's land, whatever the waves are doing.
        if (floor >= surface) return null
        val r = vessel.body.position.length
        val depth = surface - r
        if (depth < -SUIT_HALF_HEIGHT * 0.5) return null
        val g = attractor.gravityAt(vessel.body.position, scratchCrew).length
        val air = attractor.atmosphere?.pressureAt(0.0) ?: 0.0
        val deepest = (vessel.defs[0].maxPressure * DIVE_SHARE - air) / (ocean.density * g)
        return Walking.Water(depth, r - SUIT_HALF_HEIGHT - floor, deepest, ocean.density)
    }

    /**
     * Someone in the sea getting colder, or out of it warming up again. How long they can last
     * goes by how cold the water is: a couple of hours in a warm sea, a quarter of an hour at
     * freezing, and minutes in another world's.
     */
    private fun cold(vessel: Vessel, attractor: CelestialBody, water: Walking.Water?, dt: Double) {
        if (water != null && water.depth > -SUIT_HALF_HEIGHT * 0.5) {
            vessel.chill += dt / SeaCold.lasts(attractor, vessel.body.position, water.depth, time)
            if (vessel.chill >= 1.0 && pendingDestruction.none { it.first == vessel.id }) pendingDestruction.add(vessel.id to COLD_REASON)
        } else if (vessel.chill > 0.0) {
            vessel.chill = maxOf(0.0, vessel.chill - dt / WARM_OUT)
        }
    }

    /**
     * Where someone in the water, or near the top of a ladder, could climb out onto: a craft, and
     * the spot on it they'd stand. It's the top of a part within [CLIMB_REACH] of them toward the
     * craft, low enough to get up onto, with open air above it: a jet ski's, a runabout's or a
     * platform's deck from the water, or a ship's from the top of her ladder. A ship's side is a
     * wall from the water.
     */
    fun climbSpot(suit: Vessel): Pair<Vessel, Vec3>? {
        if (walking.walkerOf(suit) == null) return null
        val ladder = suit.ladderVessel >= 0
        if (!suit.swimming && !ladder) return null
        val attractor = attractorFor(suit)
        val r = suit.body.position.length
        val (low, high) = if (ladder) (r - SUIT_HALF_HEIGHT - 0.3) to (r + SUIT_HALF_HEIGHT)
            else (r + depthOf(suit) - 0.3).let { it to it + 0.3 + CLIMB_HEIGHT }
        var best: Pair<Vessel, Vec3>? = null
        var bestAlong = Double.MAX_VALUE
        for (craft in vesselsById.values) {
            if (craft === suit || craft.referenceBodyId != suit.referenceBodyId || walking.walkerOf(craft) != null) continue
            if (craft.body.position.distanceTo(suit.body.position) > craft.contactRadius + CLIMB_REACH + SUIT_HALF_HEIGHT) continue
            scratchClimbDir.setTo(craft.body.position).subInPlace(suit.body.position)
            scratchClimbUp.setTo(suit.body.position).normalizeInPlace()
            scratchClimbDir.addScaledInPlace(scratchClimbUp, -(scratchClimbDir dot scratchClimbUp))
            if (scratchClimbDir.length < 1e-3) continue
            scratchClimbDir.normalizeInPlace()
            // The furthest in they can reach, not the first edge. Set down right on the edge of a
            // jet ski's hull, they slid straight back in.
            var along = 0.0
            var found: Vec3? = null
            var first = Double.NaN
            while (along <= CLIMB_REACH) {
                scratchClimbAt.setTo(suit.body.position).addScaledInPlace(scratchClimbDir, along).normalizeInPlace()
                val top = topOf(craft, scratchClimbAt, low, high)
                if (!top.isNaN()) {
                    if (first.isNaN()) first = along
                    found = scratchClimbAt.copy().mulInPlace(top + SUIT_HALF_HEIGHT + 0.1)
                } else if (!first.isNaN()) break
                along += CLIMB_STEP
            }
            if (found != null && first < bestAlong) {
                bestAlong = first
                best = craft to found
            }
        }
        return best
    }

    /**
     * The top of [craft] along [direction] from the planet's centre, as a distance from it between
     * [low] and [high], or NaN if there's none there: nothing in that band, or a wall that goes on
     * up past it.
     */
    private fun topOf(craft: Vessel, direction: Vec3, low: Double, high: Double): Double {
        var h = high
        var first = true
        while (h >= low) {
            scratchClimbPoint.setTo(direction).mulInPlace(h)
            var solid = false
            for (i in craft.defs.indices) {
                if (!craft.isBroken(i) && deckProbe.inside(scratchClimbPoint, craft, i)) { solid = true; break }
            }
            if (solid) return if (first) Double.NaN else h
            first = false
            h -= CLIMB_STEP * 0.4
        }
        return Double.NaN
    }

    private val scratchClimbDir = Vec3()
    private val scratchClimbUp = Vec3()
    private val scratchClimbAt = Vec3()
    private val scratchClimbPoint = Vec3()

    /** Someone in the water, or at the top of a ladder, climbs out onto a deck. See [climbSpot]. */
    private fun climbOut(suit: Vessel): Boolean {
        val (craft, spot) = climbSpot(suit) ?: return false
        suit.ladderVessel = -1L
        suit.ladderPart = -1
        suit.body.position.setTo(spot)
        craft.body.velocityAtOffset(scratchCrew.setTo(spot).subInPlace(craft.body.position), suit.body.linearVelocity)
        suit.body.angularVelocity.setTo(craft.body.angularVelocity)
        suit.control.ballast = 0
        suit.control.holdDepth = false
        suit.control.holdDepthAt = 0.0
        suit.swimming = false
        craft.wake()
        return true
    }

    /** Someone on their feet jumps. */
    private fun jump(vessel: Vessel) {
        val walker = walking.walkerOf(vessel) ?: return
        if (vessel.ladderVessel >= 0) { vessel.ladderVessel = -1L; vessel.ladderPart = -1 }
        if (!vessel.touchingGround) return
        vessel.body.linearVelocity.addScaledInPlace(vessel.body.position.normalized(), walker.jump)
        vessel.walking = true
    }

    /** Someone takes hold of the nearest ladder in reach, or lets go if [on] is false. */
    private fun grab(vessel: Vessel, on: Boolean) {
        if (walking.walkerOf(vessel) == null) return
        vessel.ladderVessel = -1L
        vessel.ladderPart = -1
        if (!on) return
        val ladder = ladderInReach(vessel) ?: return
        vessel.ladderVessel = ladder.first.id.raw
        vessel.ladderPart = ladder.second
    }

    /** The nearest ladder within reach of someone in [suit], as a craft and part. */
    fun ladderInReach(suit: Vessel): Pair<Vessel, Int>? {
        var best: Pair<Vessel, Int>? = null
        var bestGap = Walking.GRAB_REACH
        for (craft in vesselsById.values) {
            if (craft === suit || craft.referenceBodyId != suit.referenceBodyId) continue
            if (craft.body.position.distanceTo(suit.body.position) > craft.contactRadius + 5.0) continue
            for (i in craft.defs.indices) {
                val hold = walking.ladderOf(craft, i) ?: continue
                val gap = walking.distanceTo(hold, suit.body.position)
                if (gap < bestGap) { bestGap = gap; best = craft to i }
            }
        }
        return best
    }

    /**
     * Someone standing still on the ground plants their flag beside them. It's a craft of its own,
     * theirs, for good.
     */
    private fun plantFlag(suit: Vessel): Vessel? {
        // On the ground itself. A flag on a deck would be planted in whatever's under the deck.
        if (walking.walkerOf(suit) == null || !suit.touchingGround || suit.standingOn != null) return null
        val attractor = attractorFor(suit)
        attractor.surfaceVelocityAt(suit.body.position, scratchCrew).subInPlace(suit.body.linearVelocity)
        if (scratchCrew.length > FLAG_STILL) return null
        val up = suit.body.position.normalized()
        val ahead = suit.body.orientation.rotate(Walking.FACING, Vec3())
        ahead.addScaledInPlace(up, -(ahead dot up))
        if (ahead.length < 1e-6) return null
        ahead.normalizeInPlace()
        val spot = suit.body.position.copy().addScaledInPlace(ahead, FLAG_AHEAD)
        attractor.rotationAt(time, scratchRotation)
        val fixed = attractor.toBodyFixed(spot, scratchRotation, Vec3()).normalizeInPlace()
        spot.normalizeInPlace().mulInPlace(attractor.surfaceRadiusInBodyFrame(fixed) + FLAG_HALF_HEIGHT + 0.05)
        val who = suit.crew.getOrNull(0)?.firstOrNull()?.let { crew[it]?.name } ?: suit.name
        val flag = spawnAt(
            CraftDesign(
                name = "$who's flag", parts = listOf(com.rm.apogee.core.craft.PlacedPart(FLAG_PART, Vec3.zero())),
                stages = emptyList(), manualStaging = true, catalogHash = catalog.contentHash,
            ),
            suit.referenceBodyId, spot, attractor.surfaceVelocityAt(spot, Vec3()), quatFromTo(Vec3.unitY(), up), seat = false,
        )
        flag.owner = suit.owner
        flag.ownerName = suit.ownerName
        // Planted, so it stays up whoever walks into it.
        attractor.rotationAt(time, anchorRotation)
        flag.anchor(anchorRotation)
        return flag
    }

    /** [id] is home again, ready to fly. */
    private fun releaseCrew(id: Long) {
        val member = crew[id] ?: return
        setCrew(member.copy(status = com.rm.apogee.core.crew.CrewStatus.AVAILABLE, vessel = -1L))
    }

    /** [id] was lost, aboard [vessel], [how], and goes onto the memorial. */
    private fun loseCrew(id: Long, vessel: Vessel?, how: String) {
        val member = crew[id] ?: return
        if (member.status == com.rm.apogee.core.crew.CrewStatus.LOST) return
        val where = vessel?.let { system.bodies[it.referenceBodyId]?.displayName } ?: ""
        setCrew(
            member.copy(
                status = com.rm.apogee.core.crew.CrewStatus.LOST, vessel = -1L, lostAt = time, lostWhere = where, lostHow = how,
                lastVessel = vessel?.id?.raw ?: member.vessel,
            ),
        )
        pendingEvents.add(WorldEvent.CrewLost(id, member.name, member.owner, how))
    }

    /**
     * Makes [vessel] [owner]'s, and the crew aboard theirs too. This is for a craft claimed by the
     * one player of a solo world, whoever an older save had it under.
     */
    fun claim(vessel: Vessel, owner: String) {
        vessel.owner = owner
        for (seat in vessel.crew) for (id in seat) crew[id]?.let { if (it.owner != owner) setCrew(it.copy(owner = owner)) }
    }

    /** Whether [vessel] is somewhere its crew could walk away from: at rest on the homeworld, or in its sea. */
    fun recoverable(vessel: Vessel): Boolean {
        if (vessel.referenceBodyId != SolarSystem.HOMEWORLD_ID) return false
        val attractor = attractorFor(vessel)
        if (vessel.anchored || vessel.dormant) return true
        if (!vessel.touchingGround && !vessel.afloat) return false
        attractor.surfaceVelocityAt(vessel.body.position, scratchCrew).subInPlace(vessel.body.linearVelocity)
        return scratchCrew.length < RECOVER_SPEED
    }

    private val scratchCrew = Vec3()

    /**
     * Squares the roster with where everyone actually sits. Crew in a part that has failed are lost
     * with it. Crew whose part went with no craft to carry them (torn away and destroyed) are lost.
     * Crew whose part came away as a craft of its own are aboard that craft now.
     */
    private fun reconcileCrew() {
        val seatedIn = HashMap<Long, Vessel>()
        for (vessel in vesselsById.values) {
            for (i in vessel.crew.indices) {
                val seat = vessel.crew[i]
                if (seat.isEmpty()) continue
                if (vessel.isBroken(i)) {
                    for (member in seat) loseCrew(member, vessel, "killed when the ${vessel.defs[i].title} failed")
                    vessel.crew[i] = Vessel.NO_CREW
                    continue
                }
                for (member in seat) seatedIn[member] = vessel
            }
        }
        for (member in crew.values.toList()) {
            if (member.status != com.rm.apogee.core.crew.CrewStatus.ABOARD) continue
            val vessel = seatedIn[member.id]
            if (vessel == null) loseCrew(member.id, null, "lost when their craft broke up")
            else if (vessel.id.raw != member.vessel) setCrew(member.copy(vessel = vessel.id.raw))
        }
    }

    companion object {
        /** After undocking, the seconds before the two can capture again. */
        const val UNDOCK_GRACE = 6.0

        /**
         * A hitch: the solver passes per tick, the share of the gap closed per tick, and the yank
         * that breaks it, in g on the lighter craft.
         */
        private const val LINK_ITERATIONS = 4
        private const val LINK_STIFFNESS = 0.25
        private const val LINK_BREAK_G = 8.0

        /** What time warp offers, as multiples of real time. */
        val WARP_RATES = doubleArrayOf(1.0, 2.0, 4.0, 10.0, 50.0, 100.0, 1_000.0, 10_000.0, 100_000.0, 1_000_000.0)

        /** Up to this the world steps faster. Past it, craft go on rails. */
        const val PHYSICS_WARP = 4.0

        /** How long a craft's air sample is kept, in seconds, and how far it can go meanwhile, in metres. */
        const val AIR_KEEP = 0.05
        const val AIR_MOVE = 20.0

        /**
         * The auto-burn lights up once it's pointing this close (the cosine of 2 degrees), and
         * keeps burning within 10.
         */
        private val ALIGNED_START = kotlin.math.cos(Math.toRadians(2.0))
        private val ALIGNED_BURNING = kotlin.math.cos(Math.toRadians(10.0))

        /** Seconds at full thrust over which the auto-burn eases off at the end. */
        private const val AUTO_TAPER = 0.5
        private const val AUTO_LEAST_THROTTLE = 0.02

        /** Auto-land: standing this still on the ground, in m/s, it's down. */
        private const val LANDED_SPEED = 0.5
        /** Too little engine to land on: thrust under this many times the weight. */
        private const val LAND_LEAST_TWR = 1.1
        /** Legs go out this near the ground, in metres. */
        private const val LEGS_OUT = 300.0
        /** Braking is planned on this share of the engine, and the rest is kept in hand. */
        private const val LAND_BRAKE_SHARE = 0.75
        /** Braking starts with this much more height than stopping takes, plus this many metres. */
        private const val LAND_MARGIN = 1.15
        private const val LAND_FLARE = 60.0
        /**
         * The descent I want: this share of the stopping curve, and near the ground a metre a
         * second more for every [1 / LAND_PACE] metres up.
         */
        private const val LAND_CURVE = 0.8
        private const val LAND_PACE = 0.25
        private const val LAND_TOUCHDOWN = 1.0
        /** How hard a gap in descent rate, and sideways drift, get pushed against, per second. */
        private const val LAND_GAIN = 1.5
        private const val LAND_SIDE_GAIN = 0.6
        /** At most this share of the engine goes to taking off sideways drift. */
        private const val LAND_SIDE_SHARE = 0.5
        /**
         * It brakes hard against its motion until it's moving sideways slower than this, in m/s.
         */
        private const val LAND_SIDE_KILLED = 25.0
        /**
         * While braking hard, the nose stays at least this far up from the horizon (the sine of
         * about 6 degrees).
         */
        private const val LAND_LEAST_RISE = 0.1
        /** While braking, it leans no further than this off upright, in radians. */
        private val LAND_MOST_LEAN = Math.toRadians(30.0)
        /**
         * The engine is only lit when it's pointing this close to where it should (the cosine of 25
         * degrees).
         */
        private val LAND_ALIGNED = kotlin.math.cos(Math.toRadians(25.0))

        /**
         * The rails rates, and the height above the air (or [RAILS_CLEARANCE]) each one needs, in
         * the body's radii.
         */
        private val RAILS_RATES = doubleArrayOf(10.0, 50.0, 100.0, 1_000.0, 10_000.0, 100_000.0, 1_000_000.0)
        /**
         * The height each rate wants, in the body's radii above its air or ground. The fastest is
         * only allowed far out between worlds.
         */
        private val RAILS_HEIGHTS = doubleArrayOf(0.0, 0.1, 0.2, 0.4, 0.8, 5.0, 20.0)

        /**
         * Up to this warp, rails slices are [RAILS_STEP] long. Past it they get longer by as much,
         * so minutes each at a million times.
         */
        private const val RAILS_SLICE_WARP = 10_000.0

        /** Over an airless body, how far above the ground rails warp can start, in metres. */
        private const val RAILS_CLEARANCE = 5_000.0

        /** The longest slice of time moved on rails before looking again, in seconds. */
        private const val RAILS_STEP = 5.0

        /** Metres between neighbouring launch pads at a site. */
        private const val PAD_SPACING_METRES = 40.0

        /**
         * The gap, in metres, kept between a new craft and anything already standing near its pad.
         */
        private const val PAD_MARGIN_METRES = 5.0

        /** How many pads to look through before giving up and reusing one. */
        private const val MAX_PADS = 64

        /** Faster than this relative to the ground, in m/s, a craft is flying, not parked. */
        private const val RESEAT_MAX_SPEED = 2.0

        /** Metres above the highest ground, beyond which nothing is resting on it. */
        private const val RESEAT_MAX_HEIGHT = 500.0

        /**
         * Above this closing speed a weld is a collision, not an assembly.
         *
         * It's public because the client uses it to decide whether to offer the action at all. A
         * button that shows up when the server would refuse is worse than no button.
         */
        const val JOIN_MAX_CLOSING_SPEED = 2.0

        /** Luna's test base: its name, the site it stands by, and which of the site's pads it takes. */
        const val LUNA_TEST_SITE = "luna-mare"

        /** How far, in metres, a leg's feet have to reach below the rest to be stood on at launch. */
        private const val LEGS_REACH = 0.05

        /** Who the Cape's own buildings belong to: nobody, and everybody can launch from them. */
        const val WORLD_OWNER = "world"

        /**
         * Ground speed, in m/s, up to which a wheel steers to its full lock. Above it,
         * proportionally less.
         */
        const val FULL_LOCK_SPEED = 5.0

        /** What a base's pump moves into a craft. */
        /** What a craft unloads into a base: what it has dug up. */
        private val UNLOAD_TYPES = listOf(
            com.rm.apogee.core.part.ResourceType.ORE,
            com.rm.apogee.core.part.ResourceType.WATER,
        )

        /** The parts that make up someone out of their craft, and a planted flag. */
        const val SUIT_PART = "crew-suit"
        const val FLAG_PART = "flag-pole"
        /**
         * Half a suit's height in metres, how far clear of a craft someone steps out, and how near
         * a seat they have to be to climb in, in metres.
         */
        const val SUIT_HALF_HEIGHT = 0.9

        /** How deep, in metres, someone's middle has to be to be under water at a hatch, and can board through it. */
        const val HATCH_DEPTH = 1.2

        /** How far toward a craft, in metres, someone can reach to climb out onto it, and how high up. */
        const val CLIMB_REACH = 1.5
        const val CLIMB_HEIGHT = 0.8

        /** The spacing of the spots looked at for somewhere to climb out onto, in metres. */
        const val CLIMB_STEP = 0.25

        /** The fastest, in m/s, someone can go into the water and live. */
        const val WATER_ENTRY = 15.0

        /** How near to what their suit can take, as a share, someone will swim down to. */
        const val DIVE_SHARE = 0.9

        /** Out of the water, how long it takes to warm right up from as cold as can be, in seconds. */
        const val WARM_OUT = 1_800.0

        /** Aboard, the same. */
        const val WARM_ABOARD = 600.0

        /** What the crew panel says when it's too deep under the sea to go outside. */
        const val TOO_DEEP = "Too deep"

        /** Why someone was lost to the sea's cold. */
        const val COLD_REASON = "died of cold in the sea"
        const val SUIT_CLEARANCE = 0.7
        const val BOARD_REACH = 0.8
        /** The thickest air (Pa) and the hottest (K) anyone may step out into. */
        const val EVA_PRESSURE = 5e5
        const val EVA_HOT = 400.0

        /** The fastest a landed craft can be moving, in m/s, for anyone to step off it. */
        const val EVA_LANDED_SPEED = 5.0
        /** How far from its rungs someone can drift and still be holding a ladder, in metres. */
        const val LADDER_SLIP = 1.5
        /**
         * A flag: how still you have to be to plant it, how far ahead it goes, and half its height.
         */
        /** The fastest a founded base afloat heaves, in m/s. Anything faster is it being put somewhere. */
        const val RIDE_MOST = 15.0
        /**
         * How still a craft has to sit on a deck to go to sleep on it: across the deck in m/s, and
         * turning against it in rad/s. Looser than on the ground, because a deck in a swell never
         * quite stops under a craft on its springs.
         */
        const val RIDE_STILL = 0.3

        /** How far apart, in metres, the points looked at under a craft for a deck are. */
        const val DECK_PROBE_STEP = 0.25
        const val RIDE_SPIN = 0.1
        const val FLAG_STILL = 0.5
        /**
         * Righting a capsized craft: how far off upright it has to lie (radians), the most it can
         * weigh (kg), how still it has to be against the ground or the sea's drift (m/s), and how
         * long the roll takes (s). The still is loose, because a wild sea heaves a boat up and down
         * at a couple of metres a second however still it lies.
         */
        const val RIGHT_FROM = 75.0 * kotlin.math.PI / 180.0
        const val RIGHT_MOST_MASS = 2_500.0
        const val RIGHT_STILL = 4.0
        const val RIGHT_TIME = 4.0
        const val FLAG_AHEAD = 1.0
        const val FLAG_HALF_HEIGHT = 1.1
        /** Why a suit went: its wearer climbed aboard something. */
        const val BOARDED_REASON = "boarded"

        /** Air pressure, in Pa, under which nothing gets crushed, so there's nothing to look at. */
        const val CRUSH_FLOOR = 1e6
        /** Health lost per second for each whole limit over its pressure rating. */
        const val CRUSH_RATE = 0.002

        /**
         * The same under water, where a hull gives way far quicker than a lander in heavy air. A
         * tenth over and it has minutes. At twice what it's built for, it has seconds.
         */
        const val WATER_CRUSH_RATE = 0.05

        /**
         * Above this, in metres over the datum, a craft is clear of any sea, so there's nothing to
         * weigh.
         */
        const val SEA_CHECK_HEIGHT = 50.0

        /**
         * Holding a depth: the climb I want, in m/s, for each metre off it, and the most of it.
         * Also the band left alone, in m/s.
         */
        const val HOLD_DEPTH_GAIN = 0.15
        const val HOLD_DEPTH_SPEED = 0.8
        const val HOLD_DEPTH_BAND = 0.05


        /** Gas lift, as a share of weight, that floats a craft on its own, for founding it aloft. */
        const val FLOATS_ALONE = 0.97

        /** Gas lift this close to its weight, as a share, counts as floating in balance. */
        const val BALANCED = 0.03

        /**
         * Slower than this over the ground, in m/s, a floating platform can be founded aloft, and
         * drifting slower than the other across the sea, afloat, where the waves sway it.
         */
        const val FLOAT_FOUND_SPEED = 1.0
        const val FLOAT_FOUND_DRIFT = 3.0

        /** How near its held spot, in metres, a craft held by its keeper has to be to sleep there. */
        const val KEEP_SETTLED = 4.0

        /** Turning slower than this against the planet, in rad/s, it's still enough to sleep aloft. */
        const val ALOFT_TURN = 0.02

        /** Ticks between weighing a craft again while its ballast is changing. */
        const val BALLAST_MASS_EVERY = 6

        /**
         * Parked no deeper than this, in metres, nothing hollow gets near what it can stand, so it
         * isn't worth weighing.
         */
        const val WATER_PARKED_SAFE = 150.0

        /** Ticks between telling the seas where the founded bases are. */
        const val CALM_BASES_EVERY = 60L
        /**
         * The star: within this many of its radii its heat starts to tell, harder the closer you
         * get.
         */
        const val SOL_REACH = 2.0
        const val SOL_BURN = 0.05
        /** Faster than this through a ring's gravel, in m/s, a craft gets torn apart. */
        const val RING_SPEED = 50.0

        /** Ticks between squaring the crew roster with the seats. */
        const val CREW_CHECK_TICKS = 15L
        /** Why a craft taken away by its owner, or reset to its site, went. Its crew go home. */
        const val REMOVED_REASON = "removed"
        const val RESET_REASON = "reset to its launch site"
        /**
         * The slowest a landed or floating craft has to be going, in m/s, for its crew to walk away
         * from it.
         */
        const val RECOVER_SPEED = 1.0

        /** How high above its body a scanner can read the ground below, in metres. */
        const val READING_HEIGHT = 10_000.0

        /** Units per second that two craft docked in flight move between them, with no pump. */
        const val DOCKED_TRANSFER = 20.0

        /** How far an orbit's plane must be tipped from the equator to survey from: the cosine of 60 degrees. */
        const val SURVEY_TILT = 0.5

        private val REFUEL_TYPES = listOf(
            com.rm.apogee.core.part.ResourceType.PROPELLANT,
            com.rm.apogee.core.part.ResourceType.MONOPROPELLANT,
            com.rm.apogee.core.part.ResourceType.ELECTRIC_CHARGE,
        )

        /**
         * How far above a pad deck's top a craft's lowest point can be and still be standing on it,
         * in metres.
         */
        const val PAD_SERVICE_HEIGHT = 0.8

        /** How often the Cape's buildings get looked at for repair, in ticks. */
        const val REPAIR_CHECK_TICKS = 60L

        /**
         * Nothing awake can be within this many metres of a complex for [REPAIR_QUIET] seconds for
         * it to be rebuilt.
         */
        const val REPAIR_REACH = 2_000.0
        const val REPAIR_QUIET = 60.0

        /** How often founded bases' power gets brought up to date, in ticks. */
        const val POWER_CHECK_TICKS = 60L

        /**
         * A base's power ledger is worked out in steps of this many seconds, or coarser, up to
         * [POWER_MAX_STEPS] of them.
         */
        const val POWER_STEP = 60.0
        const val POWER_MAX_STEPS = 20_000

        /** What a base's command part takes to keep it running, in charge per second. */
        const val BASE_UPKEEP = 0.05

        /** Lamps come on when the sun is lower than this, as the sine of its elevation: dusk. */
        const val LAMP_DUSK = 0.05

        /** ...and under the sea deeper than this, in metres, where the daylight has gone. */
        const val LAMP_DEPTH = 60.0

        /**
         * Shallower than this, in metres, a craft isn't checked for the sea's named places, because
         * it's at the surface.
         */
        const val WONDER_LOOK_DEPTH = 5.0

        /** Charge units short of full that count as topped up at a pad. */
        const val CHARGE_TOPPED = 1.0

        /** Ticks between working out every flown probe's link home. */
        const val SIGNAL_CHECK_TICKS = 60L
        /** Seconds a probe's link is trusted before a command asks again. */
        const val SIGNAL_STALE = 1.0

        /** How far off straight ahead, as a cosine, a craft can be and still be hooked: 60 degrees. */
        const val HOOK_CONE = 0.5

        /** How far ahead the ground is hooked, in metres, when there's no craft to hook. */
        const val GROUND_HOOK_DISTANCE = 15.0

        /**
         * A winch line: the slack left on hooking, in metres, the shortest it winds in to, the share
         * of a stretch taken up per tick, how much harder than its pull a yank has to be to snap it,
         * and how fast the two have to be flying apart for that, in m/s. And how much it lets out at
         * a time when the drum slips.
         */
        const val LINE_SLACK = 0.3
        const val LINE_SHORTEST = 1.0
        const val LINE_STIFFNESS = 0.2
        const val LINE_SNAP = 2.0
        const val LINE_SNAP_SPEED = 3.0
        const val LINE_SLIP = 0.02

        /** Seconds for flaps to run all the way out, or back in. */
        const val FLAP_TIME = 2.0

        /** Seconds for a sun wing or dish to fold out, or away. */
        const val UNFOLD_TIME = 4.0

        /** How far clear of the truck a release clamp sets its load down, in metres. */
        const val SET_DOWN_CLEARANCE = 0.3

        /**
         * How near the ground a foundation's lowest foot has to be for the craft to be founded, in
         * metres.
         */
        const val FOOT_ON_GROUND = 0.3

        /** The fastest a craft can be going over the ground and still be founded, in m/s. */
        const val ANCHOR_MAX_SPEED = 0.3

        /** Metres the two halves of a separation are pushed apart straight away. */
        private const val SEPARATION_CLEARANCE = 0.5

        /** Impacts gentler than this aren't worth an event. */
        private const val TOUCHDOWN_REPORT_SPEED = 0.5

        /** Seconds that two halves of a staged craft only touch gently while they part. */
        private const val SEPARATION_GRACE = 1.5

        /** How long a gentle pair stays gentle after it last touched, in seconds. */
        private const val GENTLE_HOLD = 0.3

        /** The fastest drift, in m/s, at which a boat left alone in a wind drops anchor. */
        private const val ANCHOR_DRIFT = 1.5

        /** The share of a blow that a part that holds passes on to its neighbours as a jolt. */
        private const val SHOCK_SHARE = 0.2

        /**
         * Energy a part soaks up being crushed, in J, per kg of its dry mass per (m/s of crash
         * tolerance) squared. A half-tonne tank with tolerance 6 takes 2.7 MJ, and the Ember's 1.5
         * t bell 11 MJ. That's enough to stop the Starter I from 40 m/s tail first, but not from
         * 60.
         */
        private const val CRUSH_ENERGY = 150.0

        /** Crash debris lighter than this, in kg, with no controls, gets cleared away. */
        private const val WRECKAGE_KG = 200.0
        /** How long a fragment lies there first, in seconds. */
        private const val FRAGMENT_LIFETIME = 120.0
        private const val FRAGMENT_CHECK_TICKS = 60L

        /** Propellant below this, in kg, burns instead of exploding. */
        private const val MIN_EXPLOSIVE_KG = 20.0
        private const val MAX_BLAST_KG = 20_000.0
        /** Blast radius, in metres, per square root of kg of propellant. 1 t is about 24 m. */
        private const val BLAST_RADIUS_PER_KG = 0.75
        /**
         * Damage at the heart of a blast, to a part with [BLAST_TOUGHNESS] crash tolerance. Tougher
         * parts take less, and flimsier ones more, by the square of it, so a pod rides out the
         * blast that takes the tank beside it.
         */
        private const val BLAST_DAMAGE = 1.2
        private const val BLAST_TOUGHNESS = 8.0
        /** N*s of push per kg of propellant, at the centre. */
        private const val BLAST_IMPULSE_PER_KG = 6.0
        /** The most a blast can change a craft's speed, in m/s. It throws fins, not bullets. */
        private const val BLAST_MAX_KICK = 25.0

        /** Lightning gets looked for once a second. */
        private const val LIGHTNING_CHECK_TICKS = 60L

        /** How close to a craft a strike has to land to hit it, in metres. */
        private const val LIGHTNING_REACH = 60.0

        /** Above this, in metres, a craft is in or over the cloud, not under the bolt. */
        private const val LIGHTNING_CEILING = 3_000.0

        /**
         * The furthest a contact point can sweep in one substep, in metres.
         *
         * It's smaller than the smallest thing that has to be noticed, which is a landing leg
         * sticking out below the engine it protects.
         */
        private const val MAX_SUBSTEP_DISTANCE = 0.15

        /** Above eight, a tick costs more than the accuracy is worth. */
        private const val MAX_CONTACT_SUBSTEPS = 8

        /** Metres above the highest possible ground to stop subdividing. */
        private const val SUBSTEP_CEILING_METRES = 200.0

        /**
         * Metres above the ground *directly beneath* to stop subdividing.
         *
         * It's wider than the contact resolver's own margin, because a craft this close is about to
         * need the fine steps, and the test runs a tick before they matter.
         */
        private const val SUBSTEP_PROXIMITY_MARGIN = 120.0

        /**
         * How long it has to stay that still first.
         *
         * Two seconds. This is hysteresis, so a lander rocking on its gear settles once instead of
         * flickering in and out of dormancy. It's also long enough that a craft still creeping down
         * a slope doesn't get caught halfway and frozen there.
         */
        private const val SLEEP_SETTLE_TICKS = 120

        /**
         * Metres per second, of the hull or of its ends turning, below which a floating craft
         * counts as moored. It's above the few millimetres a second the heave damping leaves behind
         * after settling, and well below any drift anyone could see.
         */
        private const val FLOATING_REST_SPEED = 0.02

        /**
         * Significant wave height, in metres, above which the sea is a seaway and floating craft
         * heave with it.
         */
        private const val SEAWAY_HS = 0.3

        /**
         * How rough a sea a craft can sleep through past [ROUGH_SEA], in metres of significant
         * height per metre of its contact radius. A Trawler gets a little more than that, and in a
         * storm's sea, none.
         */
        private const val ROUGH_PER_METRE = 0.4

        /**
         * Significant wave height, in metres, that any boat can sleep through: an ordinary day's
         * sea off an open coast. Below it, boats moored at a harbour cost nothing, as a base's
         * should. Above it is weather.
         */
        private const val ROUGH_SEA = 3.0


        val launchSites = listOf(
            // The pads: a row of them east and west of the launch tower, on the coast. See
            // SolarSystem.PAD_LATITUDE.
            LaunchSite(
                id = "cape",
                displayName = "Cape Launch Complex",
                bodyId = SolarSystem.HOMEWORLD_ID,
                latitude = SolarSystem.PAD_LATITUDE,
                longitude = SolarSystem.PAD_LONGITUDE,
            ),
            // The airfield: at the runway's west end, facing down it to the east toward the bay,
            // with the next one along it forty metres on.
            capeSite("airfield", "Cape Airfield", 340.0, -400.0),
            // The harbour's berth, off its jetty in the broad bay beside the Cape. It's dredged
            // deep, and open to the sea only up a winding inlet.
            capeSite("harbour", "Cape Harbour", 2_700.0, 330.0),
            // For testing, like the worlds' sites: out over the deep, a long way for a submarine
            // from the harbour. One is just west of the Great Arch on the shelf's edge, facing it.
            // The other is upstream of the Chimneys on Farrow's flank, where the planet's turn
            // carries it onto them as it goes down.
            capeSite("arch-sea", "Great Arch (test)", -2_635.0, 7_100.0),
            capeSite("chimneys-sea", "The Chimneys (test)", com.rm.apogee.core.terrain.Seabed.CHIMNEYS_EAST - 130.0, com.rm.apogee.core.terrain.Seabed.CHIMNEYS_NORTH),
            // Out on the open ocean where Terra's seas run biggest, day in, day out: four metres on
            // an ordinary day, and past ten in a storm. Found by sampling the sea over the whole
            // planet for five days and keeping the roughest place.
            LaunchSite(
                id = "roaring-sea",
                displayName = "The Roaring Sea",
                bodyId = SolarSystem.HOMEWORLD_ID,
                latitude = Math.toRadians(-17.2),
                longitude = Math.toRadians(-138.3),
            ),
            // For testing: straight onto the Moon without flying there. It's on the mare north-east
            // of Luna's prime meridian, a kilometre and a half below the datum, where the ground
            // under the whole row of pads slopes less than one in a hundred.
            LaunchSite(
                id = "luna-mare",
                displayName = "Luna Mare (test)",
                bodyId = "luna",
                latitude = 0.131822,
                longitude = 0.131733,
            ),
        ) + worldSites()

        /** For testing, like Luna Mare: straight onto each world's landmark without flying there. */
        private fun worldSites(): List<LaunchSite> {
            fun site(id: String, name: String, body: String, lat: Double, lon: Double) =
                LaunchSite(id, "$name (test)", body, Math.toRadians(lat), Math.toRadians(lon))
            // Each one on a flat patch near its landmark, found by looking.
            return listOf(
                site("celer-basin", "Celer Great Basin", "celer", 28.0, 165.0),
                site("caligo-ishtar", "Caligo Ishtar", "caligo", 63.25, 21.85),
                site("rubra-rift", "Rubra Rift", "rubra", -9.0, -74.55),
                site("rubra-mount", "Rubra Great Mount foot", "rubra", 17.1, -120.3),
                site("timor", "Timor", "timor", 0.0, 20.0),
                site("pavor", "Pavor", "pavor", 0.0, 0.0),
                site("fornax-lake", "Fornax lava lake shore", "fornax", -12.0, 58.0),
                site("crusta-lineae", "Crusta crossing", "crusta", 5.0, 0.0),
                site("maxima-grooves", "Maxima grooves", "maxima", 10.0, 35.0),
                site("cicatrix-scar", "Cicatrix Great Scar", "cicatrix", 15.0, -60.0),
                site("aurantia-dunes", "Aurantia dunes", "aurantia", 4.1, -39.55),
                site("aurantia-sea", "Aurantia north sea", "aurantia", 82.0, 20.0),
                site("fons-stripes", "Fons Stripes", "fons", -84.0, 0.0),
                site("aversa-cap", "Aversa polar cap", "aversa", -50.0, 30.0),
                site("ultima-heart", "Ultima Heart", "ultima", 14.4, 177.9),
                site("portitor-belt", "Portitor Belt", "portitor", -0.45, 0.75),
            )
        }

        /**
         * Where a design should be launched from: the sea for anything built around a hull, and the
         * pad for everything else. A chooser is the right answer once there are more than two. With
         * two, the design already says which it wants.
         */
        fun launchSiteFor(design: CraftDesign, catalog: PartCatalog): LaunchSite {
            // A hull, or ballast tanks, means a boat or a submarine, so the harbour.
            val floats = design.parts.any {
                catalog[it.partId]?.let { p -> p.hasModule<com.rm.apogee.core.part.Buoyancy>() || p.hasModule<com.rm.apogee.core.part.Ballast>() } == true
            }
            // Built lying down, with wings, means a plane, so the runway.
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
