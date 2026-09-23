package com.rm.apogee.core.terrain

import com.rm.apogee.core.math.Vec3
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.sin

/**
 * The ground's plumbing: deterministic noise, the cube-sphere grid, and tiles
 * that reproduce the field they were sampled from.
 */
class TerrainFoundationTest {

    private val terra = TerrainField(bodyRadius = 600_000.0, homeDirection = Vec3(1.0, 0.0, 0.0))

    private fun probe(i: Int) = Vec3(cos(i * 0.9), sin(i * 0.4), cos(i * 1.7))

    /**
     * Exact bit patterns, pinned. The server is a JVM and the client is ART,
     * and they must agree on the ground to the last bit or a resting craft
     * jitters between their two opinions of it. These change only when the
     * terrain is meant to change - which bumps [TerrainField.GENERATION].
     */
    @Test
    fun `terrain is bit-for-bit what it was`() {
        assertEquals("regenerate these goldens deliberately", 2, TerrainField.GENERATION)
        val golden = longArrayOf(
            -4578718847118141824, 4633304516215518119, -4576147226361897366,
            -4606504763289932561, 4640841390928657255, 4651358863925320039,
            -4575409305962809158, -4586221167063854346, -4580391478072772684,
            -4588772863256232892, 4644246798824301635, -4583983049493818662,
        )
        golden.forEachIndexed { i, bits ->
            assertEquals("sample $i", bits, java.lang.Double.doubleToRawLongBits(terra.elevation(probe(i))))
        }
    }

    @Test
    fun `simplex noise is deterministic and roughly unit range`() {
        var low = 0.0
        var high = 0.0
        for (i in 0 until 20_000) {
            val x = i * 0.137; val y = i * 0.071; val z = i * 0.029
            val v = Noise.simplex(7, x, y, z)
            assertEquals(v, Noise.simplex(7, x, y, z), 0.0)
            low = minOf(low, v); high = maxOf(high, v)
        }
        assertTrue("range $low..$high", low < -0.7 && high > 0.7 && low >= -1.05 && high <= 1.05)
        assertTrue(Noise.simplex(7, 1.3, 2.1, 0.4) != Noise.simplex(8, 1.3, 2.1, 0.4))
    }

    @Test
    fun `the cube sphere round-trips every direction`() {
        val coords = Vec3()
        val back = Vec3()
        for (i in 0 until 5_000) {
            val d = probe(i).normalizeInPlace()
            val face = CubeSphere.locate(d, coords)
            CubeSphere.direction(face, coords.x, coords.y, back)
            assertTrue("direction $i", back.distanceTo(d) < 1e-12)
            assertTrue(abs(coords.x) <= 1.0 + 1e-12 && abs(coords.y) <= 1.0 + 1e-12)
        }
    }

    @Test
    fun `tiles are about the size they are meant to be`() {
        val tiles = TerrainTile.tilesPerFace(600_000.0)
        val metres = 600_000.0 * Math.PI / 2.0 / tiles
        assertTrue("tiles are $metres m", metres in 64.0..TerrainTile.TARGET_TILE_METRES)
    }

    /** At every sample, a tile is exactly the field; between them, a triangle. */
    @Test
    fun `a tile reproduces the field at its samples`() {
        val cache = terra.tiles
        val point = GroundPoint()
        val lookup = TerrainTileCache.Lookup()
        val coords = Vec3()
        val home = Vec3(1.0, 0.0, 0.0)
        val face = CubeSphere.locate(home, coords)
        val n = cache.tilesPerFace
        val i = ((coords.x + 1.0) * 0.5 * n).toInt()
        val j = ((coords.y + 1.0) * 0.5 * n).toInt()
        val tile = cache.tile(face, i, j)
        val direction = Vec3()
        for (q in 0..TerrainTile.CELLS step 7) for (p in 0..TerrainTile.CELLS step 7) {
            tile.position(p, q, terra.bodyRadius, direction)
            val expected = terra.solidRadius(direction)
            // Nudged a hair into the tile so the sample is unambiguously this tile's.
            cache.ground(direction, point, lookup)
            assertEquals("sample $p,$q", expected, point.radius, 1e-3)
        }
    }

    /** No step where two tiles - or two cube faces - meet. */
    @Test
    fun `the surface is continuous across tile and face seams`() {
        val cache = terra.tiles
        val a = GroundPoint(); val b = GroundPoint()
        val lookup = TerrainTileCache.Lookup()
        val n = cache.tilesPerFace
        val out = Vec3()
        // Along a tile boundary on face 0, and along the edge between faces 0 and 2.
        for (k in 0 until 200) {
            val t = -0.3 + k * 0.003
            val seam = -1.0 + 2.0 * (n / 2) / n.toDouble()
            val eps = 1e-9
            CubeSphere.direction(0, seam - eps, t, out); cache.ground(out, a, lookup)
            CubeSphere.direction(0, seam + eps, t, out); cache.ground(out, b, lookup)
            assertEquals("tile seam at $t", a.radius, b.radius, 0.01)
        }
        for (k in 0 until 200) {
            val t = -0.5 + k * 0.005
            CubeSphere.direction(0, 1.0 - 1e-9, t, out); cache.ground(out, a, lookup)
            val d = out.copy()
            val coords = Vec3()
            val otherFace = CubeSphere.locate(d.addInPlace(Vec3(0.0, 1e-7, 0.0)).normalizeInPlace(), coords)
            cache.ground(d, b, lookup)
            assertTrue(otherFace == 0 || otherFace == 2)
            assertEquals("face seam at $t", a.radius, b.radius, 0.05)
        }
    }

    @Test
    fun `the ground knows what it is made of`() {
        val point = GroundPoint()
        val lookup = TerrainTileCache.Lookup()
        // The pad grips as all ground used to - whichever of grass or packed
        // earth the country around it happens to be.
        terra.tiles.ground(Vec3(1.0, 0.0, 0.0), point, lookup)
        assertEquals(0.6, point.material.friction, 0.0)
    }

    /**
     * No steps in the ground. A step is a wall a wheel cannot climb and the
     * collider cannot resolve sensibly - and every one found while building
     * the landforms came from a single cause: something switched on or off
     * at a threshold rather than fading. Scanned at half-metre spacing across
     * the country around the launch complex.
     */
    @Test
    fun `the terrain has no steps`() {
        val r = 600_000.0
        for (row in -8..8) for (col in -8..8) {
            val north = row * 9_000.0 + 1_234.0
            val east0 = col * 9_000.0
            var previous = terra.elevation(Vec3(1.0, north / r, east0 / r))
            var m = 0.0
            while (m < 400.0) {
                m += 0.5
                val v = terra.elevation(Vec3(1.0, north / r, (east0 + m) / r))
                assertTrue(
                    "a %.1f m step at north %.0f, east %.0f".format(abs(v - previous), north, east0 + m),
                    abs(v - previous) < 3.0,
                )
                previous = v
            }
        }
    }
}
