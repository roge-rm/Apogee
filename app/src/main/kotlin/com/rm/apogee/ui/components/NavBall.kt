package com.rm.apogee.ui.components

import androidx.compose.foundation.Canvas
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
 * The attitude instrument: a sphere fixed to the local horizon, seen from the
 * craft.
 *
 * Everything is computed in the *craft's own frame*, which removes an entire
 * class of confusion. The ball's centre is the direction the nose points, so
 * the nose never moves; the world rotates behind it, exactly as a real attitude
 * indicator behaves. Screen right is the craft's +X, screen up is its +Z, and
 * depth is +Y - the nose axis - so a direction is on the visible hemisphere
 * precisely when its local Y is positive.
 *
 * Drawn on a Compose Canvas rather than in GL: it is a flat instrument in
 * screen space, the projection is a dozen lines of orthographic maths, and
 * keeping it here means it is testable and restyleable without touching a
 * shader.
 */
@Composable
fun NavBall(
    /** Orientation of the craft. */
    rotation: Quat,
    /** Local vertical, in the same frame the craft's position is in. */
    worldUp: Vec3,
    /** Direction of travel relative to the surface, or null when stationary. */
    prograde: Vec3?,
    modifier: Modifier = Modifier,
    size: Dp = 170.dp,
) {
    Canvas(modifier.size(size)) {
        val radius = kotlin.math.min(this.size.width, this.size.height) * 0.5f - 4f
        val centre = Offset(this.size.width / 2f, this.size.height / 2f)

        // Local-frame versions of the world directions we care about.
        val up = rotation.inverseRotate(worldUp).normalizeInPlace()
        val progradeLocal = prograde?.let { rotation.inverseRotate(it).normalizeInPlace() }

        // An orthonormal pair spanning the horizon plane.
        val a = perpendicularTo(up)
        val b = up.cross(a).normalizeInPlace()

        drawSphere(centre, radius, up, a, b)
        drawPitchLadder(centre, radius, up, a, b)
        drawHorizon(centre, radius, up, a, b)
        drawMarkers(centre, radius, progradeLocal)
        drawReticle(centre, radius)
    }
}

/** Any unit vector perpendicular to [v]. */
private fun perpendicularTo(v: Vec3): Vec3 {
    val axis = if (abs(v.x) < 0.9) Vec3.unitX() else Vec3.unitY()
    return v.cross(axis).normalizeInPlace()
}

/**
 * Orthographic projection of a local-space direction onto the ball.
 *
 * Returns null when the direction is on the far hemisphere, which is what makes
 * the ball read as a sphere rather than a disc.
 */
private fun project(centre: Offset, radius: Float, direction: Vec3): Offset? {
    if (direction.y <= 0.0) return null
    return Offset(
        centre.x + radius * direction.x.toFloat(),
        centre.y - radius * direction.z.toFloat(),
    )
}

/** Where a silhouette direction lands, ignoring which hemisphere it is on. */
private fun projectUnclipped(centre: Offset, radius: Float, direction: Vec3): Offset =
    Offset(
        centre.x + radius * direction.x.toFloat(),
        centre.y - radius * direction.z.toFloat(),
    )

/**
 * Fills the sky and ground hemispheres.
 *
 * The ground is bounded by two arcs: the visible half of the horizon great
 * circle, and the piece of the ball's silhouette below it. Their meeting points
 * are exactly where the horizon plane crosses the silhouette, which is
 * `±(nose × up)` - so they are found analytically rather than searched for.
 */
private fun DrawScope.drawSphere(centre: Offset, radius: Float, up: Vec3, a: Vec3, b: Vec3) {
    drawCircle(SKY, radius, centre)

    val horizon = horizonPoints(up, a, b)
    val visible = horizon.filter { it.y > 0.0 }
    if (visible.isEmpty()) {
        // Looking straight down: the whole visible hemisphere is ground.
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

    // Close along the silhouette, sweeping the way that passes beneath.
    val first = ordered.first()
    val last = ordered.last()
    val startAngle = screenAngle(centre, projectUnclipped(centre, radius, last))
    val endAngle = screenAngle(centre, projectUnclipped(centre, radius, first))
    // The nadir on the silhouette: straight "down" as far as the ball shows it.
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
 * Rotates a sampled circle so its visible run is contiguous, then returns it.
 *
 * Sampling starts at an arbitrary point on the circle, so the visible arc is
 * usually split across the ends of the list; walking it in that order would
 * draw a chord straight across the ball.
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

/** Adds an arc of the ball's rim, sweeping through [throughAngle] if given. */
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

/** Rings of constant pitch, so attitude can be read rather than guessed. */
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

private fun DrawScope.drawMarkers(centre: Offset, radius: Float, prograde: Vec3?) {
    val point = prograde?.let { project(centre, radius, it) } ?: return
    // Prograde: a ringed dot with three spurs, the conventional shape.
    drawCircle(ApogeeColors.Prograde, radius * 0.09f, point, style = Stroke(2f))
    drawCircle(ApogeeColors.Prograde, radius * 0.02f, point)
    val spur = radius * 0.15f
    drawLine(ApogeeColors.Prograde, point + Offset(-spur, 0f), point + Offset(-spur * 0.5f, 0f), strokeWidth = 2f)
    drawLine(ApogeeColors.Prograde, point + Offset(spur * 0.5f, 0f), point + Offset(spur, 0f), strokeWidth = 2f)
    drawLine(ApogeeColors.Prograde, point + Offset(0f, -spur), point + Offset(0f, -spur * 0.5f), strokeWidth = 2f)
}

/** The fixed nose marker at the centre. Never moves; the world moves behind it. */
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
