package com.rm.apogee.core.weather

import com.rm.apogee.core.math.Vec3
import com.rm.apogee.core.orbit.SolarSystem
import com.rm.apogee.core.world.World
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.cos
import kotlin.math.sin

/**
 * Quakes: none on Calm or Normal, along Terra's plate edges, the same everywhere they're worked
 * out, shaking that falls off and arrives later further out, tsunamis from the big ones under the
 * sea, and fewer near launch sites.
 */
class QuakeTest {
    private val terra = SolarSystem.defaultSystem().body(SolarSystem.HOMEWORLD_ID)
    private val radius = terra.radius
    private val extreme = WeatherConfig(intensity = WeatherIntensity.EXTREME)

    private fun direction(latDeg: Double, lonDeg: Double) = SolarSystem.surfaceDirection(Math.toRadians(latDeg), Math.toRadians(lonDeg))

    private fun cycleOf(time: Double, q: Quakes) = kotlin.math.floor((time + q.phase) / Quakes.CYCLE).toLong()

    private fun bits(a: Double) = java.lang.Double.doubleToLongBits(a)

    @Test
    fun `none on Calm or Normal`() {
        for (intensity in listOf(WeatherIntensity.CALM, WeatherIntensity.NORMAL)) {
            val w = Weather(terra, WeatherConfig(intensity = intensity))
            assertTrue(!w.quakeModel.active)
            val out = ArrayList<LiveQuake>()
            val v = Vec3()
            for (k in 0 until 200) {
                val t = k * 1_700.0
                w.quakes(Vec3(1.0, 0.0, 0.0), t, 1e9, out)
                assertEquals(0.0, w.quakeShake(direction(35.0, 140.0), t, v), 0.0)
                assertTrue(!w.quakesLive(t))
            }
            assertTrue(out.isEmpty())
        }
    }

    @Test
    fun `on Terra they strike along the plate edges, and the big ones under the sea make the tsunamis`() {
        val w = Weather(terra, extreme)
        val q = w.quakeModel
        val shocks = (0L until 2_000L).flatMap { q.mainShocks(it) }
        val sea = shocks.count { it.underSea }
        val big = shocks.count { it.magnitude >= Quakes.TSUNAMI_MAGNITUDE }
        println("main shocks in 2000 cycles: ${shocks.size}, under sea ${sea}, tsunami-sized ${big}, " +
            "8 or more ${shocks.count { it.magnitude >= 8.0 }}, most ${shocks.maxOf { it.magnitude }}")
        assertTrue(shocks.size in 7_000..10_000)
        // Never in the middle of a plate.
        for ((lat, lon) in listOf(-25.0 to 134.0, -10.0 to -52.0, 62.0 to 100.0, 58.0 to -100.0, -80.0 to 0.0)) {
            val middle = direction(lat, lon)
            val close = shocks.count { Math.toDegrees(kotlin.math.acos((it.epicentre dot middle).coerceIn(-1.0, 1.0))) < 10.0 }
            assertEquals("near $lat, $lon", 0, close)
        }
        // Japan gets plenty.
        val japan = direction(37.0, 142.0)
        assertTrue(shocks.count { Math.toDegrees(kotlin.math.acos((it.epicentre dot japan).coerceIn(-1.0, 1.0))) < 6.0 } > 100)

        // Each tsunami is its cycle's biggest quake under deep sea.
        var tsunamis = 0
        val terrain = terra.terrain!!
        for (cycle in 0L until 200L) {
            val main = q.mainShocks(cycle)
            val sources = main.filter { it.magnitude >= Quakes.TSUNAMI_MAGNITUDE && terrain.elevation(it.epicentre) < -Tsunamis.ORIGIN_DEPTH }
            val from = cycle * Quakes.CYCLE - q.phase
            val found = ArrayList<LiveTsunami>()
            w.tsunamis(Vec3(1.0, 0.0, 0.0), from + Quakes.CYCLE - 1.0, 1e9, found)
            val mine = found.filter { it.id == cycle }
            if (sources.isEmpty()) { assertTrue("cycle $cycle", mine.isEmpty()); continue }
            tsunamis++
            assertEquals("cycle $cycle", 1, mine.size)
            val source = sources.maxBy { it.magnitude }
            assertEquals(bits(source.start), bits(mine[0].start))
            assertEquals(source.epicentre, mine[0].origin)
        }
        println("tsunamis in 200 cycles: $tsunamis")
        assertTrue(tsunamis in 100..190)
    }

    @Test
    fun `two instances and one stepped through many cycles agree bit for bit`() {
        val stepped = Weather(terra, extreme)
        var t = 0.0
        var shaken = 0
        val va = Vec3(); val vb = Vec3(); val aa = Vec3(); val ab = Vec3()
        while (t < 60.0 * 3_600.0) {
            val list = ArrayList<LiveQuake>()
            stepped.quakes(Vec3(1.0, 0.0, 0.0), t, 1e9, list)
            if ((t / 600.0).toInt() % 5 == 0) {
                val fresh = Weather(terra, extreme)
                val other = ArrayList<LiveQuake>()
                fresh.quakes(Vec3(1.0, 0.0, 0.0), t, 1e9, other)
                assertEquals(list.map { it.id }, other.map { it.id })
                for ((x, y) in list.zip(other)) {
                    assertEquals(x.epicentre, y.epicentre); assertEquals(bits(x.start), bits(y.start))
                    assertEquals(bits(x.magnitude), bits(y.magnitude)); assertEquals(bits(x.peak), bits(y.peak))
                }
                // Shaking round each fresh shock, a little after it struck.
                for (s in list) for (k in 0 until 6) {
                    val d = Vec3(sin(k * 1.7), cos(k * 2.3), sin(k * 0.9 + 1.0)).normalizeInPlace()
                    val p = Vec3().setTo(s.epicentre).addScaledInPlace(d, s.strongRadius * 0.5 * k / radius).normalizeInPlace()
                    val at = s.start + 2.0 + k * 0.7
                    val la = stepped.quakeShake(p, at, va, aa); val lb = fresh.quakeShake(p, at, vb, ab)
                    assertEquals(bits(la), bits(lb)); assertEquals(va, vb); assertEquals(aa, ab)
                    if (stepped.quakeLevel() > 0.0) shaken++
                }
            }
            t += 600.0
        }
        assertTrue("shaking compared: $shaken", shaken > 20)
    }

    @Test
    fun `shaking falls off with distance and comes later further out`() {
        val w = Weather(terra, WeatherConfig(intensity = WeatherIntensity.CALM))
        val epicentre = direction(10.0, 20.0)
        w.quakeModel.force(epicentre, 100.0, magnitude = 7.5, depth = 3_000.0)
        val list = ArrayList<LiveQuake>()
        w.quakes(epicentre, 200.0, 0.0, list)
        val quake = list.single()
        println("magnitude 7.5: peak ${quake.peak} m/s^2, strong within ${quake.strongRadius} m, felt to ${quake.reach} m, ${quake.duration} s")
        var last = Double.MAX_VALUE
        var lastArrival = 0.0
        val v = Vec3()
        for (km in listOf(0.0, 5.0, 15.0, 40.0, 70.0)) {
            val p = SolarSystem.surfaceDirection(Math.toRadians(10.0), Math.toRadians(20.0) + km * 1_000.0 / (radius * cos(Math.toRadians(10.0))))
            var most = 0.0; var first = Double.NaN; var fastest = 0.0
            var t = 100.0
            while (t < 600.0) {
                w.quakeShake(p, t, v)
                val level = w.quakeLevel()
                if (level > 0.0 && first.isNaN()) first = t
                most = maxOf(most, level); fastest = maxOf(fastest, v.length)
                t += 0.01
            }
            println("$km km: shaking to $most m/s^2, ground to $fastest m/s, from ${first - 100.0} s")
            assertTrue("$km km: $most after $last", most < last)
            assertTrue(most > 0.0)
            assertTrue(first - 100.0 >= lastArrival)
            last = most; lastArrival = first - 100.0
        }
        // Out of reach it's still.
        val far = SolarSystem.surfaceDirection(Math.toRadians(10.0), Math.toRadians(20.0) + (quake.reach + 5_000.0) / (radius * cos(Math.toRadians(10.0))))
        var t = 100.0
        while (t < 800.0) { assertEquals(0.0, w.quakeShake(far, t, v), 0.0); assertEquals(0.0, v.length, 0.0); t += 0.5 }
    }

    @Test
    fun `fewer and weaker near a launch site`() {
        val w = Weather(terra, extreme)
        // Anywhere on the world, so there are enough near the sites to count.
        val q = Quakes(terra, Weather.seedOf(extreme), WeatherIntensity.EXTREME, plates = false)
        val sites = World.launchSites.filter { it.bodyId == terra.id && it.id in setOf("cape", "roaring-sea") }
            .map { SolarSystem.surfaceDirection(it.latitude, it.longitude) }
        val others = (0 until 40).map { Vec3(sin(it * 2.1) * cos(it * 0.7), cos(it * 1.3), sin(it * 0.9 + 0.4)).normalizeInPlace() }
            .filter { o -> sites.all { it.distanceTo(o) * radius > 300_000.0 } }
        val within = 30_000.0
        val nearSites = IntArray(sites.size); val nearOthers = IntArray(others.size)
        var peakNear = 0.0; var countNear = 0; var peakOther = 0.0; var countOther = 0
        for (cycle in 0L until 30_000L) for (s in q.mainShocks(cycle)) {
            for ((i, d) in sites.withIndex()) if (d.distanceTo(s.epicentre) * radius < within) {
                nearSites[i]++; peakNear += s.peak / Quakes.PEAK_AT / Math.pow(10.0, Quakes.PEAK_GROWTH * (s.magnitude - 6.5)); countNear++
            }
            for ((i, d) in others.withIndex()) if (d.distanceTo(s.epicentre) * radius < within) {
                nearOthers[i]++; peakOther += s.peak / Quakes.PEAK_AT / Math.pow(10.0, Quakes.PEAK_GROWTH * (s.magnitude - 6.5)); countOther++
            }
        }
        val site = nearSites.average(); val other = nearOthers.average()
        println("main shocks within 30 km: sites ${nearSites.toList()}, elsewhere ${"%.1f".format(other)} on average; " +
            "strength ${"%.2f".format(peakNear / countNear)} vs ${"%.2f".format(peakOther / countOther)}")
        assertTrue("$site vs $other", site < 0.8 * other)
        assertTrue(peakNear / countNear < 0.95 * peakOther / countOther)
    }
}
