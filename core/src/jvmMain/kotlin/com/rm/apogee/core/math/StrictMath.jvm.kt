package com.rm.apogee.core.math

actual object StrictMath {
    actual fun sin(x: Double): Double = java.lang.StrictMath.sin(x)
    actual fun cos(x: Double): Double = java.lang.StrictMath.cos(x)
    actual fun tan(x: Double): Double = java.lang.StrictMath.tan(x)
    actual fun atan(x: Double): Double = java.lang.StrictMath.atan(x)
    actual fun atan2(y: Double, x: Double): Double = java.lang.StrictMath.atan2(y, x)
    actual fun exp(x: Double): Double = java.lang.StrictMath.exp(x)
    actual fun pow(x: Double, y: Double): Double = java.lang.StrictMath.pow(x, y)
}
