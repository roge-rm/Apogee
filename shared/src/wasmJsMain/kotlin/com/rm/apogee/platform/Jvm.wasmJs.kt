@file:OptIn(ExperimentalWasmJsInterop::class)

package com.rm.apogee.platform

import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asComposeImageBitmap
import kotlin.js.ExperimentalWasmJsInterop

private fun performanceNow(): Double = js("performance.now()")
private fun dateNow(): Double = js("Date.now()")

actual object System {
    actual fun nanoTime(): Long = (performanceNow() * 1_000_000.0).toLong()
    actual fun currentTimeMillis(): Long = dateNow().toLong()
}

actual inline fun <R> synchronized(lock: Any, block: () -> R): R = block()

actual fun String.format(vararg args: Any?): String = Printf.format(this, args)

actual class AtomicLong actual constructor(initial: Long) {
    private var value = initial
    actual fun get(): Long = value
    actual fun set(value: Long) { this.value = value }
    actual fun incrementAndGet(): Long = ++value
    actual fun addAndGet(delta: Long): Long { value += delta; return value }
}

actual class AtomicInteger actual constructor(initial: Int) {
    private var value = initial
    actual fun get(): Int = value
    actual fun set(value: Int) { this.value = value }
    actual fun incrementAndGet(): Int = ++value
    actual fun getAndSet(value: Int): Int { val old = this.value; this.value = value; return old }
}

actual class AtomicReference<T> actual constructor(initial: T) {
    private var value = initial
    actual fun get(): T = value
    actual fun set(value: T) { this.value = value }
    actual fun getAndSet(value: T): T { val old = this.value; this.value = value; return old }
    actual fun compareAndSet(expected: T, value: T): Boolean {
        if (this.value !== expected) return false
        this.value = value
        return true
    }
}

actual class SplittableRandom actual constructor(seed: Long) {
    private val random = kotlin.random.Random(seed)
    actual fun nextDouble(): Double = random.nextDouble()
}

/** Looked up by ===, in order. The maps it's used for are small caches that get cleared. */
private class IdentityMap<K, V> : AbstractMutableMap<K, V>() {
    private val ks = ArrayList<K>()
    private val vs = ArrayList<V>()

    override fun put(key: K, value: V): V? {
        val i = ks.indexOfFirst { it === key }
        if (i >= 0) { val old = vs[i]; vs[i] = value; return old }
        ks.add(key); vs.add(value); return null
    }

    override fun get(key: K): V? {
        val i = ks.indexOfFirst { it === key }
        return if (i >= 0) vs[i] else null
    }

    override fun remove(key: K): V? {
        val i = ks.indexOfFirst { it === key }
        if (i < 0) return null
        ks.removeAt(i)
        return vs.removeAt(i)
    }

    override fun containsKey(key: K): Boolean = ks.any { it === key }

    override fun clear() { ks.clear(); vs.clear() }

    override val entries: MutableSet<MutableMap.MutableEntry<K, V>>
        get() = ks.indices.mapTo(LinkedHashSet()) { i ->
            object : MutableMap.MutableEntry<K, V> {
                override val key: K = ks[i]
                override val value: V get() = vs[i]
                override fun setValue(newValue: V): V { val old = vs[i]; vs[i] = newValue; return old }
            }
        }
}

actual fun <K, V> identityMapOf(): MutableMap<K, V> = IdentityMap()

private fun consoleLog(level: String, text: String): Unit = js("console[level](text)")

actual object Log {
    actual fun i(tag: String, message: String): Int { consoleLog("info", "$tag: $message"); return 0 }
    actual fun w(tag: String, message: String): Int { consoleLog("warn", "$tag: $message"); return 0 }
    actual fun e(tag: String, message: String): Int { consoleLog("error", "$tag: $message"); return 0 }
}

actual fun runOnMain(block: () -> Unit) = block()

actual fun imageBitmapOf(argb: IntArray, width: Int, height: Int): ImageBitmap {
    val bytes = ByteArray(argb.size * 4)
    for (i in argb.indices) {
        val c = argb[i]
        // Skia's N32 on the web is RGBA, unpremultiplied here.
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
