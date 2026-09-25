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
 * With CRASH=<metres> instead, the rocket starts that high over the pad,
 * tipped over and falling at 60 m/s (or CRASH_SPEED), for watching a crash
 * and what follows.
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
        val crash = System.getenv("CRASH")?.toDoubleOrNull()
        // AT=DAWN (or NOON, DUSK, MIDNIGHT): the next such time at the pad, for
        // looking at the light - with CRASH, up in the air at that time;
        // AT_PLUS=<seconds> moves it on from there.
        System.getenv("AT")?.let { at ->
            val design = StockCraft.starterRocket(catalog)
            val probe = world.spawnAtSite(design, World.launchSiteFor(design, catalog))
            val pad = terra.toBodyFixed(probe.body.position, terra.rotationAt(world.time), Vec3()).normalizeInPlace()
            // AT_PLUS=<seconds> on from it: an hour after dawn, say.
            val t = LaunchTime.valueOf(at).nextAt(terra, pad, LaunchTime.SUN_DIRECTION, 1_000.0) +
                (System.getenv("AT_PLUS")?.toDoubleOrNull() ?: 0.0)
            world.restore(WorldSave(catalogHash = catalog.contentHash, universeTime = t, nextVesselId = 1L))
            println("at $at: t=$t")
        }
        // STORM=day or night: a time when a grown storm stands near the pad
        // in daylight or in darkness, for looking at storms; with CRASH the
        // rocket is then up in the air at that time.
        var underRain: Vec3? = null
        System.getenv("STORM")?.let { want ->
            val design = StockCraft.starterRocket(catalog)
            val site = World.launchSiteFor(design, catalog)
            val probe = world.spawnAtSite(design, site)
            val pad = terra.toBodyFixed(probe.body.position, terra.rotationAt(0.0), Vec3()).normalizeInPlace()
            val weather = com.rm.apogee.core.weather.Weather(terra, com.rm.apogee.core.weather.WeatherConfig())
            val sun = Vec3(0.62, 0.0, 0.64).normalizeInPlace()
            var t = 600.0
            var found = Double.NaN
            while (t < 400_000.0 && found.isNaN()) {
                val facing = terra.rotationAt(t).rotate(pad, Vec3()) dot sun
                val light = if (want == "night") facing < -0.4 else facing > 0.5
                if (light || (want == "rain" && facing > 0.5)) {
                    val shapes = ArrayList<com.rm.apogee.core.weather.CloudShape>()
                    weather.clouds(pad, 35_000.0, t, shapes)
                    val here = pad.copy().mulInPlace(terra.radius)
                    val storm = shapes.firstOrNull { shape ->
                        shape.type == com.rm.apogee.core.weather.CloudType.CUMULONIMBUS && shape.amount > 0.7 &&
                            shape.lobes.first().centre.copy().normalizeInPlace().mulInPlace(terra.radius).distanceTo(here) in 12_000.0..30_000.0 &&
                            (want != "rain" || shape.rain.isNotEmpty())
                    }
                    if (storm != null) {
                        found = t
                        // STORM=rain: then under its rain, not at the pad.
                        if (want == "rain") underRain = storm.rain.first().centre.copy().normalizeInPlace()
                    }
                }
                t += 300.0
            }
            check(!found.isNaN()) { "no storm near the pad at $want" }
            println("storm near the pad at t=$found ($want)")
            world.restore(WorldSave(catalogHash = catalog.contentHash, universeTime = found, nextVesselId = 1L))
        }
        if (crash != null) {
            val design = StockCraft.starterRocket(catalog)
            val rocket = world.spawnAtSite(design, World.launchSiteFor(design, catalog))
            rocket.name = "Starter I"
            val body = rocket.body
            // ROUGH=1: not over the pad but over the roughest ground within
            // 20 km of it - where coarse drawn ground strays furthest from
            // the collider's - for checking what is drawn under a craft.
            underRain?.let { spot ->
                // In the rain, a little off the shaft's middle.
                val rotation = terra.rotationAt(world.time)
                val radius = terra.radius + maxOf(terra.terrain!!.elevation(spot), 0.0) + 2.0
                rotation.rotate(spot, body.position).mulInPlace(radius)
                terra.surfaceVelocityAt(body.position, body.linearVelocity)
            }
            if (System.getenv("ROUGH") != null) {
                val rotation = terra.rotationAt(world.time)
                val pad = terra.toBodyFixed(body.position, rotation, Vec3()).normalizeInPlace()
                val terrain = terra.terrain!!
                val east = Vec3(0.0, 1.0, 0.0).crossInPlace(pad).normalizeInPlace()
                val north = Vec3().setTo(pad).crossInPlace(east).normalizeInPlace()
                val cell = 7.2 / terra.radius
                fun at(x: Double, y: Double) = Vec3().setTo(pad).addScaledInPlace(east, x).addScaledInPlace(north, y).normalizeInPlace()
                var best = pad; var worst = 0.0
                val spacing = (System.getenv("ROUGH").toDoubleOrNull() ?: 200.0)
                for (i in -100..100) for (j in -100..100) {
                    val x = i * spacing / terra.radius; val y = j * spacing / terra.radius
                    val middle = terrain.elevation(at(x, y))
                    if (middle < (System.getenv("ROUGH_ABOVE")?.toDoubleOrNull() ?: 0.0)) continue
                    val corners = (terrain.elevation(at(x - cell / 2, y - cell / 2)) + terrain.elevation(at(x + cell / 2, y - cell / 2)) +
                        terrain.elevation(at(x - cell / 2, y + cell / 2)) + terrain.elevation(at(x + cell / 2, y + cell / 2))) / 4
                    // A dip under the coarse mesh: drawn above the ground a craft rests on.
                    if (corners - middle > worst) { worst = corners - middle; best = at(x, y) }
                }
                println("roughest dip: ${"%.2f".format(worst)} m below the coarse mesh at elevation ${terrain.elevation(best).toInt()} m")
                val radius = terra.radius + terrain.elevation(best) + 3.0
                rotation.rotate(best, body.position).mulInPlace(radius)
                terra.surfaceVelocityAt(body.position, body.linearVelocity)
            }
            val up = body.position.normalized()
            val east = terra.surfaceVelocityAt(body.position, Vec3()).normalized()
            body.position.addScaledInPlace(up, crash)
            terra.surfaceVelocityAt(body.position, body.linearVelocity).addScaledInPlace(up, -(System.getenv("CRASH_SPEED")?.toDoubleOrNull() ?: 60.0))
            // UPRIGHT=1: standing straight, for looking round from the pad.
            val tip = if (System.getenv("UPRIGHT") != null) 0.0 else 0.6
            body.orientation.setTo(quatFromTo(Vec3.unitY(), up.copy().addScaledInPlace(east, tip).normalized()))
            WorldStore(File(out)).save(world.save()).getOrThrow()
            println("saved to $out, falling from ${crash} m")
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
