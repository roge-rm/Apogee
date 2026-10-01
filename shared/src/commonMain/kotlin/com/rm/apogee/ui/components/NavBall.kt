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
import com.rm.apogee.core.math.Math

/**
 * The attitude instrument: a sphere fixed to the local horizon, seen from the craft.
 *
 * Worked out in the craft's own frame. The centre is where the nose points, so the nose stays put
 * and the world turns behind it. Screen right is the craft's +X, screen up is +Z, and depth is +Y
 * (the nose), so a direction is on the visible half when its local Y is positive.
 *
 * Drawn on a Compose Canvas, not in GL, so it's easy to test and restyle.
 */
@Composable
fun NavBall(
    /**
     * What it shows, read only while drawing, so a new attitude each frame redraws the ball
     * without recomposing anything.
     */
    live: () -> com.rm.apogee.game.FlightTelemetry,
    modifier: Modifier = Modifier,
    size: Dp = 170.dp,
    /** The frame the markers are in, for the tag. */
    frame: com.rm.apogee.core.world.NavFrame = com.rm.apogee.core.world.NavFrame.SURFACE,
    /** Whether the frame was chosen by hand instead of left on automatic. */
    frameManual: Boolean = false,
    /** Tapping the tag gives the next frame. */
    onCycleFrame: (() -> Unit)? = null,
) {
    val textMeasurer = androidx.compose.ui.text.rememberTextMeasurer()
    androidx.compose.foundation.layout.Box(modifier.size(size)) {
    Canvas(Modifier.size(size)) {
        val radius = kotlin.math.min(this.size.width, this.size.height) * 0.5f - 4f
        val centre = Offset(this.size.width / 2f, this.size.height / 2f)
        // Attitude, local up, and the markers. Prograde is null when it isn't moving.
        val now = live()
        val rotation = now.rotation
        val worldUp = now.up
        val prograde = now.prograde
        val normal = now.normal
        val radialOut = now.radialOut
        val toTarget = now.toTarget
        val throughAir = now.throughAir
        val burn = now.burn

        // Local-frame versions of the world directions we care about.
        val up = rotation.inverseRotate(worldUp).normalizeInPlace()
        fun local(v: Vec3?) = v?.let { rotation.inverseRotate(it).normalizeInPlace() }
        val progradeLocal = local(prograde)
        val normalLocal = local(normal)
        val radialLocal = local(radialOut)
        val targetLocal = local(toTarget)
        val airLocal = local(throughAir)
        // Compass north and east on the horizon, about the body's own +Y axis, whatever the frame.
        val worldEast = Vec3(worldUp.z, 0.0, -worldUp.x).let { if (it.lengthSq < 1e-12) Vec3(1.0, 0.0, 0.0) else it.normalizeInPlace() }
        val worldNorth = worldUp.cross(worldEast).normalizeInPlace()
        val northLocal = rotation.inverseRotate(worldNorth).normalizeInPlace()
        val eastLocal = rotation.inverseRotate(worldEast).normalizeInPlace()

        // An orthonormal pair spanning the horizon plane.
        val a = perpendicularTo(up)
        val b = up.cross(a).normalizeInPlace()

        // Sampled once, for the ground's fill and the horizon line.
        val horizon = horizonPoints(up, a, b)
        drawSphere(centre, radius, up, horizon)
        drawPitchLadder(centre, radius, up, a, b)
        drawHorizon(centre, radius, horizon)
        drawCompass(centre, radius, northLocal, eastLocal, textMeasurer)
        drawMarkers(centre, radius, progradeLocal, normalLocal, radialLocal)
        drawTargetMarkers(centre, radius, targetLocal)
        drawBurnMarker(centre, radius, local(burn))
        airLocal?.let { project(centre, radius, it) }?.let { flightPathMarker(it, radius * 0.09f) }
        drawReticle(centre, radius)
    }
        // The markers' frame. Tap for the next. Bright when chosen by hand, dim on automatic.
        val tagColour = when (frame) {
            com.rm.apogee.core.world.NavFrame.ORBIT -> ApogeeColors.Prograde
            com.rm.apogee.core.world.NavFrame.TARGET -> TARGET
            else -> ApogeeColors.Data
        }
        androidx.compose.material3.Text(
            frame.label + if (frameManual) "" else " \u00b7",
            style = TAG_STYLE,
            color = tagColour.alpha(if (frameManual) 1f else 0.75f),
            modifier = Modifier
                // In the corner, clear of the compass letters.
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

/** Orthographic projection of a local direction onto the ball, or null on the far half. */
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
 * Fills the sky and ground halves. The ground is bounded by the visible horizon arc and the piece
 * of the outline below it, which meet at `±(nose × up)`.
 */
private fun DrawScope.drawSphere(centre: Offset, radius: Float, up: Vec3, horizon: List<Vec3>) {
    drawCircle(SKY, radius, centre)

    val visible = horizon.count { it.y > 0.0 }
    if (visible == 0) {
        // Looking straight down, the whole visible half is ground.
        if (up.y < 0.0) drawCircle(GROUND, radius, centre)
        return
    }
    if (visible == horizon.size) return // entirely sky

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
    List(HORIZON_SAMPLES) { i ->
        val c = RING_COS[i]
        val s = RING_SIN[i]
        Vec3(a.x * c + b.x * s, a.y * c + b.y * s, a.z * c + b.z * s)
    }

/** Cosines and sines round a sampled circle, worked out once. */
private val RING_COS = DoubleArray(HORIZON_SAMPLES) { cos(2.0 * PI * it / HORIZON_SAMPLES) }
private val RING_SIN = DoubleArray(HORIZON_SAMPLES) { sin(2.0 * PI * it / HORIZON_SAMPLES) }

/**
 * The visible run of a sampled circle in one piece. Otherwise it's often split across the ends of
 * the list, and walking it would draw a line across the ball.
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

private fun DrawScope.drawHorizon(centre: Offset, radius: Float, horizon: List<Vec3>) {
    strokeCircle(centre, radius, horizon, HORIZON_LINE, HORIZON_STROKE)
    // The ball's own outline.
    drawCircle(OUTLINE, radius, centre, style = OUTLINE_STROKE)
}

/** Rings of constant pitch. */
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
        val points = List(HORIZON_SAMPLES) { i ->
            val c = RING_COS[i]
            val s = RING_SIN[i]
            Vec3(
                up.x * height + (a.x * c + b.x * s) * ringRadius,
                up.y * height + (a.y * c + b.y * s) * ringRadius,
                up.z * height + (a.z * c + b.z * s) * ringRadius,
            )
        }
        val colour = if (pitchDegrees > 0) SKY_LINE else GROUND_LINE
        strokeCircle(centre, radius, points, colour, LADDER_STROKE)
    }
}

/** Draws the visible part of a sampled circle as a polyline. */
private fun DrawScope.strokeCircle(
    centre: Offset,
    radius: Float,
    points: List<Vec3>,
    colour: Color,
    stroke: Stroke,
) {
    // One path for the whole visible run, not a line per sample.
    val path = Path()
    var drawing = false
    for (k in 0..points.size) {
        val direction = points[k % points.size]
        if (direction.y <= 0.0) { drawing = false; continue }
        val x = centre.x + radius * direction.x.toFloat()
        val y = centre.y - radius * direction.z.toFloat()
        if (drawing) path.lineTo(x, y) else path.moveTo(x, y)
        drawing = true
    }
    drawPath(path, colour, style = stroke)
}

/**
 * The six markers:
 * - prograde: a ring with three spurs. Retrograde: the same, crossed through.
 * - normal: a triangle pointing out. Anti-normal: a triangle pointing back.
 * - radial out: a ring with four spurs outward. Radial in: four spurs inward.
 *
 * Each pair shares a colour. One on the far side isn't drawn, since its opposite is on this side.
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
        // Anti-normal: a short line in from the tip.
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

/** The next burn: a blue ring with a notch each side, where to point to fly it. */
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

/** Target: a ring with a dot and four ticks. Anti-target: crossed. */
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
 * Where the craft's going through the air, as a small plane. Against prograde it shows crab and
 * angle of attack.
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
    // Normalised, in case up isn't quite at right angles to north and east.
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
            val label = CARDINALS[k / 3]
            val layout = measurer.measure(label, if (k == 0) NORTH_STYLE else CARDINAL_STYLE)
            drawText(layout, topLeft = p + Offset(-layout.size.width / 2f, -tick - layout.size.height))
        }
    }
}

/** The fixed nose marker at the centre. */
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

private val HORIZON_STROKE = Stroke(2.5f)
private val LADDER_STROKE = Stroke(1f)
private val OUTLINE_STROKE = Stroke(1.5f)
private val OUTLINE = Color.White.alpha(ApogeeAlpha.BORDER)
private val CARDINALS = arrayOf("N", "E", "S", "W")
private val NORTH_STYLE = androidx.compose.ui.text.TextStyle(color = ApogeeColors.Caution, fontSize = androidx.compose.ui.unit.TextUnit(9f, androidx.compose.ui.unit.TextUnitType.Sp))
private val CARDINAL_STYLE = androidx.compose.ui.text.TextStyle(color = HORIZON_LINE, fontSize = androidx.compose.ui.unit.TextUnit(9f, androidx.compose.ui.unit.TextUnitType.Sp))
private val TAG_STYLE = com.rm.apogee.ui.theme.TelemetryTextStyle.copy(fontSize = androidx.compose.ui.unit.TextUnit(10f, androidx.compose.ui.unit.TextUnitType.Sp))
