package com.rm.apogee.core.craft

import com.rm.apogee.core.math.Quat
import com.rm.apogee.core.math.Vec3
import com.rm.apogee.core.part.MeshSpec
import com.rm.apogee.core.part.PartCatalog
import com.rm.apogee.core.part.StockParts
import com.rm.apogee.core.math.Math

/**
 * The Cape's buildings: the launch complex, the airfield at the runway's west end, and the harbour
 * on the bay's west shore. Each is a world-owned founded base of real parts, solid and breakable,
 * rebuilt from these designs when nobody's near (see `World.repairStructures`).
 *
 * Positions are metres east and north of the pad, on the Cape's levelled ground (see
 * `TerrainField`). Each complex stands on ground flattened to one height, so one rigid craft can
 * hold it.
 */
object StockStructures {

    /**
     * One building: part [partId], centred [east] and [north] of the pad, its front (+Z) facing
     * [facing] degrees from south toward east, raised [lift] m off the ground.
     */
    class Placement(val partId: String, val east: Double, val north: Double, val facing: Double = 0.0, val lift: Double = 0.0)

    /** A complex: its buildings, on ground at [east], [north] of the pad. */
    class Complex(val name: String, val east: Double, val north: Double, val placements: List<Placement>)

    val launchComplex = Complex(
        "Cape Launch Complex", 0.0, 0.0,
        buildList {
            // Beside pad 0, north of the pads, arms reaching south.
            add(Placement("struct-launch-tower", 0.0, 17.0))
            // Lightning masts around the pads, clear of the row.
            for ((e, n) in listOf(-30.0 to 30.0, 30.0 to 30.0, -30.0 to -30.0, 30.0 to -30.0)) add(Placement("struct-lightning-mast", e, n))
            // Lamps at the edge of the concrete, each turned toward the pads.
            for ((e, n) in listOf(-90.0 to 60.0, 90.0 to 60.0, -90.0 to -60.0, 90.0 to -60.0)) {
                add(Placement("struct-floodlight", e, n, facing = facingToward(e, n, 0.0, 0.0)))
            }
            add(Placement("struct-assembly", -130.0, 170.0))
            add(Placement("struct-control-centre", 160.0, 190.0))
            add(Placement("struct-propellant-farm", -170.0, -140.0, facing = 90.0))
        },
    )

    val airfield = Complex(
        "Cape Airfield", 525.0, -285.0,
        buildList {
            // Hangars along the north side of the apron, open onto it.
            add(Placement("struct-hangar", 420.0, -250.0))
            add(Placement("struct-hangar", 580.0, -250.0))
            add(Placement("struct-control-tower", 720.0, -250.0))
            add(Placement("struct-windsock", 300.0, -340.0))
            // Edge lamps every hundred metres down both sides of the runway.
            var e = 250.0
            while (e <= 2_750.0) {
                add(Placement("struct-runway-lamp", e, -373.0))
                add(Placement("struct-runway-lamp", e, -427.0))
                e += 100.0
            }
            // Threshold bars at each end, and a dashed line down the middle.
            for (end in listOf(262.0, 2_738.0)) for (n in listOf(-418.0, -410.0, -390.0, -382.0)) {
                add(Placement("struct-paint-bar", end, n, facing = 90.0, lift = PAINT_LIFT))
            }
            e = 330.0
            while (e <= 2_670.0) {
                add(Placement("struct-paint-dash", e, -400.0, facing = 90.0, lift = PAINT_LIFT))
                e += 50.0
            }
        },
    )

    val harbour = Complex(
        "Cape Harbour", 2_570.0, 350.0,
        buildList {
            // The jetty runs from the quay into the berth, deck level with the quay.
            for (k in 0 until 13) add(Placement("struct-jetty", 2_620.0 + k * 10.0, 350.0, facing = 90.0, lift = -12.0))
            add(Placement("struct-boathouse", 2_560.0, 300.0, facing = 90.0))
            add(Placement("struct-crane", 2_605.0, 367.0, facing = 90.0))
        },
    )

    val complexes = listOf(launchComplex, airfield, harbour)

    /** Runway paint height in metres, over the paving that's drawn 4 cm up. */
    private const val PAINT_LIFT = 0.06

    /**
     * The [Placement.facing] that turns a building at [east], [north] toward [toEast], [toNorth].
     */
    fun facingToward(east: Double, north: Double, toEast: Double, toNorth: Double): Double =
        Math.toDegrees(kotlin.math.atan2(toEast - east, -(toNorth - north)))

    /**
     * [complex] as an upright craft design, origin on the ground at the complex, +X east, +Y up, +Z
     * south. Each building stands on the ground, turned to face its way.
     */
    fun design(complex: Complex, catalog: PartCatalog = StockParts.catalog): CraftDesign {
        val parts = complex.placements.mapIndexed { i, p ->
            val def = catalog.require(p.partId)
            val half = when (val m = def.mesh) {
                is MeshSpec.Box -> m.height / 2
                is MeshSpec.Cylinder -> m.height / 2
                is MeshSpec.Cone -> m.height / 2
                is MeshSpec.Sphere -> m.radius
            }
            PlacedPart(
                partId = p.partId,
                position = Vec3(p.east - complex.east, half + p.lift, -(p.north - complex.north)),
                rotation = Quat.fromAxisAngle(Vec3(0.0, 1.0, 0.0), Math.toRadians(p.facing)),
                parentIndex = if (i == 0) -1 else 0,
            )
        }
        return CraftDesign(name = complex.name, parts = parts, catalogHash = catalog.contentHash)
    }
}
