package com.rm.apogee.core.world

import com.rm.apogee.core.craft.StockCraft
import com.rm.apogee.core.craft.Vessel
import com.rm.apogee.core.math.Quat
import com.rm.apogee.core.math.Vec3
import com.rm.apogee.core.part.StockParts
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Stability assist holding a navball marker points the nose at it. */
class SasModeTest {

    private val catalog = StockParts.catalog
    private val dt = 1.0 / 60.0

    /** A probe in a circular orbit 150 km up, with its nose along the world's +X. */
    private fun inOrbit(world: World): Vessel {
        val terra = world.system.body("terra")
        val r = terra.radius + 150_000.0
        val speed = kotlin.math.sqrt(terra.gravitationalParameter / r)
        return world.spawnAt(
            StockCraft.probe(catalog), "terra", Vec3(0.0, 0.0, r), Vec3(speed, 0.0, 0.0), Quat.identity(),
        )
    }

    private fun angleBetween(a: Vec3, b: Vec3) =
        Math.toDegrees(kotlin.math.acos((a.normalized() dot b.normalized()).coerceIn(-1.0, 1.0)))

    @Test
    fun `holding retrograde turns the nose back along the orbit`() {
        val world = World.default(catalog)
        val probe = inOrbit(world)
        world.apply(Command.SetSas(probe.id.raw, true))
        world.apply(Command.SetSasMode(probe.id.raw, SasMode.RETROGRADE))
        repeat((40.0 / dt).toInt()) { world.step(dt) }
        val nose = probe.forward()
        val back = probe.body.linearVelocity.copy().negateInPlace()
        assertTrue("nose ${angleBetween(nose, back)} degrees off retrograde", angleBetween(nose, back) < 3.0)
    }

    @Test
    fun `holding normal and radial points where the navball shows them`() {
        for (mode in listOf(SasMode.NORMAL, SasMode.RADIAL_OUT)) {
            val world = World.default(catalog)
            val probe = inOrbit(world)
            world.apply(Command.SetSas(probe.id.raw, true))
            world.apply(Command.SetSasMode(probe.id.raw, mode))
            repeat((40.0 / dt).toInt()) { world.step(dt) }
            val nav = Navigation.compute(
                probe.body.position, probe.body.linearVelocity, world.attractorFor(probe),
                NavFrame.AUTO, null, null, NavDirections(),
            )
            assertEquals(NavFrame.ORBIT, nav.frame)
            val want = Vec3()
            assertTrue(nav.forMode(mode, want))
            assertTrue("$mode: ${angleBetween(probe.forward(), want)} degrees off", angleBetween(probe.forward(), want) < 3.0)
        }
    }

    @Test
    fun `holding target points at the other craft`() {
        val world = World.default(catalog)
        val probe = inOrbit(world)
        val other = world.spawnAt(
            StockCraft.probe(catalog), "terra",
            probe.body.position.copy().addInPlace(Vec3(0.0, 400.0, 0.0)), probe.body.linearVelocity.copy(), Quat.identity(),
        )
        world.apply(Command.SetTarget(probe.id.raw, other.id.raw))
        world.apply(Command.SetSas(probe.id.raw, true))
        world.apply(Command.SetSasMode(probe.id.raw, SasMode.TARGET))
        repeat((40.0 / dt).toInt()) { world.step(dt) }
        val toOther = other.body.position.copy().subInPlace(probe.body.position)
        assertTrue("${angleBetween(probe.forward(), toOther)} degrees off the target", angleBetween(probe.forward(), toOther) < 3.0)
    }

    @Test
    fun `heading reads the compass`() {
        val at = Vec3(0.0, 0.0, 600_000.0)
        val east = Vec3(); val north = Vec3()
        Navigation.horizon(at, east, north)
        assertEquals(0.0, Navigation.heading(at, north), 1e-6)
        assertEquals(90.0, Navigation.heading(at, east), 1e-6)
        assertEquals(180.0, Navigation.heading(at, north.copy().negateInPlace()), 1e-6)
        assertEquals(1.0, north.y, 1e-9)
    }
}
