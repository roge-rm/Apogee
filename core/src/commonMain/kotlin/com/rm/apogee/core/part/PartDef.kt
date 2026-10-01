package com.rm.apogee.core.part

import com.rm.apogee.core.math.SerialVec3
import com.rm.apogee.core.math.Vec3
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import com.rm.apogee.core.math.Math

/**
 * Below this radius a part is too thin to hang things off. File level rather than in a companion: a
 * private companion hides the serializer's generated PartDef.Companion, which fails when the
 * catalogue loads, not at compile time.
 */
private const val MIN_SURFACE_MOUNT_RADIUS = 0.3

/** The target size of a buoyancy sampling cell, in metres. See [PartDef.volumeCells]. */
private const val VOLUME_CELL_METRES = 0.75

/**
 * Target size, and the most cells along any axis, for a hull's buoyancy cells. See
 * [PartDef.volumeCells].
 */
private const val HULL_CELL_METRES = 0.4
private const val HULL_CELLS = 6
private const val HULL_CELLS_DEEP = 3

/** N a joint carries per square metre of radius. See [PartDef.jointStrength]. */
private const val JOINT_STRENGTH_PER_M2 = 1.0e6

/** Minimum joint radius in metres, however thin the part. */
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
    /** Base modules: foundations, habitats, depots, pads. */
    @SerialName("base") BASE,
    /** Buildings and fittings: towers, hangars, jetties, lamps. */
    @SerialName("structure") STRUCTURE,
}

/**
 * The physics shape of a part. A tagged spec rather than a model file path, so every mesh is
 * generated at load time; a `gltf` variant could join later without breaking part files. Shapes are
 * centred on the origin with +Y the axis of revolution, so stacks are placed purely by attach
 * nodes.
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

/** How a node can be used. */
@Serializable
enum class AttachNodeKind {
    /** End to end, along the stack axis. Tank onto tank. */
    @SerialName("stack") STACK,

    /** Onto the side of another part. Boosters, fins, ladders. */
    @SerialName("surface") SURFACE,
}

/**
 * A point another part can join. [direction] points outward; two parts join with their node
 * directions facing, which makes orientation automatic.
 */
@Serializable
data class AttachNode(
    val id: String,
    val position: SerialVec3,
    val direction: SerialVec3,
    /** Size class. Nodes only join equal sizes unless an adapter bridges them. */
    val size: Int = 1,
    val kind: AttachNodeKind = AttachNodeKind.STACK,
)

/** One of several solid volumes of a part: a primitive at [offset] in the part's frame. */
@Serializable
data class HullVolume(
    val mesh: MeshSpec,
    val offset: SerialVec3 = Vec3(0.0, 0.0, 0.0),
)

/**
 * The fixed definition of a kind of part, loaded from JSON so the server can check and hash the
 * catalogue, and it can be modded without a rebuild.
 */
@Serializable
data class PartDef(
    val id: String,
    val title: String,
    val category: PartCategory,
    /** Mass with all tanks empty, in kg. */
    val dryMass: Double,
    val mesh: MeshSpec,
    /** How it's drawn, if not as [mesh]. Looks only; physics still uses [mesh]. See [ModelSpec]. */
    val model: ModelSpec? = null,
    val attachNodes: List<AttachNode> = emptyList(),
    val modules: List<PartModule> = emptyList(),
    val description: String = "",
    /** Impact speed it survives, in m/s. */
    val crashTolerance: Double = 12.0,
    /** Drag coefficient, dimensionless. */
    val dragCoefficient: Double = 0.3,
    /**
     * What the joint to its parent carries before giving, in N: force plus bending moment over its
     * diameter. Zero means size-based. See [jointStrength].
     */
    val strength: Double = 0.0,
    /**
     * Not offered in the builder: only comes off another part, like a fairing half or the sea's
     * rock.
     */
    val hidden: Boolean = false,
    /** Temperature in K before it starts to fail. Zero means by category. See [heatLimit]. */
    val maxTemperature: Double = 0.0,
    /**
     * Pressure in Pa it takes before giving. Default is twenty times Terra's; Caligo's floor is
     * nearly five times that, and a gas giant's depths have no limit.
     */
    val maxPressure: Double = 2e6,
    /**
     * Whether the sea can crush it: true for things with room inside (cabin, tank, hull, battery
     * case), false for solid lumps. Null lets its modules decide. See [isHollow].
     */
    val hollow: Boolean? = null,
    /** Cost, for a money-based career that doesn't exist. */
    val cost: Double = 0.0,
    /**
     * Volume displaced under water in m³ where [mesh] is wrong, like an open frame or a solid lump.
     * Negative means use the mesh volume. See [displacedVolume].
     */
    val displaces: Double = -1.0,
    /**
     * Its solid volumes, if not just [mesh]: like a hangar's walls and roof with room inside to
     * taxi in. [mesh] is then only the outline, for size and bounds. See [HullVolume].
     */
    val hull: List<HullVolume> = emptyList(),
    /**
     * False for things only seen, like runway paint, a mast lamp or a windsock: drawn, but nothing
     * hits them.
     */
    val solid: Boolean = true,
) {
    /**
     * The first module of type [T], or null. Called many times per part per tick, so it doesn't
     * allocate.
     */
    inline fun <reified T : PartModule> module(): T? {
        val all = modules
        for (i in all.indices) {
            val m = all[i]
            if (m is T) return m
        }
        return null
    }

    inline fun <reified T : PartModule> hasModule(): Boolean {
        val all = modules
        for (i in all.indices) if (all[i] is T) return true
        return false
    }

    /** Radius across the stack in metres, which sets the joint's width. */
    val jointRadius: Double
        get() = boundsHalfExtents.let { maxOf(it.x, it.z) }.coerceAtLeast(MIN_JOINT_RADIUS)

    /**
     * [strength], or by default from the joint's area (twice as wide holds four times as much):
     * about 390 kN at 1.25 m, 100 kN at 0.625 m.
     */
    val jointStrength: Double
        get() = if (strength > 0.0) strength else JOINT_STRENGTH_PER_M2 * jointRadius * jointRadius

    /**
     * [maxTemperature], or by default from what the part is: engines run hot, wings and tanks are
     * thin skins, heat shields are made for it.
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

    /** Total mass with every tank full, in kg. */
    val wetMass: Double
        get() = dryMass + modules.filterIsInstance<Tank>()
            .sumOf { it.capacity * it.resource.densityPerUnit }

    /**
     * Half-extents of the axis-aligned box round the mesh, for the collider and inertia. Derived
     * from the mesh so the collider can't disagree with what you see.
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
     * Generated mounting points round the hull for surface attachment: four round the waist, big
     * enough to hit with a finger, with symmetry filling in. A part that declares its own surface
     * nodes gets those instead.
     */
    val surfaceNodes: List<AttachNode> by lazy {
        if (attachNodes.any { it.kind == AttachNodeKind.SURFACE }) return@lazy emptyList()

        val radius = when (val m = mesh) {
            is MeshSpec.Cylinder -> m.radius
            is MeshSpec.Cone -> maxOf(m.bottomRadius, m.topRadius)
            is MeshSpec.Box -> m.width * 0.5
            is MeshSpec.Sphere -> m.radius
        }
        // Too small to mount anything on.
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
     * Two more generated mounting points on the lower quarters. Lying on its side, a craft has
     * waist nodes on its belly and back, and wheels or floats want these corners. Only horizontal
     * designs offer them (see [com.rm.apogee.core.craft.Attachment.openNodes]).
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
     * Room inside, so the sea can crush it: crew or probe core, tank, hull, habitat or battery.
     * [maxPressure] is its limit. Anything else is a solid lump at any depth.
     */
    val isHollow: Boolean by lazy {
        hollow ?: modules.any {
            it is Command || it is Tank || it is Buoyancy || it is Habitat || it is Battery || it is Ballast
        }
    }

    /**
     * What a craft stands on rather than rests on: a wheel, leg, skid or foot. These meet the
     * ground and decks sprung and gripping.
     */
    val foot: Boolean by lazy { module<Wheel>() != null || module<LandingLeg>() != null || module<Walker>() != null || module<Skid>() != null }

    /**
     * Mesh volume in m³: what any part displaces under water, so a sealed tank or a downed rocket
     * floats. [Buoyancy] overrides it where the mesh doesn't describe what's enclosed, and
     * [displaces] where the mesh is only an outline.
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
     * The part's volume as a grid of cells through its bounds: each cell's centre in part-local
     * space, and the cell size. Buoyancy is sampled per cell so heeling makes a righting moment and
     * waves can lift one end; pitch, roll and heave come from where the water pushes. Cells are
     * about 0.75 m, at most four per axis. Across and down a hull they're finer, [HULL_CELL_METRES]
     * and up to [HULL_CELLS], or a small boat doesn't feel the buoyancy shift as it heels.
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

    /** Size of one of [volumeCells], in metres, in part-local axes. */
    val volumeCellSize: Vec3 by lazy {
        val h = boundsHalfExtents
        Vec3(2.0 * h.x / cellsAlong(h.x, 0), 2.0 * h.y / cellsAlong(h.y, 1), 2.0 * h.z / cellsAlong(h.z, 2))
    }

    /**
     * Cells along part [axis] (0 across, 1 along, 2 up). Hulls are finer across and in depth, where
     * stability is decided, and coarse lengthwise, since every cell samples the waves every tick.
     */
    private fun cellsAlong(halfExtent: Double, axis: Int): Int =
        if (axis != 1 && module<Buoyancy>() != null) {
            kotlin.math.round(2.0 * halfExtent / HULL_CELL_METRES).toInt().coerceIn(2, if (axis == 2) HULL_CELLS_DEEP else HULL_CELLS)
        } else {
            kotlin.math.round(2.0 * halfExtent / VOLUME_CELL_METRES).toInt().coerceIn(1, 4)
        }

    /** Declared nodes plus generated surface ones. */
    val allAttachNodes: List<AttachNode> get() = attachNodes + surfaceNodes

    /**
     * Points on the hull for ground contact, in part-local space. A bounding sphere touches at one
     * point, so a craft standing on its bell would topple from rounding noise; sampled hull points
     * give it a real base. Computed once per definition.
     */
    val contactPoints: List<Vec3> by lazy {
        if (!solid) return@lazy emptyList()
        if (hull.isNotEmpty()) {
            return@lazy hull.flatMap { volume -> pointsOf(volume.mesh).map { it.addInPlace(volume.offset) } }
        }
        pointsOf(mesh)
    }

    /** Points on [shape]'s surface, in its frame, for contact. */
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

    /** Four points round a circle of [radius] at height [y]. */
    private fun rim(radius: Double, y: Double): List<Vec3> = listOf(
        Vec3(radius, y, 0.0),
        Vec3(-radius, y, 0.0),
        Vec3(0.0, y, radius),
        Vec3(0.0, y, -radius),
    )

    /** Frontal reference area for drag, in m², across the stack axis. */
    val referenceArea: Double
        get() {
            val h = boundsHalfExtents
            return Math.PI * h.x * h.z
        }
}
