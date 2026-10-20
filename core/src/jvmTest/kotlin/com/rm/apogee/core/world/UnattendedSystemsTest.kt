package com.rm.apogee.core.world

import com.rm.apogee.core.craft.CraftDesign
import com.rm.apogee.core.craft.PlacedPart
import com.rm.apogee.core.craft.Stage
import com.rm.apogee.core.craft.StockCraft
import com.rm.apogee.core.craft.Vessel
import com.rm.apogee.core.math.Quat
import com.rm.apogee.core.math.Vec3
import com.rm.apogee.core.math.quatFromTo
import com.rm.apogee.core.orbit.Orbit
import com.rm.apogee.core.part.ResourceType
import com.rm.apogee.core.part.StockParts
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Unattended does not mean engines stop burning or buoyancy stops working. */
class UnattendedSystemsTest {
    private val catalog = StockParts.catalog
    private val dt = 1.0 / 60.0

    private fun observer(world: World): Vessel {
        val body = world.system.body("terra")
        val design = CraftDesign("Observer", listOf(PlacedPart("pod-halo", Vec3.zero())))
        return world.spawnInOrbit(design, body.id, Orbit.circular(body.radius + 250_000.0, body.gravitationalParameter))
    }

    private fun solid(debris: Boolean = false): Pair<World, Vessel> {
        val world = World.default(catalog)
        val design = if (debris) CraftDesign("Lit Flint", listOf(PlacedPart("srb-flint", Vec3.zero())), listOf(Stage(listOf(0))))
            else StockCraft.sparkler(catalog)
        val position = Vec3(800_000.0, 0.0, 0.0)
        val body = world.system.body("terra")
        val craft = world.spawnAt(design, body.id, position, body.surfaceVelocityAt(position, Vec3()),
            quatFromTo(Vec3.unitY(), position.normalized()))
        world.stage(craft)
        assertTrue(craft.solidLit)
        return world to craft
    }

    private fun fuel(craft: Vessel): Double = craft.defs.indices.sumOf { craft.amountInPart(it, ResourceType.SOLID_FUEL) }

    private fun underwater(): Pair<World, Vessel> {
        val world = World.default(catalog)
        val design = StockCraft.nautilus(catalog)
        val craft = world.spawnOnSurface(design, World.launchSiteFor(design, catalog))
        repeat(1_200) { world.step(dt) }
        craft.wake()
        craft.control.holdDepth = false
        craft.control.keeping = false
        val up = craft.body.position.normalized()
        craft.body.position.addScaledInPlace(up, -50.0)
        world.attractorFor(craft).surfaceVelocityAt(craft.body.position, craft.body.linearVelocity)
        return world to craft
    }

    private fun movingBoat(): Pair<World, Vessel> {
        val world = World.default(catalog)
        val design = StockCraft.boat(catalog)
        val craft = world.spawnOnSurface(design, World.launchSiteFor(design, catalog))
        repeat(1_200) { world.step(dt) }
        craft.wake()
        val across = Vec3.unitY().crossInPlace(craft.body.position.normalized()).normalizeInPlace()
        world.attractorFor(craft).surfaceVelocityAt(craft.body.position, craft.body.linearVelocity).addScaledInPlace(across, 20.0)
        return world to craft
    }

    @Test
    fun `a lit solid keeps burning when its human pilot leaves`() {
        val (reference, piloted) = solid()
        val (world, unattended) = solid()
        val before = fuel(unattended)
        reference.setHumanPilots(setOf(piloted.id.raw))
        world.setHumanPilots(emptySet())
        repeat(60) { reference.step(dt); world.step(dt) }
        assertTrue("solid propellant consumed", fuel(unattended) < before)
        assertEquals(fuel(piloted), fuel(unattended), 1e-8)
        assertEquals(piloted.body.mass, unattended.body.mass, 1e-8)
        assertTrue("thrust is still applied", piloted.body.linearVelocity.approxEquals(unattended.body.linearVelocity, 1e-8))
    }

    @Test
    fun `unattended solids burn and thrust during warp without holding it down`() {
        for (debris in listOf(false, true)) {
            val (reference, piloted) = solid(debris)
            val (world, unattended) = solid(debris)
            reference.setHumanPilots(setOf(piloted.id.raw))
            world.setHumanPilots(setOf(observer(world).id.raw))
            val before = fuel(unattended)
            assertEquals(100.0, world.maxWarp(), 0.0)
            repeat(60) { reference.step(dt) }
            assertEquals(1.0, world.advanceOnRails(1.0), 1e-8)
            assertTrue("the burning stage survives, debris=$debris", world.vessels.contains(unattended))
            assertTrue("solid propellant consumed, debris=$debris", fuel(unattended) < before)
            assertEquals(fuel(piloted), fuel(unattended), 1e-6)
            assertTrue(piloted.body.linearVelocity.approxEquals(unattended.body.linearVelocity, 1e-5))
            assertEquals(100.0, world.maxWarp(), 0.0)
        }
    }

    @Test
    fun `a solid finishes burning and coasts through the rest of a warp`() {
        val (world, craft) = solid(debris = true)
        world.setHumanPilots(setOf(observer(world).id.raw))
        assertEquals(60.0, world.advanceOnRails(60.0), 1e-8)
        assertEquals(60.0, world.time, 1e-8)
        assertTrue(world.vessels.contains(craft))
        assertEquals(0.0, fuel(craft), 1e-8)
        assertFalse(craft.solidLit)
        val before = craft.body.position.copy()
        assertEquals(5.0, world.advanceOnRails(5.0), 1e-8)
        assertTrue("burnt-out motor still coasts", craft.body.position.distanceTo(before) > 100.0)
        assertEquals(100.0, world.maxWarp(), 0.0)
    }

    @Test
    fun `an unattended submerged Nautilus is not declared sunk on the next tick`() {
        val (reference, piloted) = underwater()
        val (world, unattended) = underwater()
        reference.setHumanPilots(setOf(piloted.id.raw))
        world.setHumanPilots(emptySet())
        reference.step(dt)
        world.step(dt)
        assertTrue(world.vessels.contains(unattended))
        assertTrue("same buoyancy as the piloted sub", piloted.body.linearVelocity.approxEquals(unattended.body.linearVelocity, 1e-8))
        assertFalse("not pinned underwater", unattended.dormant)
    }

    @Test
    fun `a submerged unattended craft keeps water physics during warp`() {
        val (reference, piloted) = underwater()
        val (world, unattended) = underwater()
        reference.setHumanPilots(setOf(piloted.id.raw))
        val watching = observer(world)
        world.setHumanPilots(setOf(watching.id.raw))
        val clock = world.time
        val expectedObserver = world.orbitOf(watching).propagate(5.0)
        val before = unattended.body.position.copy()
        repeat(300) { reference.step(dt) }
        assertEquals(5.0, world.advanceOnRails(5.0), 1e-8)
        assertTrue(world.vessels.contains(unattended))
        assertEquals(clock + 5.0, world.time, 1e-8)
        assertTrue("water still moves the sub", unattended.body.position.distanceTo(before) > 0.1)
        assertTrue("buoyancy and drag agree", piloted.body.linearVelocity.approxEquals(unattended.body.linearVelocity, 0.01))
        assertTrue(piloted.body.position.approxEquals(unattended.body.position, 0.01))
        assertTrue("the orbiter advances once", watching.body.position.approxEquals(expectedObserver.position, 0.01))
        assertEquals(100.0, world.maxWarp(), 0.0)
    }

    @Test
    fun `an unattended boat keeps water drag while another craft warps`() {
        val (reference, piloted) = movingBoat()
        val (world, unattended) = movingBoat()
        reference.setHumanPilots(setOf(piloted.id.raw))
        world.setHumanPilots(setOf(observer(world).id.raw))
        repeat(60) { reference.step(dt) }
        assertEquals(1.0, world.advanceOnRails(1.0), 1e-8)
        assertTrue(world.vessels.contains(unattended))
        assertTrue(piloted.body.position.approxEquals(unattended.body.position, 0.01))
        assertTrue(piloted.body.linearVelocity.approxEquals(unattended.body.linearVelocity, 0.01))
        val speed = world.attractorFor(unattended).surfaceVelocityAt(unattended.body.position, Vec3())
            .subInPlace(unattended.body.linearVelocity).length
        assertTrue("water slows the drifting boat", speed < 19.0)
        assertEquals(100.0, world.maxWarp(), 0.0)
    }

    @Test
    fun `unattended water physics preserves a warp bubble's scope and clocks`() {
        val (world, sub) = underwater()
        val watching = observer(world)
        world.setHumanPilots(setOf(watching.id.raw))
        val clock = world.time
        val bubble = world.openBubble(listOf(watching, sub), "pilot")
        bubble.rate = 100.0
        val expected = world.orbitOf(watching).propagate(5.0)
        world.advanceBubble(bubble, 0.05)
        assertEquals("the outside clock stays put", clock, world.time, 1e-8)
        assertEquals(clock + 5.0, bubble.time, 1e-8)
        assertTrue(world.vessels.contains(sub))
        assertTrue(watching.body.position.approxEquals(expected.position, 0.01))
        assertEquals(1_000_000.0, world.maxWarp(), 0.0)
        world.closeBubble(bubble)
        assertEquals(100.0, world.maxWarp(), 0.0)
    }
}
