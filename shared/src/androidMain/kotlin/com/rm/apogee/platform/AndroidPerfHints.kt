package com.rm.apogee.platform

import android.content.Context
import android.os.Build
import android.os.PerformanceHintManager
import android.util.Log

/**
 * Every "make the scheduler treat us like a game" API, behind one front.
 *
 * minSdk is 27, so none of these can be taken for granted. Keeping the version checks here means
 * the simulation and render paths stay free of `SDK_INT` branches. They call
 * [reportActualWorkDuration] every time, and it does nothing on old devices.
 *
 * The one that matters is [PerformanceHintManager] (API 31+). Telling the kernel how long a frame
 * of simulation work is *supposed* to take keeps the physics thread on a big core instead of being
 * moved onto a little one mid-frame, which is the biggest single cause of frame-time jitter on
 * mobile.
 */
class AndroidPerfHints private constructor(
    private var session: PerformanceHintManager.Session?,
) : PerfHints {

    /**
     * Held for every call on [session]. The frame loop reports from a worker thread while leaving a
     * flight closes the session from the main one, and a report reaching the native session after
     * it's closed is a segfault, not an exception [runCatching] could catch. It crashed the game
     * when leaving a flight.
     */
    private val lock = Any()

    /**
     * Reports how long the last simulation step really took, so the scheduler can adjust. It's safe
     * to call every tick, and does nothing where it isn't supported.
     */
    override fun reportActualWorkDuration(nanos: Long) {
        if (nanos <= 0) return
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            synchronized(lock) { runCatching { session?.reportActualWorkDuration(nanos) } }
        }
    }

    /**
     * Call this when the simulation's per-tick budget changes (a display rate change, for example).
     */
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

        /**
         * @param threadIds the threads doing the per-frame work: the simulation thread and the GL
         *     thread. They have to be real OS tids, not Java thread ids.
         */
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
                // This is documented: the device might just not implement it.
                Log.i(TAG, "ADPF hint session unavailable on this device; running unhinted")
            }
            return AndroidPerfHints(session)
        }
    }
}
