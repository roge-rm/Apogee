package com.rm.apogee.game

import kotlin.math.hypot

/**
 * Turns raw touches on the assembly building's 3D view into gestures. Plain numbers in, so it's
 * testable without a device.
 *
 * - One finger moving turns the view round the craft.
 * - A tap neither travels nor lingers; two close together are a double tap.
 * - A finger held still picks up the part under it and carries it until lifted.
 * - Two fingers pan (together) and zoom (apart). After two, the finger left behind doesn't turn
 *   the view.
 */
class BuilderGestures(private val listener: Listener) {

    interface Listener {
        fun tap(x: Float, y: Float)
        fun doubleTap(x: Float, y: Float)
        /** Held still. True if that picked something up, so later moves carry it. */
        fun longPress(x: Float, y: Float): Boolean
        fun carry(x: Float, y: Float)
        fun drop(x: Float, y: Float)
        fun orbit(dx: Float, dy: Float)
        fun pan(dx: Float, dy: Float)
        fun zoom(factor: Float)
    }

    private enum class Mode { IDLE, ONE, CARRYING, TWO, SPENT }

    private var mode = Mode.IDLE
    private var downX = 0f
    private var downY = 0f
    private var downTime = 0L
    private var lastX = 0f
    private var lastY = 0f
    private var travelled = 0f
    /** Held long enough once already in this touch, and nothing was picked up. */
    private var pressed = false
    private var lastSpread = 0f
    private var lastTapTime = Long.MIN_VALUE / 2
    private var lastTapX = 0f
    private var lastTapY = 0f

    /** A first finger down at ([x], [y]). */
    fun down(x: Float, y: Float, time: Long) {
        mode = Mode.ONE
        downX = x; downY = y; downTime = time
        lastX = x; lastY = y
        travelled = 0f
        pressed = false
    }

    /** A second finger down. From here the touch is a pan and a pinch. */
    fun secondDown(x0: Float, y0: Float, x1: Float, y1: Float) {
        if (mode == Mode.CARRYING) return
        mode = Mode.TWO
        lastX = (x0 + x1) * 0.5f; lastY = (y0 + y1) * 0.5f
        lastSpread = hypot(x1 - x0, y1 - y0)
    }

    /** One finger moved. */
    fun move(x: Float, y: Float) {
        when (mode) {
            Mode.ONE -> {
                travelled = maxOf(travelled, hypot(x - downX, y - downY))
                listener.orbit(x - lastX, y - lastY)
            }
            Mode.CARRYING -> listener.carry(x, y)
            else -> {}
        }
        lastX = x; lastY = y
    }

    /** Two fingers moved. */
    fun move(x0: Float, y0: Float, x1: Float, y1: Float) {
        if (mode == Mode.CARRYING) { move(x0, y0); return }
        if (mode != Mode.TWO) return
        val cx = (x0 + x1) * 0.5f; val cy = (y0 + y1) * 0.5f
        val spread = hypot(x1 - x0, y1 - y0)
        listener.pan(cx - lastX, cy - lastY)
        if (lastSpread > 1f && spread > 1f) listener.zoom(spread / lastSpread)
        lastX = cx; lastY = cy; lastSpread = spread
    }

    /** One of two fingers lifted. The other does nothing more this touch. */
    fun secondUp() {
        if (mode == Mode.TWO) mode = Mode.SPENT
    }

    /** The last finger lifted at ([x], [y]). */
    fun up(x: Float, y: Float, time: Long) {
        when (mode) {
            Mode.CARRYING -> listener.drop(x, y)
            Mode.ONE -> {
                val moved = maxOf(travelled, hypot(x - downX, y - downY))
                if (moved < TAP_SLOP && time - downTime < TAP_TIMEOUT) {
                    if (time - lastTapTime < DOUBLE_TAP_WINDOW && hypot(x - lastTapX, y - lastTapY) < DOUBLE_TAP_SLOP) {
                        listener.doubleTap(x, y)
                        lastTapTime = Long.MIN_VALUE / 2
                    } else {
                        listener.tap(x, y)
                        lastTapTime = time; lastTapX = x; lastTapY = y
                    }
                }
            }
            else -> {}
        }
        mode = Mode.IDLE
    }

    /** The touch was taken away, by the system or a panel. */
    fun cancel() {
        if (mode == Mode.CARRYING) listener.drop(Float.NaN, Float.NaN)
        mode = Mode.IDLE
    }

    /** Called by a timer set on finger down: a finger held still long enough is a long press. */
    fun tick(time: Long) {
        if (mode != Mode.ONE || pressed || travelled >= TAP_SLOP || time - downTime < LONG_PRESS) return
        pressed = true
        // If there's nothing to pick up, the finger can still turn the view.
        if (listener.longPress(lastX, lastY)) mode = Mode.CARRYING
    }

    companion object {
        /** A finger put down to tap always moves a few pixels. */
        const val TAP_SLOP = 28f
        const val TAP_TIMEOUT = 400L
        const val LONG_PRESS = 420L
        const val DOUBLE_TAP_WINDOW = 320L
        const val DOUBLE_TAP_SLOP = 90f
    }
}
