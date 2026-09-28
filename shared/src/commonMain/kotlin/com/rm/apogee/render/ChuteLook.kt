package com.rm.apogee.render

import com.rm.apogee.core.math.Quat
import com.rm.apogee.core.math.Vec3
import com.rm.apogee.core.math.quatFromTo
import com.rm.apogee.core.part.MeshSpec
import com.rm.apogee.core.part.ModelSpec
import com.rm.apogee.core.math.Math

/**
 * An open parachute: a faceted dome on its lines, trailing from the pack away from the way the
 * craft moves through the air, and growing as it fills. It's drawn from the part's deploy value, so
 * everyone sees it open.
 */
object ChuteLook {

    /** The full canopy radius for a pack of this drag, in metres. Close to a real one's size. */
    fun radius(dragArea: Double): Double = kotlin.math.sqrt(dragArea / (Math.PI * 1.5)).coerceIn(1.5, 12.0)

    /**
     * The canopy and its lines for a pack at [pack] (world) at [open] of its full size (0..1),
     * streaming along [trail] (unit, away from the motion through the air), into [out].
     */
    fun append(
        pack: Vec3, trail: Vec3, open: Double, fullRadius: Double, key: Long,
        out: MutableList<RenderItem>,
    ) {
        // [open] is the canopy's size as a share of the full one. A drogue is small, on short
        // lines, and the main is big, on long ones.
        val r = fullRadius * open.coerceAtLeast(0.1)
        val reach = 3.0 + r * 1.6
        val turn = quatFromTo(Vec3.unitY(), trail)
        val rim = Vec3().setTo(pack).addScaledInPlace(trail, reach)
        // Filling, it's a narrow bag, and full, it's a broad dome.
        val scale = Vec3(r, r * (0.9 - 0.35 * open), r)
        out.add(RenderItem(CANOPY_OUT, rim.copy(), turn, ORANGE, caps = 0, scale = scale, ambient = 0.32f, wrap = false,
            key = RenderItem.effectKey(key, 0)))
        out.add(RenderItem(CANOPY_IN, rim.copy(), turn, INSIDE, caps = 0, scale = scale, ambient = 0.32f, wrap = false,
            key = RenderItem.effectKey(key, 1)))
        // The lines, from the pack to the rim, all the way round.
        val across = Vec3(); val side = Vec3()
        (if (kotlin.math.abs(trail.x) < 0.9) Vec3.unitX() else Vec3.unitY()).let { across.setTo(trail).crossInPlace(it).normalizeInPlace() }
        side.setTo(trail).crossInPlace(across).normalizeInPlace()
        for (k in 0 until LINES) {
            val a = 2.0 * Math.PI * k / LINES
            val end = Vec3().setTo(rim)
                .addScaledInPlace(across, r * kotlin.math.cos(a))
                .addScaledInPlace(side, r * kotlin.math.sin(a))
            val along = Vec3().setTo(end).subInPlace(pack)
            val length = along.length
            if (length < 1e-6) continue
            val middle = Vec3().setTo(pack).addScaledInPlace(along, 0.5)
            out.add(
                RenderItem(
                    LINE, middle, quatFromTo(Vec3.unitY(), along.mulInPlace(1.0 / length)), LINE_COLOUR,
                    caps = 0, scale = Vec3(1.0, length, 1.0), ambient = 0.4f, wrap = false,
                    key = RenderItem.effectKey(key, 2 + k),
                ),
            )
        }
    }

    private const val LINES = 6

    /** A dome, open at the bottom, with its rim at y 0 and crown at y 1, outside and inside. */
    private val DOME = listOf(
        listOf(1.0, 0.0), listOf(0.95, 0.3), listOf(0.8, 0.58), listOf(0.55, 0.82), listOf(0.25, 0.96), listOf(0.0, 1.0),
    )
    val CANOPY_OUT = ModelSpec.Lathe(DOME, segments = 16)
    val CANOPY_IN = ModelSpec.Lathe(DOME.reversed(), segments = 16)
    private val LINE = MeshSpec.Cylinder(radius = 0.02, height = 1.0)

    private val ORANGE = floatArrayOf(0.95f, 0.45f, 0.12f, 1f)
    private val INSIDE = floatArrayOf(0.95f, 0.9f, 0.85f, 1f)
    private val LINE_COLOUR = floatArrayOf(0.85f, 0.85f, 0.85f, 1f)
}
