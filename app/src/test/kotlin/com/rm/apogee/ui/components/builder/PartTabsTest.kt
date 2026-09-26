package com.rm.apogee.ui.components.builder

import com.rm.apogee.core.part.StockParts
import org.junit.Assert.assertEquals
import org.junit.Test

class PartTabsTest {

    private val catalog = StockParts.catalog

    /** Every stock part, where a player would look for it. */
    private val expected = mapOf(
        PartTab.PODS to setOf("pod-halo", "probe-mote", "cockpit-sparrow", "cockpit-kestrel", "cab-rover", "cab-open", "cabin-wheelhouse"),
        PartTab.TANKS to setOf(
            "tank-cask2", "tank-cask4", "tank-broad4", "tank-broad8", "bin-ore", "bin-ore-broad", "tank-water",
            "fuselage-short", "fuselage-long",
        ),
        PartTab.ENGINES to setOf("engine-ember", "engine-vesper", "engine-zephyr", "engine-prop", "engine-forge"),
        PartTab.STRUCTURE to setOf(
            "shield-halo", "decoupler-ring", "decoupler-broad", "adapter-taper", "fairing-base", "fairing-hot", "fuselage-tailcone", "chassis-small", "chassis-large", "rack-cargo",
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
            "hull-punt", "hull-bow", "hull-mid", "hull-stern", "hull-skiff", "hull-cutter", "motor-outboard", "rudder", "keel", "keel-skeg",
        ),
        PartTab.UTILITY to setOf(
            "chute-canopy", "rcs-nudge", "light-bar", "dock-port", "dock-port-small", "mooring-clamp",
            "panel-glint", "wing-kite", "battery-hoard", "cell-spark", "antenna-reed", "dish-beacon", "dish-great", "generator-glow",
            "drill-auger", "converter-small", "scanner-survey", "ladder-rung",
        ),
        PartTab.BASE to setOf(
            "base-foundation", "base-core", "base-habitat", "base-depot", "base-mono-depot",
            "base-refinery", "base-silo", "base-cistern", "base-battery", "base-solar",
            "base-connector", "base-corridor", "base-pad", "base-floodlight", "base-flatbed", "base-release-clamp",
        ),
        PartTab.BUILDINGS to setOf(
            "struct-launch-tower", "struct-lightning-mast", "struct-assembly", "struct-control-centre", "struct-propellant-farm",
            "struct-floodlight", "struct-hangar", "struct-control-tower", "struct-windsock", "struct-runway-lamp",
            "struct-paint-bar", "struct-paint-dash", "struct-jetty", "struct-boathouse", "struct-crane",
        ),
    )

    @Test
    fun `every stock part is in the tab it belongs in`() {
        val all = expected.values.flatten().toSet()
        for (def in catalog.parts.values) {
            if (def.hidden) continue
            val want = expected.entries.firstOrNull { def.id in it.value }?.key
            assertEquals("${def.id} is not in the table - which tab should it be in?", true, def.id in all)
            assertEquals(def.id, want, PartTabs.of(def))
        }
    }

    @Test
    fun `All holds every part, pods first`() {
        val all = PartTabs.parts(catalog, PartTab.ALL)
        assertEquals(catalog.parts.values.count { !it.hidden }, all.size)
        assertEquals(PartTab.PODS, PartTabs.of(all.first()))
    }
}
