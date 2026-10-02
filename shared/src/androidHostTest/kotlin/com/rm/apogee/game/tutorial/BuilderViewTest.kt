package com.rm.apogee.game.tutorial

import com.rm.apogee.core.craft.CraftDesign
import com.rm.apogee.core.craft.PlacedPart
import com.rm.apogee.core.craft.StockCraft
import com.rm.apogee.core.math.Vec3
import com.rm.apogee.core.part.StockParts
import org.junit.Assert.assertEquals
import org.junit.Test

class BuilderViewTest {
    private val catalog = StockParts.catalog

    @Test
    fun `a sounder has everything the Building tutorial asks for`() {
        assertEquals(BuilderView(true, true, true, true, false), BuilderView.of(StockCraft.sounder(catalog), catalog, stagingSeen = false))
    }

    @Test
    fun `a pod alone is only a pod, whatever it carries inside`() {
        val pod = CraftDesign("Pod", listOf(PlacedPart("pod-halo", Vec3.zero())), emptyList(), catalog.contentHash)
        assertEquals(BuilderView(hasPod = true), BuilderView.of(pod, catalog, stagingSeen = false))
    }

    @Test
    fun `nothing built is nothing`() {
        assertEquals(BuilderView(), BuilderView.of(CraftDesign("Empty", emptyList()), catalog, stagingSeen = false))
    }
}
