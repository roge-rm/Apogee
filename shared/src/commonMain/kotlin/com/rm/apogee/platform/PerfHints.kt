package com.rm.apogee.platform

/**
 * Tells the system how long the simulation's work takes, so it can clock the CPU to fit: Android's
 * performance hints. Where there's nothing to tell, there's no [PerfHints] at all.
 */
interface PerfHints {
    /** How long the last simulation step really took. Safe to call every tick. */
    fun reportActualWorkDuration(nanos: Long)

    /** The simulation's per-tick budget has changed (a display rate change, for example). */
    fun updateTargetWorkDuration(nanos: Long)

    /** Closes the session. Anything reported after that is ignored. */
    fun close()
}
