package com.rm.apogee.core.world

import com.rm.apogee.core.craft.CraftDesign
import com.rm.apogee.core.craft.StockCraft
import com.rm.apogee.core.craft.Vessel
import com.rm.apogee.core.math.Vec3
import com.rm.apogee.core.orbit.SolarSystem
import com.rm.apogee.core.part.ResourceType
import com.rm.apogee.core.part.StockParts
import com.rm.apogee.core.terrain.Deposits
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Under the sea: its floor and what lies there, the sea's weight, and the submarines that go down into it. */
class DeepTest {
    private val catalog = StockParts.catalog
    private val dt = 1.0 / 60.0

    private fun run(world: World, seconds: Double, each: () -> Unit = {}) = repeat((seconds / dt).toInt()) { each(); world.step(dt) }

    /** [design] afloat off the Cape, [east] and [north] metres from the pad, its crew aboard. */
    private fun afloat(world: World, design: CraftDesign, east: Double = -8_000.0, north: Double = 12_000.0): Vessel {
        val d = SolarSystem.capeDirection(east, north)
        val sub = world.spawnOnSurface(design, LaunchSite("sea", "Sea", "terra", SolarSystem.latitudeOf(d), SolarSystem.longitudeOf(d)))
        world.assignOwner(sub, "p1")
        world.seatCrew(sub)
        return sub
    }

    /** Taken down to [depth] with its tanks flooded, and held there. */
    private fun downTo(world: World, sub: Vessel, depth: Double) {
        world.apply(Command.SetBallast(sub.id.raw, 1))
        var t = 0.0
        while (world.depthOf(sub) < depth && t < 3_000.0) { world.step(dt); t += dt }
        world.apply(Command.HoldDepth(sub.id.raw, true))
    }

    private fun climb(world: World, v: Vessel): Double {
        val a = world.attractorFor(v)
        return a.surfaceVelocityAt(v.body.position, Vec3()).subInPlace(v.body.linearVelocity).mulInPlace(-1.0) dot v.body.position.normalized()
    }

    // --- the floor ------------------------------------------------------------------

    @Test
    fun `each of the sea's named places is where it should be, and the Terra Deep is the deepest of all`() {
        val system = SolarSystem.defaultSystem()
        val bands = mapOf(
            "lost-sounder" to (10.0..60.0), "great-arch" to (100.0..330.0), "cape-canyon" to (100.0..330.0),
            "farrow-seamount" to (40.0..100.0), "canyon-wreck" to (330.0..1_550.0), "chimneys" to (330.0..1_550.0),
            "nodule-plain" to (1_550.0..3_000.0), "terra-deep" to (6_500.0..7_100.0),
            "kraken-deep" to (500.0..1_200.0), "ligeia-spires" to (150.0..600.0),
        )
        for (wonder in SeaWonders.all) {
            val depth = -system.body(wonder.bodyId).terrain!!.elevation(wonder.direction)
            assertTrue("${wonder.name} is $depth m down", depth in bands.getValue(wonder.id))
        }
        // The Cape's, all within a submarine's reach of the harbour.
        for (wonder in SeaWonders.all.filter { it.bodyId == "terra" }) {
            val far = wonder.direction.distanceTo(SolarSystem.capeDirection(0.0, 0.0)) * 600_000.0
            assertTrue("${wonder.name} is $far m off", far < 25_000.0)
        }
        // Nowhere on a sweep round the Cape is deeper than the Deep.
        val terra = system.body("terra").terrain!!
        val deep = terra.elevation(SeaWonders.byId("terra-deep")!!.direction)
        for (i in -30..30) for (j in -30..30) {
            assertTrue(terra.elevation(SolarSystem.capeDirection(i * 1_000.0, j * 1_000.0)) >= deep - 50.0)
        }
    }

    @Test
    fun `the vents and the nodules are rich in ore, and their chimneys stand there`() {
        val terra = SolarSystem.defaultSystem().body("terra").terrain!!
        val chimneys = SeaWonders.byId("chimneys")!!.direction
        assertTrue(Deposits.richness(terra, chimneys, ResourceType.ORE) > 0.7)
        assertTrue(Deposits.richness(terra, SeaWonders.byId("nodule-plain")!!.direction, ResourceType.ORE) > 0.5)
        var vents = 0
        terra.scatter!!.forEachBlockNear(chimneys, 300.0, Vec3()) { block ->
            for (k in 0 until block.count) if (com.rm.apogee.core.terrain.ScatterKind.of(block.kinds[k].toInt()) == com.rm.apogee.core.terrain.ScatterKind.VENT) vents++
        }
        assertTrue("only $vents chimneys at the Chimneys", vents >= 5)
    }

    @Test
    fun `the Great Arch and the wrecks lie where they were left`() {
        val world = World.default(catalog)
        world.ensureStructures()
        for (wonder in SeaWonders.all.filter { it.landmark.isNotEmpty() }) {
            val mark = world.landmark(wonder)
            assertTrue("no ${wonder.landmark}", mark != null && mark.anchored)
            val body = world.attractorFor(mark!!)
            val at = body.toBodyFixed(mark.body.position, body.rotationAt(world.time)).normalizeInPlace()
            assertTrue("${wonder.landmark} is ${at.distanceTo(wonder.direction) * body.radius} m off", at.distanceTo(wonder.direction) * body.radius < 30.0)
        }
        run(world, 30.0)
        for (wonder in SeaWonders.all.filter { it.landmark.isNotEmpty() }) {
            val mark = world.landmark(wonder)!!
            assertTrue("${wonder.landmark} was broken up", mark.broken.none { it })
        }
    }

    // --- the sea's weight --------------------------------------------------------------

    @Test
    fun `the Minnow is safe at three hundred metres, and crushed well below its rating - but not its solid parts`() {
        val safe = World.default(catalog)
        val minnow = afloat(safe, StockCraft.minnow(catalog))
        downTo(safe, minnow, 300.0)
        run(safe, 60.0)
        assertTrue("hurt at 300 m: ${minnow.health.min()}", minnow.health.all { it >= 1.0 })
        assertTrue(minnow.crushShare in 0.8..1.0)

        val deep = World.default(catalog)
        val doomed = afloat(deep, StockCraft.minnow(catalog))
        downTo(deep, doomed, 450.0)
        run(deep, 20.0)
        assertTrue("not near its limit: ${doomed.crushShare}", doomed.crushShare > 1.2)
        val pod = doomed.defs.indexOfFirst { it.id == "pod-pearl" }
        val planes = doomed.defs.indexOfFirst { it.id == "planes-dive" }
        assertTrue("the Pearl stands 450 m", doomed.health[pod] < 1.0 || deep.vessel(doomed.id) == null)
        assertTrue("the dive planes were crushed", deep.vessel(doomed.id) == null || doomed.health[planes] >= 1.0)
    }

    // --- diving ---------------------------------------------------------------------------

    @Test
    fun `flooded it sinks, held it stays, blown it comes back up`() {
        val world = World.default(catalog)
        val minnow = afloat(world, StockCraft.minnow(catalog))
        run(world, 10.0)
        assertTrue("not afloat: ${world.depthOf(minnow)} m", world.depthOf(minnow) < 2.0)
        world.apply(Command.SetBallast(minnow.id.raw, 1))
        run(world, 90.0)
        assertTrue("not going down: ${world.depthOf(minnow)} m", world.depthOf(minnow) > 20.0 && climb(world, minnow) < -0.3)
        world.apply(Command.HoldDepth(minnow.id.raw, true))
        val held = minnow.control.holdDepthAt
        run(world, 60.0)
        var worst = 0.0
        run(world, 300.0) { worst = maxOf(worst, kotlin.math.abs(world.depthOf(minnow) - held)) }
        assertTrue("wandered $worst m off $held", worst < 2.0)
        world.apply(Command.SetBallast(minnow.id.raw, -1))
        run(world, 120.0)
        assertTrue("never came up: ${world.depthOf(minnow)} m", world.depthOf(minnow) < 2.0)
        assertTrue("its ballast not blown: ${world.ballastShare(minnow)}", world.ballastShare(minnow) < 0.05)
    }

    /** How far [v]'s nose is above the horizontal, degrees; below it, negative. */
    private fun pitch(v: Vessel): Double = Math.toDegrees(kotlin.math.asin((v.forward() dot v.body.position.normalized()).coerceIn(-1.0, 1.0)))

    /**
     * Level afloat; nose a little down diving, as a submarine goes down; and
     * level again held at a depth - never stood on end, as the first ones
     * were, with nothing low to hold them upright.
     */
    @Test
    fun `each submarine lies level afloat and held, and dives nose a little down`() {
        for (design in listOf(StockCraft.minnow(catalog), StockCraft.nautilus(catalog), StockCraft.abyss(catalog))) {
            val world = World.default(catalog)
            val sub = afloat(world, design, -12_000.0, 13_000.0)
            run(world, 30.0)
            assertTrue("${design.name} afloat at ${pitch(sub)} degrees", kotlin.math.abs(pitch(sub)) < 8.0)
            world.apply(Command.SetBallast(sub.id.raw, 1))
            var worst = 0.0
            run(world, 90.0) { worst = maxOf(worst, kotlin.math.abs(pitch(sub))) }
            assertTrue("${design.name} tipped to $worst degrees going down", worst < 25.0)
            assertTrue("${design.name} not under: ${world.depthOf(sub)} m", world.depthOf(sub) > 20.0)
            world.apply(Command.HoldDepth(sub.id.raw, true))
            run(world, 90.0)
            assertTrue("${design.name} held at ${pitch(sub)} degrees", kotlin.math.abs(pitch(sub)) < 8.0)
        }
    }

    @Test
    fun `the Nautilus and the Abyss each go down near their rating, and come back up`() {
        for ((design, depth) in listOf(StockCraft.nautilus(catalog) to 1_300.0, StockCraft.abyss(catalog) to 2_200.0)) {
            val world = World.default(catalog)
            // Over the deep off the Cape.
            val sub = afloat(world, design, -12_000.0, 13_000.0)
            downTo(world, sub, depth)
            run(world, 30.0)
            assertTrue("${design.name} hurt at ${world.depthOf(sub)} m", world.vessel(sub.id) != null && sub.health.all { it >= 1.0 })
            world.apply(Command.SetBallast(sub.id.raw, -1))
            run(world, 60.0)
            assertTrue("${design.name} not rising: ${climb(world, sub)}", climb(world, sub) > 0.3)
        }
    }

    @Test
    fun `the Nautilus's sonar hears the floor below and points the way to what is not yet found`() {
        val world = World.default(catalog)
        // Down near the Canyon Wreck, a few hundred metres short of it.
        val wreck = SeaWonders.byId("canyon-wreck")!!
        val sub = afloat(world, StockCraft.nautilus(catalog), -1_500.0, 9_700.0)
        downTo(world, sub, 200.0)
        run(world, 5.0)
        val heard = world.systemsOf(sub)
        val floor = world.attractorFor(sub).let { a ->
            a.altitudeOf(sub.body.position) - a.terrain!!.elevation(a.toBodyFixed(sub.body.position, a.rotationAt(world.time)))
        }
        assertEquals(floor, heard.seabed.toDouble(), 1.0)
        assertTrue("nothing found: ${heard.findRange}", heard.findRange in 0f..2_000f)
        // The nearest thing to find is the wreck, where it lies.
        val a = world.attractorFor(sub)
        val here = a.toBodyFixed(sub.body.position, a.rotationAt(world.time)).normalizeInPlace()
        assertEquals(here.distanceTo(wreck.direction) * a.radius, heard.findRange.toDouble(), 5.0)
        assertTrue(heard.findBearing in -180f..180f)
        // The Minnow has none.
        val minnow = afloat(world, StockCraft.minnow(catalog), 0.0, 5_000.0)
        assertTrue(world.systemsOf(minnow).findRange < 0f)
    }

    @Test
    fun `a Halo pod down in the sea floats`() {
        val world = World.default(catalog)
        val pod = CraftDesign("Pod", listOf(com.rm.apogee.core.craft.PlacedPart("pod-halo", Vec3.zero())), catalogHash = catalog.contentHash)
        val capsule = afloat(world, pod)
        run(world, 60.0)
        assertTrue("sank to ${world.depthOf(capsule)} m", world.depthOf(capsule) < 0.6)
        assertEquals(0.0, capsule.crushShare, 0.2)
    }
}
