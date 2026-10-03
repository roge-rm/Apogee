package com.rm.apogee.net

import kotlin.concurrent.Volatile
import com.rm.apogee.core.craft.CraftDesign
import com.rm.apogee.core.craft.Vessel
import com.rm.apogee.core.math.Quat
import com.rm.apogee.core.math.Vec3
import com.rm.apogee.core.orbit.SolarSystem
import com.rm.apogee.core.part.PartCatalog
import com.rm.apogee.core.world.VesselKinematics
import com.rm.apogee.core.world.World

/**
 * Runs a local copy of the physics for the craft this client flies, so it answers your input on the
 * same frame instead of after a round trip. Other craft are interpolated between snapshots.
 *
 * The server stays in charge: every snapshot resets the local state to the server's and simulates
 * forward again from there.
 */
class ClientPrediction(
    private val catalog: PartCatalog,
    private val system: SolarSystem = SolarSystem.defaultSystem(),
) {
    private var world: World? = null
    private var vessel: Vessel? = null
    private var accumulator = 0.0

    /**
     * Where prediction had the craft minus where reconciling put it, fading over a few frames so
     * corrections don't twitch.
     *
     * Held in the body's rotating frame. Each snapshot shifts the clock by a few ms of server
     * jitter, which inertially is 175 m/s times that at the equator; against the ground, a craft
     * that hasn't moved has no error.
     */
    private val renderOffset = Vec3()

    /** Set when the local replica can't be trusted and has to be rebuilt. */
    private var designHash: Int = 0

    val isReady: Boolean get() = vessel != null

    /**
     * Rebuilds the local replica when the craft's structure changes. [time] is the universe time
     * [state] describes; on a turning planet the ground under the craft depends on it.
     */
    fun adopt(
        design: CraftDesign,
        state: VesselKinematics,
        time: Double = 0.0,
        /** The server's weather, so the replica feels the same wind. */
        weather: com.rm.apogee.core.weather.WeatherConfig? = null,
    ) {
        val replica = World.default(catalog)
        neighbours.clear()
        neighbourDesigns.clear()
        neighbourPinned.clear()
        // No plan yet; the next sync brings the server's.
        syncedBurns = null
        replica.weatherConfig = weather
        replica.syncClock(time)
        vessel = replica.spawnAt(
            design = design,
            bodyId = state.referenceBodyId,
            position = state.position.copy(),
            velocity = state.velocity.copy(),
            rotation = state.rotation.copy(),
            angularVelocity = state.angularVelocity.copy(),
        )
        // Parked on the server, so parked here, instead of settling onto its legs.
        if (serverHasItAsleep(state)) {
            vessel?.sleep(system.body(state.referenceBodyId).rotationAt(time, scratchRotation))
        }
        vessel?.let { chutesFrom(it, state) }
        world = replica
        designHash = design.hashCode()
        lastFuel = null
        renderOffset.setZero()
        accumulator = 0.0
    }

    /**
     * Brings the replica's staging and tanks to the server's. A rebuilt replica starts unstaged and
     * full, so after a separation it would have the upper stage unlit with full tanks.
     */
    fun sync(stage: Int, activatedParts: List<Int>, fuel: List<Float>?) {
        val local = vessel ?: return
        val lit = local.design.parts.indices.filter { local.isActivated(it) }
        // Never backwards. A stage fired here is ahead of the server until its staging comes back.
        if (stage > local.currentStage || (stage == local.currentStage && lit != activatedParts.sorted())) {
            local.restoreStaging(stage, activatedParts, local.design.parts.indices.filter { local.isBroken(it) })
        }
        if (fuel != null && fuel !== lastFuel) {
            lastFuel = fuel
            local.restoreFlatResources(fuel)
        }
    }

    private var lastFuel: List<Float>? = null

    /**
     * Whether the server acts on this player's inputs. False for a probe out of touch or flat, so
     * the replica ignores inputs too.
     */
    @Volatile var heard: Boolean = true

    /** The server's word on the flown craft's power and link. See [heard]. */
    fun syncSystems(systems: com.rm.apogee.core.world.ServerMessage.CraftSystems) {
        heard = systems.controllable
        val local = vessel ?: return
        if (local.control.deployed != systems.deployed || local.control.drilling != systems.drilling || local.control.gear != systems.gear) {
            local.control.deployed = systems.deployed
            local.control.drilling = systems.drilling
            local.control.gear = systems.gear
            // A sleeping craft never steps its wings, drill or gear, and nor does a parked one.
            local.wake()
            if (parked) unpark()
        }
        local.control.refining = systems.refining
        // Cruise hold and action groups as the server has them.
        local.control.cruise = systems.cruiseHeight >= 0f
        if (local.control.cruise) {
            local.control.cruiseHeight = systems.cruiseHeight.toDouble()
            local.control.cruiseHeading = systems.cruiseHeading.toDouble()
        }
        for (k in local.groupStates.indices) local.groupStates[k] = systems.groups.getOrElse(k) { 0 }
        // Station keeping and gas cell trim too, since the keeper has the throttle.
        local.control.keeping = systems.keeping
        if (systems.keeping) local.control.keepPoint.setTo(systems.keepPoint)
        local.ballonet = systems.ballonet.toDouble()
    }

    private val serverPose = com.rm.apogee.core.world.VesselPose.Values()

    /**
     * Chutes, fold-out parts and rotor spool as the server has them. A rebuilt replica starts with
     * everything packed and at rest, so a canopy would fill all over again.
     */
    private fun chutesFrom(local: Vessel, state: VesselKinematics) {
        if (!com.rm.apogee.core.world.VesselPose.decode(local.defs, state.pose, serverPose)) return
        for (i in local.defs.indices) {
            // Sun wings and dishes too; a sleeping replica never steps them out.
            val def = local.defs[i]
            // Rotors and propellers at the server's speed, or a hovering helicopter drops.
            val propeller = def.module<com.rm.apogee.core.part.Engine>()?.exhaustKind.let {
                it == com.rm.apogee.core.part.Exhaust.PROP || it == com.rm.apogee.core.part.Exhaust.WATER
            }
            if (def.module<com.rm.apogee.core.part.Rotor>() != null || propeller) {
                if (i < local.spool.size) local.spool[i] = serverPose.output[i].coerceIn(0.0, 1.0)
            }
            if (com.rm.apogee.core.world.VesselPose.foldsOut(def)) {
                local.setLegDeploy(i, serverPose.deploy[i])
                continue
            }
            if (def.module<com.rm.apogee.core.part.Parachute>() == null) continue
            // The wire rounds to a 127th, so the drogue's 0.5 arrives as 0.504. Snap it back so it
            // doesn't read as the main filling.
            val d = serverPose.deploy[i]
            val held = com.rm.apogee.core.part.Parachute.DROGUE_FULL
            local.setLegDeploy(i, if (kotlin.math.abs(d - held) < 0.02) held else d)
        }
    }

    /** The local replica, for reading. The HUD's staging and fuel change here first. */
    val replica: Vessel? get() = vessel

    /** The sea's current where the replica is, in the world's frame, or null with no replica. */
    fun currentAt(out: Vec3 = Vec3()): Vec3? {
        val w = world ?: return null
        val v = vessel ?: return null
        return w.currentAt(v, out = out)
    }

    /** True when [design] isn't what the replica was built from. */
    fun needsAdopting(design: CraftDesign): Boolean =
        vessel == null || design.hashCode() != designHash

    /** Copies the player's controls onto the local replica. */
    fun applyControl(
        throttle: Double,
        pitch: Double,
        yaw: Double,
        roll: Double,
        sas: Boolean,
        brakes: Boolean = false,
        rcs: Boolean = false,
        reverse: Boolean = false,
        translateX: Double = 0.0,
        translateY: Double = 0.0,
        translateZ: Double = 0.0,
        flaps: Boolean = false,
    ) {
        val control = vessel?.control ?: return
        // Out of touch: the server's craft carries on as it was left, so this one does too.
        if (!heard) return
        // When it's flying itself, the autopilot has the throttle.
        if (!control.autoBurn && !control.autoLand) control.throttle = throttle
        control.pitch = pitch
        control.yaw = yaw
        control.roll = roll
        control.sasEnabled = sas
        control.brakes = brakes
        control.rcsEnabled = rcs
        control.reverse = reverse
        control.flaps = flaps
        control.translateX = translateX
        control.translateY = translateY
        control.translateZ = translateZ
        // Wakes on input, as the server does.
        if (!inputsNeutral(control)) vessel?.wake()
    }

    /** The server's burns that were last put into the replica. See [syncPlan]. */
    private var syncedBurns: List<com.rm.apogee.core.world.PlannedBurn>? = null

    /**
     * Puts the flown craft's autopilots, target body and burns onto the replica. Burns only go on
     * when the server's list changes, so one the replica just finished isn't put back.
     */
    fun syncPlan(burns: List<com.rm.apogee.core.world.PlannedBurn>, autoBurn: Boolean, autoLand: Boolean, targetBody: String) {
        val local = vessel ?: return
        if (burns !== syncedBurns) {
            syncedBurns = burns
            if (local.plannedBurns != burns) {
                val sameNext = local.plannedBurns.firstOrNull() == burns.firstOrNull()
                local.plannedBurns.clear()
                local.plannedBurns.addAll(burns)
                if (!sameNext) {
                    local.resetBurn()
                    local.burnDuration = burns.firstOrNull()?.let { com.rm.apogee.core.world.Burns.duration(local, it.deltaV) } ?: 0.0
                }
            }
        }
        local.control.autoBurn = autoBurn
        local.control.autoLand = autoLand
        local.control.targetBody = targetBody
    }

    private fun inputsNeutral(control: com.rm.apogee.core.craft.ControlState): Boolean =
        control.throttle == 0.0 && control.pitch == 0.0 && control.yaw == 0.0 &&
            control.roll == 0.0 && control.translateX == 0.0 &&
            control.translateY == 0.0 && control.translateZ == 0.0

    /** Scatter the server says is down, so the replica doesn't collide with it. */
    fun felled(ids: Collection<Long>) {
        world?.felledScatter?.addAll(ids)
    }

    /** Fires the next stage locally, so staging feels immediate too. */
    fun stage() {
        if (!heard) return
        val replica = world ?: return
        val local = vessel ?: return
        if (parked) unpark()
        local.wake()
        replica.stage(local)
    }

    /** Back to stepping from where it's been drawn, so taking the controls doesn't jump it. */
    private fun unpark() {
        parked = false
        val replica = world ?: return
        val local = vessel ?: return
        local.wake()
        local.body.position.addScaledInPlace(local.body.linearVelocity, accumulator)
        spin(local.body.orientation, local.body.angularVelocity, accumulator)
        replica.syncClock(replica.time + accumulator)
        accumulator = 0.0
    }

    /** Turns [q] on by [spin], in radians a second, over [seconds]. */
    private fun spin(q: Quat, spin: Vec3, seconds: Double) {
        val angle = spin.length * seconds
        if (angle < 1e-12) return
        scratchSpinAxis.setTo(spin).mulInPlace(1.0 / spin.length)
        Quat.fromAxisAngle(scratchSpinAxis, angle, scratchSpinTurn)
        q.setTo(scratchSpinTurn.times(q)).normalizeInPlace()
    }

    private val scratchSpinAxis = Vec3()
    private val scratchSpinTurn = Quat.identity()

    /**
     * Asleep on the server with nobody at the controls, so the replica isn't stepped; it's drawn
     * carried on from the last snapshot. Saves a lot on a big ship asleep at sea.
     */
    private var parked = false

    /** How many steps [advance] has taken the replica through, for tests. */
    var stepsTaken = 0L
        private set

    /** Moves the replica on by the real time that's passed, in fixed steps. */
    fun advance(elapsedSeconds: Double) {
        val replica = world ?: return
        val local = vessel
        if (parked && local != null && inputsNeutral(local.control)) {
            accumulator += elapsedSeconds.coerceAtMost(MAX_CATCHUP_SECONDS)
            renderOffset.mulInPlace(OFFSET_DECAY)
            return
        }
        if (parked) unpark()
        accumulator += elapsedSeconds.coerceAtMost(MAX_CATCHUP_SECONDS)
        while (accumulator >= DT) {
            replica.step(DT)
            stepsTaken++
            accumulator -= DT
        }
        // Bleed off the correction.
        renderOffset.mulInPlace(OFFSET_DECAY)
        if (renderOffset.lengthSq < 1e-6) renderOffset.setZero()
    }

    /**
     * Another craft near the flown one, as the server last had it, for the replica to push against.
     * [time] is when [state] was true.
     */
    class Neighbour(
        val id: Long,
        val design: com.rm.apogee.core.craft.CraftDesign,
        val state: VesselKinematics,
        val time: Double,
        val stage: Int,
        val activated: List<Int>,
        /** Founded on the server, so it can't be moved here either. */
        val anchored: Boolean = false,
    )

    /** The replica's copies of the craft near ours, by their ids on the server. */
    private val neighbours = HashMap<Long, Vessel>()
    /** Each copy's design and pinning, to tell when it must be rebuilt. */
    private val neighbourDesigns = HashMap<Long, CraftDesign>()
    private val neighbourPinned = HashMap<Long, Boolean>()

    /**
     * Keeps copies of [near] in the replica at [time]: added, moved to the server's place, dropped
     * when gone. So a burning dropped stage or a docking ring pushes and pulls the replica too.
     */
    private fun placeNeighbours(replica: World, near: List<Neighbour>, time: Double) {
        val keep = near.map { it.id }.toSet()
        val gone = neighbours.keys.filter { it !in keep }
        for (id in gone) {
            neighbours.remove(id)?.let { replica.destroy(it.id, "out of reach") }
            neighbourDesigns.remove(id)
            neighbourPinned.remove(id)
        }
        for (n in near) {
            val position = if (n.state.asleep && serverHasItAsleep(n.state)) {
                // Asleep, so turned with its world since last heard of, maybe seconds ago.
                val body = system.body(n.state.referenceBodyId)
                body.rotationAt(time).rotate(body.toBodyFixed(n.state.position, body.rotationAt(n.time), scratchVelocity), Vec3())
            } else n.state.position.copy().addScaledInPlace(n.state.velocity, time - n.time)
            var copy = neighbours[n.id]
            // By identity; hashing a big base 20 times a second costs more than rebuilding it.
            if (copy != null && (neighbourDesigns[n.id] !== n.design || neighbourPinned[n.id] != n.anchored)) {
                replica.destroy(copy.id, "rebuilt")
                copy = null
            }
            if (copy == null) {
                // Staged here and not heard back yet, so the replica already has its own copy.
                val stand = replica.vessels.any { v ->
                    v !== vessel && v !in neighbours.values && v.body.position.distanceTo(position) < LOCAL_COPY_REACH
                }
                if (stand) continue
                copy = replica.spawnAt(n.design, n.state.referenceBodyId, position, n.state.velocity.copy(), n.state.rotation.copy(), n.state.angularVelocity.copy())
                neighbours[n.id] = copy
                neighbourDesigns[n.id] = n.design
                neighbourPinned[n.id] = n.anchored
                // A founded base doesn't move.
                if (n.anchored) replica.pin(copy)
                // First seen beside us, so it probably just came off us.
                vessel?.let { replica.graceBetween(it.id, copy.id, NEIGHBOUR_GRACE) }
                if (!n.anchored && n.state.asleep) copy.sleep(system.body(n.state.referenceBodyId).rotationAt(time, scratchRotation))
            } else if (!copy.anchored) {
                copy.wake()
                copy.body.position.setTo(position)
                copy.body.linearVelocity.setTo(n.state.velocity)
                copy.body.orientation.setTo(n.state.rotation)
                copy.body.angularVelocity.setTo(n.state.angularVelocity)
                // Asleep on the server, so asleep here until the next snapshot. Saves floating a
                // whole ship every step.
                if (n.state.asleep) copy.sleep(system.body(n.state.referenceBodyId).rotationAt(time, scratchRotation))
            }
            copy.restoreStaging(n.stage, n.activated, copy.brokenIndices())
            copy.control.throttle = n.state.throttle
        }
    }

    private val condition = com.rm.apogee.core.world.VesselCondition.Values()

    /** The water the server says each hull has shipped, so a swamped boat sits low here too. */
    private fun floodingFrom(local: com.rm.apogee.core.craft.Vessel, state: VesselKinematics) {
        val n = local.defs.size
        com.rm.apogee.core.world.VesselCondition.decode(n, state.condition, condition)
        if (local.flooded.size != n) return
        var changed = false
        for (i in 0 until n) {
            val capacity = com.rm.apogee.core.part.Buoyancy.capacity(local.defs[i])
            if (capacity <= 0.0) continue
            val server = condition.flooded[i] * capacity
            if (kotlin.math.abs(server - local.flooded[i]) > capacity / 255.0 * 2.0) {
                local.flooded[i] = server
                changed = true
            }
        }
        if (changed) local.recomputeMass()
    }

    /**
     * Resets to the server's state and simulates forward again by [ageSeconds], so the snapshot's
     * old state is brought up to now with the same controls.
     */
    fun reconcile(
        state: VesselKinematics,
        ageSeconds: Double,
        snapshotTime: Double? = null,
        near: List<Neighbour> = emptyList(),
        /** Founded on the server, so pinned here too, where the server has it. */
        anchored: Boolean = false,
    ) {
        val replica = world ?: return
        val local = vessel ?: return
        // Unpinned so the server's state can be written in.
        if (local.anchored) replica.unanchor(local)
        // Changed body on one side only. Rebase to the server's body and drop the correction,
        // which was held against the old body's ground.
        if (local.referenceBodyId != state.referenceBodyId && system.bodies.containsKey(state.referenceBodyId)) {
            system.rebase(local.body.position, local.body.linearVelocity, local.referenceBodyId, state.referenceBodyId, replica.time)
            local.referenceBodyId = state.referenceBodyId
            renderOffset.setZero()
        }

        // Where it was drawn, not where it last stepped to, or the part step counts as error.
        val before = local.body.position.copy().addScaledInPlace(local.body.linearVelocity, accumulator)
        toGround(local, before, replica.time + accumulator)

        // A sleeping craft isn't integrated and would ignore the new state, so wake it first.
        local.wake()
        local.body.position.setTo(state.position)
        local.body.linearVelocity.setTo(state.velocity)
        local.body.orientation.setTo(state.rotation)
        local.body.angularVelocity.setTo(state.angularVelocity)
        chutesFrom(local, state)
        floodingFrom(local, state)
        // Back to the snapshot's time so the ground matches, then catch up both together. Without
        // the server's time, use our clock minus the age.
        val describes = snapshotTime ?: (replica.time + accumulator - ageSeconds)
        replica.syncClock(describes)
        placeNeighbours(replica, near, describes)

        // Asleep on the server and nobody at the controls, so asleep here at the server's pose.
        // Awake, it would bounce a few centimetres on its sprung legs between snapshots.
        parked = !anchored && state.asleep && inputsNeutral(local.control)
        if (anchored) {
            replica.pin(local)
        } else if (serverHasItAsleep(state) && inputsNeutral(local.control) && !replica.legsMoving(local)) {
            // Still, but not while its gear or wings swing: asleep, they'd stop half way.
            local.sleep(system.body(state.referenceBodyId).rotationAt(describes, scratchRotation))
        }
        if (parked) {
            // Nothing to catch up; it's drawn carried on from here.
            accumulator = ageSeconds.coerceIn(0.0, MAX_CATCHUP_SECONDS)
            val after = local.body.position.copy().addScaledInPlace(local.body.linearVelocity, accumulator)
            toGround(local, after, replica.time + accumulator)
            renderOffset.addInPlace(before).subInPlace(after)
            if (renderOffset.length > MAX_SMOOTHED_ERROR) renderOffset.setZero()
            return
        }

        val catchUp = (ageSeconds / DT).toInt().coerceIn(0, MAX_CATCHUP_TICKS)
        repeat(catchUp) { replica.step(DT) }
        // The leftover part step waits in the accumulator for the next advance and [renderTime].
        accumulator = (ageSeconds - catchUp * DT).coerceIn(0.0, DT)

        // Carry the difference so the correction is smoothed.
        val after = local.body.position.copy().addScaledInPlace(local.body.linearVelocity, accumulator)
        toGround(local, after, replica.time + accumulator)
        renderOffset.addInPlace(before).subInPlace(after)
        if (renderOffset.length > MAX_SMOOTHED_ERROR) {
            // Too far to hide, so show the truth at once.
            renderOffset.setZero()
        }
    }

    /**
     * Whether [state] is a sleeping craft. A sleeping craft's velocity matches the surface to
     * rounding error; an awake one on its gear carries a contact impulse of 0.1 m/s or more. So no
     * flag on the wire is needed.
     */
    private fun serverHasItAsleep(state: VesselKinematics): Boolean {
        val body = system.bodies[state.referenceBodyId] ?: return false
        body.surfaceVelocityAt(state.position, scratchVelocity)
        if (scratchVelocity.subInPlace(state.velocity).length > ASLEEP_SPEED) return false
        body.angularVelocity(scratchVelocity)
        return scratchVelocity.subInPlace(state.angularVelocity).length <= ASLEEP_SPIN
    }

    private val scratchVelocity = Vec3()
    private val scratchRotation = Quat.identity()

    /**
     * The universe time the predicted craft is drawn at: the last step plus the part step since.
     * The rest of the frame, the planet's rotation above all, must use the same time.
     */
    fun renderTime(): Double? {
        val replica = world ?: return null
        return replica.time + accumulator
    }

    /**
     * Shifts where the craft is drawn by [delta] (inertial), eased away like any correction, so a
     * rebuilt replica doesn't snap. Does nothing if it's too far to hide.
     */
    fun carryOffset(delta: Vec3) {
        val replica = world ?: return
        val local = vessel ?: return
        system.body(local.referenceBodyId).rotationAt(replica.time + accumulator, scratchRotation)
        val held = scratchRotation.inverseRotate(delta, Vec3())
        if (held.addInPlace(renderOffset).length <= MAX_SMOOTHED_ERROR) renderOffset.setTo(held)
    }

    /**
     * Where to draw the craft: the prediction carried forward to [renderTime] (the replica steps at
     * 60 Hz, displays run faster), plus the fading correction. Measured from the centre of
     * [bodyId], the frame's body, which near a change of body may not be the replica's.
     */
    fun renderPosition(out: Vec3 = Vec3(), bodyId: String? = null): Vec3? {
        val local = vessel ?: return null
        val replica = world ?: return null
        out.setTo(local.body.position).addScaledInPlace(local.body.linearVelocity, accumulator)
        if (renderOffset.lengthSq != 0.0) {
            // The correction is held in the ground frame, so turn it with the ground.
            system.body(local.referenceBodyId).rotationAt(replica.time + accumulator, scratchRotation)
            scratchRotation.rotate(renderOffset, scratchVelocity)
            out.addInPlace(scratchVelocity)
        }
        if (bodyId != null && bodyId != local.referenceBodyId && system.bodies.containsKey(bodyId)) {
            system.rebase(out, scratchVelocity.setZero(), local.referenceBodyId, bodyId, replica.time + accumulator)
        }
        return out
    }

    /** Turns [position] (inertial, at [time]) into the body's rotating frame, in place. */
    private fun toGround(local: Vessel, position: Vec3, time: Double) {
        system.body(local.referenceBodyId).rotationAt(time, scratchRotation)
        scratchRotation.inverseRotate(position.copy(), position)
    }

    /**
     * Pieces the replica dropped before the server heard, with where to draw each, carried and
     * corrected like [renderPosition]. They go when the server's split arrives and the replica is
     * adopted afresh.
     */
    fun droppedPieces(): List<Pair<Vessel, Vec3>> {
        val local = vessel ?: return emptyList()
        val replica = world ?: return emptyList()
        if (replica.vessels.size < 2) return emptyList()
        val offset = Vec3()
        if (renderOffset.lengthSq > 0.0) {
            system.body(local.referenceBodyId).rotationAt(replica.time + accumulator, scratchRotation)
            scratchRotation.rotate(renderOffset, offset)
        }
        return replica.vessels.filter { it !== local }.map { piece ->
            piece to Vec3().setTo(piece.body.position)
                .addScaledInPlace(piece.body.linearVelocity, accumulator)
                .addInPlace(offset)
        }
    }

    fun renderRotation(out: Quat = Quat()): Quat? {
        val local = vessel ?: return null
        out.setTo(local.body.orientation)
        // Parked, it turns on as well as moves, or a sleeping ship rocks only per snapshot.
        if (parked) spin(out, local.body.angularVelocity, accumulator)
        return out
    }

    /** The replica's moving parts from its own tick, so surfaces follow the stick at once. */
    fun pose(into: com.rm.apogee.core.world.VesselPose.Values): Boolean {
        val local = vessel ?: return false
        local.fitPose()
        into.fit(local.defs.size)
        local.surfaceDeflection.copyInto(into.deflection)
        local.wheelSteer.copyInto(into.steer)
        local.wheelCompression.copyInto(into.compression)
        local.legDeploy.copyInto(into.deploy)
        local.gimbalPitch.copyInto(into.gimbalPitch)
        local.gimbalYaw.copyInto(into.gimbalYaw)
        local.engineOutput.copyInto(into.output)
        local.rcsFiring.copyInto(into.rcs)
        local.flapPosition.copyInto(into.flap)
        local.sailAngle.copyInto(into.sailAngle)
        local.sailFill.copyInto(into.sailFill)
        return true
    }

    fun velocity(out: Vec3 = Vec3()): Vec3? {
        val local = vessel ?: return null
        return out.setTo(local.body.linearVelocity)
    }

    fun reset() {
        world = null
        vessel = null
        neighbours.clear()
        neighbourDesigns.clear()
        neighbourPinned.clear()
        designHash = 0
        renderOffset.setZero()
    }

    private companion object {
        /** Seconds a new neighbour only touches us gently. See World's separation grace. */
        const val NEIGHBOUR_GRACE = 1.5
        /** A replica craft this near a neighbour, in metres, is taken to be it. */
        const val LOCAL_COPY_REACH = 12.0
        const val DT = 1.0 / 60.0

        /** Relative speed, in m/s, below which a snapshot can only be a sleeping craft. */
        const val ASLEEP_SPEED = 1e-4

        /** The same for spin, in rad/s. */
        const val ASLEEP_SPIN = 1e-9

        /** The most real time one advance can try to make up. */
        const val MAX_CATCHUP_SECONDS = 0.25

        /** Caps the catch-up, so a stalled connection can't spiral. */
        const val MAX_CATCHUP_TICKS = 30

        /** Per-frame fading of the smoothed correction. */
        const val OFFSET_DECAY = 0.85

        /** Errors over this many metres (about a craft's length) are shown at once, unsmoothed. */
        const val MAX_SMOOTHED_ERROR = 25.0
    }
}
