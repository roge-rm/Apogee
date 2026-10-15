package com.rm.apogee.desktop

import com.rm.apogee.core.craft.StockCraft
import com.rm.apogee.core.part.StockParts
import com.rm.apogee.core.weather.CloudCover
import com.rm.apogee.core.weather.WeatherIntensity
import com.rm.apogee.core.world.World
import com.rm.apogee.game.GameSession
import com.rm.apogee.platform.AllocProbe
import com.rm.apogee.render.FrameBus
import com.rm.apogee.render.GlRenderer
import com.rm.apogee.render.QualityTier
import kotlinx.coroutines.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File
import java.lang.management.ManagementFactory

/**
 * Debug, not a check: runs a solo game with the Starter I on the Cape pad, LOW tier, at the Fire's
 * half-resolution aspect, drawing about 18 frames a second, and writes where its allocations come
 * from to build/fire-perf/alloc. Off unless run with -PallocProfile=<seconds>.
 */
class AllocationAttributionTest {
    @Test fun attributeAllocationsOnThePad() {
        val seconds = System.getProperty("apogee.allocProfile")?.toDoubleOrNull()
        assumeTrue("run with -PallocProfile=<seconds>", seconds != null)
        val warmup = System.getProperty("apogee.allocWarmup")?.toDoubleOrNull() ?: 45.0
        val out = File("../build/fire-perf/alloc").apply { mkdirs() }
        val tag = if (ManagementFactory.getRuntimeMXBean().inputArguments.any { "DoEscapeAnalysis" in it }) "noescape" else "default"

        val mx = ManagementFactory.getThreadMXBean() as com.sun.management.ThreadMXBean
        mx.isThreadAllocatedMemoryEnabled = true
        AllocProbe.threadBytes = { mx.currentThreadAllocatedBytes }

        TestGl.current()
        val frameBus = FrameBus()
        val renderer = GlRenderer({ QualityTier.LOW }, frameBus)
        renderer.onSurfaceCreated()
        // The Fire is 800x1280 drawn at half size.
        renderer.onSurfaceChanged(400, 640)
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val world = World.default(StockParts.catalog).also { it.ensureStructures() }
        val session = GameSession.hostLocal(
            frameBus = frameBus, perfHints = null, playerName = "Perf", clientId = "perf-alloc-client",
            design = StockCraft.starterRocket(StockParts.catalog), scope = scope, world = world,
            freshFlight = true, weather = WeatherIntensity.NORMAL, clouds = CloudCover.NORMAL,
        )
        session.attachTerrain(renderer.terrainSource, QualityTier.LOW)
        session.timeBuild = true
        session.start(scope)
        val log = StringBuilder()
        try {
            fun drawFor(span: Double): Int {
                val end = System.nanoTime() + (span * 1e9).toLong()
                var frames = 0
                while (System.nanoTime() < end) {
                    val started = System.nanoTime()
                    renderer.onDrawFrame()
                    org.lwjgl.opengl.GL11.glFinish()
                    frames++
                    val left = 55_000_000L - (System.nanoTime() - started)
                    if (left > 0) Thread.sleep(left / 1_000_000, (left % 1_000_000).toInt())
                }
                return frames
            }
            drawFor(warmup)
            log.appendLine("surface ready ${session.surfaceReady}, items ${frameBus.latest()?.latest?.items?.size}")
            frameBus.latest()?.latest?.let { frame ->
                log.appendLine("frame items by shape: " + frame.items.groupingBy { it.shape::class.simpleName }.eachCount().entries.sortedByDescending { it.value }.joinToString { "${it.key} ${it.value}" })
                log.appendLine("far items ${frame.farItems.size}")
            }
            runCatching {
                log.appendLine("world vessels ${world.vessels.size}, parts ${world.vessels.sumOf { it.design.parts.size }}:")
                for (v in world.vessels.sortedByDescending { it.design.parts.size }.take(40)) {
                    log.appendLine("  %-28s parts %4d anchored %s body %s".format(v.name, v.design.parts.size, v.anchored, v.referenceBodyId))
                }
            }
            session.takeBuildReport(); renderer.takePassAllocReport()

            val before = threadBytes(mx)
            val recording = jdk.jfr.Recording()
            recording.enable("jdk.ObjectAllocationSample").with("throttle", "20000/s").withStackTrace()
            recording.start()
            val started = System.nanoTime()
            val frames = drawFor(seconds!!)
            val took = (System.nanoTime() - started) / 1e9
            val buildLine = session.takeBuildReport()
            val passLine = renderer.takePassAllocReport()
            val after = threadBytes(mx)
            recording.stop()
            val jfr = File(out, "desktop-$tag.jfr")
            recording.dump(jfr.toPath())
            recording.close()

            log.appendLine("escape analysis: $tag; window %.1f s; GL frames %d (%.1f/s)".format(took, frames, frames / took))
            log.appendLine("build $buildLine")
            log.appendLine("pass-alloc $passLine")
            log.appendLine()
            log.appendLine("By thread (ThreadMXBean), MB/s and bytes per GL frame:")
            val byName = HashMap<String, Long>()
            for ((id, pair) in after) {
                val delta = pair.second - (before[id]?.second ?: 0L)
                val name = pair.first.replace(Regex("-?\\d+$"), "-#").substringBefore(" @coroutine")
                byName[name] = (byName[name] ?: 0L) + delta
            }
            val total = byName.values.sum()
            log.appendLine("  total %.2f MB/s".format(total / 1e6 / took))
            for ((name, bytes) in byName.entries.sortedByDescending { it.value }.take(25)) {
                log.appendLine("  %-40s %8.2f MB/s %10d B/GL frame".format(name, bytes / 1e6 / took, bytes / frames.coerceAtLeast(1)))
            }
            log.appendLine()
            log.append(summariseJfr(jfr, took, byName))
        } finally {
            session.stop()
            scope.cancel()
            renderer.release()
            AllocProbe.threadBytes = null
            File(out, "desktop-$tag.txt").writeText(log.toString())
            println(log)
        }
    }

    private fun threadBytes(mx: com.sun.management.ThreadMXBean): Map<Long, Pair<String, Long>> {
        val ids = mx.allThreadIds
        val bytes = mx.getThreadAllocatedBytes(ids)
        val infos = mx.getThreadInfo(ids)
        val result = HashMap<Long, Pair<String, Long>>()
        for (i in ids.indices) {
            val info = infos[i] ?: continue
            if (bytes[i] >= 0) result[ids[i]] = info.threadName to bytes[i]
        }
        return result
    }

    /**
     * The JFR allocation samples, weighted by bytes, grouped several ways. A sample's weight is
     * what its thread allocated since its last sample, so each thread's first sample (carrying
     * everything before the recording) is dropped. Each thread's samples are scaled to its
     * ThreadMXBean count, so the MB/s are the measured ones, shared out by JFR.
     */
    private fun summariseJfr(file: File, seconds: Double, measured: Map<String, Long>): String {
        class Sample(val thread: String, val weight: Long, val type: String, val frames: List<jdk.jfr.consumer.RecordedFrame>)
        val samples = ArrayList<Sample>()
        val seen = HashSet<Long>()
        jdk.jfr.consumer.RecordingFile(file.toPath()).use { recording ->
            while (recording.hasMoreEvents()) {
                val event = recording.readEvent()
                if (event.eventType.name != "jdk.ObjectAllocationSample") continue
                val threadId = event.thread?.javaThreadId ?: -1L
                if (seen.add(threadId)) continue
                samples += Sample(
                    (event.thread?.javaName ?: "?").replace(Regex("-?\\d+$"), "-#").substringBefore(" @coroutine"),
                    event.getLong("weight"), event.getClass("objectClass")?.name ?: "?", event.stackTrace?.frames ?: emptyList(),
                )
            }
        }
        val jfrByThread = HashMap<String, Long>()
        for (s in samples) jfrByThread.merge(s.thread, s.weight, Long::plus)
        // Measured bytes per JFR byte, by thread.
        val scale = jfrByThread.mapValues { (name, bytes) -> (measured[name] ?: 0L).toDouble() / bytes.coerceAtLeast(1) }
        fun name(f: jdk.jfr.consumer.RecordedFrame) = "${f.method.type.name.substringAfterLast('.')}.${f.method.name}:${f.lineNumber}"
        fun isLib(f: jdk.jfr.consumer.RecordedFrame) = f.method.type.name.let { it.startsWith("java.") || it.startsWith("kotlin.") || it.startsWith("jdk.") || it.startsWith("sun.") || it.startsWith("kotlinx.") || it.startsWith("org.lwjgl") }
        val text = StringBuilder()
        text.appendLine("JFR samples kept: ${samples.size}")
        text.appendLine("JFR vs ThreadMXBean by thread (MB/s): " + jfrByThread.entries.sortedByDescending { it.value }.take(8)
            .joinToString(", ") { (t, b) -> "%s %.2f/%.2f".format(t, b / 1e6 / seconds, (measured[t] ?: 0L) / 1e6 / seconds) })
        text.appendLine()
        fun section(title: String, threads: Set<String>?, count: Int, key: (Sample) -> String?) {
            val map = HashMap<String, Double>()
            var total = 0.0
            for (s in samples) {
                if (threads != null && s.thread !in threads) continue
                val bytes = s.weight * (scale[s.thread] ?: 0.0)
                total += bytes
                key(s)?.let { map.merge(it, bytes, Double::plus) }
            }
            text.appendLine("$title (%.2f MB/s):".format(total / 1e6 / seconds))
            for ((k, bytes) in map.entries.sortedByDescending { it.value }.take(count)) {
                text.appendLine("  %7.2f MB/s %5.1f%%  %s".format(bytes / 1e6 / seconds, 100.0 * bytes / total, k))
            }
            text.appendLine()
        }
        fun caller(s: Sample): String? {
            val i = s.frames.indexOfFirst { f -> !isLib(f) && !f.method.type.name.startsWith("com.rm.apogee.core.math.") }
            if (i < 0) return null
            val up = s.frames.drop(i + 1).firstOrNull { !isLib(it) }
            return name(s.frames[i]) + (up?.let { " <- " + name(it) } ?: "") + " [" + s.type.substringAfterLast('.') + "]"
        }
        section("All threads by class", null, 25) { it.type }
        section("All threads by allocating site past core.math, with caller", null, 70, ::caller)
        for (thread in jfrByThread.entries.sortedByDescending { (measured[it.key] ?: 0L) }.take(5).map { it.key }) {
            section("$thread by allocating site", setOf(thread), 40, ::caller)
            val inclusive = HashMap<String, Double>()
            for (s in samples) {
                if (s.thread != thread) continue
                val once = HashSet<String>()
                for (f in s.frames) if (!isLib(f)) once += "${f.method.type.name.substringAfterLast('.')}.${f.method.name}"
                for (m in once) inclusive.merge(m, s.weight * (scale[s.thread] ?: 0.0), Double::plus)
            }
            text.appendLine("$thread inclusive by app method:")
            for ((k, bytes) in inclusive.entries.sortedByDescending { it.value }.take(50)) {
                text.appendLine("  %7.2f MB/s  %s".format(bytes / 1e6 / seconds, k))
            }
            text.appendLine()
        }
        return text.toString()
    }
}
