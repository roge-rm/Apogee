package com.rm.apogee.core

actual fun resourceText(path: String): String? = EMBEDDED_RESOURCES[path]?.invoke()

actual fun <K, V> concurrentMapOf(): MutableMap<K, V> = SnapshotMap()

actual fun <T> concurrentSetOf(): MutableSet<T> = SnapshotSet()

/**
 * A map that can be changed while it's being gone through, as a ConcurrentHashMap can on the JVM.
 * There's only one thread here, but a loop over a map can still call something that adds to it or
 * takes from it, and a plain HashMap throws when that happens. Going through this one goes through
 * a copy taken at the start, and removing through the iterator removes from the map itself.
 */
private class SnapshotMap<K, V>(private val map: HashMap<K, V> = HashMap()) : AbstractMutableMap<K, V>() {
    override val size: Int get() = map.size
    override fun get(key: K): V? = map[key]
    override fun containsKey(key: K): Boolean = map.containsKey(key)
    override fun containsValue(value: V): Boolean = map.containsValue(value)
    override fun put(key: K, value: V): V? = map.put(key, value)
    override fun remove(key: K): V? = map.remove(key)
    override fun clear() = map.clear()

    override val entries: MutableSet<MutableMap.MutableEntry<K, V>> =
        object : AbstractMutableSet<MutableMap.MutableEntry<K, V>>() {
            override val size: Int get() = map.size
            override fun add(element: MutableMap.MutableEntry<K, V>): Boolean {
                val had = map.containsKey(element.key)
                map[element.key] = element.value
                return !had
            }
            override fun iterator(): MutableIterator<MutableMap.MutableEntry<K, V>> {
                val copy = map.entries.map { Entry(it.key, it.value) }
                return SnapshotIterator(copy) { map.remove(it.key) }
            }
        }

    private inner class Entry(override val key: K, private var current: V) : MutableMap.MutableEntry<K, V> {
        override val value: V get() = current
        override fun setValue(newValue: V): V {
            val old = current
            current = newValue
            map[key] = newValue
            return old
        }
        override fun equals(other: Any?): Boolean =
            other is Map.Entry<*, *> && other.key == key && other.value == current
        override fun hashCode(): Int = key.hashCode() xor current.hashCode()
        override fun toString(): String = "$key=$current"
    }
}

/** A set that can be changed while it's being gone through; see [SnapshotMap]. */
private class SnapshotSet<T>(private val set: HashSet<T> = HashSet()) : AbstractMutableSet<T>() {
    override val size: Int get() = set.size
    override fun contains(element: T): Boolean = set.contains(element)
    override fun add(element: T): Boolean = set.add(element)
    override fun remove(element: T): Boolean = set.remove(element)
    override fun clear() = set.clear()
    override fun iterator(): MutableIterator<T> = SnapshotIterator(set.toList()) { set.remove(it) }
}

/** Goes through [items], a copy, with [remove] taking the last one out of wherever it came from. */
private class SnapshotIterator<T>(items: List<T>, private val remove: (T) -> Unit) : MutableIterator<T> {
    private val items = items.iterator()
    private var last: T? = null
    private var hasLast = false
    override fun hasNext(): Boolean = items.hasNext()
    override fun next(): T = items.next().also { last = it; hasLast = true }
    override fun remove() {
        check(hasLast) { "next() first" }
        @Suppress("UNCHECKED_CAST")
        remove(last as T)
        hasLast = false
    }
}

actual class Counter actual constructor() {
    private var value = 0L
    actual fun incrementAndGet(): Long = ++value
    actual fun get(): Long = value
}

actual class PerThread<T> actual constructor(initial: () -> T) {
    private val value by lazy(initial)
    actual fun get(): T = value
}

actual inline fun <T> guarded(lock: Any, block: () -> T): T = block()

actual fun fixed(value: Double, decimals: Int): String = toFixed(value, decimals)

private fun toFixed(value: Double, decimals: Int): String = js("value.toFixed(decimals)")

actual class Background actual constructor(name: String) {
    actual fun execute(task: () -> Unit) {
        waiting.addLast(task)
    }
}

private val waiting = ArrayDeque<() -> Unit>()

private fun now(): Double = js("performance.now()")

actual fun runBackgroundWork(budgetMillis: Double) {
    val until = now() + budgetMillis
    while (waiting.isNotEmpty() && now() < until) waiting.removeFirst().invoke()
}

actual fun <K, V> lruMapOf(initialCapacity: Int, maxSize: Int): MutableMap<K, V> = LruMap(maxSize)

/** Least recently used first out: a hit moves an entry to the back, and the front goes first. */
private class LruMap<K, V>(private val maxSize: Int, private val map: LinkedHashMap<K, V> = LinkedHashMap()) :
    MutableMap<K, V> by map {
    override fun get(key: K): V? {
        if (!map.containsKey(key)) return null
        @Suppress("UNCHECKED_CAST")
        val value = map.remove(key) as V
        map[key] = value
        return value
    }

    override fun put(key: K, value: V): V? {
        val old = map.remove(key)
        map[key] = value
        while (map.size > maxSize) map.remove(map.keys.first())
        return old
    }
}

actual fun <K, V> MutableMap<K, V>.putIfAbsentShared(key: K, value: V): V? {
    get(key)?.let { return it }
    put(key, value)
    return null
}

actual class ConcurrentQueue<E> private constructor(private val queue: ArrayDeque<E>) : MutableCollection<E> by queue {
    actual constructor() : this(ArrayDeque())
    actual fun poll(): E? = queue.removeFirstOrNull()
    actual fun peek(): E? = queue.firstOrNull()
    override fun iterator(): MutableIterator<E> = SnapshotIterator(queue.toList()) { queue.remove(it) }
}

actual fun <T> copyOnWriteListOf(): MutableList<T> = SnapshotList()

/** A list gone through by a copy, as a CopyOnWriteArrayList is on the JVM. */
private class SnapshotList<T>(private val list: ArrayList<T> = ArrayList()) : MutableList<T> by list {
    override fun iterator(): MutableIterator<T> = SnapshotIterator(list.toList()) { list.remove(it) }
    override fun equals(other: Any?): Boolean = list == other
    override fun hashCode(): Int = list.hashCode()
    override fun toString(): String = list.toString()
}

actual fun nanoTime(): Long = (now() * 1_000_000.0).toLong()
