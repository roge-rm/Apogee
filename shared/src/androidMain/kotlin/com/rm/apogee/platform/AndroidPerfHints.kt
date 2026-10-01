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
 * keeps the physics thread on a big core, which cuts frame-time jitter.
 */
class AndroidPerfHints private constructor(
    private var session: PerformanceHintManager.Session?,
) : PerfHints {

    /**
     * Held for every call on [session]. Reports come from a worker thread and [close] from the main
     * one, and a report on a closed native session segfaults, which [runCatching] can't catch.
     */
    private val lock = Any()

    /** Reports how long the last simulation step took. Safe to call every tick. */
    override fun reportActualWorkDuration(nanos: Long) {
        if (nanos <= 0) return
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            synchronized(lock) { runCatching { session?.reportActualWorkDuration(nanos) } }
        }
    }

    /** Call when the per-tick budget changes, such as a new display rate. */
    override fun updateTargetWorkDuration(nanos: Long) {
        if (nanos <= 0) return
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            synchronized(lock) { runCatching { session?.updateTargetWorkDuration(nanos) } }
        }
    }

    /** Closes the session. Anything reported after that is ignored. */
    override fun close() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            synchronized(lock) {
                runCatching { session?.close() }
                session = null
            }
        }
    }

    companion object {
        private const val TAG = "ApogeePerfHints"

        /** @param threadIds the simulation and GL threads, as OS tids (not Java thread ids). */
        fun create(context: Context, threadIds: IntArray, targetWorkNanos: Long): AndroidPerfHints {
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) {
                Log.i(TAG, "ADPF unavailable on API ${Build.VERSION.SDK_INT}; running unhinted")
                return AndroidPerfHints(null)
            }
            val session = runCatching {
                val manager = context.getSystemService(PerformanceHintManager::class.java)
                manager?.createHintSession(threadIds, targetWorkNanos)
            }.getOrNull()

            if (session == null) {
                // Allowed: a device needn't implement it.
                Log.i(TAG, "ADPF hint session unavailable on this device; running unhinted")
            }
            return AndroidPerfHints(session)
        }
    }
}
