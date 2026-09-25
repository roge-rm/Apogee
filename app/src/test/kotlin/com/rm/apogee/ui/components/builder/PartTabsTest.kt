package com.rm.apogee.ui.components.builder

import com.rm.apogee.core.part.StockParts
import org.junit.Assert.assertEquals
import org.junit.Test

class PartTabsTest {

    private val catalog = StockParts.catalog

    /** Every stock part, where a player would look for it. */
    private val expected = mapOf(
        PartTab.PODS to setOf("pod-halo", "cockpit-sparrow", "cockpit-kestrel", "cab-rover", "cab-open", "cabin-wheelhouse"),
        PartTab.TANKS to setOf("tank-cask2", "tank-cask4", "fuselage-short", "fuselage-long"),
        PartTab.ENGINES to setOf("engine-ember", "engine-vesper", "engine-zephyr", "engine-prop"),
        PartTab.STRUCTURE to setOf(
            "shield-halo", "decoupler-ring", "fuselage-tailcone", "chassis-small", "chassis-large", "rack-cargo",
        ),
        PartTab.WINGS to setOf(
            "fin-vane", "wing-plank", "tail-elevon", "nosecone-spire", "wing-small", "wing-swept", "wing-delta",
            "tail-rudder", "tail-stabilator",
        ),
        PartTab.GROUND to setOf(
            "leg-stilt", "wheel-tread", "wheel-gear", "wheel-gear-main", "wheel-gear-nose", "wheel-tail",
            "wheel-small", "wheel-large", "hitch-ball", "hitch-coupling",
        ),
        PartTab.WATER to setOf(
            "hull-punt", "hull-bow", "hull-mid", "hull-stern", "hull-skiff", "hull-cutter", "motor-outboard", "rudder", "keel",
        ),
        PartTab.UTILITY to setOf("chute-canopy", "rcs-nudge", "light-bar", "dock-port", "dock-port-small", "mooring-clamp"),
    )

    @Test
    fun `every stock part is in the tab it belongs in`() {
        val all = expected.values.flatten().toSet()
        for (def in catalog.parts.values) {
            val want = expected.entries.firstOrNull { def.id in it.value }?.key
            assertEquals("${def.id} is not in the table - which tab should it be in?", true, def.id in all)
            assertEquals(def.id, want, PartTabs.of(def))
        }
    }

    @Test
    fun `All holds every part, pods first`() {
        val all = PartTabs.parts(catalog, PartTab.ALL)
        assertEquals(catalog.size, all.size)
        assertEquals(PartTab.PODS, PartTabs.of(all.first()))
    }
}
