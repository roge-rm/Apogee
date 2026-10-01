package com.rm.apogee.core.weather

import com.rm.apogee.core.craft.StockCraft
import com.rm.apogee.core.math.Vec3
import com.rm.apogee.core.orbit.CelestialBody
import com.rm.apogee.core.orbit.SolarSystem
import com.rm.apogee.core.part.StockParts
import com.rm.apogee.core.world.Power
import com.rm.apogee.core.world.World
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Each world's own weather. Terra's stays bit for bit the same. */
class ClimateTest {
    private val system = SolarSystem.defaultSystem()
    private val catalog = StockParts.catalog

    private fun dir(latDeg: Double, lonDeg: Double): Vec3 {
        val la = Math.toRadians(latDeg); val lo = Math.toRadians(lonDeg)
        return Vec3(Math.cos(la) * Math.cos(lo), Math.sin(la), Math.cos(la) * Math.sin(lo))
    }

    private fun groundTop(body: CelestialBody, d: Vec3): Double {
        val t = body.terrain ?: return 0.0
        val g = t.elevation(d)
        return if (t.hasOcean) maxOf(g, 0.0) else g
    }

    private fun at(body: CelestialBody, d: Vec3, height: Double) =
        Vec3().setTo(d).mulInPlace(body.radius + groundTop(body, d) + height)

    /** The storms on [body]'s whole face at [time], and where each one's base is. */
    private fun storms(weather: Weather, time: Double): List<Pair<CloudShape, Vec3>> {
        val all = ArrayList<CloudShape>()
        weather.globalCover(200_000.0, time, all)
        return all.filter { it.type == weather.climate.stormCloud && it.lobes.size == 2 }
            .map { it to Vec3().setTo(it.lobes[1].centre).normalizeInPlace() }
    }

    @Test
    fun `Terra's weather is bit for bit what it was before worlds had climates`() {
        val terra = system.body("terra")
        val weather = Weather(terra, WeatherConfig())
        val out = AirSample()
        var h = 1469598103934665603L
        fun mix(d: Double) { h = (h xor d.toRawBits()) * 1099511628211L }
        val strikes = ArrayList<Strike>()
        for (k in 0 until 3000) {
            val d = dir(-80.0 + 160.0 * ((k * 37) % 1000) / 1000.0, 360.0 * ((k * 91) % 1000) / 1000.0)
            val alt = listOf(10.0, 300.0, 1_500.0, 5_000.0, 9_000.0, 14_000.0, 25_000.0)[k % 7]
            val g = terra.terrain!!.elevation(d).coerceAtLeast(0.0)
            val t = 3_600.0 * (k % 50) + 17.0 * k
            weather.sample(Vec3().setTo(d).mulInPlace(terra.radius + g + alt), t, out)
            mix(out.wind.x); mix(out.wind.y); mix(out.wind.z); mix(out.turbulence); mix(out.cloudDensity)
            mix(out.precipitation); mix(out.visibility); mix(out.storm); mix((out.cloudType?.ordinal ?: -1).toDouble())
            if (k % 100 == 0) {
                strikes.clear(); weather.strikes(d, t, t + 600.0, strikes)
                for (s in strikes) mix(s.time)
                mix(weather.overcastAbove(d, 500.0, t))
            }
        }
        // A hash of Terra's weather. Re-pin it only when Terra's weather is meant to change.
        assertEquals("190c822707ecc955", java.lang.Long.toHexString(h))
    }

    @Test
    fun `only worlds with air have a climate`() {
        for (id in listOf("terra", "caligo", "rubra", "aurantia", "magna", "aurea", "obliqua", "caerula", "aversa", "ultima")) {
            assertNotNull(id, Climate.of(id))
            assertNotNull("$id has a climate but no air", system.body(id).atmosphere)
        }
        for (id in listOf("luna", "celer", "timor", "pavor", "fornax", "crusta", "maxima", "cicatrix", "fons", "portitor")) {
            assertNull(id, Climate.of(id))
        }
    }

    @Test
    fun `Caligo's ground air barely moves while the sky above races round it`() {
        val caligo = system.body("caligo")
        val weather = Weather(caligo, WeatherConfig())
        val out = AirSample()
        var fastest = 0.0
        var slowestHigh = Double.MAX_VALUE
        for (k in 0 until 60) {
            val d = dir(-60.0 + 2.0 * k, 13.0 * k)
            val t = 5_000.0 * k
            fastest = maxOf(fastest, weather.sample(at(caligo, d, 10.0), t, out).wind.length)
            slowestHigh = minOf(slowestHigh, weather.sample(Vec3().setTo(d).mulInPlace(caligo.radius + 30_000.0), t, out).wind.length)
        }
        assertTrue("Caligo's surface wind reached $fastest m/s", fastest < 3.0)
        assertTrue("Caligo's wind at 30 km fell to $slowestHigh m/s", slowestHigh > 60.0)
        // Inside the deck it's thick cloud, and under it, murk, but no rain.
        weather.sample(Vec3().setTo(dir(10.0, 10.0)).mulInPlace(caligo.radius + 59_000.0), 0.0, out)
        assertEquals(CloudType.DECK, out.cloudType)
        assertTrue(out.visibility < 1_000.0)
        weather.sample(at(caligo, dir(10.0, 10.0), 50.0), 0.0, out)
        assertEquals(0.0, out.precipitation, 0.0)
        assertTrue(out.visibility <= 12_000.0)
    }

    @Test
    fun `Rubra's dust storms blind a craft and starve its panels, and never rain or strike`() {
        val world = World.default(catalog)
        val rubra = world.system.body("rubra")
        val weather = Weather(rubra, WeatherConfig())
        val out = AirSample()
        val found = (0 until 20).asSequence().map { 7_200.0 * it }.flatMap { t -> storms(weather, t).map { Triple(it.first, it.second, t) } }
            .maxByOrNull { it.first.amount }
        assertNotNull("no dust storm on Rubra in two days", found)
        val (_, centre, time) = found!!
        weather.sample(at(rubra, centre, 150.0), time, out)
        assertEquals(CloudType.DUST, out.cloudType)
        assertTrue("visibility in the dust ${out.visibility}", out.visibility < 1_000.0)
        assertEquals(0.0, out.precipitation, 0.0)

        // A lander in one gets a fraction of the light it would get in clear air.
        val site = World.launchSites.first { it.id == "rubra-rift" }
        val lander = world.spawnOnSurface(StockCraft.lander(catalog), site)
        val power = Power(world.system)
        lander.air.clear()
        val clear = power.skyShade(lander, rubra)
        lander.air.setTo(out)
        val dusty = power.skyShade(lander, rubra)
        assertEquals(1.0, clear, 1e-9)
        assertTrue("panels in the dust get $dusty", dusty < 0.5)

        val strikes = ArrayList<Strike>()
        weather.strikes(centre, time - 3_600.0, time + 3_600.0, strikes)
        assertTrue("lightning on Rubra", strikes.isEmpty())
    }

    @Test
    fun `Aurantia's storms rain methane, with no lightning, and Terra's strike`() {
        val aurantia = system.body("aurantia")
        val climate = Climate.of("aurantia")!!
        assertEquals(Climate.Precipitation.METHANE, climate.precipitation)
        val weather = Weather(aurantia, WeatherConfig())
        val out = AirSample()
        var wettest = 0.0
        var struck = 0
        val strikes = ArrayList<Strike>()
        for (t in (0 until 12).map { 10_800.0 * it }) {
            for ((_, centre) in storms(weather, t)) {
                val e = Vec3(); val n = Vec3()
                frame(centre, e, n)
                for (i in -6..6) for (j in -6..6) {
                    val d = Vec3().setTo(centre).addScaledInPlace(e, i * 2_000.0 / aurantia.radius)
                        .addScaledInPlace(n, j * 2_000.0 / aurantia.radius).normalizeInPlace()
                    wettest = maxOf(wettest, weather.sample(at(aurantia, d, 100.0), t, out).precipitation)
                }
                strikes.clear(); weather.strikes(centre, t, t + 1_800.0, strikes); struck += strikes.size
            }
        }
        assertTrue("no methane fell on Aurantia: $wettest", wettest > 0.1)
        assertEquals("lightning on Aurantia", 0, struck)

        // Terra's storms do strike.
        val terra = system.body("terra")
        val terraWeather = Weather(terra, WeatherConfig())
        val terraStorms = (0 until 6).flatMap { k -> storms(terraWeather, 10_800.0 * k).map { it.second to 10_800.0 * k } }
        val terraStrikes = terraStorms.sumOf { (c, t) -> strikes.clear(); terraWeather.strikes(c, t, t + 1_800.0, strikes); strikes.size }
        assertTrue("Terra's storms never struck", terraStrikes > 0)
    }

    @Test
    fun `the giants' winds run in bands, and Caerula's are the fiercest`() {
        val out = AirSample()
        fun equator(id: String): Double {
            val body = system.body(id)
            return Weather(body, WeatherConfig()).sample(Vec3().setTo(dir(0.0, 30.0)).mulInPlace(body.radius), 0.0, out).wind.length
        }
        val speeds = listOf("magna", "aurea", "obliqua", "caerula").associateWith(::equator)
        for ((id, s) in speeds) assertTrue("$id's equator wind $s", s > 50.0)
        assertTrue(speeds.toString(), speeds.getValue("caerula") > speeds.values.filter { it != speeds.getValue("caerula") }.max())
        // Across the latitudes the wind turns round, band by band.
        val magna = system.body("magna")
        val weather = Weather(magna, WeatherConfig())
        val signs = (0..40).map { k ->
            val d = dir(-80.0 + 4.0 * k, 0.0)
            val e = Vec3(); val n = Vec3()
            frame(d, e, n)
            Math.signum(weather.sample(Vec3().setTo(d).mulInPlace(magna.radius), 0.0, out).wind dot e)
        }
        assertTrue("Magna's winds all one way", signs.zipWithNext().count { (a, b) -> a != b } >= 6)
    }

    @Test
    fun `the thin airs have no weather, and the murky ones let little sun through`() {
        val out = AirSample()
        for (id in listOf("aversa", "ultima")) {
            val body = system.body(id)
            val w = Weather(body, WeatherConfig()).sample(at(body, dir(20.0, 40.0), 100.0), 1_000.0, out)
            assertEquals(id, 0.0, w.wind.length, 1e-9)
            assertEquals(id, 0.0, w.turbulence, 0.0)
            assertNull(id, w.cloudType)
        }
        val caligo = Climate.of("caligo")!!
        assertTrue(caligo.sunThrough(0.0) < 0.15)
        assertEquals(1.0, caligo.sunThrough(80_000.0), 1e-9)
        assertTrue(Climate.of("aurantia")!!.sunThrough(0.0) < 0.15)
        assertEquals(1.0, Climate.TERRA.sunThrough(0.0), 0.0)
    }
}
