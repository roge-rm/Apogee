package com.rm.apogee.platform

import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asComposeImageBitmap

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

/** To standard error, as a line a terminal or a log file can read, with the last few kept for a crash report. */
actual object Log {
    actual fun i(tag: String, message: String): Int = line("I $tag: $message")
    actual fun w(tag: String, message: String): Int = line("W $tag: $message")
    actual fun e(tag: String, message: String): Int = line("E $tag: $message")

    private val lines = ArrayDeque<String>()

    private fun line(text: String): Int {
        java.lang.System.err.println(text)
        kotlin.synchronized(lines) {
            lines.addLast(text)
            if (lines.size > KEPT) lines.removeFirst()
        }
        return 0
    }

    /** The last lines logged, oldest first. */
    fun recent(): String = kotlin.synchronized(lines) { lines.joinToString("\n") }

    private const val KEPT = 200
}

/** The window's own thread, Swing's. */
actual fun runOnMain(block: () -> Unit) = javax.swing.SwingUtilities.invokeLater(block)

/** [argb] as packed ints, into a Skia bitmap, RGBA and unpremultiplied. */
actual fun imageBitmapOf(argb: IntArray, width: Int, height: Int): ImageBitmap {
    val bytes = ByteArray(argb.size * 4)
    for (i in argb.indices) {
        val c = argb[i]
        bytes[i * 4] = (c shr 16).toByte()
        bytes[i * 4 + 1] = (c shr 8).toByte()
        bytes[i * 4 + 2] = c.toByte()
        bytes[i * 4 + 3] = (c ushr 24).toByte()
    }
    val info = org.jetbrains.skia.ImageInfo(width, height, org.jetbrains.skia.ColorType.RGBA_8888, org.jetbrains.skia.ColorAlphaType.UNPREMUL)
    val bitmap = org.jetbrains.skia.Bitmap()
    bitmap.allocPixels(info)
    bitmap.installPixels(bytes)
    return bitmap.asComposeImageBitmap()
}
