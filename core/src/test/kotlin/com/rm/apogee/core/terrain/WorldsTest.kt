package com.rm.apogee.core.terrain

import com.rm.apogee.core.craft.StockCraft
import com.rm.apogee.core.math.Vec3
import com.rm.apogee.core.orbit.SolarSystem
import com.rm.apogee.core.part.StockParts
import com.rm.apogee.core.world.World
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs

/** Every world's ground: its own shape, its landmarks where they are, somewhere to land on each. */
class WorldsTest {
    private val system = SolarSystem.defaultSystem()
    private val catalog = StockParts.catalog

    private val landable = listOf(
        "celer", "caligo", "rubra", "timor", "pavor", "fornax", "crusta", "maxima", "cicatrix",
        "aurantia", "fons", "aversa", "ultima", "portitor",
    )

    private fun dir(la: Double, lo: Double) = Landforms.at(la, lo).let { Vec3(it[0], it[1], it[2]) }
    private fun ground(body: String, la: Double, lo: Double): SurfaceMaterial {
        val t = system.body(body).terrain!!
        val d = dir(la, lo)
        return t.material(d, t.elevation(d), 0.0)
    }

    @Test
    fun `every rocky world has ground, and the giants have none`() {
        for (id in landable) assertNotNull(id, system.body(id).terrain)
        for (id in listOf("magna", "aurea", "obliqua", "caerula")) assertNull(id, system.body(id).terrain)
    }

    @Test
    fun `each world's ground is the same every time, and not the same as any other's`() {
        val samples = (0 until 12).map { k -> dir(-60.0 + 10.0 * k, 17.0 * k) }
        val shapes = landable.associateWith { id ->
            val t = system.body(id).terrain!!
            val again = SolarSystem.defaultSystem().body(id).terrain!!
            samples.map { d -> t.elevation(d).also { assertEquals(id, it, again.elevation(d), 0.0) } }
        }
        for ((a, b) in landable.zipWithNext()) assertNotEquals("$a and $b are the same ground", shapes[a], shapes[b])
    }

    @Test
    fun `heights stay inside each world's reach`() {
        for (id in landable) {
            val t = system.body(id).terrain!!
            for (k in 0 until 400) {
                val d = dir(-89.0 + 178.0 * (k % 20) / 19, 360.0 * (k / 20) / 20)
                val h = t.elevation(d)
                assertTrue("$id: $h at $k beyond ${t.maxElevation}", abs(h) <= t.maxElevation * 1.05 + 500.0)
            }
        }
    }

    @Test
    fun `landmarks are where they are said to be, made of what they are said to be`() {
        assertEquals(SurfaceMaterial.NITROGEN_ICE, ground("ultima", 15.0, 177.0))
        assertEquals(SurfaceMaterial.LAVA, ground("fornax", -12.0, 50.0))
        assertEquals(SurfaceMaterial.LAVA, ground("caligo", 24.0, -110.0))
        assertEquals(SurfaceMaterial.TESSERA, ground("caligo", 63.25, 21.85))
        assertEquals(SurfaceMaterial.ORGANIC_SAND, ground("aurantia", 4.1, -39.55))
        assertEquals(SurfaceMaterial.ICE, ground("rubra", 88.0, 0.0))
        assertEquals(SurfaceMaterial.RED_DUST, ground("rubra", -30.0, 60.0).let { if (it == SurfaceMaterial.SCREE || it == SurfaceMaterial.ROCK) SurfaceMaterial.RED_DUST else it })
        // The Great Mount stands far above the plains; the Rift is kilometres below its rim.
        val rubra = system.body("rubra").terrain!!
        assertTrue("the Great Mount is ${rubra.elevation(dir(18.0, -134.0))} m", rubra.elevation(dir(18.0, -134.0)) > 12_000.0)
        assertTrue("the Rift ${rubra.elevation(dir(-9.0, -74.55))}, its rim ${rubra.elevation(dir(-2.0, -74.55))}", rubra.elevation(dir(-9.0, -74.55)) < rubra.elevation(dir(-2.0, -74.55)) - 2_500.0)
        // The Heart lies low.
        val ultima = system.body("ultima").terrain!!
        assertTrue("the Heart ${ultima.elevation(dir(15.0, 177.0))} vs ${ultima.elevation(dir(-40.0, 0.0))}", ultima.elevation(dir(15.0, 177.0)) < ultima.elevation(dir(-40.0, 0.0)) - 800.0)
        // Aurantia has seas in the north, and they are liquid.
        val aurantia = system.body("aurantia").terrain!!
        val sea = (0 until 72).map { dir(82.0, it * 5.0) }.count { aurantia.isOcean(it) }
        assertTrue("no northern sea on Aurantia", sea > 10)
        assertEquals(450.0, system.body("aurantia").ocean!!.density, 0.0)
        // Timor is no ball.
        val timor = system.body("timor").terrain!!
        val spread = (0 until 50).map { timor.elevation(dir(-60.0 + 2.4 * it, 7.0 * it)) }
        assertTrue("Timor is round", spread.max() - spread.min() > 500.0)
    }

    @Test
    fun `every world's test site stands on sound, dry, level-enough ground`() {
        for (site in World.launchSites.filter { it.bodyId != "terra" && it.bodyId != "luna" }) {
            val t = system.body(site.bodyId).terrain!!
            val d = SolarSystem.surfaceDirection(site.latitude, site.longitude)
            val m = t.material(d, t.elevation(d), 0.0)
            assertTrue("${site.id} is on lava", m != SurfaceMaterial.LAVA)
            if (site.id == "aurantia-sea") assertTrue("the sea site is dry", t.isOcean(d))
            else assertTrue("${site.id} is under the sea", !t.isOcean(d))
        }
    }

    @Test
    fun `a lander set down on each world stays there, and on Timor too`() {
        for (site in World.launchSites.filter { it.id in listOf("timor", "rubra-rift", "ultima-heart", "crusta-lineae", "celer-basin") }) {
            val world = World.default(catalog)
            val lander = world.spawnOnSurface(StockCraft.lander(catalog), site)
            repeat(60 * 8) { world.step(1.0 / 60.0) }
            assertNotNull("${site.id}: the lander was lost", world.vessel(lander.id))
            val body = world.system.body(site.bodyId)
            val speed = body.surfaceVelocityAt(lander.body.position, Vec3()).subInPlace(lander.body.linearVelocity).length
            assertTrue("${site.id}: still moving at $speed", speed < 1.0)
        }
    }
}
