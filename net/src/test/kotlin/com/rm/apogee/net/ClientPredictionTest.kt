package com.rm.apogee.net

import com.rm.apogee.core.craft.StockCraft
import com.rm.apogee.core.craft.VesselId
import com.rm.apogee.core.part.StockParts
import com.rm.apogee.core.world.Command
import com.rm.apogee.core.world.VesselKinematics
import com.rm.apogee.core.world.World
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
     * What the screen shows of a parked craft: where it sits on the planet,
     * drawn at 90 Hz from 20 Hz snapshots on a world whose clock is hours in.
     *
     * Three things used to disagree about the time. The replica started its
     * clock at zero, so its planet was turned somewhere else; the craft was
     * drawn at its last 60 Hz step; and the ground at the wall clock. At the
     * equator the surface moves 175 m/s, so each disagreement was metres of
     * craft sliding over ground - the shaking, and the plane hovering off its
     * runway. Now the frame has one time and the craft stays put on it.
     */
    @Test
    fun `a parked craft stays put on its ground when drawn between snapshots`() {
        // A millimetre: the server has it asleep, so the replica does too and
        // it rides the ground exactly. Awake, it bounced centimetres on its
        // legs between snapshots.
        val worst = onScreenDrift(keepAwake = false)
        assertTrue("the parked craft moved $worst m over its ground on screen", worst < 0.001)
    }

    /**
     * The same, with the craft awake - engine armed, say - and the server
     * ticking unevenly, so each snapshot's age is off by up to ten
     * milliseconds. The smoothing of corrections used to work inertially, and
     * read every slip of the clock as 175 m/s times the slip of error: the
     * craft held back metres while the ground moved on.
     */
    @Test
    fun `an awake craft on its pad stays on its ground when the server ticks unevenly`() {
        val worst = onScreenDrift(keepAwake = true)
        assertTrue("the craft was drawn $worst m from where the server has it on its ground", worst < 0.02)
    }

    /**
     * Worst distance, over three seconds at 90 Hz from 20 Hz snapshots, between
     * where a craft on the pad is drawn and where the server has it - measured
     * on the ground, which is what the eye compares it with. The server clock
     * wobbles by up to ten milliseconds against the client's. (Kept awake by
     * force, the server's craft never anchors and creeps a few centimetres a
     * second on its brakes; that is the test's doing, so the comparison is
     * with where it is, not where it was parked.)
     */
    private fun onScreenDrift(keepAwake: Boolean): Double {
        val world = World.default(catalog)
        world.syncClock(10_000.0)
        val rover = world.spawnOnSurface(StockCraft.rover(catalog), World.launchSites.first())
        rover.control.brakes = true
        // Long enough to settle and fall asleep, as a parked craft does.
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

    /**
     * Drawn between steps, the predicted craft is carried along its velocity:
     * at the equator a parked craft moves 175 m/s with the ground, and drawn
     * only at its 60 Hz steps it would jump 2.9 m at a time under a smoothly
     * turning planet.
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

        // Same commands on both sides.
        world.apply(Command.Stage(id.raw))
        world.apply(Command.SetThrottle(id.raw, 1.0))
        prediction.stage()
        prediction.applyControl(1.0, 0.0, 0.0, 0.0, sas = false)

        // Ten seconds of flight, no corrections at all. Ten rather than five
        // because a thrust-to-weight of 1.4 only nets about 50 m in the first
        // five seconds, which is not enough altitude to tell flying from
        // sitting still.
        //
        // Advanced a frame at a time rather than in one 5-second jump:
        // `advance` deliberately clamps how much real time a single call may
        // make up, so a long pause cannot hand the integrator a backlog it
        // spends longer catching up on than the backlog itself.
        repeat(600) { world.step(1.0 / 60.0) }
        repeat(600) { prediction.advance(1.0 / 60.0) }

        val authoritative = world.vessel(id)!!.body.position
        val predicted = prediction.renderPosition()!!
        val error = authoritative.distanceTo(predicted)

        // They are running the same deterministic code from the same state, so
        // any divergence here is a bug in the replica, not latency.
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
     * Reconciliation restores agreement; it cannot paper over disagreement.
     *
     * The replica is first starved of commands so it diverges badly, then
     * corrected *and* given the same controls the server has. That second part
     * is the realistic case and the important one: a snapshot alone cannot keep
     * a replica in step if it is still flying different inputs, and a test that
     * expected it to would be asserting something the design does not claim.
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

        // The replica never got the commands, so it is still sitting on the pad.
        repeat(600) { prediction.advance(1.0 / 60.0) }
        val before = prediction.renderPosition()!!.distanceTo(world.vessel(id)!!.body.position)
        assertTrue("the replica should have diverged badly first, was ${before}m", before > 150.0)

        // The correction, and the inputs that go with it.
        prediction.reconcile(kinematicsOf(world, id), ageSeconds = 0.0)
        prediction.stage()
        prediction.applyControl(1.0, 0.0, 0.0, 0.0, sas = false)

        // Fly on together; the smoothed offset bleeds off as they do.
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
    fun `a stale snapshot is caught up rather than rubber-banded`() {
        val (world, id) = server()
        val design = world.vessel(id)!!.design

        world.apply(Command.Stage(id.raw))
        world.apply(Command.SetThrottle(id.raw, 1.0))

        val prediction = ClientPrediction(catalog)
        prediction.adopt(design, kinematicsOf(world, id))
        prediction.stage()
        prediction.applyControl(1.0, 0.0, 0.0, 0.0, sas = false)

        // The server runs on; the client sees a snapshot from 100 ms ago.
        repeat(600) { world.step(1.0 / 60.0) }
        val stale = kinematicsOf(world, id)
        repeat(6) { world.step(1.0 / 60.0) }

        repeat(606) { prediction.advance(1.0 / 60.0) }
        prediction.reconcile(stale, ageSeconds = 0.1)

        // Applying a 100 ms old snapshot verbatim would put the craft behind
        // where the server already has it; catching up should land close.
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
}
