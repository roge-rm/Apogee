package com.rm.apogee.core.terrain

import com.rm.apogee.core.math.Vec3
import com.rm.apogee.core.orbit.SolarSystem
import org.junit.Assert.*
import org.junit.Test

class HarbourTerrainTest {
    private val terrain = SolarSystem.defaultSystem().body("terra").terrain as TerrainField
    @Test fun `harbour has a continuous sea route at high tide with boat clearance`() {
        val size = 301
        val wet = BooleanArray(size * size)
        val dir = Vec3()
        for (y in 0 until size) for (x in 0 until size) {
            wet[y * size + x] = terrain.elevation(terrain.worksDirection(-24_000.0 + x * 140.0, 22_000.0 - y * 160.0, dir)) < -5.5
        }
        val start = kotlin.math.round((22_000.0 - 330) / 160).toInt() * size +
            kotlin.math.round((24_000.0 + 2700) / 140).toInt()
        assertTrue("exact berth has boat clearance", terrain.elevation(terrain.worksDirection(2700.0, 330.0)) < -5.5)
        assertTrue("boat spawn deep enough", wet[start])
        val queue = IntArray(wet.size)
        var read = 0; var count = 1; queue[0] = start; wet[start] = false
        var reachesSea = false
        while (read < count) {
            val k = queue[read++]; val x = k % size; val y = k / size
            if (y == size - 1) reachesSea = true
            for (next in intArrayOf(if (x > 0) k - 1 else -1, if (x < size - 1) k + 1 else -1,
                if (y > 0) k - size else -1, if (y < size - 1) k + size else -1)) {
                if (next >= 0 && wet[next]) { wet[next] = false; queue[count++] = next }
            }
        }
        assertTrue("harbour connects to open sea with 5.5m depth", reachesSea)
    }
    @Test fun `former basin becomes headlands while the launch pad remains level`() {
        assertEquals(15.0, terrain.elevation(terrain.worksDirection(0.0, 0.0)), .01)
        var filled = 0
        for (north in 6000..18000 step 1000) for (east in -18000..0 step 1000) {
            val d = terrain.worksDirection(east.toDouble(), north.toDouble())
            if (terrain.previousElevation(d, 11) < -20.0 && terrain.elevation(d) > 5.0) filled++
        }
        assertTrue("old rounded lake is reshaped into land", filled > 50)
    }
    @Test fun `observed deserts do not spread into nearby wet country`() {
        fun wet(lat: Double, lon: Double) = EarthMoisture.sample(lon, lat)
        assertTrue(wet(23.0, 15.0) < .25)
        assertTrue(wet(20.0, 50.0) < .25)
        assertTrue(wet(0.0, 23.0) > .75)
        assertTrue(wet(8.0, -5.0) > .5)
        assertTrue(wet(-25.0, 135.0) < .3)
        assertEquals(wet(0.0, -180.0), wet(0.0, 180.0), 1e-12)
    }
}
