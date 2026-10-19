package com.rm.apogee.core.weather

import com.rm.apogee.core.math.Quat
import com.rm.apogee.core.math.Vec3
import com.rm.apogee.core.orbit.CelestialBody
import com.rm.apogee.core.orbit.SolarSystem
import com.rm.apogee.core.part.StockParts
import com.rm.apogee.core.terrain.Noise
import com.rm.apogee.core.world.World
import com.rm.apogee.core.world.WorldStore
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File
import kotlin.math.cos
import kotlin.math.sin

// With PERF_SCENES=1, run scenes, FireScenesScratchTest.generate, then check through :core:jvmTest.
// Saves and check.txt go in build/fire-perf/e6/scenes.
/**
 * Finds an E6 event on Extreme near a craft spot (Terra, and a moonquake on Luna) and writes
 * SCENE lines for make_scenes.py: name, time the event is at the craft, body-fixed up, ground
 * height, the body's rotation then and at the seed scene's time, its spin, its radius.
 */
class PerfScenesScratchTest {
    private val system = SolarSystem.defaultSystem()
    private val terra = system.body(SolarSystem.HOMEWORLD_ID)
    private val luna = system.body("luna")
    private val extreme = WeatherConfig(intensity = WeatherIntensity.EXTREME)
    private val out = File(System.getProperty("user.dir")).parentFile.resolve("build/fire-perf/e6/scenes")

    /** The perf seed scene's universe time, which make_scenes.py turns its craft back from. */
    private val seedTime = 16029.999999999563

    private fun guard() = assumeTrue(System.getenv("PERF_SCENES") != null)

    private fun elev(body: CelestialBody, d: Vec3) = body.terrain!!.elevation(d)

    private fun q(q: Quat) = "${q.x} ${q.y} ${q.z} ${q.w}"

    private fun line(name: String, body: CelestialBody, t: Double, n: Vec3, note: String): String {
        val r = body.rotationAt(t); val r0 = terra.rotationAt(seedTime); val w = body.angularVelocity()
        return "SCENE $name $t ${n.x} ${n.y} ${n.z} ${elev(body, n)} ${q(r)} ${q(r0)} ${w.x} ${w.y} ${w.z} ${body.radius} # $note"
    }

    /** Unit [d] moved [metres] along unit tangent [dir]. */
    private fun move(body: CelestialBody, d: Vec3, dir: Vec3, metres: Double) =
        Vec3().setTo(d).mulInPlace(body.radius).addScaledInPlace(dir, metres).normalizeInPlace()

    private fun arc(body: CelestialBody, a: Vec3, b: Vec3) =
        2.0 * kotlin.math.asin(minOf(1.0, a.distanceTo(b) / 2.0)) * body.radius

    private fun ring(d: Vec3, k: Int): Vec3 {
        val e = Vec3(); val n = Vec3(); frame(d, e, n)
        val a = k * Math.PI / 4
        return Vec3().setTo(e).mulInPlace(cos(a)).addScaledInPlace(n, sin(a))
    }

    private fun air(w: Weather, body: CelestialBody, n: Vec3, t: Double, agl: Double = 3.0): AirSample =
        AirSample().also { w.sample(Vec3().setTo(n).mulInPlace(body.radius + maxOf(elev(body, n), 0.0) + agl), t, it) }

    @Test
    fun scenes() {
        guard()
        out.mkdirs()
        val lines = ArrayList<String>()
        val w = Weather(terra, extreme)

        // Earthquake: an M8+ main shock on land, the craft 12 km off on land; t = shaking arrives.
        run {
            val q = w.quakeModel
            val shock = (8L until 400L).flatMap { q.mainShocks(it) }
                .filter { !it.underSea && it.magnitude >= 8.0 && elev(terra, it.epicentre) > 20.0 }
                .first { s -> (0 until 8).any { elev(terra, move(terra, s.epicentre, ring(s.epicentre, it), 12_000.0)) > 20.0 } }
            val k = (0 until 8).first { elev(terra, move(terra, shock.epicentre, ring(shock.epicentre, it), 12_000.0)) > 20.0 }
            val n = move(terra, shock.epicentre, ring(shock.epicentre, k), 12_000.0)
            val list = ArrayList<LiveQuake>(); q.near(n, shock.start + 0.5, 0.0, list)
            val here = list.first { it.id == shock.id }
            lines += line("quake", terra, here.arrivesHere, n,
                "M ${shock.magnitude} depth ${shock.depth} start ${shock.start} peakHere ${here.peakHere} duration ${shock.duration} dist 12000")
        }

        // Haboob: the craft 90 s ahead of the front's middle; t = the front reaches it.
        run {
            var t = 6 * 3_600.0
            var made = false
            while (!made && t < 200 * 3_600.0) {
                val list = ArrayList<LiveDust>(); w.dustStorms(Vec3.unitY(), t, Double.MAX_VALUE, list)
                for (d in list) {
                    if (d.kind != DustKind.HABOOB || d.strength < 0.6 || d.age < 0.2 * d.life || d.age > 0.5 * d.life) continue
                    val n = move(terra, d.centre, d.heading, d.speed * 90.0)
                    if (elev(terra, n) < 5.0) continue
                    val dust = air(w, terra, n, t + 150.0).dust
                    if (dust < 0.5) continue
                    lines += line("haboob", terra, t + 90.0, n,
                        "strength ${d.strength} speed ${d.speed} halfWidth ${d.halfWidth} height ${d.height} age ${d.age} life ${d.life} dust+60 $dust")
                    made = true; break
                }
                t += 900.0
            }
        }

        // Blizzard: the craft at its middle; t = then.
        run {
            var t = 6 * 3_600.0
            var made = false
            while (!made && t < 400 * 3_600.0) {
                val list = ArrayList<LiveBlizzard>(); w.blizzards(Vec3.unitY(), t, Double.MAX_VALUE, list)
                for (b in list) {
                    if (b.strength < 0.6 || b.age < 0.3 * b.life || b.age > 0.6 * b.life) continue
                    val n = b.centre
                    if (elev(terra, n) < 5.0) continue
                    val a = air(w, terra, n, t)
                    if (a.snow < 0.5) continue
                    lines += line("blizzard", terra, t, n,
                        "strength ${b.strength} halfLength ${b.halfLength} speed ${b.speed} age ${b.age} life ${b.life} snow ${a.snow} sight ${a.visibility}")
                    made = true; break
                }
                t += 1_800.0
            }
        }

        // Hail: the craft under a hard-hailing shaft over land with lightning about; t = then.
        run {
            var best: Pair<Double, String>? = null
            for (i in 0 until 4_000) {
                val lat = (Noise.hash(7, i, 0, 0) - 0.5) * 1.4
                val lon = Noise.hash(7, i, 1, 0) * 2 * Math.PI
                val d = Vec3(cos(lat) * cos(lon), sin(lat), cos(lat) * sin(lon))
                val t = 6 * 3_600.0 + Noise.hash(7, i, 2, 0) * 100 * 3_600.0
                val list = ArrayList<LiveHail>(); w.hailShafts(d, t, 60_000.0, list)
                for (h in list) {
                    if (h.hail < 0.7 || elev(terra, h.centre) < 5.0) continue
                    val n = h.centre
                    val ground = maxOf(elev(terra, n), 0.0) + 3.0
                    val hail = listOf(-30.0, 0.0, 60.0, 150.0).map { w.hailAt(n, ground, t + it) }
                    if (hail.min() < 0.3) continue
                    val strikes = ArrayList<Strike>(); w.strikes(n, t - 30.0, t + 170.0, strikes)
                    val near = strikes.count { arc(terra, it.direction, n) < 15_000.0 }
                    val score = hail.min() + near * 0.05
                    if (best == null || score > best.first)
                        best = score to line("hail", terra, t, n, "hail ${hail.joinToString("/")} at -30/0/60/150 s, strikes within 15 km over 200 s $near, width ${h.width} speed ${h.speed}")
                }
            }
            best?.let { lines += it.second }
        }

        // Eruption: of the first 10 strong long ones, the nearest place downwind with ash fallen to the ground; t = 2.5 h in.
        run {
            val v = w.ashModel
            var best: Pair<Int, String>? = null
            for (e in (4L until 400L).flatMap { v.eruptionsOf(it) }.filter { it.strength > 0.6 && it.emitting > 3 * 3_600.0 }.take(20)) {
                val t = e.start + 2.5 * 3_600.0
                val list = ArrayList<LiveEruption>(); w.eruptions(e.vent, t, 0.0, list)
                val live = list.firstOrNull { it.id == e.id } ?: continue
                val down = Vec3().setTo(live.drift).normalizeInPlace()
                for (km in 5..200 step 5) {
                    if (best != null && km >= best.first) break
                    val n = move(terra, e.vent, down, km * 1_000.0)
                    if (elev(terra, n) < 5.0) continue
                    val a = air(w, terra, n, t)
                    if (a.ash < 0.1) continue
                    best = km to line("ash", terra, t, n,
                        "vent ${e.ventHeight} m, $km km downwind, strength ${live.strength} columnTop ${live.columnTop} length ${live.length} ash ${a.ash} ashTop ${a.ashTop} sight ${a.visibility}")
                    break
                }
            }
            lines += best?.second ?: "# ash: none at the ground downwind"
        }

        // Land tornado in tornado country: the craft where its foot will be 90 s on; t = it reaches the craft.
        run {
            var t = 0.0
            var made = false
            while (!made && t < 48 * 3_600.0) {
                val list = ArrayList<LiveTornado>(); w.tornadoes(Vec3.unitY(), t, Double.MAX_VALUE, list)
                for (k in list) {
                    if (k.overWater || k.inHurricane || k.strength < 0.6 || k.age > 0.4 * k.life || k.life - k.age < 400.0) continue
                    val later = ArrayList<LiveTornado>(); w.tornadoes(k.centre, t + 90.0, 20_000.0, later)
                    val n = later.firstOrNull { it.id == k.id }?.centre ?: continue
                    if (elev(terra, n) < 5.0 || w.tornadoModel.country(n) < 0.5) continue
                    val debris = w.debrisAt(n, 3.0, t + 90.0)
                    if (debris < 0.2) continue
                    lines += line("tornado", terra, t + 90.0, n,
                        "lat ${Math.toDegrees(SolarSystem.latitudeOf(n))} country ${w.tornadoModel.country(n)} strength ${k.strength} core ${k.coreRadius} reach ${k.reach} speed ${k.velocity.length} age ${k.age} life ${k.life} debris $debris")
                    made = true; break
                }
                t += 120.0
            }
        }

        // Moonquake: of Luna's M6+ main shocks in 400 cycles the one shaking hardest 2 km off; t = shaking arrives.
        run {
            val q = Quakes.of(luna, extreme)
            val best = (0L until 400L).flatMap { q.mainShocks(it) }.filter { it.magnitude >= 6.0 }.map { shock ->
                val n = move(luna, shock.epicentre, ring(shock.epicentre, 0), 2_000.0)
                val list = ArrayList<LiveQuake>(); q.near(n, shock.start + 0.5, 0.0, list)
                list.firstOrNull { it.id == shock.id }?.let { Triple(shock, n, it) }
            }.filterNotNull().maxByOrNull { it.third.peakHere }!!
            val (shock, n, here) = best
            lines += line("moonquake", luna, here.arrivesHere, n,
                "M ${shock.magnitude} depth ${shock.depth} start ${shock.start} peakHere ${here.peakHere} duration ${shock.duration} dist 2000")
        }

        lines += "# seed R0 ${q(terra.rotationAt(seedTime))}"
        out.resolve("scenes.txt").writeText(lines.joinToString("\n", postfix = "\n"))
    }

    /** Loads each made scene as a world and reports the events round its craft over its first 200 s. */
    @Test
    fun check() {
        guard()
        val report = StringBuilder()
        val files = out.listFiles { f -> f.name.endsWith(".json") }!!.sortedBy { it.name }
        for (f in files) {
            val world = World(system, StockParts.catalog)
            val problems = world.restore(WorldStore.decode(f.readText()))
            val v = world.vessels.first()
            val body = system.body(v.referenceBodyId)
            val t0 = world.time
            val n = body.rotationAt(t0).inverseRotate(v.body.position).normalizeInPlace()
            val height = v.body.position.length - body.radius - (if (body == terra) maxOf(elev(body, n), 0.0) else elev(body, n))
            report.append("${f.name}: ${v.name} on ${body.id} at time $t0, ${"%.1f".format(height)} m over the ground, ${problems.size} problems ${problems.take(2)}\n")
            val config = if (f.name == "pad.json") WeatherConfig() else extreme
            if (body.id == "luna") {
                val q = Quakes.of(luna, config)
                for (dt in listOf(0.0, 30.0, 60.0, 65.0, 75.0, 120.0, 200.0)) {
                    val vel = Vec3(); q.shake(n, t0 + dt, vel)
                    report.append("  +${dt.toInt()} s shaking ${"%.3f".format(q.level())} m/s^2\n")
                }
                continue
            }
            val w = Weather(body, config)
            for (dt in listOf(0.0, 30.0, 60.0, 65.0, 75.0, 90.0, 120.0, 160.0, 200.0)) {
                val t = t0 + dt
                val a = air(w, body, n, t)
                val vel = Vec3(); w.quakeShake(n, t, vel)
                val tor = ArrayList<LiveTornado>(); w.tornadoes(n, t, 5_000.0, tor)
                val k = tor.filter { !it.overWater }.minByOrNull { arc(body, it.centre, n) }
                val dust = ArrayList<LiveDust>(); w.dustStorms(n, t, 0.0, dust)
                val bz = ArrayList<LiveBlizzard>(); w.blizzards(n, t, 0.0, bz)
                val er = ArrayList<LiveEruption>(); w.eruptions(n, t, 0.0, er)
                report.append("  +${dt.toInt()} s quake ${"%.3f".format(w.quakeLevel())} m/s^2, dust ${"%.2f".format(a.dust)}, snow ${"%.2f".format(a.snow)}, " +
                    "hail ${"%.2f".format(a.hail)}, ash ${"%.2f".format(a.ash)}, sight ${a.visibility.toInt()} m, wind ${"%.1f".format(a.wind.length)} m/s, " +
                    "debris ${"%.2f".format(w.debrisAt(n, 3.0, t))}" +
                    (k?.let { ", tornado ${arc(body, it.centre, n).toInt()} m strength ${"%.2f".format(it.strength)}" } ?: "") +
                    (dust.firstOrNull { it.kind == DustKind.HABOOB }?.let { ", haboob front ${arc(body, it.centre, n).toInt()} m strength ${"%.2f".format(it.strength)}" } ?: "") +
                    (bz.firstOrNull()?.let { ", blizzard middle ${arc(body, it.centre, n).toInt()} m strength ${"%.2f".format(it.strength)}" } ?: "") +
                    (er.firstOrNull()?.let { ", vent ${arc(body, it.vent, n).toInt()} m strength ${"%.2f".format(it.strength)}" } ?: "") + "\n")
            }
            val strikes = ArrayList<Strike>(); w.strikes(n, t0, t0 + 200.0, strikes)
            report.append("  strikes in 200 s: within 5 km ${strikes.count { arc(body, it.direction, n) < 5_000.0 }}, within 15 km ${strikes.count { arc(body, it.direction, n) < 15_000.0 }}\n")
        }
        out.resolve("check.txt").writeText(report.toString())
    }
}
