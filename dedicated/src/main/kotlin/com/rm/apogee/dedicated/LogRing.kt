package com.rm.apogee.dedicated

import java.time.Instant
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter

/**
 * The last few hundred log lines, kept in memory for the admin page. Capped so a long-running
 * server doesn't grow it forever; every line also goes to stdout for the container's log.
 */
class LogRing(private val capacity: Int = 400) {

    class Line(val epochMillis: Long, val level: String, val text: String) {
        val timestamp: String
            get() = FORMATTER.format(Instant.ofEpochMilli(epochMillis).atOffset(ZoneOffset.UTC))

        override fun toString(): String = "$timestamp  ${level.padEnd(5)} $text"
    }

    private val lines = ArrayDeque<Line>(capacity)

    fun info(text: String) = add("INFO", text)
    fun warn(text: String) = add("WARN", text)
    fun error(text: String) = add("ERROR", text)

    private fun add(level: String, text: String) {
        val line = Line(System.currentTimeMillis(), level, text)
        synchronized(lines) {
            lines.addLast(line)
            while (lines.size > capacity) lines.removeFirst()
        }
        // Also to stdout, so `docker logs` and a terminal both see it.
        println(line)
    }

    /** The most recent [count] lines, oldest first. */
    fun recent(count: Int = 100): List<Line> = synchronized(lines) {
        lines.takeLast(count.coerceIn(1, capacity))
    }

    private companion object {
        val FORMATTER: DateTimeFormatter = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")
    }
}
