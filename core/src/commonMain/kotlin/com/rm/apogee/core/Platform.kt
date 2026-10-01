package com.rm.apogee.core

/*
 * What the simulation needs from a platform: resource files, thread-safe maps and counters, and a
 * background thread. Real on the JVM; single-threaded equivalents in a browser.
 */

/** A resource file's text, by its path from the resources root ("/parts/stock.json"), or null. */
expect fun resourceText(path: String): String?

/** A map that's safe to use from several threads at once. */
expect fun <K, V> concurrentMapOf(): MutableMap<K, V>

/** A set that's safe to use from several threads at once. */
expect fun <T> concurrentSetOf(): MutableSet<T>

/** A counter that's safe to bump from several threads at once. */
expect class Counter() {
    fun incrementAndGet(): Long
    fun get(): Long
}

/** A value of each thread's own, made by [initial] the first time a thread asks. */
expect class PerThread<T>(initial: () -> T) {
    fun get(): T
}

/** Runs [block] holding [lock]. */
expect inline fun <T> guarded(lock: Any, block: () -> T): T

/** [value] with [decimals] places after the point, the way `"%.Nf".format` writes it. */
expect fun fixed(value: Double, decimals: Int): String

/**
 * Runs jobs in the background in order. On the JVM, one thread named [name]. In a browser they wait
 * for [runBackgroundWork].
 */
expect class Background(name: String) {
    fun execute(task: () -> Unit)
}

/**
 * Runs waiting [Background] jobs for up to [budgetMillis]. A browser calls it each frame; on the
 * JVM it does nothing.
 */
expect fun runBackgroundWork(budgetMillis: Double)

/** [bytes] as lowercase hexadecimal, two digits each. */
fun hex(bytes: ByteArray): String = buildString(bytes.size * 2) {
    for (b in bytes) {
        val v = b.toInt() and 0xFF
        append(HEX[v ushr 4]).append(HEX[v and 0x0F])
    }
}

private const val HEX = "0123456789abcdef"

/** A map of at most [maxSize] entries that drops the least recently used first, for caches. */
expect fun <K, V> lruMapOf(initialCapacity: Int, maxSize: Int): MutableMap<K, V>

/**
 * Puts [value] at [key] unless something's there, and returns what was there or null. Atomic on a
 * [concurrentMapOf] map.
 */
expect fun <K, V> MutableMap<K, V>.putIfAbsentShared(key: K, value: V): V?

/** A first in, first out queue that's safe to use from several threads at once. */
expect class ConcurrentQueue<E>() : MutableCollection<E> {
    /** The oldest, taken out, or null when it's empty. */
    fun poll(): E?

    /** The oldest, left where it is, or null when it's empty. */
    fun peek(): E?
}

/** A list for reading far more than changing, safe to walk while another thread changes it. */
expect fun <T> copyOnWriteListOf(): MutableList<T>

/** A monotonic nanosecond clock for measuring intervals. */
expect fun nanoTime(): Long
