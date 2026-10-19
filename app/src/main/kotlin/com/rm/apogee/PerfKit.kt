package com.rm.apogee

import android.content.ContentValues
import android.content.Context
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import java.io.File

/**
 * The perf build's setup, for timing a phone I can't reach with adb. On first start it seeds the
 * scene from its assets as the free play world, its craft given to this install's player, and
 * turns on the "debug-perf" log.
 * Each run copies every "ApogeePerf" line into a new Download/apogee-perf-<when>.txt, headed with
 * the phone's details, so the file can just be sent back.
 */
object PerfKit {
    private const val SCENE = "perf-scene.json"
    /** Who owns the craft in the scene's file, replaced with this install's own player. */
    private const val PLAYER = "a9e0c7d2-5f4b-4c1e-9b8a-perf00000001"

    fun start(context: Context) {
        val prefs = context.getSharedPreferences("apogee.settings", Context.MODE_PRIVATE)
        val world = File(context.filesDir, "world/solo.json")
        // Each install is its own player, so two perf builds can join each other's games. The
        // scene's craft are handed to it; a build that took the scene's player before gets its own.
        var me = prefs.getString("client_id", null)
        if (me == null || me == PLAYER) {
            me = java.util.UUID.randomUUID().toString()
            prefs.edit().putString("client_id", me).apply()
            if (world.exists()) world.writeText(world.readText().replace(PLAYER, me))
        }
        val seeded = File(context.filesDir, "perf-seeded")
        if (!seeded.exists()) {
            world.parentFile?.mkdirs()
            val scene = context.assets.open(SCENE).use { it.readBytes().decodeToString() }
            world.writeText(scene.replace(PLAYER, me))
            prefs.edit().putBoolean("career_mode", false).apply()
            seeded.writeText("1")
        }
        // adb can drop a scene (perf-scene.json) and a weather setting (perf-weather, e.g. EXTREME)
        // into the external files folder; each is taken once, at the next start.
        context.getExternalFilesDir(null)?.let { external ->
            File(external, "perf-scene.json").takeIf { it.exists() }?.let {
                // Into the sandbox universe last picked.
                val slot = File(context.filesDir, "world/${prefs.getString("sandbox_world", null) ?: "solo"}.json")
                slot.parentFile?.mkdirs()
                slot.writeText(it.readText().replace(PLAYER, me))
                it.delete()
            }
            File(external, "perf-weather").takeIf { it.exists() }?.let {
                prefs.edit().putString("weather_intensity", it.readText().trim()).apply()
                it.delete()
            }
            File(external, "perf-quality").takeIf { it.exists() }?.let {
                prefs.edit().putString("quality_tier", it.readText().trim()).apply()
                it.delete()
            }
        }
        File(context.filesDir, "debug-perf").writeText("1")
        File(context.filesDir, "debug-perf-build").writeText("1")
        Thread({ copyLog(context) }, "perf-log").apply { isDaemon = true }.start()
        File(context.filesDir, "debug-no-sea").delete()
        File(context.filesDir, "debug-no-event-items").delete()
        File(context.filesDir, "debug-perf-passes").delete()
        // Only the separate perf package runs this bridge. adb can compare passes/cache without
        // making the production app debuggable or restarting a warmed-up scene.
        Thread({
            val external = context.getExternalFilesDir(null) ?: return@Thread
            val switches = listOf("debug-no-static-scenery", "debug-no-craft-culling", "debug-no-cloud-culling", "debug-legacy-instancing", "debug-perf-passes", "debug-no-sea", "debug-no-event-items", "debug-no-cloud-items", "debug-no-tsunami-field", "debug-legacy-tsunami-terrain", "debug-no-solid-smoke")
            val profile = File(external, "debug-cpu-profile")
            var profiling = false
            var profileUntil = 0L
            var counting = false
            while (true) {
                // Allocation counting, by thread and by part of the frame. ART counts through slower
                // allocation entry points while it's on, so frame rates under it aren't comparable.
                val wantCounting = File(external, "debug-alloc").exists()
                if (wantCounting && !counting) {
                    @Suppress("DEPRECATION") android.os.Debug.startAllocCounting()
                    com.rm.apogee.platform.AllocProbe.processBytes = { android.os.Debug.getRuntimeStat("art.gc.bytes-allocated")?.toLongOrNull() ?: -1L }
                    com.rm.apogee.platform.AllocProbe.threadBytes = { threadAllocBytes() }
                    com.rm.apogee.platform.AllocProbe.threadId = { Thread.currentThread().id }
                    counting = true
                    android.util.Log.i("ApogeePerf", "allocation counting on")
                } else if (!wantCounting && counting) {
                    com.rm.apogee.platform.AllocProbe.threadBytes = null
                    com.rm.apogee.platform.AllocProbe.processBytes = null
                    com.rm.apogee.platform.AllocProbe.threadId = null
                    @Suppress("DEPRECATION") android.os.Debug.stopAllocCounting()
                    counting = false
                    android.util.Log.i("ApogeePerf", "allocation counting off")
                }
                for (name in switches) {
                    val target = File(context.filesDir, name)
                    if (File(external, name).exists()) {
                        if (!target.exists()) target.writeText("1")
                    } else if (target.exists()) target.delete()
                }
                if (!profiling && profile.exists()) {
                    runCatching {
                        android.os.Debug.startMethodTracingSampling(File(external, "apogee-cpu.trace").path, 16 * 1024 * 1024, 1_000)
                        profiling = true
                        profileUntil = android.os.SystemClock.elapsedRealtime() + 15_000
                        android.util.Log.i("ApogeePerf", "CPU sampling started")
                    }.onFailure { profile.delete(); android.util.Log.e("ApogeePerf", "CPU sampling failed", it) }
                } else if (profiling && (!profile.exists() || android.os.SystemClock.elapsedRealtime() >= profileUntil)) {
                    android.os.Debug.stopMethodTracing()
                    profiling = false
                    profile.delete()
                    android.util.Log.i("ApogeePerf", "CPU sampling saved")
                }
                Thread.sleep(1_000)
            }
        }, "perf-switches").apply { isDaemon = true }.start()
    }

    /** Per thread: ART's last 32-bit reading and the running total it's unwrapped into. */
    private val allocCounts = object : ThreadLocal<LongArray>() { override fun initialValue() = LongArray(2) }

    /** The calling thread's bytes allocated since counting began, unwrapped past ART's int. */
    private fun threadAllocBytes(): Long {
        val counts = allocCounts.get()!!
        @Suppress("DEPRECATION") val raw = android.os.Debug.getThreadAllocSize().toLong() and 0xFFFFFFFFL
        counts[1] += (raw - counts[0]) and 0xFFFFFFFFL
        counts[0] = raw
        return counts[1]
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
                    // The quality tier, once the game has settled it.
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
