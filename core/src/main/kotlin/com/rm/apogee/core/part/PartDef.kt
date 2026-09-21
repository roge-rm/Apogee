package com.rm.apogee.core.part

import com.rm.apogee.core.math.SerialVec3
import com.rm.apogee.core.math.Vec3
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * Below this radius a part is too slender to hang things off.
 *
 * File-level rather than in a companion: kotlinx-serialization generates
 * PartDef.Companion for `serializer()`, and declaring a private companion
 * makes that generated accessor unreachable from outside the class - which
 * fails at *catalogue load*, not at compile time.
 */
private const val MIN_SURFACE_MOUNT_RADIUS = 0.3

@Serializable
enum class PartCategory {
    @SerialName("command") COMMAND,
    @SerialName("propulsion") PROPULSION,
    @SerialName("fuel") FUEL,
    @SerialName("structural") STRUCTURAL,
    @SerialName("aero") AERO,
    @SerialName("utility") UTILITY,
    @SerialName("ground") GROUND,
}

/**
 * The shape a part is drawn as.
 *
 * A tagged spec rather than a model-file path, so the whole asset pipeline
 * stays out of the early milestones - every mesh in the game is generated from
 * one of these at load time. A `gltf` variant can be added to this hierarchy
 * later without invalidating a single existing part file, which is the point
 * of making it a sealed type now rather than a string.
 *
 * Convention: shapes are centred on the origin and +Y is the axis of
 * revolution, so a stack of parts is positioned purely by attachment nodes.
 */
@Serializable
sealed interface MeshSpec {
    @Serializable
    @SerialName("cylinder")
    data class Cylinder(val radius: Double, val height: Double) : MeshSpec

    @Serializable
    @SerialName("cone")
    data class Cone(
        val bottomRadius: Double,
        val topRadius: Double,
        val height: Double,
    ) : MeshSpec

    @Serializable
    @SerialName("box")
    data class Box(val width: Double, val height: Double, val depth: Double) : MeshSpec

    @Serializable
    @SerialName("sphere")
    data class Sphere(val radius: Double) : MeshSpec
}

/** How a node may be used. */
@Serializable
enum class AttachNodeKind {
    /** End-to-end, along the stack axis. Tank onto tank. */
    @SerialName("stack") STACK,

    /** Onto the side of another part. Boosters, fins, ladders. */
    @SerialName("surface") SURFACE,
}

/**
 * A point where another part may be joined.
 *
 * [direction] points *outward* from the part. Two parts join when one's node
 * direction opposes the other's, which is what makes attachment orientation
 * automatic rather than something the player has to get right by hand on a
 * touch screen.
 */
@Serializable
data class AttachNode(
    val id: String,
    val position: SerialVec3,
    val direction: SerialVec3,
    /**
     * Size class. Nodes only mate with equal sizes unless an adapter part
     * bridges them, which is what stops a 0.6 m probe core being bolted
     * straight onto a 2.5 m booster.
     */
    val size: Int = 1,
    val kind: AttachNodeKind = AttachNodeKind.STACK,
)

/**
 * The immutable definition of a kind of part.
 *
 * Loaded from JSON rather than written in Kotlin so the catalogue is data the
 * server can validate and hash, and so it is moddable without a rebuild.
 */
@Serializable
data class PartDef(
    val id: String,
    val title: String,
    val category: PartCategory,
    /** Mass with all tanks empty, kg. */
    val dryMass: Double,
    val mesh: MeshSpec,
    val attachNodes: List<AttachNode> = emptyList(),
    val modules: List<PartModule> = emptyList(),
    val description: String = "",
    /** Impact speed this survives, m/s. */
    val crashTolerance: Double = 12.0,
    /** Drag coefficient, dimensionless. */
    val dragCoefficient: Double = 0.3,
    /** Cost, for a career mode that does not exist yet. */
    val cost: Double = 0.0,
) {
    /** Convenience: the first module of a given type, or null. */
    inline fun <reified T : PartModule> module(): T? = modules.filterIsInstance<T>().firstOrNull()

    inline fun <reified T : PartModule> hasModule(): Boolean = modules.any { it is T }

    /** Total mass including a full load of every tank, kg. */
    val wetMass: Double
        get() = dryMass + modules.filterIsInstance<Tank>()
            .sumOf { it.capacity * it.resource.densityPerUnit }

    /**
     * Half-extents of the axis-aligned box bounding this part's mesh.
     *
     * Used for the collider and for the inertia approximation. Keeping it
     * derived from the mesh rather than authored separately means a part can
     * never have a collider that disagrees with what the player sees.
     */
    val boundsHalfExtents: Vec3
        get() = when (val m = mesh) {
            is MeshSpec.Cylinder -> Vec3(m.radius, m.height * 0.5, m.radius)
            is MeshSpec.Cone -> {
                val r = maxOf(m.bottomRadius, m.topRadius)
                Vec3(r, m.height * 0.5, r)
            }
            is MeshSpec.Box -> Vec3(m.width * 0.5, m.height * 0.5, m.depth * 0.5)
            is MeshSpec.Sphere -> Vec3(m.radius, m.radius, m.radius)
        }

    /**
     * Mounting points generated around the part's hull, for surface attachment.
     *
     * Real surface attachment is freeform - you stick a fin anywhere on a tank -
     * but freeform placement needs the player to aim precisely at a curved
     * surface with a fingertip, which is the worst case for touch. Four
     * generated points around the waist give the same capability with a target
     * big enough to hit, and symmetry then fills in the rest.
     *
     * Authored nodes always win: a part that declares its own surface nodes
     * gets those instead, so the generated ones are a default rather than an
     * imposition.
     */
    val surfaceNodes: List<AttachNode> by lazy {
        if (attachNodes.any { it.kind == AttachNodeKind.SURFACE }) return@lazy emptyList()

        val radius = when (val m = mesh) {
            is MeshSpec.Cylinder -> m.radius
            is MeshSpec.Cone -> maxOf(m.bottomRadius, m.topRadius)
            is MeshSpec.Box -> m.width * 0.5
            is MeshSpec.Sphere -> m.radius
        }
        // Too small to mount anything on sensibly.
        if (radius < MIN_SURFACE_MOUNT_RADIUS) return@lazy emptyList()

        listOf(
            Vec3(1.0, 0.0, 0.0),
            Vec3(-1.0, 0.0, 0.0),
            Vec3(0.0, 0.0, 1.0),
            Vec3(0.0, 0.0, -1.0),
        ).mapIndexed { index, direction ->
            AttachNode(
                id = "surface-$index",
                position = direction * radius,
                direction = direction,
                size = 0,
                kind = AttachNodeKind.SURFACE,
            )
        }
    }

    /** Authored nodes plus the generated surface ones. */
    val allAttachNodes: List<AttachNode> get() = attachNodes + surfaceNodes

    /**
     * Points on the part's hull used for ground contact, in part-local space.
     *
     * A part is *not* collided as a bounding sphere. A sphere touches a plane
     * at exactly one point, which gives a resting craft no footprint and no
     * resistance to tipping - a rocket standing on its engine bell becomes a
     * literal inverted pendulum and topples from numerical noise alone. Sampling
     * the hull gives contacts a real base, so a wide part resists tipping and a
     * narrow one does not, which is the behaviour the player expects.
     *
     * Computed once per definition; the catalogue is loaded once.
     */
    val contactPoints: List<Vec3> by lazy {
        when (val m = mesh) {
            is MeshSpec.Cylinder -> rim(m.radius, -m.height * 0.5) + rim(m.radius, m.height * 0.5)
            is MeshSpec.Cone ->
                rim(m.bottomRadius, -m.height * 0.5) + rim(m.topRadius, m.height * 0.5)
            is MeshSpec.Box -> {
                val hx = m.width * 0.5
                val hy = m.height * 0.5
                val hz = m.depth * 0.5
                buildList {
                    for (sx in intArrayOf(-1, 1)) for (sy in intArrayOf(-1, 1)) {
                        for (sz in intArrayOf(-1, 1)) add(Vec3(sx * hx, sy * hy, sz * hz))
                    }
                }
            }
            is MeshSpec.Sphere -> {
                val r = m.radius
                listOf(
                    Vec3(r, 0.0, 0.0), Vec3(-r, 0.0, 0.0),
                    Vec3(0.0, r, 0.0), Vec3(0.0, -r, 0.0),
                    Vec3(0.0, 0.0, r), Vec3(0.0, 0.0, -r),
                )
            }
        }
    }

    /** Four points around a circle of [radius] at height [y]. */
    private fun rim(radius: Double, y: Double): List<Vec3> = listOf(
        Vec3(radius, y, 0.0),
        Vec3(-radius, y, 0.0),
        Vec3(0.0, y, radius),
        Vec3(0.0, y, -radius),
    )

    /** Frontal reference area for drag, m², taken across the stack axis. */
    val referenceArea: Double
        get() {
            val h = boundsHalfExtents
            return Math.PI * h.x * h.z
        }
}
