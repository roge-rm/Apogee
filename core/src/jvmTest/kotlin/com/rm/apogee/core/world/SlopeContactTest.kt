package com.rm.apogee.core.world

import com.rm.apogee.core.craft.CraftDesign
import com.rm.apogee.core.craft.PlacedPart
import com.rm.apogee.core.craft.StockCraft
import com.rm.apogee.core.math.Vec3
import com.rm.apogee.core.math.quatFromTo
import com.rm.apogee.core.part.StockParts
import com.rm.apogee.core.terrain.GroundPoint
import com.rm.apogee.core.terrain.TerrainTileCache
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * A craft at rest on a slope rests on it, not in it.
 *
 * The ground turns with the planet (175 m/s at Terra's equator), and contacts were once solved
 * against where it stood at the start of the tick the craft had just moved through, 2.9 m back
 * along the turn. On flat ground that changes nothing. On a slope facing along the turn it's metres
 * up or down, and a pod landed on a mountainside came to rest two metres inside it.
 */
class SlopeContactTest {

    private val catalog = StockParts.catalog
    private val dt = 1.0 / 60.0

    @Test
    fun `a pod set down on a steep east-west slope rests on the surface`() {
        val world = World.default(catalog)
        val terra = world.system.body("terra")
        val terrain = terra.terrain!!
        val rotation = terra.rotationAt(world.time)
        // On the equator, where the ground moves fastest, among the hills where the launch complex
        // first stood.
        val pad = Vec3(1.0, 0.0, 0.0)

        // The steepest east-west slope near there that a pod can still sit on (under the 31 degrees
        // its grip holds), where the lag showed most.
        val east = Vec3(0.0, 1.0, 0.0).crossInPlace(pad).normalizeInPlace().mulInPlace(-1.0)
        val north = Vec3().setTo(pad).crossInPlace(east).normalizeInPlace()
        fun at(x: Double, y: Double) = Vec3().setTo(pad).addScaledInPlace(east, x / terra.radius).addScaledInPlace(north, y / terra.radius).normalizeInPlace()
        var best = pad
        var steepest = 0.0
        for (i in -60..60) for (j in -60..60) {
            val x = i * 150.0; val y = j * 150.0
            if (terrain.elevation(at(x, y)) < 5.0) continue
            val slope = kotlin.math.abs(terrain.elevation(at(x + 5.0, y)) - terrain.elevation(at(x - 5.0, y))) / 10.0
            if (slope in steepest..0.45) { steepest = slope; best = at(x, y) }
        }
        assertTrue("found a slope ($steepest)", steepest > 0.2)

        val pod = CraftDesign(
            name = "Pod",
            parts = listOf(PlacedPart("pod-halo", Vec3.zero())),
            stages = emptyList(),
            catalogHash = catalog.contentHash,
        )
        val up = rotation.rotate(best, Vec3())
        val position = Vec3().setTo(up).mulInPlace(terra.radius + terrain.elevation(best) + 1.5)
        val craft = world.spawnAt(pod, "terra", position, terra.surfaceVelocityAt(position, Vec3()), quatFromTo(Vec3.unitY(), up))
        repeat(60 * 6) { world.step(dt) }

        // The deepest of its contact points under the collider's ground now.
        val ground = GroundPoint()
        val lookup = TerrainTileCache.Lookup()
        val point = Vec3()
        var deepest = -1e9
        for (p in craft.defs.indices) for (c in craft.defs[p].contactPoints.indices) {
            craft.contactPointWorld(p, c, point)
            terra.groundInBodyFrame(terra.toBodyFixed(point, terra.rotationAt(world.time), Vec3()), ground, lookup)
            deepest = maxOf(deepest, ground.radius - point.length)
        }
        assertTrue("on a ${"%.0f".format(Math.toDegrees(kotlin.math.atan(steepest)))} degree slope, it rests on it: deepest ${"%.2f".format(deepest)} m in", deepest < 0.15)
    }
}
