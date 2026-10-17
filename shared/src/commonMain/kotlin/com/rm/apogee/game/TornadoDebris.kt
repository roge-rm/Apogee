package com.rm.apogee.game

import com.rm.apogee.core.math.Vec3
import com.rm.apogee.render.QualityTier

/** What a land tornado picks up, by the ground under it. */
enum class DebrisKind { SOIL, SAND, SNOW }

/**
 * The bits a land tornado throws this frame, for [Effects] to draw with the particles: body-fixed
 * places, sizes in metres and colours. Filled by [TornadoScene.append] each frame from where each
 * bit is in its own loop, so nothing is kept from frame to frame; empty with no tornado near.
 */
class TornadoDebris(tier: QualityTier) {
    /** At most this many a frame. */
    val capacity = when (tier) {
        QualityTier.LOW -> 64
        QualityTier.MEDIUM -> 360
        QualityTier.HIGH -> 1_000
    }

    val x = DoubleArray(capacity); val y = DoubleArray(capacity); val z = DoubleArray(capacity)
    /** Half its length and half its width as drawn, in metres. */
    val length = FloatArray(capacity)
    val width = FloatArray(capacity)
    /** Which way its length lies on the screen, radians, and how much of it shows as it tumbles, 0..1. */
    val angle = FloatArray(capacity)
    val fore = FloatArray(capacity)
    /** r, g, b, a per bit. */
    val colour = FloatArray(capacity * 4)
    var count = 0
        internal set

    internal fun add(at: Vec3, long: Double, wide: Double, turned: Double, showing: Double, r: Float, g: Float, b: Float, a: Float) {
        if (count >= capacity) return
        val i = count++
        x[i] = at.x; y[i] = at.y; z[i] = at.z
        length[i] = long.toFloat(); width[i] = wide.toFloat(); angle[i] = turned.toFloat(); fore[i] = showing.toFloat()
        colour[i * 4] = r; colour[i * 4 + 1] = g; colour[i * 4 + 2] = b; colour[i * 4 + 3] = a
    }

    internal fun clear() { count = 0 }

    companion object {
        /** A piece is drawn at least this many radians long each side, and [SEEN_WIDE] wide, so it reads from afar. */
        const val SEEN = 0.0035
        const val SEEN_WIDE = 0.0014
    }
}
