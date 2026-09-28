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
 * Runs a local copy of the physics for the craft this client is flying.
 *
 * Without it, pressing the throttle does nothing you can see until a command has reached the
 * server, been stepped, and come back in a snapshot. At 20 Hz that's up to 50 ms of stale state on
 * top of the round trip, and the controls feel spongy even on a LAN. With it the craft responds on
 * the same frame as your input, and the server stays in charge by correcting instead of by waiting.
 *
 * Only the craft you're *controlling* is predicted. Everything else is interpolated between
 * snapshots, because there's no input to predict from, and guessing at another player's throttle
 * would give confident, wrong motion.
 *
 * The server is in charge the whole time. Every snapshot resets the local state to what the server
 * said and simulates forward again from there. This hides latency. It isn't a second opinion.
 */
class ClientPrediction(
    private val catalog: PartCatalog,
    private val system: SolarSystem = SolarSystem.defaultSystem(),
) {
    private var world: World? = null
    private var vessel: Vessel? = null
    private var accumulator = 0.0

    /**
     * The difference between where prediction had the craft and where reconciling put it, fading
     * toward zero over a few frames.
     *
     * Snapping straight to the corrected state makes the craft visibly twitch on every snapshot.
     * Carrying the error and bleeding it off hides the correction without lying about where the
     * craft really is.
     *
     * It's held in the body's rotating frame (relative to the ground), not the inertial one. Each
     * snapshot moves the prediction's clock by however unevenly the server has been ticking, a few
     * milliseconds. Measured inertially, that's 175 m/s times the slip at the equator, and the
     * smoothing faithfully kept the craft where it had been while the ground was drawn at the new
     * time. That put the craft metres off its ground, fading over a few frames, on every snapshot.
     * Against the ground, a craft that hasn't moved over it has no error to smooth.
     */
    private val renderOffset = Vec3()

    /** Set when the local replica can't be trusted and has to be rebuilt. */
    private var designHash: Int = 0

    val isReady: Boolean get() = vessel != null

    /**
     * Rebuilds the local replica. Called when the craft's structure changes.
     *
     * [time] is the universe time [state] describes. It matters as much as the position. On a
     * turning planet the ground under a craft is wherever the planet has turned to by then, and a
     * replica on its own clock puts a craft parked on the runway onto whatever ground its wrong
     * time says is there, and every snapshot drags it back.
     */
    fun adopt(
        design: CraftDesign,
        state: VesselKinematics,
        time: Double = 0.0,
        /** The server's weather, so the replica gets pushed by the same wind. */
        weather: com.rm.apogee.core.weather.WeatherConfig? = null,
    ) {
        val replica = World.default(catalog)
        neighbours.clear()
        neighbourDesigns.clear()
        // A new replica has no plan yet. The next sync gives it the server's.
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
        // Parked on the server, so parked here from the start, instead of settling onto its legs
        // until the first snapshot says otherwise.
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
     * Brings the replica's staging and tanks to what the server last said.
     *
     * A replica is rebuilt from the design alone, unstaged and full. After a separation that left
     * the upper stage's engine unlit and its tanks brimming here, while the server's burned on.
     * Staging is taken whenever it's different, and fuel whenever it arrives, a small correction a
     * few times a second.
     */
    fun sync(stage: Int, activatedParts: List<Int>, fuel: List<Float>?) {
        val local = vessel ?: return
        val lit = local.design.parts.indices.filter { local.isActivated(it) }
        // Never backwards. A stage fired here a moment ago is ahead of the server until its own
        // staging comes back, and undoing it in between would put the engine out for a frame or
        // two.
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
     * Whether the server acts on what this player sends the craft. It's false for a probe that's
     * out of touch or flat, so the replica doesn't fly on inputs the server's craft never gets.
     */
    @Volatile var heard: Boolean = true

    /** The server's word on the flown craft's power and link. See [heard]. */
    fun syncSystems(systems: com.rm.apogee.core.world.ServerMessage.CraftSystems) {
        heard = systems.controllable
        val local = vessel ?: return
        if (local.control.deployed != systems.deployed || local.control.drilling != systems.drilling) {
            local.control.deployed = systems.deployed
            local.control.drilling = systems.drilling
            // Parked, it would never step its wings or drill out.
            local.wake()
        }
        local.control.refining = systems.refining
        // Holding its height and heading, and its action groups, as the server has them, so the
        // replica flies the same attitude and runs the same parts.
        local.control.cruise = systems.cruiseHeight >= 0f
        if (local.control.cruise) {
            local.control.cruiseHeight = systems.cruiseHeight.toDouble()
            local.control.cruiseHeading = systems.cruiseHeading.toDouble()
        }
        for (k in local.groupStates.indices) local.groupStates[k] = systems.groups.getOrElse(k) { 0 }
        // Holding station, and the gas cells' trim, the same way, since the keeper has the
        // throttle.
        local.control.keeping = systems.keeping
        if (systems.keeping) local.control.keepPoint.setTo(systems.keepPoint)
        local.ballonet = systems.ballonet.toDouble()
    }

    private val serverPose = com.rm.apogee.core.world.VesselPose.Values()

    /**
     * Each chute as the server has it: packed, filling, open or cut away. A rebuilt replica starts
     * with every chute packed, so after a pause or a change of warp its canopy filled all over
     * again, and for the second that took it fell with almost no drag while the server's hung under
     * a full one. Each fold-out part is done too, for the same kind of reason.
     */
    private fun chutesFrom(local: Vessel, state: VesselKinematics) {
        if (!com.rm.apogee.core.world.VesselPose.decode(local.defs, state.pose, serverPose)) return
        for (i in local.defs.indices) {
            // Sun wings and dishes too. A craft parked on the pad sleeps here as it does on the
            // server, and a sleeping replica never steps them out.
            val def = local.defs[i]
            // Rotors and propellers at the speed they're turning on the server. Rebuilt at rest,
            // a hovering helicopter's replica dropped while its rotor spooled up again.
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
            // The wire carries it to a 127th, so the drogue's 0.5 arrives as 0.504, which read as
            // the main starting to fill. A held drogue stays held.
            val d = serverPose.deploy[i]
            val held = com.rm.apogee.core.part.Parachute.DROGUE_FULL
            local.setLegDeploy(i, if (kotlin.math.abs(d - held) < 0.02) held else d)
        }
    }

    /**
     * The local replica, for reading. Staging and fuel for the HUD move here first with the
     * player's own presses and burns.
     */
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
        // Out of touch, so the server's craft carries on as it was left, and so does this one.
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
        // A hand on the controls is the same signal the server wakes on.
        if (!inputsNeutral(control)) vessel?.wake()
    }

    /** The server's burns that were last put into the replica. See [syncPlan]. */
    private var syncedBurns: List<com.rm.apogee.core.world.PlannedBurn>? = null

    /**
     * Puts the flown craft's plan onto the replica: its autopilots, its target body, and its burns.
     * The burns only go on when the server's list has changed, so a burn the replica has just
     * finished isn't put back while the server finishes it too.
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
        local.wake()
        replica.stage(local)
    }

    /** Moves the replica on by the real time that's passed, in fixed steps. */
    fun advance(elapsedSeconds: Double) {
        val replica = world ?: return
        accumulator += elapsedSeconds.coerceAtMost(MAX_CATCHUP_SECONDS)
        while (accumulator >= DT) {
            replica.step(DT)
            accumulator -= DT
        }
        // Bleed off any correction that's left.
        renderOffset.mulInPlace(OFFSET_DECAY)
        if (renderOffset.lengthSq < 1e-6) renderOffset.setZero()
    }

    /**
     * Resets to the server's state and simulates forward again by [ageSeconds].
     *
     * The snapshot describes the world as it was when the server sent it. Using it directly would
     * rubber-band the craft backwards on every update. The catch-up runs the same controls again
     * over the time that's passed, so the local state is the server's answer brought up to date.
     */
    /**
     * Another craft near the one being flown, as the server last had it, for the replica to push
     * against and be pushed by. [time] is when [state] was true.
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
    private val neighbourDesigns = HashMap<Long, Int>()

    /**
     * Keeps copies of [near] in the replica, at [time]: added, moved to where the server says they
     * are, and dropped once they're gone or far away. A stage just let go of and still burning
     * pushes the flown craft, and a ring being docked with draws it in, and the replica sees that,
     * instead of every snapshot dragging the craft to where the server had it pushed.
     */
    private fun placeNeighbours(replica: World, near: List<Neighbour>, time: Double) {
        val keep = near.map { it.id }.toSet()
        val gone = neighbours.keys.filter { it !in keep }
        for (id in gone) {
            neighbours.remove(id)?.let { replica.destroy(it.id, "out of reach") }
            neighbourDesigns.remove(id)
        }
        for (n in near) {
            val position = n.state.position.copy().addScaledInPlace(n.state.velocity, time - n.time)
            var copy = neighbours[n.id]
            val key = n.design.hashCode() * 31 + if (n.anchored) 1 else 0
            if (copy != null && neighbourDesigns[n.id] != key) {
                replica.destroy(copy.id, "rebuilt")
                copy = null
            }
            if (copy == null) {
                // Staged here already and not heard back yet, so the replica has its own copy of
                // what fell away, right there. Not two.
                val stand = replica.vessels.any { v ->
                    v !== vessel && v !in neighbours.values && v.body.position.distanceTo(position) < LOCAL_COPY_REACH
                }
                if (stand) continue
                copy = replica.spawnAt(n.design, n.state.referenceBodyId, position, n.state.velocity.copy(), n.state.rotation.copy(), n.state.angularVelocity.copy())
                neighbours[n.id] = copy
                neighbourDesigns[n.id] = key
                // A founded base doesn't give. It's where the server has it, for good.
                if (n.anchored) replica.pin(copy)
                // First seen already beside us, so it probably just came away from us.
                vessel?.let { replica.graceBetween(it.id, copy.id, NEIGHBOUR_GRACE) }
            } else if (!copy.anchored) {
                copy.wake()
                copy.body.position.setTo(position)
                copy.body.linearVelocity.setTo(n.state.velocity)
                copy.body.orientation.setTo(n.state.rotation)
                copy.body.angularVelocity.setTo(n.state.angularVelocity)
            }
            copy.restoreStaging(n.stage, n.activated, copy.brokenIndices())
            copy.control.throttle = n.state.throttle
        }
    }

    private val condition = com.rm.apogee.core.world.VesselCondition.Values()

    /**
     * The water the server says each hull has shipped. Otherwise a boat that swamped before this
     * replica existed (or before it could see why) would float high and dry here, and the two would
     * never agree.
     */
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
        // Let go for the moment, so the server's state can be written into it.
        if (local.anchored) replica.unanchor(local)
        // It passed into another body's pull on one side and not yet on the other, so it's measured
        // from the server's body from here on. It's the same place, from another centre, and any
        // correction being eased away (held against the old body's ground) goes with it.
        if (local.referenceBodyId != state.referenceBodyId && system.bodies.containsKey(state.referenceBodyId)) {
            system.rebase(local.body.position, local.body.linearVelocity, local.referenceBodyId, state.referenceBodyId, replica.time)
            local.referenceBodyId = state.referenceBodyId
            renderOffset.setZero()
        }

        // Where it was being drawn, not where it last stepped to. The two differ by the fraction of
        // a step since, and counting that as error would twitch the craft on every snapshot.
        val before = local.body.position.copy().addScaledInPlace(local.body.linearVelocity, accumulator)
        toGround(local, before, replica.time + accumulator)

        // Writing state into a sleeping replica would be ignored. A dormant craft rides the
        // planet's rotation from its stored ground position and isn't integrated, so it would sit
        // there while the server's craft flew away. The replica sleeps for the same reason the
        // server's world does (it's the same World), and reconciling is exactly the moment to say
        // it isn't parked any more.
        local.wake()
        local.body.position.setTo(state.position)
        local.body.linearVelocity.setTo(state.velocity)
        local.body.orientation.setTo(state.rotation)
        local.body.angularVelocity.setTo(state.angularVelocity)
        chutesFrom(local, state)
        floodingFrom(local, state)
        // Back to the moment the snapshot describes, so the ground is where it was then, and the
        // catch-up brings both forward together. Without the server's time, it's the replica's own
        // clock minus the snapshot's age.
        val describes = snapshotTime ?: (replica.time + accumulator - ageSeconds)
        replica.syncClock(describes)
        placeNeighbours(replica, near, describes)

        // Asleep on the server, and nobody's touching the controls, so it's asleep here too, at
        // exactly the server's pose. Woken instead, the replica's craft settles onto its sprung
        // legs for the three frames until the next snapshot resets it. That's a few centimetres of
        // bounce, which with the camera following the craft looks like the ground jittering under
        // it.
        if (anchored) {
            replica.pin(local)
        } else if (serverHasItAsleep(state) && inputsNeutral(local.control)) {
            local.sleep(system.body(state.referenceBodyId).rotationAt(describes, scratchRotation))
        }

        val catchUp = (ageSeconds / DT).toInt().coerceIn(0, MAX_CATCHUP_TICKS)
        repeat(catchUp) { replica.step(DT) }
        // The part of the age too short for a whole step waits in the accumulator, where the next
        // advance spends it and [renderTime] counts it, instead of being dropped.
        accumulator = (ageSeconds - catchUp * DT).coerceIn(0.0, DT)

        // Carry the difference so the correction gets smoothed out instead of applied as a jump.
        val after = local.body.position.copy().addScaledInPlace(local.body.linearVelocity, accumulator)
        toGround(local, after, replica.time + accumulator)
        renderOffset.addInPlace(before).subInPlace(after)
        if (renderOffset.length > MAX_SMOOTHED_ERROR) {
            // Too far out to hide. The replica was wrong about something real, so show the truth
            // instead of sliding toward it for a second.
            renderOffset.setZero()
        }
    }

    /**
     * Whether [state] is a sleeping craft. The server rebuilds a sleeping craft's velocity from the
     * planet's rotation every tick, so it matches the surface to rounding error. An awake one
     * resting on its gear never does, because it carries the tick's contact impulse, a tenth of a
     * metre a second or more. No flag on the wire is needed.
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
     * The universe time the predicted craft is drawn at: the replica's last step plus the part of a
     * step since then. Everything else in the frame (above all the planet's rotation) has to be
     * drawn at this same time, or the ground and the craft on it disagree by 175 m/s times the
     * difference.
     */
    fun renderTime(): Double? {
        val replica = world ?: return null
        return replica.time + accumulator
    }

    /**
     * Where to draw the craft: the prediction carried forward to [renderTime], plus the fading
     * correction.
     *
     * It's carried forward because the replica steps at 60 Hz and a display runs at 60 to 120.
     * Drawn at its last step, a craft parked on the equator moves in 2.9 m jumps while the ground
     * under it turns smoothly.
     */
    /**
     * Shifts where the craft is drawn by [delta] (inertial), to be eased away like any correction.
     * This keeps it where it was drawn when the replica is rebuilt, instead of snapping. It does
     * nothing if it's too far to hide.
     */
    fun carryOffset(delta: Vec3) {
        val replica = world ?: return
        val local = vessel ?: return
        system.body(local.referenceBodyId).rotationAt(replica.time + accumulator, scratchRotation)
        val held = scratchRotation.inverseRotate(delta, Vec3())
        if (held.addInPlace(renderOffset).length <= MAX_SMOOTHED_ERROR) renderOffset.setTo(held)
    }

    /**
     * Where to draw the craft, from the centre of body [bodyId]. That's the one the rest of the
     * frame is drawn around, which for a moment either side of passing into another's pull isn't
     * the one the replica has.
     */
    fun renderPosition(out: Vec3 = Vec3(), bodyId: String? = null): Vec3? {
        val local = vessel ?: return null
        val replica = world ?: return null
        out.setTo(local.body.position).addScaledInPlace(local.body.linearVelocity, accumulator)
        if (renderOffset.lengthSq != 0.0) {
            // The correction is held against the ground, so turn it with the ground.
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
     * Pieces the replica has dropped (a stage let go here a moment before the server hears about
     * it), with where each one is drawn, carried and corrected the way [renderPosition] carries the
     * replica. This only lasts until the server's own version of the split arrives. The replica
     * gets adopted afresh then, and these go with its old world.
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
        return out.setTo(local.body.orientation)
    }

    /**
     * The replica's moving parts, straight from its own tick, so control surfaces move on the same
     * frame the stick does instead of a snapshot later.
     */
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
        designHash = 0
        renderOffset.setZero()
    }

    private companion object {
        /**
         * A craft first seen beside ours only touches it gently for this long, in seconds. See
         * World's separation grace.
         */
        const val NEIGHBOUR_GRACE = 1.5
        /** A craft of the replica's own this near a neighbour, in metres, is taken to be it. */
        const val LOCAL_COPY_REACH = 12.0
        const val DT = 1.0 / 60.0

        /** Relative speed, in m/s, below which a snapshot can only be a sleeping craft. */
        const val ASLEEP_SPEED = 1e-4

        /** The same for spin, in rad/s. */
        const val ASLEEP_SPIN = 1e-9

        /** The most real time one advance can try to make up. */
        const val MAX_CATCHUP_SECONDS = 0.25

        /** A cap on the reconciliation catch-up, so a stalled connection can't spiral. */
        const val MAX_CATCHUP_TICKS = 30

        /** Per-frame fading of the smoothed correction. */
        const val OFFSET_DECAY = 0.85

        /**
         * Errors bigger than this get shown straight away.
         *
         * Smoothing a large error means drawing the craft somewhere it isn't for a noticeable time.
         * If prediction was wrong by more than a craft's length, the honest thing is to correct it
         * where you can see it.
         */
        const val MAX_SMOOTHED_ERROR = 25.0
    }
}
