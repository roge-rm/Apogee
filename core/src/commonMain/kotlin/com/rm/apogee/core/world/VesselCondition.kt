package com.rm.apogee.core.world

import com.rm.apogee.core.craft.Vessel

/**
 * The state of a craft's parts (how hurt, how hot, how dented, and how hard their joints are
 * working) packed for sending, so everyone sees the scorch and the glow, the crumpled nose and the
 * sparks off a straining seam, not only the pilot.
 *
 * It's seven bytes a part: health, temperature, the dent's three axes, the load on its joint, and
 * how full of water it is. It's empty for a craft that's whole, cool and unstrained, which is
 * nearly every craft nearly all the time, so a snapshot of a fleet parked on the pad costs nothing.
 */
object VesselCondition {

    const val BYTES_PER_PART = 7

    /** Joint load, as a share of strength, below which nothing shows and nothing gets sent. */
    const val LOAD_VISIBLE = 0.6

    /** The most load a byte can say. Past [Stress.SNAP] a joint has gone. */
    const val MOST_LOAD = Stress.SNAP

    /** Below this nothing glows, and a craft this cool and whole sends nothing, in K. */
    const val WARM = 400.0

    /** The coolest and hottest a byte can say, in K. */
    const val COOLEST = 250.0
    const val HOTTEST = 4_000.0

    fun encode(vessel: Vessel): ByteArray {
        val n = vessel.design.parts.size
        val health = vessel.health
        val temperature = vessel.temperature
        val crumple = vessel.crumple
        val load = vessel.jointLoad
        val flooded = vessel.flooded
        var anything = false
        for (i in 0 until n) {
            if (health[i] < 0.999 || temperature[i] > WARM || load[i] > LOAD_VISIBLE || flooded.getOrElse(i) { 0.0 } > 0.0 ||
                crumple[i * 3] != 0f || crumple[i * 3 + 1] != 0f || crumple[i * 3 + 2] != 0f
            ) { anything = true; break }
        }
        if (!anything) return ByteArray(0)
        val out = ByteArray(n * BYTES_PER_PART)
        for (i in 0 until n) {
            val k = i * BYTES_PER_PART
            out[k] = (health[i].coerceIn(0.0, 1.0) * 255.0 + 0.5).toInt().toByte()
            out[k + 1] = (((temperature[i] - COOLEST) / (HOTTEST - COOLEST)).coerceIn(0.0, 1.0) * 255.0 + 0.5).toInt().toByte()
            for (a in 0..2) out[k + 2 + a] = (crumple[i * 3 + a].coerceIn(-1f, 1f) * 127f).toInt().toByte()
            out[k + 5] = ((load[i] / MOST_LOAD).coerceIn(0.0, 1.0) * 255.0 + 0.5).toInt().toByte()
            val capacity = com.rm.apogee.core.part.Buoyancy.capacity(vessel.defs[i])
            val full = if (capacity > 0.0) flooded.getOrElse(i) { 0.0 } / capacity else 0.0
            out[k + 6] = (full.coerceIn(0.0, 1.0) * 255.0 + 0.5).toInt().toByte()
        }
        return out
    }

    /** A craft's parts as the network last described them. */
    class Values {
        var health = FloatArray(0); private set
        var temperature = FloatArray(0); private set
        var crumple = FloatArray(0); private set
        /** Each part's joint to its parent: load as a share of strength. */
        var load = FloatArray(0); private set
        /** How full of water each part is, 0..1. */
        var flooded = FloatArray(0); private set

        /** Whether anything is hurt, hot or dented at all. */
        var any = false; private set

        fun fit(parts: Int) {
            if (health.size != parts) {
                health = FloatArray(parts)
                temperature = FloatArray(parts)
                crumple = FloatArray(parts * 3)
                load = FloatArray(parts)
                flooded = FloatArray(parts)
            }
        }

        internal fun clear() {
            health.fill(1f)
            temperature.fill(com.rm.apogee.core.craft.Vessel.AMBIENT_TEMPERATURE.toFloat())
            crumple.fill(0f)
            load.fill(0f)
            flooded.fill(0f)
            any = false
        }

        internal fun mark() { any = true }
    }

    /**
     * Unpacks [bytes] for a craft of [parts] parts into [into]. An empty block, or one for a
     * different number of parts (the structure changed and the snapshot hasn't caught up), reads as
     * whole and cool.
     */
    fun decode(parts: Int, bytes: ByteArray, into: Values): Values {
        into.fit(parts)
        into.clear()
        if (bytes.size != parts * BYTES_PER_PART) return into
        into.mark()
        for (i in 0 until parts) {
            val k = i * BYTES_PER_PART
            into.health[i] = (bytes[k].toInt() and 0xFF) / 255f
            into.temperature[i] = (COOLEST + (bytes[k + 1].toInt() and 0xFF) / 255.0 * (HOTTEST - COOLEST)).toFloat()
            for (a in 0..2) into.crumple[i * 3 + a] = bytes[k + 2 + a] / 127f
            into.load[i] = ((bytes[k + 5].toInt() and 0xFF) / 255.0 * MOST_LOAD).toFloat()
            into.flooded[i] = (bytes[k + 6].toInt() and 0xFF) / 255f
        }
        return into
    }
}
