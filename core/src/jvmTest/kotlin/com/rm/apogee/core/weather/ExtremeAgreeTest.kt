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
 * Hurricanes, tornadoes, tsunamis, quakes, dust storms, blizzards and hail agree everywhere they're worked out: a server that's run for
 * hours and warped, a replica that joined at that moment, the same world saved and loaded, and the
 * renderer's own weather and sea. Lists bit-equal, and the sea, air and the ground's shaking
 * bit-equal at points round each event. The caches instances share are emptied before each works things out.
 */
class ExtremeAgreeTest {
    private val catalog = StockParts.catalog
    private val config = WeatherConfig(intensity = WeatherIntensity.EXTREME)
    private val json = Json { classDiscriminator = "type" }

    private class Lists(val hurricanes: List<LiveHurricane>, val tornadoes: List<LiveTornado>, val tsunamis: List<LiveTsunami>, val quakes: List<LiveQuake>, val dust: List<LiveDust>, val blizzards: List<LiveBlizzard>, val eruptions: List<LiveEruption> = emptyList())

    private fun lists(w: Weather, time: Double): Lists {
        val h = ArrayList<LiveHurricane>(); w.hurricanes(Vec3.unitY(), time, Double.MAX_VALUE, h)
        val t = ArrayList<LiveTornado>(); w.tornadoes(Vec3.unitY(), time, Double.MAX_VALUE, t)
        val s = ArrayList<LiveTsunami>(); w.tsunamis(Vec3.unitY(), time, Double.MAX_VALUE, s)
        val q = ArrayList<LiveQuake>(); w.quakes(Vec3.unitY(), time, Double.MAX_VALUE, q)
        val d = ArrayList<LiveDust>(); w.dustStorms(Vec3.unitY(), time, Double.MAX_VALUE, d)
        val b = ArrayList<LiveBlizzard>(); w.blizzards(Vec3.unitY(), time, Double.MAX_VALUE, b)
        val e = ArrayList<LiveEruption>(); w.eruptions(Vec3.unitY(), time, Double.MAX_VALUE, e)
        return Lists(h.sortedBy { it.id }, t.sortedBy { it.id }, s.sortedBy { it.id }, q.sortedBy { it.id }, d.sortedBy { it.id }, b.sortedBy { it.id }, e.sortedBy { it.id })
    }

    /** The first time from 6 h on, every 10 min, with a hurricane, a tornado, a tsunami, a recent quake, a haboob, a blizzard and an eruption all live on Terra. */
    private val busy: Double by lazy {
        val terra = SolarSystem.defaultSystem().body(SolarSystem.HOMEWORLD_ID)
        val w = Weather(terra, config)
        var t = 6 * 3_600.0
        while (t < 200 * 3_600.0) {
            val l = lists(w, t)
            if (l.hurricanes.isNotEmpty() && l.tornadoes.isNotEmpty() && l.tsunamis.isNotEmpty() && l.quakes.isNotEmpty() && l.dust.isNotEmpty() && l.blizzards.isNotEmpty() && l.eruptions.any { it.strength > 0.0 }) return@lazy t
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
        assertEquals("$who quakes", a.quakes.map { it.id }, b.quakes.map { it.id })
        for ((x, y) in a.quakes.zip(b.quakes)) {
            val w = "$who quake ${x.id}"
            assertEquals("$w main", x.mainId, y.mainId); assertEquals("$w sea", x.underSea, y.underSea)
            same("$w epicentre", x.epicentre, y.epicentre); same("$w start", x.start, y.start)
            same("$w magnitude", x.magnitude, y.magnitude); same("$w depth", x.depth, y.depth); same("$w peak", x.peak, y.peak)
            same("$w radius", x.strongRadius, y.strongRadius); same("$w reach", x.reach, y.reach); same("$w duration", x.duration, y.duration)
            same("$w age", x.age, y.age); same("$w arrives", x.arrivesHere, y.arrivesHere); same("$w here", x.peakHere, y.peakHere)
        }
        assertEquals("$who dust", a.dust.map { it.id }, b.dust.map { it.id })
        for ((x, y) in a.dust.zip(b.dust)) {
            val w = "$who dust ${x.id}"
            assertEquals("$w kind", x.kind, y.kind)
            same("$w centre", x.centre, y.centre); same("$w origin", x.origin, y.origin); same("$w heading", x.heading, y.heading)
            same("$w speed", x.speed, y.speed); same("$w width", x.halfWidth, y.halfWidth); same("$w height", x.height, y.height)
            same("$w reach", x.reach, y.reach); same("$w strength", x.strength, y.strength); same("$w age", x.age, y.age); same("$w life", x.life, y.life)
        }
        assertEquals("$who blizzards", a.blizzards.map { it.id }, b.blizzards.map { it.id })
        for ((x, y) in a.blizzards.zip(b.blizzards)) {
            val w = "$who blizzard ${x.id}"
            same("$w centre", x.centre, y.centre); same("$w origin", x.origin, y.origin); same("$w heading", x.heading, y.heading)
            same("$w speed", x.speed, y.speed); same("$w length", x.halfLength, y.halfLength); same("$w width", x.halfWidth, y.halfWidth)
            same("$w height", x.height, y.height); same("$w strength", x.strength, y.strength); same("$w age", x.age, y.age); same("$w life", x.life, y.life)
        }
        assertEquals("$who eruptions", a.eruptions.map { it.id }, b.eruptions.map { it.id })
        for ((x, y) in a.eruptions.zip(b.eruptions)) {
            val w = "$who eruption ${x.id}"
            same("$w vent", x.vent, y.vent); same("$w vent height", x.ventHeight, y.ventHeight); same("$w start", x.start, y.start)
            same("$w age", x.age, y.age); same("$w life", x.life, y.life); same("$w strength", x.strength, y.strength)
            same("$w top", x.top, y.top); same("$w column", x.columnTop, y.columnTop); same("$w drift", x.drift, y.drift)
            same("$w length", x.length, y.length); same("$w width", x.halfWidth, y.halfWidth)
            for (f in listOf(0.3, 0.7, 1.0)) {
                same("$w track $f", x.track.pointAt(x.track.length * f, Vec3()), y.track.pointAt(y.track.length * f, Vec3()))
            }
        }
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
        for (q in l.quakes) { add(q.epicentre.copy()); ring(q.epicentre, q.strongRadius / r, 4) }
        // Each haboob's front, and behind it.
        for (d in l.dust) {
            add(d.centre.copy()); ring(d.centre, 2_000.0 / r, 4)
            add(Vec3().setTo(d.centre).addScaledInPlace(d.heading, -5_000.0 / r).normalizeInPlace())
        }
        // Each blizzard's middle, its front edge and a ring about it.
        for (z in l.blizzards) {
            add(z.centre.copy()); ring(z.centre, 0.5 * z.halfWidth / r, 4)
            add(Vec3().setTo(z.centre).addScaledInPlace(z.heading, 0.9 * z.halfLength / r).normalizeInPlace())
        }
        // Each eruption's vent, and along and beside its cloud.
        for (e in l.eruptions) {
            add(e.vent.copy())
            for (f in listOf(0.25, 0.5, 0.8)) {
                val along = e.length * f
                val on = e.track.pointAt(along, Vec3())
                add(on.copy())
                add(on.mulInPlace(r).addScaledInPlace(e.track.sideAt(along, Vec3()), 0.6 * e.halfWidth).normalizeInPlace())
            }
        }
        val cape = World.launchSites.first { it.id == "cape" }
        add(SolarSystem.surfaceDirection(cape.latitude, cape.longitude))
    }

    /** Under the main towers of storms hailing at [time] near 300 places on Terra, unit, body-fixed. */
    private fun hailSpots(w: Weather, time: Double): List<Vec3> = buildList {
        val list = ArrayList<Storms.Storm>()
        val seen = HashSet<Storms.Storm>()
        for (i in 0 until 300) {
            val lat = (com.rm.apogee.core.terrain.Noise.hash(9, i, 0, 0) - 0.5) * 1.6
            val lon = com.rm.apogee.core.terrain.Noise.hash(9, i, 1, 0) * 2 * Math.PI
            val d = Vec3(kotlin.math.cos(lat) * kotlin.math.cos(lon), kotlin.math.sin(lat), kotlin.math.cos(lat) * kotlin.math.sin(lon))
            val e = Vec3(); val n = Vec3(); frame(d, e, n)
            list.clear(); w.stormModel.around(d, e, n, 1, time, list)
            for (s in list) {
                if (s.hail <= 0.0 || !seen.add(s)) continue
                val c = Vec3(); val tr = Vec3(); val rt = Vec3()
                w.stormModel.frameAt(s, time, c, tr, rt)
                val m = s.mainCell
                add(w.stormModel.place(c, tr, rt, s.cellAlong[m] + s.shaftAlong * s.cellRadius[m], s.cellAcross[m], 1.0, Vec3()))
                if (size >= 24) return@buildList
            }
        }
    }

    /** Also the ground's shaking, a few seconds after each quake listed: lift and velocity and acceleration. */
    private class Here(val sea: SeaSample?, val air: AirSample, val shake: List<Double>, val high: AirSample)

    private var shakeTimes: List<Double> = emptyList()

    /** Metres over datum where ash clouds mostly drift. */
    private val ASH_HEIGHT = 9_000.0

    private fun at(body: CelestialBody, sea: Sea?, weather: Weather, d: Vec3, time: Double): Here {
        val ground = body.radius + maxOf(body.terrain?.elevation(d) ?: 0.0, 0.0)
        val s = sea?.let { val out = SeaSample(); it.sample(Vec3().setTo(d).mulInPlace(body.radius), time, out, below = 1.0); out }
        val air = AirSample(); weather.sample(Vec3().setTo(d).mulInPlace(ground + 30.0), time, air)
        val shake = ArrayList<Double>()
        val v = Vec3(); val a = Vec3()
        for (t in shakeTimes) {
            shake += weather.quakeShake(d, t, v, a); shake += v.x; shake += v.y; shake += v.z; shake += a.x; shake += a.y; shake += a.z
            shake += weather.quakeLevel()
        }
        // In an ash cloud's layer.
        val high = AirSample(); weather.sample(Vec3().setTo(d).mulInPlace(body.radius + ASH_HEIGHT), time, high)
        return Here(s, air, shake, high)
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
        same("$what dust", a.air.dust, b.air.dust); same("$what sight", a.air.visibility, b.air.visibility)
        same("$what snow", a.air.snow, b.air.snow); same("$what snowfall", a.air.snowfall, b.air.snowfall)
        same("$what hail", a.air.hail, b.air.hail)
        same("$what ash", a.air.ash, b.air.ash); same("$what ash top", a.air.ashTop, b.air.ashTop)
        same("$what high ash", a.high.ash, b.high.ash); same("$what high ash top", a.high.ashTop, b.high.ashTop)
        same("$what high sight", a.high.visibility, b.high.visibility); same("$what high wind", a.high.wind, b.high.wind)
        assertEquals("$what shake", a.shake.map { bits(it) }, b.shake.map { bits(it) })
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
        shakeTimes = truth.quakes.map { it.start + 3.0 }
        println("busy at ${time / 3_600.0} h: ${truth.hurricanes.size} hurricanes, ${truth.tornadoes.size} tornadoes, ${truth.tsunamis.size} tsunamis, ${truth.quakes.size} quakes, ${truth.dust.size} dust storms, ${truth.blizzards.size} blizzards, ${truth.eruptions.size} eruptions")
        val spots = points(truth, serverTerra.radius) + hailSpots(serverWeather, now)
        val expected = spots.map { at(serverTerra, serverTerra.ocean!!.sea, serverWeather, it, now) }
        assertTrue("points: ${spots.size}", spots.size > 20)
        assertTrue("hailing at some: ${expected.count { it.air.hail > 0.0 }}", expected.count { it.air.hail > 0.0 } >= 3)
        assertTrue("ash at some: ${expected.count { it.high.ash > 0.0 }}", expected.count { it.high.ash > 0.0 } >= 3)
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
        shakeTimes = truth.quakes.map { it.start + 3.0 }
        val spots = points(truth, terraOf(server).radius) + hailSpots(sw, now)
        val expected = spots.map { at(terraOf(server), terraOf(server).ocean!!.sea, sw, it, now) }
        assertTrue("hailing at some", expected.any { it.air.hail > 0.0 })
        assertTrue("ash at some", expected.any { it.high.ash > 0.0 })
        // The replica's from empty shared caches, points first and in reverse.
        Weather.forgetShared(terraOf(server))
        for (j in spots.indices.reversed()) same("point $j", expected[j], at(terraOf(replica), terraOf(replica).ocean!!.sea, rw, spots[j], now))
        same("end", truth, lists(rw, now))
    }

    /** Moonquakes on Luna, which has no weather: lists and shaking round each, every way they're worked out. */
    @Test
    fun `moonquakes agree on a server, a replica, a loaded world and the renderer`() {
        val probe = Quakes.of(SolarSystem.defaultSystem().body("luna"), config)
        var time = 6 * 3_600.0
        while (time < 400 * 3_600.0) {
            val q = ArrayList<LiveQuake>(); probe.near(Vec3.unitY(), time, Double.MAX_VALUE, q)
            if (q.any { it.id != it.mainId }) break
            time += 600.0
        }
        assertTrue("an aftershock by 400 h", time < 400 * 3_600.0)
        fun list(q: Quakes, t: Double) = ArrayList<LiveQuake>().also { q.near(Vec3.unitY(), t, Double.MAX_VALUE, it) }.sortedBy { it.id }
        fun quakeLists(q: Quakes, t: Double) = Lists(emptyList(), emptyList(), emptyList(), list(q, t), emptyList(), emptyList())
        fun shaking(q: Quakes, d: Vec3, times: List<Double>): List<Long> {
            val v = Vec3(); val a = Vec3(); val out = ArrayList<Long>()
            for (t in times) {
                out += bits(q.shake(d, t, v, a)); for (x in listOf(v.x, v.y, v.z, a.x, a.y, a.z, q.level())) out += bits(x)
                out += bits(q.shakeIn(d, t - 30.0, 60.0, 0.1))
            }
            return out
        }
        val server = world(time - 4 * 3_600.0)
        val luna = server.system.body("luna")
        val sq = server.quakesFor(luna)!!
        var t = server.time
        while (t < time - 3_600.0) { list(sq, t); t += 900.0 }
        repeat(30) { server.step(1.0 / 60.0) }
        server.advanceOnRails(time - server.time)
        repeat(60) { server.step(1.0 / 60.0) }
        val now = server.time
        val truth = quakeLists(sq, now)
        val times = truth.quakes.map { it.start + 3.0 } + truth.quakes.map { it.start + 20.0 }
        val spots = buildList {
            for (q in truth.quakes) {
                add(q.epicentre.copy())
                val east = Vec3(q.epicentre.z, 0.0, -q.epicentre.x).normalizeInPlace()
                val north = Vec3().setTo(q.epicentre).crossInPlace(east)
                for (k in 0 until 4) {
                    val a = 0.5 * Math.PI * k
                    add(Vec3().setTo(q.epicentre).addScaledInPlace(east, kotlin.math.cos(a) * q.strongRadius / luna.radius)
                        .addScaledInPlace(north, kotlin.math.sin(a) * q.strongRadius / luna.radius).normalizeInPlace())
                }
            }
        }
        println("moonquakes at ${now / 3_600.0} h: ${truth.quakes.size}, magnitudes ${truth.quakes.map { "%.2f".format(it.magnitude) }}")
        val expected = spots.map { shaking(sq, it, times) }
        val text = json.encodeToString(WorldSave.serializer(), server.save())
        fun check(who: String, q: Quakes) {
            for (k in spots.indices.reversed()) assertEquals("$who point $k", expected[k], shaking(q, spots[k], times))
            same(who, truth, quakeLists(q, now))
        }
        val replica = world(now)
        check("replica", replica.quakesFor(replica.system.body("luna"))!!)
        val loaded = world(now, WorldStore.decode(text))
        check("loaded", loaded.quakesFor(loaded.system.body("luna"))!!)
        check("renderer", Quakes.of(SolarSystem.defaultSystem().body("luna"), config))
    }

    /**
     * Lightning on craft parked round a strike: a server and a replica stepping along pick the same
     * craft, take the same charge and health and put the same electrics out until the same time;
     * saved and loaded in the outage, the world carries on the same.
     */
    @Test
    fun `lightning does the same on a server, a replica and a loaded world`() {
        val strike = com.rm.apogee.core.world.LightningScene.strikes(World.default(catalog).also { it.weatherConfig = com.rm.apogee.core.world.LightningScene.config }, 1).single()
        fun parked(): World {
            val w = World.default(catalog).also { it.weatherConfig = com.rm.apogee.core.world.LightningScene.config }
            w.syncClock(strike.time - 2_600.0)
            com.rm.apogee.core.world.LightningScene.park(w, com.rm.apogee.core.world.LightningScene.short(catalog), com.rm.apogee.core.world.LightningScene.east(w, strike.direction, 25.0))
            com.rm.apogee.core.world.LightningScene.park(w, com.rm.apogee.core.world.LightningScene.tall(catalog), com.rm.apogee.core.world.LightningScene.east(w, strike.direction, -40.0))
            repeat(30 * 60) { w.step(1.0 / 60.0) }
            w.syncClock(strike.time - 3.0)
            return w
        }
        fun state(w: World): List<Long> = w.vessels.sortedBy { it.id.raw }.flatMap { v ->
            listOf(v.id.raw, bits(v.stunnedUntil), bits(v.amountOf(com.rm.apogee.core.part.ResourceType.ELECTRIC_CHARGE)), if (v.stunned(w.time)) 1L else 0L) +
                v.health.map { bits(it) } + v.defs.indices.map { if (v.isBroken(it)) 1L else 0L }
        }
        val server = parked()
        val replica = parked()
        val hits = ArrayList<String>()
        val replicaHits = ArrayList<String>()
        while (server.time < strike.time + 1.0) {
            server.step(1.0 / 60.0); replica.step(1.0 / 60.0)
            server.drainEvents().filterIsInstance<com.rm.apogee.core.world.WorldEvent.LightningHit>().forEach { hits += "${it.id.raw} ${it.strikeId} ${it.partIndex}" }
            replica.drainEvents().filterIsInstance<com.rm.apogee.core.world.WorldEvent.LightningHit>().forEach { replicaHits += "${it.id.raw} ${it.strikeId} ${it.partIndex}" }
        }
        assertTrue("struck: $hits", hits.any { it.contains(" ${strike.id} ") })
        assertEquals("the same hits", hits, replicaHits)
        assertEquals("the same craft after", state(server), state(replica))
        assertTrue("out", server.vessels.any { it.stunned(server.time) })
        val loaded = World(SolarSystem.defaultSystem(), catalog).also {
            it.restore(WorldStore.decode(json.encodeToString(WorldSave.serializer(), server.save())))
        }
        assertEquals("loaded in the outage", state(server), state(loaded))
        repeat(10 * 60) { server.step(1.0 / 60.0); loaded.step(1.0 / 60.0) }
        assertTrue("back", server.vessels.none { it.stunned(server.time) })
        assertEquals("and on alike", server.vessels.sortedBy { it.id.raw }.map { bits(it.stunnedUntil) }, loaded.vessels.sortedBy { it.id.raw }.map { bits(it.stunnedUntil) })
    }
}
