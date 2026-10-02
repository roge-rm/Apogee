package com.rm.apogee.core.terrain

import com.rm.apogee.core.math.Vec3
import com.rm.apogee.core.orbit.SolarSystem
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** The close-up relief leaves every test site's ground as it was, and nothing is scattered on the pads. */
class SitesStayFlatTest {
    private val system = SolarSystem.defaultSystem()

    /** Points round [site] out to [reach] metres, on a grid [step] apart. */
    private fun around(site: Worlds.Site, reach: Double, step: Double): List<Vec3> {
        val terrain = system.body(site.bodyId).terrain!!
        val up = SolarSystem.surfaceDirection(site.latitude, site.longitude)
        val east = Vec3(0.0, 1.0, 0.0).crossInPlace(up).normalizeInPlace()
        val north = up.cross(east).normalizeInPlace()
        val out = ArrayList<Vec3>()
        var e = -reach
        while (e <= reach) {
            var n = -reach
            while (n <= reach) {
                if (e * e + n * n <= reach * reach) out += Vec3().setTo(up).mulInPlace(terrain.bodyRadius)
                    .addScaledInPlace(east, e).addScaledInPlace(north, n).normalizeInPlace()
                n += step
            }
            e += step
        }
        return out
    }

    private fun bare(body: String, d: Vec3): Double = when (val t = system.body(body).terrain) {
        is WorldField -> t.withoutDetail(d)
        is TerrainField -> t.lunaWithoutDetail(d)
        else -> error("no ground on $body")
    }

    @Test
    fun `round every test site the ground is just as it was`() {
        for (site in Worlds.SITES) {
            val terrain = system.body(site.bodyId).terrain!!
            for (d in around(site, Worlds.SITE_FLAT, 25.0)) assertEquals(site.id, bare(site.bodyId, d), terrain.elevation(d), 0.0)
        }
    }

    @Test
    fun `nothing is scattered on a test site's pads`() {
        for (site in Worlds.SITES) {
            val terrain = system.body(site.bodyId).terrain!!
            for (d in around(site, 250.0, 25.0)) assertTrue(site.id, terrain.isKeptClear(d))
        }
    }
}
