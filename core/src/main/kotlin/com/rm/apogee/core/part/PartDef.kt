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

/** Target size of a buoyancy sampling cell, metres. See [PartDef.volumeCells]. */
private const val VOLUME_CELL_METRES = 0.75

/** Target size, and most along any axis, of a hull's buoyancy cells. See [PartDef.volumeCells]. */
private const val HULL_CELL_METRES = 0.4
private const val HULL_CELLS = 6
private const val HULL_CELLS_DEEP = 3

/** N a joint carries per square metre of its radius. See [PartDef.jointStrength]. */
private const val JOINT_STRENGTH_PER_M2 = 1.0e6

/** However thin a part, its joint is at least this wide, m. */
private const val MIN_JOINT_RADIUS = 0.15

@Serializable
enum class PartCategory {
    @SerialName("command") COMMAND,
    @SerialName("propulsion") PROPULSION,
    @SerialName("fuel") FUEL,
    @SerialName("structural") STRUCTURAL,
    @SerialName("aero") AERO,
    @SerialName("utility") UTILITY,
    @SerialName("ground") GROUND,
    /** Modules for building bases: foundations, habitats, depots, pads. */
    @SerialName("base") BASE,
    /** Buildings and fittings: towers, hangars, jetties, lamps. */
    @SerialName("structure") STRUCTURE,
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
sealed interface MeshSpec : Shape {
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
 * One of the volumes a part is solid as, where it is more than one: a
 * primitive, placed at [offset] in the part's own frame.
 */
@Serializable
data class HullVolume(
    val mesh: MeshSpec,
    val offset: SerialVec3 = Vec3(0.0, 0.0, 0.0),
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
    /**
     * How it is drawn, if not simply as [mesh]. Looks only: every physical
     * property still comes from [mesh]. See [ModelSpec].
     */
    val model: ModelSpec? = null,
    val attachNodes: List<AttachNode> = emptyList(),
    val modules: List<PartModule> = emptyList(),
    val description: String = "",
    /** Impact speed this survives, m/s. */
    val crashTolerance: Double = 12.0,
    /** Drag coefficient, dimensionless. */
    val dragCoefficient: Double = 0.3,
    /**
     * What the joint to its parent carries before it starts to give, N -
     * force, plus bending moment over its diameter. Zero means "by its
     * size": see [jointStrength].
     */
    val strength: Double = 0.0,
    /**
     * How hot it can get before it starts to fail, K. Zero means "by what
     * it is": see [heatLimit].
     */
    val maxTemperature: Double = 0.0,
    /** Cost, for a career mode that does not exist yet. */
    val cost: Double = 0.0,
    /**
     * What it displaces under water, m³, where its [mesh] is no guide - an
     * open frame the water runs through, a solid lump of metal. Negative
     * means "its mesh's volume": see [displacedVolume].
     */
    val displaces: Double = -1.0,
    /**
     * What it is solid as, if not simply its [mesh]: several volumes - a
     * hangar's walls and roof, with the room inside open to taxi into. [mesh]
     * is then only its outline, for its size and bounds. See [HullVolume].
     */
    val hull: List<HullVolume> = emptyList(),
    /**
     * False for things that are only seen - paint on a runway, a lamp on a
     * mast, a windsock: drawn, but nothing meets them and they cost nothing
     * in collision.
     */
    val solid: Boolean = true,
) {
    /** Convenience: the first module of a given type, or null. */
    inline fun <reified T : PartModule> module(): T? = modules.filterIsInstance<T>().firstOrNull()

    inline fun <reified T : PartModule> hasModule(): Boolean = modules.any { it is T }

    /** Its radius across the stack, m: what a joint to it is as wide as. */
    val jointRadius: Double
        get() = boundsHalfExtents.let { maxOf(it.x, it.z) }.coerceAtLeast(MIN_JOINT_RADIUS)

    /**
     * [strength], or by default by the joint's area: a ring of metal twice
     * as wide holds four times as much. A 1.25 m stack joint takes about
     * 390 kN, a 0.625 m one about 100 kN.
     */
    val jointStrength: Double
        get() = if (strength > 0.0) strength else JOINT_STRENGTH_PER_M2 * jointRadius * jointRadius

    /**
     * [maxTemperature], or by default by what the part is: engines are
     * built to run hot, wings and tanks are thin skins, and a heat shield
     * is made for nothing else.
     */
    val heatLimit: Double
        get() = when {
            maxTemperature > 0.0 -> maxTemperature
            hasModule<HeatShield>() -> 3_200.0
            else -> when (category) {
                PartCategory.COMMAND -> 1_500.0
                PartCategory.PROPULSION -> 2_000.0
                PartCategory.FUEL -> 1_300.0
                PartCategory.STRUCTURAL -> 1_500.0
                PartCategory.AERO -> 1_200.0
                PartCategory.UTILITY -> 1_300.0
                PartCategory.GROUND -> 1_100.0
                PartCategory.BASE -> 1_300.0
                PartCategory.STRUCTURE -> 1_300.0
            }
        }

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

    /**
     * Two more generated mounting points, on the lower quarters of the hull.
     *
     * The four waist nodes are all a stack needs, but a craft lying on its
     * side has one of them on its belly and one on its back, so every wheel
     * would go on the centreline in a row and the craft would fall over
     * sideways. These are the corners a pair of wheels or floats actually
     * wants. Only a horizontal design offers them - see
     * [com.rm.apogee.core.craft.Attachment.openNodes] - since on a standing
     * craft they would be two more targets cluttering every tank for no gain.
     */
    val quarterNodes: List<AttachNode> by lazy {
        if (surfaceNodes.isEmpty()) return@lazy emptyList()
        val radius = surfaceNodes.first().position.length
        val d = 1.0 / kotlin.math.sqrt(2.0)
        listOf(Vec3(d, 0.0, -d), Vec3(-d, 0.0, -d)).mapIndexed { index, direction ->
            AttachNode(
                id = "surface-q$index",
                position = direction * radius,
                direction = direction,
                size = 0,
                kind = AttachNodeKind.SURFACE,
            )
        }
    }

    /**
     * Volume of the mesh, m³ - what the part displaces when it is under water.
     *
     * Every part, not only ones carrying [Buoyancy]: a sealed tank floats
     * whether or not anyone thought of it as a boat, and a rocket that comes
     * down in the sea should bob rather than sink like a stone. [Buoyancy]
     * overrides it, for a part whose mesh does not describe what it encloses,
     * and [displaces] for one whose mesh is only its outline.
     */
    val displacedVolume: Double by lazy {
        module<Buoyancy>()?.displacedVolume ?: displaces.takeIf { it >= 0.0 } ?: when (val m = mesh) {
            is MeshSpec.Cylinder -> Math.PI * m.radius * m.radius * m.height
            is MeshSpec.Cone -> Math.PI * m.height / 3.0 *
                (m.bottomRadius * m.bottomRadius + m.bottomRadius * m.topRadius + m.topRadius * m.topRadius)
            is MeshSpec.Box -> m.width * m.height * m.depth
            is MeshSpec.Sphere -> 4.0 / 3.0 * Math.PI * m.radius * m.radius * m.radius
        }
    }

    /**
     * Where the part's volume is, as a grid of cells through its bounds: each
     * cell's centre in part-local space, and the cell's full size.
     *
     * Buoyancy is sampled cell by cell rather than as one force at the part's
     * centre, and that is the whole point of it. A force at the centre gives a
     * hull no reason to right itself when it heels, and gives a wave nothing
     * to lift one end of it by - pitch, roll and heave all come from *where*
     * the water is pushing, the same lesson drag taught the fins. Cells about
     * three-quarters of a metre on a side, at most four along any axis - and
     * finer across and down a hull, [HULL_CELL_METRES] and up to [HULL_CELLS]: two
     * cells across the skiff and one deep felt so little of the buoyancy
     * shifting to the low side as she heeled that the seat's own reaction
     * wheel could roll her over.
     */
    val volumeCells: List<Vec3> by lazy {
        val h = boundsHalfExtents
        val nx = cellsAlong(h.x, 0)
        val ny = cellsAlong(h.y, 1)
        val nz = cellsAlong(h.z, 2)
        val cells = ArrayList<Vec3>(nx * ny * nz)
        for (i in 0 until nx) for (j in 0 until ny) for (k in 0 until nz) {
            cells.add(
                Vec3(
                    -h.x + (i + 0.5) * 2.0 * h.x / nx,
                    -h.y + (j + 0.5) * 2.0 * h.y / ny,
                    -h.z + (k + 0.5) * 2.0 * h.z / nz,
                )
            )
        }
        cells
    }

    /** The size of one of [volumeCells], m, in part-local axes. */
    val volumeCellSize: Vec3 by lazy {
        val h = boundsHalfExtents
        Vec3(2.0 * h.x / cellsAlong(h.x, 0), 2.0 * h.y / cellsAlong(h.y, 1), 2.0 * h.z / cellsAlong(h.z, 2))
    }

    /**
     * Cells along part [axis] (0 across, 1 along, 2 up). A hull is finer
     * across and in depth, where its stability is decided, and as coarse as
     * anything along its length: every cell samples the waves every tick,
     * and a Trawler at the finer grid all round was over five hundred.
     */
    private fun cellsAlong(halfExtent: Double, axis: Int): Int =
        if (axis != 1 && module<Buoyancy>() != null) {
            kotlin.math.round(2.0 * halfExtent / HULL_CELL_METRES).toInt().coerceIn(2, if (axis == 2) HULL_CELLS_DEEP else HULL_CELLS)
        } else {
            kotlin.math.round(2.0 * halfExtent / VOLUME_CELL_METRES).toInt().coerceIn(1, 4)
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
        if (!solid) return@lazy emptyList()
        if (hull.isNotEmpty()) {
            return@lazy hull.flatMap { volume -> pointsOf(volume.mesh).map { it.addInPlace(volume.offset) } }
        }
        pointsOf(mesh)
    }

    /** Points on the surface of [shape], in its own frame, for contact. */
    private fun pointsOf(shape: MeshSpec): List<Vec3> =
        when (val m = shape) {
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
