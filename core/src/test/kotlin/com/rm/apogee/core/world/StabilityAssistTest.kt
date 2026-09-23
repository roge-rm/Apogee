package com.rm.apogee.core.world

import com.rm.apogee.core.craft.StockCraft
import com.rm.apogee.core.craft.Vessel
import com.rm.apogee.core.math.Vec3
import com.rm.apogee.core.part.StockParts
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs
import kotlin.math.asin

/**
 * Letting go of the stick.
 *
 * An aircraft here weathervanes to zero angle of attack and so to zero lift,
 * whatever its layout: the nose drops the moment nothing holds it up. SAS
 * holds it. Measured against the same flight with SAS off, so the test shows
 * the difference and not merely a number that happens to pass.
 */
class StabilityAssistTest {

    private val catalog = StockParts.catalog
    private val dt = 1.0 / 60.0

    /** Nose angle above the local horizon, degrees. */
    private fun noseAngle(vessel: Vessel): Double {
        val up = vessel.body.position.copy().normalizeInPlace()
        return Math.toDegrees(asin((vessel.forward() dot up).coerceIn(-1.0, 1.0)))
    }

    /**
     * Takes off and climbs out on the test pilot, then lets go at altitude.
     * Returns the nose angle at release and after [handsOff] seconds.
     */
    private fun releaseAndWatch(sas: Boolean, handsOff: Double = 30.0): Pair<Double, Double> {
        val world = World.default(catalog)
        val plane = world.spawnOnSurface(StockCraft.aeroplane(catalog), World.launchSites.first())
        val attractor = world.attractorFor(plane)
        val pilot = AttitudeController()
        world.stage(plane)
        plane.control.throttle = 1.0

        val up = Vec3()
        val east = Vec3()
        val surface = Vec3()
        // Off the runway the way a player does it - the same technique as
        // TakeoffTest: level on the roll, full back stick from sixty metres a
        // second to a ten-degree climb, then SAS holds it there. A
        // proportional test pilot asking for six degrees only ever commanded
        // half elevator, and on the day the pad moved up sixty metres into
        // thinner air, that stopped being enough to rotate.
        plane.control.sasEnabled = true
        var rotated = false
        while (world.time < 45.0) {
            up.setTo(plane.body.position).normalizeInPlace()
            attractor.surfaceVelocityAt(plane.body.position, surface)
            east.setTo(surface).addScaledInPlace(up, -(surface dot up)).normalizeInPlace()
            val speed = plane.body.linearVelocity.copy().subInPlace(surface).length
            when {
                speed < 60.0 -> pilot.steer(plane, east)
                !rotated -> {
                    plane.control.pitch = 1.0
                    plane.control.yaw = 0.0
                    plane.control.roll = 0.0
                    if ((plane.forward() dot up) > kotlin.math.sin(Math.toRadians(10.0))) rotated = true
                }
                else -> plane.control.pitch = 0.0
            }
            world.step(dt)
        }
        check(!plane.touchingGround) { "never got airborne" }

        plane.control.pitch = 0.0
        plane.control.yaw = 0.0
        plane.control.roll = 0.0
        plane.control.sasEnabled = sas
        val atRelease = noseAngle(plane)
        val end = world.time + handsOff
        while (world.time < end) world.step(dt)
        return atRelease to noseAngle(plane)
    }

    @Test
    fun `hands off, SAS holds the nose where it was let go`() {
        val (released, after) = releaseAndWatch(sas = true)
        assertTrue(
            "released at %.1f degrees, drifted to %.1f".format(released, after),
            abs(after - released) < 1.5,
        )
    }

    @Test
    fun `hands off without SAS, the nose drops`() {
        val (released, after) = releaseAndWatch(sas = false)
        assertTrue(
            "released at %.1f degrees and it stayed at %.1f - then SAS is not what holds it"
                .format(released, after),
            after < released - 3.0,
        )
    }
}
