package com.rm.apogee.core.math

/**
 * The strict maths the terrain and the sea are made with, so the same world comes out on every JVM.
 * On the JVM it's `java.lang.StrictMath` itself. A browser plays alone, so its own maths is enough.
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
