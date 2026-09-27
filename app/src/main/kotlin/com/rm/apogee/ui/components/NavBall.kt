package com.rm.apogee.ui.components

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.clickable
import androidx.compose.ui.text.drawText
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.rm.apogee.core.math.Quat
import com.rm.apogee.core.math.Vec3
import com.rm.apogee.ui.theme.ApogeeAlpha
import com.rm.apogee.ui.theme.ApogeeColors
import com.rm.apogee.ui.theme.alpha
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin

/**
 * The attitude instrument: a sphere fixed to the local horizon, seen from the craft.
 *
 * Everything is worked out in the *craft's own frame*, which gets rid of a whole class of
 * confusion. The ball's centre is the direction the nose points, so the nose never moves, and the
 * world rotates behind it, exactly like a real attitude indicator. Screen right is the craft's +X,
 * screen up is its +Z, and depth is +Y (the nose axis), so a direction is on the visible half
 * exactly when its local Y is positive.
 *
 * It's drawn on a Compose Canvas instead of in GL. It's a flat instrument in screen space, the
 * projection is a dozen lines of orthographic maths, and keeping it here means it can be tested and
 * restyled without touching a shader.
 */
@Composable
fun NavBall(
    /** The craft's orientation. */
    rotation: Quat,
    /** The local vertical, in the same frame the craft's position is in. */
    worldUp: Vec3,
    /** The direction of travel, in the navball's frame, or null when it isn't moving. */
    prograde: Vec3?,
    modifier: Modifier = Modifier,
    size: Dp = 170.dp,
    /** The orbit normal, in the same frame. Null hides normal and anti-normal. */
    normal: Vec3? = null,
    /** Radial out. Null hides radial out and in. */
    radialOut: Vec3? = null,
    /** The frame the markers are in, for the tag. */
    frame: com.rm.apogee.core.world.NavFrame = com.rm.apogee.core.world.NavFrame.SURFACE,
    /** Whether the frame was chosen by hand instead of left on automatic. */
    frameManual: Boolean = false,
    /** Tapping the tag gives the next frame. */
    onCycleFrame: (() -> Unit)? = null,
    /** Toward the target. Null hides target and anti-target. */
    toTarget: Vec3? = null,
    /** The direction of travel through the air, when it's different from over the ground. */
    throughAir: Vec3? = null,
    /** Along what's left of the next planned burn. Null hides it. */
    burn: Vec3? = null,
) {
    val textMeasurer = androidx.compose.ui.text.rememberTextMeasurer()
    androidx.compose.foundation.layout.Box(modifier.size(size)) {
    Canvas(Modifier.size(size)) {
        val radius = kotlin.math.min(this.size.width, this.size.height) * 0.5f - 4f
        val centre = Offset(this.size.width / 2f, this.size.height / 2f)

        // Local-frame versions of the world directions we care about.
        val up = rotation.inverseRotate(worldUp).normalizeInPlace()
        fun local(v: Vec3?) = v?.let { rotation.inverseRotate(it).normalizeInPlace() }
        val progradeLocal = local(prograde)
        val normalLocal = local(normal)
        val radialLocal = local(radialOut)
        val targetLocal = local(toTarget)
        val airLocal = local(throughAir)
        // Compass north and east on the horizon, about the body's own axis, +Y, whatever frame the
        // craft is in.
        val worldEast = Vec3(worldUp.z, 0.0, -worldUp.x).let { if (it.lengthSq < 1e-12) Vec3(1.0, 0.0, 0.0) else it.normalizeInPlace() }
        val worldNorth = worldUp.cross(worldEast).normalizeInPlace()
        val northLocal = rotation.inverseRotate(worldNorth).normalizeInPlace()
        val eastLocal = rotation.inverseRotate(worldEast).normalizeInPlace()

        // An orthonormal pair spanning the horizon plane.
        val a = perpendicularTo(up)
        val b = up.cross(a).normalizeInPlace()

        drawSphere(centre, radius, up, a, b)
        drawPitchLadder(centre, radius, up, a, b)
        drawHorizon(centre, radius, up, a, b)
        drawCompass(centre, radius, northLocal, eastLocal, textMeasurer)
        drawMarkers(centre, radius, progradeLocal, normalLocal, radialLocal)
        drawTargetMarkers(centre, radius, targetLocal)
        drawBurnMarker(centre, radius, local(burn))
        airLocal?.let { project(centre, radius, it) }?.let { flightPathMarker(it, radius * 0.09f) }
        drawReticle(centre, radius)
    }
        // Which frame the markers are in. Tap for the next one. It's bright when chosen by hand,
        // and dim when left on automatic.
        val tagColour = when (frame) {
            com.rm.apogee.core.world.NavFrame.ORBIT -> ApogeeColors.Prograde
            com.rm.apogee.core.world.NavFrame.TARGET -> TARGET
            else -> ApogeeColors.Data
        }
        androidx.compose.material3.Text(
            frame.label + if (frameManual) "" else " \u00b7",
            style = com.rm.apogee.ui.theme.TelemetryTextStyle.copy(fontSize = androidx.compose.ui.unit.TextUnit(10f, androidx.compose.ui.unit.TextUnitType.Sp)),
            color = tagColour.alpha(if (frameManual) 1f else 0.75f),
            modifier = Modifier
                // In the corner, outside the ball. At the bottom of it, the tag sat on the compass
                // letters.
                .align(androidx.compose.ui.Alignment.BottomEnd)
                .padding(end = 0.dp, bottom = 0.dp)
                .then(if (onCycleFrame != null) Modifier.clickable(onClick = onCycleFrame) else Modifier)
                .padding(horizontal = 10.dp, vertical = 4.dp),
        )
    }
}

/** Any unit vector at right angles to [v]. */
private fun perpendicularTo(v: Vec3): Vec3 {
    val axis = if (abs(v.x) < 0.9) Vec3.unitX() else Vec3.unitY()
    return v.cross(axis).normalizeInPlace()
}

/**
 * Orthographic projection of a local-space direction onto the ball.
 *
 * It returns null when the direction is on the far half, which is what makes the ball look like a
 * sphere and not a disc.
 */
private fun project(centre: Offset, radius: Float, direction: Vec3): Offset? {
    if (direction.y <= 0.0) return null
    return Offset(
        centre.x + radius * direction.x.toFloat(),
        centre.y - radius * direction.z.toFloat(),
    )
}

/** Where a silhouette direction lands, ignoring which half it's on. */
private fun projectUnclipped(centre: Offset, radius: Float, direction: Vec3): Offset =
    Offset(
        centre.x + radius * direction.x.toFloat(),
        centre.y - radius * direction.z.toFloat(),
    )

/**
 * Fills the sky and ground halves.
 *
 * The ground is bounded by two arcs: the visible half of the horizon great circle, and the piece of
 * the ball's outline below it. They meet exactly where the horizon plane crosses the outline, which
 * is `±(nose × up)`, so those points are worked out directly instead of searched for.
 */
private fun DrawScope.drawSphere(centre: Offset, radius: Float, up: Vec3, a: Vec3, b: Vec3) {
    drawCircle(SKY, radius, centre)

    val horizon = horizonPoints(up, a, b)
    val visible = horizon.filter { it.y > 0.0 }
    if (visible.isEmpty()) {
        // Looking straight down, the whole visible half is ground.
        if (up.y < 0.0) drawCircle(GROUND, radius, centre)
        return
    }
    if (visible.size == horizon.size) return // entirely sky

    val ordered = orderArc(horizon)
    val path = Path()
    var started = false
    for (direction in ordered) {
        val point = projectUnclipped(centre, radius, direction)
        if (!started) {
            path.moveTo(point.x, point.y)
            started = true
        } else {
            path.lineTo(point.x, point.y)
        }
    }

    // Close along the outline, sweeping the way that passes underneath.
    val first = ordered.first()
    val last = ordered.last()
    val startAngle = screenAngle(centre, projectUnclipped(centre, radius, last))
    val endAngle = screenAngle(centre, projectUnclipped(centre, radius, first))
    // The nadir on the outline: straight "down" as far as the ball shows it.
    val nadirOnRim = (-up).let { down ->
        val flattened = Vec3(down.x, 0.0, down.z)
        if (flattened.lengthSq < 1e-9) null else flattened.normalizeInPlace()
    }
    val throughAngle = nadirOnRim?.let { screenAngle(centre, projectUnclipped(centre, radius, it)) }

    appendRimArc(path, centre, radius, startAngle, endAngle, throughAngle)
    path.close()
    drawPath(path, GROUND)
}

/** Samples the horizon great circle. */
private fun horizonPoints(up: Vec3, a: Vec3, b: Vec3): List<Vec3> =
    (0 until HORIZON_SAMPLES).map { i ->
        val t = 2.0 * PI * i / HORIZON_SAMPLES
        Vec3(
            a.x * cos(t) + b.x * sin(t),
            a.y * cos(t) + b.y * sin(t),
            a.z * cos(t) + b.z * sin(t),
        )
    }

/**
 * Rotates a sampled circle so its visible run is all in one piece, then returns it.
 *
 * Sampling starts at any point on the circle, so the visible arc is usually split across the ends
 * of the list. Walking it in that order would draw a line straight across the ball.
 */
private fun orderArc(points: List<Vec3>): List<Vec3> {
    val n = points.size
    val start = (0 until n).firstOrNull { i ->
        points[i].y > 0.0 && points[(i - 1 + n) % n].y <= 0.0
    } ?: return points.filter { it.y > 0.0 }

    val result = ArrayList<Vec3>(n)
    var i = start
    while (points[i].y > 0.0 && result.size < n) {
        result.add(points[i])
        i = (i + 1) % n
    }
    return result
}

private fun screenAngle(centre: Offset, point: Offset): Float =
    atan2(point.y - centre.y, point.x - centre.x)

/** Adds an arc of the ball's rim, sweeping through [throughAngle] if it's given. */
private fun appendRimArc(
    path: Path,
    centre: Offset,
    radius: Float,
    fromAngle: Float,
    toAngle: Float,
    throughAngle: Float?,
) {
    val rect = Rect(
        Offset(centre.x - radius, centre.y - radius),
        Size(radius * 2f, radius * 2f),
    )
    var sweep = toAngle - fromAngle
    while (sweep <= -PI.toFloat()) sweep += (2.0 * PI).toFloat()
    while (sweep > PI.toFloat()) sweep -= (2.0 * PI).toFloat()

    if (throughAngle != null) {
        // Flip to the long way round if the short sweep misses the nadir.
        var offsetToward = throughAngle - fromAngle
        while (offsetToward <= -PI.toFloat()) offsetToward += (2.0 * PI).toFloat()
        while (offsetToward > PI.toFloat()) offsetToward -= (2.0 * PI).toFloat()
        val onShortArc = (offsetToward / sweep) in 0f..1f
        if (!onShortArc) {
            sweep = if (sweep > 0f) sweep - (2.0 * PI).toFloat() else sweep + (2.0 * PI).toFloat()
        }
    }

    path.arcTo(
        rect,
        Math.toDegrees(fromAngle.toDouble()).toFloat(),
        Math.toDegrees(sweep.toDouble()).toFloat(),
        false,
    )
}

private fun DrawScope.drawHorizon(centre: Offset, radius: Float, up: Vec3, a: Vec3, b: Vec3) {
    strokeCircle(centre, radius, horizonPoints(up, a, b), HORIZON_LINE, 2.5f)
    // The ball's own outline.
    drawCircle(Color.White.alpha(ApogeeAlpha.BORDER), radius, centre, style = Stroke(1.5f))
}

/** Rings of constant pitch, so you can read the attitude instead of guessing it. */
private fun DrawScope.drawPitchLadder(
    centre: Offset,
    radius: Float,
    up: Vec3,
    a: Vec3,
    b: Vec3,
) {
    for (pitchDegrees in intArrayOf(-60, -30, 30, 60)) {
        val fromUp = PI / 2.0 - Math.toRadians(pitchDegrees.toDouble())
        val ringRadius = sin(fromUp)
        val height = cos(fromUp)
        val points = (0 until HORIZON_SAMPLES).map { i ->
            val t = 2.0 * PI * i / HORIZON_SAMPLES
            Vec3(
                up.x * height + (a.x * cos(t) + b.x * sin(t)) * ringRadius,
                up.y * height + (a.y * cos(t) + b.y * sin(t)) * ringRadius,
                up.z * height + (a.z * cos(t) + b.z * sin(t)) * ringRadius,
            )
        }
        val colour = if (pitchDegrees > 0) SKY_LINE else GROUND_LINE
        strokeCircle(centre, radius, points, colour, 1f)
    }
}

/** Draws the visible part of a sampled circle as a polyline. */
private fun DrawScope.strokeCircle(
    centre: Offset,
    radius: Float,
    points: List<Vec3>,
    colour: Color,
    width: Float,
) {
    var previous: Offset? = null
    for (direction in points + points.first()) {
        val projected = project(centre, radius, direction)
        if (projected != null && previous != null) {
            drawLine(colour, previous, projected, strokeWidth = width)
        }
        previous = projected
    }
}

/**
 * The six markers, in the shapes pilots know them by:
 * - prograde: a ring with three spurs. Retrograde: the same, crossed through.
 * - normal: a triangle pointing out. Anti-normal: a triangle pointing back.
 * - radial out: a ring with four spurs outward. Radial in: four spurs inward.
 *
 * Each pair shares a colour, and a marker on the far side of the ball isn't drawn, because its
 * opposite is on this side instead.
 */
private fun DrawScope.drawMarkers(centre: Offset, radius: Float, prograde: Vec3?, normal: Vec3?, radial: Vec3?) {
    val size = radius * 0.09f
    if (normal != null) {
        project(centre, radius, normal)?.let { triangle(it, size, pointingUp = true, NORMAL, filledDot = true) }
        project(centre, radius, -normal)?.let { triangle(it, size, pointingUp = false, NORMAL, filledDot = false) }
    }
    if (radial != null) {
        project(centre, radius, radial)?.let { radialMarker(it, size, outward = true) }
        project(centre, radius, -radial)?.let { radialMarker(it, size, outward = false) }
    }
    if (prograde != null) {
        project(centre, radius, prograde)?.let { travelMarker(it, size, ApogeeColors.Prograde, crossed = false) }
        project(centre, radius, -prograde)?.let { travelMarker(it, size, ApogeeColors.Retrograde, crossed = true) }
    }
}

private fun DrawScope.travelMarker(point: Offset, size: Float, colour: Color, crossed: Boolean) {
    drawCircle(colour, size, point, style = Stroke(2f))
    val spur = size * 1.65f
    drawLine(colour, point + Offset(-spur, 0f), point + Offset(-size, 0f), strokeWidth = 2f)
    drawLine(colour, point + Offset(size, 0f), point + Offset(spur, 0f), strokeWidth = 2f)
    drawLine(colour, point + Offset(0f, -spur), point + Offset(0f, -size), strokeWidth = 2f)
    if (crossed) {
        val d = size * 0.7f
        drawLine(colour, point + Offset(-d, -d), point + Offset(d, d), strokeWidth = 2f)
        drawLine(colour, point + Offset(-d, d), point + Offset(d, -d), strokeWidth = 2f)
    } else {
        drawCircle(colour, size * 0.22f, point)
    }
}

private fun DrawScope.triangle(point: Offset, size: Float, pointingUp: Boolean, colour: Color, filledDot: Boolean) {
    val s = if (pointingUp) -1f else 1f
    val path = Path().apply {
        moveTo(point.x, point.y + s * size * 1.2f)
        lineTo(point.x + size * 1.1f, point.y - s * size * 0.7f)
        lineTo(point.x - size * 1.1f, point.y - s * size * 0.7f)
        close()
    }
    drawPath(path, colour, style = Stroke(2f))
    if (filledDot) drawCircle(colour, size * 0.22f, point)
    else {
        // Anti-normal: three short lines from the corners inward.
        val tip = Offset(point.x, point.y + s * size * 1.2f)
        drawLine(colour, tip, tip + (point - tip) * 0.5f, strokeWidth = 2f)
    }
}

private fun DrawScope.radialMarker(point: Offset, size: Float, outward: Boolean) {
    drawCircle(RADIAL, size, point, style = Stroke(2f))
    for (k in 0 until 4) {
        val angle = k * PI / 2.0 + PI / 4.0
        val dir = Offset(cos(angle).toFloat(), sin(angle).toFloat())
        if (outward) {
            drawLine(RADIAL, point + dir * size, point + dir * (size * 1.7f), strokeWidth = 2f)
        } else {
            drawLine(RADIAL, point + dir * (size * 0.3f), point + dir * size, strokeWidth = 2f)
        }
    }
    if (outward) drawCircle(RADIAL, size * 0.22f, point)
}

/** Target: a ring with a dot and four ticks. Anti-target: crossed. Magenta, as usual. */
/** The next burn: a blue ring with a notch each side, showing the way to point to fly it. */
private fun DrawScope.drawBurnMarker(centre: Offset, radius: Float, burn: Vec3?) {
    if (burn == null) return
    val size = radius * 0.11f
    project(centre, radius, burn)?.let { p ->
        drawCircle(BURN, size, p, style = Stroke(3f))
        drawCircle(BURN, size * 0.3f, p)
        drawLine(BURN, p + Offset(-size * 1.6f, 0f), p + Offset(-size, 0f), strokeWidth = 3f)
        drawLine(BURN, p + Offset(size, 0f), p + Offset(size * 1.6f, 0f), strokeWidth = 3f)
    }
}

private val BURN = Color(0xFF4FA3FF)

private fun DrawScope.drawTargetMarkers(centre: Offset, radius: Float, target: Vec3?) {
    if (target == null) return
    val size = radius * 0.1f
    project(centre, radius, target)?.let { p ->
        drawCircle(TARGET, size, p, style = Stroke(2f))
        drawCircle(TARGET, size * 0.25f, p)
        for (k in 0 until 4) {
            val angle = k * PI / 2.0
            val d = Offset(cos(angle).toFloat(), sin(angle).toFloat())
            drawLine(TARGET, p + d * size, p + d * (size * 1.5f), strokeWidth = 2f)
        }
    }
    project(centre, radius, -target)?.let { p ->
        drawCircle(TARGET, size, p, style = Stroke(2f))
        val d = size * 0.7f
        drawLine(TARGET, p + Offset(-d, -d), p + Offset(d, d), strokeWidth = 2f)
        drawLine(TARGET, p + Offset(-d, d), p + Offset(d, -d), strokeWidth = 2f)
    }
}

/**
 * Where the craft is going through the air: a small aircraft symbol. Compared with prograde, it
 * shows the crosswind a plane is crabbing into and the angle of attack it's holding.
 */
private fun DrawScope.flightPathMarker(point: Offset, size: Float) {
    val colour = AIR
    drawCircle(colour, size * 0.55f, point, style = Stroke(2f))
    drawLine(colour, point + Offset(-size * 1.6f, 0f), point + Offset(-size * 0.55f, 0f), strokeWidth = 2f)
    drawLine(colour, point + Offset(size * 0.55f, 0f), point + Offset(size * 1.6f, 0f), strokeWidth = 2f)
    drawLine(colour, point + Offset(0f, -size * 0.55f), point + Offset(0f, -size * 1.1f), strokeWidth = 2f)
}

/** Heading ticks round the horizon every 30 degrees, and N, E, S, W. */
private fun DrawScope.drawCompass(
    centre: Offset,
    radius: Float,
    north: Vec3,
    east: Vec3,
    measurer: androidx.compose.ui.text.TextMeasurer,
) {
    // North and east are on the horizon. Tilt them back onto the sphere's surface if the local
    // vertical isn't quite at right angles to them.
    for (k in 0 until 12) {
        val angle = k * PI / 6.0
        val d = Vec3(
            north.x * cos(angle) + east.x * sin(angle),
            north.y * cos(angle) + east.y * sin(angle),
            north.z * cos(angle) + east.z * sin(angle),
        ).normalizeInPlace()
        val p = project(centre, radius, d) ?: continue
        val cardinal = k % 3 == 0
        val tick = radius * (if (cardinal) 0.07f else 0.04f)
        drawLine(HORIZON_LINE, p + Offset(0f, -tick), p + Offset(0f, tick), strokeWidth = if (cardinal) 2f else 1.2f)
        if (cardinal) {
            val label = arrayOf("N", "E", "S", "W")[k / 3]
            val layout = measurer.measure(
                label,
                androidx.compose.ui.text.TextStyle(
                    color = if (label == "N") ApogeeColors.Caution else HORIZON_LINE,
                    fontSize = androidx.compose.ui.unit.TextUnit(9f, androidx.compose.ui.unit.TextUnitType.Sp),
                ),
            )
            drawText(layout, topLeft = p + Offset(-layout.size.width / 2f, -tick - layout.size.height))
        }
    }
}

/** The fixed nose marker at the centre. It never moves, and the world moves behind it. */
private fun DrawScope.drawReticle(centre: Offset, radius: Float) {
    val arm = radius * 0.22f
    val gap = radius * 0.06f
    val colour = ApogeeColors.Caution
    drawLine(colour, centre + Offset(-arm, 0f), centre + Offset(-gap, 0f), strokeWidth = 2.5f)
    drawLine(colour, centre + Offset(gap, 0f), centre + Offset(arm, 0f), strokeWidth = 2.5f)
    drawLine(colour, centre + Offset(0f, -gap), centre + Offset(0f, -arm * 0.6f), strokeWidth = 2.5f)
    drawCircle(colour, 2.5f, centre)
}

private const val HORIZON_SAMPLES = 96

private val SKY = Color(0xFF2C5A8C)
private val GROUND = Color(0xFF6B4A2A)
private val SKY_LINE = Color(0x66D6E8FF)
private val GROUND_LINE = Color(0x66FFD9B0)
private val HORIZON_LINE = Color(0xFFF2F2F2)
private val NORMAL = Color(0xFFD27CFF)
private val RADIAL = Color(0xFF6FE3FF)
private val TARGET = Color(0xFFFF5FD2)
private val AIR = Color(0xFFFFE08A)
