package com.rm.apogee.core.craft

import com.rm.apogee.core.math.Mat3
import com.rm.apogee.core.math.Quat
import com.rm.apogee.core.math.Vec3
import com.rm.apogee.core.part.MeshSpec
import com.rm.apogee.core.part.PartDef
import com.rm.apogee.core.fixed

/**
 * The total mass, centre of mass and inertia tensor of a built craft.
 *
 * It gets worked out again as propellant drains, and that's not just an optimisation detail. A
 * rocket at burnout is a fraction of its launch mass and its centre of mass has moved by metres. A
 * craft that ignored that would fly nothing like one that didn't.
 */
class MassProperties(
    /** In kg. */
    val mass: Double,
    /** In craft-local space, in metres from the design origin. */
    val centerOfMass: Vec3,
    /** Around the centre of mass, in craft-local axes. */
    val inertia: Mat3,
) {
    val inverseMass: Double = if (mass > 0.0) 1.0 / mass else 0.0
    val inverseInertia: Mat3 = inertia.inverted()

    override fun toString(): String =
        "MassProperties(mass=${fixed(mass, 1)}kg, com=$centerOfMass)"

    companion object {

        /**
         * Builds up the craft's properties from its parts.
         *
         * @param partMasses the current mass of each part in [design]'s order, including whatever
         *     propellant it's holding right now.
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

            // Add up each part's own tensor, rotated into craft axes, plus its parallel-axis term
            // around the craft's centre of mass.
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
         * A part's inertia tensor around its own centre, from its mesh shape.
         *
         * This uses the real exact tensor for each primitive instead of treating everything as a
         * box. A fuel tank is a cylinder, and a cylinder only resists roll half as much as it
         * resists pitch. Getting that wrong makes long stacks roll far too slowly.
         *
         * +Y is the axis of revolution, to match the mesh convention.
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
                // Treated as a cylinder of the average radius. The error is small next to the
                // parallel-axis term for any part that isn't sitting on the craft's centre of mass,
                // which is most of them.
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

        /** A helper with no rotation, for tests and single-part craft. */
        fun ofSinglePart(def: PartDef, mass: Double, position: Vec3 = Vec3.zero()) =
            MassProperties(
                mass = mass,
                centerOfMass = position.copy(),
                inertia = Mat3().setRotated(partInertia(def, mass), Quat.identity()),
            )
    }
}
