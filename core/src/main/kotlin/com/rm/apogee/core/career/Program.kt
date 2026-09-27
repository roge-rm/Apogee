package com.rm.apogee.core.career

import com.rm.apogee.core.craft.CraftDesign
import com.rm.apogee.core.craft.CraftOrientation
import com.rm.apogee.core.craft.Vessel
import com.rm.apogee.core.math.Vec3
import com.rm.apogee.core.orbit.CelestialBody
import com.rm.apogee.core.orbit.SolarSystem
import com.rm.apogee.core.part.PartCatalog
import com.rm.apogee.core.terrain.SurfaceMaterial
import com.rm.apogee.core.world.SeaWonders
import com.rm.apogee.core.world.World
import com.rm.apogee.core.world.WorldEvent
import kotlin.math.abs

/**
 * All the careers in a world: every player's, and the world firsts they race each other for.
 *
 * It watches what craft do, a few times a second and at every event the world raises, and credits
 * their owners with the feats and visits that shows. That might be a stage dropped and the crew
 * brought home, an orbit held above the air, or a moon passed close by for its pull. Nobody is told
 * what to do. Whatever works, counts.
 */
class Program(val tree: TechTree = TechTree.stock) {
    private val careers = LinkedHashMap<String, CareerState>()
    val firsts = ArrayList<WorldFirst>()

    /** Goes up on every change, so a server knows who to tell. */
    var revision: Long = 0
        private set

    /** [owner]'s career, started from the starting kit the first time it's asked for. */
    fun careerOf(owner: String): CareerState =
        // A craft belonging to nobody, like one of the world's own or a player without a name yet,
        // can show a career but doesn't keep one.
        if (owner.isBlank() || owner == World.WORLD_OWNER) CareerState(owner, insight = tree.start.insight)
        else careers.getOrPut(owner) { CareerState(owner, insight = tree.start.insight) }

    val all: Collection<CareerState> get() = careers.values

    fun restore(states: List<CareerState>, firsts: List<WorldFirst>) {
        careers.clear()
        // Careers belonging to nobody, saved by an earlier build, aren't kept.
        for (s in states) if (s.owner.isNotBlank() && s.owner != World.WORLD_OWNER) careers[s.owner] = s
        this.firsts.clear()
        this.firsts.addAll(firsts)
        revision++
    }

    private fun put(state: CareerState) {
        careers[state.owner] = state
        revision++
    }

    // --- what a career may do ------------------------------------------------

    /**
     * Spends [owner]'s insight on node [id]. Returns null if it worked, or the reason it didn't.
     */
    fun unlock(owner: String, id: String): String? {
        val node = tree.node(id) ?: return "No such node"
        val state = careerOf(owner)
        state.blocker(tree, node)?.let { return it }
        put(state.copy(insight = state.insight - node.cost, nodes = state.nodes + id))
        return null
    }

    fun refusal(owner: String, design: CraftDesign, siteId: String, catalog: PartCatalog): String? =
        CareerRules.refusal(tree, careerOf(owner), design, siteId, catalog)

    fun allows(owner: String, ability: String): Boolean = careerOf(owner).ability(tree, ability)

    fun crewCap(owner: String): Int = careerOf(owner).crewCap(tree)

    // --- crediting -------------------------------------------------------------

    /**
     * [feat] was done by [vessel]'s owner, scoring [value] in the feat's metric. It pays for the
     * grade that earns, minus whatever an earlier, lower grade already paid.
     */
    internal fun award(world: World, vessel: Vessel, feat: Feat, value: Double = 0.0) {
        val owner = vessel.owner
        if (owner.isBlank() || owner == World.WORLD_OWNER) return
        val state = careerOf(owner)
        val grade = feat.gradeFor(value)
        val had = state.grade(feat)
        if (had != null && had.ordinal >= grade.ordinal) return
        val pay = Insight.worth(feat, grade) - (had?.let { Insight.worth(feat, it) } ?: 0)
        put(state.copy(insight = state.insight + pay, feats = state.feats + (feat.id to grade.ordinal)))
        world.raise(WorldEvent.FeatEarned(owner, feat.title, if (feat.graded) grade.title else "", pay, vessel.id))
    }

    /**
     * [vessel]'s owner has been to [body] in the way [visit] says. It pays the first time, and it's
     * a world first if nobody has done it yet.
     */
    internal fun visit(world: World, vessel: Vessel, body: String, visit: Visit) {
        val owner = vessel.owner
        if (owner.isBlank() || owner == World.WORLD_OWNER || body == SolarSystem.HOMEWORLD_ID) return
        val base = tree.worlds[body] ?: return
        val state = careerOf(owner)
        if (state.visited(body, visit)) return
        val pay = base * visit.multiplier
        put(state.copy(insight = state.insight + pay, visits = state.visits + "$body:${visit.id}"))
        val name = world.system.bodies[body]?.displayName ?: body
        if (firsts.none { it.bodyId == body && it.visit == visit.id }) {
            firsts.add(WorldFirst(body, visit.id, owner, vessel.ownerName, world.time))
        }
        world.raise(WorldEvent.FeatEarned(owner, "$name ${visit.title}", "", pay, vessel.id))
    }

    // --- watching ----------------------------------------------------------------

    /** A craft launched by [vessel]'s owner, which starts its log. */
    fun launched(vessel: Vessel) {
        vessel.log = FlightLog(
            launchMass = vessel.body.mass, parts = vessel.defs.size,
            // It's as full as it was built, so only more than this counts as a top-up.
            propellant = vessel.amountOf(com.rm.apogee.core.part.ResourceType.PROPELLANT),
        )
    }

    private val scratch = Vec3()
    private val scratch2 = Vec3()

    /**
     * What [world] raised since the career last looked, in [events], and if [look] is set (every
     * [LOOK_TICKS]) a look at every craft that has a log.
     */
    fun observe(world: World, events: List<WorldEvent>, look: Boolean) {
        for (event in events) when (event) {
            is WorldEvent.Staged -> world.vessel(event.id)?.log?.stagePending = true
            is WorldEvent.Docked -> {
                val keeper = world.vessel(event.keeper) ?: continue
                if (inOrbit(world, keeper)) award(world, keeper, Feat.DOCK_ORBIT)
            }
            is WorldEvent.Surveyed -> world.vessel(event.id)?.let { award(world, it, Feat.SURVEY) }
            else -> Unit
        }
        if (!look) return
        for (vessel in world.vessels.toList()) {
            if (vessel.anchored) continue
            val log = vessel.log ?: continue
            // If it's asleep and already counted, nothing is happening to it. If it fell asleep
            // before its landing was counted, and craft do settle quickly, it still needs a look.
            if (vessel.dormant && log.resting) continue
            look(world, vessel, log)
        }
    }

    /** A base was founded where [vessel] stands. */
    fun founded(world: World, vessel: Vessel) {
        val home = vessel.referenceBodyId == SolarSystem.HOMEWORLD_ID
        if (!home) award(world, vessel, Feat.OUTPOST)
        // Afloat on the sea, or floating in the sky.
        if (vessel.buoyed) award(world, vessel, Feat.SEA_STEAD)
        else if (!vessel.touchingGround && !home && vessel.defs.any { it.hasModule<com.rm.apogee.core.part.LiftGas>() }) award(world, vessel, Feat.CLOUD_CITY)
    }

    private val hoverPoint = Vec3()

    private fun skies(
        world: World, vessel: Vessel, log: FlightLog, attractor: com.rm.apogee.core.orbit.CelestialBody,
        home: Boolean, grounded: Boolean, height: Double, altitude: Double, speed: Double, dt: Double,
    ) {
        val rotors = vessel.defs.indices.any { vessel.defs[it].module<com.rm.apogee.core.part.Rotor>()?.tail == false && vessel.engineOutput[it] > 0.0 }
        val gas = vessel.defs.any { it.hasModule<com.rm.apogee.core.part.LiftGas>() }
        val aloft = !grounded && attractor.atmosphere != null
        // Held still over a spot by hand for half a minute.
        if (aloft && rotors && height > HOVER_HEIGHT && !vessel.control.keeping) {
            attractor.toBodyFixed(vessel.body.position, attractor.rotationAt(world.time), hoverPoint)
            if (log.hoverSince < 0.0 || hoverPoint.distanceTo(log.hoverSpot) > HOVER_LOST) {
                log.hoverSpot.setTo(hoverPoint)
                log.hoverSince = world.time
                log.hoverWorst = 0.0
            } else {
                log.hoverWorst = maxOf(log.hoverWorst, hoverPoint.distanceTo(log.hoverSpot))
                if (world.time - log.hoverSince >= HOVER_TIME) award(world, vessel, Feat.HOVER, log.hoverWorst)
            }
        } else {
            log.hoverSince = -1.0
        }
        // On gas, with nothing running: up a kilometre, and a long way across.
        val running = vessel.engineOutput.any { it > 0.0 }
        if (aloft && gas && !running) {
            if (log.floatFrom.isNaN()) log.floatFrom = altitude
            val risen = altitude - log.floatFrom
            if (risen >= UP_AND_AWAY) award(world, vessel, Feat.UP_AND_AWAY, risen / 1000.0)
        } else {
            log.floatFrom = Double.NaN
        }
        if (aloft && gas) {
            log.floated += speed * dt
            if (log.floated >= LONG_FLOAT) award(world, vessel, Feat.LONG_FLOAT, log.floated / 1000.0)
        }
        if (aloft && !home && (rotors || gas)) award(world, vessel, Feat.ALIEN_SKIES)
    }

    /** Someone on a spacewalk, [suit], climbed aboard [into]. */
    fun boarded(world: World, suit: Vessel, into: Vessel) {
        if (!suit.touchingGround && !into.touchingGround && !onWater(into)) award(world, suit, Feat.RESCUE)
    }

    private fun look(world: World, vessel: Vessel, log: FlightLog) {
        val attractor = world.attractorFor(vessel)
        val position = vessel.body.position
        val home = attractor.id == SolarSystem.HOMEWORLD_ID
        attractor.toBodyFixed(position, attractor.rotationAt(world.time), scratch).normalizeInPlace()
        val height = attractor.heightAboveTerrain(position, scratch)
        val altitude = attractor.altitudeOf(position)
        attractor.surfaceVelocityAt(position, scratch2)
        val speed = scratch2.subInPlace(vessel.body.linearVelocity).length
        val dt = if (log.lookedAt < 0.0) 0.0 else (world.time - log.lookedAt).coerceIn(0.0, 60.0)
        log.lookedAt = world.time
        // Whether it's down. Touching the ground can flicker on and off at rest, so this goes by
        // how long it has been down.
        val down = vessel.touchingGround || vessel.groundedSeconds > 0.0
        val wet = onWater(vessel)
        val grounded = down || wet || vessel.dormant
        val working = commandIntact(vessel)
        val suit = vessel.defs.any { it.id == World.SUIT_PART }
        val horizontal = vessel.design.orientation == CraftOrientation.HORIZONTAL

        // A stage fired since the last look that let something go.
        if (log.stagePending) {
            if (vessel.defs.size < log.parts) log.stagesDropped++
            log.stagePending = false
        }
        log.parts = vessel.defs.size
        log.peak = maxOf(log.peak, if (grounded) 0.0 else height)
        // Standing on a runway, which is where a flight that takes off now started.
        if (down && home && horizontal) log.onRunway = onRunway(world, vessel, attractor)
        val thrusting = vessel.control.throttle > 0.0 && vessel.activeEngines().isNotEmpty()

        // Off the ground, so this is a new flight. That includes a craft refuelled on another world
        // taking off again.
        if (!grounded && height > LIFTOFF && !log.airborne) {
            if (log.refuelledAway && !home) award(world, vessel, Feat.REFUEL_OFF_WORLD)
            log.airborne = true
            log.resting = false
            log.relaunch(vessel.body.mass)
        }
        if (speed > MOVING) log.resting = false
        // Someone out walking on another world's ground.
        if (suit && !home && down) award(world, vessel, Feat.MOONWALK)

        // How far it went, and how: flown, driven or sailed.
        // Sailing: the sail drawing and no engine running. Any engine starts the count again.
        if (wet) {
            val engine = vessel.engineOutput.any { it > 0.0 }
            if (engine) log.underSail = 0.0
            else if (vessel.sailFill.any { it > 0.0 }) log.underSail += speed * dt
            if (log.underSail >= UNDER_SAIL) award(world, vessel, Feat.UNDER_SAIL, log.underSail / 1000.0)
        }
        when {
            wet -> log.sailed += speed * dt
            down && hasWheels(vessel) && upright(vessel, attractor) -> log.driven += speed * dt
            !grounded && horizontal && attractor.atmosphere != null && altitude < attractor.atmosphereHeight -> log.flown += speed * dt
        }
        if (home && log.driven >= ROAD_TRIP) award(world, vessel, Feat.ROAD_TRIP, log.driven / 1000.0)
        if (!home && log.driven >= OFF_WORLD_DRIVE) award(world, vessel, Feat.ROVER_OFF_WORLD, log.driven / 1000.0)
        if (!home && wet && log.sailed >= ALIEN_SAIL) award(world, vessel, Feat.ALIEN_SEA)

        // Rotors and gas: hovering by hand, rising on gas alone, floating a long way, and other
        // worlds' air.
        skies(world, vessel, log, attractor, home, grounded, height, altitude, speed, dt)

        // Aircraft: fast and high.
        if (horizontal && !grounded && attractor.atmosphere != null) {
            val air = vessel.air.wind
            val airspeed = scratch2.setTo(vessel.body.linearVelocity).subInPlace(attractor.surfaceVelocityAt(position, Vec3())).subInPlace(air).length
            if (airspeed > SOUND && altitude < HIGH) award(world, vessel, Feat.SUPERSONIC)
            if (altitude > HIGH_FLYER && airBreathingOnly(vessel)) award(world, vessel, Feat.HIGH_FLYER)
        }

        // Out of Terra's air, and an orbit held above it, or above any world's ground.
        val orbit = world.orbitOf(vessel)
        if (home && altitude > attractor.atmosphereHeight) log.aboveAir = true
        if (!grounded && orbit.isBound && orbit.periapsis > attractor.radius + clearance(attractor)) {
            if (home) {
                if (!log.orbitedHome) award(world, vessel, Feat.ORBIT, log.launchMass / 1000.0)
                log.orbitedHome = true
            } else {
                visit(world, vessel, attractor.id, Visit.ORBIT)
            }
        }

        // Rendezvous: in orbit, close and slow next to another craft.
        if (!grounded && inOrbit(world, vessel)) {
            for (other in world.vessels) {
                if (other === vessel || other.referenceBodyId != vessel.referenceBodyId || world.isDebris(other)) continue
                if (other.body.position.distanceTo(position) > RENDEZVOUS_RANGE) continue
                if (scratch2.setTo(other.body.linearVelocity).subInPlace(vessel.body.linearVelocity).length < RENDEZVOUS_SPEED) {
                    award(world, vessel, Feat.RENDEZVOUS)
                    break
                }
            }
        }

        // Aerobraking: the high point of its orbit on the way into the air, compared to on the way
        // out.
        attractor.atmosphere?.let {
            val inAir = altitude < attractor.atmosphereHeight
            if (inAir && !grounded) {
                if (log.airApoapsis < 0.0) {
                    log.airApoapsis = if (orbit.isBound) orbit.apoapsis else Double.MAX_VALUE
                    log.airUnbound = !orbit.isBound
                    log.airThrust = false
                }
                if (thrusting) log.airThrust = true
            } else if (!inAir && log.airApoapsis >= 0.0) {
                if (!log.airThrust && orbit.isBound) {
                    // Measured as heights above the ground, how much of the climb to the high point
                    // it took off.
                    val top = log.airApoapsis - attractor.radius
                    val drop = if (log.airUnbound) 1.0 else 1.0 - (orbit.apoapsis - attractor.radius) / top
                    if (drop >= AEROBRAKE_DROP) award(world, vessel, Feat.AEROBRAKE, drop * 100.0)
                }
                log.airApoapsis = -1.0
            }
        }
        if (log.flybyBody.isNotEmpty() && thrusting) log.flybyThrust = true

        // Probes whose signal is passed along by a relay beyond the Moon.
        if (!vessel.hasCrew() && vessel.signalPath.isNotEmpty()) {
            val out = world.system.positionOf(attractor.id, world.time).addInPlace(position)
                .subInPlace(world.system.positionOf(SolarSystem.HOMEWORLD_ID, world.time)).length
            val moon = world.system.bodies["luna"]?.orbit?.semiMajorAxis ?: Double.MAX_VALUE
            if (out > moon) award(world, vessel, Feat.RELAY)
        }

        // Topped up while standing on another world: more propellant than the lowest it has had
        // since it came down. A refinery makes it slowly, only a fraction of a unit between one
        // look and the next.
        val propellant = vessel.amountOf(com.rm.apogee.core.part.ResourceType.PROPELLANT)
        if (!home && grounded) {
            if (propellant < log.propellant) log.propellant = propellant
            else if (propellant > log.propellant + REFUELLED) log.refuelledAway = true
        } else {
            log.propellant = propellant
        }

        // Came down on the water at the end of a flight. A splashdown is over once it's in the sea,
        // whether or not wind and waves (or its own chute being dragged along the surface) ever let
        // it lie still.
        if (wet && log.airborne) { if (log.wetAt < 0.0) log.wetAt = world.time } else log.wetAt = -1.0
        val splashedDown = log.wetAt >= 0.0 && world.time - log.wetAt > SPLASHDOWN

        underSea(world, vessel, log, attractor, home, down)

        // At rest, so work out what the flight added up to.
        if (grounded && (speed < REST || splashedDown) && !log.resting) {
            log.resting = true
            if (working || suit) settle(world, vessel, log, attractor, home, suit, horizontal, down, wet)
            log.airborne = false
        }
    }

    /**
     * Under the sea. This tracks how deep [vessel] has been since it was last at the surface,
     * whether it settled on the floor, whether there was a vent next to it, and which named places
     * it reached. Once it's back at the surface with its crew, it works out what the dive was
     * worth.
     */
    private fun underSea(world: World, vessel: Vessel, log: FlightLog, attractor: CelestialBody, home: Boolean, down: Boolean) {
        if (attractor.ocean == null) return
        val depth = world.depthOf(vessel)
        if (depth > DIVED) {
            log.deepest = maxOf(log.deepest, depth)
            if (depth > SEAFLOOR_DEPTH && down) log.seafloor = true
            if (!home && vessel.hasCrew()) award(world, vessel, Feat.ALIEN_DEEP)
            val terrain = attractor.terrain
            if (terrain != null) {
                attractor.toBodyFixed(vessel.body.position, attractor.rotationAt(world.time), scratch)
                // Height over the floor, not the surface, using the terrain's own height under the
                // water.
                val floor = attractor.altitudeOf(vessel.body.position) - terrain.elevation(scratch)
                if (floor < VENT_HEIGHT && terrain.ventField(scratch) > VENT_FIELD) award(world, vessel, Feat.VENTS)
                // The world spots the named places a craft reaches, in free play too, and passes
                // them on here. See World.lookForWonders.
            }
        } else if (depth < SURFACED && log.deepest > DIVED) {
            // Back at the top, so the dive counts, as long as the crew is aboard.
            if (vessel.hasCrew()) {
                award(world, vessel, Feat.DIVE, log.deepest)
                if (log.seafloor) award(world, vessel, Feat.SEAFLOOR)
                if (log.deepest > ABYSS) award(world, vessel, Feat.ABYSS)
            }
            log.deepest = 0.0
            log.seafloor = false
        }
    }

    /**
     * [vessel]'s owner has reached [wonder]. It pays the first time, and it's a world first if
     * nobody has found it yet.
     */
    internal fun found(world: World, vessel: Vessel, wonder: SeaWonders.Wonder) {
        val owner = vessel.owner
        if (owner.isBlank() || owner == World.WORLD_OWNER) return
        val state = careerOf(owner)
        val key = "${WONDER}:${wonder.id}"
        if (key in state.visits) return
        put(state.copy(insight = state.insight + wonder.insight, visits = state.visits + key))
        if (firsts.none { it.bodyId == wonder.id && it.visit == WONDER }) {
            firsts.add(WorldFirst(wonder.id, WONDER, owner, vessel.ownerName, world.time))
        }
        world.raise(WorldEvent.FeatEarned(owner, wonder.name, FOUND, wonder.insight, vessel.id))
    }

    /**
     * [vessel] has come to rest on [attractor], so work out what the flight that brought it there
     * was worth.
     */
    private fun settle(world: World, vessel: Vessel, log: FlightLog, attractor: CelestialBody, home: Boolean, suit: Boolean, horizontal: Boolean, down: Boolean, wet: Boolean) {
        if (suit) return
        if (home) {
            val crewed = vessel.hasCrew()
            if (crewed && log.peak > HOP) award(world, vessel, Feat.HOP)
            if (crewed && log.stagesDropped > 0) award(world, vessel, Feat.STAGING, log.peak / 1000.0)
            if (crewed && log.aboveAir) award(world, vessel, Feat.SPACE, log.launchMass / 1000.0)
            if (crewed && log.orbitedHome) award(world, vessel, Feat.HOME_AGAIN)
            if (crewed) for (body in log.landedOn) visit(world, vessel, body, Visit.RETURN)
            val runway = down && horizontal && log.onRunway
            if (runway && log.aboveAir) award(world, vessel, Feat.GLIDE_HOME)
            if (runway && log.fromRunway && log.peak > FLIGHT_HEIGHT) award(world, vessel, Feat.FIRST_FLIGHT)
            if (horizontal && log.flown >= LONG_HAUL && down) award(world, vessel, Feat.LONG_HAUL, log.flown / 1000.0)
            if (wet && log.sailed >= SEAWORTHY && nearHarbour(world, vessel)) award(world, vessel, Feat.SEAWORTHY)
            log.landedOn.clear()
            return
        }
        if (!down && !vessel.dormant) return
        // It has to have set down there, not launched there. A craft coming off a base's pad hasn't
        // landed anywhere.
        if (!log.airborne) return
        award(world, vessel, Feat.TOUCHDOWN)
        visit(world, vessel, attractor.id, Visit.LAND)
        if (attractor.id !in log.landedOn) log.landedOn.add(attractor.id)
        // Next to something already there, like a flag, a base or another craft.
        val position = vessel.body.position
        val nearest = world.vessels.filter { it !== vessel && it.referenceBodyId == vessel.referenceBodyId && !world.isDebris(it) }
            .minOfOrNull { it.body.position.distanceTo(position) }
        if (nearest != null && nearest < PRECISION) award(world, vessel, Feat.PRECISION_LANDING, nearest)
    }

    /**
     * [vessel] has just passed from [fromId]'s pull into [toId]'s at [time], and its state is
     * already measured from the new body. This takes its energy relative to the parent right then,
     * because on rails a slice of warp can carry it a long way on before the world's events get
     * looked at.
     */
    fun crossed(world: World, vessel: Vessel, fromId: String, toId: String, time: Double) {
        val log = vessel.log ?: return
        val to = world.system.bodies[toId] ?: return
        val from = world.system.bodies[fromId] ?: return
        if (to.parentId == from.id) {
            // Its energy relative to [from] on the way in, from its state moved back to that body.
            log.flybyBody = to.id
            log.flybyEnergy = energyAbout(world, vessel, to, from, time)
            log.flybyThrust = false
        } else if (from.parentId == to.id && log.flybyBody == from.id) {
            val after = energy(vessel.body.position, vessel.body.linearVelocity, to.gravitationalParameter)
            val before = log.flybyEnergy
            log.flybyBody = ""
            if (log.flybyThrust || before == 0.0) return
            val change = abs(after - before) / abs(before)
            if (change >= ASSIST_CHANGE) award(world, vessel, Feat.GRAVITY_ASSIST, change * 100.0)
        }
    }

    private fun energyAbout(world: World, vessel: Vessel, child: CelestialBody, parent: CelestialBody, time: Double): Double {
        val p = world.system.positionOf(child.id, time).subInPlace(world.system.positionOf(parent.id, time)).addInPlace(vessel.body.position)
        val v = world.system.velocityOf(child.id, time).subInPlace(world.system.velocityOf(parent.id, time)).addInPlace(vessel.body.linearVelocity)
        return energy(p, v, parent.gravitationalParameter)
    }

    private fun energy(p: Vec3, v: Vec3, mu: Double) = v.lengthSq / 2.0 - mu / p.length

    /** Above the air or the highest ground, including its low point. */
    private fun clearance(body: CelestialBody): Double =
        maxOf(body.atmosphere?.let { body.atmosphereHeight } ?: 0.0, body.terrain?.maxElevation ?: 0.0)

    /** On the sea, either riding it while asleep or held up by it while awake. */
    private fun onWater(vessel: Vessel): Boolean = vessel.afloat || (vessel.buoyed && !vessel.touchingGround)

    private fun inOrbit(world: World, vessel: Vessel): Boolean {
        if (vessel.touchingGround || onWater(vessel)) return false
        val attractor = world.attractorFor(vessel)
        val orbit = world.orbitOf(vessel)
        return orbit.isBound && orbit.periapsis > attractor.radius + clearance(attractor)
    }

    private fun commandIntact(vessel: Vessel): Boolean =
        vessel.defs.indices.any { vessel.defs[it].module<com.rm.apogee.core.part.Command>() != null && !vessel.isBroken(it) }

    /** The right way up. Tumbling across the ground doesn't count as driving. */
    private fun upright(vessel: Vessel, attractor: CelestialBody): Boolean =
        (vessel.body.orientation.rotate(vessel.design.orientation.up, scratch) dot attractor.let { vessel.body.position.normalized() }) > UPRIGHT

    private fun hasWheels(vessel: Vessel): Boolean =
        vessel.defs.any { it.module<com.rm.apogee.core.part.Wheel>() != null }

    /** Only air-breathing engines are lit: a propeller or the Zephyr, no rockets. */
    private fun airBreathingOnly(vessel: Vessel): Boolean {
        val lit = vessel.activeEngines()
        if (lit.isEmpty() || vessel.control.throttle <= 0.0) return false
        return lit.all { i -> vessel.defs[i].module<com.rm.apogee.core.part.Engine>()?.let { it.thrustVacuum < it.thrustSeaLevel * 0.2 } == true }
    }

    /** On tarmac, meaning the runway or a road. The pad's concrete isn't a place to land. */
    private fun onRunway(world: World, vessel: Vessel, attractor: CelestialBody): Boolean {
        val terrain = attractor.terrain ?: return false
        val d = attractor.toBodyFixed(vessel.body.position, attractor.rotationAt(world.time), Vec3()).normalizeInPlace()
        // The paved surface, not the ground underneath it.
        return terrain.material(d, terrain.elevation(d), 0.0) == SurfaceMaterial.ASPHALT
    }

    private fun nearHarbour(world: World, vessel: Vessel): Boolean {
        val site = World.launchSites.firstOrNull { it.id == "harbour" } ?: return false
        val body = world.system.body(site.bodyId)
        val at = body.rotationAt(world.time).rotate(SolarSystem.surfaceDirection(site.latitude, site.longitude)).mulInPlace(body.radius)
        return at.distanceTo(vessel.body.position) < HARBOUR_REACH
    }

    companion object {
        /** Ticks between looks at every craft, which works out to six a second. */
        const val LOOK_TICKS = 10L

        /** How many metres above the ground count as having left it, as a hop, and as a flight. */
        const val LIFTOFF = 20.0
        const val HOP = 1_000.0
        const val FLIGHT_HEIGHT = 50.0

        /**
         * Faster than this over the ground counts as moving, and slower than [REST] counts as at
         * rest, in m/s.
         */
        const val MOVING = 2.0
        const val REST = 0.5

        /** How many seconds in the sea after coming down make it a splashdown, drifting or not. */
        const val SPLASHDOWN = 3.0

        const val ROAD_TRIP = 10_000.0
        const val OFF_WORLD_DRIVE = 2_000.0
        const val ALIEN_SAIL = 100.0
        const val SEAWORTHY = 5_000.0
        const val UNDER_SAIL = 1_000.0

        /**
         * Hovering: the least height above the ground, in metres, how far off the spot counts as
         * having left it, and how long it has to be held, in seconds.
         */
        const val HOVER_HEIGHT = 20.0
        const val HOVER_LOST = 20.0
        const val HOVER_TIME = 30.0

        /** Metres risen on gas alone, and floated across, for their feats. */
        const val UP_AND_AWAY = 1_000.0
        const val LONG_FLOAT = 20_000.0
        const val LONG_HAUL = 100_000.0
        const val HARBOUR_REACH = 400.0

        /**
         * The speed of sound, near enough, in m/s, and the height below which beating it counts, in
         * metres. A jet's thrust drops off with the air, so none of them get there in level flight.
         * You need a rocket, or a dive from a long way up.
         */
        const val SOUND = 343.0
        const val HIGH = 15_000.0

        /**
         * High for a jet, in metres. The stock Sparrow flown well is near the top of its climb
         * here.
         */
        const val HIGH_FLYER = 5_000.0

        const val RENDEZVOUS_RANGE = 50.0
        const val RENDEZVOUS_SPEED = 1.0
        const val PRECISION = 200.0

        /**
         * How many units of propellant gained while standing on another world count as refuelling
         * there.
         */
        const val REFUELLED = 5.0

        /**
         * How closely its own up has to match the ground's for a craft to count as on its wheels,
         * which is within about 45 degrees.
         */
        const val UPRIGHT = 0.7

        /**
         * Under the sea, deeper than this in metres counts as a dive, and shallower than [SURFACED]
         * counts as back at the top.
         */
        const val DIVED = 5.0
        const val SURFACED = 2.0
        /**
         * Settling on the floor deeper than this, in metres, is the Seafloor feat, and deeper than
         * [ABYSS] is the Abyss.
         */
        const val SEAFLOOR_DEPTH = 100.0
        const val ABYSS = 3_000.0
        /**
         * Being within this many metres of the floor, over a vent field at least this thick (0..1),
         * counts as finding a vent.
         */
        const val VENT_HEIGHT = 50.0
        const val VENT_FIELD = 0.3
        /** How a found wonder is written in a career's visits and in the world firsts. */
        const val WONDER = "wonder"

        /** Shown on a find's banner in place of a feat's grade. */
        const val FOUND = "found"

        /**
         * Taking a third off the high point using the air alone, or a tenth of the energy using a
         * flyby alone.
         */
        const val AEROBRAKE_DROP = 0.3
        const val ASSIST_CHANGE = 0.1
    }
}
