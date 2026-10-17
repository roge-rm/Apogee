package com.rm.apogee.core.world

import com.rm.apogee.core.part.ResourceType
import com.rm.apogee.core.part.StockParts
import com.rm.apogee.core.terrain.Noise
import com.rm.apogee.core.weather.Strike
import com.rm.apogee.core.weather.WeatherConfig
import com.rm.apogee.core.weather.WeatherIntensity
import com.rm.apogee.core.math.Vec3
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.cos
import kotlin.math.sin

/**
 * Lightning: a tall craft draws strikes from further off, a strike takes most of the charge and
 * puts probe cores, assist and wheels out for a few seconds, hurts the part it hits, and only a
 * big one breaks it, rarely.
 */
class LightningTest {
    private val catalog = StockParts.catalog

    private fun world(): World = World.default(catalog).also { it.weatherConfig = LightningScene.config }

    private val strikes: List<Strike> by lazy { LightningScene.strikes(world(), 3) }

    /** Parks [designs] at [offsets] metres east of [strike] before its storm, then runs to 3 s past it. */
    private fun scene(strike: Strike, vararg craft: Pair<com.rm.apogee.core.craft.CraftDesign, Double>): Pair<World, List<WorldEvent.LightningHit>> {
        val world = world()
        world.syncClock(strike.time - 2_600.0)
        for ((design, offset) in craft) LightningScene.park(world, design, LightningScene.east(world, strike.direction, offset))
        val hits = ArrayList<WorldEvent.LightningHit>()
        LightningScene.run(world, 30.0, hits)
        world.syncClock(strike.time - 3.0)
        hits.clear()
        LightningScene.run(world, 6.0, hits)
        return world to hits
    }

    @Test
    fun `a tall craft draws a strike a short one is out of reach of`() {
        assertTrue("strikes over flat land: ${strikes.size}", strikes.size >= 2)
        for (strike in strikes) {
            val (_, shortHits) = scene(strike, LightningScene.short(catalog) to 70.0)
            assertFalse("70 m off, the short craft is out of reach", shortHits.any { it.strikeId == strike.id })
            val (tall, tallHits) = scene(strike, LightningScene.tall(catalog) to 70.0)
            assertTrue("70 m off, the tall one takes it", tallHits.any { it.strikeId == strike.id && it.id == tall.vessels.single().id })
            // Both near: the tall one, though further off.
            val (both, bothHits) = scene(strike, LightningScene.short(catalog) to 20.0, LightningScene.tall(catalog) to -30.0)
            val tallOne = both.vessels.single { it.design.name == "Tall" }
            assertEquals("one craft per strike", 1, bothHits.count { it.strikeId == strike.id })
            assertEquals("the tall one takes it", tallOne.id, bothHits.single { it.strikeId == strike.id }.id)
        }
    }

    @Test
    fun `a strike takes most of the charge and the electrics are out for a few seconds`() {
        val strike = strikes.first()
        val world = world()
        world.syncClock(strike.time - 2_600.0)
        val probe = LightningScene.park(world, LightningScene.short(catalog), strike.direction)
        val hits = ArrayList<WorldEvent.LightningHit>()
        LightningScene.run(world, 30.0, hits)
        world.syncClock(strike.time - 3.0)
        LightningScene.run(world, 1.0, hits)
        hits.clear()
        val charge = probe.amountOf(ResourceType.ELECTRIC_CHARGE)
        val health = probe.health.copyOf()
        assertTrue("charged: $charge", charge > 1.0)
        while (world.time < strike.time + 0.5) LightningScene.run(world, 1.0 / 60.0, hits)
        assertTrue("it was struck", hits.any { it.id == probe.id && it.strikeId == strike.id })
        val energy = strike.energy.coerceIn(0.0, 1.0)
        val left = probe.amountOf(ResourceType.ELECTRIC_CHARGE)
        assertTrue("most of the charge gone: $left of $charge", left <= charge * (1.0 - World.LIGHTNING_DRAIN - World.LIGHTNING_DRAIN_MORE * energy) + 0.05)
        assertTrue("stunned", probe.stunned(world.time))
        assertEquals("until", strike.time + World.LIGHTNING_STUN + World.LIGHTNING_STUN_MORE * energy, probe.stunnedUntil, 1e-9)
        assertFalse("not flown while out", world.controllable(probe))
        if (probe.powered) assertEquals("STRUCK", world.whyNotControllable(probe))
        // The probe core on top took it; the tank and panel didn't.
        assertEquals("probe core hurt", health[1] - World.LIGHTNING_DAMAGE - World.LIGHTNING_DAMAGE_MORE * energy, probe.health[1], 1e-9)
        assertEquals("tank whole", health[0], probe.health[0], 0.0)
        assertEquals("panel whole", health[2], probe.health[2], 0.0)
        assertEquals("broken only by a big strike", World.lightningBreaks(LightningScene.config.seed, strike), probe.isBroken(1))
        while (world.time < probe.stunnedUntil + 0.5) LightningScene.run(world, 1.0 / 60.0, hits)
        assertFalse("back", probe.stunned(world.time))
        assertTrue("not held by the strike", world.whyNotControllable(probe) != "STRUCK")
    }

    @Test
    fun `only big strikes break parts, and rarely`() {
        val world = World.default(catalog).also { it.weatherConfig = WeatherConfig(intensity = WeatherIntensity.EXTREME) }
        val terra = world.system.body("terra")
        val weather = world.weatherFor(terra)!!
        val all = HashMap<Long, Strike>()
        val found = ArrayList<Strike>()
        for (i in 0 until 600) {
            val lat = (Noise.hash(5, i, 0, 0) - 0.5) * 1.4
            val lon = Noise.hash(5, i, 1, 0) * 2 * Math.PI
            found.clear()
            weather.strikes(Vec3(cos(lat) * cos(lon), sin(lat), cos(lat) * sin(lon)), 3_000.0, 9_000.0, found)
            for (s in found) all[s.id] = s
        }
        val seed = WeatherConfig(intensity = WeatherIntensity.EXTREME).seed
        val breaking = all.values.filter { World.lightningBreaks(seed, it) }
        println("strikes ${all.size}, breaking ${breaking.size}, big ${all.values.count { it.energy > World.LIGHTNING_BIG }}")
        assertTrue("strikes: ${all.size}", all.size > 1_000)
        assertTrue("some break: ${breaking.size}", breaking.isNotEmpty())
        assertTrue("rarely: ${breaking.size} of ${all.size}", breaking.size < 0.03 * all.size)
        assertTrue("only big ones", breaking.all { it.energy > World.LIGHTNING_BIG })
    }

    /** In a warp bubble a craft is struck on the bubble's clock, while the world is still well before. */
    @Test
    fun `a craft in a warp bubble is struck on its clock`() {
        val strike = strikes.first()
        val world = world()
        world.syncClock(strike.time - 2_600.0)
        val probe = LightningScene.park(world, LightningScene.short(catalog), strike.direction)
        val hits = ArrayList<WorldEvent.LightningHit>()
        LightningScene.run(world, 30.0, hits)
        world.syncClock(strike.time - 600.0)
        LightningScene.run(world, 1.0, hits)
        hits.clear()
        val bubble = world.openBubble(listOf(probe), "struck")
        bubble.rate = 50.0
        bubble.until = strike.time + 12.0
        var outFor = -1f
        var blocked = ""
        while (bubble.time < strike.time + 12.0 - 1e-6) {
            world.step(1.0 / 60.0)
            world.advanceBubble(bubble, 1.0 / 60.0)
            hits.addAll(world.drainEvents().filterIsInstance<WorldEvent.LightningHit>())
            if (outFor < 0f && hits.any { it.strikeId == strike.id }) {
                outFor = world.systemsOf(probe).stunned
                blocked = world.whyNotControllable(probe)
            }
        }
        repeat(60) {
            world.step(1.0 / 60.0)
            world.advanceBubble(bubble, 1.0 / 60.0)
            hits.addAll(world.drainEvents().filterIsInstance<WorldEvent.LightningHit>())
        }
        assertTrue("the world well before: ${strike.time - world.time} s", world.time < strike.time - 500.0)
        assertTrue("struck in the bubble: $hits", hits.any { it.strikeId == strike.id && it.id == probe.id })
        val stun = probe.stunnedUntil - strike.time
        assertTrue("out 2-8 s on its clock: $stun", stun >= World.LIGHTNING_STUN - 1e-9 && stun <= World.LIGHTNING_STUN + World.LIGHTNING_STUN_MORE + 1e-9)
        assertTrue("sent as seconds of its own clock: $outFor", outFor > 0f && outFor <= stun + 0.1)
        if (probe.powered) assertEquals("STRUCK", blocked)
        assertTrue("past it", bubble.time > probe.stunnedUntil + 0.5)
        assertEquals("nothing left", 0f, world.systemsOf(probe).stunned, 0f)
        assertTrue("heard again: ${world.whyNotControllable(probe)}", world.whyNotControllable(probe) != "STRUCK")
    }
}

