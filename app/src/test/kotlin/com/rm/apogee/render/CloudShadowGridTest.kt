package com.rm.apogee.render

import com.rm.apogee.core.math.Quat
import com.rm.apogee.core.math.Vec3
import com.rm.apogee.core.weather.CloudLobe
import com.rm.apogee.core.weather.CloudShape
import com.rm.apogee.core.weather.CloudType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class CloudShadowGridTest {

    private val radius = 600_000.0
    private val up = Vec3(0.3, 0.8, 0.52).normalizeInPlace()
    private val size = 128
    private val extent = 20_000.0

    private fun cumulusAt(offsetEast: Double, height: Double, across: Double = 800.0, type: CloudType = CloudType.CUMULUS, amount: Double = 1.0): CloudShape {
        val east = Vec3.unitY().crossInPlace(up).normalizeInPlace()
        val centre = up.copy().mulInPlace(radius + height).addScaledInPlace(east, offsetEast)
        return CloudShape(type, amount).also { it.lobes.add(CloudLobe(centre, across, 400.0, 1.0)) }
    }

    private fun cellOf(grid: CloudShadowGrid, eastMetres: Double, northMetres: Double = 0.0): Pair<Int, Int> {
        val cell = 2 * extent / size
        return ((eastMetres + extent) / cell).toInt() to ((northMetres + extent) / cell).toInt()
    }

    @Test
    fun `with the sun overhead a cloud shades the ground right under it`() {
        val grid = CloudShadowGrid.build(listOf(cumulusAt(0.0, 1_500.0)), up, radius, 0.0, up, 0.6f, size, extent, 1)!!
        val (i, j) = cellOf(grid, 0.0)
        assertTrue("shaded under it: ${grid.shade(i, j)}", grid.shade(i, j) > 0.4)
        val (fi, fj) = cellOf(grid, 5_000.0)
        assertEquals("clear well away", 0.0, grid.shade(fi, fj), 0.01)
        // Its base: 1,500 m minus 0.4 of its 400 m half-height.
        assertEquals(1_340.0, grid.base(i, j), CloudShadowGrid.HEIGHT_SCALE / 255.0 * 1.5)
    }

    @Test
    fun `a low sun casts the shadow away from it, by height over the tangent of its elevation`() {
        val east = Vec3.unitY().crossInPlace(up).normalizeInPlace()
        // Thirty degrees up, in the west, so the shadow falls east of the cloud.
        val elevation = Math.toRadians(30.0)
        val light = up.copy().mulInPlace(kotlin.math.sin(elevation)).addScaledInPlace(east, -kotlin.math.cos(elevation))
        val grid = CloudShadowGrid.build(listOf(cumulusAt(0.0, 1_500.0)), up, radius, 0.0, light, 0.6f, size, extent, 1)!!
        val shift = 1_500.0 / kotlin.math.tan(elevation) // about 2.6 km
        val (si, sj) = cellOf(grid, shift)
        val (ui, uj) = cellOf(grid, 0.0)
        assertTrue("shaded where the shadow falls: ${grid.shade(si, sj)}", grid.shade(si, sj) > 0.4)
        assertTrue("not under the cloud itself: ${grid.shade(ui, uj)}", grid.shade(ui, uj) < 0.1)
        assertTrue("and nothing with the light below the horizon", CloudShadowGrid.build(
            listOf(cumulusAt(0.0, 1_500.0)), up, radius, 0.0, up.copy().mulInPlace(-1.0), 0.6f, size, extent, 1,
        ) == null)
    }

    @Test
    fun `a thick deck shades more than a lone cumulus`() {
        val deck = CloudShape(CloudType.STRATUS, 1.0)
        val east = Vec3.unitY().crossInPlace(up).normalizeInPlace()
        for (k in -2..2) deck.lobes.add(CloudLobe(up.copy().mulInPlace(radius + 900.0).addScaledInPlace(east, k * 900.0), 1_000.0, 200.0, 1.0))
        val grid = CloudShadowGrid.build(listOf(deck), up, radius, 0.0, up, 0.6f, size, extent, 1)!!
        val lone = CloudShadowGrid.build(listOf(cumulusAt(0.0, 900.0)), up, radius, 0.0, up, 0.6f, size, extent, 1)!!
        val (i, j) = cellOf(grid, 0.0)
        assertTrue("deck ${grid.shade(i, j)} > cumulus ${lone.shade(i, j)}", grid.shade(i, j) > lone.shade(i, j))
    }

    @Test
    fun `the shader's matrix finds a point on the ground in its cell however the planet has turned`() {
        val east = Vec3.unitY().crossInPlace(up).normalizeInPlace()
        val grid = CloudShadowGrid.build(listOf(cumulusAt(0.0, 1_500.0)), up, radius, 0.0, up, 0.6f, size, extent, 1)!!
        val turn = Quat.fromAxisAngle(Vec3.unitY(), 0.7)
        // A point on the ground 3 km east of the middle, body-fixed, and turned in the world.
        val groundFixed = up.copy().mulInPlace(radius).addScaledInPlace(east, 3_000.0)
        val world = turn.rotate(groundFixed, Vec3())
        val camera = world.copy().addScaledInPlace(turn.rotate(up, Vec3()), 50.0)
        val m = FloatArray(16)
        grid.matrix(turn, camera, m)
        val p = world.copy().subInPlace(camera)
        val u = m[0] * p.x + m[4] * p.y + m[8] * p.z + m[12]
        val v = m[1] * p.x + m[5] * p.y + m[9] * p.z + m[13]
        val z = m[2] * p.x + m[6] * p.y + m[10] * p.z + m[14]
        assertEquals(0.5 + 3_000.0 / (2 * extent), u, 1e-4)
        assertEquals(0.5, v, 1e-4)
        assertEquals("on the sheet", 0.0, z, 1e-4)
    }
}
