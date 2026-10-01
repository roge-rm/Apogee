package com.rm.apogee

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.withFrameMillis
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.isSecondaryPressed
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import kotlin.time.TimeSource

/**
 * The world's touches and mouse, taken in Compose, for a host whose screens sit over the world and
 * take every pointer event (a web page). It's under the screens, so HUD controls get theirs first
 * and the rest go to [gestures]. A mouse wheel zooms.
 *
 * It also clears its patch of the canvas, since Compose on the web paints it white every frame.
 *
 * A right-button drag acts as two fingers moving together, which pans in the assembly building.
 */
@Composable
fun WorldInputLayer(gestures: WorldGestures) {
    val held = remember { Held() }
    // A long press has to fire while the pointer's still and no events come, so time is passed on
    // every frame while it's down, on the events' clock.
    LaunchedEffect(gestures) {
        while (true) {
            withFrameMillis { held.now()?.let(gestures::tick) }
        }
    }
    Box(
        Modifier
            .fillMaxSize()
            .drawBehind { drawRect(Color.Black, blendMode = BlendMode.Clear) }
            .onSizeChanged { gestures.size(it.width.toFloat(), it.height.toFloat()) }
            .pointerInput(gestures) {
                awaitPointerEventScope {
                    var down = 0
                    while (true) {
                        val event = awaitPointerEvent()
                        val time = event.changes.firstOrNull()?.uptimeMillis ?: 0L
                        when (event.type) {
                            PointerEventType.Scroll -> {
                                val dy = event.changes.firstOrNull()?.scrollDelta?.y ?: 0f
                                if (dy != 0f) gestures.zoom(if (dy < 0f) 1.1f else 1f / 1.1f)
                            }
                            PointerEventType.Press -> {
                                val pressed = event.changes.filter { it.pressed }
                                if (down == 0 && pressed.size == 1 && event.buttons.isSecondaryPressed) {
                                    val p = pressed[0].position
                                    gestures.down(p.x, p.y, time)
                                    gestures.secondDown(p.x, p.y, p.x + TWIN, p.y)
                                    held.twin = true
                                } else if (down == 0 && pressed.size == 1) {
                                    val p = pressed[0].position
                                    gestures.down(p.x, p.y, time)
                                } else if (pressed.size >= 2) {
                                    val (a, b) = pressed[0].position to pressed[1].position
                                    gestures.secondDown(a.x, a.y, b.x, b.y)
                                }
                                down = pressed.size
                                held.pressed(time)
                            }
                            PointerEventType.Move -> {
                                val pressed = event.changes.filter { it.pressed }
                                if (held.twin && pressed.size == 1) {
                                    val p = pressed[0].position
                                    gestures.move(p.x, p.y, p.x + TWIN, p.y, measurePinch = true)
                                } else if (pressed.size >= 2) {
                                    val (a, b) = pressed[0].position to pressed[1].position
                                    gestures.move(a.x, a.y, b.x, b.y, measurePinch = true)
                                } else if (pressed.size == 1) {
                                    val p = pressed[0].position
                                    gestures.move(p.x, p.y)
                                }
                                gestures.tick(time)
                            }
                            PointerEventType.Release -> {
                                val pressed = event.changes.filter { it.pressed }
                                if (pressed.isEmpty()) {
                                    val p = event.changes.first().position
                                    if (held.twin) gestures.secondUp()
                                    gestures.up(p.x, p.y, time)
                                    held.released()
                                } else if (down >= 2 && pressed.size == 1) {
                                    gestures.secondUp()
                                }
                                down = pressed.size
                            }
                            else -> {}
                        }
                        event.changes.forEach { it.consume() }
                    }
                }
            },
    )
}

/** How far apart the two make-believe fingers of a right-button drag are, in pixels. */
private const val TWIN = 60f

/** Whether a pointer's down, and the time since then on the pointer events' clock. */
private class Held {
    private var at = 0L
    private var since: TimeSource.Monotonic.ValueTimeMark? = null
    var twin = false

    fun pressed(time: Long) {
        if (since == null) {
            at = time
            since = TimeSource.Monotonic.markNow()
        }
    }

    fun released() {
        since = null
        twin = false
    }

    fun now(): Long? = since?.let { at + it.elapsedNow().inWholeMilliseconds }
}
