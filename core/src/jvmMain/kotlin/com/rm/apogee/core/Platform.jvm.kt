package com.rm.apogee.core

actual fun resourceText(path: String): String? =
    Counter::class.java.getResourceAsStream(path)?.bufferedReader()?.use { it.readText() }

actual fun <K, V> concurrentMapOf(): MutableMap<K, V> = java.util.concurrent.ConcurrentHashMap()

actual fun <T> concurrentSetOf(): MutableSet<T> = java.util.concurrent.ConcurrentHashMap.newKeySet()

actual class Counter actual constructor() {
    private val value = java.util.concurrent.atomic.AtomicLong()
    actual fun incrementAndGet(): Long = value.incrementAndGet()
    actual fun get(): Long = value.get()
}

actual class PerThread<T> actual constructor(initial: () -> T) {
    private val local = ThreadLocal.withInitial(initial)
    actual fun get(): T = local.get()
}

actual inline fun <T> guarded(lock: Any, block: () -> T): T = synchronized(lock, block)

actual fun fixed(value: Double, decimals: Int): String = "%.${decimals}f".format(value)

actual class Background actual constructor(name: String) {
    private val executor by lazy {
        java.util.concurrent.Executors.newSingleThreadExecutor { runnable ->
            Thread(runnable, name).apply {
                isDaemon = true
                priority = Thread.NORM_PRIORITY - 1
            }
        }
    }

    actual fun execute(task: () -> Unit) = executor.execute(task)
}

actual fun runBackgroundWork(budgetMillis: Double) {}

actual fun <K, V> lruMapOf(initialCapacity: Int, maxSize: Int): MutableMap<K, V> =
    object : LinkedHashMap<K, V>(initialCapacity, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<K, V>?) = size > maxSize
    }

actual fun <K, V> MutableMap<K, V>.putIfAbsentShared(key: K, value: V): V? = putIfAbsent(key, value)

actual typealias ConcurrentQueue<E> = java.util.concurrent.ConcurrentLinkedQueue<E>

actual fun <T> copyOnWriteListOf(): MutableList<T> = java.util.concurrent.CopyOnWriteArrayList()

actual fun nanoTime(): Long = System.nanoTime()
