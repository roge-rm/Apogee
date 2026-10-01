package com.rm.apogee.platform

import androidx.compose.ui.graphics.ImageBitmap

/*
 * The few JVM things the shared code uses, under their JVM names. On Android each is the JVM's
 * own; in a browser (one thread, no java.*) it's a single-threaded stand-in. Files import them from
 * here, which on Android takes precedence over java.lang.
 */

/** The clocks and array copying of `java.lang.System`. */
expect object System {
    fun nanoTime(): Long
    fun currentTimeMillis(): Long
}

/** Runs [block] holding [lock]. */
expect inline fun <R> synchronized(lock: Any, block: () -> R): R

/** `String.format`, for the formats the app uses: %d, %s, %x, %f, with flags, width and precision. */
expect fun String.format(vararg args: Any?): String

expect class AtomicLong(initial: Long = 0L) {
    fun get(): Long
    fun set(value: Long)
    fun incrementAndGet(): Long
    fun addAndGet(delta: Long): Long
}

expect class AtomicInteger(initial: Int = 0) {
    fun get(): Int
    fun set(value: Int)
    fun incrementAndGet(): Int
    fun getAndSet(value: Int): Int
}

expect class AtomicReference<T>(initial: T) {
    fun get(): T
    fun set(value: T)
    fun getAndSet(value: T): T
    fun compareAndSet(expected: T, value: T): Boolean
}

/** Random numbers split off a seed, as `java.util.SplittableRandom` makes them. */
expect class SplittableRandom(seed: Long) {
    fun nextDouble(): Double
}

/** A map keyed by the objects themselves (===), not by what they equal. */
expect fun <K, V> identityMapOf(): MutableMap<K, V>

/** The log: Android's, or the browser's console. */
expect object Log {
    fun i(tag: String, message: String): Int
    fun w(tag: String, message: String): Int
    fun e(tag: String, message: String): Int
}

/** Runs [block] on the thread Compose's state is written on. */
expect fun runOnMain(block: () -> Unit)

/** [argb] (0xAARRGGBB, [width] by [height], top row first) as a picture for Compose. */
expect fun imageBitmapOf(argb: IntArray, width: Int, height: Int): ImageBitmap
