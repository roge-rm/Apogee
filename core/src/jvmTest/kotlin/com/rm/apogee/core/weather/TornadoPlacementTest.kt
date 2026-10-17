package com.rm.apogee.core.weather

import com.rm.apogee.core.math.Vec3
import com.rm.apogee.core.orbit.SolarSystem
import com.rm.apogee.core.terrain.EarthRelief
import com.rm.apogee.core.terrain.EarthMoisture
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Where Terra's land tornadoes come down: most over warm, moist, flat land, few over mountains,
 * deserts, ice and cold country. Each tornado is counted once, where it was first seen.
 */
class TornadoPlacementTest {
    private val system = SolarSystem.defaultSystem()
    private val terra = system.body(SolarSystem.HOMEWORLD_ID)
    private val terrain = terra.terrain!!
    private val radius = terra.radius
    private val heightScale = radius / EarthRelief.EARTH_RADIUS

    enum class Land { GOOD, MOUNTAIN, DESERT, COLD, OTHER }

    private fun latitude(p: Vec3) = Math.toDegrees(StrictMath.atan2(p.y, sqrt(p.x * p.x + p.z * p.z)))
    private fun longitude(p: Vec3) = Math.toDegrees(StrictMath.atan2(p.z, p.x))

    /** How much the ground rises and falls within 4 km, in Earth metres. */
    private fun relief(p: Vec3): Double {
        val e = Vec3(); val n = Vec3()
        frame(p, e, n)
        var lo = terrain.elevation(p); var hi = lo
        for (k in 0 until 6) {
            val a = k * Math.PI / 3.0
            val q = Vec3().setTo(p).mulInPlace(radius).addScaledInPlace(e, cos(a) * 4_000.0).addScaledInPlace(n, sin(a) * 4_000.0).normalizeInPlace()
            val h = terrain.elevation(q)
            lo = minOf(lo, h); hi = maxOf(hi, h)
        }
        return (hi - lo) / heightScale
    }

    private fun classify(w: Weather, p: Vec3): Land? {
        val h = terrain.elevation(p)
        if (h < 1.0) return null
        val lat = abs(latitude(p))
        if (w.blizzardModel.coldness(p) > 0.5 || lat > 52.0) return Land.COLD
        if (h / heightScale > 1_500.0 || relief(p) > 900.0) return Land.MOUNTAIN
        if (w.dustModel.dryness(p) > 0.5 || EarthMoisture.sample(longitude(p), latitude(p)) < 0.3) return Land.DESERT
        val moist = EarthMoisture.sample(longitude(p), latitude(p))
        if (lat in 22.0..50.0 && moist >= 0.42) return Land.GOOD
        return Land.OTHER
    }


    @Test
    fun `warm moist flat land gets the most tornadoes`() {
        val w = Weather(terra, WeatherConfig(intensity = WeatherIntensity.EXTREME))
        // The land's own shares, from points spread evenly over the world.
        val area = HashMap<Land, Int>()
        val golden = Math.PI * (3.0 - sqrt(5.0))
        val points = 40_000
        for (i in 0 until points) {
            val y = 1.0 - 2.0 * (i + 0.5) / points
            val r = sqrt(1.0 - y * y)
            val p = Vec3(cos(golden * i) * r, y, sin(golden * i) * r)
            val c = classify(w, p) ?: continue
            area[c] = (area[c] ?: 0) + 1
        }
        val seen = HashSet<Long>()
        val count = HashMap<Land, Int>()
        var spouts = 0; var bands = 0
        var t = 0.0
        val list = ArrayList<LiveTornado>()
        while (t < HOURS * 3_600.0) {
            list.clear()
            w.tornadoes(Vec3(1.0, 0.0, 0.0), t, 1e9, list)
            for (k in list) {
                if (!seen.add(k.id)) continue
                if (k.inHurricane) { bands++; continue }
                if (k.overWater) { spouts++; continue }
                val c = classify(w, k.centre) ?: continue
                count[c] = (count[c] ?: 0) + 1
            }
            t += STEP
        }
        val landArea = area.values.sum().toDouble()
        val landCount = count.values.sum().toDouble()
        println("tornadoes on land ${landCount.toInt()}, waterspouts $spouts, in hurricanes $bands")
        for (c in Land.entries) {
            val a = (area[c] ?: 0) / landArea
            val n = (count[c] ?: 0) / landCount
            println("  $c: land %.3f, tornadoes %.3f (%d), per land %.2f".format(a, n, count[c] ?: 0, n / a))
        }
        fun share(c: Land) = (count[c] ?: 0) / landCount
        fun density(c: Land) = share(c) / ((area[c] ?: 1) / landArea)
        assertTrue("plenty on land: $landCount", landCount > 200)
        for (c in listOf(Land.MOUNTAIN, Land.DESERT, Land.COLD, Land.OTHER)) {
            assertTrue("good land gets the most: ${share(Land.GOOD)} vs $c ${share(c)}", share(Land.GOOD) > share(c))
        }
        for (c in listOf(Land.MOUNTAIN, Land.DESERT, Land.COLD)) {
            assertTrue("$c gets few for its size: ${density(c)}", density(c) < 0.35 * density(Land.GOOD))
        }
    }

    companion object {
        const val STEP = 150.0
        const val HOURS = 48.0
    }
}
