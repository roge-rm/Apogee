package com.rm.apogee.core.world

import com.rm.apogee.core.craft.StockCraft
import com.rm.apogee.core.math.Vec3
import com.rm.apogee.core.part.StockParts
import com.rm.apogee.core.terrain.SurfaceMaterial
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs
import kotlin.math.asin
import kotlin.math.atan2

/**
 * Setting down on the Moon: on a mare, the flat dark plain every first
 * landing aims for, a kilometre and more below the datum on a world that has
 * no sea to fill it.
 */
class LunaLandingTest {

    private val catalog = StockParts.catalog
    private val dt = 1.0 / 60.0

    /**
     * A level patch of basalt near the mare north-west of Luna's prime
     * meridian. Searched rather than pinned, so tuning the craters does not
     * quietly move the test onto a crater wall.
     */
    private fun mareSite(world: World): LaunchSite {
        val luna = world.system.body("luna").terrain!!
        val r = luna.bodyRadius
        val d = Vec3()
        for (ring in 0 until 40) for (k in 0 until 8) {
            val north = 30_000.0 + ring * 150.0 * kotlin.math.sin(k * Math.PI / 4)
            val z = 25_000.0 + ring * 150.0 * kotlin.math.cos(k * Math.PI / 4)
            d.setTo(r, north, z).normalizeInPlace()
            val h = luna.elevation(d)
            if (luna.material(d, h, 0.0) != SurfaceMaterial.BASALT) continue
            var level = true
            for (a in 0 until 8) {
                val e = Vec3(
                    r,
                    north + 6.0 * kotlin.math.sin(a * Math.PI / 4),
                    z + 6.0 * kotlin.math.cos(a * Math.PI / 4),
                ).normalizeInPlace()
                if (abs(luna.elevation(e) - h) > 0.4) level = false
            }
            if (level) return LaunchSite("test-mare", "Test Mare", "luna", asin(d.y), atan2(d.z, d.x))
        }
        error("no level basalt near the test mare")
    }

    @Test
    fun `a lander sets down on a mare`() {
        val world = World.default(catalog)
        val site = mareSite(world)
        val vessel = world.spawnOnSurface(StockCraft.lander(catalog), site)
        repeat(3) { world.stage(vessel) }
        vessel.control.throttle = 0.0

        val luna = world.attractorFor(vessel)
        assertEquals("luna", luna.id)
        val up = Vec3().setTo(vessel.body.position).normalizeInPlace()
        val radius = vessel.body.position.length
        assertTrue(
            "the craft should spawn on the mare floor, below the datum: ${radius - luna.radius} m",
            radius < luna.radius - 300.0,
        )

        // Engine cut a couple of metres up: touchdown at about 2.5 m/s. In a
        // sixth of a g a bounce carries a long way before gravity settles it,
        // so a lunar landing has to be gentle - the real ones touched down at
        // about one metre a second - and a free fall from ten metres is enough
        // to rock a lander over.
        vessel.body.position.addScaledInPlace(up, 2.0)
        luna.surfaceVelocityAt(vessel.body.position, vessel.body.linearVelocity)
        vessel.body.linearVelocity.addScaledInPlace(up, -1.5)

        val id = vessel.id
        // Long enough for the rocking to die away in low gravity.
        repeat((30.0 / dt).toInt()) { world.step(dt) }
        val landed = world.vessel(id)
        assertNotNull("the lander should survive a gentle mare landing", landed)
        val scratch = luna.surfaceVelocityAt(landed!!.body.position, Vec3())
        val drift = Vec3().setTo(landed.body.linearVelocity).subInPlace(scratch).length
        assertTrue("it should come to rest, still moving at $drift m/s", drift < 0.05)
        val nowUp = Vec3().setTo(landed.body.position).normalizeInPlace()
        val tilt = Math.toDegrees(kotlin.math.acos((landed.forward() dot nowUp).coerceIn(-1.0, 1.0)))
        assertTrue("upright, tilted $tilt degrees", tilt < 10.0)
        assertFalse("no leg should have failed", landed.broken.any { it })
        // Resting on the ground, not sunk into it or hovering.
        val bf = luna.toBodyFixed(landed.body.position, luna.rotationAt(world.time, com.rm.apogee.core.math.Quat.identity()))
        val ground = luna.surfaceRadiusInBodyFrame(bf.normalizeInPlace())
        assertTrue(
            "the craft should sit on the surface: ${landed.body.position.length - ground} m above it",
            landed.body.position.length - ground in 0.3..6.0,
        )
    }

    /** The test site in the launch chooser puts craft down level and at rest, pad after pad. */
    @Test
    fun `the luna test site is level ground for every pad`() {
        val world = World.default(catalog)
        val site = World.launchSites.first { it.id == "luna-mare" }
        val ids = (0 until 4).map { pad -> world.spawnOnSurface(StockCraft.rover(catalog), site, pad).also { it.control.brakes = true }.id }
        repeat((10.0 / dt).toInt()) { world.step(dt) }
        for (id in ids) {
            val v = assertNotNull(world.vessel(id)).let { world.vessel(id)!! }
            val luna = world.attractorFor(v)
            assertEquals("luna", luna.id)
            val drift = Vec3().setTo(v.body.linearVelocity).subInPlace(luna.surfaceVelocityAt(v.body.position, Vec3())).length
            assertTrue("rover $id should be parked, moving at $drift m/s", drift < 0.05)
            assertFalse("rover $id should be undamaged", v.broken.any { it })
        }
    }
}
