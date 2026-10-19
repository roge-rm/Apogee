package com.rm.apogee.core.weather

import com.rm.apogee.core.craft.StockCraft
import com.rm.apogee.core.part.StockParts
import com.rm.apogee.core.world.World
import com.rm.apogee.core.world.LaunchSite
import kotlinx.serialization.json.Json
import org.junit.Test
import java.io.File
import kotlin.math.asin
import kotlin.math.atan2

/** Writes Fire timing saves at the event locations found by PerfScenesScratchTest.scenes. */
class FireScenesScratchTest {
    @Test
    fun generate() {
        org.junit.Assume.assumeTrue(System.getenv("PERF_SCENES") != null)
        val out = File(System.getProperty("user.dir")).parentFile.resolve("build/fire-perf/e6/scenes")
        val json = Json { prettyPrint = true; classDiscriminator = "type"; encodeDefaults = true }
        fun write(name: String, t: Double, site: LaunchSite, intensity: WeatherIntensity, design: com.rm.apogee.core.craft.CraftDesign = StockCraft.sounder()) {
            val world = World.default(StockParts.catalog)
            world.restore(com.rm.apogee.core.world.WorldSave(catalogHash = StockParts.catalog.contentHash, universeTime = t, nextVesselId = 1L))
            world.weatherConfig = WeatherConfig(intensity = intensity)
            val v = world.spawnOnSurface(design, site)
            v.owner = "a9e0c7d2-5f4b-4c1e-9b8a-perf00000001"
            out.resolve("$name.json").writeText(json.encodeToString(world.save()))
        }
        write("pad", 16030.0, World.launchSites.first { it.id == "cape" }, WeatherIntensity.NORMAL)
        write("sparkler", 16030.0, World.launchSites.first { it.id == "cape" }, WeatherIntensity.CALM, StockCraft.sparkler())
        for (line in out.resolve("scenes.txt").readLines().filter { it.startsWith("SCENE ") }) {
            val p = line.split(" ")
            val name = p[1]; val t = p[2].toDouble()
            val site = LaunchSite("perf-$name", name, if (name == "moonquake") "luna" else "terra",
                asin(p[4].toDouble()), atan2(p[5].toDouble(), p[3].toDouble()))
            write(name, t - 60.0, site, WeatherIntensity.EXTREME)
            if (name == "tornado") {
                write("tornado-starter", t - 60.0, site, WeatherIntensity.EXTREME, StockCraft.starterRocket())
            }
        }
    }
}
