package com.rm.apogee.game.tutorial

import com.rm.apogee.core.craft.VesselId
import com.rm.apogee.core.part.StockParts
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class TutorialWorldTest {
    private val catalog = StockParts.catalog

    private fun start(id: String) = Tutorials.byId(id)!!.start as TutorialStart.InOrbit

    @Test
    fun `coming home starts on the upper stage in orbit, owned, with nothing dropped beside it`() {
        val (world, id) = TutorialWorld.inOrbit(start("coming-home"), catalog, "me")
        val craft = world.vessel(VesselId(id))!!
        assertEquals("me", craft.owner)
        assertEquals("only the craft", 1, world.vessels.size)
        assertTrue("crewed", craft.crewAboard > 0)
        assertTrue("its upper stage, not the lifter", craft.defs.none { it.id == "engine-ember" })
        repeat(600) { world.step(1.0 / 60.0) }
        val orbit = world.orbitOf(craft)
        val terra = world.attractorFor(craft)
        assertTrue("still in orbit after ten seconds: ${orbit.periapsis - terra.radius}", orbit.periapsis - terra.radius > terra.atmosphereHeight)
    }

    @Test
    fun `docking starts with a second probe close ahead`() {
        val (world, id) = TutorialWorld.inOrbit(start("docking"), catalog, "me")
        assertEquals(2, world.vessels.size)
        val craft = world.vessel(VesselId(id))!!
        val other = world.vessels.first { it !== craft }
        assertEquals("me", other.owner)
        repeat(600) { world.step(1.0 / 60.0) }
        val gap = craft.body.position.distanceTo(other.body.position)
        assertTrue("$gap m apart", gap in 20.0..100.0)
    }
}
