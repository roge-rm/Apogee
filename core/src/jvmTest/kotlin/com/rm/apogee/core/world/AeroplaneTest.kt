package com.rm.apogee.core.world

import com.rm.apogee.core.craft.CraftDesign
import com.rm.apogee.core.craft.StockCraft
import com.rm.apogee.core.craft.Vessel
import com.rm.apogee.core.math.Quat
import com.rm.apogee.core.math.Vec3
import com.rm.apogee.core.math.quatFromTo
import com.rm.apogee.core.part.AeroSurface
import com.rm.apogee.core.part.StockParts
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Wings and control surfaces. These measure forces over a tick or two, since this craft isn't
 * trimmed to fly hands-off: a wing lifts, more angle of attack lifts harder, and a control surface
 * moves the aircraft.
 */
class AeroplaneTest {

    private val catalog = StockParts.catalog
    private val dt = 1.0 / 60.0

    /** Puts [design] in the air at [speed], with the nose pitched above its track. */
    private fun flying(
        design: CraftDesign,
        speed: Double = 185.0,
        pitchDegrees: Double = 8.0,
    ): Pair<World, Vessel> {
        val world = World.default(catalog)
        val body = world.system.body("terra")!!

        val up = Vec3(1.0, 0.0, 0.0)
        val east = Vec3(0.0, 0.0, 1.0)
        // east x up. The other way round pitches the nose down.
        val north = Vec3().setTo(east).crossInPlace(up).normalizeInPlace()

        val position = Vec3().setTo(up).mulInPlace(body.radius + 2_000.0)
        val velocity = Vec3()
        body.surfaceVelocityAt(position, velocity)
        velocity.addScaledInPlace(east, speed)

        val nose = quatFromTo(Vec3(0.0, 1.0, 0.0), east)
        val pitch = Quat.fromAxisAngle(north, Math.toRadians(pitchDegrees))
        val rotation = Quat().setTo(pitch).mulInPlace(nose)

        return world to world.spawnAt(design, "terra", position, velocity, rotation)
    }

    /**
     * Vertical speed gained in one tick, in m/s. One tick is too short for the craft to rotate out
     * of the attitude. Gravity's in it, so free fall is negative.
     */
    private fun verticalGainOverOneTick(world: World, vessel: Vessel): Double {
        val up = Vec3().setTo(vessel.body.position).normalizeInPlace()
        val before = vessel.body.linearVelocity dot up
        world.step(dt)
        return (vessel.body.linearVelocity dot up) - before
    }

    /**
     * The rate about the craft's own pitch axis, positive nose up. Signed, since an untrimmed craft
     * is already turning as the tail weathervanes it, so total spin barely changes with the stick.
     */
    private fun pitchRate(vessel: Vessel): Double {
        val axis = Vec3()
        vessel.body.orientation.rotate(Vec3(1.0, 0.0, 0.0), axis)
        return vessel.body.angularVelocity dot axis
    }

    private fun stripped(of: String, from: CraftDesign) = CraftDesign(
        name = "${from.name} minus $of",
        parts = from.parts.filter { it.partId != of },
        stages = emptyList(),
        catalogHash = catalog.contentHash,
    )

    @Test
    fun `a wing lifts`() {
        val (wingedWorld, winged) = flying(StockCraft.aeroplane(catalog))
        val (bareWorld, bare) = flying(stripped("wing-plank", StockCraft.aeroplane(catalog)))

        val withWings = verticalGainOverOneTick(wingedWorld, winged)
        val without = verticalGainOverOneTick(bareWorld, bare)

        assertTrue(
            "wings did not lift: $withWings m/s vs $without m/s in one tick",
            withWings > without,
        )
        // And by enough to matter: more than the craft's own weight.
        assertTrue(
            "the wings are not carrying the aircraft ($withWings m/s per tick)",
            withWings > 0.0,
        )
    }

    @Test
    fun `more angle of attack lifts harder`() {
        val (shallowWorld, shallow) =
            flying(StockCraft.aeroplane(catalog), pitchDegrees = 2.0)
        val (steepWorld, steep) =
            flying(StockCraft.aeroplane(catalog), pitchDegrees = 10.0)

        val atTwo = verticalGainOverOneTick(shallowWorld, shallow)
        val atTen = verticalGainOverOneTick(steepWorld, steep)

        assertTrue("10 degrees did not out-lift 2 ($atTen vs $atTwo)", atTen > atTwo)
    }

    /** The elevator. Without it the tail weathervanes the nose into the airflow and the wing does nothing. */
    @Test
    fun `a control surface deflects and pitches the aircraft`() {
        val (freeWorld, free) = flying(StockCraft.aeroplane(catalog))
        val (heldWorld, held) = flying(StockCraft.aeroplane(catalog))
        held.control.pitch = 1.0

        repeat(30) { freeWorld.step(dt); heldWorld.step(dt) }

        assertTrue(
            "back-stick produced no nose-up pitch rate " +
                "(${pitchRate(held)} vs ${pitchRate(free)})",
            pitchRate(held) > pitchRate(free) + 0.05,
        )
    }

    @Test
    fun `a stabiliser isn't a control surface`() {
        assertFalse(
            "rocket fins must not deflect: it hands the ascent a second set " +
                "of controls its guidance was never written for",
            catalog["fin-vane"]!!.module<AeroSurface>()!!.controllable,
        )
        assertTrue(catalog["tail-elevon"]!!.module<AeroSurface>()!!.controllable)
    }

    @Test
    fun `an aircraft needs no new module type`() {
        val design = StockCraft.aeroplane(catalog)
        assertTrue("expected wings", design.parts.count { it.partId == "wing-plank" } == 2)
        assertTrue(
            "a wing is the same module a fin is",
            catalog["wing-plank"]?.module<AeroSurface>() != null &&
                catalog["fin-vane"]?.module<AeroSurface>() != null,
        )
    }
}
