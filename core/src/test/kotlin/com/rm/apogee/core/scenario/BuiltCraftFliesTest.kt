package com.rm.apogee.core.scenario

import com.rm.apogee.core.craft.CraftBuilder
import com.rm.apogee.core.craft.CraftStats
import com.rm.apogee.core.craft.CraftStore
import com.rm.apogee.core.craft.SymmetryMode
import com.rm.apogee.core.part.AttachNodeKind
import com.rm.apogee.core.part.ResourceType
import com.rm.apogee.core.part.StockParts
import com.rm.apogee.core.world.Command
import com.rm.apogee.core.world.World
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * The M2 loop end to end: put a craft together with the builder, save it, load it back, and fly the
 * result.
 *
 * The builder and the simulation share their part model, their crossfeed rule and how they work out
 * staging, but they're still two users of it. This is the test that says a craft someone actually
 * built is a craft that actually flies, and that the delta-v the builder promised is the delta-v it
 * delivers.
 */
class BuiltCraftFliesTest {

    @get:Rule
    val folder = TemporaryFolder()

    private val catalog = StockParts.catalog

    /** Puts a small two-stage rocket together the way a player would. */
    private fun assemble(): CraftBuilder {
        val builder = CraftBuilder(catalog)
        builder.placeRoot("pod-halo")

        fun stackNode(partIndex: Int, nodeId: String) =
            builder.openNodes().first { it.partIndex == partIndex && it.node.id == nodeId }

        // Top down: capsule, upper tank, vacuum engine, separator, two lower tanks, lifter. That's
        // exactly the order a player works in, and it only goes together at all because engines
        // have a bottom node. Without one nothing can be hung under them, which is how that gap was
        // found.
        val upperTank = builder.attach("tank-cask2", stackNode(0, "bottom")).first()
        val upperEngine = builder.attach("engine-vesper", stackNode(upperTank, "bottom")).first()
        val decoupler = builder.attach("decoupler-ring", stackNode(upperEngine, "bottom")).first()
        val lowerTank = builder.attach("tank-cask4", stackNode(decoupler, "bottom")).first()
        val lowerTank2 = builder.attach("tank-cask4", stackNode(lowerTank, "bottom")).first()
        builder.attach("engine-ember", stackNode(lowerTank2, "bottom"))

        builder.symmetry = SymmetryMode.QUAD
        builder.attach(
            "fin-vane",
            builder.openNodes().first {
                it.partIndex == lowerTank2 && it.kind == AttachNodeKind.SURFACE
            },
        )
        return builder
    }

    @Test
    fun `a craft assembled in the builder validates and has staging`() {
        val builder = assemble()
        val problems = builder.design.validate(catalog)
        assertTrue("assembled craft should validate: $problems", problems.isEmpty())
        assertTrue("it should have a staging sequence", builder.design.stages.isNotEmpty())
    }

    @Test
    fun `a craft assembled in the builder survives a save and load`() {
        val builder = assemble()
        val store = CraftStore(folder.newFolder("craft"))

        val saved = store.save(builder.design.copy(name = "Built")).getOrThrow()
        val loaded = store.load(saved.fileName).getOrThrow()

        assertEquals(builder.design.copy(name = "Built"), loaded)
        assertTrue(loaded.validate(catalog).isEmpty())
    }

    @Test
    fun `a craft assembled in the builder actually leaves the pad`() {
        val builder = assemble()
        val stats = CraftStats.analyze(builder.design, catalog)
        assertTrue("builder says it cannot fly: ${stats.problems}", stats.isFlyable)
        assertTrue("needs thrust to weight above 1, got ${stats.liftoffTwr}", stats.liftoffTwr > 1.0)

        val world = World.default(catalog)
        val vessel = world.spawnOnSurface(builder.design, World.launchSites.first())
        val terra = world.attractorFor(vessel)
        val startAltitude = terra.altitudeOf(vessel.body.position)

        world.apply(Command.Stage(vessel.id.raw))
        world.apply(Command.SetThrottle(vessel.id.raw, 1.0))
        repeat(600) { world.step(1.0 / 60.0) }

        val endAltitude = terra.altitudeOf(vessel.body.position)
        assertTrue(
            "10 seconds at full throttle should climb well clear of the pad " +
                "($startAltitude -> $endAltitude)",
            endAltitude > startAltitude + 200.0,
        )
    }

    @Test
    fun `the builder's crossfeed matches the simulation's`() {
        val builder = assemble()
        val stats = CraftStats.analyze(builder.design, catalog)

        val world = World.default(catalog)
        val vessel = world.spawnOnSurface(builder.design, World.launchSites.first())

        // Whatever the builder says the first burn will use, the vessel really has to be able to
        // reach. Two versions of the crossfeed rule would drift apart, and the builder would
        // predict flights that can't happen.
        val firstBurn = stats.burns.first()
        world.apply(Command.Stage(vessel.id.raw))

        val reachable = vessel.propellantAvailableToActiveEngines(ResourceType.PROPELLANT) *
            ResourceType.PROPELLANT.densityPerUnit

        assertEquals(
            "builder predicted ${firstBurn.propellantMass}kg, vessel can reach ${reachable}kg",
            firstBurn.propellantMass,
            reachable,
            1.0,
        )
    }
}
