package com.rm.apogee.core.world

import com.rm.apogee.core.craft.CraftDesign
import com.rm.apogee.core.craft.PlacedPart
import com.rm.apogee.core.craft.Vessel
import com.rm.apogee.core.math.Vec3
import com.rm.apogee.core.part.PartCatalog
import com.rm.apogee.core.terrain.Noise
import com.rm.apogee.core.weather.Strike
import com.rm.apogee.core.weather.WeatherConfig
import com.rm.apogee.core.weather.WeatherIntensity
import kotlin.math.asin
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin

/** Craft parked round a lightning strike on Terra's land, for the lightning tests. */
internal object LightningScene {
    val config = WeatherConfig(intensity = WeatherIntensity.WILD)

    /** A probe core on a short tank with a solar panel and a battery on its sides: about 2.5 m tall. */
    fun short(catalog: PartCatalog) = CraftDesign(
        "Short",
        listOf(
            PlacedPart("tank-cask2", Vec3.zero()),
            PlacedPart("probe-mote", Vec3(0.0, 1.2, 0.0), parentIndex = 0),
            PlacedPart("panel-glint", Vec3(0.66, 0.3, 0.0), parentIndex = 0),
            PlacedPart("battery-hoard", Vec3(-0.75, 0.0, 0.0), parentIndex = 0),
        ),
        emptyList(),
        catalog.contentHash,
    )

    /** A probe core on two broad tanks: about 16.5 m tall. */
    fun tall(catalog: PartCatalog) = CraftDesign(
        "Tall",
        listOf(
            PlacedPart("tank-broad8", Vec3.zero()),
            PlacedPart("tank-broad8", Vec3(0.0, 8.0, 0.0), parentIndex = 0),
            PlacedPart("probe-mote", Vec3(0.0, 12.2, 0.0), parentIndex = 1),
        ),
        emptyList(),
        catalog.contentHash,
    )

    /** The first of [count] strikes over flat land a Wild sky gives, past 3000 s. */
    fun strikes(world: World, count: Int): List<Strike> {
        val terra = world.system.body("terra")
        val weather = world.weatherFor(terra)!!
        val found = ArrayList<Strike>()
        val out = ArrayList<Strike>()
        val seen = HashSet<Long>()
        for (i in 0 until 4_000) {
            val lat = (Noise.hash(3, i, 0, 0) - 0.5) * 1.2
            val lon = Noise.hash(3, i, 1, 0) * 2 * Math.PI
            val dir = Vec3(cos(lat) * cos(lon), sin(lat), cos(lat) * sin(lon))
            found.clear()
            weather.strikes(dir, 3_000.0, 6_000.0, found)
            for (s in found) {
                if (terra.terrain!!.elevation(s.direction) < 5.0 || !flat(world, s.direction) || !seen.add(s.id)) continue
                out += s
                if (out.size >= count) return out
            }
        }
        return out
    }

    private fun flat(world: World, d: Vec3): Boolean {
        val terra = world.system.body("terra")
        val e = Vec3(d.z, 0.0, -d.x).normalizeInPlace()
        val n = Vec3().setTo(d).crossInPlace(e)
        val h0 = terra.terrain!!.elevation(d)
        for (k in 0 until 4) {
            val a = k * Math.PI / 2
            val p = Vec3().setTo(d).addScaledInPlace(e, cos(a) * 20.0 / terra.radius).addScaledInPlace(n, sin(a) * 20.0 / terra.radius).normalizeInPlace()
            if (kotlin.math.abs(terra.terrain!!.elevation(p) - h0) > 1.5) return false
        }
        return true
    }

    /** Unit, body-fixed: [metres] east of [d]. */
    fun east(world: World, d: Vec3, metres: Double): Vec3 {
        val e = Vec3(d.z, 0.0, -d.x).normalizeInPlace()
        return Vec3().setTo(d).addScaledInPlace(e, metres / world.system.body("terra").radius).normalizeInPlace()
    }

    /** [design] parked at unit, body-fixed [d]. */
    fun park(world: World, design: CraftDesign, d: Vec3): Vessel =
        world.spawnOnSurface(design, LaunchSite("test", "", "terra", asin(d.y), atan2(d.z, d.x)))

    /** Steps [seconds], gathering the lightning hits. */
    fun run(world: World, seconds: Double, hits: MutableList<WorldEvent.LightningHit>) {
        repeat((seconds * 60.0).toInt()) {
            world.step(1.0 / 60.0)
            hits.addAll(world.drainEvents().filterIsInstance<WorldEvent.LightningHit>())
        }
    }
}
