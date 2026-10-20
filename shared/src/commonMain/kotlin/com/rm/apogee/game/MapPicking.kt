package com.rm.apogee.game

import com.rm.apogee.core.math.Quat
import com.rm.apogee.core.math.Vec3
import kotlin.math.*

enum class MapPickKind { WORLD, PLACE, CRAFT, PATH, EVENT, BURN }
enum class MapAction(val label: String) {
    FOCUS("Focus view"), TARGET("Set target"), CLEAR_TARGET("Clear target"),
    ADD_BURN("Add burn"), MOVE_BURN("Move burn here"), EDIT_BURN("Edit burn"),
    DELETE_BURN("Delete burn"), WARP("Warp here"), FLY("Fly craft"),
}

/** A selectable map object, in the same reference frame as the drawn path. */
data class MapPick(
    val key: String, val name: String, val kind: MapPickKind, val at: Vec3,
    val bodyId: String = "", val vesselId: Long = -1L, val radius: Double = 0.0,
    val time: Double = Double.NaN, val detail: String = "", val canPlan: Boolean = false,
    val altitude: Double = Double.NaN, val speed: Double = Double.NaN, val separation: Double = Double.NaN,
)

data class MapSelection(
    val choices: List<MapPick> = emptyList(), val selected: MapPick? = null,
    val distance: Double = Double.NaN, val inSeconds: Double = Double.NaN,
    val actions: List<MapAction> = emptyList(),
)

/** Projection and picking share the map camera's 55 degree field of view. */
internal class MapPicking(val camera: Vec3, val rotation: Quat, val width: Float, val height: Float) {
    private val focal = height / (2.0 * tan(55.0 * PI / 360.0))
    fun project(point: Vec3): Pair<Float, Float>? {
        val d = rotation.inverseRotate(point - camera)
        if (-d.z <= 1e-6) return null
        return (width / 2 + d.x / -d.z * focal).toFloat() to (height / 2 - d.y / -d.z * focal).toFloat()
    }

    /** True when the tap ray crosses the visible sphere, including away from its centre. */
    fun sphere(x: Float, y: Float, centre: Vec3, radius: Double): Boolean {
        val ray = rotation.rotate(Vec3((x - width / 2) / focal, -(y - height / 2) / focal, -1.0)).normalizeInPlace()
        val offset = camera - centre
        val near = -(offset dot ray)
        val square = radius * radius - (offset.lengthSq - near * near)
        return square >= 0.0 && near + sqrt(square) > 0.0
    }

    /** Interpolates between projected samples so a thin path has no gaps between tap targets. */
    fun path(x: Float, y: Float, points: Array<Vec3>, times: DoubleArray, visible: (Vec3) -> Boolean): PathHit? {
        var best: PathHit? = null
        for (i in 0 until points.size - 1) {
            val a = project(points[i]) ?: continue
            val b = project(points[i + 1]) ?: continue
            val dx = b.first - a.first; val dy = b.second - a.second
            val square = dx * dx + dy * dy
            val f = if (square > 0f) (((x - a.first) * dx + (y - a.second) * dy) / square).coerceIn(0f, 1f) else 0f
            val distance = hypot(a.first + f * dx - x, a.second + f * dy - y)
            val da = -rotation.inverseRotate(points[i] - camera).z
            val db = -rotation.inverseRotate(points[i + 1] - camera).z
            val along = f * da / ((1.0 - f) * db + f * da)
            val at = points[i].copy().addScaledInPlace(points[i + 1] - points[i], along)
            if (visible(at) && (best == null || distance < best.distance))
                best = PathHit(times[i] + along * (times[i + 1] - times[i]), distance, at)
        }
        return best
    }

    fun radius(centre: Vec3, radius: Double): Float {
        val distance = centre.distanceTo(camera)
        return if (distance > radius) (focal * radius / sqrt(distance * distance - radius * radius)).toFloat() else 0f
    }

    data class PathHit(val time: Double, val distance: Float, val at: Vec3)
}
