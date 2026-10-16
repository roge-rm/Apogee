package com.rm.apogee.core.weather

import com.rm.apogee.core.math.Vec3
import com.rm.apogee.core.orbit.CelestialBody
import com.rm.apogee.core.orbit.SolarSystem
import com.rm.apogee.core.part.StockParts
import com.rm.apogee.core.sea.Sea
import com.rm.apogee.core.sea.SeaSample
import com.rm.apogee.core.world.World
import com.rm.apogee.core.world.WorldSave
import com.rm.apogee.core.world.WorldStore
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Hurricanes, tornadoes and tsunamis agree everywhere they're worked out: a server that's run for
 * hours and warped, a replica that joined at that moment, the same world saved and loaded, and the
 * renderer's own weather and sea. Lists bit-equal, and the sea and air bit-equal at points round
 * each event. The caches instances share are emptied before each works things out.
 */
class ExtremeAgreeTest {
    private val catalog = StockParts.catalog
    private val config = WeatherConfig(intensity = WeatherIntensity.EXTREME)
    private val json = Json { classDiscriminator = "type" }

    private class Lists(val hurricanes: List<LiveHurricane>, val tornadoes: List<LiveTornado>, val tsunamis: List<LiveTsunami>)

    private fun lists(w: Weather, time: Double): Lists {
        val h = ArrayList<LiveHurricane>(); w.hurricanes(Vec3.unitY(), time, Double.MAX_VALUE, h)
        val t = ArrayList<LiveTornado>(); w.tornadoes(Vec3.unitY(), time, Double.MAX_VALUE, t)
        val s = ArrayList<LiveTsunami>(); w.tsunamis(Vec3.unitY(), time, Double.MAX_VALUE, s)
        return Lists(h.sortedBy { it.id }, t.sortedBy { it.id }, s.sortedBy { it.id })
    }

    /** The first time from 6 h on, every 10 min, with a hurricane, a tornado and a tsunami all live on Terra. */
    private val busy: Double by lazy {
        val terra = SolarSystem.defaultSystem().body(SolarSystem.HOMEWORLD_ID)
        val w = Weather(terra, config)
        var t = 6 * 3_600.0
        while (t < 200 * 3_600.0) {
            val l = lists(w, t)
            if (l.hurricanes.isNotEmpty() && l.tornadoes.isNotEmpty() && l.tsunamis.isNotEmpty()) return@lazy t
            t += 600.0
        }
        error("nothing busy in 200 h")
    }

    private fun world(at: Double, save: WorldSave? = null): World =
        World(SolarSystem.defaultSystem(), catalog).also {
            it.restore(save ?: WorldSave(catalogHash = catalog.contentHash, universeTime = at, nextVesselId = 1L, weather = config))
        }

    private fun terraOf(world: World): CelestialBody = world.system.body(SolarSystem.HOMEWORLD_ID)

    private fun bits(a: Double) = java.lang.Double.doubleToLongBits(a)

    private fun same(what: String, a: Double, b: Double) = assertEquals(what, bits(a), bits(b))

    private fun same(what: String, a: Vec3, b: Vec3) {
        same("$what.x", a.x, b.x); same("$what.y", a.y, b.y); same("$what.z", a.z, b.z)
    }

    private fun same(who: String, a: Lists, b: Lists) {
        assertEquals("$who hurricanes", a.hurricanes.map { it.id }, b.hurricanes.map { it.id })
        assertEquals("$who tornadoes", a.tornadoes.map { it.id }, b.tornadoes.map { it.id })
        assertEquals("$who tsunamis", a.tsunamis.map { it.id }, b.tsunamis.map { it.id })
        for ((x, y) in a.hurricanes.zip(b.hurricanes)) {
            val w = "$who hurricane ${x.id}"
            same("$w centre", x.centre, y.centre); same("$w heading", x.heading, y.heading)
            same("$w eyewall", x.maxWindRadius, y.maxWindRadius); same("$w outer", x.outerRadius, y.outerRadius)
            same("$w strength", x.strength, y.strength); same("$w speed", x.speed, y.speed); same("$w turn", x.turn, y.turn)
        }
        for ((x, y) in a.tornadoes.zip(b.tornadoes)) {
            val w = "$who tornado ${x.id}"
            same("$w centre", x.centre, y.centre); same("$w velocity", x.velocity, y.velocity)
            same("$w core", x.coreRadius, y.coreRadius); same("$w reach", x.reach, y.reach)
            same("$w strength", x.strength, y.strength); same("$w age", x.age, y.age); same("$w life", x.life, y.life)
            same("$w turn", x.turn, y.turn)
            assertEquals("$w water", x.overWater, y.overWater); assertEquals("$w band", x.inHurricane, y.inHurricane)
        }
        for ((x, y) in a.tsunamis.zip(b.tsunamis)) {
            val w = "$who tsunami ${x.id}"
            same("$w origin", x.origin, y.origin); same("$w start", x.start, y.start)
            same("$w strength", x.strength, y.strength); same("$w period", x.period, y.period)
            same("$w age", x.age, y.age); same("$w front", x.frontRadius, y.frontRadius); same("$w arrives", x.arrivesHere, y.arrivesHere)
        }
    }

    /** Points round each event: its middle and a ring about it, and the Cape. Unit, body-fixed. */
    private fun points(l: Lists, r: Double): List<Vec3> = buildList {
        fun ring(centre: Vec3, angle: Double, count: Int) {
            val east = Vec3(centre.z, 0.0, -centre.x).normalizeInPlace()
            val north = Vec3().setTo(centre).crossInPlace(east)
            for (k in 0 until count) {
                val a = 2.0 * Math.PI * k / count
                add(Vec3().setTo(centre).addScaledInPlace(east, kotlin.math.cos(a) * angle).addScaledInPlace(north, kotlin.math.sin(a) * angle).normalizeInPlace())
            }
        }
        for (h in l.hurricanes) { add(h.centre.copy()); ring(h.centre, h.maxWindRadius / r, 4); ring(h.centre, h.outerRadius * 0.5 / r, 4) }
        for (t in l.tornadoes) { add(t.centre.copy()); ring(t.centre, t.coreRadius * 1.5 / r, 4) }
        for (s in l.tsunamis) { add(s.origin.copy()); ring(s.origin, s.frontRadius / r, 12) }
        val cape = World.launchSites.first { it.id == "cape" }
        add(SolarSystem.surfaceDirection(cape.latitude, cape.longitude))
    }

    private class Here(val sea: SeaSample?, val air: AirSample)

    private fun at(body: CelestialBody, sea: Sea?, weather: Weather, d: Vec3, time: Double): Here {
        val ground = body.radius + maxOf(body.terrain?.elevation(d) ?: 0.0, 0.0)
        val s = sea?.let { val out = SeaSample(); it.sample(Vec3().setTo(d).mulInPlace(body.radius), time, out, below = 1.0); out }
        val air = AirSample(); weather.sample(Vec3().setTo(d).mulInPlace(ground + 30.0), time, air)
        return Here(s, air)
    }

    private fun same(what: String, a: Here, b: Here) {
        val x = a.sea; val y = b.sea
        if (x != null && y != null) {
            same("$what height", x.height, y.height); same("$what tide", x.tide, y.tide); same("$what tsunami", x.tsunami, y.tsunami)
            same("$what normal", x.normal, y.normal); same("$what velocity", x.velocity, y.velocity); same("$what flow", x.flow, y.flow)
            same("$what breaking", x.breaking, y.breaking); same("$what Hs", x.significantHeight, y.significantHeight)
        } else assertEquals("$what sea", x == null, y == null)
        same("$what wind", a.air.wind, b.air.wind); same("$what lift", a.air.lift, b.air.lift)
        same("$what turbulence", a.air.turbulence, b.air.turbulence); same("$what rain", a.air.precipitation, b.air.precipitation)
        same("$what cloud", a.air.cloudDensity, b.air.cloudDensity)
    }

    @Test
    fun `server, replica, a saved and loaded world and the renderer agree`() {
        val time = busy
        // A server that's run on for hours, queried as it went, then warped up to the busy time.
        val server = world(time - 4 * 3_600.0)
        val serverTerra = terraOf(server)
        val serverWeather = server.weatherFor(serverTerra)!!
        var t = server.time
        while (t < time - 3_600.0) {
            lists(serverWeather, t)
            serverTerra.ocean!!.sea!!.height(Vec3.unitX().mulInPlace(serverTerra.radius), t)
            t += 900.0
        }
        repeat(30) { server.step(1.0 / 60.0) }
        server.advanceOnRails(time - server.time)
        repeat(60) { server.step(1.0 / 60.0) }
        val now = server.time
        assertTrue("warped to the busy time: $now vs $time", kotlin.math.abs(now - time) < 5.0)

        // What the server works out, before anything else looks.
        val truth = lists(serverWeather, now)
        println("busy at ${time / 3_600.0} h: ${truth.hurricanes.size} hurricanes, ${truth.tornadoes.size} tornadoes, ${truth.tsunamis.size} tsunamis")
        val spots = points(truth, serverTerra.radius)
        val expected = spots.map { at(serverTerra, serverTerra.ocean!!.sea, serverWeather, it, now) }
        assertTrue("points: ${spots.size}", spots.size > 20)
        val text = json.encodeToString(WorldSave.serializer(), server.save())

        // A replica that joined just now, the world saved and loaded, and the renderer's own, each
        // from empty shared caches, alternately points first in reverse or lists first.
        fun check(who: String, body: CelestialBody, sea: Sea?, weather: Weather, reverse: Boolean) {
            if (reverse) {
                for (k in spots.indices.reversed()) same("$who point $k", expected[k], at(body, sea, weather, spots[k], now))
                same(who, truth, lists(weather, now))
            } else {
                same(who, truth, lists(weather, now))
                for (k in spots.indices) same("$who point $k", expected[k], at(body, sea, weather, spots[k], now))
            }
        }
        Weather.forgetShared(serverTerra)
        val replica = world(now)
        check("replica", terraOf(replica), terraOf(replica).ocean!!.sea, replica.weatherFor(terraOf(replica))!!, reverse = true)

        Weather.forgetShared(serverTerra)
        val loaded = world(now, WorldStore.decode(text))
        assertEquals("loaded time", bits(now), bits(loaded.time))
        check("loaded", terraOf(loaded), terraOf(loaded).ocean!!.sea, loaded.weatherFor(terraOf(loaded))!!, reverse = false)

        Weather.forgetShared(serverTerra)
        val drawn = SolarSystem.defaultSystem()
        val drawnTerra = drawn.body(SolarSystem.HOMEWORLD_ID)
        val moon = drawn.bodies.values.firstOrNull { it.parentId == drawnTerra.id && it.orbit != null }
        val renderWeather = Weather(drawnTerra, config)
        check("renderer", drawnTerra, Sea(drawnTerra, moon, Weather(drawnTerra, config), config.seed), renderWeather, reverse = true)
    }

    @Test
    fun `a replica stepping along with the server keeps agreeing`() {
        val time = busy
        val server = world(time - 120.0)
        val replica = world(time - 120.0)
        val sw = server.weatherFor(terraOf(server))!!
        val rw = replica.weatherFor(terraOf(replica))!!
        // The replica only asks now and then, the server every few seconds.
        var k = 0
        while (server.time < time) {
            repeat(60) { server.step(1.0 / 60.0); replica.step(1.0 / 60.0) }
            lists(sw, server.time)
            if (k++ % 10 == 0) same("step $k", lists(sw, server.time), lists(rw, replica.time))
        }
        val now = server.time
        assertEquals(bits(now), bits(replica.time))
        val truth = lists(sw, now)
        val spots = points(truth, terraOf(server).radius)
        val expected = spots.map { at(terraOf(server), terraOf(server).ocean!!.sea, sw, it, now) }
        // The replica's from empty shared caches, points first and in reverse.
        Weather.forgetShared(terraOf(server))
        for (j in spots.indices.reversed()) same("point $j", expected[j], at(terraOf(replica), terraOf(replica).ocean!!.sea, rw, spots[j], now))
        same("end", truth, lists(rw, now))
    }
}
