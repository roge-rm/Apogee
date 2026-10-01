package com.rm.apogee.core.world

import com.rm.apogee.core.craft.CraftBuilder
import com.rm.apogee.core.craft.CraftDesign
import com.rm.apogee.core.craft.CraftOrientation
import com.rm.apogee.core.craft.SymmetryMode
import com.rm.apogee.core.craft.Vessel
import com.rm.apogee.core.math.Vec3
import com.rm.apogee.core.part.StockParts
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * A craft built lying down, launched lying down. Built through [CraftBuilder] to test the whole
 * path: wheels go underneath, it spawns nose first along the ground, and rolls along its nose.
 */
class HorizontalCraftTest {

    private val catalog = StockParts.catalog
    private val dt = 1.0 / 60.0

    /** A pod and a long tank lying down, with a pair of wheels under each. */
    private fun cart(): CraftDesign {
        val builder = CraftBuilder(catalog)
        builder.placeRoot("pod-halo")
        builder.orientation = CraftOrientation.HORIZONTAL
        builder.attach("tank-cask4", builder.openNodes().first { it.node.id == "bottom" })
        builder.symmetry = SymmetryMode.MIRROR
        for (part in 0..1) {
            val quarter = builder.openNodes().first {
                it.partIndex == part && it.node.id.startsWith("surface-q")
            }
            check(builder.attach("wheel-tread", quarter).size == 2)
        }
        return builder.design
    }

    private fun launched(): Triple<World, Vessel, Vec3> {
        val world = World.default(catalog)
        val cart = world.spawnOnSurface(cart(), World.launchSites.first())
        repeat(120) { world.step(dt) }
        val up = cart.body.position.copy().normalizeInPlace()
        return Triple(world, cart, up)
    }

    private fun groundVelocity(world: World, vessel: Vessel): Vec3 {
        val attractor = world.system.body(vessel.referenceBodyId)!!
        val surface = attractor.surfaceVelocityAt(vessel.body.position, Vec3())
        return vessel.body.linearVelocity.copy().subInPlace(surface)
    }

    @Test
    fun `it lands lying down, nose east, on its wheels`() {
        val (world, cart, up) = launched()
        val nose = cart.forward()
        val roof = cart.body.orientation.rotate(Vec3(0.0, 0.0, 1.0))
        val east = world.system.body(cart.referenceBodyId)!!
            .surfaceVelocityAt(cart.body.position, Vec3()).normalizeInPlace()

        assertTrue("the nose is not level: ${nose dot up}", kotlin.math.abs(nose dot up) < 0.1)
        assertTrue("the roof is not up: ${roof dot up}", (roof dot up) > 0.99)
        assertTrue("the nose is not east: ${nose dot east}", (nose dot east) > 0.99)
        assertTrue(
            "it did not come to rest: ${groundVelocity(world, cart).length} m/s",
            groundVelocity(world, cart).length < 0.5,
        )
    }

    @Test
    fun `throttle rolls it along its nose`() {
        val (world, cart, _) = launched()
        cart.control.throttle = 1.0
        repeat(600) { world.step(dt) }

        val velocity = groundVelocity(world, cart)
        val speed = velocity.length
        assertTrue("it never got moving ($speed m/s)", speed > 8.0)
        val along = (velocity dot cart.forward()) / speed
        assertTrue("it is going sideways or backwards: $along", along > 0.95)
    }
}
