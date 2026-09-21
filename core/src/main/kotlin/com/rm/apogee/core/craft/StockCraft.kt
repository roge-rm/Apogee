package com.rm.apogee.core.craft

import com.rm.apogee.core.math.Quat
import com.rm.apogee.core.math.Vec3
import com.rm.apogee.core.part.PartCatalog
import com.rm.apogee.core.part.StockParts

/**
 * Reference craft, built in code.
 *
 * These exist so the simulation has something real to fly before the builder
 * exists, and so the headless ascent scenario has a fixed subject - a
 * regression in thrust, drag or staging shows up as this craft failing to make
 * orbit, which is a far more legible signal than a number moving in a unit
 * test.
 *
 * Layout convention: +Y is up, the design origin is at the very bottom of the
 * stack, and the tree is rooted at the command pod with everything hanging
 * below it. Rooting at the pod is what makes staging work - separating the
 * subtree below a decoupler discards the spent stage and leaves the crew
 * flying, rather than the other way round.
 */
object StockCraft {

    /**
     * Two-stage launcher sized to reach a ~100 km orbit with margin.
     *
     * Roughly 3.5 km/s of delta-v against the ~3.4 km/s the homeworld demands,
     * at a liftoff thrust-to-weight of about 1.4.
     */
    fun starterRocket(catalog: PartCatalog = StockParts.catalog): CraftDesign {
        val parts = ArrayList<PlacedPart>()

        fun add(partId: String, y: Double, parent: Int, x: Double = 0.0, z: Double = 0.0): Int {
            parts.add(
                PlacedPart(
                    partId = partId,
                    position = Vec3(x, y, z),
                    rotation = Quat.identity(),
                    parentIndex = parent,
                )
            )
            return parts.size - 1
        }

        // Built top-down so the pod can be the root, but positioned in the
        // bottom-up coordinates the stack actually occupies.
        val pod = add("pod-mk1", 13.2, -1)
        add("parachute-mk1", 14.0, pod)
        val upperTank = add("tank-t400", 11.6, pod)
        val upperEngine = add("engine-lv909", 10.1, upperTank)
        val decoupler = add("decoupler-1", 9.5, upperEngine)
        val lowerTankTop = add("tank-t800", 7.4, decoupler)
        val lowerTankMid = add("tank-t800", 3.4, lowerTankTop)
        val lowerTankBottom = add("tank-t800", -0.6, lowerTankMid)
        val mainEngine = add("engine-lvt45", -2.0, lowerTankBottom)

        // Fins low on the stack, well behind the centre of mass, which is what
        // makes them stabilising rather than destabilising.
        val finRadius = 0.975
        add("fin-basic", -0.6, lowerTankBottom, x = finRadius)
        add("fin-basic", -0.6, lowerTankBottom, x = -finRadius)
        add("fin-basic", -0.6, lowerTankBottom, z = finRadius)
        add("fin-basic", -0.6, lowerTankBottom, z = -finRadius)

        val chute = 1

        val stages = listOf(
            // Ignite the lifter.
            Stage(listOf(mainEngine)),
            // Separate the spent first stage and light the vacuum engine. Both
            // in one stage, so there is no coasting gap.
            Stage(listOf(decoupler, upperEngine)),
            // Chute, for coming home.
            Stage(listOf(chute)),
        )

        return CraftDesign(
            name = "Starter I",
            parts = parts,
            stages = stages,
            catalogHash = catalog.contentHash,
        )
    }

    /** The smallest thing that counts as a craft. Used by physics tests. */
    fun probe(catalog: PartCatalog = StockParts.catalog): CraftDesign =
        CraftDesign(
            name = "Probe",
            parts = listOf(PlacedPart("pod-mk1", Vec3.zero())),
            stages = emptyList(),
            catalogHash = catalog.contentHash,
        )
}
