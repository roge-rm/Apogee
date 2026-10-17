package com.rm.apogee.core.weather

import com.rm.apogee.core.craft.CraftDesign
import com.rm.apogee.core.craft.PlacedPart
import com.rm.apogee.core.math.Vec3
import com.rm.apogee.core.orbit.CelestialBody
import com.rm.apogee.core.orbit.SolarSystem
import com.rm.apogee.core.part.StockParts
import com.rm.apogee.core.world.LaunchSite
import com.rm.apogee.core.world.World
import com.rm.apogee.core.world.WorldSave
import com.rm.apogee.core.world.WorldStore
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.asin
import kotlin.math.atan2

/**
 * Every extreme event added with quakes and after (quakes and aftershocks, moonquakes, haboobs,
 * broad and world dust, storm and ground blizzards, hail, eruptions and ash, tornado country and
 * debris) comes out bit-equal from a server run for hours and warped, two fresh instances, the
 * second from empty shared caches and asked other things first in another order, and a world saved
 * and loaded. Terra at a busy time, Rubra in a world dust storm, Caligo erupting, Luna shaking.
 * Debris knocks on a parked craft are the same stepping with a save and load in the middle.
 */
class ExtremeEventsAgreeTest {
    private val catalog = StockParts.catalog
    private val config = WeatherConfig(intensity = WeatherIntensity.EXTREME)
    private val json = Json { classDiscriminator = "type" }

    private fun bits(a: Double) = java.lang.Double.doubleToLongBits(a)

    /** Labelled bits, so a difference names what and where. */
    private class Print { val items = ArrayList<String>()
        fun add(what: String, v: Double) { items += "$what=${java.lang.Double.doubleToLongBits(v)}" }
        fun add(what: String, v: Vec3) { add("$what.x", v.x); add("$what.y", v.y); add("$what.z", v.z) }
        fun add(what: String, v: Any?) { items += "$what=$v" }
    }

    private fun air(p: Print, what: String, a: AirSample) {
        p.add("$what wind", a.wind); p.add("$what lift", a.lift); p.add("$what turbulence", a.turbulence)
        p.add("$what cloud", a.cloudDensity); p.add("$what cloud type", a.cloudType); p.add("$what rain", a.precipitation)
        p.add("$what hail", a.hail); p.add("$what sight", a.visibility); p.add("$what dust", a.dust); p.add("$what dust top", a.dustTop)
        p.add("$what dust look", a.dustLook); p.add("$what ash", a.ash); p.add("$what ash top", a.ashTop)
        p.add("$what snow", a.snow); p.add("$what snow top", a.snowTop); p.add("$what snowfall", a.snowfall)
        p.add("$what storm", a.storm); p.add("$what gloom", a.gloom)
    }

    private fun quakeList(q: Quakes, t: Double) = ArrayList<LiveQuake>().also { q.near(Vec3.unitY(), t, Double.MAX_VALUE, it) }.sortedBy { it.id }

    /** Every event listed on the world at [t], field by field. */
    private fun lists(w: Weather?, q: Quakes, t: Double): Print {
        val p = Print()
        for (x in quakeList(q, t)) {
            val n = "quake ${x.id}"
            p.add("$n main", x.mainId); p.add("$n sea", x.underSea); p.add("$n epicentre", x.epicentre); p.add("$n start", x.start)
            p.add("$n magnitude", x.magnitude); p.add("$n depth", x.depth); p.add("$n peak", x.peak); p.add("$n radius", x.strongRadius)
            p.add("$n reach", x.reach); p.add("$n duration", x.duration); p.add("$n age", x.age); p.add("$n arrives", x.arrivesHere); p.add("$n here", x.peakHere)
        }
        p.add("quakes live", q.live(t))
        if (w == null) return p
        val up = Vec3.unitY()
        for (x in ArrayList<LiveDust>().also { w.dustStorms(up, t, Double.MAX_VALUE, it) }.sortedBy { it.id }) {
            val n = "dust ${x.id}"
            p.add("$n kind", x.kind); p.add("$n centre", x.centre); p.add("$n origin", x.origin); p.add("$n heading", x.heading)
            p.add("$n speed", x.speed); p.add("$n width", x.halfWidth); p.add("$n height", x.height); p.add("$n reach", x.reach)
            p.add("$n strength", x.strength); p.add("$n age", x.age); p.add("$n life", x.life)
        }
        for (x in ArrayList<LiveBlizzard>().also { w.blizzards(up, t, Double.MAX_VALUE, it) }.sortedBy { it.id }) {
            val n = "blizzard ${x.id}"
            p.add("$n centre", x.centre); p.add("$n origin", x.origin); p.add("$n heading", x.heading); p.add("$n speed", x.speed)
            p.add("$n length", x.halfLength); p.add("$n width", x.halfWidth); p.add("$n height", x.height)
            p.add("$n strength", x.strength); p.add("$n age", x.age); p.add("$n life", x.life)
        }
        for (x in ArrayList<LiveEruption>().also { w.eruptions(up, t, Double.MAX_VALUE, it) }.sortedBy { it.id }) {
            val n = "eruption ${x.id}"
            p.add("$n vent", x.vent); p.add("$n vent height", x.ventHeight); p.add("$n start", x.start); p.add("$n age", x.age)
            p.add("$n life", x.life); p.add("$n strength", x.strength); p.add("$n top", x.top); p.add("$n column", x.columnTop)
            p.add("$n drift", x.drift); p.add("$n length", x.length); p.add("$n width", x.halfWidth)
        }
        for (x in ArrayList<LiveTornado>().also { w.tornadoes(up, t, Double.MAX_VALUE, it) }.sortedBy { it.id }) {
            val n = "tornado ${x.id}"
            p.add("$n centre", x.centre); p.add("$n velocity", x.velocity); p.add("$n core", x.coreRadius); p.add("$n reach", x.reach)
            p.add("$n strength", x.strength); p.add("$n age", x.age); p.add("$n life", x.life); p.add("$n turn", x.turn)
            p.add("$n water", x.overWater); p.add("$n band", x.inHurricane)
        }
        for (x in ArrayList<LiveHail>().also { w.hailShafts(up, t, Double.MAX_VALUE, it) }.sortedBy { it.id }) {
            val n = "hail ${x.id}"
            p.add("$n centre", x.centre); p.add("$n heading", x.heading); p.add("$n speed", x.speed); p.add("$n width", x.width); p.add("$n hail", x.hail)
        }
        p.add("world dust share", w.worldDustShare(t)); p.add("ash live", w.ashLive(t)); p.add("quakes live w", w.quakesLive(t))
        return p
    }

    /** Points round each event listed at [t], and some scattered ones for ground blizzards and broad dust. Unit, body-fixed. */
    private fun spots(body: CelestialBody, w: Weather?, q: Quakes, t: Double): List<Vec3> = buildList {
        val r = body.radius
        fun near(c: Vec3, metres: Double, turn: Double = 0.0): Vec3 {
            val e = Vec3(); val n = Vec3(); frame(c, e, n)
            return Vec3().setTo(c).mulInPlace(r).addScaledInPlace(e, metres * kotlin.math.cos(turn)).addScaledInPlace(n, metres * kotlin.math.sin(turn)).normalizeInPlace()
        }
        for (x in quakeList(q, t).take(12)) { add(x.epicentre.copy()); add(near(x.epicentre, x.strongRadius)); add(near(x.epicentre, 3 * x.strongRadius, 2.0)) }
        if (w != null) {
            val up = Vec3.unitY()
            for (x in ArrayList<LiveDust>().also { w.dustStorms(up, t, Double.MAX_VALUE, it) }.take(12)) {
                add(x.centre.copy()); add(near(x.centre, 2_000.0, 1.0))
                add(Vec3().setTo(x.centre).mulInPlace(r).addScaledInPlace(x.heading, -5_000.0).normalizeInPlace())
            }
            for (x in ArrayList<LiveBlizzard>().also { w.blizzards(up, t, Double.MAX_VALUE, it) }.take(12)) {
                add(x.centre.copy()); add(near(x.centre, 0.5 * x.halfWidth, 2.0))
                add(Vec3().setTo(x.centre).mulInPlace(r).addScaledInPlace(x.heading, 0.9 * x.halfLength).normalizeInPlace())
            }
            for (x in ArrayList<LiveEruption>().also { w.eruptions(up, t, Double.MAX_VALUE, it) }) {
                add(x.vent.copy())
                val heading = x.drift.copy().normalizeInPlace()
                for (f in listOf(0.3, 0.7)) add(Vec3().setTo(x.vent).mulInPlace(r).addScaledInPlace(heading, x.length * f).normalizeInPlace())
            }
            for (x in ArrayList<LiveTornado>().also { w.tornadoes(up, t, Double.MAX_VALUE, it) }.filter { !it.overWater }.take(12)) {
                add(x.centre.copy()); add(near(x.centre, 1.5 * x.coreRadius))
            }
            for (x in ArrayList<LiveHail>().also { w.hailShafts(up, t, Double.MAX_VALUE, it) }.take(12)) add(x.centre.copy())
        }
        val rnd = java.util.Random(body.id.hashCode().toLong())
        repeat(80) { add(Vec3(rnd.nextGaussian(), rnd.nextGaussian(), rnd.nextGaussian()).normalizeInPlace()) }
    }

    /** Everything worked out at unit [d] at [t]: the air low and up in the ash, hail, debris, ground, tornado country and shaking. */
    private fun at(body: CelestialBody, w: Weather?, q: Quakes, d: Vec3, t: Double, shakeTimes: List<Double>): Print {
        val p = Print()
        val ground = maxOf(body.terrain?.elevation(d) ?: 0.0, if (body.ocean != null) 0.0 else -1e9)
        if (w != null) {
            air(p, "low", w.sample(Vec3().setTo(d).mulInPlace(body.radius + ground + 30.0), t, AirSample()))
            air(p, "high", w.sample(Vec3().setTo(d).mulInPlace(body.radius + 9_000.0), t, AirSample()))
            p.add("hail at", w.hailAt(d, ground + 30.0, t))
            for (k in -1..1) p.add("debris $k", w.debrisAt(d, 20.0, t + 30.0 * k))
            p.add("debris maybe", w.debrisMaybe(d, t - 600.0, t + 600.0))
            p.add("snow cover", w.snowCover(d)); p.add("cold", w.blizzardCold(d)); p.add("dustiness", w.dustiness(d))
            p.add("world dust", w.worldDustCover(d, t)); p.add("country", w.tornadoModel.country(d))
        }
        val v = Vec3(); val a = Vec3()
        for (s in shakeTimes) { p.add("shake $s", q.shake(d, s, v, a)); p.add("v $s", v); p.add("a $s", a); p.add("level $s", q.level()) }
        p.add("shake in", q.shakeIn(d, t - 30.0, 60.0, 0.1))
        return p
    }

    /** One way of working things out: the world's weather (none on Luna) and quakes. */
    private class Source(val who: String, val body: CelestialBody, val weather: Weather?, val quakes: Quakes)

    private fun source(who: String, body: CelestialBody, world: World?): Source {
        val w = world?.weatherFor(body) ?: if (world == null && body.atmosphere != null) Weather(body, config) else null
        val q = world?.quakesFor(body) ?: w?.quakeModel ?: Quakes.of(body, config)
        return Source(who, body, w, q)
    }

    private fun same(who: String, a: Print, b: Print) {
        val k = a.items.indices.firstOrNull { it >= b.items.size || a.items[it] != b.items[it] }
        if (k != null || a.items.size != b.items.size) {
            val i = k ?: minOf(a.items.size, b.items.size)
            throw AssertionError("$who differs at item $i of ${a.items.size}/${b.items.size}: ${a.items.getOrNull(i)} against ${b.items.getOrNull(i)}")
        }
    }

    /** Asks [s] lots of other things round [t]: lists at other times, air at other places and times, shaking. */
    private fun distract(s: Source, t: Double) {
        val rnd = java.util.Random(77)
        val up = Vec3.unitY()
        for (dt in doubleArrayOf(-3 * 3_600.0, -900.0, 1_800.0)) {
            s.quakes.near(up, t + dt, Double.MAX_VALUE, ArrayList())
            val w = s.weather ?: continue
            w.dustStorms(up, t + dt, Double.MAX_VALUE, ArrayList()); w.blizzards(up, t + dt, Double.MAX_VALUE, ArrayList())
            w.eruptions(up, t + dt, Double.MAX_VALUE, ArrayList()); w.tornadoes(up, t + dt, Double.MAX_VALUE, ArrayList())
        }
        repeat(150) {
            val d = Vec3(rnd.nextGaussian(), rnd.nextGaussian(), rnd.nextGaussian()).normalizeInPlace()
            val dt = (rnd.nextDouble() - 0.5) * 7_200.0
            s.quakes.shake(d, t + dt, Vec3())
            val w = s.weather ?: return@repeat
            w.sample(Vec3().setTo(d).mulInPlace(s.body.radius + 50.0 + rnd.nextDouble() * 10_000.0), t + dt, AirSample())
            w.hailAt(d, 100.0, t + dt); w.debrisAt(d, 10.0, t + dt); w.debrisMaybe(d, t + dt - 3_600.0, t + dt)
            w.worldDustCover(d, t + dt); w.snowCover(d); w.dustiness(d)
        }
    }

    /** Everything at [t] from [s], lists first and points in order, or points first in reverse then lists. */
    private fun everything(s: Source, t: Double, spots: List<Vec3>, shakeTimes: List<Double>, reverse: Boolean): Pair<Print, List<Print>> =
        if (reverse) {
            val points = spots.indices.reversed().map { at(s.body, s.weather, s.quakes, spots[it], t, shakeTimes) }.reversed()
            lists(s.weather, s.quakes, t) to points
        } else lists(s.weather, s.quakes, t) to spots.map { at(s.body, s.weather, s.quakes, it, t, shakeTimes) }

    private fun world(at: Double, save: WorldSave? = null): World =
        World(SolarSystem.defaultSystem(), catalog).also {
            it.restore(save ?: WorldSave(catalogHash = catalog.contentHash, universeTime = at, nextVesselId = 1L, weather = config))
        }

    /** A server run on for hours and warped to [time], a fresh pair of instances and a loaded world agree on [bodyId]. */
    private fun agree(bodyId: String, time: Double, check: (Source, List<Print>) -> Unit) {
        val server = world(time - 4 * 3_600.0)
        val serverBody = server.system.body(bodyId)
        val ss = source("server", serverBody, server)
        var t = server.time
        while (t < time - 3_600.0) { lists(ss.weather, ss.quakes, t); t += 900.0 }
        repeat(30) { server.step(1.0 / 60.0) }
        server.advanceOnRails(time - server.time)
        repeat(60) { server.step(1.0 / 60.0) }
        val now = server.time
        val spots = spots(serverBody, ss.weather, ss.quakes, now)
        val shakeTimes = quakeList(ss.quakes, now).take(6).flatMap { listOf(it.start + 3.0, it.start + 20.0) }
        val (truthLists, truth) = everything(ss, now, spots, shakeTimes, reverse = false)
        println("$bodyId at ${now / 3_600.0} h: ${spots.size} points, ${truthLists.items.size} list items, ${truth.sumOf { it.items.size }} point items")
        check(ss, truth)
        val text = json.encodeToString(WorldSave.serializer(), server.save())

        fun compare(s: Source, reverse: Boolean) {
            val (l, points) = everything(s, now, spots, shakeTimes, reverse)
            same("$bodyId ${s.who} lists", truthLists, l)
            for (k in spots.indices) same("$bodyId ${s.who} point $k", truth[k], points[k])
        }
        Weather.forgetShared(serverBody)
        compare(source("first", SolarSystem.defaultSystem().body(bodyId), null), reverse = false)
        Weather.forgetShared(serverBody)
        val second = source("second", SolarSystem.defaultSystem().body(bodyId), null)
        distract(second, now)
        compare(second, reverse = true)
        Weather.forgetShared(serverBody)
        val loaded = world(now, WorldStore.decode(text))
        assertEquals("loaded time", bits(now), bits(loaded.time))
        val ls = source("loaded", loaded.system.body(bodyId), loaded)
        distract(ls, now)
        compare(ls, reverse = false)
    }

    private fun find(what: String, from: Double, step: Double, until: Double, ok: (Double) -> Boolean): Double {
        var t = from
        while (t < until) { if (ok(t)) return t; t += step }
        error("no $what by ${until / 3_600.0} h")
    }

    @Test
    fun `terra's quakes, dust, blizzards, hail, ash and tornado debris agree`() {
        val terra = SolarSystem.defaultSystem().body(SolarSystem.HOMEWORLD_ID)
        val w = Weather(terra, config)
        val up = Vec3.unitY()
        val time = find("busy Terra", 6 * 3_600.0, 600.0, 300 * 3_600.0) { t ->
            ArrayList<LiveQuake>().also { w.quakes(up, t, Double.MAX_VALUE, it) }.any { it.id != it.mainId } &&
                ArrayList<LiveDust>().also { w.dustStorms(up, t, Double.MAX_VALUE, it) }.isNotEmpty() &&
                ArrayList<LiveBlizzard>().also { w.blizzards(up, t, Double.MAX_VALUE, it) }.isNotEmpty() &&
                ArrayList<LiveEruption>().also { w.eruptions(up, t, Double.MAX_VALUE, it) }.any { it.strength > 0.0 } &&
                ArrayList<LiveTornado>().also { w.tornadoes(up, t, Double.MAX_VALUE, it) }.any { !it.overWater && w.debrisAt(it.centre, 20.0, t) > 0.1 }
        }
        agree(SolarSystem.HOMEWORLD_ID, time) { s, truth ->
            fun count(label: String, test: (Double) -> Boolean) = truth.count { p -> p.items.any { it.startsWith("$label=") && test(java.lang.Double.longBitsToDouble(it.substringAfter('=').toLong())) } }
            val counts = mapOf(
                "low dust" to count("low dust") { it > 0.0 }, "low snow" to count("low snow") { it > 0.0 },
                "high ash" to count("high ash") { it > 0.0 }, "hail at" to count("hail at") { it > 0.0 },
                "debris 0" to count("debris 0") { it > 0.0 }, "country" to count("country") { it < 1.0 },
                "shake in" to count("shake in") { it > 0.0 },
            )
            println("terra: $counts")
            for ((k, n) in counts) assertTrue("$k at some points: $n", n >= 2)
            // Ground blizzards: blown snow at a scattered point away from any storm blizzard.
            val w2 = s.weather!!
            val ground = (0 until 4000).count { k ->
                val lat = (com.rm.apogee.core.terrain.Noise.hash(5, k, 0, 0) * 2 - 1) * 1.5
                val lon = com.rm.apogee.core.terrain.Noise.hash(5, k, 1, 0) * 2 * Math.PI
                val d = Vec3(kotlin.math.cos(lat) * kotlin.math.cos(lon), kotlin.math.sin(lat), kotlin.math.cos(lat) * kotlin.math.sin(lon))
                if (w2.snowCover(d) <= 0.0) return@count false
                val a = w2.sample(Vec3().setTo(d).mulInPlace(terra.radius + maxOf(terra.terrain!!.elevation(d), 0.0) + 10.0), time, AirSample())
                a.snow > 0.0 && a.snowfall == 0.0
            }
            println("terra ground blizzard points: $ground")
        }
    }

    @Test
    fun `rubra's world dust storm agrees`() {
        val rubra = SolarSystem.defaultSystem().body("rubra")
        val w = Weather(rubra, config)
        val time = find("world dust on Rubra", 6 * 3_600.0, 3 * 3_600.0, 3_000 * 3_600.0) { t ->
            ArrayList<LiveDust>().also { w.dustStorms(Vec3.unitY(), t, Double.MAX_VALUE, it) }.any { it.kind == DustKind.WORLD && it.age > 0.4 * it.life }
        }
        agree("rubra", time) { _, truth ->
            val dusty = truth.count { p -> p.items.any { it.startsWith("world dust=") && java.lang.Double.longBitsToDouble(it.substringAfter('=').toLong()) > 0.0 } }
            assertTrue("world dust at some points: $dusty", dusty >= 10)
        }
    }

    @Test
    fun `caligo's eruptions agree`() {
        val caligo = SolarSystem.defaultSystem().body("caligo")
        val w = Weather(caligo, config)
        val time = find("an eruption on Caligo", 6 * 3_600.0, 1_800.0, 500 * 3_600.0) { t ->
            ArrayList<LiveEruption>().also { w.eruptions(Vec3.unitY(), t, Double.MAX_VALUE, it) }.any { it.strength > 0.0 && it.age > 3_600.0 }
        }
        agree("caligo", time) { _, truth ->
            val ash = truth.count { p -> p.items.any { (it.startsWith("high ash=") || it.startsWith("low ash=")) && java.lang.Double.longBitsToDouble(it.substringAfter('=').toLong()) > 0.0 } }
            assertTrue("ash at some points: $ash", ash >= 2)
        }
    }

    @Test
    fun `luna's moonquakes agree`() {
        val probe = Quakes.of(SolarSystem.defaultSystem().body("luna"), config)
        val time = find("an aftershock on Luna", 6 * 3_600.0, 600.0, 400 * 3_600.0) { t -> quakeList(probe, t).any { it.id != it.mainId } }
        agree("luna", time) { s, truth ->
            assertTrue("no weather on Luna", s.weather == null)
            val shaking = truth.count { p -> p.items.any { it.startsWith("shake in=") && java.lang.Double.longBitsToDouble(it.substringAfter('=').toLong()) > 0.0 } }
            println("luna shaking points: $shaking")
        }
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

    /** A craft parked in a land tornado's path through its life, stepped or warped, saved and loaded at [saveAt] or not: its health and breaks. */
    private fun throughDebris(saveAt: Boolean, warp: Boolean): List<Long> {
        val (time, pick) = debrisPick
        val start = time - pick.age
        val end = start + pick.life + 60.0
        var world = world(start - 120.0)
        val d = pick.centre
        world.spawnOnSurface(craft(), LaunchSite("debris", "", "terra", asin(d.y), atan2(d.z, d.x)))
        repeat(30 * 60) { world.step(1.0 / 60.0) }
        fun to(until: Double) {
            if (warp) world.advanceOnRails(until - world.time)
            else while (world.time < until) world.step(1.0 / 60.0)
        }
        // Both ways stop at [time], since a warp's slices and ticks depend on where it stops.
        to(time)
        if (saveAt) {
            val text = json.encodeToString(WorldSave.serializer(), world.save())
            world = World(SolarSystem.defaultSystem(), catalog).also { it.restore(WorldStore.decode(text)) }
        }
        to(end)
        repeat(120) { world.step(1.0 / 60.0) }
        return world.vessels.sortedBy { it.id.raw }.flatMap { v -> v.health.map { bits(it) } + v.defs.indices.map { if (v.isBroken(it)) 1L else 0L } }
    }

    /** The land tornado that throws the most at its middle in Terra's first 8 h, and when it's there. */
    private val debrisPick: Pair<Double, LiveTornado> by lazy {
        val terra = SolarSystem.defaultSystem().body(SolarSystem.HOMEWORLD_ID)
        val w = Weather(terra, config)
        val seen = LinkedHashMap<Long, Pair<Double, LiveTornado>>()
        var t = 0.0
        while (t < 8 * 3_600.0) {
            val list = ArrayList<LiveTornado>().also { w.tornadoes(Vec3.unitY(), t, Double.MAX_VALUE, it) }
            for (k in list) if (!k.inHurricane && !k.overWater && k.age / k.life in 0.35..0.6 && k.id !in seen) seen[k.id] = t to k
            t += 120.0
        }
        seen.values.filter { terra.terrain!!.elevation(it.second.centre) > 5.0 }.maxBy { (t, k) -> w.debrisAt(k.centre, 5.0, t) * k.coreRadius }
    }

    private fun healths(a: List<Long>) = a.take(3).map { java.lang.Double.longBitsToDouble(it) }

    @Test
    fun `tornado debris knocks are the same stepping across a save and load`() {
        val straight = throughDebris(saveAt = false, warp = false)
        val loaded = throughDebris(saveAt = true, warp = false)
        println("stepped health: ${healths(straight)}, loaded ${healths(loaded)}")
        assertTrue("knocked", healths(straight).any { it < 1.0 })
        assertEquals(straight, loaded)
    }

    @Test
    fun `tornado debris knocks are the same warping across a save and load`() {
        val straight = throughDebris(saveAt = false, warp = true)
        val loaded = throughDebris(saveAt = true, warp = true)
        println("warped health: ${healths(straight)}, loaded ${healths(loaded)}")
        assertTrue("knocked", healths(straight).any { it < 1.0 })
        assertEquals(straight, loaded)
    }
}
