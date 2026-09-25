package com.rm.apogee.render

import com.rm.apogee.core.math.Quat
import com.rm.apogee.core.math.Vec3
import com.rm.apogee.core.part.AeroSurface
import com.rm.apogee.core.part.MeshSpec
import com.rm.apogee.core.part.ModelSpec
import com.rm.apogee.core.part.PartDef
import com.rm.apogee.core.part.PieceRole
import com.rm.apogee.core.part.Shape
import com.rm.apogee.core.part.Tint
import kotlin.math.hypot
import kotlin.math.max

/**
 * How a part's moving pieces are posed this frame. All zero is a part at rest:
 * surfaces neutral, wheels straight and still, legs deployed, springs slack.
 */
class PartAnim(
    /** Control deflection, -1..1 of the surface's travel. */
    var deflection: Double = 0.0,
    /** Steering angle, radians. */
    var steer: Double = 0.0,
    /** Accumulated spin, radians - a wheel's roll, a propeller's turn. */
    var spin: Double = 0.0,
    /** Landing-gear deploy progress, 0 stowed to 1 deployed. */
    var deploy: Double = 1.0,
    /** Suspension compression, metres. */
    var compression: Double = 0.0,
    /** Engine gimbal, -1..1 of its range, about pitch (X) and yaw (Z). */
    var gimbalPitch: Double = 0.0,
    var gimbalYaw: Double = 0.0,
    /**
     * For a wheel: turns its tyre from the authored axle, X, to the craft's
     * real one - across the craft's forward, level with its up - whatever the
     * wheel is mounted on. A gear leg under a fuselage and a wheel on a
     * rover's flank are the same part rotated differently, and both must
     * roll the way the craft drives. Null for anything that is not a wheel.
     */
    var wheelAlign: Quat? = null,
    /** For a wheel: the craft's up in part space, the axis it steers about. */
    var steerAxis: Vec3? = null,
    /**
     * For a control surface: which way round its hinge a positive deflection
     * turns it, as mounted. See [PartModels.alignSurface].
     */
    var hingeSign: Double = 1.0,
)

/**
 * Turns a part into what to draw: its [ModelSpec] - or, without one, its
 * physics [MeshSpec] - as leaf shapes placed in part space, tinted, and posed
 * by a [PartAnim].
 */
object PartModels {

    /** The landing leg of the part being expanded, if it is one. Render thread only. */
    private var leg: com.rm.apogee.core.part.LandingLeg? = null

    /** The gimbal range of the part being expanded, radians. */
    private var gimbalRange = 0.0

    class Leaf(
        val shape: Shape,
        val position: Vec3,
        val rotation: Quat,
        val tint: Tint,
        val caps: Int,
    )

    fun expand(def: PartDef, caps: Int, anim: PartAnim?, out: MutableList<Leaf>) {
        val model = def.model
        if (model == null) {
            out.add(Leaf(def.mesh, Vec3.zero(), Quat.identity(), Tint.BODY, caps))
            return
        }
        val maxDeflection = def.module<AeroSurface>()?.maxDeflection
            ?: def.module<com.rm.apogee.core.part.HydroSurface>()?.maxDeflection ?: 0.0
        gimbalRange = Math.toRadians(def.module<com.rm.apogee.core.part.Engine>()?.gimbalRange ?: 0.0)
        leg = def.module<com.rm.apogee.core.part.LandingLeg>()
        expand(model, Vec3.zero(), Quat.identity(), Tint.BODY, caps, anim, maxDeflection, out)
    }

    private fun expand(
        model: ModelSpec,
        position: Vec3,
        rotation: Quat,
        tint: Tint,
        caps: Int,
        anim: PartAnim?,
        maxDeflection: Double,
        out: MutableList<Leaf>,
    ) {
        when (model) {
            is ModelSpec.Compound -> for (piece in model.pieces) {
                // The piece's own placement...
                val local = euler(piece.rotation)
                val offset = piece.offset.copy()
                // ...then what moves it, about its pivot.
                val motion = motion(piece, anim, maxDeflection)
                // A leg folds about its module's hinge - the one the physics
                // folds its feet about - so the two cannot disagree.
                // And a propeller spins where it is, on its own shaft: about
                // the part's origin, the outboard's swung round in a circle
                // nearly a metre across, in and out of the water (Dan).
                val pivot = when (piece.role) {
                    PieceRole.DEPLOY -> leg?.hinge ?: piece.pivot
                    PieceRole.SPIN -> piece.offset
                    else -> piece.pivot
                }
                val placedRotation = motion * local
                val placedOffset = motion.rotate(offset.subInPlace(pivot)).addInPlace(pivot)
                if (piece.role == PieceRole.SUSPENSION && anim != null) {
                    placedOffset.addScaledInPlace(piece.axis.normalized(), anim.compression)
                }
                // Into the parent's frame.
                val childPosition = rotation.rotate(placedOffset).addInPlace(position)
                val childRotation = rotation * placedRotation
                val childTint = if (piece.tint == Tint.BODY) tint else piece.tint
                val childCaps = if (piece.stackEnds) caps else StackCaps.BOTH
                expand(piece.model, childPosition, childRotation, childTint, childCaps, anim, maxDeflection, out)
            }
            is ModelSpec.Primitive -> out.add(Leaf(model.mesh, position, rotation, tint, caps))
            else -> out.add(Leaf(model, position, rotation, tint, caps))
        }
    }

    private fun motion(piece: ModelSpec.Piece, anim: PartAnim?, maxDeflection: Double): Quat {
        if (anim == null) return Quat.identity()
        val axis = piece.axis.normalized()
        return when (piece.role) {
            PieceRole.FIXED, PieceRole.SUSPENSION -> Quat.identity()
            // As Forces.gimballedDirection turns the thrust.
            PieceRole.GIMBAL ->
                Quat.fromAxisAngle(Vec3(1.0, 0.0, 0.0), anim.gimbalPitch * gimbalRange) *
                    Quat.fromAxisAngle(Vec3(0.0, 0.0, 1.0), anim.gimbalYaw * gimbalRange)
            PieceRole.HINGED ->
                Quat.fromAxisAngle(axis, anim.hingeSign * Math.toRadians(maxDeflection) * anim.deflection)
            PieceRole.SPIN -> Quat.fromAxisAngle(axis, anim.spin)
            PieceRole.STEER -> {
                val steerAxis = anim.steerAxis ?: axis
                Quat.fromAxisAngle(steerAxis, anim.steer) * (anim.wheelAlign ?: Quat.identity())
            }
            // Steered about the craft's up; aligned to its real axle; rolling
            // about the authored axle, X.
            PieceRole.STEER_SPIN -> {
                val steerAxis = anim.steerAxis ?: axis
                Quat.fromAxisAngle(steerAxis, anim.steer) * (anim.wheelAlign ?: Quat.identity()) *
                    Quat.fromAxisAngle(Vec3(1.0, 0.0, 0.0), anim.spin)
            }
            PieceRole.DEPLOY -> {
                val l = leg
                if (l != null) Quat.fromAxisAngle(l.foldAxis.normalized(), Math.toRadians(l.stowedAngle) * (1.0 - anim.deploy))
                else Quat.fromAxisAngle(axis, Math.toRadians(piece.travel) * (1.0 - anim.deploy))
            }
        }
    }

    /**
     * Fills in [anim]'s wheel alignment for a wheel part mounted at
     * [partRotation] (design space) on a craft facing [forward] with [up].
     */
    fun alignWheel(def: PartDef, partRotation: Quat, forward: Vec3, up: Vec3, anim: PartAnim) {
        if (def.module<com.rm.apogee.core.part.Wheel>() == null) {
            anim.wheelAlign = null
            anim.steerAxis = null
            return
        }
        val f = partRotation.inverseRotate(forward)
        val u = partRotation.inverseRotate(up)
        val axle = u.cross(f).normalizeInPlace()
        // Either way along the axle is the same tyre; take the one nearer the
        // authored axle so a wheel mounted normally is not flipped round.
        if (axle.x < 0.0) axle.mulInPlace(-1.0)
        anim.wheelAlign = com.rm.apogee.core.math.quatFromTo(Vec3(1.0, 0.0, 0.0), axle)
        anim.steerAxis = u.normalizeInPlace()
    }

    /**
     * Sets [anim]'s hinge direction for a control surface mounted at
     * [partRotation], [offset] from the craft's centre (both design space),
     * so its trailing edge is drawn moving against the push the physics
     * applies. That push is across the fuselage and the mounting radius -
     * see Forces.controlDeflection - so a wing on the left and one on the
     * right, mounted mirror-fashion, turn opposite ways round their own
     * hinges for the same deflection. See ControlSurfaceLookTest.
     */
    fun alignSurface(def: PartDef, partRotation: Quat, offset: Vec3, anim: PartAnim) {
        val controllable = def.module<AeroSurface>()?.controllable == true ||
            def.module<com.rm.apogee.core.part.HydroSurface>()?.controllable == true
        if (!controllable) return
        val radial = Vec3(offset.x, 0.0, offset.z)
        if (radial.length < 1e-6) return
        radial.normalizeInPlace()
        // The push for a positive deflection, in part space.
        val push = partRotation.inverseRotate(Vec3(0.0, 1.0, 0.0).cross(radial))
        // Which way a positive turn about the hinge moves the trailing edge,
        // which is at -Y in every authored surface.
        val hinge = (def.model as? ModelSpec.Compound)?.pieces?.firstOrNull { it.role == PieceRole.HINGED } ?: return
        val trailingMoves = hinge.axis.normalized().cross(Vec3(0.0, -1.0, 0.0))
        anim.hingeSign = if ((trailingMoves dot push) > 0.0) -1.0 else 1.0
    }

    /** Degrees about X, then Y, then Z. */
    private fun euler(degrees: Vec3): Quat {
        if (degrees.x == 0.0 && degrees.y == 0.0 && degrees.z == 0.0) return Quat.identity()
        val qx = Quat.fromAxisAngle(Vec3(1.0, 0.0, 0.0), Math.toRadians(degrees.x))
        val qy = Quat.fromAxisAngle(Vec3(0.0, 1.0, 0.0), Math.toRadians(degrees.y))
        val qz = Quat.fromAxisAngle(Vec3(0.0, 0.0, 1.0), Math.toRadians(degrees.z))
        return qz * qy * qx
    }

    /** Radius of a sphere round a leaf's origin containing all of it. */
    fun boundingRadius(shape: Shape): Double = when (shape) {
        is MeshSpec.Cylinder -> hypot(shape.radius, shape.height * 0.5)
        is MeshSpec.Cone -> hypot(max(shape.bottomRadius, shape.topRadius), shape.height * 0.5)
        is MeshSpec.Box -> 0.5 * kotlin.math.sqrt(
            shape.width * shape.width + shape.height * shape.height + shape.depth * shape.depth,
        )
        is MeshSpec.Sphere -> shape.radius
        is ModelSpec.Lathe -> shape.profile.maxOf { hypot(it[0], it[1]) }
        is ModelSpec.NoseCone -> hypot(shape.radius, shape.length * 0.5)
        is ModelSpec.Tank -> hypot(shape.radius + 0.03, shape.height * 0.5)
        is ModelSpec.Fin -> hypot(shape.span * 0.5, max(shape.rootChord, shape.sweep + shape.tipChord))
        is ModelSpec.Loft -> shape.sections.maxOf {
            hypot(hypot(it.halfWidth, it.y), max(kotlin.math.abs(it.top), kotlin.math.abs(it.bottom)))
        }
        is ModelSpec.Tyre -> hypot(shape.radius + shape.lugDepth, shape.width * 0.5)
        is ModelSpec.Prop -> shape.radius
        is CloudPuff -> 1.3
        else -> 1.0
    }

    /**
     * A part's own colour, by family. Kit parts of one vehicle class share a
     * scheme, so a craft reads as one machine rather than a parts bin.
     */
    fun bodyColour(partId: String): FloatArray = when {
        partId.startsWith("engine") -> floatArrayOf(0.45f, 0.45f, 0.50f, 1f)
        partId.startsWith("tank") -> floatArrayOf(0.82f, 0.82f, 0.86f, 1f)
        partId.startsWith("pod") -> floatArrayOf(0.70f, 0.62f, 1.00f, 1f)
        partId.startsWith("decoupler") -> floatArrayOf(0.90f, 0.70f, 0.35f, 1f)
        partId.startsWith("fin") -> floatArrayOf(0.60f, 0.20f, 0.20f, 1f)
        partId.startsWith("chute") -> floatArrayOf(0.55f, 0.55f, 0.60f, 1f)
        // Aircraft: white airframe, the cockpit a warm accent.
        partId.startsWith("cockpit") -> floatArrayOf(0.92f, 0.92f, 0.94f, 1f)
        partId.startsWith("fuselage") -> floatArrayOf(0.90f, 0.90f, 0.92f, 1f)
        partId.startsWith("wing") || partId.startsWith("tail") -> floatArrayOf(0.84f, 0.85f, 0.88f, 1f)
        // Land: a field-vehicle olive.
        partId.startsWith("chassis") || partId.startsWith("cab") || partId.startsWith("rack") ->
            floatArrayOf(0.46f, 0.52f, 0.36f, 1f)
        // Water: a painted hull.
        partId.startsWith("hull") || partId.startsWith("cabin") -> floatArrayOf(0.20f, 0.36f, 0.56f, 1f)
        else -> floatArrayOf(0.75f, 0.75f, 0.78f, 1f)
    }

    /** The small palette pieces are tinted from; [Tint.BODY] takes the part's colour. */
    fun colour(tint: Tint, body: FloatArray): FloatArray = when (tint) {
        Tint.BODY -> body
        Tint.DARK -> floatArrayOf(0.20f, 0.20f, 0.23f, 1f)
        Tint.METAL -> floatArrayOf(0.62f, 0.63f, 0.66f, 1f)
        Tint.RUBBER -> floatArrayOf(0.10f, 0.10f, 0.11f, 1f)
        Tint.GLASS -> floatArrayOf(0.30f, 0.48f, 0.66f, 1f)
        Tint.ACCENT -> floatArrayOf(0.88f, 0.56f, 0.16f, 1f)
        Tint.LIGHT -> floatArrayOf(0.92f, 0.92f, 0.90f, 1f)
    }
}
