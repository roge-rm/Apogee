package com.rm.apogee.core.math

import kotlin.math.pow

/**
 * The parts of `java.lang.Math` the simulation uses, on every platform. Conversions use the JVM's
 * constants, so results match to the bit.
 */
object Math {
    const val PI: Double = kotlin.math.PI

    fun toRadians(degrees: Double): Double = degrees * DEGREES_TO_RADIANS
    fun toDegrees(radians: Double): Double = radians * RADIANS_TO_DEGREES

    fun floor(x: Double): Double = kotlin.math.floor(x)
    fun pow(x: Double, y: Double): Double = x.pow(y)
    fun sin(x: Double): Double = kotlin.math.sin(x)

    fun floorMod(x: Int, y: Int): Int = x.mod(y)
    fun floorMod(x: Long, y: Long): Long = x.mod(y)
    fun floorMod(x: Long, y: Int): Int = x.mod(y)
    fun floorDiv(x: Int, y: Int): Int = x.floorDiv(y)
    fun floorDiv(x: Long, y: Long): Long = x.floorDiv(y)

    /** Nearest whole number, halves up, as the JVM rounds. */
    fun round(x: Double): Long = if (x.isNaN()) 0L else kotlin.math.floor(x + 0.5).toLong()
    fun round(x: Float): Int = if (x.isNaN()) 0 else kotlin.math.floor(x + 0.5f).toInt()

    fun signum(x: Double): Double = kotlin.math.sign(x)
    fun signum(x: Float): Float = kotlin.math.sign(x)

    private const val DEGREES_TO_RADIANS = 0.017453292519943295
    private const val RADIANS_TO_DEGREES = 57.29577951308232
}
