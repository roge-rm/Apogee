package com.rm.apogee.core.orbit

import com.rm.apogee.core.craft.CraftDesign
import com.rm.apogee.core.craft.PlacedPart
import com.rm.apogee.core.craft.StockCraft
import com.rm.apogee.core.math.Quat
import com.rm.apogee.core.math.Vec3
import com.rm.apogee.core.part.StockParts
import com.rm.apogee.core.world.Command
import com.rm.apogee.core.world.World
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs
import kotlin.math.acos
import kotlin.math.sqrt

/** The whole system: every world where it should be, spinning as it should, lit as it should. */
class SystemTest {
    private val system = SolarSystem.defaultSystem()
    private val catalog = StockParts.catalog

    private val planets = listOf("celer", "caligo", "terra", "rubra", "magna", "aurea", "obliqua", "caerula", "ultima")
    private val moons = mapOf(
        "luna" to "terra", "timor" to "rubra", "pavor" to "rubra",
        "fornax" to "magna", "crusta" to "magna", "maxima" to "magna", "cicatrix" to "magna",
        "aurantia" to "aurea", "fons" to "aurea", "aversa" to "caerula", "portitor" to "ultima",
    )

    @Test
    fun `every world is there, round its own parent`() {
        for (id in planets) assertEquals(id, "sol", system.body(id).parentId)
        for ((moon, planet) in moons) assertEquals(moon, planet, system.body(moon).parentId)
        assertEquals(1 + planets.size + moons.size, system.bodies.size)
    }

    @Test
    fun `each orbit stays inside its parent's reach, clear of the parent, and no two reaches overlap`() {
        for (body in system.bodies.values) {
            val orbit = body.orbit ?: continue
            val parent = system.body(body.parentId!!)
            assertTrue("${body.id} flies out of ${parent.id}'s reach", orbit.apoapsis + body.sphereOfInfluence < parent.sphereOfInfluence)
            assertTrue("${body.id} grazes ${parent.id}", orbit.periapsis - body.sphereOfInfluence > parent.radius)
            assertTrue("${body.id} is inside its own reach", body.sphereOfInfluence > body.radius * 1.5)
        }
        // Siblings never overlap, whenever they are.
        for (parent in system.bodies.values) {
            val kids = system.childrenOf(parent.id).sortedBy { it.orbit!!.semiMajorAxis }
            for ((a, b) in kids.zipWithNext()) {
                // Ultima crosses inside Caerula's orbit, as the real pair do - kept apart by resonance, not distance.
                if (a.id == "caerula" && b.id == "ultima") continue
                assertTrue("${a.id} and ${b.id} overlap", a.orbit!!.apoapsis + a.sphereOfInfluence < b.orbit!!.periapsis - b.sphereOfInfluence)
            }
        }
    }

    @Test
    fun `surface gravity and the planets' years are as the scale says`() {
        val g = mapOf("celer" to 3.70, "rubra" to 3.72, "magna" to 24.79, "ultima" to 0.62, "aurantia" to 1.352)
        for ((id, want) in g) assertEquals(id, want, system.body(id).surfaceGravity, 1e-6)
        // Kepler: years go as the distance to the three-halves.
        val terraYear = SystemData.period(system.body("terra").orbit!!)
        val rubraYear = SystemData.period(system.body("rubra").orbit!!)
        assertEquals(1.524 * sqrt(1.524), rubraYear / terraYear, 0.01)
        val ultimaYear = SystemData.period(system.body("ultima").orbit!!)
        assertEquals(39.48 * sqrt(39.48), ultimaYear / terraYear, 0.5)
    }

    @Test
    fun `Terra and Luna are where they always were, spinning as they always did`() {
        val terra = system.body("terra")
        val luna = system.body("luna")
        assertEquals(Vec3.unitY().distanceTo(terra.spinAxis), 0.0, 1e-12)
        assertEquals(600_000.0, terra.radius, 0.0)
        assertEquals(21_549.425, terra.rotationPeriod, 0.0)
        assertEquals(12_000_000.0, luna.orbit!!.semiMajorAxis, 1.0)
        // The ground turns about +Y exactly as before.
        val q = terra.rotationAt(5_000.0)
        val before = Quat.fromAxisAngle(Vec3.unitY(), 2 * Math.PI * 5_000.0 / 21_549.425)
        assertTrue(q.rotate(Vec3.unitX()).distanceTo(before.rotate(Vec3.unitX())) < 1e-12)
    }

    @Test
    fun `the sun stands where the old one did at the start, and the Cape has seasons`() {
        val sun = system.sunDirection("terra", Vec3.zero(), 0.0)
        val old = Vec3(0.62, 0.45, 0.64).normalizeInPlace()
        assertTrue("sun at ${sun}", acos((sun dot old).coerceIn(-1.0, 1.0)) < Math.toRadians(5.0))
        // The sun's height above the equator swings through the year, ±23.4 degrees.
        val year = SystemData.period(system.body("terra").orbit!!)
        val declinations = (0 until 24).map { k -> Math.toDegrees(kotlin.math.asin(system.sunDirection("terra", Vec3.zero(), year * k / 24).y)) }
        assertEquals(23.4, declinations.max(), 0.3)
        assertEquals(-23.4, declinations.min(), 0.3)
    }

    @Test
    fun `worlds are tipped as they are - Obliqua on its side, Caligo upside down`() {
        fun tilt(id: String) = Math.toDegrees(acos((system.body(id).spinAxis dot SystemData.ECLIPTIC_NORTH).coerceIn(-1.0, 1.0)))
        assertEquals(97.8, tilt("obliqua"), 0.01)
        assertEquals(177.4, tilt("caligo"), 0.01)
        assertEquals(25.2, tilt("rubra"), 0.01)
        assertEquals(23.4, tilt("terra"), 0.01)
        // A world's ground turns about its own axis: a point on its pole stays put.
        val obliqua = system.body("obliqua")
        val pole = obliqua.rotationAt(1_000.0).rotate(Vec3.unitY())
        assertTrue(pole.distanceTo(obliqua.spinAxis) < 1e-9)
        // The ground moves round that axis, and not along it.
        val p = obliqua.spinAxis.cross(Vec3.unitX()).normalizeInPlace().mulInPlace(obliqua.radius)
        assertEquals(0.0, obliqua.surfaceVelocityAt(p, Vec3()) dot obliqua.spinAxis, 1e-9)
        // Aversa goes round Caerula backwards.
        val aversa = system.body("aversa").orbit!!
        assertTrue(aversa.angularMomentum.normalized() dot system.body("caerula").spinAxis < 0.0)
    }

    @Test
    fun `sunlight fades with distance`() {
        val magna = system.sunStrength("magna", Vec3.zero(), 0.0)
        val expected = 1.0 / (5.203 * 5.203)
        assertEquals(expected, magna, expected * 0.1)
        assertTrue(system.sunStrength("ultima", Vec3.zero(), 0.0) < 0.002)
    }

    @Test
    fun `a transfer from Terra's orbit to Rubra's meets Rubra`() {
        val sol = system.body("sol")
        val rubra = system.body("rubra")
        val solMu = sol.gravitationalParameter
        val arrive = 10_000_000.0
        val there = system.positionOf("rubra", arrive)
        val r2 = there.length
        val r1 = SystemData.AU
        val a = (r1 + r2) / 2
        val flight = Math.PI * sqrt(a * a * a / solMu)
        val depart = arrive - flight
        // Leaving from opposite where Rubra will be, in the plane of Rubra's own motion.
        val out = there.copy().normalizeInPlace().mulInPlace(-r1)
        val normal = there.cross(system.velocityOf("rubra", arrive)).normalizeInPlace()
        val along = normal.cross(out).normalizeInPlace()
        val speed = sqrt(solMu * (2 / r1 - 1 / a))
        val path = Trajectory.predict(system, "sol", out, along.mulInPlace(speed), depart, horizon = flight * 1.2)
        val first = path.segments.first()
        assertEquals("never reached Rubra: ${path.segments.map { it.ending }}", Trajectory.Ending.ENCOUNTER, first.ending)
        assertEquals(rubra.id, first.nextBodyId)
    }

    @Test
    fun `the fastest warps come only far out between the worlds`() {
        assertTrue(1_000_000.0 in World.WARP_RATES.toList())
        val world = World.default(catalog)
        val terra = world.system.body("terra")
        val r = terra.radius + 150_000.0
        val low = world.spawnAt(StockCraft.probe(catalog), "terra", Vec3(r, 0.0, 0.0), Vec3(0.0, 0.0, sqrt(terra.gravitationalParameter / r)), Quat.identity())
        assertTrue(world.warpLimit(low) <= 10_000.0)
        val far = terra.radius * 30.0
        low.body.position.setTo(far, 0.0, 0.0)
        low.body.linearVelocity.setTo(0.0, 0.0, sqrt(terra.gravitationalParameter / far))
        assertEquals(1_000_000.0, world.warpLimit(low), 0.0)
    }

    @Test
    fun `a craft falling into Magna is crushed in the deep`() {
        val world = World.default(catalog)
        val magna = world.system.body("magna")
        val depth = magna.atmosphere!!.scaleHeight * 4.0
        val pod = world.spawnAt(StockCraft.probe(catalog), "magna", Vec3(magna.radius - depth, 0.0, 0.0), magna.surfaceVelocityAt(Vec3(magna.radius - depth, 0.0, 0.0), Vec3()), Quat.identity())
        // Sinking through ever-thicker air: a few minutes.
        repeat(60 * 400) { world.step(1.0 / 60.0) }
        assertNull("survived Magna's depths", world.vessel(pod.id))
    }

    @Test
    fun `a craft that crosses Aurea's rings against the gravel is torn apart`() {
        val world = World.default(catalog)
        val aurea = world.system.body("aurea")
        val rings = aurea.rings!!
        val r = (rings.inner + rings.outer) / 2
        // Just above the ring plane, falling straight through it.
        val axis = aurea.spinAxis
        val across = axis.cross(Vec3.unitX()).normalizeInPlace()
        val position = across.copy().mulInPlace(r).addScaledInPlace(axis, 20.0)
        val velocity = axis.copy().mulInPlace(-500.0)
        val craft = world.spawnAt(StockCraft.probe(catalog), "aurea", position, velocity, Quat.identity())
        repeat(60) { world.step(1.0 / 60.0) }
        assertNull("flew through the rings unhurt", world.vessel(craft.id))
    }

    @Test
    fun `a lander on Caligo's floor is crushed in minutes, and one inside a Hotshell is not`() {
        fun survives(shelled: Boolean): Boolean {
            val world = World.default(catalog)
            val caligo = world.system.body("caligo")
            val parts = if (!shelled) listOf(PlacedPart("pod-halo", Vec3.zero()))
            else listOf(
                PlacedPart("fairing-hot", Vec3.zero()),
                PlacedPart("pod-halo", Vec3(0.0, 0.7, 0.0), parentIndex = 0),
            )
            val design = CraftDesign(name = "Lander", parts = parts, stages = emptyList(), manualStaging = true, catalogHash = catalog.contentHash)
            val at = Vec3(caligo.radius + 200.0, 0.0, 0.0)
            val craft = world.spawnAt(design, "caligo", at, caligo.surfaceVelocityAt(at, Vec3()), Quat.identity())
            repeat((300.0 * 60).toInt()) { world.step(1.0 / 60.0) }
            val pod = world.vessel(craft.id) ?: return false
            return pod.defs.indices.any { pod.defs[it].id == "pod-halo" && !pod.isBroken(it) }
        }
        assertTrue("a bare pod lasted five minutes on Caligo", !survives(shelled = false))
        assertTrue("the Hotshell did not keep the pod alive", survives(shelled = true))
    }

    @Test
    fun `the system's hash is the same every time, and changes with any world`() {
        val hash = system.contentHash
        assertEquals(16, hash.length)
        assertEquals(hash, SolarSystem.defaultSystem().contentHash)
        assertEquals(hash, SolarSystem.DEFAULT_HASH)
        // A lone test planet is another system altogether.
        val lone = SolarSystem(listOf(system.body("terra").let {
            CelestialBody(it.id, it.displayName, it.gravitationalParameter, it.radius, it.rotationPeriod)
        }), "terra")
        assertNotEquals(hash, lone.contentHash)
    }

    @Test
    fun `panels among the giants make a sliver of what they do at home`() {
        val world = World.default(catalog)
        val magna = world.system.body("magna")
        val r = magna.radius * 40.0
        val craft = world.spawnAt(StockCraft.probe(catalog), "magna", Vec3(r, 0.0, 0.0), Vec3(0.0, 0.0, sqrt(magna.gravitationalParameter / r)), Quat.identity())
        assertTrue(abs(world.system.sunStrength("magna", craft.body.position, world.time) - 1.0 / 27.07) < 0.01)
        world.apply(Command.SetThrottle(craft.id.raw, 0.0))
    }
}
