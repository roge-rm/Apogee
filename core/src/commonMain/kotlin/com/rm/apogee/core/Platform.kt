package com.rm.apogee.core

/*
 * The few things the simulation needs that only a platform can give it: its resource files,
 * maps and counters that can be shared between threads, and a thread to build things on. On the
 * JVM (the app and the servers) they're the real thing; in a browser there's one thread, so they're
 * the plain single-threaded equivalents.
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

/** Runs [block] holding [lock], so no other thread holding it runs at the same time. */
expect inline fun <T> guarded(lock: Any, block: () -> T): T

/** [value] with [decimals] places after the point, the way `"%.Nf".format` writes it. */
expect fun fixed(value: Double, decimals: Int): String

/**
 * Somewhere to run jobs in the background, in the order they're given. On the JVM it's one thread
 * of its own, named [name]. In a browser they wait until [runBackgroundWork] is given the time.
 */
expect class Background(name: String) {
    fun execute(task: () -> Unit)
}

/**
 * Runs waiting [Background] jobs for up to [budgetMillis]. A browser calls it each frame; on the
 * JVM the jobs run on their own threads and this does nothing.
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

/**
 * A map that holds at most [maxSize] entries and throws out the least recently used first, for
 * caches.
 */
expect fun <K, V> lruMapOf(initialCapacity: Int, maxSize: Int): MutableMap<K, V>

/**
 * Puts [value] at [key] unless something's there already, and returns what was there, or null.
 * On a [concurrentMapOf] map it's atomic, so two threads racing get the same one.
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

/** A clock in nanoseconds for measuring time between two moments, from no set start. */
expect fun nanoTime(): Long
