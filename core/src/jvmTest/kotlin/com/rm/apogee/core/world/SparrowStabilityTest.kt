package com.rm.apogee.core.world

import com.rm.apogee.core.craft.StockCraft
import com.rm.apogee.core.craft.Vessel
import com.rm.apogee.core.math.Quat
import com.rm.apogee.core.math.Vec3
import com.rm.apogee.core.math.quatFromTo
import com.rm.apogee.core.part.AeroSurface
import com.rm.apogee.core.part.StockParts
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs

/**
 * The stock Sparrow is stable in pitch by itself: with SAS off, full stick at speed doesn't pull
 * it into a stall that breaks the wings, and a kick hands off dies away.
 */
class SparrowStabilityTest {

    private val catalog = StockParts.catalog
    private val dt = 1.0 / 60.0

    /** The Sparrow at [height] over the Cape, flying east level at [speed], SAS off, full throttle. */
    private fun flying(world: World, height: Double, speed: Double): Vessel {
        val terra = world.system.body("terra")!!
        val up = com.rm.apogee.core.orbit.SolarSystem.capeDirection(-5_000.0, 0.0)
        val position = Vec3().setTo(up).mulInPlace(terra.radius + height)
        val surface = terra.surfaceVelocityAt(position, Vec3())
        val east = surface.copy().normalizeInPlace()
        val right = east.copy().crossInPlace(up).normalizeInPlace()
        val rotation = quatFromTo(Vec3(0.0, 0.0, 1.0), up)
        val nose = rotation.rotate(Vec3(0.0, 1.0, 0.0))
        rotation.setTo(quatFromTo(nose, east) * rotation)
        rotation.setTo(Quat.fromAxisAngle(right, Math.toRadians(1.0)) * rotation)
        val velocity = surface.copy().addScaledInPlace(east, speed)
        val plane = world.spawnAt(StockCraft.sparrow(catalog), "terra", position, velocity, rotation)
        world.assignOwner(plane, "p1")
        world.seatCrew(plane)
        plane.activated.fill(true)
        plane.control.gear = false
        for (i in plane.defs.indices) if (plane.defs[i].fold != null) plane.setLegDeploy(i, 0.0)
        world.apply(Command.SetSas(plane.id.raw, false))
        world.apply(Command.SetThrottle(plane.id.raw, 1.0))
        return plane
    }

    private fun surfaces(v: Vessel) = v.defs.indices.filter { v.defs[it].module<AeroSurface>() != null }

    @Test
    fun `lift sits behind the centre of mass`() {
        val world = World.default(catalog)
        val plane = flying(world, 600.0, 158.0)
        val com = plane.centerOfMass().y
        var area = 0.0
        var moment = 0.0
        for (i in surfaces(plane)) {
            val position = plane.design.parts[i].position
            // Horizontal surfaces only; the fin doesn't lift in pitch.
            if (abs(position.z) > 0.5) continue
            val a = plane.defs[i].module<AeroSurface>()!!.area
            area += a
            moment += a * position.y
        }
        val margin = com - moment / area
        // A light plane's margin, a tenth or so of the wing's mean chord of about 1.65 m.
        assertTrue("static margin $margin m", margin in 0.08..0.25)
    }

    @Test
    fun `full stick with SAS off keeps the wings on`() {
        for ((name, pitch) in listOf("pull" to 1.0, "push" to -1.0)) {
            val world = World.default(catalog)
            val plane = flying(world, 600.0, 158.0)
            val parts = plane.design.parts.size
            repeat(30) { world.step(dt) }
            world.apply(Command.SetAttitude(plane.id.raw, pitch, 0.0, 0.0))
            var worst = 0.0
            repeat(180) {
                world.step(dt)
                for (i in surfaces(plane)) worst = maxOf(worst, plane.jointLoad[i].toDouble())
            }
            assertEquals("$name: parts left", parts, plane.design.parts.size)
            assertTrue("$name: worst surface load $worst", worst < 1.0)
        }
    }

    /** Angle of attack in degrees, nose above the airflow positive. */
    private fun attack(world: World, v: Vessel): Double {
        val terra = world.system.body("terra")!!
        val air = terra.surfaceVelocityAt(v.body.position, Vec3()).negateInPlace().addInPlace(v.body.linearVelocity)
        val forward = v.body.orientation.rotate(Vec3(0.0, 1.0, 0.0))
        val up = v.body.orientation.rotate(Vec3(0.0, 0.0, 1.0))
        return Math.toDegrees(kotlin.math.atan2(-(air dot up), air dot forward))
    }

    @Test
    fun `a pitch kick hands off dies away`() {
        val world = World.default(catalog)
        val plane = flying(world, 600.0, 158.0)
        repeat(60) { world.step(dt) }
        val trim = attack(world, plane)
        val span = plane.body.orientation.rotate(Vec3(1.0, 0.0, 0.0))
        plane.body.angularVelocity.addScaledInPlace(span, 0.3)
        var worst = 0.0
        var settled = 0.0
        repeat(240) { tick ->
            world.step(dt)
            val off = abs(attack(world, plane) - trim)
            worst = maxOf(worst, off)
            if (tick >= 90) settled = maxOf(settled, off)
        }
        // Nose-heavy, the kick is gone in a second and a half. Balanced or tail-heavy, the nose
        // rises a couple of degrees and takes seconds to come back, if it does.
        assertTrue("attack strayed $worst degrees from trim $trim", worst < 2.0)
        assertTrue("attack still $settled degrees off trim after 1.5 s", settled < 0.3)
    }
}
