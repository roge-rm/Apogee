package com.rm.apogee.core.world

import com.rm.apogee.core.craft.CraftDesign
import com.rm.apogee.core.craft.CraftOrientation
import com.rm.apogee.core.craft.PlacedPart
import com.rm.apogee.core.craft.StockCraft
import com.rm.apogee.core.craft.Vessel
import com.rm.apogee.core.math.Quat
import com.rm.apogee.core.math.Vec3
import com.rm.apogee.core.orbit.SolarSystem
import com.rm.apogee.core.part.Sail
import com.rm.apogee.core.part.StockParts
import com.rm.apogee.core.sea.Rogue
import com.rm.apogee.core.sea.Sea
import com.rm.apogee.core.sea.SeaSample
import com.rm.apogee.core.terrain.Noise
import com.rm.apogee.core.weather.Storms
import com.rm.apogee.core.weather.Weather
import com.rm.apogee.core.weather.WeatherConfig
import com.rm.apogee.core.weather.WeatherIntensity
import com.rm.apogee.core.weather.frame
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

/** Breaking crests and green water hit craft: big breakers smash small boats, ships ride them. */
class SlamTest {
    private val catalog = StockParts.catalog
    private val system = SolarSystem.defaultSystem()
    private val terra = system.body(SolarSystem.HOMEWORLD_ID)
    private val dt = 1.0 / 60.0

    private fun world(intensity: WeatherIntensity, time: Double): World = World.default(catalog).also {
        it.weatherConfig = WeatherConfig(intensity = intensity)
        it.restore(WorldSave(catalogHash = catalog.contentHash, universeTime = time, nextVesselId = 1L))
    }

    /** A strong storm over deep sea on [intensity], as a site under it and the time it's at its worst. */
    private fun stormSea(intensity: WeatherIntensity): Pair<LaunchSite, Double> = storms.getOrPut(intensity) {
        val terrain = terra.terrain!!
        val weather = Weather(terra, WeatherConfig(intensity = intensity))
        val sea = Sea(terra, system.body("luna"), weather, WeatherConfig().seed)
        val e = Vec3(); val n = Vec3()
        val list = ArrayList<Storms.Storm>()
        val sample = SeaSample()
        for (i in 0 until 2_000) {
            val lat = (Noise.hash(23, i, 0, 0) - 0.5) * 1.4
            val lon = Noise.hash(23, i, 1, 0) * 2 * Math.PI
            val d = Vec3(cos(lat) * cos(lon), sin(lat), cos(lat) * sin(lon)).normalizeInPlace()
            if (terrain.elevation(d) > -300.0) continue
            frame(d, e, n)
            list.clear()
            weather.stormModel.around(d, e, n, 1, 20_000.0, list)
            for (st in list) {
                if (st.strength < 0.8) continue
                val t = st.start + 0.5 * Storms.CYCLE
                val c = weather.stormModel.centreAt(st, t, Vec3())
                if (terrain.elevation(c) > -300.0) continue
                if (sea.sample(c.copy().mulInPlace(terra.radius), t, sample).stormHeight < 6.0) continue
                return@getOrPut LaunchSite("storm", "Storm", "terra", SolarSystem.latitudeOf(c), SolarSystem.longitudeOf(c)) to t
            }
        }
        throw AssertionError("no storm sea")
    }

    /** The tallest Extreme storm rogue over deep sea standing its full height, as a site where it peaks. */
    private fun rogue(): Pair<LaunchSite, Rogue> = rogueFound ?: run {
        val weather = Weather(terra, WeatherConfig(intensity = WeatherIntensity.EXTREME))
        val sea = Sea(terra, system.body("luna"), weather, WeatherConfig().seed)
        val terrain = terra.terrain!!
        val e = Vec3(); val n = Vec3()
        val list = ArrayList<Storms.Storm>()
        val rogues = ArrayList<Rogue>()
        var count = 0
        for (i in 0 until 3_000) {
            if (count >= 25) break
            val lat = (Noise.hash(23, i, 0, 0) - 0.5) * 1.4
            val lon = Noise.hash(23, i, 1, 0) * 2 * Math.PI
            val d = Vec3(cos(lat) * cos(lon), sin(lat), cos(lat) * sin(lon)).normalizeInPlace()
            if (terrain.elevation(d) > -300.0) continue
            frame(d, e, n)
            list.clear()
            weather.stormModel.around(d, e, n, 1, 20_000.0, list)
            val st = list.firstOrNull { it.strength >= 0.6 } ?: continue
            val t = st.start + 0.5 * Storms.CYCLE
            val c = weather.stormModel.centreAt(st, t, Vec3())
            if (terrain.elevation(c) > -300.0) continue
            count++
            val slot = kotlin.math.floor(t / 600.0).toInt()
            val p = c.copy().mulInPlace(terra.radius)
            val cx = kotlin.math.floor(p.x / 6_000.0).toInt(); val cy = kotlin.math.floor(p.y / 6_000.0).toInt(); val cz = kotlin.math.floor(p.z / 6_000.0).toInt()
            for (s in slot - 2..slot + 2) for (a in -3..3) for (b in -3..3) for (cc in -3..3) {
                val r = sea.rogueIn(cx + a, cy + b, cz + cc, s, true) ?: continue
                if (r.exists && r.storm && rogues.none { it.focus.x == r.focus.x && it.time == r.time }) rogues.add(r)
            }
        }
        // Standing its full height where it peaks: crest to the hole in front, as tall as its sea makes it.
        fun peak(r: Rogue): Double {
            val l = 2.0 * Math.PI / r.k
            var crest = -Double.MAX_VALUE; var trough = Double.MAX_VALUE
            for (k in 0..200) {
                val x = -l + k * l / 100.0
                val at = r.focus.copy().addScaledInPlace(r.along, x).normalizeInPlace().mulInPlace(terra.radius)
                val h = sea.rogueAt(at, r.time)
                if (abs(x) < 0.25 * l) crest = maxOf(crest, h)
                if (x > 0.2 * l && x < 0.8 * l) trough = minOf(trough, h)
            }
            return crest - trough
        }
        val r = rogues.sortedByDescending { it.height }.first {
            val expected = minOf(it.ratio * sea.sample(it.focus, it.time - 210.0, SeaSample()).significantHeight, it.cap)
            abs(peak(it) - expected) < 0.05 * expected
        }
        val at = r.focus.normalized()
        (LaunchSite("rogue", "Rogue", "terra", SolarSystem.latitudeOf(at), SolarSystem.longitudeOf(at)) to r).also { rogueFound = it }
    }

    /** Two punt hulls end to end on two thin girders. */
    private fun raft(): CraftDesign = CraftDesign(
        name = "Raft",
        parts = listOf(
            PlacedPart("hull-punt", Vec3(0.0, 0.0, 0.0), Quat.identity()),
            PlacedPart("girder-long", Vec3(0.0, 7.0, 0.0), Quat.identity(), parentIndex = 0, parentNodeId = "top", ownNodeId = "bottom"),
            PlacedPart("girder-long", Vec3(0.0, 14.0, 0.0), Quat.identity(), parentIndex = 1, parentNodeId = "top", ownNodeId = "bottom"),
            PlacedPart("hull-punt", Vec3(0.0, 21.0, 0.0), Quat.identity(), parentIndex = 2, parentNodeId = "top", ownNodeId = "bottom"),
        ),
        catalogHash = catalog.contentHash,
        orientation = CraftOrientation.HORIZONTAL,
    )

    /** The slam on each part of [v] now, summed, in N. */
    private fun slam(v: Vessel): Double {
        var total = 0.0
        val load = v.slamLoad
        for (i in 0 until load.size / 6) {
            val o = i * 6
            total += sqrt(load[o] * load[o] + load[o + 1] * load[o + 1] + load[o + 2] * load[o + 2])
        }
        return total
    }

    private class Ride(
        val lost: Int, val slam: Double, val stress: Double, val end: Vec3, val slams: DoubleArray, val hs: Double,
        /** How upright it ended and at its least, 1 upright and -1 upside down. */
        val upEnd: Double, val upLeast: Double,
        val causes: List<String>,
        /** Parts other than masts and sails torn off or broken. */
        val hull: Int,
        /** Masts torn off or sails blown out. */
        val rigs: Int,
    ) {
        val capsized: Boolean get() = upEnd < 0.0
    }

    /**
     * Rides [design] for [seconds] from [time] at [site] on [intensity]: parts lost, the most slam
     * on it over its weight, and its worst stress past the first seconds. Kept awake throughout.
     * With [slamOn] off the breakers don't hit it.
     */
    private fun ride(
        design: CraftDesign, intensity: WeatherIntensity, site: LaunchSite, time: Double, seconds: Double,
        slamOn: Boolean = true, heading: Heading? = null, rogue: Rogue? = null, setUp: (Vessel) -> Unit = {},
    ): Ride {
        val world = world(intensity, time)
        world.hydrostatics.slamOn = slamOn
        val v = world.spawnOnSurface(design, site)
        v.control.holdDepth = true
        if (heading != null) face(v, world, rogue!!, heading)
        setUp(v)
        val parts = v.defs.size
        var most = 0.0; var stress = 0.0; var hs = 0.0; var least = 1.0
        val causes = ArrayList<String>()
        var hull = 0; var rigs = 0
        repeat((seconds / dt).toInt()) { tick ->
            world.step(dt)
            most = maxOf(most, slam(v) / (v.body.mass * 9.81))
            hs = maxOf(hs, world.hydrostatics.seaHeight)
            if (tick * dt > 8.0) stress = maxOf(stress, v.stress)
            least = minOf(least, upright(v))
            for (e in world.drainEvents()) if (e is WorldEvent.PartDetached) {
                causes.add(e.cause)
                if (catalog.require(e.partId).module<Sail>() != null) rigs++ else hull++
            }
        }
        for (i in v.defs.indices) if (v.broken[i]) { if (v.defs[i].module<Sail>() != null) rigs++ else hull++ }
        return Ride(
            parts - v.defs.size + v.broken.count { it }, most, stress, v.body.position.copy(), v.slamLoad.copyOf(), hs,
            upright(v), least, causes, hull, rigs,
        )
    }

    enum class Heading { INTO, BEAM, BEFORE }

    /** How upright [v] is: its up along the vertical. */
    private fun upright(v: Vessel): Double =
        v.body.orientation.rotate(v.design.orientation.up) dot v.body.position.normalized()

    /** Turns [v] about the vertical to meet [r]'s sea bow on, beam on or running before it. */
    private fun face(v: Vessel, world: World, r: Rogue, heading: Heading) {
        val up = v.body.position.normalized()
        val run = terra.rotationAt(world.time).rotate(r.along)
        run.addScaledInPlace(up, -(run dot up)).normalizeInPlace()
        val want = when (heading) {
            Heading.INTO -> run.copy().mulInPlace(-1.0)
            Heading.BEAM -> up.copy().crossInPlace(run)
            Heading.BEFORE -> run
        }
        val bow = v.body.orientation.rotate(v.design.orientation.forward)
        bow.addScaledInPlace(up, -(bow dot up)).normalizeInPlace()
        val angle = kotlin.math.atan2(bow.copy().crossInPlace(want) dot up, bow dot want)
        v.body.orientation.setTo(Quat.fromAxisAngle(up, angle).mulInPlace(v.body.orientation)).normalizeInPlace()
    }

    @Test
    fun `calm and ordinary seas don't slam`() {
        val (site, time) = stormSea(WeatherIntensity.WILD)
        for (intensity in listOf(WeatherIntensity.CALM, WeatherIntensity.NORMAL)) {
            for (design in listOf(StockCraft.boat(catalog), StockCraft.skiff(catalog), StockCraft.jetSki(catalog), StockCraft.coaster(catalog))) {
                val r = ride(design, intensity, site, time, 60.0)
                assertTrue("the ${design.name} on $intensity was slammed at ${r.slam} of its weight", r.slam < 0.01)
            }
        }
    }

    @Test
    fun `small boats ride out a wild storm sea, whose crests barely slam`() {
        val (site, time) = stormSea(WeatherIntensity.WILD)
        for (design in listOf(StockCraft.skiff(catalog), StockCraft.sloop(catalog), StockCraft.jetBoat(catalog), StockCraft.jetSki(catalog))) {
            val r = ride(design, WeatherIntensity.WILD, site, time, 120.0)
            println("Wild storm sea: ${design.name} in Hs ${r.hs} m, slam ${r.slam} of its weight, stress ${r.stress}")
            assertTrue("a sea of ${r.hs} m", r.hs > 6.0)
            // Crests break here, but stand under SLAM_HEIGHT_FROM; Extreme's are what slam.
            assertTrue("the ${design.name} was slammed at ${r.slam} of its weight", r.slam < 0.05)
            assertTrue("the ${design.name} lost parts", r.lost == 0)
        }
    }

    @Test
    fun `a rogue's breaker works small boats hard and breaks a flimsy raft`() {
        val (site, r) = rogue()
        // The same ride with the breakers off is the control each time.
        for (design in listOf(StockCraft.jetBoat(catalog), StockCraft.jetSki(catalog))) {
            val ride = ride(design, WeatherIntensity.EXTREME, site, r.time - 20.0, 150.0)
            val calm = ride(design, WeatherIntensity.EXTREME, site, r.time - 20.0, 150.0, slamOn = false)
            println("${r.height} m rogue: ${design.name} stress ${ride.stress}, slam ${ride.slam} of its weight; without ${calm.stress}")
            assertTrue("the ${design.name} took no slam", ride.slam > 1.0 && calm.slam == 0.0)
            assertTrue("the ${design.name} at ${ride.stress} against ${calm.stress} without", ride.stress > 3.0 * calm.stress)
        }
        val ride = ride(raft(), WeatherIntensity.EXTREME, site, r.time - 40.0, 150.0)
        val calm = ride(raft(), WeatherIntensity.EXTREME, site, r.time - 40.0, 150.0, slamOn = false)
        println("${r.height} m rogue: raft lost ${ride.lost}, stress ${ride.stress}; without lost ${calm.lost}, stress ${calm.stress}")
        assertTrue("the raft lost nothing", ride.lost > 0)
        assertTrue("the raft lost ${calm.lost} with no slam", calm.lost == 0)
    }

    /**
     * Beam on, a rogue's breaker rolls every small boat past her beam ends or tears something off.
     * The Punt and the Cutter ride the same sea upright with the breakers off. The Jet Boat and Jet
     * Ski turn over in that sea anyway, having no keel to right them.
     */
    @Test
    fun `small boats beam on to a rogue's breaker are rolled over or lose parts`() {
        val (site, r) = rogue()
        for (design in smallBoats()) {
            val beam = ride(design, WeatherIntensity.EXTREME, site, r.time - 20.0, 150.0, heading = Heading.BEAM, rogue = r)
            val bow = ride(design, WeatherIntensity.EXTREME, site, r.time - 20.0, 150.0, heading = Heading.INTO, rogue = r)
            println(
                "${design.name}: beam on lost ${beam.lost}, least upright ${beam.upLeast}, ended ${beam.upEnd}; " +
                    "bow on lost ${bow.lost}, least upright ${bow.upLeast}, ended ${bow.upEnd}",
            )
            assertTrue("the ${design.name} beam on stayed upright and whole", beam.upLeast < 0.0 || beam.lost > 0)
        }
        for (design in listOf(StockCraft.boat(catalog), StockCraft.cutter(catalog))) {
            val calm = ride(design, WeatherIntensity.EXTREME, site, r.time - 20.0, 150.0, slamOn = false, heading = Heading.BEAM, rogue = r)
            assertTrue("the ${design.name} rolled to ${calm.upLeast} or lost ${calm.lost} with no slam", calm.upLeast > 0.0 && calm.lost == 0)
        }
    }

    private fun smallBoats() = listOf(
        StockCraft.boat(catalog), StockCraft.skiff(catalog), StockCraft.jetBoat(catalog), StockCraft.jetSki(catalog),
        StockCraft.sloop(catalog), StockCraft.cutter(catalog),
    )

    @Test
    fun `stock ships ride a 38 m rogue keeping their hulls`() {
        val (site, r) = rogue()
        assertTrue("the rogue is ${r.height} m", r.height > 37.0)
        for (design in listOf(
            StockCraft.trawler(catalog), StockCraft.coaster(catalog), StockCraft.schooner(catalog), StockCraft.deckBarge(catalog),
            StockCraft.harbourTug(catalog), StockCraft.landingBarge(catalog), StockCraft.flatTop(catalog),
        )) {
            val ride = ride(design, WeatherIntensity.EXTREME, site, r.time - 90.0, 150.0)
            println("${design.name}: slam ${ride.slam} of its weight, stress ${ride.stress}")
            assertTrue("the ${design.name} took no slam", ride.slam > 0.1)
            assertTrue("the ${design.name} lost ${ride.hull} parts besides her rig", ride.hull == 0)
        }
    }

    @Test
    fun `a schooner rolled over by a rogue's breaker loses her masts`() {
        val (site, r) = rogue()
        var dismasted = 0
        val rides = listOf(-40.0 to Heading.INTO, -40.0 to Heading.BEAM, -40.0 to Heading.BEFORE, -20.0 to Heading.BEAM)
        for ((at, heading) in rides) {
            val ride = ride(StockCraft.schooner(catalog), WeatherIntensity.EXTREME, site, r.time + at, 150.0, heading = heading, rogue = r)
            println("Schooner $heading from ${-at} s before: least upright ${ride.upLeast}, masts lost ${ride.rigs}, other parts ${ride.hull}")
            assertTrue("rolled to ${ride.upLeast}", ride.upLeast < 0.0)
            assertTrue("she lost ${ride.hull} parts besides her rig", ride.hull == 0)
            if (ride.rigs > 0) dismasted++
        }
        assertTrue("dismasted $dismasted times in ${rides.size}", dismasted * 2 > rides.size)
    }

    /** A boat sailing with [sheet] set in a steady [wind] (m/s, across her) off the Cape, or in [world]'s weather. */
    private fun sail(design: CraftDesign, sheet: Double, wind: Double? = null, world: World = World.default(catalog), site: LaunchSite = capeSea(), seconds: Double = 120.0): Ride {
        if (wind != null) world.steadyWind = Vec3(0.0, wind, 0.0)
        val v = world.spawnOnSurface(design, site)
        world.assignOwner(v, "p1"); world.seatCrew(v)
        world.apply(Command.SetSas(v.id.raw, true))
        repeat((10.0 / dt).toInt()) { world.step(dt) }
        world.apply(Command.SetThrottle(v.id.raw, sheet))
        var hull = 0; var rigs = 0; var least = 1.0
        repeat((seconds / dt).toInt()) {
            world.step(dt)
            least = minOf(least, upright(v))
            for (e in world.drainEvents()) if (e is WorldEvent.PartDetached) {
                if (catalog.require(e.partId).module<Sail>() != null) rigs++ else hull++
            }
        }
        for (i in v.defs.indices) if (v.broken[i]) { if (v.defs[i].module<Sail>() != null) rigs++ else hull++ }
        return Ride(0, 0.0, v.stress, v.body.position.copy(), DoubleArray(0), 0.0, upright(v), least, emptyList(), hull, rigs)
    }

    private fun capeSea(): LaunchSite {
        val d = com.rm.apogee.core.terrain.Seabed.capeDirection(-8_000.0, 12_000.0)
        return LaunchSite("sea", "Sea", "terra", SolarSystem.latitudeOf(d), SolarSystem.longitudeOf(d))
    }

    @Test
    fun `full sail in an eyewall's wind blows out, reefed or bare poles hold`() {
        for (design in listOf(StockCraft.schooner(catalog), StockCraft.sloop(catalog))) {
            val full = sail(design, 1.0, wind = 60.0)
            val reefed = sail(design, 0.3, wind = 60.0)
            val bare = sail(design, 0.0, wind = 60.0)
            println("${design.name} in 60 m/s: full sail lost ${full.rigs} rigs, reefed ${reefed.rigs}, bare poles ${bare.rigs}")
            assertTrue("full sail held", full.rigs > 0)
            assertTrue("bare poles lost ${bare.rigs}", bare.rigs == 0 && bare.hull == 0)
            if (design.name == "Schooner") assertTrue("reefed lost ${reefed.rigs}", reefed.rigs == 0 && reefed.hull == 0)
        }
    }

    @Test
    fun `a land yacht's own speed doesn't tear its sail, a gale across it does`() {
        val buggy = StockCraft.buggy(catalog)
        val yacht = buggy.copy(name = "Land Yacht", parts = buggy.parts + PlacedPart("sail-sloop", Vec3(0.0, 0.0, 3.0), parentIndex = 0))
        for ((speed, wind) in listOf(45.0 to 0.0, 0.0 to 45.0)) {
            val world = World.default(catalog)
            world.steadyWind = Vec3(0.0, wind, 0.0)
            val v = world.spawnOnSurface(yacht, World.launchSites.first { it.id == "cape" })
            repeat(60) { world.step(dt) }
            v.control.throttle = 1.0
            val rig = v.defs.indexOfFirst { it.module<Sail>() != null }
            val ground = Vec3(); val bow = Vec3()
            repeat(120) {
                // Held at its speed along its bow over the ground.
                terra.surfaceVelocityAt(v.body.position, ground)
                v.body.orientation.rotate(v.design.orientation.forward, bow)
                v.body.linearVelocity.setTo(ground).addScaledInPlace(bow, speed)
                world.step(dt)
            }
            println("Land yacht at $speed m/s in $wind m/s of wind: sail torn ${v.isBroken(rig)}")
            assertEquals("at $speed m/s in $wind m/s of wind", wind > 0.0, v.isBroken(rig))
        }
    }

    @Test
    fun `a sail stowed by its action group meets the water furled`() {
        val (site, r) = rogue()
        val plain = StockCraft.schooner(catalog)
        val grouped = plain.copy(parts = plain.parts.mapIndexed { i, p -> if (catalog.require(p.partId).module<Sail>() != null) p.copy(group = 1) else p })
        fun go(design: CraftDesign, sheet: Double, stowed: Boolean) =
            ride(design, WeatherIntensity.EXTREME, site, r.time - 40.0, 60.0, heading = Heading.BEAM, rogue = r, setUp = { v ->
                v.control.throttle = sheet
                if (stowed) v.groupStates[1] = -1
            })
        val stowed = go(grouped, 1.0, true)
        val furled = go(plain, 0.0, false)
        val set = go(grouped, 1.0, false)
        assertEquals(furled.end, stowed.end)
        assertTrue("stowed rode like set", set.end != stowed.end)
    }

    @Test
    fun `sailing in a wild sea keeps the rig`() {
        val (storm, time) = stormSea(WeatherIntensity.WILD)
        for (design in listOf(StockCraft.schooner(catalog), StockCraft.sloop(catalog))) for (sheet in listOf(1.0, 0.5)) {
            for (site in listOf(capeSea(), storm)) {
                val ride = sail(design, sheet, world = world(WeatherIntensity.WILD, time), site = site, seconds = 300.0)
                println("${design.name} at ${site.id} with $sheet set: rigs lost ${ride.rigs}, other ${ride.hull}, least upright ${ride.upLeast}")
                assertTrue("${design.name} at ${site.id} lost ${ride.rigs} rigs", ride.rigs == 0 && ride.hull == 0)
            }
        }
    }

    @Test
    fun `a submarine deep under a rogue feels nothing`() {
        val (site, r) = rogue()
        val ride = ride(StockCraft.minnow(catalog), WeatherIntensity.EXTREME, site, r.time - 90.0, 150.0, setUp = { v ->
            v.body.position.addScaledInPlace(v.body.position.normalized(), -40.0)
            v.control.holdDepthAt = 40.0
        })
        val depth = terra.radius - ride.end.length
        assertTrue("it ended $depth m down", depth > 25.0)
        assertTrue("slammed at ${ride.slam} of its weight", ride.slam == 0.0)
    }

    @Test
    fun `a breaker never throws a light craft past its crest's speed`() {
        val (site, r) = rogue()
        val world = world(WeatherIntensity.EXTREME, r.time - 60.0)
        val v = world.spawnOnSurface(StockCraft.jetSki(catalog), site)
        v.control.holdDepth = true
        val ground = Vec3()
        var crest = 0.0; var over = -Double.MAX_VALUE; var hit = 0
        repeat((80.0 / dt).toInt()) {
            world.step(dt)
            if (!v.slamming) return@repeat
            hit++
            val h = world.hydrostatics
            terra.surfaceVelocityAt(v.body.position, ground)
            val along = (v.body.linearVelocity.copy().subInPlace(ground)) dot h.lastSeaWay
            crest = maxOf(crest, h.lastCrestSpeed)
            over = maxOf(over, along - h.lastCrestSpeed)
        }
        println("crest ${crest} m/s, slammed $hit ticks, most past the crest $over m/s")
        assertTrue("slammed $hit ticks", hit > 10)
        assertTrue("the crest ran at $crest m/s", crest > 15.0)
        assertTrue("thrown $over m/s past the crest", over < 1.0)
    }

    @Test
    fun `ships in a breaking storm sea stay awake and wake when one reaches them`() {
        val (site, r) = rogue()
        // Asleep in a calm, and in the storm too with breakers not keeping it awake, so it's the
        // breakers that do.
        for ((intensity, wake, sleeps) in listOf(
            Triple(WeatherIntensity.CALM, true, true), Triple(WeatherIntensity.EXTREME, true, false), Triple(WeatherIntensity.EXTREME, false, true),
        )) {
            val world = world(intensity, r.time - 400.0)
            world.breakersWake = wake
            val v = world.spawnOnSurface(StockCraft.flatTop(catalog), site)
            var asleep = 0
            repeat((120.0 / dt).toInt()) { world.step(dt); if (v.dormant) asleep++ }
            println("Flat Top on $intensity, breakers wake $wake: asleep $asleep ticks")
            assertEquals("asleep $asleep ticks on $intensity, breakers wake $wake", sleeps, asleep > 0)
        }
        // Asleep in a calm, then the storm comes: set down asleep on the new sea, the breakers wake it.
        for (wake in listOf(true, false)) {
            val world = world(WeatherIntensity.CALM, r.time - 400.0)
            world.breakersWake = wake
            val v = world.spawnOnSurface(StockCraft.flatTop(catalog), site)
            repeat((120.0 / dt).toInt()) { world.step(dt) }
            assertTrue("never slept", v.dormant)
            world.weatherConfig = WeatherConfig(intensity = WeatherIntensity.EXTREME)
            assertTrue("woken by the change itself", v.dormant)
            repeat(60) { world.step(dt) }
            assertEquals("asleep with breakers wake $wake", !wake, v.dormant)
        }
    }

    @Test
    fun `slam is the same every run`() {
        val (site, r) = rogue()
        val a = ride(StockCraft.jetBoat(catalog), WeatherIntensity.EXTREME, site, r.time - 30.0, 40.0)
        val b = ride(StockCraft.jetBoat(catalog), WeatherIntensity.EXTREME, site, r.time - 30.0, 40.0)
        assertTrue("it never slammed", a.slam > 0.1)
        assertEquals(a.end, b.end)
        assertTrue(a.slams.contentEquals(b.slams) && a.slam == b.slam && a.lost == b.lost)
    }

    /** Sails [design] at [site] in [world] with SAS on and [sheet] set for [seconds]: lost, sails torn, worst rig load, least upright. */
    private fun sailing(world: World, design: CraftDesign, site: LaunchSite, sheet: Double, seconds: Double): String {
        val boat = world.spawnOnSurface(design, site)
        world.assignOwner(boat, "p1"); world.seatCrew(boat)
        world.apply(Command.SetSas(boat.id.raw, true))
        repeat((10.0 / dt).toInt()) { world.step(dt) }
        world.apply(Command.SetThrottle(boat.id.raw, sheet))
        val parts = boat.defs.size
        var worst = 0.0; var least = 1.0; var wind = 0.0
        val causes = ArrayList<String>()
        repeat((seconds / dt).toInt()) {
            world.step(dt)
            for (i in boat.defs.indices) if (boat.defs[i].module<com.rm.apogee.core.part.Sail>() != null) worst = maxOf(worst, boat.jointLoad[i].toDouble())
            least = minOf(least, upright(boat))
            wind = maxOf(wind, boat.air.wind.length)
            for (e in world.drainEvents()) if (e is WorldEvent.PartDetached) causes.add(e.cause) else if (e is WorldEvent.PartFailed) causes.add(e.reason)
        }
        return "lost ${parts - boat.defs.size} torn ${boat.broken.count { it }} rig ${"%.2f".format(worst)} least ${"%.2f".format(least)} wind ${"%.1f".format(wind)} $causes"
    }

    private companion object {
        val storms = HashMap<WeatherIntensity, Pair<LaunchSite, Double>>()
        var rogueFound: Pair<LaunchSite, Rogue>? = null
    }
}
