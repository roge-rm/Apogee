package com.rm.apogee.core.part

import com.rm.apogee.core.math.SerialVec3
import com.rm.apogee.core.math.Vec3
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/** Something the renderer can mesh: a physics primitive ([MeshSpec]) or a leaf of a [ModelSpec]. */
interface Shape

/**
 * What a part looks like, separate from [MeshSpec], the physics shape. The physics shape drives
 * inertia, contacts, collisions and stack joints, so detailed looks can't change how a craft flies;
 * physics tests guard that.
 *
 * Same conventions as [MeshSpec]: metres, centred on the part origin, +Y the stack axis. Surface
 * parts (fins, wings, legs, wheels) are rooted at -X and reach along +X, chord along Y, thickness
 * along Z, matching their attach nodes.
 */
@Serializable
sealed interface ModelSpec : Shape {

    /**
     * A surface of revolution about +Y. [profile] is `[radius, y]` pairs, bottom to top. For tanks,
     * cones, bells, pods, fuselages, spinners and rings. Ends with radius above zero get a flat cap
     * unless a stacked part covers them (see StackCaps).
     */
    @Serializable
    @SerialName("lathe")
    data class Lathe(
        val profile: List<List<Double>>,
        val segments: Int = 20,
        /**
         * Degrees round from [from] (0 at +X, toward +Z): 360 for a tank, 180 for a fairing shell.
         * Partial sweeps aren't capped.
         */
        val sweep: Double = 360.0,
        val from: Double = 0.0,
    ) : ModelSpec

    /**
     * An ogive nose cone: tangent to a cylinder of [radius] at the base, to a point [length] above,
     * blunted by [bluntness] of the radius. Centred on its middle, like the cone it replaces.
     */
    @Serializable
    @SerialName("noseCone")
    data class NoseCone(
        val radius: Double,
        val length: Double,
        val bluntness: Double = 0.06,
        val segments: Int = 20,
    ) : ModelSpec

    /**
     * A tank: a cylinder with slightly chamfered ends, so a stack reads as separate tanks, with
     * [bands] raised rings round the middle.
     */
    @Serializable
    @SerialName("tank")
    data class Tank(
        val radius: Double,
        val height: Double,
        val chamfer: Double = 0.04,
        val bands: Int = 1,
        val segments: Int = 20,
    ) : ModelSpec

    /**
     * A flying surface: a trapezoid [span] along +X from its root at -X/2, [rootChord] along Y at
     * the root narrowing to [tipChord], leading edge swept back [sweep] metres over the span.
     * [thickness] at the root thins toward the tip, diamond section, sharp edges. Chord centred on
     * Y = 0 at the root. A control surface is its own Fin piece hinged at its leading edge.
     */
    @Serializable
    @SerialName("fin")
    data class Fin(
        val rootChord: Double,
        val tipChord: Double,
        val span: Double,
        val sweep: Double = 0.0,
        val thickness: Double = 0.08,
    ) : ModelSpec

    /**
     * A body lofted through sections along +Y. Each is a rounded rectangle [Section.halfWidth]
     * across, from [Section.bottom] to [Section.top] in Z, its lower edge drawn in by
     * [Section.vee]. For hulls, rover bodies and canopies.
     */
    @Serializable
    @SerialName("loft")
    data class Loft(
        val sections: List<Section>,
        /** Points round each section. More is rounder. */
        val around: Int = 12,
        /** Close the end sections with flat faces. */
        val capped: Boolean = true,
    ) : ModelSpec {
        @Serializable
        data class Section(
            val y: Double,
            val halfWidth: Double,
            val top: Double,
            val bottom: Double,
            /** 0 flat-bottomed, 1 a sharp V. */
            val vee: Double = 0.0,
            /** 0 square corners, 1 fully round. */
            val round: Double = 0.4,
        )
    }

    /** A tire about the X axis, [radius] outside, [width] across, with [lugs] tread blocks. */
    @Serializable
    @SerialName("tyre")
    data class Tyre(
        val radius: Double,
        val width: Double,
        val lugs: Int = 16,
        val lugDepth: Double = 0.04,
    ) : ModelSpec

    /** A propeller with [blades] about +Y, [radius] to the tips. */
    @Serializable
    @SerialName("prop")
    data class Prop(
        val radius: Double,
        val blades: Int = 3,
        val chord: Double = 0.12,
        val pitchDegrees: Double = 20.0,
    ) : ModelSpec

    /** A plain [MeshSpec] shape, for a bracket or block. */
    @Serializable
    @SerialName("primitive")
    data class Primitive(val mesh: MeshSpec) : ModelSpec

    /** Several pieces, each placed, tinted and possibly animated. */
    @Serializable
    @SerialName("compound")
    data class Compound(val pieces: List<Piece>) : ModelSpec

    /**
     * One piece of a [Compound]. [offset] and [rotation] (degrees about X, then Y, then Z) place it
     * in the part. [role] says what moves it, about [axis] through [pivot], both in part space.
     */
    @Serializable
    data class Piece(
        val model: ModelSpec,
        val offset: SerialVec3 = Vec3(0.0, 0.0, 0.0),
        val rotation: SerialVec3 = Vec3(0.0, 0.0, 0.0),
        val tint: Tint = Tint.BODY,
        val role: PieceRole = PieceRole.FIXED,
        val axis: SerialVec3 = Vec3(1.0, 0.0, 0.0),
        val pivot: SerialVec3 = Vec3(0.0, 0.0, 0.0),
        /**
         * Full travel of the role: degrees folded when stowed for [PieceRole.DEPLOY], or metres of
         * stroke for [PieceRole.SUSPENSION]. Modelled deployed and uncompressed.
         */
        val travel: Double = 0.0,
        /**
         * Whether this piece's ends are the part's stack ends, so a joint covered by the next part
         * is left open.
         */
        val stackEnds: Boolean = false,
    )
}

/** A piece's colour: the part's own or one from a small palette. */
@Serializable
enum class Tint {
    /** The part's category colour. */
    @SerialName("body") BODY,
    @SerialName("dark") DARK,
    @SerialName("metal") METAL,
    @SerialName("rubber") RUBBER,
    @SerialName("glass") GLASS,
    @SerialName("accent") ACCENT,
    @SerialName("light") LIGHT,
    /** The rocket stack's colour on any part, like an engine's shroud. */
    @SerialName("stack") STACK,
}

/** What moves a [ModelSpec.Piece]. */
@Serializable
enum class PieceRole {
    @SerialName("fixed") FIXED,
    /** Turns with the part's control deflection, up to its maximum. */
    @SerialName("hinged") HINGED,
    /** Spins, like a wheel with the ground or a propeller with the throttle. */
    @SerialName("spin") SPIN,
    /** Turns with steering, about [ModelSpec.Piece.axis]. */
    @SerialName("steer") STEER,
    /** Steers and spins: a steered wheel's tire. */
    @SerialName("steerSpin") STEER_SPIN,
    /** Folds from stowed to deployed as the leg deploys. */
    @SerialName("deploy") DEPLOY,
    /** Slides along [ModelSpec.Piece.axis] as the suspension compresses. */
    @SerialName("suspension") SUSPENSION,
    /** Swings with the engine gimbal about [ModelSpec.Piece.pivot]. For nozzles. */
    @SerialName("gimbal") GIMBAL,
    /** Gone once the part is staged, like a fairing's shell. */
    @SerialName("jettison") JETTISON,
    /** Swings down about [ModelSpec.Piece.pivot] as the flaps run out. */
    @SerialName("flap") FLAP,
    /** A sail: swings about the mast ([ModelSpec.Piece.axis] through the pivot) to its angle. */
    @SerialName("sail") SAIL,
    /**
     * A gear door: modelled shut, it swings [ModelSpec.Piece.travel] degrees about the axis through
     * the pivot to open. Open while the gear is out or on its way; it stays on the body as the gear
     * folds.
     */
    @SerialName("door") DOOR,
}
