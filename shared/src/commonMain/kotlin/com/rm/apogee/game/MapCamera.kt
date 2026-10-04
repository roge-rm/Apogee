package com.rm.apogee.game

import com.rm.apogee.core.math.Quat
import com.rm.apogee.core.math.Vec3
import com.rm.apogee.core.math.quatLookAt
import com.rm.apogee.platform.System
import kotlin.math.exp
import kotlin.math.ln

/** What a drag, a pinch or a stick turns: the flight camera, the builder's, or the map's. */
interface OrbitControls {
    fun orbitBy(deltaYaw: Double, deltaPitch: Double)
    fun zoomBy(factor: Double)
}

/**
 * The map's camera. It turns round whatever it's looking at like a trackball: across about the
 * looked-at world's north, and up and down about the screen's own across, so it goes straight
 * over a pole without stopping or flipping. Zoom glides to the distance asked for, and a new thing
 * to look at is eased to, from wherever the view was.
 */
class MapCamera(private val minDistance: Double, private val maxDistance: Double) : OrbitControls {

    /** Camera to world: X across the screen, Y up it, and looking along -Z. */
    val rotation = Quat.identity()

    /** How far out it is now, and where it's gliding to. */
    var distance = minDistance * 10.0
        private set
    private var wanted = distance

    /** The looked-at world's north, which the view turns across about. */
    private val north = Vec3.unitY()

    /** Added to the pivot: where the last pivot was, from the new one, eased to nothing. */
    private val shift = Vec3()

    private var solvedNanos = 0L
    private val scratch = Vec3()
    private val turn = Quat()

    /** Sets which way is north, the axis the view turns across about. */
    fun setNorth(axis: Vec3) {
        if (axis.lengthSq < 1e-12) return
        north.setTo(axis).normalizeInPlace()
    }

    override fun orbitBy(deltaYaw: Double, deltaPitch: Double) {
        // Across, about north; up and down, about the screen's own across.
        Quat.fromAxisAngle(north, deltaYaw, turn)
        rotation.setTo((turn * rotation).normalizeInPlace())
        rotation.rotate(Vec3.unitX(), scratch)
        Quat.fromAxisAngle(scratch, -deltaPitch, turn)
        rotation.setTo((turn * rotation).normalizeInPlace())
    }

    override fun zoomBy(factor: Double) {
        if (factor <= 0.0) return
        wanted = (wanted / factor).coerceIn(minDistance, maxDistance)
    }

    /** Glides out or in to [distance] metres, or with [now], goes there at once. */
    fun frame(distance: Double, now: Boolean = false) {
        wanted = distance.coerceIn(minDistance, maxDistance)
        if (now) this.distance = wanted
    }

    /**
     * Looks back at the pivot from out along [from], turned so [screenUp] is up the screen as near
     * as it can be.
     */
    fun lookFrom(from: Vec3, screenUp: Vec3) {
        if (from.lengthSq < 1e-12) return
        quatLookAt(Vec3().setTo(from).mulInPlace(-1.0), screenUp, rotation)
    }

    /**
     * The pivot is about to jump from [old] to [new] (both where they are now). The view eases
     * across instead of jumping, from wherever it is on the way already.
     */
    fun moveFocus(old: Vec3, new: Vec3) {
        shift.addInPlace(old).subInPlace(new)
    }

    /** Snaps any easing still under way. */
    fun settle() {
        shift.setZero()
        distance = wanted
    }

    /** The camera at [pivot] (eased from the last), round it at the distance, into [outPosition] and [outRotation]. */
    fun solve(pivot: Vec3, outPosition: Vec3, outRotation: Quat, nowNanos: Long = System.nanoTime()) {
        val dt = if (solvedNanos == 0L) 0.0 else ((nowNanos - solvedNanos) / 1e9).coerceIn(0.0, 0.1)
        solvedNanos = nowNanos
        step(dt)
        rotation.rotate(Vec3.unitZ(), outPosition).mulInPlace(distance).addInPlace(pivot).addInPlace(shift)
        outRotation.setTo(rotation)
    }

    /** Moves the glides on by [dt] seconds. */
    fun step(dt: Double) {
        // Zoom in log space, so it feels the same near a moon as across the system.
        val zoomed = 1.0 - exp(-dt / ZOOM_EASE)
        distance = exp(ln(distance) + (ln(wanted) - ln(distance)) * zoomed)
        shift.mulInPlace(exp(-dt / FOCUS_EASE))
        if (shift.lengthSq < 1e-6) shift.setZero()
        // Keeps the screen's across level with north, a little at a time, so a new world's north
        // doesn't leave the view rolled.
        rotation.rotate(Vec3.unitX(), scratch)
        val tilt = scratch dot north
        if (kotlin.math.abs(tilt) > 1e-4) {
            rotation.rotate(Vec3.unitZ(), scratch)
            Quat.fromAxisAngle(scratch, kotlin.math.asin(tilt.coerceIn(-1.0, 1.0)) * (1.0 - exp(-dt / LEVEL_EASE)), turn)
            rotation.setTo((turn * rotation).normalizeInPlace())
        }
    }

    /** Where the camera is from the pivot, unit. */
    fun outward(out: Vec3 = Vec3()): Vec3 = rotation.rotate(Vec3.unitZ(), out)

    companion object {
        /** Time constants of the zoom and focus glides, and of levelling, in seconds. */
        const val ZOOM_EASE = 0.12
        const val FOCUS_EASE = 0.15
        const val LEVEL_EASE = 0.3
    }
}
