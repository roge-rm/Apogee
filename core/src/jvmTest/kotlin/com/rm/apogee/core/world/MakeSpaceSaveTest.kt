package com.rm.apogee.core.world
import com.rm.apogee.core.craft.StockCraft
import com.rm.apogee.core.math.Vec3
import com.rm.apogee.core.math.quatFromTo
import com.rm.apogee.core.part.StockParts
import org.junit.Test
import java.io.File
/**
 * A tool. Writes a solo-world save with a Starter I coasting up through 240 km at
 * 1 km/s, for checking things in space. Does nothing without SPACE_SAVE.
 *
 *     SPACE_SAVE=/tmp/solo.json ./gradlew :core:test --tests '*MakeSpaceSaveTest*' --rerun-tasks
 *
 * Copy it over the app's files/world/solo.json and use Resume Flight. NIGHT puts the rocket on the
 * pad at local midnight, or at NIGHT seconds if given (2400 is just before sunset). CRASH=<metres>
 * starts it that high over the pad, tipped over and falling at 60 m/s (or CRASH_SPEED).
 */
class MakeSpaceSaveTest {
    @Test fun make() {
        val out = System.getenv("SPACE_SAVE") ?: return
        val catalog = StockParts.catalog
        val world = World.default(catalog)
        val terra = world.system.body("terra")
        val night = System.getenv("NIGHT")
        if (night != null) {
            // The site turns with Terra from +X and the sun is at x 0.62, z 0.64. Midnight is when
            // the site faces away.
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
        // SEA=wind|storm|surf: boats afloat in that sea, in daylight. A few metres of open-ocean
        // wind sea, a storm sea with a Cutter, Skiff and Trawler, or surf over a beach.
        System.getenv("SEA")?.let { kind ->
            world.weatherConfig = com.rm.apogee.core.weather.WeatherConfig()
            val sun = Vec3(0.62, 0.0, 0.64).normalizeInPlace()
            val sample = com.rm.apogee.core.sea.SeaSample()
            val ocean = terra.ocean!!
            fun dir(i: Int): Vec3 {
                val lat = (com.rm.apogee.core.terrain.Noise.hash(31, i, 0, 0) - 0.5) * 1.4
                val lon = com.rm.apogee.core.terrain.Noise.hash(31, i, 1, 0) * 2 * Math.PI
                return Vec3(kotlin.math.cos(lat) * kotlin.math.cos(lon), kotlin.math.sin(lat), kotlin.math.cos(lat) * kotlin.math.sin(lon))
            }
            var found: Pair<Vec3, Double>? = null
            var t = 6_000.0
            search@ while (t < 600_000.0) {
                for (i in 0 until 3_000) {
                    val d = dir(i)
                    if ((terra.rotationAt(t).rotate(d, Vec3()) dot sun) < 0.4) continue
                    val bed = terra.terrain!!.elevation(d)
                    val ok = when (kind) {
                        "surf" -> bed in -6.0..-2.0
                        else -> bed < -300.0
                    }
                    if (!ok) continue
                    ocean.sample(d, t, sample)
                    val good = when (kind) {
                        "wind" -> sample.significantHeight in 2.0..4.5 && sample.stormHeight < 0.5
                        "storm" -> sample.stormHeight > 7.0 && sample.wind > 12.0
                        "surf" -> sample.significantHeight > 0.8 && sample.depth > 1.0
                        else -> true
                    }
                    if (good) { found = d to t; break@search }
                }
                t += 1_500.0
            }
            val (where, time) = found ?: error("no $kind sea in daylight")
            world.restore(WorldSave(catalogHash = catalog.contentHash, universeTime = time, nextVesselId = 1L, weather = world.weatherConfig))
            val site = LaunchSite("sea", "Sea", "terra", kotlin.math.asin(where.y), kotlin.math.atan2(where.z, where.x))
            world.spawnOnSurface(StockCraft.cutter(catalog), site).name = "Cutter"
            if (kind == "storm" || kind == "surf") world.spawnOnSurface(StockCraft.skiff(catalog), site, pad = 2).name = "Skiff"
            if (kind == "storm") world.spawnOnSurface(StockCraft.trawler(catalog), site, pad = 1).name = "Trawler"
            WorldStore(File(out)).save(world.save()).getOrThrow()
            println("saved to $out: $kind sea, Hs ${"%.1f".format(ocean.sample(where, time, sample).significantHeight)} m, t=$time")
            return
        }
        // DOCK=space|luna|land|water: two craft a few metres apart, ready to dock. Tugs in orbit or
        // on Luna, a buggy and cart, or two skiffs.
        System.getenv("DOCK")?.let { where ->
            fun ref(v: com.rm.apogee.core.craft.Vessel, part: Int) =
                com.rm.apogee.core.physics.PortRef(v, part, v.defs[part].module<com.rm.apogee.core.part.DockingPort>()!!).update()
            fun facing(design: com.rm.apogee.core.craft.CraftDesign, part: Int, target: com.rm.apogee.core.craft.Vessel, targetPart: Int, gap: Double, about: Vec3): com.rm.apogee.core.craft.Vessel {
                val t = ref(target, targetPart)
                val v = world.spawnAt(design, target.referenceBodyId, target.body.position.copy(), target.body.linearVelocity.copy(), target.body.orientation.copy())
                val own = ref(v, part)
                val q = if ((own.axis dot t.axis) > 0.999) com.rm.apogee.core.math.Quat.fromAxisAngle(target.body.orientation.rotate(about, Vec3()), Math.PI, com.rm.apogee.core.math.Quat())
                    else quatFromTo(own.axis, Vec3().setTo(t.axis).mulInPlace(-1.0))
                v.body.orientation.setTo(q * v.body.orientation).normalizeInPlace()
                v.body.position.addInPlace(Vec3().setTo(t.face).addScaledInPlace(t.axis, gap).subInPlace(ref(v, part).face))
                return v
            }
            fun settle(seconds: Double) = repeat((seconds * 60).toInt()) { world.step(1.0 / 60.0) }
            when (where) {
                "space", "luna" -> {
                    val tug = StockCraft.portTug(catalog)
                    val a = if (where == "space") world.spawnInOrbit(tug, "terra", com.rm.apogee.core.orbit.Orbit.circular(700_000.0, 3.5316000e12))
                        else world.spawnOnSurface(tug, World.launchSites.first { it.id == "luna-mare" })
                    a.name = "Port Tug A"
                    if (where == "luna") { repeat(2) { world.stage(a) }; settle(8.0) }
                    val ring = a.defs.indices.first { a.defs[it].id == "dock-port" }
                    val b = facing(tug, ring, a, ring, if (where == "space") 12.0 else 4.0, Vec3.unitY())
                    b.name = "Port Tug B"
                    if (where == "luna") { repeat(2) { world.stage(b) }; settle(8.0) }
                }
                "land" -> {
                    val buggy = world.spawnOnSurface(StockCraft.towBuggy(catalog), World.launchSites.first { it.id == "cape" })
                    settle(4.0)
                    val ball = buggy.defs.indices.first { buggy.defs[it].id == "hitch-ball" }
                    val cart = StockCraft.cart(catalog)
                    facing(cart, cart.parts.indices.first { cart.parts[it].partId == "hitch-coupling" }, buggy, ball, 3.0, buggy.design.orientation.up)
                    settle(4.0)
                }
                "water" -> {
                    val skiff = StockCraft.skiff(catalog)
                    val a = world.spawnOnSurface(skiff, World.launchSites.first { it.id == "harbour" })
                    a.name = "Skiff A"
                    settle(8.0)
                    val right = a.defs.indices.first { a.defs[it].id == "mooring-clamp" && a.design.parts[it].position.x > 0 }
                    val left = skiff.parts.indices.first { skiff.parts[it].partId == "mooring-clamp" && skiff.parts[it].position.x < 0 }
                    facing(skiff, left, a, right, 4.0, Vec3.unitZ()).name = "Skiff B"
                    settle(4.0)
                }
            }
            WorldStore(File(out)).save(world.save()).getOrThrow()
            println("saved to $out: docking at $where, ${world.vessels.size} craft")
            return
        }
        val crash = System.getenv("CRASH")?.toDoubleOrNull()
        // AT=DAWN|NOON|DUSK|MIDNIGHT: the next such time at the pad. AT_PLUS=<seconds> moves it on.
        System.getenv("AT")?.let { at ->
            val design = StockCraft.starterRocket(catalog)
            val probe = world.spawnAtSite(design, World.launchSiteFor(design, catalog))
            val pad = terra.toBodyFixed(probe.body.position, terra.rotationAt(world.time), Vec3()).normalizeInPlace()
            val t = LaunchTime.valueOf(at).nextAt(world.system, terra, pad, 1_000.0) +
                (System.getenv("AT_PLUS")?.toDoubleOrNull() ?: 0.0)
            world.restore(WorldSave(catalogHash = catalog.contentHash, universeTime = t, nextVesselId = 1L))
            println("at $at: t=$t")
        }
        // STORM=day|night|rain: a time when a grown storm is near the pad.
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
                    // STORM_KIND=single|multicell|supercell|squall: that kind, 15 to 45 km away.
                    val kind = System.getenv("STORM_KIND")?.let { com.rm.apogee.core.weather.StormKind.valueOf(it.uppercase()) }
                    val storm = if (kind != null) {
                        val e = Vec3(); val n = Vec3()
                        com.rm.apogee.core.weather.frame(pad, e, n)
                        val list = ArrayList<com.rm.apogee.core.weather.Storms.Storm>()
                        weather.stormModel.around(pad, e, n, 2, t, list)
                        list.firstOrNull { st ->
                            st.kind == kind && weather.stormModel.envelope(st, t) > 0.7 &&
                                weather.stormModel.centreAt(st, t, Vec3()).mulInPlace(terra.radius).distanceTo(here) in 15_000.0..45_000.0
                        }?.let { shapes.firstOrNull() }
                    } else shapes.firstOrNull { shape ->
                        shape.type == com.rm.apogee.core.weather.CloudType.CUMULONIMBUS && shape.amount > 0.7 &&
                            shape.lobes.first().centre.copy().normalizeInPlace().mulInPlace(terra.radius).distanceTo(here) in 12_000.0..30_000.0 &&
                            (want != "rain" || shape.rain.isNotEmpty())
                    }
                    if (storm != null) {
                        found = t
                        // STORM=rain: under its rain instead, not at the pad.
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
            // ROUGH: over the ground near the pad where the coarse drawn mesh strays furthest from
            // the collider's. A number sets the search spacing in metres (200, about 20 km out).
            underRain?.let { spot ->
                // In the rain, a little off the middle of the shaft.
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
                    // A dip under the coarse mesh, drawn above the ground a craft rests on.
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
