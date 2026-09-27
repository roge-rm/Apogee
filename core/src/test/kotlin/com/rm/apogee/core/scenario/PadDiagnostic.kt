package com.rm.apogee.core.scenario

import com.rm.apogee.core.craft.StockCraft
import com.rm.apogee.core.part.StockParts
import com.rm.apogee.core.world.World

/** A throwaway probe: what does an unpowered craft do sitting on the pad? */
fun main() {
    val catalog = StockParts.catalog
    val world = World.default(catalog)
    val vessel = world.spawnOnSurface(StockCraft.starterRocket(catalog), World.launchSites.first())
    val terra = world.attractorFor(vessel)

    val startAltitude = terra.altitudeOf(vessel.body.position)
    println("spawn altitude = %.4f m".format(startAltitude))

    repeat(600) { step ->
        world.step(1.0 / 60.0)
        if (step % 60 == 0 || step < 5) {
            val altitude = terra.altitudeOf(vessel.body.position)
            val surfaceVelocity = terra.surfaceVelocityAt(vessel.body.position)
            val relative = vessel.body.linearVelocity - surfaceVelocity
            println(
                "t=%5.2f alt=%10.4f  drift=%8.4f  vrel=%8.4f  omega=%8.5f".format(
                    world.time, altitude, altitude - startAltitude,
                    relative.length, vessel.body.angularVelocity.length,
                )
            )
        }
    }
}
