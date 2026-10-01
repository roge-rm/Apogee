package com.rm.apogee

import com.rm.apogee.game.BuilderGestures

/**
 * Touches on the world's surface, the same on every platform. In flight a drag turns the camera
 * and a pinch zooms; on the map a tap plans a burn and a finger on the burn drags it; in the
 * assembly building [BuilderGestures] has them. The host feeds its own events in, in pixels, with
 * times in milliseconds.
 */
class WorldGestures(private val app: ApogeeApp) {

    private var width = 1f
    private var height = 1f

    private var lastX = 0f
    private var lastY = 0f
    private var downX = 0f
    private var downY = 0f
    private var downTime = 0L

    /**
     * Set when a second finger lands, cleared when the next gesture starts. After a pinch the rest
     * of the gesture belongs to it, so the finger left behind doesn't turn into a huge drag.
     */
    private var multiTouch = false

    /** A finger holding the planned burn on the map, dragging it along the path. */
    private var holdingBurn = false

    /** Two fingers apart, for a pinch the host doesn't measure itself. */
    private var spread = 0f

    /** The assembly building's gestures: taps, holds that lift a part, two-finger pan and pinch. */
    private val building = BuilderGestures(object : BuilderGestures.Listener {
        override fun tap(x: Float, y: Float) { app.builderSession?.tap(x, y, width, height) }
        override fun doubleTap(x: Float, y: Float) { app.builderSession?.recentre() }
        override fun longPress(x: Float, y: Float): Boolean = app.builderSession?.liftAt(x, y, width, height) == true
        override fun carry(x: Float, y: Float) { app.builderSession?.carryTo(x, y) }
        override fun drop(x: Float, y: Float) {
            if (x.isNaN()) app.builderSession?.cancelCarry() else app.builderSession?.endCarry()
        }
        override fun orbit(dx: Float, dy: Float) {
            app.builderSession?.camera?.orbitBy(deltaYaw = -dx * ORBIT_RADIANS_PER_PIXEL, deltaPitch = dy * ORBIT_RADIANS_PER_PIXEL)
        }
        override fun pan(dx: Float, dy: Float) { app.builderSession?.panBy(dx, dy) }
        override fun zoom(factor: Float) { app.builderSession?.camera?.zoomBy(factor.toDouble()) }
    })

    private val building0: Boolean get() = app.builderSession != null && app.session == null

    /** The surface's size, in pixels. */
    fun size(width: Float, height: Float) {
        this.width = width.coerceAtLeast(1f)
        this.height = height.coerceAtLeast(1f)
        app.builderSession?.setViewSize(this.width, this.height)
    }

    /** The first finger (or the mouse button) goes down at [x], [y]. */
    fun down(x: Float, y: Float, time: Long) {
        if (building0) {
            app.builderSession?.setViewSize(width, height)
            building.down(x, y, time)
            return
        }
        lastX = x; lastY = y
        downX = x; downY = y
        downTime = time
        multiTouch = false
        // On the map, a finger on the planned burn takes hold of it.
        holdingBurn = app.session?.takeIf { it.mapMode }?.mapPress(x, y, width, height) == true
    }

    /** A second finger goes down: the two are at [x0], [y0] and [x1], [y1]. */
    fun secondDown(x0: Float, y0: Float, x1: Float, y1: Float) {
        if (building0) { building.secondDown(x0, y0, x1, y1); return }
        multiTouch = true
        spread = kotlin.math.hypot(x1 - x0, y1 - y0)
    }

    /** One finger moves to [x], [y]. */
    fun move(x: Float, y: Float) {
        if (building0) { building.move(x, y); return }
        if (holdingBurn) {
            app.session?.mapDrag(x, y, width, height)
        } else if (!multiTouch) {
            val dx = x - lastX
            val dy = y - lastY
            lastX = x; lastY = y
            app.activeCamera()?.orbitBy(deltaYaw = -dx * ORBIT_RADIANS_PER_PIXEL, deltaPitch = dy * ORBIT_RADIANS_PER_PIXEL)
        }
    }

    /**
     * Two fingers move. [measurePinch] when the host doesn't measure the pinch itself (Android's
     * ScaleGestureDetector does), so it's measured here from their spread.
     */
    fun move(x0: Float, y0: Float, x1: Float, y1: Float, measurePinch: Boolean) {
        if (building0) { building.move(x0, y0, x1, y1); return }
        if (!measurePinch) return
        val now = kotlin.math.hypot(x1 - x0, y1 - y0)
        if (spread > 1f && now > 1f) zoom(now / spread)
        spread = now
    }

    /** The second finger lifts. */
    fun secondUp() {
        if (building0) building.secondUp()
    }

    /** The last finger lifts at [x], [y]. */
    fun up(x: Float, y: Float, time: Long) {
        if (building0) { building.up(x, y, time); return }
        // A tap neither travelled nor lingered. The slop is generous: a finger put down to tap
        // always moves a few pixels.
        val travelled = kotlin.math.hypot(x - downX, y - downY)
        val duration = time - downTime
        if (holdingBurn) {
            holdingBurn = false
            app.session?.mapRelease()
        } else if (!multiTouch && travelled < TAP_SLOP_PIXELS && duration < TAP_TIMEOUT_MILLIS && app.session?.mapMode == true) {
            app.session?.mapTap(x, y, width, height)
        } else if (!multiTouch && travelled < TAP_SLOP_PIXELS && duration < TAP_TIMEOUT_MILLIS) {
            app.builderSession?.tap(x, y, width, height)
        }
    }

    fun cancel() {
        if (building0) building.cancel()
        holdingBurn = false
    }

    /** Time passing, for a long press in the assembly building, at [now] in milliseconds. */
    fun tick(now: Long) {
        if (building0) building.tick(now)
    }

    /** A pinch (or the mouse wheel) by [factor]: over 1 closer, under 1 further. */
    fun zoom(factor: Float) {
        if (building0) { app.builderSession?.camera?.zoomBy(factor.toDouble()); return }
        // The map's own camera in map view, not the flight view behind it.
        app.session?.let { (if (it.mapMode) it.mapCamera else it.camera).zoomBy(factor.toDouble()) }
    }

    companion object {
        const val ORBIT_RADIANS_PER_PIXEL = 0.005

        /** How far a touch can travel and still count as a tap. */
        const val TAP_SLOP_PIXELS = 28f
        const val TAP_TIMEOUT_MILLIS = 400L
    }
}
