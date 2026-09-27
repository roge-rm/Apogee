package com.rm.apogee.core.world

import com.rm.apogee.core.craft.CraftDesign
import com.rm.apogee.core.craft.CraftOrientation
import com.rm.apogee.core.craft.CraftStats
import com.rm.apogee.core.craft.PlacedPart
import com.rm.apogee.core.craft.Stage
import com.rm.apogee.core.craft.StockCraft
import com.rm.apogee.core.math.Vec3
import com.rm.apogee.core.part.Parachute
import com.rm.apogee.core.part.StockParts
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Staging in flight: what separates, what fires next, and what's left. */
class StagingTest {

    private val catalog = StockParts.catalog
    private val dt = 1.0 / 60.0

    /**
     * After the upper stage separates, the chute is still next, and still stage 2, the number the
     * player saw it by in the builder and on the stack. The lower stage's entry stays in the
     * sequence, empty.
     */
    @Test
    fun `the chute still fires after the stages below it are gone`() {
        val world = World.default(catalog)
        val rocket = world.spawnOnSurface(StockCraft.starterRocket(catalog), World.launchSites.first())
        world.stage(rocket) // lower engine
        world.stage(rocket) // separation, upper engine
        world.stage(rocket) // the pod lets go of the upper stage
        assertEquals("the chute keeps its number", 3, rocket.currentStage)
        world.stage(rocket) // chute
        val chute = rocket.defs.indices.first { rocket.defs[it].module<Parachute>() != null }
        assertTrue("the chute never fired", rocket.isActivated(chute))
        assertEquals(0, rocket.stagesRemaining)
    }

    /** Radial boosters: several decouplers in one stage, and every one of them lets go. */
    @Test
    fun `every decoupler in a stage separates`() {
        val parts = listOf(
            PlacedPart("pod-halo", Vec3.zero()),
            PlacedPart("decoupler-ring", Vec3(1.5, 0.0, 0.0), parentIndex = 0),
            PlacedPart("tank-cask2", Vec3(1.5, -1.0, 0.0), parentIndex = 1),
            PlacedPart("decoupler-ring", Vec3(-1.5, 0.0, 0.0), parentIndex = 0),
            PlacedPart("tank-cask2", Vec3(-1.5, -1.0, 0.0), parentIndex = 3),
        )
        val design = CraftDesign("Pair", parts, listOf(Stage(listOf(1, 3))), catalog.contentHash)
        val world = World.default(catalog)
        val craft = world.spawnOnSurface(design, World.launchSites.first())
        val before = world.vessels.size
        world.stage(craft)
        assertEquals("two boosters should have fallen away", before + 2, world.vessels.size)
        assertEquals(listOf("pod-halo"), craft.design.parts.map { it.partId })
    }

    @Test
    fun `a craft laid down stays laid down after dropping a stage`() {
        val world = World.default(catalog)
        val design = StockCraft.starterRocket(catalog).copy(orientation = CraftOrientation.HORIZONTAL)
        val craft = world.spawnOnSurface(design, World.launchSites.first())
        world.stage(craft)
        world.stage(craft)
        assertEquals(CraftOrientation.HORIZONTAL, craft.design.orientation)
    }

    /**
     * The flight HUD's figures. Burning brings the current stage's fuel and delta-v down from what
     * the builder promised, and the stages above still read full.
     */
    @Test
    fun `live stage figures follow the burn`() {
        val design = StockCraft.starterRocket(catalog)
        val planned = CraftStats.analyze(design, catalog).burns.first()
        val world = World.default(catalog)
        val rocket = world.spawnOnSurface(design, World.launchSites.first())
        world.apply(Command.Stage(rocket.id.raw))
        world.apply(Command.SetThrottle(rocket.id.raw, 1.0))
        repeat((planned.burnTime * 0.4 / dt).toInt()) { world.step(dt) }

        val live = CraftStats.analyzeLive(rocket)
        val now = live.first()
        assertEquals("the first entry is the stage burning", 0, now.index)
        assertTrue("fuel should be part used: ${now.fuelFraction}", now.fuelFraction in 0.45..0.75)
        assertTrue("delta-v should have dropped: ${now.deltaV} of ${planned.deltaV}", now.deltaV < planned.deltaV * 0.8)
        val upper = live.first { it.index == 1 }
        assertEquals("the upper stage is untouched", 1.0, upper.fuelFraction, 1e-9)
    }

    /** A jet's figures, which read zero when only vacuum was quoted. */
    @Test
    fun `an air-breathing stage has delta-v`() {
        val stats = CraftStats.analyze(StockCraft.sparrow(catalog), catalog)
        val burn = stats.burns.firstOrNull()
        assertTrue("the Sparrow should have a burn", burn != null)
        assertTrue("and delta-v: ${burn!!.deltaV}", burn.deltaV > 100.0)
        assertTrue(burn.fuel.isNotEmpty())
    }

    /** The Starter I coasting high up, with its lower stage lit and then let go. */
    private fun separatedInSpace(): Triple<World, com.rm.apogee.core.craft.Vessel, com.rm.apogee.core.craft.Vessel> {
        val world = World.default(catalog)
        val terra = world.system.body("terra")
        val position = Vec3(terra.radius + 240_000.0, 0.0, 0.0)
        val rocket = world.spawnAt(
            StockCraft.starterRocket(catalog), "terra", position,
            Vec3(1_000.0, 0.0, kotlin.math.sqrt(terra.gravitationalParameter / position.x) * 0.5),
            com.rm.apogee.core.math.quatFromTo(Vec3.unitY(), Vec3(1.0, 0.0, 0.0)),
        )
        world.stage(rocket)
        world.apply(Command.SetThrottle(rocket.id.raw, 1.0))
        repeat(60) { world.step(1.0 / 60) }
        world.apply(Command.SetThrottle(rocket.id.raw, 0.0))
        world.stage(rocket)
        val spent = world.vessels.first { it.id != rocket.id }
        return Triple(world, rocket, spent)
    }

    /**
     * Let go of while coasting, the halves drift apart on the ring's push alone, and keep drifting.
     * The spent half was once placed at the whole rocket's centre, inside the stage above it, and
     * the two locked together as soon as they stopped ignoring each other.
     */
    @Test
    fun `coasting halves drift apart and stay apart`() {
        val (world, rocket, spent) = separatedInSpace()
        val start = rocket.body.position.distanceTo(spent.body.position)
        repeat(6 * 60) { world.step(1.0 / 60) }
        val apart = rocket.body.linearVelocity.copy().subInPlace(spent.body.linearVelocity).length
        assertTrue("still separating after six seconds: $apart m/s", apart > 0.5)
        assertTrue(
            "and further apart than they started",
            rocket.body.position.distanceTo(spent.body.position) > start + 3.0,
        )
    }

    /** A spent stage keeps what was left in it. It doesn't fall away refuelled. */
    @Test
    fun `a spent stage keeps its fuel`() {
        val (_, rocket, spent) = separatedInSpace()
        val full = spent.defs.filter { it.id == "tank-cask4" }.sumOf { 400.0 }
        val left = spent.amountOf(com.rm.apogee.core.part.ResourceType.PROPELLANT)
        assertTrue("it burned for a second before letting go: $left of $full", left < full - 1.0)
        assertTrue(rocket.amountOf(com.rm.apogee.core.part.ResourceType.PROPELLANT) > 0.0)
    }

    /**
     * A stage let go of while its engine is burning keeps on burning. Its control module is gone,
     * but nothing told the engine to stop, so it burns at the throttle it had until its tanks run
     * dry. That's how I wanted it.
     */
    @Test
    fun `a stage dropped while burning burns on until it is dry`() {
        val world = World.default(catalog)
        val rocket = world.spawnInOrbit(
            StockCraft.starterRocket(catalog), "terra",
            com.rm.apogee.core.orbit.Orbit.circular(700_000.0, 3.5316000e12),
        )
        world.apply(com.rm.apogee.core.world.Command.SetThrottle(rocket.id.raw, 0.7))
        world.stage(rocket) // lower engine
        repeat(60) { world.step(dt) }
        val before = world.vessels.map { it.id }.toSet()
        world.stage(rocket) // separation, upper engine
        val lower = world.vessels.first { it.id !in before }
        assertEquals("it keeps the throttle it had", 0.7, lower.control.throttle, 1e-9)
        val fuel0 = lower.amountOf(com.rm.apogee.core.part.ResourceType.PROPELLANT)
        repeat(120) { world.step(dt) }
        val engine = lower.defs.indices.first { lower.defs[it].module<com.rm.apogee.core.part.Engine>() != null }
        assertTrue("still burning: ${lower.engineOutput[engine]}", lower.engineOutput[engine] > 0.5)
        assertTrue("on its own propellant", lower.amountOf(com.rm.apogee.core.part.ResourceType.PROPELLANT) < fuel0)
        val gap = lower.body.position.distanceTo(rocket.body.position)
        println("two seconds on: ${"%.1f".format(gap)} m apart; upper parts ${rocket.design.parts.size}, lower alive ${world.vessels.any { it.id == lower.id }}")
        // And it runs dry in the end, and stops.
        repeat(60 * 400) { world.step(dt) }
        if (world.vessels.any { it.id == lower.id }) {
            assertEquals("dry, it has stopped", 0.0, lower.engineOutput[engine], 1e-9)
        }
    }

    /**
     * Staged with the stage below still burning. It's solid, so it shoves the craft above along
     * instead of flying through it, which is what I asked for.
     */
    @Test
    fun `a stage let go while burning pushes the craft above, and doesn't pass through it`() {
        val world = World.default(catalog)
        val rocket = world.spawnInOrbit(
            StockCraft.starterRocket(catalog), "terra",
            com.rm.apogee.core.orbit.Orbit.circular(700_000.0, 3.5316000e12),
        )
        rocket.body.angularVelocity.setZero()
        world.apply(com.rm.apogee.core.world.Command.SetThrottle(rocket.id.raw, 1.0))
        world.stage(rocket) // lower engine
        repeat(30) { world.step(dt) }
        val before = world.vessels.map { it.id }.toSet()
        world.stage(rocket) // separation, and the upper engine lights too
        world.apply(com.rm.apogee.core.world.Command.SetThrottle(rocket.id.raw, 0.0))
        val lower = world.vessels.first { it.id !in before }
        // The craft above coasts, and what's below still burns.
        val nose = rocket.body.orientation.rotate(Vec3.unitY(), Vec3())
        val v0 = rocket.body.linearVelocity dot nose
        var worst = Double.MAX_VALUE
        repeat(180) {
            world.step(dt)
            // How far the lower stage's centre is below the upper's, along the nose.
            val ahead = Vec3().setTo(rocket.body.position).subInPlace(lower.body.position) dot nose
            worst = minOf(worst, ahead)
        }
        val pushed = (rocket.body.linearVelocity dot nose) - v0
        println("upper pushed +${"%.1f".format(pushed)} m/s; lower never closer than ${"%.2f".format(worst)} m below; ${world.vessels.size} craft")
        assertTrue("never passed through: the lower stage stayed below ($worst m)", worst > 0.5)
        assertTrue("the upper stage was pushed along ($pushed m/s)", pushed > 2.0)
    }

    /**
     * A stage let go with its engine burning pushes the one above, and pushes it straight. Taken at
     * the touching points around the decoupler, it spun the upper stage past a radian a second,
     * which at 4x warp was a craft flickering all over the screen.
     */
    @Test
    fun `a burning stage below pushes the upper one without spinning it`() {
        val world = World.default(catalog)
        val rocket = world.spawnInOrbit(StockCraft.starterRocket(catalog), "terra", com.rm.apogee.core.orbit.Orbit.circular(700_000.0, 3.5316000e12))
        rocket.body.angularVelocity.setZero()
        world.apply(Command.SetThrottle(rocket.id.raw, 1.0))
        world.stage(rocket)
        repeat(30) { world.step(dt) }
        world.stage(rocket)
        var most = 0.0
        repeat(60 * 4) {
            world.step(dt)
            most = maxOf(most, rocket.body.angularVelocity.length)
        }
        assertTrue("the upper stage spun up to $most rad/s", most < 0.05)
    }
}
