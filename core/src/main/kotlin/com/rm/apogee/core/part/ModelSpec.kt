package com.rm.apogee.core.part

import com.rm.apogee.core.math.SerialVec3
import com.rm.apogee.core.math.Vec3
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * Something the renderer can turn into a mesh: a physics primitive
 * ([MeshSpec]) or a leaf of a part's [ModelSpec].
 */
interface Shape

/**
 * What a part looks like, as opposed to [MeshSpec], which is the shape the
 * physics uses for it.
 *
 * Kept apart deliberately. The physics shape feeds inertia, contact points,
 * craft-to-craft collision and the joints between stacked parts; a nose cone
 * drawn as a proper ogive instead of a straight cone must not quietly change
 * how a rocket flies. So a part can look as detailed as it likes and collide
 * exactly as it always did, and every physics test stands guard over that.
 *
 * Same conventions as [MeshSpec]: metres, centred on the part's origin, +Y the
 * stack axis. Surface-mounted parts - fins, wings, legs, wheels - are rooted
 * at -X and reach out along +X, with their chord along Y and their thickness
 * along Z, matching their attach nodes.
 */
@Serializable
sealed interface ModelSpec : Shape {

    /**
     * A surface of revolution about +Y: [profile] is `[radius, y]` pairs from
     * bottom to top. The workhorse - tanks, nose cones, engine bells, pods,
     * fuselages, spinners, rings.
     *
     * An end with a nonzero radius is closed with a flat cap, unless the part
     * is in a stack and something covers it - see StackCaps.
     */
    @Serializable
    @SerialName("lathe")
    data class Lathe(
        val profile: List<List<Double>>,
        val segments: Int = 20,
    ) : ModelSpec

    /**
     * An ogive nose cone: tangent to a cylinder of [radius] at its base, to a
     * point [length] above it, blunted by [bluntness] of the radius at the
     * tip. Centred on its middle, like the cone it replaces.
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
     * A tank: a cylinder with its ends chamfered in slightly, so a stack of
     * them reads as separate vessels bolted together rather than one long
     * tube, and [bands] raised rings round the middle.
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
     * A flying surface: a trapezoid [span] long along +X from its root at
     * -X/2, [rootChord] along Y at the root narrowing to [tipChord] at the
     * tip, whose leading edge is swept back by [sweep] metres over the span.
     * [thickness] is at the root, thinning towards the tip, with a diamond
     * section - sharp leading and trailing edges.
     *
     * The chord is centred on Y = 0 at the root. A control surface is a Fin
     * piece of its own, hinged along its leading edge.
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
     * A body lofted through cross-sections along +Y. Each section is a
     * rounded rectangle [Section.halfWidth] across and from [Section.bottom]
     * to [Section.top] in Z, with its lower edge drawn in by [Section.vee]
     * to make a V - hulls, rover bodies, cockpit canopies.
     */
    @Serializable
    @SerialName("loft")
    data class Loft(
        val sections: List<Section>,
        /** Points round each section. More is rounder. */
        val around: Int = 12,
        /** Close the first and last sections with flat faces. */
        val capped: Boolean = true,
    ) : ModelSpec {
        @Serializable
        data class Section(
            val y: Double,
            val halfWidth: Double,
            val top: Double,
            val bottom: Double,
            /** 0 flat-bottomed to 1 a sharp V down the middle. */
            val vee: Double = 0.0,
            /** 0 square corners to 1 fully round. */
            val round: Double = 0.4,
        )
    }

    /**
     * A tyre about the X axis: [radius] outside, [width] across, with [lugs]
     * tread blocks round it.
     */
    @Serializable
    @SerialName("tyre")
    data class Tyre(
        val radius: Double,
        val width: Double,
        val lugs: Int = 16,
        val lugDepth: Double = 0.04,
    ) : ModelSpec

    /** A propeller of [blades] about +Y, [radius] to the tips. */
    @Serializable
    @SerialName("prop")
    data class Prop(
        val radius: Double,
        val blades: Int = 3,
        val chord: Double = 0.12,
        val pitchDegrees: Double = 20.0,
    ) : ModelSpec

    /** A plain [MeshSpec] shape, for the odd bracket or block. */
    @Serializable
    @SerialName("primitive")
    data class Primitive(val mesh: MeshSpec) : ModelSpec

    /** Several pieces, each placed, tinted and possibly animated. */
    @Serializable
    @SerialName("compound")
    data class Compound(val pieces: List<Piece>) : ModelSpec

    /**
     * One piece of a [Compound].
     *
     * [offset] and [rotation] (degrees about X, then Y, then Z) place it in
     * the part. [role] says what moves it, about [axis] through [pivot] -
     * both in part space. A hinged elevon turns with its control deflection,
     * a wheel spins with the ground going by, a leg folds with its deploy.
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
         * How far the role moves it at full travel: degrees folded away when
         * stowed for [PieceRole.DEPLOY], metres of stroke for
         * [PieceRole.SUSPENSION]. The piece is authored in its deployed,
         * uncompressed pose.
         */
        val travel: Double = 0.0,
        /**
         * Whether this piece's ends are the part's stack ends, so a joint
         * covered by the next part in the stack is left open.
         */
        val stackEnds: Boolean = false,
    )
}

/** What colour a piece takes: the part's own, or one of a small palette. */
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
}

/** What moves a [ModelSpec.Piece]. */
@Serializable
enum class PieceRole {
    @SerialName("fixed") FIXED,
    /** Turns with the part's control deflection, up to its max deflection. */
    @SerialName("hinged") HINGED,
    /** Spins: a wheel with the ground, a propeller with the throttle. */
    @SerialName("spin") SPIN,
    /** Turns with the steering, about [ModelSpec.Piece.axis]. */
    @SerialName("steer") STEER,
    /** Steers, and a wheel on it also spins - the tyre of a steered wheel. */
    @SerialName("steerSpin") STEER_SPIN,
    /** Folds from stowed to deployed with the leg's deploy progress. */
    @SerialName("deploy") DEPLOY,
    /** Slides along [ModelSpec.Piece.axis] with suspension compression. */
    @SerialName("suspension") SUSPENSION,
    /** Swings with the engine's gimbal, about [ModelSpec.Piece.pivot]: a nozzle. */
    @SerialName("gimbal") GIMBAL,
}
