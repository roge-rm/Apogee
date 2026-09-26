package com.rm.apogee.core.world

import com.rm.apogee.core.craft.StockCraft
import com.rm.apogee.core.math.Quat
import com.rm.apogee.core.math.Vec3
import com.rm.apogee.core.math.quatFromTo
import com.rm.apogee.core.orbit.SolarSystem
import com.rm.apogee.core.part.StockParts
import com.rm.apogee.core.weather.WeatherConfig
import org.junit.Test
import java.io.File

class SceneSaves {
    @Test
    fun make() {
        val dir = System.getenv("SCENE_DIR") ?: return
        val c = StockParts.catalog
        // Late morning at the Cape.
        val pad = SolarSystem.surfaceDirection(SolarSystem.PAD_LATITUDE, SolarSystem.PAD_LONGITUDE)
        val sun = Vec3(0.62, 0.0, 0.64).normalizeInPlace()
        val probe = World.default(c)
        val terra = probe.system.body("terra")
        var t = 0.0
        while ((terra.rotationAt(t).rotate(pad, Vec3()) dot sun) < 0.85) t += 30.0

        fun world(weather: WeatherConfig = WeatherConfig()): World = World.default(c).also {
            it.restore(WorldSave(catalogHash = c.contentHash, universeTime = t, nextVesselId = 1L, weather = weather))
        }
        // On the pad.
        run {
            val w = world()
            w.spawnOnSurface(StockCraft.starterRocket(c), World.launchSites.first { it.id == "cape" }).name = "Starter I"
            WorldStore(File(dir, "pad.json")).save(w.save()).getOrThrow()
        }
        // A plane at 300 m, 120 m/s, heading inland over the bay and on east.
        run {
            val w = world()
            val up = w.system.body("terra").rotationAt(t).rotate(pad, Vec3())
            val east = Vec3(0.0, 1.0, 0.0).crossInPlace(up).normalizeInPlace()
            val position = up.copy().mulInPlace(terra.radius + 300.0).addScaledInPlace(east, 3_000.0)
            val velocity = terra.surfaceVelocityAt(position, Vec3()).addScaledInPlace(east, 120.0)
            val design = StockCraft.sparrow(c)
            val level = quatFromTo(design.orientation.up, up)
            val nose = level.rotate(design.orientation.forward, Vec3())
            val rotation = quatFromTo(nose, east) * level
            val plane = w.spawnAt(design, "terra", position, velocity, rotation)
            plane.name = "Sparrow"
            w.stage(plane)
            plane.control.throttle = 0.7
            plane.control.sasEnabled = true
            WorldStore(File(dir, "plane.json")).save(w.save()).getOrThrow()
        }
        // A boat in the harbour.
        run {
            val w = world()
            w.spawnOnSurface(StockCraft.boat(c), World.launchSites.first { it.id == "harbour" }).name = "Boat"
            WorldStore(File(dir, "harbour.json")).save(w.save()).getOrThrow()
        }
        // A plane at 500 m over the harbour's bay, heading north for the inlet, under a clear sky.
        run {
            val w = world(WeatherConfig(clouds = com.rm.apogee.core.weather.CloudCover.LIGHT))
            val harbour = SolarSystem.surfaceDirection(SolarSystem.HARBOUR_LATITUDE, SolarSystem.HARBOUR_LONGITUDE)
            val up = w.system.body("terra").rotationAt(t).rotate(harbour, Vec3())
            val east = Vec3(0.0, 1.0, 0.0).crossInPlace(up).normalizeInPlace()
            val north = up.copy().crossInPlace(east)
            val position = up.copy().mulInPlace(terra.radius + 500.0).addScaledInPlace(north, -4_500.0)
            val velocity = terra.surfaceVelocityAt(position, Vec3()).addScaledInPlace(north, 110.0)
            val design = StockCraft.sparrow(c)
            val level = quatFromTo(design.orientation.up, up)
            val nose = level.rotate(design.orientation.forward, Vec3())
            val plane = w.spawnAt(design, "terra", position, velocity, quatFromTo(nose, north) * level)
            plane.name = "Sparrow"
            w.stage(plane)
            plane.control.throttle = 0.7
            plane.control.sasEnabled = true
            WorldStore(File(dir, "overbay.json")).save(w.save()).getOrThrow()
        }
        // A plane on the airfield, at the runway's end.
        run {
            val w = world(WeatherConfig(clouds = com.rm.apogee.core.weather.CloudCover.LIGHT))
            w.spawnOnSurface(StockCraft.sparrow(c), World.launchSites.first { it.id == "airfield" }).name = "Sparrow"
            WorldStore(File(dir, "airfield.json")).save(w.save()).getOrThrow()
        }
        // A plane at 250 m over the Cape, flying east down the runway toward the harbour.
        run {
            val w = world(WeatherConfig(clouds = com.rm.apogee.core.weather.CloudCover.LIGHT))
            val start = SolarSystem.capeDirection(-2_600.0, -150.0)
            val up = w.system.body("terra").rotationAt(t).rotate(start, Vec3())
            val east = Vec3(0.0, 1.0, 0.0).crossInPlace(up).normalizeInPlace()
            val position = up.copy().mulInPlace(terra.radius + 1_500.0)
            val velocity = terra.surfaceVelocityAt(position, Vec3()).addScaledInPlace(east, 100.0)
            val design = StockCraft.sparrow(c)
            val level = quatFromTo(design.orientation.up, up)
            val nose = level.rotate(design.orientation.forward, Vec3())
            val plane = w.spawnAt(design, "terra", position, velocity, quatFromTo(nose, east) * level)
            plane.name = "Sparrow"
            w.stage(plane)
            plane.control.throttle = 0.6
            plane.control.sasEnabled = true
            WorldStore(File(dir, "overcape.json")).save(w.save()).getOrThrow()
        }
        // A base core on a flatbed at the pads, ready to set down.
        run {
            val w = world(WeatherConfig(clouds = com.rm.apogee.core.weather.CloudCover.LIGHT))
            w.spawnOnSurface(StockCraft.baseCoreHauler(c), World.launchSites.first { it.id == "cape" }, pad = 4)
            WorldStore(File(dir, "hauler.json")).save(w.save()).getOrThrow()
        }
        // The airfield at night: half a day on from the others.
        run {
            val w = World.default(c).also {
                it.restore(WorldSave(catalogHash = c.contentHash, universeTime = t + 10_775.0, nextVesselId = 1L,
                    weather = WeatherConfig(clouds = com.rm.apogee.core.weather.CloudCover.LIGHT)))
            }
            w.spawnOnSurface(StockCraft.sparrow(c), World.launchSites.first { it.id == "airfield" }).name = "Sparrow"
            WorldStore(File(dir, "night.json")).save(w.save()).getOrThrow()
        }
        // A founded pad base beside the Cape's pads, and a half-empty lander launched from it.
        run {
            val w = world(WeatherConfig(clouds = com.rm.apogee.core.weather.CloudCover.LIGHT))
            val pad = w.spawnOnSurface(StockCraft.padBase(c), World.launchSites.first { it.id == "cape" }, pad = 6)
            pad.owner = "scene"
            repeat(240) { w.step(1.0 / 60) }
            check(w.anchor(pad)) { "the pad would not found" }
            val site = w.baseSites("scene").single()
            val lander = w.spawnFor(Command.SpawnCraft(StockCraft.lander(c), site.id), "scene")
            lander.takeFrom(lander.defs.indices.toList(), com.rm.apogee.core.part.ResourceType.PROPELLANT,
                lander.amountOf(com.rm.apogee.core.part.ResourceType.PROPELLANT) / 2)
            repeat(120) { w.step(1.0 / 60) }
            WorldStore(File(dir, "padbase.json")).save(w.save()).getOrThrow()
        }
        // A rocket nine kilometres up over the pads, climbing, at the day's highest tide there.
        run {
            val sea = com.rm.apogee.core.sea.Sea(terra, probe.system.body("luna"), null, 0)
            val padGround = SolarSystem.capeDirection(-600.0, 0.0)
            var high = t
            var best = -1e9
            // The morning's high water, in daylight.
            var s = t - 7_000.0
            while (s < t + 1_000.0) {
                val h = sea.tides.height(padGround, s, 1.0)
                if (h > best) { best = h; high = s }
                s += 60.0
            }
            val w = World.default(c).also {
                it.restore(WorldSave(catalogHash = c.contentHash, universeTime = high, nextVesselId = 1L,
                    weather = WeatherConfig(clouds = com.rm.apogee.core.weather.CloudCover.LIGHT)))
            }
            val up = terra.rotationAt(high).rotate(pad, Vec3())
            val position = up.copy().mulInPlace(terra.radius + 9_000.0)
            val velocity = terra.surfaceVelocityAt(position, Vec3()).addScaledInPlace(up, 380.0)
            val design = StockCraft.starterRocket(c)
            val rocket = w.spawnAt(design, "terra", position, velocity, quatFromTo(Vec3(0.0, 1.0, 0.0), up))
            rocket.name = "Starter I"
            w.stage(rocket)
            rocket.control.throttle = 1.0
            WorldStore(File(dir, "high.json")).save(w.save()).getOrThrow()
            println("high tide ${"%.2f".format(best)} m at ${high}")
        }
        println("scenes saved at t=$t")
    }
}
