package com.rm.apogee.core.world

import com.rm.apogee.core.craft.CraftDesign
import com.rm.apogee.core.craft.StockCraft
import com.rm.apogee.core.craft.Vessel
import com.rm.apogee.core.math.Vec3
import com.rm.apogee.core.part.StockParts
import com.rm.apogee.core.sea.SeaSample
import com.rm.apogee.core.terrain.Noise
import com.rm.apogee.core.weather.Storms
import com.rm.apogee.core.weather.WeatherConfig
import com.rm.apogee.core.weather.WeatherIntensity
import com.rm.apogee.core.weather.frame
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.asin
import kotlin.math.atan2

/** Boats at sea: heaving on the swell, swamped by a storm, riding the tide asleep. */
class SeawayTest {

    private val catalog = StockParts.catalog
    private val dt = 1.0 / 60.0

    private fun wildWorld(): World = World.default(catalog).also { it.weatherConfig = WeatherConfig(intensity = WeatherIntensity.WILD) }

    private fun siteAt(direction: Vec3) = LaunchSite("here", "Here", "terra", asin(direction.y), atan2(direction.z, direction.x))

    private fun randomDirection(i: Int): Vec3 {
        val lat = (Noise.hash(21, i, 0, 0) - 0.5) * 1.6
        val lon = Noise.hash(21, i, 1, 0) * 2 * Math.PI
        return Vec3(kotlin.math.cos(lat) * kotlin.math.cos(lon), kotlin.math.sin(lat), kotlin.math.cos(lat) * kotlin.math.sin(lon))
    }

    private fun aboveWater(world: World, vessel: Vessel): Double {
        val terra = world.attractorFor(vessel)
        val fixed = terra.toBodyFixed(vessel.body.position, terra.rotationAt(world.time))
        return terra.altitudeOf(vessel.body.position) - terra.ocean!!.surfaceHeight(fixed, world.time)
    }

    private fun tilt(vessel: Vessel): Double {
        val deck = vessel.body.orientation.rotate(Vec3(0.0, 0.0, 1.0))
        val up = vessel.body.position.copy().normalizeInPlace()
        return Math.toDegrees(kotlin.math.acos((deck dot up).coerceIn(-1.0, 1.0)))
    }

    /** Somewhere deep with a sea of [lo]-[hi] m at [time]. */
    private fun seaOf(world: World, time: Double, lo: Double, hi: Double): Vec3 {
        val terra = world.system.body("terra")
        val sample = SeaSample()
        for (i in 0 until 20_000) {
            val d = randomDirection(i)
            if (terra.terrain!!.elevation(d) > -400.0) continue
            terra.ocean!!.sample(d, time, sample)
            if (sample.significantHeight in lo..hi && sample.stormHeight < 0.5) return d
        }
        throw AssertionError("no sea of $lo-$hi m")
    }

    private fun launch(world: World, design: CraftDesign, where: Vec3): Vessel = world.spawnOnSurface(design, siteAt(where))

    @Test
    fun `a boat on the swell heaves and pitches with it, and stays upright`() {
        val world = wildWorld()
        world.skipTo(20_000.0)
        val where = seaOf(world, world.time, 1.5, 4.0)
        val boat = launch(world, StockCraft.boat(catalog), where)
        val terra = world.attractorFor(boat)
        var lo = Double.MAX_VALUE; var hi = -Double.MAX_VALUE
        var tiltMost = 0.0; var tiltLeast = 180.0
        repeat((60.0 / dt).toInt()) {
            world.step(dt)
            val a = terra.altitudeOf(boat.body.position)
            lo = minOf(lo, a); hi = maxOf(hi, a)
            val t = tilt(boat); tiltMost = maxOf(tiltMost, t); tiltLeast = minOf(tiltLeast, t)
        }
        assertTrue("heaved ${hi - lo} m", hi - lo > 0.5)
        assertTrue("rocked ${tiltMost - tiltLeast} degrees", tiltMost - tiltLeast > 2.0)
        assertTrue("upright: $tiltMost degrees at worst", tiltMost < 60.0)
        assertTrue("afloat: ${aboveWater(world, boat)} m", aboveWater(world, boat) > -2.0)
    }

    /** A grown storm's sea, somewhere deep, and when. */
    private fun stormSea(world: World): Pair<Vec3, Double> {
        val terra = world.system.body("terra")
        val weather = world.weatherFor(terra)!!
        val sample = SeaSample()
        val e = Vec3(); val n = Vec3()
        val list = ArrayList<Storms.Storm>()
        for (i in 0 until 1_500) {
            val d = randomDirection(i)
            if (terra.terrain!!.elevation(d) > -500.0) continue
            frame(d, e, n)
            list.clear()
            weather.stormModel.around(d, e, n, 1, 10_000.0, list)
            for (st in list) {
                if (st.strength < 0.85) continue
                val t = st.start + 0.5 * Storms.CYCLE
                val c = weather.stormModel.centreAt(st, t, Vec3())
                if (terra.terrain!!.elevation(c) > -500.0) continue
                terra.ocean!!.sample(c, t, sample)
                if (sample.significantHeight > 8.0) return c to t
            }
        }
        throw AssertionError("no big storm sea")
    }

    @Test
    fun `a storm sea swamps a skiff, and the Cutter rides it out`() {
        val probe = wildWorld()
        val (where, t) = stormSea(probe)

        val skiffWorld = wildWorld()
        skiffWorld.skipTo(t - 30.0)
        val skiff = launch(skiffWorld, StockCraft.skiff(catalog), where)
        var shipped = 0.0
        var capsized = false
        var sank = false
        repeat((240.0 / dt).toInt()) {
            skiffWorld.step(dt)
            shipped = maxOf(shipped, skiff.flooded.sum())
            if (tilt(skiff) > 100.0) capsized = true
            if (aboveWater(skiffWorld, skiff) < -3.0) sank = true
        }
        assertTrue("the skiff shipped $shipped kg, capsized: $capsized, sank: $sank", shipped > 1_500.0 || capsized || sank)

        val cutterWorld = wildWorld()
        cutterWorld.skipTo(t - 30.0)
        val cutter = launch(cutterWorld, StockCraft.cutter(catalog), where)
        var cutterSlept = false
        repeat((150.0 / dt).toInt()) { cutterWorld.step(dt); if (cutter.dormant) cutterSlept = true }
        // Not riding it asleep, like a cork: a sea this big is the physics' to play out.
        assertTrue("the Cutter stayed awake in it", !cutterSlept)
        assertTrue("the Cutter is decked: ${cutter.flooded.sum()} kg shipped", cutter.flooded.sum() == 0.0)
        assertTrue("the Cutter is afloat: ${aboveWater(cutterWorld, cutter)} m", aboveWater(cutterWorld, cutter) > -3.0)

        val trawlerWorld = wildWorld()
        trawlerWorld.skipTo(t - 30.0)
        val trawler = launch(trawlerWorld, StockCraft.trawler(catalog), where)
        var worst = 0.0
        var trawlerSlept = false
        repeat((150.0 / dt).toInt()) { trawlerWorld.step(dt); worst = maxOf(worst, tilt(trawler)); if (trawler.dormant) trawlerSlept = true }
        assertTrue("the Trawler stayed awake in it", !trawlerSlept)
        assertTrue("the Trawler rides it upright: $worst degrees at worst", worst < 60.0)
        assertTrue("and afloat: ${aboveWater(trawlerWorld, trawler)} m", aboveWater(trawlerWorld, trawler) > -3.0)
    }

    @Test
    fun `hurried, a boat left alone in a storm sea rides it asleep so time can warp`() {
        val probe = wildWorld()
        val (where, t) = stormSea(probe)
        val world = wildWorld()
        world.skipTo(t - 30.0)
        val cutter = launch(world, StockCraft.cutter(catalog), where)
        repeat((20.0 / dt).toInt()) { world.step(dt) }
        assertTrue("awake in it at real time", !cutter.dormant)
        world.hurried = true
        var slept = false
        repeat((30.0 / dt).toInt()) { world.step(dt); if (cutter.dormant) slept = true }
        assertTrue("dropped anchor once hurried", slept)
        assertTrue("and lets time warp: ${world.maxWarp()}", world.maxWarp() > World.PHYSICS_WARP)
        world.hurried = false
        repeat(10) { world.step(dt) }
        assertTrue("woken again at real time", !cutter.dormant)
    }

    @Test
    fun `a swamped skiff sinks to the bottom`() {
        val world = World.default(catalog)
        val skiff = world.spawnOnSurface(StockCraft.skiff(catalog), World.launchSites.first { it.id == "harbour" })
        repeat((2.0 / dt).toInt()) { world.step(dt) }
        val terra = world.attractorFor(skiff)
        val up = terra.toBodyFixed(skiff.body.position, terra.rotationAt(world.time), Vec3()).normalizeInPlace()
        val bed = terra.terrain!!.elevation(up)
        skiff.wake()
        val hull = skiff.defs.indices.first { skiff.defs[it].id == "hull-skiff" }
        skiff.flooded[hull] = com.rm.apogee.core.part.Buoyancy.capacity(skiff.defs[hull], terra.ocean!!.density)
        skiff.recomputeMass()
        repeat((40.0 / dt).toInt()) { world.step(dt) }
        val altitude = terra.altitudeOf(skiff.body.position)
        assertTrue("on the bottom at $bed m: at $altitude m", altitude < bed + 3.0)
    }

    @Test
    fun `moored, a boat sleeps and rides the tide up and down`() {
        val world = World.default(catalog)
        val design = StockCraft.boat(catalog)
        val boat = world.spawnOnSurface(design, World.launchSiteFor(design, catalog))
        var slept = false
        repeat((60.0 / dt).toInt()) { world.step(dt); if (boat.dormant) slept = true }
        assertTrue("went to sleep afloat", slept && boat.afloat)
        val terra = world.attractorFor(boat)
        val first = terra.altitudeOf(boat.body.position)
        val gap = aboveWater(world, boat)
        // An hour, a quarter of a tide.
        repeat((3_000.0 / 0.5).toInt()) { world.step(0.5) }
        val moved = terra.altitudeOf(boat.body.position) - first
        assertTrue("still asleep", boat.dormant)
        assertTrue("rode the tide: moved $moved m", kotlin.math.abs(moved) > 0.3)
        assertTrue("kept its place in the water: ${aboveWater(world, boat)} vs $gap", kotlin.math.abs(aboveWater(world, boat) - gap) < 0.15)
    }
}
