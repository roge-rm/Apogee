package com.rm.apogee.core.weather

import com.rm.apogee.core.math.Vec3
import com.rm.apogee.core.orbit.CelestialBody
import com.rm.apogee.core.orbit.SolarSystem
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * The air comes out bit for bit the same whatever was asked of the instance before: points in
 * another order, after other places, other times and the storm, hurricane, dust, blizzard and quake lists.
 */
class WeatherOrderTest {
    private val system = SolarSystem.defaultSystem()
    private val extreme = WeatherConfig(intensity = WeatherIntensity.EXTREME)

    private fun bits(a: Double) = java.lang.Double.doubleToLongBits(a)

    private fun fields(a: AirSample): List<Long> = listOf(
        bits(a.wind.x), bits(a.wind.y), bits(a.wind.z), bits(a.lift), bits(a.turbulence),
        bits(a.cloudDensity), (a.cloudType?.ordinal ?: -1).toLong(), bits(a.precipitation),
        bits(a.visibility), bits(a.dust), bits(a.storm), bits(a.gloom),
        bits(a.snow), bits(a.snowTop), bits(a.snowfall),
    )

    private fun points(body: CelestialBody, seed: Long): List<Vec3> {
        val rnd = java.util.Random(seed)
        val heights = doubleArrayOf(2.0, 20.0, 150.0, 900.0, 3_000.0, 12_000.0)
        return List(120) { k ->
            val d = Vec3(rnd.nextGaussian(), rnd.nextGaussian(), rnd.nextGaussian()).normalizeInPlace()
            // Clusters of near points too, so neighbours share lattice cells.
            if (k % 3 != 0) d.addScaledInPlace(Vec3(rnd.nextGaussian(), rnd.nextGaussian(), rnd.nextGaussian()), 2e-4).normalizeInPlace()
            val ground = body.terrain?.elevation(d)?.coerceAtLeast(0.0) ?: 0.0
            d.mulInPlace(body.radius + ground + heights[k % heights.size])
        }
    }

    /** Asks [w] lots of unrelated things around [time]. */
    private fun busy(w: Weather, body: CelestialBody, time: Double, spots: List<Vec3>) {
        val air = AirSample()
        // The same points along the way up to the time, as a craft would.
        var t = time - 3 * 3_600.0
        while (t < time) { for (p in spots) w.sample(p, t, air); t += 700.0 }
        for (p in points(body, 99)) for (dt in doubleArrayOf(-7_200.0, -60.0, 0.5, 3_600.0)) w.sample(p, time + dt, air)
        val up = Vec3.unitY()
        w.hurricanes(up, time, Double.MAX_VALUE, ArrayList())
        w.tornadoes(up, time, Double.MAX_VALUE, ArrayList())
        w.tsunamis(up, time, Double.MAX_VALUE, ArrayList())
        w.quakes(up, time, Double.MAX_VALUE, ArrayList())
        w.dustStorms(up, time, Double.MAX_VALUE, ArrayList())
        w.blizzards(up, time, Double.MAX_VALUE, ArrayList())
        w.globalStorms(time, ArrayList())
        for (p in points(body, 7)) {
            val d = Vec3().setTo(p).normalizeInPlace()
            w.pressure(d, time)
            w.surfaceWind(d, time - 600.0, Vec3())
            w.quakeShake(d, time, Vec3())
        }
    }

    @Test
    fun `the same air whatever was asked before`() {
        val failures = ArrayList<String>()
        for (id in listOf("rubra", SolarSystem.HOMEWORLD_ID, "aurantia", "caligo")) {
            val body = system.body(id)
            for (time in doubleArrayOf(20 * 3_600.0, 3 * 86_400.0 + 1_234.5, 137 * 86_400.0 + 4_321.0)) {
                val spots = points(body, 1)
                val first = Weather(body, extreme)
                val expected = spots.map { first.sample(it, time, AirSample()) }
                val second = Weather(body, extreme)
                busy(second, body, time, spots)
                var bad = 0
                for (k in spots.indices.reversed()) {
                    val got = second.sample(spots[k], time, AirSample())
                    if (fields(got) != fields(expected[k])) bad++
                }
                if (bad > 0) failures += "$id at $time: $bad of ${spots.size} differ"
            }
        }
        assertEquals(failures.joinToString("\n"), 0, failures.size)
    }

    /** Rubra in a world storm with dust off, asked along the way as DustStormTest does. */
    @Test
    fun `rubra asked along the way with dust off`() {
        val body = system.body("rubra")
        val probe = Weather(body, extreme)
        var time = 0.0
        while (true) {
            val s = ArrayList<LiveDust>().also { probe.dustStorms(Vec3.unitY(), time, Double.MAX_VALUE, it) }.firstOrNull { it.kind == DustKind.WORLD }
            if (s != null && s.age > 0.45 * s.life && s.age < 0.6 * s.life) break
            time += 3 * 3_600.0
        }
        val rnd = java.util.Random(3)
        val dirs = List(60) { Vec3(rnd.nextGaussian(), rnd.nextGaussian(), rnd.nextGaussian()).normalizeInPlace() }
        fun over(d: Vec3) = Vec3().setTo(d).mulInPlace(body.radius + maxOf(body.terrain!!.elevation(d), 0.0) + 50.0)
        val off = Weather(body, extreme).also { it.dustModel.enabled = false }
        val air = AirSample()
        var t = time - 3 * 3_600.0
        while (t < time) { for (d in dirs) off.sample(over(d), t, air); t += 700.0 }
        val without = dirs.map { off.sample(over(it), time, AirSample()) }
        val freshOff = Weather(body, extreme).also { it.dustModel.enabled = false }
        var bad = 0
        for (k in dirs.indices.reversed()) {
            val got = freshOff.sample(over(dirs[k]), time, AirSample())
            if (fields(got) != fields(without[k])) {
                bad++
                println("rubra $k at $time: ${fields(without[k])} against ${fields(got)}")
            }
        }
        assertEquals(0, bad)
    }
}
