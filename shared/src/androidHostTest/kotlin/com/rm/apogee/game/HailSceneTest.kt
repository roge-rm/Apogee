package com.rm.apogee.game

import com.rm.apogee.core.math.Vec3
import com.rm.apogee.core.orbit.SolarSystem
import com.rm.apogee.core.terrain.Noise
import com.rm.apogee.core.weather.CloudShape
import com.rm.apogee.core.weather.CloudType
import com.rm.apogee.core.weather.LiveHail
import com.rm.apogee.core.weather.Weather
import com.rm.apogee.core.weather.WeatherConfig
import com.rm.apogee.core.weather.WeatherIntensity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Hail seen from afar: a hailing tower's curtain is listed with its hail and drawn whiter. */
class HailSceneTest {
    private val terra = SolarSystem.defaultSystem().body(SolarSystem.HOMEWORLD_ID)
    private val weather = Weather(terra, WeatherConfig(intensity = WeatherIntensity.EXTREME))

    @Test
    fun `a hailing tower's curtain is whiter than a raining one's`() {
        val (time, shaft) = hailShaft(weather, 0.6)
        val shapes = ArrayList<CloudShape>()
        weather.clouds(shaft.centre, 60_000.0, time, shapes)
        val curtains = shapes.filter { it.type == CloudType.CUMULONIMBUS }.flatMap { it.rain }
        assertTrue("curtains: ${curtains.size}", curtains.isNotEmpty())
        val nearest = curtains.minBy { Vec3().setTo(it.centre).normalizeInPlace().distanceTo(shaft.centre) }
        println("shaft hail %.2f, its curtain's %.2f".format(shaft.hail, nearest.hail))
        assertEquals("the curtain carries the tower's hail", shaft.hail, nearest.hail, 0.05)
        val rain = hailColour(0.0, 0.8f)
        val hail = hailColour(nearest.hail, 0.8f)
        assertEquals("rain stays dark grey", 0.22f, rain[0], 1e-6f)
        assertTrue("hail paler: ${hail.toList()}", hail[0] > rain[0] + 0.2f && hail[2] > rain[2] + 0.2f)
        assertEquals(0.8f, hail[3], 1e-6f)
        assertTrue("whiter with harder hail", hailColour(1.0, 1f)[0] >= hail[0])
    }

    companion object {
        /** A shaft hailing at least [least] and when, from places and times over Terra. */
        fun hailShaft(weather: Weather, least: Double): Pair<Double, LiveHail> {
            val found = ArrayList<LiveHail>()
            for (i in 0 until 6_000) {
                val lat = (Noise.hash(5, i, 0, 0) - 0.5) * 1.6
                val lon = Noise.hash(5, i, 1, 0) * 2 * Math.PI
                val d = Vec3(kotlin.math.cos(lat) * kotlin.math.cos(lon), kotlin.math.sin(lat), kotlin.math.cos(lat) * kotlin.math.sin(lon))
                val t = 6 * 3_600.0 + Noise.hash(5, i, 2, 0) * 100 * 3_600.0
                found.clear()
                weather.hailShafts(d, t, 100_000.0, found)
                found.firstOrNull { it.hail >= least }?.let { return t to it }
            }
            error("no shaft hailing $least")
        }
    }
}
