package com.rm.apogee.core.scenario

import com.rm.apogee.core.craft.CraftDesign
import com.rm.apogee.core.craft.StockCraft
import com.rm.apogee.core.math.Vec3
import com.rm.apogee.core.part.StockParts
import com.rm.apogee.core.world.World

/**
 * How still a craft really gets once it has settled.
 *
 * The anchoring rule can only hold a craft whose leftover motion is under one tick's worth of
 * friction, so this prints what each reference craft really leaves behind. `./gradlew
 * :core:restSurvey`
 */
fun main() {
    val catalog = StockParts.catalog
    val dt = 1.0 / 60.0
    val budget = 0.6 * 9.81 * dt

    println("one tick of friction can cancel %.4f m/s".format(budget))
    println("%-12s %10s %12s  %s".format("craft", "worst v", "height drift", "state"))

    for ((name, design, gearStages) in listOf(
        Triple("lander", StockCraft.lander(catalog), 3),
        Triple("moduleTug", StockCraft.moduleTug(catalog), 3),
        Triple("lander/gearup", StockCraft.lander(catalog), 0),
        Triple("starter", StockCraft.starterRocket(catalog), 0),
    )) {
        val world = World.default(catalog)
        val vessel = world.spawnOnSurface(design, World.launchSites.first())
        // Gear down for the craft that have it. Not for the starter, because its second stage is a
        // decoupler, and firing that on the pad is a separation, not a landing.
        if (gearStages > 0) repeat(gearStages) { world.stage(vessel) }

        val surface = Vec3()
        val attractor = world.attractorFor(vessel)
        var worst = 0.0
        var settledAgl = 0.0
        var startAgl = 0.0
        repeat(1_800) { tick ->
            world.step(dt)
            val rotation = attractor.rotationAt(world.time)
            val bodyFixed = attractor.toBodyFixed(vessel.body.position, rotation, Vec3())
            val agl = vessel.body.position.length -
                attractor.surfaceRadiusInBodyFrame(bodyFixed)
            if (tick == 300) startAgl = agl
            if (tick > 300) {
                settledAgl = agl
                if (!vessel.dormant) {
                    attractor.surfaceVelocityAt(vessel.body.position, surface)
                    val rel = Vec3().setTo(vessel.body.linearVelocity).subInPlace(surface)
                    if (rel.length > worst) worst = rel.length
                }
            }
        }
        // Height is the honest measure. A craft in equilibrium reads one tick of gravity as
        // "velocity" whatever it's really doing, so what matters is whether it's actually going
        // anywhere.
        println(
            "%-12s %10.4f %12.5f  %s".format(
                name, worst, settledAgl - startAgl,
                if (vessel.dormant) "asleep" else "awake",
            )
        )
    }
}
