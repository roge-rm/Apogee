package com.rm.apogee.core.weather

import com.rm.apogee.core.craft.CraftDesign
import com.rm.apogee.core.craft.StockCraft
import com.rm.apogee.core.craft.Vessel
import com.rm.apogee.core.craft.VesselId
import com.rm.apogee.core.math.Quat
import com.rm.apogee.core.math.Vec3
import com.rm.apogee.core.orbit.CelestialBody
import com.rm.apogee.core.orbit.SolarSystem
import com.rm.apogee.core.part.StockParts
import com.rm.apogee.core.world.Aloft
import com.rm.apogee.core.world.Forces
import com.rm.apogee.core.world.Rotors
import com.rm.apogee.core.world.World
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Volcanic ash: eruptions hashed and repeatable, as often as the setting says, on land at real
 * volcanoes, a cloud carried downwind that cuts sight range, and air-breathing engines choking in it.
 */
class AshTest {
    private val system = SolarSystem.defaultSystem()
    private val terra = system.body(SolarSystem.HOMEWORLD_ID)
    private val extreme = WeatherConfig(intensity = WeatherIntensity.EXTREME)
    private val catalog = StockParts.catalog

    private fun bits(a: Double) = java.lang.Double.doubleToLongBits(a)

    private fun count(body: CelestialBody, config: WeatherConfig, cycles: Int): Int {
        val v = Weather(body, config).ashModel
        return (0 until cycles).sumOf { v.eruptionsOf(it.toLong()).size }
    }

    /** [d] moved [along] metres along unit tangent [heading] and [across] metres to its side, unit. */
    private fun step(body: CelestialBody, d: Vec3, heading: Vec3, along: Double, across: Double = 0.0): Vec3 {
        val side = Vec3().setTo(d).crossInPlace(heading).normalizeInPlace()
        return Vec3().setTo(d).mulInPlace(body.radius).addScaledInPlace(heading, along).addScaledInPlace(side, across).normalizeInPlace()
    }

    /** Unit point [along] metres along [e]'s track and [across] metres to its side. */
    private fun on(e: LiveEruption, along: Double, across: Double = 0.0): Vec3 =
        e.track.pointAt(along, Vec3()).mulInPlace(terra.radius).addScaledInPlace(e.track.sideAt(along, Vec3()), across).normalizeInPlace()

    private fun air(w: Weather, body: CelestialBody, d: Vec3, altitude: Double, time: Double): AirSample =
        w.sample(Vec3().setTo(d).mulInPlace(body.radius + altitude), time, AirSample())

    /** A hashed Terra eruption on Extreme, part way through giving out ash, and the time to look. */
    private val busy: Pair<Double, LiveEruption> by lazy {
        val w = Weather(terra, extreme)
        var t = 6 * 3_600.0
        while (t < 400 * 3_600.0) {
            val out = ArrayList<LiveEruption>()
            w.eruptions(Vec3.unitY(), t, Double.MAX_VALUE, out)
            val e = out.firstOrNull { it.age > 2.5 * 3_600.0 && it.age < it.emitting - 1_800.0 && it.strength > 0.5 && it.drift.length > 8.0 }
            if (e != null) return@lazy t to e
            t += 1_800.0
        }
        error("no eruption in 400 h")
    }

    @Test
    fun `none on Calm or Normal, nor where there's no air or no volcano`() {
        for (intensity in listOf(WeatherIntensity.CALM, WeatherIntensity.NORMAL)) {
            for (id in listOf(SolarSystem.HOMEWORLD_ID, "caligo", "rubra")) {
                assertTrue("$intensity $id", !Weather(system.body(id), WeatherConfig(intensity = intensity)).ashPossible)
            }
        }
        for (id in listOf(SolarSystem.HOMEWORLD_ID, "caligo", "rubra")) assertTrue(id, Weather(system.body(id), extreme).ashPossible)
        for (id in listOf("aurantia", "ultima", "magna")) assertTrue(id, !Weather(system.body(id), extreme).ashPossible)
    }

    @Test
    fun `hashed and repeatable however they're asked`() {
        val a = Weather(terra, extreme).ashModel
        val b = Weather(terra, extreme).ashModel
        val forward = (0 until 60).flatMap { a.eruptionsOf(it.toLong()) }
        val backward = (59 downTo 0).flatMap { b.eruptionsOf(it.toLong()).reversed() }.reversed()
        assertTrue("some: ${forward.size}", forward.size > 20)
        assertEquals(forward.map { it.id }, backward.map { it.id })
        for ((x, y) in forward.zip(backward)) {
            assertEquals(bits(x.start), bits(y.start)); assertEquals(bits(x.strength), bits(y.strength))
            assertEquals(bits(x.drift.x), bits(y.drift.x)); assertEquals(bits(x.drift.z), bits(y.drift.z))
            assertEquals(bits(x.top), bits(y.top))
        }
        // Another seed, other eruptions.
        val c = Weather(terra, WeatherConfig(seed = 7, intensity = WeatherIntensity.EXTREME)).ashModel
        assertTrue((0 until 60).flatMap { c.eruptionsOf(it.toLong()) }.map { it.start } != forward.map { it.start })
    }

    @Test
    fun `Extreme often, Wild about a tenth, hot Caligo more, Rubra less`() {
        val cycles = 2_000
        val ex = count(terra, extreme, cycles)
        val wild = count(terra, WeatherConfig(intensity = WeatherIntensity.WILD), cycles)
        val caligo = count(system.body("caligo"), extreme, cycles)
        val rubra = count(system.body("rubra"), extreme, cycles)
        println("eruptions per ${Volcanoes.CYCLE / 3_600.0} h cycle: Terra Extreme ${ex.toDouble() / cycles}, Wild ${wild.toDouble() / cycles}, Caligo ${caligo.toDouble() / cycles}, Rubra ${rubra.toDouble() / cycles}")
        assertTrue("Terra Extreme $ex", ex.toDouble() / cycles in 0.8..1.1)
        assertTrue("Wild $wild against $ex", wild.toDouble() / ex in 0.06..0.14)
        assertTrue("Caligo $caligo", caligo > ex)
        assertTrue("Rubra $rubra", rubra in 1 until ex / 2)
    }

    @Test
    fun `on Terra at its volcanoes, on land, and fewer near the Cape`() {
        val v = Weather(terra, extreme).ashModel
        val all = (0 until 3_000).flatMap { v.eruptionsOf(it.toLong()) }
        val vents = all.groupBy { Math.round(Math.toDegrees(SolarSystem.latitudeOf(it.vent)) * 10) to Math.round(Math.toDegrees(SolarSystem.longitudeOf(it.vent)) * 10) }
        for ((k, list) in vents) {
            val e = list.first()
            val h = terra.terrain!!.elevation(e.vent)
            println("vent ${k.first / 10.0}, ${k.second / 10.0}: ${list.size} eruptions, ground ${h.toInt()} m, top ${e.top.toInt()} m")
            assertTrue("vent at $k under the sea, $h m", h > 0.0)
        }
        assertTrue("vents ${vents.size}", vents.size >= 20)
        // Mount Cameroon is about as active as Shishaldin, but by the Cape.
        val cape = World.launchSites.first { it.id == "cape" }
        val pad = SolarSystem.surfaceDirection(cape.latitude, cape.longitude)
        val near = all.filter { it.vent.distanceTo(pad) * terra.radius < 100_000.0 }
        val far = all.filter { kotlin.math.abs(Math.toDegrees(SolarSystem.latitudeOf(it.vent)) - 54.76) < 0.1 }
        println("by the Cape ${near.size} (strength ${near.map { it.strength }.average()}), Shishaldin ${far.size} (${far.map { it.strength }.average()})")
        assertTrue(near.size < 0.8 * 0.8 * far.size)
    }

    @Test
    fun `the cloud drifts downwind, cuts sight and falls to the ground`() {
        val (t, e) = busy
        val w = Weather(terra, extreme)
        val height = e.top - 0.25 * Volcanoes.DEPTH * (e.top - e.ventHeight)
        val down = air(w, terra, on(e, 60_000.0), height, t)
        val up = air(w, terra, on(e, -60_000.0), height, t)
        val aside = air(w, terra, on(e, 60_000.0, 40_000.0), height, t)
        println("eruption ${e.id} at ${t / 3_600.0} h: drift ${e.drift.length} m/s, top ${e.top} m; ash downwind ${down.ash}, upwind ${up.ash}, aside ${aside.ash}; sight ${down.visibility} m")
        assertTrue("downwind ${down.ash}", down.ash > 0.15)
        assertEquals(0.0, up.ash, 0.0)
        assertEquals(0.0, aside.ash, 0.0)
        assertTrue("sight ${down.visibility}", down.visibility <= Volcanoes.ASH_SIGHT / down.ash + 1e-9 && down.visibility < 1_000.0)
        assertTrue("ash top ${down.ashTop}", down.ashTop > height)
        // Under ash an hour old, some has fallen to the ground; well above the cloud, none.
        val under = on(e, e.drift.length * 3_600.0)
        val ground = maxOf(terra.terrain!!.elevation(under), 0.0)
        val low = air(w, terra, under, ground + 50.0, t)
        val high = air(w, terra, under, e.top + 3_000.0, t)
        println("under the cloud ${low.ash}, sight ${low.visibility} m; over it ${high.ash}")
        assertTrue("fallen ${low.ash}", low.ash > 0.02)
        assertEquals(0.0, high.ash, 0.0)
        // In the column over the vent.
        val column = air(w, terra, e.vent, e.ventHeight + 2_000.0, t)
        assertTrue("column ${column.ash}", column.ash > 0.4)
        // Carried further the longer it goes: ash given out an hour ago is about drift x 1 h off.
        val further = air(w, terra, on(e, e.drift.length * 3_600.0), height, t)
        assertTrue("an hour downwind ${further.ash}", further.ash > 0.1)
    }

    @Test
    fun `no steps through the cloud or as it goes by`() {
        val (t, e) = busy
        val w = Weather(terra, extreme)
        val height = e.top - 0.4 * Volcanoes.DEPTH * (e.top - e.ventHeight)
        var worst = 0.0
        var last = Double.NaN
        // Across the cloud 40 km downwind, every 50 m.
        for (k in -800..800) {
            val a = air(w, terra, on(e, 40_000.0, k * 50.0), height, t).ash
            if (!last.isNaN()) worst = maxOf(worst, kotlin.math.abs(a - last))
            last = a
        }
        // Up through it, every 20 m.
        last = Double.NaN
        val d = on(e, 40_000.0)
        var h = 0.0
        while (h < e.top + 3_000.0) {
            val a = air(w, terra, d, h, t).ash
            if (!last.isNaN()) worst = maxOf(worst, kotlin.math.abs(a - last))
            last = a; h += 20.0
        }
        // Over time at one place, every 10 s.
        last = Double.NaN
        for (k in 0..2_000) {
            val a = air(w, terra, d, height, t + k * 10.0).ash
            if (!last.isNaN()) worst = maxOf(worst, kotlin.math.abs(a - last))
            last = a
        }
        println("largest step $worst")
        assertTrue("step $worst", worst < 0.02)
    }

    @Test
    fun `a forced eruption drifts where it's told`() {
        val w = Weather(terra, extreme)
        val vent = SolarSystem.surfaceDirection(Math.toRadians(37.75), Math.toRadians(14.99))
        val east = Vec3(); val north = Vec3(); frame(vent, east, north)
        w.forceEruption(vent, 0.0, 1.0, Vec3().setTo(north).mulInPlace(20.0))
        val ventHeight = terra.terrain!!.elevation(vent)
        val height = ventHeight + Volcanoes.TOP_MOST * 0.9
        val t = 2 * 3_600.0
        val n = air(w, terra, step(terra, vent, north, 80_000.0), height, t).ash
        val s = air(w, terra, step(terra, vent, north, -80_000.0), height, t).ash
        val e = air(w, terra, step(terra, vent, east, 80_000.0), height, t).ash
        println("forced: north ${n}, south $s, east $e")
        assertTrue(n > 0.3 && s == 0.0 && e == 0.0)
        assertTrue(w.ashLive(t))
        val listed = ArrayList<LiveEruption>(); w.eruptions(vent, t, 10_000.0, listed)
        assertEquals(1, listed.size)
    }

    @Test
    fun `the track bends with a changing wind and stays smooth`() {
        val r = terra.radius
        val vent = SolarSystem.surfaceDirection(Math.toRadians(10.0), Math.toRadians(20.0))
        val east = Vec3(); val north = Vec3(); frame(vent, east, north)
        val startSide = Vec3().setTo(vent).crossInPlace(east).normalizeInPlace()
        // East for the first 100 km, then north.
        val bent = AshTrack.trace(vent, east, 10_000.0, r) { p, s, out ->
            val e = Vec3(); val n = Vec3(); frame(p, e, n)
            out.setTo(if (s < 100_000.0) e else n)
        }
        val straight = AshTrack.trace(vent, east, 10_000.0, r) { _, _, _ -> null }
        val bentOff = kotlin.math.abs(bent.pointAt(bent.length, Vec3()) dot startSide) * r
        val straightOff = kotlin.math.abs(straight.pointAt(straight.length, Vec3()) dot startSide) * r
        println("track end off its first great circle: bent ${bentOff / 1e3} km, straight $straightOff m")
        assertTrue("bent $bentOff", bentOff > 80_000.0)
        assertTrue("straight $straightOff", straightOff < 1.0)
        // Points beside it are found where they were put.
        val got = DoubleArray(2)
        for (k in 1..46) for (aside in listOf(-6_000.0, 0.0, 4_000.0)) {
            val s = k * 5_000.0
            val p = bent.pointAt(s, Vec3()).mulInPlace(r).addScaledInPlace(bent.sideAt(s, Vec3()), aside).normalizeInPlace()
            bent.project(p, got)
            assertEquals("along at $s, $aside", s, got[0], 1_000.0)
            assertEquals("off at $s, $aside", kotlin.math.abs(aside), got[1], 100.0)
        }
        // No jumps going by it on either side of the bend, every 100 m.
        for (aside in listOf(-15_000.0, 15_000.0)) {
            var last: DoubleArray? = null
            var worstAlong = 0.0; var worstOff = 0.0
            for (k in 0..2_000) {
                val s = k * 100.0
                val p = bent.pointAt(s, Vec3()).mulInPlace(r).addScaledInPlace(bent.sideAt(s, Vec3()), aside).normalizeInPlace()
                val now = bent.project(p, DoubleArray(2))
                last?.let { worstAlong = maxOf(worstAlong, kotlin.math.abs(now[0] - it[0])); worstOff = maxOf(worstOff, kotlin.math.abs(now[1] - it[1])) }
                last = now
            }
            println("by the bend at $aside m: largest step along $worstAlong m, off $worstOff m")
            assertTrue("along $worstAlong", worstAlong < 400.0)
            assertTrue("off $worstOff", worstOff < 100.0)
        }
    }

    @Test
    fun `nothing on the far side of the world from the track`() {
        val r = terra.radius
        val vent = SolarSystem.surfaceDirection(Math.toRadians(10.0), Math.toRadians(20.0))
        val east = Vec3(); val north = Vec3(); frame(vent, east, north)
        val bent = AshTrack.trace(vent, east, 10_000.0, r) { p, s, out ->
            val e = Vec3(); val n = Vec3(); frame(p, e, n)
            out.setTo(if (s < 100_000.0) e else n)
        }
        val straight = AshTrack.trace(vent, east, 10_000.0, r) { _, _, _ -> null }
        val got = DoubleArray(2)
        for (track in listOf(bent, straight, busy.second.track)) for (k in 0..track.segments) {
            val back = track.pointAt(k * track.step, Vec3()).mulInPlace(-1.0)
            track.project(back, got)
            assertTrue("antipode of point $k: off ${got[1]}", got[1] > r)
        }
        // A hashed cloud and a forced straight one: no ash at the antipodes of their track points.
        val (t, e) = busy
        val w = Weather(terra, extreme)
        val forced = Weather(terra, extreme)
        forced.forceEruption(vent, 0.0, 1.0, Vec3().setTo(east).mulInPlace(20.0))
        val listed = ArrayList<LiveEruption>(); forced.eruptions(vent, 2 * 3_600.0, 10_000.0, listed)
        for ((weather, live, time) in listOf(Triple(w, e, t), Triple(forced, listed.single(), 2 * 3_600.0))) {
            for (k in 0..live.track.segments) {
                val back = live.track.pointAt(k * live.track.step, Vec3()).mulInPlace(-1.0)
                for (height in listOf(2_000.0, 0.5 * (live.top + live.ventHeight), live.top - 200.0))
                    assertEquals("ash at the antipode of point $k, $height m", 0.0, air(weather, terra, back, height, time).ash, 0.0)
            }
        }
    }

    @Test
    fun `a hashed cloud follows its bending track`() {
        val w = Weather(terra, extreme)
        var most = 0.0
        var found: Pair<Double, LiveEruption>? = null
        var t = 6 * 3_600.0
        while (t < 600 * 3_600.0) {
            val out = ArrayList<LiveEruption>()
            w.eruptions(Vec3.unitY(), t, Double.MAX_VALUE, out)
            for (e in out) {
                if (e.strength < 0.5 || e.age < 2.5 * 3_600.0 || e.age > e.emitting - 1_800.0 || e.length < 80_000.0) continue
                // Off the great circle it set out on, where the ash given out an hour and a half ago is.
                val side = Vec3().setTo(e.vent).crossInPlace(e.drift).normalizeInPlace()
                val off = kotlin.math.abs(e.track.pointAt(e.drift.length * 5_400.0, Vec3()) dot side) * terra.radius
                if (off > most) { most = off; found = t to e }
            }
            t += 3_600.0
        }
        val (at, e) = found!!
        val height = e.top - 0.25 * Volcanoes.DEPTH * (e.top - e.ventHeight)
        val far = e.drift.length * 5_400.0
        val heading = e.drift.copy().normalizeInPlace()
        val onTrack = air(w, terra, on(e, far), height, at).ash
        val straight = air(w, terra, step(terra, e.vent, heading, far), height, at).ash
        println("most bent: eruption ${e.id}, cloud ${e.length / 1e3} km, ${most / 1e3} km off a great circle at ${far / 1e3} km; ash at ${far / 1e3} km on its track $onTrack, straight on $straight")
        assertTrue("bends $most", most > 10_000.0)
        assertTrue("on its track $onTrack", onTrack > 0.05)
        assertEquals("straight on", 0.0, straight, 0.0)
    }

    private fun lit(design: CraftDesign): Vessel {
        val vessel = Vessel(id = VesselId(1), design = design, defs = design.parts.map { catalog.require(it.partId) }, referenceBodyId = "terra")
        vessel.body.position.setTo(0.0, terra.radius + 1_000.0, 0.0)
        vessel.body.orientation.setIdentity()
        while (vessel.activeEngines().isEmpty() && vessel.currentStage < design.stages.size) vessel.activateNextStage()
        vessel.recomputeMass(shiftBodyPosition = false)
        vessel.fitPose()
        for (i in vessel.activeEngines()) if (i < vessel.spool.size) vessel.spool[i] = 1.0
        vessel.control.throttle = 1.0
        return vessel
    }

    private fun thrust(design: CraftDesign, ash: Double): Double {
        val vessel = lit(design)
        vessel.air.ash = ash
        vessel.body.clearAccumulators()
        Forces().applyThrust(vessel, terra, 1.0 / 60.0)
        return vessel.body.force.length
    }

    @Test
    fun `jets flame out in thick ash, props lose some, rockets don't`() {
        val jet = StockCraft.sparrow(catalog)
        val prop = StockCraft.petrel(catalog)
        val rocket = StockCraft.starterRocket(catalog)
        val clear = listOf(thrust(jet, 0.0), thrust(prop, 0.0), thrust(rocket, 0.0))
        assertTrue("all push: $clear", clear.all { it > 100.0 })
        val ashes = listOf(0.05, 0.2, 0.35, 0.5, 0.6, 0.9)
        for (a in ashes) {
            val left = listOf(thrust(jet, a) / clear[0], thrust(prop, a) / clear[1], thrust(rocket, a) / clear[2])
            println("ash $a: jet ${left[0]}, prop ${left[1]}, rocket ${left[2]}")
            assertEquals("rocket at $a", 1.0, left[2], 1e-12)
        }
        assertEquals(1.0, thrust(jet, 0.05) / clear[0], 1e-12)
        assertTrue(thrust(jet, 0.35) / clear[0] in 0.3..0.7)
        assertEquals(0.0, thrust(jet, 0.6), 0.0)
        assertTrue(thrust(prop, 0.35) / clear[1] in 0.6..0.9)
        assertEquals(1.0 - Volcanoes.PROP_LOSS, thrust(prop, 0.9) / clear[1], 1e-9)
        // Smooth: no step as ash rises.
        var worst = 0.0
        for (k in 1..1_000) worst = maxOf(worst, kotlin.math.abs(Volcanoes.jetLeft(k / 1_000.0) - Volcanoes.jetLeft((k - 1) / 1_000.0)))
        assertTrue("jet step $worst", worst < 0.005)
    }

    @Test
    fun `a helicopter's rotor loses lift in ash`() {
        fun lift(ash: Double): Double {
            val world = World.default(catalog)
            val heli = Aloft.spawn(world, StockCraft.hummingbird(catalog), 300.0)
            heli.control.throttle = 0.7
            Aloft.spinUp(heli, 0.7)
            heli.air.clear()
            heli.air.ash = ash
            heli.body.clearAccumulators()
            Rotors().apply(heli, world.attractorFor(heli), Quat(0.0, 0.0, 0.0, 1.0), world.time, 1.0 / 60.0)
            return heli.body.force.length
        }
        val clear = lift(0.0)
        val thick = lift(0.9)
        println("rotor lift clear $clear N, in ash $thick N")
        assertTrue(clear > 1_000.0)
        assertEquals(1.0 - Volcanoes.PROP_LOSS, thick / clear, 0.02)
    }
}
