package com.rm.apogee.core.weather

import com.rm.apogee.core.math.Vec3
import com.rm.apogee.core.terrain.Noise
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.asin
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sin

/**
 * What a hurricane looks like near the camera, as lobes: a low dark deck on rings round the eye
 * where its cloud is, a towering wall of stacked billows round a clear eye, and rain curtains and
 * scud under the deck close by.
 *
 * Lobes sit on fixed rings and spokes round the centre, so each keeps its slot as the hurricane
 * moves and strengthens: strength changes their size and how solid the whole shape is, never
 * which are there. Only those within the reach are built.
 *
 * Slots: deck 0 until [EYEWALL_SLOT] (ring x 256 + spoke), eyewall from [EYEWALL_SLOT] (level x
 * 128 + spoke, the lip the top level), scud [SCUD_SLOT] + its deck lobe's. A curtain takes its
 * lobe's slot, which drawing moves up by [SCUD_SLOT], so a deck lobe has a curtain or scud, never both.
 */
internal class HurricaneShapes(
    private val seed: Int,
    private val radius: Double,
    private val climate: Climate,
) {
    private val point = Vec3()
    private val foot = Vec3()

    /**
     * [h]'s lobes within [reach] metres of unit [camera], at [detail] 0..1 (fewer, bigger lobes
     * lower), or null if none are. [groundAt] gives the ground's height for its curtains.
     */
    fun build(h: LiveHurricane, camera: Vec3, reach: Double, detail: Double, groundAt: (Vec3) -> Double): CloudShape? {
        val wet = h.wet
        if (wet <= 0.0) return null
        val key = Noise.hashInt(seed + 995, h.id.toInt(), (h.id ushr 32).toInt(), 0).toLong() and 0xffffffffL
        val shape = CloudShape(climate.stormCloud, wet, key = key, hurricane = true)
        shape.drift.setTo(h.heading).mulInPlace(h.speed)
        val rm = h.maxWindRadius
        val spacing = spacing(detail)
        val rainReach = CURTAIN_REACH * (0.5 + detail)
        // Where the camera is from the centre, as the air measures it.
        val cx = (camera dot h.east); val cy = (camera dot h.north); val cz = camera dot h.centre
        val cameraR = kotlin.math.sqrt(cx * cx + cy * cy) * radius
        val cameraBearing = kotlin.math.atan2(cy, cx)
        val behind = cz < 0.0
        fun hh(k: Int, slot: Int) = Noise.hash(seed + 996 + k, h.id.toInt(), slot, 0)

        /** Whether the point at ([r], [bearing]) is within [reach] of the camera, by bearing first. */
        fun near(r: Double, bearing: Double, out: Vec3, size: Double): Boolean {
            h.pointAt(r, cos(bearing), sin(bearing), radius, out)
            return out.distanceTo(camera) * radius - size < reach
        }

        /** How far either side of the camera's bearing a ring of [r] can be within reach, radians. */
        fun window(r: Double): Double {
            if (behind || r <= reach + spacing || cameraR <= 1.0) return PI
            return min(PI, asin(min(1.0, (reach + 2.0 * spacing) / r)) * 1.3)
        }

        // The deck, ring by ring outward from the eyewall: low, dark and close, solid toward the eye.
        val rings = min(((h.outerRadius - DECK_FROM * rm) / spacing).toInt(), MAX_RINGS)
        for (k in 0 until rings) {
            val ringR = DECK_FROM * rm + spacing * (k + 0.5)
            if (!behind && abs(ringR - cameraR) > reach + spacing) continue
            val spokes = max(6, (2.0 * PI * ringR / spacing).roundToInt()).coerceAtMost(255)
            val half = window(ringR)
            // Bigger toward the eye, so the inner bands have few gaps, but none in the moat.
            val inner = (1.0 - smooth(2.0 * rm, 5.0 * rm, ringR)) * (1.0 - Hurricanes.moatAt(ringR, rm))
            val least = DECK_LEAST * (1.0 - 0.6 * inner)
            for (j in 0 until spokes) {
                val slot = k * 256 + j
                val bearing = 2.0 * PI * (j + 0.5 * (k and 1) + 0.6 * (hh(0, slot) - 0.5)) / spokes
                if (half < PI && abs(wrap(bearing - cameraBearing)) > half) continue
                val r = ringR + (hh(1, slot) - 0.5) * 0.6 * spacing
                val ax = cos(bearing); val ay = sin(bearing)
                val c = h.cloudAt(r, ax, ay, radius)
                if (c < least) continue
                val size = spacing * (0.45 + 0.5 * c) * (0.75 + 0.25 * wet) * (1.0 + 0.3 * inner)
                if (!near(r, bearing, point, size)) continue
                val base = DECK_BASE - 400.0 * c + 150.0 * hh(2, slot)
                val thick = (600.0 + 2_200.0 * c) * (0.6 + 0.4 * wet)
                shape.lobes.add(
                    CloudLobe(
                        Vec3().setTo(point).mulInPlace(radius + base + thick), size, thick,
                        shade = 0.2 - 0.1 * c + 0.14 * hh(3, slot), flat = c < 0.45,
                    ).also { it.visualSlot = slot },
                )
                // Rain under the heavier parts near the camera, and ragged scud under the rest.
                val rain = hh(4, slot) < 0.6
                if (c > CURTAIN_LEAST && rain) {
                    curtain(shape, point, camera, rainReach, slot, 0.5 * spacing, base + 300.0, c * wet, groundAt)
                } else if (c > SCUD_LEAST && !rain && point.distanceTo(camera) * radius - size < rainReach) {
                    val height = SCUD_HEIGHT + 200.0 * hh(9, slot)
                    shape.lobes.add(
                        CloudLobe(
                            Vec3().setTo(point).mulInPlace(radius + height), 0.3 * spacing * (0.8 + 0.4 * hh(10, slot)),
                            (100.0 + 100.0 * c) * wet, shade = 0.2 + 0.05 * hh(11, slot), flat = true,
                        ).also { it.visualSlot = SCUD_SLOT + slot },
                    )
                }
            }
        }

        // The eyewall: a stadium of billows from low down to the canopy, flaring out as they
        // climb, lit at the top and dark below, with a lip round the top over the eye.
        val levels = 2 + (3.0 * detail).roundToInt()
        val ring = 1.15 * rm
        val spokes = min((2.0 * PI * ring / (0.5 * spacing)).roundToInt(), (20 + 16 * detail).roundToInt()).coerceAtLeast(10)
        val top = EYEWALL_TOP + EYEWALL_TOP_STRONG * h.strength
        val rise = (top - EYEWALL_BASE) / levels
        for (level in 0..levels) {
            val lip = level == levels
            val up = (level + 0.5) / levels
            for (j in 0 until spokes) {
                val slot = EYEWALL_SLOT + level * 128 + j
                val bearing = 2.0 * PI * (j + 0.5 * (level and 1) + 0.4 * (hh(5, slot) - 0.5)) / spokes
                val r = if (lip) ring - 0.1 * rm else ring + rm * (0.5 * up * up + 0.1 * (hh(6, slot) - 0.5))
                val step = 2.0 * PI * r / spokes
                val size = (if (lip) 0.5 * rm else max(0.8 * step, 0.3 * rm)) * (0.85 + 0.3 * hh(7, slot)) * (0.8 + 0.2 * wet)
                if (!near(r, bearing, point, size)) continue
                val height = if (lip) top - 600.0 else EYEWALL_BASE + rise * (level + 0.5)
                shape.lobes.add(
                    CloudLobe(
                        Vec3().setTo(point).mulInPlace(radius + height), size,
                        if (lip) 700.0 else rise * 0.75,
                        shade = if (lip) 1.4 else 0.25 + 1.15 * up * kotlin.math.sqrt(up) + 0.05 * hh(8, slot), flat = lip,
                    ).also { it.visualSlot = slot },
                )
                if (level == 0 && (j and 1) == 0) {
                    curtain(shape, point, camera, rainReach, slot, 0.9 * size, EYEWALL_BASE + 300.0, wet, groundAt)
                }
            }
        }
        return if (shape.lobes.isEmpty() && shape.rain.isEmpty()) null else shape
    }

    /** A rain curtain under unit [at] from the ground to [base], if within [reach] of [camera]. */
    private fun curtain(
        shape: CloudShape, at: Vec3, camera: Vec3, reach: Double, slot: Int, width: Double, base: Double, shade: Double,
        groundAt: (Vec3) -> Double,
    ) {
        if (at.distanceTo(camera) * radius - width > reach) return
        foot.setTo(at)
        val ground = groundAt(foot)
        val bottom = if (ground > 0.0) ground - 400.0 else -30.0
        if (base <= max(ground, 0.0) + 200.0) return
        shape.rain.add(
            CloudLobe(Vec3().setTo(foot).mulInPlace(radius + (bottom + base) * 0.5), width, (base - bottom) * 0.5, shade = shade.coerceIn(0.0, 1.0))
                .also { it.visualSlot = slot },
        )
    }

    private fun wrap(a: Double): Double {
        var x = a % (2.0 * PI)
        if (x > PI) x -= 2.0 * PI
        if (x < -PI) x += 2.0 * PI
        return x
    }

    companion object {
        /** The deck's lobe spacing in metres at [detail]: wider where fewer can be drawn. */
        fun spacing(detail: Double): Double = 11_000.0 + 6_000.0 * (1.0 - detail.coerceIn(0.0, 1.0))

        /** Where the deck's rings start, in eyewall radii, just outside the wall. */
        const val DECK_FROM = 1.15

        /**
         * Cloud thinner than this has no lobe, less toward the eye. Its hurricane's whole life,
         * so none pop in or out.
         */
        const val DECK_LEAST = 0.12

        /** The deck's base in metres where its cloud is thinnest. Thicker cloud hangs lower. */
        const val DECK_BASE = 900.0

        /** Cloud thicker than this has rain shown under it, within [CURTAIN_REACH] (scaled by detail) in metres. */
        const val CURTAIN_LEAST = 0.45
        const val CURTAIN_REACH = 20_000.0

        /** Cloud thicker than this and without rain has scud under it this high, in metres. */
        const val SCUD_LEAST = 0.35
        const val SCUD_HEIGHT = 250.0

        /** The eyewall's lowest billows, and its top, in metres: higher the stronger it is. */
        const val EYEWALL_BASE = 500.0
        const val EYEWALL_TOP = 11_000.0
        const val EYEWALL_TOP_STRONG = 4_000.0

        const val MAX_RINGS = 76
        const val EYEWALL_SLOT = 20_000
        const val SCUD_SLOT = 32_768
    }
}
