package com.rm.apogee.desktop

import com.rm.apogee.BuildInfo
import com.rm.apogee.platform.Log
import java.io.File
import java.io.PrintWriter
import java.io.StringWriter
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Crash reports, kept in the data folder's crashes/. Two kinds:
 *
 * - A Kotlin crash on any thread, written with its stack and the recent log, then passed on.
 * - A crash in the JVM, a driver or the sound engine, which the JVM writes as an hs_err file there
 *   (the launchers set -XX:ErrorFile). Each becomes a report on the next start.
 *
 * The newest unread one is shown once on the next start. The last [KEEP] are kept.
 */
class CrashReports(dataDir: File) {
    val directory: File = File(dataDir, "crashes").apply { mkdirs() }
    private val unreadMark = File(directory, ".unread")

    /** Call first thing at start. */
    fun install() {
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { t, e ->
            runCatching {
                val trace = StringWriter().also { e.printStackTrace(PrintWriter(it)) }.toString()
                write("crash", "Crashed on thread \"${t.name}\".\n\n$trace\n--- what it last logged ---\n${Log.recent()}\n")
            }
            previous?.uncaughtException(t, e) ?: e.printStackTrace()
        }
    }

    /** Turns the JVM's crash files from earlier runs into reports. */
    fun collect() {
        val files = directory.listFiles { f -> f.name.startsWith("hs_err") && f.name.endsWith(".log") }.orEmpty()
        for (f in files.sortedBy { it.lastModified() }) {
            runCatching {
                write("native", "Apogee ended: native crash.\n\n" + f.readText().take(60_000), f.lastModified())
                f.delete()
            }.onFailure { Log.w(TAG, "could not read ${f.name}: $it") }
        }
    }

    /** The newest report not shown yet, if any. */
    fun unread(): File? =
        runCatching { unreadMark.readText().trim() }.getOrNull()?.let { File(directory, it) }?.takeIf { it.isFile }

    fun markRead() {
        unreadMark.delete()
    }

    private fun reports(): List<File> = directory.listFiles { f -> f.extension == "txt" }.orEmpty().toList()

    private fun write(kind: String, body: String, whenMs: Long = System.currentTimeMillis()) {
        val name = "${SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(Date(whenMs))}-$kind.txt"
        File(directory, name).writeText(header(whenMs) + body)
        unreadMark.writeText(name)
        reports().sortedByDescending { it.name }.drop(KEEP).forEach { it.delete() }
    }

    private fun header(whenMs: Long): String = buildString {
        append("Apogee ${BuildInfo.VERSION_NAME} (${BuildInfo.VERSION_CODE}), desktop\n")
        append(distribution()).append(", ")
        append("${System.getProperty("os.name")} ${System.getProperty("os.version")} ${System.getProperty("os.arch")}, ")
        append("Java ${System.getProperty("java.version")} (${System.getProperty("java.vm.name")})\n")
        append("When: ${SimpleDateFormat("yyyy-MM-dd HH:mm:ss Z", Locale.US).format(Date(whenMs))}\n\n")
    }

    /** For example "Debian GNU/Linux 13 (trixie)", from /etc/os-release. */
    private fun distribution(): String = runCatching {
        File("/etc/os-release").readLines().firstOrNull { it.startsWith("PRETTY_NAME=") }?.substringAfter('=')?.trim('"')
    }.getOrNull() ?: "unknown system"

    private companion object {
        const val TAG = "ApogeeCrash"
        const val KEEP = 10
    }
}
