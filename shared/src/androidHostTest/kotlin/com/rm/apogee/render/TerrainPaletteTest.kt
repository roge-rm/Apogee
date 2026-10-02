package com.rm.apogee.render

import com.rm.apogee.core.orbit.SolarSystem
import com.rm.apogee.core.terrain.SurfaceMaterial
import org.junit.Assert.assertTrue
import org.junit.Test

/** Every ground on every world has a colour, and layered rock shows its bands. */
class TerrainPaletteTest {
    private val worlds = SolarSystem.defaultSystem().bodies.values.filter { it.terrain != null }.map { it.terrain!!.world }

    @Test
    fun `every ground on every world has a colour`() {
        val out = FloatArray(3)
        for (world in worlds) for (m in SurfaceMaterial.entries) for (h in listOf(-3_000.0, 0.0, 15.0, 4_000.0)) {
            TerrainPalette.colour(m, h, 7, out, 0, world)
            assertTrue("$m on $world", out.all { it.isFinite() && it in 0f..1.2f })
        }
    }

    @Test
    fun `layered rock changes colour from band to band`() {
        val out = FloatArray(3)
        val seen = HashSet<Float>()
        for (k in 0 until 20) { TerrainPalette.colour(SurfaceMaterial.LAYERED_ROCK, k * 31.0, 7, out, 0, "rubra"); seen += out[0] }
        assertTrue("only ${seen.size} tones", seen.size >= 2)
    }
}
