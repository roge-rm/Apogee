package com.rm.apogee.platform

import kotlin.concurrent.Volatile

/**
 * Debug: bytes allocated, for the perf build and tests. Off (null) everywhere else, and then every
 * count that reads it is skipped.
 */
object AllocProbe {
    /** The calling thread's running total of bytes allocated, never going down. */
    @Volatile var threadBytes: (() -> Long)? = null

    /** The whole process's running total of bytes allocated, where the platform has one. */
    @Volatile var processBytes: (() -> Long)? = null

    /** The calling thread's id, so a pool's threads are kept apart under one name. */
    @Volatile var threadId: (() -> Long)? = null

    private val names = ArrayList<String>()
    private val totals = ArrayList<LongArray>()

    /** Keeps the calling thread's running total under [name], for [totals]. */
    fun note(name: String) {
        val probe = threadBytes ?: return
        val bytes = probe()
        val id = threadId?.invoke()
        val key = if (id != null) "$name#$id" else name
        synchronized(names) {
            var i = names.indexOf(key)
            if (i < 0) { names += key; totals += LongArray(1); i = names.size - 1 }
            totals[i][0] = bytes
        }
    }

    /** Each noted thread's latest running total, as "name" or "name#thread id". */
    fun totals(): Map<String, Long> = synchronized(names) { names.indices.associate { names[it] to totals[it][0] } }
}
