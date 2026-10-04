package com.rm.apogee.platform

/**
 * The OS thread ids that do a frame's work, by job: the game server, the frame build and the GL
 * thread. Each sets its own when it starts, so a GL thread made again for a new surface takes the
 * old one's place. [AndroidPerfHints] puts them all in its session.
 */
object HintedThreads {
    private val tids = java.util.concurrent.ConcurrentHashMap<String, Int>()

    fun set(job: String, tid: Int) {
        tids[job] = tid
    }

    fun all(): IntArray = tids.values.toIntArray()
}
