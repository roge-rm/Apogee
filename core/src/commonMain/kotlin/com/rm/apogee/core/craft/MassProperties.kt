package com.rm.apogee.core.craft

import com.rm.apogee.core.math.Mat3
import com.rm.apogee.core.math.Quat
import com.rm.apogee.core.math.Vec3
import com.rm.apogee.core.part.MeshSpec
import com.rm.apogee.core.part.PartDef
import com.rm.apogee.core.fixed

/**
 * Total mass, centre of mass and inertia tensor of a built craft. Recomputed as propellant drains,
 * since mass and centre of mass shift a lot over a burn.
 */
class MassProperties(
    /** In kg. */
    val mass: Double,
    /** Craft-local, in metres from the design origin. */
    val centerOfMass: Vec3,
    /** About the centre of mass, in craft-local axes. */
    val inertia: Mat3,
) {
    val inverseMass: Double = if (mass > 0.0) 1.0 / mass else 0.0
    val inverseInertia: Mat3 = inertia.inverted()

    override fun toString(): String =
        "MassProperties(mass=${fixed(mass, 1)}kg, com=$centerOfMass)"

    companion object {

        /**
         * Builds the craft's properties from its parts.
         *
         * @param partMasses each part's current mass in [design]'s order, propellant included.
         */
        fun compute(
            design: CraftDesign,
            defs: List<PartDef>,
            partMasses: DoubleArray,
        ): MassProperties {
            require(defs.size == design.parts.size && partMasses.size == defs.size) {
                "compute() needs one definition and one mass per placed part " +
                    "(${design.parts.size} parts, ${defs.size} defs, ${partMasses.size} masses)"
            }

            var totalMass = 0.0
            val com = Vec3.zero()
            for (i in design.parts.indices) {
                val m = partMasses[i]
                totalMass += m
                com.addScaledInPlace(design.parts[i].position, m)
            }

            if (totalMass <= 0.0) {
                return MassProperties(0.0, Vec3.zero(), Mat3())
            }
            com.mulInPlace(1.0 / totalMass)

            // Each part's own tensor rotated into craft axes, plus its parallel-axis term about the
            // craft's centre of mass.
            val inertia = Mat3()
            val offset = Vec3()
            for (i in design.parts.indices) {
                val placed = design.parts[i]
                val local = partInertia(defs[i], partMasses[i])
                val rotated = Mat3().setRotated(local, placed.rotation)
                inertia.addInPlace(rotated)

                offset.setTo(placed.position).subInPlace(com)
                inertia.addInPlace(Mat3.parallelAxisTerm(partMasses[i], offset))
            }

            return MassProperties(totalMass, com, inertia)
        }

        /**
         * A part's inertia tensor about its own centre, from the exact tensor of its mesh
         * primitive. A cylinder resists roll half as much as pitch, so a box would make long stacks
         * roll too slowly. +Y is the axis of revolution, as for meshes.
         */
        fun partInertia(def: PartDef, mass: Double): Mat3 = when (val mesh = def.mesh) {
            is MeshSpec.Cylinder -> {
                val r2 = mesh.radius * mesh.radius
                val h2 = mesh.height * mesh.height
                Mat3.diagonal(
                    mass * (3.0 * r2 + h2) / 12.0,
                    mass * r2 / 2.0,
                    mass * (3.0 * r2 + h2) / 12.0,
                )
            }

            is MeshSpec.Cone -> {
                // A cylinder of the average radius. The error is small next to the parallel-axis
                // term for parts off the centre of mass.
                val r = (mesh.bottomRadius + mesh.topRadius) * 0.5
                val r2 = r * r
                val h2 = mesh.height * mesh.height
                Mat3.diagonal(
                    mass * (3.0 * r2 + h2) / 12.0,
                    mass * r2 / 2.0,
                    mass * (3.0 * r2 + h2) / 12.0,
                )
            }

            is MeshSpec.Box -> {
                val w2 = mesh.width * mesh.width
                val h2 = mesh.height * mesh.height
                val d2 = mesh.depth * mesh.depth
                Mat3.diagonal(
                    mass * (h2 + d2) / 12.0,
                    mass * (w2 + d2) / 12.0,
                    mass * (w2 + h2) / 12.0,
                )
            }

            is MeshSpec.Sphere -> {
                val i = 0.4 * mass * mesh.radius * mesh.radius
                Mat3.diagonal(i, i, i)
            }
        }

        /** No rotation, for tests and single-part craft. */
        fun ofSinglePart(def: PartDef, mass: Double, position: Vec3 = Vec3.zero()) =
            MassProperties(
                mass = mass,
                centerOfMass = position.copy(),
                inertia = Mat3().setRotated(partInertia(def, mass), Quat.identity()),
            )
    }
}
