package com.rm.apogee.render

/**
 * A map from Long keys to values, open addressing with linear probing, so keys aren't boxed and
 * nothing is allocated per entry. [clear] keeps its arrays, so one map can be refilled every frame
 * without allocating. Not thread safe.
 */
class LongMap<V : Any>(expected: Int = 16) {
    private var keys = LongArray(capacityFor(expected))
    private var values = arrayOfNulls<Any>(keys.size)
    private var mask = keys.size - 1
    // Key 0 marks an empty slot, so a value under key 0 is kept on its own.
    private var zeroValue: Any? = null

    var size = 0
        private set

    @Suppress("UNCHECKED_CAST")
    operator fun get(key: Long): V? {
        if (key == 0L) return zeroValue as V?
        var i = slot(key)
        while (true) {
            val k = keys[i]
            if (k == key) return values[i] as V?
            if (k == 0L) return null
            i = (i + 1) and mask
        }
    }

    operator fun contains(key: Long): Boolean = get(key) != null

    /** Puts [value] under [key], giving back what was there before, or null. */
    @Suppress("UNCHECKED_CAST")
    fun put(key: Long, value: V): V? {
        if (key == 0L) {
            val before = zeroValue as V?
            zeroValue = value
            if (before == null) size++
            return before
        }
        var i = slot(key)
        while (true) {
            val k = keys[i]
            if (k == key) {
                val before = values[i] as V?
                values[i] = value
                return before
            }
            if (k == 0L) break
            i = (i + 1) and mask
        }
        keys[i] = key
        values[i] = value
        size++
        // At most half full, so probes stay short.
        if (size * 2 > keys.size) grow()
        return null
    }

    operator fun set(key: Long, value: V) { put(key, value) }

    /** Takes [key] out, giving back its value, or null if it wasn't there. */
    @Suppress("UNCHECKED_CAST")
    fun remove(key: Long): V? {
        if (key == 0L) {
            val before = zeroValue as V?
            if (before != null) size--
            zeroValue = null
            return before
        }
        var i = slot(key)
        while (true) {
            val k = keys[i]
            if (k == 0L) return null
            if (k == key) break
            i = (i + 1) and mask
        }
        val before = values[i] as V?
        size--
        // Shift later keys of the same run back, so lookups never stop early at the gap.
        var gap = i
        var j = i
        while (true) {
            j = (j + 1) and mask
            val k = keys[j]
            if (k == 0L) break
            val home = slot(k)
            // Move it into the gap unless its home lies cyclically in (gap, j].
            val stays = if (gap <= j) home in (gap + 1)..j else home > gap || home <= j
            if (!stays) {
                keys[gap] = k
                values[gap] = values[j]
                gap = j
            }
        }
        keys[gap] = 0L
        values[gap] = null
        return before
    }

    /** Empties it, keeping its room. */
    fun clear() {
        if (size == 0) return
        keys.fill(0L)
        values.fill(null)
        zeroValue = null
        size = 0
    }

    private fun slot(key: Long): Int {
        // Spread the bits, since keys are often packed fields.
        var h = key * -0x61c8864680b583ebL
        h = h xor (h ushr 32)
        return h.toInt() and mask
    }

    private fun grow() {
        val oldKeys = keys
        val oldValues = values
        keys = LongArray(oldKeys.size * 2)
        values = arrayOfNulls(keys.size)
        mask = keys.size - 1
        for (i in oldKeys.indices) {
            val k = oldKeys[i]
            if (k == 0L) continue
            var j = slot(k)
            while (keys[j] != 0L) j = (j + 1) and mask
            keys[j] = k
            values[j] = oldValues[i]
        }
    }

    private companion object {
        fun capacityFor(expected: Int): Int {
            var capacity = 8
            while (capacity < expected * 2) capacity *= 2
            return capacity
        }
    }
}
