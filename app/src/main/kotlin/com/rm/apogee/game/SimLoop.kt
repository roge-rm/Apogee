package com.rm.apogee.game

import com.rm.apogee.core.math.Quat
import com.rm.apogee.core.math.Vec3
import com.rm.apogee.platform.PerfHints
import com.rm.apogee.render.FrameBus
import com.rm.apogee.render.MeshId
import com.rm.apogee.render.RenderFrame
import com.rm.apogee.render.RenderItem
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.util.concurrent.atomic.AtomicLong

/**
 * The simulation clock: a fixed-timestep accumulator that advances the world
 * and publishes an immutable frame for the renderer.
 *
 * Fixed timestep is not a stylistic preference. A variable `dt` makes physics
 * results depend on frame rate, which means a phone that drops to 40 fps flies
 * a different trajectory from one at 60 - unacceptable on its own, and fatal
 * once a server is reconciling a client's predicted state against its own.
 *
 * M0 scaffolding: the "world" here is a single rotating cube, enough to prove
 * the clock, the frame hand-off and the render path end to end. M1 replaces
 * [step] with `World.step(DT)` from :core and changes nothing else.
 */
class SimLoop(
    private val frameBus: FrameBus,
    private val perfHints: PerfHints?,
) {
    /** Published for the debug overlay. Nanos spent inside the last [step]. */
    val lastStepNanos = AtomicLong(0)
    val tick = AtomicLong(0)

    // --- M0 placeholder world ------------------------------------------------
    private val cubeRotation = Quat.identity()
    private val cubeAngularVelocity = Vec3(0.35, 0.8, 0.15)
    private val cubePosition = Vec3(0.0, 0.0, 0.0)
    private val cameraPosition = Vec3(0.0, 0.0, 4.0)
    private val cameraRotation = Quat.identity()

    fun start(scope: CoroutineScope): Job = scope.launch(Dispatchers.Default) {
        var simClockNanos = System.nanoTime()
        var lastWallNanos = simClockNanos
        var accumulator = 0.0

        perfHints?.updateTargetWorkDuration(DT_NANOS)

        while (isActive) {
            val now = System.nanoTime()
            var elapsed = (now - lastWallNanos) / 1e9
            lastWallNanos = now

            // Clamp. Without this, coming back from a long pause (the app was
            // backgrounded, the device slept) hands the integrator a huge
            // elapsed time, which it then tries to catch up on in one burst -
            // each step taking longer than real time, so the backlog grows
            // instead of shrinking. The classic spiral of death.
            if (elapsed > MAX_CATCHUP_SECONDS) elapsed = MAX_CATCHUP_SECONDS

            accumulator += elapsed

            while (accumulator >= DT && isActive) {
                val stepStart = System.nanoTime()
                step(DT)
                lastStepNanos.set(System.nanoTime() - stepStart)
                perfHints?.reportActualWorkDuration(lastStepNanos.get())

                accumulator -= DT
                simClockNanos += DT_NANOS
                tick.incrementAndGet()
                publish(simClockNanos)
            }

            // Sleep until the next step is due rather than spinning.
            val untilNextNanos = ((DT - accumulator) * 1e9).toLong()
            val sleepMillis = untilNextNanos / 1_000_000
            if (sleepMillis > 0) delay(sleepMillis)
        }
    }

    private fun step(dt: Double) {
        cubeRotation.integrateAngularVelocity(cubeAngularVelocity, dt)
    }

    private fun publish(timestampNanos: Long) {
        frameBus.publish(
            RenderFrame(
                simTick = tick.get(),
                timestampNanos = timestampNanos,
                cameraPosition = cameraPosition.copy(),
                cameraRotation = cameraRotation.copy(),
                fovYRadians = Math.toRadians(55.0),
                items = listOf(
                    RenderItem(
                        meshId = MeshId.CUBE,
                        position = cubePosition.copy(),
                        rotation = cubeRotation.copy(),
                        scale = 1.0,
                        color = floatArrayOf(0.70f, 0.62f, 1.0f, 1f),
                    ),
                ),
            )
        )
    }

    companion object {
        /** The simulation's fixed timestep. 60 Hz. */
        const val DT = 1.0 / 60.0
        const val DT_NANOS = (DT * 1e9).toLong()

        /**
         * Most real time one iteration is allowed to try to catch up on.
         * Anything beyond this is simply dropped - the simulation falls behind
         * wall clock rather than locking the device up trying to catch up.
         */
        const val MAX_CATCHUP_SECONDS = 0.25
    }
}
