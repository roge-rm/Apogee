package com.rm.apogee.platform

import android.content.Context
import android.os.Build
import android.os.PerformanceHintManager
import android.util.Log

/**
 * Every "make the scheduler treat us like a game" API, behind one facade.
 *
 * minSdk is 27, so none of these can be assumed. Isolating the version checks
 * here means the simulation and render paths stay free of `SDK_INT` branches -
 * they call [reportActualWorkDuration] unconditionally and it is a no-op on old
 * devices.
 *
 * The one that matters is [PerformanceHintManager] (API 31+): telling the
 * kernel how long a frame of simulation work is *supposed* to take keeps the
 * physics thread on a big core instead of being migrated onto a little one
 * mid-frame, which is the single largest source of frame-time jitter on mobile.
 */
class PerfHints private constructor(
    private var session: PerformanceHintManager.Session?,
) {

    /**
     * Held for every call on [session]. The frame loop reports from a worker
     * thread while leaving a flight closes the session from the main one, and
     * a report reaching the native session after it is closed is a segfault -
     * not an exception [runCatching] could catch. It took the game down on
     * leaving a flight.
     */
    private val lock = Any()

    /**
     * Reports how long the last simulation step actually took, so the scheduler
     * can adjust. Safe to call every tick; a no-op where unsupported.
     */
    fun reportActualWorkDuration(nanos: Long) {
        if (nanos <= 0) return
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            synchronized(lock) { runCatching { session?.reportActualWorkDuration(nanos) } }
        }
    }

    /** Call when the simulation's per-tick budget changes (e.g. display rate change). */
    fun updateTargetWorkDuration(nanos: Long) {
        if (nanos <= 0) return
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            synchronized(lock) { runCatching { session?.updateTargetWorkDuration(nanos) } }
        }
    }

    /** Closes the session; anything reported after is ignored. */
    fun close() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            synchronized(lock) {
                runCatching { session?.close() }
                session = null
            }
        }
    }

    companion object {
        private const val TAG = "ApogeePerfHints"

        /**
         * @param threadIds the threads doing the per-frame work - the simulation
         *   thread and the GL thread. Must be real OS tids, not Java thread ids.
         */
        fun create(context: Context, threadIds: IntArray, targetWorkNanos: Long): PerfHints {
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) {
                Log.i(TAG, "ADPF unavailable on API ${Build.VERSION.SDK_INT}; running unhinted")
                return PerfHints(null)
            }
            val session = runCatching {
                val manager = context.getSystemService(PerformanceHintManager::class.java)
                manager?.createHintSession(threadIds, targetWorkNanos)
            }.getOrNull()

            if (session == null) {
                // Documented behaviour: the device may simply not implement it.
                Log.i(TAG, "ADPF hint session unavailable on this device; running unhinted")
            }
            return PerfHints(session)
        }
    }
}
