package com.rm.apogee

import android.content.ContentValues
import android.content.Context
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import java.io.File

/**
 * The perf build's setup, for timing a phone nobody can reach with adb. On its first start it puts
 * the scene from its assets in place as the free play world, with the player it was made for, and
 * switches on the "debug-perf" log, with each part of building a frame timed. From then on it copies every "ApogeePerf" line into
 * a new Download/apogee-perf-<when>.txt each run, under what the phone is, so the file can simply be sent back.
 */
object PerfKit {
    private const val SCENE = "perf-scene.json"
    private const val PLAYER = "a9e0c7d2-5f4b-4c1e-9b8a-perf00000001"

    fun start(context: Context) {
        val seeded = File(context.filesDir, "perf-seeded")
        if (!seeded.exists()) {
            File(context.filesDir, "world").mkdirs()
            context.assets.open(SCENE).use { input -> File(context.filesDir, "world/solo.json").outputStream().use { input.copyTo(it) } }
            context.getSharedPreferences("apogee.settings", Context.MODE_PRIVATE).edit()
                .putString("client_id", PLAYER)
                .putBoolean("career_mode", false)
                .apply()
            seeded.writeText("1")
        }
        File(context.filesDir, "debug-perf").writeText("1")
        File(context.filesDir, "debug-perf-build").writeText("1")
        Thread({ copyLog(context) }, "perf-log").apply { isDaemon = true }.start()
        File(context.filesDir, "debug-no-sea").delete()
        File(context.filesDir, "debug-perf-passes").delete()
    }

    /** Follows this app's own log, writing each timing line out as it comes. */
    private fun copyLog(context: Context) {
        runCatching {
            val out = open(context) ?: return
            out.bufferedWriter().use { writer ->
                val settings = context.getSharedPreferences("apogee.settings", Context.MODE_PRIVATE)
                val soc = if (Build.VERSION.SDK_INT >= 31) "${Build.SOC_MANUFACTURER} ${Build.SOC_MODEL}" else Build.HARDWARE
                val screen = context.resources.displayMetrics.let { "${it.widthPixels}x${it.heightPixels}" }
                writer.write("Apogee ${BuildConfig.VERSION_NAME} on ${Build.MANUFACTURER} ${Build.MODEL}, $soc, Android ${Build.VERSION.RELEASE}, $screen\n")
                writer.flush()
                val logcat = ProcessBuilder("logcat", "-v", "time", "--pid=${android.os.Process.myPid()}", "-s", "ApogeePerf:I", "ApogeePerfHints:I").redirectErrorStream(true).start()
                var saidQuality = false
                logcat.inputStream.bufferedReader().forEachLine { line ->
                    // What it's drawing at, once the game has settled that, which it hasn't when this starts.
                    if (!saidQuality && "ApogeePerf" in line) {
                        writer.write("quality ${settings.getString("quality_tier", null) ?: "auto"} (detected ${settings.getString("detected_quality_tier", "?")}), shadows ${settings.getString("shadow_quality", null) ?: "auto"}\n")
                        saidQuality = true
                    }
                    writer.write(line); writer.write("\n"); writer.flush()
                }
            }
        }
    }

    /** A new file in Download for this run, named for when it started. */
    private fun open(context: Context): java.io.OutputStream? {
        val name = "apogee-perf-" + java.text.SimpleDateFormat("yyyyMMdd-HHmmss", java.util.Locale.US).format(java.util.Date()) + ".txt"
        if (Build.VERSION.SDK_INT >= 29) {
            val values = ContentValues().apply {
                put(MediaStore.MediaColumns.DISPLAY_NAME, name)
                put(MediaStore.MediaColumns.MIME_TYPE, "text/plain")
                put(MediaStore.MediaColumns.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS)
            }
            val uri = context.contentResolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values) ?: return null
            return context.contentResolver.openOutputStream(uri)
        }
        return File(context.getExternalFilesDir(null), name).outputStream()
    }
}
