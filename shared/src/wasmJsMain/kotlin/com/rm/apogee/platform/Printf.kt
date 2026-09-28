package com.rm.apogee.platform

import kotlin.math.abs
import kotlin.math.pow
import kotlin.math.round

/**
 * The part of `String.format` the app uses, for the browser: %d, %s, %x and %f, with the flags
 * `-`, `0`, `,`, `+` and space, a width and a precision, and %% and %n. Numbers are written the
 * English way, with a point and comma grouping.
 */
internal object Printf {
    fun format(pattern: String, args: Array<out Any?>): String {
        val out = StringBuilder()
        var next = 0
        var i = 0
        while (i < pattern.length) {
            val c = pattern[i]
            if (c != '%') { out.append(c); i++; continue }
            i++
            if (i >= pattern.length) break
            var flags = ""
            while (i < pattern.length && pattern[i] in "-0,+ #(") { flags += pattern[i]; i++ }
            var width = 0
            while (i < pattern.length && pattern[i].isDigit()) { width = width * 10 + (pattern[i] - '0'); i++ }
            var precision = -1
            if (i < pattern.length && pattern[i] == '.') {
                i++; precision = 0
                while (i < pattern.length && pattern[i].isDigit()) { precision = precision * 10 + (pattern[i] - '0'); i++ }
            }
            if (i >= pattern.length) break
            val conversion = pattern[i++]
            val body = when (conversion) {
                '%' -> "%"
                'n' -> "\n"
                'd' -> integer((args.getOrNull(next++) as Number).toLong(), flags)
                'x', 'X' -> (args.getOrNull(next++) as Number).toLong().let { v ->
                    val s = if (v < 0) v.toULong().toString(16) else v.toString(16)
                    if (conversion == 'X') s.uppercase() else s
                }
                'f' -> fixed((args.getOrNull(next++) as Number).toDouble(), if (precision < 0) 6 else precision, flags)
                's', 'S' -> args.getOrNull(next++).toString().let { if (precision >= 0) it.take(precision) else it }
                    .let { if (conversion == 'S') it.uppercase() else it }
                else -> "%$flags$conversion"
            }
            out.append(pad(body, width, flags, conversion))
        }
        return out.toString()
    }

    private fun integer(v: Long, flags: String): String {
        var digits = abs(v).toString()
        if (',' in flags) digits = group(digits)
        return sign(v < 0, flags) + digits
    }

    private fun fixed(v: Double, precision: Int, flags: String): String {
        if (v.isNaN()) return "NaN"
        if (v.isInfinite()) return if (v > 0) "Infinity" else "-Infinity"
        val scale = 10.0.pow(precision)
        // Half away from zero, like Java's HALF_UP on the decimal value.
        val scaled = round(abs(v) * scale)
        val whole = (scaled / scale).toLong()
        val fraction = (scaled - whole * scale).toLong()
        var text = if (',' in flags) group(whole.toString()) else whole.toString()
        if (precision > 0) text += "." + fraction.toString().padStart(precision, '0')
        val negative = v < 0 && scaled != 0.0
        return sign(negative, flags) + text
    }

    private fun sign(negative: Boolean, flags: String) = when {
        negative -> "-"
        '+' in flags -> "+"
        ' ' in flags -> " "
        else -> ""
    }

    private fun group(digits: String): String {
        val out = StringBuilder()
        for ((k, ch) in digits.withIndex()) {
            if (k > 0 && (digits.length - k) % 3 == 0) out.append(',')
            out.append(ch)
        }
        return out.toString()
    }

    private fun pad(body: String, width: Int, flags: String, conversion: Char): String {
        if (body.length >= width) return body
        if ('-' in flags) return body.padEnd(width)
        if ('0' in flags && conversion in "dfxX") {
            val signed = body.startsWith("-") || body.startsWith("+") || body.startsWith(" ")
            val head = if (signed) body.substring(0, 1) else ""
            return head + body.substring(head.length).padStart(width - head.length, '0')
        }
        return body.padStart(width)
    }
}
