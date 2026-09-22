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
        val pod = add("pod-halo", 13.2, -1)
        add("chute-canopy", 14.0, pod)
        val upperTank = add("tank-cask2", 11.6, pod)
        val upperEngine = add("engine-vesper", 10.1, upperTank)
        val decoupler = add("decoupler-ring", 9.5, upperEngine)
        val lowerTankTop = add("tank-cask4", 7.4, decoupler)
        val lowerTankMid = add("tank-cask4", 3.4, lowerTankTop)
        val lowerTankBottom = add("tank-cask4", -0.6, lowerTankMid)
        // Below the tank, not inside it. The Ember is 1.4m tall and the
        // bottom tank ends at -2.6, so its centre belongs at -3.3; at -2.0 all
        // but the last ten centimetres of the engine was buried in the tank
        // above it, which is what made the stack look wrong at the base.
        val mainEngine = add("engine-ember", -3.3, lowerTankBottom)

        // Fins low on the stack, well behind the centre of mass, which is what
        // makes them stabilising rather than destabilising.
        val finRadius = 0.975
        add("fin-vane", -0.6, lowerTankBottom, x = finRadius)
        add("fin-vane", -0.6, lowerTankBottom, x = -finRadius)
        add("fin-vane", -0.6, lowerTankBottom, z = finRadius)
        add("fin-vane", -0.6, lowerTankBottom, z = -finRadius)

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

    /**
     * A lander: engine, tank, pod, chute and four sprung legs.
     *
     * Deliberately not the starter rocket with legs bolted on. Arriving is a
     * different problem from leaving, and this is the craft the descent
     * scenario flies - a wide footprint, gear that reaches below the engine
     * bell, and enough propellant for a retro burn but not for orbit.
     */
    fun lander(catalog: PartCatalog = StockParts.catalog): CraftDesign {
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

        // Bottom of the stack is y = 0: engine 0.0-1.0, tank 1.0-3.0,
        // pod 3.0-4.2, chute on top.
        val pod = add("pod-halo", 3.6, -1)
        val chute = add("chute-canopy", 4.4, pod)
        val tank = add("tank-cask2", 2.0, pod)
        val engine = add("engine-vesper", 0.5, tank)

        // Feet at y = -0.6: further below the engine bell at 0.0 than the
        // suspension's 0.4 m of travel, so the leg can compress fully and the
        // bell still clears the ground by 0.2 m. Gear that reaches less far
        // than it compresses is decoration - the first firm landing bottoms
        // the springs out and puts the engine in the dirt anyway, which is
        // exactly what the first version of this craft did.
        //
        // A metre out from the axis each way gives a two-metre footprint. The
        // footprint is what stops a lander tipping; the legs' strength only
        // decides whether it survives the arrival.
        val legReach = 1.0
        val legHeight = 0.2
        add("leg-stilt", legHeight, tank, x = legReach)
        add("leg-stilt", legHeight, tank, x = -legReach)
        add("leg-stilt", legHeight, tank, z = legReach)
        add("leg-stilt", legHeight, tank, z = -legReach)

        val stages = listOf(
            Stage(listOf(engine)),
            Stage(listOf(chute)),
            // Gear last, because it is the last thing you want out.
            Stage(parts.indices.filter { catalog[parts[it].partId]?.id == "leg-stilt" }),
        )

        return CraftDesign(
            name = "Stilt Lander",
            parts = parts,
            stages = stages,
            catalogHash = catalog.contentHash,
        )
    }

    /** The smallest thing that counts as a craft. Used by physics tests. */
    fun probe(catalog: PartCatalog = StockParts.catalog): CraftDesign =
        CraftDesign(
            name = "Probe",
            parts = listOf(PlacedPart("pod-halo", Vec3.zero())),
            stages = emptyList(),
            catalogHash = catalog.contentHash,
        )
}
