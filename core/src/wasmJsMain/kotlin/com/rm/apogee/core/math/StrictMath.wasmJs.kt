package com.rm.apogee.core.math

import kotlin.math.pow

actual object StrictMath {
    actual fun sin(x: Double): Double = kotlin.math.sin(x)
    actual fun cos(x: Double): Double = kotlin.math.cos(x)
    actual fun tan(x: Double): Double = kotlin.math.tan(x)
    actual fun atan(x: Double): Double = kotlin.math.atan(x)
    actual fun atan2(y: Double, x: Double): Double = kotlin.math.atan2(y, x)
    actual fun exp(x: Double): Double = kotlin.math.exp(x)
    actual fun pow(x: Double, y: Double): Double = x.pow(y)
}
