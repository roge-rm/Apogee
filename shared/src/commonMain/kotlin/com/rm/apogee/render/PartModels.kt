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
import com.rm.apogee.platform.synchronized
import kotlin.math.hypot
import kotlin.math.max
import com.rm.apogee.core.math.Math

/**
 * How a part's moving pieces are posed this frame. All zero is a part at rest: surfaces neutral,
 * wheels straight and still, legs deployed, and springs slack.
 */
class PartAnim(
    /** Control deflection, -1..1 of the surface's travel. */
    var deflection: Double = 0.0,
    /** Steering angle, in radians. */
    var steer: Double = 0.0,
    /** Spin built up so far, in radians: a wheel's roll, or a propeller's turn. */
    var spin: Double = 0.0,
    /** Landing gear deploy progress, from 0 stowed to 1 deployed. */
    var deploy: Double = 1.0,
    /** Suspension compression, in metres. */
    var compression: Double = 0.0,
    /** Staged, where that throws something off it, like a fairing's shell. */
    var jettisoned: Boolean = false,
    /** Engine gimbal, -1..1 of its range, about pitch (X) and yaw (Z). */
    var gimbalPitch: Double = 0.0,
    var gimbalYaw: Double = 0.0,
    /**
     * For a wheel: turns its tire from its made axle, X, to the craft's real one (across forward,
     * level with up), however it's mounted, so it rolls the way the craft drives. Null otherwise.
     */
    var wheelAlign: Quat? = null,
    /** For a wheel: the craft's up in part space, the axis it steers about. */
    var steerAxis: Vec3? = null,
    /**
     * For a control surface: which way round its hinge a positive deflection turns it, as mounted.
     * See [PartModels.alignSurface].
     */
    var hingeSign: Double = 1.0,
    /** A wing's flaps, 0 up to 1 all the way down. */
    var flap: Double = 0.0,
    /**
     * Which way round its hinge a flap goes down, as mounted: 1 when the part's +Z is the sky side,
     * -1 on a wing mounted the other way up. See [PartModels.alignFlap].
     */
    var flapSign: Double = 1.0,
    /** A sail's angle round its mast, radians from the part's -Z, and how full it is (0..1). */
    var sailAngle: Double = 0.0,
    var sailFill: Double = 1.0,
)

/**
 * Turns a part into what to draw: its [ModelSpec] (or, without one, its physics [MeshSpec]) as leaf
 * shapes placed in part space, tinted, and posed by a [PartAnim].
 */
object PartModels {

    /** The landing leg of the part being expanded, if it is one. Render thread only. */
    private var leg: com.rm.apogee.core.part.LandingLeg? = null

    /** The gimbal range of the part being expanded, in radians. */
    private var gimbalRange = 0.0

    class Leaf(
        val shape: Shape,
        val position: Vec3,
        val rotation: Quat,
        val tint: Tint,
        val caps: Int,
        /** Part of spinning blades, which tip with their disc as a rotor pulls. */
        val spinning: Boolean = false,
    )

    /** A part's spinning blades, in its own space: their middle, the axis they turn on, and how far they reach. */
    class Blades(val centre: Vec3, val axis: Vec3, val radius: Double)

    private val bladesCache = HashMap<String, Blades?>()

    /** Where [def]'s blades are, if any: its model's first spinning propeller. Blur discs go here. */
    fun blades(def: PartDef): Blades? = bladesCache.getOrPut(def.id) {
        val compound = def.model as? ModelSpec.Compound ?: return@getOrPut null
        compound.pieces.firstOrNull { it.role == PieceRole.SPIN && it.model is ModelSpec.Prop }?.let { piece ->
            Blades(piece.offset.copy(), piece.axis.normalized(), (piece.model as ModelSpec.Prop).radius)
        }
    }

    fun expand(def: PartDef, caps: Int, anim: PartAnim?, out: MutableList<Leaf>) {
        // A part with no moving pieces is made once per cap setting and reused.
        if (caps in 0..StackCaps.BOTH && isStill(def)) {
            val made = synchronized(stillLeaves) {
                stillLeaves.getOrPut(def) { arrayOfNulls(StackCaps.BOTH + 1) }.let { byCaps ->
                    byCaps[caps] ?: ArrayList<Leaf>().also { expandAfresh(def, caps, null, it); byCaps[caps] = it }
                }
            }
            out.addAll(made)
            return
        }
        expandAfresh(def, caps, anim, out)
    }

    /** Parts by whether nothing in their model ever moves. */
    private val still = com.rm.apogee.platform.identityMapOf<PartDef, Boolean>()
    private val stillLeaves = com.rm.apogee.platform.identityMapOf<PartDef, Array<List<Leaf>?>>()

    private fun isStill(def: PartDef): Boolean = synchronized(still) {
        still.getOrPut(def) { def.model?.let(::stillModel) ?: true }
    }

    private fun stillModel(model: ModelSpec): Boolean = when (model) {
        is ModelSpec.Compound -> model.pieces.all { it.role == PieceRole.FIXED && stillModel(it.model) }
        else -> true
    }

    private fun expandAfresh(def: PartDef, caps: Int, anim: PartAnim?, out: MutableList<Leaf>) {
        val model = def.model
        if (model == null) {
            out.add(Leaf(def.mesh, Vec3.zero(), Quat.identity(), Tint.BODY, caps))
            return
        }
        val maxDeflection = def.module<AeroSurface>()?.maxDeflection
            ?: def.module<com.rm.apogee.core.part.HydroSurface>()?.maxDeflection
            ?: def.module<com.rm.apogee.core.part.Walker>()?.swing ?: 0.0
        gimbalRange = Math.toRadians(def.module<com.rm.apogee.core.part.Engine>()?.gimbalRange ?: 0.0)
        leg = def.module<com.rm.apogee.core.part.LandingLeg>()
        // A wheel that folds turns about its hinge into the body, its doors staying where they are.
        val fold = def.fold?.takeIf { it.wheel && anim != null && anim.deploy < 1.0 }
        if (fold != null && model is ModelSpec.Compound) {
            val (doors, rest) = split(def, model)
            val out01 = com.rm.apogee.core.part.GearFold.wheelOut(anim!!.deploy)
            val turn = Quat.fromAxisAngle(fold.axis, Math.toRadians(fold.stowedAngle) * (1.0 - out01))
            val at = turn.rotate(fold.hinge.copy().mulInPlace(-1.0)).addInPlace(fold.hinge)
            expand(rest, at, turn, Tint.BODY, caps, anim, maxDeflection, out, spinning = false)
            expand(doors, Vec3.zero(), Quat.identity(), Tint.BODY, caps, anim, maxDeflection, out, spinning = false)
            return
        }
        expand(model, Vec3.zero(), Quat.identity(), Tint.BODY, caps, anim, maxDeflection, out, spinning = false)
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
        spinning: Boolean,
    ) {
        when (model) {
            is ModelSpec.Compound -> for (piece in model.pieces) {
                if (piece.role == PieceRole.JETTISON && anim?.jettisoned == true) continue
                // The piece's own placement...
                val local = euler(piece.rotation)
                val offset = piece.offset.copy()
                // ...then what moves it, about its pivot.
                val motion = motion(piece, anim, maxDeflection)
                // A leg folds about its module's hinge, the same one the physics uses. A propeller
                // spins about its own offset, not the part's origin.
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
                expand(piece.model, childPosition, childRotation, childTint, childCaps, anim, maxDeflection, out, spinning || piece.role == PieceRole.SPIN)
            }
            is ModelSpec.Primitive -> out.add(Leaf(model.mesh, position, rotation, tint, caps, spinning))
            else -> out.add(Leaf(model, position, rotation, tint, caps, spinning))
        }
    }

    /** A folding wheel's model split into its doors and the rest, made once per part. */
    private fun split(def: PartDef, model: ModelSpec.Compound): Pair<ModelSpec.Compound, ModelSpec.Compound> = synchronized(splits) {
        splits.getOrPut(def) {
            val (doors, rest) = model.pieces.partition { it.role == PieceRole.DOOR }
            ModelSpec.Compound(doors) to ModelSpec.Compound(rest)
        }
    }

    private val splits = com.rm.apogee.platform.identityMapOf<PartDef, Pair<ModelSpec.Compound, ModelSpec.Compound>>()

    private fun motion(piece: ModelSpec.Piece, anim: PartAnim?, maxDeflection: Double): Quat {
        // Unposed, as in the builder, the gear is out and its doors open.
        if (anim == null) return if (piece.role == PieceRole.DOOR) Quat.fromAxisAngle(piece.axis.normalized(), Math.toRadians(piece.travel)) else Quat.identity()
        val axis = piece.axis.normalized()
        return when (piece.role) {
            PieceRole.FIXED, PieceRole.SUSPENSION, PieceRole.JETTISON -> Quat.identity()
            // Down with the flaps: the trailing edge (-Y) swings toward -Z, a flap's underside.
            PieceRole.FLAP -> Quat.fromAxisAngle(axis, anim.flapSign * Math.toRadians(FLAP_ANGLE) * anim.flap)
            // Around the mast. Positive swings the chord (-Z) toward +X.
            PieceRole.SAIL -> Quat.fromAxisAngle(axis, -anim.sailAngle)
            // The same way Forces.gimballedDirection turns the thrust.
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
            // Steered about the craft's up, lined up with its real axle, and rolling about the axle
            // it was made with, X.
            PieceRole.STEER_SPIN -> {
                val steerAxis = anim.steerAxis ?: axis
                Quat.fromAxisAngle(steerAxis, anim.steer) * (anim.wheelAlign ?: Quat.identity()) *
                    Quat.fromAxisAngle(Vec3(1.0, 0.0, 0.0), anim.spin)
            }
            PieceRole.DOOR -> Quat.fromAxisAngle(axis, Math.toRadians(piece.travel) * com.rm.apogee.core.part.GearFold.doorOpen(anim.deploy))
            PieceRole.DEPLOY -> {
                val l = leg
                if (l != null) Quat.fromAxisAngle(l.foldAxis.normalized(), Math.toRadians(l.stowedAngle) * (1.0 - anim.deploy))
                else Quat.fromAxisAngle(axis, Math.toRadians(piece.travel) * (1.0 - anim.deploy))
            }
        }
    }

    /** How far flaps go down, in degrees. */
    private const val FLAP_ANGLE = 25.0

    /**
     * Sets [anim]'s flap sign for a wing mounted at [partRotation] on a craft whose sky side is
     * [up] (both design space), so its flaps go down toward the ground whichever way it's mounted.
     */
    fun alignFlap(partRotation: Quat, up: Vec3, anim: PartAnim) {
        anim.flapSign = if ((partRotation.rotate(Vec3(0.0, 0.0, 1.0)) dot up) >= 0.0) 1.0 else -1.0
    }

    /**
     * Fills in [anim]'s wheel alignment for a wheel part mounted at [partRotation] (design space)
     * on a craft facing [forward] with [up].
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
        // Either way along the axle is the same tire; take the one nearer X so it isn't flipped.
        if (axle.x < 0.0) axle.mulInPlace(-1.0)
        anim.wheelAlign = com.rm.apogee.core.math.quatFromTo(Vec3(1.0, 0.0, 0.0), axle)
        anim.steerAxis = u.normalizeInPlace()
    }

    /**
     * Sets [anim]'s hinge direction for a control surface at [partRotation], [offset] from the
     * craft's centre (design space), so its trailing edge moves against the physics' push (see
     * Forces.controlDeflection). Mirrored wings turn opposite ways. See ControlSurfaceLookTest.
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
        // Which way a positive turn moves the trailing edge, at -Y in every surface as made.
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

    /** The radius of a sphere around a leaf's origin that holds all of it. */
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
     * A part's colour by family, from the start of its name, cached per name. Parts of one vehicle
     * class share a scheme. The arrays are shared, so only ever read them.
     */
    fun bodyColour(partId: String): FloatArray = synchronized(bodyColours) { bodyColours.getOrPut(partId) { bodyColourOf(partId) } }

    private val bodyColours = HashMap<String, FloatArray>()

    private fun bodyColourOf(partId: String): FloatArray = when {
        partId.startsWith("engine") -> floatArrayOf(0.45f, 0.45f, 0.50f, 1f)
        partId.startsWith("tank") -> floatArrayOf(0.82f, 0.82f, 0.86f, 1f)
        partId.startsWith("pod") -> floatArrayOf(0.70f, 0.62f, 1.00f, 1f)
        partId.startsWith("decoupler") -> floatArrayOf(0.90f, 0.70f, 0.35f, 1f)
        partId.startsWith("fin") -> floatArrayOf(0.60f, 0.20f, 0.20f, 1f)
        partId.startsWith("chute") -> floatArrayOf(0.55f, 0.55f, 0.60f, 1f)
        // Aircraft: a white airframe, with the cockpit a warm accent.
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

    /** The small palette pieces are tinted from. [Tint.BODY] takes the part's colour. */
    fun colour(tint: Tint, body: FloatArray): FloatArray = when (tint) {
        Tint.BODY -> body
        Tint.DARK -> DARK
        Tint.METAL -> METAL
        Tint.RUBBER -> RUBBER
        Tint.GLASS -> GLASS
        Tint.ACCENT -> ACCENT
        Tint.LIGHT -> LIGHT
        Tint.STACK -> bodyColour("tank")
    }

    // Shared, and only ever read.
    private val DARK = floatArrayOf(0.20f, 0.20f, 0.23f, 1f)
    private val METAL = floatArrayOf(0.62f, 0.63f, 0.66f, 1f)
    private val RUBBER = floatArrayOf(0.10f, 0.10f, 0.11f, 1f)
    private val GLASS = floatArrayOf(0.30f, 0.48f, 0.66f, 1f)
    private val ACCENT = floatArrayOf(0.88f, 0.56f, 0.16f, 1f)
    private val LIGHT = floatArrayOf(0.92f, 0.92f, 0.90f, 1f)
}
