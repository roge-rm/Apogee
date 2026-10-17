package com.rm.apogee.core.weather

import com.rm.apogee.core.craft.CraftDesign
import com.rm.apogee.core.craft.PlacedPart
import com.rm.apogee.core.math.Vec3
import com.rm.apogee.core.orbit.SolarSystem
import com.rm.apogee.core.part.StockParts
import com.rm.apogee.core.terrain.Noise
import com.rm.apogee.core.world.LaunchSite
import com.rm.apogee.core.world.World
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.asin
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin

/**
 * Hail: only under strong storms on a world whose storms are water, none in hurricanes, the air's
 * the same as asked alone; a storm passing over mostly nicks a panel and rarely takes one; parked
 * through one, warped or stepped, a panel dents and a tank doesn't; only light parts open to the
 * sky dent.
 */
class HailTest {
    private val system = SolarSystem.defaultSystem()
    private val terra = system.body(SolarSystem.HOMEWORLD_ID)
    private val catalog = StockParts.catalog

    /** A storm that hails and where its main tower's shaft is at its middle of life: unit, body-fixed. */
    private class Hailer(val storm: Storms.Storm, val point: Vec3, val side: Vec3)

    /** Storms that hail on Terra at [intensity], each once, from 3000 places at random times. */
    private fun hailers(w: Weather): List<Hailer> {
        val storms = w.stormModel
        val seen = HashSet<Triple<Int, Int, Long>>()
        val out = ArrayList<Hailer>()
        val list = ArrayList<Storms.Storm>()
        for (i in 0 until 3_000) {
            val lat = (Noise.hash(3, i, 0, 0) - 0.5) * 1.6
            val lon = Noise.hash(3, i, 1, 0) * 2 * Math.PI
            val d = Vec3(cos(lat) * cos(lon), sin(lat), cos(lat) * sin(lon))
            val e = Vec3(); val n = Vec3(); frame(d, e, n)
            val t = 6 * 3_600.0 + Noise.hash(3, i, 2, 0) * 100 * 3_600.0
            list.clear(); storms.around(d, e, n, 1, t, list)
            for (s in list) {
                if (s.hail <= 0.0 || !seen.add(Triple(s.cx, s.cy, s.cycle))) continue
                val mid = s.start + 0.5 * Storms.CYCLE
                val c = Vec3(); val tr = Vec3(); val rt = Vec3()
                storms.frameAt(s, mid, c, tr, rt)
                val m = s.mainCell
                val r = s.cellRadius[m]
                val along = s.cellAlong[m] + s.shaftAlong * r
                out += Hailer(s, storms.place(c, tr, rt, along, s.cellAcross[m], 1.0, Vec3()), storms.place(c, tr, rt, along, s.cellAcross[m] + r, 1.0, Vec3()))
            }
        }
        return out
    }

    private fun ground(d: Vec3) = maxOf(terra.terrain!!.elevation(d), 0.0)

    /** What a part as easily dented as a panel would lose at [d], 2 m up, over [s]'s life. */
    private fun panelLoss(w: Weather, s: Storms.Storm, d: Vec3): Double {
        var sum = 0.0
        var t = s.start
        while (t < s.start + Storms.CYCLE) {
            val h = w.hailAt(d, ground(d) + 2.0, t)
            if (h >= 0.05) sum += h * h * 10.0
            t += 10.0
        }
        return sum * World.HAIL_RATE
    }

    @Test
    fun `hail only under strong storms where storms are water`() {
        val w = Weather(terra, WeatherConfig(intensity = WeatherIntensity.EXTREME))
        assertTrue(w.hailPossible)
        val all = hailers(w)
        assertTrue("storms that hail: ${all.size}", all.size > 500)
        assertTrue("only strong ones", all.all { it.storm.strength > Storms.HAIL_FROM })
        val supercells = all.count { it.storm.kind == StormKind.SUPERCELL }
        println("hailing storms ${all.size}, supercells $supercells")
        for (id in listOf("rubra", "aurantia", "caligo")) {
            val other = Weather(system.body(id), WeatherConfig(intensity = WeatherIntensity.EXTREME))
            assertFalse("none on $id", other.hailPossible)
            assertEquals(0.0, other.hailAt(Vec3.unitX(), 100.0, 50_000.0), 0.0)
        }
        assertFalse("none on Calm", Weather(terra, WeatherConfig(intensity = WeatherIntensity.CALM)).hailPossible)
        // None in a hurricane's own rain.
        val hurricanes = ArrayList<LiveHurricane>()
        var t = 6 * 3_600.0
        while (hurricanes.isEmpty()) { w.hurricanes(Vec3.unitY(), t, Double.MAX_VALUE, hurricanes); t += 3_600.0 }
        val h = hurricanes.first()
        val air = AirSample()
        for (k in 0 until 8) {
            val a = k * Math.PI / 4
            val e = Vec3(h.centre.z, 0.0, -h.centre.x).normalizeInPlace(); val n = Vec3().setTo(h.centre).crossInPlace(e)
            val d = Vec3().setTo(h.centre).addScaledInPlace(e, cos(a) * h.maxWindRadius / terra.radius).addScaledInPlace(n, sin(a) * h.maxWindRadius / terra.radius).normalizeInPlace()
            w.sample(Vec3().setTo(d).mulInPlace(terra.radius + ground(d) + 30.0), t - 3_600.0, air)
            assertEquals("no hail in the eyewall", 0.0, air.hail, 0.0)
        }
    }

    @Test
    fun `the air's hail is the hail asked for alone`() {
        val w = Weather(terra, WeatherConfig(intensity = WeatherIntensity.EXTREME))
        val air = AirSample()
        var hailing = 0
        for (x in hailers(w).take(200)) {
            val t = x.storm.start + 0.5 * Storms.CYCLE
            for (d in listOf(x.point, x.side)) {
                val alt = ground(d) + 30.0
                w.sample(Vec3().setTo(d).mulInPlace(terra.radius + alt), t, air)
                val alone = Weather(terra, WeatherConfig(intensity = WeatherIntensity.EXTREME)).hailAt(d, alt, t)
                assertEquals("hail", alone, air.hail, 1e-9)
                if (air.hail > 0.05) { hailing++; assertTrue("raining too", air.precipitation > 0.6 * air.hail) }
            }
        }
        assertTrue("some hailing: $hailing", hailing > 50)
    }

    @Test
    fun `a storm passing over mostly nicks a panel and rarely takes one`() {
        val w = Weather(terra, WeatherConfig(intensity = WeatherIntensity.EXTREME))
        val all = hailers(w)
        val centre = all.map { panelLoss(w, it.storm, it.point) }.sorted()
        val side = all.map { panelLoss(w, it.storm, it.side) }.sorted()
        fun q(l: List<Double>, f: Double) = l[((l.size - 1) * f).toInt()]
        val lost = centre.count { it >= 1.0 }
        println("panel loss under the shaft: median ${q(centre, 0.5)}, 90% ${q(centre, 0.9)}, most ${centre.last()}, lost $lost of ${centre.size}; a tower's radius off: 90% ${q(side, 0.9)}, most ${side.last()}")
        assertTrue("mostly a nick", q(centre, 0.5) < 0.1)
        assertTrue("now and then a dent", q(centre, 0.9) < 0.5)
        assertTrue("a panel lost under some", lost > 0)
        assertTrue("but rarely: $lost", lost < 0.02 * centre.size)
        assertTrue("a tower's radius off, a dent at most: ${side.last()}", side.last() < 0.5)
        // A tank, a wing and a base's panel don't care, or barely.
        val world = World.default(catalog)
        assertEquals(1.0, world.hailFragility(catalog.require("panel-glint")), 0.0)
        assertEquals(0.0, world.hailFragility(catalog.require("tank-cask2")), 0.0)
        assertEquals(0.0, world.hailFragility(catalog.require("base-solar")), 0.0)
        assertEquals(0.0, world.hailFragility(catalog.require("probe-mote")), 0.0)
        assertTrue(world.hailFragility(catalog.require("wing-plank")) in 0.2..0.3)
    }

    private fun craft() = CraftDesign(
        "Hailed",
        listOf(
            PlacedPart("tank-cask2", Vec3.zero()),
            PlacedPart("probe-mote", Vec3(0.0, 1.2, 0.0), parentIndex = 0),
            PlacedPart("panel-glint", Vec3(0.66, 0.3, 0.0), parentIndex = 0),
        ),
        emptyList(),
        catalog.contentHash,
    )

    @Test
    fun `parked through a storm its panel dents and its tank doesn't, warped or stepped`() {
        val config = WeatherConfig(intensity = WeatherIntensity.NORMAL)
        val w = Weather(terra, config)
        val pick = hailers(w).firstOrNull {
            val g = terra.terrain!!.elevation(it.point)
            g > 5.0 && panelLoss(w, it.storm, it.point) in 0.3..0.9
        } ?: throw AssertionError("no storm over land that dents")
        val expected = panelLoss(w, pick.storm, pick.point)
        val end = pick.storm.start + Storms.CYCLE
        fun parked(): Pair<World, com.rm.apogee.core.craft.Vessel> {
            val world = World.default(catalog).also { it.weatherConfig = config }
            world.syncClock(pick.storm.start)
            val d = pick.point
            val v = world.spawnOnSurface(craft(), LaunchSite("hail", "", "terra", asin(d.y), atan2(d.z, d.x)))
            repeat(30 * 60) { world.step(1.0 / 60.0) }
            assertTrue("parked", v.dormant)
            return world to v
        }
        val (warped, a) = parked()
        warped.advanceOnRails(end - warped.time)
        assertEquals("warped through", end, warped.time, 1.0)
        repeat(60) { warped.step(1.0 / 60.0) }
        val (stepped, b) = parked()
        while (stepped.time < end) {
            stepped.advanceOnRails(50.0)
            repeat(60) { stepped.step(1.0 / 60.0) }
        }
        println("panel loss expected $expected, warped ${1.0 - a.health[2]}, stepped ${1.0 - b.health[2]}")
        assertEquals("warped panel", 1.0 - expected, a.health[2], 0.02)
        assertEquals("stepped like warped", a.health[2], b.health[2], 1e-6)
        assertEquals("tank", 1.0, a.health[0], 0.0)
        assertEquals("tank", 1.0, b.health[0], 0.0)
        assertTrue("whole", a.defs.size == 3 && b.defs.size == 3)
    }

    /**
     * Parked through the same storm in a warp bubble, on the bubble's own clock while the world
     * barely moves, the panel takes what it would warped on the world's.
     */
    @Test
    fun `in a warp bubble a craft takes the hail it would on the world's clock`() {
        val config = WeatherConfig(intensity = WeatherIntensity.NORMAL)
        val w = Weather(terra, config)
        val pick = hailers(w).firstOrNull {
            val g = terra.terrain!!.elevation(it.point)
            g > 5.0 && panelLoss(w, it.storm, it.point) in 0.3..0.9
        } ?: throw AssertionError("no storm over land that dents")
        val end = pick.storm.start + Storms.CYCLE
        fun parked(): Pair<World, com.rm.apogee.core.craft.Vessel> {
            val world = World.default(catalog).also { it.weatherConfig = config }
            world.syncClock(pick.storm.start)
            val d = pick.point
            val v = world.spawnOnSurface(craft(), LaunchSite("hail", "", "terra", asin(d.y), atan2(d.z, d.x)))
            repeat(30 * 60) { world.step(1.0 / 60.0) }
            assertTrue("parked", v.dormant)
            return world to v
        }
        val (warped, a) = parked()
        warped.advanceOnRails(end - warped.time)
        repeat(60) { warped.step(1.0 / 60.0) }
        val (world, b) = parked()
        val worldStart = world.time
        val bubble = world.openBubble(listOf(b), "hailed")
        bubble.rate = 500.0
        bubble.until = end
        while (bubble.time < end - 1e-6) { world.step(1.0 / 60.0); world.advanceBubble(bubble, 1.0 / 60.0) }
        repeat(60) { world.step(1.0 / 60.0); world.advanceBubble(bubble, 1.0 / 60.0) }
        println("panel loss warped ${1.0 - a.health[2]}, in a bubble ${1.0 - b.health[2]}, world on ${world.time - worldStart} s")
        assertTrue("the world barely moved", world.time - worldStart < 0.05 * (end - worldStart))
        assertTrue("dented", b.health[2] < 0.95)
        assertEquals("in a bubble like warped", a.health[2], b.health[2], 1e-6)
        assertEquals("tank", 1.0, b.health[0], 0.0)
    }

    @Test
    fun `only light parts open to the sky dent`() {
        val world = World.default(catalog)
        val design = CraftDesign(
            "Sheltered",
            listOf(
                PlacedPart("tank-cask2", Vec3.zero()),
                PlacedPart("plate-deck", Vec3(0.0, 1.075, 0.0), parentIndex = 0),
                PlacedPart("panel-glint", Vec3(0.66, 0.3, 0.0), parentIndex = 0),
                PlacedPart("panel-glint", Vec3(1.2, 1.65, 0.0), parentIndex = 1),
            ),
            emptyList(),
            catalog.contentHash,
        )
        val v = world.spawnOnSurface(design, World.launchSites.first { it.id == "cape" })
        assertEquals("the panel on the deck, not the one under it", listOf(3), world.hailExposed(v))
    }
}
