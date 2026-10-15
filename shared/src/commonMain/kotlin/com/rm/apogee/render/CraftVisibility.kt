package com.rm.apogee.render

import com.rm.apogee.core.math.Quat
import com.rm.apogee.core.math.Vec3
import kotlin.math.PI
import kotlin.math.acos
import kotlin.math.asin
import kotlin.math.atan
import kotlin.math.sqrt
import kotlin.math.tan

/**
 * Whether a craft's bounding sphere could show in a frame, erring towards drawing it. Used by the
 * frame build only, on its own thread; one instance per thread.
 */
internal class CraftVisibility {
    private val toCraft = Vec3()
    private val forward = Vec3()

    /**
     * [centre] and [radius] bound the craft, in the same frame as [camera], which looks down its
     * own -z turned by [rotation]. [fovY] is the vertical field of view in radians, [aspect] width
     * over height and [heightPx] the drawn height in pixels. [ground] is the radius of the solid
     * ground of the world centred on the origin, or 0 to skip the horizon test.
     */
    fun visible(
        centre: Vec3, radius: Double, camera: Vec3, rotation: Quat,
        fovY: Double, aspect: Double, heightPx: Int, ground: Double,
    ): Boolean {
        toCraft.setTo(centre).subInPlace(camera)
        val distance = toCraft.length
        if (distance <= radius) return true
        // Too small: under a fraction of a pixel across.
        val tanY = tan(fovY * 0.5)
        if (radius / distance * heightPx * 0.5 / tanY < MIN_PIXELS) return false
        // Outside a cone round the view's corners, widened for the camera turning before the next
        // frame is built.
        val tanX = tanY * aspect
        val cone = atan(sqrt(tanX * tanX + tanY * tanY)) + TURN_MARGIN
        if (cone < PI) {
            rotation.rotate(forward.setTo(0.0, 0.0, -1.0), forward)
            val off = acos(((toCraft dot forward) / distance).coerceIn(-1.0, 1.0)) - asin(radius / distance)
            if (off > cone) return false
        }
        if (ground > 0.0 && hidden(radius, distance, camera, ground)) return false
        return true
    }

    /**
     * Whether the ground sphere hides all of the craft: inside the cone the sphere fills from the
     * camera and past the circle where that cone touches it, so behind the near side.
     */
    private fun hidden(radius: Double, distance: Double, camera: Vec3, ground: Double): Boolean {
        val height = camera.length
        if (height <= ground) return false
        // Along the line from the camera to the world's centre.
        val along = -(toCraft dot camera) / height
        val touch = (height * height - ground * ground) / height
        if (along - radius <= touch) return false
        val off = acos((along / distance).coerceIn(-1.0, 1.0))
        return off + asin(radius / distance) < asin(ground / height)
    }

    companion object {
        /** Under this many pixels in radius a craft is left out. */
        const val MIN_PIXELS = 0.25
        /** How far past the view's corners a craft is kept, in radians. */
        const val TURN_MARGIN = 35.0 * PI / 180.0
    }
}
