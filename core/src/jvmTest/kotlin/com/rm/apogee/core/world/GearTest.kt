package com.rm.apogee.core.world

import com.rm.apogee.core.craft.CraftBuilder
import com.rm.apogee.core.craft.StockCraft
import com.rm.apogee.core.craft.Vessel
import com.rm.apogee.core.math.Vec3
import com.rm.apogee.core.part.LandingLeg
import com.rm.apogee.core.part.StockParts
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** The gear switch: plane wheels and legs fold up and come down with it. */
class GearTest {
    private val catalog = StockParts.catalog
    private val dt = Aloft.DT

    private fun gear(vessel: Vessel) = vessel.defs.indices.filter { vessel.defs[it].fold != null }

    @Test
    fun `a plane's wheels fold up in flight, and come down again`() {
        val world = World.default(catalog)
        val plane = Aloft.spawn(world, StockCraft.sparrow(catalog), 2_000.0)
        val wheels = gear(plane)
        assertEquals(3, wheels.size)
        for (i in wheels) assertEquals("down to start", 1.0, plane.legDeploy[i], 1e-9)
        val time = plane.defs[wheels.first()].fold!!.time
        world.apply(Command.SetGear(plane.id.raw, false))
        Aloft.run(world, time / 2.0)
        for (i in wheels) assertEquals("half way", 0.5, plane.legDeploy[i], 0.05)
        Aloft.run(world, time * 0.6)
        for (i in wheels) assertEquals("up", 0.0, plane.legDeploy[i], 1e-9)
        assertFalse(world.systemsOf(plane).gear)
        assertTrue(world.systemsOf(plane).gearFolds)
        world.apply(Command.SetGear(plane.id.raw, true))
        Aloft.run(world, time * 1.1)
        for (i in wheels) assertEquals("down", 1.0, plane.legDeploy[i], 1e-9)
    }

    @Test
    fun `gear up is less drag`() {
        fun speedLost(down: Boolean): Double {
            val world = World.default(catalog)
            val plane = Aloft.spawn(world, StockCraft.sparrow(catalog), 2_000.0)
            plane.control.gear = down
            for (i in gear(plane)) plane.setLegDeploy(i, if (down) 1.0 else 0.0)
            val forward = plane.forward()
            plane.body.linearVelocity.addScaledInPlace(forward, 90.0)
            val before = plane.body.linearVelocity.dot(forward)
            Aloft.run(world, 2.0)
            return before - plane.body.linearVelocity.dot(forward)
        }
        val down = speedLost(true)
        val up = speedLost(false)
        assertTrue("down lost $down m/s, up $up m/s", up < down)
    }

    @Test
    fun `on the ground with its gear up, a plane sits on its belly`() {
        fun height(down: Boolean): Double {
            val world = World.default(catalog)
            val plane = world.spawnOnSurface(StockCraft.sparrow(catalog), World.launchSites.first { it.id == "airfield" })
            plane.control.gear = down
            for (i in gear(plane)) plane.setLegDeploy(i, if (down) 1.0 else 0.0)
            repeat(240) { world.step(dt) }
            val attractor = world.attractorFor(plane)
            val up = Vec3().setTo(plane.body.position).normalizeInPlace()
            return plane.body.position.length - attractor.surfaceRadiusInBodyFrame(attractor.toBodyFixed(up, attractor.rotationAt(world.time)))
        }
        val wheels = height(true)
        val belly = height(false)
        assertTrue("on its wheels at $wheels m, on its belly at $belly m", wheels - belly > 0.5)
    }

    @Test
    fun `climbing away from the ground, a plane's gear folds up by itself`() {
        val world = World.default(catalog)
        val plane = world.spawnOnSurface(StockCraft.sparrow(catalog), World.launchSites.first { it.id == "airfield" })
        repeat(60) { world.step(dt) }
        assertTrue(plane.control.gear && plane.gearRaiseArmed)
        val up = Vec3().setTo(plane.body.position).normalizeInPlace()
        // Lifted clear and climbing.
        plane.body.position.addScaledInPlace(up, 60.0)
        plane.body.linearVelocity.addScaledInPlace(up, 15.0)
        repeat(30) { world.step(dt) }
        assertTrue("folded too soon", plane.control.gear)
        plane.body.position.addScaledInPlace(up, 80.0)
        repeat(30) { world.step(dt) }
        assertFalse("still down past ${World.GEAR_UP} m", plane.control.gear)
        // Put down again by hand, it stays down.
        world.apply(Command.SetGear(plane.id.raw, true))
        repeat(30) { world.step(dt) }
        assertTrue(plane.control.gear)
    }

    @Test
    fun `legs aren't staged, they're gear`() {
        val lander = StockCraft.lander(catalog)
        for (design in listOf(lander, StockCraft.prospector(catalog))) {
            val legs = design.parts.indices.filter { catalog[design.parts[it].partId]?.module<LandingLeg>() != null }
            assertTrue(legs.isNotEmpty())
            for (stage in design.stages + CraftBuilder.autoStage(design, catalog)) assertTrue(stage.activatedParts.none { it in legs })
        }
        // And come out with the gear.
        val world = World.default(catalog)
        val probe = Aloft.spawn(world, lander, 500.0, bodyId = "luna")
        world.apply(Command.SetGear(probe.id.raw, false))
        Aloft.run(world, 2.0)
        for (i in gear(probe)) assertEquals(0.0, probe.legDeploy[i], 1e-9)
        world.apply(Command.SetGear(probe.id.raw, true))
        Aloft.run(world, 2.0)
        for (i in gear(probe)) assertEquals(1.0, probe.legDeploy[i], 1e-9)
    }

    @Test
    fun `a parked plane folds its gear when told to`() {
        val world = World.default(catalog)
        val plane = world.spawnOnSurface(StockCraft.sparrow(catalog), World.launchSites.first { it.id == "airfield" }, legsOut = true)
        world.assignOwner(plane, "p1")
        repeat(900) { world.step(dt) }
        assertTrue("asleep", plane.dormant)
        world.apply(Command.SetGear(plane.id.raw, false))
        repeat(180) { world.step(dt) }
        for (i in gear(plane)) assertEquals("folded", 0.0, plane.legDeploy[i], 1e-9)
    }
}
