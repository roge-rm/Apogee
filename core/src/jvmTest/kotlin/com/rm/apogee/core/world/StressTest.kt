package com.rm.apogee.core.world

import com.rm.apogee.core.craft.StockCraft
import com.rm.apogee.core.craft.Vessel
import com.rm.apogee.core.math.Vec3
import com.rm.apogee.core.part.Engine
import com.rm.apogee.core.part.StockParts
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.sqrt

/** What the joints carry, and what gives. */
class StressTest {

    private val catalog = StockParts.catalog
    private val dt = 1.0 / 60.0

    /** The Starter I coasting well clear of Terra's air, nose out. */
    private fun inSpace(world: World, spin: Vec3 = Vec3.zero()): Vessel {
        val terra = world.system.body("terra")
        val position = Vec3(terra.radius + 400_000.0, 0.0, 0.0)
        val velocity = Vec3(0.0, 0.0, sqrt(terra.gravitationalParameter / position.x))
        val rotation = com.rm.apogee.core.math.quatFromTo(Vec3.unitY(), Vec3(1.0, 0.0, 0.0))
        return world.spawnAt(StockCraft.starterRocket(catalog), "terra", position, velocity, rotation, spin)
    }

    /** The joint above a burning engine carries its thrust, less what pushes the engine itself. */
    @Test
    fun `the joint above an engine carries its thrust less its own share`() {
        val world = World.default(catalog)
        val rocket = inSpace(world)
        world.stage(rocket)
        world.apply(Command.SetThrottle(rocket.id.raw, 1.0))
        world.step(dt)

        val engine = rocket.defs.indexOfFirst { it.id == "engine-ember" }
        val parent = rocket.design.parts[engine].parentIndex
        val thrust = rocket.defs[engine].module<Engine>()!!.thrustVacuum
        val expected = thrust * (1.0 - rocket.partMass(engine) / rocket.body.mass)
        val strength = minOf(rocket.defs[engine].jointStrength, rocket.defs[parent].jointStrength)
        val carried = rocket.jointLoad[engine] * strength
        assertEquals("the Ember's joint carries ${carried.toInt()} N", expected, carried, expected * 0.02)
    }

    /** Coasting, nothing pushes, and nothing is loaded. */
    @Test
    fun `a coasting craft carries nothing`() {
        val world = World.default(catalog)
        val rocket = inSpace(world)
        repeat(10) { world.step(dt) }
        assertTrue("stress ${rocket.stress}", rocket.stress < 1e-6)
    }

    /** A slow tumble is nothing; a fast one flies the craft apart into pieces. */
    @Test
    fun `a craft spun too fast flies apart`() {
        fun spun(rate: Double): Pair<Int, Double> {
            val world = World.default(catalog)
            val rocket = inSpace(world, Vec3(0.0, 0.0, rate))
            var peak = 0.0
            repeat((2.0 / dt).toInt()) {
                world.step(dt)
                peak = maxOf(peak, world.vessel(rocket.id)?.stress ?: 0.0)
            }
            return world.vessels.size to peak
        }
        val (slowPieces, slowPeak) = spun(1.0)
        assertEquals("at 1 rad/s it stays whole (peak $slowPeak)", 1, slowPieces)
        assertTrue("and well within its strength", slowPeak < 0.5)
        val (fastPieces, _) = spun(8.0)
        assertTrue("at 8 rad/s it comes apart, into $fastPieces pieces", fastPieces > 1)
    }

    /** A wing pulled past its limit snaps off, and flies on as a piece of its own. */
    @Test
    fun `an overstressed wing snaps off as debris`() {
        val world = World.default(catalog)
        val design = StockCraft.sparrow(catalog)
        val terra = world.system.body("terra")
        val up = Vec3(1.0, 0.0, 0.0)
        val position = up.copy().mulInPlace(terra.radius + 1_500.0)
        val rotation = com.rm.apogee.core.math.quatFromTo(Vec3.unitZ(), up)
        val nose = rotation.rotate(Vec3.unitY(), Vec3())
        val velocity = terra.surfaceVelocityAt(position, Vec3()).addScaledInPlace(nose, 260.0)
        val jet = world.spawnAt(design, "terra", position, velocity, rotation)
        val partsBefore = jet.design.parts.size
        world.apply(Command.SetAttitude(jet.id.raw, 1.0, 0.0, 0.0))
        val detached = ArrayList<WorldEvent.PartDetached>()
        repeat((6.0 / dt).toInt()) {
            world.step(dt)
            detached += world.drainEvents().filterIsInstance<WorldEvent.PartDetached>()
        }
        assertTrue("a full pull at 260 m/s should snap something", detached.isNotEmpty())
        assertTrue("the jet is smaller", world.vessel(jet.id)!!.design.parts.size < partsBefore)
        assertTrue("and what snapped is flying on its own", world.vessels.size > 1)
    }
}
