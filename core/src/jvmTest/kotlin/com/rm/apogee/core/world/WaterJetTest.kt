package com.rm.apogee.core.world

import com.rm.apogee.core.craft.CraftDesign
import com.rm.apogee.core.craft.CraftKind
import com.rm.apogee.core.craft.StockCraft
import com.rm.apogee.core.craft.Vessel
import com.rm.apogee.core.math.Vec3
import com.rm.apogee.core.orbit.SolarSystem
import com.rm.apogee.core.part.StockParts
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Water jets: the Jet Boat and Jet Ski are quick, turn hard on their nozzles, and stay upright. */
class WaterJetTest {
    private val catalog = StockParts.catalog
    private val dt = 1.0 / 60.0

    /** [design] afloat on calm open water off the Cape, crewed, its jet lit. */
    private fun launch(design: CraftDesign): Pair<World, Vessel> {
        val world = World.default(catalog)
        world.steadyWind = Vec3(0.0, 0.0, 0.0)
        val d = SolarSystem.capeDirection(-8_000.0, 12_000.0)
        val boat = world.spawnOnSurface(design, LaunchSite("sea", "Sea", "terra", SolarSystem.latitudeOf(d), SolarSystem.longitudeOf(d)))
        world.assignOwner(boat, "p1")
        world.seatCrew(boat)
        // Stability assist afloat is a helmsman, holding her heading and her deck level.
        world.apply(Command.SetSas(boat.id.raw, true))
        repeat((5.0 / dt).toInt()) { world.step(dt) }
        world.apply(Command.Stage(boat.id.raw))
        return world to boat
    }

    private fun speed(world: World, boat: Vessel): Double =
        boat.body.linearVelocity.copy().subInPlace(world.attractorFor(boat).surfaceVelocityAt(boat.body.position, Vec3())).length

    private fun heel(boat: Vessel): Double {
        val deck = boat.body.orientation.rotate(boat.design.orientation.up)
        return Math.toDegrees(kotlin.math.acos((deck dot boat.body.position.normalized()).coerceIn(-1.0, 1.0)))
    }

    @Test
    fun `they're boats`() {
        assertEquals(CraftKind.BOAT, CraftKind.of(StockCraft.jetBoat(catalog), catalog))
        assertEquals(CraftKind.BOAT, CraftKind.of(StockCraft.jetSki(catalog), catalog))
    }

    @Test
    fun `flat out, the Jet Boat is far quicker than the Skiff`() {
        val (jw, jet) = launch(StockCraft.jetBoat(catalog))
        val (sw, skiff) = launch(StockCraft.skiff(catalog))
        for ((w, b) in listOf(jw to jet, sw to skiff)) {
            w.apply(Command.SetThrottle(b.id.raw, 1.0))
            repeat((60.0 / dt).toInt()) { w.step(dt) }
        }
        val fast = speed(jw, jet)
        val slow = speed(sw, skiff)
        assertTrue("the Jet Boat makes $fast m/s against the Skiff's $slow", fast > 1.8 * slow && fast > 10.0)
        assertTrue("heeled ${heel(jet)} degrees", heel(jet) < 20.0)
    }

    @Test
    fun `the Jet Ski is quick, and they both turn hard without going over`() {
        for (design in listOf(StockCraft.jetBoat(catalog), StockCraft.jetSki(catalog))) {
            val (world, boat) = launch(design)
            world.apply(Command.SetThrottle(boat.id.raw, 1.0))
            repeat((30.0 / dt).toInt()) { world.step(dt) }
            val straight = speed(world, boat)
            if (design.name == "Jet Ski") assertTrue("the Jet Ski makes only $straight m/s", straight > 10.0)
            // Hard over for twenty seconds at full throttle.
            val heading0 = boat.body.orientation.rotate(boat.design.orientation.forward)
            var most = 0.0
            var turned = 0.0
            var last = heading0.copy()
            repeat((20.0 / dt).toInt()) {
                world.apply(Command.SetAttitude(boat.id.raw, 0.0, 1.0, 0.0))
                world.step(dt)
                most = maxOf(most, heel(boat))
                val now = boat.body.orientation.rotate(boat.design.orientation.forward)
                turned += Math.toDegrees(kotlin.math.acos((now dot last).coerceIn(-1.0, 1.0)))
                last = now
            }
            assertTrue("${design.name} turned only $turned degrees in twenty seconds", turned > 180.0)
            assertTrue("${design.name} heeled $most degrees in the turn", most < 45.0)
            assertTrue("${design.name} broke: ${boat.broken.count { it }}", boat.broken.none { it })
        }
    }
}
