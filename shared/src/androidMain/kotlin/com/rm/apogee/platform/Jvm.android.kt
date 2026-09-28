package com.rm.apogee.platform

import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap

actual object System {
    actual fun nanoTime(): Long = java.lang.System.nanoTime()
    actual fun currentTimeMillis(): Long = java.lang.System.currentTimeMillis()
}

actual inline fun <R> synchronized(lock: Any, block: () -> R): R = kotlin.synchronized(lock, block)

actual fun String.format(vararg args: Any?): String = java.lang.String.format(this, *args)

actual class AtomicLong actual constructor(initial: Long) {
    private val value = java.util.concurrent.atomic.AtomicLong(initial)
    actual fun get(): Long = value.get()
    actual fun set(value: Long) = this.value.set(value)
    actual fun incrementAndGet(): Long = value.incrementAndGet()
    actual fun addAndGet(delta: Long): Long = value.addAndGet(delta)
}

actual class AtomicInteger actual constructor(initial: Int) {
    private val value = java.util.concurrent.atomic.AtomicInteger(initial)
    actual fun get(): Int = value.get()
    actual fun set(value: Int) = this.value.set(value)
    actual fun incrementAndGet(): Int = value.incrementAndGet()
    actual fun getAndSet(value: Int): Int = this.value.getAndSet(value)
}

actual class AtomicReference<T> actual constructor(initial: T) {
    private val value = java.util.concurrent.atomic.AtomicReference(initial)
    actual fun get(): T = value.get()
    actual fun set(value: T) = this.value.set(value)
    actual fun getAndSet(value: T): T = this.value.getAndSet(value)
    actual fun compareAndSet(expected: T, value: T): Boolean = this.value.compareAndSet(expected, value)
}

actual class SplittableRandom actual constructor(seed: Long) {
    private val random = java.util.SplittableRandom(seed)
    actual fun nextDouble(): Double = random.nextDouble()
}

actual fun <K, V> identityMapOf(): MutableMap<K, V> = java.util.IdentityHashMap()

actual object Log {
    actual fun i(tag: String, message: String): Int = android.util.Log.i(tag, message)
    actual fun w(tag: String, message: String): Int = android.util.Log.w(tag, message)
    actual fun e(tag: String, message: String): Int = android.util.Log.e(tag, message)
}

private val mainHandler by lazy { android.os.Handler(android.os.Looper.getMainLooper()) }

actual fun runOnMain(block: () -> Unit) {
    mainHandler.post(block)
}

actual fun imageBitmapOf(argb: IntArray, width: Int, height: Int): ImageBitmap =
    android.graphics.Bitmap.createBitmap(argb, width, height, android.graphics.Bitmap.Config.ARGB_8888).asImageBitmap()
