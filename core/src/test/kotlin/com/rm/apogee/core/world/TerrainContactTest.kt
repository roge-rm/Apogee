package com.rm.apogee.core.world

import com.rm.apogee.core.craft.StockCraft
import com.rm.apogee.core.craft.Vessel
import com.rm.apogee.core.math.Vec3
import com.rm.apogee.core.orbit.CelestialBody
import com.rm.apogee.core.orbit.SolarSystem
import com.rm.apogee.core.part.StockParts
import com.rm.apogee.core.terrain.SurfaceMaterial
import com.rm.apogee.core.terrain.Terrain
import com.rm.apogee.core.terrain.TerrainTileCache
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The collider against ground built to order: a wall exactly where the test
 * wants one, ice exactly where it wants ice. The generated planet puts these
 * wherever its noise does, which is no use for asserting what happens at them.
 */
class TerrainContactTest {

    private val catalog = StockParts.catalog
    private val dt = 1.0 / 60.0

    /** A test surface: shape and material as plain functions of direction. */
    private class Synthetic(
        private val shape: (Vec3) -> Double,
        private val made: SurfaceMaterial = SurfaceMaterial.GRASS,
    ) : Terrain {
        override val bodyRadius = 600_000.0
        override val maxElevation = 100.0
        override val generation = 0
        override fun elevation(direction: Vec3) = shape(direction.normalized())
        override fun material(direction: Vec3, elevation: Double, slope: Double) = made
        override val tiles by lazy { TerrainTileCache(this) }
    }

    private fun worldOn(terrain: Terrain): Pair<World, LaunchSite> {
        val body = CelestialBody(
            id = "test",
            displayName = "Test",
            gravitationalParameter = 3.5316e12,
            radius = 600_000.0,
            terrain = terrain,
        )
        return World(SolarSystem(listOf(body), "test"), catalog) to
            LaunchSite("site", "Site", "test", 0.0, 0.0)
    }

    /** Metres along the surface from the site at +X. */
    private fun fromSite(d: Vec3) = d.normalized().subInPlace(Vec3(1.0, 0.0, 0.0)).length * 600_000.0

    private fun speed(v: Vessel) = v.body.linearVelocity.length

    /**
     * A rover driven at a wall. With the collider pushing along the radial
     * direction a wall is a floor that happens to be high, and the rover was
     * lifted up it; with the face's own normal it is stopped.
     */
    @Test
    fun `a cliff stops a rover rather than lifting it`() {
        // A fifteen-metre wall all the way round, twenty-five metres out.
        val (world, site) = worldOn(Synthetic({ d ->
            val m = fromSite(d)
            when {
                m < 25.0 -> 0.0
                m < 26.0 -> (m - 25.0) * 15.0
                else -> 15.0
            }
        }))
        val rover = world.spawnOnSurface(StockCraft.rover(catalog), site)
        repeat(60) { world.step(dt) }
        rover.control.throttle = 1.0
        repeat((15.0 / dt).toInt()) { world.step(dt) }

        val body = world.attractorFor(rover)
        val height = body.altitudeOf(rover.body.position)
        assertTrue("it climbed the wall: $height m up", height < 5.0)
        assertTrue("it went through the wall", fromSite(rover.body.position) < 27.0)
    }

    private fun stoppingDistance(material: SurfaceMaterial): Double {
        val (world, site) = worldOn(Synthetic({ 0.0 }, material))
        val rover = world.spawnOnSurface(StockCraft.rover(catalog), site)
        repeat(120) { world.step(dt) }
        val forward = rover.body.orientation.rotate(Vec3(0.0, 0.0, 1.0))
        rover.body.linearVelocity.setTo(forward).mulInPlace(10.0)
        rover.control.brakes = true
        val start = rover.body.position.copy()
        repeat((20.0 / dt).toInt()) { world.step(dt) }
        assertTrue("never stopped on $material: ${speed(rover)} m/s", speed(rover) < 0.3)
        return rover.body.position.distanceTo(start)
    }

    @Test
    fun `brakes on ice go a long way`() {
        val grass = stoppingDistance(SurfaceMaterial.GRASS)
        val ice = stoppingDistance(SurfaceMaterial.ICE)
        assertTrue("stopped in $grass m on grass and $ice m on ice", ice > grass * 3.0)
    }

    /** Top ground speed a rover reaches in [seconds] at full throttle on [material]. */
    private fun driveOn(
        material: SurfaceMaterial,
        design: com.rm.apogee.core.craft.CraftDesign,
        seconds: Double = 15.0,
        throttle: Double = 1.0,
    ): Double {
        val (world, site) = worldOn(Synthetic({ 0.0 }, material))
        val rover = world.spawnOnSurface(design, site)
        repeat(120) { world.step(dt) }
        rover.control.throttle = throttle
        repeat((seconds / dt).toInt()) { world.step(dt) }
        return speed(rover)
    }

    /**
     * The stock rover carrying a full four-metre tank's weight, low down at
     * the pod: three times as heavy, without the tank on top that rolled it
     * over the moment it moved. (It passed that way while a roll wrote the
     * craft off, reading the wreck's last speed.)
     */
    private fun heavyRover(): com.rm.apogee.core.craft.CraftDesign {
        val light = StockCraft.rover(catalog)
        return light.copy(
            name = "Heavy Trundler",
            parts = light.parts + com.rm.apogee.core.craft.PlacedPart(
                "tank-cask4", light.parts[0].position.copy(), parentIndex = 0,
            ),
        )
    }

    @Test
    fun `soft ground costs speed`() {
        val grass = driveOn(SurfaceMaterial.GRASS, StockCraft.rover(catalog))
        val sand = driveOn(SurfaceMaterial.SAND, StockCraft.rover(catalog))
        assertTrue("grass $grass m/s, sand $sand m/s", sand < grass * 0.9)
    }

    /**
     * Sinkage grows with load: the same mud a light rover gets through, a
     * heavy one bogs in.
     */
    @Test
    fun `a heavy rover bogs in mud where a light one gets through`() {
        val light = driveOn(SurfaceMaterial.MUD, StockCraft.rover(catalog))
        val heavy = driveOn(SurfaceMaterial.MUD, heavyRover())
        val heavyOnGrass = driveOn(SurfaceMaterial.GRASS, heavyRover())
        assertTrue("light rover stuck in mud: $light m/s", light > 3.0)
        assertTrue("heavy $heavy m/s in mud, light $light m/s", heavy < light * 0.6)
        assertTrue("heavy rover no slower in mud ($heavy) than on grass ($heavyOnGrass)", heavy < heavyOnGrass * 0.6)
    }

    /**
     * A tank lying on its side and rolling comes to rest. Sliding friction
     * does nothing to a rolling body, and before rolling resistance a toppled
     * tug rolled a kilometre across a flat pad.
     */
    @Test
    fun `a rolling hull comes to rest`() {
        val (world, site) = worldOn(Synthetic({ 0.0 }))
        val design = com.rm.apogee.core.craft.CraftDesign(
            name = "Log",
            parts = listOf(com.rm.apogee.core.craft.PlacedPart("tank-cask4", Vec3.zero())),
            catalogHash = catalog.contentHash,
            orientation = com.rm.apogee.core.craft.CraftOrientation.HORIZONTAL,
        )
        val log = world.spawnOnSurface(design, site)
        repeat(60) { world.step(dt) }
        // Spin it about its own long axis: rolling, at a metre and a quarter a second.
        log.body.angularVelocity.setTo(log.forward()).mulInPlace(2.0)
        val start = log.body.position.copy()
        repeat((15.0 / dt).toInt()) { world.step(dt) }
        val rolled = log.body.position.distanceTo(start)
        assertTrue("still rolling at ${log.body.angularVelocity.length} rad/s", log.body.angularVelocity.length < 0.05)
        assertTrue("rolled $rolled m", rolled < 15.0)
    }

    /**
     * A gearless lander dropped at [speed] onto [material]: how much of it
     * was damaged - health lost, summed over its parts, and a whole part for
     * each part lost.
     */
    private fun damageFromDrop(material: SurfaceMaterial, speed: Double): Double {
        val (world, site) = worldOn(Synthetic({ 0.0 }, material))
        val lander = world.spawnOnSurface(StockCraft.lander(catalog), site)
        val up = lander.body.position.copy().normalizeInPlace()
        lander.body.position.addScaledInPlace(up, 1.0)
        lander.body.linearVelocity.setTo(up).mulInPlace(-speed)
        val parts = lander.design.parts.size
        repeat(300) { world.step(dt) }
        val left = world.vessel(lander.id) ?: return parts.toDouble()
        return (parts - left.design.parts.size) + left.health.sumOf { 1.0 - it }
    }

    @Test
    fun `snow cushions a hard landing that rock does not`() {
        val rock = damageFromDrop(SurfaceMaterial.ROCK, 10.0)
        val snow = damageFromDrop(SurfaceMaterial.SNOW, 10.0)
        // The engine bell takes the blow and crumples - so this is the damage
        // a crumple zone lets through, not a write-off.
        assertTrue("ten metres a second onto rock did only $rock damage", rock > 0.1)
        assertTrue("snow ($snow) should cushion what rock ($rock) does not", snow < rock * 0.5)
    }
}
