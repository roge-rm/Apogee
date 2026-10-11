package com.rm.apogee.core.world

/** Words for warp, said to everyone in the game. */
object WarpWords {
    /** [seconds] in its biggest whole unit: "45 s", "12 min", "5 h", "3 d", "2 y". */
    fun span(seconds: Double): String {
        val s = seconds.coerceAtLeast(0.0)
        return when {
            s < 120.0 -> "${s.toLong()} s"
            s < 7_200.0 -> "${(s / 60.0).toLong()} min"
            s < 172_800.0 -> "${(s / 3_600.0).toLong()} h"
            s < 63_072_000.0 -> "${(s / 86_400.0).toLong()} d"
            else -> "${(s / 31_536_000.0).toLong()} y"
        }
    }
}
