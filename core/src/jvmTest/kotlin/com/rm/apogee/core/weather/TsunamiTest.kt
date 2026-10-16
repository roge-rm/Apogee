package com.rm.apogee.core.weather

import com.rm.apogee.core.craft.StockCraft
import com.rm.apogee.core.math.Vec3
import com.rm.apogee.core.orbit.Atmosphere
import com.rm.apogee.core.orbit.CelestialBody
import com.rm.apogee.core.orbit.SolarSystem
import com.rm.apogee.core.part.StockParts
import com.rm.apogee.core.sea.Sea
import com.rm.apogee.core.sea.SeaSample
import com.rm.apogee.core.terrain.Ocean
import com.rm.apogee.core.terrain.SurfaceMaterial
import com.rm.apogee.core.terrain.Terrain
import com.rm.apogee.core.terrain.TerrainTileCache
import com.rm.apogee.core.world.LaunchSite
import com.rm.apogee.core.world.World
import com.rm.apogee.core.world.WorldSave
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Tsunamis: hashed quakes agree everywhere, none on Calm or Normal, the front runs at
 * sqrt(g * depth), the sea draws back before the crest, low land by the sea floods and drains, and
 * a boat near the shore gets carried in.
 */
class TsunamiTest {
    private val radius = 600_000.0
    private val mu = 3.5316e12
    private val g = mu / (radius * radius)

    /**
     * A flat 3000 m deep sea with one continent from longitude 0 to 1.2 east, within 0.6 of the
     * equator. A shelf runs 60 km out from its west coast; the land rises 1 in 500 for 3 km, then
     * lies flat at 6 m, with a dip to 2 m 30-35 km in. North of latitude 0.3 the coast is a 40 m
     * cliff.
     */
    private class Flat : Terrain {
        override val bodyRadius = 600_000.0
        override val maxElevation = 200.0
        override val generation = 0
        override fun material(direction: Vec3, elevation: Double, slope: Double) = SurfaceMaterial.GRASS
        override val tiles by lazy { TerrainTileCache(this) }
        override fun elevation(direction: Vec3): Double {
            val d = direction.normalized()
            val lat = kotlin.math.asin(d.y.coerceIn(-1.0, 1.0))
            val lon = atan2(d.z, d.x)
            if (abs(lat) > 0.6 || lon > 1.2) return -3_000.0
            val m = lon * bodyRadius * cos(lat)
            if (m < -60_000.0) return -3_000.0
            if (m < -10_000.0) return -3_000.0 + (m + 60_000.0) / 50_000.0 * 2_960.0
            if (m < 0.0) return m / 10_000.0 * 40.0
            if (lat > 0.3) return 40.0
            if (m < 3_000.0) return m / 500.0
            if (m in 30_000.0..35_000.0) return 2.0
            return 6.0
        }
    }

    private val flat = Flat()
    private val body = CelestialBody(
        id = "tsunami-flat", displayName = "Flat", gravitationalParameter = mu, radius = radius,
        atmosphere = Atmosphere(seaLevelDensity = 1.225, seaLevelPressure = 101_325.0, scaleHeight = 5_600.0, height = 70_000.0),
        terrain = flat, ocean = Ocean(),
    )

    /** Unit direction at [m] metres east of the west coast along latitude [lat]. */
    private fun east(m: Double, lat: Double = 0.0) = SolarSystem.surfaceDirection(lat, m / (radius * cos(lat)))

    private val origin = east(-180_000.0)

    private fun flatSea(intensity: WeatherIntensity = WeatherIntensity.EXTREME): Pair<Weather, Sea> {
        val w = Weather(body, WeatherConfig(intensity = intensity))
        w.tsunamiModel.force(origin, 0.0, strength = 1.0, period = 600.0)
        return w to Sea(body, null, w, 0)
    }

    private val terra = SolarSystem.defaultSystem().body(SolarSystem.HOMEWORLD_ID)

    private fun quakes(w: Weather, from: Double, to: Double): List<LiveTsunami> {
        val seen = LinkedHashMap<Long, LiveTsunami>()
        var t = from
        while (t < to) {
            val out = ArrayList<LiveTsunami>()
            w.tsunamis(Vec3(1.0, 0.0, 0.0), t, 1e9, out)
            for (q in out) seen.putIfAbsent(q.id, q)
            t += 3_000.0
        }
        return seen.values.toList()
    }

    @Test
    fun `none on Calm or Normal`() {
        for (intensity in listOf(WeatherIntensity.CALM, WeatherIntensity.NORMAL)) {
            val w = Weather(terra, WeatherConfig(intensity = intensity))
            assertTrue(!w.tsunamiModel.active)
            val out = ArrayList<LiveTsunami>()
            for (k in 0 until 100) w.tsunamis(Vec3(1.0, 0.0, 0.0), k * 5_000.0, 1e9, out)
            assertTrue(out.isEmpty())
            for (k in 0 until 100) assertEquals(0.0, w.tsunamiModel.height(Vec3(0.0, 0.0, 1.0), k * 5_000.0, -5.0), 0.0)
        }
    }

    @Test
    fun `two instances agree bit for bit, and the arrival times are made once and quickly`() {
        val a = Weather(terra, WeatherConfig(intensity = WeatherIntensity.EXTREME))
        val b = Weather(terra, WeatherConfig(intensity = WeatherIntensity.EXTREME))
        val quakes = quakes(a, 0.0, 30.0 * 3_600.0)
        assertTrue("some quakes in 30 h: ${quakes.size}", quakes.size >= 4)
        // Time the grid and a fresh solve, a few times over so the JIT has warmed up.
        repeat(3) {
            val began = System.nanoTime()
            val grid = Tsunamis.Grid.unkept(terra.terrain!!, radius)
            val made = (System.nanoTime() - began) / 1e6
            val solveBegan = System.nanoTime()
            grid.solve(Vec3(0.3, -0.2, 0.93 + it * 0.01).normalizeInPlace(), terra.gravitationalParameter / (radius * radius))
            println("grid ${grid.perFace} per face, ${grid.count} nodes: made in $made ms, solved in ${(System.nanoTime() - solveBegan) / 1e6} ms")
        }
        val sa = Sea(terra, null, a, 0); val sb = Sea(terra, null, b, 0)
        val oa = SeaSample(); val ob = SeaSample()
        val terrain = terra.terrain!!
        var moved = 0
        for (q in quakes.take(4)) {
            for (k in 0 until 40) {
                val t = q.start + 600.0 + k * 337.0
                val la = ArrayList<LiveTsunami>(); val lb = ArrayList<LiveTsunami>()
                a.tsunamis(Vec3(0.0, 0.0, 1.0), t, 1e9, la); b.tsunamis(Vec3(0.0, 0.0, 1.0), t, 1e9, lb)
                assertEquals(la.size, lb.size)
                for ((x, y) in la.zip(lb)) {
                    assertEquals(x.id, y.id); assertEquals(x.origin, y.origin); assertEquals(x.frontRadius, y.frontRadius, 0.0)
                }
                for (i in 0 until 30) {
                    val d = Vec3(sin(i * 2.1 + k) * cos(i * 0.7), cos(i * 1.3), sin(i * 0.9 + k)).normalizeInPlace()
                    if (terrain.elevation(d) > 20.0) continue
                    val p = d.copy().mulInPlace(radius)
                    sa.sample(p, t, oa); sb.sample(p, t, ob)
                    assertEquals(oa.height, ob.height, 0.0)
                    assertEquals(oa.velocity, ob.velocity)
                    if (abs(a.tsunamiModel.height(d, t, terrain.elevation(d))) > 0.01) moved++
                }
            }
        }
        assertTrue("the waves were somewhere: $moved", moved > 10)
    }

    @Test
    fun `an instance stepped through many cycles agrees with a fresh one`() {
        val stepped = Weather(terra, WeatherConfig(intensity = WeatherIntensity.EXTREME))
        val terrain = terra.terrain!!
        val points = (0 until 40).map { i ->
            Vec3(sin(i * 2.1) * cos(i * 0.7), cos(i * 1.3), sin(i * 0.9 + 0.4)).normalizeInPlace()
        }.filter { terrain.elevation(it) < 15.0 }
        val beds = points.map { terrain.elevation(it) }
        // Walk it through 80 h in steps, and jump about now and then, as a game and the map would.
        var t = 0.0
        var compared = 0
        while (t < 80.0 * 3_600.0) {
            for ((d, bed) in points.zip(beds)) stepped.tsunamiModel.height(d, t, bed)
            stepped.tsunamis(Vec3(1.0, 0.0, 0.0), t + 37_000.0, 1e9, ArrayList())
            if ((t / 900.0).toInt() % 7 == 0) {
                val fresh = Weather(terra, WeatherConfig(intensity = WeatherIntensity.EXTREME))
                for ((d, bed) in points.zip(beds)) {
                    val a = stepped.tsunamiModel.height(d, t, bed)
                    val b = fresh.tsunamiModel.height(d, t, bed)
                    assertEquals("at $t", b, a, 0.0)
                    if (a != 0.0) compared++
                }
                val la = ArrayList<LiveTsunami>(); val lb = ArrayList<LiveTsunami>()
                stepped.tsunamis(Vec3(1.0, 0.0, 0.0), t, 1e9, la); fresh.tsunamis(Vec3(1.0, 0.0, 0.0), t, 1e9, lb)
                assertEquals(lb.map { it.id to it.start }, la.map { it.id to it.start })
                assertEquals(lb.map { it.strength }, la.map { it.strength })
            }
            t += 900.0
        }
        assertTrue("waves compared: $compared", compared > 20)
    }

    @Test
    fun `the Cape harbour berth gets the wave`() {
        val field = terra.terrain as com.rm.apogee.core.terrain.TerrainField
        val berth = field.worksDirection(2_700.0, 330.0).normalizeInPlace()
        val bed = field.elevation(berth)
        val w = Weather(terra, WeatherConfig(intensity = WeatherIntensity.EXTREME))
        val origin = quakes(w, 0.0, 30.0 * 3_600.0).first().origin
        w.tsunamiModel.force(origin, 0.0, strength = 1.0, period = 900.0)
        val arrival = w.tsunamiModel.arrivalAt(origin, berth)
        assertTrue("arrives: $arrival", arrival < Tsunamis.NEVER)
        var low = 0.0; var high = 0.0
        var t = arrival
        while (t < arrival + 1_800.0) {
            val h = w.tsunamiModel.height(berth, t, bed)
            low = minOf(low, h); high = maxOf(high, h)
            t += 20.0
        }
        println("berth bed $bed: arrives at $arrival s, from $low m to $high m")
        assertTrue("draws back: $low", low < -0.1)
        assertTrue("rises: $high", high > 0.1)
    }

    @Test
    fun `Wild has rarer and smaller ones than Extreme`() {
        val hours = 600.0 * 3_600.0
        val extreme = quakes(Weather(terra, WeatherConfig(intensity = WeatherIntensity.EXTREME)), 0.0, hours)
        val wild = quakes(Weather(terra, WeatherConfig(intensity = WeatherIntensity.WILD)), 0.0, hours)
        println("quakes in 600 h: Extreme ${extreme.size}, Wild ${wild.size}")
        assertTrue("about one per few hours on Extreme: ${extreme.size}", extreme.size in 100..200)
        assertTrue("Wild ${wild.size} vs Extreme ${extreme.size}", wild.size * 4 < extreme.size)
        for (list in listOf(extreme, wild)) assertEquals("no quake twice", list.size, list.map { it.start }.toSet().size)
        assertTrue(wild.all { it.strength <= Tsunamis.SIZE_WILD + 1e-9 })
        assertTrue(extreme.maxOf { it.strength } > 0.8)
    }

    @Test
    fun `the front runs at the square root of g times the depth`() {
        val (w, _) = flatSea()
        val c = sqrt(g * 3_000.0)
        var worst = 0.0
        for (lon in listOf(-0.6, -1.0, -1.5, -2.2, -2.8)) for (lat in listOf(-0.5, 0.0, 0.4, 0.9)) {
            val d = SolarSystem.surfaceDirection(lat, lon)
            val angle = atan2(origin.cross(d).length, origin dot d)
            val expected = angle * radius / c
            val got = w.tsunamiModel.arrivalAt(origin, d)
            worst = maxOf(worst, abs(got / expected - 1.0))
            assertTrue("at $lat, $lon: $got s vs $expected s", abs(got / expected - 1.0) < 0.06)
        }
        println("arrival worst error ${worst * 100} %")
    }

    @Test
    fun `the sea draws back before the crest at the coast`() {
        val (w, sea) = flatSea()
        val d = east(-600.0)
        val bed = flat.elevation(d)
        val arrival = w.tsunamiModel.arrivalAt(origin, d)
        val s = SeaSample()
        var first = 0.0
        var dried = false
        var crest = 0.0
        var t = arrival - 60.0
        while (t < arrival + 900.0) {
            sea.sample(d.copy().mulInPlace(radius), t, s)
            val eta = s.tide - sea.tides.height(d, t, bed)
            if (first == 0.0 && abs(eta) > 0.05) first = eta
            if (s.depth <= 0.0) dried = true
            crest = maxOf(crest, eta)
            t += 10.0
        }
        println("bed $bed: first move $first m, crest $crest m, dried $dried")
        assertTrue("draws back first: $first", first < 0.0)
        assertTrue("the bed shows", dried)
        assertTrue("then a crest: $crest", crest > 3.0 && crest <= Tsunamis.COAST_CAP)
    }

    @Test
    fun `low land by the sea floods and drains, high land and a far basin stay dry`() {
        val (w, sea) = flatSea()
        val s = SeaSample()
        fun wetness(d: Vec3): Pair<Double, Double> {
            val arrival = w.tsunamiModel.arrivalAt(origin, d)
            var most = 0.0
            var t = arrival - 60.0
            while (t < arrival + 1_200.0) {
                sea.sample(d.copy().mulInPlace(radius), t, s)
                most = maxOf(most, s.depth)
                t += 15.0
            }
            sea.sample(d.copy().mulInPlace(radius), arrival + Tsunamis.TRAIN + 60.0, s)
            return most to s.depth
        }
        val (low, lowAfter) = wetness(east(600.0))
        println("low land at 1.2 m: up to $low m deep, then $lowAfter")
        assertTrue("floods: $low", low > 0.5)
        assertTrue("drains: $lowAfter", lowAfter <= 0.0)
        val (high, _) = wetness(east(1_000.0, 0.4))
        assertTrue("40 m cliff top stays dry: $high", high <= 0.0)
        val (plateau, _) = wetness(east(6_000.0))
        assertTrue("6 m land 6 km in stays dry: $plateau", plateau <= 0.0)
        val (basin, _) = wetness(east(32_000.0))
        assertTrue("a 2 m basin 32 km in stays dry: $basin", basin <= 0.0)
    }

    @Test
    fun `a boat near the shore is carried in with the crest and settles as the water drains`() {
        val catalog = StockParts.catalog
        val world = World(SolarSystem(listOf(body), body.id), catalog)
        world.restore(WorldSave(catalogHash = catalog.contentHash, universeTime = 0.0, nextVesselId = 1L))
        world.weatherConfig = WeatherConfig(intensity = WeatherIntensity.CALM)
        val weather = world.weatherFor(body)!!
        weather.tsunamiModel.force(origin, 0.0, strength = 1.0, period = 600.0)
        val start = east(-1_500.0)
        val arrival = weather.tsunamiModel.arrivalAt(origin, start)
        world.restore(WorldSave(catalogHash = catalog.contentHash, universeTime = arrival - 120.0, nextVesselId = 1L))
        val site = LaunchSite("shore", "Shore", body.id, 0.0, SolarSystem.longitudeOf(start))
        val boat = world.spawnOnSurface(StockCraft.boat(catalog), site)
        val dt = 1.0 / 60.0
        fun along() = SolarSystem.longitudeOf(boat.body.position.normalized()) * radius
        val from = along()
        var furthest = from
        repeat(((120.0 + 900.0) / dt).toInt()) {
            world.step(dt)
            furthest = maxOf(furthest, along())
        }
        val speed = boat.body.linearVelocity.length
        println("boat from ${from} m: furthest ${furthest - from} m in, ends ${along() - from} m in at $speed m/s")
        assertTrue("carried in: ${furthest - from} m", furthest - from > 300.0)
        assertTrue("settles: $speed m/s", speed < 1.5)
    }

    @Test
    fun `a boat parked asleep on low land wakes as the flood reaches it and floats`() {
        val catalog = StockParts.catalog
        val world = World(SolarSystem(listOf(body), body.id), catalog)
        world.restore(WorldSave(catalogHash = catalog.contentHash, universeTime = 0.0, nextVesselId = 1L))
        world.weatherConfig = WeatherConfig(intensity = WeatherIntensity.CALM)
        val weather = world.weatherFor(body)!!
        weather.tsunamiModel.force(origin, 0.0, strength = 1.0, period = 600.0)
        val spot = east(400.0)
        val arrival = weather.tsunamiModel.arrivalAt(origin, spot)
        world.restore(WorldSave(catalogHash = catalog.contentHash, universeTime = arrival - 60.0, nextVesselId = 1L))
        val site = LaunchSite("field", "Field", body.id, 0.0, SolarSystem.longitudeOf(spot))
        val boat = world.spawnOnSurface(StockCraft.boat(catalog), site)
        val dt = 1.0 / 60.0
        repeat((5.0 / dt).toInt()) { world.step(dt) }
        if (!boat.dormant) boat.sleep(body.rotationAt(world.time))
        assertTrue("parked asleep on dry land", boat.dormant && !boat.afloat)
        fun along() = SolarSystem.longitudeOf(boat.body.position.normalized()) * radius
        val from = along()
        var wokeAt = Double.NaN
        var floated = false
        var furthest = 0.0
        while (world.time < arrival + 900.0) {
            world.step(dt)
            if (wokeAt.isNaN() && !boat.dormant) wokeAt = world.time - arrival
            if (boat.buoyed && !boat.touchingGround) floated = true
            furthest = maxOf(furthest, abs(along() - from))
        }
        println("parked boat woke ${wokeAt} s after arrival, floated $floated, carried up to $furthest m")
        assertTrue("woke", !wokeAt.isNaN())
        assertTrue("floated", floated)
        assertTrue("carried: $furthest m", furthest > 30.0)
    }

    /** The shader's foam (Shaders.TSUNAMI_FOAM) from crest, whiteness and period, sharp. */
    private fun drawnFoam(c: Double, w: Double, period: Double): Double {
        if (abs(w) < 0.01) return 0.0
        val k = if (period > 0.0) maxOf(kotlin.math.floor(c / period + 0.5), 0.0) else 0.0
        val s = c - k * period
        var white = if (w > 0.0) (if (s > -3.0 && s < 15.0) 1.0 else 0.0) else (if (s > -8.0 && s < 0.0) 1.0 else 0.0)
        val behind = s - 15.0
        if (w > 0.0 && behind > 0.0 && behind < 52.0) {
            val line = 0.5 + 0.5 * cos(2.0 * Math.PI * behind / 7.0)
            val edge = 0.45 + 0.55 * smooth(0.0, 52.0, behind)
            white = maxOf(white, smooth(edge, edge + 0.1, line) * 0.9)
        }
        return white * abs(w)
    }

    private fun smooth(a: Double, b: Double, x: Double): Double {
        val t = ((x - a) / (b - a)).coerceIn(0.0, 1.0)
        return t * t * (3.0 - 2.0 * t)
    }

    @Test
    fun `foam drawn per pixel from the crest's time is where the model has it`() {
        val (w, _) = flatSea()
        val model = w.tsunamiModel
        var seaSame = 0; var seaWhite = 0; var seaMiss = 0
        var landSame = 0; var landWhite = 0; var landMiss = 0
        var m = -12_000.0
        while (m < 2_000.0) {
            val d = east(m)
            val bed = flat.elevation(d)
            val arrival = model.arrivalAt(origin, d)
            var t = arrival
            while (t < arrival + 1_500.0) {
                model.height(d, t, bed)
                val a = model.foam() > 0.5
                val b = drawnFoam(model.crest(), model.whiteness(), model.period()) > 0.5
                if (bed <= 0.0) { if (a || b) { seaWhite++; if (a == b) seaSame++ else seaMiss++ } }
                else if (a || b) { landWhite++; if (a == b) landSame++ else landMiss++ }
                t += 2.0
            }
            m += 50.0
        }
        println("drawn foam agrees with the model: sea $seaSame of $seaWhite white, land $landSame of $landWhite")
        assertTrue("sea foam seen: $seaWhite", seaWhite > 200)
        assertTrue("land foam seen: $landWhite", landWhite > 50)
        assertTrue("sea foam where the model has it: $seaMiss of $seaWhite off", seaMiss <= seaWhite / 20)
        assertTrue("land foam where the model has it: $landMiss of $landWhite off", landMiss <= landWhite / 5)
    }
}
