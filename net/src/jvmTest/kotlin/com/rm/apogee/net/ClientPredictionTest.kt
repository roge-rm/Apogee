package com.rm.apogee.net

import com.rm.apogee.core.craft.StockCraft
import com.rm.apogee.core.craft.VesselId
import com.rm.apogee.core.part.StockParts
import com.rm.apogee.core.world.Command
import com.rm.apogee.core.world.VesselKinematics
import com.rm.apogee.core.world.World
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ClientPredictionTest {

    private val catalog = StockParts.catalog

    /** A server-side world with one craft on the pad, already throttled up. */
    private fun server(): Pair<World, VesselId> {
        val world = World.default(catalog)
        val vessel = world.spawnOnSurface(
            StockCraft.starterRocket(catalog),
            World.launchSites.first(),
        )
        return world to vessel.id
    }

    private fun kinematicsOf(world: World, id: VesselId): VesselKinematics {
        val vessel = world.vessel(id)!!
        return VesselKinematics(
            vessel = id.raw,
            referenceBodyId = vessel.referenceBodyId,
            position = vessel.body.position.copy(),
            rotation = vessel.body.orientation.copy(),
            velocity = vessel.body.linearVelocity.copy(),
            angularVelocity = vessel.body.angularVelocity.copy(),
            throttle = vessel.control.throttle,
        )
    }

    /**
     * What the screen shows of a parked craft: where it sits on the planet, drawn at 90 Hz from 20
     * Hz snapshots on a world whose clock is hours in.
     *
     * Three things used to disagree about the time. The replica started its clock at zero, so its
     * planet was turned somewhere else, the craft was drawn at its last 60 Hz step, and the ground
     * at the wall clock. At the equator the surface moves at 175 m/s, so each disagreement was
     * metres of craft sliding over ground. That was the shaking, and the plane hovering off its
     * runway. Now the frame has one time and the craft stays put on it.
     */
    @Test
    fun `a parked craft stays put on its ground when drawn between snapshots`() {
        // A millimetre. The server has it asleep, so the replica does too and it rides the ground
        // exactly. Awake, it bounced centimetres on its legs between snapshots.
        val worst = onScreenDrift(keepAwake = false)
        assertTrue("the parked craft moved $worst m over its ground on screen", worst < 0.001)
    }

    /**
     * The same, with the craft awake (engine armed, say) and the server ticking unevenly, so each
     * snapshot's age is off by up to ten milliseconds. The smoothing of corrections used to work
     * inertially, and read every slip of the clock as 175 m/s times the slip of error, so the craft
     * was held back metres while the ground moved on.
     */
    @Test
    fun `an awake craft on its pad stays on its ground when the server ticks unevenly`() {
        val worst = onScreenDrift(keepAwake = true)
        assertTrue("the craft was drawn $worst m from where the server has it on its ground", worst < 0.02)
    }

    /**
     * The worst distance, over three seconds at 90 Hz from 20 Hz snapshots, between where a craft
     * on the pad is drawn and where the server has it, measured on the ground, because that's what
     * the eye compares it with. The server clock wobbles by up to ten milliseconds against the
     * client's. (Kept awake by force, the server's craft never anchors and creeps a few centimetres
     * a second on its brakes. That's the test's doing, so the comparison is with where it is, not
     * where it was parked.)
     */
    private fun onScreenDrift(keepAwake: Boolean): Double {
        val world = World.default(catalog)
        world.syncClock(10_000.0)
        val rover = world.spawnOnSurface(StockCraft.rover(catalog), World.launchSites.first())
        rover.control.brakes = true
        // Long enough to settle and fall asleep, the way a parked craft does.
        repeat(600) { world.step(1.0 / 60.0) }
        assertTrue("the server's rover should be asleep by now", rover.dormant)
        val terra = world.system.body("terra")
        fun onGround(position: com.rm.apogee.core.math.Vec3, time: Double) =
            terra.toBodyFixed(position, terra.rotationAt(time, com.rm.apogee.core.math.Quat.identity()))
        val start = world.time
        val prediction = ClientPrediction(catalog)
        prediction.adopt(rover.design, kinematicsOf(world, rover.id), world.time)
        prediction.applyControl(0.0, 0.0, 0.0, 0.0, sas = false, brakes = true)
        var snapshot = kinematicsOf(world, rover.id) to world.time
        var reconciled = snapshot.second
        var worst = 0.0
        for (frame in 1..270) {
            val now = start + frame / 90.0
            // The server runs in real time and sends every third tick.
            while (world.time + 1.0 / 60.0 <= now + 1e-9) {
                if (keepAwake) rover.wake()
                world.step(1.0 / 60.0)
                if (world.tick % 3 == 0L) snapshot = kinematicsOf(world, rover.id) to world.time
            }
            prediction.advance(1.0 / 90.0)
            if (snapshot.second != reconciled) {
                reconciled = snapshot.second
                val slip = 0.01 * kotlin.math.sin(frame * 1.7)
                prediction.reconcile(snapshot.first, now - snapshot.second + slip, snapshot.second)
            }
            val drawn = onGround(prediction.renderPosition()!!, prediction.renderTime()!!)
            worst = maxOf(worst, drawn.distanceTo(onGround(rover.body.position, world.time)))
        }
        return worst
    }

    /** A Jet Ski left at sea off the Cape until she's asleep, riding the swell. */
    private fun asleepAtSea(): Pair<World, VesselId> {
        val world = World.default(catalog)
        world.weatherConfig = com.rm.apogee.core.weather.WeatherConfig(intensity = com.rm.apogee.core.weather.WeatherIntensity.CALM)
        world.syncClock(10_000.0)
        val d = com.rm.apogee.core.orbit.SolarSystem.capeDirection(-3_000.0, 2_000.0)
        val ski = world.spawnOnSurface(
            StockCraft.jetSki(catalog),
            com.rm.apogee.core.world.LaunchSite("sea", "Sea", "terra", com.rm.apogee.core.orbit.SolarSystem.latitudeOf(d), com.rm.apogee.core.orbit.SolarSystem.longitudeOf(d)),
        )
        repeat(1200) { world.step(1.0 / 60.0) }
        assertTrue("the Jet Ski should be asleep on the water by now", ski.dormant && ski.buoyed)
        return world to ski.id
    }

    private fun snapshotOf(world: World, id: VesselId): VesselKinematics = world.snapshot().vessels.first { it.vessel == id.raw }

    /**
     * Asleep afloat on the server, the replica isn't stepped, only carried on from each snapshot,
     * and it's still drawn where the server has it as she rides the waves.
     */
    @Test
    fun `a craft asleep at sea is drawn riding the waves without being simulated`() {
        val (world, id) = asleepAtSea()
        val ski = world.vessel(id)!!
        val prediction = ClientPrediction(catalog)
        val first = snapshotOf(world, id)
        assertTrue("the snapshot should say she's asleep", first.asleep)
        prediction.adopt(ski.design, first, world.time)
        prediction.reconcile(first, 0.0, world.time)
        var snapshot = first to world.time
        var reconciled = snapshot.second
        val start = world.time
        var worst = 0.0
        for (frame in 1..270) {
            val now = start + frame / 90.0
            while (world.time + 1.0 / 60.0 <= now + 1e-9) {
                world.step(1.0 / 60.0)
                if (world.tick % 3 == 0L) snapshot = snapshotOf(world, id) to world.time
            }
            prediction.advance(1.0 / 90.0)
            if (snapshot.second != reconciled) {
                reconciled = snapshot.second
                prediction.reconcile(snapshot.first, now - snapshot.second, snapshot.second)
            }
            // On the ground, each at its own time, since that's what the eye compares.
            val terra = world.system.body("terra")
            val drawn = terra.toBodyFixed(prediction.renderPosition()!!, terra.rotationAt(prediction.renderTime()!!, com.rm.apogee.core.math.Quat.identity()))
            val there = terra.toBodyFixed(ski.body.position, terra.rotationAt(world.time, com.rm.apogee.core.math.Quat.identity()))
            worst = maxOf(worst, drawn.distanceTo(there))
        }
        assertTrue("drawn $worst m from where the server has her", worst < 0.05)
        assertTrue("the replica was stepped while she slept", prediction.stepsTaken == 0L)
    }

    /** Opening the throttle on a craft that's asleep afloat gets the replica stepping straight away. */
    @Test
    fun `taking the controls of a craft asleep at sea wakes the replica at once`() {
        val (world, id) = asleepAtSea()
        val ski = world.vessel(id)!!
        val prediction = ClientPrediction(catalog)
        val first = snapshotOf(world, id)
        prediction.adopt(ski.design, first, world.time)
        prediction.reconcile(first, 0.0, world.time)
        prediction.advance(0.5)
        val parkedAt = prediction.renderPosition()!!.copy()
        prediction.stage()
        prediction.applyControl(1.0, 0.0, 0.0, 0.0, sas = false)
        repeat(120) { prediction.advance(1.0 / 60.0) }
        assertTrue("the replica never stepped", prediction.stepsTaken > 0L)
        val moved = prediction.renderPosition()!!.distanceTo(parkedAt)
        assertTrue("she only moved $moved m in two seconds at full throttle", moved > 2.0)
    }

    /**
     * Drawn between steps, the predicted craft is carried along its velocity. At the equator a
     * parked craft moves at 175 m/s with the ground, and drawn only at its 60 Hz steps it would
     * jump 2.9 m at a time under a smoothly turning planet.
     */
    @Test
    fun `between steps the craft is drawn where it is, not where it last stepped`() {
        val (world, id) = server()
        val prediction = ClientPrediction(catalog)
        prediction.adopt(world.vessel(id)!!.design, kinematicsOf(world, id), world.time)
        val start = prediction.renderPosition()!!.copy()
        val velocity = world.vessel(id)!!.body.linearVelocity.copy()
        prediction.advance(0.008)
        val half = prediction.renderPosition()!!
        val expected = start.copy().addScaledInPlace(velocity, 0.008)
        assertTrue("drawn ${half.distanceTo(expected)} m from where it should be", half.distanceTo(expected) < 0.01)
        assertTrue(kotlin.math.abs(prediction.renderTime()!! - (world.time + 0.008)) < 1e-9)
    }

    @Test
    fun `prediction reproduces the server when given the same inputs`() {
        val (world, id) = server()
        val design = world.vessel(id)!!.design

        val prediction = ClientPrediction(catalog)
        prediction.adopt(design, kinematicsOf(world, id))

        // The same commands on both sides.
        world.apply(Command.Stage(id.raw))
        world.apply(Command.SetThrottle(id.raw, 1.0))
        prediction.stage()
        prediction.applyControl(1.0, 0.0, 0.0, 0.0, sas = false)

        // Ten seconds of flight, with no corrections at all. It's ten instead of five because a
        // thrust-to-weight of 1.4 only nets about 50 m in the first five seconds, which isn't
        // enough altitude to tell flying from sitting still.
        //
        // It's moved on a frame at a time instead of in one 5-second jump. `advance` clamps how
        // much real time a single call can make up, on purpose, so a long pause can't hand the
        // integrator a backlog it spends longer catching up on than the backlog itself.
        repeat(600) { world.step(1.0 / 60.0) }
        repeat(600) { prediction.advance(1.0 / 60.0) }

        val authoritative = world.vessel(id)!!.body.position
        val predicted = prediction.renderPosition()!!
        val error = authoritative.distanceTo(predicted)

        // They're running the same deterministic code from the same state, so any divergence here
        // is a bug in the replica, not latency.
        assertTrue(
            "prediction drifted ${error}m from the server over 10s of identical input",
            error < 1.0,
        )
        assertTrue(
            "and it should actually have flown",
            authoritative.length > world.system.body("terra").radius + 150.0,
        )
    }

    /**
     * Reconciling brings back agreement, but it can't paper over disagreement.
     *
     * The replica is first starved of commands so it drifts badly, then corrected *and* given the
     * same controls the server has. That second part is the realistic case and the important one. A
     * snapshot alone can't keep a replica in step if it's still flying different inputs, and a test
     * that expected it to would be claiming something the design doesn't claim.
     */
    @Test
    fun `reconciling resynchronises a diverged replica`() {
        val (world, id) = server()
        val design = world.vessel(id)!!.design

        val prediction = ClientPrediction(catalog)
        prediction.adopt(design, kinematicsOf(world, id))

        world.apply(Command.Stage(id.raw))
        world.apply(Command.SetThrottle(id.raw, 1.0))
        repeat(600) { world.step(1.0 / 60.0) }

        // The replica never got the commands, so it's still sitting on the pad.
        repeat(600) { prediction.advance(1.0 / 60.0) }
        val before = prediction.renderPosition()!!.distanceTo(world.vessel(id)!!.body.position)
        assertTrue("the replica should have diverged badly first, was ${before}m", before > 100.0)

        // The correction, and the inputs that go with it.
        prediction.reconcile(kinematicsOf(world, id), ageSeconds = 0.0)
        prediction.stage()
        prediction.applyControl(1.0, 0.0, 0.0, 0.0, sas = false)

        // Fly on together, and the smoothed offset bleeds off as they do.
        repeat(120) {
            world.step(1.0 / 60.0)
            prediction.advance(1.0 / 60.0)
        }

        val after = prediction.renderPosition()!!.distanceTo(world.vessel(id)!!.body.position)
        assertTrue(
            "reconciliation should bring the replica back into step " +
                "(${before}m -> ${after}m)",
            after < 5.0,
        )
    }

    @Test
    fun `a stale snapshot is caught up instead of rubber-banded`() {
        val (world, id) = server()
        val design = world.vessel(id)!!.design

        world.apply(Command.Stage(id.raw))
        world.apply(Command.SetThrottle(id.raw, 1.0))

        val prediction = ClientPrediction(catalog)
        prediction.adopt(design, kinematicsOf(world, id))
        prediction.stage()
        prediction.applyControl(1.0, 0.0, 0.0, 0.0, sas = false)

        // The server runs on, and the client sees a snapshot from 100 ms ago.
        repeat(600) { world.step(1.0 / 60.0) }
        val stale = kinematicsOf(world, id)
        repeat(6) { world.step(1.0 / 60.0) }

        repeat(606) { prediction.advance(1.0 / 60.0) }
        prediction.reconcile(stale, ageSeconds = 0.1)

        // Applying a 100 ms old snapshot as it is would put the craft behind where the server
        // already has it. Catching up should land close.
        val error = prediction.renderPosition()!!.distanceTo(world.vessel(id)!!.body.position)
        assertTrue("caught-up state was ${error}m off", error < 5.0)
    }

    @Test
    fun `a replica is rebuilt when the craft's structure changes`() {
        val (world, id) = server()
        val design = world.vessel(id)!!.design

        val prediction = ClientPrediction(catalog)
        assertTrue("nothing adopted yet", prediction.needsAdopting(design))

        prediction.adopt(design, kinematicsOf(world, id))
        assertTrue("same design needs no rebuild", !prediction.needsAdopting(design))

        // Separation leaves a different craft behind.
        val shorter = design.copy(parts = design.parts.take(4))
        assertTrue("a changed design must rebuild", prediction.needsAdopting(shorter))
    }

    /**
     * A replica is rebuilt unstaged and full. Told the server's staging and tanks, it matches them,
     * and a stage fired locally a moment before the server's word arrives isn't undone by the older
     * word.
     */
    @Test
    fun `the replica takes the server's staging and fuel`() {
        val (world, id) = server()
        val craft = world.vessel(id)!!
        world.stage(craft)
        craft.control.throttle = 1.0
        repeat(300) { world.step(1.0 / 60.0) }

        val prediction = ClientPrediction(catalog)
        prediction.adopt(craft.design, kinematicsOf(world, id), world.time)
        val lit = craft.design.parts.indices.filter { craft.isActivated(it) }
        prediction.sync(craft.currentStage, lit, craft.flatResources())

        val replica = prediction.replica!!
        org.junit.Assert.assertEquals(craft.currentStage, replica.currentStage)
        assertTrue(lit.all { replica.isActivated(it) })
        org.junit.Assert.assertEquals(
            craft.flatResources().sum().toDouble(), replica.flatResources().sum().toDouble(), 1e-3,
        )

        prediction.stage()
        val ahead = replica.currentStage
        prediction.sync(craft.currentStage, lit, craft.flatResources())
        org.junit.Assert.assertEquals("a local stage is not undone by older news", ahead, replica.currentStage)
    }

    /**
     * Rebuilt after a pause or a change of warp, the replica used to start with its chute packed
     * and fill it all over again, falling for a second with almost no drag while the server's craft
     * hung under a full canopy.
     */
    @Test
    fun `a replica built under an open chute has it open`() {
        val world = World.default(catalog)
        val full = StockCraft.starterRocket(catalog)
        val pod = com.rm.apogee.core.craft.CraftDesign(
            full.name, listOf(0, 1, 2).map { full.parts[it] },
            stages = listOf(com.rm.apogee.core.craft.Stage(listOf(1))),
        )
        val terra = world.system.body("terra")
        val up = com.rm.apogee.core.math.Vec3(1.0, 0.0, 0.0)
        val position = up.copy().mulInPlace(terra.radius + 3_000.0)
        val velocity = terra.surfaceVelocityAt(position, com.rm.apogee.core.math.Vec3()).addScaledInPlace(up, -60.0)
        val vessel = world.spawnAt(pod, "terra", position, velocity, com.rm.apogee.core.math.quatFromTo(com.rm.apogee.core.math.Vec3.unitY(), up))
        world.stage(vessel)
        repeat(60 * 5) { world.step(1.0 / 60.0) }
        val chute = vessel.defs.indexOfFirst { it.module<com.rm.apogee.core.part.Parachute>() != null }
        val server = vessel.legDeploy[chute]
        assertTrue("open on the server", server > 0.4)

        val state = kinematicsOf(world, vessel.id).copy(pose = com.rm.apogee.core.world.VesselPose.encode(vessel))
        val prediction = ClientPrediction(catalog)
        prediction.adopt(pod, state, world.time)
        prediction.sync(vessel.currentStage, vessel.design.parts.indices.filter { vessel.isActivated(it) }, null)
        val replica = prediction.replica!!
        assertEquals("open in the replica from the start", server, replica.legDeploy[chute], 0.01)
        // And a drogue held high up stays held. Stepped on, the replica mustn't take the wire's
        // rounding for the main starting to fill.
        repeat(60) { prediction.advance(1.0 / 60.0) }
        assertEquals("still the drogue", com.rm.apogee.core.part.Parachute.DROGUE_FULL, replica.legDeploy[chute], 1e-9)
    }

    /**
     * Staged with the stage below still burning, the server has that stage shove the craft above
     * along. A replica that knows the stage is there shoves it too. One that didn't got dragged
     * back to the server's answer on every snapshot, metres at a time.
     */
    @Test
    fun `a burning stage below pushes the replica as it pushes the server's craft`() {
        fun run(withNeighbours: Boolean): Double {
            val world = World.default(catalog)
            val rocket = world.spawnInOrbit(
                StockCraft.starterRocket(catalog), "terra",
                com.rm.apogee.core.orbit.Orbit.circular(700_000.0, 3.5316000e12),
            )
            rocket.body.angularVelocity.setZero()
            world.apply(com.rm.apogee.core.world.Command.SetThrottle(rocket.id.raw, 1.0))
            world.stage(rocket)
            repeat(30) { world.step(1.0 / 60.0) }
            val before = world.vessels.map { it.id }.toSet()
            world.stage(rocket)
            world.apply(com.rm.apogee.core.world.Command.SetThrottle(rocket.id.raw, 0.0))
            val lower = world.vessels.first { it.id !in before }
            fun neighbours() = if (!withNeighbours) emptyList() else listOf(
                ClientPrediction.Neighbour(lower.id.raw, lower.design, kinematicsOf(world, lower.id), world.time, lower.currentStage, lower.activatedIndices()),
            )
            val prediction = ClientPrediction(catalog)
            prediction.adopt(rocket.design, kinematicsOf(world, rocket.id), world.time)
            prediction.sync(rocket.currentStage, rocket.activatedIndices(), rocket.flatResources())
            // A second of snapshots at 20 Hz: the replica stepped between them, then set right, and
            // how far it had gone wrong each time.
            var worst = 0.0
            repeat(20) {
                prediction.reconcile(kinematicsOf(world, rocket.id), 0.0, world.time, neighbours())
                prediction.advance(3.0 / 60.0)
                repeat(3) { world.step(1.0 / 60.0) }
                // Both a snapshot on, so this is where the replica got to on its own.
                val predicted = prediction.renderPosition(com.rm.apogee.core.math.Vec3())!!
                worst = maxOf(worst, predicted.distanceTo(rocket.body.position))
            }
            return worst
        }
        val without = run(false)
        val with = run(true)
        println("worst replica error over a second after a hot staging: without neighbours ${"%.2f".format(without)} m, with ${"%.2f".format(with)} m")
        assertTrue("the replica feels the push ($with m vs $without m)", with < without * 0.5)
    }
}
