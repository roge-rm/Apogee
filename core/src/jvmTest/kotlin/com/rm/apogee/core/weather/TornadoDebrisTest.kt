package com.rm.apogee.core.weather

import com.rm.apogee.core.craft.CraftDesign
import com.rm.apogee.core.craft.PlacedPart
import com.rm.apogee.core.math.Vec3
import com.rm.apogee.core.orbit.SolarSystem
import com.rm.apogee.core.part.StockParts
import com.rm.apogee.core.world.LaunchSite
import com.rm.apogee.core.world.World
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.asin
import kotlin.math.atan2

/**
 * Tornado debris: only round a land tornado's foot and low down, none off waterspouts, the same on
 * every instance; a craft parked in its path takes a few light knocks, the same on two servers,
 * and none on a replica.
 */
class TornadoDebrisTest {
    private val system = SolarSystem.defaultSystem()
    private val terra = system.body(SolarSystem.HOMEWORLD_ID)
    private val radius = terra.radius
    private val catalog = StockParts.catalog
    private val config = WeatherConfig(intensity = WeatherIntensity.EXTREME)

    /** Land and sea supercell tornadoes seen over a few hours, each once in the middle of its life, and when. */
    private val seen: List<Pair<Double, LiveTornado>> by lazy {
        val w = Weather(terra, config)
        val out = LinkedHashMap<Long, Pair<Double, LiveTornado>>()
        var t = 0.0
        while (t < 8 * 3_600.0) {
            val list = ArrayList<LiveTornado>()
            w.tornadoes(Vec3.unitY(), t, Double.MAX_VALUE, list)
            for (k in list) if (!k.inHurricane && k.age / k.life in 0.35..0.6 && k.id !in out) out[k.id] = t to k
            t += 120.0
        }
        out.values.toList()
    }

    private fun offset(c: Vec3, metres: Double): Vec3 {
        val e = Vec3(); val n = Vec3()
        frame(c, e, n)
        return Vec3().setTo(c).mulInPlace(radius).addScaledInPlace(e, metres).normalizeInPlace()
    }

    @Test
    fun `debris round a land tornado's foot only, low down, none off waterspouts`() {
        val a = Weather(terra, config)
        val b = Weather(terra, config)
        val land = seen.filter { !it.second.overWater && it.second.strength > 0.3 }
        val sea = seen.filter { it.second.overWater }
        assertTrue("land ones ${land.size}", land.size > 10)
        assertTrue("sea ones ${sea.size}", sea.size > 10)
        for ((t, k) in land.take(20)) {
            val at = a.debrisAt(k.centre, 20.0, t)
            assertTrue("at its foot: $at", at > 0.2)
            assertEquals(at, b.debrisAt(k.centre, 20.0, t), 0.0)
            assertEquals(0.0, a.debrisAt(k.centre, 400.0, t), 0.0)
            assertEquals(0.0, a.debrisAt(offset(k.centre, 3.2 * k.coreRadius + 50.0), 20.0, t), 0.0)
        }
        for ((t, k) in sea.take(20)) assertEquals(0.0, a.debrisAt(k.centre, 10.0, t), 0.0)
        assertTrue(Weather(terra, WeatherConfig(intensity = WeatherIntensity.NORMAL)).let { !it.debrisPossible })
    }

    private fun craft() = CraftDesign(
        "Tossed",
        listOf(
            PlacedPart("tank-cask2", Vec3.zero()),
            PlacedPart("probe-mote", Vec3(0.0, 1.2, 0.0), parentIndex = 0),
            PlacedPart("panel-glint", Vec3(0.66, 0.3, 0.0), parentIndex = 0),
        ),
        emptyList(),
        catalog.contentHash,
    )

    @Test
    fun `a craft parked in its path takes a few light knocks, on the server only`() {
        val w = Weather(terra, config)
        val (time, pick) = seen.filter { !it.second.overWater && terra.terrain!!.elevation(it.second.centre) > 5.0 }
            .maxBy { (t, k) -> w.debrisAt(k.centre, 5.0, t) * k.coreRadius }
        val start = time - pick.age
        val end = start + pick.life + 60.0
        fun parked(replica: Boolean): List<Double> {
            val world = World.default(catalog).also { it.weatherConfig = config }
            world.syncClock(start - 120.0)
            val d = pick.centre
            val v = world.spawnOnSurface(craft(), LaunchSite("debris", "", "terra", asin(d.y), atan2(d.z, d.x)))
            world.isReplica = replica
            repeat(30 * 60) { world.step(1.0 / 60.0) }
            assertTrue("parked", v.dormant)
            world.advanceOnRails(end - world.time)
            assertEquals("warped through", end, world.time, 1.0)
            repeat(120) { world.step(1.0 / 60.0) }
            return v.health.toList()
        }
        val a = parked(false)
        val b = parked(false)
        val r = parked(true)
        println("core %.0f m, strength %.2f, health after: $a".format(pick.coreRadius, pick.strength))
        assertEquals(a, b)
        assertTrue("knocked: $a", a.any { it < 1.0 })
        assertTrue("light knocks on the tank: $a", a[0] > 0.6)
        assertTrue("replica untouched: $r", r.all { it == 1.0 })
    }
}
