package com.rm.apogee.ui.components.builder

import com.rm.apogee.core.part.StockParts
import org.junit.Assert.assertEquals
import org.junit.Test

class PartTabsTest {

    private val catalog = StockParts.catalog

    /** Every stock part, where a player would look for it. */
    private val expected = mapOf(
        PartTab.PODS to setOf("cockpit-bubble", "basket-wicker", "core-keeper", "pod-halo", "pod-trio", "probe-mote", "cockpit-sparrow", "cockpit-kestrel", "cab-rover", "cab-open", "cabin-wheelhouse",
            "pod-pearl", "pod-abyss", "hull-nautilus",
        ),
        PartTab.TANKS to setOf(
            "tank-cask2", "tank-cask4", "tank-broad4", "tank-broad8", "tank-grand4", "tank-grand8", "bin-ore", "bin-ore-broad", "tank-water",
            "fuselage-short", "fuselage-long",
        ),
        PartTab.ENGINES to setOf("engine-ember", "engine-vesper", "engine-zephyr", "engine-prop", "engine-forge", "engine-lantern", "engine-anvil"),
        PartTab.STRUCTURE to setOf(
            "boom-tail", "skids", "frame-drone",
            "shield-halo", "shield-broad", "decoupler-ring", "decoupler-broad", "decoupler-grand", "decoupler-side", "decoupler-feed", "adapter-taper", "adapter-grand", "girder-short", "girder-long", "beam-i", "plate-deck", "hub-cube", "strut-brace", "fairing-base", "fairing-hot", "fuselage-tailcone", "chassis-small", "chassis-large", "rack-cargo",
        ),
        PartTab.WINGS to setOf(
            "fin-vane", "wing-plank", "tail-elevon", "nosecone-spire", "nosecone-broad", "nosecone-grand", "wing-small", "wing-swept", "wing-delta",
            "tail-rudder", "tail-stabilator",
        ),
        PartTab.GROUND to setOf(
            "leg-stilt", "wheel-tread", "wheel-gear", "wheel-gear-main", "wheel-gear-nose", "wheel-tail",
            "wheel-small", "wheel-large", "hitch-ball", "hitch-coupling", "winch-drum",
        ),
        PartTab.WATER to setOf(
            "hull-punt", "hull-bow", "hull-mid", "hull-stern", "hull-skiff", "hull-cutter", "motor-outboard", "rudder", "keel", "keel-skeg",
            "ballast-trim", "ballast-deep", "ballast-abyss", "float-foam", "keel-lead", "screw-drive", "planes-dive",
            "sail-sloop", "sail-cutter", "pontoon",
        ),
        PartTab.UTILITY to setOf(
            "chute-canopy", "chute-side", "chute-side-heavy", "rcs-nudge", "light-bar", "dock-port", "dock-port-small", "mooring-clamp",
            "panel-glint", "wing-kite", "battery-hoard", "cell-spark", "antenna-reed", "dish-beacon", "dish-great", "generator-glow",
            "drill-auger", "converter-small", "scanner-survey", "ladder-rung",
            "battery-deep", "battery-abyss", "lamp-deep", "sonar-array",
        ),
        PartTab.AIR to setOf("rotor-main", "rotor-tail", "rotor-drone", "fan-lift", "balloon-small", "cell-gas", "envelope-airship"),
        PartTab.BASE to setOf(
            "deck-sky", "deck-sea",
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
    fun `All holds every part, with pods first`() {
        val all = PartTabs.parts(catalog, PartTab.ALL)
        assertEquals(catalog.parts.values.count { !it.hidden }, all.size)
        assertEquals(PartTab.PODS, PartTabs.of(all.first()))
    }
}
