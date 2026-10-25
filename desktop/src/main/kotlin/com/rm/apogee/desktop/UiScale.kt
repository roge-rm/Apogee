package com.rm.apogee.desktop

import java.io.File
import java.util.concurrent.CompletableFuture
import java.util.concurrent.TimeUnit
import kotlin.math.roundToInt

/** What the interface size is picked from. Null means not known. */
class UiScaleInputs(
    /** The density the window toolkit already reports. */
    val awt: Float = 1f,
    val env: Map<String, String> = emptyMap(),
    /** Output of `xrdb -query`. */
    val xrdb: String? = null,
    /** Text of ~/.config/kwinoutputconfig.json, Plasma 6's per-output scales. */
    val kwinOutputs: String? = null,
    /** Text of ~/.config/kdeglobals. */
    val kdeglobals: String? = null,
    val steamDeck: Boolean = false,
    /** Physical dots per inch of the monitor. */
    val monitorDpi: Float? = null,
)

class UiScalePick(val scale: Float, val source: String)

object UiScale {
    private const val DECK = 1.5f
    private const val BASE_DPI = 110f

    /** The first source that says the screen needs more than 100%, else 1. */
    fun pick(i: UiScaleInputs): UiScalePick {
        fun found(value: Float?, source: String): UiScalePick? =
            value?.takeIf { it > 1.01f }?.let { UiScalePick(snap(it), source) }

        found(i.awt, "window toolkit")?.let { return it }
        found(gdk(i.env), "GDK_SCALE")?.let { return it }
        found(i.env["QT_SCALE_FACTOR"]?.toFloatOrNull(), "QT_SCALE_FACTOR")?.let { return it }
        found(i.xrdb?.let(::xftDpi)?.div(96f), "Xft.dpi")?.let { return it }
        found(i.kwinOutputs?.let(::kwinScale), "kwinoutputconfig")?.let { return it }
        found(i.kdeglobals?.let(::kdeScale), "kdeglobals")?.let { return it }
        if (i.steamDeck) return UiScalePick(DECK, "Steam Deck")
        i.monitorDpi?.takeIf { it in 70f..400f }?.let { dpi ->
            found(dpi / BASE_DPI, "monitor ${dpi.roundToInt()} dpi")?.let { return it }
        }
        return UiScalePick(1f, "default")
    }

    /** To 0.25, within 1 to 2.5. */
    fun snap(v: Float): Float = ((v * 4f).roundToInt() / 4f).coerceIn(1f, 2.5f)

    private fun gdk(env: Map<String, String>): Float? {
        val scale = env["GDK_SCALE"]?.toIntOrNull() ?: 1
        val dpi = env["GDK_DPI_SCALE"]?.toFloatOrNull() ?: 1f
        return scale * dpi
    }

    private fun xftDpi(xrdb: String): Float? =
        Regex("""(?m)^Xft\.dpi:\s*([0-9.]+)""").find(xrdb)?.groupValues?.get(1)?.toFloatOrNull()

    /** The largest "scale" in the file. */
    private fun kwinScale(json: String): Float? =
        Regex(""""scale"\s*:\s*([0-9.]+)""").findAll(json).mapNotNull { it.groupValues[1].toFloatOrNull() }.maxOrNull()

    private fun kdeScale(text: String): Float? {
        var inScreen = false
        for (raw in text.lineSequence()) {
            val line = raw.trim()
            if (line.startsWith("[")) inScreen = line == "[KScreen]"
            if (!inScreen) continue
            if (line.startsWith("ScaleFactor=")) return line.substringAfter('=').toFloatOrNull()
            if (line.startsWith("ScreenScaleFactors=")) {
                // Like eDP-1=1.5;HDMI-1=1;
                return line.substringAfter('=').split(';').mapNotNull { it.substringAfter('=', "").toFloatOrNull() }.maxOrNull()
            }
        }
        return null
    }

    /** Pixel size over physical size of the primary (else first) connected output in `xrandr --query`. */
    fun xrandrDpi(output: String): Float? {
        val outputs = output.lineSequence().filter { " connected" in it }.toList()
        val line = (outputs.firstOrNull { " primary " in it } ?: outputs.firstOrNull { MM.containsMatchIn(it) }) ?: return null
        val px = Regex("""(\d+)x\d+\+\d+\+\d+""").find(line)?.groupValues?.get(1)?.toFloatOrNull() ?: return null
        val mm = MM.find(line)?.groupValues?.get(1)?.toFloatOrNull()?.takeIf { it > 0f } ?: return null
        return px / (mm / 25.4f)
    }

    private val MM = Regex("""(\d+)mm x \d+mm""")

    /**
     * Output of [cmd], or null if it fails or takes over [seconds]. Goes through a temp file so a
     * hung command can't block the read.
     */
    internal fun run(vararg cmd: String, seconds: Long = 2): String? {
        val out = runCatching { File.createTempFile("apogee-probe", ".txt") }.getOrNull() ?: return null
        return try {
            val p = ProcessBuilder(*cmd).redirectErrorStream(true).redirectOutput(out).start()
            if (!p.waitFor(seconds, TimeUnit.SECONDS)) {
                p.destroyForcibly()
                null
            } else out.readText()
        } catch (e: Exception) {
            null
        } finally {
            out.delete()
        }
    }

    private class Probes(val xrdb: String?, val xrandr: String?)

    /** xrdb and xrandr, run off the UI thread from [startProbe]. */
    private val probes: CompletableFuture<Probes> by lazy {
        CompletableFuture.supplyAsync {
            if (System.getProperty("os.name").orEmpty().startsWith("Linux")) Probes(run("xrdb", "-query"), run("xrandr", "--query"))
            else Probes(null, null)
        }
    }

    /** Starts reading the screen settings, so they're ready by the first frame. */
    fun startProbe() {
        probes
    }

    /** Reads this machine and picks. Logs the source and value once. */
    fun detect(awt: Float, steamDeck: Boolean): UiScalePick {
        val linux = System.getProperty("os.name").orEmpty().startsWith("Linux")
        val env = System.getenv()
        val pick = if (!linux) pick(UiScaleInputs(awt = awt, steamDeck = steamDeck)) else {
            // Both commands time out at 2 s, so this waits a little longer at most.
            val probed = runCatching { probes.get(5, TimeUnit.SECONDS) }.getOrNull() ?: Probes(null, null)
            val home = System.getProperty("user.home")
            val config = env["XDG_CONFIG_HOME"]?.takeIf { it.isNotBlank() } ?: "$home/.config"
            val first = pick(UiScaleInputs(
                awt = awt, env = env,
                xrdb = probed.xrdb,
                kwinOutputs = runCatching { File(config, "kwinoutputconfig.json").readText() }.getOrNull(),
                kdeglobals = runCatching { File(config, "kdeglobals").readText() }.getOrNull(),
                steamDeck = steamDeck,
            ))
            if (first.source != "default") first else {
                pick(UiScaleInputs(awt = awt, steamDeck = steamDeck, monitorDpi = probed.xrandr?.let(::xrandrDpi)))
            }
        }
        println("Interface size: ${(pick.scale * 100).roundToInt()}% from ${pick.source}")
        return pick
    }
}
