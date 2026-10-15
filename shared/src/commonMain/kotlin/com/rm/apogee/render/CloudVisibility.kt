package com.rm.apogee.render

import com.rm.apogee.core.math.Quat
import com.rm.apogee.core.math.Vec3
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.sqrt

/** Conservative sphere bounds for cloud puffs; no scene membership or fade changes. */
internal class CloudVisibility {
    private val local = Vec3()

    fun visible(item: RenderItem, previous: RenderItem?, camera: Vec3, rotation: Quat, tanY: Double, aspect: Double): Boolean {
        if (item.shape !is CloudPuff) return true
        // Both endpoints must be outside the same plane to reject an interpolated puff.
        val now = outside(item, camera, rotation, tanY, aspect)
        if (now == 0) return true
        return previous?.takeIf { it.shape == item.shape }?.let {
            now and outside(it, camera, rotation, tanY, aspect) == 0
        } ?: false
    }

    private fun outside(item: RenderItem, camera: Vec3, rotation: Quat, tanY: Double, aspect: Double): Int {
        rotation.inverseRotate(local.setTo(item.position).subInPlace(camera), local)
        val scale = item.scale
        val radius = 1.3 * if (scale == null) 1.0 else max(abs(scale.x), max(abs(scale.y), abs(scale.z)))
        val depth = -local.z
        val tanX = tanY * aspect
        val xMargin = radius * sqrt(1.0 + tanX * tanX)
        val yMargin = radius * sqrt(1.0 + tanY * tanY)
        var planes = 0
        if (depth < -radius) planes = planes or 1
        if (local.x - depth * tanX > xMargin) planes = planes or 2
        if (-local.x - depth * tanX > xMargin) planes = planes or 4
        if (local.y - depth * tanY > yMargin) planes = planes or 8
        if (-local.y - depth * tanY > yMargin) planes = planes or 16
        return planes
    }
}
