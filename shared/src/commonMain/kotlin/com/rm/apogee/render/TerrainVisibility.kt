package com.rm.apogee.render

import com.rm.apogee.core.math.Quat
import com.rm.apogee.core.math.Vec3
import kotlin.math.sqrt

/** Side-plane tests for a terrain sphere, including the water and morphs it may draw. */
internal class TerrainVisibility {
    private val local = Vec3()

    fun visible(centreFromCamera: Vec3, radius: Double, rotation: Quat, tanY: Double, aspect: Double): Boolean {
        rotation.inverseRotate(centreFromCamera, local)
        val depth = -local.z
        if (depth < -radius) return false
        val tanX = tanY * aspect
        val xMargin = radius * sqrt(1.0 + tanX * tanX)
        val yMargin = radius * sqrt(1.0 + tanY * tanY)
        return local.x - depth * tanX <= xMargin && -local.x - depth * tanX <= xMargin &&
            local.y - depth * tanY <= yMargin && -local.y - depth * tanY <= yMargin
    }
}
