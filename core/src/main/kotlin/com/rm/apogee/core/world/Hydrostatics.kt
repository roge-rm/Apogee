package com.rm.apogee.core.world

import com.rm.apogee.core.craft.Vessel
import com.rm.apogee.core.math.Quat
import com.rm.apogee.core.math.Vec3
import com.rm.apogee.core.orbit.CelestialBody
import kotlin.math.abs

/**
 * Water: buoyancy and drag, cell by cell.
 *
 * Each part's volume is carried as a grid of cells
 * ([com.rm.apogee.core.part.PartDef.volumeCells]), and each cell is weighed
 * against the water surface where it is. The fraction of a cell below the
 * surface displaces that fraction of its volume, pushing up at the cell and
 * not at the craft's centre - so a hull that heels has more of itself under
 * on the low side and rights itself, and when there are waves, a crest under
 * the bow lifts the bow. One force at the centre of mass gives neither, and is
 * the failure this is built to avoid.
 *
 * Drag is per cell too, and per axis of the part it belongs to: water resists
 * a cell's motion across each face in proportion to that face's area. A long,
 * narrow hull therefore slides easily along its length and hardly at all
 * sideways - which is a keel, arrived at without anything called a keel, and
 * what lets a boat turn rather than skate.
 */
class Hydrostatics {

    private val rotation = Quat()
    private val bodyFixed = Vec3()
    private val offset = Vec3()
    private val point = Vec3()
    private val waterVelocity = Vec3()
    private val relative = Vec3()
    private val local = Vec3()
    private val force = Vec3()
    private val up = Vec3()
    private val cellAxis = Vec3()

    /** Submerged volume last tick, m³, for tests and the HUD. */
    var submergedVolume: Double = 0.0
        private set

    fun apply(vessel: Vessel, attractor: CelestialBody, time: Double, dt: Double) {
        submergedVolume = 0.0
        val ocean = attractor.ocean ?: return
        val body = vessel.body

        // Nowhere near the water. The generous margin is the craft's own
        // reach, since that is how far below its centre a cell can be.
        val altitude = attractor.altitudeOf(body.position)
        if (altitude - vessel.contactRadius > SURFACE_MARGIN) return

        // Whether there is sea here at all, asked once for the whole craft
        // rather than per cell: the height field is the most expensive thing
        // in the step, and a craft is small beside a coastline.
        attractor.rotationAt(time, rotation)
        attractor.toBodyFixed(body.position, rotation, bodyFixed)
        val terrain = attractor.terrain
        if (terrain != null &&
            terrain.elevation(bodyFixed) >= ocean.surfaceHeight(bodyFixed, time)
        ) return

        val g = attractor.gravityAt(body.position, force).length
        val rho = ocean.density

        // Cells go into a pass that first counts what is under, so drag can
        // be capped by each cell's share of the craft's momentum.
        var submergedCells = 0
        for (i in vessel.defs.indices) {
            val def = vessel.defs[i]
            for (cell in def.volumeCells) {
                vessel.partPointOffsetWorld(i, cell, offset)
                point.setTo(offset).addInPlace(body.position)
                if (depthFraction(vessel, i, point, attractor, ocean, time) > 0.0) submergedCells++
            }
        }
        if (submergedCells == 0) return

        for (i in vessel.defs.indices) {
            val def = vessel.defs[i]
            val cells = def.volumeCells
            val cellVolume = def.displacedVolume / cells.size
            val size = def.volumeCellSize
            val placed = vessel.design.parts[i]

            for (cell in cells) {
                vessel.partPointOffsetWorld(i, cell, offset)
                point.setTo(offset).addInPlace(body.position)
                val fraction = depthFraction(vessel, i, point, attractor, ocean, time)
                if (fraction <= 0.0) continue

                // Buoyancy: the weight of the water displaced, straight up.
                val displaced = cellVolume * fraction
                submergedVolume += displaced
                up.setTo(point).normalizeInPlace()
                force.setTo(up).mulInPlace(rho * g * displaced)
                body.applyForceAtOffset(force, offset)

                // Drag against the water, which moves with the ground.
                body.velocityAtOffset(offset, relative)
                attractor.surfaceVelocityAt(point, waterVelocity)
                relative.subInPlace(waterVelocity)
                val speed = relative.length
                if (speed < 1e-6) continue

                // Into the part's own axes, where its face areas are known.
                body.orientation.inverseRotate(relative, local)
                placed.rotation.inverseRotate(local, local)
                val areaX = size.y * size.z * fraction
                val areaY = size.x * size.z * fraction
                val areaZ = size.x * size.y * fraction
                // |v| + a constant, not |v|: the constant is wave-making.
                // A hull moving through the surface sheds energy into the
                // waves it makes, in proportion to speed rather than its
                // square, and that is what stops a floating boat bobbing. On
                // quadratic drag alone the stock boat was still heaving at a
                // tenth of a metre a second half a minute after launch.
                local.setTo(
                    -0.5 * rho * WATER_CD * areaX * (abs(local.x) + WAVE_MAKING_SPEED) * local.x,
                    -0.5 * rho * WATER_CD * areaY * (abs(local.y) + WAVE_MAKING_SPEED) * local.y,
                    -0.5 * rho * WATER_CD * areaZ * (abs(local.z) + WAVE_MAKING_SPEED) * local.z,
                )
                placed.rotation.rotate(local, force)
                body.orientation.rotate(force, force)

                // Never more than stops this cell's share of the craft in one
                // tick. Quadratic drag on a craft arriving at a hundred metres
                // a second is a force that would reverse its motion, and an
                // explicit step would fling it back out of the water.
                val limit = body.mass / submergedCells * speed / dt
                val magnitude = force.length
                if (magnitude > limit) force.mulInPlace(limit / magnitude)
                body.applyForceAtOffset(force, offset)
            }
        }
    }

    /**
     * How much of the cell centred at [point] is under water, 0..1.
     *
     * A cell straddling the surface is partly submerged in proportion to how
     * far its centre is below it, over the cell's own height as seen from
     * above - which for a cell tipped on its side is its width, not its
     * height. Without that the lift would switch on and off as each cell
     * centre crossed the surface, and a floating hull would buzz.
     */
    private fun depthFraction(
        vessel: Vessel,
        partIndex: Int,
        point: Vec3,
        attractor: CelestialBody,
        ocean: com.rm.apogee.core.terrain.Ocean,
        time: Double,
    ): Double {
        attractor.toBodyFixed(point, rotation, bodyFixed)
        val surface = attractor.radius + ocean.surfaceHeight(bodyFixed, time)
        val depth = surface - point.length

        // The cell's vertical extent: its three edges projected onto the local
        // vertical.
        val def = vessel.defs[partIndex]
        val size = def.volumeCellSize
        val placed = vessel.design.parts[partIndex]
        up.setTo(point).normalizeInPlace()
        vessel.body.orientation.inverseRotate(up, cellAxis)
        placed.rotation.inverseRotate(cellAxis, cellAxis)
        val height = abs(cellAxis.x) * size.x + abs(cellAxis.y) * size.y + abs(cellAxis.z) * size.z

        return (depth / height + 0.5).coerceIn(0.0, 1.0)
    }

    private companion object {
        /** Metres above the surface within which a craft is worth sampling. */
        const val SURFACE_MARGIN = 2.0

        /**
         * Drag coefficient of a hull face moving through water. Bluff-body
         * order; a hull is not streamlined, but it is long, and the per-face
         * areas are what make it prefer to go forwards.
         */
        const val WATER_CD = 0.8

        /**
         * The linear part of water drag, as the speed at which it equals the
         * quadratic part. Sized so the stock boat's heave is damped to about
         * a third of critical: settles in a couple of bobs, still visibly
         * floats rather than being set in jelly.
         */
        const val WAVE_MAKING_SPEED = 1.0
    }
}
