package com.rm.apogee.platform

import android.content.Context
import android.os.Build
import android.os.PerformanceHintManager
import android.util.Log

/**
 * The Android performance hint APIs behind one front, with the version checks kept here so callers
 * can call every frame. On old devices it does nothing.
 *
 * The one that matters is [PerformanceHintManager] (API 31+). Telling the kernel a frame's target
 * keeps the game server, frame build and GL threads on big cores, which cuts frame-time jitter.
 */
class AndroidPerfHints private constructor(
    private val manager: PerformanceHintManager?,
    private var targetNanos: Long,
) : PerfHints {

    /** The session, over the threads it was made with. Remade when another one starts. */
    private var session: PerformanceHintManager.Session? = null
    private var threads = IntArray(0)

    /**
     * Held for every call on [session]. Reports come from the frame thread and [close] from the main
     * one, and a report on a closed native session segfaults, which [runCatching] can't catch.
     */
    private val lock = Any()
    private var closed = false

    /**
     * Reports how long the last frame's work took. Safe to call every frame. The first report, and
     * the first after a hinted thread starts or is replaced, makes the session over all of them.
     */
    override fun reportActualWorkDuration(nanos: Long) {
        if (nanos <= 0) return
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) return
        synchronized(lock) {
            if (closed || manager == null) return
            val now = HintedThreads.all()
            if (!now.contentEquals(threads)) {
                runCatching { session?.close() }
                session = runCatching { manager?.createHintSession(now, targetNanos) }.getOrNull()
                threads = now
                Log.i(TAG, if (session != null) "Hinting ${now.size} threads" else "ADPF hint session unavailable on this device; running unhinted")
            }
            runCatching { session?.reportActualWorkDuration(nanos) }
        }
    }

    /** Call when the per-frame budget changes, such as a new display rate. */
    override fun updateTargetWorkDuration(nanos: Long) {
        if (nanos <= 0) return
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            synchronized(lock) {
                targetNanos = nanos
                runCatching { session?.updateTargetWorkDuration(nanos) }
            }
        }
    }

    /** Closes the session. Anything reported after that is ignored. */
    override fun close() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            synchronized(lock) {
                runCatching { session?.close() }
                session = null
                closed = true
            }
        }
    }

    companion object {
        private const val TAG = "ApogeePerfHints"

        /** A front for hints over [HintedThreads], aiming each frame's work at [targetWorkNanos]. */
        fun create(context: Context, targetWorkNanos: Long): AndroidPerfHints {
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) {
                Log.i(TAG, "ADPF unavailable on API ${Build.VERSION.SDK_INT}; running unhinted")
                return AndroidPerfHints(null, targetWorkNanos)
            }
            val manager = runCatching { context.getSystemService(PerformanceHintManager::class.java) }.getOrNull()
            return AndroidPerfHints(manager, targetWorkNanos)
        }
    }
}
