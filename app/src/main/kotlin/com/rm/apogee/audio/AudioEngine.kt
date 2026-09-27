package com.rm.apogee.audio

import android.util.Log

/**
 * The native sound engine: a synth on its own low-latency audio thread, driven from here. Nothing
 * in the game waits on it. The scene is handed over once a frame and one-shots are queued, and if
 * the library can't load, the game just plays silent.
 */
object AudioEngine {

    private const val TAG = "ApogeeAudio"

    private val available: Boolean = runCatching { System.loadLibrary("apogee_audio") }
        .onFailure { Log.w(TAG, "No sound engine: ${it.message}") }
        .isSuccess

    @Volatile
    var running = false
        private set

    /** Opens the output and starts the synth with room for [voiceBudget] voices at once. */
    fun start(voiceBudget: Int) {
        if (!available || running) return
        running = nativeStart(voiceBudget)
    }

    fun stop() {
        if (!available || !running) return
        nativeStop()
        running = false
    }

    /** With the app in the background, the stream stops, and picks up where it was. */
    fun pause(paused: Boolean) {
        if (running) nativePause(paused)
    }

    /**
     * The held sounds this frame: [count] of them, each with a key that stays the same while the
     * sound does, its recipe, flags, and [SharedParams.COUNT] parameters in [params].
     */
    fun scene(count: Int, keys: IntArray, recipes: IntArray, flags: IntArray, params: FloatArray) {
        if (logging) logScene(count, recipes, params)
        if (running) nativeScene(count, keys, recipes, flags, params)
    }

    /** A one-shot, [delay] seconds from now. */
    fun event(recipe: Int, flags: Int, seed: Int, delay: Float, params: FloatArray) {
        if (logging) eventsSince[recipe] = (eventsSince[recipe] ?: 0) + 1
        if (running) nativeEvent(recipe, flags, seed, delay, params)
    }

    /** Logs what's playing every two seconds under "ApogeeSound", for the `debug-sound` switch. */
    @Volatile
    var logging = false
    private var loggedNanos = 0L
    private val eventsSince = HashMap<Int, Int>()

    private fun logScene(count: Int, recipes: IntArray, params: FloatArray) {
        val now = System.nanoTime()
        if (now - loggedNanos < 2_000_000_000L) return
        loggedNanos = now
        val held = (0 until count).joinToString(" ") { i ->
            val o = i * SharedParams.COUNT
            "r${recipes[i]}[" + (0 until SharedParams.COUNT).joinToString(",") { "%.2f".format(params[o + it]) } + "]"
        }
        Log.i("ApogeeSound", "held $held · shots $eventsSince")
        eventsSince.clear()
    }

    /** Loudness of each [Buses] entry, 0..1. */
    fun busGains(gains: FloatArray) {
        if (running) nativeBusGains(gains)
    }

    /** How much room the mix is in: close and boxy inside a hull, open outdoors. */
    fun room(amount: Float) {
        if (running) nativeRoom(amount)
    }

    /** Voices sounding now, for the diagnostics overlay. */
    val activeVoices: Int get() = if (running) nativeActiveVoices() else 0

    private external fun nativeActiveVoices(): Int
    private external fun nativeStart(voiceBudget: Int): Boolean
    private external fun nativeStop()
    private external fun nativePause(paused: Boolean)
    private external fun nativeScene(count: Int, keys: IntArray, recipes: IntArray, flags: IntArray, params: FloatArray)
    private external fun nativeEvent(recipe: Int, flags: Int, seed: Int, delay: Float, params: FloatArray)
    private external fun nativeBusGains(gains: FloatArray)
    private external fun nativeRoom(amount: Float)
}
