package com.rm.apogee.game

import com.rm.apogee.core.math.Quat
import com.rm.apogee.core.math.Vec3
import com.rm.apogee.core.part.ModelSpec
import com.rm.apogee.render.RenderItem

/** A shed engine cover, starting at the attached shell's pose in the frame that creates it. */
class ShroudPanel(
    val shape: ModelSpec.Lathe,
    val bodyId: String,
    val pivot: Vec3,
    val velocity: Vec3,
    val start: Quat,
    val axis: Vec3,
    val rate: Double,
    val foot: Vec3,
    val key: Long,
    val referenceKey: Long = 0L,
) {
    var age = 0.0
        private set
    private var fresh = true

    fun advance(frameDt: Double, gravity: Double, colour: FloatArray): RenderItem {
        // The creation pose is already at this frame's time; only subsequent frames may advance it.
        val born = fresh
        val dt = if (fresh) 0.0 else frameDt
        fresh = false
        age += dt
        val down = pivot.normalized().negateInPlace()
        velocity.addScaledInPlace(down, gravity * dt)
        pivot.addScaledInPlace(velocity, dt)
        val rotation = Quat.fromAxisAngle(axis, rate * age) * start
        val position = pivot.copy().subInPlace(rotation.rotate(foot))
        return RenderItem(shape, position, rotation, colour, caps = 0, key = key, spawnReference = if (born) referenceKey else 0L)
    }
}
