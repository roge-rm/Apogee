package com.rm.apogee.core.world
import com.rm.apogee.core.craft.StockCraft
import com.rm.apogee.core.math.Vec3
import com.rm.apogee.core.math.quatFromTo
import com.rm.apogee.core.part.StockParts
import org.junit.Test
import java.io.File
/**
 * Not a test: a tool. Writes a solo-world save with a Starter I coasting up
 * through 240 km at a kilometre a second - somewhere a tap-driven emulator
 * cannot fly to - for checking staging, warp and the like in space.
 *
 *   SPACE_SAVE=/tmp/solo.json ./gradlew :core:test --tests '*MakeSpaceSaveTest*' --rerun-tasks
 *
 * then copy it over the app's files/world/solo.json and Resume Flight.
 * With NIGHT set as well, the rocket stands on the pad instead, with the
 * clock moved on until the launch site is at local midnight, for checking
 * how things look after dark - or, given a number of seconds, to that time
 * instead: NIGHT=2400 is just before sunset.
 * Does nothing without SPACE_SAVE set.
 */
class MakeSpaceSaveTest {
    @Test fun make() {
        val out = System.getenv("SPACE_SAVE") ?: return
        val catalog = StockParts.catalog
        val world = World.default(catalog)
        val terra = world.system.body("terra")
        val night = System.getenv("NIGHT")
        if (night != null) {
            // The site turns with Terra from +X; the sun sits a little north of
            // the equator at x 0.62, z 0.64. Midnight is when the site faces away.
            val away = kotlin.math.atan2(0.64, -0.62)
            val midnight = night.toDoubleOrNull()?.takeIf { it > 1.0 }
                ?: ((away + 2 * kotlin.math.PI) % (2 * kotlin.math.PI)) / (2 * kotlin.math.PI) * terra.rotationPeriod
            world.restore(WorldSave(catalogHash = catalog.contentHash, universeTime = midnight, nextVesselId = 1L))
            val design = StockCraft.starterRocket(catalog)
            world.spawnAtSite(design, World.launchSiteFor(design, catalog)).name = "Starter I"
            WorldStore(File(out)).save(world.save()).getOrThrow()
            println("saved to $out at t=$midnight")
            return
        }
        val up = Vec3(1.0, 0.0, 0.0)
        val position = up.copy().mulInPlace(terra.radius + 240_000.0)
        val velocity = terra.surfaceVelocityAt(position, Vec3()).addScaledInPlace(up, 1_000.0)
        val rocket = world.spawnAt(StockCraft.starterRocket(catalog), "terra", position, velocity, quatFromTo(Vec3.unitY(), up))
        rocket.name = "Starter I"
        WorldStore(File(out)).save(world.save()).getOrThrow()
        println("saved to $out")
    }
}
