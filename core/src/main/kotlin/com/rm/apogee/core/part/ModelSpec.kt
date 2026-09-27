package com.rm.apogee.core.part

import com.rm.apogee.core.math.SerialVec3
import com.rm.apogee.core.math.Vec3
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * Something the renderer can turn into a mesh: a physics primitive ([MeshSpec]) or a leaf of a
 * part's [ModelSpec].
 */
interface Shape

/**
 * What a part looks like, as opposed to [MeshSpec], which is the shape the physics uses for it.
 *
 * These are kept apart on purpose. The physics shape feeds inertia, contact points, craft-to-craft
 * collision and the joints between stacked parts. A nose cone drawn as a proper ogive instead of a
 * straight cone mustn't quietly change how a rocket flies. So a part can look as detailed as it
 * likes and still collide exactly like it always did, and every physics test guards that.
 *
 * It uses the same conventions as [MeshSpec]: metres, centred on the part's origin, with +Y as the
 * stack axis. Surface-mounted parts like fins, wings, legs and wheels are rooted at -X and reach
 * out along +X, with their chord along Y and their thickness along Z, matching their attach nodes.
 */
@Serializable
sealed interface ModelSpec : Shape {

    /**
     * A surface of revolution around +Y. [profile] is `[radius, y]` pairs from bottom to top. This
     * is the workhorse for tanks, nose cones, engine bells, pods, fuselages, spinners and rings.
     *
     * An end with a radius above zero gets closed with a flat cap, unless the part is in a stack
     * and something covers it. See StackCaps.
     */
    @Serializable
    @SerialName("lathe")
    data class Lathe(
        val profile: List<List<Double>>,
        val segments: Int = 20,
        /**
         * How far round it goes, in degrees, from [from] (0 at +X, turning toward +Z). All the way
         * for a tank, half for a fairing's shell. A partial turn isn't capped.
         */
        val sweep: Double = 360.0,
        val from: Double = 0.0,
    ) : ModelSpec

    /**
     * An ogive nose cone: tangent to a cylinder of [radius] at its base, running to a point
     * [length] above it, and blunted by [bluntness] of the radius at the tip. Centred on its
     * middle, like the cone it replaces.
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
     * A tank: a cylinder with its ends chamfered in slightly, so a stack of them looks like
     * separate vessels bolted together instead of one long tube, with [bands] raised rings around
     * the middle.
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
     * A flying surface: a trapezoid [span] long along +X from its root at -X/2, [rootChord] along Y
     * at the root narrowing to [tipChord] at the tip, with its leading edge swept back by [sweep]
     * metres over the span. [thickness] is at the root and thins toward the tip, with a diamond
     * section, so the leading and trailing edges are sharp.
     *
     * The chord is centred on Y = 0 at the root. A control surface is a Fin piece of its own,
     * hinged along its leading edge.
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
     * A body lofted through cross-sections along +Y. Each section is a rounded rectangle
     * [Section.halfWidth] across and from [Section.bottom] to [Section.top] in Z, with its lower
     * edge drawn in by [Section.vee] to make a V. Used for hulls, rover bodies and cockpit
     * canopies.
     */
    @Serializable
    @SerialName("loft")
    data class Loft(
        val sections: List<Section>,
        /** Points around each section. More is rounder. */
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
            /** 0 is flat-bottomed and 1 is a sharp V down the middle. */
            val vee: Double = 0.0,
            /** 0 is square corners and 1 is fully round. */
            val round: Double = 0.4,
        )
    }

    /**
     * A tyre around the X axis, [radius] outside and [width] across, with [lugs] tread blocks
     * around it.
     */
    @Serializable
    @SerialName("tyre")
    data class Tyre(
        val radius: Double,
        val width: Double,
        val lugs: Int = 16,
        val lugDepth: Double = 0.04,
    ) : ModelSpec

    /** A propeller with [blades] around +Y, [radius] out to the tips. */
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
     * [offset] and [rotation] (degrees around X, then Y, then Z) place it in the part. [role] says
     * what moves it, around [axis] through [pivot], both in part space. A hinged elevon turns with
     * its control deflection, a wheel spins as the ground goes by, and a leg folds as it deploys.
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
         * How far the role moves it at full travel: degrees folded away when stowed for
         * [PieceRole.DEPLOY], or metres of stroke for [PieceRole.SUSPENSION]. The piece is made in
         * its deployed, uncompressed pose.
         */
        val travel: Double = 0.0,
        /**
         * Whether this piece's ends are the part's stack ends, so a joint covered by the next part
         * in the stack is left open.
         */
        val stackEnds: Boolean = false,
    )
}

/** What colour a piece takes: the part's own, or one from a small palette. */
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
    /** Turns with the part's control deflection, up to its maximum deflection. */
    @SerialName("hinged") HINGED,
    /** Spins, like a wheel with the ground or a propeller with the throttle. */
    @SerialName("spin") SPIN,
    /** Turns with the steering, around [ModelSpec.Piece.axis]. */
    @SerialName("steer") STEER,
    /** Steers, and a wheel on it also spins. This is the tyre of a steered wheel. */
    @SerialName("steerSpin") STEER_SPIN,
    /** Folds from stowed to deployed as the leg deploys. */
    @SerialName("deploy") DEPLOY,
    /** Slides along [ModelSpec.Piece.axis] as the suspension compresses. */
    @SerialName("suspension") SUSPENSION,
    /** Swings with the engine's gimbal, around [ModelSpec.Piece.pivot]. Used for a nozzle. */
    @SerialName("gimbal") GIMBAL,
    /** Gone once the part is staged, like a fairing's shell once it opens. */
    @SerialName("jettison") JETTISON,
    /** Swings down around [ModelSpec.Piece.pivot] as the wing's flaps run out. */
    @SerialName("flap") FLAP,
    /** A sail: swings around the mast ([ModelSpec.Piece.axis] through the pivot) to its angle. */
    @SerialName("sail") SAIL,
}
