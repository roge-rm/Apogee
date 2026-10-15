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
 * Tornadoes and waterspouts: only under supercells and in hurricane bands, only with rare events
 * on, the same on every instance, lifting and spinning what's in them, weaker over the sea and
 * near launch sites, and nothing away from them.
 */
class TornadoTest {
    private val system = SolarSystem.defaultSystem()
    private val terra = system.body(SolarSystem.HOMEWORLD_ID)
    private val terrain = terra.terrain!!
    private val radius = terra.radius

    private fun weather(intensity: WeatherIntensity) = Weather(terra, WeatherConfig(intensity = intensity))

    private fun all(weather: Weather, time: Double): List<LiveTornado> =
        ArrayList<LiveTornado>().also { weather.tornadoes(Vec3(1.0, 0.0, 0.0), time, 1e9, it) }

    /** The unit direction [r] metres from unit [c] on bearing [bearing] (radians from east). */
    private fun at(c: Vec3, r: Double, bearing: Double): Vec3 {
        val e = Vec3(); val n = Vec3()
        frame(c, e, n)
        return Vec3().setTo(c).mulInPlace(radius)
            .addScaledInPlace(e, cos(bearing) * r).addScaledInPlace(n, sin(bearing) * r).normalizeInPlace()
    }

    private fun ground(d: Vec3) = maxOf(terrain.elevation(d), 0.0)

    /** Every tornado seen on Extreme every [STEP] seconds over [HOURS], each at its strongest. */
    private val strongest: List<Pair<Double, LiveTornado>> by lazy {
        val w = weather(WeatherIntensity.EXTREME)
        val best = LinkedHashMap<Long, Pair<Double, LiveTornado>>()
        var t = 0.0
        while (t < HOURS * 3_600.0) {
            for (k in all(w, t)) {
                val old = best[k.id]
                if (old == null || k.strength > old.second.strength) best[k.id] = t to k
            }
            t += STEP
        }
        best.values.toList()
    }

    @Test
    fun `none on Calm or Normal, and Wild has far fewer than Extreme`() {
        for (intensity in listOf(WeatherIntensity.CALM, WeatherIntensity.NORMAL)) {
            val w = weather(intensity)
            assertTrue(!w.tornadoModel.active)
            for (k in 0 until 100) assertTrue(all(w, k * 1_900.0).isEmpty())
        }
        val wild = weather(WeatherIntensity.WILD)
        val extreme = weather(WeatherIntensity.EXTREME)
        var wildCount = 0; var extremeCount = 0
        for (k in 0 until 120) {
            val t = k * 1_300.0
            wildCount += all(wild, t).size
            extremeCount += all(extreme, t).size
        }
        println("live over 120 times: Extreme $extremeCount, Wild $wildCount")
        assertTrue("Extreme has plenty: $extremeCount", extremeCount > 120)
        assertTrue("Wild has far fewer: $wildCount vs $extremeCount", wildCount * 4 < extremeCount)
    }

    @Test
    fun `a supercell on Extreme usually has one in its mature stage`() {
        val w = weather(WeatherIntensity.EXTREME)
        val model = w.stormModel
        val with = HashSet<Long>()
        val supercells = HashSet<Long>()
        var t = 0.0
        while (t < 12.0 * 3_600.0) {
            val cells = ArrayList<Storms.Storm>()
            val e = Vec3(); val n = Vec3()
            frame(Vec3(1.0, 0.0, 0.0), e, n)
            model.supercells(Vec3(1.0, 0.0, 0.0), e, n, 1e9, t, cells)
            for (s in cells) if (model.envelope(s, t) > 0.9) supercells.add(key(s))
            for (k in all(w, t)) if (!k.inHurricane) with.add(k.id and -2L)
            t += 60.0
        }
        // A supercell's key is its tornadoes' ids without their window bit.
        val had = supercells.count { it in with }
        println("supercells ${supercells.size}, with a tornado $had")
        assertTrue(supercells.size > 50)
        assertTrue("$had of ${supercells.size}", had > 0.8 * supercells.size)
    }

    private fun key(s: Storms.Storm): Long =
        (((s.cx.toLong() shl 42) xor (s.cy.toLong() shl 21) xor (s.cycle and 0x1fffffL)) shl 1) and Long.MAX_VALUE

    @Test
    fun `two instances agree on where they are and what they blow`() {
        val a = weather(WeatherIntensity.EXTREME)
        val b = weather(WeatherIntensity.EXTREME)
        for (k in 0 until 20) all(b, 50_000.0 + k * 3_000.0)
        val sa = AirSample(); val sb = AirSample()
        var compared = 0
        for (k in 0 until 30) {
            val t = k * 2_100.0 + 33.0
            val la = all(a, t); val lb = all(b, t)
            assertEquals(la.size, lb.size)
            for ((x, y) in la.zip(lb).take(6)) {
                assertEquals(x.id, y.id)
                assertEquals(x.centre, y.centre)
                assertEquals(x.strength, y.strength, 0.0)
                for (i in 0 until 6) {
                    val d = at(x.centre, x.coreRadius * (0.3 + 0.8 * i), i * 1.1)
                    val p = Vec3().setTo(d).mulInPlace(radius + ground(d) + 20.0 + 150.0 * (i % 3))
                    a.sample(p, t, sa); b.sample(p, t, sb)
                    assertEquals(sa.wind, sb.wind)
                    assertEquals(sa.lift, sb.lift, 0.0)
                    assertEquals(sa.turbulence, sb.turbulence, 0.0)
                    compared++
                }
            }
        }
        assertTrue(compared > 50)
    }

    @Test
    fun `only under supercells or in hurricane bands`() {
        val w = weather(WeatherIntensity.EXTREME)
        var fromBands = 0
        var checked = 0
        for (k in 0 until 40) {
            val t = k * 4_700.0 + 11.0
            for (tornado in all(w, t)) {
                checked++
                if (tornado.inHurricane) {
                    fromBands++
                    val hs = ArrayList<LiveHurricane>()
                    w.hurricanes(tornado.centre, t, 0.0, hs)
                    assertTrue("band tornado with no hurricane round it", hs.any { it.centre.distanceTo(tornado.centre) * radius < it.outerRadius })
                } else {
                    val cells = ArrayList<Storms.Storm>()
                    val e = Vec3(); val n = Vec3()
                    frame(tornado.centre, e, n)
                    w.stormModel.supercells(tornado.centre, e, n, 0.0, t, cells)
                    val c = Vec3()
                    assertTrue(
                        "tornado with no supercell over it",
                        cells.any { w.stormModel.centreAt(it, t, c).distanceTo(tornado.centre) * radius < 1.2 * it.core },
                    )
                }
            }
        }
        println("checked $checked, $fromBands in hurricane bands")
        assertTrue(checked > 100)
    }

    @Test
    fun `the core lifts a light craft and spins near a hundred metres a second`() {
        val (t, k) = strongest.filter { !it.second.overWater && !it.second.inHurricane }.maxByOrNull { it.second.strength }
            ?: error("no land tornado")
        assertTrue("strongest ${k.strength}", k.strength > 0.8)
        val w = weather(WeatherIntensity.EXTREME)
        val s = AirSample()
        val up = k.centre
        w.sample(Vec3().setTo(up).mulInPlace(radius + ground(up) + 100.0), t, s)
        val rising = s.wind dot up
        // A 50 kg craft a metre across, drag coefficient 1, in sea level air.
        val needed = kotlin.math.sqrt(50.0 * 9.81 / (0.5 * 1.2 * 1.0))
        println("strength ${k.strength}, core ${k.coreRadius} m: rising $rising m/s, needs $needed")
        assertTrue("rising $rising vs $needed", rising > needed)
        // Its own wind, the storm's taken away, with the inflow low down.
        val most = vortexWind(w, t, k)
        val expected = Tornadoes.MAX_WIND * k.strength
        println("its own wind round the core $most, expected about $expected")
        assertTrue("$most vs $expected", most > 0.85 * expected && most < 1.15 * expected)
    }

    /** The most the vortex itself adds to the wind round [k]'s core, 30 m up. */
    private fun vortexWind(w: Weather, t: Double, k: LiveTornado): Double {
        val on = AirSample(); val off = AirSample()
        var most = 0.0
        for (i in 0 until 16) for (f in listOf(0.8, 1.0, 1.25)) {
            val d = at(k.centre, k.coreRadius * f, i * Math.PI / 8.0)
            val p = Vec3().setTo(d).mulInPlace(radius + ground(d) + 30.0)
            w.tornadoModel.enabled = true; w.sample(p, t, on)
            w.tornadoModel.enabled = false; w.sample(p, t, off)
            most = maxOf(most, on.wind.distanceTo(off.wind))
        }
        w.tornadoModel.enabled = true
        return most
    }

    @Test
    fun `a waterspout is weaker than a tornado on land`() {
        val spouts = strongest.filter { it.second.overWater }
        val land = strongest.filter { !it.second.overWater && !it.second.inHurricane }
        println("${spouts.size} waterspouts, ${land.size} on land")
        assertTrue(spouts.size > 5 && land.size > 5)
        val w = weather(WeatherIntensity.EXTREME)
        val spout = spouts.maxOf { vortexWind(w, it.first, it.second) }
        val tornado = land.maxOf { vortexWind(w, it.first, it.second) }
        println("worst waterspout $spout m/s, worst tornado $tornado m/s")
        assertTrue(spout < 0.6 * tornado)
        // A waterspout is over half sea, so at most this strong.
        assertTrue(spouts.all { it.second.strength <= 1.0 - 0.5 * (1.0 - Tornadoes.SPOUT) + 1e-9 })
    }

    @Test
    fun `nothing far from the vortex`() {
        val w = weather(WeatherIntensity.EXTREME)
        val model = w.tornadoModel
        val on = AirSample(); val off = AirSample()
        var near = 0
        for ((t, k) in strongest.take(40)) {
            for (i in 0 until 6) {
                val far = at(k.centre, k.reach * 1.05 + 500.0 * i, i * 1.0)
                val p = Vec3().setTo(far).mulInPlace(radius + ground(far) + 50.0)
                model.enabled = true; w.sample(p, t, on)
                model.enabled = false; w.sample(p, t, off)
                assertEquals(off.wind, on.wind)
                assertEquals(off.turbulence, on.turbulence, 0.0)
            }
            val core = at(k.centre, k.coreRadius, 0.0)
            val p = Vec3().setTo(core).mulInPlace(radius + ground(core) + 50.0)
            model.enabled = true; w.sample(p, t, on)
            model.enabled = false; w.sample(p, t, off)
            if (on.wind.distanceTo(off.wind) > 10.0 * k.strength) near++
        }
        model.enabled = true
        assertTrue("felt at the core $near of 40", near >= 38)
        // Nor high above it.
        for ((t, k) in strongest.take(20)) {
            val p = Vec3().setTo(k.centre).mulInPlace(radius + ground(k.centre) + Tornadoes.TOP + 100.0)
            model.enabled = true; w.sample(p, t, on)
            model.enabled = false; w.sample(p, t, off)
            assertEquals(off.wind, on.wind)
        }
        model.enabled = true
    }

    @Test
    fun `weaker near launch sites`() {
        val model = weather(WeatherIntensity.EXTREME).tornadoModel
        val site = World.launchSites.first { it.bodyId == terra.id }
        val d = SolarSystem.surfaceDirection(site.latitude, site.longitude)
        assertEquals(1.0 - Tornadoes.SITE_SHY, model.siteFactor(d), 1e-9)
        val sites = World.launchSites.filter { it.bodyId == terra.id }.map { SolarSystem.surfaceDirection(it.latitude, it.longitude) }
        val clear = (0 until 50).map { at(d, 30_000.0 + 20_000.0 * it, it * 0.9) }
            .first { p -> sites.all { it.distanceTo(p) * radius > Tornadoes.SITE_FAR } }
        assertEquals(1.0, model.siteFactor(clear), 0.0)
        // Every one seen is held to it.
        for ((_, k) in strongest) assertTrue(k.strength <= model.siteFactor(k.centre) + 1e-9)
    }

    @Test
    fun `no tornado work unless a storm is in reach`() {
        val w = weather(WeatherIntensity.EXTREME)
        val model = w.tornadoModel
        val s = AirSample()
        val p = Vec3()
        var worked = 0
        for (i in 0 until 5_000) {
            val d = Vec3(cos(i * 0.37), sin(i * 0.61) * 0.8, sin(i * 0.37)).normalizeInPlace()
            val before = model.tries
            w.sample(p.setTo(d).mulInPlace(radius + ground(d) + 300.0), 1_000.0 + i * 0.05, s)
            if (model.tries != before) {
                worked++
                assertTrue(model.storms.supercellsSeen.isNotEmpty() || model.hurricanes.seenCount > 0)
            }
        }
        assertTrue("worked at $worked of 5000", worked < 2_500)
    }

    @Test
    fun `spin and place stay continuous through a life`() {
        val w = weather(WeatherIntensity.EXTREME)
        var checked = 0
        for ((t0, k0) in strongest.filter { !it.second.inHurricane }.take(25)) {
            var last: LiveTornado? = null
            var t = t0 - k0.age
            while (t < t0 - k0.age + k0.life) {
                val k = all(w, t).firstOrNull { it.id == k0.id }
                if (k != null) {
                    assertEquals(k0.turn, k.turn, 0.0)
                    last?.let { assertTrue("jumped ${it.centre.distanceTo(k.centre) * radius} m", it.centre.distanceTo(k.centre) * radius < 600.0) }
                    last = k; checked++
                } else last = null
                t += 5.0
            }
        }
        assertTrue(checked > 100)
    }

    companion object {
        const val STEP = 120.0
        const val HOURS = 8.0
    }
}
