package com.rm.apogee.core.craft

import com.rm.apogee.core.math.Quat
import com.rm.apogee.core.math.Vec3
import com.rm.apogee.core.part.MeshSpec
import com.rm.apogee.core.part.PartCatalog
import com.rm.apogee.core.part.StockParts
import com.rm.apogee.core.math.Math

/**
 * The Cape's own buildings: the launch complex around the pads, the airfield at the west end of the
 * runway, and the harbour on the west shore of the bay. Each is a founded base belonging to the
 * world, built from real parts. They're solid and they can be broken by things hitting them, and
 * they get rebuilt from these designs once nobody is nearby (see `World.repairStructures`).
 *
 * They're placed in metres east and north of the pad, on the Cape's levelled ground (see
 * `TerrainField`). Each complex stands on ground flattened to one height, so one rigid craft can
 * hold it.
 */
object StockStructures {

    /**
     * One building: part [partId], with its middle [east] and [north] of the pad, its front (+Z)
     * facing [facing] degrees round from south toward east, and set [lift] m above where it would
     * stand on the ground.
     */
    class Placement(val partId: String, val east: Double, val north: Double, val facing: Double = 0.0, val lift: Double = 0.0)

    /** A complex: its buildings, standing on ground at [east], [north] of the pad. */
    class Complex(val name: String, val east: Double, val north: Double, val placements: List<Placement>)

    val launchComplex = Complex(
        "Cape Launch Complex", 0.0, 0.0,
        buildList {
            // Next to pad 0, north of the row of pads, with its arms reaching south toward it.
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
            // The jetty runs out from the front of the quay into the berth, with its deck level
            // with the quay.
            for (k in 0 until 13) add(Placement("struct-jetty", 2_620.0 + k * 10.0, 350.0, facing = 90.0, lift = -12.0))
            add(Placement("struct-boathouse", 2_560.0, 300.0, facing = 90.0))
            add(Placement("struct-crane", 2_605.0, 367.0, facing = 90.0))
        },
    )

    val complexes = listOf(launchComplex, airfield, harbour)

    /**
     * How far runway paint stands off the ground, in metres. It sits on the paving laid over it,
     * which is drawn 4 cm up.
     */
    private const val PAINT_LIFT = 0.06

    /** The [Placement.facing] that turns a building at [east], [north] to look at [toEast], [toNorth]. */
    fun facingToward(east: Double, north: Double, toEast: Double, toNorth: Double): Double =
        Math.toDegrees(kotlin.math.atan2(toEast - east, -(toNorth - north)))

    /**
     * [complex] as a craft design. It's upright, with its origin on the ground at the complex's
     * position, +X east, +Y up and +Z south. Each building stands on the ground where it's placed,
     * turned to face the right way.
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
