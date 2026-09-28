package com.rm.apogee.core.world

import com.rm.apogee.core.craft.CraftStats
import com.rm.apogee.core.craft.StockCraft
import com.rm.apogee.core.craft.Vessel
import com.rm.apogee.core.math.Vec3
import com.rm.apogee.core.orbit.SolarSystem
import com.rm.apogee.core.part.Sail
import com.rm.apogee.core.part.StockParts
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Sails: the Sloop, out on open water off the Cape in a steady wind, goes where a sailing boat can
 * and not where it can't.
 */
class SailTest {

    private val catalog = StockParts.catalog
    private val dt = 1.0 / 60.0

    /**
     * The Sloop afloat well off the Cape, facing east, in a steady [wind] (x toward the east and y
     * toward the north, m/s), with [sheet] of her sail out. She sails for [seconds].
     */
    private fun sail(wind: Vec3, sheet: Double = 1.0, seconds: Double = 60.0): Pair<World, Vessel> {
        val world = World.default(catalog)
        world.steadyWind = wind
        val d = SolarSystem.capeDirection(-8_000.0, 12_000.0)
        val boat = world.spawnOnSurface(StockCraft.sloop(catalog), LaunchSite("sea", "Sea", "terra", SolarSystem.latitudeOf(d), SolarSystem.longitudeOf(d)))
        world.assignOwner(boat, "p1")
        world.seatCrew(boat)
        // Stability assist afloat is a helmsman: it holds her heading with the rudder.
        world.apply(Command.SetSas(boat.id.raw, true))
        repeat((10.0 / dt).toInt()) { world.step(dt) }
        world.apply(Command.SetThrottle(boat.id.raw, sheet))
        repeat((seconds / dt).toInt()) { world.step(dt) }
        return world to boat
    }

    /** Her speed over the ground along her own bow, in m/s. */
    private fun ahead(world: World, boat: Vessel): Double {
        val surface = world.attractorFor(boat).surfaceVelocityAt(boat.body.position, Vec3())
        val v = boat.body.linearVelocity.copy().subInPlace(surface)
        val bow = boat.body.orientation.rotate(boat.design.orientation.forward)
        return v dot bow
    }

    /** How far her mast leans from vertical, in degrees. */
    private fun heel(boat: Vessel): Double {
        val deck = boat.body.orientation.rotate(Vec3(0.0, 0.0, 1.0))
        val up = boat.body.position.copy().normalizeInPlace()
        return Math.toDegrees(kotlin.math.acos((deck dot up).coerceIn(-1.0, 1.0)))
    }

    @Test
    fun `the Sloop can be launched`() {
        val stats = CraftStats.analyze(StockCraft.sloop(catalog), catalog)
        assertTrue(stats.problems.toString(), stats.problems.isEmpty())
        assertTrue(StockCraft.sloop(catalog).parts.any { catalog[it.partId]?.module<Sail>() != null })
    }

    @Test
    fun `with the wind on the beam she sails, and heels without going over`() {
        val (world, boat) = sail(Vec3(0.0, 7.0, 0.0))
        val speed = ahead(world, boat)
        assertTrue("under way at $speed m/s", speed > 1.0)
        assertTrue("heeled ${heel(boat)} degrees", heel(boat) < 35.0)
    }

    @Test
    fun `with the wind behind her she runs`() {
        val (world, boat) = sail(Vec3(5.0, 5.0, 0.0))
        assertTrue("running at ${ahead(world, boat)} m/s", ahead(world, boat) > 0.8)
    }

    @Test
    fun `pointed straight into the wind she goes nowhere`() {
        val (world, boat) = sail(Vec3(-7.0, 0.0, 0.0), seconds = 30.0)
        assertTrue("making ${ahead(world, boat)} m/s into the wind", ahead(world, boat) < 0.3)
    }

    @Test
    fun `furled, the sail does nothing`() {
        val (world, boat) = sail(Vec3(0.0, 7.0, 0.0), sheet = 0.0, seconds = 30.0)
        assertTrue("making ${ahead(world, boat)} m/s furled", ahead(world, boat) < 0.3)
        assertTrue(boat.sailFill.all { it == 0.0 })
    }

    @Test
    fun `other players see the sail where it is`() {
        val (_, boat) = sail(Vec3(0.0, 7.0, 0.0), seconds = 5.0)
        val values = VesselPose.Values()
        assertTrue(VesselPose.decode(boat.defs, VesselPose.encode(boat), values))
        val mast = boat.defs.indices.first { boat.defs[it].module<Sail>() != null }
        assertTrue("swung out: ${boat.sailAngle[mast]}", kotlin.math.abs(boat.sailAngle[mast]) > 0.2)
        assertTrue(kotlin.math.abs(values.sailAngle[mast] - boat.sailAngle[mast]) < 0.03)
        assertTrue(kotlin.math.abs(values.sailFill[mast] - boat.sailFill[mast]) < 0.01)
    }
}
