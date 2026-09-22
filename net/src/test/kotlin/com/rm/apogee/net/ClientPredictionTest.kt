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
