package com.rm.apogee.core.math

/**
 * Strict maths for terrain and sea, so every JVM makes the same world. `java.lang.StrictMath` on
 * the JVM; a browser plays alone, so its own maths is fine.
 */
expect object StrictMath {
    fun sin(x: Double): Double
    fun cos(x: Double): Double
    fun tan(x: Double): Double
    fun atan(x: Double): Double
    fun atan2(y: Double, x: Double): Double
    fun exp(x: Double): Double
    fun pow(x: Double, y: Double): Double
}
