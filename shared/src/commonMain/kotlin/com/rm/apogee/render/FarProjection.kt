package com.rm.apogee.render

import com.rm.apogee.core.math.Vec3

/** Keep depth precision as the map camera moves away from the worlds it draws. */
object FarProjection {
    const val MIN_NEAR = 100.0
    fun nearPlane(frame: RenderFrame, camera: Vec3, far: Double): Double {
        val world = frame.world ?: return maxOf(MIN_NEAR, far * 1e-6)
        var gap = camera.length - world.radius - maxOf(world.maxElevation, world.radius * 0.012)
        if (frame.nearestDistance > 0.0) gap = minOf(gap, frame.nearestDistance)
        for (item in frame.farItems) {
            val scale = item.scale
            val stretch = if (scale == null) 1.0 else maxOf(kotlin.math.abs(scale.x), kotlin.math.abs(scale.y), kotlin.math.abs(scale.z))
            gap = minOf(gap, item.position.distanceTo(camera) - PartModels.boundingRadius(item.shape) * stretch)
        }
        for (globe in frame.farGlobes) gap = minOf(gap, globe.position.distanceTo(camera) - globe.radius * 1.02)
        return maxOf(MIN_NEAR, far * 1e-6, minOf(gap * 0.1, far * 0.1))
    }
}
