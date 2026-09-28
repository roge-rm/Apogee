package com.rm.apogee.core.world

import com.rm.apogee.core.math.Vec3
import com.rm.apogee.core.part.StockParts
import org.junit.Test
import java.io.File

/**
 * A tool, not a test. PROBE_SAVE=<save file> replays it, stages the first craft four times (down to
 * the pod on its chute) and prints how it lands: the height of its centre of mass above the ground,
 * its speed, and its contacts.
 */
class LandingProbeTest {
    @Test fun probe() {
        val path = System.getenv("PROBE_SAVE") ?: return
        val world = World.default(StockParts.catalog)
        world.restore(WorldStore(File(path)).load().getOrThrow())
        val dt = 1.0 / 60.0
        var craft = world.vessels.first()
        repeat(4) { world.stage(craft); repeat(20) { world.step(dt) }; craft = world.vessels.filter { v -> v.defs.any { it.module<com.rm.apogee.core.part.Command>() != null } }.minBy { it.defs.size } }
        val terra = world.attractorFor(craft)
        for (tick in 0 until 60 * 150) {
            world.step(dt)
            craft = world.vessels.filter { v -> v.defs.any { it.module<com.rm.apogee.core.part.Command>() != null } }.minByOrNull { it.defs.size } ?: break
            if (tick % 60 == 0) {
                val fixed = terra.toBodyFixed(craft.body.position, terra.rotationAt(world.time), Vec3())
                val h = terra.heightAboveTerrain(craft.body.position, fixed)
                val v = Vec3().setTo(craft.body.linearVelocity).subInPlace(terra.surfaceVelocityAt(craft.body.position, Vec3()))
                val chute = craft.defs.indices.filter { craft.defs[it].module<com.rm.apogee.core.part.Parachute>() != null }.map { "%.2f".format(craft.legDeploy[it]) }
                val gp = com.rm.apogee.core.terrain.GroundPoint()
                val look = com.rm.apogee.core.terrain.TerrainTileCache.Lookup()
                terra.groundInBodyFrame(fixed, gp, look)
                val colliderVsField = gp.radius - terra.terrain!!.surfaceRadius(fixed)
                var deepest = -1e9
                var deepAt = ""
                val point = Vec3()
                for (pi in craft.defs.indices) for (ci in craft.defs[pi].contactPoints.indices) {
                    craft.contactPointWorld(pi, ci, point)
                    val f = terra.toBodyFixed(point, terra.rotationAt(world.time), Vec3())
                    terra.groundInBodyFrame(f, gp, look)
                    val d = gp.radius - point.length
                    if (d > deepest) { deepest = d; deepAt = "${craft.defs[pi].id}#$ci" }
                }
                println("t ${tick / 60}s  h ${"%.2f".format(h)}  collider-field ${"%.2f".format(colliderVsField)}  deepest contact ${"%.2f".format(deepest)} $deepAt  v ${"%.1f".format(v.length)}  ground ${craft.touchingGround}/${craft.groundContacts}  chute $chute  parts ${craft.defs.size}")
            }
        }
    }
}
