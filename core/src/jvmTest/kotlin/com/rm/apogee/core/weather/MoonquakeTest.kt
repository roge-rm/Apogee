package com.rm.apogee.core.weather

import com.rm.apogee.core.math.Vec3
import com.rm.apogee.core.orbit.SolarSystem
import com.rm.apogee.core.part.StockParts
import com.rm.apogee.core.world.World
import com.rm.apogee.core.world.WorldSave
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.cos
import kotlin.math.sin

/**
 * Moonquakes on airless solid worlds: a quarter as many as Terra's, weaker, anywhere on the
 * world, fewer near Luna's sites, none on Calm or Normal, on gas giants or on moons too small to
 * feel one; and aftershocks under the sea or not by their own epicentre.
 */
class MoonquakeTest {
    private val system = SolarSystem.defaultSystem()
    private val luna = system.body("luna")
    private val terra = system.body(SolarSystem.HOMEWORLD_ID)
    private val extreme = WeatherConfig(intensity = WeatherIntensity.EXTREME)

    @Test
    fun `a quarter as many as Terra's, weaker, and anywhere`() {
        val moon = Quakes.of(luna, extreme)
        assertTrue(moon.active)
        val shocks = (0L until 2_000L).flatMap { moon.mainShocks(it) }
        val home = Weather(terra, extreme).quakeModel
        val terraShocks = (0L until 2_000L).sumOf { home.mainShocks(it).size }
        println("moonquakes in 2000 cycles: ${shocks.size} (Terra $terraShocks), magnitudes ${shocks.minOf { it.magnitude }} to ${shocks.maxOf { it.magnitude }}, " +
            "peak up to ${shocks.maxOf { it.peak }} m/s^2, strong within ${shocks.maxOf { it.strongRadius }} m, felt to ${shocks.maxOf { it.reach }} m, " +
            "${shocks.maxOf { it.duration }} s")
        val share = shocks.size.toDouble() / terraShocks
        assertTrue("share $share", share in 0.18..0.32)
        for (s in shocks) {
            assertTrue(s.magnitude >= Quakes.MOON_LEAST && s.magnitude <= Quakes.MOON_MOST)
            assertTrue(!s.underSea)
            assertTrue("peak ${s.peak}", s.peak <= Quakes.MOON_PEAK * luna.surfaceGravity + 1e-12)
        }
        // Anywhere: every eighth of the world gets some.
        val octants = IntArray(8)
        for (s in shocks) octants[(if (s.epicentre.x > 0) 1 else 0) + (if (s.epicentre.y > 0) 2 else 0) + (if (s.epicentre.z > 0) 4 else 0)]++
        assertTrue(octants.toList().toString(), octants.all { it > shocks.size / 16 })
    }

    @Test
    fun `none on Calm or Normal, on gas giants or on the smallest moons`() {
        for (intensity in listOf(WeatherIntensity.CALM, WeatherIntensity.NORMAL)) {
            val q = Quakes.of(luna, WeatherConfig(intensity = intensity))
            assertTrue(!q.active)
            for (c in 0L until 200L) assertTrue(q.mainShocks(c).isEmpty())
        }
        for (id in listOf("magna", "aurea", "obliqua", "caerula", "timor", "pavor", "fons")) {
            val body = system.body(id)
            val q = if (body.atmosphere != null) Weather(body, extreme).quakeModel else Quakes.of(body, extreme)
            assertTrue(id, !q.active)
            for (c in 0L until 50L) assertTrue(id, q.mainShocks(c).isEmpty())
        }
        // Every other airless world with ground has them.
        for (id in listOf("luna", "celer", "fornax", "crusta", "maxima", "cicatrix", "portitor")) assertTrue(id, Quakes.of(system.body(id), extreme).active)
    }

    @Test
    fun `a world hands out its weather's quakes where it has air, its own where not`() {
        val catalog = StockParts.catalog
        val world = World(SolarSystem.defaultSystem(), catalog).also {
            it.restore(WorldSave(catalogHash = catalog.contentHash, universeTime = 0.0, nextVesselId = 1L, weather = extreme))
        }
        val t = world.system.body(SolarSystem.HOMEWORLD_ID)
        val l = world.system.body("luna")
        assertTrue(world.quakesFor(t) === world.weatherFor(t)!!.quakeModel)
        assertNull(world.weatherFor(l))
        val moon = world.quakesFor(l)!!
        assertTrue(moon.active)
        assertTrue(world.quakesFor(l) === moon)
        assertNull(world.quakesFor(world.system.body("sol")))
        val still = World(SolarSystem.defaultSystem(), catalog)
        assertNull(still.quakesFor(still.system.body("luna")))
    }

    @Test
    fun `fewer and weaker near Luna's sites`() {
        val q = Quakes.of(luna, extreme)
        val radius = luna.radius
        val sites = World.launchSites.filter { it.bodyId == luna.id }.map { SolarSystem.surfaceDirection(it.latitude, it.longitude) }
        assertTrue(sites.isNotEmpty())
        val others = (0 until 40).map { Vec3(sin(it * 2.1) * cos(it * 0.7), cos(it * 1.3), sin(it * 0.9 + 0.4)).normalizeInPlace() }
            .filter { o -> sites.all { it.distanceTo(o) * radius > 150_000.0 } }
        val within = 30_000.0
        val nearSites = IntArray(sites.size); val nearOthers = IntArray(others.size)
        val full = Quakes.MOON_PEAK * luna.surfaceGravity
        var peakNear = 0.0; var countNear = 0; var peakOther = 0.0; var countOther = 0
        for (cycle in 0L until 30_000L) for (s in q.mainShocks(cycle)) {
            val unshy = full * Math.pow(10.0, Quakes.PEAK_GROWTH * (s.magnitude - 6.5))
            for ((i, d) in sites.withIndex()) if (d.distanceTo(s.epicentre) * radius < within) { nearSites[i]++; peakNear += s.peak / unshy; countNear++ }
            for ((i, d) in others.withIndex()) if (d.distanceTo(s.epicentre) * radius < within) { nearOthers[i]++; peakOther += s.peak / unshy; countOther++ }
        }
        val site = nearSites.average(); val other = nearOthers.average()
        println("moonquakes within 30 km: sites ${nearSites.toList()}, elsewhere ${"%.1f".format(other)} on average; " +
            "strength ${"%.2f".format(peakNear / countNear)} vs ${"%.2f".format(peakOther / countOther)}")
        assertTrue("$site vs $other", site < 0.8 * other)
        assertTrue(peakNear / countNear < 0.95 * peakOther / countOther)
    }

    @Test
    fun `an aftershock is under the sea by its own epicentre`() {
        val w = Weather(terra, extreme)
        val ground = terra.terrain!!
        val seen = HashSet<Long>()
        var after = 0; var differ = 0
        var t = 0.0
        while (t < 300 * 3_600.0) {
            val list = ArrayList<LiveQuake>()
            w.quakes(Vec3.unitY(), t, Double.MAX_VALUE, list)
            val mains = list.filter { it.id == it.mainId }.associateBy { it.id }
            for (q in list) {
                if (q.id == q.mainId || !seen.add(q.id)) continue
                after++
                assertEquals("aftershock ${q.id}", ground.elevation(q.epicentre) < 0.0, q.underSea)
                mains[q.mainId]?.let { if (it.underSea != q.underSea) differ++ }
            }
            t += 1_800.0
        }
        println("aftershocks: $after, $differ on the other side of a shore from their main shock")
        assertTrue(after > 50)
    }
}
