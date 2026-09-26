package com.rm.apogee.core.world

import com.rm.apogee.core.craft.CraftDesign
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
        // The pads at night, a rocket on pad 0 under the floodlights.
        run {
            val w = World.default(c).also {
                it.restore(WorldSave(catalogHash = c.contentHash, universeTime = t + 10_775.0, nextVesselId = 1L,
                    weather = WeatherConfig(clouds = com.rm.apogee.core.weather.CloudCover.LIGHT)))
            }
            w.spawnOnSurface(StockCraft.starterRocket(c), World.launchSites.first { it.id == "cape" }).name = "Starter I"
            WorldStore(File(dir, "padnight.json")).save(w.save()).getOrThrow()
        }
        // In a 100 km orbit about Terra, in Luna's plane, a transfer to Luna planned five minutes on.
        run {
            val w = world()
            val system = w.system
            val luna = system.body("luna")
            val mu = terra.gravitationalParameter
            val r1 = terra.radius + 100_000.0
            val r2 = luna.orbit!!.semiMajorAxis + 800_000.0
            val a = 0.5 * (r1 + r2)
            val flight = Math.PI * kotlin.math.sqrt(a * a * a / mu)
            val lead = 300.0
            val departs = w.time + lead
            val normal = luna.orbit!!.angularMomentum.normalized()
            val departure = luna.orbit!!.stateAt(departs + flight).position.normalized().negateInPlace()
            val position = Quat.fromAxisAngle(normal, -lead * kotlin.math.sqrt(mu / r1) / r1).rotate(departure).mulInPlace(r1)
            val velocity = normal.cross(position).normalizeInPlace().mulInPlace(kotlin.math.sqrt(mu / r1))
            val design = StockCraft.lander(c)
            val craft = w.spawnAt(design, "terra", position, velocity, quatFromTo(design.orientation.forward, velocity.normalized()))
            craft.name = "Stilt Lander"
            w.stage(craft)
            val boost = kotlin.math.sqrt(mu * (2.0 / r1 - 1.0 / a)) - kotlin.math.sqrt(mu / r1)
            w.apply(Command.PlanBurns(craft.id.raw, listOf(PlannedBurn(departs, prograde = boost))))
            w.apply(Command.SetTarget(craft.id.raw, -1L, "luna"))
            WorldStore(File(dir, "transfer.json")).save(w.save()).getOrThrow()
        }
        // In a 30 km orbit about Luna, over the mare, engine lit: to come down on it.
        run {
            val w = world()
            val luna = w.system.body("luna")
            val mare = World.launchSites.first { it.id == "luna-mare" }
            val over = luna.rotationAt(w.time).rotate(SolarSystem.surfaceDirection(mare.latitude, mare.longitude))
            val r = luna.radius + 30_000.0
            val position = over.copy().mulInPlace(r)
            // Eastward, so it passes over the mare: along the turn of the ground.
            val east = Vec3(0.0, 1.0, 0.0).crossInPlace(over).normalizeInPlace()
            val velocity = east.mulInPlace(luna.circularVelocityAt(r))
            val design = StockCraft.lander(c)
            val craft = w.spawnAt(design, "luna", position, velocity, quatFromTo(design.orientation.forward, velocity.normalized()))
            craft.name = "Stilt Lander"
            w.stage(craft)
            WorldStore(File(dir, "lunaorbit.json")).save(w.save()).getOrThrow()
        }
        // Three kilometres over the mare, falling and drifting, the engine lit and the legs next.
        run {
            val w = world()
            val craft = w.spawnOnSurface(StockCraft.lander(c), World.launchSites.first { it.id == "luna-mare" }, pad = 3)
            craft.name = "Stilt Lander"
            w.stage(craft); w.stage(craft)
            val luna = w.attractorFor(craft)
            craft.wake()
            val up = craft.body.position.copy().normalizeInPlace()
            val east = Vec3(0.0, 1.0, 0.0).crossInPlace(up).normalizeInPlace()
            craft.body.position.addScaledInPlace(up, 3_000.0)
            luna.surfaceVelocityAt(craft.body.position, craft.body.linearVelocity).addScaledInPlace(up, -40.0).addScaledInPlace(east, 60.0)
            WorldStore(File(dir, "lunafall.json")).save(w.save()).getOrThrow()
        }
        // A pod on a Shroud in a 100 km orbit: stage it and watch the shell fall open.
        run {
            val w = world()
            val design = CraftDesign(
                name = "Shrouded Pod",
                parts = listOf(
                    com.rm.apogee.core.craft.PlacedPart("pod-halo", Vec3(0.0, 1.9, 0.0)),
                    com.rm.apogee.core.craft.PlacedPart("adapter-taper", Vec3(0.0, 0.7, 0.0), parentIndex = 0),
                    com.rm.apogee.core.craft.PlacedPart("fairing-base", Vec3(0.0, 0.0, 0.0), parentIndex = 1),
                ),
                stages = listOf(com.rm.apogee.core.craft.Stage(listOf(2))),
                manualStaging = true,
                catalogHash = c.contentHash,
            )
            val r = terra.radius + 100_000.0
            val up = w.system.body("terra").rotationAt(t).rotate(pad, Vec3())
            val east = Vec3(0.0, 1.0, 0.0).crossInPlace(up).normalizeInPlace()
            val velocity = east.copy().mulInPlace(terra.circularVelocityAt(r))
            w.spawnAt(design, "terra", up.copy().mulInPlace(r), velocity, quatFromTo(Vec3.unitY(), east)).name = "Shrouded Pod"
            WorldStore(File(dir, "shroud.json")).save(w.save()).getOrThrow()
        }
        // The Moonshot on the pad.
        run {
            val w = world()
            w.spawnOnSurface(StockCraft.moonshot(c), World.launchSites.first { it.id == "cape" }).name = "Moonshot"
            WorldStore(File(dir, "moonshot.json")).save(w.save()).getOrThrow()
        }
        // A base core lander on Luna's mare, ready to fly.
        run {
            val w = world()
            w.spawnOnSurface(StockCraft.baseCoreLander(c), World.launchSites.first { it.id == "luna-mare" }, pad = 2).name = "Base Core Lander"
            WorldStore(File(dir, "lunalander.json")).save(w.save()).getOrThrow()
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
        // A lander launched from Luna's test base.
        run {
            val w = world(WeatherConfig(clouds = com.rm.apogee.core.weather.CloudCover.LIGHT))
            w.ensureStructures()
            val site = w.baseSites("scene").single { it.bodyId == "luna" }
            w.spawnFor(Command.SpawnCraft(StockCraft.lander(c), site.id), "scene")
            repeat(120) { w.step(1.0 / 60) }
            WorldStore(File(dir, "luna.json")).save(w.save()).getOrThrow()
        }
        println("scenes saved at t=$t")
    }
}
